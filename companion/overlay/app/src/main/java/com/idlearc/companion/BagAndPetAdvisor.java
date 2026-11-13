package com.idlearc.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Умный анализатор Сумки (Предметы + Самоцветы) и Глубокий Советник Аффиксов Питомцев для всех 5 классов.
 *
 * Ключевые принципы:
 * 1) Оценка предметов в инвентаре (/api/inventory + newLoot) строится СТРОГО по неизменяемому
 *    бонусу дропа (drop_bonus_type / drop_bonus_value), допустимому классу (allowed_classes) и тиру (tier),
 *    НЕ бракуя хорошие базы из-за обычных аффиксов, которые всегда можно перековать!
 * 2) Оценка всех камней в мешке (/api/gems bag + newGems) сравнивает каждый камень с 20 надетыми
 *    оправами под активный класс (Воин / Лучник / Маг / Разбойник / Друид) и считает выход Пыли с разбора.
 * 3) Советник аффиксов петов учитывает класс героя, этап уровня, капы (включая Доп. убийство >= 100%),
 *    механику составного уровня (compound_level) и реальный запас самоцветов в оправах и сумке.
 */
public final class BagAndPetAdvisor {

    private BagAndPetAdvisor() {
    }

    // =========================================================================
    // 1. АНАЛИЗ ВСЕХ ПРЕДМЕТОВ В ИНВЕНТАРЕ СТРОГО ПО БОНУСУ ДРОПА И КЛАССУ
    // =========================================================================
    public enum ItemBagAction {
        KEEP_UPGRADE_BASE("🔥 ОСТАВИТЬ ПОД ОСНОВУ", "#143829", "#69F0AE"),
        KEEP_BOSS_PRESET("👹 В БОСС-ПРЕСЕТ", "#311B3F", "#EA80FC"),
        KEEP_FARM_PRESET("🍀 НЕИСТОВСТВО / УДАЧА / ФАРМ", "#1A3248", "#81D4FA"),
        KEEP_LOCKED("🔒 ЗАБЛОКИРОВАН", "#2C2816", "#FFD54F"),
        SALVAGE_DESTROY("🗑️ УНИЧТОЖИТЬ (В РАЗБОР)", "#381818", "#FF8A80");

        public final String badgeText;
        public final String bgHex;
        public final String fgHex;

        ItemBagAction(String badgeText, String bgHex, String fgHex) {
            this.badgeText = badgeText;
            this.bgHex = bgHex;
            this.fgHex = fgHex;
        }
    }

    public static class BagItemEntry {
        public final String itemName;
        public final String slotRu;
        public final SlotAffixAdvisor.GearSlot gearSlot;
        public final int tier;
        public final int tierLevel;
        public final int awakenLevel;
        public final int itemLevel;
        public final boolean locked;
        public final String dropBonusSlug;
        public final String dropBonusRu;
        public final double dropBonusValue;
        public final String implicitSummary;
        public final ItemBagAction action;
        public final String reasonRu;

        public BagItemEntry(String itemName, String slotRu, SlotAffixAdvisor.GearSlot gearSlot,
                            int tier, int tierLevel, int awakenLevel, int itemLevel,
                            boolean locked, String dropBonusSlug, String dropBonusRu,
                            double dropBonusValue, String implicitSummary,
                            ItemBagAction action, String reasonRu) {
            this.itemName = itemName;
            this.slotRu = slotRu;
            this.gearSlot = gearSlot;
            this.tier = tier;
            this.tierLevel = tierLevel;
            this.awakenLevel = awakenLevel;
            this.itemLevel = itemLevel;
            this.locked = locked;
            this.dropBonusSlug = dropBonusSlug;
            this.dropBonusRu = dropBonusRu;
            this.dropBonusValue = dropBonusValue;
            this.implicitSummary = implicitSummary;
            this.action = action;
            this.reasonRu = reasonRu;
        }
    }

    public static class InventoryAuditSummary {
        public final HeroClassProfile.HeroClass heroClass;
        public final int totalItems;
        public final int serverTotalCount;
        public final int keepCount;
        public final int salvageCount;
        public final String dropBonusSummaryLine;
        public final List<BagItemEntry> entries;

        public InventoryAuditSummary(HeroClassProfile.HeroClass heroClass, int totalItems,
                                     int serverTotalCount, int keepCount, int salvageCount,
                                     String dropBonusSummaryLine, List<BagItemEntry> entries) {
            this.heroClass = heroClass;
            this.totalItems = totalItems;
            this.serverTotalCount = serverTotalCount;
            this.keepCount = keepCount;
            this.salvageCount = salvageCount;
            this.dropBonusSummaryLine = dropBonusSummaryLine;
            this.entries = entries;
        }
    }

    private static final class EquippedDropRef {
        int maxTier = 0;
        double minBisDropVal = Double.MAX_VALUE;
        boolean allSlotsHaveBis = false;
        int bisCount = 0;
        int slotCapacity = 1;
    }

    public static InventoryAuditSummary evaluateInventoryItems(Map<String, Object> dash,
                                                               List<Map<String, Object>> invItems,
                                                               DruidProfile p) {
        HeroClassProfile.HeroClass hc = HeroClassProfile.resolveHeroClass(dash, p);
        List<Map<String, Object>> combined = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        // Исключаем ID уже надетых на героя 10 предметов, чтобы они никогда не задваивались в сумке
        if (dash != null) {
            List<Object> eqCheck = SimpleJson.asList(dash.get("characterEquippedItems"));
            if (eqCheck != null) {
                for (Object eo : eqCheck) {
                    Map<String, Object> em = SimpleJson.asMap(eo);
                    if (em != null) {
                        String eid = SimpleJson.str(em, "id", "");
                        if (!eid.isEmpty()) seenIds.add(eid);
                    }
                }
            }
        }

        // Собираем все уникальные предметы из /api/inventory + gearPresetSlots + newLoot СТРОГО без дублей по id!
        if (invItems != null) {
            for (Map<String, Object> it : invItems) {
                if (it == null) continue;
                String id = SimpleJson.str(it, "id", "");
                if (!id.isEmpty()) {
                    if (seenIds.contains(id)) continue;
                    seenIds.add(id);
                }
                combined.add(it);
            }
        }
        if (dash != null) {
            List<Object> gpList = SimpleJson.asList(dash.get("gearPresetSlots"));
            if (gpList != null) {
                for (Object gpo : gpList) {
                    Map<String, Object> gpm = SimpleJson.asMap(gpo);
                    if (gpm == null) continue;
                    List<Object> pItems = SimpleJson.asList(gpm.get("items"));
                    if (pItems == null) continue;
                    for (Object pio : pItems) {
                        Map<String, Object> it = SimpleJson.asMap(pio);
                        if (it == null || it.get("drop_bonus_type") == null) continue;
                        String id = SimpleJson.str(it, "id", "");
                        if (!id.isEmpty() && seenIds.contains(id)) continue;
                        if (!id.isEmpty()) seenIds.add(id);
                        combined.add(it);
                    }
                }
            }
            List<Object> nl = SimpleJson.asList(dash.get("newLoot"));
            if (nl != null) {
                for (Object o : nl) {
                    Map<String, Object> it = SimpleJson.asMap(o);
                    if (it == null) continue;
                    String id = SimpleJson.str(it, "id", "");
                    if (!id.isEmpty() && seenIds.contains(id)) continue;
                    if (!id.isEmpty()) seenIds.add(id);
                    combined.add(it);
                }
            }
        }

        // Снимаем эталон надетых бонусов дропа по каждому слоту на герое
        Map<SlotAffixAdvisor.GearSlot, EquippedDropRef> eqRefs = new HashMap<>();
        int globalMaxEqTier = 6;
        if (dash != null) {
            List<Object> eqList = SimpleJson.asList(dash.get("characterEquippedItems"));
            if (eqList != null) {
                for (Object eo : eqList) {
                    Map<String, Object> em = SimpleJson.asMap(eo);
                    if (em == null) continue;
                    SlotAffixAdvisor.GearSlot gs = mapSlot(SimpleJson.str(em, "type", ""), SimpleJson.str(em, "slot", ""));
                    if (gs == null) continue;
                    EquippedDropRef ref = eqRefs.get(gs);
                    if (ref == null) {
                        ref = new EquippedDropRef();
                        ref.slotCapacity = (gs == SlotAffixAdvisor.GearSlot.RING) ? 2 : 1;
                        eqRefs.put(gs, ref);
                    }
                    int t = SimpleJson.intVal(em, "tier", 6);
                    ref.maxTier = Math.max(ref.maxTier, t);
                    globalMaxEqTier = Math.max(globalMaxEqTier, t);
                    String dbt = SimpleJson.str(em, "drop_bonus_type", "").toLowerCase(Locale.ROOT);
                    double dbv = SimpleJson.num(em, "drop_bonus_value", 0.0);
                    if (isBisCombatDropForClass(dbt, hc)) {
                        ref.bisCount++;
                        ref.minBisDropVal = Math.min(ref.minBisDropVal, dbv);
                    }
                }
            }
        }
        for (EquippedDropRef ref : eqRefs.values()) {
            ref.allSlotsHaveBis = (ref.bisCount >= ref.slotCapacity);
            if (!ref.allSlotsHaveBis) ref.minBisDropVal = 0.0;
        }

        // Находим по 1 лучшему предмету в сумке для Босс-сета (slayers) и Фарм-сета на каждый слот,
        // чтобы все слабые дубликаты в том же слоте сразу отправлять В РАЗБОР!
        Map<String, Double> bestSpecialInBag = new HashMap<>();
        for (Map<String, Object> it : combined) {
            if (!isAllowedForClass(it, hc.id)) continue;
            SlotAffixAdvisor.GearSlot gs = mapSlot(SimpleJson.str(it, "type", ""), SimpleJson.str(it, "slot", ""));
            if (gs == null) continue;
            int tier = SimpleJson.intVal(it, "tier", 1);
            if (tier < globalMaxEqTier - 1) continue;
            String dbt = SimpleJson.str(it, "drop_bonus_type", "").toLowerCase(Locale.ROOT);
            double dbv = SimpleJson.num(it, "drop_bonus_value", 0.0);
            double score = tier * 1000.0 + dbv;
            if ("slayers".equals(dbt) || isValuableFarmDropBonus(dbt)) {
                String bucketKey = gs.name() + "|" + (isValuableFarmDropBonus(dbt) ? "FARM:" + dbt : "BOSS");
                Double prev = bestSpecialInBag.get(bucketKey);
                if (prev == null || score > prev) {
                    bestSpecialInBag.put(bucketKey, score);
                }
            }
        }

        Set<String> claimedBestBucket = new HashSet<>();
        List<BagItemEntry> result = new ArrayList<>();
        int keepCnt = 0;
        int salvageCnt = 0;
        String activeStance = dash != null ? SimpleJson.str(dash, "activeCombatStance", "") : "";
        boolean bulwarkWarrior = (hc == HeroClassProfile.HeroClass.WARRIOR) && activeStance.toLowerCase(Locale.ROOT).contains("bulwark");

        for (Map<String, Object> it : combined) {
            String typeStr = SimpleJson.str(it, "type", "");
            String slotStr = SimpleJson.str(it, "slot", "");
            SlotAffixAdvisor.GearSlot gs = mapSlot(typeStr, slotStr);
            String slotRu = gs != null ? gs.ruTitle : RuGameDictionary.humanizeSlug(typeStr.isEmpty() ? slotStr : typeStr);
            String name = SimpleJson.str(it, "name", slotRu);
            int tier = SimpleJson.intVal(it, "tier", 1);
            int tierLvl = SimpleJson.intVal(it, "tier_level", 0);
            int awkLvl = SimpleJson.intVal(it, "awaken_level", 0);
            int iLvl = SimpleJson.intVal(it, "level", 1);
            boolean locked = SimpleJson.bool(it, "locked", SimpleJson.bool(it, "is_locked", false));

            String dbt = SimpleJson.str(it, "drop_bonus_type", "").toLowerCase(Locale.ROOT);
            double dbv = SimpleJson.num(it, "drop_bonus_value", 0.0);
            String dbRu = dbt.isEmpty() ? "Без бонуса дропа" : RuGameDictionary.statName(dbt);
            String impSummary = extractImplicitSummary(it);

            EquippedDropRef eqRef = gs != null ? eqRefs.get(gs) : null;
            int eqTier = eqRef != null && eqRef.maxTier > 0 ? eqRef.maxTier : globalMaxEqTier;

            ItemBagAction action;
            String reason;

            // 1. Проверка классового ограничения оружия/оффхенда (allowed_classes)
            if (!isAllowedForClass(it, hc.id)) {
                String allowedClassRu = describeAllowedClasses(it);
                action = locked ? ItemBagAction.KEEP_LOCKED : ItemBagAction.SALVAGE_DESTROY;
                reason = "Предмет для другого класса (" + allowedClassRu + ") — твой класс «" + hc.ruName + "» не может его надеть.";
            }
            // 2. Низкий тир (на 2+ тира ниже уже надетого снаряжения)
            else if (tier < eqTier - 1) {
                action = locked ? ItemBagAction.KEEP_LOCKED : ItemBagAction.SALVAGE_DESTROY;
                reason = "Устаревший тир T" + tier + " (на тебе уже T" + eqTier + ") — потолок статов и бонуса дропа слишком низкий.";
            }
            // 3. Нет бонуса дропа вообще
            else if (dbt.isEmpty() || dbv <= 0.0) {
                action = locked ? ItemBagAction.KEEP_LOCKED : ItemBagAction.SALVAGE_DESTROY;
                reason = "Отсутствует врождённый бонус дропа — такую базу невыгодно точить и перековывать.";
            }
            // 4. ГЛАВНЫЙ БИС-БОНУС ДРОПА ПОД КЛАСС (companions для Друида, fierce для Воина/Лучника/Мага/Разбойника)
            else if (isBisCombatDropForClass(dbt, hc) || (bulwarkWarrior && ("armored".equals(dbt) || "resilient".equals(dbt)))) {
                if (eqRef == null || !eqRef.allSlotsHaveBis) {
                    action = ItemBagAction.KEEP_UPGRADE_BASE;
                    reason = "🔥 Профильный БИС-бонус дропа для класса «" + hc.ruName + "» («" + dbRu + " +"
                            + fmt1(dbv) + "%»)! На тебе в этом слоте ещё нет такого бонуса — оставляй под перековку аффиксов!";
                } else if (tier > eqRef.maxTier || (tier == eqRef.maxTier && dbv > eqRef.minBisDropVal + 0.15)) {
                    action = ItemBagAction.KEEP_UPGRADE_BASE;
                    reason = String.format(Locale.US,
                            "🔥 АПГРЕЙД БАЗЫ под «%s»: бонус дропа «%s +%.1f%%» ВЫШЕ твоего надетого (+%.1f%%)! Перекуй аффиксы и замени слот.",
                            hc.ruName, dbRu, dbv, eqRef.minBisDropVal);
                } else {
                    action = locked ? ItemBagAction.KEEP_LOCKED : ItemBagAction.SALVAGE_DESTROY;
                    reason = String.format(Locale.US,
                            "На тебе в слоте «%s» уже надет профильный «%s +%.1f%%» (здесь +%.1f%% слабее) — дубль не нужен, смело в разбор!",
                            slotRu, dbRu, eqRef.minBisDropVal, dbv);
                }
            }
            // 5. БОСС-БОНУС ДРОПА (slayers = Урон по боссам %)
            else if ("slayers".equals(dbt) && gs != null) {
                String bKey = gs.name() + "|BOSS";
                double score = tier * 1000.0 + dbv;
                Double bestScore = bestSpecialInBag.get(bKey);
                if (bestScore != null && Math.abs(score - bestScore) < 0.001 && !claimedBestBucket.contains(bKey)) {
                    claimedBestBucket.add(bKey);
                    action = ItemBagAction.KEEP_BOSS_PRESET;
                    reason = "Лучший в сумке ролл «Урон по боссам +" + fmt1(dbv)
                            + "%» в этот слот — можно отложить 1 шт. в отдельный Босс/Рейд-пресет.";
                } else {
                    action = locked ? ItemBagAction.KEEP_LOCKED : ItemBagAction.SALVAGE_DESTROY;
                    reason = "Слабый дубль «Урон по боссам +" + fmt1(dbv) + "%» (в сумке есть ролл сильнее) — на разбор.";
                }
            }
            // 6. НЕИСТОВСТВО (Доп. убийство), УДАЧА (Повыш. тир), МАРОДЁРА, СТИХИЙНЫЙ ВСПЛЕСК, РУНЫ, РУБИНЫ
            else if (isValuableFarmDropBonus(dbt) && gs != null) {
                if ("frenzy".equals(dbt) && p != null && p.extraKillChance >= 100.0 && p.mode != DruidProfile.BuildMode.FARM) {
                    action = locked ? ItemBagAction.KEEP_LOCKED : ItemBagAction.SALVAGE_DESTROY;
                    reason = "Бонус дропа «Неистовство (Доп. убийство +" + fmt1(dbv)
                            + "%)», но у тебя в статах Доп. убийство уже >= 100% (" + fmt1(p.extraKillChance)
                            + "%). Если не нужен сменный Фарм-слот — можно разобрать.";
                } else {
                    String fKey = gs.name() + "|FARM:" + dbt;
                    double score = tier * 1000.0 + dbv;
                    Double bestScore = bestSpecialInBag.get(fKey);
                    if (bestScore != null && Math.abs(score - bestScore) < 0.001 && !claimedBestBucket.contains(fKey)) {
                        claimedBestBucket.add(fKey);
                        action = ItemBagAction.KEEP_FARM_PRESET;
                        reason = "🔥 Лучший в сумке ролл «" + dbRu + " +" + fmt1(dbv)
                                + "%» для слота «" + slotRu + "» — ОБЯЗАТЕЛЬНО ОСТАВЬ под пресет Фарма / Удачи / Неистовства!";
                    } else {
                        action = locked ? ItemBagAction.KEEP_LOCKED : ItemBagAction.SALVAGE_DESTROY;
                        reason = "Слабый дубль «" + dbRu + " +" + fmt1(dbv)
                                + "%» (в сумке для этого слота уже есть экземпляр с роллом выше) — этот дубль в разбор!";
                    }
                }
            }
            // 7. Защитные бонусы дропа (СУ / Уклонение) — нужны ТОЛЬКО если у героя не закрыт кап 95%!
            else if ("resilient".equals(dbt) && p != null && p.damageReductionRaw < 95.0) {
                action = ItemBagAction.KEEP_UPGRADE_BASE;
                reason = "У тебя ещё не закрыт кап Снижения урона 95% (сейчас " + fmt1(p.damageReductionRaw)
                        + "%), бонус дропа «Снижение урона +" + fmt1(dbv) + "%» временно полезен.";
            }
            else if ("evasive".equals(dbt) && p != null && p.dodgeRaw < 95.0 && !bulwarkWarrior) {
                action = ItemBagAction.KEEP_UPGRADE_BASE;
                reason = "У тебя ещё не закрыт кап Уклонения 95% (сейчас " + fmt1(p.dodgeRaw)
                        + "%), бонус дропа «Уклонение +" + fmt1(dbv) + "%» временно полезен.";
            }
            // 8. ВСЕ ОСТАЛЬНЫЕ БОНУСЫ ДРОПА — БЕСПОЛЕЗНЫ ПОД КЛАСС И УХОДЯТ В РАЗБОР!
            else {
                action = locked ? ItemBagAction.KEEP_LOCKED : ItemBagAction.SALVAGE_DESTROY;
                reason = explainWhyDropBonusIsTrashForClass(dbt, dbRu, dbv, hc, p);
            }

            // Не уничтожать базы атаки/питомца только по классу: стойка и гибрид
            // могут сделать обе стороны полезными, а точного симулятора здесь нет.
            if ("harmony".equals(StanceAdvisor.stance(dash))
                    && ("fierce".equals(dbt) || "companions".equals(dbt))) {
                action = locked ? ItemBagAction.KEEP_LOCKED : ItemBagAction.KEEP_UPGRADE_BASE;
                reason = "Сохрани для ручного сравнения под Гармонию: вклад вещи в баланс и DPS не рассчитан.";
            } else if (action == ItemBagAction.SALVAGE_DESTROY && !StanceAdvisor.stance(dash).isEmpty()
                    && ("fierce".equals(dbt) || "companions".equals(dbt))) {
                action = ItemBagAction.KEEP_UPGRADE_BASE;
                reason = "Сохрани для ручного сравнения под текущую стойку: вклад вещи в DPS не рассчитан.";
            }
            if (action == ItemBagAction.SALVAGE_DESTROY) {
                salvageCnt++;
            } else {
                keepCnt++;
            }

            result.add(new BagItemEntry(name, slotRu, gs, tier, tierLvl, awkLvl, iLvl,
                    locked, dbt, dbRu, dbv, impSummary, action, reason));
        }

        // Сортируем: сначала Апгрейды базы, затем На разбор (чтобы удобно чистить сумку), затем Пресеты
        Collections.sort(result, new Comparator<BagItemEntry>() {
            @Override
            public int compare(BagItemEntry a, BagItemEntry b) {
                int oa = orderOf(a.action);
                int ob = orderOf(b.action);
                if (oa != ob) return Integer.compare(oa, ob);
                if (a.tier != b.tier) return Integer.compare(b.tier, a.tier);
                return Double.compare(b.dropBonusValue, a.dropBonusValue);
            }

            private int orderOf(ItemBagAction act) {
                switch (act) {
                    case KEEP_UPGRADE_BASE: return 0;
                    case SALVAGE_DESTROY: return 1;
                    case KEEP_BOSS_PRESET: return 2;
                    case KEEP_FARM_PRESET: return 3;
                    default: return 4;
                }
            }
        });

        // Сводка по найденным в сумке бонусам дропа (чтобы Неистовство и Удача были видны с первой секунды!)
        Map<String, Integer> bonusCounts = new HashMap<>();
        for (BagItemEntry e : result) {
            String k = e.dropBonusRu;
            Integer prev = bonusCounts.get(k);
            bonusCounts.put(k, prev == null ? 1 : (prev + 1));
        }
        List<String> sumParts = new ArrayList<>();
        for (Map.Entry<String, Integer> be : bonusCounts.entrySet()) {
            sumParts.add(be.getKey() + ": " + be.getValue() + " шт.");
        }
        Collections.sort(sumParts);
        int srvTotal = Math.max(result.size(), IdleArcApiClient.getCachedInventoryTotalCount());

        return new InventoryAuditSummary(hc, result.size(), srvTotal, keepCnt, salvageCnt,
                sumParts.isEmpty() ? "" : join(sumParts, " · "), result);
    }

    private static boolean isBisCombatDropForClass(String dbt, HeroClassProfile.HeroClass hc) {
        if (dbt == null || dbt.isEmpty()) return false;
        if (hc == HeroClassProfile.HeroClass.DRUID) {
            return "companions".equals(dbt) || "pet_damage_bonus".equals(dbt) || "pet_damage".equals(dbt);
        }
        return "fierce".equals(dbt) || "attack_damage_percent".equals(dbt);
    }

    private static boolean isValuableFarmDropBonus(String dbt) {
        return "frenzy".equals(dbt) || "lucky".equals(dbt) || "scavengers".equals(dbt)
                || "elemental_surge".equals(dbt) || "runic".equals(dbt) || "jeweled".equals(dbt)
                || "item_quantity".equals(dbt) || "rune_drops".equals(dbt)
                || "ruby_drops".equals(dbt) || "material_quantity".equals(dbt);
    }

    private static String explainWhyDropBonusIsTrashForClass(String dbt, String dbRu, double dbv,
                                                             HeroClassProfile.HeroClass hc, DruidProfile p) {
        String needRu = hc == HeroClassProfile.HeroClass.DRUID ? "«Урон питомцев %»" : "«Урон атаки %»";
        if ("companions".equals(dbt) && hc != HeroClassProfile.HeroClass.DRUID) {
            return "Бонус дропа «Урон питомцев +" + fmt1(dbv) + "%» нужен только Друиду — классу «"
                    + hc.ruName + "» на вещах нужен " + needRu + ". Уничтожить!";
        }
        if ("fierce".equals(dbt) && hc == HeroClassProfile.HeroClass.DRUID) {
            return "Бонус дропа «Урон атаки +" + fmt1(dbv) + "%» на Друиде в разы слабее отдельного множителя «Урон питомцев %». Уничтожить!";
        }
        if ("precise".equals(dbt)) {
            return "Бонус дропа «Шанс крита +" + fmt1(dbv) + "%» не нужен (кап крита 100% закрывается легко, слот обязан давать " + needRu + "). Уничтожить!";
        }
        if ("brutal".equals(dbt)) {
            return "Бонус дропа «Крит. урон +" + fmt1(dbv) + "%» растворяется в общем пуле крит. урона и сильно проигрывает " + needRu + ". Уничтожить!";
        }
        if ("resilient".equals(dbt) || "evasive".equals(dbt)) {
            return "Кап защиты/уворота 95% уже закрыт — тратить бонус дропа на «" + dbRu + " +" + fmt1(dbv) + "%» не нужно. Уничтожить!";
        }
        if ("prosperous".equals(dbt) || "scholarly".equals(dbt)) {
            return "Бонус дропа «" + dbRu + " +" + fmt1(dbv) + "%» легко перекрывается камнями и талисманами — база слабая, на разбор!";
        }
        return "Непрофильный бонус дропа «" + dbRu + " +" + fmt1(dbv) + "%» для класса «"
                + hc.ruName + "» (нужен " + needRu + "). Аффиксы крутить бессмысленно — уничтожить!";
    }

    // =========================================================================
    // 2. ПОЛНЫЙ АУДИТ МЕШКА САМОЦВЕТОВ (/api/gems bag + newGems) ПОД КЛАСС
    // =========================================================================
    public enum GemBagAction {
        SOCKET_NOW("💎 ВСТАВИТЬ В СЛОТ", "#143829", "#69F0AE"),
        KEEP_RESERVE("✨ В РЕЗЕРВ / ФАРМ", "#1A3248", "#81D4FA"),
        FUSE_UPGRADE("🧬 НА СЛИЯНИЕ (3→1)", "#2B213A", "#CE93D8"),
        SALVAGE_DUST("🔨 РАСПЫЛИТЬ В ПЫЛЬ", "#381818", "#FF8A80");

        public final String badgeText;
        public final String bgHex;
        public final String fgHex;

        GemBagAction(String badgeText, String bgHex, String fgHex) {
            this.badgeText = badgeText;
            this.bgHex = bgHex;
            this.fgHex = fgHex;
        }
    }

    public static class BagGemEntry {
        public final String titleRu;
        public final String familySlug;
        public final String familyHex;
        public final int rarityTier;
        public final int quality;
        public final boolean pristine;
        public final boolean locked;
        public final String secondariesRu;
        public final int dustValue;
        public final GemBagAction action;
        public final String reasonRu;

        public BagGemEntry(String titleRu, String familySlug, String familyHex,
                           int rarityTier, int quality, boolean pristine, boolean locked,
                           String secondariesRu, int dustValue,
                           GemBagAction action, String reasonRu) {
            this.titleRu = titleRu;
            this.familySlug = familySlug;
            this.familyHex = familyHex;
            this.rarityTier = rarityTier;
            this.quality = quality;
            this.pristine = pristine;
            this.locked = locked;
            this.secondariesRu = secondariesRu;
            this.dustValue = dustValue;
            this.action = action;
            this.reasonRu = reasonRu;
        }
    }

    public static class GemBagAuditSummary {
        public final int bagCount;
        public final int bagCap;
        public final int countJade;
        public final int countLapis;
        public final int countGarnet;
        public final int countAmber;
        public final int upgradeSocketsCount;
        public final int salvageGemsCount;
        public final int totalSalvageDust;
        public final List<BagGemEntry> entries;

        public GemBagAuditSummary(int bagCount, int bagCap, int countJade, int countLapis,
                                  int countGarnet, int countAmber, int upgradeSocketsCount,
                                  int salvageGemsCount, int totalSalvageDust,
                                  List<BagGemEntry> entries) {
            this.bagCount = bagCount;
            this.bagCap = bagCap;
            this.countJade = countJade;
            this.countLapis = countLapis;
            this.countGarnet = countGarnet;
            this.countAmber = countAmber;
            this.upgradeSocketsCount = upgradeSocketsCount;
            this.salvageGemsCount = salvageGemsCount;
            this.totalSalvageDust = totalSalvageDust;
            this.entries = entries;
        }
    }

    public static GemBagAuditSummary evaluateGemBag(Map<String, Object> dash,
                                                    Map<String, Object> gemsData,
                                                    DruidProfile p) {
        HeroClassProfile.HeroClass hc = HeroClassProfile.resolveHeroClass(dash, p);
        String stance = dash != null ? SimpleJson.str(dash, "activeCombatStance", "") : "";

        // Собираем список камней из /api/gems -> bag (или резервно из dash -> newGems)
        List<Map<String, Object>> rawGems = new ArrayList<>();
        int bagCap = 100;
        if (gemsData != null) {
            bagCap = SimpleJson.intVal(gemsData, "bag_cap", 100);
            List<Object> bagList = SimpleJson.asList(gemsData.get("bag"));
            if (bagList != null) {
                for (Object go : bagList) {
                    Map<String, Object> gm = SimpleJson.asMap(go);
                    if (gm != null) rawGems.add(gm);
                }
            }
        }
        if (rawGems.isEmpty() && dash != null) {
            List<Object> ng = SimpleJson.asList(dash.get("newGems"));
            if (ng != null) {
                for (Object go : ng) {
                    Map<String, Object> gm = SimpleJson.asMap(go);
                    if (gm != null) rawGems.add(gm);
                }
            }
        }

        // Смотрим минимальное качество и редкость надетых камней в каждой категории под активный класс
        Map<GemSocketOptimizer.GemFamily, Integer> worstEquippedScoreByFam = new HashMap<>();
        Map<GemSocketOptimizer.GemFamily, String> worstEquippedLabelByFam = new HashMap<>();
        if (dash != null) {
            Map<String, Object> tracks = SimpleJson.asMap(dash.get("gemSockets"));
            if (tracks != null) {
                for (Map.Entry<String, Object> e : tracks.entrySet()) {
                    Map<String, Object> tr = SimpleJson.asMap(e.getValue());
                    if (tr == null) continue;
                    GemSocketOptimizer.SocketCategory cat = mapTrackCat(e.getKey());
                    GemSocketOptimizer.GemFamily idealFam = HeroClassProfile.bestGemFamilyForClass(
                            hc, cat, p != null ? p.mode : DruidProfile.BuildMode.PUSH, stance);
                    List<Object> socks = SimpleJson.asList(tr.get("sockets"));
                    if (socks == null) continue;
                    for (Object so : socks) {
                        Map<String, Object> sk = SimpleJson.asMap(so);
                        if (sk == null || !SimpleJson.bool(sk, "unlocked", false)) continue;
                        int idx = SimpleJson.intVal(sk, "index", 1);
                        String slotName = ruTrackName(e.getKey()) + " #" + idx;
                        Map<String, Object> g = SimpleJson.asMap(sk.get("gem"));
                        int eqScore = 0;
                        if (g != null) {
                            GemSocketOptimizer.GemFamily gFam = mapFam(SimpleJson.str(g, "family", ""));
                            int gRar = parseGemRarityTier(g);
                            int gQual = SimpleJson.intVal(g, "quality", 80);
                            eqScore = (gFam == idealFam ? 1000 : 0) + gRar * 150 + gQual;
                        }
                        Integer prevWorst = worstEquippedScoreByFam.get(idealFam);
                        if (prevWorst == null || eqScore < prevWorst) {
                            worstEquippedScoreByFam.put(idealFam, eqScore);
                            worstEquippedLabelByFam.put(idealFam, slotName);
                        }
                    }
                }
            }
        }

        int cJade = 0, cLapis = 0, cGarnet = 0, cAmber = 0;
        int upgCnt = 0, salvCnt = 0, totalDust = 0;
        List<BagGemEntry> entries = new ArrayList<>();

        for (Map<String, Object> gm : rawGems) {
            String famSlug = SimpleJson.str(gm, "family", "").toLowerCase(Locale.ROOT);
            if ("jade".equals(famSlug)) cJade++;
            else if ("lapis".equals(famSlug)) cLapis++;
            else if ("garnet".equals(famSlug)) cGarnet++;
            else if ("amber".equals(famSlug)) cAmber++;

            GemSocketOptimizer.GemFamily fam = mapFam(famSlug);
            int rarTier = parseGemRarityTier(gm);
            String rarSlug = SimpleJson.str(gm, "rarity_slug", SimpleJson.str(gm, "rarity", String.valueOf(rarTier)));
            int qual = SimpleJson.intVal(gm, "quality", 100);
            boolean pristine = SimpleJson.bool(gm, "pristine", false);
            boolean locked = SimpleJson.bool(gm, "locked", false);
            int dust = salvageDustForRarity(rarTier);

            List<String> secParts = new ArrayList<>();
            int goodCombatSecs = 0;
            int wastedSecs = 0;
            List<Object> secs = SimpleJson.asList(gm.get("secondaries"));
            if (secs != null) {
                for (Object so : secs) {
                    Map<String, Object> sm = SimpleJson.asMap(so);
                    if (sm == null) continue;
                    String st = SimpleJson.str(sm, "stat", "");
                    double sv = SimpleJson.num(sm, "value", 0.0);
                    secParts.add(RuGameDictionary.statName(st) + " " + RuGameDictionary.formatStatValue(st, sv, null));
                    if (("critical_strike_chance".equals(st) && p != null && p.critChance >= 100.0)
                            || ("dodge_chance".equals(st) && p != null && p.dodgeRaw >= 95.0)) {
                        wastedSecs++;
                    } else if ("double_hit_chance".equals(st) || "double_damage_chance".equals(st) || "critical_damage".equals(st)) {
                        goodCombatSecs++;
                    }
                }
            }

            String titleRu = RuGameDictionary.gemRarityRu(rarSlug) + " " + RuGameDictionary.gemFamilyRu(famSlug)
                    + " (" + qual + "%)" + (pristine ? " ★ Первозд." : "");
            String famHex = RuGameDictionary.gemFamilyColorHex(famSlug);

            int myScore = 1000 + rarTier * 150 + qual + (pristine ? 15 : 0) + goodCombatSecs * 5 - wastedSecs * 6;
            Integer worstEq = fam != null ? worstEquippedScoreByFam.get(fam) : null;

            GemBagAction action;
            String reason;

            // 1. T1..T3 в игре сливаются 3→1 в более высокую редкость (fuse_gold_fee != null в конфиге!)
            if (rarTier <= 3 && !pristine) {
                action = GemBagAction.FUSE_UPGRADE;
                reason = "Редкость T" + rarTier + " поддерживает слияние (3→1) за золото — объедини в Сияющий (T4), не распыляй зря!";
            }
            // 2. Прямой апгрейд одного из 20 надетых слотов под твой класс!
            else if (worstEq != null && myScore > worstEq + 4) {
                action = GemBagAction.SOCKET_NOW;
                upgCnt++;
                reason = "🔥 Сильнее текущего камня в слоте «" + worstEquippedLabelByFam.get(fam)
                        + "» под класс «" + hc.ruName + "»!";
            }
            // 3. Первозданный, заблокированный, топ-качество >=95% или лучший Янтарь под фарм
            else if (locked || pristine || (qual >= 95 && wastedSecs == 0)
                    || ("amber".equals(famSlug) && rarTier >= 5 && qual >= 88)) {
                action = GemBagAction.KEEP_RESERVE;
                reason = locked ? "Закреплён замком в сумке."
                        : ("amber".equals(famSlug) ? "Сильный Янтарь T5 — оставь для Фарм-пресета золота и материалов."
                        : "Высокое качество огранки / удачные доп. свойства — хороший резерв.");
            }
            // 4. T4/T5 камень, который НЕ сливается выше (fuse_gold_fee == null для T4/T5 в конфиге!) и слабее надетых -> В ПЫЛЬ!
            else {
                action = GemBagAction.SALVAGE_DUST;
                salvCnt++;
                totalDust += dust;
                String wasteNote = wastedSecs > 0 ? " (есть сгоревшие в кап доп. статы)" : "";
                reason = "В конфиге игры T" + rarTier + " больше не сливается выше, уступает твоим оправам" + wasteNote
                        + " → разбери на +" + dust + " Пыли самоцветов!";
            }

            if ("harmony".equals(StanceAdvisor.stance(dash))) {
                if (action == GemBagAction.SOCKET_NOW) upgCnt--;
                if (action == GemBagAction.SALVAGE_DUST) { salvCnt--; totalDust -= dust; }
                action = GemBagAction.KEEP_RESERVE;
                reason = "Для Гармонии влияние камня на баланс не рассчитано — сравни вручную перед слиянием или разбором.";
            }
            entries.add(new BagGemEntry(titleRu, famSlug, famHex, rarTier, qual, pristine, locked,
                    secParts.isEmpty() ? "Без доп. свойств" : join(secParts, " · "),
                    dust, action, reason));
        }

        Collections.sort(entries, new Comparator<BagGemEntry>() {
            @Override
            public int compare(BagGemEntry a, BagGemEntry b) {
                int oa = ord(a.action);
                int ob = ord(b.action);
                if (oa != ob) return Integer.compare(oa, ob);
                if (a.rarityTier != b.rarityTier) return Integer.compare(b.rarityTier, a.rarityTier);
                return Integer.compare(b.quality, a.quality);
            }

            private int ord(GemBagAction act) {
                switch (act) {
                    case SOCKET_NOW: return 0;
                    case SALVAGE_DUST: return 1;
                    case FUSE_UPGRADE: return 2;
                    default: return 3;
                }
            }
        });

        return new GemBagAuditSummary(rawGems.size(), bagCap, cJade, cLapis, cGarnet, cAmber,
                upgCnt, salvCnt, totalDust, entries);
    }

    private static int parseGemRarityTier(Map<String, Object> gm) {
        int num = SimpleJson.intVal(gm, "rarity", 0);
        if (num >= 1 && num <= 6) return num;
        String slug = SimpleJson.str(gm, "rarity_slug", SimpleJson.str(gm, "rarity", "")).toLowerCase(Locale.ROOT);
        switch (slug) {
            case "rough": return 1;
            case "cut": return 2;
            case "polished": return 3;
            case "brilliant": return 4;
            case "flawless":
            case "perfect": return 5;
            default: return 4;
        }
    }

    private static int salvageDustForRarity(int rTier) {
        switch (rTier) {
            case 1: return 2;
            case 2: return 6;
            case 3: return 20;
            case 4: return 70;
            case 5: default: return 300;
        }
    }

    // =========================================================================
    // 3. ГЛУБОКИЙ СОВЕТНИК АФФИКСОВ ПИТОМЦЕВ (УРОВЕНЬ + КАПЫ + КАМНИ + КЛАСС)
    // =========================================================================
    public static class PetDeepAdvice {
        public final String mechanicsExplainer;
        public final String levelStageTitle;
        public final List<String> recommended4AffixesNow;
        public final List<String> gemSynergyNotes;
        public final List<String> perPetActionPlan;

        public PetDeepAdvice(String mechanicsExplainer, String levelStageTitle,
                             List<String> recommended4AffixesNow,
                             List<String> gemSynergyNotes,
                             List<String> perPetActionPlan) {
            this.mechanicsExplainer = mechanicsExplainer;
            this.levelStageTitle = levelStageTitle;
            this.recommended4AffixesNow = recommended4AffixesNow;
            this.gemSynergyNotes = gemSynergyNotes;
            this.perPetActionPlan = perPetActionPlan;
        }
    }

    public static PetDeepAdvice analyzePetsDeep(Map<String, Object> dash,
                                                Map<String, Object> gemsData,
                                                DruidProfile p) {
        HeroClassProfile.HeroClass hc = HeroClassProfile.resolveHeroClass(dash, p);
        int lvl = p != null ? p.charLevel : 1;
        double extraKill = p != null ? p.extraKillChance : 0.0;
        double elemConv = p != null ? p.elementalConvertPct : 0.0;
        double elemShards = dash != null ? SimpleJson.num(dash, "elementalShardsAmount", 0.0) : 0.0;
        DruidProfile.BuildMode mode = p != null ? p.mode : DruidProfile.BuildMode.PUSH;

        // Считаем реальные камни игрока (в 20 гнёздах + в мешке /api/gems)
        int socketedAmber = 0, socketedLapisArmor = 0;
        if (dash != null) {
            Map<String, Object> tracks = SimpleJson.asMap(dash.get("gemSockets"));
            if (tracks != null) {
                for (Map.Entry<String, Object> e : tracks.entrySet()) {
                    Map<String, Object> tr = SimpleJson.asMap(e.getValue());
                    if (tr == null) continue;
                    boolean isArmor = !e.getKey().startsWith("weapon") && !"torch".equals(e.getKey())
                            && !"amulet".equals(e.getKey()) && !e.getKey().startsWith("ring");
                    List<Object> socks = SimpleJson.asList(tr.get("sockets"));
                    if (socks == null) continue;
                    for (Object so : socks) {
                        Map<String, Object> sk = SimpleJson.asMap(so);
                        Map<String, Object> g = sk != null ? SimpleJson.asMap(sk.get("gem")) : null;
                        if (g == null) continue;
                        String fam = SimpleJson.str(g, "family", "").toLowerCase(Locale.ROOT);
                        if ("amber".equals(fam)) socketedAmber++;
                        if (isArmor && "lapis".equals(fam)) socketedLapisArmor++;
                    }
                }
            }
        }
        int bagAmber = 0, bagLapis = 0;
        if (gemsData != null) {
            List<Object> bag = SimpleJson.asList(gemsData.get("bag"));
            if (bag != null) {
                for (Object go : bag) {
                    Map<String, Object> gm = SimpleJson.asMap(go);
                    if (gm == null) continue;
                    String fam = SimpleJson.str(gm, "family", "").toLowerCase(Locale.ROOT);
                    if ("amber".equals(fam)) bagAmber++;
                    if ("lapis".equals(fam)) bagLapis++;
                }
            }
        }

        // 1. Кристально понятное объяснение устройства петов из серверного конфига /api/config/game
        String specNeedRu = hc == HeroClassProfile.HeroClass.DRUID ? "«Урон питомцев %»" : "«Урон атаки %»";
        String explainer = "У каждого питомца в IdleArc есть ДВА независимых слоя бонусов:\n"
                + "1️⃣ Врождённый спец-бонус (1 строка в шапке пета) — ОН НА 100% ЗАВИСИТ ОТ КЛАССА И СБОРКИ! "
                + "Для класса «" + hc.ruName + "» на всех 4 боевых петах должен стоять спец-бонус " + specNeedRu
                + " — его сила растёт от Составного уровня (compound level) всей четвёрки!\n"
                + "2️⃣ 4 перековываемых аффикса пета — в серверном пуле игры здесь существует ровно 9 статов: "
                + "«Все классовые навыки (макс. ур. 2)», «Шанс доп. убийства +3% (макс. ур. 1)», «Конверсия в стихию %» и 6 фарм-статов "
                + "(Руны, Рубины, Предметы, Материалы, Опыт, Золото). Прямого «% урона пета» или «% атаки» в этих 4 слотах НЕТ — "
                + "поэтому выбор 4 аффиксов зависит от твоего УРОВНЯ, закрытых КАПОВ и доступных САМОЦВЕТОВ!";

        // 2. Рекомендация идеальной 4-ки аффиксов прямо сейчас с учётом уровня, капа Доп. убийства и камней!
        String stageTitle;
        List<String> ideal4 = new ArrayList<>();
        boolean extraKillCapped = (extraKill >= 100.0);
        boolean hasElementalTech = (elemConv > 0.0 || elemShards >= 50.0);

        if (lvl < 140) {
            stageTitle = "🌱 Этап 1 (Ур. " + lvl + " — Разгон уровней и базы)";
            ideal4.add("1. Все классовые навыки (ур. 2/2) — даёт до +8 ко всем навыкам с 4 петов, обязателен всегда!");
            if (!extraKillCapped && mode != DruidProfile.BuildMode.BOSS) {
                ideal4.add("2. Шанс доп. убийства (+3%, ур. 1/1) — пока твоё Доп. убийство ниже 100% (сейчас " + fmt1(extraKill) + "%), ускоряет зачистку волн.");
            } else {
                ideal4.add("2. Дроп предметов % — качается без потолка осколками петов и даёт приток новых тиров.");
            }
            ideal4.add("3. Получение опыта % (до ур. ~200, если мало Лазуритов в броне) ИЛИ Дроп рун %.");
            ideal4.add("4. Дроп рубинов % — копит рубины на Эволюцию петов (по 500 рубинов) и перековку.");
        } else if (lvl < 290) {
            stageTitle = "⚔️ Этап 2 (Ур. " + lvl + " — Мидгейм: переход в T6, Руны и Эволюцию петов)";
            ideal4.add("1. Все классовые навыки (ур. 2/2) — главный боевой слот на каждом из 4 петов (+8 ко всем навыкам!).");
            ideal4.add("2. Дроп рун % (качай до 300–500+ ур.) — КАМНИ НЕ ДАЮТ дроп рун, а руны дают огромную отдельную корзину урона!");
            ideal4.add("3. Дроп рубинов % (качай до 300–500+ ур.) — КАМНИ НЕ ДАЮТ рубины, а они окупают все эволюции и роллы.");
            if (mode == DruidProfile.BuildMode.BOSS || hasElementalTech) {
                ideal4.add("4. Конверсия урона в стихию % (под Боссов/Пуш со стихией) ИЛИ Дроп предметов % (для ловли идеальных бонусов дропа T6).");
            } else if (!extraKillCapped) {
                ideal4.add("4. Дроп предметов % (лучше масштабируется осколками!) — «Доп. убийство +3%» упирается в потолок ур. 1 и не растит уровень слота.");
            } else {
                ideal4.add("4. Дроп предметов % — так как Доп. убийство у тебя уже >= 100% (" + fmt1(extraKill) + "%), слоты на доп. убийство на петах больше НЕ нужны!");
            }
        } else {
            stageTitle = "👑 Этап 3 (Ур. " + lvl + " — Эндгейм: максимальный импакт для профиля)";
            ideal4.add("1. Все классовые навыки (ур. 2/2) — железный №1 на всех 4 петах (проверяй лишь оверкап навыков во вкладке Навыки).");
            ideal4.add("2. Дроп рун % (бесконечный скейл ур. 400+) — ни один самоцвет или талисман не заменит этот источник силы рун!");
            ideal4.add("3. Дроп рубинов % (бесконечный скейл ур. 400+) — единственный масштабируемый генератор рубинов в игре.");
            if (mode == DruidProfile.BuildMode.BOSS || hasElementalTech) {
                ideal4.add("4. Конверсия в стихию % (если пушишь через стихийный урон) ИЛИ Дроп предметов % / Дроп материалов % (ур. 400+ для заточки T6/T7 +30).");
            } else {
                ideal4.add("4. Дроп предметов % (или Дроп материалов % под заточку +30) — даёт +40..50% на ур. 400+ и поднимает составной уровень пета!");
            }
        }

        // 3. Связка с доступными самоцветами и капом Доп. убийства
        List<String> gemNotes = new ArrayList<>();
        if (extraKillCapped) {
            gemNotes.add("🚨 КАП ДОП. УБИЙСТВА ЗАКРЫТ (" + fmt1(extraKill) + "% >= 100%): аффикс «Доп. убийство +3%» на питомцах стал бесполезен — замени его на масштабируемый стат (Дроп рун / рубинов / предметов / конверсию стихии)!");
        } else {
            gemNotes.add("ℹ️ Про «Доп. убийство +3%» на петах (сейчас у тебя " + fmt1(extraKill)
                    + "%): у этого аффикса жёсткий потолок Ур. 1 (он НЕ качается осколками!). На Боссах он даёт 0%, а в эндгейме мешает разгонять суммарные уровни пета ради роста спец-бонуса в шапке!");
        }

        int totalAmber = socketedAmber + bagAmber;
        int totalLapis = socketedLapisArmor + bagLapis;
        if (totalAmber > 0 || totalLapis > 0) {
            gemNotes.add(String.format(Locale.US,
                    "💎 Учёт твоих камней (Янтарей: %d шт., Лазуритов: %d шт.): Золото и Материалы при необходимости легко разгоняются Янтарём, а Опыт — Лазуритом в броне (+11%% за камень × оправа). "
                    + "А вот «Все навыки», «Дроп рун» и «Дроп рубинов» НЕ ДАЁТ НИ ОДИН КАМЕНЬ в игре — именно поэтому держи их на питомцах!",
                    totalAmber, totalLapis));
        } else {
            gemNotes.add("💎 Синергия с камнями: никогда не трать слоты аффиксов петов на Золото и Опыт в мид/лейтгейме — эти статы легко закрываются Лазуритом и Янтарём в оправах, а слоты петов оставь под Все навыки, Руны, Рубины и Предметы!");
        }

        // 4. Персональный разбор каждого из 4 надетых петов игрока прямо сейчас!
        List<String> petActions = new ArrayList<>();
        if (dash != null) {
            List<Object> pets = SimpleJson.asList(dash.get("characterPets"));
            if (pets != null) {
                for (Object po : pets) {
                    Map<String, Object> pm = SimpleJson.asMap(po);
                    if (pm == null || !SimpleJson.bool(pm, "is_equipped", false)) continue;
                    String pName = SimpleJson.str(pm, "name", "Питомец");
                    String spec = SimpleJson.str(pm, "special_bonus_type", "");
                    double specVal = SimpleJson.num(pm, "special_bonus_active_value", 0.0);

                    boolean specMatchesClass = (hc == HeroClassProfile.HeroClass.DRUID && "pet_damage".equals(spec))
                            || (hc != HeroClassProfile.HeroClass.DRUID && ("attack_damage".equals(spec) || "retaliation".equals(spec)))
                            || "all_skills".equals(spec);

                    List<String> issues = new ArrayList<>();
                    if (!specMatchesClass && mode != DruidProfile.BuildMode.FARM) {
                        issues.add("спец-бонус «" + RuGameDictionary.statName(spec) + "» не боевой под класс «"
                                + hc.ruName + "» (лучше " + specNeedRu + ")");
                    }

                    boolean hasAllSkills = false;
                    int allSkillsLvl = 0;
                    List<Object> affs = SimpleJson.asList(pm.get("affixes"));
                    if (affs != null) {
                        for (Object ao : affs) {
                            Map<String, Object> am = SimpleJson.asMap(ao);
                            if (am == null) continue;
                            String aName = SimpleJson.str(am, "name", "");
                            int aLvl = SimpleJson.intVal(am, "level", 1);
                            if ("all_skills".equals(aName)) {
                                hasAllSkills = true;
                                allSkillsLvl = aLvl;
                            } else if ("extra_kill_chance".equals(aName)) {
                                if (extraKillCapped || lvl >= 250 || mode == DruidProfile.BuildMode.BOSS) {
                                    issues.add("замени «Доп. убийство +3% (ур.1/1)» → в «Дроп рубинов» или «Конверсию стихии» (потолок ур.1 тормозит прокачку пета"
                                            + (extraKillCapped ? ", а кап 100%+ уже набран!" : " и даёт 0% на боссах!") + ")");
                                }
                            } else if (("gold_gain".equals(aName) || "exp_gain".equals(aName)) && lvl >= 180) {
                                issues.add("замени «" + RuGameDictionary.statName(aName)
                                        + "» → в «Дроп рун / рубинов / предметов» (золото и опыт перекрываются камнями и талисманами)");
                            }
                        }
                    }
                    if (!hasAllSkills) {
                        issues.add("🔥 ДОРОЛЛЬ «Все классовые навыки» (+2 ко всем навыкам)!");
                    } else if (allSkillsLvl < 2) {
                        issues.add("прокачай «Все классовые навыки» с ур.1 до ур.2 (1000 осколков)!");
                    }

                    if (issues.isEmpty()) {
                        petActions.add("✅ «" + pName + "» (спец: " + RuGameDictionary.statName(spec)
                                + " +" + Math.round(specVal) + "%): идеальный набор из 4 аффиксов, просто поднимай уровни за осколки!");
                    } else {
                        petActions.add("🔧 «" + pName + "»: " + join(issues, "; "));
                    }
                }
            }
        }

        return new PetDeepAdvice(explainer, stageTitle, ideal4, gemNotes, petActions);
    }

    // =========================================================================
    // ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
    // =========================================================================
    private static boolean isAllowedForClass(Map<String, Object> item, int classId) {
        List<Object> allowed = SimpleJson.asList(item.get("allowed_classes"));
        if (allowed == null || allowed.isEmpty()) return true;
        for (Object o : allowed) {
            if (o instanceof Number && ((Number) o).intValue() == classId) return true;
            try {
                if (Integer.parseInt(String.valueOf(o).trim()) == classId) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static String describeAllowedClasses(Map<String, Object> item) {
        List<Object> allowed = SimpleJson.asList(item.get("allowed_classes"));
        if (allowed == null || allowed.isEmpty()) return "Все классы";
        List<String> names = new ArrayList<>();
        for (Object o : allowed) {
            int cid = 0;
            if (o instanceof Number) cid = ((Number) o).intValue();
            else {
                try { cid = Integer.parseInt(String.valueOf(o).trim()); } catch (Exception ignored) {}
            }
            if (cid >= 1 && cid <= 5) names.add(RuGameDictionary.classNameRu(cid, ""));
        }
        return names.isEmpty() ? "Другой класс" : join(names, ", ");
    }

    private static String extractImplicitSummary(Map<String, Object> item) {
        List<String> parts = new ArrayList<>();
        Map<String, Object> i1 = SimpleJson.asMap(item.get("implicit_bonus"));
        if (i1 != null) {
            String t = SimpleJson.str(i1, "type", "");
            double v = SimpleJson.num(i1, "value", 0.0);
            if (v > 0) parts.add(RuGameDictionary.statName(t) + " +" + Math.round(v));
        }
        Map<String, Object> i2 = SimpleJson.asMap(item.get("secondary_implicit_bonus"));
        if (i2 != null) {
            String t = SimpleJson.str(i2, "type", "");
            double v = SimpleJson.num(i2, "value", 0.0);
            if (v > 0) parts.add(RuGameDictionary.statName(t) + " " + RuGameDictionary.formatStatValue(t, v, null));
        }
        return join(parts, " · ");
    }

    private static SlotAffixAdvisor.GearSlot mapSlot(String type, String slot) {
        String s = ((type != null ? type : "") + " " + (slot != null ? slot : "")).toLowerCase(Locale.ROOT);
        if (s.contains("weapon") || s.contains("main_hand") || s.contains("sword") || s.contains("bow") || s.contains("staff") || s.contains("dagger")) {
            return SlotAffixAdvisor.GearSlot.WEAPON;
        }
        if (s.contains("torch") || s.contains("shield") || s.contains("offhand") || s.contains("off_hand")) {
            return SlotAffixAdvisor.GearSlot.TORCH;
        }
        if (s.contains("helmet") || s.contains("head")) return SlotAffixAdvisor.GearSlot.HELMET;
        if (s.contains("chest")) return SlotAffixAdvisor.GearSlot.CHEST;
        if (s.contains("glove") || s.contains("hand")) return SlotAffixAdvisor.GearSlot.GLOVES;
        if (s.contains("boot") || s.contains("feet")) return SlotAffixAdvisor.GearSlot.BOOTS;
        if (s.contains("belt") || s.contains("waist")) return SlotAffixAdvisor.GearSlot.BELT;
        if (s.contains("amulet") || s.contains("neck")) return SlotAffixAdvisor.GearSlot.AMULET;
        if (s.contains("ring")) return SlotAffixAdvisor.GearSlot.RING;
        return null;
    }

    private static GemSocketOptimizer.SocketCategory mapTrackCat(String track) {
        if (track.startsWith("weapon")) return GemSocketOptimizer.SocketCategory.WEAPON;
        if ("torch".equals(track)) return GemSocketOptimizer.SocketCategory.OFFHAND;
        if ("amulet".equals(track) || track.startsWith("ring")) return GemSocketOptimizer.SocketCategory.JEWELRY;
        return GemSocketOptimizer.SocketCategory.ARMOR;
    }

    private static GemSocketOptimizer.GemFamily mapFam(String slug) {
        if (slug == null) return null;
        switch (slug.toLowerCase(Locale.ROOT)) {
            case "jade": return GemSocketOptimizer.GemFamily.JADE;
            case "lapis": return GemSocketOptimizer.GemFamily.LAPIS;
            case "garnet": return GemSocketOptimizer.GemFamily.GARNET;
            case "amber": return GemSocketOptimizer.GemFamily.AMBER;
            default: return null;
        }
    }

    private static String ruTrackName(String track) {
        switch (track) {
            case "weapon_1": return "Оружие";
            case "torch": return "Факел";
            case "helmet": return "Шлем";
            case "chest_armor": return "Торс";
            case "boots": return "Сапоги";
            case "gloves": return "Перчатки";
            case "belt": return "Пояс";
            case "amulet": return "Амулет";
            case "ring_1": return "Кольцо #1";
            case "ring_2": return "Кольцо #2";
            default: return RuGameDictionary.humanizeSlug(track);
        }
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
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
