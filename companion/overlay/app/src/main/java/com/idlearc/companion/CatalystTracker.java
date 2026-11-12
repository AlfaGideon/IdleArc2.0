package com.idlearc.companion;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/** Read-only estimate based on the game's catalyst config and observed floor progress. */
public final class CatalystTracker {
    private static final String PREFS = "idlearc_catalyst_tracker_v1";
    private static final long MIN_INTERVAL_MS = 60_000L;
    private static final long MAX_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final long RATE_MAX_AGE_MS = 30 * 60 * 1000L;
    private CatalystTracker() {}

    public static final class Sample {
        public String character, biome;
        public int selected, monsterLevel, effective;
        public double kills;
        public long timeMs;
    }

    public static final class State {
        public long count = -1;
        public boolean configKnown;
        public boolean enabled;
        public double baseChance = -1;
        public int minLevel;
        public int pityKills;
        public int currentLevel;
        public double progressPerHour = -1;
        public double meanHours = -1;
        public double oneHourChance = -1;
    }

    public static Sample sample(Map<String, Object> dash, long nowMs) {
        if (dash == null || nowMs <= 0) return null;
        Map<String, Object> progress = SimpleJson.asMap(dash.get("monsterLevelProgress"));
        String character = SimpleJson.str(dash, "characterId", "");
        if (progress == null || character.isEmpty()) return null;
        Sample s = new Sample();
        s.character = character;
        s.biome = SimpleJson.str(dash, "currentBiomeId", "");
        s.selected = SimpleJson.intVal(progress, "selected_monster_level", SimpleJson.intVal(dash, "selectedMonsterLevel", -1));
        s.monsterLevel = SimpleJson.intVal(progress, "monster_level", -1);
        s.effective = SimpleJson.intVal(progress, "effective_fighting_level", -1);
        s.kills = SimpleJson.num(progress, "kills", -1);
        s.timeMs = nowMs;
        if (s.selected < 0 || s.monsterLevel < 0 || s.effective < 0 || !Double.isFinite(s.kills) || s.kills < 0) return null;
        return s;
    }

    public static boolean sameCounter(Sample a, Sample b) {
        return a != null && b != null && a.character.equals(b.character) && a.biome.equals(b.biome)
                && a.selected == b.selected && a.monsterLevel == b.monsterLevel && a.effective == b.effective
                && b.kills >= a.kills && b.timeMs > a.timeMs;
    }

    /** Returns -1 for incomparable or too-short/old samples, 0 for no progress. */
    public static double measureRate(Sample a, Sample b) {
        if (!sameCounter(a, b)) return -1;
        long elapsed = b.timeMs - a.timeMs;
        if (elapsed < MIN_INTERVAL_MS || elapsed > MAX_INTERVAL_MS) return -1;
        return (b.kills - a.kills) * 3_600_000.0 / elapsed;
    }

    public static State analyze(Map<String, Object> dash, Map<String, Object> config, double progressPerHour) {
        State state = new State();
        Map<String, Object> materials = dash == null ? null : SimpleJson.asMap(dash.get("craftingMaterials"));
        double count = SimpleJson.num(materials, "astral-catalyst", -1);
        if (Double.isFinite(count) && count >= 0 && count <= Long.MAX_VALUE) state.count = (long) count;
        Map<String, Object> progress = dash == null ? null : SimpleJson.asMap(dash.get("monsterLevelProgress"));
        state.currentLevel = SimpleJson.intVal(progress, "effective_fighting_level", 0);
        Map<String, Object> itemization = config == null ? null : SimpleJson.asMap(config.get("itemization"));
        Map<String, Object> enhancement = itemization == null ? null : SimpleJson.asMap(itemization.get("drop_bonus_enhancement"));
        Map<String, Object> catalyst = enhancement == null ? null : SimpleJson.asMap(enhancement.get("catalyst"));
        if (catalyst == null) return state;
        double chance = SimpleJson.num(catalyst, "drop_chance", -1);
        if (!Double.isFinite(chance) || chance < 0 || chance > 1) return state;
        state.configKnown = true;
        state.enabled = SimpleJson.bool(enhancement, "enabled", true);
        state.baseChance = chance;
        state.minLevel = SimpleJson.intVal(catalyst, "min_monster_level", 0);
        state.pityKills = SimpleJson.intVal(catalyst, "pity_kills", 0);
        if (Double.isFinite(progressPerHour) && progressPerHour > 0) state.progressPerHour = progressPerHour;
        if (state.enabled && state.currentLevel > 0 && state.minLevel > 0
                && state.currentLevel >= state.minLevel && chance > 0 && state.progressPerHour > 0) {
            state.meanHours = 1.0 / (chance * state.progressPerHour);
            state.oneHourChance = chance >= 1 ? 1 : -Math.expm1(state.progressPerHour * Math.log1p(-chance));
        }
        return state;
    }

    public static void recordSync(Context ctx, Map<String, Object> dash, long nowMs) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp == null) return;
        Sample current = sample(dash, nowMs);
        Sample previous = readSample(sp);
        if (current == null) {
            clear(ctx);
            return;
        }
        if (!sameCounter(previous, current) || current.timeMs - previous.timeMs > MAX_INTERVAL_MS) {
            saveSample(sp, current, -1, 0);
            return;
        }
        if (current.timeMs - previous.timeMs < MIN_INTERVAL_MS) return;
        double rate = measureRate(previous, current);
        saveSample(sp, current, rate, nowMs);
    }

    public static double getRate(Context ctx, long nowMs) {
        if (ctx == null) return -1;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp == null) return -1;
        long time = sp.getLong("rate_time", 0);
        if (time <= 0 || nowMs < time || nowMs - time > RATE_MAX_AGE_MS) return -1;
        try { return Double.parseDouble(sp.getString("rate", "-1")); }
        catch (Exception ignored) { return -1; }
    }

    public static void clear(Context ctx) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp != null) sp.edit().putLong("sample_time", 0).putLong("rate_time", 0).apply();
    }

    private static Sample readSample(SharedPreferences sp) {
        long time = sp.getLong("sample_time", 0);
        if (time <= 0) return null;
        Sample s = new Sample();
        s.timeMs = time;
        s.character = sp.getString("character", "");
        s.biome = sp.getString("biome", "");
        s.selected = sp.getInt("selected", -1);
        s.monsterLevel = sp.getInt("monster", -1);
        s.effective = sp.getInt("effective", -1);
        try { s.kills = Double.parseDouble(sp.getString("kills", "-1")); }
        catch (Exception ignored) { return null; }
        return s;
    }

    private static void saveSample(SharedPreferences sp, Sample s, double rate, long rateTime) {
        sp.edit().putLong("sample_time", s.timeMs).putString("character", s.character)
                .putString("biome", s.biome).putInt("selected", s.selected)
                .putInt("monster", s.monsterLevel).putInt("effective", s.effective)
                .putString("kills", String.valueOf(s.kills)).putString("rate", String.valueOf(rate))
                .putLong("rate_time", rateTime).apply();
    }
}
