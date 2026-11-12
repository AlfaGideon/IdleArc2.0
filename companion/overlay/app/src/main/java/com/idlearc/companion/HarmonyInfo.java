package com.idlearc.companion;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads Harmony values already calculated by the game; does not reconstruct damage or DPS. */
public final class HarmonyInfo {
    public final boolean active;
    public final double maximumBonusPct;
    public final double heroBonusPct;
    public final double petBonusPct;
    public final int monsterLevel;

    private HarmonyInfo(boolean active, double maximumBonusPct, double heroBonusPct,
                        double petBonusPct, int monsterLevel) {
        this.active = active;
        this.maximumBonusPct = maximumBonusPct;
        this.heroBonusPct = heroBonusPct;
        this.petBonusPct = petBonusPct;
        this.monsterLevel = monsterLevel;
    }

    public static HarmonyInfo fromDashboard(Map<String, Object> dash) {
        boolean active = "harmony".equalsIgnoreCase(SimpleJson.str(dash, "activeCombatStance", ""));
        // The API field was verified under Harmony; do not assume its meaning in other stances.
        double cap = active ? nonnegative(dash, "activeCombatStanceHarmonyMax") : Double.NaN;
        Map<String, Object> attack = dash == null ? null : SimpleJson.asMap(dash.get("attackDamageBreakdown"));
        Map<String, Object> bonus = dash == null ? null : SimpleJson.asMap(dash.get("bonusStats"));
        double hero = active ? stancePercent(attack, "multiplicative") : Double.NaN;
        double pet = active ? stancePercent(bonus, "petDamageBreakdown") : Double.NaN;
        Map<String, Object> progress = dash == null ? null : SimpleJson.asMap(dash.get("monsterLevelProgress"));
        int ml = SimpleJson.intVal(progress, "effective_fighting_level", 0);
        return new HarmonyInfo(active, cap, hero, pet, ml > 0 ? ml : 0);
    }

    private static double stancePercent(Map<String, Object> obj, String key) {
        List<Object> list = obj == null ? null : SimpleJson.asList(obj.get(key));
        if (list == null) return Double.NaN;
        for (Object item : list) {
            Map<String, Object> entry = SimpleJson.asMap(item);
            if ("stance".equalsIgnoreCase(SimpleJson.str(entry, "key", ""))) {
                return nonnegative(entry, "pct");
            }
        }
        return Double.NaN;
    }

    private static double nonnegative(Map<String, Object> obj, String key) {
        double n = SimpleJson.num(obj, key, Double.NaN);
        return Double.isFinite(n) && n >= 0 && Double.isFinite(1.0 + n / 100.0) ? n : Double.NaN;
    }

    public static String formatMultiplier(double pct) {
        if (!Double.isFinite(pct) || pct < 0) return "—";
        double factor = 1.0 + pct / 100.0;
        if (!Double.isFinite(factor)) return "—";
        return factor >= 1e9 ? String.format(Locale.US, "×%.3e", factor)
                : String.format(Locale.US, "×%.2f", factor);
    }
}
