package com.idlearc.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Qualitative Bulwark guidance; no claim of DPS gain or a best reforge. */
public final class RetaliationGuide {
    private RetaliationGuide() {}

    public static boolean active(Map<String, Object> dash) {
        return "bulwark".equals(StanceAdvisor.stance(dash));
    }

    public static List<String> advice(Map<String, Object> dash, int cls, String mode) {
        List<String> lines = new ArrayList<>();
        if (!active(dash)) return lines;
        lines.add("Возмездие зависит от защиты и Reflecting; заблокированные атаки усиливают ответный урон. Оплот отключает уклонение. Официальные заметки 1.3.1.");
        lines.add("Экипировка: сравни бонусы Reflecting (возмездие), Armored (защита), здоровье и снижение урона. Не меняй все вещи сразу: учитывай потерю атаки и выживаемости.");
        lines.add("Обычные аффиксы: сравни локальную защиту брони, защиту % бижутерии, здоровье и восстановление; уклонение не действует в Оплоте. Доступность зависит от слота.");
        lines.add("Питомцы и руны: сравни возмездие, защиту и здоровье по доступным бонусам. Не считаем их универсально лучшими.");
        if (cls == 1) lines.add("Воин: проверь по описанию в игре Iron Constitution (защита %), Battle Recovery (защита и восстановление), Deflection (блок и возмездие), Counterstrike (ответ при блоке). Названия по неофициальной wiki; вложения и открытые ярусы неизвестны.");
        if ("FARM".equals(mode) || "LEVELING".equals(mode)) lines.add("Фарм/прокачка: сравни скорость убийств и добычу; без выживаемости бонусы добычи не помогут.");
        else if ("BOSS".equals(mode)) lines.add("Босс: сравни выживаемость и урон на одном боссе; здоровье за убийство во время одиночного боя может не сработать.");
        else lines.add("Пуш: сравни выживаемость и скорость на одном уровне монстров; не жертвуй нужным восстановлением.");
        lines.add("Это ориентиры, не персональный расчёт DPS, перековки или капов. Проверяй текущие игровые описания и стоимость.");
        return lines;
    }

    public static boolean relevant(AffixPositionOptimizer.AffixType t) {
        return t == AffixPositionOptimizer.AffixType.LOCAL_PERCENT_DEFENSE
                || t == AffixPositionOptimizer.AffixType.LOCAL_FLAT_DEFENSE
                || t == AffixPositionOptimizer.AffixType.DEFENSE_PERCENT
                || t == AffixPositionOptimizer.AffixType.DAMAGE_REDUCTION
                || t == AffixPositionOptimizer.AffixType.MAX_HEALTH
                || t == AffixPositionOptimizer.AffixType.STRENGTH
                || t == AffixPositionOptimizer.AffixType.LIFE_ON_HIT
                || t == AffixPositionOptimizer.AffixType.LIFE_ON_KILL;
    }

    /** Slot-pool alternatives of the same kind, excluding duplicates; not a ranking. */
    public static List<AffixPositionOptimizer.AffixType> alternatives(ApiDashboardEngine.ServerItemAudit item,
                                                                     ApiDashboardEngine.ServerPositionEval row) {
        List<AffixPositionOptimizer.AffixType> out = new ArrayList<>();
        if (item == null || item.gearSlot == null || item.positions == null || row == null || row.currentType == null || row.isLocked) return out;
        for (AffixPositionOptimizer.AffixType t : AffixPositionOptimizer.getCompatiblePool(item.gearSlot)) {
            if (!relevant(t) || t == row.currentType || t.kind != row.currentType.kind) continue;
            boolean alreadyPresent = false;
            for (ApiDashboardEngine.ServerPositionEval existing : item.positions) {
                if (existing != null && existing.currentType == t) alreadyPresent = true;
            }
            if (!alreadyPresent) out.add(t);
        }
        return out;
    }
}
