package com.idlearc.companion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * По-слотовый классификатор предметов и динамический калькулятор пула слота для Друида в IdleArc 1.3.15.
 *
 * Не содержит захардкоженных списков «лучших статов» или чужих чисел со скриншотов:
 * для любого выбранного слота строит рейтинг всех допустимых игрой аффиксов через LiveBuildSimulator
 * относительно ТЕКУЩЕГО состояния персонажа (DruidProfile) и выбранного режима билда.
 */
public class SlotAffixAdvisor {

    public enum GearSlot {
        WEAPON(
                "⚔️ Оружие",
                "ОРУЖИЕ (Посох / Палка / Жезл / Меч / Булава)",
                new String[]{
                        "оружие", "двуручное", "посох", "жезл", "скипетр", "палка", "дубина", "молот",
                        "меч", "клинок", "сабля", "кинжал", "нож", "рапира",
                        "топор", "секира", "коса", "булава", "палица", "кастет", "когти",
                        "лук", "арбалет", "копье", "копьё", "алебарда"
                },
                "Динамический расчёт пула из 18 серверных аффиксов оружия",
                "База атаки оружия масштабирует навыки героя и урон питомцев. В оружии до 3 гнёзд самоцветов (Нефрит даёт +% Урона питомца, Лазурит — гибрид Атака/Пет/Возмездие, Янтарь — +% Золота).",
                "По правилу Пробуждения игры (awaken): базовый урон оружия растёт на +12% за тир A1..A6, а кап проков «Двойного удара / Двойного урона» расширяется."
        ),
        TORCH(
                "🔥 Оффхенд",
                "ВТОРАЯ РУКА (Факел / Щит / Фолиант / Тотем)",
                new String[]{"факел", "фонарь", "фолиант", "гримуар", "тотем", "сфера", "щит", "баклер", "вторая рука", "левая рука"},
                "Динамический расчёт пула оффхенда (Факел: 9 аффиксов / Щит: 8 аффиксов)",
                "У Факела встроенный имплисит «+N Все классовые навыки» и уникальный суффикс «Здоровье за удар и за убийство %». На Щите этого суффикса в пуле нет (зато есть базовая Защита).",
                "В слоте оффхенда по правилам игры НЕТ «Шанса двойного удара» и НЕТ атрибутов (Интеллект/Ловкость/Сила)."
        ),
        HELMET(
                "🪖 Шлем",
                "ШЛЕМ / ГОЛОВА",
                new String[]{"шлем", "капюшон", "корона", "тиара", "маска", "диадема", "голова", "шапка", "рога"},
                "Динамический расчёт пула шлема (11 серверных аффиксов)",
                "Шлем и Нагрудник имеют общий пул из 11 аффиксов (включая префикс «Снижение урона %», которого нет на перчатках и сапогах).",
                "На «Снижение урона %» в игре действует замороженный кап предмета (frozen_cap_stats), поэтому избыточные стаки Пробуждения выгоднее направлять в масштабируемые множители."
        ),
        CHEST(
                "🛡️ Нагрудник",
                "НАГРУДНИК / ДОСПЕХ",
                new String[]{"нагрудник", "доспех", "броня", "мантия", "роба", "кираса", "туника", "торс", "одеяние", "жилет"},
                "Динамический расчёт пула нагрудника (11 серверных аффиксов)",
                "Нагрудник даёт самую высокую базовую Защиту среди кусков брони и поддерживает префикс «Снижение урона %».",
                "Пробуждение A1..A6 поднимает базовую защиту доспеха (+12%/тир) и потолок «Шанса двойного урона»."
        ),
        GLOVES(
                "🧤 Перчатки",
                "ПЕРЧАТКИ / РУКАВИЦЫ",
                new String[]{"перчатки", "рукавицы", "наручи", "краги", "руки", "митенки", "захваты"},
                "Динамический расчёт пула перчаток (10 серверных аффиксов)",
                "На Перчатках и Сапогах по конфигу игры НЕ выпадает «Снижение урона %» и «Двойной удар %» — симулятор учитывает этот усечённый пул из 10 аффиксов.",
                "Синий самоцвет в гнезде брони даёт фиксированный бонус к шансу крита."
        ),
        BOOTS(
                "🥾 Сапоги",
                "САПОГИ / БОТИНКИ",
                new String[]{"сапоги", "ботинки", "поножи", "ботфорты", "обувь", "ноги", "сандалии", "башмаки"},
                "Динамический расчёт пула сапог (10 серверных аффиксов)",
                "Пул Сапог полностью совпадает с Перчатками (10 серверных аффиксов без «Снижения урона %»).",
                "Выбор между критами, уроном питомца и уклонением рассчитывается ниже по текущему соотношению статов героя."
        ),
        BELT(
                "ベルト Пояс",
                "ПОЯС / РЕМЕНЬ",
                new String[]{"пояс", "ремень", "кушак", "опояска", "талия"},
                "Динамический расчёт пула пояса (13 серверных аффиксов)",
                "Пояс относится к группе бижутерии: здесь открыты и «Двойной удар %», и «Двойной урон %», и атрибуты, и «Доп. урон», и «Защита %» (без восстановления HP за удар/убийство).",
                "За счёт 13 доступных статов симулятор подбирает топ-5 индивидуально под недобор проков или критов."
        ),
        AMULET(
                "📿 Амулет",
                "АМУЛЕТ / ОЖЕРЕЛЬЕ",
                new String[]{"амулет", "ожерелье", "кулон", "медальон", "подвеска", "шея", "оберег"},
                "Динамический расчёт пула амулета (15 серверных аффиксов)",
                "Амулет и Кольца имеют самый широкий пул без стихийных конверсий (15 аффиксов), а также собственные имплиситы бижутерии.",
                "Зелёный самоцвет (Изумруд/Нефрит) в бижутерии добавляет бонус к шансу двойного урона."
        ),
        RING(
                "💍 Кольцо",
                "КОЛЬЦО / ПЕРСТЕНЬ (Оба слота колец)",
                new String[]{"кольцо", "перстень", "печатка", "ободок"},
                "Динамический расчёт пула колец (15 серверных аффиксов)",
                "Два слота колец удваивают вклад каждого найденного здесь множителя.",
                "Ниже приведён точный математический рейтинг всех 15 допустимых аффиксов кольца относительно текущих статов персонажа."
        ),
        TALISMAN(
                "🧿 Талисман",
                "ТАЛИСМАН (2 слота талисманов + 6 Автонадписей)",
                new String[]{"талисман", "реликвия", "фетиш", "идол", "автонадпис", "надпись"},
                "Оценка через симулятор силы атаки талисмана и Автонадписей",
                "Талисманы не куются обычными камнями перековки: они точатся дубликатами и несут по 3 слота Автонадписей (всего 6 надписей на 2 талисманах).",
                "Нажми СКАН в окне выбора Автонадписи — симулятор сравнит предложенные варианты с текущим списком надписей!"
        ),
        PET(
                "🐾 Питомец",
                "ПИТОМЕЦ (Боевой отряд из 4 слотов)",
                new String[]{"питомец", "спутник", "компаньон", "зверь", "фамильяр"},
                "Масштабирование отряда питомцев от статов героя",
                "По механике игры все 4 экипированных питомца наследуют «Шанс крита», «Крит. урон», «Шанс двойного урона» и «Урон питомца %» хозяина.",
                "Соотношение ценности «Урона питомца %» к критам и двойному урону автоматически пересчитывается при изменении твоего пула!"
        );

        public final String shortChip;
        public final String ruTitle;
        public final String[] headerHints;
        public final String awakenHeadline;
        public final String slotMechanicsNote;
        public final String awakenMechanicsNote;

        GearSlot(String shortChip, String ruTitle, String[] headerHints,
                 String awakenHeadline, String slotMechanicsNote, String awakenMechanicsNote) {
            this.shortChip = shortChip;
            this.ruTitle = ruTitle;
            this.headerHints = headerHints;
            this.awakenHeadline = awakenHeadline;
            this.slotMechanicsNote = slotMechanicsNote;
            this.awakenMechanicsNote = awakenMechanicsNote;
        }
    }

    public static GearSlot detectSlotFromLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        int affixIdx = AffixPositionOptimizer.findAffixHeaderIndexFromStrings(lines);
        int headerLimit = affixIdx > 0 ? Math.min(affixIdx, 9) : Math.min(lines.size(), 8);

        for (int i = 0; i < headerLimit; i++) {
            String raw = lines.get(i);
            String low = DruidKnowledgeBase.normalizeForMatch(raw);
            if (raw.trim().startsWith("+") || raw.contains("%")) continue;
            if (isGemOrDropHeaderLine(low)) continue;

            for (GearSlot slot : GearSlot.values()) {
                for (String hint : slot.headerHints) {
                    if (containsSlotWord(low, hint)) return slot;
                }
            }
        }

        for (String raw : lines) {
            String low = DruidKnowledgeBase.normalizeForMatch(raw);
            if (low.startsWith("надето:") || low.startsWith("слот:") || low.startsWith("тип:")) {
                for (GearSlot slot : GearSlot.values()) {
                    for (String hint : slot.headerHints) {
                        if (containsSlotWord(low, hint)) return slot;
                    }
                }
            }
            if (low.contains("все классовые навыки")) return GearSlot.TORCH;
            if (low.contains("двуручное") || low.contains("физический урон")) return GearSlot.WEAPON;
            if (low.contains("сила атаки талисмана") || low.contains("бонусы жертв")) return GearSlot.TALISMAN;
        }
        return null;
    }

    public static GearSlot detectSlotFromModalLines(List<IdleArcOcrEngine.OcrLine> modalLines) {
        if (modalLines == null || modalLines.isEmpty()) return null;
        List<String> plain = new ArrayList<>(modalLines.size());
        for (IdleArcOcrEngine.OcrLine l : modalLines) plain.add(l.text);
        return detectSlotFromLines(plain);
    }

    public static boolean isShieldHeaderLines(List<String> lines) {
        if (lines == null) return false;
        int limit = Math.min(lines.size(), 9);
        for (int i = 0; i < limit; i++) {
            String low = DruidKnowledgeBase.normalizeForMatch(lines.get(i));
            if (containsSlotWord(low, "щит") || containsSlotWord(low, "баклер")) return true;
        }
        return false;
    }

    private static boolean isGemOrDropHeaderLine(String low) {
        return low.contains("гнезд") || low.contains("гнёзд") || low.contains("самоцвет")
                || low.contains("бонус добычи") || low.contains("бонус дропа");
    }

    private static boolean containsSlotWord(String lowLine, String hint) {
        int idx = lowLine.indexOf(hint);
        while (idx >= 0) {
            boolean leftOk = (idx == 0) || !Character.isLetterOrDigit(lowLine.charAt(idx - 1));
            int end = idx + hint.length();
            boolean rightOk = (end >= lowLine.length());
            if (!rightOk) {
                char next = lowLine.charAt(end);
                rightOk = !Character.isLetterOrDigit(next);
                if (!rightOk && hint.length() >= 5 && end + 1 >= lowLine.length()) {
                    rightOk = (next == 'а' || next == 'ы' || next == 'и' || next == 'у' || next == 'е' || next == 'я');
                }
            }
            if (leftOk && rightOk) return true;
            idx = lowLine.indexOf(hint, idx + 1);
        }
        return false;
    }

    /**
     * Динамически рассчитывает рейтинг ВСЕХ совместимых аффиксов выбранного слота
     * через LiveBuildSimulator относительно текущего состояния игрока (profile).
     */
    public static String formatSlotGuide(GearSlot slot, DruidProfile profile) {
        if (slot == null) slot = GearSlot.WEAPON;
        if (profile == null) profile = new DruidProfile();

        StringBuilder sb = new StringBuilder();
        sb.append("📌 ").append(slot.ruTitle).append("\n");
        sb.append("Режим оценки: ").append(profile.mode.title).append(" (").append(profile.mode.subtitle).append(")\n");

        if (profile.hasScannedStats) {
            sb.append("📊 Расчёт построен по ТВОИМ отсканированным статам (Крит ")
              .append(fmt0(profile.critChance)).append("%/").append(fmt0(profile.critDamage))
              .append("%, Дв.удар ").append(fmt0(profile.doubleHitChance))
              .append("%, Дв.урон ").append(fmt0(profile.doubleDamageChance))
              .append("%, Сниж.урона ").append(fmt1(profile.damageReductionRaw))
              .append("%, Уклонение ").append(fmt1(profile.dodgeRaw)).append("%).\n\n");
        } else {
            sb.append("⚠️ «Характеристики персонажа» ещё не отсканированы — рейтинг ниже рассчитан от нейтральной стартовой базы. Открой лист «Характеристики персонажа» и нажми СКАН, чтобы рейтинг пересчитался под твои реальные числа!\n\n");
        }

        if (slot != GearSlot.TALISMAN && slot != GearSlot.PET) {
            // Определяем масштаб типового предмета под уровень героя (или нейтральный масштаб 20)
            double scale = profile.hasScannedStats && profile.charLevel > 1
                    ? Math.max(4.0, Math.min(75.0, profile.charLevel * 0.16))
                    : 20.0;
            double procCap = scale >= 24.0 ? (26.5 + 0.47 * scale) : (scale * 1.5);
            double btCap = 15.0 + (scale >= 30.0 ? 5.0 : 2.0);
            AffixPositionOptimizer.ItemRollModel model =
                    new AffixPositionOptimizer.ItemRollModel(0, 0, scale, procCap, btCap);

            LiveBuildSimulator.HeroState heroState = LiveBuildSimulator.buildHeroState(profile, scale, 0.0, 0.0);
            List<AffixPositionOptimizer.AffixType> pool = AffixPositionOptimizer.getCompatiblePool(slot, false);

            // Совместный расчёт оптимальной 5-ки для этого слота
            List<Double> fiveEqualMults = Arrays.asList(1.0, 1.0, 1.0, 1.0, 1.0);
            List<AffixPositionOptimizer.AffixType> optimal5 = LiveBuildSimulator.solveOptimalSubset(
                    heroState, pool, fiveEqualMults, model,
                    Collections.<AffixPositionOptimizer.AffixType, Double>emptyMap(), 0.0, profile.mode);

            // Индивидуальный относительный вклад каждого кандидата пула слота
            List<LiveBuildSimulator.CandidateEvaluation> ranked = new ArrayList<>();
            for (AffixPositionOptimizer.AffixType cand : pool) {
                ranked.add(LiveBuildSimulator.evaluateCandidate(
                        heroState, cand,
                        model.expectedAvgRoll(cand, 1.0),
                        model.expectedMinRoll(cand, 1.0),
                        model.expectedMaxRoll(cand, 1.0),
                        0.0, profile.mode));
            }
            Collections.sort(ranked, new Comparator<LiveBuildSimulator.CandidateEvaluation>() {
                @Override
                public int compare(LiveBuildSimulator.CandidateEvaluation a, LiveBuildSimulator.CandidateEvaluation b) {
                    return Double.compare(b.totalObjectiveGainPct, a.totalObjectiveGainPct);
                }
            });

            sb.append("🏆 ОПТИМАЛЬНАЯ КОМБИНАЦИЯ ТОП-5 ДЛЯ СЛОТА СЕЙЧАС:\n");
            for (int i = 0; i < optimal5.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(optimal5.get(i).taggedName()).append("\n");
            }

            sb.append("\n📐 ЖИВОЙ СРАВНИТЕЛЬНЫЙ РАСЧЁТ ВСЕГО ПУЛА СЛОТА:\n");
            for (int i = 0; i < ranked.size(); i++) {
                LiveBuildSimulator.CandidateEvaluation ce = ranked.get(i);
                sb.append("  #").append(i + 1).append(" ").append(ce.type.taggedName())
                  .append(" → итог +").append(fmt2(ce.totalObjectiveGainPct)).append("% (")
                  .append(ce.mathExplanation).append(")\n");
            }
            sb.append("\n");

            int maxSocks = GemSocketOptimizer.SocketCategory.maxSocketsForSlot(slot);
            if (maxSocks > 0) {
                GemSocketOptimizer.SocketCategory sCat = GemSocketOptimizer.SocketCategory.fromGearSlot(slot);
                List<GemSocketOptimizer.RankedGemChoice<GemSocketOptimizer.GemFamily>> famRank =
                        GemSocketOptimizer.rankFamiliesForCategory(
                                heroState, sCat, GemSocketOptimizer.GemRarity.PERFECT, 100.0, 46, profile.mode);
                List<GemSocketOptimizer.RankedGemChoice<GemSocketOptimizer.GemSecondaryStat>> secRank =
                        GemSocketOptimizer.rankSecondaries(
                                heroState, GemSocketOptimizer.GemRarity.PERFECT, 100.0, 46, profile.mode);
                GemSocketOptimizer.RankedGemChoice<GemSocketOptimizer.GemFamily> bestFam = famRank.get(0);
                sb.append("💎 ГНЁЗДА СЛОТА (до ").append(maxSocks).append(" гн. · ").append(sCat.shortName).append("):\n")
                  .append("  • Лучший камень под «").append(profile.mode.title).append("»: ")
                  .append(bestFam.item.badge).append(" (").append(bestFam.summary)
                  .append(" → +").append(fmt2(bestFam.totalGainPct)).append("% с гнезда)\n")
                  .append("  • Топ-3 доп. бонуса на камнях: 1) ").append(secRank.get(0).item.ruName)
                  .append(", 2) ").append(secRank.get(1).item.ruName)
                  .append(", 3) ").append(secRank.get(2).item.ruName).append("\n\n");
            }
        }

        sb.append("⚙️ МЕХАНИКА СЛОТА В ИГРЕ:\n• ").append(slot.slotMechanicsNote).append("\n");
        sb.append("• ").append(slot.awakenMechanicsNote).append("\n\n");
        sb.append("💡 Открой любой реальный предмет этого слота в игре и нажми 🔍 СКАН — компаньон считает точные числа каждой из 5 строк с экрана и рассчитает выгоду замены с учётом уже надетых статов!");
        return sb.toString();
    }

    private static String fmt0(double v) {
        return String.format(Locale.US, "%.0f", v);
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
