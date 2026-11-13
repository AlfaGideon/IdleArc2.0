package com.idlearc.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Conservative planning guidance: the Harmony damage split and the game formula are unknown. */
public final class StanceAdvisor {
    private StanceAdvisor() {}
    public static final int UNKNOWN = 0, ATTACK_DOMINANT = 1, PET_DOMINANT = 2, BALANCED = 3;

    public static String stance(Map<String, Object> dash) {
        String s = SimpleJson.str(dash, "activeCombatStance", "").trim().toLowerCase(Locale.ROOT);
        return s.equals("harmony") || s.equals("aggressive") || s.equals("beastmaster") || s.equals("bulwark") ? s : "";
    }
    public static String scenarioName(int scenario) {
        switch (scenario) {
            case ATTACK_DOMINANT: return "предположение: преобладает атака";
            case PET_DOMINANT: return "предположение: преобладает питомец";
            case BALANCED: return "предположение: близко к балансу";
            default: return "не задано";
        }
    }
    public static String target(String stance, int scenario) {
        if ("aggressive".equals(stance)) return "attack";
        if ("beastmaster".equals(stance)) return "pet";
        if ("harmony".equals(stance) && scenario == ATTACK_DOMINANT) return "pet";
        if ("harmony".equals(stance) && scenario == PET_DOMINANT) return "attack";
        return "";
    }
    public static List<String> advice(Map<String, Object> dash, int scenario) {
        List<String> lines = new ArrayList<>();
        String stance = stance(dash);
        if (stance.isEmpty()) {
            lines.add("Стойка неизвестна: обнови данные или проверь её в игре. Не подменяем её предполагаемой стойкой.");
            return lines;
        }
        lines.add("Активная стойка по последнему снимку: " + RuGameDictionary.stanceRu(stance) + ".");
        if ("harmony".equals(stance)) {
            HarmonyInfo h = HarmonyInfo.fromDashboard(dash);
            if (Double.isFinite(h.maximumBonusPct)) {
                lines.add("Потолок бонуса при 50/50: +" + number(h.maximumBonusPct) + "% (" + HarmonyInfo.formatMultiplier(h.maximumBonusPct) + ")"
                        + (h.monsterLevel > 0 ? " · ML " + h.monsterLevel : "") + ". Он зависит от прогресса и не фиксирован.");
            } else lines.add("Потолок бонуса Гармонии в снимке отсутствует.");
            if (Double.isFinite(h.heroBonusPct) || Double.isFinite(h.petBonusPct)) {
                lines.add("Текущий бонус стойки: атака " + bonus(h.heroBonusPct) + " · питомец " + bonus(h.petBonusPct) + ". Он уже включён в урон игры.");
            }
            if (Double.isFinite(h.heroBonusPct) && Double.isFinite(h.petBonusPct)
                    && Double.isFinite(h.maximumBonusPct) && h.maximumBonusPct > 0
                    && Math.abs(h.heroBonusPct - h.petBonusPct) < 0.01
                    && h.heroBonusPct <= h.maximumBonusPct) {
                lines.add("Получено " + number(100.0 * (h.heroBonusPct / h.maximumBonusPct))
                        + "% от потолка бонуса. Это не соотношение урона и не определяет, какая сторона сильнее.");
            }
            lines.add("Соотношение урона и направление перекоса API здесь не подтверждает. Выбранный ниже сценарий — только предположение, не измерение.");
            lines.add("Сценарий: " + scenarioName(scenario) + ". После каждого изменения сравни бонус и реальный бой на одном ML; не обязательно максимум Гармонии даёт максимум DPS.");
        } else if ("aggressive".equals(stance)) {
            lines.add("Приоритет сравнения: атака героя. Не считай это доказательством, что атака выгоднее питомца для твоей сборки.");
        } else if ("beastmaster".equals(stance)) {
            lines.add("Приоритет сравнения: урон питомца. Проверяй итоговый урон и не игнорируй полезные общие характеристики.");
        } else {
            lines.add("Оплот усиливает возмездие и отключает уклонение. Сравни защиту и возмездие с другими стойками на том же противнике.");
        }
        String target = target(stance, scenario);
        if (target.equals("pet")) {
            lines.add("Предметы и слоты: сравни варианты с «Компаньона» / уроном питомца; учитывай собственные характеристики вещи и стоимость перековки.");
            lines.add("Руны и дерево: сравни узлы и вторичные статы урона питомца с атакой. Это гипотеза, а не гарантия сдвига баланса.");
        } else if (target.equals("attack")) {
            lines.add("Предметы и слоты: сравни варианты со «Свирепостью» / уроном атаки; учитывай собственные характеристики вещи и стоимость перековки.");
            lines.add("Руны и дерево: сравни узлы и вторичные статы атаки с уроном питомца. Это гипотеза, а не гарантия сдвига баланса.");
        } else if (stance.equals("harmony")) {
            lines.add("Предметы, руны и дерево: сравни оба направления — атаку и питомца. Без проверенного перекоса нельзя выбрать слабую сторону.");
        } else {
            lines.add("Предметы и руны: сравни защиту, здоровье и возмездие; в дереве отдельно проверь защитные узлы.");
        }
        if (stance.equals("harmony") || stance.equals("aggressive") || stance.equals("beastmaster")) {
            Map<String,Object> bs = SimpleJson.asMap(dash.get("bonusStats"));
            appendPair(lines, bs, "runeAttackDamagePercent", "runePetDamagePercent", "Бонусы рун по API");
            appendPair(lines, bs, "treeAttackDamagePercent", "treePetDamagePercent", "Бонусы дерева по API");
            List<Object> items = dash == null ? null : SimpleJson.asList(dash.get("characterEquippedItems"));
            List<String> selected = new ArrayList<>();
            if (items != null) for (Object o : items) {
                Map<String,Object> item = SimpleJson.asMap(o);
                String type = SimpleJson.str(item, "drop_bonus_type", "").toLowerCase(Locale.ROOT);
                if (!type.equals("fierce") && !type.equals("companions")) continue;
                String slot = SimpleJson.str(item, "slot", SimpleJson.str(item, "type", "слот"));
                String name = SimpleJson.str(item, "name", "");
                String text = RuGameDictionary.humanizeSlug(slot) + (name.isEmpty() ? "" : " · " + name)
                        + " — " + RuGameDictionary.statName(type);
                selected.add(text);
                if (selected.size() == 4) break;
            }
            if (!selected.isEmpty()) lines.add("Надетые варианты для сравнения: " + join(selected) + ".");
        }
        lines.add("Меняй в игре один параметр за раз; рекомендации не меняют игру и не рассчитывают итог перековки или DPS.");
        return lines;
    }
    private static void appendPair(List<String> lines, Map<String,Object> bs, String a, String p, String label) {
        double attack = SimpleJson.num(bs, a, Double.NaN), pet = SimpleJson.num(bs, p, Double.NaN);
        if (Double.isFinite(attack) || Double.isFinite(pet))
            lines.add(label + ": атака " + bonus(attack) + " · питомец " + bonus(pet) + ". Это бонусы источника, не баланс урона.");
    }
    private static String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            if (text.length() > 0) text.append("; ");
            text.append(value);
        }
        return text.toString();
    }
    private static String number(double v) { return String.format(Locale.US, "%.1f", v); }
    private static String bonus(double v) { return Double.isFinite(v) && v >= 0 ? "+" + number(v) + "%" : "—"; }
}
