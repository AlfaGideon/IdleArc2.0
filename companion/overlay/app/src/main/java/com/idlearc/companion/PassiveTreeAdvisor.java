package com.idlearc.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Planning guide for the public, shared skillTree config. It never modifies the game. */
public final class PassiveTreeAdvisor {
    private PassiveTreeAdvisor() {}

    public static int detectedClass(Map<String, Object> dash, int override) {
        if (override >= 1 && override <= 5) return override;
        int id = SimpleJson.intVal(dash, "characterClassId", 0);
        if (id >= 1 && id <= 5) return id;
        String name = SimpleJson.str(dash, "characterClassName", "").trim().toLowerCase(Locale.ROOT);
        if (name.equals("warrior") || name.equals("воин")) return 1;
        if (name.equals("archer") || name.equals("лучник")) return 2;
        if (name.equals("mage") || name.equals("маг")) return 3;
        if (name.equals("rogue") || name.equals("разбойник")) return 4;
        if (name.equals("druid") || name.equals("друид")) return 5;
        return 0;
    }

    public static int tierCount(Map<String, Object> config) {
        Map<String, Object> tree = config == null ? null : SimpleJson.asMap(config.get("skillTree"));
        List<Object> tiers = tree == null ? null : SimpleJson.asList(tree.get("tiers"));
        return tiers == null ? 0 : tiers.size();
    }

    // Relative preferences, not a DPS simulation. Alternatives within a row are compared only
    // by category, not by incomparable units or as a proof of mathematical optimality.
    static int priority(String stat, int cls, String mode) {
        boolean farm = "FARM".equals(mode), leveling = "LEVELING".equals(mode);
        if (stat.equals("exp_gain")) return leveling ? 100 : farm ? 88 : 15;
        if (stat.equals("item_drop_chance") || stat.equals("material_drop_chance")) return farm ? 100 : 10;
        if (stat.equals("gold_gain")) return farm ? 90 : leveling ? 55 : 10;
        if (stat.equals("pet_damage_percent")) return cls == 5 ? 95 : 55;
        if (stat.equals("attack_damage_percent")) return cls == 5 ? 80 : 95;
        if (stat.equals("pet_damage_flat")) return cls == 5 ? 95 : 55;
        if (stat.equals("attack_damage_flat")) return cls == 5 ? 80 : 95;
        if (stat.equals("crit_chance")) return 90;
        if (stat.equals("double_hit_chance")) return cls == 5 || cls == 1 || cls == 4 ? 92 : 85;
        if (stat.equals("double_damage_chance")) return cls == 3 ? 92 : 84;
        if (stat.equals("crit_damage")) return cls == 2 ? 90 : 78;
        if (stat.equals("strength")) return cls == 1 ? 90 : 45;
        if (stat.equals("dexterity")) return cls == 2 || cls == 4 ? 90 : 60;
        if (stat.equals("intelligence")) return cls == 3 || cls == 5 ? 90 : 70;
        if (stat.equals("max_hp_percent")) return 75;
        if (stat.equals("defense_percent")) return 72;
        if (stat.equals("max_hp_flat")) return 60;
        if (stat.equals("defense")) return 58;
        if (stat.equals("life_on_hit")) return 68;
        if (stat.equals("life_on_kill")) return "BOSS".equals(mode) ? 20 : 65;
        if (stat.equals("dodge_chance")) return 65;
        return 0;
    }

    public static List<String> recommendations(Map<String, Object> config, int tierIndex, int cls, String mode) {
        return recommendations(config, tierIndex, cls, mode, "", StanceAdvisor.UNKNOWN);
    }

    public static List<String> recommendations(Map<String, Object> config, int tierIndex, int cls, String mode,
                                               String stance, int scenario) {
        List<String> result = new ArrayList<>();
        if (cls < 1 || cls > 5) { result.add("Выбери класс, чтобы увидеть ориентиры."); return result; }
        Map<String, Object> tree = config == null ? null : SimpleJson.asMap(config.get("skillTree"));
        List<Object> tiers = tree == null ? null : SimpleJson.asList(tree.get("tiers"));
        List<Object> rows = tree == null ? null : SimpleJson.asList(tree.get("rows"));
        if (tiers == null || rows == null || tierIndex < 0 || tierIndex >= tiers.size()) {
            result.add("Данные дерева недоступны. Дождись загрузки публичной конфигурации игры."); return result;
        }
        Map<String, Object> tier = SimpleJson.asMap(tiers.get(tierIndex));
        int start = SimpleJson.intVal(tier, "start_row", -1), end = SimpleJson.intVal(tier, "end_row", -1);
        if (start < 0 || end < start || end >= rows.size() || end - start > 100) {
            result.add("Конфигурация яруса неполная; рекомендации недоступны."); return result;
        }
        for (int i = start; i <= end; i++) {
            Map<String, Object> row = SimpleJson.asMap(rows.get(i));
            List<Object> nodes = row == null ? null : SimpleJson.asList(row.get("nodes"));
            Map<String, Object> best = null, attackNode = null, petNode = null;
            int bestPriority = 0;
            if (nodes != null) for (Object obj : nodes) {
                Map<String, Object> node = SimpleJson.asMap(obj);
                String stat = SimpleJson.str(node, "stat", "");
                double value = SimpleJson.num(node, "per_point", Double.NaN);
                int max = SimpleJson.intVal(node, "max_points", 0);
                int priority = priority(stat, cls, mode);
                if (max <= 0 || !Double.isFinite(value) || value <= 0) continue;
                boolean attack = stat.equals("attack_damage_flat") || stat.equals("attack_damage_percent");
                boolean pet = stat.equals("pet_damage_flat") || stat.equals("pet_damage_percent");
                if (attack) attackNode = node;
                if (pet) petNode = node;
                String target = StanceAdvisor.target(stance, scenario);
                if (attack && target.equals("attack") || pet && target.equals("pet")) priority = 100;
                if ("bulwark".equals(stance) && stat.equals("dodge_chance")) priority = 0;
                if ("bulwark".equals(stance) && (stat.equals("defense") || stat.equals("defense_percent"))) priority = 95;
                if (priority > bestPriority) {
                    best = node; bestPriority = priority;
                }
            }
            if ("harmony".equals(stance) && StanceAdvisor.target(stance, scenario).isEmpty()
                    && attackNode != null && petNode != null) {
                result.add("Строка " + (i + 1) + ": сравни " + formatNode(attackNode) + " или " + formatNode(petNode)
                        + " — слабая сторона неизвестна.");
                continue;
            }
            if (best == null) {
                result.add("Строка " + (i + 1) + ": нет известного ориентира — сравни узлы в игре.");
                continue;
            }
            result.add("Строка " + (i + 1) + (SimpleJson.bool(row, "milestone", false) ? " · веха" : "") + ": " + formatNode(best));
        }
        return result;
    }

    private static String formatNode(Map<String, Object> best) {
        String stat = SimpleJson.str(best, "stat", "");
        double amount = SimpleJson.num(best, "per_point", 0);
        int max = SimpleJson.intVal(best, "max_points", 0);
        boolean pct = stat.endsWith("_percent") || stat.endsWith("_chance") || stat.equals("crit_damage") || stat.equals("exp_gain") || stat.equals("gold_gain");
        return RuGameDictionary.statName(stat) + " (" + stat + ") +"
                + (amount == Math.rint(amount) ? Long.toString((long) amount) : Double.toString(amount))
                + (pct ? "%" : "") + "/очко · максимум узла " + max;
    }
}
