package com.idlearc.companion;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Прямой клиент официального API игры (https://idlearc.com/api/login и https://idlearc.com/api/dashboard).
 *
 * Безопасность:
 * - Никакие учётные данные не вшиваются в код или APK.
 * - Логин/пароль (или Bearer-токен), введённые пользователем на своём устройстве, хранятся исключительно
 *   в локальном закрытом хранилище приложения (Context.MODE_PRIVATE) и передаются только по HTTPS на idlearc.com.
 */
public class IdleArcApiClient {

    public static final String API_BASE = "https://idlearc.com";
    private static final String AUTH_PREFS = "druid_helper_api_auth_v1";
    private static final String CACHE_PREFS = "druid_helper_api_cache_v3";

    private static volatile Map<String, Object> cachedDashboard = null;
    private static volatile Map<String, Object> cachedGameConfig = null;
    private static volatile List<Map<String, Object>> cachedInventoryItems = new ArrayList<>();
    private static volatile int cachedInventoryTotalCount = 0;
    private static volatile Map<String, Object> cachedGemsData = null;
    private static volatile Map<String, Object> cachedDungeonData = null;
    private static volatile Map<String, Object> cachedBossFightStatus = null;
    private static volatile long cachedBossFightStatusTimeMs = 0L;
    private static volatile Map<String, Object> cachedGuildData = null;
    private static volatile long lastSyncTimeMs = 0L;

    public interface ApiCallback {
        void onSuccess(ScreenAnalyzer.ScanResult scanResult);
        void onError(String errorMessage);
    }

    public static boolean hasSavedCredentials(Context ctx) {
        if (ctx == null) return false;
        SharedPreferences sp = ctx.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        if (sp == null) return false;
        String tok = sp.getString("bearer_token", "");
        String em = sp.getString("email", "");
        String pw = sp.getString("password", "");
        return (tok != null && !tok.trim().isEmpty())
                || (em != null && !em.trim().isEmpty() && pw != null && !pw.isEmpty());
    }

    public static String getSavedEmail(Context ctx) {
        if (ctx == null) return "";
        SharedPreferences sp = ctx.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        return sp != null ? sp.getString("email", "") : "";
    }

    public static void saveCredentials(Context ctx, String email, String password, String optionalBearerToken) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        if (sp == null) return;
        SharedPreferences.Editor ed = sp.edit();
        if (email != null) ed.putString("email", email.trim());
        if (password != null) ed.putString("password", password);
        if (optionalBearerToken != null) ed.putString("bearer_token", optionalBearerToken.trim());
        ed.apply();
    }

    public static void clearCredentials(Context ctx) {
        cachedDashboard = null;
        cachedInventoryItems = new ArrayList<>();
        cachedInventoryTotalCount = 0;
        cachedGemsData = null;
        cachedDungeonData = null;
        cachedBossFightStatus = null;
        cachedBossFightStatusTimeMs = 0L;
        cachedGuildData = null;
        lastSyncTimeMs = 0L;
        if (ctx == null) return;
        CatalystTracker.clear(ctx);
        SnapshotHistoryTracker.clearFarmSession(ctx);
        SharedPreferences alarms = ctx.getSharedPreferences("druid_helper_alarms_v31", Context.MODE_PRIVATE);
        if (alarms != null) alarms.edit().putLong("manual_boss_deadline", 0L).apply();
        SharedPreferences sp = ctx.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        if (sp != null) {
            sp.edit()
              .putString("email", "")
              .putString("password", "")
              .putString("bearer_token", "")
              .apply();
        }
        SharedPreferences cp = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        if (cp != null) {
            SharedPreferences.Editor clearCache = cp.edit();
            // The loader caps page counts at 100. Clear old pages too, even if a later
            // sync stored fewer pages and overwrote inv_page_cnt.
            for (int i = 1; i <= 100; i++) clearCache.remove("inv_page_" + i);
            clearCache
              .putInt("inv_total_cnt", 0)
              .putString("dashboard_json", "")
              .putString("gems_json", "")
              .putString("dungeon_json", "")
              .putString("guild_json", "")
              .putString("boss_status_json", "")
              .putLong("boss_status_ts", 0L)
              .putInt("inv_page_cnt", 0)
              .putLong("sync_ts", 0L)
              .apply();
        }
    }

    public static Map<String, Object> getCachedDashboard() {
        return cachedDashboard;
    }

    public static Map<String, Object> getCachedGameConfig() {
        return cachedGameConfig;
    }

    public static List<Map<String, Object>> getCachedInventoryItems() {
        return cachedInventoryItems;
    }

    public static int getCachedInventoryTotalCount() {
        return cachedInventoryTotalCount > 0 ? cachedInventoryTotalCount : cachedInventoryItems.size();
    }

    public static Map<String, Object> getCachedGemsData() {
        return cachedGemsData;
    }

    public static Map<String, Object> getCachedBossFightStatus() {
        return cachedBossFightStatus;
    }

    public static long getCachedBossFightStatusTimeMs() {
        return cachedBossFightStatusTimeMs;
    }

    public static Map<String, Object> getCachedDungeonData() {
        return cachedDungeonData;
    }

    public static Map<String, Object> getCachedGuildData() {
        return cachedGuildData;
    }

    public static void setCachedInventoryAndGems(List<Map<String, Object>> invItems, Map<String, Object> gemsData) {
        cachedInventoryItems = invItems != null ? invItems : new ArrayList<Map<String, Object>>();
        cachedInventoryTotalCount = cachedInventoryItems.size();
        cachedGemsData = gemsData;
    }

    public static long getLastSyncTimeMs() {
        return lastSyncTimeMs;
    }

    /** Мгновенно подгружает последний сохранённый слепок /api/dashboard, /api/inventory и /api/gems из локального кэша */
    public static boolean ensureCacheLoaded(Context ctx, DruidProfile profile) {
        if (cachedDashboard != null) return true;
        if (ctx == null) return false;
        SharedPreferences cp = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        if (cp == null) return false;
        String raw = cp.getString("dashboard_json", "");
        if (raw == null || raw.isEmpty()) return false;
        Map<String, Object> parsed = SimpleJson.parseObject(raw);
        if (parsed == null || parsed.isEmpty()) return false;
        cachedDashboard = parsed;
        lastSyncTimeMs = cp.getLong("sync_ts", 0L);
        String rawCfg = cp.getString("game_config_json", "");
        if (rawCfg != null && !rawCfg.isEmpty()) {
            cachedGameConfig = SimpleJson.parseObject(rawCfg);
        }
        String rawGems = cp.getString("gems_json", "");
        if (rawGems != null && !rawGems.isEmpty()) {
            cachedGemsData = SimpleJson.parseObject(rawGems);
        }
        String rawBoss = cp.getString("boss_status_json", "");
        if (rawBoss != null && !rawBoss.isEmpty()) {
            cachedBossFightStatus = SimpleJson.parseObject(rawBoss);
            cachedBossFightStatusTimeMs = cp.getLong("boss_status_ts", 0L);
        }
        String rawDung = cp.getString("dungeon_json", "");
        if (rawDung != null && !rawDung.isEmpty()) {
            cachedDungeonData = SimpleJson.parseObject(rawDung);
        }
        String rawGuild = cp.getString("guild_json", "");
        if (rawGuild != null && !rawGuild.isEmpty()) {
            cachedGuildData = SimpleJson.parseObject(rawGuild);
        }
        cachedInventoryTotalCount = cp.getInt("inv_total_cnt", 0);
        int pageCnt = cp.getInt("inv_page_cnt", 0);
        if (pageCnt > 0) {
            List<Map<String, Object>> loadedInv = new ArrayList<>();
            java.util.Set<String> seenIds = new java.util.HashSet<>();
            for (int pIdx = 1; pIdx <= pageCnt; pIdx++) {
                String pJson = cp.getString("inv_page_" + pIdx, "");
                extractInventoryPageItems(SimpleJson.parseObject(pJson), loadedInv, seenIds);
            }
            cachedInventoryItems = loadedInv;
        }
        if (profile != null) {
            applyDashboardToProfile(parsed, profile);
        }
        return true;
    }

    /** Асинхронная синхронизация с https://idlearc.com/api/dashboard в фоновом потоке */
    public static void syncAsync(final Context ctx, final DruidProfile profile, final ApiCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences sp = ctx != null ? ctx.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE) : null;
                    String token = sp != null ? sp.getString("bearer_token", "") : "";
                    String email = sp != null ? sp.getString("email", "") : "";
                    String password = sp != null ? sp.getString("password", "") : "";

                    if ((token == null || token.isEmpty()) && (email == null || email.isEmpty() || password == null || password.isEmpty())) {
                        if (cb != null) cb.onError("Сначала введи Email и пароль от IdleArc в главном окне Druid Helper.");
                        return;
                    }

                    if (token == null || token.isEmpty()) {
                        token = loginForBearerToken(email, password);
                        if (sp != null) {
                            sp.edit().putString("bearer_token", token).apply();
                        }
                    }

                    String dashJson;
                    try {
                        dashJson = fetchDashboardJson(token);
                    } catch (AuthExpiredException expired) {
                        if (email != null && !email.isEmpty() && password != null && !password.isEmpty()) {
                            token = loginForBearerToken(email, password);
                            if (sp != null) {
                                sp.edit().putString("bearer_token", token).apply();
                            }
                            dashJson = fetchDashboardJson(token);
                        } else {
                            throw expired;
                        }
                    }

                    Map<String, Object> dash = SimpleJson.parseObject(dashJson);
                    if (dash == null || dash.isEmpty()) {
                        if (cb != null) cb.onError("Сервер вернул пустой ответ /api/dashboard.");
                        return;
                    }
                    Map<String, Object> prevDash = cachedDashboard;
                    cachedDashboard = dash;
                    lastSyncTimeMs = System.currentTimeMillis();
                    applyDashboardToProfile(dash, profile);
                    SnapshotHistoryTracker.onNewDashboardSynced(ctx, prevDash, dash, lastSyncTimeMs);
                    CatalystTracker.recordSync(ctx, dash, lastSyncTimeMs);
                    // Дополнительно загружаем Инвентарь (/api/inventory), Мешок камней (/api/gems), Данжи/Боссов (/api/dungeon) и Гильдию (/api/guild)
                    String gemsJson = "";
                    try {
                        gemsJson = fetchAuthenticatedGetJson(token, "/api/gems");
                        Map<String, Object> gMap = SimpleJson.parseObject(gemsJson);
                        if (gMap != null && !gMap.isEmpty()) {
                            cachedGemsData = gMap;
                        }
                    } catch (Exception ignored) {
                    }

                    String bossJson = "";
                    try {
                        bossJson = fetchAuthenticatedGetJson(token, "/api/boss-fight/status");
                        Map<String, Object> status = SimpleJson.parseObject(bossJson);
                        if (status != null && status.containsKey("can_fight")) {
                            cachedBossFightStatusTimeMs = System.currentTimeMillis();
                            cachedBossFightStatus = status;
                        } else {
                            bossJson = "";
                        }
                    } catch (Exception ignored) {
                        bossJson = "";
                    }

                    String dungeonJson = "";
                    try {
                        dungeonJson = fetchAuthenticatedGetJson(token, "/api/dungeon");
                        Map<String, Object> dngMap = SimpleJson.parseObject(dungeonJson);
                        if (dngMap != null && !dngMap.isEmpty()) {
                            cachedDungeonData = dngMap;
                        }
                    } catch (Exception ignored) {
                    }

                    String guildJson = "";
                    try {
                        guildJson = fetchAuthenticatedGetJson(token, "/api/guild");
                        Map<String, Object> gldMap = SimpleJson.parseObject(guildJson);
                        if (gldMap != null && !gldMap.isEmpty()) {
                            cachedGuildData = gldMap;
                        }
                    } catch (Exception ignored) {
                    }

                    List<String> rawInvPages = new ArrayList<>();
                    try {
                        final String inventoryToken = token;
                        InventoryPageLoader.Result inventory = InventoryPageLoader.load(new InventoryPageLoader.Fetcher() {
                            @Override public String get(String path) throws Exception {
                                return fetchAuthenticatedGetJson(inventoryToken, path);
                            }
                        });
                        rawInvPages.addAll(inventory.pages);
                        cachedInventoryItems = inventory.items;
                        cachedInventoryTotalCount = inventory.total;
                    } catch (Exception ignored) {
                        // Keep the last successful snapshot if the first page cannot be loaded.
                    }

                    if (ctx != null) {
                        profile.save(ctx);
                        SharedPreferences cp = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
                        if (cp != null) {
                            SharedPreferences.Editor ed = cp.edit()
                                    .putString("dashboard_json", dashJson)
                                    .putLong("sync_ts", lastSyncTimeMs);
                            if (!gemsJson.isEmpty()) ed.putString("gems_json", gemsJson);
                            if (!bossJson.isEmpty()) ed.putString("boss_status_json", bossJson)
                                    .putLong("boss_status_ts", cachedBossFightStatusTimeMs);
                            if (!dungeonJson.isEmpty()) ed.putString("dungeon_json", dungeonJson);
                            if (!guildJson.isEmpty()) ed.putString("guild_json", guildJson);
                            if (!rawInvPages.isEmpty()) {
                                ed.putInt("inv_total_cnt", cachedInventoryTotalCount);
                                ed.putInt("inv_page_cnt", rawInvPages.size());
                                for (int i = 0; i < rawInvPages.size(); i++) {
                                    ed.putString("inv_page_" + (i + 1), rawInvPages.get(i));
                                }
                            }
                            long configTs = cp.getLong("game_config_ts", 0L);
                            long configAge = System.currentTimeMillis() - configTs;
                            if (cachedGameConfig == null || configTs <= 0 || configAge < 0 || configAge > 6 * 60 * 60 * 1000L) {
                                try {
                                    String cfgJson = fetchPublicGameConfigJson();
                                    Map<String, Object> cfgMap = SimpleJson.parseObject(cfgJson);
                                    if (cfgMap != null && !cfgMap.isEmpty()) {
                                        cachedGameConfig = cfgMap;
                                        ed.putString("game_config_json", cfgJson)
                                                .putLong("game_config_ts", System.currentTimeMillis());
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                            ed.apply();
                        }
                    }
                    ScreenAnalyzer.ScanResult sr = buildFullDashboardScanResult(dash, profile);
                    if (cb != null) cb.onSuccess(sr);
                } catch (Exception e) {
                    if (cb != null) cb.onError(e.getMessage() != null ? e.getMessage() : e.toString());
                }
            }
        }, "IdleArcApiSync").start();
    }

    private static final class AuthExpiredException extends Exception {
        AuthExpiredException(String msg) {
            super(msg);
        }
    }

    public static String loginForBearerToken(String email, String password) throws Exception {
        URL url = new URL(API_BASE + "/api/login");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "IdleArc-DruidHelper/2.2");

        String body = "{\"email\":" + SimpleJson.quote(email)
                + ",\"password\":" + SimpleJson.quote(password)
                + ",\"device_name\":\"DruidHelper\"}";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        String respText = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();

        if (code != 200 && code != 201) {
            Map<String, Object> err = SimpleJson.parseObject(respText);
            String msg = SimpleJson.str(err, "message", "HTTP " + code);
            throw new Exception("Ошибка входа в API (" + code + "): " + msg);
        }
        Map<String, Object> obj = SimpleJson.parseObject(respText);
        String token = SimpleJson.str(obj, "token", "");
        if (token.isEmpty()) {
            throw new Exception("Сервер не вернул Bearer-токен при входе.");
        }
        return token;
    }

    public static String fetchDashboardJson(String bearerToken) throws Exception {
        return fetchAuthenticatedGetJson(bearerToken, "/api/dashboard");
    }

    public static String fetchAuthenticatedGetJson(String bearerToken, String apiPath) throws Exception {
        if (apiPath == null || !apiPath.matches("/api/[a-z0-9/-]+")) {
            throw new IllegalArgumentException("Недопустимый путь API");
        }
        URL url = new URL(API_BASE + apiPath);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        conn.setRequestProperty("User-Agent", "IdleArcHelper/4.0");

        int code = conn.getResponseCode();
        String respText = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();

        if (code == 401 || code == 403) {
            throw new AuthExpiredException("Сессия API истекла (HTTP " + code + ") — проверь логин и пароль.");
        }
        if (code != 200) {
            throw new Exception("Ошибка загрузки " + apiPath + ": HTTP " + code);
        }
        return respText;
    }

    private static int extractInventoryPageItems(Map<String, Object> pageRoot,
                                                 List<Map<String, Object>> out,
                                                 java.util.Set<String> seenIds) {
        return InventoryPageLoader.append(pageRoot, out, seenIds);
    }

    public static String fetchPublicGameConfigJson() throws Exception {
        URL url = new URL(API_BASE + "/api/config/game");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "IdleArc-DruidHelper/3.0");
        int code = conn.getResponseCode();
        String respText = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();
        if (code != 200) throw new Exception("HTTP " + code);
        return respText;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        try (InputStream in = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                if (bos.size() + r > 10 * 1024 * 1024) throw new java.io.IOException("Ответ API слишком большой");
                bos.write(buf, 0, r);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Переносит ВСЕ 31+ характеристики героя напрямую из JSON /api/dashboard в живой DruidProfile (100% точность).
     */
    public static void applyDashboardToProfile(Map<String, Object> d, DruidProfile p) {
        if (d == null || p == null) return;

        int cid = SimpleJson.intVal(d, "characterClassId", 0);
        String cname = SimpleJson.str(d, "characterClassName", "");
        p.detectedClassId = HeroClassProfile.HeroClass.fromIdOrName(cid, cname).id;

        p.charLevel = Math.max(1, SimpleJson.intVal(d, "level", p.charLevel));
        p.gearScore = Math.max(0, SimpleJson.intVal(d, "gear_score", p.gearScore));
        p.maxHp = Math.max(0.0, SimpleJson.num(d, "maxHealth", p.maxHp));
        p.defense = Math.max(0.0, SimpleJson.num(d, "defense", p.defense));

        Map<String, Object> atkObj = SimpleJson.asMap(d.get("attackDamage"));
        if (atkObj != null) {
            p.minAtk = SimpleJson.num(atkObj, "min", p.minAtk);
            p.maxAtk = SimpleJson.num(atkObj, "max", p.maxAtk);
        }

        double dr = SimpleJson.num(d, "damageReduction", p.damageReductionEff);
        double drUncap = SimpleJson.num(d, "damageReductionUncapped", dr);
        p.damageReductionEff = Math.min(95.0, Math.max(0.0, dr));
        p.damageReductionRaw = Math.max(dr, drUncap);

        p.critChance = Math.max(0.0, SimpleJson.num(d, "criticalStrikeChance", p.critChance));
        p.critDamage = Math.max(0.0, SimpleJson.num(d, "criticalDamage", p.critDamage));
        p.doubleHitChance = Math.max(0.0, SimpleJson.num(d, "doubleHitChance", p.doubleHitChance));
        p.doubleDamageChance = Math.max(0.0, SimpleJson.num(d, "doubleDamageChance", p.doubleDamageChance));
        p.blockChance = Math.max(0.0, SimpleJson.num(d, "blockChance", p.blockChance));
        p.bossDamage = Math.max(0.0, SimpleJson.num(d, "bossDamage", p.bossDamage));
        p.extraKillChance = Math.max(0.0, SimpleJson.num(d, "extraKillChance", p.extraKillChance));

        Map<String, Object> retObj = SimpleJson.asMap(d.get("retaliation"));
        if (retObj != null) {
            p.retaliation = SimpleJson.num(retObj, "expectedPerExchange", 0.0);
        }

        Map<String, Object> bs = SimpleJson.asMap(d.get("bonusStats"));
        if (bs != null) {
            p.petDamageBonusPct = Math.max(0.0, SimpleJson.num(bs, "petDamagePercent", p.petDamageBonusPct));
            double trueMult = SimpleJson.num(bs, "petDamageTrueMultiplier", 0.0);
            if (trueMult > 0.0) {
                p.petDamageMultMillions = trueMult / 1_000_000.0;
            }
            p.expBonus = Math.max(0.0, SimpleJson.num(bs, "expGainPercent", p.expBonus));
            p.goldBonus = Math.max(0.0, SimpleJson.num(bs, "goldGainPercent", p.goldBonus));
            p.lifeOnHit = Math.max(0.0, SimpleJson.num(bs, "lifeOnHit", p.lifeOnHit));
            p.lifeOnKill = Math.max(0.0, SimpleJson.num(bs, "lifeOnKill", p.lifeOnKill));
            p.lifeStealBonusPct = Math.max(0.0, SimpleJson.num(bs, "bloodthirstPercent", p.lifeStealBonusPct));

            p.itemDropPct = Math.max(0.0, SimpleJson.num(bs, "totalItemDropChance", p.itemDropPct));
            p.itemDropMult = Math.max(1.0, SimpleJson.num(bs, "effectiveItemDropMultiplier", p.itemDropMult));
            p.matDropPct = Math.max(0.0, SimpleJson.num(bs, "totalMaterialDropChance", p.matDropPct));
            p.matDropMult = Math.max(1.0, SimpleJson.num(bs, "effectiveMaterialDropMultiplier", p.matDropMult));
            p.runeDropPct = Math.max(0.0, SimpleJson.num(bs, "totalRuneDropChance", p.runeDropPct));
            p.runeDropMult = Math.max(1.0, SimpleJson.num(bs, "effectiveRuneDropMultiplier", p.runeDropMult));
            p.rubyDropPct = Math.max(0.0, SimpleJson.num(bs, "totalRubyDropChance", p.rubyDropPct));
            p.rubyDropMult = Math.max(1.0, SimpleJson.num(bs, "effectiveRubyDropMultiplier", p.rubyDropMult));

            Map<String, Object> prim = SimpleJson.asMap(bs.get("primaryStats"));
            if (prim != null) {
                Map<String, Object> strObj = SimpleJson.asMap(prim.get("strength"));
                if (strObj != null) p.strength = SimpleJson.num(strObj, "value", p.strength);

                Map<String, Object> dexObj = SimpleJson.asMap(prim.get("dexterity"));
                if (dexObj != null) {
                    p.agility = SimpleJson.num(dexObj, "value", p.agility);
                    double dEff = SimpleJson.num(dexObj, "dodge_chance", p.dodgeEff);
                    double dRaw = SimpleJson.num(dexObj, "dodge_chance_uncapped", dEff);
                    p.dodgeEff = Math.min(95.0, Math.max(0.0, dEff));
                    p.dodgeRaw = Math.max(dEff, dRaw);
                }

                Map<String, Object> intObj = SimpleJson.asMap(prim.get("intelligence"));
                if (intObj != null) p.intelligence = SimpleJson.num(intObj, "value", p.intelligence);
            }

            Map<String, Object> petSpec = SimpleJson.asMap(bs.get("petSpecialBonuses"));
            if (petSpec != null) {
                p.bonusAllClassSkills = SimpleJson.intVal(petSpec, "all_skills", p.bonusAllClassSkills);
            }

            Map<String, Object> asc = SimpleJson.asMap(bs.get("ascentBonuses"));
            if (asc != null) {
                p.eggDropPct = SimpleJson.num(asc, "pet_egg_drop_pct", p.eggDropPct);
            }
        }

        p.hasScannedStats = true;
        p.scannedStatMask = 0x7FFFFFFFL;
        p.scannedStatsCount = 31;
        p.lastUpdatedMs = System.currentTimeMillis();
    }

    private static class EquippedItemAudit {
        final String slotLabel;
        final SlotAffixAdvisor.GearSlot gearSlot;
        final int tier;
        final int tierLevel;
        final AffixPositionOptimizer.OptimizationReport report;
        final int rerollsNeeded;
        final double upgradeGainPct;

        EquippedItemAudit(String slotLabel, SlotAffixAdvisor.GearSlot gearSlot, int tier, int tierLevel,
                          AffixPositionOptimizer.OptimizationReport report, int rerollsNeeded, double upgradeGainPct) {
            this.slotLabel = slotLabel;
            this.gearSlot = gearSlot;
            this.tier = tier;
            this.tierLevel = tierLevel;
            this.report = report;
            this.rerollsNeeded = rerollsNeeded;
            this.upgradeGainPct = upgradeGainPct;
        }
    }

    /**
     * Строит полный математический разбор всего аккаунта из /api/dashboard:
     * 31 стат, все 10 надетых предметов, новый лут в сумке, все 20 гнёзд и самоцветов, 4 питомца, талисманы и навыки.
     */
    public static ScreenAnalyzer.ScanResult buildFullDashboardScanResult(Map<String, Object> d, DruidProfile profile) {
        ScreenAnalyzer.ScanResult res = new ScreenAnalyzer.ScanResult();
        if (d == null || profile == null) return res;

        res.screenType = "API Сервера (/api/dashboard)";
        res.profileUpdated = true;

        Map<String, Object> bs = SimpleJson.asMap(d.get("bonusStats"));
        double petAvgDmg = bs != null ? SimpleJson.num(bs, "petDamageFinalAvg", 0.0) : 0.0;

        // 1. Аудит всех 10 надетых предметов через наш реляционный AffixPositionOptimizer
        List<EquippedItemAudit> itemAudits = new ArrayList<>();
        List<Object> eqList = SimpleJson.asList(d.get("characterEquippedItems"));
        int totalRerollSlots = 0;
        if (eqList != null) {
            for (Object itemObj : eqList) {
                Map<String, Object> item = SimpleJson.asMap(itemObj);
                if (item == null) continue;
                EquippedItemAudit audit = analyzeServerItem(item, profile, true);
                if (audit != null) {
                    itemAudits.add(audit);
                    if (audit.rerollsNeeded > 0) totalRerollSlots++;
                }
            }
        }
        Collections.sort(itemAudits, new Comparator<EquippedItemAudit>() {
            @Override
            public int compare(EquippedItemAudit a, EquippedItemAudit b) {
                return Double.compare(b.upgradeGainPct, a.upgradeGainPct);
            }
        });

        // 2. Аудит всех 20 гнёзд самоцветов
        String gemSummary = buildServerGemsAudit(d, profile);

        res.shortBadge = String.format(Locale.US, "🌐 API · GS %d · %d/10 слот.",
                profile.gearScore, itemAudits.size() - totalRerollSlots);
        res.color = totalRerollSlots == 0 ? ScreenAnalyzer.VerdictColor.GOOD : ScreenAnalyzer.VerdictColor.WARN;
        res.headline = String.format(Locale.US,
                "🌐 Синхронизировано 31/31 статов, %d вещей и 20 гнёзд (%s)",
                itemAudits.size(), profile.mode.title);

        StringBuilder sb = new StringBuilder();
        sb.append("⚡ ПРЯМОЙ СЛЕПОК СЕРВЕРА IDLEARC (0% погрешности OCR)\n");
        sb.append(String.format(Locale.US,
                "• Друид Ур. %d · GS %d · Ср. удар питомца: %s (×%.2fM)\n",
                profile.charLevel, profile.gearScore, formatBigNumber(petAvgDmg), profile.petDamageMultMillions));
        sb.append(String.format(Locale.US,
                "• Инт %.0f · Ловк %.0f · Сила %.0f · Крит %.1f%%/%.0f%% · Дв.удар %.0f%% · Дв.урон %.0f%%\n",
                profile.intelligence, profile.agility, profile.strength,
                profile.critChance, profile.critDamage, profile.doubleHitChance, profile.doubleDamageChance));
        sb.append(String.format(Locale.US,
                "• Сниж. урона: %.1f%% (сырое %.1f%%) · Уклонение: %.1f%% (сырое %.1f%%)\n",
                profile.damageReductionEff, profile.damageReductionRaw, profile.dodgeEff, profile.dodgeRaw));

        if (profile.wastedDodgePct() > 0.5) {
            sb.append(String.format(Locale.US,
                    "  ⚠️ Оверкап Уклонения +%.1f%% сверх 95%% — лишние аффиксы уклонения дают 0%% EHP!\n",
                    profile.wastedDodgePct()));
        }
        if (profile.critChance >= 100.0) {
            sb.append(String.format(Locale.US,
                    "  ⚠️ Шанс крита %.1f%% >= 100%% — аффиксы и доп. статы камней на Шанс крита можно смело менять!\n",
                    profile.critChance));
        }

        // Раздел: 10 надетых предметов
        sb.append("\n⚔️ 1. РАЗБОР ВСЕХ 10 НАДЕТЫХ ВЕЩЕЙ ПОД «").append(profile.mode.title).append("»:\n");
        for (EquippedItemAudit ia : itemAudits) {
            if (ia.rerollsNeeded == 0) {
                sb.append(String.format(Locale.US, "  ✅ %s (А%d +%d) — все 5 статов в топе!\n",
                        ia.slotLabel, ia.tier, ia.tierLevel));
            } else {
                sb.append(String.format(Locale.US, "  🔧 %s (А%d +%d): потенциал +%.2f%% (%d/5 замен)\n",
                        ia.slotLabel, ia.tier, ia.tierLevel, ia.upgradeGainPct, ia.rerollsNeeded));
                int n = Math.min(ia.report.currentRows.size(), ia.report.idealByPos.size());
                java.util.Set<AffixPositionOptimizer.AffixType> idealSet =
                        new java.util.HashSet<>(ia.report.idealByPos);
                for (int i = 0; i < n; i++) {
                    AffixPositionOptimizer.ParsedAffixRow cur = ia.report.currentRows.get(i);
                    AffixPositionOptimizer.AffixType target = ia.report.idealByPos.get(i);
                    if (!idealSet.contains(cur.type)) {
                        sb.append(String.format(Locale.US, "     • #%d %s (%.0f%s) → лови %s\n",
                                cur.index, cur.type.ruName, cur.rolledValue,
                                cur.type.isPercent ? "%" : "", target.taggedName()));
                    }
                }
            }
        }

        // Проверка новых вещей в сумке (newLoot)
        List<Object> bagLoot = SimpleJson.asList(d.get("newLoot"));
        if (bagLoot != null && !bagLoot.isEmpty()) {
            sb.append(String.format(Locale.US,
                    "\n🎒 Новых предметов в сумке (newLoot): %d шт.\n", bagLoot.size()));
        }

        // Раздел: 20 гнёзд и самоцветов
        sb.append("\n💎 2. АУДИТ ВСЕХ 20 ГНЁЗД И САМОЦВЕТОВ:\n").append(gemSummary);

        // Раздел: Питомцы, Талисманы и Навыки
        sb.append("\n🐾 3. ОТРЯД ПИТОМЦЕВ, ТАЛИСМАНЫ И НАВЫКИ:\n");
        sb.append(buildPetsTalismansSkillsAudit(d, profile));

        res.details = sb.toString();
        return res;
    }

    /** Возвращает детальный построчный отчёт конкретного надетого слота из кэша API (для вкладки «Слоты») */
    public static String buildEquippedSlotDetailFromCache(SlotAffixAdvisor.GearSlot slot, DruidProfile profile) {
        Map<String, Object> d = cachedDashboard;
        if (d == null || slot == null || profile == null) return null;
        List<Object> eqList = SimpleJson.asList(d.get("characterEquippedItems"));
        if (eqList == null) return null;

        StringBuilder out = new StringBuilder();
        for (Object itemObj : eqList) {
            Map<String, Object> item = SimpleJson.asMap(itemObj);
            if (item == null) continue;
            EquippedItemAudit ia = analyzeServerItem(item, profile, true);
            if (ia != null && ia.gearSlot == slot && ia.report != null) {
                if (out.length() > 0) out.append("\n\n━━━━━━━━━━━━━━━━━━━━\n\n");
                out.append("🌐 ЖИВОЙ РАЗБОР НАДЕТОЙ ВЕЩИ С СЕРВЕРА (").append(ia.slotLabel).append("):\n");
                out.append(ia.report.details);
            }
        }
        return out.length() > 0 ? out.toString() : null;
    }

    private static EquippedItemAudit analyzeServerItem(Map<String, Object> item, DruidProfile profile, boolean isEquipped) {
        String typeStr = SimpleJson.str(item, "type", "");
        String slotStr = SimpleJson.str(item, "slot", "");
        int subSlot = SimpleJson.intVal(item, "equipped_sub_slot", 0);
        int tier = SimpleJson.intVal(item, "tier", 6);
        int tierLvl = SimpleJson.intVal(item, "tier_level", 0);

        SlotAffixAdvisor.GearSlot gs = mapServerSlot(typeStr, slotStr);
        if (gs == null) return null;

        String label = gs.shortChip + (subSlot > 0 ? (" #" + subSlot) : "");
        List<IdleArcOcrEngine.OcrLine> synLines = new ArrayList<>();
        int y = 100;
        synLines.add(new IdleArcOcrEngine.OcrLine(
                gs.ruTitle + " · А" + tier + (tierLvl > 0 ? (" +" + tierLvl) : ""), y, y + 25, 0f, 0f, 0, 220));
        y += 35;

        // Базовая атака оружия / защита брони из имплиситов и статов
        Map<String, Object> imp = SimpleJson.asMap(item.get("implicit_bonus"));
        if (imp != null) {
            String impType = SimpleJson.str(imp, "type", "");
            double impVal = SimpleJson.num(imp, "value", 0.0);
            if (impVal > 0) {
                if (impType.contains("defense")) {
                    synLines.add(new IdleArcOcrEngine.OcrLine(
                            "+" + Math.round(impVal) + " Защита", y, y + 25, 0f, 0f, 0, 220));
                    y += 35;
                } else if (impType.contains("damage") && gs == SlotAffixAdvisor.GearSlot.WEAPON) {
                    synLines.add(new IdleArcOcrEngine.OcrLine(
                            "+" + Math.round(impVal) + " Физический урон", y, y + 25, 0f, 0f, 0, 220));
                    y += 35;
                }
            }
        }
        if (gs == SlotAffixAdvisor.GearSlot.WEAPON && cachedDashboard != null) {
            Map<String, Object> adb = SimpleJson.asMap(cachedDashboard.get("attackDamageBreakdown"));
            Map<String, Object> wpn = adb != null ? SimpleJson.asMap(adb.get("weapon")) : null;
            if (wpn != null) {
                double wAvg = 0.5 * (SimpleJson.num(wpn, "min", 0.0) + SimpleJson.num(wpn, "max", 0.0));
                if (wAvg > 0) {
                    synLines.add(new IdleArcOcrEngine.OcrLine(
                            "+" + Math.round(wAvg) + " Физический урон", y, y + 25, 0f, 0f, 0, 220));
                    y += 35;
                }
            }
        }

        synLines.add(new IdleArcOcrEngine.OcrLine("АФФИКСЫ 5/5", y, y + 25, 0f, 0f, 0, 220));
        y += 35;

        List<Object> positions = SimpleJson.asList(item.get("affix_positions"));
        if (positions != null && !positions.isEmpty()) {
            for (Object posObj : positions) {
                Map<String, Object> pos = SimpleJson.asMap(posObj);
                if (pos == null) continue;
                Map<String, Object> aff = SimpleJson.asMap(pos.get("affix"));
                int boost = SimpleJson.intVal(pos, "boost_count", aff != null ? SimpleJson.intVal(aff, "awaken_boost_count", 0) : 0);
                if (aff == null) {
                    synLines.add(new IdleArcOcrEngine.OcrLine("Пустая позиция", y, y + 25, 0f, 0f, 0, 220));
                } else {
                    String ruAffixLine = formatServerAffixToRuLine(aff, boost);
                    synLines.add(new IdleArcOcrEngine.OcrLine(ruAffixLine, y, y + 25, 0.12f, 0f, Math.max(0, boost), 220));
                }
                y += 35;
            }
        } else {
            List<Object> affs = SimpleJson.asList(item.get("affixes"));
            if (affs != null) {
                for (Object affObj : affs) {
                    Map<String, Object> aff = SimpleJson.asMap(affObj);
                    if (aff == null) continue;
                    int boost = SimpleJson.intVal(aff, "awaken_boost_count", 0);
                    synLines.add(new IdleArcOcrEngine.OcrLine(
                            formatServerAffixToRuLine(aff, boost), y, y + 25, 0.12f, 0f, Math.max(0, boost), 220));
                    y += 35;
                }
            }
        }

        synLines.add(new IdleArcOcrEngine.OcrLine(isEquipped ? "Снять" : "Надеть", y + 20, y + 50, 0f, 0f, 0, 220));

        AffixPositionOptimizer.OptimizationReport rep =
                AffixPositionOptimizer.analyzeItemAffixes(synLines, gs, profile, isEquipped);
        if (rep == null) return null;

        java.util.Set<AffixPositionOptimizer.AffixType> idealSet = new java.util.HashSet<>(rep.idealByPos);
        int rerolls = 0;
        for (AffixPositionOptimizer.ParsedAffixRow r : rep.currentRows) {
            if (!idealSet.contains(r.type)) rerolls++;
        }
        double relGain = Math.max(0.0, ((100.0 + rep.idealTotalDpsEq) / Math.max(1.0, 100.0 + rep.currentTotalDpsEq) - 1.0) * 100.0);
        return new EquippedItemAudit(label, gs, tier, tierLvl, rep, rerolls, relGain);
    }

    private static SlotAffixAdvisor.GearSlot mapServerSlot(String typeStr, String slotStr) {
        String s = (typeStr + " " + slotStr).toLowerCase(Locale.ROOT);
        if (s.contains("weapon") || s.contains("main_hand")) return SlotAffixAdvisor.GearSlot.WEAPON;
        if (s.contains("torch") || s.contains("shield") || s.contains("off_hand")) return SlotAffixAdvisor.GearSlot.TORCH;
        if (s.contains("helmet") || s.contains("head")) return SlotAffixAdvisor.GearSlot.HELMET;
        if (s.contains("chest")) return SlotAffixAdvisor.GearSlot.CHEST;
        if (s.contains("gloves") || s.contains("hands")) return SlotAffixAdvisor.GearSlot.GLOVES;
        if (s.contains("boots") || s.contains("feet")) return SlotAffixAdvisor.GearSlot.BOOTS;
        if (s.contains("belt")) return SlotAffixAdvisor.GearSlot.BELT;
        if (s.contains("amulet")) return SlotAffixAdvisor.GearSlot.AMULET;
        if (s.contains("ring")) return SlotAffixAdvisor.GearSlot.RING;
        return null;
    }

    private static String formatServerAffixToRuLine(Map<String, Object> aff, int boostCount) {
        String en = SimpleJson.str(aff, "readable_bonus_name", "").toLowerCase(Locale.ROOT);
        String kind = SimpleJson.str(aff, "affix_type", SimpleJson.str(aff, "type", "")).toLowerCase(Locale.ROOT);
        String bType = SimpleJson.str(aff, "bonus_type", "").toLowerCase(Locale.ROOT);
        double val = SimpleJson.num(aff, "value", 0.0);
        boolean isPct = bType.contains("percent");

        String ruName;
        if (en.contains("pet damage") || en.contains("companion damage")) {
            ruName = "Урон питомца";
            isPct = true;
        } else if (en.contains("double hit")) {
            ruName = "Шанс двойного удара";
            isPct = true;
        } else if (en.contains("double damage")) {
            ruName = "Шанс двойного урона";
            isPct = true;
        } else if (en.contains("crit") && en.contains("chance")) {
            ruName = "Шанс крита";
            isPct = true;
        } else if (en.contains("crit") && en.contains("damage")) {
            ruName = "Крит. урон";
            isPct = true;
        } else if (en.contains("damage reduction")) {
            ruName = "Снижение урона";
            isPct = true;
        } else if (en.contains("dodge")) {
            ruName = "Шанс уклонения";
            isPct = true;
        } else if (en.contains("intelligence")) {
            ruName = "Интеллект";
            isPct = false;
        } else if (en.contains("dexterity") || en.contains("agility")) {
            ruName = "Ловкость";
            isPct = false;
        } else if (en.contains("strength")) {
            ruName = "Сила";
            isPct = false;
        } else if (en.contains("max health") || en.contains("maximum health")) {
            ruName = "Макс. здоровье";
            isPct = false;
        } else if ((en.contains("hit") && en.contains("kill")) || en.contains("bloodthirst")) {
            ruName = "Здоровье за удар и за убийство";
            isPct = true;
        } else if (en.contains("life on hit")) {
            ruName = "Здоровье за удар";
            isPct = false;
        } else if (en.contains("life on kill")) {
            ruName = "Здоровье за убийство";
            isPct = false;
        } else if (en.contains("defense")) {
            if (!isPct) {
                ruName = "Доп. защита";
            } else if (kind.contains("prefix") || en.contains("increased") || en.contains("enhanced")) {
                ruName = "Увеличенная защита";
            } else {
                ruName = "Защита";
            }
        } else if (en.contains("exp") || en.contains("experience")) {
            ruName = "Бонус опыта";
            isPct = true;
        } else if (en.contains("gold")) {
            ruName = "Бонус золота";
            isPct = true;
        } else if (en.contains("convert") || en.contains("conversion") || en.contains("fire")
                || en.contains("cold") || en.contains("lightning") || en.contains("poison")) {
            ruName = "Конверсия урона в стихию";
            isPct = true;
        } else {
            ruName = "Доп. урон";
            isPct = false;
        }

        String valStr = (Math.abs(val - Math.rint(val)) < 0.05)
                ? String.valueOf(Math.round(val))
                : String.format(Locale.US, "%.1f", val);
        String roman = toRoman(boostCount);
        return "• " + ruName + " +" + valStr + (isPct ? "%" : "")
                + (roman.isEmpty() ? "" : (" " + roman));
    }

    private static String toRoman(int n) {
        switch (n) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            case 6: return "VI";
            case 7: return "VII";
            case 8: return "VIII";
            default: return "";
        }
    }

    private static String buildServerGemsAudit(Map<String, Object> d, DruidProfile profile) {
        Map<String, Object> tracks = SimpleJson.asMap(d.get("gemSockets"));
        if (tracks == null || tracks.isEmpty()) return "  • Данные о гнёздах отсутствуют.\n";

        LiveBuildSimulator.HeroState hs = LiveBuildSimulator.buildHeroState(profile, 60.0, 0.0, 0.0);
        int totalSockets = 0;
        int filledGems = 0;
        int minLevel = Integer.MAX_VALUE;
        String minLevelTrack = "";
        List<String> gemAdvice = new ArrayList<>();

        for (Map.Entry<String, Object> entry : tracks.entrySet()) {
            Map<String, Object> tr = SimpleJson.asMap(entry.getValue());
            if (tr == null || !SimpleJson.bool(tr, "active", true)) continue;
            String trackName = entry.getKey();
            GemSocketOptimizer.SocketCategory cat = mapTrackToCategory(trackName);
            List<GemSocketOptimizer.RankedGemChoice<GemSocketOptimizer.GemFamily>> bestFam =
                    GemSocketOptimizer.rankFamiliesForCategory(hs, cat, GemSocketOptimizer.GemRarity.PERFECT, 100.0, 46, profile.mode);
            GemSocketOptimizer.GemFamily topFam = bestFam.get(0).item;

            List<Object> sockets = SimpleJson.asList(tr.get("sockets"));
            if (sockets == null) continue;
            for (Object sObj : sockets) {
                Map<String, Object> sk = SimpleJson.asMap(sObj);
                if (sk == null || !SimpleJson.bool(sk, "unlocked", false)) continue;
                totalSockets++;
                int sLvl = SimpleJson.intVal(sk, "setting_level", 0);
                int sIdx = SimpleJson.intVal(sk, "index", 1);
                if (sLvl < minLevel) {
                    minLevel = sLvl;
                    minLevelTrack = ruTrackName(trackName) + " #" + sIdx;
                }
                Map<String, Object> gem = SimpleJson.asMap(sk.get("gem"));
                if (gem == null) {
                    gemAdvice.add("  ⚠️ " + ruTrackName(trackName) + " #" + sIdx + ": пустое гнездо — вставь " + topFam.badge);
                    continue;
                }
                filledGems++;
                String famSlug = SimpleJson.str(gem, "family", "").toLowerCase(Locale.ROOT);
                int qual = SimpleJson.intVal(gem, "quality", 100);
                GemSocketOptimizer.GemFamily curFam = mapFamilySlug(famSlug);

                List<String> wastedSec = new ArrayList<>();
                List<Object> secs = SimpleJson.asList(gem.get("secondaries"));
                if (secs != null) {
                    for (Object secObj : secs) {
                        Map<String, Object> sec = SimpleJson.asMap(secObj);
                        if (sec == null) continue;
                        String st = SimpleJson.str(sec, "stat", "");
                        double sv = SimpleJson.num(sec, "value", 0.0);
                        if ("critical_strike_chance".equals(st) && (profile.critChance - sv) >= 100.0) {
                            wastedSec.add("Шанс крита +" + fmt1(sv) + "% (впустую при капе 100%)");
                        } else if ("dodge_chance".equals(st) && (profile.dodgeRaw - sv) >= 95.0) {
                            wastedSec.add("Уклонение +" + fmt1(sv) + "% (впустую при капе 95%)");
                        }
                    }
                }

                if (curFam != null && curFam != topFam) {
                    String extraWarn = wastedSec.isEmpty() ? "" : (" · лишний доп. стат: " + wastedSec.get(0));
                    gemAdvice.add(String.format(Locale.US,
                            "  🔄 %s #%d (ур.%d): стоит %s (%d%%) → под «%s» сильнее %s%s",
                            ruTrackName(trackName), sIdx, sLvl, curFam.ruName, qual, profile.mode.title, topFam.badge, extraWarn));
                } else if (!wastedSec.isEmpty()) {
                    gemAdvice.add(String.format(Locale.US,
                            "  ⚠️ %s #%d (%s %d%%): лишний доп. стат — %s",
                            ruTrackName(trackName), sIdx, curFam != null ? curFam.ruName : famSlug, qual, wastedSec.get(0)));
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "  • Открыто гнёзд: %d/20 (вставлено камней: %d)", totalSockets, filledGems));
        if (minLevel < Integer.MAX_VALUE) {
            sb.append(String.format(Locale.US, " · Следующий апгрейд лестницы: %s (ур. %d)", minLevelTrack, minLevel));
        }
        sb.append("\n");
        List<Object> bagGems = SimpleJson.asList(d.get("newGems"));
        if (bagGems != null && !bagGems.isEmpty()) {
            sb.append("  • Новых самоцветов в сумке: ").append(bagGems.size()).append(" шт.\n");
        }
        if (gemAdvice.isEmpty()) {
            sb.append("  ✅ Все 20 камней идеально подобраны под текущий режим без потерь в оверкап!\n");
        } else {
            int limit = Math.min(8, gemAdvice.size());
            for (int i = 0; i < limit; i++) {
                sb.append(gemAdvice.get(i)).append("\n");
            }
            if (gemAdvice.size() > limit) {
                sb.append("  ... и ещё ").append(gemAdvice.size() - limit).append(" позиций для тонкой полировки камней.\n");
            }
        }
        return sb.toString();
    }

    private static String buildPetsTalismansSkillsAudit(Map<String, Object> d, DruidProfile p) {
        StringBuilder sb = new StringBuilder();

        // Питомцы
        List<Object> pets = SimpleJson.asList(d.get("characterPets"));
        if (pets != null && !pets.isEmpty()) {
            int eqCount = 0;
            int minCompound = Integer.MAX_VALUE;
            int capCompound = 0;
            for (Object po : pets) {
                Map<String, Object> pm = SimpleJson.asMap(po);
                if (pm != null && SimpleJson.bool(pm, "is_equipped", false)) {
                    eqCount++;
                    minCompound = Math.min(minCompound, SimpleJson.intVal(pm, "compound_min_equipped", 0));
                    capCompound = Math.max(capCompound, SimpleJson.intVal(pm, "compound_effective_cap", 0));
                }
            }
            if (eqCount > 0) {
                sb.append(String.format(Locale.US,
                        "  • Отряд питомцев (%d/4): мин. уровень в четвёрке %d (потолок синергии %d) · Мастерство петов ур. %d\n",
                        eqCount, minCompound == Integer.MAX_VALUE ? 0 : minCompound, capCompound,
                        SimpleJson.intVal(d, "effectivePetMasteryLevel", 0)));
            }
        }

        // Талисманы
        List<Object> tals = SimpleJson.asList(d.get("equippedTalismans"));
        if (tals != null && !tals.isEmpty()) {
            double dhInsc = 0, ddInsc = 0, petInsc = 0;
            for (Object to : tals) {
                Map<String, Object> tm = SimpleJson.asMap(to);
                Map<String, Object> totals = tm != null ? SimpleJson.asMap(tm.get("inscription_totals")) : null;
                if (totals != null) {
                    dhInsc += SimpleJson.num(SimpleJson.asMap(totals.get("double_hit_chance")), "total", 0.0);
                    ddInsc += SimpleJson.num(SimpleJson.asMap(totals.get("double_damage_chance")), "total", 0.0);
                    petInsc += SimpleJson.num(SimpleJson.asMap(totals.get("pet_damage_bonus")), "total", 0.0);
                }
            }
            sb.append(String.format(Locale.US,
                    "  • Надписи обоих талисманов: Дв.удар +%.0f%% · Дв.урон +%.0f%% · Урон пета +%.0f%%\n",
                    dhInsc, ddInsc, petInsc));
        }

        // Классовые навыки Друида (проверка перекапа лимитных узлов с учётом +all_skills)
        int activeSkillSlot = SimpleJson.intVal(d, "activeClassSkillPresetSlot", 1);
        List<Object> skillPresets = SimpleJson.asList(d.get("classSkillPresetSlots"));
        if (skillPresets != null) {
            for (Object spObj : skillPresets) {
                Map<String, Object> sp = SimpleJson.asMap(spObj);
                if (sp != null && SimpleJson.intVal(sp, "slot", 0) == activeSkillSlot) {
                    int pts = SimpleJson.intVal(sp, "point_count", 0);
                    sb.append(String.format(Locale.US,
                            "  • Дерево классовых навыков (слот #%d): вложено %d очков, бонус ко всем навыкам +%d.\n",
                            activeSkillSlot, pts, p.bonusAllClassSkills));
                    break;
                }
            }
        }
        return sb.toString();
    }

    private static GemSocketOptimizer.SocketCategory mapTrackToCategory(String track) {
        if (track.startsWith("weapon")) return GemSocketOptimizer.SocketCategory.WEAPON;
        if (track.equals("torch")) return GemSocketOptimizer.SocketCategory.OFFHAND;
        if (track.equals("amulet") || track.startsWith("ring")) return GemSocketOptimizer.SocketCategory.JEWELRY;
        return GemSocketOptimizer.SocketCategory.ARMOR;
    }

    private static GemSocketOptimizer.GemFamily mapFamilySlug(String slug) {
        if ("garnet".equals(slug)) return GemSocketOptimizer.GemFamily.GARNET;
        if ("jade".equals(slug)) return GemSocketOptimizer.GemFamily.JADE;
        if ("lapis".equals(slug)) return GemSocketOptimizer.GemFamily.LAPIS;
        if ("amber".equals(slug)) return GemSocketOptimizer.GemFamily.AMBER;
        return null;
    }

    private static String ruTrackName(String track) {
        switch (track) {
            case "weapon_1": return "Оружие";
            case "torch": return "Факел";
            case "helmet": return "Шлем";
            case "chest_armor": return "Нагрудник";
            case "boots": return "Сапоги";
            case "gloves": return "Перчатки";
            case "belt": return "Пояс";
            case "amulet": return "Амулет";
            case "ring_1": return "Кольцо #1";
            case "ring_2": return "Кольцо #2";
            default: return track;
        }
    }

    private static String formatBigNumber(double v) {
        if (v >= 1e12) return String.format(Locale.US, "%.2fT", v / 1e12);
        if (v >= 1e9) return String.format(Locale.US, "%.2fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.US, "%.2fM", v / 1e6);
        if (v >= 1e3) return String.format(Locale.US, "%.1fK", v / 1e3);
        return String.format(Locale.US, "%.0f", v);
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }
}
