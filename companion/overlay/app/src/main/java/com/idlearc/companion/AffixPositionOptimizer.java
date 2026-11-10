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
        PET_DAMAGE("Урон питомца %", 120.0, true),
        DOUBLE_HIT("Шанс двойного удара %", 75.0, true),
        DOUBLE_DAMAGE("Шанс двойного урона %", 95.0, true),
        CRIT_DAMAGE("Крит. урон %", 150.0, true),
        CRIT_CHANCE("Шанс крита %", 65.0, true),
        INTELLIGENCE("Интеллект", 145.0, false),
        DEXTERITY("Ловкость", 145.0, false),
        STRENGTH("Сила", 145.0, false),
        DAMAGE_REDUCTION("Снижение урона %", 9.0, true),
        MAX_HEALTH("Макс. здоровье %", 22.0, true),
        DEFENSE_PERCENT("Защита %", 25.0, true),
        FLAT_DEFENSE("Защита (плоская)", 3500.0, false),
        DODGE_CHANCE("Шанс уклонения %", 16.0, true),
        LIFE_ON_HIT("Здоровье за удар", 120.0, false),
        LIFE_ON_KILL("Здоровье за убийство", 350.0, false),
        LIFE_RECOVERY("Восстановление здоровья", 150.0, false),
        GOLD_GAIN("Бонус золота %", 110.0, true),
        EXP_GAIN("Бонус опыта %", 110.0, true),
        BLOODTHIRST("Жажда крови %", 18.0, true),
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

    private static final Pattern NUMBER_PAT = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)");
    private static final Pattern ROMAN_EDGE_PAT = Pattern.compile(
            "(?:^|\\s|\\[|\\()(VIII|VII|VI|IV|III|II|V|I|lll|ll|lV)(?:\\)|\\]|\\s|$)");
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
                        AffixType.CRIT_DAMAGE, AffixType.CRIT_CHANCE, AffixType.INTELLIGENCE,
                        AffixType.DEXTERITY, AffixType.STRENGTH, AffixType.ADDED_DAMAGE,
                        AffixType.LIFE_ON_HIT, AffixType.ELEMENTAL_CONVERT);
            case TORCH:
                return Arrays.asList(
                        AffixType.PET_DAMAGE, AffixType.DOUBLE_HIT, AffixType.DOUBLE_DAMAGE,
                        AffixType.INTELLIGENCE, AffixType.CRIT_CHANCE, AffixType.CRIT_DAMAGE,
                        AffixType.BLOODTHIRST, AffixType.GOLD_GAIN, AffixType.EXP_GAIN);
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

        List<String> plainLines = new ArrayList<>(ocrLines.size());
        for (IdleArcOcrEngine.OcrLine l : ocrLines) plainLines.add(l.text);

        SlotAffixAdvisor.GearSlot detectedSlot = explicitSlot != null
                ? explicitSlot
                : SlotAffixAdvisor.detectSlotFromLines(plainLines);

        // На талисманах и питомцах своя механика (надписи +9 / гарант петов), здесь — только предметы с позициями аффиксов
        if (detectedSlot == SlotAffixAdvisor.GearSlot.TALISMAN || detectedSlot == SlotAffixAdvisor.GearSlot.PET) {
            return null;
        }

        List<ParsedAffixRow> rows = extractSecondaryAffixRows(ocrLines);
        if (rows.isEmpty()) {
            return null;
        }

        boolean slotInferred = false;
        if (detectedSlot == null) {
            detectedSlot = inferSlotFromObservedAffixes(rows);
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

        // 2. Формируем по-строчный вердикт ТОЛЬКО по текущим аффиксам #1..#N и их реальной разнице
        StringBuilder out = new StringBuilder();
        String slotHeader = detectedSlot.shortChip
                + (slotInferred ? " (пул определён по аффиксам)" : " (" + detectedSlot.slotTitle + ")");

        List<String> awakenedSummary = new ArrayList<>();
        for (ParsedAffixRow r : rows) {
            if (r.awakenBonusPct > 0) {
                awakenedSummary.add("#" + r.positionIndex + " (+" + r.awakenBonusPct + "%"
                        + (r.awakenStacks > 0 ? " [" + toRoman(r.awakenStacks) + "]" : "") + ")");
            }
        }

        out.append("Предмет: ").append(slotHeader).append(" · Аффиксов: ").append(n).append("\n");
        if (!awakenedSummary.isEmpty()) {
            out.append("💎 Бонус Пробуждения на: ").append(join(awakenedSummary, ", ")).append("\n");
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
            shortBadge = "✅ " + detectedSlot.shortChip + ": все " + n + "/" + n + " на месте!";
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
    // 1. ТОЧНОЕ ИЗВЛЕЧЕНИЕ ТОЛЬКО ВТОРИЧНЫХ АФФИКСОВ ПРЕДМЕТА (#1..#N)
    // =========================================================================

    static List<ParsedAffixRow> extractSecondaryAffixRows(List<IdleArcOcrEngine.OcrLine> ocrLines) {
        int startIdx = 0;
        int endIdx = ocrLines.size();
        boolean sawExplicitAffixHeader = false;

        for (int i = 0; i < ocrLines.size(); i++) {
            String low = DruidKnowledgeBase.normalizeForMatch(ocrLines.get(i).text);
            if (!sawExplicitAffixHeader && (low.equals("аффиксы") || low.startsWith("аффиксы:")
                    || low.contains("позиции аффиксов") || low.contains("позиций аффиксов"))) {
                sawExplicitAffixHeader = true;
                startIdx = i + 1;
                continue;
            }
            if (i >= startIdx && isSectionAfterAffixes(low)) {
                endIdx = i;
                break;
            }
        }

        List<ParsedAffixRow> parsed = new ArrayList<>();
        boolean passedItemTitle = sawExplicitAffixHeader;

        for (int i = startIdx; i < endIdx; i++) {
            IdleArcOcrEngine.OcrLine ol = ocrLines.get(i);
            String raw = ol.text.trim();
            String low = DruidKnowledgeBase.normalizeForMatch(raw);

            if (raw.length() < 3) continue;
            if (isNonAffixMetadataLine(low)) {
                passedItemTitle = true;
                continue;
            }

            // Если заголовка «Аффиксы» не было на экране, пропускаем первую строку базового имплисита
            // (например «Урон атаки: 48500» или «Защита предмета: 12000» или «Все классовые навыки +6» на факеле)
            if (!sawExplicitAffixHeader && parsed.isEmpty() && isLikelyImplicitHeaderLine(low, raw)) {
                passedItemTitle = true;
                continue;
            }

            if (low.contains("заполните позицию") || low.contains("пустая позиция") || low.contains("выберите аффикс")) {
                parsed.add(new ParsedAffixRow(parsed.size() + 1, AffixType.EMPTY_POSITION, raw, 0.0, 0, 0));
                if (parsed.size() >= 6) break;
                continue;
            }

            AffixType type = classifyAffixLine(low, raw);
            if (type == null) continue;

            // Отсекаем строки без чисел (например случайный кусок описания без ролла)
            List<Double> nums = extractAllNumbers(raw);
            if (nums.isEmpty()) continue;

            double statVal = pickPrimaryStatValue(type, raw, nums);
            int[] awaken = detectAwakeningOnRow(ol, raw, nums, statVal);

            parsed.add(new ParsedAffixRow(
                    parsed.size() + 1, type, raw, statVal, awaken[0], awaken[1]));
            if (parsed.size() >= 6) break;
        }
        return parsed;
    }

    private static boolean isSectionAfterAffixes(String low) {
        return low.startsWith("бонус добычи")
                || low.startsWith("гнезд") || low.startsWith("гнёзд") || low.contains("самоцвет")
                || low.equals("нефрит") || low.equals("янтарь") || low.equals("лазурит") || low.equals("гранат")
                || low.startsWith("спутника") || low.startsWith("свирепый") || low.startsWith("отражающий")
                || low.startsWith("истребителя") || low.startsWith("сокрушительный") || low.startsWith("неумолимый")
                || low.startsWith("удачливый") || low.startsWith("процветающий") || low.startsWith("собирателя")
                || low.equals("надеть") || low.equals("разобрать") || low.equals("продать")
                || low.contains("надеть предмет") || low.contains("перераспределить пробуждение")
                || low.contains("материалы пробуждения");
    }

    private static boolean isNonAffixMetadataLine(String low) {
        return low.contains("очки снаряжения") || low.startsWith("уровень") || low.startsWith("требуется")
                || low.contains("инфернальн") || low.contains("мифическ") || low.contains("легендарн")
                || low.contains("эпическ") || low.contains("редкий") || low.contains("необычн")
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
        if (low.contains("двойного удара") || low.contains("двойной удар")) {
            return AffixType.DOUBLE_HIT;
        }
        if (low.contains("двойного урона") || low.contains("двойной урон")) {
            return AffixType.DOUBLE_DAMAGE;
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
        if (low.contains("здоровье за удар") || low.contains("здоровье при ударе")) {
            return AffixType.LIFE_ON_HIT;
        }
        if (low.contains("здоровье за убийство")) {
            return AffixType.LIFE_ON_KILL;
        }
        if (low.contains("восстановление здоров") || low.contains("регенерац")) {
            return AffixType.LIFE_RECOVERY;
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
        if ((low.contains("сила") || low.contains("силы")) && !low.contains("талисман") && !low.contains("слова")) {
            return AffixType.STRENGTH;
        }
        if (low.contains("преобразуется в") || low.contains("преобразование в")) {
            return AffixType.ELEMENTAL_CONVERT;
        }
        if (low.contains("фикс. урон атаки") || low.contains("дополнительный урон") || low.contains("урон атаки")) {
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
     *  - по тексту значка римской цифры I, II, III, IV, V, VI...;
     *  - по явному тегу (+10%), (+20%), (+30%) или ★1..★5;
     *  - по голубому/фиолетовому цвету строки + числу штрихов в плашке [I..V].
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

        // 2. Римская цифра I / II / III / IV / V / VI на краю строки аффикса
        if (pct == 0) {
            Matcher rm = ROMAN_EDGE_PAT.matcher(raw);
            if (rm.find()) {
                int rVal = parseRoman(rm.group(1));
                // Для обычной строки без голубого цвета одиночную 'I' принимаем, если она стоит отдельным префиксом перед '+'
                if (rVal >= 2 || ol.hasAwakenHighlight() || raw.trim().matches("^(?:I|\\[I\\])\\s+\\+.*")) {
                    stacks = rVal;
                    pct = stacks * 10;
                }
            }
        }

        // 3. Если на одной строке два числа и второе кратно 10% (напр. «+840 Интеллект +20%»)
        if (pct == 0 && nums.size() >= 2 && raw.contains("%")) {
            double last = nums.get(nums.size() - 1);
            if (Math.abs(last - statVal) > 0.01 && last >= 10 && last <= 100 && ((int) last) % 10 == 0) {
                pct = (int) Math.round(last);
                stacks = pct / 10;
            }
        }

        // 4. Пиксельная подсветка голубым (cyan) / фиолетовым цветом усиленного аффикса в IdleArc
        if (pct == 0 && ol.hasAwakenHighlight()) {
            stacks = ol.badgeStrokes >= 1 ? ol.badgeStrokes : 1;
            pct = stacks * 10;
        }

        return new int[]{stacks, pct};
    }

    private static int parseRoman(String s) {
        if (s == null) return 0;
        String u = s.trim().replace('l', 'I');
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

    private static SlotAffixAdvisor.GearSlot inferSlotFromObservedAffixes(List<ParsedAffixRow> rows) {
        for (ParsedAffixRow r : rows) {
            if (r.type == AffixType.BLOODTHIRST) return SlotAffixAdvisor.GearSlot.TORCH;
            if (r.type == AffixType.ADDED_DAMAGE || r.type == AffixType.ELEMENTAL_CONVERT) {
                return SlotAffixAdvisor.GearSlot.WEAPON;
            }
        }
        for (ParsedAffixRow r : rows) {
            if (r.type == AffixType.PET_DAMAGE || r.type == AffixType.DOUBLE_HIT
                    || r.type == AffixType.DOUBLE_DAMAGE || r.type == AffixType.CRIT_CHANCE
                    || r.type == AffixType.CRIT_DAMAGE) {
                return SlotAffixAdvisor.GearSlot.RING; // атакующий ювелирный/оружейный пул
            }
        }
        return SlotAffixAdvisor.GearSlot.CHEST; // защитно-статовый пул брони/пояса/сапог/шлема
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
                // Прямой профильный множитель урона питомца (основной пул пета ~950..1050% на эндгейм-шмоте)
                double dpsPct = (val / 1070.0) * 100.0;
                return new EvalMetric(dpsPct,
                        String.format(Locale.US, "+%.1f%% итогового DPS пета", dpsPct),
                        "прямой профильный множитель урона питомца");
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
            case LIFE_RECOVERY:
            case LIFE_ON_HIT: {
                return new EvalMetric(0.25,
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
                // Небольшой бонус стабильности (+0.08), чтобы при равном множителе пробуждения НЕ двигать уже выбитый хороший аффикс
                if (r.type == cand) {
                    u += 0.08;
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
