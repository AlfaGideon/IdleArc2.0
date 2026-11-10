package com.idlearc.companion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Визуальный по-позиционный анализатор предметов IdleArc 1.3.15 (0% хардкода весов или готовых замен).
 *
 * Весь выбор лучшего параметра строится в реальном времени из визуального OCR-скана экрана:
 * 1) Считывает с карточки предмета тип слота, надет ли предмет («Снять» vs «Надеть»), базовую атаку/защиту шапки,
 *    тир пробуждения предмета (А1..А6 +заточка) и все открытые строки аффиксов с их числами и рангами [I..VIII].
 * 2) Калибрует масштаб роллов конкретного предмета (unitScale и аффикс-кап procCapBase) напрямую из считанных чисел.
 * 3) Вычитает текущий стат позиции #i из живого состояния героя (LiveBuildSimulator.HeroState) и прогоняет
 *    ВСЕ допустимые аффиксы этого слота через математическую модель урона/живучести/фарма, выбирая максимум
 *    относительного прироста (Δ% по отношению к текущему состоянию).
 */
public class AffixPositionOptimizer {

    public static final int MAX_AFFIXES_PER_ITEM = 5;

    private static final Pattern NUMBER_PAT = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)");
    private static final Pattern PLUS_STAT_PAT = Pattern.compile("\\+\\s*([0-9]+(?:\\s?[0-9]+)*(?:[.,][0-9]+)?)\\s*(%?)");
    private static final Pattern AFFIX_COUNTER_PAT = Pattern.compile(
            "(?:^|\\s|\\(|:)([0-5])\\s*(?:/|\\|)\\s*5(?:\\)|\\s|$)");
    private static final Pattern AFFIX_OCR_COUNTER_PAT = Pattern.compile(
            "(?:^|\\s)([1-5])15(?:\\s|$)");
    private static final Pattern ROMAN_SUFFIX_PAT = Pattern.compile(
            "(?:^|\\s|\\[|\\()([IVXIl1|]{1,5})(?:\\)|\\])?\\s*$");
    private static final Pattern ROMAN_PREFIX_PAT = Pattern.compile(
            "^\\s*(?:\\[|\\()?([IVXIl1|]{1,5})(?:\\]|\\))?\\s+(?=\\+|[А-Яа-яA-Za-z])");
    private static final Pattern EXPLICIT_AWAKEN_PCT_PAT = Pattern.compile(
            "\\(\\s*\\+\\s*([1-9][0-9]?)\\s*%\\s*\\)|★\\s*([1-9])");

    // Поддержка и латинской A, и кириллической А из OCR («Оружие . А6 +30», «Факел . А5 +5»)
    private static final Pattern HEADER_AWAKEN_ENH = Pattern.compile(
            "(?:^|\\s|[.·])[AА]([1-9])(?:\\s*\\+\\s*(\\d{1,3}))?(?:\\s|$)");
    private static final Pattern HEADER_PLUS_ENH = Pattern.compile("(?:^|\\s)\\+(\\d{1,3})(?:\\s|$)");

    public enum AffixKind {
        PREFIX("[П]", "Префикс"),
        SUFFIX("[С]", "Суффикс"),
        NONE("", "");

        public final String tag;
        public final String ruLabel;

        AffixKind(String tag, String ruLabel) {
            this.tag = tag;
            this.ruLabel = ruLabel;
        }
    }

    /**
     * Таблица базовых диапазонов (base_min..base_max) всех 26 аффиксов из серверного конфига /api/config/game.
     * Внимание: здесь НЕТ никаких весов или приоритетов — только физические диапазоны генератора предметов игры!
     */
    public enum AffixType {
        // Префиксы (12 в серверном конфиге: 6 обычных + 6 стихийных конверсий оружия)
        PET_DAMAGE(AffixKind.PREFIX, "Урон питомца", true, true, 2.0, 8.0),
        DAMAGE_REDUCTION(AffixKind.PREFIX, "Снижение урона", true, false, 1.0, 3.0),
        DODGE_CHANCE(AffixKind.PREFIX, "Шанс уклонения", true, false, 0.5, 1.0),
        MAX_HEALTH(AffixKind.PREFIX, "Макс. здоровье", false, true, 10.0, 50.0),
        LOCAL_PERCENT_DEFENSE(AffixKind.PREFIX, "Увеличенная защита", true, true, 8.0, 23.0),
        LOCAL_FLAT_DEFENSE(AffixKind.PREFIX, "Доп. защита", false, true, 3.0, 11.0),
        ELEMENTAL_CONVERT(AffixKind.PREFIX, "Конверсия урона в стихию", true, false, 3.0, 5.0),

        // Суффиксы (14 в серверном конфиге)
        DOUBLE_DAMAGE(AffixKind.SUFFIX, "Шанс двойного урона", true, false, 1.0, 2.0),
        DOUBLE_HIT(AffixKind.SUFFIX, "Шанс двойного удара", true, false, 1.0, 2.0),
        CRIT_DAMAGE(AffixKind.SUFFIX, "Крит. урон", true, true, 1.0, 3.0),
        CRIT_CHANCE(AffixKind.SUFFIX, "Шанс крита", true, true, 1.0, 2.0),
        INTELLIGENCE(AffixKind.SUFFIX, "Интеллект", false, true, 2.0, 4.0),
        DEXTERITY(AffixKind.SUFFIX, "Ловкость", false, true, 2.0, 4.0),
        STRENGTH(AffixKind.SUFFIX, "Сила", false, true, 2.0, 4.0),
        BLOODTHIRST(AffixKind.SUFFIX, "Здоровье за удар и за убийство", true, false, 1.0, 2.0),
        ADDED_DAMAGE(AffixKind.SUFFIX, "Доп. урон", false, true, 4.0, 10.0),
        DEFENSE_PERCENT(AffixKind.SUFFIX, "Защита", true, true, 2.0, 6.0),
        LIFE_ON_HIT(AffixKind.SUFFIX, "Здоровье за удар", false, true, 1.0, 1.0),
        LIFE_ON_KILL(AffixKind.SUFFIX, "Здоровье за убийство", false, true, 3.0, 10.0),
        EXP_GAIN(AffixKind.SUFFIX, "Бонус опыта", true, true, 1.0, 3.0),
        GOLD_GAIN(AffixKind.SUFFIX, "Бонус золота", true, true, 2.0, 5.0),

        EMPTY_POSITION(AffixKind.NONE, "Пустая позиция", false, false, 0.0, 0.0);

        public final AffixKind kind;
        public final String ruName;
        public final boolean isPercent;
        public final boolean ignoresAffixCap;
        public final double baseMin;
        public final double baseMax;
        public final double baseAvg;

        AffixType(AffixKind kind, String ruName, boolean isPercent, boolean ignoresAffixCap,
                  double baseMin, double baseMax) {
            this.kind = kind;
            this.ruName = ruName;
            this.isPercent = isPercent;
            this.ignoresAffixCap = ignoresAffixCap;
            this.baseMin = baseMin;
            this.baseMax = baseMax;
            this.baseAvg = 0.5 * (baseMin + baseMax);
        }

        public String taggedName() {
            return kind == AffixKind.NONE ? ruName : (kind.tag + " " + ruName);
        }
    }

    public static class ParsedAffixRow {
        public final int index;
        public final AffixType type;
        public final String rawLabel;
        public final double rolledValue;
        public final boolean valueHasPct;
        public final int awakenTier;
        public final int awakenBonusPct;

        public ParsedAffixRow(int index, AffixType type, String rawLabel, double rolledValue,
                              boolean valueHasPct, int awakenTier, int awakenBonusPct) {
            this.index = index;
            this.type = type;
            this.rawLabel = rawLabel;
            this.rolledValue = rolledValue;
            this.valueHasPct = valueHasPct;
            this.awakenTier = awakenTier;
            this.awakenBonusPct = awakenBonusPct;
        }

        public double awakenMultiplier() {
            return 1.0 + (awakenBonusPct / 100.0);
        }
    }

    /** Визуально считанные параметры шапки самого предмета (до секции АФФИКСЫ) */
    public static class ScannedItemHeader {
        public final boolean isEquipped;
        public final double flatAttack;
        public final double baseDefense;
        public final int awakenLevel;
        public final int enhancementLevel;

        public ScannedItemHeader(boolean isEquipped, double flatAttack, double baseDefense,
                                 int awakenLevel, int enhancementLevel) {
            this.isEquipped = isEquipped;
            this.flatAttack = flatAttack;
            this.baseDefense = baseDefense;
            this.awakenLevel = awakenLevel;
            this.enhancementLevel = enhancementLevel;
        }
    }

    public static class ItemRollModel {
        public final int itemAwakenLevel;
        public final int enhancementLevel;
        public final double unitScale;
        public final double procCapBase;
        public final double bloodthirstCap;

        public ItemRollModel(int itemAwakenLevel, int enhancementLevel,
                             double unitScale, double procCapBase, double bloodthirstCap) {
            this.itemAwakenLevel = itemAwakenLevel;
            this.enhancementLevel = enhancementLevel;
            this.unitScale = unitScale;
            this.procCapBase = procCapBase;
            this.bloodthirstCap = bloodthirstCap;
        }

        public double expectedAvgRoll(AffixType t, double awakenMult) {
            if (t == AffixType.EMPTY_POSITION) return 0.0;
            if (t == AffixType.DOUBLE_DAMAGE || t == AffixType.DOUBLE_HIT) {
                return Math.min(t.baseAvg * unitScale, procCapBase) * awakenMult;
            }
            if (t == AffixType.BLOODTHIRST) {
                return Math.min(t.baseAvg * unitScale, bloodthirstCap) * awakenMult;
            }
            if (t == AffixType.DAMAGE_REDUCTION) {
                // В /api/config/game damage_reduction входит в frozen_cap_stats (жёсткий потолок 14.0% на предмете)
                return Math.min(t.baseAvg * unitScale * awakenMult, 14.0);
            }
            if (t == AffixType.DODGE_CHANCE) {
                return Math.min(t.baseAvg * unitScale, 25.0) * awakenMult;
            }
            if (t == AffixType.ELEMENTAL_CONVERT) {
                return Math.min(t.baseAvg * unitScale, 100.0) * awakenMult;
            }
            return t.baseAvg * unitScale * awakenMult;
        }

        public double expectedMinRoll(AffixType t, double awakenMult) {
            if (t == AffixType.EMPTY_POSITION) return 0.0;
            if (t == AffixType.DAMAGE_REDUCTION) {
                return Math.min(t.baseMin * unitScale * awakenMult, 14.0);
            }
            if (!t.ignoresAffixCap) {
                double cap = (t == AffixType.BLOODTHIRST) ? bloodthirstCap : procCapBase;
                return Math.min(t.baseMin * unitScale, cap) * awakenMult;
            }
            return t.baseMin * unitScale * awakenMult;
        }

        public double expectedMaxRoll(AffixType t, double awakenMult) {
            if (t == AffixType.EMPTY_POSITION) return 0.0;
            if (t == AffixType.DAMAGE_REDUCTION) {
                return Math.min(t.baseMax * unitScale * awakenMult, 14.0);
            }
            if (!t.ignoresAffixCap) {
                double cap = (t == AffixType.BLOODTHIRST) ? bloodthirstCap : procCapBase;
                return Math.min(t.baseMax * unitScale, cap) * awakenMult;
            }
            return t.baseMax * unitScale * awakenMult;
        }
    }

    public static class OptimizationReport {
        public final SlotAffixAdvisor.GearSlot slot;
        public final boolean slotInferredFromAffixes;
        public final boolean isShieldOffhand;
        public final List<ParsedAffixRow> currentRows;
        public final List<AffixType> idealByPos;
        public final double currentTotalDpsEq;
        public final double idealTotalDpsEq;
        public final String shortBadge;
        public final String headline;
        public final String details;
        public final ScreenAnalyzer.VerdictColor verdictColor;

        public OptimizationReport(SlotAffixAdvisor.GearSlot slot, boolean slotInferredFromAffixes,
                                  boolean isShieldOffhand, List<ParsedAffixRow> currentRows,
                                  List<AffixType> idealByPos, double currentTotalDpsEq,
                                  double idealTotalDpsEq, String shortBadge, String headline,
                                  String details, ScreenAnalyzer.VerdictColor verdictColor) {
            this.slot = slot;
            this.slotInferredFromAffixes = slotInferredFromAffixes;
            this.isShieldOffhand = isShieldOffhand;
            this.currentRows = currentRows;
            this.idealByPos = idealByPos;
            this.currentTotalDpsEq = currentTotalDpsEq;
            this.idealTotalDpsEq = idealTotalDpsEq;
            this.shortBadge = shortBadge;
            this.headline = headline;
            this.details = details;
            this.verdictColor = verdictColor;
        }
    }

    /** Серверная матрица допустимости аффиксов по типам предметов (/api/config/game -> applicable_to) */
    public static List<AffixType> getCompatiblePool(SlotAffixAdvisor.GearSlot slot) {
        return getCompatiblePool(slot, false);
    }

    public static List<AffixType> getCompatiblePool(SlotAffixAdvisor.GearSlot slot, boolean isShieldBase) {
        if (slot == null) slot = SlotAffixAdvisor.GearSlot.WEAPON;
        List<AffixType> p = new ArrayList<>();
        switch (slot) {
            case WEAPON:
                Collections.addAll(p,
                        AffixType.PET_DAMAGE, AffixType.DOUBLE_HIT, AffixType.DOUBLE_DAMAGE,
                        AffixType.CRIT_DAMAGE, AffixType.CRIT_CHANCE,
                        AffixType.INTELLIGENCE, AffixType.DEXTERITY, AffixType.STRENGTH,
                        AffixType.LIFE_ON_HIT, AffixType.LIFE_ON_KILL,
                        AffixType.EXP_GAIN, AffixType.GOLD_GAIN, AffixType.ELEMENTAL_CONVERT);
                break;
            case TORCH:
                p.add(AffixType.PET_DAMAGE);
                p.add(AffixType.DAMAGE_REDUCTION);
                p.add(AffixType.DODGE_CHANCE);
                if (!isShieldBase) p.add(AffixType.BLOODTHIRST);
                p.add(AffixType.DOUBLE_DAMAGE);
                p.add(AffixType.CRIT_DAMAGE);
                p.add(AffixType.CRIT_CHANCE);
                p.add(AffixType.EXP_GAIN);
                p.add(AffixType.GOLD_GAIN);
                break;
            case HELMET:
            case CHEST:
                Collections.addAll(p,
                        AffixType.PET_DAMAGE, AffixType.DAMAGE_REDUCTION, AffixType.MAX_HEALTH,
                        AffixType.LOCAL_PERCENT_DEFENSE, AffixType.LOCAL_FLAT_DEFENSE, AffixType.DODGE_CHANCE,
                        AffixType.DOUBLE_DAMAGE, AffixType.CRIT_DAMAGE, AffixType.CRIT_CHANCE,
                        AffixType.EXP_GAIN, AffixType.GOLD_GAIN);
                break;
            case GLOVES:
            case BOOTS:
                Collections.addAll(p,
                        AffixType.PET_DAMAGE, AffixType.MAX_HEALTH,
                        AffixType.LOCAL_PERCENT_DEFENSE, AffixType.LOCAL_FLAT_DEFENSE, AffixType.DODGE_CHANCE,
                        AffixType.DOUBLE_DAMAGE, AffixType.CRIT_DAMAGE, AffixType.CRIT_CHANCE,
                        AffixType.EXP_GAIN, AffixType.GOLD_GAIN);
                break;
            case BELT:
                Collections.addAll(p,
                        AffixType.PET_DAMAGE, AffixType.MAX_HEALTH,
                        AffixType.DOUBLE_HIT, AffixType.DOUBLE_DAMAGE, AffixType.CRIT_DAMAGE, AffixType.CRIT_CHANCE,
                        AffixType.INTELLIGENCE, AffixType.DEXTERITY, AffixType.STRENGTH,
                        AffixType.ADDED_DAMAGE, AffixType.DEFENSE_PERCENT,
                        AffixType.EXP_GAIN, AffixType.GOLD_GAIN);
                break;
            case AMULET:
            case RING:
            default:
                Collections.addAll(p,
                        AffixType.PET_DAMAGE, AffixType.MAX_HEALTH,
                        AffixType.DOUBLE_HIT, AffixType.DOUBLE_DAMAGE, AffixType.CRIT_DAMAGE, AffixType.CRIT_CHANCE,
                        AffixType.INTELLIGENCE, AffixType.DEXTERITY, AffixType.STRENGTH,
                        AffixType.ADDED_DAMAGE, AffixType.DEFENSE_PERCENT,
                        AffixType.LIFE_ON_HIT, AffixType.LIFE_ON_KILL,
                        AffixType.EXP_GAIN, AffixType.GOLD_GAIN);
                break;
        }
        return p;
    }

    public static OptimizationReport analyzeItemAffixes(
            List<IdleArcOcrEngine.OcrLine> ocrLines,
            SlotAffixAdvisor.GearSlot explicitSlot,
            DruidProfile profile) {
        return analyzeItemAffixes(ocrLines, explicitSlot, profile, false);
    }

    /**
     * Динамический расчёт позиций предмета из живого OCR-скана экрана.
     */
    public static OptimizationReport analyzeItemAffixes(
            List<IdleArcOcrEngine.OcrLine> ocrLines,
            SlotAffixAdvisor.GearSlot explicitSlot,
            DruidProfile profile,
            boolean equippedVisualHint) {
        if (ocrLines == null || ocrLines.isEmpty()) return null;
        if (profile == null) profile = new DruidProfile();

        List<IdleArcOcrEngine.OcrLine> modalLines = IdleArcOcrEngine.isolateActiveModalLines(ocrLines);
        if (modalLines.isEmpty()) return null;

        List<String> plain = new ArrayList<>(modalLines.size());
        for (IdleArcOcrEngine.OcrLine l : modalLines) plain.add(l.text);

        boolean slotInferred = false;
        SlotAffixAdvisor.GearSlot headerSlot = explicitSlot != null
                ? explicitSlot
                : SlotAffixAdvisor.detectSlotFromModalLines(modalLines);

        if (headerSlot == SlotAffixAdvisor.GearSlot.TALISMAN || headerSlot == SlotAffixAdvisor.GearSlot.PET) {
            return null;
        }

        boolean isShieldBase = (headerSlot == SlotAffixAdvisor.GearSlot.TORCH)
                && SlotAffixAdvisor.isShieldHeaderLines(plain);

        List<ParsedAffixRow> rows = extractSecondaryAffixRows(modalLines, headerSlot, isShieldBase);
        if (rows.isEmpty()) return null;

        SlotAffixAdvisor.GearSlot detectedSlot = headerSlot;
        if (detectedSlot == null) {
            detectedSlot = inferSlotFromObservedAffixes(rows, plain);
            slotInferred = true;
        }

        List<AffixType> pool = new ArrayList<>(getCompatiblePool(detectedSlot, isShieldBase));
        ScannedItemHeader itemHdr = parseItemHeaderStats(plain, equippedVisualHint);
        ItemRollModel rollModel = calibrateRollModel(itemHdr, rows);
        int n = rows.size();

        // Формируем живой вектор состояния героя из отсканированных характеристик (или масштаба предмета)
        LiveBuildSimulator.HeroState currentHeroState = LiveBuildSimulator.buildHeroState(
                profile, rollModel.unitScale, itemHdr.flatAttack, itemHdr.baseDefense);

        // Если предмет сейчас НАДЕТ («Снять» на экране), то для честного расчёта «что лучше по отношению»
        // вычитаем текущие вторичные аффиксы этого предмета, получая чистую базу героя без этих 5 строк!
        LiveBuildSimulator.HeroState stateWithoutAllRows = currentHeroState.copy();
        if (itemHdr.isEquipped) {
            for (ParsedAffixRow r : rows) {
                stateWithoutAllRows.applyAffixDelta(r.type, -r.rolledValue, itemHdr.baseDefense);
            }
        }

        // Запоминаем фактические небустованные роллы статов, уже стоящих на предмете
        final Map<AffixType, Double> observedUnboosted = new HashMap<>();
        for (ParsedAffixRow r : rows) {
            if (r.type != AffixType.EMPTY_POSITION && r.rolledValue > 0.0) {
                observedUnboosted.put(r.type, r.rolledValue / r.awakenMultiplier());
            }
        }

        // Список множителей пробуждения предмета по убыванию [m_1 >= m_2 >= ... >= m_n]
        List<Double> sortedMultsDesc = new ArrayList<>(n);
        for (ParsedAffixRow r : rows) sortedMultsDesc.add(r.awakenMultiplier());
        Collections.sort(sortedMultsDesc, Collections.<Double>reverseOrder());

        // ПОЛНЫЙ МАТЕМАТИЧЕСКИЙ ПЕРЕБОР всех C(|pool|, n) комбинаций в симуляторе!
        List<AffixType> chosenTop = LiveBuildSimulator.solveOptimalSubset(
                stateWithoutAllRows, pool, sortedMultsDesc, rollModel,
                observedUnboosted, itemHdr.baseDefense, profile.mode);

        // Сортируем выбранный оптимальный набор по МАРЖИНАЛЬНОЙ выгоде от Пробуждения (+20% к аффиксу),
        // чтобы замороженные статы (frozen_cap_stats: Снижение урона 14%) не забирали замки Пробуждения у масштабируемых!
        final LiveBuildSimulator.HeroState refBase = stateWithoutAllRows;
        final DruidProfile.BuildMode buildMode = profile.mode;
        final double hdrDef = itemHdr.baseDefense;
        Collections.sort(chosenTop, new Comparator<AffixType>() {
            @Override
            public int compare(AffixType a, AffixType b) {
                double gainA = marginalAwakenGain(a);
                double gainB = marginalAwakenGain(b);
                return Double.compare(gainB, gainA);
            }

            private double marginalAwakenGain(AffixType t) {
                double baseUnboosted = observedUnboosted.containsKey(t)
                        ? observedUnboosted.get(t)
                        : rollModel.expectedAvgRoll(t, 1.0);
                double boosted = (t == AffixType.DAMAGE_REDUCTION)
                        ? Math.min(baseUnboosted * 1.20, 14.0)
                        : (baseUnboosted * 1.20);
                LiveBuildSimulator.HeroState s1 = refBase.copy();
                s1.applyAffixDelta(t, baseUnboosted, hdrDef);
                LiveBuildSimulator.HeroState s2 = refBase.copy();
                s2.applyAffixDelta(t, boosted, hdrDef);
                double o1 = LiveBuildSimulator.computeTotalObjective(s1, buildMode);
                double o2 = LiveBuildSimulator.computeTotalObjective(s2, buildMode);
                return (o2 / Math.max(1e-9, o1)) - 1.0;
            }
        });

        // Жёсткий инвариант: все статы из chosenTop, которые УЖЕ есть на любой из строк предмета,
        // остаются строго на СВОИХ текущих позициях, а в список замен попадают ТОЛЬКО реально отсутствующие на предмете!
        Set<AffixType> allPresentOnItem = EnumSet.noneOf(AffixType.class);
        for (ParsedAffixRow r : rows) {
            if (r.type != AffixType.EMPTY_POSITION) allPresentOnItem.add(r.type);
        }

        Set<AffixType> chosenTopSet = new HashSet<>(chosenTop);
        Set<AffixType> alreadyPresentTop = new HashSet<>();
        List<Integer> replaceIndices = new ArrayList<>();
        List<AffixType> idealByPos = new ArrayList<>(Collections.nCopies(n, AffixType.PET_DAMAGE));

        for (int i = 0; i < n; i++) {
            AffixType curT = rows.get(i).type;
            if (curT != AffixType.EMPTY_POSITION && chosenTopSet.contains(curT) && !alreadyPresentTop.contains(curT)) {
                idealByPos.set(i, curT);
                alreadyPresentTop.add(curT);
            } else {
                replaceIndices.add(i);
            }
        }

        List<AffixType> missingStats = new ArrayList<>();
        for (AffixType st : chosenTop) {
            if (!alreadyPresentTop.contains(st) && !allPresentOnItem.contains(st)) {
                missingStats.add(st);
            }
        }
        Collections.sort(replaceIndices, new Comparator<Integer>() {
            @Override
            public int compare(Integer i1, Integer i2) {
                int c = Integer.compare(rows.get(i2).awakenBonusPct, rows.get(i1).awakenBonusPct);
                return c != 0 ? c : Integer.compare(i1, i2);
            }
        });
        for (int k = 0; k < replaceIndices.size() && k < missingStats.size(); k++) {
            idealByPos.set(replaceIndices.get(k), missingStats.get(k));
        }

        // Если заменяется ровно ОДНА позиция, выбираем лучший свободный стат строго относительно
        // ФАКТИЧЕСКИХ роллов остальных 4 сохранённых строк предмета (чтобы рекомендация на 100% совпадала с #1 таблицы)
        if (replaceIndices.size() == 1) {
            int rIdx = replaceIndices.get(0);
            ParsedAffixRow rr = rows.get(rIdx);
            LiveBuildSimulator.HeroState stateWithoutSingle = stateWithoutAllRows.copy();
            for (int j = 0; j < n; j++) {
                if (j != rIdx && rows.get(j).type != AffixType.EMPTY_POSITION) {
                    stateWithoutSingle.applyAffixDelta(rows.get(j).type, rows.get(j).rolledValue, itemHdr.baseDefense);
                }
            }
            double rMult = rr.awakenMultiplier();
            AffixType bestSingle = idealByPos.get(rIdx);
            double bestSingleGain = -1e9;
            for (AffixType cand : pool) {
                if (cand == AffixType.EMPTY_POSITION || allPresentOnItem.contains(cand)) continue;
                LiveBuildSimulator.CandidateEvaluation ce = LiveBuildSimulator.evaluateCandidate(
                        stateWithoutSingle, cand,
                        rollModel.expectedAvgRoll(cand, rMult),
                        rollModel.expectedMinRoll(cand, rMult),
                        rollModel.expectedMaxRoll(cand, rMult),
                        itemHdr.baseDefense, buildMode);
                if (ce.totalObjectiveGainPct > bestSingleGain) {
                    bestSingleGain = ce.totalObjectiveGainPct;
                    bestSingle = cand;
                }
            }
            idealByPos.set(rIdx, bestSingle);
            int oldIdx = chosenTop.indexOf(missingStats.isEmpty() ? null : missingStats.get(0));
            if (oldIdx >= 0 && !chosenTop.contains(bestSingle)) {
                chosenTop.set(oldIdx, bestSingle);
            }
        }

        // Определяем, какой бонус Пробуждения даёт максимум итоговой функции для каждого стата из chosenTop
        List<Integer> awakenSortedPos = new ArrayList<>();
        for (int i = 0; i < n; i++) awakenSortedPos.add(i);
        Collections.sort(awakenSortedPos, new Comparator<Integer>() {
            @Override
            public int compare(Integer i1, Integer i2) {
                int c = Integer.compare(rows.get(i2).awakenBonusPct, rows.get(i1).awakenBonusPct);
                return c != 0 ? c : Integer.compare(i1, i2);
            }
        });
        Map<AffixType, Integer> optimalAwakenPctForStat = new HashMap<>();
        for (int k = 0; k < n && k < chosenTop.size(); k++) {
            optimalAwakenPctForStat.put(chosenTop.get(k), rows.get(awakenSortedPos.get(k)).awakenBonusPct);
        }

        // Считаем точное совместное отношение ИДЕАЛЬНОЙ сборки к ТЕКУЩЕЙ сборке предмета!
        LiveBuildSimulator.HeroState stateWithCurrentItem = stateWithoutAllRows.copy();
        for (ParsedAffixRow r : rows) {
            stateWithCurrentItem.applyAffixDelta(r.type, r.rolledValue, itemHdr.baseDefense);
        }

        LiveBuildSimulator.HeroState stateWithIdealItem = stateWithoutAllRows.copy();
        for (int i = 0; i < n; i++) {
            AffixType target = idealByPos.get(i);
            int bestAwkPct = optimalAwakenPctForStat.containsKey(target)
                    ? optimalAwakenPctForStat.get(target) : rows.get(i).awakenBonusPct;
            double bestMult = 1.0 + bestAwkPct / 100.0;
            double unboosted = (rows.get(i).type == target && rows.get(i).rolledValue > 0)
                    ? Math.max(rows.get(i).rolledValue / rows.get(i).awakenMultiplier(), rollModel.expectedAvgRoll(target, 1.0))
                    : rollModel.expectedAvgRoll(target, 1.0);
            stateWithIdealItem.applyAffixDelta(target, unboosted * bestMult, itemHdr.baseDefense);
        }

        double objWithoutItem = LiveBuildSimulator.computeTotalObjective(stateWithoutAllRows, profile.mode);
        double objCurrent = LiveBuildSimulator.computeTotalObjective(stateWithCurrentItem, profile.mode);
        double objIdeal = LiveBuildSimulator.computeTotalObjective(stateWithIdealItem, profile.mode);

        double curContributionPct = ((objCurrent / objWithoutItem) - 1.0) * 100.0;
        double idealContributionPct = ((objIdeal / objWithoutItem) - 1.0) * 100.0;
        // Относительный прирост идеальной раскладки ПО ОТНОШЕНИЮ к текущему предмету:
        double relativeUpgradePct = Math.max(0.0, ((objIdeal / objCurrent) - 1.0) * 100.0);

        int moveAwakenCount = 0;
        int rerollCount = 0;
        for (int i = 0; i < n; i++) {
            ParsedAffixRow r = rows.get(i);
            AffixType target = idealByPos.get(i);
            if (r.type == target) {
                int optAwk = optimalAwakenPctForStat.containsKey(target)
                        ? optimalAwakenPctForStat.get(target) : r.awakenBonusPct;
                if (optAwk > r.awakenBonusPct) moveAwakenCount++;
            } else {
                rerollCount++;
            }
        }

        ScreenAnalyzer.VerdictColor color;
        String badge;
        String headline;
        String slotChip = isShieldBase ? "🛡️ Щит" : detectedSlot.shortChip;
        String badgePrefix = slotInferred ? "📦 Предмет" : slotChip;

        if (rerollCount == 0 && moveAwakenCount == 0) {
            color = ScreenAnalyzer.VerdictColor.GOOD;
            badge = "✅ " + badgePrefix + ": " + n + "/" + n + " ОПТИМУМ";
            headline = "👑 " + badgePrefix + " — все " + n + " позиций математически оптимальны!";
        } else if (rerollCount == 0) {
            color = ScreenAnalyzer.VerdictColor.GOOD;
            badge = "💎 " + badgePrefix + ": переставь ★ (+" + fmt2(relativeUpgradePct) + "%)";
            headline = "💎 " + badgePrefix + ": статы оптимальны — перераспредели Пробуждение (+"
                    + fmt2(relativeUpgradePct) + "% к итоговой силе)";
        } else if (rerollCount <= 2) {
            color = ScreenAnalyzer.VerdictColor.WARN;
            badge = "⚒️ " + badgePrefix + ": замени " + rerollCount + "/" + n + " (+" + fmt2(relativeUpgradePct) + "%)";
            headline = "⚒️ " + badgePrefix + ": расчёт скана — оставить " + (n - rerollCount) + ", заменить "
                    + rerollCount + " поз. (+" + fmt2(relativeUpgradePct) + "% к персонажу)";
        } else {
            color = ScreenAnalyzer.VerdictColor.BAD;
            badge = "🔄 " + badgePrefix + ": замени " + rerollCount + "/" + n + " (+" + fmt2(relativeUpgradePct) + "%)";
            headline = "🔄 " + badgePrefix + ": расчёт скана — выгодно заменить " + rerollCount + " из " + n
                    + " поз. (+" + fmt2(relativeUpgradePct) + "% к персонажу)";
        }

        String details = buildLiveAnalyticalReport(ocrLines, detectedSlot, slotInferred, isShieldBase, itemHdr,
                pool, rows, idealByPos, optimalAwakenPctForStat, rollModel, currentHeroState,
                profile, curContributionPct, idealContributionPct, relativeUpgradePct, rerollCount);

        return new OptimizationReport(detectedSlot, slotInferred, isShieldBase, rows, idealByPos,
                curContributionPct, idealContributionPct, badge, headline, details, color);
    }

    /**
     * Строит полностью прозрачный аналитический отчёт, где для КАЖДОЙ позиции показан живой расчёт кандидатов
     * относительно состояния персонажа без этой строки (никаких скрытых шаблонов!).
     */
    private static String buildLiveAnalyticalReport(
            List<IdleArcOcrEngine.OcrLine> ocrLines,
            SlotAffixAdvisor.GearSlot slot,
            boolean slotInferred,
            boolean isShieldBase,
            ScannedItemHeader itemHdr,
            List<AffixType> compatiblePool,
            List<ParsedAffixRow> rows,
            List<AffixType> idealByPos,
            Map<AffixType, Integer> optimalAwakenPctForStat,
            ItemRollModel rollModel,
            LiveBuildSimulator.HeroState currentHeroState,
            DruidProfile profile,
            double curContributionPct,
            double idealContributionPct,
            double relativeUpgradePct,
            int rerollCount) {

        StringBuilder sb = new StringBuilder();

        // Блок 1: прозрачная телеметрия визуального сканирования (что именно увидел OCR и от какой базы идёт расчёт)
        sb.append("📷 СЧИТАНО С ЭКРАНА: ")
          .append(isShieldBase ? "Щит/Оффхенд" : slot.shortChip)
          .append(itemHdr.awakenLevel > 0 ? (" · А" + itemHdr.awakenLevel + (itemHdr.enhancementLevel > 0 ? " +" + itemHdr.enhancementLevel : "")) : "")
          .append(itemHdr.isEquipped ? " · [НАДЕТО на герое]" : " · [В СУМКЕ]")
          .append(" · масштаб роллов ×").append(fmt1(rollModel.unitScale)).append("\n");

        if (profile.hasScannedStats) {
            sb.append("📊 База сравнения (из скана «Характеристик персонажа»): Крит ")
              .append(fmt0(profile.critChance)).append("%/").append(fmt0(profile.critDamage))
              .append("%, Дв.удар ").append(fmt0(profile.doubleHitChance))
              .append("%, Дв.урон ").append(fmt0(profile.doubleDamageChance))
              .append("%, Сниж.урона ").append(fmt1(profile.damageReductionRaw))
              .append("%, Уклонение ").append(fmt1(profile.dodgeRaw)).append("%.\n");
        } else {
            sb.append("⚠️ Окно «Характеристики персонажа» ещё не сканировалось — расчёт выполнен пропорционально числам самого предмета. Открой «Характеристики персонажа» и нажми СКАН для 100% учёта твоих капов!\n");
        }

        if (slotInferred) {
            sb.append("⚠️ Заголовок предмета вне кадра — слот определён по набору статов. Если открыт другой слот, нажми его кнопку выше.\n");
        }

        Set<AffixType> idealSet = new HashSet<>(idealByPos);
        int n = rows.size();

        // Построчный относительный анализ каждой позиции #1..#n
        for (int i = 0; i < n; i++) {
            ParsedAffixRow r = rows.get(i);
            AffixType assignedTarget = idealByPos.get(i);
            double mult = r.awakenMultiplier();

            sb.append("\n━━ Позиция #").append(r.index).append(formatAwakenBadge(r)).append(" ━━\n");

            // Состояние героя БЕЗ конкретно этой строки #i
            LiveBuildSimulator.HeroState stateWithoutThisRow = currentHeroState.copy();
            if (itemHdr.isEquipped && r.type != AffixType.EMPTY_POSITION) {
                stateWithoutThisRow.applyAffixDelta(r.type, -r.rolledValue, itemHdr.baseDefense);
            }

            // Оцениваем ТЕКУЩИЙ стат на этой строке относительно stateWithoutThisRow
            LiveBuildSimulator.CandidateEvaluation curEval = null;
            if (r.type != AffixType.EMPTY_POSITION) {
                curEval = LiveBuildSimulator.evaluateCandidate(
                        stateWithoutThisRow, r.type, r.rolledValue, r.rolledValue, r.rolledValue,
                        itemHdr.baseDefense, profile.mode);
                sb.append("  Скан строки: ").append(r.type.taggedName())
                  .append(" (").append(formatStatRoll(r.type, r.rolledValue)).append(") → ")
                  .append(curEval.mathExplanation)
                  .append(" [итог: +").append(fmt2(curEval.totalObjectiveGainPct)).append("%]\n");
            } else {
                sb.append("  Скан строки: ⬜ Пустая позиция (вклад +0.00%)\n");
            }

            // Считаем ВСЕ допустимые аффиксы пула слота на этой позиции (при множителе mult)
            // кроме тех, что уже заняты на ДРУГИХ строках предмета (дубликаты на одном предмете запрещены игрой!)
            Set<AffixType> occupiedOnOtherKeptRows = new HashSet<>();
            for (int j = 0; j < n; j++) {
                if (j != i && rows.get(j).type == idealByPos.get(j) && rows.get(j).type != AffixType.EMPTY_POSITION) {
                    occupiedOnOtherKeptRows.add(rows.get(j).type);
                }
            }

            List<LiveBuildSimulator.CandidateEvaluation> rankedFreeCandidates = new ArrayList<>();
            for (AffixType cand : compatiblePool) {
                if (occupiedOnOtherKeptRows.contains(cand)) continue;
                double avgR = (cand == r.type && r.rolledValue > 0)
                        ? r.rolledValue
                        : rollModel.expectedAvgRoll(cand, mult);
                double minR = rollModel.expectedMinRoll(cand, mult);
                double maxR = rollModel.expectedMaxRoll(cand, mult);
                rankedFreeCandidates.add(LiveBuildSimulator.evaluateCandidate(
                        stateWithoutThisRow, cand, avgR, minR, maxR, itemHdr.baseDefense, profile.mode));
            }
            Collections.sort(rankedFreeCandidates, new Comparator<LiveBuildSimulator.CandidateEvaluation>() {
                @Override
                public int compare(LiveBuildSimulator.CandidateEvaluation a, LiveBuildSimulator.CandidateEvaluation b) {
                    return Double.compare(b.totalObjectiveGainPct, a.totalObjectiveGainPct);
                }
            });

            double curObjGain = curEval != null ? curEval.totalObjectiveGainPct : 0.0;

            if (r.type == assignedTarget) {
                int optAwkPct = optimalAwakenPctForStat.containsKey(r.type)
                        ? optimalAwakenPctForStat.get(r.type) : r.awakenBonusPct;
                sb.append("  ✅ РЕШЕНИЕ СИМУЛЯТОРА: ОСТАВИТЬ «").append(r.type.taggedName())
                  .append("» (входит в лучший расчётный набор ").append(n).append(" статов этого слота).\n");

                if (optAwkPct > r.awakenBonusPct) {
                    sb.append("  💎 По Пробуждению: после доводки перекинь сюда Пробуждение ")
                      .append(roman(optAwkPct / 10)).append(" (+").append(optAwkPct)
                      .append("%) через «Перераспределение пробуждения».\n");
                }

                // Математическая проверка диапазона самого числового ролла
                if (r.type.ignoresAffixCap && r.rolledValue > 0) {
                    double minR = rollModel.expectedMinRoll(r.type, mult);
                    double maxR = rollModel.expectedMaxRoll(r.type, mult);
                    if (maxR > minR * 1.35 && r.rolledValue < 0.62 * maxR) {
                        LiveBuildSimulator.CandidateEvaluation maxEval = LiveBuildSimulator.evaluateCandidate(
                                stateWithoutThisRow, r.type, maxR, minR, maxR, itemHdr.baseDefense, profile.mode);
                        double upside = Math.max(0.0, maxEval.totalObjectiveGainPct - curObjGain);
                        sb.append("  💡 Диапазон ролла на этом предмете: ")
                          .append(formatStatRoll(r.type, minR)).append("..").append(formatStatRoll(r.type, maxR))
                          .append(" (сейчас ").append(formatStatRoll(r.type, r.rolledValue))
                          .append(" — переброс самого числа может дать ещё до +").append(fmt2(upside)).append("%).\n");
                    }
                }
            } else {
                // Выводим живую таблицу сравнения топ-3 свободных кандидатов пула по отношению к текущему стату!
                LiveBuildSimulator.CandidateEvaluation targetEval = LiveBuildSimulator.evaluateCandidate(
                        stateWithoutThisRow,
                        assignedTarget,
                        rollModel.expectedAvgRoll(assignedTarget, mult),
                        rollModel.expectedMinRoll(assignedTarget, mult),
                        rollModel.expectedMaxRoll(assignedTarget, mult),
                        itemHdr.baseDefense,
                        profile.mode);
                double netDelta = targetEval.totalObjectiveGainPct - curObjGain;

                if (r.awakenTier > 0) {
                    sb.append("  🔒 [СЛОТ ПРОБУЖДЁН ").append(roman(r.awakenTier)).append("] ");
                } else {
                    sb.append("  🔄 ");
                }
                sb.append("ВЫГОДНЕЕ ЗАМЕНИТЬ НА: «").append(assignedTarget.taggedName())
                  .append("» (чистый выигрыш к текущему: ")
                  .append(netDelta >= 0 ? "+" : "").append(fmt2(netDelta)).append("%)\n");

                sb.append("  📐 Сравнение свободных аффиксов пула на месте #").append(r.index)
                  .append(" (×").append(fmt2(mult)).append("):\n");
                int showTop = Math.min(3, rankedFreeCandidates.size());
                for (int cIdx = 0; cIdx < showTop; cIdx++) {
                    LiveBuildSimulator.CandidateEvaluation ce = rankedFreeCandidates.get(cIdx);
                    double relToCurrent = ce.totalObjectiveGainPct - curObjGain;
                    int otherAssignedPos = -1;
                    if (ce.type != assignedTarget) {
                        for (int j = 0; j < n; j++) {
                            if (j != i && idealByPos.get(j) == ce.type && rows.get(j).type != ce.type) {
                                otherAssignedPos = j + 1;
                                break;
                            }
                        }
                    }
                    sb.append("     ").append(cIdx + 1).append(") ").append(ce.type.taggedName())
                      .append(" ").append(formatRollBand(ce.type, ce.minRoll, ce.expectedRoll, ce.maxRoll))
                      .append(" → итог +").append(fmt2(ce.totalObjectiveGainPct)).append("% (макс. до +")
                      .append(fmt2(ce.maxRollObjectiveGainPct)).append("%; к текущему ")
                      .append(relToCurrent >= 0 ? "+" : "").append(fmt2(relToCurrent)).append("%)")
                      .append(otherAssignedPos > 0 ? " [→ Поз. #" + otherAssignedPos + "]" : "")
                      .append("\n");
                }
                if (r.awakenTier > 0) {
                    int freePos = findFirstUnawakenedRerollPos(rows, idealSet);
                    if (freePos > 0) {
                        sb.append("  ⚒️ Кузница: сначала перенеси замок Пробуждения ").append(roman(r.awakenTier))
                          .append(" «Перераспределением» (либо лови стат на свободной Позиции #").append(freePos)
                          .append(" и перекинь на неё ").append(roman(r.awakenTier)).append(").\n");
                    } else {
                        sb.append("  ⚒️ Кузница: сними замок Пробуждения ").append(roman(r.awakenTier))
                          .append(" «Перераспределением», перекуй аффикс и верни Пробуждение.\n");
                    }
                }
            }
        }

        sb.append("\n📊 СВОДНЫЙ БАЛАНС ПРЕДМЕТА:\n");
        sb.append("• Вклад текущих ").append(n).append(" позиций: +").append(fmt2(curContributionPct))
          .append("% → Расчётный оптимум слота: +").append(fmt2(idealContributionPct))
          .append("% (относительный прирост предмета: +").append(fmt2(relativeUpgradePct)).append("%).\n");
        if (rerollCount == 0) {
            sb.append("• Все типы аффиксов уже дают максимальную математическую отдачу под текущие характеристики.\n");
        }

        // Одновременный разбор блока «ГНЁЗДА» (камней, стоящих выше аффиксов на карточке предмета!)
        String gemSection = GemSocketOptimizer.buildItemCardGemSection(
                ocrLines, slot, itemHdr.isEquipped, currentHeroState, profile);
        if (gemSection != null && !gemSection.isEmpty()) {
            sb.append(gemSection);
        }

        sb.append("\n📚 Допустимый пул слота в игре (").append(formatPoolSummary(slot, compatiblePool)).append(")");
        return sb.toString();
    }

    static ScannedItemHeader parseItemHeaderStats(List<String> plainLines) {
        return parseItemHeaderStats(plainLines, false);
    }

    /** Извлекает данные шапки карточки предмета (надет ли предмет, плоская атака/защита базы, А1..А6 +заточка) */
    static ScannedItemHeader parseItemHeaderStats(List<String> plainLines, boolean equippedVisualHint) {
        boolean hasUnequipBtn = equippedVisualHint;
        boolean hasEquipBtn = false;
        double flatAtk = 0.0;
        double baseDef = 0.0;
        int awk = 0;
        int enh = 0;

        int affixIdx = findAffixHeaderIndexFromStrings(plainLines);
        int hdrLimit = affixIdx > 0 ? affixIdx : Math.min(plainLines.size(), 12);

        for (int i = 0; i < plainLines.size(); i++) {
            String raw = plainLines.get(i);
            String low = DruidKnowledgeBase.normalizeForMatch(raw);

            if (low.contains("снять") || low.startsWith("надето")) hasUnequipBtn = true;
            if (low.equals("надеть") || low.startsWith("надеть ")) hasEquipBtn = true;

            if (i < hdrLimit) {
                if (awk == 0) {
                    Matcher m = HEADER_AWAKEN_ENH.matcher(raw);
                    if (m.find()) {
                        try {
                            awk = Integer.parseInt(m.group(1));
                            if (m.group(2) != null) enh = Integer.parseInt(m.group(2));
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (enh == 0 && i < 5) {
                    Matcher mp = HEADER_PLUS_ENH.matcher(raw);
                    if (mp.find()) {
                        try {
                            enh = Integer.parseInt(mp.group(1));
                        } catch (Exception ignored) {
                        }
                    }
                }
                if ((low.contains("физический урон") || low.contains("урон атаки") || low.contains("базовый урон"))
                        && !raw.contains("%")) {
                    double v = DruidKnowledgeBase.extractFirstNumber(raw);
                    if (v > flatAtk) flatAtk = v;
                }
                if (low.contains("защит") && !raw.contains("%")) {
                    double v = DruidKnowledgeBase.extractFirstNumber(raw);
                    if (v > baseDef) baseDef = v;
                }
            }
        }
        boolean isEquipped = hasUnequipBtn && !hasEquipBtn;
        return new ScannedItemHeader(isEquipped, flatAtk, baseDef, awk, enh);
    }

    static ItemRollModel calibrateRollModel(ScannedItemHeader hdr, List<ParsedAffixRow> rows) {
        int itemAwaken = hdr.awakenLevel;
        int enh = hdr.enhancementLevel;

        double priorUnitScale = 8.0;
        if (itemAwaken > 0) {
            double effEnh = enh <= 90 ? enh : (90.0 + 0.5 * (enh - 90.0));
            priorUnitScale = 17.2 * (1.0 + 0.16 * itemAwaken) * (1.0 + 0.025 * effEnh);
        }

        double lowerBound = 0.0;
        double upperBound = Double.POSITIVE_INFINITY;
        double weightedSum = 0.0;
        int uncappedCount = 0;

        double observedProcCap = 0.0;
        double observedBloodthirstCap = 0.0;

        for (ParsedAffixRow r : rows) {
            if (r.type == AffixType.EMPTY_POSITION || r.rolledValue <= 0.0) continue;
            double unboosted = r.rolledValue / r.awakenMultiplier();

            if (r.type.ignoresAffixCap && r.type.baseMin > 0.0 && r.type.baseMax > 0.0) {
                double rowLo = unboosted / r.type.baseMax;
                double rowHi = unboosted / r.type.baseMin;
                if (rowLo > lowerBound) lowerBound = rowLo;
                if (rowHi < upperBound) upperBound = rowHi;
                weightedSum += unboosted / r.type.baseAvg;
                uncappedCount++;
            } else if (r.type == AffixType.DOUBLE_DAMAGE || r.type == AffixType.DOUBLE_HIT) {
                if (unboosted > observedProcCap) observedProcCap = unboosted;
            } else if (r.type == AffixType.BLOODTHIRST) {
                if (unboosted > observedBloodthirstCap) observedBloodthirstCap = unboosted;
                if (itemAwaken == 0 && unboosted >= 16.0 && unboosted <= 25.0) {
                    itemAwaken = (int) Math.round(unboosted - 15.0);
                }
            }
        }

        double unitScale;
        if (uncappedCount > 0) {
            double avgEst = weightedSum / uncappedCount;
            if (itemAwaken > 0) {
                avgEst = 0.55 * avgEst + 0.45 * priorUnitScale;
            }
            if (lowerBound <= upperBound * 1.03) {
                double lo = Math.min(lowerBound, upperBound);
                double hi = Math.max(lowerBound, upperBound);
                unitScale = Math.max(lo, Math.min(hi, avgEst));
            } else {
                unitScale = Math.max(lowerBound * 0.95, avgEst);
            }
        } else if (observedProcCap > 0.0) {
            unitScale = (observedProcCap >= 35.0)
                    ? Math.max(priorUnitScale, (observedProcCap - 27.0) / 0.45)
                    : (observedProcCap / 1.5);
        } else {
            unitScale = priorUnitScale;
        }
        unitScale = Math.max(1.0, Math.min(150.0, unitScale));

        if (itemAwaken == 0 && unitScale >= 30.0) {
            itemAwaken = (unitScale >= 46.0) ? 6 : 5;
        }

        double procCapBase;
        if (observedProcCap > 0.0) {
            procCapBase = observedProcCap;
        } else if (unitScale >= 24.0) {
            procCapBase = 26.5 + 0.47 * unitScale;
        } else {
            procCapBase = unitScale * 1.5;
        }

        double bloodthirstCap = observedBloodthirstCap > 0.0
                ? observedBloodthirstCap
                : (15.0 + Math.max(0, itemAwaken) * 1.0);

        return new ItemRollModel(itemAwaken, enh, unitScale, procCapBase, bloodthirstCap);
    }

    public static int findAffixHeaderIndexFromStrings(List<String> lines) {
        if (lines == null) return -1;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String low = DruidKnowledgeBase.normalizeForMatch(raw);
            if (isAffixSectionHeaderLine(low, raw)) {
                return i;
            }
        }
        return -1;
    }

    static boolean isAffixSectionHeaderLine(String low, String raw) {
        return isAffixSectionHeaderLine(null, low, raw);
    }

    static boolean isAffixSectionHeaderLine(IdleArcOcrEngine.OcrLine ol, String low, String raw) {
        if (low == null || raw == null) return false;
        String rt = raw.trim();
        if (rt.isEmpty() || rt.startsWith("+") || rt.contains("%")) return false;
        if (low.contains("совместимые аффикс") || low.contains("все аффикс")
                || low.contains("выберите аффикс") || low.contains("нет аффикс")
                || low.contains("автонадписи") || low.contains("ячейка") || low.contains("чейка")
                || low.contains("уровень") || low.contains("урсг")) {
            return false;
        }
        if (low.contains("урон") || low.contains("интеллект") || low.contains("здоров")
                || low.contains("крит") || low.contains("уклонен") || low.contains("защит")
                || low.contains("спутника")) {
            return false;
        }
        if (low.startsWith("аффикс") || low.startsWith("аффuкс") || low.startsWith("аффnкс")
                || low.contains("позиции аффикс") || low.contains("позиций аффикс")
                || low.contains("аффиксы") || low.contains("аффиксов")) {
            return true;
        }
        String lettersOnly = low.replaceAll("[^а-яa-z]", "");
        if (lettersOnly.length() >= 5 && lettersOnly.length() <= 10
                && (lettersOnly.endsWith("ффиксы") || lettersOnly.endsWith("фиксы")
                    || lettersOnly.startsWith("аффикс") || lettersOnly.equals("эффиксы")
                    || lettersOnly.equals("оффиксы"))) {
            return true;
        }
        if (!low.contains("ранг") && !low.contains("уров") && !low.contains("гнезд") && !low.contains("гнёзд")) {
            Matcher cm = AFFIX_COUNTER_PAT.matcher(rt);
            if (cm.find() && rt.length() <= 28) {
                return true;
            }
            if (ol != null && ol.purpleRatio >= 0.16 && rt.length() <= 28
                    && (AFFIX_OCR_COUNTER_PAT.matcher(rt).find() || low.contains("5") || low.contains("аф"))) {
                return true;
            }
        }
        return false;
    }

    static int parseExpectedAffixCountFromHeader(String raw) {
        if (raw == null) return MAX_AFFIXES_PER_ITEM;
        Matcher m = AFFIX_COUNTER_PAT.matcher(raw);
        if (m.find()) {
            int x = Integer.parseInt(m.group(1));
            return Math.max(0, Math.min(MAX_AFFIXES_PER_ITEM, x));
        }
        Matcher om = AFFIX_OCR_COUNTER_PAT.matcher(raw);
        if (om.find()) {
            int x = Integer.parseInt(om.group(1));
            return Math.max(1, Math.min(MAX_AFFIXES_PER_ITEM, x));
        }
        return MAX_AFFIXES_PER_ITEM;
    }

    static List<ParsedAffixRow> extractSecondaryAffixRows(List<IdleArcOcrEngine.OcrLine> ocrLines,
                                                          SlotAffixAdvisor.GearSlot headerSlot,
                                                          boolean isShieldBase) {
        int startIdx = 0;
        int endIdx = ocrLines.size();
        boolean sawExplicitAffixHeader = false;
        int maxAllowedRows = MAX_AFFIXES_PER_ITEM;

        for (int i = 0; i < ocrLines.size(); i++) {
            IdleArcOcrEngine.OcrLine ol = ocrLines.get(i);
            String raw = ol.text;
            String low = DruidKnowledgeBase.normalizeForMatch(raw);
            if (!sawExplicitAffixHeader && isAffixSectionHeaderLine(ol, low, raw)) {
                sawExplicitAffixHeader = true;
                startIdx = i + 1;
                maxAllowedRows = parseExpectedAffixCountFromHeader(raw);
                continue;
            }
            if (sawExplicitAffixHeader && i >= startIdx && isSectionStrictlyBelowAffixes(low)) {
                endIdx = i;
                break;
            }
        }

        if (maxAllowedRows <= 0) return new ArrayList<>();

        // Если заголовок «АФФИКСЫ» почему-либо не распознался (обрезан сверху или перекрыт),
        // НЕ обрезаем сканирование на «ГНЁЗДА» / «Спутника:» (они находятся ВЫШЕ аффиксов!),
        // а опираемся на нижний футер «Аффиксы 5/5 · Ячейка ...» / кнопки действий СНИЗУ аффиксов!
        if (!sawExplicitAffixHeader) {
            for (int i = 0; i < ocrLines.size(); i++) {
                String low = DruidKnowledgeBase.normalizeForMatch(ocrLines.get(i).text);
                if (isSectionStrictlyBelowAffixes(low)) {
                    endIdx = i;
                    break;
                }
            }
            for (int i = endIdx - 1; i >= 0; i--) {
                String low = DruidKnowledgeBase.normalizeForMatch(ocrLines.get(i).text);
                if (isSectionStrictlyAboveAffixes(low)) {
                    startIdx = i + 1;
                    break;
                }
            }
        }

        // Склеиваем длинные двустрочные аффиксы («Здоровье за удар и за убийство» \n «+20%»)
        Set<AffixType> emptySeen = EnumSet.noneOf(AffixType.class);
        List<IdleArcOcrEngine.OcrLine> mergedSectionLines = new ArrayList<>();
        for (int i = startIdx; i < endIdx; i++) {
            IdleArcOcrEngine.OcrLine cur = ocrLines.get(i);
            String curRaw = cur.text.trim();
            if (curRaw.isEmpty()) continue;
            String curLow = DruidKnowledgeBase.normalizeForMatch(curRaw);

            if (i + 1 < endIdx && !curRaw.contains("+")) {
                IdleArcOcrEngine.OcrLine nxt = ocrLines.get(i + 1);
                String nxtRaw = nxt.text.trim();
                String nxtTrimmedBullet = nxtRaw.replaceFirst("^[.•·\\-\\s]+", "");
                String nxtLow = DruidKnowledgeBase.normalizeForMatch(nxtRaw);
                int maxLineGap = Math.max(85, Math.max(16, cur.y1 - cur.y0) * 3);
                if (nxtTrimmedBullet.startsWith("+")
                        && !hasStatNameBeforePlus(nxtTrimmedBullet)
                        && classifyAffixRowRobust(curLow, curRaw, nxtRaw.contains("%"), headerSlot, isShieldBase, emptySeen) != null
                        && classifyAffixName(nxtLow, nxtRaw, nxtRaw.contains("%"), headerSlot, isShieldBase) == null
                        && (nxt.y0 - cur.y1) <= maxLineGap) {
                    IdleArcOcrEngine.OcrLine combined = new IdleArcOcrEngine.OcrLine(
                            curRaw + " " + nxtTrimmedBullet,
                            cur.y0, nxt.y1,
                            Math.max(cur.cyanRatio, nxt.cyanRatio),
                            Math.max(cur.purpleRatio, nxt.purpleRatio),
                            Math.max(cur.badgeStrokes, nxt.badgeStrokes),
                            Math.max(cur.maxLum, nxt.maxLum));
                    mergedSectionLines.add(combined);
                    i++;
                    continue;
                }
            }
            mergedSectionLines.add(cur);
        }

        List<ParsedAffixRow> rawCandidates = new ArrayList<>();
        Set<AffixType> seenTypes = EnumSet.noneOf(AffixType.class);

        for (IdleArcOcrEngine.OcrLine ol : mergedSectionLines) {
            String raw = ol.text.trim();
            String low = DruidKnowledgeBase.normalizeForMatch(raw);

            if (raw.length() < 3 || isNonAffixMetadataLine(low)) continue;
            if (!sawExplicitAffixHeader && isLikelyImplicitHeaderLine(low, raw)) continue;

            if (low.contains("заполните позицию") || low.contains("пустая позиция")
                    || low.contains("выберите аффикс") || (low.contains("добавьте") && low.contains("кузниц"))) {
                rawCandidates.add(new ParsedAffixRow(
                        rawCandidates.size() + 1, AffixType.EMPTY_POSITION, raw, 0.0, false, 0, 0));
                if (sawExplicitAffixHeader && rawCandidates.size() >= maxAllowedRows) break;
                continue;
            }

            // Самоцветы, базовая защита/атака и имплиситы ВСЕГДА имеют формат «+Число Название»
            // (знак «+» стоит ПЕРЕД текстом), тогда как все 5 вторичных аффиксов в IdleArc имеют формат
            // «• Название стата +Число[%] [I..VIII]» (русское название стоит ПЕРЕД «+»)!
            if (!sawExplicitAffixHeader && !hasStatNameBeforePlus(raw)) {
                continue;
            }

            double statVal = extractPlusStatValue(raw);
            List<Double> nums = extractAllNumbers(raw);
            if (statVal <= 0.0 && nums.isEmpty()) continue;
            if (statVal <= 0.0) statVal = nums.get(0);

            boolean hasPct = raw.contains("%");
            AffixType type = classifyAffixRowRobust(low, raw, hasPct, headerSlot, isShieldBase, seenTypes);
            if (type == null) continue;

            int[] awaken = detectAwakeningOnRow(ol, raw);

            if (sawExplicitAffixHeader) {
                if (seenTypes.contains(type)) continue;
                seenTypes.add(type);
                rawCandidates.add(new ParsedAffixRow(
                        rawCandidates.size() + 1, type, raw, statVal, hasPct, awaken[0], awaken[1]));
                if (rawCandidates.size() >= maxAllowedRows) break;
            } else {
                // Сохраняем строгий экранный порядок сверху вниз без перестановки строк местами
                if (!seenTypes.contains(type)) {
                    seenTypes.add(type);
                    rawCandidates.add(new ParsedAffixRow(
                            rawCandidates.size() + 1, type, raw, statVal, hasPct, awaken[0], awaken[1]));
                }
            }
        }

        if (!sawExplicitAffixHeader && rawCandidates.size() < 2) {
            return Collections.emptyList();
        }

        int cap = Math.min(MAX_AFFIXES_PER_ITEM, maxAllowedRows);
        if (rawCandidates.size() > cap) {
            rawCandidates = new ArrayList<>(rawCandidates.subList(rawCandidates.size() - cap, rawCandidates.size()));
        }

        List<ParsedAffixRow> result = new ArrayList<>(rawCandidates.size());
        for (int i = 0; i < rawCandidates.size(); i++) {
            ParsedAffixRow r = rawCandidates.get(i);
            result.add(new ParsedAffixRow(
                    i + 1, r.type, r.rawLabel, r.rolledValue, r.valueHasPct, r.awakenTier, r.awakenBonusPct));
        }
        return result;
    }

    /**
     * Проверяет, что название стата идёт ПЕРЕД числовым бонусом «+Число» (формат вторичного аффикса:
     * «• Шанс крита +55% I»), а НЕ наоборот (формат камня/базы/имплисита: «+4.26% Шанс крита», «+6862 Защита»
     * или продолжения второй строки «+55% I»).
     */
    private static boolean hasStatNameBeforePlus(String raw) {
        if (raw == null) return false;
        String trimmed = raw.replaceFirst("^[.•·\\-\\s]+", "");
        if (trimmed.isEmpty() || trimmed.startsWith("+") || Character.isDigit(trimmed.charAt(0))) {
            return false;
        }
        int plusIdx = trimmed.indexOf('+');
        String before = plusIdx >= 0 ? trimmed.substring(0, plusIdx) : trimmed;
        if (before.indexOf(':') >= 0) return false; // «Спутника: +50.6% Урон питомца» — аспект дропа, не аффикс
        int letters = 0;
        for (int i = 0; i < before.length(); i++) {
            char c = before.charAt(i);
            if (plusIdx < 0 && Character.isDigit(c)) break;
            if (Character.isLetter(c)) letters++;
        }
        return letters >= 3;
    }

    /** Элементы интерфейса карточки предмета, которые гарантированно находятся СТРОГО НИЖЕ блока 5 аффиксов */
    private static boolean isSectionStrictlyBelowAffixes(String low) {
        return low.contains("ячейка") || low.contains("чейка")
                || (low.startsWith("аф") && (low.contains("шлем") || low.contains("оружи") || low.contains("факел")
                    || low.contains("брон") || low.contains("нагруд") || low.contains("перчат") || low.contains("сапог")
                    || low.contains("пояс") || low.contains("амулет") || low.contains("кольц") || low.contains("ур")))
                || low.contains("внешний вид") || low.contains("улучшить") || low.contains("учшить")
                || low.equals("пробуждение") || low.equals("стоимость") || low.startsWith("камни крафта")
                || low.startsWith("улучшение тира") || low.startsWith("улучшение уровня")
                || low.equals("надеть") || low.startsWith("снять") || low.equals("разобрать") || low.equals("продать")
                || low.contains("надеть предмет") || low.contains("перераспределить пробуждение")
                || low.contains("материалы пробуждения")
                || low.contains("двуручное") || low.contains("занимает слот") || low.startsWith("надето:");
    }

    /** Элементы интерфейса карточки предмета, которые находятся СТРОГО ВЫШЕ блока 5 аффиксов (гнёзда, база, имплиситы, аспект) */
    private static boolean isSectionStrictlyAboveAffixes(String low) {
        return low.contains("управление гнездами") || low.startsWith("гнезд") || low.startsWith("о гн")
                || low.contains("выпало на") || low.contains("выоало на") || low.contains("сияющий")
                || low.contains("все классовые навыки")
                || low.startsWith("спутника") || low.contains("спутника ")
                || low.startsWith("бонус добычи") || low.startsWith("бонус дропа");
    }

    /**
     * Двухступенчатый классификатор строки аффикса:
     * 1) Точное сопоставление по ключевым корням (classifyAffixName).
     * 2) Нечёткое (Levenshtein) сопоставление текста ДО знака «+» со словарём официальных русских названий
     *    аффиксов этого слота (исключая уже встреченные на этом же предмете типы seenTypes!).
     * Это на 100% исключает пропуск строки и сдвиг нумерации позиций #1..#5 даже при сильных искажениях OCR!
     */
    static AffixType classifyAffixRowRobust(String low, String raw, boolean hasPct,
                                            SlotAffixAdvisor.GearSlot headerSlot, boolean isShieldBase,
                                            Set<AffixType> seenTypes) {
        AffixType exact = classifyAffixName(low, raw, hasPct, headerSlot, isShieldBase);
        if (exact != null && (seenTypes == null || !seenTypes.contains(exact))) {
            return exact;
        }

        // Выделяем только текстовую часть названия аффикса ДО «+числа»
        int plusIdx = raw != null ? raw.indexOf('+') : -1;
        String rawPrefix = plusIdx > 0 ? raw.substring(0, plusIdx) : raw;
        String normLabel = DruidKnowledgeBase.normalizeForMatch(rawPrefix)
                .replaceAll("^[^а-яa-z]+", "")
                .replaceAll("[^а-яa-z\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normLabel.length() < 3) {
            return exact;
        }

        List<AffixType> candidates = (headerSlot != null
                && headerSlot != SlotAffixAdvisor.GearSlot.TALISMAN
                && headerSlot != SlotAffixAdvisor.GearSlot.PET)
                ? getCompatiblePool(headerSlot, isShieldBase)
                : Arrays.asList(AffixType.values());

        AffixType bestType = null;
        double bestScore = 0.0;

        for (AffixType cand : candidates) {
            if (cand == AffixType.EMPTY_POSITION) continue;
            if (seenTypes != null && seenTypes.contains(cand)) continue;

            for (String phrase : canonicalPhrasesFor(cand)) {
                double sim = normalizedEditSimilarity(normLabel, phrase);
                // Учёт совпадения наличия знака «%» у процентных vs плоских статов
                double pctBonus = (cand.isPercent == hasPct) ? 0.08 : -0.06;
                double total = sim + pctBonus;
                if (sim >= 0.48 && total > bestScore) {
                    bestScore = total;
                    bestType = cand;
                }
            }
        }
        return bestType != null ? bestType : exact;
    }

    private static String[] canonicalPhrasesFor(AffixType t) {
        switch (t) {
            case PET_DAMAGE: return new String[]{"урон питомца"};
            case DAMAGE_REDUCTION: return new String[]{"снижение урона"};
            case DODGE_CHANCE: return new String[]{"шанс уклонения"};
            case MAX_HEALTH: return new String[]{"макс здоровье", "максимальное здоровье"};
            case LOCAL_PERCENT_DEFENSE: return new String[]{"увеличенная защита"};
            case LOCAL_FLAT_DEFENSE: return new String[]{"доп защита"};
            case ELEMENTAL_CONVERT: return new String[]{"преобразуется в", "преобразовано в"};
            case DOUBLE_DAMAGE: return new String[]{"шанс двойного урона"};
            case DOUBLE_HIT: return new String[]{"шанс двойного удара"};
            case CRIT_DAMAGE: return new String[]{"крит урон", "критический урон"};
            case CRIT_CHANCE: return new String[]{"шанс крита"};
            case INTELLIGENCE: return new String[]{"интеллект"};
            case DEXTERITY: return new String[]{"ловкость"};
            case STRENGTH: return new String[]{"сила"};
            case BLOODTHIRST: return new String[]{"здоровье за удар и за убийство", "жажда крови"};
            case ADDED_DAMAGE: return new String[]{"доп урон", "дополнительный урон"};
            case DEFENSE_PERCENT: return new String[]{"защита"};
            case LIFE_ON_HIT: return new String[]{"здоровье за удар"};
            case LIFE_ON_KILL: return new String[]{"здоровье за убийство"};
            case EXP_GAIN: return new String[]{"бонус опыта"};
            case GOLD_GAIN: return new String[]{"бонус золота"};
            default: return new String[0];
        }
    }

    private static double normalizedEditSimilarity(String a, String b) {
        int la = a.length(), lb = b.length();
        int maxLen = Math.max(la, lb);
        if (maxLen == 0) return 1.0;
        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return 1.0 - ((double) prev[lb] / maxLen);
    }

    private static boolean isNonAffixMetadataLine(String low) {
        return low.contains("очки снаряжения") || low.startsWith("уровень") || low.startsWith("требуется")
                || low.contains("инфернальн") || low.contains("мифическ") || low.contains("легендарн")
                || low.contains("эпическ") || low.contains("редкий") || low.contains("необычн")
                || low.equals("базовый урон") || low.equals("надет") || low.equals("надето")
                || low.contains("пробуждение:") || low.contains("ранг пробуждения")
                || low.contains("сила атаки талисмана") || low.contains("бонусы жертв")
                || low.contains("совместимые аффиксы") || low.contains("урона не добавляет");
    }

    private static boolean isLikelyImplicitHeaderLine(String low, String raw) {
        if (low.contains("все классовые навыки")) return true;
        if (low.startsWith("защита предмета") || low.startsWith("базовый урон")) return true;
        if (low.startsWith("урон атаки") && !raw.trim().startsWith("+")) return true;
        if (low.startsWith("защита:") || (low.startsWith("защита ") && !raw.contains("+") && !raw.contains("%"))) return true;
        return false;
    }

    static AffixType classifyAffixName(String low, String raw, boolean hasPct,
                                       SlotAffixAdvisor.GearSlot headerSlot, boolean isShieldBase) {
        if (low.contains("преобразуетс") || low.contains("преобразование в") || low.contains("преобразовано в")
                || low.contains("конверс")) {
            return AffixType.ELEMENTAL_CONVERT;
        }
        if (low.contains("урон питомц") || low.contains("урона питомц") || low.contains("урону питомц")
                || low.contains("урон пит") || (low.contains("питомц") && low.contains("урон"))) {
            return AffixType.PET_DAMAGE;
        }
        if ((low.contains("за удар") && (low.contains("убийств") || low.contains("и за") || hasPct))
                || low.contains("жажда крови") || low.contains("жажды крови")) {
            return AffixType.BLOODTHIRST;
        }
        if (headerSlot == SlotAffixAdvisor.GearSlot.TORCH && !isShieldBase && low.contains("здоров")) {
            return AffixType.BLOODTHIRST;
        }

        boolean slotForbidsDoubleHit = (headerSlot == SlotAffixAdvisor.GearSlot.TORCH
                || headerSlot == SlotAffixAdvisor.GearSlot.HELMET
                || headerSlot == SlotAffixAdvisor.GearSlot.CHEST
                || headerSlot == SlotAffixAdvisor.GearSlot.GLOVES
                || headerSlot == SlotAffixAdvisor.GearSlot.BOOTS);

        if (low.contains("двойного урон") || low.contains("двойной урон")) {
            return AffixType.DOUBLE_DAMAGE;
        }
        if (low.contains("двойного удар") || low.contains("двойной удар")
                || (low.contains("двойного ") && !low.contains("урон"))) {
            return slotForbidsDoubleHit ? AffixType.DOUBLE_DAMAGE : AffixType.DOUBLE_HIT;
        }

        if (low.contains("шанс крит") || low.contains("крит. шанс") || low.contains("крит. удара")
                || low.contains("критического удара")) {
            return AffixType.CRIT_CHANCE;
        }
        if (low.contains("крит. урон") || low.contains("крит урон") || low.contains("критический урон")
                || (low.contains("крит") && low.contains("урон"))) {
            return AffixType.CRIT_DAMAGE;
        }

        if (low.contains("интеллект")) return AffixType.INTELLIGENCE;
        if (low.contains("ловкост")) return AffixType.DEXTERITY;
        if ((low.startsWith("сила") || low.contains(" к силе") || low.contains("силы"))
                && !low.contains("талисман") && !low.contains("слов")) {
            return AffixType.STRENGTH;
        }

        if (low.contains("снижение урона") || low.contains("получаемого урона")) return AffixType.DAMAGE_REDUCTION;
        if (low.contains("уклонен")) return AffixType.DODGE_CHANCE;

        if (low.contains("бонус опыт") || (low.contains("опыт") && hasPct) || low.startsWith("опыт")) {
            return AffixType.EXP_GAIN;
        }
        if (low.contains("бонус золот") || (low.contains("золот") && hasPct) || low.startsWith("золото")) {
            return AffixType.GOLD_GAIN;
        }

        if (headerSlot == SlotAffixAdvisor.GearSlot.BELT && low.contains("здоров")) {
            return AffixType.MAX_HEALTH;
        }
        if (low.contains("за удар") && (low.contains("здоров") || low.contains("hp") || low.contains("нр"))) {
            return hasPct ? AffixType.BLOODTHIRST : AffixType.LIFE_ON_HIT;
        }
        if (low.contains("за убийств") && (low.contains("здоров") || low.contains("hp") || low.contains("нр"))) {
            return hasPct ? AffixType.BLOODTHIRST : AffixType.LIFE_ON_KILL;
        }
        if (low.contains("макс. здоров") || low.contains("макс здоров") || low.contains("максимальное здоров")
                || low.contains("к здоровью") || low.startsWith("здоровье")) {
            return AffixType.MAX_HEALTH;
        }

        if (low.contains("увеличенная защит")) return AffixType.LOCAL_PERCENT_DEFENSE;
        if (low.contains("доп. защит") || low.contains("доп защит")) return AffixType.LOCAL_FLAT_DEFENSE;
        if (low.contains("защит")) {
            boolean isJewelry = (headerSlot == SlotAffixAdvisor.GearSlot.BELT
                    || headerSlot == SlotAffixAdvisor.GearSlot.AMULET
                    || headerSlot == SlotAffixAdvisor.GearSlot.RING);
            if (isJewelry) return AffixType.DEFENSE_PERCENT;
            return hasPct ? AffixType.LOCAL_PERCENT_DEFENSE : AffixType.LOCAL_FLAT_DEFENSE;
        }

        if (low.contains("доп. урон") || low.contains("доп урон") || low.contains("дополнительный урон")
                || low.contains("к урону") || low.contains("фикс. урон")) {
            return AffixType.ADDED_DAMAGE;
        }
        return null;
    }

    private static int[] detectAwakeningOnRow(IdleArcOcrEngine.OcrLine ol, String raw) {
        int stacks = 0;
        int pct = 0;

        Matcher pm = EXPLICIT_AWAKEN_PCT_PAT.matcher(raw);
        if (pm.find()) {
            if (pm.group(1) != null) {
                pct = Integer.parseInt(pm.group(1));
                stacks = Math.max(1, pct / 10);
            } else if (pm.group(2) != null) {
                stacks = Integer.parseInt(pm.group(2));
                pct = stacks * 10;
            }
        }

        if (pct == 0) {
            Matcher plusM = PLUS_STAT_PAT.matcher(raw);
            if (plusM.find()) {
                String tail = raw.substring(plusM.end()).trim();
                if (!tail.isEmpty()) {
                    Matcher sm = ROMAN_SUFFIX_PAT.matcher(" " + tail);
                    if (sm.find()) {
                        int rVal = parseRoman(sm.group(1));
                        if (rVal >= 2 || (rVal == 1 && (ol.hasAwakenHighlight() || !sm.group(1).equals("1")))) {
                            stacks = rVal;
                            pct = stacks * 10;
                        }
                    }
                }
            }
        }

        if (pct == 0) {
            Matcher prefM = ROMAN_PREFIX_PAT.matcher(raw);
            if (prefM.find()) {
                int rVal = parseRoman(prefM.group(1));
                if (rVal >= 2 || ol.hasAwakenHighlight() || raw.trim().matches("^(?:I|\\[I\\])\\s+\\+.*")) {
                    stacks = rVal;
                    pct = stacks * 10;
                }
            }
        }

        if (ol.hasAwakenHighlight()) {
            if (ol.badgeStrokes > stacks) {
                stacks = ol.badgeStrokes;
                pct = stacks * 10;
            } else if (pct == 0) {
                stacks = 1;
                pct = 10;
            }
        }
        return new int[]{stacks, pct};
    }

    private static int parseRoman(String s) {
        if (s == null) return 0;
        String u = s.trim()
                .replace('l', 'I').replace('1', 'I').replace('|', 'I')
                .replace('!', 'I').replace('і', 'I').replace('І', 'I')
                .replace('v', 'V').replace('x', 'X');
        switch (u) {
            case "I": return 1;
            case "II": return 2;
            case "III": return 3;
            case "IV": return 4;
            case "V": return 5;
            case "VI": return 6;
            case "VII": return 7;
            case "VIII": return 8;
            default: return 0;
        }
    }

    private static double extractPlusStatValue(String raw) {
        if (raw == null) return 0.0;
        String cleaned = raw.replaceAll("(?<=\\d)\\s+(?=[0-9.,])", "")
                            .replaceAll("(?<=[.,])\\s+(?=\\d)", "");
        Matcher m = PLUS_STAT_PAT.matcher(cleaned);
        if (m.find()) {
            String numStr = m.group(1).replace(" ", "").replace(',', '.');
            try {
                return Double.parseDouble(numStr);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0;
    }

    private static List<Double> extractAllNumbers(String s) {
        List<Double> out = new ArrayList<>();
        String cleaned = s.replaceAll("(?<=\\d)\\s+(?=[0-9.,])", "")
                          .replaceAll("(?<=[.,])\\s+(?=\\d)", "");
        Matcher m = NUMBER_PAT.matcher(cleaned);
        while (m.find()) {
            try {
                out.add(Double.parseDouble(m.group(1).replace(',', '.')));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static SlotAffixAdvisor.GearSlot inferSlotFromObservedAffixes(
            List<ParsedAffixRow> rows, List<String> plain) {
        StringBuilder all = new StringBuilder();
        for (String s : plain) all.append(' ').append(DruidKnowledgeBase.normalizeForMatch(s));
        String full = all.toString();

        for (ParsedAffixRow r : rows) {
            if (r.type == AffixType.BLOODTHIRST) return SlotAffixAdvisor.GearSlot.TORCH;
            if (r.type == AffixType.ELEMENTAL_CONVERT) return SlotAffixAdvisor.GearSlot.WEAPON;
        }

        boolean hasJewelryOnly = false, hasWeaponOrJewelry = false, hasArmorOnlyDef = false;
        boolean hasDR = false, hasDodge = false, hasMaxHp = false, hasLoHOrLoK = false;

        for (ParsedAffixRow r : rows) {
            if (r.type == AffixType.ADDED_DAMAGE || r.type == AffixType.DEFENSE_PERCENT) hasJewelryOnly = true;
            if (r.type == AffixType.DOUBLE_HIT || r.type == AffixType.INTELLIGENCE
                    || r.type == AffixType.DEXTERITY || r.type == AffixType.STRENGTH) hasWeaponOrJewelry = true;
            if (r.type == AffixType.LOCAL_FLAT_DEFENSE || r.type == AffixType.LOCAL_PERCENT_DEFENSE) hasArmorOnlyDef = true;
            if (r.type == AffixType.DAMAGE_REDUCTION) hasDR = true;
            if (r.type == AffixType.DODGE_CHANCE) hasDodge = true;
            if (r.type == AffixType.MAX_HEALTH) hasMaxHp = true;
            if (r.type == AffixType.LIFE_ON_HIT || r.type == AffixType.LIFE_ON_KILL) hasLoHOrLoK = true;
        }

        if (hasJewelryOnly) return hasLoHOrLoK ? SlotAffixAdvisor.GearSlot.RING : SlotAffixAdvisor.GearSlot.AMULET;
        if (hasArmorOnlyDef) return hasDR ? SlotAffixAdvisor.GearSlot.CHEST : SlotAffixAdvisor.GearSlot.GLOVES;
        if (hasDR || hasDodge) return (hasMaxHp || full.contains("защита")) ? SlotAffixAdvisor.GearSlot.CHEST : SlotAffixAdvisor.GearSlot.TORCH;
        if (hasWeaponOrJewelry) {
            if (hasMaxHp) return hasLoHOrLoK ? SlotAffixAdvisor.GearSlot.AMULET : SlotAffixAdvisor.GearSlot.BELT;
            return SlotAffixAdvisor.GearSlot.WEAPON;
        }
        return SlotAffixAdvisor.GearSlot.WEAPON;
    }

    private static String formatPoolSummary(SlotAffixAdvisor.GearSlot slot, List<AffixType> pool) {
        List<String> prefixes = new ArrayList<>();
        List<String> suffixes = new ArrayList<>();
        for (AffixType t : pool) {
            if (t.kind == AffixKind.PREFIX) {
                prefixes.add(t == AffixType.ELEMENTAL_CONVERT ? "6× Конверсия стихий %" : (t.ruName + (t.isPercent ? " %" : "")));
            } else if (t.kind == AffixKind.SUFFIX) {
                suffixes.add(t.ruName + (t.isPercent ? " %" : ""));
            }
        }
        int officialCount = (slot == SlotAffixAdvisor.GearSlot.WEAPON) ? (pool.size() + 5) : pool.size();
        return officialCount + " аффиксов — [П]: " + joinSimple(prefixes) + " · [С]: " + joinSimple(suffixes);
    }

    private static int findFirstUnawakenedRerollPos(List<ParsedAffixRow> rows, Set<AffixType> idealSet) {
        for (ParsedAffixRow r : rows) {
            if (r.awakenTier == 0 && !idealSet.contains(r.type)) return r.index;
        }
        return -1;
    }

    private static String formatAwakenBadge(ParsedAffixRow r) {
        if (r.awakenTier <= 0) return "";
        return " [💎 " + roman(r.awakenTier) + " · ×" + fmt2(r.awakenMultiplier()) + "]";
    }

    private static String roman(int tier) {
        switch (tier) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            case 6: return "VI";
            case 7: return "VII";
            case 8: return "VIII";
            default: return String.valueOf(tier);
        }
    }

    private static String formatStatRoll(AffixType t, double val) {
        if (val <= 0.0) return "—";
        return t.isPercent ? ("+" + fmt1(val) + "%") : ("+" + Math.round(val));
    }

    private static String formatRollBand(AffixType t, double minV, double avgV, double maxV) {
        if (!t.ignoresAffixCap || Math.abs(maxV - minV) < 2.0) {
            return "(" + formatStatRoll(t, avgV) + ")";
        }
        return "(~" + formatStatRoll(t, avgV) + ", диап. " + formatStatRoll(t, minV) + ".." + formatStatRoll(t, maxV) + ")";
    }

    private static String joinSimple(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private static String fmt0(double v) {
        return String.format(Locale.US, "%.0f", v);
    }
}
