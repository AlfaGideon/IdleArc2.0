import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import jdk4py


ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = ROOT / "companion" / "overlay" / "app" / "src" / "main" / "java" / "com" / "idlearc" / "companion"
ECJ_JAR = Path("/tmp/android-tools/ecj.jar")
TOOLS_JAR = Path("/tmp/android-tools/tools.jar")


@unittest.skipUnless(
    ECJ_JAR.exists() or TOOLS_JAR.exists() or (jdk4py.JAVA_HOME / "bin" / "javac").exists(),
    "Java compiler toolchain not present",
)
class TestApiDashboardClient(unittest.TestCase):
    """
    Проверяет парсер SimpleJson и клиент IdleArcApiClient на синтетическом слепке /api/dashboard:
    - заполнение всех 31 характеристики DruidProfile (включая сырые и эффективные капы 95%);
    - аудит надетых предметов (characterEquippedItems с affix_positions) под разные BuildMode;
    - аудит всех гнёзд самоцветов (gemSockets) с обнаружением лишнего Шанса крита при капе >= 100%.
    """

    def test_dashboard_json_profile_and_equipment_audit(self):
        tmp = Path(tempfile.mkdtemp(prefix="test_api_dash_"))
        try:
            (tmp / "android" / "os").mkdir(parents=True, exist_ok=True)
            (tmp / "android" / "os" / "SystemClock.java").write_text("package android.os; public class SystemClock { public static long elapsedRealtime(){ return System.currentTimeMillis(); } }")
            (tmp / "android" / "content").mkdir(parents=True, exist_ok=True)
            (tmp / "android" / "content" / "Context.java").write_text(
                "package android.content;\n"
                "public class Context {\n"
                "  public static final int MODE_PRIVATE = 0;\n"
                "  public SharedPreferences getSharedPreferences(String n, int m) { return null; }\n"
                "}\n",
                encoding="utf-8",
            )
            (tmp / "android" / "content" / "SharedPreferences.java").write_text(
                "package android.content;\n"
                "public interface SharedPreferences {\n"
                "  boolean getBoolean(String k, boolean d);\n"
                "  int getInt(String k, int d); float getFloat(String k, float d);\n"
                "  long getLong(String k, long d); String getString(String k, String d);\n"
                "  Editor edit();\n"
                "  interface Editor {\n"
                "    Editor putBoolean(String k, boolean v);\n"
                "    Editor putInt(String k, int v); Editor putFloat(String k, float v);\n"
                "    Editor putLong(String k, long v); Editor putString(String k, String v);\n"
                "    Editor remove(String k);\n"
                "    void apply();\n"
                "  }\n"
                "}\n",
                encoding="utf-8",
            )

            (tmp / "ApiClientSuite.java").write_text(
                """
import com.idlearc.companion.*;
import java.util.Map;

public class ApiClientSuite {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new RuntimeException("ASSERT FAILED: " + msg);
    }

    static class MemPrefs implements android.content.SharedPreferences {
        final java.util.Map<String,Object> data = new java.util.HashMap<>();
        public boolean getBoolean(String k, boolean d) { return data.containsKey(k) ? (Boolean)data.get(k) : d; }
        public int getInt(String k, int d) { return data.containsKey(k) ? (Integer)data.get(k) : d; }
        public long getLong(String k, long d) { return data.containsKey(k) ? (Long)data.get(k) : d; }
        public float getFloat(String k, float d) { return data.containsKey(k) ? (Float)data.get(k) : d; }
        public String getString(String k, String d) { return data.containsKey(k) ? (String)data.get(k) : d; }
        public Editor edit() { return new Editor() {
            public Editor putBoolean(String k, boolean v) { data.put(k,v); return this; }
            public Editor putInt(String k, int v) { data.put(k,v); return this; }
            public Editor putFloat(String k, float v) { data.put(k,v); return this; }
            public Editor putLong(String k, long v) { data.put(k,v); return this; }
            public Editor putString(String k, String v) { data.put(k,v); return this; }
            public Editor remove(String k) { data.remove(k); return this; }
            public void apply() {}
        }; }
    }
    static class MemContext extends android.content.Context {
        final MemPrefs prefs = new MemPrefs();
        public android.content.SharedPreferences getSharedPreferences(String n, int m) { return prefs; }
    }

    static class FakeConnection extends java.net.HttpURLConnection {
        static volatile java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        static volatile java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        static final java.util.List<String> paths = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        static final java.util.Map<String,String> bodies = new java.util.HashMap<String,String>();
        static final java.util.Map<String,Integer> codes = new java.util.HashMap<String,Integer>();
        FakeConnection(java.net.URL url) { super(url); paths.add(url.getPath()); }
        public void connect() {} public void disconnect() {} public boolean usingProxy() { return false; }
        public int getResponseCode() { Integer code=codes.get(url.getPath()); return code == null ? 200 : code; }
        public java.io.InputStream getErrorStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
        public java.io.InputStream getInputStream() throws java.io.IOException {
            entered.countDown();
            try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("blocked"); }
            catch (InterruptedException e) { throw new java.io.IOException(e); }
            String body = bodies.get(url.getPath());
            if (body == null) body = "{\\"characterId\\":1,\\"level\\":10}";
            return new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String inventoryPage(int page, int total, String nextKey) {
        int last = (total + 9) / 10;
        StringBuilder b = new StringBuilder("{\\"characterItemsPaginated\\":{\\"total\\":" + total
                + ",\\"per_page\\":10,\\"last_page\\":" + last + ",\\"current_page\\":" + page
                + ",\\"next_page_url\\":\\"https://idlearc.com/api/inventory?" + nextKey + "=2\\",\\"data\\":[");
        for (int i = (page - 1) * 10 + 1; i <= Math.min(page * 10, total); i++) {
            if (i > (page - 1) * 10 + 1) b.append(",");
            b.append("{\\"id\\":" + i + "}");
        }
        return b.append("]}}").toString();
    }

    public static void main(String[] args) throws Exception {
        String syntheticDashboard = "{"
            + "\\"characterClassName\\":\\"Druid\\","
            + "\\"level\\":420,"
            + "\\"gear_score\\":7500,"
            + "\\"maxHealth\\":120000,"
            + "\\"defense\\":250000,"
            + "\\"damageReduction\\":95,"
            + "\\"damageReductionUncapped\\":112.4,"
            + "\\"attackDamage\\":{\\"min\\":4000000,\\"max\\":6000000},"
            + "\\"criticalStrikeChance\\":\\"210.5\\","
            + "\\"criticalDamage\\":1800,"
            + "\\"doubleHitChance\\":180,"
            + "\\"doubleDamageChance\\":140,"
            + "\\"blockChance\\":25,"
            + "\\"bossDamage\\":15,"
            + "\\"extraKillChance\\":40,"
            + "\\"effectivePetMasteryLevel\\":85,"
            + "\\"bonusStats\\":{"
                + "\\"petDamagePercent\\":9000,"
                + "\\"petDamageTrueMultiplier\\":50000000,"
                + "\\"petDamageFinalAvg\\":2500000000000.0,"
                + "\\"expGainPercent\\":300,"
                + "\\"goldGainPercent\\":250,"
                + "\\"lifeOnHit\\":1500,"
                + "\\"lifeOnKill\\":3000,"
                + "\\"bloodthirstPercent\\":120,"
                + "\\"totalItemDropChance\\":95,"
                + "\\"effectiveItemDropMultiplier\\":3.5,"
                + "\\"totalMaterialDropChance\\":80,"
                + "\\"effectiveMaterialDropMultiplier\\":2.8,"
                + "\\"totalRuneDropChance\\":45,"
                + "\\"effectiveRuneDropMultiplier\\":1.9,"
                + "\\"totalRubyDropChance\\":20,"
                + "\\"effectiveRubyDropMultiplier\\":1.2,"
                + "\\"primaryStats\\":{"
                    + "\\"strength\\":{\\"value\\":2200},"
                    + "\\"dexterity\\":{\\"value\\":4100,\\"dodge_chance\\":95,\\"dodge_chance_uncapped\\":128.0},"
                    + "\\"intelligence\\":{\\"value\\":4800}"
                + "},"
                + "\\"petSpecialBonuses\\":{\\"all_skills\\":12},"
                + "\\"ascentBonuses\\":{\\"pet_egg_drop_pct\\":35}"
            + "},"
            + "\\"characterEquippedItems\\":["
                + "{"
                    + "\\"slot\\":\\"hands\\",\\"type\\":\\"gloves\\",\\"tier\\":6,\\"tier_level\\":10,"
                    + "\\"implicit_bonus\\":{\\"type\\":\\"defense\\",\\"value\\":1800},"
                    + "\\"affix_positions\\":["
                        + "{\\"index\\":1,\\"boost_count\\":2,\\"affix\\":{\\"readable_bonus_name\\":\\"Intelligence\\",\\"value\\":190,\\"bonus_type\\":\\"flat\\",\\"affix_type\\":\\"suffix\\"}},"
                        + "{\\"index\\":2,\\"boost_count\\":1,\\"affix\\":{\\"readable_bonus_name\\":\\"Critical Strike Chance\\",\\"value\\":45,\\"bonus_type\\":\\"percentage\\",\\"affix_type\\":\\"suffix\\"}},"
                        + "{\\"index\\":3,\\"boost_count\\":2,\\"affix\\":{\\"readable_bonus_name\\":\\"Pet Damage\\",\\"value\\":210,\\"bonus_type\\":\\"percentage\\",\\"affix_type\\":\\"prefix\\"}},"
                        + "{\\"index\\":4,\\"boost_count\\":1,\\"affix\\":{\\"readable_bonus_name\\":\\"Double Hit Chance\\",\\"value\\":24,\\"bonus_type\\":\\"percentage\\",\\"affix_type\\":\\"prefix\\"}},"
                        + "{\\"index\\":5,\\"boost_count\\":1,\\"affix\\":{\\"readable_bonus_name\\":\\"Dexterity\\",\\"value\\":175,\\"bonus_type\\":\\"flat\\",\\"affix_type\\":\\"suffix\\"}}"
                    + "]"
                + "}"
            + "],"
            + "\\"gemSockets\\":{"
                + "\\"gloves\\":{"
                    + "\\"active\\":true,"
                    + "\\"sockets\\":["
                        + "{\\"index\\":1,\\"unlocked\\":true,\\"setting_level\\":32,\\"gem\\":{"
                            + "\\"family\\":\\"lapis\\",\\"quality\\":98,"
                            + "\\"secondaries\\":[{\\"stat\\":\\"critical_strike_chance\\",\\"value\\":4.2}]"
                        + "}}"
                    + "]"
                + "}"
            + "},"
            + "\\"newLoot\\":[{\\"name\\":\\"Ancient Gauntlets\\",\\"slot\\":\\"hands\\",\\"type\\":\\"gloves\\",\\"tier\\":6,\\"level\\":420,\\"drop_bonus_type\\":\\"rune_drops\\",\\"drop_bonus_value\\":22}],"
            + "\\"newGems\\":[{\\"family\\":\\"jade\\",\\"rarity_slug\\":\\"perfect\\",\\"quality\\":97,\\"pristine\\":true,\\"secondaries\\":[{\\"stat\\":\\"pet_damage\\",\\"value\\":8}]}],"
            + "\\"characterPets\\":[{\\"name\\":\\"Beaver\\",\\"is_equipped\\":true,\\"compound_level\\":120,\\"compound_min_equipped\\":120,\\"compound_effective_cap\\":135}],"
            + "\\"equippedTalismans\\":[{\\"name\\":\\"Sun Talisman\\",\\"slot\\":1,\\"level\\":50,\\"inscription_totals\\":{\\"double_hit_chance\\":{\\"total\\":36},\\"double_damage_chance\\":{\\"total\\":30},\\"pet_damage_bonus\\":{\\"total\\":120}}}],"
            + "\\"activeClassSkillPresetSlot\\":1,"
            + "\\"classSkillPresetSlots\\":[{\\"slot\\":1,\\"point_count\\":145,\\"ranks\\":{\\"73\\":10,\\"88\\":5}}]"
        + "}";

        Map<String, Object> dash = SimpleJson.parseObject(syntheticDashboard);
        check(dash != null, "SimpleJson parsed object");

        DruidProfile p = new DruidProfile();
        p.mode = DruidProfile.BuildMode.PUSH;
        IdleArcApiClient.applyDashboardToProfile(dash, p);

        check(p.hasScannedStats && p.scannedStatsCount == 31, "31 stats marked scanned");
        check(p.charLevel == 420 && p.gearScore == 7500, "level and gear score");
        check(Math.abs(p.damageReductionEff - 95.0) < 0.01 && Math.abs(p.damageReductionRaw - 112.4) < 0.01, "DR eff vs raw");
        check(Math.abs(p.dodgeEff - 95.0) < 0.01 && Math.abs(p.dodgeRaw - 128.0) < 0.01, "Dodge eff vs raw");
        check(Math.abs(p.critChance - 210.5) < 0.01 && Math.abs(p.critDamage - 1800.0) < 0.01, "Crit parsed from string & number");
        check(Math.abs(p.petDamageMultMillions - 50.0) < 0.01, "True pet multiplier in millions");
        check(p.bonusAllClassSkills == 12, "all_skills parsed");

        ScreenAnalyzer.ScanResult sr = IdleArcApiClient.buildFullDashboardScanResult(dash, p);
        check(sr != null && sr.profileUpdated, "ScanResult created");
        check(sr.details.contains("Шанс крита (45%)"), "Detects wasted Crit Chance affix #2 on Gloves when hero CC >= 100%");
        check(sr.details.contains("Шанс крита +4.2%"), "Detects wasted Crit Chance secondary on Lapis gem");

        // В режиме FARM советует Янтарь вместо Лазурита!
        p.mode = DruidProfile.BuildMode.FARM;
        ScreenAnalyzer.ScanResult srFarm = IdleArcApiClient.buildFullDashboardScanResult(dash, p);
        check(srFarm.details.contains("Янтарь"), "Recommends Amber in FARM_LOOT mode");

        // Тестируем все 6 вкладок нового чистого API-движка v3.0 (ApiDashboardEngine)
        p.mode = DruidProfile.BuildMode.PUSH;
        String tab0 = ApiDashboardEngine.buildSummaryAndActionPlan(dash, p);
        check(tab0.contains("ЖИВЫЕ ДАННЫЕ СЕРВЕРА") && tab0.contains("ПЛАН ДЕЙСТВИЙ"), "Tab 0 Summary & Action Plan");

        String tab1All = ApiDashboardEngine.buildEquipmentReport(dash, p, ApiDashboardEngine.EquippedSlotKey.ALL);
        String tab1Gloves = ApiDashboardEngine.buildEquipmentReport(dash, p, ApiDashboardEngine.EquippedSlotKey.GLOVES);
        check(tab1All.contains("Перчатки") && tab1Gloves.contains("ЗАМЕНИТЬ НА:"), "Tab 1 Equipment audit");

        String tab2Bag = ApiDashboardEngine.buildBagLootAndGemsReport(dash, p);
        check(tab2Bag.contains("В ФАРМ-СЕТ") && tab2Bag.contains("PRISTINE"), "Tab 2 Bag sorter");

        String tab3Gems = ApiDashboardEngine.buildAll20GemsReport(dash, p);
        check(tab3Gems.contains("СЕРВЕРНЫЙ АУДИТ ГНЁЗД") && tab3Gems.contains("Шанс крита +4.2%"), "Tab 3 All 20 Gems audit");

        String tab4Pets = ApiDashboardEngine.buildPetsAndTalismansReport(dash, p);
        check(tab4Pets.contains("Beaver") && tab4Pets.contains("Sun Talisman"), "Tab 4 Pets & Talismans");

        String tab5Skills = ApiDashboardEngine.buildSkillsAuditReport(dash, p, null);
        check(tab5Skills.contains("НАЙДЕНЫ СВОБОДНЫЕ ОЧКИ НАВЫКОВ") && tab5Skills.contains("Сердце-близнец"), "Tab 5 Skills overcap reclaim");

        // === Тесты v3.1.0: RuGameDictionary (0% сырых GSON-ключей), RadarTimeParser, SnapshotHistoryTracker и 16 функций ===
        check("Получение опыта".equals(RuGameDictionary.statName("exp_gain")), "exp_gain translated");
        check("Сила атаки".equals(RuGameDictionary.statName("attack_power")), "attack_power translated");
        check("Атака и урон питомцев".equals(RuGameDictionary.statName("attack_pet_damage")), "attack_pet_damage translated");
        check(RuGameDictionary.statName("companions").contains("Урон питомцев"), "companions drop bonus translated");
        check("Безупречный".equals(RuGameDictionary.gemRarityRu("flawless")), "gem rarity translated");
        check("Талисман Духа".equals(RuGameDictionary.talismanNameRu("spirit", "Talisman of Spirit")), "talisman name translated");
        check(Math.abs(RuGameDictionary.normalizeRollQualityPct(1.0, 47, 24, 47) - 100.0) < 0.01, "0..1 roll_quality normalized to 100%");

        // SimpleJson.str никогда не должен возвращать сырой Map.toString() вида {id=1, name=...}
        Map<String, Object> nestedTest = SimpleJson.parseObject("{\\"biome\\":{\\"id\\":5,\\"name\\":\\"Frozen Tundra\\"},\\"arr\\":[1,2]}");
        check("Frozen Tundra".equals(SimpleJson.str(nestedTest, "biome", "")), "SimpleJson.str unwraps nested name cleanly");
        check("".equals(SimpleJson.str(nestedTest, "arr", "")), "SimpleJson.str does not dump raw List");

        // Радар аккаунта и расчёт будильника с Тихими часами (00:00–08:00 -> 08:00)
        long nowMs = 1762900000000L;
        Map<String, Object> radarDash = SimpleJson.parseObject("{"
            + "\\"isDungeonOnCooldown\\":true,"
            + "\\"dungeonCooldownExpirationTime\\":" + ((nowMs / 1000L) + 3600L) + ","
            + "\\"guild_boss_can_fight\\":true,"
            + "\\"treePoints\\":3,"
            + "\\"monsterLevelPaused\\":true"
            + "}");
        RadarTimeParser.RadarState rs = RadarTimeParser.parseRadar(radarDash, nowMs);
        check(!rs.dungeonReady && rs.dungeonReadyAtMs > nowMs, "Dungeon cooldown parsed");
        check(rs.guildBossReady && rs.unspentTreePoints == 3 && rs.floorPaused, "Radar alerts parsed");
        RadarTimeParser.AlarmTarget at = RadarTimeParser.buildAlarmTarget("dungeon", "Данж", rs.dungeonReadyAtMs, nowMs, false);
        check(at != null && at.formattedTime.contains(":"), "System alarm target formatted");

        // Примерочная статов (What-If), ROI ресурсов и Детектор До/После
        java.util.List<ApiDashboardEngine.ServerItemAudit> eqList = ApiDashboardEngine.analyzeAllEquippedItems(dash, p);
        check(!eqList.isEmpty() && eqList.get(0).positions.get(0).index == 1, "1-based position index");
        String whatIf = ApiFeatureAnalyzers.simulateWhatIfChange(
            dash, p, eqList.get(0), 2, AffixPositionOptimizer.AffixType.INTELLIGENCE, 0.90);
        check(whatIf.contains("ПРИМЕРОЧНАЯ") && whatIf.contains("Интеллект"), "What-If simulator works");

        ApiFeatureAnalyzers.ResourceRoiReport roi = ApiFeatureAnalyzers.analyzeResourcesAndRoi(dash, p);
        check(roi != null, "Resource ROI report built");

        Map<String, Object> newerDash = SimpleJson.parseObject(syntheticDashboard.replace("2500000000000.0", "2750000000000.0"));
        SnapshotHistoryTracker.computeBeforeAfterDiff(dash, newerDash);
        check(SnapshotHistoryTracker.getLastDiffSummary().contains("+10.00%"), "Before/After diff detects +10% pet hit");

        // === v4.0.0: Тесты всех 5 классов, 90 навыков, Сумки по бонусу дропа и Советника петов ===
        for (int sid = 1; sid <= 90; sid++) {
            check(!HeroClassProfile.skillNameRu(sid, "").isEmpty(), "Skill RU #" + sid);
        }
        Map<String,Object> harmonyDash = SimpleJson.parseObject("{\\"activeCombatStance\\":\\"harmony\\",\\"activeCombatStanceHarmonyMax\\":8000,\\"monsterLevelProgress\\":{\\"effective_fighting_level\\":310},\\"attackDamageBreakdown\\":{\\"multiplicative\\":[{\\"key\\":\\"stance\\",\\"pct\\":6400,\\"mult\\":65}]},\\"bonusStats\\":{\\"petDamageBreakdown\\":[{\\"key\\":\\"stance\\",\\"pct\\":6400}]}}");
        HarmonyInfo harmony = HarmonyInfo.fromDashboard(harmonyDash);
        check(harmony.active && harmony.monsterLevel == 310, "harmony snapshot and effective ML");
        check(harmony.maximumBonusPct == 8000 && HarmonyInfo.formatMultiplier(harmony.maximumBonusPct).equals("×81.00"), "8000 percent is multiplier 81");
        check(HarmonyInfo.formatMultiplier(harmony.heroBonusPct).equals("×65.00") && harmony.petBonusPct == 6400, "actual stance percentages read separately, no double multiplication");
        harmonyDash.put("activeCombatStanceHarmonyMax", 20000.0);
        check(HarmonyInfo.formatMultiplier(HarmonyInfo.fromDashboard(harmonyDash).maximumBonusPct).equals("×201.00"), "server cap grows without hardcoded 81 maximum");
        harmonyDash.put("activeCombatStance", "beastmaster");
        check(Double.isNaN(HarmonyInfo.fromDashboard(harmonyDash).petBonusPct)
                && Double.isNaN(HarmonyInfo.fromDashboard(harmonyDash).maximumBonusPct), "other stance never mislabelled as harmony");
        check(Double.isNaN(HarmonyInfo.fromDashboard(SimpleJson.parseObject("{} ")).maximumBonusPct), "missing cap remains unknown");
        harmonyDash.put("activeCombatStance", "harmony");
        harmonyDash.put("activeCombatStanceHarmonyMax", -1.0);
        check(Double.isNaN(HarmonyInfo.fromDashboard(harmonyDash).maximumBonusPct), "invalid cap ignored");
        check(HarmonyInfo.formatMultiplier(Double.NaN).equals("—"), "invalid multiplier displayed as unknown");

        String druidGuide = HeroClassProfile.buildClassGuide(HeroClassProfile.HeroClass.DRUID, DruidProfile.BuildMode.PUSH);
        check(druidGuide.contains("Гармони") && druidGuide.contains("Повелител") && !druidGuide.contains("Лучшая стойка:"), "druid stance guide does not assume a universally best stance");
        check(RuGameDictionary.stanceRu("harmony").equals("Гармония"), "harmony stance translated");
        check(RuGameDictionary.stanceRu("beastmaster").equals("Повелитель зверей"), "beastmaster stance translated");
        check(HeroClassProfile.HeroClass.fromIdOrName(1, "") == HeroClassProfile.HeroClass.WARRIOR, "Warrior id=1");
        check(HeroClassProfile.HeroClass.fromIdOrName(3, "Mage") == HeroClassProfile.HeroClass.MAGE, "Mage id=3");

        java.util.List<ApiDashboardEngine.ServerPositionEval> posForHarmony = new java.util.ArrayList<>();
        posForHarmony.add(new ApiDashboardEngine.ServerPositionEval(1, AffixPositionOptimizer.AffixType.GOLD_GAIN, "Gold", 4, 1, 5, 50, 0, false, false, false, null, 0, 0, new java.util.ArrayList<String>()));
        ApiDashboardEngine.ServerItemAudit ringForHarmony = new ApiDashboardEngine.ServerItemAudit(ApiDashboardEngine.EquippedSlotKey.RING_1, SlotAffixAdvisor.GearSlot.RING, "кольцо", "кольцо", 1, 1, 0, 10, 0, 0, "", "", posForHarmony, 0, 0, "");
        Map<String,Object> bulwarkDash = new java.util.HashMap<String,Object>(); bulwarkDash.put("activeCombatStance", "bulwark"); bulwarkDash.put("characterClassId", 1);
        check(RetaliationGuide.active(bulwarkDash), "bulwark activates retaliation guidance");
        check(!RetaliationGuide.active(harmonyDash), "no bulwark recommendation outside bulwark");
        String warriorRet = RetaliationGuide.advice(bulwarkDash, 1, "BOSS").toString();
        check(warriorRet.contains("Reflecting") && warriorRet.contains("Iron Constitution") && warriorRet.contains("Босс"), "warrior boss retaliation priorities");
        check(!RetaliationGuide.advice(bulwarkDash, 4, "FARM").toString().contains("Iron Constitution"), "warrior skills not attributed to other classes");
        check(RetaliationGuide.relevant(AffixPositionOptimizer.AffixType.DEFENSE_PERCENT), "defense affix recognized");
        check(!RetaliationGuide.relevant(AffixPositionOptimizer.AffixType.DODGE_CHANCE), "dodge not a defense recommendation in bulwark");
        check(RetaliationGuide.alternatives(ringForHarmony, posForHarmony.get(0)).contains(AffixPositionOptimizer.AffixType.DEFENSE_PERCENT), "same-kind defense alternative on ring");
        check(!HeroClassProfile.buildClassGuide(HeroClassProfile.HeroClass.WARRIOR, DruidProfile.BuildMode.PUSH).contains("Эталонный"), "warrior guide no assumed best drop");
        posForHarmony.add(new ApiDashboardEngine.ServerPositionEval(5, AffixPositionOptimizer.AffixType.DEFENSE_PERCENT, "Defense", 1, 1, 5, 50, 0, false, false, true, null, 0, 0, new java.util.ArrayList<String>()));
        check(!RetaliationGuide.alternatives(ringForHarmony, posForHarmony.get(0)).contains(AffixPositionOptimizer.AffixType.DEFENSE_PERCENT), "do not suggest duplicate defense affix");
        posForHarmony.remove(1);
        check(!RetaliationGuide.alternatives(ringForHarmony, posForHarmony.get(0)).contains(AffixPositionOptimizer.AffixType.LOCAL_PERCENT_DEFENSE), "incompatible armor prefix never shown on ring suffix");
        check(HarmonyAffixGuide.candidate(ringForHarmony, StanceAdvisor.ATTACK_DOMINANT).contains("Урон питомца"), "real ring pet candidate");
        check(HarmonyAffixGuide.target(ringForHarmony, StanceAdvisor.ATTACK_DOMINANT) == AffixPositionOptimizer.AffixType.PET_DAMAGE, "attack-heavy scenario compares pet");
        check(HarmonyAffixGuide.target(ringForHarmony, StanceAdvisor.PET_DOMINANT) == AffixPositionOptimizer.AffixType.ADDED_DAMAGE, "pet-heavy scenario compares attack");
        check(HarmonyAffixGuide.target(ringForHarmony, StanceAdvisor.BALANCED) == null && HarmonyAffixGuide.target(ringForHarmony, StanceAdvisor.UNKNOWN) == null, "no forced target when balanced or unknown");
        check(HarmonyAffixGuide.canCompareRow(HarmonyAffixGuide.target(ringForHarmony, StanceAdvisor.ATTACK_DOMINANT), posForHarmony.get(0)), "unlocked row highlighted for comparison");
        check(HarmonyAffixGuide.candidate(ringForHarmony, StanceAdvisor.PET_DOMINANT).contains("конверсию"), "flat attack also affects pet");
        posForHarmony.add(new ApiDashboardEngine.ServerPositionEval(2, AffixPositionOptimizer.AffixType.PET_DAMAGE, "Pet", 4, 1, 5, 50, 0, false, false, false, null, 0, 0, new java.util.ArrayList<String>()));
        check(HarmonyAffixGuide.candidate(ringForHarmony, StanceAdvisor.ATTACK_DOMINANT).isEmpty(), "avoid duplicate affix");
        posForHarmony.clear();
        posForHarmony.add(new ApiDashboardEngine.ServerPositionEval(1, AffixPositionOptimizer.AffixType.GOLD_GAIN, "Gold", 4, 1, 5, 50, 0, true, false, false, null, 0, 0, new java.util.ArrayList<String>()));
        check(HarmonyAffixGuide.candidate(ringForHarmony, StanceAdvisor.ATTACK_DOMINANT).isEmpty(), "no suggestion when all rows locked");
        check(!HarmonyAffixGuide.canCompareRow(AffixPositionOptimizer.AffixType.PET_DAMAGE, posForHarmony.get(0)), "locked row is not highlighted");
        check(RetaliationGuide.alternatives(ringForHarmony, posForHarmony.get(0)).isEmpty(), "locked row has no retaliation alternatives");
        ApiDashboardEngine.ServerItemAudit helmForHarmony = new ApiDashboardEngine.ServerItemAudit(ApiDashboardEngine.EquippedSlotKey.HELMET, SlotAffixAdvisor.GearSlot.HELMET, "шлем", "шлем", 1, 1, 0, 10, 0, 0, "", "", posForHarmony, 0, 0, "");
        check(HarmonyAffixGuide.candidate(helmForHarmony, StanceAdvisor.PET_DOMINANT).isEmpty(), "no incompatible slot candidate");


        // Сумка предметов: проверка оценки СТРОГО по drop_bonus_type под класс!
        java.util.List<Map<String, Object>> testInv = new java.util.ArrayList<>();
        // 1) Перчатки с companions +31.8% (сильнее любого среднего ролла) и мусорными аффиксами -> ОСТАВИТЬ ПОД ОСНОВУ на Друиде!
        testInv.add(SimpleJson.parseObject("{\\"id\\":101,\\"name\\":\\"Дикие перчатки\\",\\"slot\\":\\"gloves\\",\\"type\\":\\"gloves\\",\\"tier\\":6,\\"drop_bonus_type\\":\\"companions\\",\\"drop_bonus_value\\":31.8}"));
        // 2) Перчатки с fierce +25.0% -> УНИЧТОЖИТЬ на Друиде, но ОСТАВИТЬ на Маге/Воине!
        testInv.add(SimpleJson.parseObject("{\\"id\\":102,\\"name\\":\\"Перчатки ярости\\",\\"slot\\":\\"gloves\\",\\"type\\":\\"gloves\\",\\"tier\\":6,\\"drop_bonus_type\\":\\"fierce\\",\\"drop_bonus_value\\":25.0}"));
        // 3) Оружие только для Воина (allowed_classes=[1]) -> УНИЧТОЖИТЬ на Друиде!
        testInv.add(SimpleJson.parseObject("{\\"id\\":103,\\"name\\":\\"Двуручный меч\\",\\"slot\\":\\"main_hand\\",\\"type\\":\\"weapon\\",\\"tier\\":6,\\"drop_bonus_type\\":\\"companions\\",\\"drop_bonus_value\\":59.0,\\"allowed_classes\\":[1]}"));
        // 4) Неистовство (frenzy = Доп. убийство) и Удача (lucky = Шанс повышенного тира) + дубль id=104 (не должен задваиваться!)
        testInv.add(SimpleJson.parseObject("{\\"id\\":104,\\"name\\":\\"Сапоги неистовства\\",\\"slot\\":\\"boots\\",\\"type\\":\\"boots\\",\\"tier\\":6,\\"drop_bonus_type\\":\\"frenzy\\",\\"drop_bonus_value\\":5.8}"));
        testInv.add(SimpleJson.parseObject("{\\"id\\":104,\\"name\\":\\"Сапоги неистовства\\",\\"slot\\":\\"boots\\",\\"type\\":\\"boots\\",\\"tier\\":6,\\"drop_bonus_type\\":\\"frenzy\\",\\"drop_bonus_value\\":5.8}"));
        testInv.add(SimpleJson.parseObject("{\\"id\\":105,\\"name\\":\\"Амулет удачи\\",\\"slot\\":\\"neck\\",\\"type\\":\\"amulet\\",\\"tier\\":6,\\"drop_bonus_type\\":\\"lucky\\",\\"drop_bonus_value\\":8.7}"));

        check(RuGameDictionary.statName("frenzy").contains("Неистовство") && RuGameDictionary.statName("frenzy").contains("Доп. убийство"), "frenzy = Неистовство (Доп. убийство)");
        check(RuGameDictionary.statName("lucky").contains("Удача"), "lucky = Удача");

        p.extraKillChance = 42.0; // ниже капа 100%, чтобы Неистовство сохранялось!
        p.overrideClassId = 5; // Друид
        BagAndPetAdvisor.InventoryAuditSummary druidBag = BagAndPetAdvisor.evaluateInventoryItems(dash, testInv, p);
        check(druidBag.totalItems == 6, "Strict ID deduplication (5 unique in testInv + 1 unique in newLoot, duplicate id=104 ignored)");
        boolean keptFrenzy = false;
        boolean keptLucky = false;
        for (BagAndPetAdvisor.BagItemEntry e : druidBag.entries) {
            if ("Сапоги неистовства".equals(e.itemName) && e.action == BagAndPetAdvisor.ItemBagAction.KEEP_FARM_PRESET) keptFrenzy = true;
            if ("Амулет удачи".equals(e.itemName) && e.action == BagAndPetAdvisor.ItemBagAction.KEEP_FARM_PRESET) keptLucky = true;
        }
        check(keptFrenzy && keptLucky, "Frenzy and Lucky drop bonuses kept for preset");
        dash.put("activeCombatStance", "harmony");
        BagAndPetAdvisor.InventoryAuditSummary hybridBag = BagAndPetAdvisor.evaluateInventoryItems(dash, testInv, p);
        for (BagAndPetAdvisor.BagItemEntry e : hybridBag.entries) {
            if ("fierce".equals(e.dropBonusSlug) || "companions".equals(e.dropBonusSlug)) {
                check(e.action != BagAndPetAdvisor.ItemBagAction.SALVAGE_DESTROY, "hybrid damage items not condemned solely by class");
            }
        }
        Map<String,Object> gemData = SimpleJson.parseObject("{\\\"bag\\\":[{\\\"family\\\":\\\"jade\\\",\\\"rarity\\\":\\\"legendary\\\",\\\"quality\\\":1}]}");
        BagAndPetAdvisor.GemBagAuditSummary safeGems = BagAndPetAdvisor.evaluateGemBag(dash, gemData, p);
        check(safeGems.entries.size() == 1, "harmony fixture contains a gem");
        for (BagAndPetAdvisor.BagGemEntry e : safeGems.entries) check(e.action == BagAndPetAdvisor.GemBagAction.KEEP_RESERVE, "harmony gem advice is conservative");
        check(safeGems.salvageGemsCount == 0 && safeGems.upgradeSocketsCount == 0, "harmony gem counters avoid unjustified choices");
        dash.remove("activeCombatStance");


        // /api/inventory returns page 1, /api/items-page/N returns a bare paginator.
        try {
            for (final int total : new int[]{61, 62, 90}) {
                InventoryPageLoader.Result loaded = InventoryPageLoader.load(new InventoryPageLoader.Fetcher() {
                    public String get(String path) {
                        int page = path.startsWith("/api/items-page/") ? Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)) : 1;
                        String wrapped = inventoryPage(page, total, "page");
                        return page == 1 ? wrapped : wrapped.substring("{\\"characterItemsPaginated\\":".length(), wrapped.length() - 1);
                    }
                });
                check(loaded.items.size() == total && loaded.total == total, "all official inventory pages " + total);
                java.util.List<java.util.Map<String,Object>> restored = new java.util.ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (String json : loaded.pages) InventoryPageLoader.append(SimpleJson.parseObject(json), restored, seen);
                check(restored.size() == total, "cached pages restore all items " + total);
            }
            InventoryPageLoader.Result ignoredPage = InventoryPageLoader.load(new InventoryPageLoader.Fetcher() {
                public String get(String path) { return inventoryPage(1, 62, "page"); }
            });
            check(ignoredPage.items.size() == 10 && ignoredPage.total == 62, "ignored page does not invent items");
            InventoryPageLoader.Result partial = InventoryPageLoader.load(new InventoryPageLoader.Fetcher() {
                public String get(String path) throws Exception {
                    if (path.endsWith("/3")) throw new Exception("offline");
                    if (path.endsWith("/2")) {
                        String wrapped = inventoryPage(2, 62, "page");
                        return wrapped.substring("{\\"characterItemsPaginated\\":".length(), wrapped.length() - 1);
                    }
                    return inventoryPage(1, 62, "page");
                }
            });
            check(partial.items.size() == 20 && partial.total == 62, "retain partial inventory after failure");
        } catch (Exception e) { throw new RuntimeException(e); }
        Map<String, Object> floorOnly = SimpleJson.parseObject("{\\"monsterLevelProgress\\":{\\"kills\\":1,\\"required\\":50}}");
        RadarTimeParser.RadarState unknownBoss = RadarTimeParser.parseRadar(floorOnly, nowMs);
        check(!unknownBoss.bossStatusKnown && !unknownBoss.bossFightReady && unknownBoss.bossFightReadyAtMs == 0,
                "floor progress is not the three hour boss cooldown");
        Map<String, Object> bossLast = SimpleJson.parseObject("{\\"last_boss_fight_at\\":" + ((nowMs - 3600000L) / 1000L) + "}");
        RadarTimeParser.RadarState lastBoss = RadarTimeParser.parseRadar(bossLast, nowMs);
        check(lastBoss.bossFightReadyAtMs > nowMs + 7198000L && lastBoss.bossFightReadyAtMs <= nowMs + 7200000L,
                "three hours calculated from last fight, not sync time");
        Map<String, Object> expiredBoss = SimpleJson.parseObject("{\\"isBossOnCooldown\\":true,\\"bossCooldownExpirationTime\\":" + ((nowMs - 60000L) / 1000L) + "}");
        check(RadarTimeParser.parseRadar(expiredBoss, nowMs).bossFightReady, "expired timestamp overrides stale cooldown flag");

        Map<String, Object> realBossStatus = SimpleJson.parseObject("{\\"can_fight\\":false,\\"seconds_until_available\\":7200,\\"cooldown_hours\\":3}");
        RadarTimeParser.RadarState realBoss = RadarTimeParser.parseRadar(floorOnly, nowMs, realBossStatus, nowMs - 60000L);
        check(realBoss.bossFightReadyAtMs == nowMs + 7140000L, "boss status countdown anchored to fetch time");
        check(RadarTimeParser.parseRadar(floorOnly, nowMs + 7200001L, realBossStatus, nowMs).bossFightReady,
                "boss status countdown expires without resync");
        Map<String, Object> lockedBoss = SimpleJson.parseObject("{\\"can_fight\\":false,\\"seconds_until_available\\":0}");
        check(!RadarTimeParser.parseRadar(floorOnly, nowMs, lockedBoss, nowMs).bossFightReady,
                "no false ready alert when server says unavailable without timer");

        // Настройки из реального /api/config/game и количество из /api/dashboard.
        Map<String, Object> catalystConfig = SimpleJson.parseObject("{\\"itemization\\":{\\"drop_bonus_enhancement\\":{\\"enabled\\":true,\\"catalyst\\":{\\"min_monster_level\\":260,\\"drop_chance\\":0.00007,\\"pity_kills\\":16000}}}}");
        Map<String, Object> cDash1 = SimpleJson.parseObject("{\\"characterId\\":1,\\"currentBiomeId\\":2,\\"craftingMaterials\\":{\\"astral-catalyst\\":3},\\"monsterLevelProgress\\":{\\"monster_level\\":300,\\"selected_monster_level\\":300,\\"effective_fighting_level\\":280,\\"kills\\":\\"1000\\"}}");
        Map<String, Object> cDash2 = SimpleJson.parseObject("{\\"characterId\\":1,\\"currentBiomeId\\":2,\\"craftingMaterials\\":{\\"astral-catalyst\\":4},\\"monsterLevelProgress\\":{\\"monster_level\\":300,\\"selected_monster_level\\":300,\\"effective_fighting_level\\":280,\\"kills\\":\\"1100\\"}}");
        CatalystTracker.State catalyst = CatalystTracker.analyze(cDash1, catalystConfig, 1000);
        check(catalyst.count == 3 && catalyst.minLevel == 260 && catalyst.pityKills == 16000 && Math.abs(catalyst.baseChance * 100 - 0.007) < 0.000001, "catalyst count, base chance and config");
        check(Math.abs(catalyst.meanHours - 1.0 / 0.07) < 0.001 && catalyst.oneHourChance > 0.067 && catalyst.oneHourChance < 0.068, "probabilistic estimate from observed progress");
        CatalystTracker.State missingCatalyst = CatalystTracker.analyze(SimpleJson.parseObject("{}"), null, 1000);
        check(missingCatalyst.count == -1 && !missingCatalyst.configKnown && missingCatalyst.meanHours < 0, "missing catalyst fields remain unknown");
        Map<String, Object> lowLevel = SimpleJson.parseObject("{\\"monsterLevelProgress\\":{\\"effective_fighting_level\\":259}}");
        check(CatalystTracker.analyze(lowLevel, catalystConfig, 1000).meanHours < 0, "no monster-drop estimate below minimum level");
        MemContext cctx = new MemContext();
        CatalystTracker.recordSync(cctx, cDash1, nowMs);
        check(CatalystTracker.getRate(cctx, nowMs) < 0, "first catalyst sample has no rate");
        CatalystTracker.recordSync(cctx, cDash2, nowMs + 60000);
        check(Math.abs(CatalystTracker.getRate(cctx, nowMs + 60000) - 6000) < 0.01, "floor progress measured over one minute");
        check(CatalystTracker.getRate(cctx, nowMs + 2000000) < 0, "old rate expires");
        Map<String, Object> changedFloor = SimpleJson.parseObject("{\\"characterId\\":1,\\"currentBiomeId\\":2,\\"monsterLevelProgress\\":{\\"monster_level\\":301,\\"selected_monster_level\\":301,\\"effective_fighting_level\\":280,\\"kills\\":1200}}");
        CatalystTracker.recordSync(cctx, changedFloor, nowMs + 120000);
        check(CatalystTracker.getRate(cctx, nowMs + 120000) < 0, "changing floor invalidates measured rate");
        CatalystTracker.clear(cctx);
        check(CatalystTracker.getRate(cctx, nowMs + 120000) < 0, "logout clears rate");
        CatalystTracker.recordSync(cctx, cDash1, nowMs + 180000);
        CatalystTracker.recordSync(cctx, cDash2, nowMs + 240000);
        Map<String, Object> otherCharacter = SimpleJson.parseObject("{\\"characterId\\":2,\\"currentBiomeId\\":2,\\"monsterLevelProgress\\":{\\"monster_level\\":300,\\"selected_monster_level\\":300,\\"effective_fighting_level\\":280,\\"kills\\":1500}}");
        CatalystTracker.recordSync(cctx, otherCharacter, nowMs + 300000);
        check(CatalystTracker.getRate(cctx, nowMs + 300000) < 0, "character switch invalidates rate");
        check(CatalystTracker.analyze(SimpleJson.parseObject("{\\"craftingMaterials\\":{\\"astral-catalyst\\":0}}"), catalystConfig, -1).count == 0, "zero inventory is not unknown");
        Map<String, Object> disabledCatalyst = SimpleJson.parseObject("{\\"itemization\\":{\\"drop_bonus_enhancement\\":{\\"enabled\\":false,\\"catalyst\\":{\\"min_monster_level\\":260,\\"drop_chance\\":0.00007}}}}");
        check(CatalystTracker.analyze(cDash1, disabledCatalyst, 1000).meanHours < 0, "disabled catalyst cannot show a time");


        // Session rates use snapshot timestamps and signed net changes, not the UI clock.
        MemContext farmCtx = new MemContext();
        long t0 = 1000000L;
        Map<String, Object> farm1 = SimpleJson.parseObject("{\\"characterId\\":1,\\"goldAmountStr\\":\\"9007199254740993000000\\",\\"gemDustStr\\":\\"500\\",\\"rubyAmount\\":100,\\"level\\":300,\\"levelProgress\\":25}");
        Map<String, Object> farm2 = SimpleJson.parseObject("{\\"characterId\\":1,\\"goldAmountStr\\":\\"9007199254740993000010\\",\\"gemDustStr\\":\\"500\\",\\"rubyAmount\\":99,\\"level\\":300,\\"levelProgress\\":75}");
        SnapshotHistoryTracker.onNewDashboardSynced(farmCtx, null, farm1, t0);
        check(!SnapshotHistoryTracker.getSessionRates(farmCtx, farm1, t0).active, "first snapshot has no rate");
        check(!SnapshotHistoryTracker.getSessionRates(farmCtx, farm1, t0).active, "old snapshot never becomes a rate as wall clock advances");
        SnapshotHistoryTracker.onNewDashboardSynced(farmCtx, farm1, farm2, t0 + 1000);
        SnapshotHistoryTracker.Observation obs = SnapshotHistoryTracker.getObservation(t0 + 1000);
        check(obs != null && obs.toMs - obs.fromMs == 1000 && obs.gold.toPlainString().equals("10")
              && obs.rubies.compareTo(new java.math.BigDecimal("-1")) == 0 && obs.shards == null, "observed delta is exact and missing data stays unknown");
        check(SnapshotHistoryTracker.getObservation(t0 + 2000) == null, "observation must match snapshot timestamp");
        SnapshotHistoryTracker.SessionRates farmRate = SnapshotHistoryTracker.getSessionRates(farmCtx, farm2, t0 + 60000);
        check(farmRate.active && Math.abs(farmRate.elapsedMinutes - 1) < 0.001, "snapshot interval used for rate");
        check(Math.abs(farmRate.goldPerHour - 600) < 0.001, "exact gold string avoids floating point loss");
        check(farmRate.dustPerHour == 0 && farmRate.rubiesPerHour == -60 && Double.isNaN(farmRate.shardsPerHour), "spending remains negative; missing resource remains unknown");
        check(Math.abs(farmRate.levelsPerHour - 30) < 0.001, "level percentage converted to estimated levels per hour");
        SnapshotHistoryTracker.SessionRates sameRate = SnapshotHistoryTracker.getSessionRates(farmCtx, farm2, t0 + 60000);
        check(sameRate.goldPerHour == farmRate.goldPerHour && sameRate.elapsedMinutes == farmRate.elapsedMinutes, "redraw does not change rates");
        SnapshotHistoryTracker.requestFarmSessionReset(farmCtx, t0 + 70000);
        check(!SnapshotHistoryTracker.getSessionRates(farmCtx, farm2, t0 + 60000).active, "reset waits for fresh snapshot");
        SnapshotHistoryTracker.onNewDashboardSynced(farmCtx, farm1, farm2, t0 + 60000);
        check(SnapshotHistoryTracker.isFarmResetPending(farmCtx), "older in-flight snapshot does not finish reset");
        SnapshotHistoryTracker.onNewDashboardSynced(farmCtx, farm1, farm2, t0 + 80000);
        check(!SnapshotHistoryTracker.isFarmResetPending(farmCtx) && !SnapshotHistoryTracker.getSessionRates(farmCtx, farm2, t0 + 80000).active, "fresh snapshot starts reset session");
        Map<String, Object> farmOther = SimpleJson.parseObject("{\\"characterId\\":2,\\"goldAmount\\":100}");
        check(!SnapshotHistoryTracker.getSessionRates(farmCtx, farmOther, t0 + 200000).active, "never mix characters");
        SnapshotHistoryTracker.onNewDashboardSynced(farmCtx, farm2, farmOther, t0 + 200000);
        check(!SnapshotHistoryTracker.getSessionRates(farmCtx, farmOther, t0 + 200000).active, "character switch starts a new session");
        check(SnapshotHistoryTracker.getObservation(t0 + 200000) == null, "observations never cross characters");
        SnapshotHistoryTracker.clearFarmSession(farmCtx);
        check(!SnapshotHistoryTracker.getSessionRates(farmCtx, farmOther, t0 + 300000).active, "logout clears farm session");
        Map<String, Object> hugeWithoutString = SimpleJson.parseObject("{\\"characterId\\":2,\\"goldAmount\\":9007199254740993000000}");
        SnapshotHistoryTracker.resetFarmSession(farmCtx, hugeWithoutString, t0);
        check(Double.isNaN(SnapshotHistoryTracker.getSessionRates(farmCtx, hugeWithoutString, t0 + 60000).goldPerHour), "inexact large numbers are not presented as exact gain");

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 500; i++) deep.append("[");
        for (int i = 0; i < 500; i++) deep.append("]");
        boolean depthRejected = false;
        try { SimpleJson.parse(deep.toString()); }
        catch (IllegalArgumentException expected) { depthRejected = true; }
        check(depthRejected, "deep JSON rejected before stack exhaustion");

        MemContext clearCtx = new MemContext();
        clearCtx.prefs.edit().putString("inv_page_1", "private1").putString("inv_page_99", "private99")
                .putInt("inv_page_cnt", 1).putString("guild_json", "privateGuild").apply();
        IdleArcApiClient.clearCredentials(clearCtx);
        check(clearCtx.prefs.getString("inv_page_1", "").isEmpty()
                && clearCtx.prefs.getString("inv_page_99", "").isEmpty()
                && clearCtx.prefs.getString("guild_json", "").isEmpty(), "logout clears old cache pages and guild");
        boolean rejectedPath = false;
        try { IdleArcApiClient.fetchAuthenticatedGetJson("fake", "//evil.example/"); }
        catch (IllegalArgumentException expected) { rejectedPath = true; }
        catch (Exception other) { throw new RuntimeException(other); }
        check(rejectedPath, "API rejects injected paths before making a request");

        // Проверка отката Боя с Боссом в RadarTimeParser
        Map<String, Object> bossDash = SimpleJson.parseObject("{\\"isBossOnCooldown\\":true,\\"bossCooldownExpirationTime\\":" + ((nowMs / 1000L) + 1800L) + "}");
        RadarTimeParser.RadarState bossRs = RadarTimeParser.parseRadar(bossDash, nowMs);
        check(!bossRs.bossFightReady && bossRs.bossFightReadyAtMs > nowMs, "Boss fight cooldown parsed for system alarm");
        boolean keptCompanionsOnDruid = false;
        boolean destroyedFierceOnDruid = false;
        boolean destroyedWrongClass = false;
        for (BagAndPetAdvisor.BagItemEntry e : druidBag.entries) {
            if ("Дикие перчатки".equals(e.itemName) && e.action == BagAndPetAdvisor.ItemBagAction.KEEP_UPGRADE_BASE) keptCompanionsOnDruid = true;
            if ("Перчатки ярости".equals(e.itemName) && e.action == BagAndPetAdvisor.ItemBagAction.SALVAGE_DESTROY) destroyedFierceOnDruid = true;
            if ("Двуручный меч".equals(e.itemName) && e.action == BagAndPetAdvisor.ItemBagAction.SALVAGE_DESTROY) destroyedWrongClass = true;
        }
        check(keptCompanionsOnDruid, "Druid keeps higher companions drop bonus regardless of rerollable affixes");
        check(destroyedFierceOnDruid, "Druid salvages fierce drop bonus");
        check(destroyedWrongClass, "Druid salvages Warrior-only weapon");

        // Переключаем класс на Мага (3): теперь fierce = БИС (ОСТАВИТЬ), а companions = УНИЧТОЖИТЬ!
        p.overrideClassId = 3;
        BagAndPetAdvisor.InventoryAuditSummary mageBag = BagAndPetAdvisor.evaluateInventoryItems(dash, testInv, p);
        boolean keptFierceOnMage = false;
        boolean destroyedCompanionsOnMage = false;
        for (BagAndPetAdvisor.BagItemEntry e : mageBag.entries) {
            if ("Перчатки ярости".equals(e.itemName) && e.action == BagAndPetAdvisor.ItemBagAction.KEEP_UPGRADE_BASE) keptFierceOnMage = true;
            if ("Дикие перчатки".equals(e.itemName) && e.action == BagAndPetAdvisor.ItemBagAction.SALVAGE_DESTROY) destroyedCompanionsOnMage = true;
        }
        check(keptFierceOnMage, "Mage keeps fierce drop bonus");
        check(destroyedCompanionsOnMage, "Mage salvages companions drop bonus");
        p.overrideClassId = 0;

        // Мешок самоцветов (/api/gems) и Советник аффиксов петов при extraKill >= 100%
        Map<String, Object> fakeGems = SimpleJson.parseObject("{\\"bag_cap\\":100,\\"bag\\":["
            + "{\\"id\\":1,\\"family\\":\\"amber\\",\\"rarity\\":5,\\"rarity_slug\\":\\"flawless\\",\\"quality\\":92,\\"pristine\\":false,\\"secondaries\\":[]},"
            + "{\\"id\\":2,\\"family\\":\\"lapis\\",\\"rarity\\":3,\\"rarity_slug\\":\\"polished\\",\\"quality\\":84,\\"pristine\\":false,\\"secondaries\\":[]},"
            + "{\\"id\\":3,\\"family\\":\\"garnet\\",\\"rarity\\":4,\\"rarity_slug\\":\\"brilliant\\",\\"quality\\":72,\\"pristine\\":false,\\"secondaries\\":[]}"
            + "]}");
        BagAndPetAdvisor.GemBagAuditSummary gemBag = BagAndPetAdvisor.evaluateGemBag(dash, fakeGems, p);
        check(gemBag.bagCount == 3 && gemBag.countAmber == 1 && gemBag.countLapis == 1, "Gem bag parsed");

        p.extraKillChance = 112.0;
        BagAndPetAdvisor.PetDeepAdvice petAdv = BagAndPetAdvisor.analyzePetsDeep(dash, fakeGems, p);
        check(petAdv.gemSynergyNotes.get(0).contains("КАП ДОП. УБИЙСТВА ЗАКРЫТ"), "Detects extra kill >= 100% cap on pets");

        InventoryPageLoader.Result strictBag = InventoryPageLoader.loadStrict(new InventoryPageLoader.Fetcher() {
            public String get(String path) throws Exception {
                if (path.equals("/api/inventory")) return inventoryPage(1, 11, "page");
                if (path.equals("/api/items-page/2")) return "{\\"current_page\\":2,\\"data\\":[{\\"id\\":11}]}";
                throw new Exception("unexpected path");
            }
        });
        check(strictBag.items.size() == 11 && strictBag.pages.size() == 2, "live bag loads all pages");
        boolean strictFailed = false;
        try { InventoryPageLoader.loadStrict(new InventoryPageLoader.Fetcher() {
            public String get(String path) throws Exception {
                if (path.equals("/api/inventory")) return inventoryPage(1, 11, "page");
                throw new Exception("HTTP 429");
            }
        }); } catch (Exception e) { strictFailed = e.getMessage().contains("429"); }
        check(strictFailed, "partial bag failure propagates instead of replacing cached bag");
        boolean invalidBag = false;
        try { InventoryPageLoader.loadStrict(new InventoryPageLoader.Fetcher() {
            public String get(String path) { return "{\\"invalid\\":true}"; }
        }); } catch (Exception e) { invalidBag = true; }
        check(invalidBag, "invalid first bag page rejected");

        check(LivePollPolicy.failureDelayMs(1, false) == 5000 && LivePollPolicy.failureDelayMs(5, false) == 60000
                && LivePollPolicy.failureDelayMs(1, true) == 60000, "live error and rate-limit backoff");
        java.net.URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new java.net.URLStreamHandler() {
            protected java.net.URLConnection openConnection(java.net.URL url) { return new FakeConnection(url); }
        } : null);
        MemContext liveCtx = new MemContext();
        IdleArcApiClient.saveCredentials(liveCtx, "x", "", "synthetic-token");
        java.util.concurrent.CountDownLatch doneLive = new java.util.concurrent.CountDownLatch(1);
        final String[] liveError = {null};
        IdleArcApiClient.syncDashboardAsync(liveCtx, new IdleArcApiClient.ApiCallback() {
            public void onSuccess(ScreenAnalyzer.ScanResult ignored) { doneLive.countDown(); }
            public void onError(String err) { liveError[0] = err; doneLive.countDown(); }
        });
        check(FakeConnection.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "live request started");
        final String[] busy = {null};
        IdleArcApiClient.syncAsync(liveCtx, p, new IdleArcApiClient.ApiCallback() {
            public void onSuccess(ScreenAnalyzer.ScanResult ignored) {}
            public void onError(String err) { busy[0] = err; }
        });
        check(IdleArcApiClient.SYNC_BUSY_MESSAGE.equals(busy[0]), "full and live requests cannot overlap");
        FakeConnection.release.countDown();
        check(doneLive.await(5, java.util.concurrent.TimeUnit.SECONDS) && liveError[0] == null, "live snapshot succeeds");
        check(FakeConnection.paths.size() == 1 && FakeConnection.paths.get(0).equals("/api/dashboard"), "live sync fetches only dashboard");
        check(IdleArcApiClient.getLastSyncTimeMs() > 0, "live snapshot timestamp recorded");
        // Give the worker's finally block time to release the single-flight gate.
        for (int i = 0; i < 50; i++) { Thread.sleep(10); if (!IdleArcApiClient.isSyncBusy()) break; }
        FakeConnection.entered = new java.util.concurrent.CountDownLatch(1);
        FakeConnection.release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch loggedOutLive = new java.util.concurrent.CountDownLatch(1);
        final String[] cancelled = {null};
        IdleArcApiClient.syncDashboardAsync(liveCtx, new IdleArcApiClient.ApiCallback() {
            public void onSuccess(ScreenAnalyzer.ScanResult ignored) { cancelled[0] = "unexpected success"; loggedOutLive.countDown(); }
            public void onError(String err) { cancelled[0] = err; loggedOutLive.countDown(); }
        });
        check(FakeConnection.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "second live request started");
        IdleArcApiClient.clearCredentials(liveCtx);
        FakeConnection.release.countDown();
        check(loggedOutLive.await(5, java.util.concurrent.TimeUnit.SECONDS) && cancelled[0] != null && IdleArcApiClient.getCachedDashboard() == null,
                "logout invalidates in-flight live response");

        // End-to-end live section refresh through a fake HTTP transport.
        for (int i = 0; i < 50; i++) { Thread.sleep(10); if (!IdleArcApiClient.isSyncBusy()) break; }
        IdleArcApiClient.saveCredentials(liveCtx, "x", "", "synthetic-token");
        FakeConnection.release = new java.util.concurrent.CountDownLatch(0);
        java.util.concurrent.CountDownLatch prepared = new java.util.concurrent.CountDownLatch(1);
        IdleArcApiClient.syncDashboardAsync(liveCtx, new IdleArcApiClient.ApiCallback() {
            public void onSuccess(ScreenAnalyzer.ScanResult ignored) { prepared.countDown(); }
            public void onError(String err) { throw new RuntimeException(err); }
        });
        check(prepared.await(5, java.util.concurrent.TimeUnit.SECONDS), "dashboard associates account before section refresh");
        for (int i = 0; i < 50; i++) { Thread.sleep(10); if (!IdleArcApiClient.isSyncBusy()) break; }
        FakeConnection.paths.clear();
        FakeConnection.bodies.put("/api/inventory", inventoryPage(1, 11, "page"));
        FakeConnection.bodies.put("/api/items-page/2", "{\\"current_page\\":2,\\"data\\":[{\\"id\\":11}]}");
        final boolean[] interested = {true};
        IdleArcApiClient.LiveResourceInterest interest = new IdleArcApiClient.LiveResourceInterest() {
            public boolean isInterested(int resource) { return interested[0]; }
            public void onDashboard() {}
        };
        java.util.concurrent.CountDownLatch bagDone = new java.util.concurrent.CountDownLatch(1);
        final String[] bagError = {null};
        IdleArcApiClient.syncLiveSectionAsync(liveCtx, LiveSections.INVENTORY, interest, new IdleArcApiClient.ApiCallback() {
            public void onSuccess(ScreenAnalyzer.ScanResult ignored) { bagDone.countDown(); }
            public void onError(String err) { bagError[0]=err; bagDone.countDown(); }
        });
        check(bagDone.await(5, java.util.concurrent.TimeUnit.SECONDS) && bagError[0]==null, "live inventory succeeds");
        check(FakeConnection.paths.equals(java.util.Arrays.asList("/api/inventory", "/api/items-page/2"))
                && IdleArcApiClient.getCachedInventoryItems().size()==11, "only inventory pages fetched and complete bag committed");
        for (int i = 0; i < 50; i++) { Thread.sleep(10); if (!IdleArcApiClient.isSyncBusy()) break; }
        FakeConnection.codes.put("/api/items-page/2", 429);
        java.util.concurrent.CountDownLatch failedBag = new java.util.concurrent.CountDownLatch(1);
        final String[] failedReason={null};
        IdleArcApiClient.syncLiveSectionAsync(liveCtx, LiveSections.INVENTORY, interest, new IdleArcApiClient.ApiCallback() {
            public void onSuccess(ScreenAnalyzer.ScanResult ignored) { failedBag.countDown(); }
            public void onError(String err) { failedReason[0]=err; failedBag.countDown(); }
        });
        check(failedBag.await(5, java.util.concurrent.TimeUnit.SECONDS) && failedReason[0]!=null && failedReason[0].contains("429")
                && IdleArcApiClient.getCachedInventoryItems().size()==11, "rate limit retains previous complete bag");
        FakeConnection.codes.clear();
        for (int i = 0; i < 50; i++) { Thread.sleep(10); if (!IdleArcApiClient.isSyncBusy()) break; }
        interested[0]=false;
        FakeConnection.paths.clear();
        java.util.concurrent.CountDownLatch notVisible = new java.util.concurrent.CountDownLatch(1);
        final String[] notVisibleReason={null};
        IdleArcApiClient.syncLiveSectionAsync(liveCtx, LiveSections.INVENTORY, interest, new IdleArcApiClient.ApiCallback() {
            public void onSuccess(ScreenAnalyzer.ScanResult ignored) { notVisible.countDown(); }
            public void onError(String err) { notVisibleReason[0]=err; notVisible.countDown(); }
        });
        check(notVisible.await(5, java.util.concurrent.TimeUnit.SECONDS) && IdleArcApiClient.LIVE_CANCELLED.equals(notVisibleReason[0])
                && FakeConnection.paths.isEmpty(), "hidden bag starts no requests");

        // A long inventory traversal must continue sampling dashboard without overlapping requests.
        for (int i = 0; i < 50; i++) { Thread.sleep(10); if (!IdleArcApiClient.isSyncBusy()) break; }
        interested[0]=true;
        FakeConnection.paths.clear();
        FakeConnection.bodies.put("/api/inventory", inventoryPage(1, 41, "page"));
        for (int page=2; page<=5; page++) {
            String raw = inventoryPage(page, 41, "page");
            FakeConnection.bodies.put("/api/items-page/"+page,
                    raw.substring(raw.indexOf("{", raw.indexOf("characterItemsPaginated")), raw.length()-1));
        }
        final int[] progress={0};
        IdleArcApiClient.LiveResourceInterest longInterest=new IdleArcApiClient.LiveResourceInterest() {
            public boolean isInterested(int resource) { return true; }
            public void onDashboard() { progress[0]++; }
        };
        java.util.concurrent.CountDownLatch longBag = new java.util.concurrent.CountDownLatch(1);
        final String[] longError={null};
        IdleArcApiClient.syncLiveSectionAsync(liveCtx, LiveSections.INVENTORY, longInterest, new IdleArcApiClient.ApiCallback() {
            public void onSuccess(ScreenAnalyzer.ScanResult ignored) { longBag.countDown(); }
            public void onError(String err) { longError[0]=err; longBag.countDown(); }
        });
        check(longBag.await(8, java.util.concurrent.TimeUnit.SECONDS) && longError[0]==null, "long bag completes");
        check(progress[0]>0 && FakeConnection.paths.contains("/api/dashboard")
                && IdleArcApiClient.getCachedInventoryItems().size()==41, "dashboard sampled during paginated bag refresh");

        for (int i = 0; i < 50; i++) { Thread.sleep(10); if (!IdleArcApiClient.isSyncBusy()) break; }
        FakeConnection.bodies.put("/api/dashboard", "{\\"characterId\\":2,\\"level\\":11}");
        java.util.concurrent.CountDownLatch switched = new java.util.concurrent.CountDownLatch(1);
        final String[] switchError = {null};
        IdleArcApiClient.syncDashboardAsync(liveCtx, new IdleArcApiClient.ApiCallback() {
            public void onSuccess(ScreenAnalyzer.ScanResult ignored) { switched.countDown(); }
            public void onError(String err) { switchError[0]=err; switched.countDown(); }
        });
        check(switched.await(5, java.util.concurrent.TimeUnit.SECONDS) && switchError[0] == null
                && IdleArcApiClient.getCachedInventoryItems().isEmpty(), "switching character clears old bag");
        check(SnapshotHistoryTracker.getObservation(IdleArcApiClient.getLastSyncTimeMs()) == null,
                "switching character does not produce fake resource changes");

        System.out.println("API_DASHBOARD_SUITE_OK");
    }
}
""",
                encoding="utf-8",
            )

            java_files = [
                str(tmp / "android" / "os" / "SystemClock.java"),
                str(tmp / "android" / "content" / "Context.java"),
                str(tmp / "android" / "content" / "SharedPreferences.java"),
                str(SRC_DIR / "SimpleJson.java"),
                str(SRC_DIR / "HarmonyInfo.java"),
                str(SRC_DIR / "StanceAdvisor.java"),
                str(SRC_DIR / "HarmonyBalance.java"),
                str(SRC_DIR / "HarmonyAffixGuide.java"),
                str(SRC_DIR / "RetaliationGuide.java"),
                str(SRC_DIR / "LivePollPolicy.java"),
                str(SRC_DIR / "LiveSections.java"),
                str(SRC_DIR / "DruidProfile.java"),
                str(SRC_DIR / "LiveBuildSimulator.java"),
                str(SRC_DIR / "IdleArcOcrEngine.java"),
                str(SRC_DIR / "DruidKnowledgeBase.java"),
                str(SRC_DIR / "SlotAffixAdvisor.java"),
                str(SRC_DIR / "GemSocketOptimizer.java"),
                str(SRC_DIR / "AffixPositionOptimizer.java"),
                str(SRC_DIR / "ScreenAnalyzer.java"),
                str(SRC_DIR / "IdleArcApiClient.java"),
                str(SRC_DIR / "InventoryPageLoader.java"),
                str(SRC_DIR / "CatalystTracker.java"),
                str(SRC_DIR / "RuGameDictionary.java"),
                str(SRC_DIR / "HeroClassProfile.java"),
                str(SRC_DIR / "BagAndPetAdvisor.java"),
                str(SRC_DIR / "SnapshotHistoryTracker.java"),
                str(SRC_DIR / "RadarTimeParser.java"),
                str(SRC_DIR / "ApiFeatureAnalyzers.java"),
                str(SRC_DIR / "ApiDashboardEngine.java"),
                str(tmp / "ApiClientSuite.java"),
            ]

            java_bin = str(jdk4py.JAVA)
            javac_bin = str(jdk4py.JAVA_HOME / "bin" / "javac")
            if ECJ_JAR.exists():
                comp_cmd = [java_bin, "-Dfile.encoding=UTF-8", "-jar", str(ECJ_JAR), "-17", "-encoding", "UTF-8", "-d", str(tmp)] + java_files
            elif TOOLS_JAR.exists():
                comp_cmd = [java_bin, "-Dfile.encoding=UTF-8", "-cp", str(TOOLS_JAR), "com.sun.tools.javac.Main", "-source", "8", "-target", "8", "-bootclasspath", "/tmp/android-tools/rt.jar", "-encoding", "UTF-8", "-d", str(tmp)] + java_files
            else:
                comp_cmd = [javac_bin, "-J-Dfile.encoding=UTF-8", "--release", "17", "-encoding", "UTF-8", "-d", str(tmp)] + java_files

            comp = subprocess.run(comp_cmd, capture_output=True, text=True)
            self.assertEqual(comp.returncode, 0, f"Java compile failed:\n{comp.stdout}\n{comp.stderr}")

            env = os.environ.copy()
            env["LC_ALL"] = "C.UTF-8"
            run = subprocess.run(
                [java_bin, "-Dfile.encoding=UTF-8", "-cp", str(tmp), "ApiClientSuite"],
                capture_output=True,
                text=True,
                env=env,
            )
            self.assertEqual(run.returncode, 0, f"ApiClientSuite failed:\n{run.stdout}\n{run.stderr}")
            self.assertIn("API_DASHBOARD_SUITE_OK", run.stdout)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
