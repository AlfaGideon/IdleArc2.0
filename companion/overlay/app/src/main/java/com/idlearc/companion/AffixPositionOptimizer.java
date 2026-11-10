package com.idlearc.companion;

import java.util.ArrayList;
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
 * По-позиционный оптимизатор аффиксов снаряжения Кузницы и карточек предметов (IdleArc 1.3.15).
 *
 * Верифицировано на 100% по серверному конфигу https://idlearc.com/api/config/game и libapp.so:
 * 1) Все 26 официальных аффиксов игры (12 Префиксов [П] + 14 Суффиксов [С]) и их точные списки
 *    совместимости applicable_to для каждого типа предмета (Оружие=18, Факел=9, Щит=8, Шлем=11,
 *    Броня=11, Перчатки=10, Сапоги=10, Пояс=13, Амулет=15, Кольцо=15).
 * 2) Двухрежимное масштабирование статов на высоких тирах (A1..A6):
 *    - статы БЕЗ аффикс-капа (Урон питомца %, Интеллект, Ловкость, Сила, Крит. урон %, Шанс крита %,
 *      Опыт %, Золото %, Здоровье, Доп. урон, Защита) растут линейно с масштабом предмета unitScale;
 *    - статы С АФФИКС-КАПОМ (Шанс двойного урона %, Шанс двойного удара %, Здоровье за удар/убийство %
 *      с плоским ростом 15%+1%×A, Снижение урона % с замороженным капом) упираются в потолок до
 *      умножения на позиционное Пробуждение [I..V].
 * 3) Официальные формулы характеристик из primary_stats (/api/config/game):
 *    - 1 Интеллект = +0.20% Шанса двойного урона (множит И героя, И питомца!) + 0.38% Атаки + 0.05% Пета;
 *    - 1 Ловкость  = +0.10% Урона питомца + 0.05% Шанса уклонения (при капе 95% уклонение сгорает);
 *    - 1 Сила      = +0.75% Физ. урона + 0.50% Макс. HP + 0.50% Возмездия;
 *    - Шанс крита свыше 100% конвертируется 1:1 в Крит. урон.
 */
public class AffixPositionOptimizer {

    /** Жёсткий лимит позиций вторичных аффиксов на любом предмете в IdleArc — строго 5 из 5 (5/5). */
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

    /** Все официальные вторичные аффиксы снаряжения IdleArc 1.3.15 (/api/config/game) */
    public enum AffixType {
        // === ПРЕФИКСЫ (12 в серверном конфиге: 6 обычных + 6 стихийных конверсий оружия) ===
        PET_DAMAGE(AffixKind.PREFIX, "Урон питомца", true, true, 2.0, 8.0),
        DAMAGE_REDUCTION(AffixKind.PREFIX, "Снижение урона", true, false, 1.0, 3.0),
        DODGE_CHANCE(AffixKind.PREFIX, "Шанс уклонения", true, false, 0.5, 1.0),
        MAX_HEALTH(AffixKind.PREFIX, "Макс. здоровье", false, true, 10.0, 50.0),
        LOCAL_PERCENT_DEFENSE(AffixKind.PREFIX, "Увеличенная защита", true, true, 8.0, 23.0),
        LOCAL_FLAT_DEFENSE(AffixKind.PREFIX, "Доп. защита", false, true, 3.0, 11.0),
        ELEMENTAL_CONVERT(AffixKind.PREFIX, "Конверсия урона в стихию", true, false, 3.0, 5.0),

        // === СУФФИКСЫ (14 в серверном конфиге) ===
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

        // Пустой слот аффикса («Пустая позиция — добавьте в Кузнице»)
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

    /** Распознанная позиция аффикса на предмете (#1..#5) */
    public static class ParsedAffixRow {
        public final int index;             // 1..5 сверху вниз
        public final AffixType type;
        public final String rawLabel;
        public final double rolledValue;    // уже с учётом пробуждения на карточке
        public final boolean valueHasPct;
        public final int awakenTier;        // 0 = без пробуждения, 1 = [I], 2 = [II], 3 = [III]...
        public final int awakenBonusPct;    // +10% за уровень пробуждения ([I]=10, [II]=20, [III]=30...)

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

    /** Калиброванная модель роллов конкретного отсканированного предмета */
    public static class ItemRollModel {
        public final int itemAwakenLevel;   // A0..A6 из шапки карточки (например A5 или A6)
        public final int enhancementLevel;  // +0..+120 из шапки карточки (например +5 или +30)
        public final double unitScale;      // цена 1 ед. base для безкаповых аффиксов (~38 на A5+5, ~51.5 на A6+30)
        public final double procCapBase;    // потолок базы каповых проков DD/DH до позиционного [I..V] (~43% на A5+5, ~51% на A6+30)
        public final double bloodthirstCap; // потолок of Bloodthirst до позиционного [I..V] (15% + 1% * A)

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
                double rawAvg = t.baseAvg * unitScale;
                return Math.min(rawAvg, procCapBase) * awakenMult;
            }
            if (t == AffixType.BLOODTHIRST) {
                double rawAvg = t.baseAvg * unitScale;
                return Math.min(rawAvg, bloodthirstCap) * awakenMult;
            }
            if (t == AffixType.DAMAGE_REDUCTION) {
                double rawAvg = t.baseAvg * unitScale;
                return Math.min(rawAvg, 14.0) * awakenMult;
            }
            if (t == AffixType.DODGE_CHANCE) {
                double rawAvg = t.baseAvg * unitScale;
                return Math.min(rawAvg, 25.0) * awakenMult;
            }
            if (t == AffixType.ELEMENTAL_CONVERT) {
                double rawAvg = t.baseAvg * unitScale;
                return Math.min(rawAvg, 100.0) * awakenMult;
            }
            return t.baseAvg * unitScale * awakenMult;
        }

        public double expectedMinRoll(AffixType t, double awakenMult) {
            if (t == AffixType.EMPTY_POSITION) return 0.0;
            if (!t.ignoresAffixCap) {
                double cap = (t == AffixType.BLOODTHIRST) ? bloodthirstCap
                        : ((t == AffixType.DAMAGE_REDUCTION) ? 14.0 : procCapBase);
                return Math.min(t.baseMin * unitScale, cap) * awakenMult;
            }
            return t.baseMin * unitScale * awakenMult;
        }

        public double expectedMaxRoll(AffixType t, double awakenMult) {
            if (t == AffixType.EMPTY_POSITION) return 0.0;
            if (!t.ignoresAffixCap) {
                double cap = (t == AffixType.BLOODTHIRST) ? bloodthirstCap
                        : ((t == AffixType.DAMAGE_REDUCTION) ? 14.0 : procCapBase);
                return Math.min(t.baseMax * unitScale, cap) * awakenMult;
            }
            return t.baseMax * unitScale * awakenMult;
        }
    }

    /** Итог расчёта оптимальной раскладки аффиксов по позициям */
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

    /**
     * 100% точный пул совместимых вторичных аффиксов слота из https://idlearc.com/api/config/game (`applicable_to`).
     */
    public static List<AffixType> getCompatiblePool(SlotAffixAdvisor.GearSlot slot) {
        return getCompatiblePool(slot, false);
    }

    public static List<AffixType> getCompatiblePool(SlotAffixAdvisor.GearSlot slot, boolean isShieldBase) {
        if (slot == null) slot = SlotAffixAdvisor.GearSlot.WEAPON;
        List<AffixType> p = new ArrayList<>();
        switch (slot) {
            case WEAPON:
                Collections.addAll(p,
                        AffixType.PET_DAMAGE,
                        AffixType.DOUBLE_HIT,
                        AffixType.DOUBLE_DAMAGE,
                        AffixType.CRIT_DAMAGE,
                        AffixType.CRIT_CHANCE,
                        AffixType.INTELLIGENCE,
                        AffixType.DEXTERITY,
                        AffixType.STRENGTH,
                        AffixType.LIFE_ON_HIT,
                        AffixType.LIFE_ON_KILL,
                        AffixType.EXP_GAIN,
                        AffixType.GOLD_GAIN,
                        AffixType.ELEMENTAL_CONVERT);
                break;

            case TORCH:
                p.add(AffixType.PET_DAMAGE);
                p.add(AffixType.DAMAGE_REDUCTION);
                p.add(AffixType.DODGE_CHANCE);
                if (!isShieldBase) {
                    p.add(AffixType.BLOODTHIRST);
                }
                p.add(AffixType.DOUBLE_DAMAGE);
                p.add(AffixType.CRIT_DAMAGE);
                p.add(AffixType.CRIT_CHANCE);
                p.add(AffixType.EXP_GAIN);
                p.add(AffixType.GOLD_GAIN);
                break;

            case HELMET:
            case CHEST:
                Collections.addAll(p,
                        AffixType.PET_DAMAGE,
                        AffixType.DAMAGE_REDUCTION,
                        AffixType.MAX_HEALTH,
                        AffixType.LOCAL_PERCENT_DEFENSE,
                        AffixType.LOCAL_FLAT_DEFENSE,
                        AffixType.DODGE_CHANCE,
                        AffixType.DOUBLE_DAMAGE,
                        AffixType.CRIT_DAMAGE,
                        AffixType.CRIT_CHANCE,
                        AffixType.EXP_GAIN,
                        AffixType.GOLD_GAIN);
                break;

            case GLOVES:
            case BOOTS:
                Collections.addAll(p,
                        AffixType.PET_DAMAGE,
                        AffixType.MAX_HEALTH,
                        AffixType.LOCAL_PERCENT_DEFENSE,
                        AffixType.LOCAL_FLAT_DEFENSE,
                        AffixType.DODGE_CHANCE,
                        AffixType.DOUBLE_DAMAGE,
                        AffixType.CRIT_DAMAGE,
                        AffixType.CRIT_CHANCE,
                        AffixType.EXP_GAIN,
                        AffixType.GOLD_GAIN);
                break;

            case BELT:
                Collections.addAll(p,
                        AffixType.PET_DAMAGE,
                        AffixType.MAX_HEALTH,
                        AffixType.DOUBLE_HIT,
                        AffixType.DOUBLE_DAMAGE,
                        AffixType.CRIT_DAMAGE,
                        AffixType.CRIT_CHANCE,
                        AffixType.INTELLIGENCE,
                        AffixType.DEXTERITY,
                        AffixType.STRENGTH,
                        AffixType.ADDED_DAMAGE,
                        AffixType.DEFENSE_PERCENT,
                        AffixType.EXP_GAIN,
                        AffixType.GOLD_GAIN);
                break;

            case AMULET:
            case RING:
            default:
                Collections.addAll(p,
                        AffixType.PET_DAMAGE,
                        AffixType.MAX_HEALTH,
                        AffixType.DOUBLE_HIT,
                        AffixType.DOUBLE_DAMAGE,
                        AffixType.CRIT_DAMAGE,
                        AffixType.CRIT_CHANCE,
                        AffixType.INTELLIGENCE,
                        AffixType.DEXTERITY,
                        AffixType.STRENGTH,
                        AffixType.ADDED_DAMAGE,
                        AffixType.DEFENSE_PERCENT,
                        AffixType.LIFE_ON_HIT,
                        AffixType.LIFE_ON_KILL,
                        AffixType.EXP_GAIN,
                        AffixType.GOLD_GAIN);
                break;
        }
        return p;
    }

    /**
     * Главная точка входа: изолирует активное модальное окно карточки предмета/Кузницы
     * и выдаёт по-позиционный план #1..#N строго из официального пула этого слота.
     */
    public static OptimizationReport analyzeItemAffixes(
            List<IdleArcOcrEngine.OcrLine> ocrLines,
            SlotAffixAdvisor.GearSlot explicitSlot,
            DruidProfile profile) {
        if (ocrLines == null || ocrLines.isEmpty()) return null;

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

        // СТРОГАЯ ГАРАНТИЯ: пул возможных замен берётся ТОЛЬКО из официального серверного пула этого слота!
        List<AffixType> pool = new ArrayList<>(getCompatiblePool(detectedSlot, isShieldBase));

        ItemRollModel rollModel = calibrateRollModel(plain, rows);
        int n = rows.size();

        // Карта реальных небустованных значений статов, которые УЖЕ пойманы на этом предмете
        final Map<AffixType, Double> observedUnboosted = new HashMap<>();
        for (ParsedAffixRow r : rows) {
            if (r.type != AffixType.EMPTY_POSITION && r.rolledValue > 0.0) {
                observedUnboosted.put(r.type, r.rolledValue / r.awakenMultiplier());
            }
        }

        // 1) Для выбора ТОП-N типов аффиксов в пуле слота учитываем потенциал стата (или реальный высокий ролл, если он выше среднего)
        final Map<AffixType, Double> selectionValues = new HashMap<>();
        // 2) Для распределения позиционных Пробуждений [III..I] учитываем ФАКТИЧЕСКИЙ ролл уже стоящего на предмете стата
        //    (чтобы никогда не снимать [II]/[III] с топ-ролла Урона питомца +494% ради мин-ролла Крит. урона +51%!)
        final Map<AffixType, Double> awakenPlacementValues = new HashMap<>();

        for (AffixType cand : pool) {
            double avgRoll = rollModel.expectedAvgRoll(cand, 1.0);
            Double obs = observedUnboosted.get(cand);
            double selRoll = (obs != null) ? Math.max(avgRoll, obs) : avgRoll;
            double placeRoll = (obs != null) ? obs : avgRoll;
            selectionValues.put(cand, evaluateAffixValue(cand, selRoll, detectedSlot, profile));
            awakenPlacementValues.put(cand, evaluateAffixValue(cand, placeRoll, detectedSlot, profile));
        }

        List<AffixType> rankedPool = new ArrayList<>(pool);
        Collections.sort(rankedPool, new Comparator<AffixType>() {
            @Override
            public int compare(AffixType a, AffixType b) {
                double va = selectionValues.containsKey(a) ? selectionValues.get(a) : 0.0;
                double vb = selectionValues.containsKey(b) ? selectionValues.get(b) : 0.0;
                return Double.compare(vb, va);
            }
        });

        List<AffixType> chosenTop = new ArrayList<>();
        for (int i = 0; i < rankedPool.size() && chosenTop.size() < n; i++) {
            chosenTop.add(rankedPool.get(i));
        }
        while (chosenTop.size() < n && !rankedPool.isEmpty()) {
            chosenTop.add(rankedPool.get(0));
        }

        // Сортируем выбранные топ-N статов по реальной выгоде от множителя Пробуждения [III -> II -> I -> 0]
        Collections.sort(chosenTop, new Comparator<AffixType>() {
            @Override
            public int compare(AffixType a, AffixType b) {
                double va = awakenPlacementValues.containsKey(a) ? awakenPlacementValues.get(a) : 0.0;
                double vb = awakenPlacementValues.containsKey(b) ? awakenPlacementValues.get(b) : 0.0;
                return Double.compare(vb, va);
            }
        });

        // Разделяем позиции на УЖЕ ПОЙМАННЫЕ топ-статы (их НЕ надо перековывать!) и позиции ПОД ЗАМЕНУ:
        // это гарантирует, что компаньон НИКОГДА не предложит ловить на Позиции #3 стат, который УЖЕ стоит на Позиции #5!
        Set<AffixType> chosenTopSet = new HashSet<>(chosenTop);
        Set<AffixType> alreadyPresentTop = new HashSet<>();
        List<Integer> replaceIndices = new ArrayList<>();

        List<AffixType> idealByPos = new ArrayList<>(Collections.nCopies(n, AffixType.PET_DAMAGE));
        for (int i = 0; i < n; i++) {
            AffixType curT = rows.get(i).type;
            if (curT != AffixType.EMPTY_POSITION && chosenTopSet.contains(curT) && !alreadyPresentTop.contains(curT)) {
                idealByPos.set(i, curT); // уже пойманный BiS-аффикс остаётся на своей строке!
                alreadyPresentTop.add(curT);
            } else {
                replaceIndices.add(i);
            }
        }

        // Список реально ОТСУТСТВУЮЩИХ на предмете топ-аффиксов (их ровно столько же, сколько replaceIndices!)
        List<AffixType> missingStats = new ArrayList<>();
        for (AffixType st : chosenTop) {
            if (!alreadyPresentTop.contains(st)) {
                missingStats.add(st);
            }
        }
        // Сортируем позиции под замену по убыванию Пробуждения [III -> II -> I -> 0], чтобы самый сильный новый стат встал на лучший множитель
        Collections.sort(replaceIndices, new Comparator<Integer>() {
            @Override
            public int compare(Integer i1, Integer i2) {
                int c = Integer.compare(rows.get(i2).awakenBonusPct, rows.get(i1).awakenBonusPct);
                if (c != 0) return c;
                return Integer.compare(i1, i2);
            }
        });
        for (int k = 0; k < replaceIndices.size() && k < missingStats.size(); k++) {
            idealByPos.set(replaceIndices.get(k), missingStats.get(k));
        }

        // Дополнительно проверяем, лежит ли максимальное Пробуждение [III/II] на самом выгодном стате,
        // или после перековки выгодно поменять Пробуждения местами («Перераспределение пробуждения» в Кузнице)
        List<Integer> awakenSortedPos = new ArrayList<>();
        for (int i = 0; i < n; i++) awakenSortedPos.add(i);
        Collections.sort(awakenSortedPos, new Comparator<Integer>() {
            @Override
            public int compare(Integer i1, Integer i2) {
                int c = Integer.compare(rows.get(i2).awakenBonusPct, rows.get(i1).awakenBonusPct);
                if (c != 0) return c;
                return Integer.compare(i1, i2);
            }
        });
        // Какой бонус Пробуждения в идеале должен достаться каждому из chosenTop (уже отсортированному по awakenPlacementValues)
        Map<AffixType, Integer> optimalAwakenPctForStat = new HashMap<>();
        for (int k = 0; k < n && k < chosenTop.size(); k++) {
            optimalAwakenPctForStat.put(chosenTop.get(k), rows.get(awakenSortedPos.get(k)).awakenBonusPct);
        }

        double curSum = 0.0;
        double idealSum = 0.0;
        int keepCount = 0;
        int moveAwakenCount = 0;
        int rerollCount = 0;
        int awakenedRerollCount = 0;

        Set<AffixType> idealSet = new HashSet<>(idealByPos);
        for (int i = 0; i < n; i++) {
            ParsedAffixRow r = rows.get(i);
            AffixType target = idealByPos.get(i);
            double curV = evaluateAffixValue(r.type, r.rolledValue, detectedSlot, profile);
            int bestAwkPct = optimalAwakenPctForStat.containsKey(target)
                    ? optimalAwakenPctForStat.get(target)
                    : r.awakenBonusPct;
            double bestMult = 1.0 + (bestAwkPct / 100.0);
            double unboostedTarget = (r.type == target && r.rolledValue > 0)
                    ? Math.max(r.rolledValue / r.awakenMultiplier(), rollModel.expectedAvgRoll(target, 1.0))
                    : rollModel.expectedAvgRoll(target, 1.0);
            double idealV = evaluateAffixValue(target, unboostedTarget * bestMult, detectedSlot, profile);
            curSum += Math.max(0.0, curV);
            idealSum += Math.max(0.0, idealV);

            if (r.type == target) {
                if (bestAwkPct > r.awakenBonusPct && rerollCount == 0) {
                    moveAwakenCount++;
                } else {
                    keepCount++;
                }
            } else {
                rerollCount++;
                if (r.awakenTier > 0) {
                    awakenedRerollCount++;
                }
            }
        }

        double netGainDpsEq = Math.max(0.0, idealSum - curSum);

        ScreenAnalyzer.VerdictColor color;
        String badge;
        String headline;
        String slotChip = isShieldBase ? "🛡️ Щит" : detectedSlot.shortChip;
        String badgePrefix = slotInferred ? "📦 Предмет" : slotChip;
        String headlineSlot = slotInferred
                ? "📦 Предмет (нажми точный слот выше, если шапка скрыта)"
                : slotChip;

        if (keepCount == n) {
            color = ScreenAnalyzer.VerdictColor.GOOD;
            badge = "✅ " + badgePrefix + ": " + n + "/" + n + " ИДЕАЛ!";
            headline = "👑 " + headlineSlot + " — все " + n + " из " + n + " аффиксов стоят идеально!";
        } else if (rerollCount == 0 && moveAwakenCount > 0) {
            color = ScreenAnalyzer.VerdictColor.GOOD;
            badge = "💎 " + badgePrefix + ": перекинь Пробуждение (+" + fmt1(netGainDpsEq) + "%)";
            headline = "💎 " + headlineSlot + ": все статы топовые — переставь Пробуждение (+"
                    + fmt1(netGainDpsEq) + "% к ценности)!";
        } else if (rerollCount <= 2) {
            color = ScreenAnalyzer.VerdictColor.WARN;
            badge = "⚒️ " + badgePrefix + ": замени " + rerollCount + " из " + n + " (+" + fmt1(netGainDpsEq) + "%)";
            headline = "⚒️ " + headlineSlot + ": оставляем " + (n - rerollCount) + " из " + n
                    + ", меняем " + rerollCount + " поз. -> +" + fmt1(netGainDpsEq) + "% к эффективности!";
        } else {
            color = ScreenAnalyzer.VerdictColor.BAD;
            badge = "🔄 " + badgePrefix + ": под перековку " + rerollCount + "/" + n + " (+" + fmt1(netGainDpsEq) + "%)";
            headline = "🔄 " + headlineSlot + ": нужно заменить " + rerollCount + " из " + n
                    + " аффиксов (потенциал +" + fmt1(netGainDpsEq) + "%)";
        }

        String details = buildDetailedPlan(detectedSlot, slotInferred, isShieldBase, pool, rows, idealByPos,
                idealSet, optimalAwakenPctForStat, rollModel, profile, curSum, idealSum, keepCount, rerollCount,
                moveAwakenCount, awakenedRerollCount);

        return new OptimizationReport(detectedSlot, slotInferred, isShieldBase, rows, idealByPos,
                curSum, idealSum, badge, headline, details, color);
    }

    /** Находит индекс строки заголовка секции «АФФИКСЫ» по списку строк */
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
        if (low == null || raw == null) return false;
        String rt = raw.trim();
        if (rt.isEmpty() || rt.startsWith("+") || rt.contains("%")) return false;
        if (low.contains("совместимые аффикс") || low.contains("все аффикс")
                || low.contains("выберите аффикс") || low.contains("нет аффикс")
                || low.contains("автонадписи")) {
            return false;
        }
        if (low.contains("урон") || low.contains("интеллект") || low.contains("здоров")
                || low.contains("крит") || low.contains("уклонен") || low.contains("защит")) {
            return false;
        }
        if (low.startsWith("аффикс") || low.startsWith("аффuкс") || low.startsWith("аффnкс")
                || low.contains("позиции аффикс") || low.contains("позиций аффикс")
                || low.contains("аффиксы") || low.contains("аффиксов")) {
            return true;
        }
        String lettersOnly = low.replaceAll("[^а-яa-z]", "");
        if (lettersOnly.length() >= 6 && lettersOnly.length() <= 9
                && (lettersOnly.endsWith("ффиксы") || lettersOnly.startsWith("аффикс")
                    || lettersOnly.equals("эффиксы") || lettersOnly.equals("оффиксы"))) {
            return true;
        }
        if (!low.contains("ранг") && !low.contains("уров") && !low.contains("гнезд") && !low.contains("гнёзд")) {
            Matcher cm = AFFIX_COUNTER_PAT.matcher(rt);
            if (cm.find() && rt.length() <= 28) {
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

    /**
     * Извлекает до 5 вторичных аффиксов предмета из активного модального окна (со склейкой 2-строчных переносов
     * вида «Здоровье за удар и за убийство» + «+20%» и распознаванием голубых/фиолетовых значков [I..V]).
     */
    static List<ParsedAffixRow> extractSecondaryAffixRows(List<IdleArcOcrEngine.OcrLine> ocrLines,
                                                          SlotAffixAdvisor.GearSlot headerSlot,
                                                          boolean isShieldBase) {
        int startIdx = 0;
        int endIdx = ocrLines.size();
        boolean sawExplicitAffixHeader = false;
        int maxAllowedRows = MAX_AFFIXES_PER_ITEM;

        for (int i = 0; i < ocrLines.size(); i++) {
            String raw = ocrLines.get(i).text;
            String low = DruidKnowledgeBase.normalizeForMatch(raw);
            if (!sawExplicitAffixHeader && isAffixSectionHeaderLine(low, raw)) {
                sawExplicitAffixHeader = true;
                startIdx = i + 1;
                maxAllowedRows = parseExpectedAffixCountFromHeader(raw);
                continue;
            }
            if (sawExplicitAffixHeader && i >= startIdx && isSectionAfterAffixes(low)) {
                endIdx = i;
                break;
            }
        }

        if (maxAllowedRows <= 0) {
            return new ArrayList<>();
        }

        if (!sawExplicitAffixHeader) {
            for (int i = 0; i < ocrLines.size(); i++) {
                String low = DruidKnowledgeBase.normalizeForMatch(ocrLines.get(i).text);
                if (isSectionAfterAffixes(low)) {
                    endIdx = i;
                    break;
                }
            }
        }

        // Предварительно склеиваем 2-строчные переносы внутри блока АФФИКСЫ:
        // напр. строка 1: «Здоровье за удар и за убийство», строка 2: «+20%»
        List<IdleArcOcrEngine.OcrLine> mergedSectionLines = new ArrayList<>();
        for (int i = startIdx; i < endIdx; i++) {
            IdleArcOcrEngine.OcrLine cur = ocrLines.get(i);
            String curRaw = cur.text.trim();
            if (curRaw.isEmpty()) continue;
            String curLow = DruidKnowledgeBase.normalizeForMatch(curRaw);

            if (i + 1 < endIdx && !curRaw.contains("+")
                    && classifyAffixName(curLow, curRaw, curRaw.contains("%"), headerSlot, isShieldBase) != null) {
                IdleArcOcrEngine.OcrLine nxt = ocrLines.get(i + 1);
                String nxtRaw = nxt.text.trim();
                String nxtLow = DruidKnowledgeBase.normalizeForMatch(nxtRaw);
                if (nxtRaw.startsWith("+")
                        && classifyAffixName(nxtLow, nxtRaw, nxtRaw.contains("%"), headerSlot, isShieldBase) == null
                        && (nxt.y0 - cur.y1) <= 48) {
                    IdleArcOcrEngine.OcrLine combined = new IdleArcOcrEngine.OcrLine(
                            curRaw + " " + nxtRaw,
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

            if (raw.length() < 3) continue;
            if (isNonAffixMetadataLine(low)) continue;

            if (!sawExplicitAffixHeader && rawCandidates.isEmpty() && isLikelyImplicitHeaderLine(low, raw)) {
                continue;
            }

            if (low.contains("заполните позицию") || low.contains("пустая позиция")
                    || low.contains("выберите аффикс") || (low.contains("добавьте") && low.contains("кузниц"))) {
                rawCandidates.add(new ParsedAffixRow(
                        rawCandidates.size() + 1, AffixType.EMPTY_POSITION, raw, 0.0, false, 0, 0));
                if (sawExplicitAffixHeader && rawCandidates.size() >= maxAllowedRows) break;
                continue;
            }

            boolean hasPct = raw.contains("%");
            AffixType type = classifyAffixName(low, raw, hasPct, headerSlot, isShieldBase);
            if (type == null) continue;

            double statVal = extractPlusStatValue(raw);
            List<Double> nums = extractAllNumbers(raw);
            if (statVal <= 0.0 && nums.isEmpty()) continue;
            if (statVal <= 0.0) {
                statVal = nums.get(0);
            }
            int[] awaken = detectAwakeningOnRow(ol, raw);

            if (sawExplicitAffixHeader) {
                if (seenTypes.contains(type)) continue;
                seenTypes.add(type);
                rawCandidates.add(new ParsedAffixRow(
                        rawCandidates.size() + 1, type, raw, statVal, hasPct, awaken[0], awaken[1]));
                if (rawCandidates.size() >= maxAllowedRows) break;
            } else {
                int prevDup = -1;
                for (int k = 0; k < rawCandidates.size(); k++) {
                    if (rawCandidates.get(k).type == type) {
                        prevDup = k;
                        break;
                    }
                }
                if (prevDup >= 0) {
                    rawCandidates.remove(prevDup);
                }
                rawCandidates.add(new ParsedAffixRow(
                        rawCandidates.size() + 1, type, raw, statVal, hasPct, awaken[0], awaken[1]));
            }
        }

        if (!sawExplicitAffixHeader && rawCandidates.size() < 2) {
            return Collections.emptyList();
        }

        int cap = Math.min(MAX_AFFIXES_PER_ITEM, maxAllowedRows);
        if (rawCandidates.size() > cap) {
            rawCandidates = new ArrayList<>(
                    rawCandidates.subList(rawCandidates.size() - cap, rawCandidates.size()));
        }

        List<ParsedAffixRow> result = new ArrayList<>(rawCandidates.size());
        for (int i = 0; i < rawCandidates.size(); i++) {
            ParsedAffixRow r = rawCandidates.get(i);
            result.add(new ParsedAffixRow(
                    i + 1, r.type, r.rawLabel, r.rolledValue, r.valueHasPct, r.awakenTier, r.awakenBonusPct));
        }
        return result;
    }

    private static boolean isSectionAfterAffixes(String low) {
        return low.contains("ячейка") || low.contains("чейка")
                || (low.startsWith("афф") && (low.contains("5") || low.contains("уров")))
                || low.startsWith("бонус добычи") || low.startsWith("бонус дропа")
                || low.startsWith("гнезд") || low.startsWith("гнёзд") || low.contains("самоцвет")
                || low.equals("нефрит") || low.equals("янтарь") || low.equals("лазурит") || low.equals("гранат")
                || low.startsWith("спутника") || low.startsWith("свирепый") || low.startsWith("отражающий")
                || low.startsWith("истребителя") || low.startsWith("сокрушительный") || low.startsWith("неумолимый")
                || low.startsWith("бешенство") || low.startsWith("стихийный всплеск")
                || low.startsWith("укреплённый") || low.startsWith("укрепленный") || low.startsWith("бронированный")
                || low.startsWith("живучий") || low.startsWith("точный") || low.startsWith("жестокий")
                || low.startsWith("кровожадный") || low.startsWith("вампирский") || low.startsWith("иссушающий")
                || low.startsWith("удачливый") || low.startsWith("процветающий")
                || low.startsWith("учёного") || low.startsWith("ученого") || low.startsWith("собирателя")
                || low.contains("внешний вид") || low.contains("улучшить") || low.contains("учшить")
                || low.equals("пробуждение") || low.equals("стоимость") || low.startsWith("камни крафта")
                || low.startsWith("улучшение тира") || low.startsWith("улучшение уровня")
                || low.equals("надеть") || low.startsWith("снять") || low.equals("разобрать") || low.equals("продать")
                || low.contains("надеть предмет") || low.contains("перераспределить пробуждение")
                || low.contains("материалы пробуждения")
                || low.contains("двуручное") || low.contains("занимает слот") || low.startsWith("надето:");
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

    /**
     * Классифицирует все 26 официальных русских шаблонов аффиксов IdleArc (из libapp.so)
     * с математической сверкой против слота предмета, когда слот известен из шапки.
     */
    static AffixType classifyAffixName(String low, String raw, boolean hasPct,
                                       SlotAffixAdvisor.GearSlot headerSlot, boolean isShieldBase) {
        // 1. Стихийные конверсии урона оружия («{value}% урона преобразуется в Огонь/Воду/Воздух/Землю/Яд/Природу»)
        if (low.contains("преобразуетс") || low.contains("преобразование в") || low.contains("преобразовано в")
                || low.contains("конверс")) {
            return AffixType.ELEMENTAL_CONVERT;
        }

        // 2. Урон питомца % (`of Pet Damage`)
        if (low.contains("урон питомц") || low.contains("урона питомц") || low.contains("урону питомц")
                || low.contains("урон пит") || (low.contains("питомц") && low.contains("урон"))) {
            return AffixType.PET_DAMAGE;
        }

        // 3. Эксклюзив Факела: «Здоровье за удар и за убийство %» (`of Bloodthirst`)
        // На Факеле НЕ существует ни плоского Здоровья, ни плоских LoH/LoK!
        if ((low.contains("за удар") && (low.contains("убийств") || low.contains("и за") || hasPct))
                || low.contains("жажда крови") || low.contains("жажды крови")) {
            return AffixType.BLOODTHIRST;
        }
        if (headerSlot == SlotAffixAdvisor.GearSlot.TORCH && !isShieldBase && low.contains("здоров")) {
            return AffixType.BLOODTHIRST;
        }

        // 4. Двойной урон % (`of Devastation`) vs Двойной удар % (`of Double Hit`)
        // На Факеле и Броне/Шлеме/Перчатках/Сапогах НЕ существует `of Double Hit` во вторичных аффиксах!
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

        // 5. Крит. урон % (`of Crit Damage`) и Шанс крита % (`of Crit Chance`)
        if (low.contains("шанс крит") || low.contains("крит. шанс") || low.contains("крит. удара")
                || low.contains("критического удара")) {
            return AffixType.CRIT_CHANCE;
        }
        if (low.contains("крит. урон") || low.contains("крит урон") || low.contains("критический урон")
                || (low.contains("крит") && low.contains("урон"))) {
            return AffixType.CRIT_DAMAGE;
        }

        // 6. Характеристики (`of Intelligence`, `of Dexterity`, `of Strength`)
        if (low.contains("интеллект")) return AffixType.INTELLIGENCE;
        if (low.contains("ловкост")) return AffixType.DEXTERITY;
        if ((low.startsWith("сила") || low.contains(" к силе") || low.contains("силы"))
                && !low.contains("талисман") && !low.contains("слов")) {
            return AffixType.STRENGTH;
        }

        // 7. Снижение урона % (`of Warding`) и Шанс уклонения % (`Evasive`)
        if (low.contains("снижение урона") || low.contains("получаемого урона")) return AffixType.DAMAGE_REDUCTION;
        if (low.contains("уклонен")) return AffixType.DODGE_CHANCE;

        // 8. Бонус опыта % (`of Experience`) и Бонус золота % (`of Wealth`)
        if (low.contains("бонус опыт") || (low.contains("опыт") && hasPct) || low.startsWith("опыт")) {
            return AffixType.EXP_GAIN;
        }
        if (low.contains("бонус золот") || (low.contains("золот") && hasPct) || low.startsWith("золото")) {
            return AffixType.GOLD_GAIN;
        }

        // 9. Здоровье за удар (`of Regeneration`), Здоровье за убийство (`of Vampirism`), Макс. здоровье (`of Ambulance`)
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

        // 10. Защита: на броне [П] `Доп. защита` (без %) и `Увеличенная защита %` (с %), на бижутерии [С] `Защита %`
        if (low.contains("увеличенная защит")) {
            return AffixType.LOCAL_PERCENT_DEFENSE;
        }
        if (low.contains("доп. защит") || low.contains("доп защит")) {
            return AffixType.LOCAL_FLAT_DEFENSE;
        }
        if (low.contains("защит")) {
            boolean isJewelry = (headerSlot == SlotAffixAdvisor.GearSlot.BELT
                    || headerSlot == SlotAffixAdvisor.GearSlot.AMULET
                    || headerSlot == SlotAffixAdvisor.GearSlot.RING);
            if (isJewelry) {
                return AffixType.DEFENSE_PERCENT;
            }
            return hasPct ? AffixType.LOCAL_PERCENT_DEFENSE : AffixType.LOCAL_FLAT_DEFENSE;
        }

        // 11. Доп. урон бижутерии (`of Striking`, `jewelry_flat_damage`: `+{value} к урону` / `Доп. урон +{value}`)
        if (low.contains("доп. урон") || low.contains("доп урон") || low.contains("дополнительный урон")
                || low.contains("к урону") || low.contains("фикс. урон")) {
            return AffixType.ADDED_DAMAGE;
        }
        return null;
    }

    /**
     * Распознаёт уровень позиционного Пробуждения (I..VIII -> +10%..+80%) по тексту и пиксельной подсветке значка.
     */
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
                .replace('l', 'I')
                .replace('1', 'I')
                .replace('|', 'I')
                .replace('!', 'I')
                .replace('і', 'I')
                .replace('І', 'I')
                .replace('v', 'V')
                .replace('x', 'X');
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
        Matcher m = PLUS_STAT_PAT.matcher(raw);
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
        String cleaned = s.replaceAll("(?<=\\d)\\s+(?=\\d{3}\\b)", "");
        Matcher m = NUMBER_PAT.matcher(cleaned);
        while (m.find()) {
            try {
                out.add(Double.parseDouble(m.group(1).replace(',', '.')));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /**
     * Строгое определение слота по математическим инвариантам `/api/config/game`, если шапка карточки скрыта скроллом.
     */
    private static SlotAffixAdvisor.GearSlot inferSlotFromObservedAffixes(
            List<ParsedAffixRow> rows, List<String> plain) {
        StringBuilder all = new StringBuilder();
        for (String s : plain) all.append(' ').append(DruidKnowledgeBase.normalizeForMatch(s));
        String full = all.toString();

        for (ParsedAffixRow r : rows) {
            if (r.type == AffixType.BLOODTHIRST) return SlotAffixAdvisor.GearSlot.TORCH;
            if (r.type == AffixType.ELEMENTAL_CONVERT) return SlotAffixAdvisor.GearSlot.WEAPON;
        }

        boolean hasJewelryOnly = false;
        boolean hasWeaponOrJewelry = false;
        boolean hasArmorOnlyDef = false;
        boolean hasDR = false;
        boolean hasDodge = false;
        boolean hasMaxHp = false;
        boolean hasLoHOrLoK = false;

        for (ParsedAffixRow r : rows) {
            if (r.type == AffixType.ADDED_DAMAGE || r.type == AffixType.DEFENSE_PERCENT) {
                hasJewelryOnly = true;
            }
            if (r.type == AffixType.DOUBLE_HIT || r.type == AffixType.INTELLIGENCE
                    || r.type == AffixType.DEXTERITY || r.type == AffixType.STRENGTH) {
                hasWeaponOrJewelry = true;
            }
            if (r.type == AffixType.LOCAL_FLAT_DEFENSE || r.type == AffixType.LOCAL_PERCENT_DEFENSE) {
                hasArmorOnlyDef = true;
            }
            if (r.type == AffixType.DAMAGE_REDUCTION) hasDR = true;
            if (r.type == AffixType.DODGE_CHANCE) hasDodge = true;
            if (r.type == AffixType.MAX_HEALTH) hasMaxHp = true;
            if (r.type == AffixType.LIFE_ON_HIT || r.type == AffixType.LIFE_ON_KILL) hasLoHOrLoK = true;
        }

        if (hasJewelryOnly) {
            return hasLoHOrLoK ? SlotAffixAdvisor.GearSlot.RING : SlotAffixAdvisor.GearSlot.AMULET;
        }
        if (hasArmorOnlyDef) {
            return hasDR ? SlotAffixAdvisor.GearSlot.CHEST : SlotAffixAdvisor.GearSlot.GLOVES;
        }
        if (hasDR || hasDodge) {
            if (hasMaxHp || full.contains("защита")) {
                return SlotAffixAdvisor.GearSlot.CHEST;
            }
            return SlotAffixAdvisor.GearSlot.TORCH;
        }
        if (hasWeaponOrJewelry) {
            if (hasMaxHp) {
                return hasLoHOrLoK ? SlotAffixAdvisor.GearSlot.AMULET : SlotAffixAdvisor.GearSlot.BELT;
            }
            return SlotAffixAdvisor.GearSlot.WEAPON;
        }
        return SlotAffixAdvisor.GearSlot.WEAPON;
    }

    private static final Pattern HEADER_AWAKEN_ENH = Pattern.compile("\\bA([1-9])(?:\\s*\\+\\s*(\\d{1,3}))?\\b");
    private static final Pattern HEADER_PLUS_ENH = Pattern.compile("(?:^|\\s)\\+(\\d{1,3})(?:\\s|$)");

    static ItemRollModel calibrateRollModel(List<String> plainLines, List<ParsedAffixRow> rows) {
        int itemAwaken = 0;
        int enh = 0;
        int hdrLim = Math.min(plainLines.size(), 8);
        for (int i = 0; i < hdrLim; i++) {
            String s = plainLines.get(i);
            Matcher m = HEADER_AWAKEN_ENH.matcher(s);
            if (m.find()) {
                try {
                    itemAwaken = Integer.parseInt(m.group(1));
                    if (m.group(2) != null) enh = Integer.parseInt(m.group(2));
                } catch (Exception ignored) {
                }
                break;
            }
        }
        if (enh == 0) {
            for (int i = 0; i < Math.min(plainLines.size(), 5); i++) {
                Matcher mp = HEADER_PLUS_ENH.matcher(plainLines.get(i));
                if (mp.find()) {
                    try {
                        enh = Integer.parseInt(mp.group(1));
                    } catch (Exception ignored) {
                    }
                    break;
                }
            }
        }

        double priorUnitScale = 12.0;
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
        unitScale = Math.max(1.5, Math.min(120.0, unitScale));

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

    static double evaluateAffixValue(
            AffixType type,
            double rolledVal,
            SlotAffixAdvisor.GearSlot slot,
            DruidProfile p) {
        if (type == AffixType.EMPTY_POSITION || rolledVal <= 0.0) return 0.0;

        boolean isFarm = (p.mode == DruidProfile.BuildMode.FARM);
        double petPoolPct = estimateAdditivePetPoolPct(p);

        switch (type) {
            case PET_DAMAGE: {
                double dpsGain = (rolledVal / (100.0 + petPoolPct)) * 100.0;
                return isFarm ? dpsGain * 0.85 : dpsGain;
            }
            case DOUBLE_HIT: {
                double dpsGain = rolledVal * p.marginalGainPer1PctDoubleHit();
                return isFarm ? dpsGain * 0.80 : dpsGain;
            }
            case DOUBLE_DAMAGE: {
                double dpsGain = rolledVal * p.marginalGainPer1PctDoubleDamage();
                return isFarm ? dpsGain * 0.80 : dpsGain;
            }
            case CRIT_DAMAGE: {
                double dpsGain = rolledVal * p.marginalGainPer1PctCrit();
                return isFarm ? dpsGain * 0.78 : dpsGain;
            }
            case CRIT_CHANCE: {
                double mult = (p.critChance < 100.0) ? 1.55 : 1.0;
                double dpsGain = rolledVal * p.marginalGainPer1PctCrit() * mult;
                return isFarm ? dpsGain * 0.78 : dpsGain;
            }
            case INTELLIGENCE: {
                double ddFromInt = (rolledVal * 0.20) * p.marginalGainPer1PctDoubleDamage();
                double petFromInt = ((rolledVal * 0.05) / (100.0 + petPoolPct)) * 100.0;
                double totalDps = ddFromInt + petFromInt;
                return isFarm ? totalDps * 0.78 : totalDps;
            }
            case DEXTERITY: {
                double petGain = ((rolledVal * 0.10) / (100.0 + petPoolPct)) * 100.0;
                double dodgeUtility = (p.dodgeRaw < 95.0) ? Math.min(2.0, rolledVal * 0.05 * 0.15) : 0.0;
                return (petGain + dodgeUtility) * (isFarm ? 0.65 : 1.0);
            }
            case DAMAGE_REDUCTION: {
                if (p.damageReductionRaw < 94.8) {
                    double needed = Math.min(rolledVal, 95.0 - p.damageReductionRaw);
                    return needed * 0.95 + Math.max(0.0, rolledVal - needed) * 0.08;
                }
                return 0.35;
            }
            case DODGE_CHANCE: {
                if (p.dodgeRaw >= 95.0) {
                    return 0.0;
                }
                return Math.min(rolledVal, 95.0 - p.dodgeRaw) * 0.45;
            }
            case BLOODTHIRST: {
                return isFarm ? (0.8 + rolledVal * 0.04) : (1.55 + rolledVal * 0.04);
            }
            case MAX_HEALTH: {
                double hpGainPct = (rolledVal / Math.max(2000.0, p.maxHp)) * 100.0;
                double ehpScore = Math.min(2.4, 0.85 + hpGainPct * 0.06);
                return isFarm ? ehpScore * 0.45 : ehpScore;
            }
            case LOCAL_PERCENT_DEFENSE:
                return isFarm ? 0.25 : 0.70;
            case DEFENSE_PERCENT:
                return isFarm ? 0.25 : 0.65;
            case LOCAL_FLAT_DEFENSE:
                return isFarm ? 0.15 : 0.40;
            case ADDED_DAMAGE:
                return isFarm ? 0.35 : 0.80;
            case GOLD_GAIN:
                if (isFarm) {
                    return 4.5 + (rolledVal / (100.0 + Math.max(0.0, p.goldBonus))) * 55.0;
                }
                return 0.18;
            case EXP_GAIN:
                if (isFarm || p.mode == DruidProfile.BuildMode.LEVELING) {
                    return 4.2 + (rolledVal / (100.0 + Math.max(0.0, p.expBonus))) * 50.0;
                }
                return 0.15;
            case LIFE_ON_HIT:
            case LIFE_ON_KILL:
                return isFarm ? 0.15 : 0.35;
            case ELEMENTAL_CONVERT:
                return 0.10;
            case STRENGTH:
            default:
                return 0.12;
        }
    }

    private static double estimateAdditivePetPoolPct(DruidProfile p) {
        if (p.charLevel >= 280 || p.petDamageMultMillions >= 10.0) {
            return 3500.0;
        }
        if (p.charLevel >= 150) {
            return 1600.0;
        }
        return 550.0;
    }

    private static String buildDetailedPlan(
            SlotAffixAdvisor.GearSlot slot,
            boolean slotInferred,
            boolean isShieldBase,
            List<AffixType> compatiblePool,
            List<ParsedAffixRow> rows,
            List<AffixType> idealByPos,
            Set<AffixType> idealSet,
            Map<AffixType, Integer> optimalAwakenPctForStat,
            ItemRollModel rollModel,
            DruidProfile p,
            double curSum,
            double idealSum,
            int keepCount,
            int rerollCount,
            int moveAwakenCount,
            int awakenedRerollCount) {

        String modeLabel = (p.mode == DruidProfile.BuildMode.FARM) ? "Фарм / Дроп"
                : ((p.mode == DruidProfile.BuildMode.BOSS) ? "Боссы / Рейд" : "Бой / Питомец");

        StringBuilder sb = new StringBuilder();
        if (slotInferred) {
            sb.append("⚠️ Шапка предмета не попала в кадр — слот оценён по статам как «")
              .append(slot.slotTitle)
              .append("». Нажми кнопку точного слота в полоске выше, чтобы пересчитать мгновенно!\n\n");
        }
        sb.append("🎯 ПЛАН ПО ПОЗИЦИЯМ (")
          .append(isShieldBase ? "Щит / Вторая рука · пул 8 аффиксов" : slot.shortChip)
          .append(" · режим: ").append(modeLabel).append("):\n");

        int n = rows.size();
        for (int i = 0; i < n; i++) {
            ParsedAffixRow r = rows.get(i);
            AffixType target = idealByPos.get(i);
            String posHeader = "━━ Позиция #" + r.index + formatAwakenBadge(r) + " ━━\n";
            sb.append("\n").append(posHeader);

            double curGain = evaluateAffixValue(r.type, r.rolledValue, slot, p);
            double targetAvgRoll = rollModel.expectedAvgRoll(target, r.awakenMultiplier());
            double targetMaxRoll = rollModel.expectedMaxRoll(target, r.awakenMultiplier());
            double targetGain = evaluateAffixValue(target, targetAvgRoll, slot, p);

            if (r.type == AffixType.EMPTY_POSITION) {
                sb.append("  Сейчас: ⬜ Пустая позиция (не открыта в Кузнице)\n");
                sb.append("  ➕ ДОБАВИТЬ В КУЗНИЦЕ: «").append(target.taggedName())
                  .append("» (ожид. ").append(formatRollRange(target, targetAvgRoll, targetMaxRoll))
                  .append(" -> +").append(fmt1(targetGain)).append("% ценности)\n");
                sb.append("  Почему: ").append(explainWhyTarget(target, targetAvgRoll, slot, p)).append("\n");
                continue;
            }

            sb.append("  Сейчас: ").append(r.type.taggedName()).append(" (")
              .append(formatStatRoll(r.type, r.rolledValue)).append(") — ")
              .append(explainCurrentShort(r.type, r.rolledValue, curGain, p)).append("\n");

            if (r.type == target) {
                int optAwkPct = optimalAwakenPctForStat.containsKey(r.type)
                        ? optimalAwakenPctForStat.get(r.type) : r.awakenBonusPct;
                sb.append("  ✅ ОСТАВИТЬ СТАТ НА МЕСТЕ! Входит в топ-").append(n)
                  .append(" реального пула слота");
                if (r.awakenTier > 0 && optAwkPct <= r.awakenBonusPct) {
                    sb.append(" и отлично использует Пробуждение ").append(roman(r.awakenTier))
                      .append(" (+").append(r.awakenBonusPct).append("%)");
                }
                sb.append(".\n");
                if (optAwkPct > r.awakenBonusPct) {
                    sb.append("  💎 По Пробуждению: после замены лишних статов сюда выгодно перекинуть Пробуждение ")
                      .append(roman(optAwkPct / 10)).append(" (+").append(optAwkPct)
                      .append("%) через «Перераспределение пробуждения» в Кузнице!\n");
                }

                if (r.type.ignoresAffixCap && r.rolledValue > 0) {
                    double minPossible = rollModel.expectedMinRoll(r.type, r.awakenMultiplier());
                    double maxPossible = rollModel.expectedMaxRoll(r.type, r.awakenMultiplier());
                    if (maxPossible > minPossible * 1.35 && r.rolledValue < 0.62 * maxPossible) {
                        double extraFromRerollVal = evaluateAffixValue(r.type, 0.85 * maxPossible, slot, p) - curGain;
                        sb.append("  💡 Подсказка по числу: сам тип стата правильный, но выпал НИЗКИЙ ролл значения (")
                          .append(formatStatRoll(r.type, r.rolledValue)).append(" из возможных ~")
                          .append(formatStatRoll(r.type, minPossible)).append("..").append(formatStatRoll(r.type, maxPossible))
                          .append(" на этом предмете — докрутка числа даст ещё ~+")
                          .append(fmt1(Math.max(0.3, extraFromRerollVal))).append("% DPS!).\n");
                    }
                }
            } else {
                double delta = Math.max(0.0, targetGain - Math.max(0.0, curGain));
                if (r.awakenTier > 0) {
                    sb.append("  🔒 [ПОЗИЦИЯ ПРОБУЖДЕНА ").append(roman(r.awakenTier))
                      .append(" — ЗАБЛОКИРОВАНА ОТ ПРЯМОЙ ЗАМЕНЫ!] ");
                } else {
                    sb.append("  🔄 ");
                }
                sb.append("ВМЕСТО НЕГО ПОСТАВИТЬ: «").append(target.taggedName()).append("» (ожид. ")
                  .append(formatRollRange(target, targetAvgRoll, targetMaxRoll)).append(")\n");
                sb.append("  📈 Выгода замены: +").append(fmt1(delta)).append("% к эффективности (")
                  .append(explainWhyTarget(target, targetAvgRoll, slot, p)).append(").\n");
                if (target == AffixType.CRIT_CHANCE && compatiblePool.contains(AffixType.INTELLIGENCE)
                        && !idealSet.contains(AffixType.INTELLIGENCE)) {
                    double intAvg = rollModel.expectedAvgRoll(AffixType.INTELLIGENCE, r.awakenMultiplier());
                    double intMax = rollModel.expectedMaxRoll(AffixType.INTELLIGENCE, r.awakenMultiplier());
                    sb.append("  ✨ Отличная альтернатива в этом же пуле: «[С] Интеллект» (")
                      .append(formatRollRange(AffixType.INTELLIGENCE, intAvg, intMax))
                      .append(" без капа -> даёт до +").append(fmt1(intMax * 0.20))
                      .append("% Дв.урона и +").append(fmt1(intMax * 0.05)).append("% Пета) — оставляй то, что прокнет первым!\n");
                }
                if (r.awakenTier > 0) {
                    int freePos = findFirstUnawakenedRerollPos(rows, idealSet);
                    if (freePos > 0) {
                        sb.append("  ⚒️ Как заменить в Кузнице: в IdleArc пробуждённый слот ").append(roman(r.awakenTier))
                          .append(" защищён замком. Сначала перекинь Пробуждение «Перераспределением» (либо сразу лови «")
                          .append(target.taggedName()).append("» на свободной Позиции #").append(freePos)
                          .append(", а потом перенеси на неё Пробуждение ").append(roman(r.awakenTier)).append("!).\n");
                    } else {
                        sb.append("  ⚒️ Как заменить в Кузнице: пробуждённый слот ").append(roman(r.awakenTier))
                          .append(" защищён от случайного реролла — сначала сними/перераспредели Пробуждение с Позиции #")
                          .append(r.index).append(", замени стат на «").append(target.taggedName())
                          .append("» и верни Пробуждение.\n");
                    }
                }
            }
        }

        sb.append("\n📊 ИТОГО ПО ПРЕДМЕТУ:\n");
        sb.append("• Сейчас работает на билд: ").append(fmt1(curSum))
          .append("% экв. ценности -> После доводки: ").append(fmt1(idealSum))
          .append("% (+").append(fmt1(Math.max(0.0, idealSum - curSum))).append("% прироста).\n");

        if (rerollCount > 0 || moveAwakenCount > 0) {
            sb.append("• Рекомендуемый порядок в Кузнице: ");
            List<String> steps = new ArrayList<>();
            List<String> freeToRoll = new ArrayList<>();
            List<String> lockedAwakenedToRoll = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ParsedAffixRow row = rows.get(i);
                if (row.type != idealByPos.get(i) && !idealSet.contains(row.type)) {
                    String item = "#" + row.index + " («" + row.type.ruName + "» -> «" + idealByPos.get(i).taggedName() + "»)";
                    if (row.awakenTier > 0) {
                        lockedAwakenedToRoll.add(item + " [" + roman(row.awakenTier) + "]");
                    } else {
                        freeToRoll.add(item);
                    }
                }
            }
            if (!freeToRoll.isEmpty()) {
                steps.add("сразу перекуй свободные непробуждённые позиции " + joinSimple(freeToRoll));
            }
            if (!lockedAwakenedToRoll.isEmpty()) {
                steps.add("для пробуждённых позиций " + joinSimple(lockedAwakenedToRoll)
                        + " сначала сними замок «Перераспределением пробуждения» в Кузнице (в игре пробуждённый аффикс заблокирован от прямой замены)");
            }
            if (hasAnyAwakening(rows)) {
                steps.add("поставь максимальное Пробуждение на «" + idealByPos.get(findBestAwakenIdx(rows)).taggedName() + "»");
            }
            sb.append(joinSteps(steps)).append(".\n");
        } else {
            sb.append("• Ничего перековывать по типам аффиксов не требуется — все позиции собраны по BiS-канону слота!\n");
        }

        sb.append("\n📚 Верифицированный пул слота в игре (").append(formatPoolSummary(slot, isShieldBase, compatiblePool)).append(")");

        return sb.toString();
    }

    private static String formatPoolSummary(SlotAffixAdvisor.GearSlot slot, boolean isShieldBase, List<AffixType> pool) {
        List<String> prefixes = new ArrayList<>();
        List<String> suffixes = new ArrayList<>();
        for (AffixType t : pool) {
            if (t.kind == AffixKind.PREFIX) {
                if (t == AffixType.ELEMENTAL_CONVERT) {
                    prefixes.add("6× Конверсия стихий %");
                } else {
                    prefixes.add(t.ruName + (t.isPercent ? " %" : ""));
                }
            } else if (t.kind == AffixKind.SUFFIX) {
                suffixes.add(t.ruName + (t.isPercent ? " %" : ""));
            }
        }
        int officialCount = (slot == SlotAffixAdvisor.GearSlot.WEAPON) ? (pool.size() + 5) : pool.size();
        return officialCount + " аффиксов — [П] Префиксы: " + joinSimple(prefixes)
                + " · [С] Суффиксы: " + joinSimple(suffixes);
    }

    private static int findFirstUnawakenedRerollPos(List<ParsedAffixRow> rows, Set<AffixType> idealSet) {
        for (ParsedAffixRow r : rows) {
            if (r.awakenTier == 0 && !idealSet.contains(r.type)) {
                return r.index;
            }
        }
        return -1;
    }

    private static String explainCurrentShort(AffixType t, double val, double gain, DruidProfile p) {
        switch (t) {
            case DODGE_CHANCE:
                if (p.dodgeRaw >= 95.0) {
                    return "🚫 0.0% пользы (уклонение уже " + fmt1(p.dodgeRaw) + "% при жёстком капе 95%!)";
                }
                return "защита уклонением (+" + fmt1(gain) + "%)";
            case ELEMENTAL_CONVERT:
                return "⚠️ меняет стихию только удара героя, НЕ действует на питомца (~0% DPS пета)";
            case STRENGTH:
                return "🚫 непрофильный стат Воина (+физ. урон/возмездие, почти 0% DPS пету)";
            case DEXTERITY:
                if (p.dodgeRaw >= 95.0) {
                    return "⚠️ даёт лишь +" + fmt1(val * 0.10) + "% к пулу пета (~+" + fmt1(gain)
                            + "% DPS), а уклонение сгорает в капе 95% (Интеллект в ~6× сильнее!)";
                }
                return "полу-стат (~+" + fmt1(gain) + "% DPS)";
            case GOLD_GAIN:
            case EXP_GAIN:
                if (p.mode != DruidProfile.BuildMode.FARM && p.mode != DruidProfile.BuildMode.LEVELING) {
                    return "фарм-стат (0% боевого урона в пуше/боссах)";
                }
                return "фарм-бонус (+" + fmt1(gain) + "% ценности фарма)";
            case DAMAGE_REDUCTION:
                if (p.damageReductionRaw >= 95.0) {
                    return "кап Снижения урона 95% уже закрыт (" + fmt1(p.damageReductionRaw) + "%)";
                }
                return "держит кап выживаемости 95% (+" + fmt1(gain) + "%)";
            case BLOODTHIRST:
                return "эксклюзив Факела: множит весь отхил за удар и за убийство (кап 15%+1%×A)";
            default:
                return "даёт ~+" + fmt1(gain) + "% к эффективности билда";
        }
    }

    private static String explainWhyTarget(AffixType target, double expRoll, SlotAffixAdvisor.GearSlot slot, DruidProfile p) {
        switch (target) {
            case PET_DAMAGE:
                return "главный префикс Друида БЕЗ аффикс-капа — разгоняет урон активного питомца сильнее любого другого стата";
            case DOUBLE_HIT:
                return "топ боевой суффикс оружия/бижутерии: каждые 100% дают гарантированный доп. удар";
            case DOUBLE_DAMAGE:
                return "официально умножает урон И героя, И питомца (при " + fmt0(p.doubleDamageChance) + "% даёт отличный чистый множитель)";
            case INTELLIGENCE:
                return "БЕЗ аффикс-капа! Даёт +" + fmt1(expRoll * 0.20) + "% Шанса двойного урона (и герою, и пету!) + "
                        + fmt1(expRoll * 0.05) + "% Урона пета";
            case CRIT_DAMAGE:
                return "официально игнорирует аффикс-кап предмета и напрямую множит криты";
            case CRIT_CHANCE:
                return (p.critChance >= 100.0)
                        ? "игнорирует аффикс-кап, а весь шанс выше 100% конвертируется 1:1 в Крит. урон!"
                        : "поднимает шанс крита до 100%+ и далее идёт 1:1 в Крит. урон";
            case BLOODTHIRST:
                return "уникальный суффикс ТОЛЬКО Факела (5-й лучший боевой стат слота после 4 атакующих аффиксов пула Факела)";
            case DAMAGE_REDUCTION:
                return "нужен для удержания порога 95% Снижения урона";
            case MAX_HEALTH:
                return "лучший защитный префикс брони после закрытия 4 атакующих статов пула (PetDmg, Дв. урон, Крит. урон, Шанс крита)";
            case GOLD_GAIN:
                return "ускоряет прокачку ячеек снаряжения, гнёзд самоцветов и надписей за золото";
            case EXP_GAIN:
                return "ускоряет набор уровней героя в фарм-пресете";
            default:
                return "лучший доступный аффикс из официального пула слота «" + slot.shortChip + "»";
        }
    }

    private static String formatAwakenBadge(ParsedAffixRow r) {
        if (r.awakenTier <= 0) return "";
        return " [💎 ПРОБУЖДЕНИЕ " + roman(r.awakenTier) + " · +" + r.awakenBonusPct + "%]";
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
        if (t.isPercent) {
            return "+" + fmt1(val) + "%";
        }
        return "+" + Math.round(val);
    }

    private static String formatRollRange(AffixType t, double avgVal, double maxVal) {
        if (avgVal <= 0.0) return "—";
        if (!t.ignoresAffixCap || Math.abs(maxVal - avgVal) < 2.0) {
            return "~" + formatStatRoll(t, avgVal);
        }
        return "~" + formatStatRoll(t, avgVal) + " (макс. до " + formatStatRoll(t, maxVal) + ")";
    }

    private static int findFirstPositionFor(List<AffixType> list, AffixType target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) return i;
        }
        return 0;
    }

    private static boolean hasAnyAwakening(List<ParsedAffixRow> rows) {
        for (ParsedAffixRow r : rows) {
            if (r.awakenTier > 0) return true;
        }
        return false;
    }

    private static int findBestAwakenIdx(List<ParsedAffixRow> rows) {
        int bestIdx = 0;
        int bestBonus = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).awakenBonusPct > bestBonus) {
                bestBonus = rows.get(i).awakenBonusPct;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static String joinSimple(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String joinSteps(List<String> steps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append("; затем ");
            sb.append((i + 1)).append(") ").append(steps.get(i));
        }
        return sb.toString();
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String fmt0(double v) {
        return String.format(Locale.US, "%.0f", v);
    }
}
