package com.idlearc.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Послотовый советник снаряжения Друида (IdleArc 1.3.15 · верифицировано по /api/config/game и libapp.so):
 * - 100% точные пулы префиксов [П] и суффиксов [С] для каждого из 9 слотов Друида + Талисман + Питомец;
 * - точные бонусы самоцветов (Нефрит / Лазурит / Гранат / Янтарь) по типам предметов и число сокетов;
 * - мгновенное определение открытого слота по карточке предмета.
 */
public class SlotAffixAdvisor {

    public enum GearSlot {
        WEAPON(
                "🪄 Оружие",
                "Двуручный посох Друида (Оружие · пул: 7 префиксов + 11 суффиксов)",
                "Корявая палка → Рунический посох → Дикий посох → Зачарованный посох → Посох природы → Древний посох → Рунная палка (A6 +30). До 75% плоской базы оружия передаётся в базу питомца («Родство» + «Дикая связь»).",
                new String[]{
                        "1. [S+ · П] Урон питомца % — единственный боевой префикс Оружия, БЕЗ аффикс-капа (на A6 +30 до +494% на [II]!)",
                        "2. [S+ · С] Шанс двойного удара % — каповый суффикс (~56% на A6 [I]), каждые 100% = гарантированный доп. удар",
                        "3. [S+ · С] Шанс двойного урона % — умножает урон И героя, И питомца (~56% на A6 [I])",
                        "4. [S  · С] Интеллект — БЕЗ аффикс-капа! 1 Инт = +0.20% Дв.урона + 0.38% Атаки + 0.05% Пета (на A6 [II] +247 Инт = +49.4% Дв.урона + 12.4% Пета!)",
                        "5. [A+ · С] Крит. урон % / Шанс крита % — игнорируют аффикс-кап (свыше 100% крит-шанс идёт 1:1 в Крит. урон)"
                },
                new String[]{
                        "Дроп-бонус ячейки: «Свирепый» (Урон питомца %) / «Проворный» (Дв. удар %) / «Смертоносный» (Дв. урон %) / для фарма «Удачливый» / «Богатый»",
                        "Фарм-суффиксы в пуле оружия: Бонус золота % и Бонус опыта %"
                },
                "3 гнезда: Нефрит (+0.5% урона питомца/ур — топ для чистого пета) или Лазурит (+0.25% урона питомца и атаки/ур); Янтарь (+2% золота/ур) в фарм-пресете.",
                "[П] Конверсия урона в стихию % (6 стихийных префиксов оружия меняют только удар самого героя, не пета); Ловкость (в ~7× слабее Интеллекта по DPS при капе уклонения 95%); Сила.",
                "★ ПРОБУЖДЕНИЕ [I–V]: #1 [П] Урон питомца % -> #2 [С] Двойной удар % / Крит. урон %"
        ),
        TORCH(
                "🔥 Факел",
                "Факел (Вторая рука Друида · пул: строго 3 префикса + 6 суффиксов = 9 аффиксов)",
                "Факел / Пламенный факел / Адский факел (при надетом 2H посохе слот второй руки занят, но Факел работает на 1H-сборках или лежит в сумке; даёт 4 гнезда самоцветов — максимум в игре!).",
                new String[]{
                        "1. [S+ · П] Урон питомца % — главный префикс Факела, БЕЗ аффикс-капа (на A5 [III] до +424%!)",
                        "2. [S+ · С] Шанс двойного урона % — умножает урон и героя, и питомца (~47% на A5 [I])",
                        "3. [S  · С] Крит. урон % — игнорирует аффикс-кап (до ~+115% без пробуждения на A5)",
                        "4. [A+ · С] Шанс крита % — игнорирует аффикс-кап; весь шанс свыше 100% конвертируется 1:1 в Крит. урон!",
                        "5. [A  · С] Здоровье за удар и за убийство % — ЭКСКЛЮЗИВ Факела (кап 15% + 1%×A = 20% на A5), либо [П] Снижение урона % (если без Факела DR < 95%)"
                },
                new String[]{
                        "ВАЖНО (по серверному конфигу игры): на Факеле НЕТ Шанса двойного удара и НЕТ Интеллекта/Ловкости/Силы!",
                        "Фарм-суффиксы Факела: Бонус золота % и Бонус опыта % + дроп-бонус ячейки"
                },
                "4 гнезда (максимум в игре!): Лазурит (+0.3% урона питомца/ур × 4) — топ на урон; Гранат (+0.6% макс. HP/ур × 4) — выживаемость; Янтарь — фарм.",
                "Уклонение % при перекапе >95% (даёт 0% пользы); Снижение урона % если уже набрано 95%.",
                "★ ПРОБУЖДЕНИЕ [I–V]: #1 [П] Урон питомца % -> #2 [С] Дв. урон % / Крит. урон % (пул = 9 аффиксов)"
        ),
        HELMET(
                "🪖 Шлем",
                "Шлем (Голова · пул: 6 префиксов + 5 суффиксов = 11 аффиксов)",
                "Рогатый шлем / Корона Предводителя / Рунный шлем (A5–A6). Внимание: в пуле брони (Шлем/Нагрудник/Перчатки/Сапоги) НЕТ характеристик (Инт/Ловк/Сила) и Двойного удара, зато ЕСТЬ Урон питомца %, Двойной урон % и Крит!",
                new String[]{
                        "1. [S+ · П] Урон питомца % — главный боевой префикс Шлема, БЕЗ капа!",
                        "2. [S+ · С] Шанс двойного урона % — умножает урон и героя, и питомца",
                        "3. [S  · С] Крит. урон % — игнорирует аффикс-кап",
                        "4. [A+ · С] Шанс крита % — игнорирует аффикс-кап (свыше 100% идёт 1:1 в Крит. урон)",
                        "5. [A  · П] Снижение урона % (если общее DR < 95%) или Макс. здоровье (плоское, если DR уже 95%)"
                },
                new String[]{
                        "Дроп-бонус ячейки: Поиск предметов % / Рубины % / Материалы % / Руны %",
                        "Фарм-суффиксы Шлема: Бонус золота % и Бонус опыта %"
                },
                "2 гнезда брони: Нефрит (+0.6% макс. HP/ур) для выживаемости, Гранат (+0.6% защиты/ур) или Лазурит (+2% опыта/ур).",
                "Шанс уклонения % при перекапе >95%; Снижение урона % сверх 95%; Доп. защита / Увеличенная защита % вместо атакующих топ-4.",
                "★ ПРОБУЖДЕНИЕ [I–V]: #1 [П] Урон питомца % -> #2 [С] Дв. урон % / Крит. урон % (пул = 11 аффиксов)"
        ),
        CHEST(
                "🛡️ Броня",
                "Нагрудник (Броня · пул: 6 префиксов + 5 суффиксов = 11 аффиксов)",
                "Мантия Друида / Шкура Зверя / Рунный доспех (A5–A6). Самая большая база Защиты среди всех слотов; пул вторичных аффиксов идентичен Шлему (11 аффиксов).",
                new String[]{
                        "1. [S+ · П] Урон питомца % — главный боевой префикс Брони, БЕЗ капа!",
                        "2. [S+ · С] Шанс двойного урона % — умножает урон и героя, и питомца",
                        "3. [S  · С] Крит. урон % — игнорирует аффикс-кап",
                        "4. [A+ · С] Шанс крита % — игнорирует аффикс-кап (свыше 100% = 1:1 Крит. урон)",
                        "5. [A  · П] Снижение урона % (ключевой слот для удержания капа 95%!) или Макс. здоровье"
                },
                new String[]{
                        "Дроп-бонус ячейки: «Укреплённый» (Макс. здоровье %) / «Каменный» (Снижение урона %) для пуша или Дроп % для фарма",
                        "Фарм-суффиксы Брони: Бонус золота % и Бонус опыта %"
                },
                "2 гнезда брони: Нефрит (+0.6% макс. HP/ур) или Гранат (+0.6% защиты/ур).",
                "Шанс уклонения % при перекапе >95% (сейчас в профиле 131.7% -> даёт 0% выживаемости!).",
                "★ ПРОБУЖДЕНИЕ [I–V]: #1 [П] Урон питомца % -> #2 [С] Дв. урон % / Крит. урон % (пул = 11 аффиксов)"
        ),
        GLOVES(
                "🧤 Перчатки",
                "Перчатки (Руки · пул: 5 префиксов + 5 суффиксов = 10 аффиксов)",
                "Обмотки Дикаря / Перчатки Ловчего / Рунные рукавицы (встроенная скорость атаки базы). В отличие от Шлема и Брони, в пуле Перчаток НЕТ «Снижения урона %»!",
                new String[]{
                        "1. [S+ · П] Урон питомца % — главный боевой префикс Перчаток, БЕЗ капа!",
                        "2. [S+ · С] Шанс двойного урона % — умножает урон и героя, и питомца",
                        "3. [S  · С] Крит. урон % — игнорирует аффикс-кап",
                        "4. [A+ · С] Шанс крита % — игнорирует аффикс-кап (свыше 100% = 1:1 Крит. урон)",
                        "5. [B+ · П] Макс. здоровье (плоское) или Увеличенная защита % (так как Снижения урона % на Перчатках нет)"
                },
                new String[]{
                        "Дроп-бонус ячейки: Урон питомца % / Двойной удар % / Двойной урон % или Поиск предметов % / Рубины %",
                        "Фарм-суффиксы Перчаток: Бонус золота % и Бонус опыта %"
                },
                "2 гнезда брони: Нефрит (+0.6% макс. HP/ур) или Лазурит (+2% опыта/ур).",
                "Шанс уклонения % при перекапе >95%; помни, что Интеллект и Двойной удар во вторичных аффиксах Перчаток не выпадают.",
                "★ ПРОБУЖДЕНИЕ [I–V]: #1 [П] Урон питомца % -> #2 [С] Дв. урон % / Крит. урон % (пул = 10 аффиксов)"
        ),
        BOOTS(
                "🥾 Сапоги",
                "Сапоги (Ноги · пул: 5 префиксов + 5 суффиксов = 10 аффиксов)",
                "Сапоги Быстрого Шага / Поножи Следопыта / Рунные сапоги (дают встроенный имплисит Шанса двойного удара % или Уклонения на самой базе, но во вторичных 5 аффиксах пул из 10 статов идентичен Перчаткам!).",
                new String[]{
                        "1. [S+ · П] Урон питомца % — главный боевой префикс Сапог, БЕЗ капа!",
                        "2. [S+ · С] Шанс двойного урона % — умножает урон и героя, и питомца",
                        "3. [S  · С] Крит. урон % — игнорирует аффикс-кап",
                        "4. [A+ · С] Шанс крита % — игнорирует аффикс-кап (свыше 100% = 1:1 Крит. урон)",
                        "5. [B+ · П] Макс. здоровье (плоское) или Увеличенная защита % (Снижения урона % в пуле Сапог нет)"
                },
                new String[]{
                        "База Сапог: выбирай базу со встроенным Шансом двойного удара %, а НЕ с Уклонением (если уклонение уже >= 95%)",
                        "Фарм-суффиксы Сапог: Бонус золота % и Бонус опыта %"
                },
                "2 гнезда брони: Нефрит (+0.6% макс. HP/ур) или Лазурит (+2% опыта/ур).",
                "Шанс уклонения % при перекапе >95% (снимай лишнее уклонение на Сапогах первым делом!).",
                "★ ПРОБУЖДЕНИЕ [I–V]: #1 [П] Урон питомца % -> #2 [С] Дв. урон % / Крит. урон % (пул = 10 аффиксов)"
        ),
        BELT(
                "🎗️ Пояс",
                "Пояс (Талия · категория «Бижутерия» в игре! Пул: 2 префикса + 11 суффиксов = 13 аффиксов)",
                "Кушак Гиганта / Ремень Стойкости / Рунный пояс (★5). ВАЖНОЕ ОТКРЫТИЕ ИЗ КОНФИГА ИГРЫ: Пояс в IdleArc относится к категории Jewelry — на нём ВЫПАДАЮТ Урон питомца %, Двойной удар %, Двойной урон %, Интеллект и Крит, а гнезда дают бонусы бижутерии (+5 Инт/ур от Лазурита)!",
                new String[]{
                        "1. [S+ · П] Урон питомца % — главный префикс Пояса, БЕЗ капа!",
                        "2. [S+ · С] Шанс двойного удара % — полноценный боевой прок бижутерии!",
                        "3. [S+ · С] Шанс двойного урона % — умножает урон и героя, и питомца",
                        "4. [S  · С] Интеллект — БЕЗ капа (+0.20% Дв.урона + 0.05% Пета за очко!)",
                        "5. [A+ · С] Крит. урон % / Шанс крита % (или [П] Макс. здоровье, если нужна банка HP)"
                },
                new String[]{
                        "Дроп-бонус ячейки: Урон питомца % / Дв. удар % / Дв. урон % или Рубины % / Материалы % / Предметы %",
                        "На Поясе НЕТ Снижения урона %, Уклонения % и Здоровья за удар/убийство!"
                },
                "2 гнезда бижутерии: Лазурит (+5 Интеллекта/ур × 2 — топ!) или Нефрит (+5 Ловкости/ур × 2).",
                "Сила, Ловкость (слабее Интеллекта в ~7× по DPS при капе уклонения 95%), Защита % вместо атакующих суффиксов.",
                "★ ПРОБУЖДЕНИЕ [I–V]: #1 [П] Урон питомца % -> #2 [С] Дв. удар % / Интеллект (пул = 13 аффиксов)"
        ),
        AMULET(
                "📿 Амулет",
                "Амулет (Шея · пул: 2 префикса + 13 суффиксов = 15 аффиксов)",
                "Ожерелье Затмения / Печать Пустоты (встроенный Урон боссам % + Урон атаки %) — топ база для пуша и боссов! Альтернатива: Медальон Хищника (Шанс крита % + Крит. урон %).",
                new String[]{
                        "1. [S+ · П] Урон питомца % — главный префикс Амулета, БЕЗ капа!",
                        "2. [S+ · С] Шанс двойного удара % — топ боевой суффикс бижутерии",
                        "3. [S+ · С] Шанс двойного урона % — умножает урон и героя, и питомца",
                        "4. [S  · С] Интеллект — БЕЗ капа (+0.20% Дв.урона + 0.05% Пета за очко!)",
                        "5. [A+ · С] Крит. урон % / Шанс крита % — без аффикс-капа"
                },
                new String[]{
                        "Дроп-бонус ячейки (для Фарм-набора №2): «Удачливый» (Предметы %), «Рубиновый» (Рубины %), «Рунный» (Руны %), «Изобильный» (Шанс доп. убийства %)",
                        "Все 5 позиций Амулета можно собрать на 100% из чистых DPS-множителей!"
                },
                "1 гнездо бижутерии: Лазурит (+5 Интеллекта/ур) — даёт Двойной урон и Урон пета без потерь на оверкап уклонения.",
                "Сила, Ловкость (при капе уклонения 95%), Защита %, плоское Здоровье за удар/убийство на позднем этапе.",
                "★ ПРОБУЖДЕНИЕ [I–V]: #1 [П] Урон питомца % -> #2 [С] Дв. удар % / Крит. урон % (пул = 15 аффиксов)"
        ),
        RING(
                "💍 Кольцо",
                "Кольцо ×2 (Левое и Правое · пул: 2 префикса + 13 суффиксов = 15 аффиксов)",
                "Боевой набор: 2× Кольцо Когтя / Перстень Ярости (встроенный Крит. урон %). Фарм-набор: 2× Печатка Удачи (встроенный бонус золота/дропа).",
                new String[]{
                        "1. [S+ · П] Урон питомца % — главный префикс Кольца, БЕЗ капа!",
                        "2. [S+ · С] Шанс двойного удара % — топ боевой суффикс",
                        "3. [S+ · С] Шанс двойного урона % — умножает урон и героя, и питомца",
                        "4. [S  · С] Интеллект — БЕЗ капа (+0.20% Дв.урона + 0.05% Пета за очко!)",
                        "5. [A+ · С] Крит. урон % / Шанс крита % — без аффикс-капа"
                },
                new String[]{
                        "Держи отдельную пару фарм-колец во 2-м бесплатном Пресете активностей (Предметы % + Рубины % + Золото % + Опыт %)"
                },
                "По 1 гнезду в каждом кольце: Лазурит (+5 Интеллекта/ур).",
                "Сила, Ловкость (при уклонении >= 95%), Защита %.",
                "★ ПРОБУЖДЕНИЕ [I–V]: #1 [П] Урон питомца % -> #2 [С] Дв. удар % / Крит. урон % (пул = 15 аффиксов)"
        ),
        TALISMAN(
                "🔮 Талисман",
                "Талисманы ×2 (Уровень +9 и Автонадписи)",
                "Два талисмана максимального ранга +9 с полностью закрытыми бонусами жертв (прокачивай оба синхронно!).",
                new String[]{
                        "1. [S+] Все классовые навыки / Урон питомца % — абсолютный приоритет №1",
                        "2. [S+] Шанс двойного удара %",
                        "3. [S+] Шанс двойного урона %",
                        "4. [S]  Урон боссам % (в Босс-пресете) / Интеллект",
                        "5. [A]  Крит. урон % / Шанс крита %"
                },
                new String[]{
                        "В Фарм-талисмане Автонадпись №1: Шанс доп. убийства % (удваивает весь лут, опыт и золото с моба!)",
                        "Далее: Шанс рубинов % + Поиск предметов % + Дроп рун %"
                },
                "Жертвы талисмана: закрывай все ступени равномерно на обоих талисманах для открытия слотов надписей.",
                "+% Урон атаки (0% пользы питомцу!), Шанс уклонения % (оверкап 131.7% > 95%), Сила, Возмездие, Блок.",
                "★ ТАЛИСМАНЫ +9: качай оба синхронно и крути Автонадписи на Урон пета / Дв. удар / Доп. убийство"
        ),
        PET(
                "🐾 Питомец",
                "Команда из 4 Питомцев (1 Активный ⚡ + 3 в поддержке)",
                "Легендарные питомцы максимального уровня слияния (★) и РАВНОЙ заточки +уровня у всей четвёрки!",
                new String[]{
                        "1. [S+ ГАРАНТ №1] +Все классовые навыки — крути осколки переброса до гаранта в первую очередь!",
                        "2. [S+ ГАРАНТ №2] +Шанс доп. убийства % — если «Все классовые навыки» уже есть на пете, следующий гарант даст Доп. убийство!",
                        "3. [S]  Урон питомца % / Мастерство питомцев",
                        "4. [A+] Шанс двойного удара % / Шанс двойного урона % / Урон боссам %"
                },
                new String[]{
                        "Яйца питомцев: выводи на максимальном открытом уровне монстров; включи «Автоподбор питомца под слабость» (+25% от навыка «Единение с природой»!)"
                },
                "ВАЖНО: держи одинаковый +уровень у всех 4 надетых петов — командный бонус ограничивается САМЫМ НИЗКИМ уровнем в четвёрке!",
                "Не вливай все ресурсы в одного пета +50 при остальных +10; не оставляй на петах +% Урон атаки или лишнее Уклонение.",
                "★ ЭВОЛЮЦИЯ И СЛИЯНИЕ: Особый бонус -> «Урон питомца» + гарант «Все классовые навыки»"
        );

        public final String shortChip;
        public final String slotTitle;
        public final String bestBaseRu;
        public final String[] topAffixesRu;
        public final String[] dropAffixesRu;
        public final String gemsRu;
        public final String avoidRu;
        public final String awakenHeadline;

        GearSlot(String shortChip, String slotTitle, String bestBaseRu,
                 String[] topAffixesRu, String[] dropAffixesRu, String gemsRu, String avoidRu,
                 String awakenHeadline) {
            this.shortChip = shortChip;
            this.slotTitle = slotTitle;
            this.bestBaseRu = bestBaseRu;
            this.topAffixesRu = topAffixesRu;
            this.dropAffixesRu = dropAffixesRu;
            this.gemsRu = gemsRu;
            this.avoidRu = avoidRu;
            this.awakenHeadline = awakenHeadline;
        }
    }

    /**
     * Проверяет по шапке карточки, является ли предмет неклассовым Щитом / Книгой / Колчаном (`type="shield"`),
     * у которого пул состоит из 8 аффиксов (как у Факела, но БЕЗ `of Bloodthirst`).
     */
    public static boolean isShieldHeaderLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return false;
        int limit = Math.min(lines.size(), 6);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            String low = DruidKnowledgeBase.normalizeForMatch(lines.get(i));
            if (low.contains("аффиксы") || low.contains("характеристик")) break;
            sb.append(' ').append(low);
        }
        String h = sb.toString();
        return hasWord(h, "щит") || hasStem(h, "колчан") || hasWord(h, "книга") || hasStem(h, "фолиант") || hasStem(h, "гримуар");
    }

    /**
     * Геометрически точное определение слота по строкам с координатами Y внутри модалки.
     */
    public static GearSlot detectSlotFromModalLines(List<IdleArcOcrEngine.OcrLine> ocrLines) {
        if (ocrLines == null || ocrLines.isEmpty()) return null;
        List<String> texts = new ArrayList<>(ocrLines.size());
        for (IdleArcOcrEngine.OcrLine l : ocrLines) {
            texts.add(l.text);
        }
        return detectSlotFromLines(texts);
    }

    /**
     * Определяет слот открытой карточки предмета по распознанным строкам.
     */
    public static GearSlot detectSlotFromLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        StringBuilder all = new StringBuilder();
        for (String s : lines) all.append(' ').append(DruidKnowledgeBase.normalizeForMatch(s));
        String full = all.toString();

        if (hasStem(full, "автонадпис") || hasStem(full, "жертв")
                || (hasStem(full, "надпис") && hasStem(full, "талисман"))) {
            return GearSlot.TALISMAN;
        }
        if ((hasStem(full, "автоподбор") && hasStem(full, "слабост"))
                || (hasStem(full, "слиян") && hasStem(full, "питом"))
                || hasStem(full, "инкубац")) {
            return GearSlot.PET;
        }

        // Шаг 1: Ищем точное название подзаголовка слота в первых строках шапки карточки (до «Характеристики»/«Аффиксы»)
        StringBuilder headerBuilder = new StringBuilder();
        int headerLimit = Math.min(lines.size(), 7);
        for (int i = 0; i < headerLimit; i++) {
            String low = DruidKnowledgeBase.normalizeForMatch(lines.get(i));
            if (low.contains("аффиксы") || low.equals("характеристики")
                    || low.startsWith("характеристики ") || low.contains("пустая позиция")) {
                break;
            }
            if (hasStem(low, "талисман")) return GearSlot.TALISMAN;
            GearSlot exactLineSlot = matchSlotCategorySubtitle(low);
            if (exactLineSlot != null) return exactLineSlot;
            headerBuilder.append(' ').append(low);
        }

        String topHeader = headerBuilder.toString().trim();
        GearSlot fromTopSub = matchSlotCategorySubtitle(topHeader);
        if (fromTopSub != null) return fromTopSub;
        GearSlot fromTopBase = matchItemBaseDictionary(topHeader);
        if (fromTopBase != null) return fromTopBase;

        // Шаг 2: Проверяем строку сравнения экипировки и футер карточки («Надето: Оружие», «Двуручное · занимает слот второй руки»)
        for (String raw : lines) {
            String low = DruidKnowledgeBase.normalizeForMatch(raw);
            if ((low.contains("двуруч") || low.contains("занимает слот")) && low.contains("рук")) {
                return GearSlot.WEAPON;
            }
            if (low.contains("надето") || low.contains("слот:") || low.contains("тип:")) {
                GearSlot footerSlot = matchSlotCategorySubtitle(low);
                if (footerSlot != null) return footerSlot;
                if (low.contains("оруж") || low.contains("сруж")) return GearSlot.WEAPON;
                if (low.contains("факел") || low.contains("акел")) return GearSlot.TORCH;
            }
        }

        // Шаг 3: Расширенный заголовок (до 10 строк, если карточка крупная)
        StringBuilder widerHeader = new StringBuilder();
        int wideLimit = Math.min(lines.size(), 10);
        for (int i = 0; i < wideLimit; i++) {
            String low = DruidKnowledgeBase.normalizeForMatch(lines.get(i));
            if (low.contains("аффиксы") || low.contains("пустая позиция") || low.contains("пробужден")) break;
            widerHeader.append(' ').append(low);
        }
        String head = widerHeader.toString().trim();
        if (hasStem(head, "талисман")) return GearSlot.TALISMAN;
        GearSlot exactCategory = matchSlotCategorySubtitle(head);
        if (exactCategory != null) return exactCategory;
        GearSlot byBaseName = matchItemBaseDictionary(head);
        if (byBaseName != null) return byBaseName;

        // Шаг 4: Уникальная сигнатура Факела — «Здоровье за удар и за убийство %» существует ТОЛЬКО на Факеле!
        if (full.contains("за удар и за убийств") || full.contains("удар и за убийств")) {
            return GearSlot.TORCH;
        }
        return null;
    }

    /** Проверяет, есть ли в строке отдельное слово с данным префиксом */
    private static boolean hasStem(String text, String stem) {
        if (text == null || stem == null || stem.isEmpty()) return false;
        int idx = 0;
        while ((idx = text.indexOf(stem, idx)) >= 0) {
            boolean leftOk = (idx == 0) || !Character.isLetterOrDigit(text.charAt(idx - 1));
            if (leftOk) return true;
            idx += 1;
        }
        return false;
    }

    /** Проверяет точное совпадение отдельного слова */
    private static boolean hasWord(String text, String word) {
        if (text == null || word == null || word.isEmpty()) return false;
        int idx = 0;
        int wLen = word.length();
        while ((idx = text.indexOf(word, idx)) >= 0) {
            boolean leftOk = (idx == 0) || !Character.isLetterOrDigit(text.charAt(idx - 1));
            boolean rightOk = (idx + wLen >= text.length()) || !Character.isLetterOrDigit(text.charAt(idx + wLen));
            if (leftOk && rightOk) return true;
            idx += 1;
        }
        return false;
    }

    /**
     * Точные подзаголовки категорий предметов в шапке карточки IdleArc (`get:itemType*` из libapp.so):
     * «Оружие», «Факел» / «Вторая рука» / «Щит», «Шлем», «Броня», «Перчатки», «Сапоги», «Пояс», «Амулет», «Кольцо».
     */
    private static GearSlot matchSlotCategorySubtitle(String h) {
        if (h == null || h.isEmpty()) return null;
        if ((h.contains("двуруч") || h.contains("занимает слот")) && h.contains("рук")) {
            return GearSlot.WEAPON;
        }
        if (hasWord(h, "оружие") || hasWord(h, "сружие") || hasWord(h, "оруж") || hasWord(h, "двуручное") || hasWord(h, "одноручное")) {
            return GearSlot.WEAPON;
        }
        if (hasWord(h, "факел") || hasWord(h, "акел") || hasWord(h, "оффхенд") || hasWord(h, "щит")
                || (hasWord(h, "вторая") && hasWord(h, "рука") && !h.contains("занимает"))) {
            return GearSlot.TORCH;
        }
        if (hasWord(h, "перчатки")) return GearSlot.GLOVES;
        if (hasWord(h, "сапоги") || hasWord(h, "обувь")) return GearSlot.BOOTS;
        if (hasWord(h, "шлем")) return GearSlot.HELMET;
        if (hasWord(h, "броня") || hasWord(h, "нагрудник")) return GearSlot.CHEST;
        if (hasWord(h, "пояс")) return GearSlot.BELT;
        if (hasWord(h, "амулет")) return GearSlot.AMULET;
        if (hasWord(h, "кольцо")) return GearSlot.RING;
        return null;
    }

    /**
     * Полный словарь всех 45 базовых предметов IdleArc (T1..T6 из /api/config/game + item_variants).
     */
    private static GearSlot matchItemBaseDictionary(String h) {
        if (h == null || h.isEmpty()) return null;
        // 1. Оружие Друида и других классов
        if (hasStem(h, "посох") || hasStem(h, "палка") || hasStem(h, "палиц")
                || hasStem(h, "жезл") || hasStem(h, "скипетр") || hasStem(h, "трость")
                || hasStem(h, "ветв") || hasStem(h, "древк")) {
            return GearSlot.WEAPON;
        }
        // 2. Вторая рука (Факел Друида + неклассовые щиты/книги/колчаны)
        if (hasStem(h, "факел") || hasWord(h, "акел") || hasStem(h, "светоч") || hasStem(h, "фонар")
                || hasStem(h, "пламя") || hasStem(h, "лампа") || hasStem(h, "жаровн")
                || hasWord(h, "щит") || hasStem(h, "колчан") || hasWord(h, "книга")
                || hasStem(h, "фолиант") || hasStem(h, "гримуар") || hasStem(h, "тотем")) {
            return GearSlot.TORCH;
        }
        // 3. Остальное оружие (мечи, луки, топоры, кинжалы, молоты, косы, арбалеты)
        if (hasWord(h, "меч") || hasStem(h, "клинок") || hasStem(h, "сабл") || hasStem(h, "палаш")
                || hasStem(h, "двуручник") || hasStem(h, "клеймор")
                || hasWord(h, "лук") || hasStem(h, "арбалет")
                || hasStem(h, "топор") || hasStem(h, "секир") || hasStem(h, "колун")
                || hasStem(h, "кинжал") || hasStem(h, "нож") || hasStem(h, "стилет")
                || hasStem(h, "молот") || hasStem(h, "булав") || hasStem(h, "моргенштерн")
                || hasStem(h, "копье") || hasStem(h, "алебард") || hasStem(h, "коса")) {
            return GearSlot.WEAPON;
        }
        // 4. Перчатки
        if (hasStem(h, "перчатк") || hasStem(h, "рукавиц") || hasStem(h, "обмотк")
                || hasStem(h, "краги") || hasStem(h, "наруч") || hasStem(h, "захват")
                || hasStem(h, "латные руки")) {
            return GearSlot.GLOVES;
        }
        // 5. Сапоги
        if (hasStem(h, "сапог") || hasStem(h, "ботин") || hasStem(h, "понож")
                || hasStem(h, "башмак") || hasStem(h, "ботфорт") || hasStem(h, "сандал")
                || hasStem(h, "туфл") || hasStem(h, "ступн")) {
            return GearSlot.BOOTS;
        }
        // 6. Пояс
        if (hasStem(h, "пояс") || hasStem(h, "ремен") || hasStem(h, "кушак")
                || hasStem(h, "перевяз") || hasStem(h, "опояс")) {
            return GearSlot.BELT;
        }
        // 7. Амулет
        if (hasStem(h, "амулет") || hasStem(h, "ожерел") || hasStem(h, "медальон")
                || hasStem(h, "подвеск") || hasStem(h, "кулон") || hasStem(h, "колье")) {
            return GearSlot.AMULET;
        }
        // 8. Кольцо
        if (hasStem(h, "кольц") || hasStem(h, "перстен") || hasStem(h, "печатк")
                || hasStem(h, "обод")) {
            return GearSlot.RING;
        }
        // 9. Шлем
        if (hasStem(h, "шлем") || hasStem(h, "капюшон") || hasStem(h, "корон")
                || hasStem(h, "диадем") || hasStem(h, "маск") || hasStem(h, "шапк")
                || hasStem(h, "тиар") || hasStem(h, "венец") || hasStem(h, "череп")
                || hasStem(h, "каск") || hasStem(h, "забрал")) {
            return GearSlot.HELMET;
        }
        // 10. Броня / Нагрудник
        if (hasStem(h, "брон") || hasStem(h, "нагруд") || hasStem(h, "доспех")
                || hasStem(h, "манти") || hasStem(h, "кирас") || hasStem(h, "туник")
                || hasStem(h, "жилет") || hasStem(h, "ряса") || hasStem(h, "облачен")
                || hasStem(h, "шкура") || hasStem(h, "латы") || hasStem(h, "кольчуг")
                || hasStem(h, "мундир") || hasStem(h, "одеяни")) {
            return GearSlot.CHEST;
        }
        return null;
    }

    /**
     * Формирует подробную карточку рекомендаций по выбранному слоту с учётом текущих статов Друида.
     */
    public static String formatSlotGuide(GearSlot slot, DruidProfile p) {
        boolean isFarm = (p.mode == DruidProfile.BuildMode.FARM);
        StringBuilder sb = new StringBuilder();
        sb.append("🎯 СЛОТ: ").append(slot.slotTitle).append("\n\n");

        sb.append("🏆 ЛУЧШАЯ БАЗА ПРЕДМЕТА:\n• ").append(slot.bestBaseRu).append("\n\n");

        sb.append(isFarm
                ? "💎 ФАРМ-РЕЖИМ — ДРОП И СПЕЦ-СВОЙСТВА СЛОТА:\n"
                : "⚔️ ТОП-5 РЕАЛЬНЫХ АФФИКСОВ В ПУЛЕ ЭТОГО СЛОТА ([П]=Префикс, [С]=Суффикс):\n");

        if (isFarm) {
            for (String d : slot.dropAffixesRu) {
                sb.append("• ").append(d).append("\n");
            }
            sb.append("\n⚔️ БОЕВЫЕ АФФИКСЫ В ПУЛЕ СЛОТА (основной пресет):\n");
            for (String a : slot.topAffixesRu) {
                sb.append("• ").append(a).append("\n");
            }
        } else {
            for (String a : slot.topAffixesRu) {
                sb.append("• ").append(a).append("\n");
            }
            sb.append("\n✨ ДРОП-БОНУС ЯЧЕЙКИ И ФАРМ-ОПЦИИ:\n");
            for (String d : slot.dropAffixesRu) {
                sb.append("• ").append(d).append("\n");
            }
        }

        sb.append("\n💎 САМОЦВЕТЫ В ГНЁЗДА:\n• ").append(slot.gemsRu).append("\n\n");
        sb.append("🚫 ЧЕГО ИЗБЕГАТЬ НА ЭТОМ СЛОТЕ:\n• ").append(slot.avoidRu);

        // Персональная поправка по текущим капам профиля игрока
        if ((slot == GearSlot.BOOTS || slot == GearSlot.CHEST || slot == GearSlot.HELMET
                || slot == GearSlot.GLOVES || slot == GearSlot.TORCH) && p.dodgeRaw >= 95.0) {
            sb.append(String.format(Locale.US,
                    "\n\n⚠️ ТВОЙ ПРОФИЛЬ: Уклонение сейчас %.1f%% (жёсткий кап Друида 95%% -> +%.1f%% лишних!). Смело убирай «Шанс уклонения %%» с этого слота в Урон питомца / Двойной урон / Крит!",
                    p.dodgeRaw, p.wastedDodgePct()));
        }
        if ((slot == GearSlot.CHEST || slot == GearSlot.HELMET || slot == GearSlot.TORCH)
                && p.damageReductionRaw >= 95.0) {
            sb.append(String.format(Locale.US,
                    "\nℹ️ ТВОЙ ПРОФИЛЬ: Снижение урона %.1f%% (кап 95%% закрыт). Следи только, чтобы при замене предмета общее Снижение урона не упало ниже 95%%!",
                    p.damageReductionRaw));
        }

        return sb.toString();
    }

    /**
     * Сравнивает реально надетый/отсканированный предмет с идеалом для его слота.
     */
    public static String buildScannedItemSlotAdvice(GearSlot slot, List<String> lines, DruidProfile p) {
        StringBuilder all = new StringBuilder();
        for (String s : lines) all.append(' ').append(DruidKnowledgeBase.normalizeForMatch(s));
        String full = all.toString();

        List<String> hits = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean isFarm = (p.mode == DruidProfile.BuildMode.FARM);

        boolean hasPetDmg = full.contains("урон питомц") || full.contains("урона питомц") || full.contains("урон пит");
        boolean hasDH = full.contains("двойного удара") || full.contains("двойной удар");
        boolean hasDD = full.contains("двойного урона") || full.contains("двойной урон");
        boolean hasInt = full.contains("интеллект");
        boolean hasCrit = full.contains("крит");
        boolean hasAllSkills = full.contains("классовые навыки") || full.contains("классовым навыкам");
        boolean hasExtraKill = full.contains("доп. убийств") || full.contains("доп убийств");
        boolean hasDR = full.contains("снижение урона");
        boolean hasBloodthirst = full.contains("за удар и за убийств");

        boolean hasDHAndIntInPool = (slot == GearSlot.WEAPON || slot == GearSlot.BELT
                || slot == GearSlot.AMULET || slot == GearSlot.RING);

        if (slot == GearSlot.PET || slot == GearSlot.TALISMAN) {
            if (hasAllSkills) hits.add("Все классовые навыки");
            else missing.add("Все классовые навыки (S+ приоритет №1!)");
            if (isFarm && !hasExtraKill) missing.add("Шанс доп. убийства % (топ-1 для фарма!)");
        } else {
            if (hasPetDmg) hits.add("[П] Урон питомца %");
            else missing.add("[П] Урон питомца % (главный префикс слота, без капа!)");

            if (hasDHAndIntInPool) {
                if (hasDH) hits.add("[С] Двойной удар %");
                else missing.add("[С] Шанс двойного удара %");
            }

            if (hasDD) hits.add("[С] Двойной урон %");
            else missing.add("[С] Шанс двойного урона %");

            if (hasDHAndIntInPool) {
                if (hasInt) hits.add("[С] Интеллект");
                else missing.add("[С] Интеллект (+0.2% Дв.урона/очко без капа!)");
            } else {
                if (hasCrit) hits.add("[С] Крит %");
                else missing.add("[С] Крит. урон % / Шанс крита %");
            }

            if (slot == GearSlot.TORCH && hasBloodthirst) {
                hits.add("[С] Здоровье за удар/убийство %");
            }
            if ((slot == GearSlot.CHEST || slot == GearSlot.HELMET || slot == GearSlot.TORCH) && hasDR) {
                hits.add("[П] Снижение урона %");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🧩 СВЕРКА СО СЛОТОМ «").append(slot.shortChip).append("»:\n");
        if (!hits.isEmpty()) {
            sb.append("• Уже поймано из BiS-пула слота: ").append(joinList(hits)).append(".\n");
        }
        if (!missing.isEmpty()) {
            sb.append("• Чего не хватает из реального пула слота: ").append(joinList(missing)).append(".\n");
        }
        sb.append("• Топ-ориентир слота: ").append(slot.topAffixesRu[0]);
        if (slot.topAffixesRu.length > 1) {
            sb.append("; ").append(slot.topAffixesRu[1]);
        }
        return sb.toString();
    }

    private static String joinList(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
