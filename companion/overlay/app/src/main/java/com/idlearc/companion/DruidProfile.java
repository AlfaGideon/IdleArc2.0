package com.idlearc.companion;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Динамический профиль персонажа Друида.
 * НЕ содержит никаких захардкоженных чужих статов: наполняется исключительно визуальным OCR-сканированием
 * окна «Характеристики персонажа» в игре (с поддержкой догрузки при прокрутке списка статов вверх/вниз).
 */
public class DruidProfile {

    public enum BuildMode {
        PUSH("🐾 Пуш / Пет", "Макс. итоговый урон и выживаемость (DPS × EHP)"),
        BOSS("👹 Боссы / Рейд", "Одиночный босс, Босс гильдии, Башня и Рейды"),
        FARM("💎 Фарм / Дроп", "Доп. убийство, предметы, рубины, руны, золото и опыт"),
        LEVELING("🌱 1 → Кап", "Прокачка персонажа: опыт, база пета и закрытие капов");

        public final String title;
        public final String subtitle;

        BuildMode(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    // Признак того, что статы героя реально сняты визуальным сканером с экрана игры
    public boolean hasScannedStats = false;
    public int scannedStatsCount = 0;
    public long scannedStatMask = 0L;
    public long lastUpdatedMs = 0L;

    // Чистые базовые значения нового персонажа до первого скана «Характеристик персонажа»
    public int charLevel = 1;
    public int forgeLevel = 1;
    public int gearScore = 0;
    public int bonusAllClassSkills = 0;

    public double maxHp = 0.0;
    public double minAtk = 0.0;
    public double maxAtk = 0.0;
    public double defense = 0.0;
    public double damageReductionEff = 0.0;
    public double damageReductionRaw = 0.0;

    public double strength = 0.0;
    public double agility = 0.0;
    public double intelligence = 0.0;

    public double critChance = 5.0;
    public double critDamage = 50.0;
    public double doubleHitChance = 0.0;
    public double doubleDamageChance = 0.0;
    public double dodgeEff = 0.0;
    public double dodgeRaw = 0.0;
    public double blockChance = 0.0;
    public double bossDamage = 0.0;

    public double lifeOnHit = 0.0;
    public double lifeOnKill = 0.0;
    public double lifeStealBonusPct = 0.0;
    public double retaliation = 0.0;

    public double petDamageBonusPct = 0.0;
    public double petDamageMultMillions = 0.0;
    public double elementalConvertPct = 0.0;
    public double eggDropPct = 0.0;
    public double eggDropMult = 1.0;
    public double expBonus = 0.0;
    public double goldBonus = 0.0;

    public double itemDropPct = 0.0;
    public double itemDropMult = 1.0;
    public double matDropPct = 0.0;
    public double matDropMult = 1.0;
    public double runeDropPct = 0.0;
    public double runeDropMult = 1.0;
    public double rubyDropPct = 0.0;
    public double rubyDropMult = 1.0;
    public double extraKillChance = 0.0;

    public BuildMode mode = BuildMode.PUSH;

    private static final String PREFS = "druid_helper_live_profile_v2";

    public static DruidProfile load(Context ctx) {
        DruidProfile p = new DruidProfile();
        if (ctx == null) return p;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.hasScannedStats = sp.getBoolean("hasScannedStats", false);
        p.scannedStatsCount = sp.getInt("scannedStatsCount", 0);
        p.scannedStatMask = sp.getLong("scannedStatMask", 0L);
        p.lastUpdatedMs = sp.getLong("lastUpdatedMs", 0L);

        p.charLevel = sp.getInt("charLevel", p.charLevel);
        p.forgeLevel = sp.getInt("forgeLevel", p.forgeLevel);
        p.gearScore = sp.getInt("gearScore", p.gearScore);
        p.bonusAllClassSkills = sp.getInt("bonusAllClassSkills", p.bonusAllClassSkills);

        p.maxHp = sp.getFloat("maxHp", (float) p.maxHp);
        p.minAtk = sp.getFloat("minAtk", (float) p.minAtk);
        p.maxAtk = sp.getFloat("maxAtk", (float) p.maxAtk);
        p.defense = sp.getFloat("defense", (float) p.defense);
        p.damageReductionEff = sp.getFloat("drEff", (float) p.damageReductionEff);
        p.damageReductionRaw = sp.getFloat("drRaw", (float) p.damageReductionRaw);

        p.strength = sp.getFloat("str", (float) p.strength);
        p.agility = sp.getFloat("agi", (float) p.agility);
        p.intelligence = sp.getFloat("intel", (float) p.intelligence);

        p.critChance = sp.getFloat("critChance", (float) p.critChance);
        p.critDamage = sp.getFloat("critDamage", (float) p.critDamage);
        p.doubleHitChance = sp.getFloat("doubleHit", (float) p.doubleHitChance);
        p.doubleDamageChance = sp.getFloat("doubleDmg", (float) p.doubleDamageChance);
        p.dodgeEff = sp.getFloat("dodgeEff", (float) p.dodgeEff);
        p.dodgeRaw = sp.getFloat("dodgeRaw", (float) p.dodgeRaw);
        p.blockChance = sp.getFloat("blockChance", (float) p.blockChance);
        p.bossDamage = sp.getFloat("bossDmg", (float) p.bossDamage);

        p.lifeOnHit = sp.getFloat("lifeOnHit", (float) p.lifeOnHit);
        p.lifeOnKill = sp.getFloat("lifeOnKill", (float) p.lifeOnKill);
        p.lifeStealBonusPct = sp.getFloat("lifeStealBonusPct", (float) p.lifeStealBonusPct);
        p.retaliation = sp.getFloat("retaliation", (float) p.retaliation);

        p.petDamageBonusPct = sp.getFloat("petDmgPct", (float) p.petDamageBonusPct);
        p.petDamageMultMillions = sp.getFloat("petMultM", (float) p.petDamageMultMillions);
        p.elementalConvertPct = sp.getFloat("elemConvPct", (float) p.elementalConvertPct);
        p.eggDropPct = sp.getFloat("eggDropPct", (float) p.eggDropPct);
        p.expBonus = sp.getFloat("expBonus", (float) p.expBonus);
        p.goldBonus = sp.getFloat("goldBonus", (float) p.goldBonus);

        p.itemDropPct = sp.getFloat("itemDropPct", (float) p.itemDropPct);
        p.itemDropMult = sp.getFloat("itemDropMult", (float) p.itemDropMult);
        p.matDropPct = sp.getFloat("matDropPct", (float) p.matDropPct);
        p.matDropMult = sp.getFloat("matDropMult", (float) p.matDropMult);
        p.runeDropPct = sp.getFloat("runeDropPct", (float) p.runeDropPct);
        p.runeDropMult = sp.getFloat("runeDropMult", (float) p.runeDropMult);
        p.rubyDropPct = sp.getFloat("rubyDropPct", (float) p.rubyDropPct);
        p.rubyDropMult = sp.getFloat("rubyDropMult", (float) p.rubyDropMult);
        p.eggDropMult = sp.getFloat("eggDropMult", (float) p.eggDropMult);
        p.extraKillChance = sp.getFloat("extraKill", (float) p.extraKillChance);

        String m = sp.getString("mode", BuildMode.PUSH.name());
        try {
            p.mode = BuildMode.valueOf(m);
        } catch (Exception ignored) {
            p.mode = BuildMode.PUSH;
        }
        return p;
    }

    public void save(Context ctx) {
        if (ctx == null) return;
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putBoolean("hasScannedStats", hasScannedStats);
        ed.putInt("scannedStatsCount", scannedStatsCount);
        ed.putLong("scannedStatMask", scannedStatMask);
        ed.putLong("lastUpdatedMs", lastUpdatedMs);

        ed.putInt("charLevel", charLevel);
        ed.putInt("forgeLevel", forgeLevel);
        ed.putInt("gearScore", gearScore);
        ed.putInt("bonusAllClassSkills", bonusAllClassSkills);

        ed.putFloat("maxHp", (float) maxHp);
        ed.putFloat("minAtk", (float) minAtk);
        ed.putFloat("maxAtk", (float) maxAtk);
        ed.putFloat("defense", (float) defense);
        ed.putFloat("drEff", (float) damageReductionEff);
        ed.putFloat("drRaw", (float) damageReductionRaw);

        ed.putFloat("str", (float) strength);
        ed.putFloat("agi", (float) agility);
        ed.putFloat("intel", (float) intelligence);

        ed.putFloat("critChance", (float) critChance);
        ed.putFloat("critDamage", (float) critDamage);
        ed.putFloat("doubleHit", (float) doubleHitChance);
        ed.putFloat("doubleDmg", (float) doubleDamageChance);
        ed.putFloat("dodgeEff", (float) dodgeEff);
        ed.putFloat("dodgeRaw", (float) dodgeRaw);
        ed.putFloat("blockChance", (float) blockChance);
        ed.putFloat("bossDmg", (float) bossDamage);

        ed.putFloat("lifeOnHit", (float) lifeOnHit);
        ed.putFloat("lifeOnKill", (float) lifeOnKill);
        ed.putFloat("lifeStealBonusPct", (float) lifeStealBonusPct);
        ed.putFloat("retaliation", (float) retaliation);

        ed.putFloat("petDmgPct", (float) petDamageBonusPct);
        ed.putFloat("petMultM", (float) petDamageMultMillions);
        ed.putFloat("elemConvPct", (float) elementalConvertPct);
        ed.putFloat("eggDropPct", (float) eggDropPct);
        ed.putFloat("expBonus", (float) expBonus);
        ed.putFloat("goldBonus", (float) goldBonus);

        ed.putFloat("itemDropPct", (float) itemDropPct);
        ed.putFloat("itemDropMult", (float) itemDropMult);
        ed.putFloat("matDropPct", (float) matDropPct);
        ed.putFloat("matDropMult", (float) matDropMult);
        ed.putFloat("runeDropPct", (float) runeDropPct);
        ed.putFloat("runeDropMult", (float) runeDropMult);
        ed.putFloat("rubyDropPct", (float) rubyDropPct);
        ed.putFloat("rubyDropMult", (float) rubyDropMult);
        ed.putFloat("eggDropMult", (float) eggDropMult);
        ed.putFloat("extraKill", (float) extraKillChance);
        ed.putString("mode", mode.name());
        ed.apply();
    }

    /** Полный сброс сохранённых статов героя к чистому нулячему состоянию */
    public void resetStats(Context ctx) {
        BuildMode keepMode = this.mode;
        DruidProfile fresh = new DruidProfile();
        fresh.mode = keepMode;
        copyFrom(fresh);
        save(ctx);
    }

    private void copyFrom(DruidProfile o) {
        this.hasScannedStats = o.hasScannedStats;
        this.scannedStatsCount = o.scannedStatsCount;
        this.scannedStatMask = o.scannedStatMask;
        this.lastUpdatedMs = o.lastUpdatedMs;
        this.charLevel = o.charLevel;
        this.forgeLevel = o.forgeLevel;
        this.gearScore = o.gearScore;
        this.bonusAllClassSkills = o.bonusAllClassSkills;
        this.maxHp = o.maxHp;
        this.minAtk = o.minAtk;
        this.maxAtk = o.maxAtk;
        this.defense = o.defense;
        this.damageReductionEff = o.damageReductionEff;
        this.damageReductionRaw = o.damageReductionRaw;
        this.strength = o.strength;
        this.agility = o.agility;
        this.intelligence = o.intelligence;
        this.critChance = o.critChance;
        this.critDamage = o.critDamage;
        this.doubleHitChance = o.doubleHitChance;
        this.doubleDamageChance = o.doubleDamageChance;
        this.dodgeEff = o.dodgeEff;
        this.dodgeRaw = o.dodgeRaw;
        this.blockChance = o.blockChance;
        this.bossDamage = o.bossDamage;
        this.lifeOnHit = o.lifeOnHit;
        this.lifeOnKill = o.lifeOnKill;
        this.lifeStealBonusPct = o.lifeStealBonusPct;
        this.retaliation = o.retaliation;
        this.petDamageBonusPct = o.petDamageBonusPct;
        this.petDamageMultMillions = o.petDamageMultMillions;
        this.elementalConvertPct = o.elementalConvertPct;
        this.eggDropPct = o.eggDropPct;
        this.expBonus = o.expBonus;
        this.goldBonus = o.goldBonus;
        this.itemDropPct = o.itemDropPct;
        this.itemDropMult = o.itemDropMult;
        this.matDropPct = o.matDropPct;
        this.matDropMult = o.matDropMult;
        this.runeDropPct = o.runeDropPct;
        this.runeDropMult = o.runeDropMult;
        this.rubyDropPct = o.rubyDropPct;
        this.rubyDropMult = o.rubyDropMult;
        this.eggDropMult = o.eggDropMult;
        this.extraKillChance = o.extraKillChance;
    }

    /** Относительный прирост реального DPS от +1% Шанса двойного удара */
    public double marginalGainPer1PctDoubleHit() {
        return 1.0 / (100.0 + Math.max(0.0, doubleHitChance)) * 100.0;
    }

    /** Относительный прирост реального DPS от +1% Шанса двойного урона */
    public double marginalGainPer1PctDoubleDamage() {
        return 1.0 / (100.0 + Math.max(0.0, doubleDamageChance)) * 100.0;
    }

    /** Относительный прирост реального DPS от +1% Крит. урона (с учётом порога насыщения 1500%) */
    public double marginalGainPer1PctCritDamage() {
        double p = Math.min(1.0, Math.max(0.0, critChance) / 100.0);
        double cd = Math.max(0.0, critDamage);
        double effBefore = cd <= 1500.0 ? cd : (1500.0 + (cd - 1500.0) * 0.12);
        double cdNext = cd + 1.0;
        double effAfter = cdNext <= 1500.0 ? cdNext : (1500.0 + (cdNext - 1500.0) * 0.12);
        double mBefore = 1.0 + p * (effBefore / 100.0);
        double mAfter = 1.0 + p * (effAfter / 100.0);
        return ((mAfter / mBefore) - 1.0) * 100.0;
    }

    /** Относительный прирост реального DPS от +1% Шанса крита (на капе >= 100% все удары уже критуют -> 0%) */
    public double marginalGainPer1PctCritChance() {
        if (critChance >= 100.0) {
            return 0.0;
        }
        double cd = Math.max(0.0, critDamage);
        double effCdFrac = (cd <= 1500.0 ? cd : (1500.0 + (cd - 1500.0) * 0.12)) / 100.0;
        double p = Math.max(0.0, critChance) / 100.0;
        double mBefore = 1.0 + p * effCdFrac;
        double mAfter = 1.0 + Math.min(1.0, p + 0.01) * effCdFrac;
        return ((mAfter / mBefore) - 1.0) * 100.0;
    }

    /** Относительный прирост урона по боссам от +1% Урона боссам */
    public double marginalGainPer1PctBossDmg() {
        return 1.0 / (100.0 + Math.max(0.0, bossDamage)) * 100.0;
    }

    /** Лишнее уклонение сверх жёсткого капа Друида 95% */
    public double wastedDodgePct() {
        return Math.max(0.0, dodgeRaw - 95.0);
    }

    /** Лишний шанс крита сверх 100% вероятности крита */
    public double wastedCritChancePct() {
        return Math.max(0.0, critChance - 100.0);
    }
}
