package com.idlearc.companion;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
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
    public static void onNewDashboardSynced(Context ctx, Map<String, Object> prevDash, Map<String, Object> newDash) {
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
        if (sp.getLong("sess_start_ms", 0L) <= 0L) {
            resetFarmSession(ctx, newDash);
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

    public static void resetFarmSession(Context ctx, Map<String, Object> dash) {
        if (ctx == null || dash == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp == null) return;
        double lvlFrac = SimpleJson.num(dash, "level", 1.0) + SimpleJson.num(dash, "levelProgress", 0.0) / 100.0;
        sp.edit()
          .putLong("sess_start_ms", System.currentTimeMillis())
          .putString("sess_gold", String.valueOf(SimpleJson.num(dash, "goldAmount", 0.0)))
          .putString("sess_dust", String.valueOf(SimpleJson.num(dash, "gemDust", 0.0)))
          .putString("sess_shards", String.valueOf(SimpleJson.num(dash, "petShardsAmount", 0.0)))
          .putString("sess_rubies", String.valueOf(SimpleJson.num(dash, "rubyAmount", 0.0)))
          .putString("sess_lvl", String.valueOf(lvlFrac))
          .apply();
    }

    public static SessionRates getSessionRates(Context ctx, Map<String, Object> dash) {
        if (ctx == null || dash == null) {
            return new SessionRates(false, 0, 0, 0, 0, 0, 0);
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp == null) return new SessionRates(false, 0, 0, 0, 0, 0, 0);
        long startMs = sp.getLong("sess_start_ms", 0L);
        long elapsedMs = System.currentTimeMillis() - startMs;
        if (startMs <= 0L || elapsedMs < 30_000L) {
            return new SessionRates(false, Math.max(0.0, elapsedMs / 60000.0), 0, 0, 0, 0, 0);
        }
        double hours = elapsedMs / 3_600_000.0;
        double sGold = parseD(sp.getString("sess_gold", "0"));
        double sDust = parseD(sp.getString("sess_dust", "0"));
        double sShards = parseD(sp.getString("sess_shards", "0"));
        double sRubies = parseD(sp.getString("sess_rubies", "0"));
        double sLvl = parseD(sp.getString("sess_lvl", "0"));

        double curGold = SimpleJson.num(dash, "goldAmount", 0.0);
        double curDust = SimpleJson.num(dash, "gemDust", 0.0);
        double curShards = SimpleJson.num(dash, "petShardsAmount", 0.0);
        double curRubies = SimpleJson.num(dash, "rubyAmount", 0.0);
        double curLvl = SimpleJson.num(dash, "level", 1.0) + SimpleJson.num(dash, "levelProgress", 0.0) / 100.0;

        return new SessionRates(
                true,
                elapsedMs / 60000.0,
                Math.max(0.0, (curGold - sGold) / hours),
                Math.max(0.0, (curDust - sDust) / hours),
                Math.max(0.0, (curShards - sShards) / hours),
                Math.max(0.0, (curRubies - sRubies) / hours),
                Math.max(0.0, (curLvl - sLvl) / hours));
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
