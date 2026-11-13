package com.idlearc.companion;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

/** A comparison of sheet hit averages, not the game's actual Harmony balance or DPS. */
public final class HarmonyBalance {
    private static final MathContext MC = MathContext.DECIMAL128;
    public final BigDecimal attackAverage, petAverage, attackShare, petShare;
    private HarmonyBalance(BigDecimal attack, BigDecimal pet) {
        attackAverage = attack; petAverage = pet;
        BigDecimal total = attack.add(pet, MC);
        attackShare = attack.divide(total, MC).multiply(BigDecimal.valueOf(100), MC);
        petShare = pet.divide(total, MC).multiply(BigDecimal.valueOf(100), MC);
    }
    private static BigDecimal value(Map<String,Object> m, String numeric, String exact) {
        if (m == null) return null;
        Object v = m.get(exact);
        if (!(v instanceof String) || ((String) v).trim().isEmpty()) v = m.get(numeric);
        if (!(v instanceof String) && !(v instanceof Number)) return null;
        try {
            BigDecimal n = new BigDecimal(v.toString().trim());
            return n.signum() >= 0 && n.precision() <= 1000 && Math.abs((long) n.scale()) <= 1000 ? n : null;
        } catch (RuntimeException ex) { return null; }
    }
    public static BigDecimal conversionAmount(Map<String,Object> d) {
        Map<String,Object> bonus = d == null ? null : SimpleJson.asMap(d.get("bonusStats"));
        java.util.List<Object> rows = bonus == null ? null : SimpleJson.asList(bonus.get("petDamageFlatBreakdown"));
        if (rows == null) return null;
        for (Object row : rows) {
            Map<String,Object> part = SimpleJson.asMap(row);
            if ("conversion".equalsIgnoreCase(SimpleJson.str(part, "key", "")))
                return value(part, "amount", "amountStr");
        }
        return null;
    }
    public static HarmonyBalance fromDashboard(Map<String,Object> d) {
        if (!"harmony".equals(StanceAdvisor.stance(d))) return null;
        Map<String,Object> attack = SimpleJson.asMap(d.get("attackDamage"));
        Map<String,Object> bonus = SimpleJson.asMap(d.get("bonusStats"));
        BigDecimal min = value(attack, "min", "minStr"), max = value(attack, "max", "maxStr");
        BigDecimal pet = value(bonus, "petDamageFinalAvg", "petDamageFinalAvgStr");
        if (min == null || max == null || pet == null || min.compareTo(max) > 0 || pet.signum() <= 0) return null;
        BigDecimal avg = min.add(max, MC).divide(BigDecimal.valueOf(2), MC);
        if (avg.signum() <= 0) return null;
        return new HarmonyBalance(avg, pet);
    }
    /** Wide neutral zone is deliberate: a sheet comparison omits real fight mechanics. */
    public int estimatedScenario() {
        if (attackShare.compareTo(BigDecimal.valueOf(55)) > 0) return StanceAdvisor.ATTACK_DOMINANT;
        if (attackShare.compareTo(BigDecimal.valueOf(45)) < 0) return StanceAdvisor.PET_DOMINANT;
        return StanceAdvisor.BALANCED;
    }
}
