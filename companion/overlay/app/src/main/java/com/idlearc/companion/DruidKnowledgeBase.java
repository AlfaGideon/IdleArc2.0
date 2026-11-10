package com.idlearc.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Полная база механик IdleArc 1.3.15 (176) для Друида:
 * - все 18 классовых навыков (Хатка / Туннели / Симбиоз), капы ЛИМИТ и синергия +Все классовые навыки;
 * - точные формулы ценности аффиксов снаряжения, талисманов, надписей, рун и питомцев;
 * - детектор статов-ловушек (% Урон атаки не работает на питомца, оверкап Уклонения >95% и т.д.);
 * - подробный пошаговый гайд 1 -> 324+ (кап).
 */
public class DruidKnowledgeBase {

    public static class SkillInfo {
        public final String id;
        public final String ruName;
        public final String branch; // Хатка, Туннели, Симбиоз
        public final int tierRank;  // 1, 2, 3
        public final int maxBasePoints;
        public final String formulaRu;
        public final boolean hasHardCap;
        public final int effectiveCapLevels; // 0 если без лимита
        public final String capNoteRu;
        public final int levelingPriority; // 1 = качать первым, 18 = последним

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
        // ВЕТКА 1: ХАТКА (Бобр — урон и мастерство питомцев)
        SKILLS.add(new SkillInfo("druid_gnaw", "Погрыз", "Хатка", 1, 10,
                "+8% урона питомцев за уровень навыка", false, 0,
                "БЕЗ ЛИМИТА! Масштабируется от +Всех классовых навыков бесконечно (на Ур. 30 дает +240% урона петов).", 1));
        SKILLS.add(new SkillInfo("druid_thick_pelt", "Густой мех", "Хатка", 1, 5,
                "+7% макс. здоровья и +8 HP за убийство за уровень", false, 0,
                "БЕЗ ЛИМИТА. Отличная подушка HP для пуша высоких биомов и Башни.", 11));
        SKILLS.add(new SkillInfo("druid_dam_builder", "Строитель плотин", "Хатка", 2, 5,
                "+15 плоского базового урона питомцев за уровень", false, 0,
                "БЕЗ ЛИМИТА. Разгоняет саму базу питомца до всех мультипликативных слоёв!", 5));
        SKILLS.add(new SkillInfo("druid_timberfall", "Лесоповал", "Хатка", 2, 5,
                "+5% урона по боссам за уровень (ЛИМИТ: макс. 100%)", true, 20,
                "ЛИМИТ 100% (20 эфф. ур.). При +19..+20 ко всем навыкам достаточно 1 вложенного очка до капа всех веток!", 6));
        SKILLS.add(new SkillInfo("druid_tail_slap", "Удар хвостом", "Хатка", 3, 5,
                "Шанс 3% за уровень, что питомец ударит дважды (ЛИМИТ: макс. 60%)", true, 20,
                "ЛИМИТ 60% (20 эфф. ур.) = чистый ×1.60 мультипликатор DPS пета! Топ-навык, но выше 20 суммарного ур. не растёт.", 2));
        SKILLS.add(new SkillInfo("druid_lodgekeeper", "Хранитель хатки", "Хатка", 3, 5,
                "+1 доп. уровень мастерства питомцев за уровень навыка", false, 0,
                "БЕЗ ЛИМИТА! Каждый уровень мастерства умножает базу активного пета (особенно Легендарного).", 3));

        // ВЕТКА 2: ТУННЕЛИ (Крот — дроп, казнь, крит, яйца и руны)
        SKILLS.add(new SkillInfo("druid_digger", "Землекоп", "Туннели", 1, 10,
                "+2% материалов и +1% шанса дропа предметов за уровень", false, 0,
                "БЕЗ ЛИМИТА! Лучший фарм-навык старта и эндгейма (кормит Кузницу и Схождение).", 7));
        SKILLS.add(new SkillInfo("druid_keen_snout", "Чуткий нюх", "Туннели", 1, 5,
                "+1% дропа яиц питомцев (ЛИМИТ яиц: 15%) и +1% материалов за уровень", true, 15,
                "Шанс яиц упирается в ЛИМИТ 15% на 15 эфф. уровне, но +1% материалов продолжает работать.", 8));
        SKILLS.add(new SkillInfo("druid_hoard", "Запасы", "Туннели", 2, 5,
                "+5% получения золота за уровень навыка", false, 0,
                "БЕЗ ЛИМИТА. Нужен для дорогих грейдов ячеек и платных надписей талисманов.", 14));
        SKILLS.add(new SkillInfo("druid_earthbind", "Земные путы", "Туннели", 2, 5,
                "+3% шанса крит. удара за уровень навыка", false, 0,
                "БЕЗ ЛИМИТА. Сверх 100% крита каждый 1% превращается в +1% крит. урона.", 12));
        SKILLS.add(new SkillInfo("druid_undermine", "Подкоп", "Туннели", 3, 5,
                "Мгновенная казнь врага при HP <= 2% за уровень (ЛИМИТ: макс. 35%)", true, 18,
                "ЛИМИТ 35% HP (18 эфф. ур.)! Срезает больше трети HP мобов — ускоряет зачистку в ×1.54 раза!", 4));
        SKILLS.add(new SkillInfo("druid_deep_roots", "Глубокие корни", "Туннели", 3, 5,
                "+1% опыта питомца (ЛИМИТ: 15%) и +1% дропа рун за уровень", true, 15,
                "Опыт пета ограничен 15% (15 ур.), а +1% шанса выпадения рун ценен для рунных ядер.", 13));

        // ВЕТКА 3: СИМБИОЗ (Связь героя и зверя — конверсия базы и множители)
        SKILLS.add(new SkillInfo("druid_kinship", "Родство", "Симбиоз", 1, 10,
                "Преобразует 3% базового урона атаки в урон питомца (ОБЩИЙ ЛИМИТ: 75%)", true, 25,
                "ВНИМАНИЕ: делит ОБЩИЙ кап 75% с «Дикой связью»! Учитывает только БАЗОВУЮ атаку оружия, а НЕ % Урон атаки!", 10));
        SKILLS.add(new SkillInfo("druid_shared_instinct", "Общий инстинкт", "Симбиоз", 1, 5,
                "+10% критического урона за уровень навыка", false, 0,
                "БЕЗ ЛИМИТА. На 25 эфф. уровне даёт +250% крит. урона.", 9));
        SKILLS.add(new SkillInfo("druid_feral_bond", "Дикая связь", "Симбиоз", 2, 5,
                "Преобразует 4% базового урона атаки в урон питомца (ОБЩИЙ ЛИМИТ: 75%)", true, 19,
                "Делит ОБЩИЙ кап 75% с «Родством»! При +20 ко всем навыкам уже 1 очко здесь (21×4%=84%) закрывает весь кап 75%!", 15));
        SKILLS.add(new SkillInfo("druid_twin_heart", "Сердце-близнец", "Симбиоз", 2, 5,
                "+3 HP за удар за уровень и +0.5% снижения урона (до 2.5%)", true, 5,
                "Снижение урона капается на 2.5% (5 ур.), но +3 HP за удар растёт дальше без лимита.", 16));
        SKILLS.add(new SkillInfo("druid_wild_attunement", "Единение с природой", "Симбиоз", 3, 5,
                "+3% к бонусу питомца против стихийной слабости (ЛИМИТ: макс. 25%)", true, 9,
                "ЛИМИТ 25% (всего 9 эфф. ур.!). Работает отлично в связке с «Автоподбором питомца под слабость».", 17));
        SKILLS.add(new SkillInfo("druid_one_soul", "Одна душа", "Симбиоз", 3, 5,
                "+12% бонуса урона питомцев за уровень навыка", false, 0,
                "БЕЗ ЛИМИТА! Главный финишер Симбиоза: +12% за каждый уровень от +Всех классовых навыков!", 1));
    }

    public static SkillInfo findSkillByText(String text) {
        if (text == null) return null;
        String low = normalizeForMatch(text);
        for (SkillInfo s : SKILLS) {
            String key = normalizeForMatch(s.ruName);
            if (low.contains(key)) return s;
        }
        // Доп. нечёткие ключи для OCR
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
        return s.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace('6', 'б')
                .replaceAll("[^а-яa-z0-9%+.]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Результат оценки одного предмета / набора аффиксов для Друида */
    public static class ItemEvaluation {
        public double totalScore;
        public String tierGrade; // S+, S, A, B, C
        public int gearScoreFound;
        public String tierTagFound = "";
        public final List<String> pros = new ArrayList<>();
        public final List<String> traps = new ArrayList<>();
    }

    /**
     * Оценивает список распознанных строк предмета/талисмана/надписи с учётом текущего профиля Друида и режима.
     */
    public static ItemEvaluation evaluateItemLines(List<String> lines, DruidProfile p) {
        ItemEvaluation ev = new ItemEvaluation();
        boolean isFarm = (p.mode == DruidProfile.BuildMode.FARM);
        boolean isBoss = (p.mode == DruidProfile.BuildMode.BOSS);

        for (String raw : lines) {
            String low = normalizeForMatch(raw);
            double val = extractFirstNumber(raw);

            // GS / Тир A5, A6, Инфернальный
            if (low.contains("очки снаряжения") || low.contains("gs")) {
                if (val >= 10) {
                    ev.gearScoreFound = (int) val;
                    ev.totalScore += val * 0.04;
                }
            }
            if (raw.matches(".*\\bA[1-9]\\b.*") || low.contains("инфернальн")) {
                if (low.contains("инфернальн")) {
                    ev.tierTagFound = "Инфернальный";
                    ev.totalScore += 32;
                    ev.pros.add("🔥 Инфернальный тир (доступно Пробуждение)");
                } else {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(A\\d(?:\\s*\\+\\d+)?)").matcher(raw);
                    if (m.find()) ev.tierTagFound = m.group(1);
                }
            }

            // 1. Все классовые навыки — S+ стат №1 для Друида!
            if (low.contains("все классовые навыки") || low.contains("классовым навыкам") || low.contains("классовых навыков")) {
                double pts = val > 0 ? val : 1.0;
                double w = pts * 28.0;
                ev.totalScore += w;
                ev.pros.add(String.format(Locale.US, "👑 +%.0f Все классовые навыки — S+ ТОП (качает сразу 10 безлимитных скиллов Друида: Погрыз +8%%/ур, Одна душа +12%%/ур, Хранитель хатки!)", pts));
                continue;
            }

            // 2. Шанс доп. убийства — S+ в фарме
            if (low.contains("доп. убийств") || low.contains("доп убийств") || low.contains("бонусное убийство")) {
                double pts = val > 0 ? val : 2.0;
                double w = pts * (isFarm ? 24.0 : 12.0);
                ev.totalScore += w;
                ev.pros.add(String.format(Locale.US, "💎 +%.1f%% Шанс доп. убийства — S+ для фарма (удваивает золото, опыт и весь дроп!)", pts));
                continue;
            }

            // 3. Урон питомца / Мастерство питомцев
            if (low.contains("урон питомц") || low.contains("урона питомц") || low.contains("урон пит")) {
                double pts = val > 0 ? val : 15.0;
                double w = pts * (isFarm ? 1.3 : 2.2);
                ev.totalScore += w;
                ev.pros.add(String.format(Locale.US, "🐾 +%.1f%% Урон питомца — главный мультипликативный слой Друида!", pts));
                continue;
            }
            if (low.contains("мастерств") && low.contains("питом")) {
                double pts = val > 0 ? val : 2.0;
                ev.totalScore += pts * 10.0;
                ev.pros.add(String.format(Locale.US, "🐾 +%.0f Мастерство питомцев — растит чистую базу активного пета!", pts));
                continue;
            }

            // 4. Двойной удар (сильнее Двойного урона и Крита на текущих статах!)
            if (low.contains("двойного удара") || low.contains("двойной удар")) {
                double pts = val > 0 ? val : 12.0;
                double realGain = pts * p.marginalGainPer1PctDoubleHit();
                ev.totalScore += realGain * 14.0;
                ev.pros.add(String.format(Locale.US, "⚡ +%.1f%% Шанс двойного удара -> +%.2f%% к реальному DPS пета (лучший боевой прок!)", pts, realGain));
                continue;
            }

            // 5. Двойной урон
            if (low.contains("двойного урона") || low.contains("двойной урон")) {
                double pts = val > 0 ? val : 12.0;
                double realGain = pts * p.marginalGainPer1PctDoubleDamage();
                ev.totalScore += realGain * 13.5;
                ev.pros.add(String.format(Locale.US, "⚡ +%.1f%% Шанс двойного урона -> +%.2f%% к реальному DPS пета", pts, realGain));
                continue;
            }

            // 6. Урон боссам
            if (low.contains("урон боссам") || low.contains("урона по боссам") || low.contains("по боссам")) {
                double pts = val > 0 ? val : 15.0;
                double realGain = pts * p.marginalGainPer1PctBossDmg();
                double mult = isBoss ? 16.0 : 7.5;
                ev.totalScore += realGain * mult;
                ev.pros.add(String.format(Locale.US, "👹 +%.1f%% Урон боссам -> +%.2f%% урона по боссам/рейдам", pts, realGain));
                continue;
            }

            // 7. Интеллект (главный стат Друида: +0.1% урона питомца за очко)
            if (low.contains("интеллект")) {
                double pts = val > 0 ? val : 80.0;
                ev.totalScore += pts * 0.14;
                ev.pros.add(String.format(Locale.US, "🧠 +%.0f Интеллекта (+%.1f%% к пулу урона питомца)", pts, pts * 0.1));
                continue;
            }

            // 8. Крит шанс / Крит урон
            if (low.contains("шанс крит") || low.contains("крит. урон") || low.contains("крит урон") || low.contains("критического")) {
                double pts = val > 0 ? val : 15.0;
                double realGain = pts * p.marginalGainPer1PctCrit();
                ev.totalScore += realGain * 12.0;
                ev.pros.add(String.format(Locale.US, "🎯 +%.1f%% Крит -> +%.2f%% реального DPS (при %.0f%% крит. урона в 2.6× слабее Двойного удара за 1%%)", pts, realGain, p.critDamage));
                continue;
            }

            // 9. Ловкость (полу-стат: +0.05% пет урона, но уклонение уже в капе!)
            if (low.contains("ловкост")) {
                double pts = val > 0 ? val : 80.0;
                ev.totalScore += pts * 0.06;
                if (p.dodgeRaw >= 95.0) {
                    ev.traps.add(String.format(Locale.US, "⚠️ Ловкость (+%.0f): половина стата (уклонение) сгорает из-за капа 95%% (сейчас %.1f%%). Интеллект вдвое сильнее!", pts, p.dodgeRaw));
                } else {
                    ev.pros.add(String.format(Locale.US, "🍃 +%.0f Ловкости (+%.1f%% урон пета и уклонение)", pts, pts * 0.05));
                }
                continue;
            }

            // 10. ЛОВУШКА №1: % Урон атаки vs Базовая атака оружия!
            if (low.contains("урон атаки") || low.contains("урона атаки") || low.contains("физический урон")) {
                if (raw.contains("%")) {
                    ev.traps.add("🚫 ЛОВУШКА: +% Урон атаки НЕ действует на питомца (0% пользы пет-друиду! Навыки «Родство/Дикая связь» берут только плоскую БАЗУ посоха).");
                } else if (val >= 100) {
                    ev.totalScore += 22.0;
                    ev.pros.add("🪄 Высокая плоская база атаки оружия (75% передаётся в базу питомца через Родство/Дикую связь!)");
                }
                continue;
            }

            // 11. ЛОВУШКА №2: Уклонение при перекапе 95%!
            if (low.contains("уклонен")) {
                if (p.dodgeRaw >= 95.0) {
                    ev.traps.add(String.format(Locale.US, "🚫 ОВЕРКАП: Уклонение уже %.1f%% при жёстком пределе Друида 95%% (+%.1f%% лишних)! Этот ролл даёт 0%% пользы — под переброс!", p.dodgeRaw, p.wastedDodgePct()));
                } else {
                    ev.totalScore += (val > 0 ? val : 8.0) * 1.5;
                    ev.pros.add("🛡️ Уклонение помогает добрать кап 95% Друида");
                }
                continue;
            }

            // 12. Снижение урона (софткап 95%)
            if (low.contains("снижение урона") || low.contains("получаемого урона")) {
                if (p.damageReductionRaw >= 95.0) {
                    ev.totalScore += 3.0;
                    ev.traps.add("ℹ️ Снижение урона уже 95%+ (выше 95% жесткая убывающая отдача, потолок <99%).");
                } else {
                    ev.totalScore += 18.0;
                    ev.pros.add("🛡️ Фиксированное Снижение урона (не слабеет с ростом уровня мобов до 95%!)");
                }
                continue;
            }

            // 13. ЛОВУШКА №3: Сила / Блок / Возмездие
            if (low.contains("возмезд") || low.contains("шанс блока") || (low.startsWith("сила") && !low.contains("талисман"))) {
                ev.traps.add("🚫 Мёртвый стат для Друида: Сила / Блок / Возмездие работают под Бастион/Воина, пет-друиду не дают DPS.");
                continue;
            }

            // 14. Дроп-аффиксы (Рубины, Руны, Предметы, Материалы, Яйца, Золото, Опыт)
            if (low.contains("рубин")) {
                double pts = val > 0 ? val : 10.0;
                ev.totalScore += pts * (isFarm ? 2.4 : 0.7);
                ev.pros.add(String.format(Locale.US, "♦️ +%.0f%% Шанс рубинов (пул рубинов наименее насыщен: +%.0f%% -> хорошая отдача!)", pts, p.rubyDropPct));
            } else if (low.contains("рун") && (low.contains("выпаден") || low.contains("дроп") || low.contains("шанс"))) {
                double pts = val > 0 ? val : 12.0;
                ev.totalScore += pts * (isFarm ? 1.8 : 0.5);
                ev.pros.add(String.format(Locale.US, "🔷 +%.0f%% Дроп рун (нужен для рунной пыли и Пробуждения ядер)", pts));
            } else if (low.contains("предметов") || low.contains("добыч")) {
                double pts = val > 0 ? val : 15.0;
                ev.totalScore += pts * (isFarm ? 1.4 : 0.4);
                ev.pros.add(String.format(Locale.US, "🎁 +%.0f%% Поиск предметов (сверх 100%% кормит бонус Схождения!)", pts));
            } else if (low.contains("материал")) {
                double pts = val > 0 ? val : 15.0;
                ev.totalScore += pts * (isFarm ? 1.1 : 0.3);
                ev.pros.add(String.format(Locale.US, "⚒️ +%.0f%% Материалы (свыше 100%% даёт доп. кол-во материалов для Кузницы)", pts));
            } else if (low.contains("опыт") || low.contains("золот")) {
                double pts = val > 0 ? val : 20.0;
                ev.totalScore += pts * (isFarm ? 0.8 : 0.25);
                ev.pros.add("🪙 Бонус золота/опыта для фонового фарма");
            }
        }

        if (ev.totalScore >= 85) ev.tierGrade = "S+ (ИДЕАЛ)";
        else if (ev.totalScore >= 52) ev.tierGrade = "S (ОТЛИЧНО)";
        else if (ev.totalScore >= 28) ev.tierGrade = "A (ХОРОШО)";
        else if (ev.totalScore >= 12) ev.tierGrade = "B (ПРОХОДНОЙ)";
        else ev.tierGrade = "C (МУСОР / В РАЗБОР)";

        return ev;
    }

    public static double extractFirstNumber(String s) {
        if (s == null) return 0.0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*([KkКкMmМм])?").matcher(s);
        double best = 0.0;
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1).replace(',', '.'));
                String suf = m.group(2);
                if (suf != null) {
                    String sl = suf.toLowerCase(Locale.ROOT);
                    if (sl.equals("k") || sl.equals("к")) v *= 1000.0;
                    else if (sl.equals("m") || sl.equals("м")) v *= 1000000.0;
                }
                best = v; // берём последнее/основное число на строке со значением справа
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    /** Персональный аудит текущего персонажа по данным профиля */
    public static String buildAuditReport(DruidProfile p) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌿 АУДИТ ПЕРСОНАЖА (Ур. ").append(p.charLevel)
          .append(" · GS ").append(p.gearScore).append(")\n\n");

        // 1. Проверка оверкапа уклонения
        if (p.wastedDodgePct() > 1.0) {
            sb.append("⚠️ 1. СГОРАЕТ УКЛОНЕНИЕ: у тебя ")
              .append(String.format(Locale.US, "%.1f%%", p.dodgeRaw))
              .append(" при жёстком капе Друида 95.0%! Целых ")
              .append(String.format(Locale.US, "+%.1f%%", p.wastedDodgePct()))
              .append(" уклонения работают ВПУСТУЮ. Смело перебрасывай Уклонение и Ловкость на шмоте/рунах в Двойной удар, Урон питомца или Интеллект.\n\n");
        } else {
            sb.append("✅ 1. Уклонение в норме (").append(String.format(Locale.US, "%.1f%% / 95%%", p.dodgeRaw)).append(").\n\n");
        }

        // 2. Сравнение ценности боевых статов (точная производная DPS)
        double dh10 = 10.0 * p.marginalGainPer1PctDoubleHit();
        double dd10 = 10.0 * p.marginalGainPer1PctDoubleDamage();
        double cr10 = 10.0 * p.marginalGainPer1PctCrit();
        double bs10 = 10.0 * p.marginalGainPer1PctBossDmg();
        sb.append("📊 2. ЧТО СЕЙЧАС ДАЁТ БОЛЬШЕ DPS (за каждые +10% стата):\n")
          .append(String.format(Locale.US, "  • Шанс двойного удара (%.0f%%): +%.2f%% итогового DPS [ТОП-1]\n", p.doubleHitChance, dh10))
          .append(String.format(Locale.US, "  • Шанс двойного урона (%.0f%%): +%.2f%% итогового DPS [ТОП-2]\n", p.doubleDamageChance, dd10))
          .append(String.format(Locale.US, "  • Крит шанс/урон (%.0f%%): +%.2f%% итогового DPS (в %.1f× слабее Дв. удара!)\n", p.critDamage, cr10, dh10 / Math.max(0.01, cr10)))
          .append(String.format(Locale.US, "  • Урон боссам (+%.0f%%): +%.2f%% урона в босс-файтах\n\n", p.bossDamage, bs10));

        // 3. Стойка и ловушка % Урона атаки
        sb.append("🐾 3. БОЕВАЯ СТОЙКА И ОРУЖИЕ:\n")
          .append("  • Множитель питомца ×").append(String.format(Locale.US, "%.2fM", p.petDamageMultMillions))
          .append(" -> используй ТОЛЬКО стойку «Повелитель зверей» («Гармония» требует 50/50 между героем и петом и режет урон чистым пет-друидам!).\n")
          .append("  • Двуручный посох (A6 +30 ★5 — супер!): качай его тир/уровень первым ради плоской базы (75% идёт в пета), но НИКОГДА не ролль «+% Урон атаки» — он не передаётся питомцу!\n\n");

        // 4. Снаряжение и талисманы по скриншоту экипировки
        sb.append("⚒️ 4. ЧТО ПОДТЯНУТЬ ПО СНАРЯЖЕНИЮ:\n")
          .append("  • Оба талисмана уже +9 — держи их равного ранга жертв и крути Автонадписи.\n")
          .append("  • Слоты A5 +5 (Факел, Амулет, Броня, Пояс ★5, Кольца, Перчатки, Сапоги): сначала поднимай до A6 Бижутерию и Перчатки (боевые статы/дроп), затем Броню/Пояс/Сапоги.\n")
          .append("  • Астральные катализаторы вливай в самые сильные дроп-ячейки (усиление +5% навсегда остаётся на САМОЙ ячейке при смене шмотки!).");

        return sb.toString();
    }

    /** Приоритеты Автонадписей, Питомцев и Рун */
    public static String buildInscriptionsAndPetsGuide(DruidProfile p) {
        return "✒️ ПРИОРИТЕТ АВТОНАДПИСЕЙ ТАЛИСМАНОВ (+9):\n"
             + "1. [S+] Все классовые навыки (если доступно в пуле) / Урон питомца %\n"
             + "2. [S+] Шанс двойного удара % (сейчас в 2.6× выгоднее крита!)\n"
             + "3. [S]  Шанс двойного урона %\n"
             + "4. [S]  Шанс доп. убийства % (для Фарм-набора №2 — ставь приоритетом №1!)\n"
             + "5. [A+] Интеллект / Урон боссам % (для Босс-пресета)\n"
             + "6. [A]  Крит. урон % / Шанс крита %\n"
             + "🚫 ИСКЛЮЧИТЬ из Автонадписей: +% Урон атаки, Уклонение (уже 131.7% > 95%), Блок, Возмездие, Сила.\n\n"
             + "🐾 ПИТОМЦЫ (КОМАНДА ИЗ 4 ПЕТОВ):\n"
             + "• Активный питомец (с молнией ⚡): Легендарный с максимальной базой и слиянием + включи «Автоподбор питомца под слабость» (навык «Единение с природой» даёт ещё +25% к слабости!).\n"
             + "• Гарант переброса аффиксов пета: крути осколки на петах до гаранта «Все классовые навыки», а когда он уже есть на пете — следующий гарант даст «Шанс доп. убийства»!\n"
             + "• Следи, чтобы все 4 надетых пета были прокачаны РАВНОМЕРНО (игра режет бонус по самому низкому +уровню в четвёрке!).\n\n"
             + "🔷 ПРЕСЕТЫ АКТИВНОСТЕЙ (БЕСПЛАТНО):\n"
             + "• Набор 1 (Пуш биомов/Башня): Повелитель зверей + макс. Урон пета / Двойной удар + Снижение урона 95%.\n"
             + "• Набор 2 (Фарм/Нашествия): Шанс доп. убийства + Рубины + Предметы + Яйца.\n"
             + "• Босс / Босс гильдии / Рейды Разлома: привяжи отдельный пресет с упором в Урон боссам и Двойной удар (игра применяет его на лету, не меняя надетый фарм-шмот!).";
    }

    /** Полный пошаговый гайд прокачки Друида от 1 уровня до эндгейма 324+ */
    public static String buildFullLevelingGuide() {
        return "🗺️ ПОЛНЫЙ ГАЙД ПРОКАЧКИ ДРУИДА: ОТ 1 УРОВНЯ ДО КАПА (324+)\n\n"
             + "━━ ЭТАП 1: УРОВНИ 1–35 (СТАРТ И ПЕРВЫЕ ПЕТЫ) ━━\n"
             + "• Сразу выбирай стойку «Повелитель зверей» и надевай двуручный посох с максимальной плоской атакой.\n"
             + "• Первые очки классовых навыков вкладывай в ветку «Хатка»:\n"
             + "  1) Погрыз (до 5–10 очк.) — базовый множитель пета +8%/ур.\n"
             + "  2) Строитель плотин (5/5) — даёт плоскую базу (+15/ур), которая на старте утраивает DPS.\n"
             + "  3) Открой Ранг 3 Хатки: Удар хвостом (5/5, двойная атака пета) + Хранитель хатки (5/5).\n\n"
             + "━━ ЭТАП 2: УРОВНИ 35–108 (ДО ЗАКРЫТИЯ ВСЕХ 18 НАВЫКОВ) ━━\n"
             + "• Качай ветку «Туннели» до Ранга 3:\n"
             + "  4) Землекоп (5/10) + Чуткий нюх (5/5) -> ради потока яиц питомцев (кап 15%) и материалов.\n"
             + "  5) Подкоп (5/5) -> МГНОВЕННАЯ КАЗНЬ мобов ниже 35% HP! Ускоряет зачистку всех биомов в 1.5×.\n"
             + "• Далее ветка «Симбиоз»:\n"
             + "  6) Родство (5–10) + Общий инстинкт (5/5) -> Одна душа (5/5, +12% урона пета за уровень!).\n"
             + "• ВАЖНАЯ ХИТРОСТЬ С «+ВСЕ КЛАССОВЫЕ НАВЫКИ»: как только ты собрал +15..+20 ко всем навыкам с петов/шмота, навыки с плашкой ЛИМИТ (Лесоповал 20 ур., Удар хвостом 20 ур., Подкоп 18 ур., Дикая связь+Родство 75%, Единение 9 ур.) достигают потолка уже с 1 вложенным очком! Пока очков мало (до ~108 ур.), держи в лимитных скиллах по 1 очку, а свободные кидай в безлимитные (Погрыз, Одна душа, Хранитель хатки, Землекоп). После ~110 ур. очков хватит замаксить все 18/18.\n\n"
             + "━━ ЭТАП 3: УРОВНИ 110–240 (МИДГЕЙМ, РУНЫ И КОМАНДА ПЕТОВ) ━━\n"
             + "• Добери жёсткие защитные пороги: Уклонение ровно 95% (кап Друида) и Снижение урона 95%.\n"
             + "• Набери 100%+ Шанса крита (весь избыток выше 100% конвертируется 1 к 1 в Крит. урон).\n"
             + "• Выводи яйца на максимальном доступном уровне монстров; настрой автопродажу петов через «Свои правила», оставляя Легендарных и аффиксы «Все классовые навыки» / «Шанс доп. убийства» / «Урон питомца».\n\n"
             + "━━ ЭТАП 4: УРОВНИ 240–350+ (ЭНДГЕЙМ A5–A6 / ИНФЕРНАЛ) ━━\n"
             + "• Подними оба Талисмана до +9 со всеми бонусами жертв -> открой Автонадписи.\n"
             + "• Перебалансируй атакующие статы: когда Крит. урон перевалил за 1500%, каждый 1% Двойного удара и Двойного урона даёт в 2–3 раза больше чистого прироста DPS, чем крит!\n"
             + "• Сними лишнее Уклонение сверх 95% и избегай «+% Урон атаки».\n"
             + "• Фарми Инфернальные (красные) предметы макс. уровня для Пробуждения осколками Инфернальных Ям и пробуждай руны Рунными ядрами.";
    }
}
