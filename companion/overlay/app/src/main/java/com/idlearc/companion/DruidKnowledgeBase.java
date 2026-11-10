package com.idlearc.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Справочник игровых механик IdleArc 1.3.15 для класса Друид + живой расчётчик
 * талисманов, автонадписей, сравнений предметов и аудита персонажа через LiveBuildSimulator.
 *
 * 0% захардкоженных чужих статов или готовых списков замен: все оценки строятся
 * математически от текущего состояния персонажа (DruidProfile).
 */
public class DruidKnowledgeBase {

    public static class SkillInfo {
        public final String id;
        public final String ruName;
        public final String branch;
        public final int tierRank;
        public final int maxBasePoints;
        public final String formulaRu;
        public final boolean hasHardCap;
        public final int effectiveCapLevels;
        public final String capNoteRu;
        public final int levelingPriority;

        public SkillInfo(String id, String ruName, String branch, int tierRank, int maxBasePoints,
                         String formulaRu, boolean hasHardCap, int effectiveCapLevels,
                         String capNoteRu, int levelingPriority) {
            this.id = id;
            this.ruName = ruName;
            this.branch = branch;
            this.tierRank = tierRank;
            this.maxBasePoints = maxBasePoints;
            this.formulaRu = formulaRu;
            this.hasHardCap = hasHardCap;
            this.effectiveCapLevels = effectiveCapLevels;
            this.capNoteRu = capNoteRu;
            this.levelingPriority = levelingPriority;
        }
    }

    public static final List<SkillInfo> SKILLS = new ArrayList<>();

    static {
        // ВЕТКА 1: ХАТКА
        SKILLS.add(new SkillInfo("druid_gnaw", "Погрыз", "Хатка", 1, 10,
                "+8% урона питомцев за уровень навыка", false, 0,
                "БЕЗ ЛИМИТА! Масштабируется от «+Все классовые навыки» на каждом уровне.", 1));
        SKILLS.add(new SkillInfo("druid_thick_pelt", "Густой мех", "Хатка", 1, 5,
                "+7% макс. здоровья и +8 HP за убийство за уровень", false, 0,
                "БЕЗ ЛИМИТА. Увеличивает процентный пул здоровья героя.", 11));
        SKILLS.add(new SkillInfo("druid_dam_builder", "Строитель плотин", "Хатка", 2, 5,
                "+15 плоского базового урона питомцев за уровень", false, 0,
                "БЕЗ ЛИМИТА. Добавляет плоскую базу урона питомца до всех множителей.", 5));
        SKILLS.add(new SkillInfo("druid_timberfall", "Лесоповал", "Хатка", 2, 5,
                "+5% урона по боссам за уровень (ЛИМИТ: макс. 100%)", true, 20,
                "ЛИМИТ 100% (20 эффективных уровней с учётом бонуса ко всем навыкам).", 6));
        SKILLS.add(new SkillInfo("druid_tail_slap", "Удар хвостом", "Хатка", 3, 5,
                "Шанс 3% за уровень, что питомец ударит дважды (ЛИМИТ: макс. 60%)", true, 20,
                "ЛИМИТ 60% (20 эфф. уровней) = множитель ×1.60 к атакам питомца + повышает кап уклонения класса.", 2));
        SKILLS.add(new SkillInfo("druid_lodgekeeper", "Хранитель хатки", "Хатка", 3, 5,
                "+1 доп. уровень мастерства питомцев за уровень навыка", false, 0,
                "БЕЗ ЛИМИТА! Умножает базовую силу экипированных питомцев.", 3));

        // ВЕТКА 2: ТУННЕЛИ
        SKILLS.add(new SkillInfo("druid_digger", "Землекоп", "Туннели", 1, 10,
                "+2% материалов и +1% шанса дропа предметов за уровень", false, 0,
                "БЕЗ ЛИМИТА! Ускоряет приток материалов Кузницы и предметов.", 7));
        SKILLS.add(new SkillInfo("druid_keen_snout", "Чуткий нюх", "Туннели", 1, 5,
                "+1% дропа яиц питомцев (ЛИМИТ яиц: 15%) и +1% материалов за уровень", true, 15,
                "Шанс яиц ограничен ЛИМИТОМ 15% (15 эфф. ур.), но бонус материалов растёт дальше.", 8));
        SKILLS.add(new SkillInfo("druid_hoard", "Запасы", "Туннели", 2, 5,
                "+5% получения золота за уровень навыка", false, 0,
                "БЕЗ ЛИМИТА. Масштабирует получение золота.", 14));
        SKILLS.add(new SkillInfo("druid_earthbind", "Земные путы", "Туннели", 2, 5,
                "+3% шанса крит. удара за уровень навыка", false, 0,
                "БЕЗ ЛИМИТА. Сверх 100% шанса крита каждый 1% переходит 1:1 в крит. урон.", 12));
        SKILLS.add(new SkillInfo("druid_undermine", "Подкоп", "Туннели", 3, 5,
                "Мгновенная казнь врага при HP <= 2% за уровень (ЛИМИТ: макс. 35%)", true, 18,
                "ЛИМИТ 35% HP (18 эфф. уровней) — мгновенное добивание обычных и элитных врагов.", 4));
        SKILLS.add(new SkillInfo("druid_deep_roots", "Глубокие корни", "Туннели", 3, 5,
                "+1% опыта питомца (ЛИМИТ: 15%) и +1% дропа рун за уровень", true, 15,
                "Опыт питомца ограничен 15% (15 ур.), бонус дропа рун продолжает расти + расширяет кап уклонения.", 13));

        // ВЕТКА 3: СИМБИОЗ
        SKILLS.add(new SkillInfo("druid_kinship", "Родство", "Симбиоз", 1, 10,
                "Преобразует 3% базового урона атаки в урон питомца (ОБЩИЙ ЛИМИТ: 75%)", true, 25,
                "Делит ОБЩИЙ кап 75% с «Дикой связью». Передаёт плоскую базу атаки оружия в базу питомца.", 10));
        SKILLS.add(new SkillInfo("druid_shared_instinct", "Общий инстинкт", "Симбиоз", 1, 5,
                "+10% критического урона за уровень навыка", false, 0,
                "БЕЗ ЛИМИТА. Прямая прибавка к критическому урону героя и питомцев.", 9));
        SKILLS.add(new SkillInfo("druid_feral_bond", "Дикая связь", "Симбиоз", 2, 5,
                "Преобразует 4% базового урона атаки в урон питомца (ОБЩИЙ ЛИМИТ: 75%)", true, 19,
                "Делит ОБЩИЙ кап 75% с «Родством» — следи, чтобы сумма обоих навыков не превышала 75%.", 15));
        SKILLS.add(new SkillInfo("druid_twin_heart", "Сердце-близнец", "Симбиоз", 2, 5,
                "+3 HP за удар за уровень и +0.5% снижения урона (до 2.5%)", true, 5,
                "Снижение урона ограничено 2.5% (5 ур.), плоский отхил за удар растёт без лимита.", 16));
        SKILLS.add(new SkillInfo("druid_wild_attunement", "Единение с природой", "Симбиоз", 3, 5,
                "+3% к бонусу питомца против стихийной слабости (ЛИМИТ: макс. 25%)", true, 9,
                "ЛИМИТ 25% (9 эфф. уровней). Усиливает автоподбор питомца под уязвимость врага.", 17));
        SKILLS.add(new SkillInfo("druid_one_soul", "Одна душа", "Симбиоз", 3, 5,
                "+12% бонуса урона питомцев за уровень навыка", false, 0,
                "БЕЗ ЛИМИТА! Крупнейший процентный навык урона питомцев за уровень.", 1));
    }

    public static SkillInfo findSkillByText(String text) {
        if (text == null) return null;
        String low = normalizeForMatch(text);
        for (SkillInfo s : SKILLS) {
            String key = normalizeForMatch(s.ruName);
            if (low.contains(key)) return s;
        }
        if (low.contains("погры") || low.contains("огрыз")) return SKILLS.get(0);
        if (low.contains("густ") || low.contains("8 hp за") || low.contains("8 нр за") || (low.contains("7%") && low.contains("здоров"))) return SKILLS.get(1);
        if (low.contains("плотин")) return SKILLS.get(2);
        if (low.contains("лесопов")) return SKILLS.get(3);
        if (low.contains("хвост")) return SKILLS.get(4);
        if (low.contains("хранител")) return SKILLS.get(5);
        if (low.contains("землекоп")) return SKILLS.get(6);
        if (low.contains("чутки") || low.contains("нюх")) return SKILLS.get(7);
        if (low.contains("запас")) return SKILLS.get(8);
        if (low.contains("земные") || low.contains("путы")) return SKILLS.get(9);
        if (low.contains("подкоп") || low.contains("мгновенно убива")) return SKILLS.get(10);
        if (low.contains("глубоки") || low.contains("корни")) return SKILLS.get(11);
        if (low.contains("родств") || low.contains("3% вашего базового")) return SKILLS.get(12);
        if (low.contains("общий инстинкт") || low.contains("инстинкт")) return SKILLS.get(13);
        if (low.contains("дикая связ") || low.contains("4% вашего базового")) return SKILLS.get(14);
        if (low.contains("близнец")) return SKILLS.get(15);
        if (low.contains("единени") || low.contains("слабост")) return SKILLS.get(16);
        if (low.contains("одна душ") || low.contains("12% за уровень")) return SKILLS.get(17);
        return null;
    }

    public static String normalizeForMatch(String s) {
        if (s == null) return "";
        String low = s.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace('a', 'а')
                .replace('b', 'в')
                .replace('c', 'с')
                .replace('e', 'е')
                .replace('h', 'н')
                .replace('k', 'к')
                .replace('m', 'м')
                .replace('n', 'п')
                .replace('o', 'о')
                .replace('p', 'р')
                .replace('t', 'т')
                .replace('u', 'и')
                .replace('x', 'х')
                .replace('y', 'у');
        StringBuilder sb = new StringBuilder(low.length());
        for (int i = 0; i < low.length(); i++) {
            char ch = low.charAt(i);
            char prev = i > 0 ? sb.charAt(i - 1) : ' ';
            boolean prevCyr = prev >= 'а' && prev <= 'я';
            boolean nextCyr = i + 1 < low.length() && low.charAt(i + 1) >= 'а' && low.charAt(i + 1) >= 'я';
            if (ch == '6' && (prevCyr || nextCyr)) {
                ch = 'б';
            } else if (ch == '0' && prevCyr && nextCyr) {
                ch = 'о';
            } else if (ch == '3' && (prevCyr || nextCyr) && prev != '+') {
                ch = 'з';
            } else if (ch == 'r' && (prevCyr || nextCyr)) {
                // В шрифте Roboto строчная русская «г» часто совпадает по глифу с латинской «r» («двойноrо»)
                ch = 'г';
            } else if (ch == 'v' && (prevCyr || nextCyr)) {
                ch = 'у';
            } else if (ch == 'g' && prevCyr && nextCyr) {
                ch = 'д';
            } else if (ch == 'w' && (prevCyr || nextCyr)) {
                ch = 'ш';
            } else if (ch == 'й') {
                // Унифицируем «й»/«и» только в устойчивых заменах ниже, здесь оставляем «й»
            } else if (ch == 'э' && "бвгджзйклмнпрстфхцчшщ".indexOf(prev) >= 0) {
                ch = 'а';
            }
            sb.append(ch);
        }
        return sb.toString()
                .replaceAll("[^а-яa-z0-9%+.]", " ")
                .replaceAll("\\s+", " ")
                .replace("коита", "крита")
                .replace("коит.", "крит.")
                .replace("двсйного", "двойного")
                .replace("двоиного", "двойного")
                .replace("двойного мара", "двойного удара")
                .replace("двойного дара", "двойного удара")
                .replace("здорсвье", "здоровье")
                .replace("убииств", "убийств")
                .replace("снижениеурона", "снижение урона")
                .replace("шансуклонения", "шанс уклонения")
                .replace("здоровьезаудар", "здоровье за удар")
                .replace("заудар", "за удар")
                .replace("заубийств", "за убийств")
                .replace("множительдобычи", "множитель добычи")
                .trim();
    }

    /** Результат математической оценки набора строк (сравнения предметов, талисмана, автонадписи) */
    public static class ItemEvaluation {
        public double totalScore; // суммарный эквивалентный % прироста целевой функции
        public String tierGrade;
        public int gearScoreFound;
        public String tierTagFound = "";
        public final List<String> pros = new ArrayList<>();
        public final List<String> traps = new ArrayList<>();
    }

    /**
     * Математически оценивает распознанные строки карточки/надписи/сравнения через LiveBuildSimulator
     * по отношению к текущему состоянию игрока (DruidProfile).
     */
    public static ItemEvaluation evaluateItemLines(List<String> lines, DruidProfile p) {
        if (p == null) p = new DruidProfile();
        ItemEvaluation ev = new ItemEvaluation();

        double estScale = p.hasScannedStats && p.charLevel > 1 ? Math.max(5.0, p.charLevel * 0.16) : 20.0;
        LiveBuildSimulator.HeroState baseState = LiveBuildSimulator.buildHeroState(p, estScale, 0.0, 0.0);
        double baseObj = LiveBuildSimulator.computeTotalObjective(baseState, p.mode);

        boolean isFarm = (p.mode == DruidProfile.BuildMode.FARM);
        boolean isBoss = (p.mode == DruidProfile.BuildMode.BOSS);

        Matcher awkMatcher;
        Pattern awkPat = Pattern.compile("(?:^|\\s|[.·])([AА]\\d(?:\\s*\\+\\d+)?)");

        for (String raw : lines) {
            String low = normalizeForMatch(raw);
            double val = extractFirstNumber(raw);

            if (low.contains("очки снаряжения") || low.contains("gs")) {
                if (val >= 10) {
                    ev.gearScoreFound = (int) val;
                }
            }
            awkMatcher = awkPat.matcher(raw);
            if (awkMatcher.find() || low.contains("инфернальн")) {
                if (low.contains("инфернальн")) {
                    ev.tierTagFound = "Инфернальный";
                } else {
                    awkMatcher.reset();
                    if (awkMatcher.find()) ev.tierTagFound = awkMatcher.group(1);
                }
            }

            // 1. +N Все классовые навыки: считаем реальный вклад в навыки Друида
            if (low.contains("все классовые навыки") || low.contains("классовым навыкам") || low.contains("классовых навыков")) {
                double pts = val > 0 ? val : 1.0;
                LiveBuildSimulator.HeroState mod = baseState.copy();
                mod.petDmgAdditivePct += pts * 28.0; // Погрыз + Одна душа + Хранитель хатки
                mod.critChancePct += pts * 3.0;      // Земные путы
                mod.critDamagePct += pts * 10.0;     // Общий инстинкт
                mod.maxHpPctBonus += pts * 7.0;      // Густой мех
                mod.expBonusPct += pts * 3.0;
                mod.goldBonusPct += pts * 5.0;       // Запасы
                double gainPct = ((LiveBuildSimulator.computeTotalObjective(mod, p.mode) / baseObj) - 1.0) * 100.0;
                ev.totalScore += gainPct;
                ev.pros.add(String.format(Locale.US, "👑 +%.0f Все классовые навыки → расчётный вклад +%.2f%% к сборке", pts, gainPct));
                continue;
            }

            // 2. Шанс доп. убийства % (множитель всего дропа, опыта и золота)
            if (low.contains("доп. убийств") || low.contains("доп убийств") || low.contains("бонусное убийство")) {
                double pts = val > 0 ? val : 2.0;
                double farmGainPct = pts * (isFarm ? 1.8 : 0.65);
                ev.totalScore += farmGainPct;
                ev.pros.add(String.format(Locale.US, "💎 +%.1f%% Шанс доп. убийства → +%.2f%% к ценности фарма", pts, farmGainPct));
                continue;
            }

            // 3. Стандартные 26 аффиксов снаряжения — напрямую через LiveBuildSimulator!
            boolean hasPct = raw.contains("%");
            AffixPositionOptimizer.AffixType mapped = AffixPositionOptimizer.classifyAffixName(
                    low, raw, hasPct, null, false);
            if (mapped != null && val > 0) {
                LiveBuildSimulator.CandidateEvaluation ce = LiveBuildSimulator.evaluateCandidate(
                        baseState, mapped, val, val, val, 0.0, p.mode);
                ev.totalScore += ce.totalObjectiveGainPct;
                if (ce.totalObjectiveGainPct >= 0.15) {
                    ev.pros.add(String.format(Locale.US, "✅ %s (+%s) → +%.2f%% (%s)",
                            mapped.ruName,
                            mapped.isPercent ? String.format(Locale.US, "%.1f%%", val) : String.valueOf(Math.round(val)),
                            ce.totalObjectiveGainPct, ce.mathExplanation));
                } else {
                    ev.traps.add(String.format(Locale.US, "⚠️ %s (+%s) → малая отдача +%.2f%% (%s)",
                            mapped.ruName,
                            mapped.isPercent ? String.format(Locale.US, "%.1f%%", val) : String.valueOf(Math.round(val)),
                            ce.totalObjectiveGainPct, ce.mathExplanation));
                }
                continue;
            }

            // 4. Урон боссам %
            if (low.contains("урон боссам") || low.contains("урона по боссам") || low.contains("по боссам")) {
                double pts = val > 0 ? val : 10.0;
                double bossGain = pts * p.marginalGainPer1PctBossDmg() * (isBoss ? 1.0 : 0.35);
                ev.totalScore += bossGain;
                ev.pros.add(String.format(Locale.US, "👹 +%.1f%% Урон боссам → +%.2f%% к босс-урону", pts, pts * p.marginalGainPer1PctBossDmg()));
                continue;
            }

            // 5. Мастерство питомцев
            if (low.contains("мастерств") && low.contains("питом")) {
                double pts = val > 0 ? val : 1.0;
                LiveBuildSimulator.HeroState mod = baseState.copy();
                mod.petDmgAdditivePct += pts * 12.0;
                double gainPct = ((LiveBuildSimulator.computeTotalObjective(mod, p.mode) / baseObj) - 1.0) * 100.0;
                ev.totalScore += gainPct;
                ev.pros.add(String.format(Locale.US, "🐾 +%.0f Мастерство питомцев → +%.2f%% к силе пета", pts, gainPct));
                continue;
            }

            // 6. Плоский vs % Урон атаки
            if (low.contains("урон атаки") || low.contains("урона атаки") || low.contains("физический урон")) {
                if (hasPct) {
                    ev.traps.add("🚫 +% Урон атаки масштабирует только удар самого героя и НЕ передаётся питомцу через «Родство / Дикую связь».");
                } else if (val >= 10) {
                    LiveBuildSimulator.HeroState mod = baseState.copy();
                    mod.flatWeaponBaseAtk += val;
                    double gainPct = ((LiveBuildSimulator.computeTotalObjective(mod, p.mode) / baseObj) - 1.0) * 100.0;
                    ev.totalScore += gainPct;
                    ev.pros.add(String.format(Locale.US, "🪄 +%.0f плоской атаки → +%.2f%% к базе урона", val, gainPct));
                }
                continue;
            }

            // 7. Блок / Возмездие
            if (low.contains("возмезд") || low.contains("шанс блока")) {
                ev.traps.add("⚠️ Блок / Возмездие дают минимальную отдачу для билда через питомцев (+0% к DPS пета).");
                continue;
            }

            // 8. Бонусы добычи (ячейки дропа / руны / автонадписи)
            if (low.contains("рубин") && val > 0) {
                double g = (val / (100.0 + Math.max(0.0, p.rubyDropPct))) * 100.0 * (isFarm ? 0.65 : 0.15);
                ev.totalScore += g;
                ev.pros.add(String.format(Locale.US, "♦️ +%.0f%% Шанс рубинов → +%.2f%% ценности фарма", val, g));
            } else if (low.contains("рун") && (low.contains("выпаден") || low.contains("дроп") || low.contains("шанс")) && val > 0) {
                double g = (val / (100.0 + Math.max(0.0, p.runeDropPct))) * 100.0 * (isFarm ? 0.55 : 0.12);
                ev.totalScore += g;
                ev.pros.add(String.format(Locale.US, "🔷 +%.0f%% Дроп рун → +%.2f%% ценности фарма", val, g));
            } else if ((low.contains("предметов") || low.contains("добыч")) && val > 0) {
                double g = (val / (100.0 + Math.max(0.0, p.itemDropPct))) * 100.0 * (isFarm ? 0.55 : 0.12);
                ev.totalScore += g;
                ev.pros.add(String.format(Locale.US, "🎁 +%.0f%% Дроп предметов → +%.2f%% ценности фарма", val, g));
            } else if (low.contains("материал") && val > 0) {
                double g = (val / (100.0 + Math.max(0.0, p.matDropPct))) * 100.0 * (isFarm ? 0.50 : 0.10);
                ev.totalScore += g;
                ev.pros.add(String.format(Locale.US, "⚒️ +%.0f%% Материалы → +%.2f%% ценности фарма", val, g));
            }
        }

        if (ev.totalScore >= 12.0) ev.tierGrade = "S+ (СИЛЬНЫЙ ПРИРОСТ)";
        else if (ev.totalScore >= 6.0) ev.tierGrade = "S (ВЫСОКАЯ ОТДАЧА)";
        else if (ev.totalScore >= 2.5) ev.tierGrade = "A (ПОЛЕЗНО)";
        else if (ev.totalScore >= 0.7) ev.tierGrade = "B (МАЛЫЙ ПРИРОСТ)";
        else ev.tierGrade = "C (СЛАБО ОТНОСИТЕЛЬНО ТЕКУЩИХ СТАТОВ)";

        return ev;
    }

    /**
     * Извлекает числовое значение из OCR-строки с поддержкой разорванных пробелами чисел
     * («1 0.79K» -> 10790, «1 .08K» -> 1080, «1 190.0%» -> 1190.0, «1051 .0%» -> 1051.0, «+21 3%» -> 213).
     */
    public static double extractFirstNumber(String s) {
        if (s == null) return 0.0;
        String cleaned = s
                .replaceAll("(?<=\\d)[\\s\\u00A0]+(?=[0-9.,])", "")
                .replaceAll("(?<=[.,])[\\s\\u00A0]+(?=\\d)", "");
        Matcher m = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*([KkКкMmМмBbБб])?").matcher(cleaned);
        double best = 0.0;
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1).replace(',', '.'));
                String suf = m.group(2);
                if (suf != null) {
                    String sl = suf.toLowerCase(Locale.ROOT);
                    if (sl.equals("k") || sl.equals("к")) v *= 1_000.0;
                    else if (sl.equals("m") || sl.equals("м")) v *= 1_000_000.0;
                    else if (sl.equals("b") || sl.equals("б")) v *= 1_000_000_000.0;
                }
                best = v;
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    /** Извлекает средний урон из диапазона вида «5879 - 10.79K» или одиночного числа */
    public static double extractRangeAverage(String s) {
        if (s == null) return 0.0;
        String cleaned = s
                .replaceAll("(?<=\\d)[\\s\\u00A0]+(?=[0-9.,])", "")
                .replaceAll("(?<=[.,])[\\s\\u00A0]+(?=\\d)", "");
        Matcher m = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*([KkКкMmМмBbБб])?").matcher(cleaned);
        List<Double> vals = new ArrayList<>();
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1).replace(',', '.'));
                String suf = m.group(2);
                if (suf != null) {
                    String sl = suf.toLowerCase(Locale.ROOT);
                    if (sl.equals("k") || sl.equals("к")) v *= 1_000.0;
                    else if (sl.equals("m") || sl.equals("м")) v *= 1_000_000.0;
                    else if (sl.equals("b") || sl.equals("б")) v *= 1_000_000_000.0;
                }
                vals.add(v);
            } catch (Exception ignored) {
            }
        }
        if (vals.size() >= 2) {
            return 0.5 * (vals.get(vals.size() - 2) + vals.get(vals.size() - 1));
        }
        return vals.isEmpty() ? 0.0 : vals.get(0);
    }

    private static class StatSensitivityRow {
        final String label;
        final double dpsGainPct;
        StatSensitivityRow(String label, double dpsGainPct) {
            this.label = label;
            this.dpsGainPct = dpsGainPct;
        }
    }

    /**
     * Строит живой математический отчёт по состоянию героя (никаких захардкоженных чисел или советов под чужой шмот!).
     */
    public static String buildAuditReport(DruidProfile p) {
        if (p == null) p = new DruidProfile();
        StringBuilder sb = new StringBuilder();

        if (!p.hasScannedStats) {
            sb.append("📷 ПРОФИЛЬ ОЖИДАЕТ ПЕРВОГО СКАНА ХАРАКТЕРИСТИК\n\n");
            sb.append("Сейчас в приложении НЕТ ни одной предзаполненной характеристики — все расчёты строятся честно от визуального скана твоего экрана:\n");
            sb.append("1️⃣ Открой в игре окно «Характеристики персонажа» и нажми 🔍 СКАН (можно пролистать список вниз и нажать СКАН ещё раз — все статы объединятся автоматически).\n");
            sb.append("2️⃣ Затем открой любой предмет, сравнение шмота или выбор Автонадписи и нажми 🔍 СКАН — симулятор вычислит лучший параметр строго по отношению к ТВОИМ текущим числам!\n\n");
            sb.append("💡 Даже без скана листа персонажа ты уже можешь сканировать любой предмет прямо сейчас: компаньон считает числа самого предмета с экрана и рассчитает пропорции по его масштабу.");
            return sb.toString();
        }

        sb.append("📊 ЖИВОЙ МАТЕМАТИЧЕСКИЙ АУДИТ ПЕРСОНАЖА\n");
        sb.append("Распознано параметров: ").append(p.scannedStatsCount);
        if (p.charLevel > 1) sb.append(" · Ур. ").append(p.charLevel);
        if (p.gearScore > 0) sb.append(" · GS ").append(p.gearScore);
        sb.append("\n\n");

        // 1. Динамическая диагностика защитных капов (95.0% Уклонение и 95.0% Снижение урона)
        sb.append("🛡️ 1. СОСТОЯНИЕ ЗАЩИТНЫХ КАПОВ И ПОРОГОВ:\n");
        if (p.wastedDodgePct() > 0.1) {
            sb.append(String.format(Locale.US,
                    "  • ⚠️ Оверкап Уклонения: %.1f%% при капе класса 95.0%% (лишние +%.1f%% дают 0%% выживаемости — этот бюджет выгодно перевести в атакующие статы).\n",
                    p.dodgeRaw, p.wastedDodgePct()));
        } else {
            sb.append(String.format(Locale.US,
                    "  • Уклонение: %.1f%% / 95.0%% (до капа не хватает %.1f%%; каждый +1%% сейчас заметно разгоняет эффективное HP).\n",
                    p.dodgeRaw, Math.max(0.0, 95.0 - p.dodgeRaw)));
        }

        if (p.damageReductionRaw >= 95.0) {
            sb.append(String.format(Locale.US,
                    "  • ✅ Снижение урона: %.1f%% / 95.0%% (кап закрыт!).\n\n", p.damageReductionRaw));
        } else {
            sb.append(String.format(Locale.US,
                    "  • Снижение урона: %.1f%% / 95.0%% (до капа ещё %.1f%% — на шлеме, броне и факеле оно сейчас очень ценно).\n\n",
                    p.damageReductionRaw, 95.0 - p.damageReductionRaw));
        }

        // 2. Живой расчёт предельной ценности каждого атакующего параметра для ТЕКУЩИХ чисел игрока!
        LiveBuildSimulator.HeroState hs = LiveBuildSimulator.buildHeroState(p, 20.0, 0.0, 0.0);
        double baseDps = LiveBuildSimulator.computeDps(hs, p.mode);

        List<StatSensitivityRow> sensitivities = new ArrayList<>();
        sensitivities.add(new StatSensitivityRow(
                String.format(Locale.US, "+10%% Шанса двойного удара (сейчас %.1f%%)", p.doubleHitChance),
                relDpsGain(hs, p.mode, baseDps, AffixPositionOptimizer.AffixType.DOUBLE_HIT, 10.0)));
        sensitivities.add(new StatSensitivityRow(
                String.format(Locale.US, "+10%% Шанса двойного урона (сейчас %.1f%%)", p.doubleDamageChance),
                relDpsGain(hs, p.mode, baseDps, AffixPositionOptimizer.AffixType.DOUBLE_DAMAGE, 10.0)));
        sensitivities.add(new StatSensitivityRow(
                String.format(Locale.US, "+10%% Шанса крита (сейчас %.1f%%)", p.critChance),
                relDpsGain(hs, p.mode, baseDps, AffixPositionOptimizer.AffixType.CRIT_CHANCE, 10.0)));
        sensitivities.add(new StatSensitivityRow(
                String.format(Locale.US, "+10%% Крит. урона (сейчас %.1f%%)", p.critDamage),
                relDpsGain(hs, p.mode, baseDps, AffixPositionOptimizer.AffixType.CRIT_DAMAGE, 10.0)));
        sensitivities.add(new StatSensitivityRow(
                String.format(Locale.US, "+10%% Урона питомца (сейчас +%.0f%%)", p.petDamageBonusPct),
                relDpsGain(hs, p.mode, baseDps, AffixPositionOptimizer.AffixType.PET_DAMAGE, 10.0)));
        sensitivities.add(new StatSensitivityRow(
                String.format(Locale.US, "+50 Интеллекта (сейчас %.0f)", p.intelligence),
                relDpsGain(hs, p.mode, baseDps, AffixPositionOptimizer.AffixType.INTELLIGENCE, 50.0)));

        Collections.sort(sensitivities, new Comparator<StatSensitivityRow>() {
            @Override
            public int compare(StatSensitivityRow a, StatSensitivityRow b) {
                return Double.compare(b.dpsGainPct, a.dpsGainPct);
            }
        });

        sb.append("📐 2. ОТНОСИТЕЛЬНАЯ ЦЕННОСТЬ БОЕВЫХ СТАТОВ ПРЯМО СЕЙЧАС:\n");
        for (int i = 0; i < sensitivities.size(); i++) {
            StatSensitivityRow row = sensitivities.get(i);
            sb.append(String.format(Locale.US, "  #%d %s → +%.2f%% чистого DPS\n",
                    i + 1, row.label, row.dpsGainPct));
        }

        sb.append("\n📋 3. СЧИТАННЫЕ ОСНОВНЫЕ ПАРАМЕТРЫ:\n");
        sb.append(String.format(Locale.US, "  • Сила %.0f · Ловкость %.0f · Интеллект %.0f\n",
                p.strength, p.agility, p.intelligence));
        double avgAtk = (p.minAtk > 0 && p.maxAtk > 0) ? 0.5 * (p.minAtk + p.maxAtk) : p.maxAtk;
        if (p.maxHp > 0 || avgAtk > 0 || p.defense > 0) {
            sb.append(String.format(Locale.US, "  • HP: %.0f · Ср. база атаки: %.0f · Защита: %.0f\n",
                    p.maxHp, avgAtk, p.defense));
        }
        if (p.bonusAllClassSkills > 0) {
            sb.append(String.format(Locale.US, "  • Бонус ко всем классовым навыкам: +%d\n", p.bonusAllClassSkills));
        }
        return sb.toString();
    }

    private static double relDpsGain(LiveBuildSimulator.HeroState base, DruidProfile.BuildMode mode,
                                     double baseDps, AffixPositionOptimizer.AffixType type, double delta) {
        LiveBuildSimulator.HeroState c = base.copy();
        c.applyAffixDelta(type, delta, 0.0);
        return ((LiveBuildSimulator.computeDps(c, mode) / baseDps) - 1.0) * 100.0;
    }

    /** Живой расчёт приоритетов Автонадписей и Питомцев от текущих статов профиля */
    public static String buildInscriptionsAndPetsGuide(DruidProfile p) {
        if (p == null) p = new DruidProfile();
        LiveBuildSimulator.HeroState hs = LiveBuildSimulator.buildHeroState(p, 20.0, 0.0, 0.0);
        double baseDps = LiveBuildSimulator.computeDps(hs, p.mode);

        double gDh = relDpsGain(hs, p.mode, baseDps, AffixPositionOptimizer.AffixType.DOUBLE_HIT, 10.0);
        double gDd = relDpsGain(hs, p.mode, baseDps, AffixPositionOptimizer.AffixType.DOUBLE_DAMAGE, 10.0);
        double gCc = relDpsGain(hs, p.mode, baseDps, AffixPositionOptimizer.AffixType.CRIT_CHANCE, 10.0);
        double gCd = relDpsGain(hs, p.mode, baseDps, AffixPositionOptimizer.AffixType.CRIT_DAMAGE, 10.0);
        double gPet = relDpsGain(hs, p.mode, baseDps, AffixPositionOptimizer.AffixType.PET_DAMAGE, 10.0);

        String dodgeNote = (p.dodgeRaw >= 95.0)
                ? String.format(Locale.US, "Уклонение (уже %.1f%% >= 95%% капа)", p.dodgeRaw)
                : "Уклонение сверх капа 95%";

        return String.format(Locale.US,
               "✒️ ЖИВОЙ РАСЧЁТ АВТОНАДПИСЕЙ ПОД ТВОИ ТЕКУЩИЕ СТАТЫ (прирост DPS за +10%% стата):\n"
             + "• Все классовые навыки / Мастерство питомцев — глобальный множитель всех веток Друида\n"
             + "• Шанс двойного удара: +%.2f%% DPS за каждые +10%%\n"
             + "• Шанс двойного урона: +%.2f%% DPS за каждые +10%%\n"
             + "• Шанс крита: +%.2f%% DPS за каждые +10%%\n"
             + "• Крит. урон: +%.2f%% DPS за каждые +10%%\n"
             + "• Урон питомца: +%.2f%% DPS за каждые +10%%\n"
             + "• Шанс доп. убийства %% — прямой множитель золота, опыта и дропа для Фарм-набора.\n"
             + "🚫 Не дают DPS питомцам: +%% Урон атаки, Блок, Возмездие, %s.\n\n"
             + "💡 Открой окно выбора Автонадписи и нажми 🔍 СКАН — симулятор сравнит конкретные выпавшие числа на экране и покажет, стоит ли заменять текущую надпись!\n\n"
             + "🐾 МЕХАНИКА ОТРЯДА ИЗ 4 ПИТОМЦЕВ:\n"
             + "• Все 4 экипированных питомца наследуют твой Крит, Двойной урон и Урон питомца %%, а бонус отряда ограничивается самым низким уровнем прокачки в четвёрке — качай всех 4 равномерно.\n"
             + "• Включай «Автоподбор питомца под слабость» для работы навыка «Единение с природой».",
             gDh, gDd, gCc, gCd, gPet, dodgeNote);
    }

    /** Справочник механик прогрессии Друида по этапам игры */
    public static String buildFullLevelingGuide() {
        return "🗺️ СПРАВОЧНИК МЕХАНИК ПРОГРЕССИИ ДРУИДА (1 → ЭНДГЕЙМ)\n\n"
             + "━━ ЭТАП 1: РАСКРЫТИЕ БАЗЫ ПИТОМЦЕВ ━━\n"
             + "• Стойка «Повелитель зверей» фокусирует весь урон в отряд питомцев; двуручное оружие с высокой плоской атакой передаёт до 75% своей базы в петов через «Родство» + «Дикую связь».\n"
             + "• Стартовое ядро навыков ветки «Хатка»: Погрыз → Строитель плотин → Удар хвостом (лимит 20 эфф. ур.) и Хранитель хатки.\n\n"
             + "━━ ЭТАП 2: КАЗНЬ МОБОВ И УЧЁТ ПЛАШЕК «ЛИМИТ» ━━\n"
             + "• В ветке «Туннели» ключевой ускоритель зачистки — «Подкоп» (мгновенная казнь врагов ниже порога HP, лимит 18 эфф. ур.).\n"
             + "• В ветке «Симбиоз» финишер «Одна душа» (+12% урона петов за уровень) не имеет потолка.\n"
             + "• При росте бонуса «+Все классовые навыки» проверяй навыки с плашкой ЛИМИТ (Лесоповал 20 ур., Удар хвостом 20 ур., Подкоп 18 ур., Единение 9 ур., сумма Родство+Дикая связь <= 75%): лишние очки сверх лимита не дают прибавки.\n\n"
             + "━━ ЭТАП 3: БАЛАНС МНОЖИТЕЛЕЙ И КАПОВ (ЧЕРЕЗ ЖИВОЙ СКАН) ━━\n"
             + "• Регулярно сканируй окно «Характеристики персонажа» кнопкой 🔍 СКАН: по мере роста Крит. урона и Урона питомца соотношение ценности Крита, Интеллекта, Двойного удара и Двойного урона постоянно смещается.\n"
             + "• Компаньон автоматически пересчитывает эти пропорции для каждого открытого предмета — тебе не нужно гадать на глаз!";
    }
}
