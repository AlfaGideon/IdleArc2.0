package com.idlearc.companion;

import java.util.Locale;

/**
 * Полный русскоязычный словарь игровых терминов, характеристик, редкостей, материалов,
 * петов, талисманов и биомов IdleArc. Гарантирует 0% сырых JSON-ключей или snake_case в интерфейсе.
 */
public final class RuGameDictionary {

    private RuGameDictionary() {
    }

    /** Перевод любого идентификатора стата (камни, талисманы, петы, пассивки, имплиситы, аффиксы) */
    public static String statName(String rawKey) {
        if (rawKey == null || rawKey.trim().isEmpty()) return "Параметр";
        String k = rawKey.trim().toLowerCase(Locale.ROOT);
        switch (k) {
            case "pet_damage":
            case "pet_damage_pct":
            case "pet_damage_bonus":
            case "pet_damage_percent":
            case "companion_damage":
            case "companions":
                return "Урон питомцев";
            case "attack_pet_damage":
                return "Атака и урон питомцев";
            case "attack_damage":
            case "attack_damage_pct":
            case "attack_damage_percent":
            case "physical_damage":
            case "physical_damage_percent":
            case "added_damage":
            case "damage":
                return "Урон атаки";
            case "attack_power":
                return "Сила атаки";
            case "critical_strike_chance":
            case "crit_chance":
            case "critical_chance":
                return "Шанс крита";
            case "critical_damage":
            case "crit_damage":
                return "Критический урон";
            case "double_hit_chance":
            case "double_hit":
                return "Шанс двойного удара";
            case "double_damage_chance":
            case "double_damage":
                return "Шанс двойного урона";
            case "damage_reduction":
                return "Снижение урона";
            case "dodge_chance":
            case "dodge":
                return "Уклонение";
            case "block_chance":
            case "block":
                return "Шанс блока";
            case "max_health":
            case "max_health_pct":
            case "max_health_percent":
            case "health":
                return "Макс. здоровье";
            case "defense":
            case "defense_pct":
            case "defense_percent":
                return "Защита";
            case "increased_defense":
                return "Увеличенная защита";
            case "intelligence":
                return "Интеллект";
            case "increased_intelligence":
                return "Интеллект (%)";
            case "dexterity":
            case "agility":
                return "Ловкость";
            case "increased_dexterity":
                return "Ловкость (%)";
            case "strength":
                return "Сила";
            case "increased_strength":
                return "Сила (%)";
            case "all_skills":
            case "all_class_skills":
                return "Все классовые навыки";
            case "pet_mastery_level":
            case "pet_mastery":
                return "Мастерство питомцев";
            case "extra_kill_chance":
            case "extra_kill":
                return "Доп. убийство";
            case "boss_damage":
                return "Урон по боссам";
            case "life_on_hit":
                return "Здоровье за удар";
            case "life_on_kill":
                return "Здоровье за убийство";
            case "bloodthirst":
            case "life_on_hit_and_kill":
                return "Кровожадность (отхил)";
            case "retaliation":
            case "retaliation_percent":
                return "Возмездие";
            case "thorns":
            case "thorns_damage":
                return "Шипы";
            case "exp_gain":
            case "xp_rate_pct":
            case "experience_gain":
                return "Получение опыта";
            case "gold_gain":
            case "gold_rate_pct":
                return "Получение золота";
            case "item_quantity":
            case "item_drop_pct":
            case "item_find":
            case "item_drops":
                return "Дроп предметов";
            case "material_quantity":
            case "material_drop_pct":
            case "material_drops":
                return "Дроп материалов";
            case "rune_drops":
            case "rune_drop_pct":
                return "Дроп рун";
            case "ruby_drops":
            case "ruby_drop_pct":
                return "Дроп рубинов";
            case "pet_egg_drop_pct":
            case "egg_drops":
                return "Дроп яиц питомцев";
            case "gem_drop_pct":
            case "gem_drops":
                return "Дроп самоцветов";
            case "lucky_chance":
            case "lucky_tier":
                return "Шанс удачного тира";
            default:
                return humanizeSlug(rawKey);
        }
    }

    /** Определяет, является ли серверный ключ стата процентным (или плоским числом) */
    public static boolean isPercentStat(String rawKey, String explicitSuffix) {
        if (explicitSuffix != null) {
            if (explicitSuffix.contains("%")) return true;
            if (explicitSuffix.isEmpty()) {
                String k0 = rawKey != null ? rawKey.toLowerCase(Locale.ROOT) : "";
                if ("intelligence".equals(k0) || "dexterity".equals(k0) || "strength".equals(k0)
                        || "life_on_hit".equals(k0) || "life_on_kill".equals(k0)
                        || "all_skills".equals(k0) || "pet_mastery_level".equals(k0)
                        || "attack_power".equals(k0)) {
                    return false;
                }
            }
        }
        if (rawKey == null) return false;
        String k = rawKey.toLowerCase(Locale.ROOT);
        if ("intelligence".equals(k) || "dexterity".equals(k) || "strength".equals(k)
                || "life_on_hit".equals(k) || "life_on_kill".equals(k)
                || "all_skills".equals(k) || "pet_mastery_level".equals(k)
                || "attack_power".equals(k)) {
            return false;
        }
        if (k.contains("pct") || k.contains("percent") || k.contains("chance")
                || k.contains("bonus") || k.contains("gain") || k.contains("drops")
                || k.contains("quantity") || k.contains("reduction") || k.contains("damage")
                || k.contains("dodge") || k.contains("companions") || k.contains("increased_")) {
            return true;
        }
        return false;
    }

    /** Красивое форматирование числа стата с нужным знаком % или без него */
    public static String formatStatValue(String rawKey, double val, String explicitSuffix) {
        boolean pct = isPercentStat(rawKey, explicitSuffix);
        String numStr;
        if (Math.abs(val - Math.rint(val)) < 0.05) {
            numStr = String.valueOf(Math.round(val));
        } else {
            numStr = String.format(Locale.US, "%.2f", val).replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return "+" + numStr + (pct ? "%" : "");
    }

    /** Перевод редкости самоцветов */
    public static String gemRarityRu(String slugOrNum) {
        if (slugOrNum == null) return "Обычный";
        String s = slugOrNum.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "1":
            case "chipped": return "Надколотый";
            case "2":
            case "flawed": return "Мутный";
            case "3":
            case "normal":
            case "regular": return "Обычный";
            case "4":
            case "flawless": return "Безупречный";
            case "5":
            case "perfect": return "Совершенный";
            case "6":
            case "radiant":
            case "imperial": return "Сияющий";
            default: return humanizeSlug(slugOrNum);
        }
    }

    /** Перевод редкости предметов / питомцев / надписей */
    public static String itemOrPetRarityRu(String r) {
        if (r == null || r.isEmpty()) return "Обычный";
        String s = r.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "common": return "Обычный";
            case "uncommon": return "Необычный";
            case "rare": return "Редкий";
            case "epic":
            case "greater": return "Эпический";
            case "legendary": return "Легендарный";
            case "mythic":
            case "mythical": return "Мифический";
            case "infernal": return "Инфернальный";
            case "celestial": return "Небесный";
            case "perfect": return "Совершенный";
            case "ancient": return "Древний";
            default: return humanizeSlug(r);
        }
    }

    /** Перевод названия семейства камня */
    public static String gemFamilyRu(String slug) {
        if (slug == null) return "Самоцвет";
        switch (slug.trim().toLowerCase(Locale.ROOT)) {
            case "jade": return "🟢 Нефрит";
            case "lapis": return "🔵 Лазурит";
            case "garnet": return "🔴 Гранат";
            case "amber": return "🟡 Янтарь";
            default: return humanizeSlug(slug);
        }
    }

    /** Перевод тира снаряжения */
    public static String tierNameRu(int tier, String serverTierName) {
        if (serverTierName != null && !serverTierName.isEmpty()) {
            String low = serverTierName.toLowerCase(Locale.ROOT);
            if (low.contains("infernal")) return "Инфернальный (А" + tier + ")";
            if (low.contains("celestial")) return "Небесный (А" + tier + ")";
            if (low.contains("mythic")) return "Мифический (А" + tier + ")";
            if (low.contains("ancient")) return "Древний (А" + tier + ")";
        }
        return "Тир А" + tier;
    }

    /** Перевод боевой стойки */
    public static String stanceRu(String s) {
        if (s == null || s.isEmpty()) return "Стандартная";
        String low = s.toLowerCase(Locale.ROOT);
        if (low.contains("beast")) return "Повелитель зверей";
        if (low.contains("jugg")) return "Джаггернаут";
        if (low.contains("bers")) return "Берсерк";
        if (low.contains("loot") || low.contains("scav") || low.contains("greed")) return "Искатель добычи";
        if (low.contains("balanc")) return "Баланс";
        return humanizeSlug(s);
    }

    /** Перевод названий талисманов */
    public static String talismanNameRu(String slug, String fallbackName) {
        if (slug != null) {
            switch (slug.toLowerCase(Locale.ROOT)) {
                case "spirit": return "Талисман Духа";
                case "war":
                case "wrath": return "Талисман Войны";
                case "fortune":
                case "greed": return "Талисман Удачи";
                case "guardian":
                case "bastion": return "Талисман Стража";
                case "wisdom": return "Талисман Мудрости";
                case "shadow": return "Талисман Тени";
            }
        }
        if (fallbackName != null && !fallbackName.isEmpty()) {
            String low = fallbackName.toLowerCase(Locale.ROOT);
            if (low.contains("spirit")) return "Талисман Духа";
            if (low.contains("fortune")) return "Талисман Удачи";
            if (low.contains("wrath") || low.contains("war")) return "Талисман Войны";
            if (low.contains("guardian")) return "Талисман Стража";
            return fallbackName;
        }
        return "Талисман";
    }

    /** Перевод названий материалов кузницы */
    public static String materialNameRu(String slug) {
        if (slug == null) return "Материал";
        String s = slug.toLowerCase(Locale.ROOT).replace('-', '_');
        if (s.contains("celestial_fragment")) return "Небесный фрагмент";
        if (s.contains("infernal_fragment")) return "Инфернальный фрагмент";
        if (s.contains("mythic_fragment")) return "Мифический фрагмент";
        if (s.contains("legendary_fragment")) return "Легендарный фрагмент";
        if (s.contains("celestial_essence")) return "Небесная эссенция";
        if (s.contains("infernal_essence")) return "Инфернальная эссенция";
        if (s.contains("mythic_essence")) return "Мифическая эссенция";
        if (s.contains("awakening") || s.contains("awaken")) return "Камень Пробуждения";
        if (s.contains("gild")) return "Позолота";
        if (s.contains("scroll")) return "Свиток";
        if (s.contains("crystal")) return "Кристалл";
        if (s.contains("dust")) return "Пыль самоцветов";
        if (s.contains("shard")) return "Осколки";
        return humanizeSlug(slug);
    }

    /** Перевод названий 16 корзин урона питомца */
    public static String damageBucketRu(String key, String fallbackLabel) {
        return petBucketRu(key != null && !key.isEmpty() ? key : fallbackLabel);
    }

    public static String tierTitleRu(int tier) {
        switch (tier) {
            case 7: return "Вознесённый (T7)";
            case 6: return "Мифический (T6)";
            case 5: return "Божественный (T5)";
            case 4: return "Легендарный (T4)";
            case 3: return "Эпический (T3)";
            case 2: return "Редкий (T2)";
            default: return "Тир " + tier;
        }
    }

    public static String tierColorHex(int tier) {
        switch (tier) {
            case 7: return "#00E5FF";
            case 6: return "#FF6E40";
            case 5: return "#FFD54F";
            case 4: return "#FFA726";
            case 3: return "#BA68C8";
            default: return "#64B5F6";
        }
    }

    public static String gemFamilyColorHex(String slug) {
        if (slug == null) return "#81D4FA";
        switch (slug.trim().toLowerCase(Locale.ROOT)) {
            case "jade": return "#69F0AE";
            case "lapis": return "#4FC3F7";
            case "garnet": return "#FF8A80";
            case "amber": return "#FFD54F";
            default: return "#81D4FA";
        }
    }

    public static String rarityColorHex(String rarityRaw) {
        if (rarityRaw == null) return "#90CAF9";
        String r = rarityRaw.trim().toLowerCase(Locale.ROOT);
        if (r.contains("ascend") || "7".equals(r)) return "#00E5FF";
        if (r.contains("myth") || "6".equals(r)) return "#FF6E40";
        if (r.contains("divin") || r.contains("perfect") || "5".equals(r)) return "#FFD54F";
        if (r.contains("legend") || r.contains("flawless") || "4".equals(r)) return "#FFA726";
        if (r.contains("epic") || "3".equals(r)) return "#CE93D8";
        if (r.contains("rare") || "2".equals(r)) return "#4FC3F7";
        return "#A5D6A7";
    }

    /** Перевод названий 16 корзин урона питомца */
    public static String petBucketRu(String key) {
        if (key == null) return "Источник";
        switch (key) {
            case "stats": return "Снаряжение и статы";
            case "talismanInscription": return "Надписи талисманов";
            case "dexterity": return "От Ловкости героя";
            case "intelligence": return "От Интеллекта героя";
            case "drop": return "Имплиситы урона пета на вещах";
            case "petShard": return "Осколки и бонусы петов";
            case "classSkills": return "Классовые навыки Друида";
            case "tree": return "Глобальное дерево";
            case "rune": return "Руны";
            case "guild": return "Бонусы Гильдии";
            case "echo": return "Эхо";
            case "gearSet": return "Бонус комплекта (Сета)";
            case "gem": return "Самоцветы в оправах";
            case "boost": return "Временные бусты";
            case "petMastery": return "Мастерство питомцев";
            case "stance": return "Боевая стойка";
            case "conversion": return "Конверсия атаки Друида (75%)";
            default: return humanizeSlug(key);
        }
    }

    /** Нормализует серверный roll_quality (в API игры он часто 0.0..1.0, например 1 = 100%, 0.78 = 78%) */
    public static double normalizeRollQualityPct(double rawQuality, double val, double rMin, double rMax) {
        if (rMax > rMin + 1e-6) {
            double calc = ((val - rMin) / (rMax - rMin)) * 100.0;
            return Math.max(0.0, Math.min(100.0, calc));
        }
        if (rawQuality <= 1.0001 && rawQuality >= 0.0) {
            return rawQuality * 100.0;
        }
        return Math.max(0.0, Math.min(100.0, rawQuality));
    }

    /** Форматирование больших чисел (K, M, B, T, Qa) по-русски/читаемо */
    public static String fmtBig(double v) {
        double abs = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (abs >= 1e15) return sign + String.format(Locale.US, "%.2fQa", abs / 1e15);
        if (abs >= 1e12) return sign + String.format(Locale.US, "%.2fT", abs / 1e12);
        if (abs >= 1e9) return sign + String.format(Locale.US, "%.2fB", abs / 1e9);
        if (abs >= 1e6) return sign + String.format(Locale.US, "%.2fM", abs / 1e6);
        if (abs >= 1e3) return sign + String.format(Locale.US, "%.1fK", abs / 1e3);
        if (Math.abs(abs - Math.rint(abs)) < 0.05) {
            return sign + String.valueOf(Math.round(abs));
        }
        return sign + String.format(Locale.US, "%.1f", abs);
    }

    /** Универсальный гуманизатор неизвестных слагов (убирает snake_case/camelCase и переводит частые корни) */
    public static String humanizeSlug(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "—";
        String clean = raw.trim()
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("\\s+", " ")
                .trim();
        String low = clean.toLowerCase(Locale.ROOT);
        low = low.replace("pet", "питомца")
                 .replace("damage", "урон")
                 .replace("chance", "шанс")
                 .replace("bonus", "бонус")
                 .replace("drop", "дроп")
                 .replace("level", "уровень")
                 .replace("health", "здоровье")
                 .replace("defense", "защита")
                 .replace("attack", "атака")
                 .replace("gold", "золото")
                 .replace("ruby", "рубины")
                 .replace("rune", "руны")
                 .replace("gem", "самоцветы")
                 .replace("fragment", "фрагмент")
                 .replace("essence", "эссенция");
        if (low.isEmpty()) return clean;
        return Character.toUpperCase(low.charAt(0)) + low.substring(1);
    }
}
