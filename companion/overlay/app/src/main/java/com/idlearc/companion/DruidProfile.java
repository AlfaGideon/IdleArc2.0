package com.idlearc.companion;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Живой профиль персонажа Друида.
 * По умолчанию предзаполнен реальным снимком [UND] AlfaGideon (Ур. 324, GS 3497, +20 ко всем навыкам),
 * и автоматически обновляется при сканировании окна «Характеристики персонажа» в игре.
 */
public class DruidProfile {

    public enum BuildMode {
        PUSH("🐾 Пуш / Пет", "Макс. итоговый урон питомца и выживаемость под кап 95%"),
        BOSS("👹 Боссы / Рейд", "Одиночный босс, Босс гильдии и Рейды Разлома"),
        FARM("💎 Фарм / Дроп", "Доп. убийство, яйца петов, рубины, руны, шмот и золото"),
        LEVELING("🌱 1 → Кап", "Прокачка нового или сезонного Друида с 1 до 324+ уровня");

        public final String title;
        public final String subtitle;

        BuildMode(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    // Базовые параметры (снимок AlfaGideon Ур. 324, GS 3497)
    public int charLevel = 324;
    public int forgeLevel = 268;
    public int gearScore = 3497;
    public int bonusAllClassSkills = 20;

    public double maxHp = 10790.0;
    public double minAtk = 515600.0;
    public double maxAtk = 736600.0;
    public double defense = 78140.0;
    public double damageReductionEff = 95.0;
    public double damageReductionRaw = 95.1;

    public double strength = 263.0;
    public double agility = 1150.0;
    public double intelligence = 1630.0;

    public double critChance = 593.0;
    public double critDamage = 1892.0;
    public double doubleHitChance = 672.0;
    public double doubleDamageChance = 984.0;
    public double dodgeEff = 95.0;
    public double dodgeRaw = 131.7;
    public double blockChance = 0.0;
    public double bossDamage = 100.0;

    public double lifeOnHit = 268.0;
    public double lifeOnKill = 1080.0;
    public double lifeStealBonusPct = 20.0;
    public double retaliation = 0.0;

    public double petDamageMultMillions = 83.48; // x83.48M
    public double expBonus = 1190.0;
    public double goldBonus = 1051.0;

    public double itemDropPct = 336.3;
    public double itemDropMult = 2.70;
    public double matDropPct = 455.6;
    public double matDropMult = 2.98;
    public double runeDropPct = 213.0;
    public double runeDropMult = 2.36;
    public double rubyDropPct = 97.0;
    public double rubyDropMult = 1.82;
    public double extraKillChance = 3.0;

    public BuildMode mode = BuildMode.PUSH;
    public long lastUpdatedMs = 0L;

    private static final String PREFS = "druid_helper_profile";

    public static DruidProfile load(Context ctx) {
        DruidProfile p = new DruidProfile();
        if (ctx == null) return p;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.charLevel = sp.getInt("charLevel", p.charLevel);
        p.forgeLevel = sp.getInt("forgeLevel", p.forgeLevel);
        p.gearScore = sp.getInt("gearScore", p.gearScore);
        p.bonusAllClassSkills = sp.getInt("bonusAllClassSkills", p.bonusAllClassSkills);
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
        p.bossDamage = sp.getFloat("bossDmg", (float) p.bossDamage);
        p.petDamageMultMillions = sp.getFloat("petMultM", (float) p.petDamageMultMillions);
        p.itemDropPct = sp.getFloat("itemDropPct", (float) p.itemDropPct);
        p.matDropPct = sp.getFloat("matDropPct", (float) p.matDropPct);
        p.runeDropPct = sp.getFloat("runeDropPct", (float) p.runeDropPct);
        p.rubyDropPct = sp.getFloat("rubyDropPct", (float) p.rubyDropPct);
        p.extraKillChance = sp.getFloat("extraKill", (float) p.extraKillChance);
        p.lastUpdatedMs = sp.getLong("lastUpdatedMs", 0L);
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
        ed.putInt("charLevel", charLevel);
        ed.putInt("forgeLevel", forgeLevel);
        ed.putInt("gearScore", gearScore);
        ed.putInt("bonusAllClassSkills", bonusAllClassSkills);
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
        ed.putFloat("bossDmg", (float) bossDamage);
        ed.putFloat("petMultM", (float) petDamageMultMillions);
        ed.putFloat("itemDropPct", (float) itemDropPct);
        ed.putFloat("matDropPct", (float) matDropPct);
        ed.putFloat("runeDropPct", (float) runeDropPct);
        ed.putFloat("rubyDropPct", (float) rubyDropPct);
        ed.putFloat("extraKill", (float) extraKillChance);
        ed.putLong("lastUpdatedMs", lastUpdatedMs);
        ed.putString("mode", mode.name());
        ed.apply();
    }

    /** Относительный прирост реального DPS от +1% Шанса двойного удара */
    public double marginalGainPer1PctDoubleHit() {
        // Множитель = 1 + doubleHitChance / 100.0
        return 1.0 / (100.0 + Math.max(0.0, doubleHitChance)) * 100.0;
    }

    /** Относительный прирост реального DPS от +1% Шанса двойного урона */
    public double marginalGainPer1PctDoubleDamage() {
        return 1.0 / (100.0 + Math.max(0.0, doubleDamageChance)) * 100.0;
    }

    /** Относительный прирост реального DPS от +1% Крит. урона (или +1% Шанса крита выше 100%) */
    public double marginalGainPer1PctCrit() {
        // При крит шансе >= 100% каждый 1% крит шанса дает +1% крит урона; множитель крита = 1 + critDamage/100
        return 1.0 / (100.0 + Math.max(100.0, critDamage)) * 100.0;
    }

    /** Относительный прирост урона по боссам от +1% Урона боссам */
    public double marginalGainPer1PctBossDmg() {
        return 1.0 / (100.0 + Math.max(0.0, bossDamage)) * 100.0;
    }

    /** Лишнее уклонение сверх жёсткого капа Друида 95% */
    public double wastedDodgePct() {
        return Math.max(0.0, dodgeRaw - 95.0);
    }
}
