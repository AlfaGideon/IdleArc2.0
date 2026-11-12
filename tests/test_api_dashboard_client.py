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

    public static void main(String[] args) {
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
        check("Урон питомцев".equals(RuGameDictionary.statName("companions")), "companions drop bonus translated");
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

        System.out.println("API_DASHBOARD_SUITE_OK");
    }
}
""",
                encoding="utf-8",
            )

            java_files = [
                str(tmp / "android" / "content" / "Context.java"),
                str(tmp / "android" / "content" / "SharedPreferences.java"),
                str(SRC_DIR / "SimpleJson.java"),
                str(SRC_DIR / "DruidProfile.java"),
                str(SRC_DIR / "LiveBuildSimulator.java"),
                str(SRC_DIR / "IdleArcOcrEngine.java"),
                str(SRC_DIR / "DruidKnowledgeBase.java"),
                str(SRC_DIR / "SlotAffixAdvisor.java"),
                str(SRC_DIR / "GemSocketOptimizer.java"),
                str(SRC_DIR / "AffixPositionOptimizer.java"),
                str(SRC_DIR / "ScreenAnalyzer.java"),
                str(SRC_DIR / "IdleArcApiClient.java"),
                str(SRC_DIR / "RuGameDictionary.java"),
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
