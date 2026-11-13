package com.idlearc.companion;

/** Candidate to inspect in one equipped item, not a predicted reforge or DPS result. */
public final class HarmonyAffixGuide {
    private HarmonyAffixGuide() {}
    public static String candidate(ApiDashboardEngine.ServerItemAudit item, int scenario) {
        AffixPositionOptimizer.AffixType target = target(item, scenario);
        if (target == null) return "";
        return "Сценарий баланса: проверь в игре вариант «" + target.ruName + "» для этого слота. "
                + (target == AffixPositionOptimizer.AffixType.ADDED_DAMAGE
                   ? "Плоская атака может также усилить питомца через конверсию. " : "")
                + "Сохрани нужную защиту и проки; возможность, стоимость и результат перековки проверь вручную.";
    }
    public static boolean canCompareRow(AffixPositionOptimizer.AffixType target,
                                        ApiDashboardEngine.ServerPositionEval row) {
        return target != null && row != null && !row.isLocked;
    }

    /** A type from the slot pool to compare, not a recommendation to remove a particular row. */
    public static AffixPositionOptimizer.AffixType target(ApiDashboardEngine.ServerItemAudit item, int scenario) {
        if (item == null || item.gearSlot == null || item.positions == null || item.positions.isEmpty()) return null;
        AffixPositionOptimizer.AffixType target;
        if (scenario == StanceAdvisor.ATTACK_DOMINANT) target = AffixPositionOptimizer.AffixType.PET_DAMAGE;
        else if (scenario == StanceAdvisor.PET_DOMINANT) target = AffixPositionOptimizer.AffixType.ADDED_DAMAGE;
        else return null;
        if (!AffixPositionOptimizer.getCompatiblePool(item.gearSlot).contains(target)) return null;
        boolean unlocked = false;
        for (ApiDashboardEngine.ServerPositionEval p : item.positions) {
            if (p.currentType == target) return null;
            if (!p.isLocked) unlocked = true;
        }
        return unlocked ? target : null;
    }

}
