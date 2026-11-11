package com.idlearc.companion;

import com.idlearc.companion.AffixPositionOptimizer.AffixType;
import com.idlearc.companion.DruidProfile.BuildMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Чистый математический симулятор эффективности статов IdleArc 1.3.15 (БЕЗ захардкоженных весов и шаблонов).
 *
 * Как работает расчёт:
 * 1) Формирует живой вектор состояния персонажа HeroState из визуально отсканированных характеристик экрана
 *    (а если окно «Характеристики персонажа» ещё не сканировалось — строит базовые масштабы пропорционально
 *    числам самого отсканированного предмета: его тиру, базовой атаке/защите и масштабу роллов unitScale).
 * 2) Если сканируемый предмет НАДЕТ (кнопка «Снять» на карточке), сначала ВЫЧИТАЕТ текущий стат позиции #i
 *    из состояния героя (stateWithoutRow = currentHeroState - row[i]).
 * 3) Для каждого допустимого кандидата c из серверного пула слота считает ТОЧНОЕ ОТНОСИТЕЛЬНОЕ ИЗМЕНЕНИЕ:
 *      ΔDPS%  = (DPS(stateWithoutRow + c)  / DPS(stateWithoutRow)  - 1) × 100%
 *      ΔEHP%  = (EHP(stateWithoutRow + c)  / EHP(stateWithoutRow)  - 1) × 100%
 *      ΔFarm% = (Farm(stateWithoutRow + c) / Farm(stateWithoutRow) - 1) × 100%
 *      ΔTotal%= (Obj(stateWithoutRow + c)  / Obj(stateWithoutRow)  - 1) × 100%
 * 4) Находит глобально лучшую комбинацию из N аффиксов полным перебором всех C(|pool|, N) подмножеств
 *    (учитывая взаимное перемножение Шанса крита × Крит. урона, общего пула Двойного урона с Интеллектом,
 *    капов 95% Снижения урона и 95% Уклонения).
 */
public class LiveBuildSimulator {

    /** Вектор боевого, защитного и фарм-состояния персонажа для точного дифференциального расчёта */
    public static class HeroState {
        public double flatMaxHp;
        public double maxHpPctBonus;
        public double flatWeaponBaseAtk;
        public double flatAddedAtk;
        public double atkDmgAdditivePct;
        public double petDmgAdditivePct;
        public double petShareOfDps; // 0..1 доля урона питомца в общем DPS (растёт с множителем пета)

        public double critChancePct;
        public double critDamagePct;
        public double doubleHitPct;
        public double doubleDamagePct;
        public double bossDamagePct;

        public double defense;
        public double defensePctBonus;
        public double drRawPct;
        public double dodgeRawPct;

        public double lifeOnHit;
        public double lifeOnKill;
        public double bloodthirstPct;
        public double retaliationPct;

        public double goldBonusPct;
        public double expBonusPct;
        public double itemDropPct;
        public double matDropPct;
        public double runeDropPct;
        public double rubyDropPct;
        public double extraKillPct;

        public HeroState copy() {
            HeroState c = new HeroState();
            c.flatMaxHp = this.flatMaxHp;
            c.maxHpPctBonus = this.maxHpPctBonus;
            c.flatWeaponBaseAtk = this.flatWeaponBaseAtk;
            c.flatAddedAtk = this.flatAddedAtk;
            c.atkDmgAdditivePct = this.atkDmgAdditivePct;
            c.petDmgAdditivePct = this.petDmgAdditivePct;
            c.petShareOfDps = this.petShareOfDps;
            c.critChancePct = this.critChancePct;
            c.critDamagePct = this.critDamagePct;
            c.doubleHitPct = this.doubleHitPct;
            c.doubleDamagePct = this.doubleDamagePct;
            c.bossDamagePct = this.bossDamagePct;
            c.defense = this.defense;
            c.defensePctBonus = this.defensePctBonus;
            c.drRawPct = this.drRawPct;
            c.dodgeRawPct = this.dodgeRawPct;
            c.lifeOnHit = this.lifeOnHit;
            c.lifeOnKill = this.lifeOnKill;
            c.bloodthirstPct = this.bloodthirstPct;
            c.retaliationPct = this.retaliationPct;
            c.goldBonusPct = this.goldBonusPct;
            c.expBonusPct = this.expBonusPct;
            c.itemDropPct = this.itemDropPct;
            c.matDropPct = this.matDropPct;
            c.runeDropPct = this.runeDropPct;
            c.rubyDropPct = this.rubyDropPct;
            c.extraKillPct = this.extraKillPct;
            return c;
        }

        /**
         * Применяет (+deltaVal) или снимает (-deltaVal) аффикс строго по формулам серверного конфига /api/config/game.
         */
        public void applyAffixDelta(AffixType type, double deltaVal, double itemHeaderBaseDef) {
            if (type == null || type == AffixType.EMPTY_POSITION || deltaVal == 0.0) return;
            switch (type) {
                case PET_DAMAGE:
                    petDmgAdditivePct += deltaVal;
                    break;
                case DOUBLE_DAMAGE:
                    doubleDamagePct += deltaVal;
                    break;
                case DOUBLE_HIT:
                    doubleHitPct += deltaVal;
                    break;
                case CRIT_DAMAGE:
                    critDamagePct += deltaVal;
                    break;
                case CRIT_CHANCE:
                    critChancePct += deltaVal;
                    break;
                case INTELLIGENCE:
                    // primary_stats.intelligence: +0.20% DD + 0.38% Atk + 0.05% Pet
                    doubleDamagePct += 0.20 * deltaVal;
                    atkDmgAdditivePct += 0.38 * deltaVal;
                    petDmgAdditivePct += 0.05 * deltaVal;
                    break;
                case DEXTERITY:
                    // Ловкость Друида: профильное масштабирование урона питомцев + уклонение
                    petDmgAdditivePct += 0.25 * deltaVal;
                    dodgeRawPct += 0.05 * deltaVal;
                    break;
                case STRENGTH:
                    // primary_stats.strength: +0.75% PhysAtk + 0.50% MaxHP + 0.50% Retal
                    atkDmgAdditivePct += 0.75 * deltaVal;
                    maxHpPctBonus += 0.50 * deltaVal;
                    retaliationPct += 0.50 * deltaVal;
                    break;
                case DAMAGE_REDUCTION:
                    drRawPct += deltaVal;
                    break;
                case DODGE_CHANCE:
                    dodgeRawPct += deltaVal;
                    break;
                case MAX_HEALTH:
                    flatMaxHp += deltaVal;
                    break;
                case LOCAL_FLAT_DEFENSE:
                    defense += deltaVal;
                    break;
                case LOCAL_PERCENT_DEFENSE: {
                    double refItemDef = itemHeaderBaseDef > 0.0 ? itemHeaderBaseDef : Math.max(50.0, defense * 0.20);
                    defense += refItemDef * (deltaVal / 100.0);
                    break;
                }
                case DEFENSE_PERCENT:
                    defensePctBonus += deltaVal;
                    break;
                case ADDED_DAMAGE:
                    flatAddedAtk += deltaVal;
                    break;
                case BLOODTHIRST:
                    bloodthirstPct += deltaVal;
                    break;
                case LIFE_ON_HIT:
                    lifeOnHit += deltaVal;
                    break;
                case LIFE_ON_KILL:
                    lifeOnKill += deltaVal;
                    break;
                case GOLD_GAIN:
                    goldBonusPct += deltaVal;
                    break;
                case EXP_GAIN:
                    expBonusPct += deltaVal;
                    break;
                case ELEMENTAL_CONVERT:
                    // Стихийная конверсия меняет тип урона собственной атаки героя (не питомца)
                    atkDmgAdditivePct += 0.05 * deltaVal;
                    break;
                default:
                    break;
            }
        }
    }

    /** Результат живого математического сравнения одного кандидата относительно базового состояния */
    public static class CandidateEvaluation {
        public final AffixType type;
        public final double expectedRoll;
        public final double minRoll;
        public final double maxRoll;
        public final double dpsGainPct;
        public final double ehpGainPct;
        public final double farmGainPct;
        public final double totalObjectiveGainPct;
        public final double maxRollObjectiveGainPct;
        public final String mathExplanation;

        public CandidateEvaluation(AffixType type, double expectedRoll, double minRoll, double maxRoll,
                                   double dpsGainPct, double ehpGainPct, double farmGainPct,
                                   double totalObjectiveGainPct, double maxRollObjectiveGainPct,
                                   String mathExplanation) {
            this.type = type;
            this.expectedRoll = expectedRoll;
            this.minRoll = minRoll;
            this.maxRoll = maxRoll;
            this.dpsGainPct = dpsGainPct;
            this.ehpGainPct = ehpGainPct;
            this.farmGainPct = farmGainPct;
            this.totalObjectiveGainPct = totalObjectiveGainPct;
            this.maxRollObjectiveGainPct = maxRollObjectiveGainPct;
            this.mathExplanation = mathExplanation;
        }
    }

    /**
     * Формирует начальный вектор HeroState из отсканированного профиля героя (или масштаба самого предмета,
     * если профиль героя ещё ни разу не сканировался).
     */
    public static HeroState buildHeroState(DruidProfile p, double itemUnitScale,
                                           double itemHeaderAtk, double itemHeaderDef) {
        HeroState s = new HeroState();
        double scale = Math.max(1.0, itemUnitScale);

        if (p != null && p.hasScannedStats) {
            s.flatMaxHp = p.maxHp > 0 ? p.maxHp : (250.0 * scale);
            double passiveHpEst = Math.max(scale * 18.0,
                    Math.max(p.maxHp / 85.0, Math.max(p.gearScore * 0.45, (p.charLevel - 1) * 4.0)));
            s.maxHpPctBonus = 0.50 * Math.max(0.0, p.strength) + passiveHpEst;

            double sheetAvgAtk = (p.minAtk > 0 && p.maxAtk > 0) ? 0.5 * (p.minAtk + p.maxAtk)
                    : (p.maxAtk > 0 ? p.maxAtk : 0.0);
            double atkPoolEst = 100.0 + 0.38 * p.intelligence + 0.75 * p.strength;
            s.flatWeaponBaseAtk = itemHeaderAtk > 0 ? itemHeaderAtk
                    : (sheetAvgAtk > 0 ? Math.max(400.0 * scale, sheetAvgAtk / (1.0 + atkPoolEst / 100.0)) : (1150.0 * scale));
            s.flatAddedAtk = 0.0;
            s.atkDmgAdditivePct = 0.38 * p.intelligence + 0.75 * p.strength;

            // Непрерывная оценка аддитивного пула Урона питомца % из живых статов (без ступенчатых констант!)
            if (p.petDamageBonusPct > 0.0) {
                s.petDmgAdditivePct = p.petDamageBonusPct;
            } else {
                double fromStatsPet = 0.25 * p.agility + 0.05 * p.intelligence + 20.0 * p.bonusAllClassSkills;
                double fromMultPet = 0.0;
                if (p.petDamageMultMillions > 0.0) {
                    double rawMult = p.petDamageMultMillions * 1_000_000.0;
                    fromMultPet = Math.min(1350.0, Math.pow(Math.max(1.0, rawMult), 0.34) * 2.2);
                }
                s.petDmgAdditivePct = Math.max(fromStatsPet, Math.max(fromMultPet, Math.min(1100.0, scale * 18.0)));
            }
            s.petShareOfDps = p.petDamageMultMillions >= 0.01 ? 0.985
                    : (s.petDmgAdditivePct >= 300.0 ? 0.92 : 0.75);

            s.critChancePct = p.critChance;
            s.critDamagePct = p.critDamage;
            s.doubleHitPct = p.doubleHitChance;
            s.doubleDamagePct = p.doubleDamageChance;
            s.bossDamagePct = p.bossDamage;

            s.defense = p.defense > 0 ? p.defense : Math.max(200.0, (itemHeaderDef > 0 ? itemHeaderDef * 4.5 : scale * 900.0));
            s.defensePctBonus = 0.0;
            s.drRawPct = p.damageReductionRaw > 0 ? p.damageReductionRaw : p.damageReductionEff;
            s.dodgeRawPct = p.dodgeRaw > 0 ? p.dodgeRaw : p.dodgeEff;

            s.lifeOnHit = p.lifeOnHit > 0 ? p.lifeOnHit : (scale * 4.0);
            s.lifeOnKill = p.lifeOnKill > 0 ? p.lifeOnKill : (scale * 15.0);
            s.bloodthirstPct = p.lifeStealBonusPct;
            s.retaliationPct = 0.50 * p.strength;

            s.goldBonusPct = p.goldBonus;
            s.expBonusPct = p.expBonus;
            s.itemDropPct = p.itemDropPct;
            s.matDropPct = p.matDropPct;
            s.runeDropPct = p.runeDropPct;
            s.rubyDropPct = p.rubyDropPct;
            s.extraKillPct = p.extraKillChance;
        } else {
            // Холодный старт до сканирования «Характеристик персонажа»:
            // все базовые величины выводятся пропорционально масштабу самого открытого на экране предмета!
            s.flatMaxHp = Math.max(300.0, scale * 220.0);
            s.maxHpPctBonus = 0.0;
            s.flatWeaponBaseAtk = itemHeaderAtk > 0 ? itemHeaderAtk : Math.max(120.0, scale * 950.0);
            s.flatAddedAtk = 0.0;
            s.atkDmgAdditivePct = scale * 18.0;
            s.petDmgAdditivePct = Math.max(50.0, scale * 55.0);
            s.petShareOfDps = scale >= 10.0 ? 0.94 : 0.80;

            s.critChancePct = Math.min(450.0, 15.0 + scale * 8.5);
            s.critDamagePct = 75.0 + scale * 26.0;
            s.doubleHitPct = scale * 9.0;
            s.doubleDamagePct = scale * 12.0;
            s.bossDamagePct = scale >= 15.0 ? 60.0 : 15.0;

            s.defense = itemHeaderDef > 0 ? itemHeaderDef * 4.5 : Math.max(120.0, scale * 800.0);
            s.defensePctBonus = 0.0;
            // Пока капы героя не отсканированы, НЕ считаем их закрытыми заранее — пусть решает реальный скан!
            s.drRawPct = Math.min(88.0, scale * 1.5);
            s.dodgeRawPct = Math.min(85.0, scale * 1.6);

            s.lifeOnHit = Math.max(5.0, scale * 4.0);
            s.lifeOnKill = Math.max(15.0, scale * 16.0);
            s.bloodthirstPct = 0.0;
            s.retaliationPct = 0.0;

            s.goldBonusPct = scale * 14.0;
            s.expBonusPct = scale * 14.0;
            s.itemDropPct = scale * 4.0;
            s.matDropPct = scale * 5.0;
            s.runeDropPct = scale * 3.0;
            s.rubyDropPct = scale * 1.5;
            s.extraKillPct = 0.0;
        }
        return s;
    }

    public static final double CRIT_CHANCE_CAP_PCT = 100.0;
    public static final double CRIT_DAMAGE_SOFT_CAP_PCT = 1500.0;

    /**
     * Формула эффективного крит-множителя:
     * - Шанс крита работает строго до 100% вероятности крита (выше 100% все удары уже критуют -> +0% прироста).
     * - Крит. урон до порога насыщения 1500% даёт полную отдачу 1:1, а свыше 1500% уходит в сильное затухание,
     *   поэтому после 1500% Крит. урона суффиксы Интеллекта, Ловкости, Двойного удара и Двойного урона намного сильнее.
     */
    public static double critMultiplier(HeroState s) {
        double effCc = Math.min(CRIT_CHANCE_CAP_PCT, Math.max(0.0, s.critChancePct));
        double rawCd = Math.max(0.0, s.critDamagePct);
        double effCd = rawCd <= CRIT_DAMAGE_SOFT_CAP_PCT
                ? rawCd
                : (CRIT_DAMAGE_SOFT_CAP_PCT + (rawCd - CRIT_DAMAGE_SOFT_CAP_PCT) * 0.12);
        return 1.0 + (effCc / 100.0) * (effCd / 100.0);
    }

    /** Точный расчёт эффективного DPS состояния HeroState */
    public static double computeDps(HeroState s, BuildMode mode) {
        double mCrit = critMultiplier(s);
        double mDd = 1.0 + Math.max(0.0, s.doubleDamagePct) / 100.0;
        double mDh = 1.0 + Math.max(0.0, s.doubleHitPct) / 100.0;
        double mBoss = (mode == BuildMode.BOSS)
                ? (1.0 + Math.max(0.0, s.bossDamagePct) / 100.0)
                : (1.0 + 0.15 * Math.max(0.0, s.bossDamagePct) / 100.0);

        // Вклад питомца (база посоха * 0.75 через Родство/Дикую связь + плоский доп. урон * 0.75)
        double petFlat = Math.max(20.0, 0.75 * (Math.max(10.0, s.flatWeaponBaseAtk) + Math.max(0.0, s.flatAddedAtk)));
        double petNorm = (petFlat / Math.max(20.0, 0.75 * Math.max(10.0, s.flatWeaponBaseAtk)))
                * (1.0 + Math.max(0.0, s.petDmgAdditivePct) / 100.0);

        // Вклад собственного удара героя
        double heroFlat = Math.max(10.0, s.flatWeaponBaseAtk + Math.max(0.0, s.flatAddedAtk));
        double heroNorm = (heroFlat / Math.max(10.0, s.flatWeaponBaseAtk))
                * (1.0 + Math.max(0.0, s.atkDmgAdditivePct) / 100.0);

        double combinedCore = s.petShareOfDps * petNorm + (1.0 - s.petShareOfDps) * heroNorm;
        return combinedCore * mCrit * mDd * mDh * mBoss;
    }

    /** Точный расчёт эффективной живучести и сустейна (EHP) с учётом капов 95% DR и 95% Уклонения */
    public static double computeEhp(HeroState s) {
        double totalHp = Math.max(50.0, s.flatMaxHp) * (1.0 + Math.max(0.0, s.maxHpPctBonus) / 100.0);

        double effDr = Math.min(95.0, Math.max(0.0, s.drRawPct));
        double drMult = 1.0 / (1.0 - effDr / 100.0);

        double effDodge = Math.min(95.0, Math.max(0.0, s.dodgeRawPct));
        double dodgeMult = 1.0 / (1.0 - effDodge / 100.0);

        double totalDef = Math.max(0.0, s.defense) * (1.0 + Math.max(0.0, s.defensePctBonus) / 100.0);
        double defMult = 1.0 + totalDef / Math.max(400.0, totalHp * 6.5);

        double healCycle = (Math.max(0.0, s.lifeOnHit) * 3.0 + Math.max(0.0, s.lifeOnKill) * 0.55)
                * (1.0 + Math.max(0.0, s.bloodthirstPct) / 100.0);
        double sustainMult = 1.0 + Math.min(0.65, healCycle / Math.max(250.0, totalHp * 1.4));

        return totalHp * drMult * dodgeMult * defMult * sustainMult;
    }

    /** Точный расчёт фарм-отдачи (доп. убийство, дроп, золото, опыт) */
    public static double computeFarmMultiplier(HeroState s, BuildMode mode) {
        double extraKillMult = 1.0 + Math.max(0.0, s.extraKillPct) / 100.0;
        double goldMult = 1.0 + Math.max(0.0, s.goldBonusPct) / 100.0;
        double expMult = 1.0 + Math.max(0.0, s.expBonusPct) / 100.0;
        double lootMult = (1.0 + Math.max(0.0, s.itemDropPct) / 150.0)
                * (1.0 + Math.max(0.0, s.rubyDropPct) / 120.0)
                * (1.0 + Math.max(0.0, s.runeDropPct) / 140.0)
                * (1.0 + Math.max(0.0, s.matDropPct) / 180.0);
        if (mode == BuildMode.LEVELING) {
            return Math.pow(expMult, 0.65) * Math.pow(goldMult, 0.25) * extraKillMult;
        }
        return extraKillMult * Math.pow(goldMult, 0.42) * Math.pow(expMult, 0.28) * lootMult;
    }

    /**
     * Единая целевая функция полезности под выбранный игроком режим (PUSH / BOSS / FARM / LEVELING).
     */
    public static double computeTotalObjective(HeroState s, BuildMode mode) {
        double dps = computeDps(s, mode);
        double ehp = computeEhp(s);
        double farm = computeFarmMultiplier(s, mode);

        if (mode == BuildMode.FARM) {
            // В фарме важна скорость ваншота пачки × множитель добычи/золота/опыта
            return Math.pow(dps, 0.42) * Math.pow(ehp, 0.06) * farm;
        }
        if (mode == BuildMode.LEVELING) {
            return Math.pow(dps, 0.58) * Math.pow(ehp, 0.14) * Math.pow(farm, 0.55);
        }
        if (mode == BuildMode.BOSS) {
            // На боссах и в Башне важен максимальный урон в секунду × устойчивость к удару босса
            return dps * Math.pow(ehp, 0.20);
        }
        // Режим PUSH: произведение DPS × EHP^0.25 (за счёт 1/(1 - min(95%, DR)) ценность DR до 95% растёт естественно и обнуляется на капе!)
        return dps * Math.pow(ehp, 0.25);
    }

    /**
     * Вычисляет точный относительный вклад одного кандидата `type` со значением `rollVal`
     * по отношению к состоянию `baseWithoutRow` (где старый аффикс этой позиции уже снят!).
     */
    public static CandidateEvaluation evaluateCandidate(
            HeroState baseWithoutRow,
            AffixType type,
            double avgRoll,
            double minRoll,
            double maxRoll,
            double itemHeaderBaseDef,
            BuildMode mode) {

        double baseDps = computeDps(baseWithoutRow, mode);
        double baseEhp = computeEhp(baseWithoutRow);
        double baseFarm = computeFarmMultiplier(baseWithoutRow, mode);
        double baseObj = computeTotalObjective(baseWithoutRow, mode);

        HeroState afterAvg = baseWithoutRow.copy();
        afterAvg.applyAffixDelta(type, avgRoll, itemHeaderBaseDef);

        double dpsGain = ((computeDps(afterAvg, mode) / baseDps) - 1.0) * 100.0;
        double ehpGain = ((computeEhp(afterAvg) / baseEhp) - 1.0) * 100.0;
        double farmGain = ((computeFarmMultiplier(afterAvg, mode) / baseFarm) - 1.0) * 100.0;
        double objGain = ((computeTotalObjective(afterAvg, mode) / baseObj) - 1.0) * 100.0;

        HeroState afterMax = baseWithoutRow.copy();
        afterMax.applyAffixDelta(type, maxRoll, itemHeaderBaseDef);
        double maxObjGain = ((computeTotalObjective(afterMax, mode) / baseObj) - 1.0) * 100.0;

        String mathNote = buildLiveMathTrace(baseWithoutRow, afterAvg, type, avgRoll, dpsGain, ehpGain, farmGain, mode);

        return new CandidateEvaluation(type, avgRoll, minRoll, maxRoll,
                dpsGain, ehpGain, farmGain, objGain, maxObjGain, mathNote);
    }

    /**
     * Полный перебор всех C(|pool|, count) комбинаций совместимого пула для поиска
     * математически оптимального набора из count уникальных аффиксов (с учётом всех взаимных множителей и капов!).
     */
    public static List<AffixType> solveOptimalSubset(
            final HeroState baseWithoutAllItemAffixes,
            final List<AffixType> pool,
            final List<Double> sortedAwakenMultipliersDesc,
            final AffixPositionOptimizer.ItemRollModel rollModel,
            final java.util.Map<AffixType, Double> observedUnboostedRolls,
            final double itemHeaderBaseDef,
            final BuildMode mode) {

        final int k = Math.min(sortedAwakenMultipliersDesc.size(), pool.size());
        if (k <= 0) return Collections.emptyList();

        // Предварительно сортируем пул по одиночной эластичности, чтобы внутри каждого подмножества
        // статы раскладывались на множители [m_1 >= m_2 >= ... >= m_k] в правильном порядке убывания отдачи
        final List<AffixType> orderedPool = new ArrayList<>(pool);
        final java.util.Map<AffixType, Double> singleGain = new java.util.HashMap<>();
        double baseObj = computeTotalObjective(baseWithoutAllItemAffixes, mode);
        for (AffixType t : orderedPool) {
            Double obs = observedUnboostedRolls != null ? observedUnboostedRolls.get(t) : null;
            double unboosted = obs != null ? Math.max(obs, rollModel.expectedAvgRoll(t, 1.0)) : rollModel.expectedAvgRoll(t, 1.0);
            HeroState st = baseWithoutAllItemAffixes.copy();
            st.applyAffixDelta(t, unboosted, itemHeaderBaseDef);
            singleGain.put(t, computeTotalObjective(st, mode) / baseObj);
        }
        Collections.sort(orderedPool, new Comparator<AffixType>() {
            @Override
            public int compare(AffixType a, AffixType b) {
                return Double.compare(singleGain.get(b), singleGain.get(a));
            }
        });

        final double[] bestObjective = {-1.0};
        final List<AffixType> bestCombo = new ArrayList<>();
        final AffixType[] current = new AffixType[k];

        searchCombinations(orderedPool, 0, 0, k, current,
                baseWithoutAllItemAffixes, sortedAwakenMultipliersDesc,
                rollModel, observedUnboostedRolls, itemHeaderBaseDef, mode,
                bestObjective, bestCombo);

        return bestCombo;
    }

    private static void searchCombinations(
            List<AffixType> orderedPool,
            int poolIdx,
            int depth,
            int k,
            AffixType[] current,
            HeroState baseState,
            List<Double> multsDesc,
            AffixPositionOptimizer.ItemRollModel rollModel,
            java.util.Map<AffixType, Double> observedUnboosted,
            double itemBaseDef,
            BuildMode mode,
            double[] bestObj,
            List<AffixType> bestOut) {

        if (depth == k) {
            HeroState sim = baseState.copy();
            for (int i = 0; i < k; i++) {
                AffixType t = current[i];
                double mult = multsDesc.get(i);
                Double obs = observedUnboosted != null ? observedUnboosted.get(t) : null;
                double baseVal = obs != null
                        ? Math.max(obs, rollModel.expectedAvgRoll(t, 1.0))
                        : rollModel.expectedAvgRoll(t, 1.0);
                sim.applyAffixDelta(t, baseVal * mult, itemBaseDef);
            }
            double obj = computeTotalObjective(sim, mode);
            if (obj > bestObj[0]) {
                bestObj[0] = obj;
                bestOut.clear();
                for (int i = 0; i < k; i++) bestOut.add(current[i]);
            }
            return;
        }

        int remainingNeeded = k - depth;
        for (int i = poolIdx; i <= orderedPool.size() - remainingNeeded; i++) {
            current[depth] = orderedPool.get(i);
            searchCombinations(orderedPool, i + 1, depth + 1, k, current,
                    baseState, multsDesc, rollModel, observedUnboosted, itemBaseDef, mode,
                    bestObj, bestOut);
        }
    }

    private static String buildLiveMathTrace(HeroState before, HeroState after, AffixType t,
                                             double roll, double dpsPct, double ehpPct, double farmPct, BuildMode mode) {
        switch (t) {
            case PET_DAMAGE:
                return String.format(Locale.US, "пул пета %.0f%%→%.0f%% => +%.2f%% DPS",
                        before.petDmgAdditivePct, after.petDmgAdditivePct, dpsPct);
            case DOUBLE_HIT:
                return String.format(Locale.US, "множитель ударов ×%.2f→×%.2f => +%.2f%% DPS",
                        1.0 + before.doubleHitPct / 100.0, 1.0 + after.doubleHitPct / 100.0, dpsPct);
            case DOUBLE_DAMAGE:
                return String.format(Locale.US, "множитель дв.урона ×%.2f→×%.2f => +%.2f%% DPS",
                        1.0 + before.doubleDamagePct / 100.0, 1.0 + after.doubleDamagePct / 100.0, dpsPct);
            case CRIT_DAMAGE:
                if (before.critDamagePct >= CRIT_DAMAGE_SOFT_CAP_PCT) {
                    return String.format(Locale.US, "крит. урон %.0f%% > порога 1500%% (насыщение) => +%.2f%% DPS",
                            before.critDamagePct, dpsPct);
                }
                return String.format(Locale.US, "крит-множ. ×%.2f→×%.2f => +%.2f%% DPS",
                        critMultiplier(before), critMultiplier(after), dpsPct);
            case CRIT_CHANCE:
                if (before.critChancePct >= CRIT_CHANCE_CAP_PCT) {
                    return String.format(Locale.US, "шанс крита уже %.1f%% >= 100%% (кап закрыт) => +0.00%% DPS",
                            before.critChancePct);
                }
                return String.format(Locale.US, "шанс крита %.0f%%→%.0f%% (крит-множ. ×%.2f→×%.2f) => +%.2f%% DPS",
                        before.critChancePct, Math.min(100.0, after.critChancePct),
                        critMultiplier(before), critMultiplier(after), dpsPct);
            case INTELLIGENCE:
                return String.format(Locale.US, "+%.1f%% Дв.урона + %.1f%% Пета => +%.2f%% DPS",
                        roll * 0.20, roll * 0.05, dpsPct);
            case DEXTERITY:
                return String.format(Locale.US, "+%.1f%% Пета => +%.2f%% DPS, +%.2f%% EHP (уклонение %.1f%%→%.1f%% при капе 95%%)",
                        roll * 0.25, dpsPct, ehpPct, before.dodgeRawPct, after.dodgeRawPct);
            case STRENGTH:
                return String.format(Locale.US, "+%.1f%% HP => +%.2f%% DPS, +%.2f%% EHP",
                        roll * 0.50, dpsPct, ehpPct);
            case DAMAGE_REDUCTION:
                return String.format(Locale.US, "Сниж.урона %.1f%%→%.1f%% (кап 95%%) => +%.2f%% EHP",
                        before.drRawPct, after.drRawPct, ehpPct);
            case DODGE_CHANCE:
                return String.format(Locale.US, "Уклонение %.1f%%→%.1f%% (кап 95%%) => +%.2f%% EHP",
                        before.dodgeRawPct, after.dodgeRawPct, ehpPct);
            case MAX_HEALTH:
                return String.format(Locale.US, "HP %.0f→%.0f => +%.2f%% EHP",
                        before.flatMaxHp, after.flatMaxHp, ehpPct);
            case BLOODTHIRST:
                return String.format(Locale.US, "множитель отхила ×%.2f→×%.2f => +%.2f%% EHP",
                        1.0 + before.bloodthirstPct / 100.0, 1.0 + after.bloodthirstPct / 100.0, ehpPct);
            case LOCAL_PERCENT_DEFENSE:
            case LOCAL_FLAT_DEFENSE:
            case DEFENSE_PERCENT:
                return String.format(Locale.US, "защита => +%.2f%% EHP", ehpPct);
            case ADDED_DAMAGE:
                return String.format(Locale.US, "плоский урон (+75%% в базу пета) => +%.2f%% DPS", dpsPct);
            case GOLD_GAIN:
            case EXP_GAIN:
                return String.format(Locale.US, "+%.2f%% к фарм-отдаче (+%.2f%% боевой вклад)", farmPct, dpsPct);
            default:
                return String.format(Locale.US, "+%.2f%% DPS, +%.2f%% EHP", dpsPct, ehpPct);
        }
    }
}
