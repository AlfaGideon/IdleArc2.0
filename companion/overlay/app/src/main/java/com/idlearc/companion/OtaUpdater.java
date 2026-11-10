package com.idlearc.companion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Клиент обновления по воздуху (OTA) для IdleArc Druid Helper.
 *
 * Проверяет наличие свежих подписанных релизов через GitHub Releases API и ota-manifest.json,
 * скачивает новый APK с проверкой контрольной суммы SHA-256 и запускает штатное системное
 * обновление поверх установленной версии (с сохранением всех отсканированных статов!).
 */
public class OtaUpdater {

    private static volatile File pendingInstallApk = null;

    public static class ReleaseInfo {
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;
        public final String sha256;
        public final long sizeBytes;
        public final String changelog;

        public ReleaseInfo(int versionCode, String versionName, String apkUrl,
                           String sha256, long sizeBytes, String changelog) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256 != null ? sha256.trim().toLowerCase(Locale.ROOT) : "";
            this.sizeBytes = sizeBytes;
            this.changelog = changelog != null ? changelog.trim() : "";
        }
    }

    public interface CheckCallback {
        void onUpdateAvailable(ReleaseInfo latest, int currentCode, String currentName);
        void onUpToDate(int currentCode, String currentName);
        void onError(String message);
    }

    public interface DownloadCallback {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onReadyToInstall(File apkFile);
        void onError(String message);
    }

    public static int getCurrentVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) pi.getLongVersionCode();
            }
            //noinspection deprecation
            return pi.versionCode;
        } catch (Exception e) {
            return 210;
        }
    }

    public static String getCurrentVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "2.1.0";
        } catch (Exception e) {
            return "2.1.0";
        }
    }

    /** Проверяет наличие новой версии в фоновом потоке */
    public static void checkForUpdatesAsync(final Context ctx, final CheckCallback cb) {
        final int curCode = getCurrentVersionCode(ctx);
        final String curName = getCurrentVersionName(ctx);
        final String repoSlug = ctx.getString(R.string.ota_github_repo);
        final String activeBranch = ctx.getString(R.string.ota_active_branch);
        final String latestReleaseApi = "https://api.github.com/repos/" + repoSlug + "/releases/latest";
        final String[] manifestFeeds = new String[]{
                "https://raw.githubusercontent.com/" + repoSlug + "/main/releases/ota-manifest.json",
                "https://raw.githubusercontent.com/" + repoSlug + "/" + activeBranch + "/releases/ota-manifest.json"
        };
        final Handler ui = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            @Override
            public void run() {
                ReleaseInfo best = null;
                String lastErr = null;

                // 1. Проверяем официальный GitHub Releases (/releases/latest)
                try {
                    String relJson = httpGetText(latestReleaseApi);
                    ReleaseInfo fromRel = parseGitHubReleaseJson(relJson);
                    if (fromRel != null) {
                        best = fromRel;
                    }
                } catch (Exception e) {
                    lastErr = e.getMessage();
                }

                // 2. Проверяем ota-manifest.json (main + активная ветка)
                for (String url : manifestFeeds) {
                    try {
                        String body = httpGetText(url + "?t=" + (System.currentTimeMillis() / 15000L));
                        ReleaseInfo info = parseManifestJson(body);
                        if (info != null && (best == null || info.versionCode > best.versionCode)) {
                            best = info;
                        }
                    } catch (Exception e) {
                        lastErr = e.getMessage();
                    }
                }

                final ReleaseInfo found = best;
                final String errMsg = lastErr;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (found == null) {
                            cb.onError(errMsg != null ? errMsg : "Сервер обновлений недоступен");
                        } else if (found.versionCode > curCode) {
                            cb.onUpdateAvailable(found, curCode, curName);
                        } else {
                            cb.onUpToDate(curCode, curName);
                        }
                    }
                });
            }
        }, "OtaCheckThread").start();
    }

    /** Скачивает APK, проверяет SHA-256 и запускает системное окно обновления */
    public static void downloadUpdateAsync(final Activity activity,
                                           final ReleaseInfo info,
                                           final DownloadCallback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                File otaDir = OtaFileProvider.getOtaDir(activity);
                File partFile = new File(otaDir, "IdleArc-DruidHelper-update.apk.part");
                File finalApk = new File(otaDir, "IdleArc-DruidHelper-v" + sanitizeFileVer(info.versionName) + ".apk");

                HttpURLConnection conn = null;
                try {
                    conn = openFollowingRedirects(info.apkUrl);
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) {
                        throw new Exception("HTTP " + code + " при скачивании APK");
                    }

                    long total = conn.getContentLengthLong();
                    if (total <= 0 && info.sizeBytes > 0) total = info.sizeBytes;

                    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(partFile)) {
                        byte[] buf = new byte[16384];
                        long readTotal = 0;
                        int lastPct = -1;
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            sha256.update(buf, 0, n);
                            readTotal += n;
                            final int pct = total > 0 ? (int) Math.min(100L, (readTotal * 100L) / total) : -1;
                            if (pct != lastPct) {
                                lastPct = pct;
                                final long rt = readTotal;
                                final long tt = total;
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        cb.onProgress(pct, rt, tt);
                                    }
                                });
                            }
                        }
                    }

                    String actualSha = bytesToHex(sha256.digest());
                    if (!info.sha256.isEmpty() && !info.sha256.equalsIgnoreCase(actualSha)) {
                        //noinspection ResultOfMethodCallIgnored
                        partFile.delete();
                        throw new Exception("Контрольная сумма SHA-256 не совпала! Скачивание отменено.");
                    }

                    if (finalApk.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        finalApk.delete();
                    }
                    if (!partFile.renameTo(finalApk)) {
                        throw new Exception("Не удалось сохранить скачанный APK");
                    }

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onReadyToInstall(finalApk);
                            triggerInstallOrRequestPermission(activity, finalApk);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка загрузки обновления");
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "OtaDownloadThread").start();
    }

    /** Запускает системный установщик или открывает разрешение «Установка неизвестных приложений» (Android 8+) */
    public static void triggerInstallOrRequestPermission(Activity activity, File apkFile) {
        if (apkFile == null || !apkFile.exists()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                pendingInstallApk = apkFile;
                Intent permIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(permIntent);
                return;
            }
        }
        pendingInstallApk = null;
        Uri contentUri = OtaFileProvider.getUriForFile(apkFile);
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(contentUri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(install);
    }

    /** Если пользователь только что выдал разрешение на установку пакетов и вернулся в приложение — продолжаем установку */
    public static void resumePendingInstallIfPermitted(Activity activity) {
        File pending = pendingInstallApk;
        if (pending != null && pending.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || activity.getPackageManager().canRequestPackageInstalls()) {
                pendingInstallApk = null;
                triggerInstallOrRequestPermission(activity, pending);
            }
        }
    }

    static ReleaseInfo parseManifestJson(String rawJson) throws Exception {
        JSONObject obj = new JSONObject(rawJson);
        int code = obj.getInt("versionCode");
        String name = obj.getString("versionName");
        String url = obj.getString("apkUrl");
        String sha = obj.optString("sha256", "");
        long size = obj.optLong("sizeBytes", 0L);
        String log = obj.optString("changelog", "");
        if (code <= 0 || url.isEmpty()) return null;
        return new ReleaseInfo(code, name, url, sha, size, log);
    }

    static ReleaseInfo parseGitHubReleaseJson(String rawJson) throws Exception {
        JSONObject obj = new JSONObject(rawJson);
        String tag = obj.optString("tag_name", "");
        String body = obj.optString("body", "");
        JSONArray assets = obj.optJSONArray("assets");
        if (assets == null) return null;

        String apkUrl = null;
        long apkSize = 0L;
        String manifestUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name", "");
            String dl = a.optString("browser_download_url", "");
            if (name.endsWith(".apk") && !dl.isEmpty()) {
                apkUrl = dl;
                apkSize = a.optLong("size", 0L);
            } else if (name.equals("ota-manifest.json") && !dl.isEmpty()) {
                manifestUrl = dl;
            }
        }

        if (manifestUrl != null) {
            try {
                ReleaseInfo fromAssetManifest = parseManifestJson(httpGetText(manifestUrl));
                if (fromAssetManifest != null) return fromAssetManifest;
            } catch (Exception ignored) {
            }
        }

        if (apkUrl == null) return null;
        String verName = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        int inferredCode = inferVersionCodeFromSemver(verName);
        if (inferredCode <= 0) return null;
        return new ReleaseInfo(inferredCode, verName, apkUrl, "", apkSize, body);
    }

    static int inferVersionCodeFromSemver(String ver) {
        if (ver == null) return 0;
        Matcher m = Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+)").matcher(ver);
        if (m.find()) {
            int maj = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            int pat = Integer.parseInt(m.group(3));
            return maj * 100 + min * 10 + pat;
        }
        return 0;
    }

    private static String httpGetText(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = openFollowingRedirects(urlStr);
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new Exception("HTTP " + status);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection openFollowingRedirects(String urlStr) throws Exception {
        String currentUrl = urlStr;
        for (int hop = 0; hop < 6; hop++) {
            URL url = new URL(currentUrl);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new SecurityException("Разрешены только защищённые HTTPS-обновления");
            }
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/vnd.github+json, application/json, */*");
            conn.setRequestProperty("User-Agent", "IdleArc-DruidHelper-OTA");
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new Exception("Пустой редирект");
                URL next = new URL(url, loc);
                currentUrl = next.toString();
                continue;
            }
            return conn;
        }
        throw new Exception("Слишком много редиректов при проверке OTA");
    }

    private static String sanitizeFileVer(String s) {
        if (s == null) return "latest";
        return s.replaceAll("[^0-9A-Za-z._-]", "");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
