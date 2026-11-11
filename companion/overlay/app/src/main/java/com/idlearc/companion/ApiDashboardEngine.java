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

/**
 * Ядро Druid Helper v3.0 (Pure API Edition).
 * Работает на 100% напрямую со структурами официального серверного слепка /api/dashboard
 * и конфига /api/config/game без OCR-эмуляции и без захвата экрана.
 */
public class ApiDashboardEngine {

    /** 10 реальных экипировочных слотов сервера (включая оба кольца отдельно) */
    public enum EquippedSlotKey {
        ALL("📋 Все 10", null, 0),
        WEAPON("⚔️ Оружие", SlotAffixAdvisor.GearSlot.WEAPON, 0),
        TORCH("🔥 Факел", SlotAffixAdvisor.GearSlot.TORCH, 0),
        HELMET("🪖 Шлем", SlotAffixAdvisor.GearSlot.HELMET, 0),
        CHEST("🛡️ Торс", SlotAffixAdvisor.GearSlot.CHEST, 0),
        GLOVES("🧤 Перчатки", SlotAffixAdvisor.GearSlot.GLOVES, 0),
        BOOTS("🥾 Сапоги", SlotAffixAdvisor.GearSlot.BOOTS, 0),
        BELT("🎗️ Пояс", SlotAffixAdvisor.GearSlot.BELT, 0),
        AMULET("📿 Амулет", SlotAffixAdvisor.GearSlot.AMULET, 0),
        RING_1("💍 Кольцо #1", SlotAffixAdvisor.GearSlot.RING, 1),
        RING_2("💍 Кольцо #2", SlotAffixAdvisor.GearSlot.RING, 2);

        public final String chipTitle;
        public final SlotAffixAdvisor.GearSlot gearSlot;
        public final int subSlot;

        EquippedSlotKey(String chipTitle, SlotAffixAdvisor.GearSlot gearSlot, int subSlot) {
            this.chipTitle = chipTitle;
            this.gearSlot = gearSlot;
            this.subSlot = subSlot;
        }
    }

    public static class ServerPositionEval {
        public final int index;
        public final AffixPositionOptimizer.AffixType currentType;
        public final String rawServerName;
        public final double currentValue;
        public final double rangeMin;
        public final double rangeMax;
        public final double rollQualityPct;
        public final int boostCount;
        public final boolean isLocked;
        public final boolean isInfernal;
        public final boolean keepStat;
        public final AffixPositionOptimizer.AffixType recommendedType;
        public final double netUpgradePct;
        public final double numericRerollUpsidePct;
        public final List<String> topAlternatives;

        public ServerPositionEval(int index,
                                  AffixPositionOptimizer.AffixType currentType,
                                  String rawServerName,
                                  double currentValue,
                                  double rangeMin,
                                  double rangeMax,
                                  double rollQualityPct,
                                  int boostCount,
                                  boolean isLocked,
                                  boolean isInfernal,
                                  boolean keepStat,
                                  AffixPositionOptimizer.AffixType recommendedType,
                                  double netUpgradePct,
                                  double numericRerollUpsidePct,
                                  List<String> topAlternatives) {
            this.index = index;
            this.currentType = currentType;
            this.rawServerName = rawServerName;
            this.currentValue = currentValue;
            this.rangeMin = rangeMin;
            this.rangeMax = rangeMax;
            this.rollQualityPct = rollQualityPct;
            this.boostCount = boostCount;
            this.isLocked = isLocked;
            this.isInfernal = isInfernal;
            this.keepStat = keepStat;
            this.recommendedType = recommendedType;
            this.netUpgradePct = netUpgradePct;
            this.numericRerollUpsidePct = numericRerollUpsidePct;
            this.topAlternatives = topAlternatives;
        }
    }

    public static class ServerItemAudit {
        public final EquippedSlotKey slotKey;
        public final SlotAffixAdvisor.GearSlot gearSlot;
        public final String slotLabel;
        public final String itemName;
        public final int itemLevel;
        public final int tier;
        public final int tierLevel;
        public final int maxTierLevel;
        public final int awakenLevel;
        public final int gildLevel;
        public final String implicitText;
        public final String dropBonusText;
        public final List<ServerPositionEval> positions;
        public final int rerollsNeeded;
        public final double totalPotentialGainPct;
        public final String awakenAdvice;

        public ServerItemAudit(EquippedSlotKey slotKey,
                               SlotAffixAdvisor.GearSlot gearSlot,
                               String slotLabel,
                               String itemName,
                               int itemLevel,
                               int tier,
                               int tierLevel,
                               int maxTierLevel,
                               int awakenLevel,
                               int gildLevel,
                               String implicitText,
                               String dropBonusText,
                               List<ServerPositionEval> positions,
                               int rerollsNeeded,
                               double totalPotentialGainPct,
                               String awakenAdvice) {
            this.slotKey = slotKey;
            this.gearSlot = gearSlot;
            this.slotLabel = slotLabel;
            this.itemName = itemName;
            this.itemLevel = itemLevel;
            this.tier = tier;
            this.tierLevel = tierLevel;
            this.maxTierLevel = maxTierLevel;
            this.awakenLevel = awakenLevel;
            this.gildLevel = gildLevel;
            this.implicitText = implicitText;
            this.dropBonusText = dropBonusText;
            this.positions = positions;
            this.rerollsNeeded = rerollsNeeded;
            this.totalPotentialGainPct = totalPotentialGainPct;
            this.awakenAdvice = awakenAdvice;
        }
    }

    /**
     * Вкладка 1: Главная сводка с сервера + капы + 16 корзин урона пета + Топ-5 приоритетных шагов.
     */
    public static String buildSummaryAndActionPlan(Map<String, Object> d, DruidProfile p) {
        if (d == null || p == null) {
            return buildNotSyncedHint();
        }
        StringBuilder sb = new StringBuilder();
        Map<String, Object> bs = SimpleJson.asMap(d.get("bonusStats"));
        double petAvgDmg = bs != null ? SimpleJson.num(bs, "petDamageFinalAvg", 0.0) : 0.0;
        String stance = SimpleJson.str(d, "activeCombatStance", "—");
        String clsName = SimpleJson.str(d, "characterClassName", "Druid");

        sb.append("🌐 ЖИВЫЕ ДАННЫЕ СЕРВЕРА · ").append(clsName.toUpperCase(Locale.ROOT))
          .append(" УР. ").append(p.charLevel)
          .append(" · GS ").append(p.gearScore).append("\n");
        sb.append("Режим расчёта: ").append(p.mode.title).append(" · Стойка: ").append(ruStance(stance)).append("\n\n");

        // Ключевые боевые метрики с сервера
        sb.append("📊 ТОЧНЫЕ ПОКАЗАТЕЛИ БЕЗ ОКРУГЛЕНИЙ ИГРЫ:\n");
        sb.append(String.format(Locale.US,
                "• Ср. удар питомца: %s (истинный множитель ×%.2fM, пул пета +%.0f%%)\n",
                fmtBig(petAvgDmg), p.petDamageMultMillions, p.petDamageBonusPct));
        sb.append(String.format(Locale.US,
                "• Статы: Инт %.0f · Ловк %.0f · Сила %.0f · Все навыки +%d\n",
                p.intelligence, p.agility, p.strength, p.bonusAllClassSkills));
        sb.append(String.format(Locale.US,
                "• Проки: Дв. удар %.1f%% · Дв. урон %.1f%% · Боссы +%.1f%% · Доп. убийство %.1f%%\n",
                p.doubleHitChance, p.doubleDamageChance, p.bossDamage, p.extraKillChance));

        // Контроль капов
        sb.append("\n🛡️ КОНТРОЛЬ КАПОВ И ПОРОГОВ:\n");
        double drMargin = p.damageReductionRaw - 95.0;
        sb.append(String.format(Locale.US, "• Снижение урона: %.1f%% / 95%% (сырое %.2f%% → %s)\n",
                p.damageReductionEff, p.damageReductionRaw,
                drMargin >= 0 ? ("запас +" + fmt1(drMargin) + "% сверх капа") : ("НЕ ДОБРАНО " + fmt1(-drMargin) + "% до 95%!")));

        double dodgeMargin = p.dodgeRaw - 95.0;
        sb.append(String.format(Locale.US, "• Уклонение: %.1f%% / 95%% (сырое %.2f%% → %s)\n",
                p.dodgeEff, p.dodgeRaw,
                dodgeMargin >= 0 ? ("оверкап +" + fmt1(dodgeMargin) + "% даёт 0% выживаемости") : ("не добрано " + fmt1(-dodgeMargin) + "%")));

        sb.append(String.format(Locale.US, "• Шанс крита: %.1f%% %s\n",
                p.critChance,
                p.critChance >= 100.0 ? "✅ (>=100%: любой крит-шанс на вещах и камнях лишний!)"
                        : ("⚠️ (добери ещё " + fmt1(100.0 - p.critChance) + "% до 100%)")));

        sb.append(String.format(Locale.US, "• Крит. урон: %.0f%% %s\n",
                p.critDamage,
                p.critDamage >= 1500.0 ? "✅ (>=1500%: на Пуше выгоднее Интеллект и Ловкость, чем Крит. урон)"
                        : "(ниже 1500% — даёт сильный прямой прирост)"));

        // Топ-5 приоритетных шагов по всему аккаунту
        List<String> actions = buildTopAccountActions(d, p);
        sb.append("\n🏆 ПЛАН ДЕЙСТВИЙ ПРЯМО СЕЙЧАС (под «").append(p.mode.title).append("»):\n");
        if (actions.isEmpty()) {
            sb.append("  ✅ Все слоты, камни и навыки близки к оптимуму!\n");
        } else {
            int maxAct = Math.min(6, actions.size());
            for (int i = 0; i < maxAct; i++) {
                sb.append("  ").append(i + 1).append(") ").append(actions.get(i)).append("\n");
            }
        }

        // 16 мультипликативных корзин урона питомца с сервера
        if (bs != null) {
            List<Object> breakdown = SimpleJson.asList(bs.get("petDamageBreakdown"));
            if (breakdown != null && !breakdown.isEmpty()) {
                sb.append("\n🔬 16 СЕРВЕРНЫХ КОРЗИН УРОНА ПИТОМЦА (каждая перемножается отдельно!):\n");
                List<Map<String, Object>> buckets = new ArrayList<>();
                for (Object bo : breakdown) {
                    Map<String, Object> bm = SimpleJson.asMap(bo);
                    if (bm != null) buckets.add(bm);
                }
                Collections.sort(buckets, new Comparator<Map<String, Object>>() {
                    @Override
                    public int compare(Map<String, Object> a, Map<String, Object> b) {
                        return Double.compare(SimpleJson.num(b, "pct", 0.0), SimpleJson.num(a, "pct", 0.0));
                    }
                });
                for (Map<String, Object> bm : buckets) {
                    String key = SimpleJson.str(bm, "key", "");
                    double pct = SimpleJson.num(bm, "pct", 0.0);
                    if (Math.abs(pct) < 0.01) continue;
                    double mult = 1.0 + pct / 100.0;
                    sb.append(String.format(Locale.US, "  • %s: %+.1f%% (×%.2f)\n",
                            ruPetBucketName(key), pct, mult));
                }
                sb.append("  💡 Чем меньше текущая корзина (×), тем сильнее каждый новый +1% в ней умножает итоговый урон!\n");
            }
        }

        // Сводка дроп-множителей
        sb.append(String.format(Locale.US,
                "\n🍀 ФАРМ-МНОЖИТЕЛИ СЕРВЕРА:\n• Предметы: %.0f%% (×%.2f) · Материалы: %.0f%% (×%.2f)\n• Руны: %.0f%% (×%.2f) · Рубины: %.0f%% (×%.2f) · Яйца: +%.0f%%\n",
                p.itemDropPct, p.itemDropMult, p.matDropPct, p.matDropMult,
                p.runeDropPct, p.runeDropMult, p.rubyDropPct, p.rubyDropMult, p.eggDropPct));

        return sb.toString();
    }

    /**
     * Вкладка 2: Прямой серверный аудит надетых предметов (всех 10 сразу или выбранного слота).
     */
    public static String buildEquipmentReport(Map<String, Object> d, DruidProfile p, EquippedSlotKey filter) {
        if (d == null || p == null) return buildNotSyncedHint();

        List<ServerItemAudit> audits = analyzeAllEquippedItems(d, p);
        if (audits.isEmpty()) {
            return "На сервере не найдено надетых предметов (characterEquippedItems пуст).";
        }

        StringBuilder sb = new StringBuilder();
        if (filter == null || filter == EquippedSlotKey.ALL) {
            Collections.sort(audits, new Comparator<ServerItemAudit>() {
                @Override
                public int compare(ServerItemAudit a, ServerItemAudit b) {
                    return Double.compare(b.totalPotentialGainPct, a.totalPotentialGainPct);
                }
            });
            sb.append("⚔️ РЕЙТИНГ ПЕРЕКОВКИ ВСЕХ 10 НАДЕТЫХ ВЕЩЕЙ («").append(p.mode.title).append("»):\n");
            sb.append("(Нажми кнопку конкретного слота выше для полного разбора всех 5 позиций, диапазонов ролла и ★ Пробуждения)\n\n");
            for (ServerItemAudit ia : audits) {
                sb.append(formatCompactItemSummary(ia)).append("\n");
            }
            return sb.toString();
        }

        for (ServerItemAudit ia : audits) {
            if (ia.slotKey == filter) {
                return formatDetailedItemAudit(ia, p);
            }
        }
        return "В слоте «" + filter.chipTitle + "» сейчас ничего не надето.";
    }

    /**
     * Вкладка 3: Сумка — авто-сортировка неразобранного лута (newLoot) и новых камней (newGems) против надетых!
     */
    public static String buildBagLootAndGemsReport(Map<String, Object> d, DruidProfile p) {
        if (d == null || p == null) return buildNotSyncedHint();

        List<ServerItemAudit> equipped = analyzeAllEquippedItems(d, p);
        Map<SlotAffixAdvisor.GearSlot, List<ServerItemAudit>> eqBySlot = new HashMap<>();
        for (ServerItemAudit ia : equipped) {
            List<ServerItemAudit> list = eqBySlot.get(ia.gearSlot);
            if (list == null) {
                list = new ArrayList<>();
                eqBySlot.put(ia.gearSlot, list);
            }
            list.add(ia);
        }

        StringBuilder sb = new StringBuilder();
        List<Object> newLoot = SimpleJson.asList(d.get("newLoot"));
        sb.append("🎒 1. НОВЫЕ ПРЕДМЕТЫ В СУМКЕ (newLoot): ")
          .append(newLoot != null ? newLoot.size() : 0).append(" шт.\n");

        if (newLoot == null || newLoot.isEmpty()) {
            sb.append("  • Сумка чистая — неразобранных новых предметов сейчас нет.\n");
        } else {
            int keepCount = 0;
            int salvageCount = 0;
            List<String> keepLines = new ArrayList<>();
            List<String> salvageLines = new ArrayList<>();

            for (Object lo : newLoot) {
                Map<String, Object> item = SimpleJson.asMap(lo);
                if (item == null) continue;
                SlotAffixAdvisor.GearSlot gs = mapServerGearSlot(
                        SimpleJson.str(item, "type", ""), SimpleJson.str(item, "slot", ""));
                if (gs == null) continue;

                int bagTier = SimpleJson.intVal(item, "tier", 1);
                int bagLvl = SimpleJson.intVal(item, "level", 1);
                String bagName = SimpleJson.str(item, "name", gs.shortChip);
                String dropType = SimpleJson.str(item, "drop_bonus_type", "");
                double dropVal = SimpleJson.num(item, "drop_bonus_value", 0.0);

                // Сравниваем со слабейшим надетым предметом этого слота (для колец — из двух колец)
                List<ServerItemAudit> curList = eqBySlot.get(gs);
                int minEqTier = 0;
                if (curList != null && !curList.isEmpty()) {
                    minEqTier = Integer.MAX_VALUE;
                    for (ServerItemAudit eq : curList) {
                        minEqTier = Math.min(minEqTier, eq.tier);
                    }
                }

                // Считаем сколько топовых аффиксов уже прокнуло на выпавшей вещи
                ServerItemAudit bagAudit = analyzeSingleServerItem(item, d, p, false);
                int goodAffixes = 0;
                if (bagAudit != null) {
                    for (ServerPositionEval pe : bagAudit.positions) {
                        if (pe.keepStat && pe.rollQualityPct >= 60.0) goodAffixes++;
                    }
                }

                boolean highFarmDrop = dropVal >= 18.0 && (dropType.contains("item") || dropType.contains("rune") || dropType.contains("ruby"));
                if (bagTier > minEqTier) {
                    keepCount++;
                    keepLines.add(String.format(Locale.US,
                            "  🔥 ОСТАВИТЬ [АПГРЕЙД ТИРА]: %s (%s · А%d · ур.%d) — выше надетого А%d!",
                            bagName, gs.shortChip, bagTier, bagLvl, minEqTier));
                } else if (bagTier == minEqTier && goodAffixes >= 3) {
                    keepCount++;
                    keepLines.add(String.format(Locale.US,
                            "  ✅ ПРОВЕРИТЬ ПОД КРАФТ: %s (%s · А%d) — уже %d/5 сильных стата с хорошим роллом!",
                            bagName, gs.shortChip, bagTier, goodAffixes));
                } else if (bagTier >= minEqTier - 1 && highFarmDrop) {
                    keepCount++;
                    keepLines.add(String.format(Locale.US,
                            "  🍀 В ФАРМ-СЕТ: %s (%s · А%d) — высокий имплисит дропа (%s +%.1f%%)",
                            bagName, gs.shortChip, bagTier, ruDropBonus(dropType), dropVal));
                } else {
                    salvageCount++;
                    if (salvageLines.size() < 8) {
                        salvageLines.add(String.format(Locale.US,
                                "  🗑️ Разобрать: %s (%s · А%d · ур.%d) — слабее надетого А%d",
                                bagName, gs.shortChip, bagTier, bagLvl, minEqTier));
                    }
                }
            }

            sb.append(String.format(Locale.US, "  Итог сортировки: 🔥 Оставить: %d · 🗑️ На разбор: %d\n",
                    keepCount, salvageCount));
            for (String kl : keepLines) sb.append(kl).append("\n");
            for (String sl : salvageLines) sb.append(sl).append("\n");
            if (salvageCount > salvageLines.size()) {
                sb.append("  ... и ещё ").append(salvageCount - salvageLines.size()).append(" проходных вещей на разбор.\n");
            }
        }

        // Проверка новых камней в сумке (newGems)
        List<Object> newGems = SimpleJson.asList(d.get("newGems"));
        double gemDust = SimpleJson.num(d, "gemDust", 0.0);
        sb.append(String.format(Locale.US, "\n💎 2. НОВЫЕ САМОЦВЕТЫ В СУМКЕ (newGems): %d шт. · Пыль: %s\n",
                newGems != null ? newGems.size() : 0, fmtBig(gemDust)));

        if (newGems == null || newGems.isEmpty()) {
            sb.append("  • Новых неразобранных самоцветов в сумке нет.\n");
        } else {
            int upgradeGems = 0;
            for (Object go : newGems) {
                Map<String, Object> gm = SimpleJson.asMap(go);
                if (gm == null) continue;
                String fam = SimpleJson.str(gm, "family", "");
                String rar = SimpleJson.str(gm, "rarity_slug", SimpleJson.str(gm, "rarity", ""));
                int qual = SimpleJson.intVal(gm, "quality", 0);
                boolean pristine = SimpleJson.bool(gm, "pristine", false);

                int cleanSecs = 0;
                List<Object> secs = SimpleJson.asList(gm.get("secondaries"));
                if (secs != null) {
                    for (Object so : secs) {
                        Map<String, Object> sm = SimpleJson.asMap(so);
                        if (sm == null) continue;
                        String st = SimpleJson.str(sm, "stat", "");
                        if ("critical_strike_chance".equals(st) && p.critChance >= 100.0) continue;
                        if ("dodge_chance".equals(st) && p.dodgeRaw >= 95.0) continue;
                        cleanSecs++;
                    }
                }
                if ((rar.toLowerCase(Locale.ROOT).contains("perfect") || rar.contains("5"))
                        && (pristine || (qual >= 92 && cleanSecs >= 3))) {
                    upgradeGems++;
                    sb.append(String.format(Locale.US,
                            "  🌟 ЦЕННЫЙ КАМЕНЬ: %s (%s · качество %d%%%s · %d/3 полезных доп. стата)\n",
                            ruGemFamily(fam), rar, qual, pristine ? " · PRISTINE!" : "", cleanSecs));
                }
            }
            if (upgradeGems == 0) {
                sb.append("  🗑️ Среди новых камней нет топовых Совершенных (92%+ с 3 чистыми статами) — можно пускать на пыль.\n");
            }
        }

        return sb.toString();
    }

    /**
     * Вкладка 4: Полный разбор всех 20 гнёзд и вставленных камней (gemSockets) напрямую с сервера.
     */
    public static String buildAll20GemsReport(Map<String, Object> d, DruidProfile p) {
        if (d == null || p == null) return buildNotSyncedHint();
        Map<String, Object> tracks = SimpleJson.asMap(d.get("gemSockets"));
        if (tracks == null || tracks.isEmpty()) return "Данные gemSockets отсутствуют в ответе сервера.";

        LiveBuildSimulator.HeroState hs = LiveBuildSimulator.buildHeroState(p, 60.0, 0.0, 0.0);
        double gemDust = SimpleJson.num(d, "gemDust", 0.0);

        String[] orderedTracks = {
            "weapon_1", "torch", "helmet", "chest_armor", "gloves",
            "boots", "belt", "amulet", "ring_1", "ring_2"
        };

        int unlockedCount = 0;
        int optimalCount = 0;
        int minSettingLvl = Integer.MAX_VALUE;
        String minSettingLabel = "";

        StringBuilder detailsSb = new StringBuilder();
        List<String> priorityFixes = new ArrayList<>();

        for (String trackKey : orderedTracks) {
            Map<String, Object> tr = SimpleJson.asMap(tracks.get(trackKey));
            if (tr == null) continue;
            GemSocketOptimizer.SocketCategory cat = mapTrackToCategory(trackKey);
            List<GemSocketOptimizer.RankedGemChoice<GemSocketOptimizer.GemFamily>> rankedFam =
                    GemSocketOptimizer.rankFamiliesForCategory(hs, cat, GemSocketOptimizer.GemRarity.PERFECT, 100.0, 46, p.mode);
            GemSocketOptimizer.GemFamily bestFam = rankedFam.get(0).item;

            List<Object> sockets = SimpleJson.asList(tr.get("sockets"));
            if (sockets == null) continue;

            for (Object sObj : sockets) {
                Map<String, Object> sk = SimpleJson.asMap(sObj);
                if (sk == null || !SimpleJson.bool(sk, "unlocked", false)) continue;
                unlockedCount++;
                int idx = SimpleJson.intVal(sk, "index", 1);
                int sLvl = SimpleJson.intVal(sk, "setting_level", 0);
                double mult = SimpleJson.num(sk, "multiplier", 1.0);
                String slotTag = ruTrackName(trackKey) + " #" + idx;

                if (sLvl < minSettingLvl) {
                    minSettingLvl = sLvl;
                    minSettingLabel = slotTag;
                }

                Map<String, Object> gem = SimpleJson.asMap(sk.get("gem"));
                if (gem == null) {
                    priorityFixes.add("⚠️ " + slotTag + " (ур." + sLvl + "): ПУСТОЕ ГНЕЗДО → вставь " + bestFam.badge);
                    detailsSb.append("  ⬜ ").append(slotTag).append(" [ур.").append(sLvl).append("]: пусто\n");
                    continue;
                }

                String famSlug = SimpleJson.str(gem, "family", "").toLowerCase(Locale.ROOT);
                GemSocketOptimizer.GemFamily curFam = mapFamilySlug(famSlug);
                int qual = SimpleJson.intVal(gem, "quality", 100);
                boolean pristine = SimpleJson.bool(gem, "pristine", false);

                List<String> secStrings = new ArrayList<>();
                List<String> wasted = new ArrayList<>();
                List<Object> secs = SimpleJson.asList(gem.get("secondaries"));
                if (secs != null) {
                    for (Object secObj : secs) {
                        Map<String, Object> sec = SimpleJson.asMap(secObj);
                        if (sec == null) continue;
                        String st = SimpleJson.str(sec, "stat", "");
                        double sv = SimpleJson.num(sec, "value", 0.0);
                        secStrings.add(ruGemStat(st) + " +" + fmt1(sv) + (isGemStatPct(st) ? "%" : ""));
                        if ("critical_strike_chance".equals(st) && (p.critChance - sv) >= 100.0) {
                            wasted.add("Шанс крита +" + fmt1(sv) + "% сгорает (кап 100%)");
                        } else if ("dodge_chance".equals(st) && (p.dodgeRaw - sv) >= 95.0) {
                            wasted.add("Уклонение +" + fmt1(sv) + "% сгорает (кап 95%)");
                        } else if ("damage_reduction".equals(st) && (p.damageReductionRaw - sv) >= 95.0) {
                            wasted.add("СУ +" + fmt1(sv) + "% сверх капа 95%");
                        }
                    }
                }

                boolean famOk = (curFam == bestFam);
                if (famOk && wasted.isEmpty()) {
                    optimalCount++;
                } else if (!famOk && curFam != null) {
                    priorityFixes.add(String.format(Locale.US,
                            "🔄 %s (ур.%d): стоит %s (%d%%) → под «%s» сильнее %s%s",
                            slotTag, sLvl, curFam.ruName, qual, p.mode.title, bestFam.badge,
                            wasted.isEmpty() ? "" : (" + " + wasted.get(0))));
                } else if (!wasted.isEmpty()) {
                    priorityFixes.add(String.format(Locale.US,
                            "⚠️ %s (%s %d%%): замени доп. стат (%s)",
                            slotTag, curFam != null ? curFam.ruName : famSlug, qual, wasted.get(0)));
                }

                detailsSb.append(String.format(Locale.US,
                        "  %s %s [ур.%d · ×%.2f]: %s %d%%%s | %s\n",
                        (famOk && wasted.isEmpty()) ? "✅" : "🔧",
                        slotTag, sLvl, mult,
                        curFam != null ? curFam.ruName : famSlug,
                        qual, pristine ? " ★" : "",
                        secStrings.isEmpty() ? "без доп." : joinList(secStrings, ", ")));
            }
        }

        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.US,
                "💎 СЕРВЕРНЫЙ АУДИТ ГНЁЗД (%d/20 открыто · %d/20 идеальны · Пыль: %s)\n",
                unlockedCount, optimalCount, fmtBig(gemDust)));
        if (minSettingLvl < Integer.MAX_VALUE) {
            out.append(String.format(Locale.US,
                    "🪜 Следующая ступень прокачки оправы: %s (сейчас ур. %d — качай все 20 гнёзд равномерно лесенкой!)\n",
                    minSettingLabel, minSettingLvl));
        }

        out.append("\n🎯 ЧТО УЛУЧШИТЬ ПО КАМНЯМ ПОД «").append(p.mode.title).append("»:\n");
        if (priorityFixes.isEmpty()) {
            out.append("  ✅ Все вставленные камни и их доп. статы работают на 100% без потерь в капы!\n");
        } else {
            for (String fix : priorityFixes) {
                out.append("  • ").append(fix).append("\n");
            }
        }

        out.append("\n📋 ПОЛНАЯ КАРТА ВСЕХ 20 ГНЁЗД НА ГЕРОЕ:\n").append(detailsSb);
        return out.toString();
    }

    /**
     * Вкладка 5: Питомцы (четвёрка + резерв) и Талисманы (надписи + превью кузницы talismanInscriptionQuote).
     */
    public static String buildPetsAndTalismansReport(Map<String, Object> d, DruidProfile p) {
        if (d == null || p == null) return buildNotSyncedHint();
        StringBuilder sb = new StringBuilder();

        // 1. Отряд из 4 питомцев
        sb.append("🐾 1. ОТРЯД ИЗ 4 ЭКИПИРОВАННЫХ ПИТОМЦЕВ (Мастерство ур. ")
          .append(SimpleJson.intVal(d, "effectivePetMasteryLevel", 0)).append("):\n");

        List<Object> pets = SimpleJson.asList(d.get("characterPets"));
        if (pets == null || pets.isEmpty()) {
            sb.append("  • Данные characterPets отсутствуют.\n");
        } else {
            String weakestPetName = "";
            int weakestLvl = Integer.MAX_VALUE;
            int compoundCap = 0;

            for (Object po : pets) {
                Map<String, Object> pm = SimpleJson.asMap(po);
                if (pm == null || !SimpleJson.bool(pm, "is_equipped", false)) continue;
                String pName = SimpleJson.str(pm, "name", "Питомец");
                String rar = SimpleJson.str(pm, "rarity", "");
                int cLvl = SimpleJson.intVal(pm, "compound_level", SimpleJson.intVal(pm, "level", 1));
                compoundCap = Math.max(compoundCap, SimpleJson.intVal(pm, "compound_effective_cap", 0));
                String specType = SimpleJson.str(pm, "special_bonus_type", "");
                double specVal = SimpleJson.num(pm, "special_bonus_active_value", 0.0);
                Map<String, Object> dmg = SimpleJson.asMap(pm.get("damage"));
                double avgD = dmg != null ? 0.5 * (SimpleJson.num(dmg, "min", 0.0) + SimpleJson.num(dmg, "max", 0.0)) : 0.0;

                if (cLvl < weakestLvl) {
                    weakestLvl = cLvl;
                    weakestPetName = pName;
                }

                sb.append(String.format(Locale.US,
                        "  • %s [%s · сост. ур.%d]: база %s | спец-бонус: %s (+%.1f)\n",
                        pName, rar, cLvl, fmtBig(avgD), ruPetSpecial(specType), specVal));
            }
            if (!weakestPetName.isEmpty()) {
                sb.append(String.format(Locale.US,
                        "  💡 Узкое место синергии четвёрки: «%s» (ур. %d при потолке %d) — подтягивай в первую очередь самого младшего пета из 4!\n",
                        weakestPetName, weakestLvl, compoundCap));
            }
        }

        // 2. Надетые талисманы + суммарные надписи
        sb.append("\n✒️ 2. НАДЕТЫЕ ТАЛИСМАНЫ И АВТОНАДПИСИ:\n");
        List<Object> tals = SimpleJson.asList(d.get("equippedTalismans"));
        if (tals != null && !tals.isEmpty()) {
            for (Object to : tals) {
                Map<String, Object> tm = SimpleJson.asMap(to);
                if (tm == null) continue;
                String tName = SimpleJson.str(tm, "name", "Талисман");
                int tSlot = SimpleJson.intVal(tm, "slot", 1);
                int tLvl = SimpleJson.intVal(tm, "level", 1);
                sb.append(String.format(Locale.US, "  • Слот #%d: %s (ур. %d)\n", tSlot, tName, tLvl));

                Map<String, Object> totals = SimpleJson.asMap(tm.get("inscription_totals"));
                if (totals != null && !totals.isEmpty()) {
                    List<String> inscParts = new ArrayList<>();
                    for (Map.Entry<String, Object> e : totals.entrySet()) {
                        Map<String, Object> entryMap = SimpleJson.asMap(e.getValue());
                        double tot = entryMap != null ? SimpleJson.num(entryMap, "total", 0.0) : SimpleJson.num(totals, e.getKey(), 0.0);
                        if (Math.abs(tot) > 0.01) {
                            inscParts.add(ruInscriptionStat(e.getKey()) + " +" + fmt1(tot) + "%");
                        }
                    }
                    if (!inscParts.isEmpty()) {
                        sb.append("    Надписи: ").append(joinList(inscParts, " · ")).append("\n");
                    }
                }
            }
        }

        // 3. Проверка висящего превью ролла надписей (talismanInscriptionQuote), если открыта перековка талисмана
        Map<String, Object> quote = SimpleJson.asMap(d.get("talismanInscriptionQuote"));
        if (quote != null && !quote.isEmpty()) {
            sb.append("\n🎲 АКТИВНЫЙ РОЛЛ КУЗНИЦЫ ТАЛИСМАНОВ (talismanInscriptionQuote):\n");
            sb.append("  • Сервер видит незавершённый ролл надписей — сравни прибавку к корзине «Надписи талисманов», Дв. удару и Дв. урону выше!\n");
        }

        sb.append("\n📌 ПРИОРИТЕТ НАДПИСЕЙ ПОД «").append(p.mode.title).append("»:\n");
        sb.append(DruidKnowledgeBase.buildInscriptionsAndPetsGuide(p));
        return sb.toString();
    }

    /**
     * Вкладка 6: Дерево классовых навыков Друида (ID 73..90) с проверкой оверкапа от +all_skills!
     */
    public static String buildSkillsAuditReport(Map<String, Object> d, DruidProfile p, Map<String, Object> gameCfg) {
        if (d == null || p == null) return buildNotSyncedHint();
        StringBuilder sb = new StringBuilder();

        int activeSlot = SimpleJson.intVal(d, "activeClassSkillPresetSlot", 1);
        int bonusAll = p.bonusAllClassSkills;
        List<Object> presets = SimpleJson.asList(d.get("classSkillPresetSlots"));
        Map<String, Object> activeRanks = null;
        int spentPoints = 0;

        if (presets != null) {
            for (Object po : presets) {
                Map<String, Object> pm = SimpleJson.asMap(po);
                if (pm != null && SimpleJson.intVal(pm, "slot", 0) == activeSlot) {
                    spentPoints = SimpleJson.intVal(pm, "point_count", 0);
                    activeRanks = SimpleJson.asMap(pm.get("ranks"));
                    break;
                }
            }
        }

        sb.append(String.format(Locale.US,
                "🌳 ДЕРЕВО НАВЫКОВ ДРУИДА (Пресет #%d · Вложено очков: %d · Бонус «+Все навыки»: +%d)\n\n",
                activeSlot, spentPoints, bonusAll));

        if (activeRanks == null || activeRanks.isEmpty()) {
            sb.append("Не найден активный пресет навыков в ответе сервера.\n");
            return sb.toString();
        }

        int reclaimablePoints = 0;
        List<String> wastedWarnings = new ArrayList<>();
        StringBuilder tableSb = new StringBuilder();

        // Для Друида (classId=5) серверные ID 18 классовых навыков идут по порядку 73..90
        int kinshipEff = 0;
        int feralBondEff = 0;
        int kinshipBase = 0;
        int feralBondBase = 0;

        for (int idx = 0; idx < DruidKnowledgeBase.SKILLS.size(); idx++) {
            DruidKnowledgeBase.SkillInfo sk = DruidKnowledgeBase.SKILLS.get(idx);
            String serverId = String.valueOf(73 + idx);
            int basePts = SimpleJson.intVal(activeRanks, serverId, 0);
            int effLvl = basePts > 0 ? (basePts + bonusAll) : 0;

            if ("druid_kinship".equals(sk.id)) {
                kinshipBase = basePts;
                kinshipEff = effLvl;
            } else if ("druid_feral_bond".equals(sk.id)) {
                feralBondBase = basePts;
                feralBondEff = effLvl;
            }

            String statusIcon = basePts > 0 ? "✅" : "⬜";
            if (sk.hasHardCap && sk.effectiveCapLevels > 0 && basePts > 1
                    && !"druid_kinship".equals(sk.id) && !"druid_feral_bond".equals(sk.id)) {
                int minBaseNeeded = Math.max(1, sk.effectiveCapLevels - bonusAll);
                if (basePts > minBaseNeeded) {
                    int extra = basePts - minBaseNeeded;
                    reclaimablePoints += extra;
                    statusIcon = "⚠️";
                    wastedWarnings.add(String.format(Locale.US,
                            "«%s» (ID %s): вложено %d/%d (эфф. ур. %d при капе %d ур.) → можно оставить %d очк. и освободить +%d очк.!",
                            sk.ruName, serverId, basePts, sk.maxBasePoints, effLvl, sk.effectiveCapLevels, minBaseNeeded, extra));
                }
            }

            tableSb.append(String.format(Locale.US,
                    "  %s [%s] %s: база %d/%d → эфф. ур. %d%s\n",
                    statusIcon, sk.branch, sk.ruName, basePts, sk.maxBasePoints, effLvl,
                    sk.hasHardCap ? (" (кап " + sk.effectiveCapLevels + " ур.)") : " (БЕЗ ЛИМИТА)"));
        }

        // Проверка общего капа 75% конверсии атаки в урон пета (Родство 3%*ур + Дикая связь 4%*ур <= 75%)
        int totalConvPct = kinshipEff * 3 + feralBondEff * 4;
        if (totalConvPct > 75 && (kinshipBase > 1 || feralBondBase > 1)) {
            wastedWarnings.add(String.format(Locale.US,
                    "Конверсия атаки («Родство» + «Дикая связь»): сейчас %d%% при ОБЩЕМ КАПЕ 75%% (+%d%% сгорает впустую — сними лишние базовые очки!)",
                    totalConvPct, totalConvPct - 75));
        }

        if (wastedWarnings.isEmpty()) {
            sb.append("✅ Оверкапа по лимитным навыкам нет — все очки работают эффективно!\n\n");
        } else {
            sb.append("🔥 НАЙДЕНЫ СВОБОДНЫЕ ОЧКИ НАВЫКОВ (можно вернуть минимум +")
              .append(Math.max(1, reclaimablePoints)).append(" очк.):\n");
            for (String w : wastedWarnings) {
                sb.append("  • ").append(w).append("\n");
            }
            sb.append("  👉 Куда вложить освободившиеся очки: в безлимитные узлы урона пета («Одна душа» +12%/ур, «Погрыз» +8%/ур, «Хранитель хатки» +1 маст./ур, «Строитель плотин» +15 базы/ур) или «Землекоп» под Фарм!\n\n");
        }

        sb.append("📋 ВСЕ 18 КЛАССОВЫХ НАВЫКОВ С СЕРВЕРА:\n").append(tableSb);
        return sb.toString();
    }

    // =========================================================================
    // ВНУТРЕННИЙ ПРЯМОЙ АНАЛИЗАТОР СЕРВЕРНЫХ ПРЕДМЕТОВ (0% OCR)
    // =========================================================================

    public static List<ServerItemAudit> analyzeAllEquippedItems(Map<String, Object> d, DruidProfile p) {
        List<ServerItemAudit> out = new ArrayList<>();
        if (d == null || p == null) return out;
        List<Object> eqList = SimpleJson.asList(d.get("characterEquippedItems"));
        if (eqList == null) return out;
        for (Object io : eqList) {
            Map<String, Object> item = SimpleJson.asMap(io);
            if (item == null) continue;
            ServerItemAudit audit = analyzeSingleServerItem(item, d, p, true);
            if (audit != null) out.add(audit);
        }
        return out;
    }

    public static ServerItemAudit analyzeSingleServerItem(Map<String, Object> item,
                                                          Map<String, Object> dash,
                                                          DruidProfile profile,
                                                          boolean isEquipped) {
        String typeStr = SimpleJson.str(item, "type", "");
        String slotStr = SimpleJson.str(item, "slot", "");
        int subSlot = SimpleJson.intVal(item, "equipped_sub_slot", 0);

        SlotAffixAdvisor.GearSlot gs = mapServerGearSlot(typeStr, slotStr);
        if (gs == null) return null;
        EquippedSlotKey slotKey = mapEquippedSlotKey(gs, subSlot);

        String itemName = SimpleJson.str(item, "name", gs.ruTitle);
        int itemLvl = SimpleJson.intVal(item, "level", profile.charLevel);
        int tier = SimpleJson.intVal(item, "tier", 6);
        int tierLvl = SimpleJson.intVal(item, "tier_level", 0);
        int maxTierLvl = SimpleJson.intVal(item, "max_tier_level", 30);
        int awakenLvl = SimpleJson.intVal(item, "awaken_level", tier);
        int gildLvl = SimpleJson.intVal(item, "gild_level", 0);
        boolean isShield = typeStr.toLowerCase(Locale.ROOT).contains("shield");

        String slotLabel = gs.shortChip + (subSlot > 0 ? (" #" + subSlot) : "");

        // Имплиситы и база защиты/оружия напрямую из JSON
        double baseDefOnItem = 0.0;
        double baseWpnDmg = 0.0;
        StringBuilder impSb = new StringBuilder();
        Map<String, Object> imp1 = SimpleJson.asMap(item.get("implicit_bonus"));
        if (imp1 != null) {
            String it = SimpleJson.str(imp1, "type", "");
            double iv = SimpleJson.num(imp1, "value", 0.0);
            if (iv > 0) {
                impSb.append(ruImplicitName(it)).append(" +").append(fmt1(iv));
                if (it.contains("defense")) baseDefOnItem += iv;
                if (it.contains("damage") && gs == SlotAffixAdvisor.GearSlot.WEAPON) baseWpnDmg += iv;
            }
        }
        Map<String, Object> imp2 = SimpleJson.asMap(item.get("secondary_implicit_bonus"));
        if (imp2 != null) {
            String it = SimpleJson.str(imp2, "type", "");
            double iv = SimpleJson.num(imp2, "value", 0.0);
            if (iv > 0) {
                if (impSb.length() > 0) impSb.append(" · ");
                impSb.append(ruImplicitName(it)).append(" +").append(fmt1(iv));
            }
        }
        if (gs == SlotAffixAdvisor.GearSlot.WEAPON && dash != null) {
            Map<String, Object> adb = SimpleJson.asMap(dash.get("attackDamageBreakdown"));
            Map<String, Object> wpn = adb != null ? SimpleJson.asMap(adb.get("weapon")) : null;
            if (wpn != null) {
                baseWpnDmg = Math.max(baseWpnDmg, 0.5 * (SimpleJson.num(wpn, "min", 0.0) + SimpleJson.num(wpn, "max", 0.0)));
            }
        }

        String dropType = SimpleJson.str(item, "drop_bonus_type", "");
        double dropVal = SimpleJson.num(item, "drop_bonus_value", 0.0);
        String dropText = (dropVal > 0 && !dropType.isEmpty())
                ? (ruDropBonus(dropType) + " +" + fmt1(dropVal) + "%") : "";

        // Читаем все 5 позиций аффиксов напрямую из affix_positions JSON
        List<Object> rawPos = SimpleJson.asList(item.get("affix_positions"));
        List<RawServerPos> parsedPos = new ArrayList<>();
        double unitScaleSum = 0.0;
        int unitScaleCnt = 0;

        if (rawPos != null && !rawPos.isEmpty()) {
            for (Object po : rawPos) {
                Map<String, Object> pm = SimpleJson.asMap(po);
                if (pm == null) continue;
                int pIdx = SimpleJson.intVal(pm, "index", parsedPos.size() + 1);
                int boost = SimpleJson.intVal(pm, "boost_count", 0);
                Map<String, Object> aff = SimpleJson.asMap(pm.get("affix"));
                if (aff == null) {
                    parsedPos.add(new RawServerPos(pIdx, AffixPositionOptimizer.AffixType.EMPTY_POSITION,
                            "Пустая позиция", 0, 0, 0, 0, boost, false, false));
                } else {
                    if (boost == 0) boost = SimpleJson.intVal(aff, "awaken_boost_count", 0);
                    AffixPositionOptimizer.AffixType at = mapServerAffixType(aff);
                    String rName = SimpleJson.str(aff, "readable_bonus_name", at.ruName);
                    double val = SimpleJson.num(aff, "value", 0.0);
                    double rMin = SimpleJson.num(aff, "range_min", val);
                    double rMax = SimpleJson.num(aff, "range_max", val);
                    double rQual = SimpleJson.num(aff, "roll_quality", rMax > rMin ? ((val - rMin) / (rMax - rMin) * 100.0) : 100.0);
                    boolean locked = SimpleJson.bool(aff, "is_locked", false);
                    boolean infernal = SimpleJson.bool(aff, "is_infernal", false);

                    double awkDiv = 1.0 + 0.10 * Math.max(0, boost);
                    if (at.ignoresAffixCap && rMax > 0 && at.baseMax > 0) {
                        unitScaleSum += (rMax / awkDiv) / at.baseMax;
                        unitScaleCnt++;
                    }
                    parsedPos.add(new RawServerPos(pIdx, at, rName, val, rMin, rMax, rQual, boost, locked, infernal));
                }
            }
        }

        double unitScale = unitScaleCnt > 0 ? (unitScaleSum / unitScaleCnt) : estimateScaleByTier(tier, tierLvl);
        double procCapBase = estimateProcCapByTier(tier, tierLvl);

        // Официальный пул слота из /api/config/game
        List<AffixPositionOptimizer.AffixType> pool = AffixPositionOptimizer.getCompatiblePool(gs, isShield);
        LiveBuildSimulator.HeroState fullState = LiveBuildSimulator.buildHeroState(profile, 60.0, baseWpnDmg, baseDefOnItem);

        // Находим оптимальный набор из N неповторяющихся статов этого слота под текущий режим
        int n = parsedPos.size();
        List<RankedPoolCandidate> scoredPool = new ArrayList<>();
        for (AffixPositionOptimizer.AffixType cand : pool) {
            double avgUnboosted = expectedRoll(cand, unitScale, procCapBase, 1.0, 0.5);
            double minUnboosted = expectedRoll(cand, unitScale, procCapBase, 1.0, 0.0);
            double maxUnboosted = expectedRoll(cand, unitScale, procCapBase, 1.0, 1.0);
            LiveBuildSimulator.CandidateEvaluation ev = LiveBuildSimulator.evaluateCandidate(
                    fullState, cand, avgUnboosted, minUnboosted, maxUnboosted, baseDefOnItem, profile.mode);
            scoredPool.add(new RankedPoolCandidate(cand, ev.totalObjectiveGainPct));
        }
        Collections.sort(scoredPool, new Comparator<RankedPoolCandidate>() {
            @Override
            public int compare(RankedPoolCandidate a, RankedPoolCandidate b) {
                return Double.compare(b.gainPct, a.gainPct);
            }
        });

        Set<AffixPositionOptimizer.AffixType> idealTopN = EnumSet.noneOf(AffixPositionOptimizer.AffixType.class);
        List<AffixPositionOptimizer.AffixType> orderedIdeal = new ArrayList<>();
        for (int i = 0; i < Math.min(n, scoredPool.size()); i++) {
            idealTopN.add(scoredPool.get(i).type);
            orderedIdeal.add(scoredPool.get(i).type);
        }

        // Сопоставляем остающиеся статы своим местам, а выпадающим назначаем лучшие свободные
        Set<AffixPositionOptimizer.AffixType> usedTargets = EnumSet.noneOf(AffixPositionOptimizer.AffixType.class);
        AffixPositionOptimizer.AffixType[] targetByPos = new AffixPositionOptimizer.AffixType[n];
        for (int i = 0; i < n; i++) {
            AffixPositionOptimizer.AffixType cur = parsedPos.get(i).type;
            if (idealTopN.contains(cur) && !usedTargets.contains(cur)) {
                targetByPos[i] = cur;
                usedTargets.add(cur);
            }
        }
        for (int i = 0; i < n; i++) {
            if (targetByPos[i] == null) {
                for (AffixPositionOptimizer.AffixType cand : orderedIdeal) {
                    if (!usedTargets.contains(cand)) {
                        targetByPos[i] = cand;
                        usedTargets.add(cand);
                        break;
                    }
                }
                if (targetByPos[i] == null && !scoredPool.isEmpty()) {
                    targetByPos[i] = scoredPool.get(0).type;
                }
            }
        }

        List<ServerPositionEval> posResults = new ArrayList<>();
        int rerolls = 0;
        double totalGainSum = 0.0;
        int totalBoostHits = 0;
        String bestBoostStatName = orderedIdeal.isEmpty() ? "" : orderedIdeal.get(0).taggedName();

        for (int i = 0; i < n; i++) {
            RawServerPos rp = parsedPos.get(i);
            totalBoostHits += rp.boostCount;
            double awkMult = 1.0 + 0.10 * Math.max(0, rp.boostCount);

            LiveBuildSimulator.HeroState baseWithoutRow = fullState;
            if (isEquipped && rp.value > 0 && rp.type != AffixPositionOptimizer.AffixType.EMPTY_POSITION) {
                baseWithoutRow = fullState.copy();
                baseWithoutRow.applyAffixDelta(rp.type, -rp.value, baseDefOnItem);
            }

            double curGain = 0.0;
            if (rp.type != AffixPositionOptimizer.AffixType.EMPTY_POSITION && rp.value > 0) {
                curGain = LiveBuildSimulator.evaluateCandidate(
                        baseWithoutRow, rp.type, rp.value, rp.rangeMin, rp.rangeMax, baseDefOnItem, profile.mode)
                        .totalObjectiveGainPct;
            }

            // Ранжируем все свободные альтернативы пула для этой позиции
            Set<AffixPositionOptimizer.AffixType> otherKept = EnumSet.noneOf(AffixPositionOptimizer.AffixType.class);
            for (int j = 0; j < n; j++) {
                if (j != i && parsedPos.get(j).type != AffixPositionOptimizer.AffixType.EMPTY_POSITION) {
                    otherKept.add(parsedPos.get(j).type);
                }
            }

            List<RankedPoolCandidate> posAlts = new ArrayList<>();
            for (AffixPositionOptimizer.AffixType cand : pool) {
                if (otherKept.contains(cand)) continue;
                double cAvg = (cand == rp.type && rp.value > 0) ? rp.value : expectedRoll(cand, unitScale, procCapBase, awkMult, 0.5);
                double cMin = expectedRoll(cand, unitScale, procCapBase, awkMult, 0.0);
                double cMax = expectedRoll(cand, unitScale, procCapBase, awkMult, 1.0);
                double g = LiveBuildSimulator.evaluateCandidate(
                        baseWithoutRow, cand, cAvg, cMin, cMax, baseDefOnItem, profile.mode).totalObjectiveGainPct;
                posAlts.add(new RankedPoolCandidate(cand, g));
            }
            Collections.sort(posAlts, new Comparator<RankedPoolCandidate>() {
                @Override
                public int compare(RankedPoolCandidate a, RankedPoolCandidate b) {
                    return Double.compare(b.gainPct, a.gainPct);
                }
            });

            AffixPositionOptimizer.AffixType recType = targetByPos[i] != null ? targetByPos[i] : rp.type;
            double recAvg = expectedRoll(recType, unitScale, procCapBase, awkMult, 0.5);
            double recMin = expectedRoll(recType, unitScale, procCapBase, awkMult, 0.0);
            double recMax = expectedRoll(recType, unitScale, procCapBase, awkMult, 1.0);
            double recGain = LiveBuildSimulator.evaluateCandidate(
                    baseWithoutRow, recType, recAvg, recMin, recMax, baseDefOnItem, profile.mode).totalObjectiveGainPct;

            boolean keep = (rp.type == recType) || (curGain >= recGain - 0.03 && rp.type != AffixPositionOptimizer.AffixType.EMPTY_POSITION);
            double netDelta = keep ? 0.0 : Math.max(0.0, recGain - curGain);
            if (!keep) {
                rerolls++;
                totalGainSum += netDelta;
            }

            double numRerollUpside = 0.0;
            if (keep && rp.rangeMax > rp.value * 1.05) {
                double maxGain = LiveBuildSimulator.evaluateCandidate(
                        baseWithoutRow, rp.type, rp.rangeMax, rp.rangeMin, rp.rangeMax, baseDefOnItem, profile.mode)
                        .totalObjectiveGainPct;
                numRerollUpside = Math.max(0.0, maxGain - curGain);
            }

            List<String> top3Text = new ArrayList<>();
            for (int k = 0; k < Math.min(3, posAlts.size()); k++) {
                RankedPoolCandidate alt = posAlts.get(k);
                double dPct = alt.gainPct - curGain;
                top3Text.add(String.format(Locale.US, "%s (%+.2f%%)", alt.type.taggedName(), dPct));
            }

            posResults.add(new ServerPositionEval(
                    rp.index, rp.type, rp.rawName, rp.value, rp.rangeMin, rp.rangeMax,
                    rp.rollQuality, rp.boostCount, rp.locked, rp.infernal,
                    keep, recType, netDelta, numRerollUpside, top3Text));
        }

        String awkNote = totalBoostHits > 0
                ? String.format(Locale.US, "Попаданий Пробуждения на предмете: ★%d (+%d%%) → лучший носитель пробуждения на этом слоте: %s",
                        totalBoostHits, totalBoostHits * 10, bestBoostStatName)
                : "Предмет без попаданий ★ Пробуждения.";

        return new ServerItemAudit(
                slotKey, gs, slotLabel, itemName, itemLvl, tier, tierLvl, maxTierLvl,
                awakenLvl, gildLvl, impSb.toString(), dropText, posResults,
                rerolls, totalGainSum, awkNote);
    }

    private static String formatCompactItemSummary(ServerItemAudit ia) {
        StringBuilder sb = new StringBuilder();
        if (ia.rerollsNeeded == 0) {
            sb.append(String.format(Locale.US, "✅ %s (А%d +%d): все 5 статов в топе!",
                    ia.slotLabel, ia.tier, ia.tierLevel));
            // Подскажем, если где-то низкий числовой ролл
            for (ServerPositionEval pe : ia.positions) {
                if (pe.rollQualityPct < 60.0 && pe.numericRerollUpsidePct >= 0.15) {
                    sb.append(String.format(Locale.US, "\n   💡 #%d %s: ролл %.0f%% (до +%.2f%% перебросом значений)",
                            pe.index, pe.currentType.ruName, pe.rollQualityPct, pe.numericRerollUpsidePct));
                }
            }
        } else {
            sb.append(String.format(Locale.US, "🔧 %s (А%d +%d): потенциал +%.2f%% (%d/5 на перековку)",
                    ia.slotLabel, ia.tier, ia.tierLevel, ia.totalPotentialGainPct, ia.rerollsNeeded));
            for (ServerPositionEval pe : ia.positions) {
                if (!pe.keepStat) {
                    sb.append(String.format(Locale.US, "\n   • #%d %s (%s)%s → %s (+%.2f%%)",
                            pe.index, pe.currentType.ruName,
                            fmtVal(pe.currentValue, pe.currentType.isPercent),
                            pe.boostCount > 0 ? (" ★" + toRoman(pe.boostCount)) : "",
                            pe.recommendedType.taggedName(), pe.netUpgradePct));
                }
            }
        }
        return sb.toString();
    }

    private static String formatDetailedItemAudit(ServerItemAudit ia, DruidProfile p) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚔️ ").append(ia.slotLabel.toUpperCase(Locale.ROOT)).append(" — ").append(ia.itemName)
          .append(String.format(Locale.US, " (Ур.%d · А%d · Заточка +%d/%d",
                  ia.itemLevel, ia.tier, ia.tierLevel, ia.maxTierLevel));
        if (ia.gildLevel > 0) sb.append(" · Позолота ").append(ia.gildLevel);
        sb.append(")\n");
        if (!ia.implicitText.isEmpty()) {
            sb.append("База / Имплисит: ").append(ia.implicitText).append("\n");
        }
        if (!ia.dropBonusText.isEmpty()) {
            sb.append("Бонус дропа на вещи: ").append(ia.dropBonusText).append("\n");
        }
        sb.append("Режим оценки: ").append(p.mode.title).append("\n");
        sb.append(ia.rerollsNeeded == 0
                ? "✅ ВЕРДИКТ: все 5 типов аффиксов оптимальны!\n"
                : String.format(Locale.US, "🔧 ВЕРДИКТ: перекуй %d из 5 поз. → суммарный прирост +%.2f%%\n",
                        ia.rerollsNeeded, ia.totalPotentialGainPct));

        sb.append("\n📋 ПОЗИЦИИ АФФИКСОВ #1..#5 (прямые данные сервера):\n");
        for (ServerPositionEval pe : ia.positions) {
            String boostBadge = pe.boostCount > 0 ? (" [★" + toRoman(pe.boostCount) + " +" + (pe.boostCount * 10) + "%]") : "";
            String lockBadge = pe.isLocked ? " 🔒" : "";
            String infBadge = pe.isInfernal ? " 🔥Инферн." : "";
            sb.append(String.format(Locale.US,
                    "\n#%d %s: +%s%s%s%s\n",
                    pe.index, pe.currentType.taggedName(),
                    fmtVal(pe.currentValue, pe.currentType.isPercent),
                    boostBadge, lockBadge, infBadge));
            if (pe.rangeMax > 0) {
                sb.append(String.format(Locale.US,
                        "   Диапазон на сервере: [%s .. %s] · Качество ролла: %.0f%%\n",
                        fmtVal(pe.rangeMin, pe.currentType.isPercent),
                        fmtVal(pe.rangeMax, pe.currentType.isPercent),
                        pe.rollQualityPct));
            }
            if (pe.keepStat) {
                sb.append("   ✅ ОСТАВИТЬ СТАТ");
                if (pe.rollQualityPct < 65.0 && pe.numericRerollUpsidePct >= 0.10) {
                    sb.append(String.format(Locale.US,
                            " (но ролл %.0f%% низкий — «Изменение значений» даст до +%.2f%%)",
                            pe.rollQualityPct, pe.numericRerollUpsidePct));
                }
                sb.append("\n");
            } else {
                sb.append(String.format(Locale.US,
                        "   🔄 ЗАМЕНИТЬ НА: %s (чистый плюс +%.2f%%)\n",
                        pe.recommendedType.taggedName(), pe.netUpgradePct));
                if (!pe.topAlternatives.isEmpty()) {
                    sb.append("   Топ пула слота: ").append(joinList(pe.topAlternatives, " | ")).append("\n");
                }
            }
        }

        sb.append("\n★ ").append(ia.awakenAdvice).append("\n");
        return sb.toString();
    }

    private static List<String> buildTopAccountActions(Map<String, Object> d, DruidProfile p) {
        List<String> actions = new ArrayList<>();

        // 1. Срочный недобор капа Снижения урона 95%
        if (p.damageReductionEff < 94.5 && (p.mode == DruidProfile.BuildMode.PUSH || p.mode == DruidProfile.BuildMode.LEVELING)) {
            actions.add(String.format(Locale.US,
                    "🛡️ Закрой кап Снижения урона 95%% (сейчас %.1f%% — каждые недостающие проценты кратно режут выживаемость!)",
                    p.damageReductionEff));
        }

        // 2. Сортируем 10 надетых предметов по максимальному +Δ%
        List<ServerItemAudit> items = analyzeAllEquippedItems(d, p);
        Collections.sort(items, new Comparator<ServerItemAudit>() {
            @Override
            public int compare(ServerItemAudit a, ServerItemAudit b) {
                return Double.compare(b.totalPotentialGainPct, a.totalPotentialGainPct);
            }
        });
        int shownItems = 0;
        for (ServerItemAudit ia : items) {
            if (ia.rerollsNeeded > 0 && ia.totalPotentialGainPct >= 0.10 && shownItems < 3) {
                ServerPositionEval worstPos = null;
                for (ServerPositionEval pe : ia.positions) {
                    if (!pe.keepStat && (worstPos == null || pe.netUpgradePct > worstPos.netUpgradePct)) {
                        worstPos = pe;
                    }
                }
                if (worstPos != null) {
                    actions.add(String.format(Locale.US,
                            "⚔️ %s (А%d +%d): перекуй #%d «%s» → «%s» (+%.2f%%, всего на слоте +%.2f%%)",
                            ia.slotLabel, ia.tier, ia.tierLevel,
                            worstPos.index, worstPos.currentType.ruName,
                            worstPos.recommendedType.taggedName(),
                            worstPos.netUpgradePct, ia.totalPotentialGainPct));
                    shownItems++;
                }
            }
        }

        // 3. Проверка новых предметов в сумке
        List<Object> bag = SimpleJson.asList(d.get("newLoot"));
        if (bag != null && !bag.isEmpty()) {
            actions.add("🎒 Проверь вкладку «Сумка»: на сервере лежит " + bag.size() + " неразобранных вещей (newLoot).");
        }

        // 4. Проверка отстающего пета в четвёрке
        List<Object> pets = SimpleJson.asList(d.get("characterPets"));
        if (pets != null) {
            int minC = Integer.MAX_VALUE;
            int maxC = 0;
            String minName = "";
            for (Object po : pets) {
                Map<String, Object> pm = SimpleJson.asMap(po);
                if (pm != null && SimpleJson.bool(pm, "is_equipped", false)) {
                    int cl = SimpleJson.intVal(pm, "compound_level", SimpleJson.intVal(pm, "level", 0));
                    maxC = Math.max(maxC, cl);
                    if (cl < minC) {
                        minC = cl;
                        minName = SimpleJson.str(pm, "name", "Питомец");
                    }
                }
            }
            if (!minName.isEmpty() && maxC - minC >= 5) {
                actions.add(String.format(Locale.US,
                        "🐾 Подтяни уровень питомца «%s» (ур.%d vs макс. ур.%d в четвёрке) для роста синергии отряда!",
                        minName, minC, maxC));
            }
        }

        return actions;
    }

    private static final class RawServerPos {
        final int index;
        final AffixPositionOptimizer.AffixType type;
        final String rawName;
        final double value;
        final double rangeMin;
        final double rangeMax;
        final double rollQuality;
        final int boostCount;
        final boolean locked;
        final boolean infernal;

        RawServerPos(int index, AffixPositionOptimizer.AffixType type, String rawName,
                     double value, double rangeMin, double rangeMax, double rollQuality,
                     int boostCount, boolean locked, boolean infernal) {
            this.index = index;
            this.type = type;
            this.rawName = rawName;
            this.value = value;
            this.rangeMin = rangeMin;
            this.rangeMax = rangeMax;
            this.rollQuality = rollQuality;
            this.boostCount = boostCount;
            this.locked = locked;
            this.infernal = infernal;
        }
    }

    private static final class RankedPoolCandidate {
        final AffixPositionOptimizer.AffixType type;
        final double gainPct;

        RankedPoolCandidate(AffixPositionOptimizer.AffixType type, double gainPct) {
            this.type = type;
            this.gainPct = gainPct;
        }
    }

    private static double expectedRoll(AffixPositionOptimizer.AffixType t,
                                       double unitScale,
                                       double procCapBase,
                                       double awkMult,
                                       double q01) {
        double raw = (t.baseMin + (t.baseMax - t.baseMin) * q01) * unitScale;
        if (!t.ignoresAffixCap) {
            raw = Math.min(raw, procCapBase);
        }
        return raw * awkMult;
    }

    private static double estimateScaleByTier(int tier, int tierLvl) {
        double base = 10.0 + tier * 7.5 + tierLvl * 0.45;
        return Math.max(5.0, base);
    }

    private static double estimateProcCapByTier(int tier, int tierLvl) {
        if (tier >= 6) return 50.0;
        if (tier == 5) return 42.0 + Math.min(8.0, tierLvl * 0.25);
        return Math.min(50.0, 15.0 + tier * 6.5);
    }

    public static SlotAffixAdvisor.GearSlot mapServerGearSlot(String typeStr, String slotStr) {
        String s = (typeStr + " " + slotStr).toLowerCase(Locale.ROOT);
        if (s.contains("weapon") || s.contains("main_hand")) return SlotAffixAdvisor.GearSlot.WEAPON;
        if (s.contains("torch") || s.contains("shield") || s.contains("off_hand")) return SlotAffixAdvisor.GearSlot.TORCH;
        if (s.contains("helmet") || s.contains("head")) return SlotAffixAdvisor.GearSlot.HELMET;
        if (s.contains("chest")) return SlotAffixAdvisor.GearSlot.CHEST;
        if (s.contains("gloves") || s.contains("hands")) return SlotAffixAdvisor.GearSlot.GLOVES;
        if (s.contains("boots") || s.contains("feet")) return SlotAffixAdvisor.GearSlot.BOOTS;
        if (s.contains("belt")) return SlotAffixAdvisor.GearSlot.BELT;
        if (s.contains("amulet")) return SlotAffixAdvisor.GearSlot.AMULET;
        if (s.contains("ring")) return SlotAffixAdvisor.GearSlot.RING;
        return null;
    }

    private static EquippedSlotKey mapEquippedSlotKey(SlotAffixAdvisor.GearSlot gs, int subSlot) {
        switch (gs) {
            case WEAPON: return EquippedSlotKey.WEAPON;
            case TORCH: return EquippedSlotKey.TORCH;
            case HELMET: return EquippedSlotKey.HELMET;
            case CHEST: return EquippedSlotKey.CHEST;
            case GLOVES: return EquippedSlotKey.GLOVES;
            case BOOTS: return EquippedSlotKey.BOOTS;
            case BELT: return EquippedSlotKey.BELT;
            case AMULET: return EquippedSlotKey.AMULET;
            case RING: return subSlot == 2 ? EquippedSlotKey.RING_2 : EquippedSlotKey.RING_1;
            default: return EquippedSlotKey.WEAPON;
        }
    }

    public static AffixPositionOptimizer.AffixType mapServerAffixType(Map<String, Object> aff) {
        String en = (SimpleJson.str(aff, "readable_bonus_name", "") + " " + SimpleJson.str(aff, "name", "")).toLowerCase(Locale.ROOT);
        String kind = SimpleJson.str(aff, "affix_type", SimpleJson.str(aff, "type", "")).toLowerCase(Locale.ROOT);
        String bType = SimpleJson.str(aff, "bonus_type", "").toLowerCase(Locale.ROOT);
        boolean isPct = bType.contains("percent");

        if (en.contains("pet damage") || en.contains("companion damage")) return AffixPositionOptimizer.AffixType.PET_DAMAGE;
        if (en.contains("double hit")) return AffixPositionOptimizer.AffixType.DOUBLE_HIT;
        if (en.contains("double damage")) return AffixPositionOptimizer.AffixType.DOUBLE_DAMAGE;
        if (en.contains("crit") && en.contains("chance")) return AffixPositionOptimizer.AffixType.CRIT_CHANCE;
        if (en.contains("crit") && en.contains("damage")) return AffixPositionOptimizer.AffixType.CRIT_DAMAGE;
        if (en.contains("damage reduction")) return AffixPositionOptimizer.AffixType.DAMAGE_REDUCTION;
        if (en.contains("dodge")) return AffixPositionOptimizer.AffixType.DODGE_CHANCE;
        if (en.contains("intelligence")) return AffixPositionOptimizer.AffixType.INTELLIGENCE;
        if (en.contains("dexterity") || en.contains("agility")) return AffixPositionOptimizer.AffixType.DEXTERITY;
        if (en.contains("strength")) return AffixPositionOptimizer.AffixType.STRENGTH;
        if (en.contains("max health") || en.contains("maximum health")) return AffixPositionOptimizer.AffixType.MAX_HEALTH;
        if ((en.contains("hit") && en.contains("kill")) || en.contains("bloodthirst")) return AffixPositionOptimizer.AffixType.BLOODTHIRST;
        if (en.contains("life on hit")) return AffixPositionOptimizer.AffixType.LIFE_ON_HIT;
        if (en.contains("life on kill")) return AffixPositionOptimizer.AffixType.LIFE_ON_KILL;
        if (en.contains("defense")) {
            if (!isPct) return AffixPositionOptimizer.AffixType.LOCAL_FLAT_DEFENSE;
            if (kind.contains("prefix") || en.contains("increased") || en.contains("enhanced")) {
                return AffixPositionOptimizer.AffixType.LOCAL_PERCENT_DEFENSE;
            }
            return AffixPositionOptimizer.AffixType.DEFENSE_PERCENT;
        }
        if (en.contains("exp") || en.contains("experience")) return AffixPositionOptimizer.AffixType.EXP_GAIN;
        if (en.contains("gold")) return AffixPositionOptimizer.AffixType.GOLD_GAIN;
        if (en.contains("convert") || en.contains("conversion") || en.contains("fire")
                || en.contains("cold") || en.contains("lightning") || en.contains("poison")) {
            return AffixPositionOptimizer.AffixType.ELEMENTAL_CONVERT;
        }
        return AffixPositionOptimizer.AffixType.ADDED_DAMAGE;
    }

    private static String buildNotSyncedHint() {
        return "🌐 Серверный слепок ещё не загружен.\n\n"
                + "1) Введи свой Email и пароль от IdleArc в карточке подключения выше (они хранятся строго локально на твоём телефоне).\n"
                + "2) Нажми «🔄 Синхронизировать с сервером» — за 0.4 сек загрузятся все твои статы, 10 надетых вещей, сумка, 20 гнёзд самоцветов, 4 питомца, оба талисмана и дерево навыков!";
    }

    private static GemSocketOptimizer.SocketCategory mapTrackToCategory(String track) {
        if (track.startsWith("weapon")) return GemSocketOptimizer.SocketCategory.WEAPON;
        if (track.equals("torch")) return GemSocketOptimizer.SocketCategory.OFFHAND;
        if (track.equals("amulet") || track.startsWith("ring")) return GemSocketOptimizer.SocketCategory.JEWELRY;
        return GemSocketOptimizer.SocketCategory.ARMOR;
    }

    private static GemSocketOptimizer.GemFamily mapFamilySlug(String slug) {
        if ("garnet".equals(slug)) return GemSocketOptimizer.GemFamily.GARNET;
        if ("jade".equals(slug)) return GemSocketOptimizer.GemFamily.JADE;
        if ("lapis".equals(slug)) return GemSocketOptimizer.GemFamily.LAPIS;
        if ("amber".equals(slug)) return GemSocketOptimizer.GemFamily.AMBER;
        return null;
    }

    private static String ruGemFamily(String slug) {
        GemSocketOptimizer.GemFamily gf = mapFamilySlug(slug != null ? slug.toLowerCase(Locale.ROOT) : "");
        return gf != null ? gf.badge : slug;
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
            default: return track;
        }
    }

    private static String ruPetBucketName(String key) {
        switch (key) {
            case "stats": return "Шмот и базовая панель (stats)";
            case "talismanInscription": return "Надписи талисманов";
            case "dexterity": return "От Ловкости героя";
            case "intelligence": return "От Интеллекта героя";
            case "drop": return "Дроп-бонусы вещей";
            case "petShard": return "Осколки питомцев";
            case "classSkills": return "Классовые навыки Друида";
            case "tree": return "Глобальное дерево пассивок";
            case "rune": return "Руны";
            case "guild": return "Гильдия";
            case "echo": return "Эхо";
            case "gearSet": return "Сет снаряжения";
            case "gem": return "Самоцветы в гнёздах";
            case "boost": return "Активные бусты";
            case "petMastery": return "Мастерство питомцев";
            case "stance": return "Боевая стойка";
            default: return key;
        }
    }

    private static String ruStance(String s) {
        if (s == null) return "—";
        String low = s.toLowerCase(Locale.ROOT);
        if (low.contains("beast")) return "Повелитель зверей (Beastmaster)";
        if (low.contains("jugg")) return "Джаггернаут (Juggernaut)";
        if (low.contains("bers")) return "Берсерк (Berserker)";
        if (low.contains("loot") || low.contains("scav")) return "Искатель добычи";
        return s;
    }

    private static String ruGemStat(String st) {
        switch (st) {
            case "pet_damage": return "Урон пета";
            case "critical_strike_chance": return "Шанс крита";
            case "critical_damage": return "Крит. урон";
            case "double_hit_chance": return "Дв. удар";
            case "double_damage_chance": return "Дв. урон";
            case "dodge_chance": return "Уклонение";
            case "damage_reduction": return "СУ";
            case "intelligence": return "Инт";
            case "dexterity": return "Ловк";
            case "strength": return "Сила";
            case "max_health": return "Здоровье";
            case "extra_kill_chance": return "Доп. убийство";
            case "exp_gain": return "Опыт";
            case "gold_gain": return "Золото";
            default: return st;
        }
    }

    private static boolean isGemStatPct(String st) {
        return !("intelligence".equals(st) || "dexterity".equals(st) || "strength".equals(st));
    }

    private static String ruImplicitName(String it) {
        if (it.contains("defense")) return "Защита";
        if (it.contains("damage")) return "Атака";
        if (it.contains("health")) return "Здоровье";
        if (it.contains("crit")) return "Крит";
        if (it.contains("block")) return "Блок";
        return it;
    }

    private static String ruDropBonus(String dt) {
        if (dt.contains("item")) return "Дроп вещей";
        if (dt.contains("rune")) return "Дроп рун";
        if (dt.contains("ruby")) return "Дроп рубинов";
        if (dt.contains("material")) return "Материалы";
        if (dt.contains("pet")) return "Урон пета";
        return dt;
    }

    private static String ruPetSpecial(String sp) {
        if ("all_skills".equals(sp)) return "Все навыки";
        if ("pet_damage".equals(sp)) return "Урон питомца %";
        if ("item_quantity".equals(sp)) return "Дроп предметов %";
        if ("rune_drops".equals(sp)) return "Дроп рун %";
        if ("ruby_drops".equals(sp)) return "Дроп рубинов %";
        if ("material_quantity".equals(sp)) return "Материалы %";
        return sp;
    }

    private static String ruInscriptionStat(String k) {
        if (k.contains("double_hit")) return "Дв. удар";
        if (k.contains("double_damage")) return "Дв. урон";
        if (k.contains("pet_damage")) return "Урон пета";
        if (k.contains("crit_damage") || k.contains("critical_damage")) return "Крит. урон";
        if (k.contains("crit")) return "Шанс крита";
        if (k.contains("damage_reduction")) return "СУ";
        if (k.contains("dodge")) return "Уклонение";
        return k;
    }

    private static String toRoman(int n) {
        switch (n) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            case 6: return "VI";
            case 7: return "VII";
            case 8: return "VIII";
            default: return String.valueOf(n);
        }
    }

    private static String fmtBig(double v) {
        if (v >= 1e12) return String.format(Locale.US, "%.2fT", v / 1e12);
        if (v >= 1e9) return String.format(Locale.US, "%.2fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.US, "%.2fM", v / 1e6);
        if (v >= 1e3) return String.format(Locale.US, "%.1fK", v / 1e3);
        return String.format(Locale.US, "%.0f", v);
    }

    private static String fmtVal(double v, boolean pct) {
        String s = Math.abs(v - Math.rint(v)) < 0.05
                ? String.valueOf(Math.round(v))
                : String.format(Locale.US, "%.1f", v);
        return pct ? (s + "%") : s;
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String joinList(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
