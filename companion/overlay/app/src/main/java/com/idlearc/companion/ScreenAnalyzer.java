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
            "([0-9]+(?:[.,][0-9]+)?)\\s*%\\s*\\(\\s*([0-9]+(?:[.,][0-9]+)?)");
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
        int overlayTopY = detectSelfOverlayTopY(argb, w, h);
        if (overlayTopY > ry0 + 120 && overlayTopY < ry1) {
            ry1 = overlayTopY - 4;
        }

        List<IdleArcOcrEngine.OcrLine> rawMetaLines = filterOutSelfOverlayLines(
                ocr.recognizeLinesWithMeta(argb, w, h, rx0, ry0, rx1, ry1));
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

        // 1. ОКНО «ХАРАКТЕРИСТИКИ ПЕРСОНАЖА» (верхняя, средняя и нижняя прокрутка всех 31 строк)
        boolean hasItemModalMarkers = lowAll.contains("аффикс") || lowAll.contains("надеть")
                || lowAll.contains("снять") || lowAll.contains("гнезд") || lowAll.contains("гнёзд")
                || lowAll.contains("переков") || lowAll.contains("разобрать") || lowAll.contains("пробужд");
        boolean isCharStatsSheet = lowAll.contains("характеристики персонажа")
                || lowAll.contains("по мере их получения")
                || (!hasItemModalMarkers && lowAll.contains("шанс блока") && (lowAll.contains("возмезд") || lowAll.contains("урон боссам")))
                || (!hasItemModalMarkers && lowAll.contains("множител") && (lowAll.contains("рубин") || lowAll.contains("материал") || lowAll.contains("питомц")))
                || (!hasItemModalMarkers && lowAll.contains("бонус опыт") && lowAll.contains("бонус золот") && (lowAll.contains("выпаден") || lowAll.contains("доп. убийств") || lowAll.contains("множител")));
        if (isCharStatsSheet) {
            res.screenType = "Характеристики персонажа";
            int updatedCount = updateProfileFromStatsLines(lines, profile);
            res.profileUpdated = (updatedCount > 0);
            res.shortBadge = String.format(Locale.US, "📊 Профиль: %d стат.", profile.scannedStatsCount);

            if (profile.wastedDodgePct() > 1.0) {
                res.color = VerdictColor.WARN;
                res.headline = String.format(Locale.US,
                        "⚠️ С экрана +%d стр. (в профиле: %d/31) · Уклонение %.1f%% > 95%%",
                        updatedCount, profile.scannedStatsCount, profile.dodgeRaw);
            } else {
                res.color = VerdictColor.GOOD;
                res.headline = String.format(Locale.US,
                        "✅ Считано с экрана %d стр. · Всего собрано в профиле: %d из 31!",
                        updatedCount, profile.scannedStatsCount);
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

        // 4б. ОКНА «ОБЗОР ГНЁЗД», «ГНЁЗДА · СЛОТ» И «КОДЕКС САМОЦВЕТОВ»
        if (lowAll.contains("обзор гнезд") || lowAll.contains("обзор гнёзд")) {
            return GemSocketOptimizer.analyzeSocketsOverview(metaLines, argb, w, h, profile);
        }
        if (GemSocketOptimizer.isSlotSocketsModal(lowAll, lines)) {
            return GemSocketOptimizer.analyzeSlotSocketsModal(metaLines, profile);
        }
        if (lowAll.contains("кодекс самоцветов")
                || (lowAll.contains("самоцветы") && (lowAll.contains("как это раб")
                    || lowAll.contains("семеиства") || lowAll.contains("лестница гнезд")
                    || lowAll.contains("второстепенные характеристики")))) {
            res.screenType = "Кодекс самоцветов";
            res.color = VerdictColor.GOOD;
            res.shortBadge = "💎 Гайд камней (" + profile.mode.title + ")";
            res.headline = "💎 Кодекс самоцветов — живой расчёт под «" + profile.mode.title + "»";
            res.details = GemSocketOptimizer.buildOptimalGemBlueprint(profile, 46);
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
            boolean equippedVisualHint = detectRedUnequipButton(argb, w, h);
            AffixPositionOptimizer.OptimizationReport opt =
                    AffixPositionOptimizer.analyzeItemAffixes(metaLines, detectedSlot, profile, equippedVisualHint);
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

        // 8. Боевой экран или стороннее приложение (без открытой карточки IdleArc)
        res.screenType = "Обзор / Ожидание окна IdleArc";
        res.color = VerdictColor.GOOD;
        res.shortBadge = "🌿 Готов (" + profile.mode.title + ")";
        res.headline = "🌿 На экране сейчас не открыта карточка предмета или статов IdleArc";
        StringBuilder preview = new StringBuilder();
        preview.append("Текущий режим расчёта: ").append(profile.mode.title).append("\n")
               .append("• Сохранённый профиль героя: ")
               .append(profile.hasScannedStats
                       ? ("активен (собрано " + profile.scannedStatsCount + " из 31 параметров)")
                       : "ещё не отсканирован")
               .append("\n");
        if (profile.hasScannedStats) {
            preview.append(String.format(Locale.US,
                    "• База расчёта: Инт %.0f · Ловк %.0f · Крит %.1f%%/%.0f%% · Дв.удар %.0f%% · Дв.урон %.0f%% · Укл %.1f%%\n",
                    profile.intelligence, profile.agility, profile.critChance, profile.critDamage,
                    profile.doubleHitChance, profile.doubleDamageChance, profile.dodgeRaw));
        }
        preview.append("\n📷 Какие окна в игре можно сканировать кнопкой 🔍 СКАН:\n")
               .append("  1) «Характеристики персонажа» (верх + прокрутка вниз для всех 31 строк)\n")
               .append("  2) Любая карточка предмета (надетого или в сумке / кузнице)\n")
               .append("  3) «Обзор гнёзд», «Гнёзда слота» и «Кодекс самоцветов»\n")
               .append("  4) Сравнение двух предметов, Автонадписи и Навыки Друида");
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
     * Находит верхнюю изумрудную границу (#34D399) раскрытой карточки собственного виджета Druid Helper,
     * чтобы отсечь её ещё до запуска OCR (например, при анализе готового скриншота с открытым оверлеем).
     */
    private static int detectSelfOverlayTopY(int[] argb, int w, int h) {
        int x0 = (int) (w * 0.15);
        int x1 = (int) (w * 0.85);
        int span = Math.max(1, (x1 - x0) / 4);
        int yStart = (int) (h * 0.25);
        int yEnd = (int) (h * 0.90);
        for (int y = yStart; y < yEnd; y++) {
            int rowOff = y * w;
            int emerald = 0;
            for (int x = x0; x < x1; x += 4) {
                int px = argb[rowOff + x];
                int r = (px >> 16) & 0xFF;
                int g = (px >> 8) & 0xFF;
                int b = px & 0xFF;
                if (g >= 185 && r <= 90 && b >= 120 && b <= 185 && (g - r) >= 110) {
                    emerald++;
                }
            }
            if (emerald >= (span * 65) / 100) {
                return y;
            }
        }
        return -1;
    }

    /**
     * Исключает строки собственного плавающего виджета Druid Helper, если кадр или скриншот
     * содержал открытую карточку оверлея поверх игры.
     */
    private static List<IdleArcOcrEngine.OcrLine> filterOutSelfOverlayLines(List<IdleArcOcrEngine.OcrLine> src) {
        if (src == null || src.isEmpty()) return src;
        List<IdleArcOcrEngine.OcrLine> out = new ArrayList<>(src.size());
        for (IdleArcOcrEngine.OcrLine ln : src) {
            String rawLower = ln.text != null ? ln.text.toLowerCase(Locale.ROOT) : "";
            String low = DruidKnowledgeBase.normalizeForMatch(ln.text);
            if (rawLower.contains("druid helper")
                    || low.contains("боссы рейд")
                    || low.contains("пуш пет")
                    || low.contains("распознано параметров")
                    || low.contains("защитных капов")
                    || low.contains("живой математический")
                    || low.contains("считано с экрана")) {
                break;
            }
            out.add(ln);
        }
        return out;
    }

    /**
     * Полный визуальный парсер всех 31 строк окна «Характеристики персонажа» (включая значения 0 / 0%
     * и накопительную битовую маску при прокрутке списка характеристик вверх и вниз).
     */
    static int updateProfileFromStatsLines(List<String> lines, DruidProfile p) {
        int updated = 0;
        for (String raw : lines) {
            String low = DruidKnowledgeBase.normalizeForMatch(raw);
            if (low.isEmpty()
                    || low.contains("характеристики персонажа")
                    || low.contains("по мере их получения")
                    || low.contains("чистого dps")
                    || low.contains("до капа не хватает")
                    || low.contains("при капе класса")
                    || low.contains("распознано параметров")
                    || low.contains("собрано в профиле")) {
                continue;
            }

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

            List<Double> rowNums = DruidKnowledgeBase.extractAllNumbers(raw);
            if (rowNums.isEmpty()) continue;
            double v = Math.max(0.0, rowNums.get(rowNums.size() - 1));

            int bit = -1;
            if (low.contains("очки снаряжения") || low.contains("gs")) {
                if (v >= 10 && v <= 999999) { p.gearScore = (int) Math.round(v); updated++; }
            } else if (low.contains("снижение урона")) {
                parseEffAndRawPct(raw, v, true, p);
                bit = 0;
            } else if (low.contains("уклонен")) {
                parseEffAndRawPct(raw, v, false, p);
                bit = 1;
            } else if (low.contains("шанс блока") || (low.contains("блок") && raw.contains("%"))) {
                p.blockChance = v;
                bit = 2;
            } else if (low.startsWith("сила") && !low.contains("талисман")) {
                p.strength = v;
                bit = 3;
            } else if (low.contains("ловкост")) {
                p.agility = v;
                bit = 4;
            } else if (low.contains("интеллект")) {
                p.intelligence = v;
                bit = 5;
            } else if ((low.contains("макс. здоров") || low.contains("макс здоров") || low.startsWith("здоровье"))
                    && !low.contains("за удар") && !low.contains("за убийств") && !low.contains("реген")
                    && !raw.contains("%")) {
                p.maxHp = v;
                bit = 6;
            } else if (low.contains("урон атаки") && !raw.contains("%")) {
                double avgAtk = DruidKnowledgeBase.extractRangeAverage(raw);
                if (avgAtk >= 0) { p.minAtk = avgAtk; p.maxAtk = avgAtk; bit = 7; }
            } else if (low.startsWith("защита") && !raw.contains("%")) {
                p.defense = v;
                bit = 8;
            } else if (low.contains("шанс крит") || low.contains("крит. удара")) {
                p.critChance = v;
                bit = 9;
            } else if (low.contains("крит. урон") || low.contains("крит урон") || low.contains("критический урон")) {
                p.critDamage = v;
                bit = 10;
            } else if (low.contains("двойного удара") || low.contains("двойной удар")) {
                p.doubleHitChance = v;
                bit = 11;
            } else if (low.contains("двойного урона") || low.contains("двойной урон")) {
                p.doubleDamageChance = v;
                bit = 12;
            } else if (low.contains("урон боссам") || low.contains("по боссам") || low.contains("боссам")) {
                p.bossDamage = v;
                bit = 13;
            } else if (low.contains("множител")) {
                if (low.contains("питомц")) {
                    p.petDamageMultMillions = v >= 10000.0 ? (v / 1_000_000.0) : v;
                    bit = 14;
                } else if (low.contains("материал")) {
                    p.matDropMult = v;
                    bit = 15;
                } else if (low.contains("рубин")) {
                    p.rubyDropMult = v;
                    bit = 16;
                } else if (low.contains("рун")) {
                    p.runeDropMult = v;
                    bit = 17;
                } else if (low.contains("яиц") || low.contains("яйц")) {
                    p.eggDropMult = v;
                    bit = 18;
                } else if (low.contains("добыч") || low.contains("предмет") || low.contains("выпаден")) {
                    p.itemDropMult = v;
                    bit = 19;
                }
            } else if (low.contains("урон питомц") || low.contains("урона питомц") || low.contains("бонус питомц")) {
                if (raw.contains("%")) {
                    p.petDamageBonusPct = v;
                    bit = 20;
                } else {
                    p.petDamageMultMillions = v >= 10000.0 ? (v / 1_000_000.0) : v;
                    bit = 14;
                }
            } else if (low.contains("возмезд") || low.contains("отражен")) {
                p.retaliation = v;
                bit = 21;
            } else if (low.contains("за удар") && (low.contains("убийств") || raw.contains("%"))) {
                p.lifeStealBonusPct = v;
                bit = 22;
            } else if (low.contains("за удар") && !low.contains("убийств")) {
                p.lifeOnHit = v;
                bit = 23;
            } else if (low.contains("за убийств")) {
                p.lifeOnKill = v;
                bit = 24;
            } else if (low.contains("классовые навыки") || low.contains("классовым навыкам") || low.contains("все навыки")) {
                p.bonusAllClassSkills = (int) Math.round(v);
                bit = 25;
            } else if (low.contains("выпадения предметов") || low.contains("поиск предметов") || low.contains("добыча предметов")) {
                p.itemDropPct = v;
                bit = 26;
            } else if (low.contains("материал")) {
                p.matDropPct = v;
                bit = 27;
            } else if (low.contains("выпадения рун") || low.contains("добыча рун") || (low.contains("рун") && raw.contains("%"))) {
                p.runeDropPct = v;
                bit = 28;
            } else if (low.contains("рубин")) {
                p.rubyDropPct = v;
                bit = 29;
            } else if (low.contains("яиц") || low.contains("яйц")) {
                p.eggDropPct = v;
                bit = 30;
            } else if (low.contains("доп. убийств") || low.contains("доп убийств") || (low.contains("дополнительн") && low.contains("убийств"))) {
                p.extraKillChance = v;
                bit = 31;
            } else if (low.contains("бонус опыт") || low.contains("получение опыт") || (low.contains("опыт") && raw.contains("%"))) {
                p.expBonus = v;
                bit = 32;
            } else if (low.contains("бонус золот") || low.contains("получение золот") || (low.contains("золот") && raw.contains("%"))) {
                p.goldBonus = v;
                bit = 33;
            } else if (low.contains("преобраз") || low.contains("конверс") || low.contains("стихи")
                    || low.contains("огн") || low.contains("холод") || low.contains("льда")
                    || low.contains("молни") || low.contains("яд") || low.contains("тьм") || low.contains("свет")) {
                if (v > p.elementalConvertPct) p.elementalConvertPct = v;
                if (low.contains("огн")) bit = 34;
                else if (low.contains("холод") || low.contains("лед") || low.contains("льд")) bit = 35;
                else if (low.contains("молни")) bit = 36;
                else if (low.contains("яд")) bit = 37;
                else if (low.contains("тьм") || low.contains("хаос")) bit = 38;
                else if (low.contains("свет") || low.contains("свят")) bit = 39;
                else bit = 40;
            } else {
                // Универсальный учёт любой дополнительной строки в окне «Характеристики персонажа»
                // (например, новых динамически открытых параметров «по мере их получения»)
                String labelOnly = low.replaceAll("[0-9%+.,\\s]+", "");
                if (labelOnly.length() >= 4 && labelOnly.length() <= 36) {
                    bit = 48 + Math.abs(labelOnly.hashCode() % 14);
                }
            }

            if (bit >= 0) {
                p.scannedStatMask |= (1L << bit);
                updated++;
            }
        }
        if (updated > 0) {
            p.hasScannedStats = true;
            p.scannedStatsCount = Math.max(p.scannedStatsCount, Math.max(updated, Long.bitCount(p.scannedStatMask)));
            p.lastUpdatedMs = System.currentTimeMillis();
        }
        return updated;
    }

    private static void parseEffAndRawPct(String raw, double fallbackVal, boolean isDr, DruidProfile p) {
        String cleaned = DruidKnowledgeBase.repairNumericOcr(raw);
        Matcher m = PAREN_PCT.matcher(cleaned);
        if (m.find()) {
            try {
                double eff = Double.parseDouble(m.group(1).replace(',', '.'));
                double rawPct = Double.parseDouble(m.group(2).replace(',', '.'));
                if (rawPct < eff) {
                    rawPct = eff;
                }
                if (isDr) {
                    p.damageReductionEff = Math.min(95.0, eff);
                    p.damageReductionRaw = rawPct;
                } else {
                    p.dodgeEff = Math.min(95.0, eff);
                    p.dodgeRaw = rawPct;
                }
                return;
            } catch (Exception ignored) {
            }
        }
        List<Double> allNums = DruidKnowledgeBase.extractAllNumbers(cleaned);
        double bestPct = fallbackVal;
        for (double cand : allNums) {
            if (cand > bestPct && cand <= 5000.0) {
                bestPct = cand;
            }
        }
        if (isDr) {
            p.damageReductionEff = Math.min(95.0, bestPct);
            p.damageReductionRaw = bestPct;
        } else {
            p.dodgeEff = Math.min(95.0, bestPct);
            p.dodgeRaw = bestPct;
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

    /**
     * Визуальная детекция бордово-красной кнопки «Снять» в левой нижней части модального окна предмета
     * (страхует случай, когда текст кнопки перекрыт или искажён).
     */
    public static boolean detectRedUnequipButton(int[] argb, int w, int h) {
        if (argb == null || w < 100 || h < 100 || argb.length < w * h) return false;
        int x0 = (int) (w * 0.09), x1 = (int) (w * 0.27);
        int y0 = (int) (h * 0.81), y1 = (int) (h * 0.91);
        int step = Math.max(3, w / 250);
        int burgundyPx = 0;
        int crimsonPx = 0;
        int totalPx = 0;
        for (int y = y0; y < y1; y += step) {
            int off = y * w;
            for (int x = x0; x < x1; x += step) {
                int c = argb[off + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                totalPx++;
                if (r >= 48 && g <= 54 && b <= 54 && (r - g) >= 22 && (r - b) >= 18) {
                    burgundyPx++;
                }
                if (r >= 95 && (r - g) >= 40 && (r - b) >= 35) {
                    crimsonPx++;
                }
            }
        }
        return totalPx > 0 && burgundyPx >= (totalPx * 16) / 100 && crimsonPx >= Math.max(8, totalPx / 120);
    }
}
