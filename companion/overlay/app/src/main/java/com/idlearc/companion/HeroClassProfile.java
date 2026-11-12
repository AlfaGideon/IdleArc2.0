package com.idlearc.companion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Мультиклассовое ядро IdleArcHelper v4.0 для всех 5 классов игры:
 * 1 — Воин (Warrior), 2 — Лучник (Archer), 3 — Маг (Mage), 4 — Разбойник (Rogue), 5 — Друид (Druid).
 * Содержит точные механики скейлинга, лучшие бонусы дропа (drop_bonus_type), лучшие самоцветы
 * по слотам, спец-бонусы петов и аудит оверкапа классовых навыков для всех 5 классов.
 */
public final class HeroClassProfile {

    private HeroClassProfile() {
    }

    public enum HeroClass {
        WARRIOR(
                1, "warrior", "Воин", "⚔️", "#FF7043",
                "fierce", "attack_damage",
                "Стойка «Агрессия» (+25% атаки) для урона или «Оплот» (+25% возмездия, отключает уклонение) для щита/возврата урона",
                "Главный урон идёт от Атаки оружия, Силы (+0.75% физ. урона, +0.5% HP и +0.5% возмездия за 1 Силы) и Интеллекта. Ключевые проки: «Титанический удар» (×3 урона), «Неумолимый натиск» (доп. удар при двойном ударе) и мгновенное добивание (Казнь)."
        ),
        ARCHER(
                2, "archer", "Лучник", "🏹", "#FFD54F",
                "fierce", "attack_damage",
                "Стойка «Агрессия» (+25% атаки) или «Гармония» (в гибриде через ветку Зверолова — «Повелитель зверей»)",
                "Дальнобойный крит-урон и ловушки на % от макс. HP врага в начале боя. «Идеальный выстрел» удваивает крит. урон, а «Альфа-удар» удваивает одновременно и твой урон, и урон питомца! Скейлится от Интеллекта и Ловкости."
        ),
        MAGE(
                3, "mage", "Маг", "🔮", "#4FC3F7",
                "fierce", "attack_damage",
                "Стойка «Агрессия» (+25% атаки) — максимальный множитель чар и взрывов крита",
                "Взрывной кастер через Атаку и Интеллект (+0.38% урона атаки и +0.20% шанса двойного урона за 1 Интеллекта). «Магический каскад» превращает двойной урон в ТРОЙНОЙ (до 100%), а «Пироклазм» усиливает каждый крит до +100%!"
        ),
        ROGUE(
                4, "rogue", "Разбойник", "🗡️", "#CE93D8",
                "fierce", "attack_damage",
                "Стойка «Агрессия» (+25% атаки) — усиливает и прямые удары, и повторы Теневого эха",
                "Мастер серий ударов и «Теневого эха» (повтор атаки с шансом двойного каскада «Теневой каскад»), «Первого удара» (+30% на весь бой с боссом!) и уникального фарм-навыка «Чёрный рынок» (+шанс повышенного тира шмота). Скейлится от Интеллекта и Ловкости."
        ),
        DRUID(
                5, "druid", "Друид", "🌿", "#69F0AE",
                "companions", "pet_damage",
                "Стойка «Повелитель зверей» (+25% отдельного множителя урона питомца)",
                "Повелитель питомцев: 75% твоей Атаки переходит в плоскую базу урона пета, а все 16 корзин урона питомца перемножаются между собой. Главные статы — Урон питомцев %, Интеллект, Ловкость, Двойной удар и Двойной урон."
        );

        public final int id;
        public final String slug;
        public final String ruName;
        public final String icon;
        public final String colorHex;
        public final String bisCombatDropBonus; // "companions" (Друид) или "fierce" (Воин/Лучник/Маг/Разбойник)
        public final String bisPetSpecialBonus; // "pet_damage" или "attack_damage"
        public final String stanceAdvice;
        public final String mechanicGuide;

        HeroClass(int id, String slug, String ruName, String icon, String colorHex,
                  String bisCombatDropBonus, String bisPetSpecialBonus,
                  String stanceAdvice, String mechanicGuide) {
            this.id = id;
            this.slug = slug;
            this.ruName = ruName;
            this.icon = icon;
            this.colorHex = colorHex;
            this.bisCombatDropBonus = bisCombatDropBonus;
            this.bisPetSpecialBonus = bisPetSpecialBonus;
            this.stanceAdvice = stanceAdvice;
            this.mechanicGuide = mechanicGuide;
        }

        public boolean isPetCentric() {
            return this == DRUID;
        }

        public String chipLabel() {
            return icon + " " + ruName;
        }

        public static HeroClass fromIdOrName(int classId, String className) {
            for (HeroClass hc : values()) {
                if (hc.id == classId) return hc;
            }
            if (className != null) {
                String low = className.trim().toLowerCase(Locale.ROOT);
                if (low.contains("war") || low.contains("воин")) return WARRIOR;
                if (low.contains("arch") || low.contains("rang") || low.contains("лучн")) return ARCHER;
                if (low.contains("mag") || low.contains("wiz") || low.contains("маг")) return MAGE;
                if (low.contains("rog") || low.contains("assass") || low.contains("разб")) return ROGUE;
                if (low.contains("dru") || low.contains("друид")) return DRUID;
            }
            return DRUID;
        }
    }

    /** Определяет активный класс героя (с учётом ручного переключателя просмотра класса, если он выбран) */
    public static HeroClass resolveHeroClass(Map<String, Object> dash, DruidProfile profile) {
        if (profile != null && profile.overrideClassId >= 1 && profile.overrideClassId <= 5) {
            return HeroClass.fromIdOrName(profile.overrideClassId, null);
        }
        if (dash != null) {
            int cid = SimpleJson.intVal(dash, "characterClassId", 0);
            String cname = SimpleJson.str(dash, "characterClassName", "");
            if (cid >= 1 && cid <= 5) {
                return HeroClass.fromIdOrName(cid, cname);
            }
            if (!cname.isEmpty()) {
                return HeroClass.fromIdOrName(0, cname);
            }
        }
        if (profile != null && profile.detectedClassId >= 1 && profile.detectedClassId <= 5) {
            return HeroClass.fromIdOrName(profile.detectedClassId, null);
        }
        return HeroClass.DRUID;
    }

    /** Краткий гайд механики класса, лучшей стойки и эталонного Бонуса дропа под выбранный режим */
    public static String buildClassGuide(HeroClass hc, DruidProfile.BuildMode mode) {
        if (hc == null) hc = HeroClass.DRUID;
        String bisDropRu = RuGameDictionary.statName(hc.bisCombatDropBonus);
        String bisPetSpecRu = RuGameDictionary.statName(hc.bisPetSpecialBonus);
        return "• Боевая механика: " + hc.mechanicGuide + "\n"
                + "• Лучшая стойка: " + hc.stanceAdvice + "\n"
                + "• Эталонный Бонус дропа на всех 10 вещах (Пуш): «" + bisDropRu
                + "» (под Боссов — «Урон по боссам %», под Фарм — «Дроп предметов / рун / рубинов»)\n"
                + "• Лучший врождённый спец-бонус на 4 питомцах: «" + bisPetSpecRu + "»";
    }

    /** Лучшее семейство самоцвета под конкретный класс, категорию гнезда и цель сборки */
    public static GemSocketOptimizer.GemFamily bestGemFamilyForClass(HeroClass hc,
                                                                     GemSocketOptimizer.SocketCategory cat,
                                                                     DruidProfile.BuildMode mode,
                                                                     String activeStance) {
        if (mode == DruidProfile.BuildMode.FARM) {
            return GemSocketOptimizer.GemFamily.AMBER;
        }
        boolean bulwark = activeStance != null && activeStance.toLowerCase(Locale.ROOT).contains("bulwark");

        switch (cat) {
            case WEAPON:
                if (hc == HeroClass.DRUID) return GemSocketOptimizer.GemFamily.JADE; // +Урон пета %
                if (bulwark && hc == HeroClass.WARRIOR) return GemSocketOptimizer.GemFamily.LAPIS; // +Атака% и +Возмездие%
                return GemSocketOptimizer.GemFamily.GARNET; // +Урон атаки % для Воина/Лучника/Мага/Разбойника

            case OFFHAND: // Факел / Щит
                if (hc == HeroClass.DRUID) return GemSocketOptimizer.GemFamily.LAPIS; // +Урон пета %
                if (bulwark && hc == HeroClass.WARRIOR) return GemSocketOptimizer.GemFamily.GARNET; // +Макс. HP %
                return GemSocketOptimizer.GemFamily.JADE; // +Урон атаки % для Воина/Лучника/Мага/Разбойника

            case ARMOR:
                if (mode == DruidProfile.BuildMode.LEVELING) return GemSocketOptimizer.GemFamily.LAPIS; // +Опыт %
                if (hc == HeroClass.WARRIOR) return GemSocketOptimizer.GemFamily.GARNET; // +Защита % (и возмездие)
                return GemSocketOptimizer.GemFamily.JADE; // +Макс. HP %

            case JEWELRY:
            default:
                if (hc == HeroClass.WARRIOR) return GemSocketOptimizer.GemFamily.GARNET; // +Сила (физ. урон + HP + возмездие)
                return GemSocketOptimizer.GemFamily.LAPIS; // +Интеллект (+0.38% Атаки, +0.20% Дв. урона, +0.05% Пета)
        }
    }

    // =========================================================================
    // ПОЛНЫЙ МУЛЬТИКЛАССОВЫЙ АУДИТОР ДЕРЕВА НАВЫКОВ (ВСЕ 5 КЛАССОВ, ID 1..90)
    // =========================================================================
    private static final Map<Integer, String> RU_SKILL_NAMES = new HashMap<>();

    static {
        // Воин (1)
        RU_SKILL_NAMES.put(7, "Добивающий удар (Казнь)");
        RU_SKILL_NAMES.put(22, "Боевое восстановление");
        RU_SKILL_NAMES.put(23, "Палач");
        RU_SKILL_NAMES.put(46, "Железная выдержка");
        RU_SKILL_NAMES.put(47, "Кровожадность");
        RU_SKILL_NAMES.put(48, "Контрудар");
        RU_SKILL_NAMES.put(8, "Могучие удары");
        RU_SKILL_NAMES.put(24, "Сокрушающие удары");
        RU_SKILL_NAMES.put(25, "Подавление");
        RU_SKILL_NAMES.put(49, "Боевая концентрация");
        RU_SKILL_NAMES.put(50, "Хватка титана");
        RU_SKILL_NAMES.put(51, "Титанический удар (×3)");
        RU_SKILL_NAMES.put(9, "Берсерк");
        RU_SKILL_NAMES.put(26, "Острое лезвие");
        RU_SKILL_NAMES.put(27, "Смертельный удар");
        RU_SKILL_NAMES.put(52, "Трофеи войны");
        RU_SKILL_NAMES.put(53, "Отражение");
        RU_SKILL_NAMES.put(54, "Неумолимый натиск");

        // Лучник (2)
        RU_SKILL_NAMES.put(3, "Стрельба из лука");
        RU_SKILL_NAMES.put(10, "Точность");
        RU_SKILL_NAMES.put(11, "Выстрел в голову");
        RU_SKILL_NAMES.put(28, "Твёрдая рука");
        RU_SKILL_NAMES.put(29, "Двойная стрела");
        RU_SKILL_NAMES.put(30, "Идеальный выстрел (×2 крит)");
        RU_SKILL_NAMES.put(2, "Шипастая ловушка");
        RU_SKILL_NAMES.put(12, "Ядовитая ловушка");
        RU_SKILL_NAMES.put(13, "Добивание раненых");
        RU_SKILL_NAMES.put(31, "Зазубренные стрелы");
        RU_SKILL_NAMES.put(32, "Мастер ловушек");
        RU_SKILL_NAMES.put(33, "Инстинкт охотника (1 HP)");
        RU_SKILL_NAMES.put(1, "Крепкая связь с петом");
        RU_SKILL_NAMES.put(14, "Звериные узы");
        RU_SKILL_NAMES.put(15, "Вожак стаи");
        RU_SKILL_NAMES.put(34, "Охотник за яйцами");
        RU_SKILL_NAMES.put(35, "Спутник-собиратель");
        RU_SKILL_NAMES.put(36, "Альфа-удар (×2 герой и пет)");

        // Маг (3)
        RU_SKILL_NAMES.put(4, "Магический выброс");
        RU_SKILL_NAMES.put(16, "Чародейский всплеск");
        RU_SKILL_NAMES.put(17, "Мастерство заклинаний");
        RU_SKILL_NAMES.put(37, "Чародейский сифон");
        RU_SKILL_NAMES.put(38, "Нестабильная энергия");
        RU_SKILL_NAMES.put(39, "Магический каскад (×3 урон)");
        RU_SKILL_NAMES.put(5, "Насыщение огнём");
        RU_SKILL_NAMES.put(18, "Пылающая душа");
        RU_SKILL_NAMES.put(19, "Инферно");
        RU_SKILL_NAMES.put(40, "Возгорание");
        RU_SKILL_NAMES.put(41, "Точка плавления");
        RU_SKILL_NAMES.put(42, "Пироклазм");
        RU_SKILL_NAMES.put(6, "Дикая магия");
        RU_SKILL_NAMES.put(20, "Стрела хаоса");
        RU_SKILL_NAMES.put(21, "Искажение реальности");
        RU_SKILL_NAMES.put(43, "Благосклонность фортуны");
        RU_SKILL_NAMES.put(44, "Жатва душ");
        RU_SKILL_NAMES.put(45, "Воплощение хаоса");

        // Разбойник (4)
        RU_SKILL_NAMES.put(55, "Точные удары");
        RU_SKILL_NAMES.put(56, "Заточенные клинки");
        RU_SKILL_NAMES.put(57, "Первый удар");
        RU_SKILL_NAMES.put(58, "Наёмный убийца");
        RU_SKILL_NAMES.put(59, "Смертельная засада");
        RU_SKILL_NAMES.put(60, "Удар милосердия (Казнь)");
        RU_SKILL_NAMES.put(61, "Быстрые клинки");
        RU_SKILL_NAMES.put(62, "Теневые клинки");
        RU_SKILL_NAMES.put(63, "Теневое эхо");
        RU_SKILL_NAMES.put(64, "Призрачный удар");
        RU_SKILL_NAMES.put(65, "Мастерство эха");
        RU_SKILL_NAMES.put(66, "Теневой каскад");
        RU_SKILL_NAMES.put(67, "Мародёр");
        RU_SKILL_NAMES.put(68, "Карманник");
        RU_SKILL_NAMES.put(69, "Ловкие руки");
        RU_SKILL_NAMES.put(70, "Охотник за сокровищами");
        RU_SKILL_NAMES.put(71, "Чёрный рынок (Удачный тир)");
        RU_SKILL_NAMES.put(72, "Мастер-вор");

        // Друид (5)
        RU_SKILL_NAMES.put(73, "Грызня");
        RU_SKILL_NAMES.put(74, "Строитель плотин");
        RU_SKILL_NAMES.put(75, "Удар хвостом");
        RU_SKILL_NAMES.put(76, "Хранитель хатки");
        RU_SKILL_NAMES.put(77, "Толстая шкура");
        RU_SKILL_NAMES.put(78, "Лесоповал");
        RU_SKILL_NAMES.put(79, "Землекоп");
        RU_SKILL_NAMES.put(80, "Острый нюх");
        RU_SKILL_NAMES.put(81, "Подкоп");
        RU_SKILL_NAMES.put(82, "Запасливость");
        RU_SKILL_NAMES.put(83, "Глубокие корни");
        RU_SKILL_NAMES.put(84, "Связь с землёй");
        RU_SKILL_NAMES.put(85, "Родство");
        RU_SKILL_NAMES.put(86, "Дикие узы");
        RU_SKILL_NAMES.put(87, "Общий инстинкт");
        RU_SKILL_NAMES.put(88, "Сердце-близнец");
        RU_SKILL_NAMES.put(89, "Дикая гармония");
        RU_SKILL_NAMES.put(90, "Единая душа");
    }

    public static String skillNameRu(int skillId, String fallbackEn) {
        String ru = RU_SKILL_NAMES.get(skillId);
        if (ru != null) return ru;
        return fallbackEn != null && !fallbackEn.isEmpty() ? fallbackEn : ("Навык #" + skillId);
    }

    public static String branchNameRu(String enBranch) {
        if (enBranch == null) return "Ветка";
        switch (enBranch) {
            case "Might": return "⚔️ Мощь";
            case "Strength": return "💪 Сила";
            case "Finesse": return "⚡ Мастерство";
            case "Marksmanship": return "🎯 Меткость";
            case "Hunting": return "🪤 Охота";
            case "Beastmaster": return "🐾 Зверолов";
            case "Arcane": return "✨ Тайная магия";
            case "Destruction": return "🔥 Разрушение";
            case "Chaos": return "🌀 Хаос";
            case "Assassin": return "🗡️ Убийца";
            case "Shadowwalker": return "🌑 Тенеходец";
            case "Thief": return "🪙 Вор";
            case "Lodge": return "🦫 Хатка";
            case "Tunnels": return "⛏️ Туннели";
            case "Kinship": return "🌿 Родство";
            default: return enBranch;
        }
    }

    /** Аудит классовых навыков для ЛЮБОГО из 5 классов (с проверкой капов триггеров из /api/config/game) */
    public static String buildMultiClassSkillsReport(Map<String, Object> dash,
                                                     DruidProfile p,
                                                     Map<String, Object> gameCfg) {
        if (dash == null || p == null) return "Синхронизируй API для проверки дерева навыков.";
        HeroClass hc = resolveHeroClass(dash, p);

        // Для Друида с локальной базой сохраняем проверенный детальный отчёт + дополняем общими капами
        int realDashClassId = SimpleJson.intVal(dash, "characterClassId", 5);
        if (hc == HeroClass.DRUID && realDashClassId == 5) {
            return ApiDashboardEngine.buildSkillsAuditReport(dash, p, gameCfg);
        }

        StringBuilder sb = new StringBuilder();
        int activeSlot = SimpleJson.intVal(dash, "activeClassSkillPresetSlot", 1);
        int bonusAll = p.bonusAllClassSkills;
        Map<String, Object> activeRanks = null;
        int pointCount = 0;

        List<Object> presets = SimpleJson.asList(dash.get("classSkillPresetSlots"));
        if (presets != null) {
            for (Object po : presets) {
                Map<String, Object> pm = SimpleJson.asMap(po);
                if (pm != null && SimpleJson.intVal(pm, "slot", 0) == activeSlot) {
                    activeRanks = SimpleJson.asMap(pm.get("ranks"));
                    pointCount = SimpleJson.intVal(pm, "point_count", 0);
                    break;
                }
            }
        }

        sb.append(hc.icon).append(" ДЕРЕВО НАВЫКОВ КЛАССА «").append(hc.ruName.toUpperCase(Locale.ROOT)).append("»\n");
        sb.append("Пресет #").append(activeSlot)
          .append(" · Вложено очков: ").append(pointCount)
          .append(" · Бонус «+Все навыки»: +").append(bonusAll).append("\n\n");

        if (realDashClassId != hc.id) {
            sb.append("ℹ️ На сервере сейчас активен класс «")
              .append(RuGameDictionary.classNameRu(realDashClassId, SimpleJson.str(dash, "characterClassName", "")))
              .append("», поэтому ниже показана справочная карта всех 18 навыков выбранного класса «")
              .append(hc.ruName).append("»:\n\n");
        }

        // Проверяем капы триггеров из /api/config/game (class_skill_caps)
        if (realDashClassId == hc.id && activeRanks != null && gameCfg != null) {
            List<Object> caps = SimpleJson.asList(gameCfg.get("class_skill_caps"));
            List<String> capWarnings = new ArrayList<>();
            if (caps != null) {
                for (Object co : caps) {
                    Map<String, Object> cm = SimpleJson.asMap(co);
                    if (cm == null) continue;
                    double capVal = SimpleJson.num(cm, "cap", 0.0);
                    double base = SimpleJson.num(cm, "base", 0.0);
                    List<Object> contribs = SimpleJson.asList(cm.get("contributions"));
                    if (contribs == null || contribs.isEmpty()) continue;

                    double total = base;
                    boolean belongsToActive = false;
                    String primarySkillName = "";
                    double mainPerRank = 1.0;
                    for (Object cObj : contribs) {
                        Map<String, Object> cMap = SimpleJson.asMap(cObj);
                        if (cMap == null) continue;
                        int sid = SimpleJson.intVal(cMap, "skill_id", 0);
                        double perRank = SimpleJson.num(cMap, "per_rank", 0.0);
                        int invested = SimpleJson.intVal(activeRanks, String.valueOf(sid), 0);
                        if (invested > 0) {
                            belongsToActive = true;
                            total += (invested + bonusAll) * perRank;
                            primarySkillName = skillNameRu(sid, "");
                            mainPerRank = Math.max(0.5, perRank);
                        }
                    }
                    if (belongsToActive && capVal > 0 && total > capVal + 0.01) {
                        int wastedPts = (int) Math.floor((total - capVal) / mainPerRank);
                        if (wastedPts > 0) {
                            capWarnings.add(String.format(Locale.US,
                                    "⚠️ ОВЕРКАП КАПА НАВЫКА «%s»: сейчас %.0f%% при потолке %.0f%% — можно смело снять ~%d очк.!",
                                    primarySkillName, total, capVal, wastedPts));
                        }
                    }
                }
            }
            if (!capWarnings.isEmpty()) {
                sb.append("🚨 НАЙДЕНЫ ЛИШНИЕ ОЧКИ В ПОТОЛКАХ ТРИГГЕРОВ:\n");
                for (String w : capWarnings) sb.append("  ").append(w).append("\n");
                sb.append("\n");
            }
        }

        // Выводим ветки и навыки класса из gameCfg (или встроенного справочника)
        Map<String, Object> trees = gameCfg != null ? SimpleJson.asMap(gameCfg.get("class_skill_trees")) : null;
        List<Object> branches = trees != null ? SimpleJson.asList(trees.get(String.valueOf(hc.id))) : null;
        if (branches != null && !branches.isEmpty()) {
            for (Object bo : branches) {
                Map<String, Object> bm = SimpleJson.asMap(bo);
                if (bm == null) continue;
                sb.append(branchNameRu(SimpleJson.str(bm, "name", "Ветка"))).append(":\n");
                List<Object> skills = SimpleJson.asList(bm.get("skills"));
                if (skills == null) continue;
                for (Object so : skills) {
                    Map<String, Object> sm = SimpleJson.asMap(so);
                    if (sm == null) continue;
                    int sid = SimpleJson.intVal(sm, "id", 0);
                    int maxRank = SimpleJson.intVal(sm, "max_rank", 5);
                    String sName = skillNameRu(sid, SimpleJson.str(sm, "name", ""));
                    int inv = (realDashClassId == hc.id && activeRanks != null)
                            ? SimpleJson.intVal(activeRanks, String.valueOf(sid), 0) : 0;
                    int eff = inv > 0 ? (inv + bonusAll) : 0;
                    if (realDashClassId == hc.id) {
                        sb.append(String.format(Locale.US, "  • %s: вложено %d/%d (эффект. ур. %d)\n",
                                sName, inv, maxRank, eff));
                    } else {
                        sb.append(String.format(Locale.US, "  • %s (макс. вложение %d)\n", sName, maxRank));
                    }
                }
                sb.append("\n");
            }
        } else {
            sb.append("📌 Механика класса: ").append(hc.mechanicGuide).append("\n");
            sb.append("🥊 Рекомендуемая стойка: ").append(hc.stanceAdvice).append("\n");
        }
        return sb.toString();
    }
}
