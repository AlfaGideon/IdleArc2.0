package com.idlearc.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.AlarmClock;
import android.widget.Toast;

import java.util.Map;

/**
 * Интеграция с родным системным приложением «Часы / Будильник» телефона (AlarmClock.ACTION_SET_ALARM)
 * + умные уведомления в шторку с кнопкой «🎮 Открыть IdleArc».
 */
public final class SystemAlarmAndNotifier {

    private static final String PREFS = "druid_helper_alarms_v31";
    private static final String ALERT_CHANNEL = "druid_radar_alerts_v31";
    private static final int ALERT_NOTIF_ID = 7799;

    private SystemAlarmAndNotifier() {
    }

    public static boolean isAutoAlarmEnabled(Context ctx, String eventKey) {
        SharedPreferences sp = prefs(ctx);
        return sp != null && sp.getBoolean("auto_alarm_" + eventKey, false);
    }

    public static void setAutoAlarmEnabled(Context ctx, String eventKey, boolean enabled) {
        SharedPreferences sp = prefs(ctx);
        if (sp != null) sp.edit().putBoolean("auto_alarm_" + eventKey, enabled).apply();
    }

    public static boolean isQuietHoursEnabled(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        return sp == null || sp.getBoolean("quiet_hours_night", true);
    }

    public static void setQuietHoursEnabled(Context ctx, boolean enabled) {
        SharedPreferences sp = prefs(ctx);
        if (sp != null) sp.edit().putBoolean("quiet_hours_night", enabled).apply();
    }

    public static boolean isSmartNotifsEnabled(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        return sp == null || sp.getBoolean("smart_notifs", true);
    }

    public static void setSmartNotifsEnabled(Context ctx, boolean enabled) {
        SharedPreferences sp = prefs(ctx);
        if (sp != null) sp.edit().putBoolean("smart_notifs", enabled).apply();
    }

    /** Прописывает настоящий системный будильник в стандартные «Часы» телефона */
    public static boolean scheduleSystemClockAlarm(Context ctx, RadarTimeParser.AlarmTarget target, boolean isManualClick) {
        if (ctx == null || target == null) return false;
        SharedPreferences sp = prefs(ctx);
        String dedupSignature = target.hour24 + ":" + target.minute + "@" + (target.targetEpochMs / 120_000L);

        if (!isManualClick && sp != null) {
            String prevSig = sp.getString("last_alarm_sig_" + target.key, "");
            if (dedupSignature.equals(prevSig)) {
                return false; // Такой будильник уже поставлен, дубли не плодим
            }
        }

        try {
            Intent alarmIntent = new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, target.hour24)
                    .putExtra(AlarmClock.EXTRA_MINUTES, target.minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, target.alarmLabel)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            ctx.startActivity(alarmIntent);
            if (sp != null) {
                sp.edit().putString("last_alarm_sig_" + target.key, dedupSignature).apply();
            }
            String note = target.shiftedByQuietHours
                    ? ("⏰ Будильник поставлен на " + target.formattedTime + " (с учётом Тихих часов 00:00–08:00)")
                    : ("⏰ Системный будильник поставлен на " + target.formattedTime + " — «" + target.alarmLabel + "»");
            Toast.makeText(ctx, note, Toast.LENGTH_LONG).show();
            return true;
        } catch (Exception e) {
            if (isManualClick) {
                Toast.makeText(ctx, "Не удалось открыть системные Часы: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return false;
        }
    }

    /** Автоматическая обработка таймеров и пуш-уведомлений при каждой синхронизации /api/dashboard */
    public static void processDashboardEvents(Context ctx, Map<String, Object> dash) {
        if (ctx == null || dash == null) return;
        long now = System.currentTimeMillis();
        RadarTimeParser.RadarState radar = RadarTimeParser.parseRadar(dash, now);
        boolean quiet = isQuietHoursEnabled(ctx);

        // 1. Авто-будильники по включённым тумблерам
        if (!radar.bossFightReady && radar.bossFightReadyAtMs > now && isAutoAlarmEnabled(ctx, "boss")) {
            RadarTimeParser.AlarmTarget t = RadarTimeParser.buildAlarmTarget(
                    "boss", "⚔️ IdleArc: Откатился Бой с Боссом!", radar.bossFightReadyAtMs, now, quiet);
            scheduleSystemClockAlarm(ctx, t, false);
        }
        if (!radar.guildBossReady && radar.guildBossReadyAtMs > now && isAutoAlarmEnabled(ctx, "guild_boss")) {
            RadarTimeParser.AlarmTarget t = RadarTimeParser.buildAlarmTarget(
                    "guild_boss", "👹 IdleArc: Откатился Босс гильдии!", radar.guildBossReadyAtMs, now, quiet);
            scheduleSystemClockAlarm(ctx, t, false);
        }
        if (!radar.dungeonReady && radar.dungeonReadyAtMs > now && isAutoAlarmEnabled(ctx, "dungeon")) {
            RadarTimeParser.AlarmTarget t = RadarTimeParser.buildAlarmTarget(
                    "dungeon", "🏰 IdleArc: Подземелье откатилось!", radar.dungeonReadyAtMs, now, quiet);
            scheduleSystemClockAlarm(ctx, t, false);
        }
        if (!radar.petHatchReady && radar.petHatchAtMs > now && isAutoAlarmEnabled(ctx, "hatch")) {
            RadarTimeParser.AlarmTarget t = RadarTimeParser.buildAlarmTarget(
                    "hatch", "🥚 IdleArc: Яйцо питомца созрело!", radar.petHatchAtMs, now, quiet);
            scheduleSystemClockAlarm(ctx, t, false);
        }
        if (radar.earliestBoostEndMs > now && isAutoAlarmEnabled(ctx, "boost")) {
            RadarTimeParser.AlarmTarget t = RadarTimeParser.buildAlarmTarget(
                    "boost", "⚡ IdleArc: Истекает " + radar.earliestBoostName, radar.earliestBoostEndMs, now, quiet);
            scheduleSystemClockAlarm(ctx, t, false);
        }

        // 2. Умное уведомление в шторку с кнопкой «Открыть IdleArc» (без спама повторами)
        if (isSmartNotifsEnabled(ctx) && (radar.dungeonReady || radar.guildBossReady || radar.petHatchReady)) {
            StringBuilder titleSb = new StringBuilder("🌿 IdleArc ждёт тебя:");
            StringBuilder bodySb = new StringBuilder();
            String sig = (radar.dungeonReady ? "D1" : "D0")
                    + (radar.guildBossReady ? "G1" : "G0")
                    + (radar.petHatchReady ? "H1" : "H0")
                    + "@" + (now / (30 * 60_000L)); // не чаще раза в 30 мин при неизменном статусе

            SharedPreferences sp = prefs(ctx);
            String lastNotifSig = sp != null ? sp.getString("last_notif_sig", "") : "";
            if (!sig.equals(lastNotifSig)) {
                if (radar.dungeonReady) bodySb.append("🏰 Данж готов! ");
                if (radar.guildBossReady) bodySb.append("👹 Босс гильдии открыт! ");
                if (radar.petHatchReady) bodySb.append("🥚 Яйцо созрело! ");

                String stance = SimpleJson.str(dash, "activeCombatStance", "");
                if ((radar.dungeonReady || radar.guildBossReady) && stance.toLowerCase().contains("loot")) {
                    bodySb.append("(Подсказка: переключи стойку/пресет с Фарма на Бой!)");
                }
                sendQuickLaunchNotification(ctx, titleSb.toString(), bodySb.toString().trim());
                if (sp != null) sp.edit().putString("last_notif_sig", sig).apply();
            }
        }
    }

    public static void openIdleArcGame(Context ctx) {
        if (ctx == null) return;
        PackageManager pm = ctx.getPackageManager();
        String[] knownPackages = {
            "com.idlearc.app",
            "com.idlearc.game",
            "com.idlearc.twa",
            "app.idlearc.game",
            "io.idlearc.game"
        };
        if (pm != null) {
            for (String pkg : knownPackages) {
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(launch);
                    return;
                }
            }
        }
        try {
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://idlearc.com/app/"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(web);
        } catch (Exception e) {
            Toast.makeText(ctx, "Не удалось открыть IdleArc: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static void sendQuickLaunchNotification(Context ctx, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        ALERT_CHANNEL, "Откаты данжа и событий IdleArc", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }

            Intent gameIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://idlearc.com/app/"));
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent gamePi = PendingIntent.getActivity(ctx, 91, gameIntent, flags);

            Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, ALERT_CHANNEL)
                    : new Notification.Builder(ctx);

            nb.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
              .setContentTitle(title)
              .setContentText(text)
              .setStyle(new Notification.BigTextStyle().bigText(text))
              .setContentIntent(gamePi)
              .setAutoCancel(true);

            nm.notify(ALERT_NOTIF_ID, nb.build());
        } catch (Exception ignored) {
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx != null ? ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) : null;
    }
}
