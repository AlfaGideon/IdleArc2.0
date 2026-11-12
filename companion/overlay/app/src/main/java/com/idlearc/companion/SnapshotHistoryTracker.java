package com.idlearc.companion;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Трекер истории персонажа (Pure API):
 * 1) Детектор «До / После» (показывает точный эффект перековки/замены вещи/камня между синхронизациями).
 * 2) Живой счётчик фарм-сессии (Золото/ч, Пыль/ч, Осколки петов/ч, Рубины/ч, Опыт/ч).
 * 3) Дневник прогресса героя по дням (до 30 дней истории роста Уровня, GS и Удара питомца).
 */
public class SnapshotHistoryTracker {

    private static final String PREFS = "druid_helper_history_v31";

    private static volatile String lastDiffSummary = "";
    private static volatile boolean lastDiffPositive = true;

    public static class SessionRates {
        public final boolean active;
        public final double elapsedMinutes;
        public final double goldPerHour;
        public final double dustPerHour;
        public final double shardsPerHour;
        public final double rubiesPerHour;
        public final double levelsPerHour;

        public SessionRates(boolean active, double elapsedMinutes, double goldPerHour,
                            double dustPerHour, double shardsPerHour, double rubiesPerHour,
                            double levelsPerHour) {
            this.active = active;
            this.elapsedMinutes = elapsedMinutes;
            this.goldPerHour = goldPerHour;
            this.dustPerHour = dustPerHour;
            this.shardsPerHour = shardsPerHour;
            this.rubiesPerHour = rubiesPerHour;
            this.levelsPerHour = levelsPerHour;
        }
    }

    public static class DailyPoint {
        public final String dateStr;
        public final int level;
        public final int gearScore;
        public final double petAvgDmg;
        public final double trueMultM;

        public DailyPoint(String dateStr, int level, int gearScore, double petAvgDmg, double trueMultM) {
            this.dateStr = dateStr;
            this.level = level;
            this.gearScore = gearScore;
            this.petAvgDmg = petAvgDmg;
            this.trueMultM = trueMultM;
        }
    }

    public static String getLastDiffSummary() {
        return lastDiffSummary;
    }

    public static boolean isLastDiffPositive() {
        return lastDiffPositive;
    }

    /** Вызывается при каждом успешном получении /api/dashboard */
    public static void onNewDashboardSynced(Context ctx, Map<String, Object> prevDash, Map<String, Object> newDash, long snapshotTimeMs) {
        if (newDash == null) return;

        // 1. Считаем дельту «До / После» относительно предыдущего слепка
        if (prevDash != null && !prevDash.isEmpty()) {
            computeBeforeAfterDiff(prevDash, newDash);
        }

        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp == null) return;

        long now = System.currentTimeMillis();
        // 2. Инициализируем старт фарм-сессии, если ещё не задан
        String character = SimpleJson.str(newDash, "characterId", "");
        long start = sp.getLong("farm_start_v2", 0L);
        boolean pending = sp.getBoolean("farm_reset_pending_v2", false);
        long resetRequestedMs = sp.getLong("farm_reset_requested_v2", 0L);
        if (!character.isEmpty() && snapshotTimeMs > 0 && (!pending || snapshotTimeMs >= resetRequestedMs)
                && (start <= 0 || snapshotTimeMs < start
                || !character.equals(sp.getString("farm_character_v2", "")) || pending)) {
            resetFarmSession(ctx, newDash, snapshotTimeMs);
        }

        // 3. Сохраняем дневную точку прогресса (YYYY-MM-DD)
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now));
        int lvl = SimpleJson.intVal(newDash, "level", 1);
        int gs = SimpleJson.intVal(newDash, "gear_score", 0);
        Map<String, Object> bs = SimpleJson.asMap(newDash.get("bonusStats"));
        double petAvg = bs != null ? SimpleJson.num(bs, "petDamageFinalAvg", 0.0) : 0.0;
        double multM = bs != null ? (SimpleJson.num(bs, "petDamageTrueMultiplier", 0.0) / 1_000_000.0) : 0.0;

        String rawLog = sp.getString("daily_log", "");
        List<String> lines = new ArrayList<>();
        boolean replacedToday = false;
        if (rawLog != null && !rawLog.isEmpty()) {
            for (String row : rawLog.split("\n")) {
                if (row.trim().isEmpty()) continue;
                if (row.startsWith(today + "|")) {
                    lines.add(formatRow(today, lvl, gs, petAvg, multM));
                    replacedToday = true;
                } else {
                    lines.add(row);
                }
            }
        }
        if (!replacedToday) {
            lines.add(formatRow(today, lvl, gs, petAvg, multM));
        }
        while (lines.size() > 30) {
            lines.remove(0);
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) joined.append('\n');
            joined.append(lines.get(i));
        }
        sp.edit().putString("daily_log", joined.toString()).apply();
    }

    public static void computeBeforeAfterDiff(Map<String, Object> oldD, Map<String, Object> newD) {
        if (oldD == null || newD == null) return;
        Map<String, Object> bsOld = SimpleJson.asMap(oldD.get("bonusStats"));
        Map<String, Object> bsNew = SimpleJson.asMap(newD.get("bonusStats"));

        double oldPet = bsOld != null ? SimpleJson.num(bsOld, "petDamageFinalAvg", 0.0) : 0.0;
        double newPet = bsNew != null ? SimpleJson.num(bsNew, "petDamageFinalAvg", 0.0) : 0.0;
        int oldGs = SimpleJson.intVal(oldD, "gear_score", 0);
        int newGs = SimpleJson.intVal(newD, "gear_score", 0);
        int oldLvl = SimpleJson.intVal(oldD, "level", 0);
        int newLvl = SimpleJson.intVal(newD, "level", 0);
        double oldDr = SimpleJson.num(oldD, "damageReductionUncapped", SimpleJson.num(oldD, "damageReduction", 0.0));
        double newDr = SimpleJson.num(newD, "damageReductionUncapped", SimpleJson.num(newD, "damageReduction", 0.0));
        double oldHp = SimpleJson.num(oldD, "maxHealth", 0.0);
        double newHp = SimpleJson.num(newD, "maxHealth", 0.0);

        List<String> diffs = new ArrayList<>();
        boolean positive = true;

        if (oldPet > 0 && Math.abs(newPet - oldPet) / oldPet >= 0.001) {
            double pct = ((newPet - oldPet) / oldPet) * 100.0;
            if (pct < -0.05) positive = false;
            diffs.add(String.format(Locale.US, "Удар пета %+.2f%% (%s → %s)",
                    pct, RuGameDictionary.fmtBig(oldPet), RuGameDictionary.fmtBig(newPet)));
        }
        if (newGs != oldGs && oldGs > 0) {
            diffs.add(String.format(Locale.US, "GS %+d (%d → %d)", newGs - oldGs, oldGs, newGs));
        }
        if (newLvl != oldLvl && oldLvl > 0) {
            diffs.add(String.format(Locale.US, "Уровень %+d (%d → %d)", newLvl - oldLvl, oldLvl, newLvl));
        }
        if (oldHp > 0 && Math.abs(newHp - oldHp) / oldHp >= 0.002) {
            double hpPct = ((newHp - oldHp) / oldHp) * 100.0;
            diffs.add(String.format(Locale.US, "Здоровье %+.1f%%", hpPct));
        }
        if (Math.abs(newDr - oldDr) >= 0.1) {
            diffs.add(String.format(Locale.US, "Сырое СУ %.1f%% → %.1f%%", oldDr, newDr));
        }

        if (!diffs.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < diffs.size(); i++) {
                if (i > 0) sb.append(" · ");
                sb.append(diffs.get(i));
            }
            lastDiffSummary = sb.toString();
            lastDiffPositive = positive;
        }
    }

    /** Start a new session on the next successful server snapshot, not an old cached balance. */
    public static void requestFarmSessionReset(Context ctx) {
        requestFarmSessionReset(ctx, System.currentTimeMillis());
    }

    public static void requestFarmSessionReset(Context ctx, long requestedMs) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp != null) sp.edit().putBoolean("farm_reset_pending_v2", true)
                .putLong("farm_reset_requested_v2", requestedMs).apply();
    }

    public static boolean isFarmResetPending(Context ctx) {
        if (ctx == null) return false;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp != null && sp.getBoolean("farm_reset_pending_v2", false);
    }

    public static void clearFarmSession(Context ctx) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp != null) sp.edit().putLong("farm_start_v2", 0L)
                .putString("farm_character_v2", "").putBoolean("farm_reset_pending_v2", false).apply();
    }

    public static void resetFarmSession(Context ctx, Map<String, Object> dash, long snapshotTimeMs) {
        if (ctx == null || dash == null || snapshotTimeMs <= 0) return;
        String character = SimpleJson.str(dash, "characterId", "");
        if (character.isEmpty()) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp == null) return;
        sp.edit().putLong("farm_start_v2", snapshotTimeMs)
                .putString("farm_character_v2", character)
                .putBoolean("farm_reset_pending_v2", false)
                .putString("farm_gold_v2", serialize(resource(dash, "goldAmount", "goldAmountStr")))
                .putString("farm_dust_v2", serialize(resource(dash, "gemDust", "gemDustStr")))
                .putString("farm_shards_v2", serialize(resource(dash, "petShardsAmount", "petShardsAmountStr")))
                .putString("farm_rubies_v2", serialize(resource(dash, "rubyAmount", "rubyAmountStr")))
                .putString("farm_level_v2", serialize(levelFraction(dash)))
                .apply();
    }

    public static SessionRates getSessionRates(Context ctx, Map<String, Object> dash) {
        return getSessionRates(ctx, dash, IdleArcApiClient.getLastSyncTimeMs());
    }

    public static SessionRates getSessionRates(Context ctx, Map<String, Object> dash, long snapshotTimeMs) {
        if (ctx == null || dash == null) return unavailable(0);
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp == null || sp.getBoolean("farm_reset_pending_v2", false)) return unavailable(0);
        long start = sp.getLong("farm_start_v2", 0L);
        String character = SimpleJson.str(dash, "characterId", "");
        if (start <= 0 || character.isEmpty() || !character.equals(sp.getString("farm_character_v2", ""))) return unavailable(0);
        long elapsedMs = snapshotTimeMs - start;
        if (elapsedMs < 30_000L) return unavailable(Math.max(0, elapsedMs) / 60000.0);
        return new SessionRates(true, elapsedMs / 60000.0,
                hourly(sp, "farm_gold_v2", resource(dash, "goldAmount", "goldAmountStr"), elapsedMs),
                hourly(sp, "farm_dust_v2", resource(dash, "gemDust", "gemDustStr"), elapsedMs),
                hourly(sp, "farm_shards_v2", resource(dash, "petShardsAmount", "petShardsAmountStr"), elapsedMs),
                hourly(sp, "farm_rubies_v2", resource(dash, "rubyAmount", "rubyAmountStr"), elapsedMs),
                hourly(sp, "farm_level_v2", levelFraction(dash), elapsedMs));
    }

    private static SessionRates unavailable(double minutes) {
        return new SessionRates(false, minutes, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private static String serialize(BigDecimal value) { return value == null ? "" : value.toString(); }

    private static BigDecimal decimal(Object raw, boolean exactString) {
        if (!(raw instanceof Number) && !(raw instanceof String)) return null;
        if (raw instanceof Number && !exactString && Math.abs(((Number) raw).doubleValue()) > 9_007_199_254_740_992d) return null;
        try { return new BigDecimal(String.valueOf(raw).trim()); }
        catch (NumberFormatException ignored) { return null; }
    }

    /** Prefer the exact server string: JSON numbers have already been parsed as doubles. */
    private static BigDecimal resource(Map<String, Object> dash, String key, String exactKey) {
        BigDecimal exact = decimal(dash.get(exactKey), true);
        return exact != null ? exact : decimal(dash.get(key), false);
    }

    private static BigDecimal levelFraction(Map<String, Object> dash) {
        BigDecimal level = decimal(dash.get("level"), false);
        BigDecimal progress = decimal(dash.get("levelProgress"), false);
        if (level == null || progress == null || level.signum() < 0
                || progress.signum() < 0 || progress.compareTo(BigDecimal.valueOf(100)) > 0) return null;
        return level.add(progress.movePointLeft(2));
    }

    private static double hourly(SharedPreferences sp, String key, BigDecimal current, long elapsedMs) {
        BigDecimal start = decimal(sp.getString(key, ""), true);
        if (start == null || current == null || elapsedMs <= 0) return Double.NaN;
        double result = current.subtract(start).multiply(BigDecimal.valueOf(3_600_000L))
                .divide(BigDecimal.valueOf(elapsedMs), MathContext.DECIMAL128).doubleValue();
        return Double.isFinite(result) ? result : Double.NaN;
    }

    public static List<DailyPoint> getDailyJournal(Context ctx) {
        List<DailyPoint> out = new ArrayList<>();
        if (ctx == null) return out;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp == null) return out;
        String raw = sp.getString("daily_log", "");
        if (raw == null || raw.isEmpty()) return out;
        String[] rows = raw.split("\n");
        for (int i = rows.length - 1; i >= 0; i--) {
            String[] parts = rows[i].split("\\|");
            if (parts.length >= 5) {
                out.add(new DailyPoint(
                        parts[0],
                        (int) Math.round(parseD(parts[1])),
                        (int) Math.round(parseD(parts[2])),
                        parseD(parts[3]),
                        parseD(parts[4])));
            }
        }
        return out;
    }

    private static String formatRow(String date, int lvl, int gs, double petAvg, double multM) {
        return date + "|" + lvl + "|" + gs + "|" + petAvg + "|" + multM;
    }

    private static double parseD(String s) {
        if (s == null) return 0.0;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0.0;
        }
    }
}
