package com.idlearc.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Анализатор игрового экрана IdleArc:
 * определяет открытое окно (Сравнение шмота, Предмет/Талисман, Выбор надписи из 3,
 * Характеристики персонажа, Навык Друида, Дерево навыков, Снаряжение) и выдаёт точный вердикт.
 */
public class ScreenAnalyzer {

    public enum VerdictColor {
        GOOD,    // Изумрудный (надевать / отлично)
        WARN,    // Золотой (внимание / оверкап / инфо)
        BAD      // Красный (хуже надетого / мусор / ловушка)
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

    private static final Pattern PAREN_PCT = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)%\\s*\\(\\s*([0-9]+(?:[.,][0-9]+)?)");

    public static ScanResult analyzeFrame(IdleArcOcrEngine ocr, int[] argb, int w, int h, DruidProfile profile) {
        ScanResult res = new ScanResult();
        if (ocr == null || argb == null || w < 100 || h < 100) {
            res.headline = "Нет кадра для анализа";
            res.color = VerdictColor.WARN;
            return res;
        }

        // Сканируем центральную игровую область (без системного статус-бара и нижней навигации Android)
        int rx0 = (int) (w * 0.05);
        int rx1 = (int) (w * 0.95);
        int ry0 = (int) (h * 0.08);
        int ry1 = (int) (h * 0.90);

        List<IdleArcOcrEngine.OcrLine> metaLines = ocr.recognizeLinesWithMeta(argb, w, h, rx0, ry0, rx1, ry1);
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

        // 1. ОКНО «ХАРАКТЕРИСТИКИ ПЕРСОНАЖА» (не путать с карточкой предмета, где одновременно выпали Двойной удар и Уклонение!)
        boolean hasItemModalMarkers = lowAll.contains("аффикс") || lowAll.contains("надеть")
                || lowAll.contains("гнезд") || lowAll.contains("гнёзд") || lowAll.contains("переков")
                || lowAll.contains("разобрать") || lowAll.contains("пробужд");
        boolean isCharStatsSheet = lowAll.contains("характеристики персонажа")
                || (!hasItemModalMarkers && lowAll.contains("шанс блока") && lowAll.contains("возмезд"))
                || (!hasItemModalMarkers && lowAll.contains("множитель добычи") && lowAll.contains("рубин"));
        if (isCharStatsSheet) {
            res.screenType = "Характеристики персонажа";
            int updatedCount = updateProfileFromStatsLines(lines, profile);
            res.profileUpdated = (updatedCount > 0);
            res.shortBadge = String.format(Locale.US, "📊 Профиль обновлён (%d стат.)", updatedCount);

            if (profile.wastedDodgePct() > 1.5) {
                res.color = VerdictColor.WARN;
                res.headline = String.format(Locale.US,
                        "⚠️ Сняты живые статы! Оверкап уклонения: +%.1f%% впустую", profile.wastedDodgePct());
            } else {
                res.color = VerdictColor.GOOD;
                res.headline = "✅ Живые характеристики Друида сохранены в профиль!";
            }
            res.details = DruidKnowledgeBase.buildAuditReport(profile);
            return res;
        }

        // 2. ВЫБОР НАДПИСИ ТАЛИСМАНА (3 варианта)
        if (lowAll.contains("выбери надпись") || lowAll.contains("выбрать надпись") || lowAll.contains("предложения")) {
            return analyzeInscriptionChoices(ocr, argb, w, h, lines, profile);
        }

        // 3. ВКЛАДКА ДЕРЕВА КЛАССОВЫХ НАВЫКОВ (Хатка / Туннели / Симбиоз) — проверяем до одиночного попапа скилла
        if ((lowAll.contains("хатка") || lowAll.contains("туннели") || lowAll.contains("симбиоз") || lowAll.contains("пассивное дерево"))
                && !lowAll.contains("закрыть") && (lowAll.contains("макс") || lowAll.contains("вложить"))) {
            res.screenType = "Дерево классовых навыков";
            int maxCount = countOccurrences(lowAll, "макс");
            res.shortBadge = "🌳 Навыки Друида";
            res.color = VerdictColor.GOOD;
            if (maxCount >= 2) {
                res.headline = "✅ Ветка прокачана на МАКС! Что качать дальше:";
                res.details = "• На твоём уровне все 18 классовых навыков уже закрыты на МАКС — респекать очки за 25 Arcstone НЕ НУЖНО!\n"
                        + "• Теперь твой главный рычаг в навыках — аффикс «+Все классовые навыки» на питомцах и экипировке:\n"
                        + "  каждый +1 ранг одновременно апает 10 безлимитных скиллов Друида (Погрыз +8% пета, Одна душа +12% пета, Хранитель хатки +1 мастерство, Землекоп +2% мат/+1% шмот, Общий инстинкт +10% крит урон)!\n"
                        + "• Не забудь проверить вкладку «Пассивное дерево» под пресеты (Набор 1 — Пуш/Босс, Набор 2 — Фарм).";
            } else {
                res.headline = "🌱 Прокачка классовых навыков Друида:";
                res.details = "Качай строго в таком порядке:\n"
                        + "1) Хатка: Погрыз -> Строитель плотин -> Удар хвостом + Хранитель хатки\n"
                        + "2) Туннели: Землекоп + Чуткий нюх -> Подкоп (казнь 35% HP!)\n"
                        + "3) Симбиоз: Родство + Общий инстинкт -> Одна душа (+12%/ур пета!)\n"
                        + "💡 Если уже есть +15..+20 ко всем навыкам, в скиллах с плашкой ЛИМИТ оставляй по 1 очку!";
            }
            return res;
        }

        // 4. КАРТОЧКА КОНКРЕТНОГО НАВЫКА ДРУИДА (модальное окно с «Ранг: ...» и «Закрыть»)
        DruidKnowledgeBase.SkillInfo skill = DruidKnowledgeBase.findSkillByText(joined);
        if (skill != null && (lowAll.contains("ранг") || lowAll.contains("за уровень") || lowAll.contains("закр"))) {
            res.screenType = "Навык Друида";
            boolean overLimit = lowAll.contains("лимит") || lowAll.contains("макс.");
            int bonusAll = detectBonusAllSkills(lines, profile.bonusAllClassSkills);
            if (bonusAll > 0) profile.bonusAllClassSkills = bonusAll;

            res.shortBadge = "🌿 " + skill.ruName + (skill.hasHardCap ? " [ЛИМИТ]" : " [БЕЗ КАПА]");
            res.color = skill.hasHardCap ? VerdictColor.WARN : VerdictColor.GOOD;
            res.headline = "Навык «" + skill.ruName + "» (" + skill.branch + " · Ранг " + skill.tierRank + ")";
            StringBuilder d = new StringBuilder();
            d.append("• Эффект: ").append(skill.formulaRu).append("\n");
            d.append("• Механика капа: ").append(skill.capNoteRu).append("\n");
            d.append("• Приоритет прокачки с 1 ур.: #").append(skill.levelingPriority).append(" из 18\n");
            if (skill.hasHardCap && profile.bonusAllClassSkills >= skill.effectiveCapLevels - 1) {
                d.append("💡 У тебя +").append(profile.bonusAllClassSkills)
                 .append(" от Всех классовых навыков: навык уже выше потолка (")
                 .append(skill.effectiveCapLevels).append(" эфф. ур.) даже с 1 очком! На 324 ур. очков хватает на все 18/18, но на твинке/сезоне держи тут 1 очко.");
            } else if (overLimit) {
                d.append("ℹ️ Навык достиг жёсткого потолка (ЛИМИТ) — дальнейший рост +Всех навыков усиливает соседние безлимитные скиллы.");
            } else {
                d.append("🔥 Этот навык НЕ имеет потолка — каждый +1 к «Всем классовым навыкам» на петах/шмоте продолжает его разгонять!");
            }
            res.details = d.toString();
            return res;
        }

        // 5. СРАВНЕНИЕ ДВУХ ПРЕДМЕТОВ (Надето vs Новый / левая и правая или верхняя и нижняя карточки)
        if (isItemComparisonScreen(lowAll)) {
            return analyzeItemComparison(ocr, argb, w, h, profile);
        }

        // 6. ОДИНОЧНАЯ КАРТОЧКА ПРЕДМЕТА / ПОЗИЦИЙ АФФИКСОВ КУЗНИЦЫ (ИНТЕЛЛЕКТУАЛЬНЫЙ ПОДБОР ПО МЕСТАМ #1..#N)
        boolean equipGridNoModal = (lowAll.contains("кодекс") && lowAll.contains("автонадписи"))
                && !lowAll.contains("надеть") && !lowAll.contains("разобрать") && !lowAll.contains("перераспредел");
        SlotAffixAdvisor.GearSlot detectedSlot = equipGridNoModal ? null : SlotAffixAdvisor.detectSlotFromLines(lines);

        if (!equipGridNoModal) {
            AffixPositionOptimizer.OptimizationReport opt =
                    AffixPositionOptimizer.analyzeItemAffixes(metaLines, detectedSlot, profile);
            if (opt != null && (isSingleItemPopup(lowAll) || detectedSlot != null || opt.currentRows.size() >= 2)) {
                res.isAffixItemScan = true;
                res.detectedSlot = opt.slot;
                res.screenType = opt.slot.shortChip + " · По-позиционный подбор (" + profile.mode.title + ")";
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
            res.shortBadge = slotLabel + ": " + ev.tierGrade;
            res.color = ev.totalScore >= 52 ? VerdictColor.GOOD
                      : (ev.totalScore >= 25 ? VerdictColor.WARN : VerdictColor.BAD);
            res.headline = slotLabel + " — " + ev.tierGrade;
            res.details = formatItemEvalDetails(ev, profile);
            return res;
        }

        // 7. ЭКРАН ЭКИПИРОВКИ («Снаряжение» / «Кодекс» / «Автонадписи»)
        if (lowAll.contains("кодекс") || lowAll.contains("автонадписи") || (lowAll.contains("снаряжение") && lowAll.contains("кузница"))) {
            int greenArrows = countGreenUpgradeBadges(argb, w, h);
            res.screenType = "Сетка снаряжения";
            res.shortBadge = "⚔️ Шмот · " + greenArrows + " апгрейдов";
            res.color = VerdictColor.GOOD;
            res.headline = "⚔️ Снаряжение Друида (доступно улучшений ячеек: ~" + greenArrows + ")";
            res.details = "🎯 ПРИОРИТЕТ ПРОКАЧКИ ЯЧЕЕК ЗА ЗОЛОТО/МАТЕРИАЛЫ:\n"
                    + "1. Двуручный посох (у тебя A6 +30 ★5 — держи всегда максимальным: его плоская база через «Родство» даёт 75% в базу питомца!).\n"
                    + "2. Кольца, Амулет и Перчатки (поднимай с A5 +5 -> A6 ради атакующих проков и дропа).\n"
                    + "3. Факел (вторая рука), Шлем, Броня, Пояс и Сапоги.\n"
                    + "4. Оба Талисмана (+9) держи равного уровня жертв и крути Автонадписи (Двойной удар / Урон пета / Доп. убийство).\n"
                    + "💡 Нажми на любой выпавший предмет, чтобы открыть его статы, и нажми «СКАН» — я скажу, надевать его или разбирать!";
            return res;
        }

        // 8. Боевой экран или прочее окно
        res.screenType = "Обзор / Бой";
        res.color = VerdictColor.GOOD;
        res.shortBadge = "🌿 Готов (" + profile.mode.title + ")";
        res.headline = "🌿 Открой предмет, надпись, навык или Характеристики";
        StringBuilder preview = new StringBuilder();
        preview.append("Текущий режим оценки: ").append(profile.mode.title).append("\n")
               .append("• Открой карточку выпавшего предмета / сравнения — подскажу, надевать или ломать.\n")
               .append("• Открой «Характеристики персонажа» и нажми СКАН — обновлю твои живые статы.\n")
               .append("• Включи «⚡ АВТО», чтобы виджет сам ловил новые окна при открытии!\n");
        if (!lines.isEmpty()) {
            preview.append("\nРаспознано на экране (").append(lines.size()).append(" стр.):\n");
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
            res.screenType = opt.slot.shortChip + " · По-позиционный подбор (" + profile.mode.title + ")";
            res.shortBadge = opt.shortBadge;
            res.headline = opt.headline;
            res.color = opt.verdictColor;
            res.details = opt.details;
        }
    }

    private static ScanResult analyzeItemComparison(IdleArcOcrEngine ocr, int[] argb, int w, int h, DruidProfile profile) {
        ScanResult res = new ScanResult();
        res.screenType = "Сравнение предметов";

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
        List<String> candLines = useHorizontal ? rightLines : botLines;
        List<String> eqLines = useHorizontal ? leftLines : topLines;
        SlotAffixAdvisor.GearSlot candSlot = SlotAffixAdvisor.detectSlotFromLines(candLines);
        if (candSlot == null) candSlot = SlotAffixAdvisor.detectSlotFromLines(eqLines);
        res.detectedSlot = candSlot;

        String eqLabel = useHorizontal ? "Левый (текущий)" : "Верхний (текущий)";
        String candLabel = useHorizontal ? "Правый (новый)" : "Нижний (новый)";

        double diff = candidate.totalScore - equipped.totalScore;
        if (diff >= 4.0) {
            res.color = VerdictColor.GOOD;
            res.shortBadge = String.format(Locale.US, "✅ НАДЕВАЙ (+%.0f очк.)", diff);
            res.headline = "✅ " + candLabel + " ЛУЧШЕ под Друида (" + profile.mode.title + ")!";
        } else if (diff <= -4.0) {
            res.color = VerdictColor.BAD;
            res.shortBadge = "❌ ОСТАВЬ ТЕКУЩИЙ";
            res.headline = "❌ " + candLabel + " СЛАБЕЕ — оставляй " + eqLabel;
        } else {
            res.color = VerdictColor.WARN;
            res.shortBadge = "⚖️ ПОЧТИ РАВНЫ";
            res.headline = "⚖️ Предметы близки по силе — смотри по-строчный разбор ниже";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "• %s: %.0f балл. [%s]\n", eqLabel, equipped.totalScore, equipped.tierGrade));
        sb.append(String.format(Locale.US, "• %s: %.0f балл. [%s]\n\n", candLabel, candidate.totalScore, candidate.tierGrade));

        AffixPositionOptimizer.OptimizationReport candOpt =
                AffixPositionOptimizer.analyzeItemAffixes(candMeta, candSlot, profile);
        if (candOpt != null) {
            sb.append(candOpt.details);
        } else {
            sb.append(formatItemEvalDetails(candidate, profile));
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

        // Делим центральную модалку на 3 полосы предложений по вертикали
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

        if (bestScore >= 15.0) {
            res.color = VerdictColor.GOOD;
            res.shortBadge = "✒️ БЕРИ ВАРИАНТ #" + bestIdx;
            res.headline = "✒️ Лучшая надпись: ВАРИАНТ #" + bestIdx + " (" + String.format(Locale.US, "%.0f балл.", bestScore) + ")";
        } else {
            res.color = VerdictColor.WARN;
            res.shortBadge = "🔄 ПЕРЕБРОСЬ НАДПИСЬ";
            res.headline = "🔄 Все 3 надписи слабые под Друида — лучше перебросить!";
        }
        res.details = String.format(Locale.US,
                "Оценка под режим «%s»:\n• Вариант #1: %.0f балл. (%s)\n• Вариант #2: %.0f балл. (%s)\n• Вариант #3: %.0f балл. (%s)\n\nТоп-цели: Все классовые навыки, Урон питомца, Двойной удар, Двойной урон, Доп. убийство.",
                profile.mode.title, e1.totalScore, e1.tierGrade, e2.totalScore, e2.tierGrade, e3.totalScore, e3.tierGrade);
        return res;
    }

    private static String formatItemEvalDetails(DruidKnowledgeBase.ItemEvaluation ev, DruidProfile p) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Итоговый рейтинг Друида: %.0f балл. -> %s\n", ev.totalScore, ev.tierGrade));
        if (!ev.pros.isEmpty()) {
            sb.append("\nПолезные статы:\n");
            for (String pro : ev.pros) sb.append("  ").append(pro).append("\n");
        }
        if (!ev.traps.isEmpty()) {
            sb.append("\nЛовушки / под переброс:\n");
            for (String tr : ev.traps) sb.append("  ").append(tr).append("\n");
        }
        if (ev.pros.isEmpty() && ev.traps.isEmpty()) {
            sb.append("\nПодсказка: нажми и удерживай предмет, чтобы открылись все его вторичные характеристики, и нажми СКАН.");
        }
        return sb.toString().trim();
    }

    private static boolean isItemComparisonScreen(String low) {
        int equipWords = countOccurrences(low, "очки снаряжения") + countOccurrences(low, "надето") + countOccurrences(low, "сравн");
        return equipWords >= 2 || (low.contains("надеть") && low.contains("продать")) || (low.contains("надеть") && low.contains("разобрать"));
    }

    private static void recomputeGrade(DruidKnowledgeBase.ItemEvaluation ev) {
        if (ev.totalScore >= 85) ev.tierGrade = "S+ (ИДЕАЛ)";
        else if (ev.totalScore >= 52) ev.tierGrade = "S / A (ОТЛИЧНО)";
        else if (ev.totalScore >= 25) ev.tierGrade = "B (СРЕДНЕ)";
        else if (ev.totalScore >= 5) ev.tierGrade = "C (ПРОХОДНОЙ)";
        else ev.tierGrade = "D (В РАЗБОР)";
    }

    private static boolean isSingleItemPopup(String low) {
        return low.contains("надеть") || low.contains("переков") || low.contains("трансмутац")
                || low.contains("пробужд") || low.contains("перераспредел") || low.contains("смена типа")
                || low.contains("позиции аффиксов") || low.contains("талисман") || low.contains("аффикс")
                || low.contains("очки снаряжения") || low.contains("урон питомц")
                || low.contains("двойного удара") || low.contains("все классовые навыки");
    }

    private static int updateProfileFromStatsLines(List<String> lines, DruidProfile p) {
        int updated = 0;
        for (String raw : lines) {
            String low = DruidKnowledgeBase.normalizeForMatch(raw);
            double v = DruidKnowledgeBase.extractFirstNumber(raw);
            if (v <= 0 && !low.contains("шанс блока")) continue;

            if (low.contains("очки снаряжения") || low.contains("gs")) {
                if (v >= 50 && v <= 500000) { p.gearScore = (int) v; updated++; }
            } else if (low.contains("снижение урона")) {
                parseEffAndRawPct(raw, v, true, p);
                updated++;
            } else if (low.startsWith("сила")) {
                p.strength = v; updated++;
            } else if (low.contains("ловкост")) {
                p.agility = v; updated++;
            } else if (low.contains("интеллект")) {
                p.intelligence = v; updated++;
            } else if (low.contains("шанс крит")) {
                p.critChance = v; updated++;
            } else if (low.contains("крит. урон") || low.contains("крит урон")) {
                p.critDamage = v; updated++;
            } else if (low.contains("двойного удара")) {
                p.doubleHitChance = v; updated++;
            } else if (low.contains("двойного урона")) {
                p.doubleDamageChance = v; updated++;
            } else if (low.contains("уклонен")) {
                parseEffAndRawPct(raw, v, false, p);
                updated++;
            } else if (low.contains("урон боссам")) {
                p.bossDamage = v; updated++;
            } else if (low.contains("урон питомца")) {
                // В строке ×83.48M число уже умножено на 1e6 -> переведём обратно в миллионы
                p.petDamageMultMillions = v >= 10000.0 ? (v / 1000000.0) : v;
                updated++;
            } else if (low.contains("выпадения предметов")) {
                p.itemDropPct = v; updated++;
            } else if (low.contains("выпадения материалов")) {
                p.matDropPct = v; updated++;
            } else if (low.contains("выпадения рун")) {
                p.runeDropPct = v; updated++;
            } else if (low.contains("выпадения рубинов") || low.contains("выпадения ру6инов")) {
                p.rubyDropPct = v; updated++;
            } else if (low.contains("доп. убийства") || low.contains("доп убийства")) {
                p.extraKillChance = v; updated++;
            }
        }
        if (updated > 0) {
            p.lastUpdatedMs = System.currentTimeMillis();
        }
        return updated;
    }

    private static void parseEffAndRawPct(String raw, double fallbackVal, boolean isDr, DruidProfile p) {
        Matcher m = PAREN_PCT.matcher(raw);
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
            p.damageReductionEff = Math.min(99.0, fallbackVal);
            if (fallbackVal > 95.0) p.damageReductionRaw = fallbackVal;
        } else {
            if (fallbackVal > 95.0) {
                p.dodgeEff = 95.0;
                p.dodgeRaw = fallbackVal;
            } else {
                p.dodgeEff = fallbackVal;
            }
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
        // Считаем характерные зелёные кружки со стрелкой вверх в зоне куклы персонажа y=[24%..70%]
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
