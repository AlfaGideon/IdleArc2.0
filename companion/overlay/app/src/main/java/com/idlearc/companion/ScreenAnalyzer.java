package com.idlearc.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Визуальный анализатор экрана IdleArc 1.3.15 (0% захардкоженных статов или предзаданных ответов).
 *
 * Распознаёт открытое окно игры (Характеристики персонажа, Карточка предмета/Кузницы,
 * Сравнение двух предметов, Выбор Автонадписи из 3, Навык Друида, Дерево навыков)
 * и передаёт считанные с экрана числа в математический симулятор LiveBuildSimulator.
 */
public class ScreenAnalyzer {

    public enum VerdictColor {
        GOOD,
        WARN,
        BAD
    }

    public static class ScanResult {
        public String screenType = "Экран";
        public String shortBadge = "🌿 Скан готов";
        public String headline = "";
        public String details = "";
        public VerdictColor color = VerdictColor.GOOD;
        public boolean profileUpdated = false;
        public boolean isAffixItemScan = false;
        public SlotAffixAdvisor.GearSlot detectedSlot = null;
        public List<String> rawLines = new ArrayList<>();
        public List<IdleArcOcrEngine.OcrLine> ocrLines = new ArrayList<>();
    }

    private static final Pattern PAREN_PCT = Pattern.compile(
            "([0-9]+(?:[.,][0-9]+)?)%\\s*\\(\\s*([0-9]+(?:[.,][0-9]+)?)");
    private static final Pattern CHAR_LEVEL_PAT = Pattern.compile(
            "(?:уровень|ур\\.?)\\s*:?\\s*([0-9]{1,4})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static ScanResult analyzeFrame(IdleArcOcrEngine ocr, int[] argb, int w, int h, DruidProfile profile) {
        ScanResult res = new ScanResult();
        if (profile == null) profile = new DruidProfile();
        if (ocr == null || argb == null || w < 100 || h < 100) {
            res.headline = "Нет кадра для анализа";
            res.color = VerdictColor.WARN;
            return res;
        }

        int rx0 = (int) (w * 0.05);
        int rx1 = (int) (w * 0.95);
        int ry0 = (int) (h * 0.08);
        int ry1 = (int) (h * 0.90);

        List<IdleArcOcrEngine.OcrLine> rawMetaLines = ocr.recognizeLinesWithMeta(argb, w, h, rx0, ry0, rx1, ry1);
        List<IdleArcOcrEngine.OcrLine> metaLines = IdleArcOcrEngine.isolateActiveModalLines(rawMetaLines);
        List<String> lines = new ArrayList<>(metaLines.size());
        for (IdleArcOcrEngine.OcrLine ml : metaLines) {
            lines.add(ml.text);
        }
        res.rawLines = lines;
        res.ocrLines = metaLines;

        StringBuilder joinedSb = new StringBuilder();
        for (String l : lines) joinedSb.append(l).append('\n');
        String joined = joinedSb.toString();
        String lowAll = DruidKnowledgeBase.normalizeForMatch(joined);

        // 1. ОКНО «ХАРАКТЕРИСТИКИ ПЕРСОНАЖА»
        boolean hasItemModalMarkers = lowAll.contains("аффикс") || lowAll.contains("надеть")
                || lowAll.contains("снять") || lowAll.contains("гнезд") || lowAll.contains("гнёзд")
                || lowAll.contains("переков") || lowAll.contains("разобрать") || lowAll.contains("пробужд");
        boolean isCharStatsSheet = lowAll.contains("характеристики персонажа")
                || (!hasItemModalMarkers && lowAll.contains("шанс блока") && lowAll.contains("возмезд"))
                || (!hasItemModalMarkers && lowAll.contains("множитель добычи") && lowAll.contains("рубин"))
                || (!hasItemModalMarkers && lowAll.contains("итоговый множитель") && lowAll.contains("питомц"));
        if (isCharStatsSheet) {
            res.screenType = "Характеристики персонажа";
            int updatedCount = updateProfileFromStatsLines(lines, profile);
            res.profileUpdated = (updatedCount > 0);
            res.shortBadge = String.format(Locale.US, "📊 Скан статов (%d шт.)", updatedCount);

            if (profile.wastedDodgePct() > 1.0) {
                res.color = VerdictColor.WARN;
                res.headline = String.format(Locale.US,
                        "⚠️ Считано %d статов! Уклонение выше капа 95%% (+%.1f%% впустую)",
                        updatedCount, profile.wastedDodgePct());
            } else {
                res.color = VerdictColor.GOOD;
                res.headline = String.format(Locale.US,
                        "✅ Считано с экрана %d статов и сохранено в живой профиль!", updatedCount);
            }
            res.details = DruidKnowledgeBase.buildAuditReport(profile);
            return res;
        }

        // 2. ВЫБОР НАДПИСИ ТАЛИСМАНА (3 варианта)
        if (lowAll.contains("выбери надпись") || lowAll.contains("выбрать надпись") || lowAll.contains("предложения")) {
            return analyzeInscriptionChoices(ocr, argb, w, h, lines, profile);
        }

        // 3. ВКЛАДКА ДЕРЕВА КЛАССОВЫХ НАВЫКОВ (Хатка / Туннели / Симбиоз)
        if ((lowAll.contains("хатка") || lowAll.contains("туннели") || lowAll.contains("симбиоз") || lowAll.contains("пассивное дерево"))
                && !lowAll.contains("закрыть") && (lowAll.contains("макс") || lowAll.contains("вложить"))) {
            res.screenType = "Дерево классовых навыков";
            int maxCount = countOccurrences(lowAll, "макс");
            res.shortBadge = "🌳 Навыки Друида";
            res.color = VerdictColor.GOOD;
            if (maxCount >= 2) {
                res.headline = "✅ Открытая ветка навыков замаксена";
                res.details = "• Все видимые узлы ветки прокачаны до максимума базовых очков.\n"
                        + "• Дальнейший рост навыков без потолка (Погрыз +8%/ур, Одна душа +12%/ур, Хранитель хатки, Землекоп, Общий инстинкт) идёт через бонус «+Все классовые навыки» на факеле и питомцах.\n"
                        + "• Нажми на конкретный узел навыка и нажми 🔍 СКАН, чтобы проверить его точный кап «ЛИМИТ» с учётом твоего текущего бонуса ко всем навыкам.";
            } else {
                res.headline = "🌱 Прокачка классовых навыков Друида";
                res.details = "Базовый порядок раскрытия синергий:\n"
                        + "1) Хатка: Погрыз → Строитель плотин → Удар хвостом (лимит 20 эфф. ур.) + Хранитель хатки\n"
                        + "2) Туннели: Землекоп + Чуткий нюх → Подкоп (казнь при HP <= 35%, лимит 18 эфф. ур.)\n"
                        + "3) Симбиоз: Родство + Общий инстинкт → Одна душа (+12%/ур пета)\n"
                        + "💡 Пока очков навыков не хватает на все 18 узлов, проверяй плашку ЛИМИТ: с высоким бонусом «+Все классовые навыки» лимитные навыки достигают капа уже с 1 вложенного очка!";
            }
            return res;
        }

        // 4. КАРТОЧКА КОНКРЕТНОГО НАВЫКА ДРУИДА
        DruidKnowledgeBase.SkillInfo skill = DruidKnowledgeBase.findSkillByText(joined);
        if (skill != null && (lowAll.contains("ранг") || lowAll.contains("за уровень") || lowAll.contains("закр"))) {
            res.screenType = "Навык Друида";
            boolean overLimit = lowAll.contains("лимит") || lowAll.contains("макс.");
            int bonusAll = detectBonusAllSkills(lines, profile.bonusAllClassSkills);
            if (bonusAll > 0 && bonusAll != profile.bonusAllClassSkills) {
                profile.bonusAllClassSkills = bonusAll;
                res.profileUpdated = true;
            }

            res.shortBadge = "🌿 " + skill.ruName + (skill.hasHardCap ? " [ЛИМИТ]" : " [БЕЗ КАПА]");
            res.color = skill.hasHardCap ? VerdictColor.WARN : VerdictColor.GOOD;
            res.headline = "Навык «" + skill.ruName + "» (" + skill.branch + " · Ранг " + skill.tierRank + ")";
            StringBuilder d = new StringBuilder();
            d.append("• Формула игры: ").append(skill.formulaRu).append("\n");
            d.append("• Механика лимита: ").append(skill.capNoteRu).append("\n");
            if (skill.hasHardCap && profile.bonusAllClassSkills > 0) {
                int effWith1Pt = 1 + profile.bonusAllClassSkills;
                if (effWith1Pt >= skill.effectiveCapLevels) {
                    d.append("💡 Расчёт по твоему бонусу +").append(profile.bonusAllClassSkills)
                     .append(" ко всем навыкам: уже с 1 вложенным очком эффективный уровень равен ")
                     .append(effWith1Pt).append(" (потолок навыка: ").append(skill.effectiveCapLevels)
                     .append("). Дополнительные базовые очки сверх 1 не увеличат эффект, если очков в дереве не хватает!");
                } else {
                    int neededPts = Math.max(1, skill.effectiveCapLevels - profile.bonusAllClassSkills);
                    d.append("💡 Расчёт по твоему бонусу +").append(profile.bonusAllClassSkills)
                     .append(" ко всем навыкам: для закрытия потолка (").append(skill.effectiveCapLevels)
                     .append(" эфф. ур.) достаточно вложить ").append(neededPts).append(" очк.");
                }
            } else if (overLimit) {
                d.append("ℹ️ На карточке зафиксировано достижение потолка (ЛИМИТ).");
            } else if (!skill.hasHardCap) {
                d.append("🔥 Навык не ограничен потолком: каждый +1 к «Всем классовым навыкам» продолжает линейно увеличивать его бонус.");
            }
            res.details = d.toString();
            return res;
        }

        // 5. СРАВНЕНИЕ ДВУХ ПРЕДМЕТОВ
        if (isItemComparisonScreen(lowAll)) {
            return analyzeItemComparison(ocr, argb, w, h, profile);
        }

        // 6. ОДИНОЧНАЯ КАРТОЧКА ПРЕДМЕТА / КУЗНИЦЫ (ЖИВОЙ МАТЕМАТИЧЕСКИЙ РАСЧЁТ ПОЗИЦИЙ #1..#5)
        boolean hasOpenItemCardMarkers = AffixPositionOptimizer.findAffixHeaderIndexFromStrings(lines) >= 0
                || lowAll.contains("надеть") || lowAll.contains("снять") || lowAll.contains("разобрать")
                || lowAll.contains("бонус добычи") || lowAll.contains("перераспредел")
                || lowAll.contains("гнезд") || lowAll.contains("гнёзд");
        boolean equipGridNoModal = (lowAll.contains("кодекс") && lowAll.contains("автонадписи"))
                && !hasOpenItemCardMarkers;
        SlotAffixAdvisor.GearSlot detectedSlot = equipGridNoModal
                ? null
                : SlotAffixAdvisor.detectSlotFromModalLines(metaLines);

        if (detectedSlot == null && !equipGridNoModal && (hasOpenItemCardMarkers || isSingleItemPopup(lowAll))) {
            int hdrBottomY = (int) (h * 0.55);
            int affIdx = AffixPositionOptimizer.findAffixHeaderIndexFromStrings(lines);
            if (affIdx >= 0 && affIdx < metaLines.size()) {
                hdrBottomY = Math.max((int) (h * 0.22), metaLines.get(affIdx).y0);
            }
            float[] leftCrops = {0.22f, 0.34f};
            for (float lx : leftCrops) {
                List<IdleArcOcrEngine.OcrLine> hdrCol = ocr.recognizeLinesWithMeta(
                        argb, w, h, (int) (w * lx), (int) (h * 0.08), (int) (w * 0.84), hdrBottomY);
                detectedSlot = SlotAffixAdvisor.detectSlotFromModalLines(hdrCol);
                if (detectedSlot != null) break;
            }
        }

        if (!equipGridNoModal) {
            AffixPositionOptimizer.OptimizationReport opt =
                    AffixPositionOptimizer.analyzeItemAffixes(metaLines, detectedSlot, profile);
            if (opt != null && (isSingleItemPopup(lowAll) || detectedSlot != null || opt.currentRows.size() >= 2)) {
                res.isAffixItemScan = true;
                res.detectedSlot = opt.slotInferredFromAffixes ? null : opt.slot;
                String titleChip = opt.slotInferredFromAffixes ? "📦 Предмет (выбери слот выше)" : opt.slot.shortChip;
                res.screenType = titleChip + " · Математический разбор (" + profile.mode.title + ")";
                res.shortBadge = opt.shortBadge;
                res.headline = opt.headline;
                res.color = opt.verdictColor;
                res.details = opt.details;
                return res;
            }
        }

        if (!equipGridNoModal && (isSingleItemPopup(lowAll) || detectedSlot != null)) {
            res.detectedSlot = detectedSlot;
            DruidKnowledgeBase.ItemEvaluation ev = DruidKnowledgeBase.evaluateItemLines(lines, profile);
            String slotLabel = detectedSlot != null ? detectedSlot.shortChip : "Предмет";
            res.screenType = slotLabel + " (" + profile.mode.title + ")";
            res.shortBadge = String.format(Locale.US, "%s: +%.2f%%", slotLabel, ev.totalScore);
            res.color = ev.totalScore >= 6.0 ? VerdictColor.GOOD
                      : (ev.totalScore >= 2.0 ? VerdictColor.WARN : VerdictColor.BAD);
            res.headline = String.format(Locale.US, "%s — расчётный вклад +%.2f%% [%s]",
                    slotLabel, ev.totalScore, ev.tierGrade);
            res.details = formatItemEvalDetails(ev);
            return res;
        }

        // 7. ЭКРАН ЭКИПИРОВКИ (общая кукла без открытой карточки предмета)
        if (lowAll.contains("кодекс") || lowAll.contains("автонадписи")
                || (lowAll.contains("снаряжение") && lowAll.contains("кузница"))) {
            int greenArrows = countGreenUpgradeBadges(argb, w, h);
            res.screenType = "Окно снаряжения";
            res.shortBadge = "⚔️ Шмот · ~" + greenArrows + " апгрейдов";
            res.color = VerdictColor.GOOD;
            res.headline = "⚔️ Окно снаряжения (зелёных индикаторов улучшения ячеек: ~" + greenArrows + ")";
            res.details = "📷 Как запустить точный расчёт любого предмета:\n"
                    + "1. Сперва открой «Характеристики персонажа» и нажми 🔍 СКАН — компаньон считает твои текущие криты, проки, уклонение и снижение урона.\n"
                    + "2. Нажми на любой надетый или выпавший предмет, чтобы открылись его строки аффиксов, и снова нажми 🔍 СКАН.\n"
                    + "3. Симулятор вычтет текущий стат строки из твоего профиля и математически сравнит все доступные аффиксы этого слота!\n\n"
                    + "⚙️ Общее правило механики Друида: прокачка ячейки оружия увеличивает плоскую базу атаки, до 75% которой передаётся в базу питомцев через «Родство» и «Дикую связь».";
            return res;
        }

        // 8. Боевой экран или прочее окно
        res.screenType = "Обзор / Бой";
        res.color = VerdictColor.GOOD;
        res.shortBadge = "🌿 Готов (" + profile.mode.title + ")";
        res.headline = "🌿 Открой «Характеристики персонажа», предмет, сравнение или навык";
        StringBuilder preview = new StringBuilder();
        preview.append("Режим целевой функции: ").append(profile.mode.title).append("\n")
               .append("• Источник статов героя: ")
               .append(profile.hasScannedStats
                       ? ("живой скан (" + profile.scannedStatsCount + " параметров)")
                       : "ещё не отсканирован (открой «Характеристики персонажа» и нажми СКАН)")
               .append("\n")
               .append("• Открой любую карточку предмета / сравнения / Автонадписи и нажми 🔍 СКАН для расчёта.\n");
        if (!lines.isEmpty()) {
            preview.append("\nВизуально считано с экрана (").append(lines.size()).append(" стр.):\n");
            for (int i = 0; i < Math.min(5, lines.size()); i++) {
                preview.append("  · ").append(lines.get(i)).append("\n");
            }
        }
        res.details = preview.toString();
        return res;
    }

    public static void recomputeAffixScan(ScanResult res, SlotAffixAdvisor.GearSlot slotOverride, DruidProfile profile) {
        if (res == null || !res.isAffixItemScan || res.ocrLines == null || res.ocrLines.isEmpty()) return;
        AffixPositionOptimizer.OptimizationReport opt =
                AffixPositionOptimizer.analyzeItemAffixes(res.ocrLines, slotOverride, profile);
        if (opt != null) {
            res.detectedSlot = opt.slot;
            res.screenType = opt.slot.shortChip + " · Математический разбор (" + profile.mode.title + ")";
            res.shortBadge = opt.shortBadge;
            res.headline = opt.headline;
            res.color = opt.verdictColor;
            res.details = opt.details;
        }
    }

    private static ScanResult analyzeItemComparison(IdleArcOcrEngine ocr, int[] argb, int w, int h, DruidProfile profile) {
        ScanResult res = new ScanResult();
        res.screenType = "Сравнение двух предметов";

        List<IdleArcOcrEngine.OcrLine> topMeta = ocr.recognizeLinesWithMeta(argb, w, h, (int) (w * 0.05), (int) (h * 0.10), (int) (w * 0.95), (int) (h * 0.49));
        List<IdleArcOcrEngine.OcrLine> botMeta = ocr.recognizeLinesWithMeta(argb, w, h, (int) (w * 0.05), (int) (h * 0.49), (int) (w * 0.95), (int) (h * 0.88));
        List<IdleArcOcrEngine.OcrLine> leftMeta = ocr.recognizeLinesWithMeta(argb, w, h, (int) (w * 0.03), (int) (h * 0.14), (int) (w * 0.50), (int) (h * 0.86));
        List<IdleArcOcrEngine.OcrLine> rightMeta = ocr.recognizeLinesWithMeta(argb, w, h, (int) (w * 0.50), (int) (h * 0.14), (int) (w * 0.97), (int) (h * 0.86));

        List<String> topLines = toPlain(topMeta);
        List<String> botLines = toPlain(botMeta);
        List<String> leftLines = toPlain(leftMeta);
        List<String> rightLines = toPlain(rightMeta);

        DruidKnowledgeBase.ItemEvaluation evTop = DruidKnowledgeBase.evaluateItemLines(topLines, profile);
        DruidKnowledgeBase.ItemEvaluation evBot = DruidKnowledgeBase.evaluateItemLines(botLines, profile);
        DruidKnowledgeBase.ItemEvaluation evLeft = DruidKnowledgeBase.evaluateItemLines(leftLines, profile);
        DruidKnowledgeBase.ItemEvaluation evRight = DruidKnowledgeBase.evaluateItemLines(rightLines, profile);

        boolean useHorizontal = (evLeft.pros.size() + evLeft.traps.size() >= 1)
                && (evRight.pros.size() + evRight.traps.size() >= 1)
                && ((evLeft.pros.size() + evRight.pros.size()) > (evTop.pros.size() + evBot.pros.size()));

        DruidKnowledgeBase.ItemEvaluation equipped = useHorizontal ? evLeft : evTop;
        DruidKnowledgeBase.ItemEvaluation candidate = useHorizontal ? evRight : evBot;
        List<IdleArcOcrEngine.OcrLine> candMeta = useHorizontal ? rightMeta : botMeta;
        List<IdleArcOcrEngine.OcrLine> eqMeta = useHorizontal ? leftMeta : topMeta;
        SlotAffixAdvisor.GearSlot candSlot = SlotAffixAdvisor.detectSlotFromModalLines(candMeta);
        if (candSlot == null) candSlot = SlotAffixAdvisor.detectSlotFromModalLines(eqMeta);
        res.detectedSlot = candSlot;

        String eqLabel = useHorizontal ? "Левый (надетый)" : "Верхний (надетый)";
        String candLabel = useHorizontal ? "Правый (новый)" : "Нижний (новый)";

        double netDeltaPct = candidate.totalScore - equipped.totalScore;
        if (netDeltaPct >= 0.8) {
            res.color = VerdictColor.GOOD;
            res.shortBadge = String.format(Locale.US, "✅ НАДЕВАЙ (+%.2f%%)", netDeltaPct);
            res.headline = String.format(Locale.US, "✅ %s сильнее на +%.2f%% (%s)",
                    candLabel, netDeltaPct, profile.mode.title);
        } else if (netDeltaPct <= -0.8) {
            res.color = VerdictColor.BAD;
            res.shortBadge = String.format(Locale.US, "❌ ОСТАВЬ ТЕКУЩИЙ (%.2f%%)", netDeltaPct);
            res.headline = String.format(Locale.US, "❌ %s уступает %.2f%% текущему предмету",
                    candLabel, Math.abs(netDeltaPct));
        } else {
            res.color = VerdictColor.WARN;
            res.shortBadge = String.format(Locale.US, "⚖️ РАВНЫ (%+.2f%%)", netDeltaPct);
            res.headline = String.format(Locale.US, "⚖️ Разница в пределах %.2f%% — смотри разбор аффиксов ниже",
                    Math.abs(netDeltaPct));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "• %s: вклад +%.2f%% [%s]\n", eqLabel, equipped.totalScore, equipped.tierGrade));
        sb.append(String.format(Locale.US, "• %s: вклад +%.2f%% [%s]\n", candLabel, candidate.totalScore, candidate.tierGrade));
        sb.append(String.format(Locale.US, "• Чистая относительная разница: %+.2f%%\n\n", netDeltaPct));

        AffixPositionOptimizer.OptimizationReport candOpt =
                AffixPositionOptimizer.analyzeItemAffixes(candMeta, candSlot, profile);
        if (candOpt != null) {
            sb.append(candOpt.details);
        } else {
            sb.append(formatItemEvalDetails(candidate));
        }
        res.details = sb.toString();
        return res;
    }

    private static List<String> toPlain(List<IdleArcOcrEngine.OcrLine> meta) {
        List<String> out = new ArrayList<>(meta.size());
        for (IdleArcOcrEngine.OcrLine l : meta) out.add(l.text);
        return out;
    }

    private static ScanResult analyzeInscriptionChoices(IdleArcOcrEngine ocr, int[] argb, int w, int h,
                                                        List<String> lines, DruidProfile profile) {
        ScanResult res = new ScanResult();
        res.screenType = "Выбор надписи талисмана";

        List<String> opt1 = ocr.recognizeRegion(argb, w, h, (int) (w * 0.08), (int) (h * 0.28), (int) (w * 0.92), (int) (h * 0.44));
        List<String> opt2 = ocr.recognizeRegion(argb, w, h, (int) (w * 0.08), (int) (h * 0.44), (int) (w * 0.92), (int) (h * 0.60));
        List<String> opt3 = ocr.recognizeRegion(argb, w, h, (int) (w * 0.08), (int) (h * 0.60), (int) (w * 0.92), (int) (h * 0.76));

        DruidKnowledgeBase.ItemEvaluation e1 = DruidKnowledgeBase.evaluateItemLines(opt1, profile);
        DruidKnowledgeBase.ItemEvaluation e2 = DruidKnowledgeBase.evaluateItemLines(opt2, profile);
        DruidKnowledgeBase.ItemEvaluation e3 = DruidKnowledgeBase.evaluateItemLines(opt3, profile);

        int bestIdx = 1;
        double bestScore = e1.totalScore;
        if (e2.totalScore > bestScore) { bestScore = e2.totalScore; bestIdx = 2; }
        if (e3.totalScore > bestScore) { bestScore = e3.totalScore; bestIdx = 3; }

        if (bestScore >= 1.5) {
            res.color = VerdictColor.GOOD;
            res.shortBadge = String.format(Locale.US, "✒️ ВАРИАНТ #%d (+%.2f%%)", bestIdx, bestScore);
            res.headline = String.format(Locale.US, "✒️ Лучший расчётный вариант: #%d (+%.2f%% к сборке)", bestIdx, bestScore);
        } else {
            res.color = VerdictColor.WARN;
            res.shortBadge = "🔄 ПЕРЕБРОСЬ НАДПИСЬ";
            res.headline = "🔄 Все 3 варианта дают слабый прирост относительно твоих статов — выгоднее перебросить";
        }
        res.details = String.format(Locale.US,
                "Математическое сравнение 3 вариантов («%s»):\n"
              + "• Вариант #1: +%.2f%% (%s)\n"
              + "• Вариант #2: +%.2f%% (%s)\n"
              + "• Вариант #3: +%.2f%% (%s)",
                profile.mode.title,
                e1.totalScore, e1.tierGrade,
                e2.totalScore, e2.tierGrade,
                e3.totalScore, e3.tierGrade);
        return res;
    }

    private static String formatItemEvalDetails(DruidKnowledgeBase.ItemEvaluation ev) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Расчётный суммарный вклад: +%.2f%% → %s\n", ev.totalScore, ev.tierGrade));
        if (!ev.pros.isEmpty()) {
            sb.append("\nПолезные параметры (по отношению к текущим статам):\n");
            for (String pro : ev.pros) sb.append("  ").append(pro).append("\n");
        }
        if (!ev.traps.isEmpty()) {
            sb.append("\nСлабые / неэффективные параметры:\n");
            for (String tr : ev.traps) sb.append("  ").append(tr).append("\n");
        }
        if (ev.pros.isEmpty() && ev.traps.isEmpty()) {
            sb.append("\nПодсказка: открой карточку предмета так, чтобы были видны его строки характеристик, и нажми 🔍 СКАН.");
        }
        return sb.toString().trim();
    }

    private static boolean isItemComparisonScreen(String low) {
        int equipWords = countOccurrences(low, "очки снаряжения") + countOccurrences(low, "надето") + countOccurrences(low, "сравн");
        return equipWords >= 2 || (low.contains("надеть") && low.contains("продать")) || (low.contains("надеть") && low.contains("разобрать"));
    }

    private static boolean isSingleItemPopup(String low) {
        return low.contains("надеть") || low.contains("снять") || low.contains("переков") || low.contains("трансмутац")
                || low.contains("пробужд") || low.contains("перераспредел") || low.contains("смена типа")
                || low.contains("позиции аффиксов") || low.contains("талисман") || low.contains("аффикс")
                || low.contains("очки снаряжения") || low.contains("урон питомц")
                || low.contains("двойного удара") || low.contains("все классовые навыки");
    }

    /**
     * Полный визуальный парсер окна «Характеристики персонажа» (все 26+ боевых, защитных и фарм-статов).
     */
    static int updateProfileFromStatsLines(List<String> lines, DruidProfile p) {
        int updated = 0;
        for (String raw : lines) {
            String low = DruidKnowledgeBase.normalizeForMatch(raw);

            // Уровень персонажа
            Matcher lvlM = CHAR_LEVEL_PAT.matcher(low);
            if (lvlM.find()) {
                try {
                    int lv = Integer.parseInt(lvlM.group(1));
                    if (lv >= 1 && lv <= 999) {
                        p.charLevel = lv;
                        updated++;
                    }
                } catch (Exception ignored) {
                }
            }

            double v = DruidKnowledgeBase.extractFirstNumber(raw);
            if (v <= 0 && !low.contains("шанс блока")) continue;

            if (low.contains("очки снаряжения") || low.contains("gs")) {
                if (v >= 10 && v <= 999999) { p.gearScore = (int) Math.round(v); updated++; }
            } else if (low.contains("снижение урона")) {
                parseEffAndRawPct(raw, v, true, p);
                updated++;
            } else if (low.contains("уклонен")) {
                parseEffAndRawPct(raw, v, false, p);
                updated++;
            } else if (low.startsWith("сила") && !low.contains("талисман")) {
                p.strength = v; updated++;
            } else if (low.contains("ловкост")) {
                p.agility = v; updated++;
            } else if (low.contains("интеллект")) {
                p.intelligence = v; updated++;
            } else if ((low.contains("макс. здоров") || low.contains("макс здоров") || low.startsWith("здоровье"))
                    && !low.contains("за удар") && !low.contains("за убийств") && !raw.contains("%")) {
                p.maxHp = v; updated++;
            } else if (low.contains("урон атаки") && !raw.contains("%")) {
                double avgAtk = DruidKnowledgeBase.extractRangeAverage(raw);
                if (avgAtk > 0) { p.minAtk = avgAtk; p.maxAtk = avgAtk; updated++; }
            } else if (low.startsWith("защита") && !raw.contains("%")) {
                p.defense = v; updated++;
            } else if (low.contains("шанс крит") || low.contains("крит. удара")) {
                p.critChance = v; updated++;
            } else if (low.contains("крит. урон") || low.contains("крит урон") || low.contains("критический урон")) {
                p.critDamage = v; updated++;
            } else if (low.contains("двойного удара") || low.contains("двойной удар")) {
                p.doubleHitChance = v; updated++;
            } else if (low.contains("двойного урона") || low.contains("двойной урон")) {
                p.doubleDamageChance = v; updated++;
            } else if (low.contains("урон боссам") || low.contains("по боссам")) {
                p.bossDamage = v; updated++;
            } else if (low.contains("итоговый множитель")) {
                if (low.contains("добыч") || low.contains("предмет")) { p.itemDropMult = v; updated++; }
                else if (low.contains("материал")) { p.matDropMult = v; updated++; }
                else if (low.contains("рубин")) { p.rubyDropMult = v; updated++; }
                else if (low.contains("рун")) { p.runeDropMult = v; updated++; }
            } else if (low.contains("урон питомц") || low.contains("урона питомц")) {
                if (raw.contains("%")) {
                    p.petDamageBonusPct = v;
                } else {
                    p.petDamageMultMillions = v >= 10000.0 ? (v / 1_000_000.0) : v;
                }
                updated++;
            } else if (low.contains("преобраз") || low.contains("конверс")) {
                if (v > p.elementalConvertPct) p.elementalConvertPct = v;
                updated++;
            } else if (low.contains("возмезд")) {
                p.retaliation = v; updated++;
            } else if (low.contains("за удар") && (low.contains("убийств") || raw.contains("%"))) {
                p.lifeStealBonusPct = v; updated++;
            } else if (low.contains("за удар") && !low.contains("убийств")) {
                p.lifeOnHit = v; updated++;
            } else if (low.contains("за убийств")) {
                p.lifeOnKill = v; updated++;
            } else if (low.contains("все классовые навыки") || low.contains("классовым навыкам")) {
                p.bonusAllClassSkills = (int) Math.round(v); updated++;
            } else if (low.contains("выпадения предметов") || low.contains("поиск предметов")) {
                p.itemDropPct = v; updated++;
            } else if (low.contains("выпадения материалов")) {
                p.matDropPct = v; updated++;
            } else if (low.contains("выпадения рун")) {
                p.runeDropPct = v; updated++;
            } else if (low.contains("выпадения рубинов")) {
                p.rubyDropPct = v; updated++;
            } else if (low.contains("яиц")) {
                p.eggDropPct = v; updated++;
            } else if (low.contains("доп. убийств") || low.contains("доп убийств")) {
                p.extraKillChance = v; updated++;
            } else if (low.contains("бонус опыт") || low.contains("получение опыт")) {
                p.expBonus = v; updated++;
            } else if (low.contains("бонус золот") || low.contains("получение золот")) {
                p.goldBonus = v; updated++;
            }
        }
        if (updated > 0) {
            p.hasScannedStats = true;
            p.scannedStatsCount = Math.max(p.scannedStatsCount, updated);
            p.lastUpdatedMs = System.currentTimeMillis();
        }
        return updated;
    }

    private static void parseEffAndRawPct(String raw, double fallbackVal, boolean isDr, DruidProfile p) {
        String cleaned = raw
                .replaceAll("(?<=\\d)[\\s\\u00A0]+(?=[0-9.,])", "")
                .replaceAll("(?<=[.,])[\\s\\u00A0]+(?=\\d)", "");
        Matcher m = PAREN_PCT.matcher(cleaned);
        if (m.find()) {
            try {
                double eff = Double.parseDouble(m.group(1).replace(',', '.'));
                double rawPct = Double.parseDouble(m.group(2).replace(',', '.'));
                if (isDr) {
                    p.damageReductionEff = eff;
                    p.damageReductionRaw = rawPct;
                } else {
                    p.dodgeEff = eff;
                    p.dodgeRaw = rawPct;
                }
                return;
            } catch (Exception ignored) {
            }
        }
        if (isDr) {
            p.damageReductionEff = Math.min(95.0, fallbackVal);
            p.damageReductionRaw = fallbackVal;
        } else {
            p.dodgeEff = Math.min(95.0, fallbackVal);
            p.dodgeRaw = fallbackVal;
        }
    }

    private static int detectBonusAllSkills(List<String> lines, int fallback) {
        Pattern p = Pattern.compile("\\+\\s*([0-9]{1,3})\\s*от");
        for (String l : lines) {
            Matcher m = p.matcher(l);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (Exception ignored) {
                }
            }
        }
        return fallback;
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static int countGreenUpgradeBadges(int[] argb, int w, int h) {
        int y0 = (int) (h * 0.24), y1 = (int) (h * 0.70);
        int x0 = (int) (w * 0.12), x1 = (int) (w * 0.88);
        int step = Math.max(4, w / 180);
        int greenClusters = 0;
        for (int gy = y0; gy < y1; gy += step * 8) {
            for (int gx = x0; gx < x1; gx += step * 8) {
                int greenPx = 0;
                for (int dy = 0; dy < step * 6 && gy + dy < y1; dy += step) {
                    int off = (gy + dy) * w;
                    for (int dx = 0; dx < step * 6 && gx + dx < x1; dx += step) {
                        int c = argb[off + gx + dx];
                        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                        if (g > 155 && g > r + 45 && g > b + 35) greenPx++;
                    }
                }
                if (greenPx >= 5) greenClusters++;
            }
        }
        return Math.min(10, greenClusters);
    }
}
