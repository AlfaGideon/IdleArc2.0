package com.idlearc.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Расширенные серверные анализаторы Druid Helper v3.1 (все 16 функций поверх /api/dashboard):
 * - Монитор текущего боя и этажа
 * - Калькулятор ROI вложения ресурсов (Пыль, Осколки петов, Золото, Склад кузницы)
 * - Бесплатный Авто-перестановщик 20 самоцветов (primaries_preview × multiplier)
 * - Точная панель Конверсии Оверкапа Крита (critOvercapBreakdown)
 * - Трекер Сетов снаряжения и Стихий (gearSetStatus)
 * - Аудит Пресетов, Авто-разбора, Авто-перековки и Новых Рун
 * - Вознесение, Звёздные вехи, Гильдия, Топ игроков, Зоны и Патчноуты
 * - Интерактивный What-If симулятор («Примерочная статов»)
 */
public final class ApiFeatureAnalyzers {

    private ApiFeatureAnalyzers() {
    }

    // =========================================================================
    // ФУНКЦИЯ 4: МОНИТОР ТЕКУЩЕГО БОЯ И ЭФФЕКТИВНОСТИ ЭТАЖА
    // =========================================================================
    public static class CombatStatus {
        public final String biomeName;
        public final int monsterLevel;
        public final int selectedMonsterLevel;
        public final double floorProgressPct;
        public final double heroHpPct;
        public final boolean isRespawning;
        public final boolean floorPaused;
        public final String monsterName;
        public final double monsterMaxHp;
        public final double estimatedPetHitsToKill;
        public final String recommendation;

        public CombatStatus(String biomeName, int monsterLevel, int selectedMonsterLevel,
                            double floorProgressPct, double heroHpPct, boolean isRespawning,
                            boolean floorPaused, String monsterName, double monsterMaxHp,
                            double estimatedPetHitsToKill, String recommendation) {
            this.biomeName = biomeName;
            this.monsterLevel = monsterLevel;
            this.selectedMonsterLevel = selectedMonsterLevel;
            this.floorProgressPct = floorProgressPct;
            this.heroHpPct = heroHpPct;
            this.isRespawning = isRespawning;
            this.floorPaused = floorPaused;
            this.monsterName = monsterName;
            this.monsterMaxHp = monsterMaxHp;
            this.estimatedPetHitsToKill = estimatedPetHitsToKill;
            this.recommendation = recommendation;
        }
    }

    public static CombatStatus analyzeCombat(Map<String, Object> d, DruidProfile p) {
        if (d == null) return null;
        String biome = RuGameDictionary.humanizeSlug(SimpleJson.str(d, "currentBiome", "Текущая зона"));
        int mLvl = SimpleJson.intVal(d, "monsterLevel", 1);
        int selLvl = SimpleJson.intVal(d, "selectedMonsterLevel", mLvl);
        double prog = SimpleJson.num(d, "monsterLevelProgress", 0.0);
        Map<String, Object> mlpMap = SimpleJson.asMap(d.get("monsterLevelProgress"));
        if (mlpMap != null) {
            double pct = SimpleJson.num(mlpMap, "percentage", SimpleJson.num(mlpMap, "percent", SimpleJson.num(mlpMap, "progress", -1.0)));
            if (pct >= 0.0) {
                prog = pct;
            } else {
                double k = SimpleJson.num(mlpMap, "kills", SimpleJson.num(mlpMap, "current", 0.0));
                double req = SimpleJson.num(mlpMap, "kills_required", SimpleJson.num(mlpMap, "required", 0.0));
                if (req > 0.0) prog = (k / req) * 100.0;
            }
        }
        if (prog > 0.0 && prog <= 1.0) prog *= 100.0;

        double hpPct = SimpleJson.num(d, "currentHealthPercentage", 100.0);
        if (hpPct > 0.0 && hpPct <= 1.0 && SimpleJson.num(d, "currentHealth", 100) < SimpleJson.num(d, "maxHealth", 100) * 0.99) {
            hpPct *= 100.0;
        }
        boolean resp = SimpleJson.bool(d, "isRespawning", false);
        boolean paused = SimpleJson.bool(d, "monsterLevelPaused", false);

        Map<String, Object> monInst = SimpleJson.asMap(d.get("currentMonsterInstance"));
        if (monInst == null) monInst = SimpleJson.asMap(d.get("currentMonster"));
        String monName = monInst != null ? SimpleJson.str(monInst, "name", "Противник") : "Противник";
        double monMaxHp = monInst != null ? SimpleJson.num(monInst, "maxHealth", SimpleJson.num(monInst, "health", 0.0)) : 0.0;

        Map<String, Object> bs = SimpleJson.asMap(d.get("bonusStats"));
        double petAvg = bs != null ? SimpleJson.num(bs, "petDamageFinalAvg", 0.0) : 0.0;
        double hits = (monMaxHp > 0 && petAvg > 0) ? Math.max(1.0, monMaxHp / petAvg) : 0.0;

        String rec;
        if (resp) {
            rec = "⚠️ Герой возрождается! Текущий этаж пробивает защиту — снизь этаж на 2–5 ур. или проверь кап СУ 95%.";
        } else if (hpPct < 45.0) {
            rec = "⚠️ Здоровье просело ниже 45% — бой идёт на грани, для стабильного АФК-фарма выгоднее спуститься чуть ниже.";
        } else if (paused && selLvl < mLvl) {
            rec = "💎 Фарм закреплённого этажа (" + selLvl + " из " + mLvl + ") — проверь скорость убийства мобов.";
        } else if (hits > 12.0) {
            rec = "🐢 На одного моба уходит ~" + Math.round(hits) + " ударов пета — для быстрого фарма дропа выгоднее этаж на 3–5 ур. ниже.";
        } else if (hits > 0 && hits <= 2.2 && paused) {
            rec = "🚀 Питомец сносит мобов с 1–2 ударов, а этаж на паузе — сними паузу, у тебя отличный запас для Пуша!";
        } else {
            rec = "✅ Темп боя стабильный: HP держится уверенно, питомец быстро чистит волну.";
        }

        return new CombatStatus(biome, mLvl, selLvl, prog, Math.max(0.0, Math.min(100.0, hpPct)),
                resp, paused, monName, monMaxHp, hits, rec);
    }

    // =========================================================================
    // ФУНКЦИЯ 2 + 9: КАЛЬКУЛЯТОР ВЛОЖЕНИЯ РЕСУРСОВ (ПЫЛЬ, ОСКОЛКИ, ЗОЛОТО, СКЛАД)
    // =========================================================================
    public static class ResourceRoiReport {
        public final double gold;
        public final double rubies;
        public final double gemDust;
        public final double petShards;
        public final int petEggs;
        public final double elementalShards;
        public final int affordableSocketUpgrades;
        public final List<String> dustStepPlan;
        public final List<String> petShardPlan;
        public final List<String> goldUpgradeHints;
        public final List<String> warehouseLines;

        public ResourceRoiReport(double gold, double rubies, double gemDust, double petShards,
                                 int petEggs, double elementalShards, int affordableSocketUpgrades,
                                 List<String> dustStepPlan, List<String> petShardPlan,
                                 List<String> goldUpgradeHints, List<String> warehouseLines) {
            this.gold = gold;
            this.rubies = rubies;
            this.gemDust = gemDust;
            this.petShards = petShards;
            this.petEggs = petEggs;
            this.elementalShards = elementalShards;
            this.affordableSocketUpgrades = affordableSocketUpgrades;
            this.dustStepPlan = dustStepPlan;
            this.petShardPlan = petShardPlan;
            this.goldUpgradeHints = goldUpgradeHints;
            this.warehouseLines = warehouseLines;
        }
    }

    private static final class SocketStep {
        final String label;
        final int curLvl;
        final double cost;

        SocketStep(String label, int curLvl, double cost) {
            this.label = label;
            this.curLvl = curLvl;
            this.cost = cost;
        }
    }

    private static final class PetAffixStep {
        final String petName;
        final boolean equipped;
        final String statRu;
        final int curLvl;
        final double cost;
        final double score;

        PetAffixStep(String petName, boolean equipped, String statRu, int curLvl, double cost, double score) {
            this.petName = petName;
            this.equipped = equipped;
            this.statRu = statRu;
            this.curLvl = curLvl;
            this.cost = cost;
            this.score = score;
        }
    }

    public static ResourceRoiReport analyzeResourcesAndRoi(Map<String, Object> d, DruidProfile p) {
        if (d == null) return null;
        double gold = SimpleJson.num(d, "goldAmount", 0.0);
        double rubies = SimpleJson.num(d, "rubyAmount", 0.0);
        double dust = SimpleJson.num(d, "gemDust", 0.0);
        double shards = SimpleJson.num(d, "petShardsAmount", 0.0);
        int eggs = SimpleJson.intVal(d, "petEggsAmount", 0);
        double elemShards = SimpleJson.num(d, "elementalShardsAmount", 0.0);

        // 1. План прокачки 20 оправ за текущую Пыль (next_cost)
        List<SocketStep> steps = new ArrayList<>();
        Map<String, Object> tracks = SimpleJson.asMap(d.get("gemSockets"));
        if (tracks != null) {
            for (Map.Entry<String, Object> e : tracks.entrySet()) {
                Map<String, Object> tr = SimpleJson.asMap(e.getValue());
                if (tr == null) continue;
                List<Object> socks = SimpleJson.asList(tr.get("sockets"));
                if (socks == null) continue;
                for (Object so : socks) {
                    Map<String, Object> sk = SimpleJson.asMap(so);
                    if (sk == null || !SimpleJson.bool(sk, "unlocked", false)) continue;
                    double cost = SimpleJson.num(sk, "next_cost", 0.0);
                    int sLvl = SimpleJson.intVal(sk, "setting_level", 0);
                    int idx = SimpleJson.intVal(sk, "index", 1);
                    if (cost > 0) {
                        steps.add(new SocketStep(ruTrackLabel(e.getKey()) + " #" + idx, sLvl, cost));
                    }
                }
            }
        }
        Collections.sort(steps, new Comparator<SocketStep>() {
            @Override
            public int compare(SocketStep a, SocketStep b) {
                return Double.compare(a.cost, b.cost);
            }
        });

        double remDust = dust;
        int canBuyCount = 0;
        List<String> dustPlan = new ArrayList<>();
        for (SocketStep st : steps) {
            if (remDust >= st.cost) {
                remDust -= st.cost;
                canBuyCount++;
                if (dustPlan.size() < 6) {
                    dustPlan.add(String.format(Locale.US, "✅ %s (ур.%d → %d) за %s пыли",
                            st.label, st.curLvl, st.curLvl + 1, RuGameDictionary.fmtBig(st.cost)));
                }
            } else if (dustPlan.isEmpty()) {
                double pct = dust > 0 ? (dust / st.cost * 100.0) : 0.0;
                dustPlan.add(String.format(Locale.US,
                        "⏳ Ближайший апгрейд оправы: %s (ур.%d → %d) стоит %s пыли (накоплено %.0f%%)",
                        st.label, st.curLvl, st.curLvl + 1, RuGameDictionary.fmtBig(st.cost), pct));
                break;
            }
        }
        if (canBuyCount > dustPlan.size()) {
            dustPlan.add("... и ещё +" + (canBuyCount - dustPlan.size()) + " уровней оправ прямо сейчас!");
        }

        // 2. Самые выгодные апгрейды аффиксов петов за Осколки питомцев (upgrade_cost)
        List<PetAffixStep> petSteps = new ArrayList<>();
        List<String> shardPlan = new ArrayList<>();
        List<Object> pets = SimpleJson.asList(d.get("characterPets"));
        if (pets != null) {
            for (Object po : pets) {
                Map<String, Object> pm = SimpleJson.asMap(po);
                if (pm == null) continue;
                boolean eq = SimpleJson.bool(pm, "is_equipped", false);
                String pName = SimpleJson.str(pm, "name", "Питомец");

                int evAtMax = SimpleJson.intVal(pm, "evolve_affixes_at_max", 0);
                int evReq = SimpleJson.intVal(pm, "evolve_affixes_required", 0);
                boolean canEv = SimpleJson.bool(pm, "can_evolve", false);
                if (eq && canEv) {
                    shardPlan.add("🌟 «" + pName + "» ГОТОВ К ЭВОЛЮЦИИ (цена: "
                            + SimpleJson.intVal(pm, "evolve_cost_rubies", 0) + " рубинов)!");
                } else if (eq && evReq > 0 && evAtMax < evReq) {
                    shardPlan.add(String.format(Locale.US,
                            "🐾 Эволюция «%s»: готово макс-аффиксов %d/%d", pName, evAtMax, evReq));
                }

                List<Object> affs = SimpleJson.asList(pm.get("affixes"));
                if (affs == null) continue;
                for (Object ao : affs) {
                    Map<String, Object> am = SimpleJson.asMap(ao);
                    if (am == null || !SimpleJson.bool(am, "can_upgrade", true)) continue;
                    String aKey = SimpleJson.str(am, "name", "");
                    int aLvl = SimpleJson.intVal(am, "level", 1);
                    double cost = SimpleJson.num(am, "upgrade_cost", 0.0);
                    if (cost <= 0) continue;
                    double weight = scorePetAffixWeight(aKey, p != null ? p.mode : DruidProfile.BuildMode.PUSH);
                    if (eq) weight *= 3.0;
                    petSteps.add(new PetAffixStep(pName, eq, RuGameDictionary.statName(aKey),
                            aLvl, cost, weight / Math.max(1.0, cost)));
                }
            }
        }
        Collections.sort(petSteps, new Comparator<PetAffixStep>() {
            @Override
            public int compare(PetAffixStep a, PetAffixStep b) {
                return Double.compare(b.score, a.score);
            }
        });
        for (int i = 0; i < Math.min(5, petSteps.size()); i++) {
            PetAffixStep ps = petSteps.get(i);
            String mark = shards >= ps.cost ? "✅ Доступно" : "⏳";
            shardPlan.add(String.format(Locale.US, "%s %s%s: «%s» (ур.%d) — %s осколков",
                    mark, ps.petName, ps.equipped ? " [В отряде]" : "",
                    ps.statRu, ps.curLvl, RuGameDictionary.fmtBig(ps.cost)));
        }

        // 3. Прокачки за Золото
        List<String> goldHints = new ArrayList<>();
        double cdCost = SimpleJson.num(d, "criticalDamageUpgradeCost", 0.0);
        if (cdCost > 0) {
            if (gold >= cdCost) {
                int estLvls = (int) Math.max(1, Math.min(99, Math.floor(gold / cdCost)));
                goldHints.add("✅ Хватает золота на прокачку Крит. урона (~до " + estLvls
                        + " нажатий, след. цена " + RuGameDictionary.fmtBig(cdCost) + ")");
            } else {
                goldHints.add("⏳ След. уровень Крит. урона за золото: "
                        + RuGameDictionary.fmtBig(cdCost) + " (есть " + RuGameDictionary.fmtBig(gold) + ")");
            }
        }

        // 4. Склад кузницы (craftingMaterials) на чистом русском языке
        List<String> wh = new ArrayList<>();
        Object cmObj = d.get("craftingMaterials");
        if (cmObj instanceof List) {
            for (Object mo : (List<?>) cmObj) {
                Map<String, Object> mm = SimpleJson.asMap(mo);
                if (mm == null) continue;
                String mName = RuGameDictionary.materialNameRu(
                        SimpleJson.str(mm, "slug", SimpleJson.str(mm, "name", "Материал")));
                double qty = SimpleJson.num(mm, "quantity", SimpleJson.num(mm, "amount", 0.0));
                if (qty > 0 && wh.size() < 12) {
                    wh.add(mName + ": " + RuGameDictionary.fmtBig(qty));
                }
            }
        } else if (cmObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cmMap = (Map<String, Object>) cmObj;
            for (Map.Entry<String, Object> e : cmMap.entrySet()) {
                double qty = SimpleJson.num(cmMap, e.getKey(), 0.0);
                if (qty > 0 && wh.size() < 12) {
                    wh.add(RuGameDictionary.materialNameRu(e.getKey()) + ": " + RuGameDictionary.fmtBig(qty));
                }
            }
        }

        return new ResourceRoiReport(gold, rubies, dust, shards, eggs, elemShards,
                canBuyCount, dustPlan, shardPlan, goldHints, wh);
    }

    // =========================================================================
    // ФУНКЦИЯ 8: АВТО-ПЕРЕСТАНОВЩИК 20 КАМНЕЙ ЗА 0 РЕСУРСОВ (primaries_preview)
    // =========================================================================
    private static final class SocketedGemSlot {
        final String label;
        final GemSocketOptimizer.SocketCategory category;
        final double socketMult;
        final GemSocketOptimizer.GemFamily family;
        final int quality;
        final boolean pristine;

        SocketedGemSlot(String label, GemSocketOptimizer.SocketCategory category,
                        double socketMult, GemSocketOptimizer.GemFamily family,
                        int quality, boolean pristine) {
            this.label = label;
            this.category = category;
            this.socketMult = socketMult;
            this.family = family;
            this.quality = quality;
            this.pristine = pristine;
        }
    }

    public static List<String> findFreeGemSwaps(Map<String, Object> d, DruidProfile p) {
        List<String> swaps = new ArrayList<>();
        if (d == null || p == null) return swaps;
        Map<String, Object> tracks = SimpleJson.asMap(d.get("gemSockets"));
        if (tracks == null) return swaps;

        LiveBuildSimulator.HeroState hs = LiveBuildSimulator.buildHeroState(p, 60.0, 0.0, 0.0);
        List<SocketedGemSlot> slots = new ArrayList<>();

        for (Map.Entry<String, Object> e : tracks.entrySet()) {
            Map<String, Object> tr = SimpleJson.asMap(e.getValue());
            if (tr == null) continue;
            GemSocketOptimizer.SocketCategory cat = mapTrackCat(e.getKey());
            List<Object> socks = SimpleJson.asList(tr.get("sockets"));
            if (socks == null) continue;
            for (Object so : socks) {
                Map<String, Object> sk = SimpleJson.asMap(so);
                if (sk == null || !SimpleJson.bool(sk, "unlocked", false)) continue;
                Map<String, Object> gem = SimpleJson.asMap(sk.get("gem"));
                if (gem == null) continue;
                GemSocketOptimizer.GemFamily fam = mapFam(SimpleJson.str(gem, "family", ""));
                if (fam == null) continue;
                int idx = SimpleJson.intVal(sk, "index", 1);
                double mult = SimpleJson.num(sk, "multiplier", 1.0);
                int qual = SimpleJson.intVal(gem, "quality", 100);
                boolean prist = SimpleJson.bool(gem, "pristine", false);
                slots.add(new SocketedGemSlot(ruTrackLabel(e.getKey()) + " #" + idx, cat, mult, fam, qual, prist));
            }
        }

        // Проверяем все пары вставленных камней из разных категорий слотов (или с разным качеством × множитель)
        boolean[] used = new boolean[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            if (used[i]) continue;
            int bestJ = -1;
            double bestDelta = 0.08; // Порог значимой прибавки >= +0.08%
            for (int j = i + 1; j < slots.size(); j++) {
                if (used[j]) continue;
                SocketedGemSlot a = slots.get(i);
                SocketedGemSlot b = slots.get(j);
                if (a.family == b.family && a.category == b.category && Math.abs(a.socketMult - b.socketMult) < 0.05) {
                    continue;
                }
                double before = evalGemInSlot(hs, a.family, a.category, a.quality, a.socketMult, p.mode)
                              + evalGemInSlot(hs, b.family, b.category, b.quality, b.socketMult, p.mode);
                double after = evalGemInSlot(hs, b.family, a.category, b.quality, a.socketMult, p.mode)
                             + evalGemInSlot(hs, a.family, b.category, a.quality, b.socketMult, p.mode);
                if (after - before > bestDelta) {
                    bestDelta = after - before;
                    bestJ = j;
                }
            }
            if (bestJ >= 0) {
                used[i] = true;
                used[bestJ] = true;
                SocketedGemSlot a = slots.get(i);
                SocketedGemSlot b = slots.get(bestJ);
                swaps.add(String.format(Locale.US,
                        "🔄 Поменяй местами «%s» (%s %d%%) ↔ «%s» (%s %d%%) → бесплатный плюс +%.2f%% к «%s»!",
                        a.label, a.family.ruName, a.quality,
                        b.label, b.family.ruName, b.quality,
                        bestDelta, p.mode.title));
                if (swaps.size() >= 5) break;
            }
        }
        return swaps;
    }

    private static double evalGemInSlot(LiveBuildSimulator.HeroState hs,
                                        GemSocketOptimizer.GemFamily fam,
                                        GemSocketOptimizer.SocketCategory cat,
                                        int quality,
                                        double socketMult,
                                        DruidProfile.BuildMode mode) {
        int approxSettingLvl = Math.max(0, (int) Math.round((socketMult - 1.0) / 0.04));
        List<GemSocketOptimizer.RankedGemChoice<GemSocketOptimizer.GemFamily>> ranked =
                GemSocketOptimizer.rankFamiliesForCategory(hs, cat, GemSocketOptimizer.GemRarity.PERFECT,
                        quality, approxSettingLvl, mode);
        for (GemSocketOptimizer.RankedGemChoice<GemSocketOptimizer.GemFamily> r : ranked) {
            if (r.item == fam) return r.totalGainPct;
        }
        return 0.0;
    }

    // =========================================================================
    // ФУНКЦИЯ 6: ПАНЕЛЬ КОНВЕРСИИ ОВЕРКАПА КРИТА (critOvercapBreakdown)
    // =========================================================================
    public static String buildCritOvercapExplanation(Map<String, Object> d, DruidProfile p) {
        if (d == null || p == null) return "";
        Map<String, Object> bs = SimpleJson.asMap(d.get("bonusStats"));
        Map<String, Object> co = bs != null ? SimpleJson.asMap(bs.get("critOvercapBreakdown")) : null;
        if (co == null || !SimpleJson.bool(co, "enabled", false)) {
            return "Конверсия оверкапа крита не активна: любой Шанс крита выше 100% даёт ровно 0% урона.";
        }
        double overPct = SimpleJson.num(co, "overcap_percent", Math.max(0.0, p.critChance - 100.0));
        double bonusCd = SimpleJson.num(co, "bonus_crit_damage", 0.0);
        double ratio = SimpleJson.num(co, "ratio", 1.0);
        return String.format(Locale.US,
                "Активна конверсия оверкапа крита (1:%.0f): лишние +%.1f%% шанса крита сверх 100%% дают +%.0f%% крит. урона. "
                + "Но учти математику слотов: ролл Шанса крита на предмете (~45%%) в 2–3 раза ниже прямого ролла Крит. урона (~110%%), "
                + "а при твоём Крит. уроне %.0f%% прямые статы «Интеллект» и «Ловкость» умножают отдельные корзины и дают намного больше урона!",
                ratio, overPct, bonusCd, p.critDamage);
    }

    // =========================================================================
    // ФУНКЦИЯ 7: ИНТЕРАКТИВНАЯ «ПРИМЕРОЧНАЯ СТАТОВ» (What-If на предмете)
    // =========================================================================
    public static String simulateWhatIfChange(Map<String, Object> d,
                                              DruidProfile p,
                                              ApiDashboardEngine.ServerItemAudit item,
                                              int posOneBased,
                                              AffixPositionOptimizer.AffixType chosenType,
                                              double quality01) {
        if (item == null || p == null || chosenType == null) return "Нет данных для расчёта.";
        ApiDashboardEngine.ServerPositionEval targetPos = null;
        for (ApiDashboardEngine.ServerPositionEval pe : item.positions) {
            if (pe.index == posOneBased) {
                targetPos = pe;
                break;
            }
        }
        if (targetPos == null && !item.positions.isEmpty()) targetPos = item.positions.get(0);
        if (targetPos == null) return "На предмете нет открытых позиций.";

        Map<String, Object> bs = d != null ? SimpleJson.asMap(d.get("bonusStats")) : null;
        double curPetAvg = bs != null ? SimpleJson.num(bs, "petDamageFinalAvg", 0.0) : 0.0;

        LiveBuildSimulator.HeroState full = LiveBuildSimulator.buildHeroState(p, 60.0, 0.0, 0.0);
        LiveBuildSimulator.HeroState withoutCur = full.copy();
        if (targetPos.currentValue > 0 && targetPos.currentType != AffixPositionOptimizer.AffixType.EMPTY_POSITION) {
            withoutCur.applyAffixDelta(targetPos.currentType, -targetPos.currentValue, 0.0);
        }

        // Калибруем ожидаемое число выбранного аффикса с учётом тира предмета и текущего ★ Пробуждения строки
        double scale = 10.0 + item.tier * 7.5 + item.tierLevel * 0.45;
        for (ApiDashboardEngine.ServerPositionEval pe : item.positions) {
            if (pe.currentType.ignoresAffixCap && pe.rangeMax > 0 && pe.currentType.baseMax > 0) {
                scale = (pe.rangeMax / (1.0 + 0.10 * pe.boostCount)) / pe.currentType.baseMax;
                break;
            }
        }
        double awkMult = 1.0 + 0.10 * Math.max(0, targetPos.boostCount);
        double rawRoll = (chosenType.baseMin + (chosenType.baseMax - chosenType.baseMin) * quality01) * scale;
        if (!chosenType.ignoresAffixCap) rawRoll = Math.min(rawRoll, item.tier >= 6 ? 50.0 : 42.0);
        double newRollVal = rawRoll * awkMult;

        double curGain = (targetPos.currentValue > 0 && targetPos.currentType != AffixPositionOptimizer.AffixType.EMPTY_POSITION)
                ? LiveBuildSimulator.evaluateCandidate(withoutCur, targetPos.currentType, targetPos.currentValue,
                        targetPos.rangeMin, targetPos.rangeMax, 0.0, p.mode).totalObjectiveGainPct
                : 0.0;
        LiveBuildSimulator.CandidateEvaluation newEval = LiveBuildSimulator.evaluateCandidate(
                withoutCur, chosenType, newRollVal, newRollVal * 0.7, newRollVal * 1.15, 0.0, p.mode);

        double netObjDelta = newEval.totalObjectiveGainPct - curGain;
        double curDpsGain = (targetPos.currentValue > 0 && targetPos.currentType != AffixPositionOptimizer.AffixType.EMPTY_POSITION)
                ? LiveBuildSimulator.evaluateCandidate(withoutCur, targetPos.currentType, targetPos.currentValue,
                        targetPos.rangeMin, targetPos.rangeMax, 0.0, DruidProfile.BuildMode.BOSS).dpsGainPct
                : 0.0;
        double newDpsGain = LiveBuildSimulator.evaluateCandidate(
                withoutCur, chosenType, newRollVal, newRollVal, newRollVal, 0.0, DruidProfile.BuildMode.BOSS).dpsGainPct;
        double netDpsDelta = newDpsGain - curDpsGain;

        double projectedPetAvg = curPetAvg > 0 ? curPetAvg * (1.0 + netDpsDelta / 100.0) : 0.0;

        StringBuilder sb = new StringBuilder();
        sb.append("🧪 ПРИМЕРОЧНАЯ: ").append(item.slotLabel).append(" (Позиция #").append(targetPos.index).append(")\n\n");
        sb.append("• Было: ").append(targetPos.currentType.taggedName()).append(" +")
          .append(Math.round(targetPos.currentValue)).append(targetPos.currentType.isPercent ? "%" : "").append("\n");
        sb.append("• Станет (ролл ").append(Math.round(quality01 * 100)).append("%")
          .append(targetPos.boostCount > 0 ? (", ★+" + (targetPos.boostCount * 10) + "%") : "")
          .append("): ").append(chosenType.taggedName()).append(" +")
          .append(Math.round(newRollVal)).append(chosenType.isPercent ? "%" : "").append("\n\n");

        sb.append(String.format(Locale.US, "📈 Итог под «%s»: %+.2f%%\n", p.mode.title, netObjDelta));
        sb.append(String.format(Locale.US, "🐾 Чистый урон питомца: %+.2f%%", netDpsDelta));
        if (curPetAvg > 0) {
            sb.append(" (").append(RuGameDictionary.fmtBig(curPetAvg))
              .append(" → ").append(RuGameDictionary.fmtBig(projectedPetAvg)).append(")");
        }
        sb.append(String.format(Locale.US, "\n🛡️ Живучесть (EHP): %+.2f%%", newEval.ehpGainPct));
        return sb.toString();
    }

    // =========================================================================
    // ФУНКЦИЯ 10 + 5 + 11: СЕТЫ, ПРЕСЕТЫ, АВТО-РАЗБОР, АВТО-ПЕРЕКОВКА И НОВЫЕ РУНЫ
    // =========================================================================
    public static List<String> analyzeGearSetsAndElements(Map<String, Object> d) {
        List<String> lines = new ArrayList<>();
        if (d == null) return lines;

        // Вклад сета в корзинах урона
        Map<String, Object> bs = SimpleJson.asMap(d.get("bonusStats"));
        double setPct = 0.0;
        if (bs != null) {
            List<Object> bd = SimpleJson.asList(bs.get("petDamageBreakdown"));
            if (bd != null) {
                for (Object bo : bd) {
                    Map<String, Object> bm = SimpleJson.asMap(bo);
                    if (bm != null && "gearSet".equals(SimpleJson.str(bm, "key", ""))) {
                        setPct = SimpleJson.num(bm, "pct", 0.0);
                    }
                }
            }
        }
        if (setPct > 0) {
            lines.add(String.format(Locale.US,
                    "🧩 Бонус комплекта снаряжения активен: +%.0f%% к отдельной корзине урона пета (не разбивай сет случайной заменой слота без проверки!)",
                    setPct));
        }

        Object gsObj = d.get("gearSetStatus");
        if (gsObj instanceof List) {
            for (Object go : (List<?>) gsObj) {
                Map<String, Object> gm = SimpleJson.asMap(go);
                if (gm == null) continue;
                String sName = RuGameDictionary.humanizeSlug(SimpleJson.str(gm, "name", SimpleJson.str(gm, "slug", "Комплект")));
                int eqCnt = SimpleJson.intVal(gm, "equipped_count", SimpleJson.intVal(gm, "count", 0));
                if (eqCnt > 0) {
                    lines.add("• Комплект «" + sName + "»: надето предметов — " + eqCnt);
                }
            }
        } else if (gsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> gm = (Map<String, Object>) gsObj;
            String activeName = SimpleJson.str(gm, "name", SimpleJson.str(gm, "active_set", ""));
            int cnt = SimpleJson.intVal(gm, "count", SimpleJson.intVal(gm, "pieces", 0));
            if (!activeName.isEmpty() || cnt > 0) {
                lines.add("• Активный сет: " + (activeName.isEmpty() ? "Собран" : RuGameDictionary.humanizeSlug(activeName))
                        + (cnt > 0 ? (" (" + cnt + " предм.)") : ""));
            }
        }

        double elemShards = SimpleJson.num(d, "elementalShardsAmount", 0.0);
        if (elemShards > 0) {
            lines.add("🔥 Стихийных осколков в запасе: " + RuGameDictionary.fmtBig(elemShards));
        }
        return lines;
    }

    public static List<String> analyzeNewRunesAndAutomation(Map<String, Object> d, DruidProfile p) {
        List<String> lines = new ArrayList<>();
        if (d == null) return lines;

        // Новые руны в сумке (newRunes)
        List<Object> newRunes = SimpleJson.asList(d.get("newRunes"));
        if (newRunes != null && !newRunes.isEmpty()) {
            lines.add("🔮 Новых рун в сумке: " + newRunes.size() + " шт. — проверь вкладку Рун в игре!");
            for (int i = 0; i < Math.min(4, newRunes.size()); i++) {
                Map<String, Object> rm = SimpleJson.asMap(newRunes.get(i));
                if (rm == null) continue;
                String rName = RuGameDictionary.humanizeSlug(SimpleJson.str(rm, "name", SimpleJson.str(rm, "type", "Руна")));
                String rar = RuGameDictionary.itemOrPetRarityRu(SimpleJson.str(rm, "rarity", ""));
                int lvl = SimpleJson.intVal(rm, "level", SimpleJson.intVal(rm, "tier", 1));
                lines.add("  • " + rName + " (" + rar + ", ур. " + lvl + ")");
            }
        } else {
            lines.add("🔮 Новых неразобранных рун в сумке нет.");
        }

        // Активные пресеты
        int gearSlot = SimpleJson.intVal(d, "activeGearPresetSlot", 1);
        int petSlot = SimpleJson.intVal(d, "activePetPresetSlot", 1);
        int skillSlot = SimpleJson.intVal(d, "activeClassSkillPresetSlot", 1);
        int treeSlot = SimpleJson.intVal(d, "activeTreePresetSlot", 1);
        lines.add(String.format(Locale.US,
                "🎛️ Текущие пресеты на сервере: Шмот #%d · Петы #%d · Навыки #%d · Дерево #%d",
                gearSlot, petSlot, skillSlot, treeSlot));

        // Авто-конвертация материалов кузницы
        boolean convFrag = SimpleJson.bool(d, "autoConvertFragmentsEnabled", false);
        boolean convEss = SimpleJson.bool(d, "autoConvertEssencesEnabled", false);
        boolean convMat = SimpleJson.bool(d, "autoConvertMonsterMaterialsEnabled", false);
        lines.add("⚙️ Авто-конвертация кузницы: Фрагменты " + (convFrag ? "✅ ВКЛ" : "⬜ ВЫКЛ")
                + " · Эссенции " + (convEss ? "✅ ВКЛ" : "⬜ ВЫКЛ")
                + " · Материалы биомов " + (convMat ? "✅ ВКЛ" : "⬜ ВЫКЛ"));

        // Совет по стоп-фильтрам авто-ролла надписей талисмана
        if (p != null && p.critChance >= 100.0) {
            lines.add("🤖 Совет для Авто-надписей талисмана: обязательно убери «Шанс крита» из стоп-фильтра (у тебя уже >=100%), оставь «Двойной удар», «Двойной урон» и «Урон питомцев» редк. Эпический/Совершенный!");
        }
        return lines;
    }

    // =========================================================================
    // ФУНКЦИИ 9 + 12 + 13 + 16: ПАССИВКИ, ВОЗНЕСЕНИЕ, ГИЛЬДИЯ, ТОП СЕРВЕРА И ЗОНЫ
    // =========================================================================
    public static List<String> analyzePassivesAscentAndGuild(Map<String, Object> d) {
        List<String> lines = new ArrayList<>();
        if (d == null) return lines;

        // Неизвестная схема не позволяет заключить, что остаток равен нулю.
        lines.add("ℹ️ Остаток очков общего пассивного дерева не подтверждён — проверь в игре.");

        List<Object> passives = SimpleJson.asList(d.get("passives"));
        if (passives != null && !passives.isEmpty()) {
            List<String> pSummary = new ArrayList<>();
            for (Object po : passives) {
                Map<String, Object> pm = SimpleJson.asMap(po);
                if (pm == null) continue;
                String pKey = SimpleJson.str(pm, "name", "");
                int pLvl = SimpleJson.intVal(pm, "level", 0);
                if (pLvl > 0 && pSummary.size() < 6) {
                    pSummary.add(RuGameDictionary.statName(pKey) + " ур." + pLvl);
                }
            }
            if (!pSummary.isEmpty()) {
                lines.add("✨ Прокачанные пассивки: " + join(pSummary, " · "));
            }
        }

        // 2. Вознесение (Ascent) и Звёздные вехи
        double ascPts = SimpleJson.num(d, "ascent_points", 0.0);
        int starTier = SimpleJson.intVal(d, "star_tier", 0);
        if (ascPts > 0 || starTier > 0) {
            lines.add(String.format(Locale.US, "🌟 Вознесение: %s очков · Звёздный ранг: ★%d",
                    RuGameDictionary.fmtBig(ascPts), starTier));
        }
        Map<String, Object> bs = SimpleJson.asMap(d.get("bonusStats"));
        Map<String, Object> ascBon = bs != null ? SimpleJson.asMap(bs.get("ascentBonuses")) : null;
        if (ascBon != null && !ascBon.isEmpty()) {
            List<String> abList = new ArrayList<>();
            for (Map.Entry<String, Object> e : ascBon.entrySet()) {
                double v = SimpleJson.num(ascBon, e.getKey(), 0.0);
                if (v > 0) {
                    abList.add(RuGameDictionary.statName(e.getKey()) + " +" + Math.round(v) + "%");
                }
            }
            if (!abList.isEmpty()) {
                lines.add("🚀 Бонусы Вознесения: " + join(abList, " · "));
            }
        }

        // 3. Гильдия и Ладдер топ-игроков
        Map<String, Object> guild = SimpleJson.asMap(d.get("guild"));
        if (guild != null && !guild.isEmpty()) {
            String gName = SimpleJson.str(guild, "name", "Гильдия");
            int gLvl = SimpleJson.intVal(guild, "level", 0);
            boolean bossReady = SimpleJson.bool(d, "guild_boss_can_fight", false);
            lines.add("🏰 Гильдия «" + gName + "»" + (gLvl > 0 ? (" (ур." + gLvl + ")") : "")
                    + " · Босс гильдии: " + (bossReady ? "🔥 ДОСТУПЕН БОЙ!" : "откат"));
        }

        List<Object> topPlayers = SimpleJson.asList(d.get("top_players"));
        if (topPlayers != null && !topPlayers.isEmpty()) {
            List<String> leaders = new ArrayList<>();
            for (int i = 0; i < Math.min(3, topPlayers.size()); i++) {
                Map<String, Object> tp = SimpleJson.asMap(topPlayers.get(i));
                if (tp == null) continue;
                String tName = SimpleJson.str(tp, "name", SimpleJson.str(tp, "character_name", "Игрок"));
                int tLvl = SimpleJson.intVal(tp, "level", 0);
                leaders.add("#" + (i + 1) + " " + tName + (tLvl > 0 ? (" (ур." + tLvl + ")") : ""));
            }
            if (!leaders.isEmpty()) {
                lines.add("🏆 Ориентир Топ-3 сервера: " + join(leaders, " · "));
            }
        }

        // 4. Открытые биомы и свежие объявления разработчика
        List<Object> biomes = SimpleJson.asList(d.get("unlockedBiomeIds"));
        if (biomes != null && !biomes.isEmpty()) {
            lines.add("🗺️ Открыто биомов мира: " + biomes.size());
        }
        String ann = SimpleJson.str(d, "announcement", "");
        if (!ann.isEmpty() && !"null".equalsIgnoreCase(ann)) {
            lines.add("📢 Объявление сервера: " + ann);
        }

        return lines;
    }

    private static double scorePetAffixWeight(String key, DruidProfile.BuildMode mode) {
        String k = key != null ? key.toLowerCase(Locale.ROOT) : "";
        if (mode == DruidProfile.BuildMode.FARM) {
            if (k.contains("rune") || k.contains("ruby") || k.contains("item") || k.contains("extra_kill") || k.contains("material")) {
                return 100.0;
            }
        }
        if (k.contains("all_skills") || k.contains("pet_damage") || k.contains("double_hit") || k.contains("double_damage")) {
            return 95.0;
        }
        if (k.contains("boss") && mode == DruidProfile.BuildMode.BOSS) return 90.0;
        if (k.contains("intelligence") || k.contains("dexterity")) return 70.0;
        return 30.0;
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

    private static String ruTrackLabel(String track) {
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

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
