package com.idlearc.companion;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Чистый парсер таймеров, откатов и событий «Радара аккаунта» из /api/dashboard
 * + расчёт времени системного будильника с учётом «Тихих часов» (00:00–08:00).
 */
public final class RadarTimeParser {

    private RadarTimeParser() {
    }

    public static class AlarmTarget {
        public final String key;
        public final String alarmLabel;
        public final long targetEpochMs;
        public final int hour24;
        public final int minute;
        public final boolean shiftedByQuietHours;
        public final String formattedTime;
        public final String remainingText;

        public AlarmTarget(String key, String alarmLabel, long targetEpochMs,
                           int hour24, int minute, boolean shiftedByQuietHours,
                           String formattedTime, String remainingText) {
            this.key = key;
            this.alarmLabel = alarmLabel;
            this.targetEpochMs = targetEpochMs;
            this.hour24 = hour24;
            this.minute = minute;
            this.shiftedByQuietHours = shiftedByQuietHours;
            this.formattedTime = formattedTime;
            this.remainingText = remainingText;
        }
    }

    public static class RadarState {
        public boolean dungeonReady;
        public long dungeonReadyAtMs;
        public boolean guildBossReady;
        public boolean petHatchReady;
        public long petHatchAtMs;
        public long earliestBoostEndMs;
        public String earliestBoostName = "";
        public int talismanDailyRemaining;
        public int talismanPity;
        public boolean floorPaused;
        public int unspentTreePoints;
        public int unspentPassivePoints;
        public boolean hasCompletableQuests;
        public int unseenAchievementsCount;
        public int unreadMailCount;
        public int pendingFriendsCount;
        public final List<String> activeAlerts = new ArrayList<>();
    }

    public static RadarState parseRadar(Map<String, Object> d, long nowMs) {
        RadarState r = new RadarState();
        if (d == null) return r;

        // 1. Подземелье
        boolean onCd = SimpleJson.bool(d, "isDungeonOnCooldown", false);
        long expMs = parseFlexibleTimestampMs(d.get("dungeonCooldownExpirationTime"), nowMs);
        if (!onCd || (expMs > 0 && expMs <= nowMs)) {
            r.dungeonReady = true;
            r.dungeonReadyAtMs = 0L;
            r.activeAlerts.add("🏰 Подземелье открыто — можно заходить в бой!");
        } else {
            r.dungeonReady = false;
            r.dungeonReadyAtMs = expMs;
        }

        // 2. Босс гильдии
        r.guildBossReady = SimpleJson.bool(d, "guild_boss_can_fight", false);
        if (r.guildBossReady) {
            r.activeAlerts.add("👹 Доступна атака по Боссу гильдии!");
        }

        // 3. Вылупление яйца питомца
        Object hatchObj = d.get("pendingPetHatch");
        Map<String, Object> hatchMap = SimpleJson.asMap(hatchObj);
        if (hatchMap != null && !hatchMap.isEmpty()) {
            long hMs = extractMapDeadlineMs(hatchMap, nowMs);
            if (hMs > 0 && hMs <= nowMs) {
                r.petHatchReady = true;
                r.activeAlerts.add("🥚 Яйцо питомца готово к вылуплению!");
            } else if (hMs > nowMs) {
                r.petHatchAtMs = hMs;
            } else if (SimpleJson.bool(hatchMap, "ready", false) || SimpleJson.bool(hatchMap, "completed", false)) {
                r.petHatchReady = true;
                r.activeAlerts.add("🥚 Яйцо питомца созрело!");
            }
        }

        // 4. Временные бусты
        List<Object> boosts = SimpleJson.asList(d.get("activeTimeBoosts"));
        if (boosts != null) {
            for (Object bo : boosts) {
                Map<String, Object> bm = SimpleJson.asMap(bo);
                if (bm == null) continue;
                long bEnd = extractMapDeadlineMs(bm, nowMs);
                if (bEnd > nowMs && (r.earliestBoostEndMs == 0L || bEnd < r.earliestBoostEndMs)) {
                    r.earliestBoostEndMs = bEnd;
                    r.earliestBoostName = RuGameDictionary.humanizeSlug(
                            SimpleJson.str(bm, "name", SimpleJson.str(bm, "type", "Буст")));
                }
            }
        }

        // 5. Талисманы, пауза этажей, забытые очки и квесты
        r.talismanDailyRemaining = SimpleJson.intVal(d, "talismanDailyRemaining", 0);
        Object pityObj = d.get("talismanPity");
        if (pityObj instanceof Number || pityObj instanceof String) {
            r.talismanPity = SimpleJson.intVal(d, "talismanPity", 0);
        } else if (SimpleJson.asMap(pityObj) != null) {
            Map<String, Object> pm = SimpleJson.asMap(pityObj);
            r.talismanPity = SimpleJson.intVal(pm, "current", SimpleJson.intVal(pm, "count", 0));
        }

        r.floorPaused = SimpleJson.bool(d, "monsterLevelPaused", false);
        if (r.floorPaused) {
            r.activeAlerts.add("⏸️ Авто-продвижение по этажам стоит на ПАУЗЕ!");
        }

        r.unspentTreePoints = SimpleJson.intVal(d, "treePoints", 0);
        r.unspentPassivePoints = SimpleJson.intVal(d, "availablePassivePoints", 0);
        if (r.unspentTreePoints > 0) {
            r.activeAlerts.add("🌳 Есть невложенные очки Глобального Дерева: +" + r.unspentTreePoints + "!");
        }
        if (r.unspentPassivePoints > 0) {
            r.activeAlerts.add("✨ Есть свободные очки Пассивок: +" + r.unspentPassivePoints + "!");
        }

        r.hasCompletableQuests = SimpleJson.bool(d, "hasCompletableQuests", false);
        if (r.hasCompletableQuests) {
            r.activeAlerts.add("📜 Есть выполненные квесты — забери награду!");
        }

        List<Object> unseenAch = SimpleJson.asList(d.get("unseen_achievements"));
        r.unseenAchievementsCount = unseenAch != null ? unseenAch.size() : SimpleJson.intVal(d, "unseen_achievements", 0);
        if (r.unseenAchievementsCount > 0) {
            r.activeAlerts.add("🏆 Новых незабранных достижений: " + r.unseenAchievementsCount);
        }

        r.unreadMailCount = SimpleJson.intVal(d, "unread_mail_count", 0);
        r.pendingFriendsCount = SimpleJson.intVal(d, "pending_friend_requests_count", 0);
        if (r.unreadMailCount > 0) {
            r.activeAlerts.add("✉️ Непрочитанных писем на почте: " + r.unreadMailCount);
        }

        return r;
    }

    /** Рассчитывает точный час и минуту системного будильника (с округлением вверх до следующей минуты и учётом Тихих часов 00:00–08:00) */
    public static AlarmTarget buildAlarmTarget(String key, String label, long deadlineMs,
                                               long nowMs, boolean quietHoursNight) {
        if (deadlineMs <= nowMs) return null;
        // Добавляем +55 сек, чтобы будильник звонил сразу ПОСЛЕ фактического окончания таймера, а не за полминуты до него
        long safeMs = deadlineMs + 55_000L;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(safeMs);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int min = cal.get(Calendar.MINUTE);
        boolean shifted = false;

        if (quietHoursNight && hour >= 0 && hour < 8) {
            hour = 8;
            min = 0;
            shifted = true;
        }

        String hhmm = String.format(Locale.US, "%02d:%02d", hour, min);
        String rem = formatRemaining(deadlineMs - nowMs);
        return new AlarmTarget(key, label, deadlineMs, hour, min, shifted, hhmm, rem);
    }

    public static String formatRemaining(long diffMs) {
        if (diffMs <= 0) return "Готово!";
        long totalSec = diffMs / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) {
            return String.format(Locale.US, "%d ч %02d мин", h, m);
        }
        return String.format(Locale.US, "%d мин %02d сек", m, s);
    }

    private static long extractMapDeadlineMs(Map<String, Object> m, long nowMs) {
        String[] tsKeys = {"ready_at", "hatch_at", "completes_at", "expires_at", "ends_at", "expiration_time"};
        for (String k : tsKeys) {
            long parsed = parseFlexibleTimestampMs(m.get(k), nowMs);
            if (parsed > 0) return parsed;
        }
        double remMs = SimpleJson.num(m, "remaining_ms", 0.0);
        if (remMs > 0) return nowMs + Math.round(remMs);
        double remSec = SimpleJson.num(m, "remaining_seconds", 0.0);
        if (remSec > 0) return nowMs + Math.round(remSec * 1000.0);
        return 0L;
    }

    public static long parseFlexibleTimestampMs(Object raw, long nowMs) {
        if (raw == null) return 0L;
        if (raw instanceof Number) {
            long v = ((Number) raw).longValue();
            if (v <= 0) return 0L;
            // Если меньше 10^11 — это секунды Unix epoch, иначе миллисекунды
            return v < 100_000_000_000L ? (v * 1000L) : v;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return 0L;

        // Числовая строка
        if (s.matches("^[0-9]{9,14}$")) {
            try {
                long v = Long.parseLong(s);
                return v < 100_000_000_000L ? (v * 1000L) : v;
            } catch (Exception ignored) {
            }
        }

        // ISO-8601 формат вида 2026-11-12T04:15:30+00:00 или 2026-11-12T04:15:30.000000Z
        try {
            String norm = s.replaceAll("\\.[0-9]+", "");
            if (norm.endsWith("Z")) {
                norm = norm.substring(0, norm.length() - 1) + "+0000";
            } else if (norm.matches(".*[+-][0-9]{2}:[0-9]{2}$")) {
                int colon = norm.lastIndexOf(':');
                norm = norm.substring(0, colon) + norm.substring(colon + 1);
            }
            String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
            };
            for (String pat : patterns) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat(pat, Locale.US);
                    if (!pat.endsWith("Z")) sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                    Date dt = sdf.parse(norm);
                    if (dt != null) return dt.getTime();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }
}
