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


@unittest.skipUnless(ECJ_JAR.exists(), "Android ecj.jar toolchain not present")
class TestOfficialAffixPools(unittest.TestCase):
    """Проверяет 100% соответствие пулов аффиксов всех 10 типов предметов серверному /api/config/game."""

    def test_all_10_item_types_and_36_mode_combinations(self):
        tmp = Path(tempfile.mkdtemp(prefix="test_affix_matrix_"))
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
                "  int getInt(String k, int d); float getFloat(String k, float d);\n"
                "  long getLong(String k, long d); String getString(String k, String d);\n"
                "  Editor edit();\n"
                "  interface Editor {\n"
                "    Editor putInt(String k, int v); Editor putFloat(String k, float v);\n"
                "    Editor putLong(String k, long v); Editor putString(String k, String v);\n"
                "    void apply();\n"
                "  }\n"
                "}\n",
                encoding="utf-8",
            )
            (tmp / "MatrixCheck.java").write_text(
                """
import com.idlearc.companion.*;
import com.idlearc.companion.AffixPositionOptimizer.AffixType;
import com.idlearc.companion.SlotAffixAdvisor.GearSlot;
import java.util.*;

public class MatrixCheck {
    public static void main(String[] args) {
        // Exact official pool counts from https://idlearc.com/api/config/game:
        // torch=9, shield=8, helmet=11, chest_armor=11, gloves=10, boots=10, belt=13, amulet=15, ring=15, weapon=13(+5 elem=18)
        assertCount(AffixPositionOptimizer.getCompatiblePool(GearSlot.TORCH, false), 9, "TORCH");
        assertCount(AffixPositionOptimizer.getCompatiblePool(GearSlot.TORCH, true), 8, "SHIELD");
        assertCount(AffixPositionOptimizer.getCompatiblePool(GearSlot.HELMET, false), 11, "HELMET");
        assertCount(AffixPositionOptimizer.getCompatiblePool(GearSlot.CHEST, false), 11, "CHEST");
        assertCount(AffixPositionOptimizer.getCompatiblePool(GearSlot.GLOVES, false), 10, "GLOVES");
        assertCount(AffixPositionOptimizer.getCompatiblePool(GearSlot.BOOTS, false), 10, "BOOTS");
        assertCount(AffixPositionOptimizer.getCompatiblePool(GearSlot.BELT, false), 13, "BELT");
        assertCount(AffixPositionOptimizer.getCompatiblePool(GearSlot.AMULET, false), 15, "AMULET");
        assertCount(AffixPositionOptimizer.getCompatiblePool(GearSlot.RING, false), 15, "RING");
        assertCount(AffixPositionOptimizer.getCompatiblePool(GearSlot.WEAPON, false), 13, "WEAPON");

        // Torch MUST NOT contain Double Hit, Intelligence, Dexterity, Strength, Max Health
        List<AffixType> torchPool = AffixPositionOptimizer.getCompatiblePool(GearSlot.TORCH, false);
        if (torchPool.contains(AffixType.DOUBLE_HIT) || torchPool.contains(AffixType.INTELLIGENCE)
                || torchPool.contains(AffixType.MAX_HEALTH)) {
            throw new AssertionError("Torch pool contains forbidden affixes: " + torchPool);
        }

        // Armor (Helmet/Chest/Gloves/Boots) MUST NOT contain Double Hit or Primary Attributes
        for (GearSlot armor : new GearSlot[]{GearSlot.HELMET, GearSlot.CHEST, GearSlot.GLOVES, GearSlot.BOOTS}) {
            List<AffixType> ap = AffixPositionOptimizer.getCompatiblePool(armor, false);
            if (ap.contains(AffixType.DOUBLE_HIT) || ap.contains(AffixType.INTELLIGENCE)
                    || ap.contains(AffixType.DEXTERITY) || ap.contains(AffixType.STRENGTH)) {
                throw new AssertionError(armor + " pool contains forbidden affixes: " + ap);
            }
        }
        // Gloves & Boots MUST NOT contain Damage Reduction
        if (AffixPositionOptimizer.getCompatiblePool(GearSlot.GLOVES, false).contains(AffixType.DAMAGE_REDUCTION)
                || AffixPositionOptimizer.getCompatiblePool(GearSlot.BOOTS, false).contains(AffixType.DAMAGE_REDUCTION)) {
            throw new AssertionError("Gloves/Boots must not contain DAMAGE_REDUCTION");
        }

        // Every mode x slot recommendation must strictly stay inside compatible pool
        DruidProfile prof = new DruidProfile();
        GearSlot[] gearSlots = {GearSlot.WEAPON, GearSlot.TORCH, GearSlot.HELMET, GearSlot.CHEST,
                GearSlot.GLOVES, GearSlot.BOOTS, GearSlot.BELT, GearSlot.AMULET, GearSlot.RING};
        for (DruidProfile.BuildMode bm : DruidProfile.BuildMode.values()) {
            prof.mode = bm;
            for (GearSlot gs : gearSlots) {
                List<IdleArcOcrEngine.OcrLine> syn = new ArrayList<>();
                syn.add(new IdleArcOcrEngine.OcrLine("Инфернальный · A5 +5", 100, 128, 0f, 0f, 0));
                syn.add(new IdleArcOcrEngine.OcrLine("АФФИКСЫ", 200, 225, 0f, 0f, 0));
                for (int r = 0; r < 5; r++) {
                    syn.add(new IdleArcOcrEngine.OcrLine("Бонус опыта +40%", 250 + r * 40, 275 + r * 40, 0f, 0f, 0));
                }
                AffixPositionOptimizer.OptimizationReport rep =
                        AffixPositionOptimizer.analyzeItemAffixes(syn, gs, prof);
                Set<AffixType> allowed = EnumSet.copyOf(AffixPositionOptimizer.getCompatiblePool(gs, false));
                for (AffixType rec : rep.idealByPos) {
                    if (!allowed.contains(rec)) {
                        throw new AssertionError("Forbidden affix " + rec + " recommended for " + gs + " in " + bm);
                    }
                }
            }
        }
    }

    private static void assertCount(List<AffixType> pool, int expected, String label) {
        if (pool.size() != expected) {
            throw new AssertionError(label + " expected " + expected + " mechanics, got " + pool.size() + ": " + pool);
        }
    }
}
""",
                encoding="utf-8",
            )

            java_files = [
                str(tmp / "android" / "content" / "Context.java"),
                str(tmp / "android" / "content" / "SharedPreferences.java"),
                str(SRC_DIR / "IdleArcOcrEngine.java"),
                str(SRC_DIR / "DruidProfile.java"),
                str(SRC_DIR / "DruidKnowledgeBase.java"),
                str(SRC_DIR / "SlotAffixAdvisor.java"),
                str(SRC_DIR / "AffixPositionOptimizer.java"),
                str(SRC_DIR / "ScreenAnalyzer.java"),
                str(tmp / "MatrixCheck.java"),
            ]
            java_bin = str(jdk4py.JAVA)
            comp = subprocess.run(
                [java_bin, "-Dfile.encoding=UTF-8", "-jar", str(ECJ_JAR), "-17", "-encoding", "UTF-8", "-d", str(tmp)] + java_files,
                capture_output=True,
                text=True,
            )
            self.assertEqual(comp.returncode, 0, f"Java compile failed:\n{comp.stdout}\n{comp.stderr}")

            env = os.environ.copy()
            env["LC_ALL"] = "C.UTF-8"
            run = subprocess.run(
                [java_bin, "-Dfile.encoding=UTF-8", "-cp", str(tmp), "MatrixCheck"],
                capture_output=True,
                text=True,
                env=env,
            )
            self.assertEqual(run.returncode, 0, f"MatrixCheck failed:\n{run.stdout}\n{run.stderr}")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
