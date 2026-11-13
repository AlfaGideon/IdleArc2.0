package com.idlearc.companion;

/** Candidate to inspect in one equipped item, not a predicted reforge or DPS result. */
public final class HarmonyAffixGuide {
    private HarmonyAffixGuide() {}
    public static String candidate(ApiDashboardEngine.ServerItemAudit item, int scenario) {
        if (item == null || item.gearSlot == null || item.positions == null || item.positions.isEmpty()) return "";
        AffixPositionOptimizer.AffixType target;
        if (scenario == StanceAdvisor.ATTACK_DOMINANT) target = AffixPositionOptimizer.AffixType.PET_DAMAGE;
        else if (scenario == StanceAdvisor.PET_DOMINANT) target = AffixPositionOptimizer.AffixType.ADDED_DAMAGE;
        else return "";
        if (!AffixPositionOptimizer.getCompatiblePool(item.gearSlot).contains(target)) return "";
        boolean unlocked = false;
        for (ApiDashboardEngine.ServerPositionEval p : item.positions) {
            if (p.currentType == target) return "";
            if (!p.isLocked) unlocked = true;
        }
        if (!unlocked) return "";
        return "Сценарий баланса: проверь в игре вариант «" + target.ruName + "» для этого слота. "
                + (target == AffixPositionOptimizer.AffixType.ADDED_DAMAGE
                   ? "Плоская атака может также усилить питомца через конверсию. " : "")
                + "Сохрани нужную защиту и проки; возможность, стоимость и результат перековки проверь вручную.";
    }
}
