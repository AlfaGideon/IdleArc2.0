package com.idlearc.companion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Интеллектуальный по-позиционный оптимизатор аффиксов текущего предмета для Друида.
 *
 * Решает точную комбинаторную задачу назначения аффиксов по реальным строкам #1..#N открытого предмета:
 *  1) Считывает ТОЛЬКО текущие аффиксы предмета (#1..#N), их числовые значения и процентный бонус
 *     Пробуждения (голубая подсветка + римская цифра I/II/III/IV/V = +10%/+20%/+30%... на конкретной строке,
 *     будь то 4-й аффикс на одном предмете или 5-й на другом).
 *  2) Калибрует масштаб тира/уровня предмета по его собственным числам.
 *  3) По живому профилю Друида (с учётом капов Уклонения 95%, Снижения урона 95%, текущего Двойного удара,
 *     Двойного урона, Крита 1892% и Интеллекта) перебирает все допустимые бездубликатные наборы из пула
 *     ЭТОГО вида предмета и находит глобально оптимальное распределение по местам #1..#N.
 *  4) Для каждого места #1..#N показывает: что стоит сейчас -> оставить или чем конкретно заменить ->
 *     точная разница в % урона пета / выживаемости именно на этом месте и почему.
 */
public class AffixPositionOptimizer {

    public enum AffixType {
        PET_DAMAGE("Урон питомца %", 390.0, true),
        DOUBLE_HIT("Шанс двойного удара %", 50.0, true),
        DOUBLE_DAMAGE("Шанс двойного урона %", 50.0, true),
        CRIT_DAMAGE("Крит. урон %", 50.0, true),
        CRIT_CHANCE("Шанс крита %", 30.0, true),
        INTELLIGENCE("Интеллект", 202.0, false),
        DEXTERITY("Ловкость", 202.0, false),
        STRENGTH("Сила", 202.0, false),
        DAMAGE_REDUCTION("Снижение урона %", 12.0, true),
        MAX_HEALTH("Макс. здоровье %", 35.0, true),
        DEFENSE_PERCENT("Защита %", 40.0, true),
        FLAT_DEFENSE("Защита (плоская)", 3500.0, false),
        DODGE_CHANCE("Шанс уклонения %", 20.0, true),
        LIFE_ON_HIT("Здоровье за удар", 120.0, false),
        LIFE_ON_KILL("Здоровье за убийство", 350.0, false),
        LIFE_RECOVERY("Здоровье за удар и за убийство %", 23.5, true),
        GOLD_GAIN("Бонус золота %", 82.5, true),
        EXP_GAIN("Бонус опыта %", 50.0, true),
        BLOODTHIRST("Жажда крови %", 20.0, true),
        ADDED_DAMAGE("Фикс. урон атаки", 1800.0, false),
        ELEMENTAL_CONVERT("Преобразование в стихию %", 25.0, true),
        EMPTY_POSITION("Пустая позиция", 0.0, false);

        public final String ruName;
        public final double refRoll; // эталонный ролл для калибровки масштаба предмета
        public final boolean isPercent;

        AffixType(String ruName, double refRoll, boolean isPercent) {
            this.ruName = ruName;
            this.refRoll = refRoll;
            this.isPercent = isPercent;
        }
    }

    public static final class ParsedAffixRow {
        public final int positionIndex; // 1..N сверху вниз на предмете
        public final AffixType type;
        public final String rawLine;
        public final double value;
        public final int awakenStacks;   // I=1, II=2, III=3, IV=4, V=5...
        public final int awakenBonusPct; // +10%, +20%, +30%... на ЭТОМ месте

        public ParsedAffixRow(int positionIndex, AffixType type, String rawLine,
                              double value, int awakenStacks, int awakenBonusPct) {
            this.positionIndex = positionIndex;
            this.type = type;
            this.rawLine = rawLine;
            this.value = value;
            this.awakenStacks = awakenStacks;
            this.awakenBonusPct = awakenBonusPct;
        }
    }

    public static final class OptimizationReport {
        public final SlotAffixAdvisor.GearSlot slot;
        public final boolean slotInferredFromAffixes;
        public final List<ParsedAffixRow> currentRows;
        public final AffixType[] optimalByPos;
        public final int replaceCount;
        public final double netTotalGainPct;
        public final String shortBadge;
        public final String headline;
        public final String details;
        public final ScreenAnalyzer.VerdictColor verdictColor;

        public OptimizationReport(SlotAffixAdvisor.GearSlot slot, boolean slotInferredFromAffixes,
                                  List<ParsedAffixRow> currentRows, AffixType[] optimalByPos,
                                  int replaceCount, double netTotalGainPct,
                                  String shortBadge, String headline, String details,
                                  ScreenAnalyzer.VerdictColor verdictColor) {
            this.slot = slot;
            this.slotInferredFromAffixes = slotInferredFromAffixes;
            this.currentRows = currentRows;
            this.optimalByPos = optimalByPos;
            this.replaceCount = replaceCount;
            this.netTotalGainPct = netTotalGainPct;
            this.shortBadge = shortBadge;
            this.headline = headline;
            this.details = details;
            this.verdictColor = verdictColor;
        }
    }

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

    /**
     * Допустимые пулы вторичных аффиксов под каждый конкретный слот экипировки в IdleArc 1.3.15.
     */
    public static List<AffixType> getCompatiblePool(SlotAffixAdvisor.GearSlot slot) {
        if (slot == null) slot = SlotAffixAdvisor.GearSlot.WEAPON;
        switch (slot) {
            case WEAPON:
                return Arrays.asList(
                        AffixType.PET_DAMAGE, AffixType.DOUBLE_HIT, AffixType.DOUBLE_DAMAGE,
                        AffixType.INTELLIGENCE, AffixType.CRIT_DAMAGE, AffixType.CRIT_CHANCE,
                        AffixType.DEXTERITY, AffixType.STRENGTH, AffixType.ADDED_DAMAGE,
                        AffixType.LIFE_RECOVERY, AffixType.LIFE_ON_HIT, AffixType.ELEMENTAL_CONVERT);
            case TORCH:
                return Arrays.asList(
                        AffixType.PET_DAMAGE, AffixType.DOUBLE_HIT, AffixType.DOUBLE_DAMAGE,
                        AffixType.INTELLIGENCE, AffixType.CRIT_DAMAGE, AffixType.CRIT_CHANCE,
                        AffixType.BLOODTHIRST, AffixType.LIFE_RECOVERY,
                        AffixType.GOLD_GAIN, AffixType.EXP_GAIN);
            case GLOVES:
                return Arrays.asList(
                        AffixType.DOUBLE_HIT, AffixType.DOUBLE_DAMAGE, AffixType.PET_DAMAGE,
                        AffixType.CRIT_DAMAGE, AffixType.CRIT_CHANCE, AffixType.INTELLIGENCE,
                        AffixType.DEXTERITY, AffixType.STRENGTH, AffixType.MAX_HEALTH,
                        AffixType.LIFE_ON_HIT);
            case AMULET:
            case RING:
                return Arrays.asList(
                        AffixType.PET_DAMAGE, AffixType.DOUBLE_HIT, AffixType.DOUBLE_DAMAGE,
                        AffixType.CRIT_DAMAGE, AffixType.CRIT_CHANCE, AffixType.INTELLIGENCE,
                        AffixType.DEXTERITY, AffixType.STRENGTH, AffixType.LIFE_ON_HIT,
                        AffixType.LIFE_ON_KILL, AffixType.GOLD_GAIN, AffixType.EXP_GAIN);
            case HELMET:
                return Arrays.asList(
                        AffixType.INTELLIGENCE, AffixType.DAMAGE_REDUCTION, AffixType.MAX_HEALTH,
                        AffixType.DEFENSE_PERCENT, AffixType.LIFE_RECOVERY, AffixType.LIFE_ON_KILL,
                        AffixType.DODGE_CHANCE, AffixType.GOLD_GAIN, AffixType.EXP_GAIN,
                        AffixType.DEXTERITY, AffixType.STRENGTH, AffixType.FLAT_DEFENSE);
            case CHEST:
                return Arrays.asList(
                        AffixType.INTELLIGENCE, AffixType.DAMAGE_REDUCTION, AffixType.MAX_HEALTH,
                        AffixType.DEFENSE_PERCENT, AffixType.LIFE_RECOVERY, AffixType.DODGE_CHANCE,
                        AffixType.GOLD_GAIN, AffixType.EXP_GAIN, AffixType.DEXTERITY,
                        AffixType.STRENGTH, AffixType.FLAT_DEFENSE);
            case BELT:
                return Arrays.asList(
                        AffixType.INTELLIGENCE, AffixType.MAX_HEALTH, AffixType.DAMAGE_REDUCTION,
                        AffixType.DEFENSE_PERCENT, AffixType.LIFE_RECOVERY, AffixType.LIFE_ON_KILL,
                        AffixType.GOLD_GAIN, AffixType.EXP_GAIN, AffixType.DEXTERITY,
                        AffixType.STRENGTH, AffixType.FLAT_DEFENSE);
            case BOOTS:
                return Arrays.asList(
                        AffixType.INTELLIGENCE, AffixType.MAX_HEALTH, AffixType.DAMAGE_REDUCTION,
                        AffixType.DEFENSE_PERCENT, AffixType.DODGE_CHANCE, AffixType.LIFE_RECOVERY,
                        AffixType.GOLD_GAIN, AffixType.EXP_GAIN, AffixType.DEXTERITY,
                        AffixType.STRENGTH, AffixType.FLAT_DEFENSE);
            default:
                return Arrays.asList(
                        AffixType.PET_DAMAGE, AffixType.DOUBLE_HIT, AffixType.DOUBLE_DAMAGE,
                        AffixType.INTELLIGENCE, AffixType.CRIT_DAMAGE, AffixType.CRIT_CHANCE,
                        AffixType.MAX_HEALTH, AffixType.DAMAGE_REDUCTION, AffixType.GOLD_GAIN, AffixType.EXP_GAIN);
        }
    }

    /**
     * Главная точка входа: анализирует распознанные строки открытого предмета и выдаёт
     * точный по-строчный план замены аффиксов с учётом того, на каких именно позициях (#1..#N)
     * лежит процентный бонус Пробуждения.
     */
    public static OptimizationReport analyzeItemAffixes(
            List<IdleArcOcrEngine.OcrLine> ocrLines,
            SlotAffixAdvisor.GearSlot explicitSlot,
            DruidProfile profile) {

        if (ocrLines == null || ocrLines.isEmpty()) return null;

        // Изолируем активное модальное окно от затемнённого фонового экрана (Снаряжение/Кузница)
        List<IdleArcOcrEngine.OcrLine> modalLines = IdleArcOcrEngine.isolateActiveModalLines(ocrLines);

        SlotAffixAdvisor.GearSlot detectedSlot = explicitSlot != null
                ? explicitSlot
                : SlotAffixAdvisor.detectSlotFromModalLines(modalLines);

        // На талисманах и питомцах своя механика (надписи +9 / гарант петов), здесь — только предметы с позициями аффиксов
        if (detectedSlot == SlotAffixAdvisor.GearSlot.TALISMAN || detectedSlot == SlotAffixAdvisor.GearSlot.PET) {
            return null;
        }

        List<ParsedAffixRow> rows = extractSecondaryAffixRows(modalLines);
        if (rows.isEmpty()) {
            return null;
        }

        boolean slotInferred = false;
        if (detectedSlot == null) {
            List<String> modalPlain = new ArrayList<>(modalLines.size());
            for (IdleArcOcrEngine.OcrLine ml : modalLines) modalPlain.add(ml.text);
            boolean hasArmorBaseDef = SlotAffixAdvisor.hasArmorBaseDefenseAboveAffixes(modalPlain);
            detectedSlot = inferSlotFromObservedAffixes(rows, hasArmorBaseDef);
            slotInferred = true;
        }

        // Гарантируем, что любой уже стоящий на предмете реальный аффикс входит в пул совместимости этого предмета
        List<AffixType> pool = new ArrayList<>(getCompatiblePool(detectedSlot));
        for (ParsedAffixRow r : rows) {
            if (r.type != AffixType.EMPTY_POSITION && !pool.contains(r.type)) {
                pool.add(r.type);
            }
        }

        int n = rows.size();
        double itemScale = estimateItemScale(rows);

        // Вклад этого предмета в Снижение урона и Уклонение (чтобы проверить, не провалится ли игрок ниже капа 95% при замене!)
        double itemDrContribution = 0.0;
        double itemDodgeContribution = 0.0;
        for (ParsedAffixRow r : rows) {
            if (r.type == AffixType.DAMAGE_REDUCTION) itemDrContribution += r.value;
            if (r.type == AffixType.DODGE_CHANCE) itemDodgeContribution += r.value;
        }

        // 1. Точный комбинаторный поиск оптимального назначения без повторов по позициям 0..n-1
        AffixType[] bestAssignment = solveOptimalAssignment(
                rows, pool, itemScale, profile, itemDrContribution, itemDodgeContribution);

        // 2. Формируем по-строчный вердикт ТОЛЬКО по текущим аффиксам #1..#N (макс. 5/5) и их реальной разнице
        StringBuilder out = new StringBuilder();
        String slotHeader = slotInferred
                ? "Предмет (шапка скрыта — выбери слот кнопкой выше)"
                : (detectedSlot.shortChip + " (" + detectedSlot.slotTitle + ")");

        List<String> awakenedSummary = new ArrayList<>();
        int totalAwakenStacks = 0;
        for (ParsedAffixRow r : rows) {
            if (r.awakenBonusPct > 0) {
                totalAwakenStacks += Math.max(1, r.awakenStacks);
                awakenedSummary.add("#" + r.positionIndex + " ["
                        + (r.awakenStacks > 0 ? toRoman(r.awakenStacks) + " · " : "")
                        + "+" + r.awakenBonusPct + "%]");
            }
        }

        out.append("Предмет: ").append(slotHeader)
           .append(" · Аффиксов: ").append(n).append("/").append(MAX_AFFIXES_PER_ITEM).append("\n");
        if (!awakenedSummary.isEmpty()) {
            out.append("💎 Пробуждение позиций (A").append(totalAwakenStacks).append("): ")
               .append(join(awakenedSummary, ", ")).append("\n");
        } else {
            out.append("ℹ️ Голубых усилений Пробуждения на строках не обнаружено (все позиции ×1.00)\n");
        }

        int replaceCount = 0;
        double totalCurrentScore = 0.0;
        double totalTargetScore = 0.0;

        // Проверяем случай, когда топовый аффикс УЖЕ стоит на обычной позиции (×1.00), а бонус Пробуждения висит на другой строке
        int maxAwakenPosIdx = -1;
        int maxAwakenPct = 0;
        for (int i = 0; i < n; i++) {
            if (rows.get(i).awakenBonusPct > maxAwakenPct) {
                maxAwakenPct = rows.get(i).awakenBonusPct;
                maxAwakenPosIdx = i;
            }
        }

        for (int i = 0; i < n; i++) {
            ParsedAffixRow row = rows.get(i);
            AffixType target = bestAssignment[i];
            double mult = 1.0 + (row.awakenBonusPct / 100.0);

            double curVal = row.value > 0 ? row.value : (row.type.refRoll * itemScale * mult);
            EvalMetric curMetric = evaluateAffixMetric(
                    row.type, curVal, profile, itemDrContribution, itemDodgeContribution);

            double targetExpectedVal = target.refRoll * itemScale * mult;
            EvalMetric targetMetric = evaluateAffixMetric(
                    target, targetExpectedVal, profile, itemDrContribution, itemDodgeContribution);

            totalCurrentScore += curMetric.utilityScore;

            out.append("\n");
            // Заголовок конкретной позиции #1..#N
            out.append("#").append(row.positionIndex);
            if (row.awakenBonusPct > 0) {
                out.append(" [💎 ПРОБУЖДЕНИЕ ");
                if (row.awakenStacks > 0) out.append(toRoman(row.awakenStacks)).append(" · ");
                out.append("+").append(row.awakenBonusPct).append("%]");
            }
            out.append(": ").append(formatCurrentAffix(row)).append("\n");

            boolean sameType = (row.type == target);
            double diffUtility = targetMetric.utilityScore - curMetric.utilityScore;

            // Если уже стоит целевой аффикс ИЛИ текущий высокий ролл даёт практически столько же (разница < 0.45%)
            if (sameType || diffUtility < 0.45) {
                totalTargetScore += curMetric.utilityScore;
                out.append("   ✅ ОСТАВИТЬ НА ЭТОМ МЕСТЕ (сейчас даёт ").append(curMetric.shortImpact).append(")");
                if (row.awakenBonusPct > 0) {
                    out.append(" — отлично использует бонус +").append(row.awakenBonusPct).append("%!");
                }
                out.append("\n");
            } else {
                replaceCount++;
                totalTargetScore += targetMetric.utilityScore;
                String expectedRollStr = formatRollValue(target, targetExpectedVal);
                out.append("   🔄 ВМЕСТО НЕГО ПОСТАВИТЬ: ").append(target.ruName)
                   .append(" (~").append(expectedRollStr).append(" на этом месте)\n");
                out.append("   📊 Разница здесь: сейчас ").append(curMetric.shortImpact)
                   .append(" → даст ").append(targetMetric.shortImpact)
                   .append(String.format(Locale.US, " (чистая выгода +%.1f%% ценности)", diffUtility)).append("\n");
                out.append("   💡 Почему: ").append(buildWhyExplanation(row, curMetric, target, targetMetric, profile)).append("\n");
                if (row.awakenBonusPct > 0) {
                    out.append("   🔒 Важно по Кузнице: пробуждённый аффикс [")
                       .append(row.awakenStacks > 0 ? toRoman(row.awakenStacks) : ("+" + row.awakenBonusPct + "%"))
                       .append("] в игре нельзя перековать напрямую — сначала замени непробуждённые позиции, ")
                       .append("либо нажми «Перераспределить пробуждение», чтобы голубой бонус сошёл с #")
                       .append(row.positionIndex).append("!\n");
                }
            }
        }

        // Умная подсказка, если лучший скейлящийся аффикс уже стоит на непробуждённой позиции, а голубой бонус упал на другую строку
        if (maxAwakenPosIdx >= 0 && maxAwakenPct > 0) {
            AffixType bestForMaxBoost = findSingleBestForBoost(pool, itemScale, maxAwakenPct, profile, itemDrContribution, itemDodgeContribution);
            int existingIdx = -1;
            for (int i = 0; i < n; i++) {
                if (rows.get(i).type == bestForMaxBoost) {
                    existingIdx = i;
                    break;
                }
            }
            if (existingIdx >= 0 && existingIdx != maxAwakenPosIdx
                    && rows.get(existingIdx).awakenBonusPct < maxAwakenPct) {
                out.append("\n⚡ УМНЫЙ ХОД ПО ПРОБУЖДЕНИЮ: «").append(bestForMaxBoost.ruName)
                   .append("» уже стоит у тебя на месте #").append(existingIdx + 1)
                   .append(" (+").append(rows.get(existingIdx).awakenBonusPct).append("%), а главный бонус +")
                   .append(maxAwakenPct).append("% сейчас на месте #").append(maxAwakenPosIdx + 1)
                   .append(". Дубликаты аффиксов запрещены, поэтому на #").append(maxAwakenPosIdx + 1)
                   .append(" ставь второй топ-стат, либо нажми «Перераспределить Пробуждение», чтобы +")
                   .append(maxAwakenPct).append("% переехал на #").append(existingIdx + 1).append("!");
            } else if (rows.get(maxAwakenPosIdx).type != bestAssignment[maxAwakenPosIdx]) {
                out.append("\nℹ️ Если Кузница пишет, что пробуждённую позицию #").append(maxAwakenPosIdx + 1)
                   .append(" нельзя сменить напрямую — сначала выбей «")
                   .append(bestAssignment[maxAwakenPosIdx].ruName)
                   .append("» на свободной позиции и перекинь голубой бонус +").append(maxAwakenPct)
                   .append("% кнопкой «Перераспределить Пробуждение»!");
            }
        }

        double netGain = Math.max(0.0, totalTargetScore - totalCurrentScore);
        ScreenAnalyzer.VerdictColor color;
        String shortBadge;
        String headline;

        if (replaceCount == 0) {
            color = ScreenAnalyzer.VerdictColor.GOOD;
            String badgePrefix = slotInferred ? "📦 Предмет" : detectedSlot.shortChip;
            shortBadge = "✅ " + badgePrefix + ": все " + n + "/" + n + " на месте!";
            headline = "✅ Идеальная раскладка: все " + n + " аффиксов стоят на своих лучших местах!";
        } else if (netGain >= 8.0 || replaceCount >= 2) {
            color = ScreenAnalyzer.VerdictColor.WARN;
            shortBadge = String.format(Locale.US, "🔄 Замени %d/%d (+%.1f%%)", replaceCount, n, netGain);
            headline = String.format(Locale.US, "🔄 Замени %d из %d аффиксов → выгода +%.1f%% эффективности", replaceCount, n, netGain);
        } else {
            color = ScreenAnalyzer.VerdictColor.GOOD;
            shortBadge = String.format(Locale.US, "⚖️ Докрути %d/%d (+%.1f%%)", replaceCount, n, netGain);
            headline = String.format(Locale.US, "⚖️ Хороший предмет: можно заменить %d слот (+%.1f%%)", replaceCount, netGain);
        }

        return new OptimizationReport(
                detectedSlot, slotInferred, rows, bestAssignment,
                replaceCount, netGain, shortBadge, headline, out.toString().trim(), color);
    }

    // =========================================================================
    // 1. ТОЧНОЕ ИЗВЛЕЧЕНИЕ ТОЛЬКО ВТОРИЧНЫХ АФФИКСОВ ПРЕДМЕТА (#1..#5, МАКС 5/5!)
    // =========================================================================

    /**
     * Находит индекс строки заголовка секции вторичных аффиксов («АФФИКСЫ 5/5», «Аффиксы»,
     * «Позиции аффиксов», «Пробуждённые позиции аффиксов» и т.д.), либо -1.
     */
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
        // Не путаем со строкой самого стата (если вдруг в описании стата встретилось слово)
        if (low.contains("урон") || low.contains("интеллект") || low.contains("здоров")
                || low.contains("крит") || low.contains("уклонен") || low.contains("защит")) {
            return false;
        }
        if (low.startsWith("аффикс") || low.startsWith("аффuкс") || low.startsWith("аффnкс")
                || low.contains("позиции аффикс") || low.contains("позиций аффикс")
                || low.contains("аффиксы") || low.contains("аффиксов")) {
            return true;
        }
        // Запасной якорь на случай сильного искажения слова «АФФИКСЫ», но чёткого счётчика «5/5» / «4/5» справа
        if (!low.contains("ранг") && !low.contains("уров") && !low.contains("гнезд") && !low.contains("гнёзд")) {
            Matcher cm = AFFIX_COUNTER_PAT.matcher(rt);
            if (cm.find() && rt.length() <= 28) {
                return true;
            }
        }
        return false;
    }

    /**
     * Извлекает точный лимит открытых позиций X из заголовка вида «АФФИКСЫ 4/5» или «Аффиксы (5/5)».
     * Всегда возвращает число в диапазоне 0..MAX_AFFIXES_PER_ITEM (строго не более 5!).
     */
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

    static List<ParsedAffixRow> extractSecondaryAffixRows(List<IdleArcOcrEngine.OcrLine> ocrLines) {
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
        // напр. строка 1: «Здоровье за удар и за убийство», строка 2: «+20%» (без названия стата на 2-й строке!)
        List<IdleArcOcrEngine.OcrLine> mergedSectionLines = new ArrayList<>();
        for (int i = startIdx; i < endIdx; i++) {
            IdleArcOcrEngine.OcrLine cur = ocrLines.get(i);
            String curRaw = cur.text.trim();
            if (curRaw.isEmpty()) continue;
            String curLow = DruidKnowledgeBase.normalizeForMatch(curRaw);

            if (i + 1 < endIdx && !curRaw.contains("+")
                    && classifyAffixLine(curLow, curRaw) != null) {
                IdleArcOcrEngine.OcrLine nxt = ocrLines.get(i + 1);
                String nxtRaw = nxt.text.trim();
                String nxtLow = DruidKnowledgeBase.normalizeForMatch(nxtRaw);
                if (nxtRaw.startsWith("+") && classifyAffixLine(nxtLow, nxtRaw) == null
                        && (nxt.y0 - cur.y1) <= 48) {
                    IdleArcOcrEngine.OcrLine combined = new IdleArcOcrEngine.OcrLine(
                            curRaw + " " + nxtRaw,
                            cur.y0, nxt.y1,
                            Math.max(cur.cyanRatio, nxt.cyanRatio),
                            Math.max(cur.purpleRatio, nxt.purpleRatio),
                            Math.max(cur.badgeStrokes, nxt.badgeStrokes),
                            Math.max(cur.maxLum, nxt.maxLum));
                    mergedSectionLines.add(combined);
                    i++; // проглотили вторую строку переноса
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
            if (isNonAffixMetadataLine(low)) {
                continue;
            }

            if (!sawExplicitAffixHeader && rawCandidates.isEmpty() && isLikelyImplicitHeaderLine(low, raw)) {
                continue;
            }

            if (low.contains("заполните позицию") || low.contains("пустая позиция") || low.contains("выберите аффикс")) {
                rawCandidates.add(new ParsedAffixRow(rawCandidates.size() + 1, AffixType.EMPTY_POSITION, raw, 0.0, 0, 0));
                if (sawExplicitAffixHeader && rawCandidates.size() >= maxAllowedRows) break;
                continue;
            }

            AffixType type = classifyAffixLine(low, raw);
            if (type == null) continue;

            double statVal = extractPlusStatValue(raw);
            List<Double> nums = extractAllNumbers(raw);
            if (statVal <= 0.0 && nums.isEmpty()) continue;
            if (statVal <= 0.0) {
                statVal = pickPrimaryStatValue(type, raw, nums);
            }
            int[] awaken = detectAwakeningOnRow(ol, raw, nums, statVal);

            if (sawExplicitAffixHeader) {
                // Внутри секции вторичных аффиксов дубликаты одного типа запрещены механикой игры
                if (seenTypes.contains(type)) continue;
                seenTypes.add(type);
                rawCandidates.add(new ParsedAffixRow(
                        rawCandidates.size() + 1, type, raw, statVal, awaken[0], awaken[1]));
                if (rawCandidates.size() >= maxAllowedRows) break;
            } else {
                // Если заголовок «Аффиксы» был вне кадра и наверху попался имплисит того же типа,
                // что и реальный вторичный аффикс ниже — удаляем верхний имплисит!
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
                        rawCandidates.size() + 1, type, raw, statVal, awaken[0], awaken[1]));
            }
        }

        // Жёсткий инвариант игры 5/5: если заголовок «АФФИКСЫ» был скроллнут за верхний край,
        // а над 5 вторичными аффиксами остался вторичный имплисит редкой базы (hasSecondaryImplicit),
        // отбрасываем верхние имплиситы и оставляем ровно последние <= 5 вторичных аффиксов!
        int cap = Math.min(MAX_AFFIXES_PER_ITEM, maxAllowedRows);
        if (rawCandidates.size() > cap) {
            rawCandidates = new ArrayList<>(
                    rawCandidates.subList(rawCandidates.size() - cap, rawCandidates.size()));
        }

        List<ParsedAffixRow> result = new ArrayList<>(rawCandidates.size());
        for (int i = 0; i < rawCandidates.size(); i++) {
            ParsedAffixRow r = rawCandidates.get(i);
            result.add(new ParsedAffixRow(i + 1, r.type, r.rawLine, r.value, r.awakenStacks, r.awakenBonusPct));
        }
        return result;
    }

    private static boolean isSectionAfterAffixes(String low) {
        return low.contains("ячейка") || low.contains("чейка")
                || (low.startsWith("афф") && (low.contains("5") || low.contains("уров")))
                || low.startsWith("бонус добычи") || low.startsWith("бонус дропа")
                || low.startsWith("гнезд") || low.startsWith("гнёзд") || low.contains("самоцвет")
                || low.equals("нефрит") || low.equals("янтарь") || low.equals("лазурит") || low.equals("гранат")
                // Все префиксы секции «Бонус дропа / Бонус добычи»:
                || low.startsWith("спутника") || low.startsWith("свирепый") || low.startsWith("отражающий")
                || low.startsWith("истребителя") || low.startsWith("сокрушительный") || low.startsWith("неумолимый")
                || low.startsWith("бешенство:") || low.startsWith("стихийный всплеск")
                || low.startsWith("укреплённый") || low.startsWith("укрепленный") || low.startsWith("бронированный")
                || low.startsWith("живучий") || low.startsWith("точный") || low.startsWith("жестокий")
                || low.startsWith("кровожадный") || low.startsWith("вампирский") || low.startsWith("иссушающий")
                || low.startsWith("удачливый") || low.startsWith("процветающий")
                || low.startsWith("учёного:") || low.startsWith("ученого:") || low.startsWith("собирателя")
                // Кнопки действий внизу попапа («Снять · Улучшить · Создать · Внешний вид»):
                || low.contains("внешний вид") || low.contains("улучшить") || low.contains("учшить")
                || low.equals("пробуждение") || low.equals("стоимость") || low.startsWith("камни крафта")
                || low.startsWith("улучшение тира") || low.startsWith("улучшение уровня")
                || low.equals("надеть") || low.startsWith("снять") || low.equals("разобрать") || low.equals("продать")
                || low.contains("надеть предмет") || low.contains("перераспределить пробуждение")
                || low.contains("материалы пробуждения");
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
        // На оружии верхняя крупная строка «Урон атаки 54 200» без '+' в начале — это базовая атака оружия, а не аффикс
        if (low.startsWith("урон атаки") && !raw.trim().startsWith("+")) return true;
        if (low.startsWith("защита:") || (low.startsWith("защита ") && !raw.contains("+") && !raw.contains("%"))) return true;
        return false;
    }

    public static AffixType classifyAffixLine(String low, String raw) {
        if (low.contains("урон питомц") || low.contains("урона питомц") || low.contains("урону питомц")) {
            return AffixType.PET_DAMAGE;
        }
        // Сначала «двойного урона» (содержит «урон»), затем «двойного удара» (в т.ч. слипшееся «двойного мара»/«дара» в Roboto)
        if (low.contains("двойного урон") || low.contains("двойной урон")) {
            return AffixType.DOUBLE_DAMAGE;
        }
        if (low.contains("двойного удар") || low.contains("двойной удар")
                || (low.contains("двойного ") && !low.contains("урон"))) {
            return AffixType.DOUBLE_HIT;
        }
        if (low.contains("крит. урон") || low.contains("крит урон") || low.contains("критический урон")) {
            return AffixType.CRIT_DAMAGE;
        }
        if (low.contains("шанс крит") || low.contains("крит. удара")) {
            return AffixType.CRIT_CHANCE;
        }
        if (low.contains("снижение урона") || low.contains("получаемого урона")) {
            return AffixType.DAMAGE_REDUCTION;
        }
        if (low.contains("уклонен")) {
            return AffixType.DODGE_CHANCE;
        }
        if (low.contains("макс. здоров") || low.contains("макс здоров") || low.contains("максимальное здоров")) {
            return AffixType.MAX_HEALTH;
        }
        if (low.contains("жажда крови") || low.contains("жажды крови")) {
            return AffixType.BLOODTHIRST;
        }
        // Процентный аффикс «Здоровье за удар и за убийство +20%» проверяем ДО плоского «Здоровье за удар»!
        if ((low.contains("здоровье за удар") && low.contains("убийств"))
                || low.contains("восстановление здоров") || low.contains("регенерац")
                || (low.contains("здоровье за удар") && raw.contains("%"))) {
            return AffixType.LIFE_RECOVERY;
        }
        if (low.contains("здоровье за удар") || low.contains("здоровье при ударе")) {
            return AffixType.LIFE_ON_HIT;
        }
        if (low.contains("здоровье за убийство")) {
            return AffixType.LIFE_ON_KILL;
        }
        if (low.contains("бонус золот") || (low.contains("золот") && raw.contains("%"))) {
            return AffixType.GOLD_GAIN;
        }
        if (low.contains("бонус опыт") || (low.contains("опыт") && raw.contains("%"))) {
            return AffixType.EXP_GAIN;
        }
        if (low.contains("интеллект")) {
            return AffixType.INTELLIGENCE;
        }
        if (low.contains("ловкост")) {
            return AffixType.DEXTERITY;
        }
        if (SlotAffixAdvisor.hasStem(low, "сил") && !low.contains("талисман") && !low.contains("слов")) {
            return AffixType.STRENGTH;
        }
        if (low.contains("преобразуется в") || low.contains("преобразование в") || low.contains("преобразовано в")) {
            return AffixType.ELEMENTAL_CONVERT;
        }
        if (low.contains("фикс. урон атаки") || low.contains("дополнительный урон")
                || low.contains("физический урон") || low.contains("урон атаки")
                || low.contains("урон огн") || low.contains("урон яд") || low.contains("урон природ")
                || low.contains("урон вод") || low.contains("урон земл") || low.contains("урон воздух")) {
            return AffixType.ADDED_DAMAGE;
        }
        if (low.contains("защит")) {
            return raw.contains("%") ? AffixType.DEFENSE_PERCENT : AffixType.FLAT_DEFENSE;
        }
        return null;
    }

    /**
     * Определяет число усилений Пробуждения (I..VIII) и % бонуса (+10% за каждое попадание Пробуждения)
     * конкретно на данной строке аффикса:
     *  - в IdleArc 1.3.15 римская цифра I, II, III, IV, V, VI рисуется СПРАВА после значения ролла («+424% III», «+494% II», «+56% I»);
     *  - поддерживаются все оптические варианты штрихов Roboto (Ill, lll, ll, l, 1, lV, Vl);
     *  - также поддерживается префиксный формат и явный (+20%).
     */
    private static int[] detectAwakeningOnRow(IdleArcOcrEngine.OcrLine ol, String raw, List<Double> nums, double statVal) {
        int stacks = 0;
        int pct = 0;

        // 1. Явный процент пробуждения в скобках (+20%) или ★2
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

        // 2. Римская цифра СПРАВА после ролла +число[%] (основной формат IdleArc 1.3.15: «• Урон питомца +424% III»)
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

        // 3. Римская цифра СЛЕВА перед аффиксом (запасной формат: «II +148 Интеллект»)
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

        // 4. Пиксельная подсветка голубым (cyan) / фиолетовым цветом усиленного аффикса в IdleArc
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

    private static String toRoman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return (n >= 1 && n < r.length) ? r[n] : ("×" + n);
    }

    private static SlotAffixAdvisor.GearSlot inferSlotFromObservedAffixes(
            List<ParsedAffixRow> rows, boolean hasArmorBaseDef) {
        if (hasArmorBaseDef) {
            // Базовая Защита бывает ТОЛЬКО на броне (Шлем/Броня/Перчатки/Пояс/Сапоги) — НИКОГДА на Кольце/Амулете/Оружии/Факеле!
            for (ParsedAffixRow r : rows) {
                if (r.type == AffixType.PET_DAMAGE || r.type == AffixType.DOUBLE_HIT
                        || r.type == AffixType.DOUBLE_DAMAGE || r.type == AffixType.CRIT_CHANCE
                        || r.type == AffixType.CRIT_DAMAGE) {
                    return SlotAffixAdvisor.GearSlot.GLOVES;
                }
            }
            return SlotAffixAdvisor.GearSlot.CHEST;
        }
        for (ParsedAffixRow r : rows) {
            if (r.type == AffixType.BLOODTHIRST) return SlotAffixAdvisor.GearSlot.TORCH;
            if (r.type == AffixType.ADDED_DAMAGE || r.type == AffixType.ELEMENTAL_CONVERT) {
                return SlotAffixAdvisor.GearSlot.WEAPON;
            }
        }
        for (ParsedAffixRow r : rows) {
            if (r.type == AffixType.DAMAGE_REDUCTION || r.type == AffixType.DODGE_CHANCE
                    || r.type == AffixType.FLAT_DEFENSE || r.type == AffixType.DEFENSE_PERCENT
                    || r.type == AffixType.MAX_HEALTH) {
                return SlotAffixAdvisor.GearSlot.CHEST;
            }
        }
        return SlotAffixAdvisor.GearSlot.WEAPON;
    }

    // =========================================================================
    // 2. МАТЕМАТИЧЕСКАЯ МОДЕЛЬ ЦЕННОСТИ АФФИКСА НА КОНКРЕТНОМ МЕСТЕ И ПОИСК ОПТИМУМА
    // =========================================================================

    private static final class EvalMetric {
        final double utilityScore; // в эквивалентных % боевой/фарм эффективности Друида
        final String shortImpact;
        final String reasonNote;

        EvalMetric(double utilityScore, String shortImpact, String reasonNote) {
            this.utilityScore = utilityScore;
            this.shortImpact = shortImpact;
            this.reasonNote = reasonNote;
        }
    }

    /**
     * Вычисляет реальную маржинальную пользу ролла value для живого профиля Друида.
     */
    private static EvalMetric evaluateAffixMetric(
            AffixType type, double val, DruidProfile p,
            double itemDrContribution, double itemDodgeContribution) {

        boolean isFarm = (p.mode == DruidProfile.BuildMode.FARM);
        boolean isBoss = (p.mode == DruidProfile.BuildMode.BOSS);

        double statMult = 1.0 + 0.001 * Math.max(0.0, p.intelligence) + 0.0005 * Math.max(0.0, p.agility);
        double critTotalMult = 100.0 + Math.max(150.0, p.critDamage) + Math.max(0.0, p.critChance - 100.0);

        switch (type) {
            case PET_DAMAGE: {
                // Прямой профильный множитель урона питомца (общий пул на A5/A6 шмоте + петах ~3500..3700%)
                double dpsPct = (val / 3600.0) * 100.0;
                return new EvalMetric(dpsPct,
                        String.format(Locale.US, "+%.1f%% итогового DPS пета", dpsPct),
                        "главный прямой множитель урона питомца");
            }
            case DOUBLE_HIT: {
                double dpsPct = (val / (100.0 + Math.max(0.0, p.doubleHitChance))) * 100.0;
                double score = isBoss ? dpsPct * 1.06 : dpsPct;
                return new EvalMetric(score,
                        String.format(Locale.US, "+%.1f%% итогового DPS пета", dpsPct),
                        String.format(Locale.US, "при твоих %.0f%% Двойного удара мультипликативно даёт больше всех проков", p.doubleHitChance));
            }
            case DOUBLE_DAMAGE: {
                double dpsPct = (val / (100.0 + Math.max(0.0, p.doubleDamageChance))) * 100.0;
                return new EvalMetric(dpsPct,
                        String.format(Locale.US, "+%.1f%% итогового DPS пета", dpsPct),
                        String.format(Locale.US, "при твоих %.0f%% Двойного урона напрямую множит каждый удар пета", p.doubleDamageChance));
            }
            case INTELLIGENCE: {
                // 1 Интеллект = +0.1% к стат-множителю урона пета
                double dpsPct = ((0.001 * val) / statMult) * 100.0;
                return new EvalMetric(dpsPct,
                        String.format(Locale.US, "+%.1f%% итогового DPS пета (+%.0f Инт)", dpsPct, val),
                        "каждое очко Интеллекта даёт +0.1% урона питомца (в 2 раза сильнее Ловкости)");
            }
            case CRIT_DAMAGE: {
                double dpsPct = (val / critTotalMult) * 100.0;
                return new EvalMetric(dpsPct,
                        String.format(Locale.US, "+%.1f%% итогового DPS пета", dpsPct),
                        String.format(Locale.US, "делится на общий пул крит. множителя (%.0f%%)", critTotalMult));
            }
            case CRIT_CHANCE: {
                double dpsPct = (p.critChance >= 100.0)
                        ? (val / critTotalMult) * 100.0
                        : (val * 0.25);
                return new EvalMetric(dpsPct,
                        String.format(Locale.US, "+%.1f%% итогового DPS пета", dpsPct),
                        p.critChance >= 100.0
                                ? String.format(Locale.US, "шанс крита уже %.0f%% (>100%%), избыток идёт 1:1 в Крит. урон (меньше ролла самого КУ!)", p.critChance)
                                : "поднимает шанс крита до порога 100%");
            }
            case DAMAGE_REDUCTION: {
                double drWithoutItem = p.damageReductionRaw - itemDrContribution;
                if (drWithoutItem < 95.0) {
                    double needed = Math.min(val, 95.0 - drWithoutItem);
                    double ehpGain = needed * 1.45;
                    return new EvalMetric(isFarm ? ehpGain * 0.70 : ehpGain,
                            String.format(Locale.US, "+%.1f%% выживаемости (держит кап 95%% DR!)", ehpGain),
                            String.format(Locale.US, "без этого стата Снижение урона упадёт до %.1f%% (ниже капа 95%%!)", drWithoutItem));
                }
                return new EvalMetric(0.12,
                        "0.0% (оверкап Снижения урона > 95%)",
                        String.format(Locale.US, "даже без этой вещи у тебя ≥95%% Снижения урона (сейчас %.1f%%)", p.damageReductionRaw));
            }
            case MAX_HEALTH: {
                double util = isFarm ? (val * 0.08) : (val * 0.17);
                return new EvalMetric(util,
                        String.format(Locale.US, "+%.1f%% запаса HP (~+%.1f%% ценности)", val, util),
                        "защищает от ваншотов сквозь 95% снижения урона на пуше");
            }
            case DEFENSE_PERCENT: {
                double util = isFarm ? (val * 0.04) : (val * 0.08);
                return new EvalMetric(util,
                        String.format(Locale.US, "+%.1f%% брони (~+%.1f%% ценности)", val, util),
                        "вторичная выживаемость, уступает Интеллекту и % Макс. HP");
            }
            case DODGE_CHANCE: {
                double dodgeWithout = p.dodgeRaw - itemDodgeContribution;
                if (dodgeWithout >= 95.0) {
                    return new EvalMetric(0.0,
                            "0.0% (мёртвый оверкап уклонения!)",
                            String.format(Locale.US, "у тебя %.1f%% уклонения при жёстком капе игры 95%% — даёт ровно ноль", p.dodgeRaw));
                }
                double useful = Math.min(val, 95.0 - dodgeWithout);
                double util = useful * 0.35;
                return new EvalMetric(util,
                        String.format(Locale.US, "+%.1f%% уклонения до капа 95%%", useful),
                        "добирает недостающее уклонение до капа 95%");
            }
            case DEXTERITY: {
                // 1 Ловкость = +0.05% к стат-множителю урона пета (ровно вдвое слабее Интеллекта)
                double dpsPct = ((0.0005 * val) / statMult) * 100.0;
                return new EvalMetric(dpsPct,
                        String.format(Locale.US, "+%.1f%% итогового DPS пета (+%.0f Ловк)", dpsPct, val),
                        "Ловкость даёт вдвое меньше урона пета, чем Интеллект (+0.05% против +0.1%), а уклонение уже в капе");
            }
            case GOLD_GAIN: {
                double goldMultPct = (val / (100.0 + Math.max(0.0, p.goldBonus))) * 100.0;
                double util = isFarm ? (goldMultPct * 1.15) : 0.05;
                return new EvalMetric(util,
                        isFarm ? String.format(Locale.US, "+%.1f%% реального притока золота", goldMultPct)
                               : "0.0% к урону в бою (фарм-стат)",
                        isFarm ? "напрямую ускоряет прокачку ячеек снаряжения за золото"
                               : "в боевом режиме не даёт ни урона питомцу, ни выживаемости");
            }
            case EXP_GAIN: {
                double expMultPct = (val / (100.0 + Math.max(0.0, p.expBonus))) * 100.0;
                double util = isFarm ? (expMultPct * 1.05) : 0.04;
                return new EvalMetric(util,
                        isFarm ? String.format(Locale.US, "+%.1f%% реального притока опыта", expMultPct)
                               : "0.0% к урону в бою (фарм-стат)",
                        isFarm ? "ускоряет набор уровней"
                               : "в боевом режиме не усиливает пета");
            }
            case BLOODTHIRST: {
                double util = isFarm ? 0.3 : (val * 0.08);
                return new EvalMetric(util,
                        String.format(Locale.US, "+%.1f%% к лечению (+0%% DPS)", val),
                        "множит только лечение за удар/убийство, урона питомцу не даёт");
            }
            case LIFE_RECOVERY: {
                return new EvalMetric(0.28,
                        String.format(Locale.US, "+%.0f%% к отхилу (+0.0%% DPS пета)", val),
                        "усиливает только лечение героя за удар/убийство, урона питомцу не даёт");
            }
            case LIFE_ON_HIT: {
                return new EvalMetric(0.20,
                        "плоский отхил (+0.0% DPS пета)",
                        "слабый плоский сустейн, не масштабирует урон питомца");
            }
            case LIFE_ON_KILL: {
                return new EvalMetric(0.02,
                        "0.0% на боссах и 0.0% DPS",
                        "вообще не срабатывает в бою с одиночным боссом и не даёт урона");
            }
            case FLAT_DEFENSE: {
                return new EvalMetric(0.10,
                        "плоская защита (~0% отдачи)",
                        "сильно проигрывает процентным статам и Интеллекту");
            }
            case STRENGTH: {
                return new EvalMetric(0.0,
                        "0.0% для Друида (мёртвый стат)",
                        "Сила скейлит воина, но не даёт урона питомцу Друида");
            }
            case ADDED_DAMAGE: {
                return new EvalMetric(0.01,
                        "0.0% DPS пета (ловушка!)",
                        "бонусный урон атаки героя НЕ передаётся питомцу («Родство» берёт только базу оружия)");
            }
            case ELEMENTAL_CONVERT: {
                return new EvalMetric(0.01,
                        "0.0% на боссах и пете",
                        "преобразование стихий в IdleArc не затрагивает урон питомца и отключается на боссах");
            }
            case EMPTY_POSITION:
            default:
                return new EvalMetric(0.0, "0.0% (пусто)", "слот аффикса ещё не заполнен");
        }
    }

    /**
     * Точный комбинаторный решатель (глобальный оптимум по всем бездубликатным подмножествам пула слота):
     * распределяет N различных аффиксов из Pool(slot) по реальным позициям 0..N-1 так, чтобы:
     *  - позиции с максимальным бонусом Пробуждения (+X%) получили самые круто масштабируемые аффиксы этого слота;
     *  - уже стоящие на своих местах топовые аффиксы сохранялись на своих местах (без бессмысленных перестановок).
     */
    private static AffixType[] solveOptimalAssignment(
            List<ParsedAffixRow> rows, List<AffixType> pool, double itemScale,
            DruidProfile p, double itemDrContribution, double itemDodgeContribution) {

        int n = rows.size();
        int m = pool.size();
        double[][] utilMatrix = new double[n][m];

        for (int pos = 0; pos < n; pos++) {
            ParsedAffixRow r = rows.get(pos);
            double mult = 1.0 + (r.awakenBonusPct / 100.0);
            for (int j = 0; j < m; j++) {
                AffixType cand = pool.get(j);
                double expectedVal = cand.refRoll * itemScale * mult;
                double u = evaluateAffixMetric(cand, expectedVal, p, itemDrContribution, itemDodgeContribution).utilityScore;
                // Бонус сохранения (+0.85), чтобы не предлагать ломать уже стоящий сильный аффикс ради микроразницы <0.8%
                if (r.type == cand) {
                    u += 0.85;
                }
                utilMatrix[pos][j] = u;
            }
        }

        double[] bestScore = new double[]{-1e9};
        int[] bestChoice = new int[n];
        int[] curChoice = new int[n];
        boolean[] used = new boolean[m];

        searchBestRecursive(0, n, m, utilMatrix, used, curChoice, 0.0, bestScore, bestChoice);

        AffixType[] result = new AffixType[n];
        for (int i = 0; i < n; i++) {
            result[i] = pool.get(bestChoice[i]);
        }
        return result;
    }

    private static void searchBestRecursive(
            int pos, int n, int m, double[][] utilMatrix,
            boolean[] used, int[] curChoice, double accScore,
            double[] bestScore, int[] bestChoice) {
        if (pos == n) {
            if (accScore > bestScore[0]) {
                bestScore[0] = accScore;
                System.arraycopy(curChoice, 0, bestChoice, 0, n);
            }
            return;
        }
        for (int j = 0; j < m; j++) {
            if (!used[j]) {
                used[j] = true;
                curChoice[pos] = j;
                searchBestRecursive(pos + 1, n, m, utilMatrix, used, curChoice,
                        accScore + utilMatrix[pos][j], bestScore, bestChoice);
                used[j] = false;
            }
        }
    }

    private static AffixType findSingleBestForBoost(
            List<AffixType> pool, double itemScale, int awakenPct,
            DruidProfile p, double itemDrContribution, double itemDodgeContribution) {
        double mult = 1.0 + awakenPct / 100.0;
        AffixType best = pool.get(0);
        double bestMarginalBoost = -1e9;
        for (AffixType t : pool) {
            double uBase = evaluateAffixMetric(t, t.refRoll * itemScale, p, itemDrContribution, itemDodgeContribution).utilityScore;
            double uBoost = evaluateAffixMetric(t, t.refRoll * itemScale * mult, p, itemDrContribution, itemDodgeContribution).utilityScore;
            // Максимизируем именно ПРИРОСТ от множителя Пробуждения (чтобы не кидать +20% в стат, который и без бонуса упирается в кап 95%!)
            double marginalFromAwaken = (uBoost - uBase) + 0.01 * uBoost;
            if (marginalFromAwaken > bestMarginalBoost) {
                bestMarginalBoost = marginalFromAwaken;
                best = t;
            }
        }
        return best;
    }

    // =========================================================================
    // 3. КАЛИБРОВКА МАСШТАБА ПРЕДМЕТА И ЧИТАЕМОЕ ОБЪЯСНЕНИЕ РАЗНИЦЫ
    // =========================================================================

    private static double estimateItemScale(List<ParsedAffixRow> rows) {
        List<Double> ratios = new ArrayList<>();
        for (ParsedAffixRow r : rows) {
            if (r.type != AffixType.EMPTY_POSITION && r.type.refRoll > 0 && r.value > 0) {
                double baseUnboosted = r.value / (1.0 + r.awakenBonusPct / 100.0);
                double ratio = baseUnboosted / r.type.refRoll;
                if (ratio >= 0.02 && ratio <= 12.0) {
                    ratios.add(ratio);
                }
            }
        }
        if (ratios.isEmpty()) return 1.0;
        java.util.Collections.sort(ratios);
        int mid = ratios.size() / 2;
        return (ratios.size() % 2 == 1)
                ? ratios.get(mid)
                : 0.5 * (ratios.get(mid - 1) + ratios.get(mid));
    }

    private static String buildWhyExplanation(
            ParsedAffixRow curRow, EvalMetric curM,
            AffixType target, EvalMetric targetM, DruidProfile p) {
        StringBuilder sb = new StringBuilder();
        sb.append("сейчас «").append(curRow.type.ruName).append("» — ").append(curM.reasonNote);
        sb.append("; а «").append(target.ruName).append("» — ").append(targetM.reasonNote);
        if (curRow.awakenBonusPct > 0) {
            sb.append(String.format(Locale.US,
                    ", плюс именно на месте #%d действует усиление Пробуждения +%d%%!",
                    curRow.positionIndex, curRow.awakenBonusPct));
        } else {
            sb.append(".");
        }
        return sb.toString();
    }

    private static String formatCurrentAffix(ParsedAffixRow row) {
        if (row.type == AffixType.EMPTY_POSITION) return "Пустая позиция (не заполнена)";
        return row.type.ruName + " (+" + formatRollValue(row.type, row.value) + ")";
    }

    private static String formatRollValue(AffixType t, double v) {
        if (t.isPercent) {
            return String.format(Locale.US, "%.1f%%", v);
        }
        return String.format(Locale.US, "%.0f", Math.max(1.0, v));
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
        String norm = s.replace("\u00A0", "").replace(" ", "");
        // Для корректного отделения пробелов между словами ищем в исходной строке с склейкой тысячных разрядов
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

    private static double pickPrimaryStatValue(AffixType type, String raw, List<Double> nums) {
        if (nums.isEmpty()) return 0.0;
        // Если строка начинается с римской цифры, которая случайно распозналась как число — берём число у знака '+'/'%'
        return nums.get(0);
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
