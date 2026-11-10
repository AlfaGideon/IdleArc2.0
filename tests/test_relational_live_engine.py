import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import jdk4py


ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = ROOT / "companion" / "overlay" / "app" / "src" / "main" / "java" / "com" / "idlearc" / "companion"
GLYPHS_BIN = ROOT / "companion" / "overlay" / "app" / "src" / "main" / "assets" / "ocr_glyphs.bin"
ECJ_JAR = Path("/tmp/android-tools/ecj.jar")


@unittest.skipUnless(ECJ_JAR.exists(), "Android ecj.jar toolchain not present")
class TestRelationalLiveEngine(unittest.TestCase):
    """
    Проверяет, что в v2.0.0:
      1) Нет ни одной захардкоженной характеристики игрока или готового ответа.
      2) Лучший параметр на одном и том же предмете ДИНАМИЧЕСКИ меняется при изменении текущих статов персонажа.
      3) Для НАДЕТОГО предмета («Снять») симулятор вычитает стат проверяемой строки перед расчётом капов (95% DR / 95% Dodge).
      4) Реальный визуальный OCR считывает живые статы из PNG-скриншотов «Характеристик персонажа» и прогоняет все скриншоты.
    """

    def test_zero_hardcoded_player_stats_in_java_sources(self):
        forbidden = re.compile(
            r"AlfaGideon|131\.7|3497|83\.48|A6 \+30|A5 \+5|Оба талисмана|Рунная палка"
        )
        for java_file in SRC_DIR.glob("*.java"):
            text = java_file.read_text(encoding="utf-8")
            m = forbidden.search(text)
            self.assertIsNone(
                m,
                f"Found hardcoded screenshot string {m.group(0) if m else ''} in {java_file.name}",
            )

    def test_live_ocr_and_relational_math_simulator(self):
        tmp = Path(tempfile.mkdtemp(prefix="test_rel_engine_"))
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

            (tmp / "RelationalSuite.java").write_text(
                """
import com.idlearc.companion.*;
import com.idlearc.companion.AffixPositionOptimizer.AffixType;
import com.idlearc.companion.SlotAffixAdvisor.GearSlot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import javax.imageio.ImageIO;

public class RelationalSuite {
    public static void main(String[] args) throws Exception {
        File glyphsFile = new File(args[0]);
        File rootDir = new File(args[1]);

        // 1. Чистый профиль на старте (0% предзаполненных статов)
        DruidProfile fresh = new DruidProfile();
        if (fresh.hasScannedStats || fresh.scannedStatsCount != 0 || fresh.charLevel != 1
                || fresh.gearScore != 0 || fresh.dodgeRaw != 0.0 || fresh.damageReductionRaw != 0.0) {
            throw new AssertionError("DruidProfile is NOT clean on startup!");
        }

        // 2. Доказательство относительного выбора: ОДИН И ТОТ ЖЕ предмет даёт РАЗНЫЙ лучший стат
        // при изменении текущих характеристик персонажа!
        List<IdleArcOcrEngine.OcrLine> weaponCard = Arrays.asList(
                new IdleArcOcrEngine.OcrLine("Посох · А5 +10", 100, 125, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("+4500 Физический урон", 135, 160, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("АФФИКСЫ", 180, 205, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Урон питомца +180%", 215, 240, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Шанс двойного удара +40%", 250, 275, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Шанс двойного урона +40%", 285, 310, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Интеллект +110", 320, 345, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Бонус золота +90%", 355, 380, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Надеть", 420, 450, 0f, 0f, 0, 220)
        );

        // Профиль А: низкий Шанс крита (12%), огромный Крит. урон (700%), умеренный DD (30%)
        // -> Топ-5: [PET_DAMAGE, DOUBLE_HIT, DOUBLE_DAMAGE, INTELLIGENCE, CRIT_CHANCE] (CRIT_CHANCE внутри, CRIT_DAMAGE вне!)
        DruidProfile lowCcHero = new DruidProfile();
        lowCcHero.hasScannedStats = true;
        lowCcHero.critChance = 12.0;
        lowCcHero.critDamage = 700.0;
        lowCcHero.doubleHitChance = 60.0;
        lowCcHero.doubleDamageChance = 30.0;
        lowCcHero.petDamageBonusPct = 800.0;
        lowCcHero.dodgeRaw = 95.0;
        lowCcHero.damageReductionRaw = 95.0;
        AffixPositionOptimizer.OptimizationReport repLowCc =
                AffixPositionOptimizer.analyzeItemAffixes(weaponCard, GearSlot.WEAPON, lowCcHero);
        if (!repLowCc.idealByPos.contains(AffixType.CRIT_CHANCE)
                || repLowCc.idealByPos.contains(AffixType.CRIT_DAMAGE)) {
            throw new AssertionError("Expected CRIT_CHANCE (and not CRIT_DAMAGE) for 12% CC / 700% CD hero, got: " + repLowCc.idealByPos);
        }

        // Профиль Б: Шанс крита 110%, высокий Крит. урон 600%, низкий DD 30%
        // -> ИНТЕЛЛЕКТ (+16.9% DPS) и CRIT_DAMAGE обходят CRIT_CHANCE (+7.6% DPS)!
        // Топ-5: [PET_DAMAGE, DOUBLE_HIT, DOUBLE_DAMAGE, INTELLIGENCE, CRIT_DAMAGE] (CRIT_DAMAGE внутри, CRIT_CHANCE вне!)
        DruidProfile highCdLowDdHero = new DruidProfile();
        highCdLowDdHero.hasScannedStats = true;
        highCdLowDdHero.critChance = 110.0;
        highCdLowDdHero.critDamage = 600.0;
        highCdLowDdHero.doubleHitChance = 60.0;
        highCdLowDdHero.doubleDamageChance = 30.0;
        highCdLowDdHero.petDamageBonusPct = 800.0;
        highCdLowDdHero.dodgeRaw = 95.0;
        highCdLowDdHero.damageReductionRaw = 95.0;
        AffixPositionOptimizer.OptimizationReport repHighCdLowDd =
                AffixPositionOptimizer.analyzeItemAffixes(weaponCard, GearSlot.WEAPON, highCdLowDdHero);
        if (!repHighCdLowDd.idealByPos.contains(AffixType.CRIT_DAMAGE)
                || !repHighCdLowDd.idealByPos.contains(AffixType.INTELLIGENCE)
                || repHighCdLowDd.idealByPos.contains(AffixType.CRIT_CHANCE)) {
            throw new AssertionError("Expected CRIT_DAMAGE + INTELLIGENCE (and not CRIT_CHANCE) for 110% CC / 600% CD / 30% DD, got: "
                    + repHighCdLowDd.idealByPos);
        }

        // Профиль В: Шанс крита 110%, просевший Крит. урон 250%, высокий DD 60%
        // -> ОБА критовых аффикса (CRIT_DAMAGE + CRIT_CHANCE) математически обходят ИНТЕЛЛЕКТ!
        DruidProfile lowCdHighDdHero = new DruidProfile();
        lowCdHighDdHero.hasScannedStats = true;
        lowCdHighDdHero.critChance = 110.0;
        lowCdHighDdHero.critDamage = 250.0;
        lowCdHighDdHero.doubleHitChance = 60.0;
        lowCdHighDdHero.doubleDamageChance = 60.0;
        lowCdHighDdHero.petDamageBonusPct = 800.0;
        lowCdHighDdHero.dodgeRaw = 95.0;
        lowCdHighDdHero.damageReductionRaw = 95.0;
        AffixPositionOptimizer.OptimizationReport repLowCdHighDd =
                AffixPositionOptimizer.analyzeItemAffixes(weaponCard, GearSlot.WEAPON, lowCdHighDdHero);
        if (!repLowCdHighDd.idealByPos.contains(AffixType.CRIT_DAMAGE)
                || !repLowCdHighDd.idealByPos.contains(AffixType.CRIT_CHANCE)
                || repLowCdHighDd.idealByPos.contains(AffixType.INTELLIGENCE)) {
            throw new AssertionError("Expected BOTH CRIT_DAMAGE + CRIT_CHANCE beating INTELLIGENCE for 110% CC / 250% CD / 60% DD, got: "
                    + repLowCdHighDd.idealByPos);
        }

        // 3. Доказательство честного ВЫЧИТАНИЯ строки на НАДЕТОМ предмете («Снять» vs «Надеть»)
        // На герое сейчас 95.0% Снижения урона. На НАДЕТОМ нагруднике («Снять») стоит +14.0% Снижение урона.
        // Если его снять — у героя останется 81.0% Снижения урона (входящий урон вырастет в 3.8 раза!).
        // Значит, на НАДЕТОМ нагруднике симулятор обязан ОСТАВИТЬ Снижение урона, а на НЕНАДЕТОМ («Надеть») — заменить!
        DruidProfile cappedDrHero = new DruidProfile();
        cappedDrHero.hasScannedStats = true;
        cappedDrHero.mode = DruidProfile.BuildMode.PUSH;
        cappedDrHero.critChance = 80.0;
        cappedDrHero.critDamage = 400.0;
        cappedDrHero.doubleDamageChance = 50.0;
        cappedDrHero.petDamageBonusPct = 600.0;
        cappedDrHero.dodgeRaw = 95.0;
        cappedDrHero.damageReductionRaw = 95.0;
        cappedDrHero.maxHp = 50000.0;
        cappedDrHero.defense = 20000.0;

        List<IdleArcOcrEngine.OcrLine> equippedChest = Arrays.asList(
                new IdleArcOcrEngine.OcrLine("Нагрудник · А5 +5", 100, 125, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Защита 4000", 135, 160, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("АФФИКСЫ", 180, 205, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Урон питомца +150%", 215, 240, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Шанс двойного урона +38%", 250, 275, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Крит. урон +75%", 285, 310, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Шанс крита +55%", 320, 345, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Снижение урона +14%", 355, 380, 0f, 0f, 0, 220),
                new IdleArcOcrEngine.OcrLine("Снять", 420, 450, 0f, 0f, 0, 220)
        );
        AffixPositionOptimizer.OptimizationReport repEquippedChest =
                AffixPositionOptimizer.analyzeItemAffixes(equippedChest, GearSlot.CHEST, cappedDrHero);
        if (!repEquippedChest.idealByPos.contains(AffixType.DAMAGE_REDUCTION)) {
            throw new AssertionError("Equipped chest MUST keep DAMAGE_REDUCTION because removing it drops DR 95% -> 81%!");
        }

        List<IdleArcOcrEngine.OcrLine> bagChest = new ArrayList<>(equippedChest.subList(0, 8));
        bagChest.add(new IdleArcOcrEngine.OcrLine("Надеть", 420, 450, 0f, 0f, 0, 220));
        AffixPositionOptimizer.OptimizationReport repBagChest =
                AffixPositionOptimizer.analyzeItemAffixes(bagChest, GearSlot.CHEST, cappedDrHero);
        if (repBagChest.idealByPos.contains(AffixType.DAMAGE_REDUCTION)) {
            throw new AssertionError("Bag chest MUST NOT pick DAMAGE_REDUCTION when hero ALREADY has 95% DR without it!");
        }

        // 4. Реальный визуальный OCR-скан скриншотов «Характеристик персонажа» и прогон всех PNG
        IdleArcOcrEngine ocr;
        try (FileInputStream fis = new FileInputStream(glyphsFile)) {
            ocr = IdleArcOcrEngine.loadFromStream(fis);
        }

        DruidProfile scannedFromScreenshots = new DruidProfile();
        String[] statSheets = {"Screenshot_20261110-022137.png", "Screenshot_20261110-022141.png"};
        for (String fn : statSheets) {
            File f = new File(rootDir, fn);
            if (f.exists()) {
                int[] wh = new int[2];
                int[] argb = loadArgb(f, wh);
                ScreenAnalyzer.ScanResult sr = ScreenAnalyzer.analyzeFrame(ocr, argb, wh[0], wh[1], scannedFromScreenshots);
                if (!sr.profileUpdated) {
                    throw new AssertionError("Expected profileUpdated=true on Character Stats screenshot: " + fn);
                }
            }
        }

        File statSheet1 = new File(rootDir, "Screenshot_20261110-022137.png");
        if (statSheet1.exists()) {
            if (!scannedFromScreenshots.hasScannedStats
                    || Math.abs(scannedFromScreenshots.dodgeRaw - 131.7) > 0.2
                    || Math.abs(scannedFromScreenshots.damageReductionRaw - 95.1) > 0.2
                    || scannedFromScreenshots.agility < 1000.0
                    || Math.abs(scannedFromScreenshots.petDamageMultMillions - 83.48) > 1.0) {
                throw new AssertionError("OCR stat parsing mismatch: dodgeRaw=" + scannedFromScreenshots.dodgeRaw
                        + " drRaw=" + scannedFromScreenshots.damageReductionRaw
                        + " agi=" + scannedFromScreenshots.agility
                        + " petMultM=" + scannedFromScreenshots.petDamageMultMillions);
            }
        }

        // Прогон реальных скриншотов карточек предметов с живым отсканированным профилем
        File[] allPngs = rootDir.listFiles((dir, name) -> name.endsWith(".png"));
        int analyzedPngs = 0;
        if (allPngs != null) {
            Arrays.sort(allPngs);
            for (File png : allPngs) {
                int[] wh = new int[2];
                int[] argb = loadArgb(png, wh);
                ScreenAnalyzer.ScanResult sr = ScreenAnalyzer.analyzeFrame(ocr, argb, wh[0], wh[1], scannedFromScreenshots);
                if (sr == null || sr.headline == null || sr.details == null) {
                    throw new AssertionError("Null scan output on " + png.getName());
                }
                analyzedPngs++;
            }
        }
        System.out.println("RELATIONAL_SUITE_OK: analyzedPngs=" + analyzedPngs
                + " A=" + repLowCc.idealByPos
                + " B=" + repHighCdLowDd.idealByPos
                + " C=" + repLowCdHighDd.idealByPos);
    }

    private static int[] loadArgb(File f, int[] wh) throws Exception {
        BufferedImage img = ImageIO.read(f);
        int w = img.getWidth();
        int h = img.getHeight();
        wh[0] = w;
        wh[1] = h;
        return img.getRGB(0, 0, w, h, null, 0, w);
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
                str(SRC_DIR / "LiveBuildSimulator.java"),
                str(SRC_DIR / "DruidKnowledgeBase.java"),
                str(SRC_DIR / "SlotAffixAdvisor.java"),
                str(SRC_DIR / "AffixPositionOptimizer.java"),
                str(SRC_DIR / "ScreenAnalyzer.java"),
                str(tmp / "RelationalSuite.java"),
            ]
            java_bin = str(jdk4py.JAVA)
            comp = subprocess.run(
                [java_bin, "-Dfile.encoding=UTF-8", "-jar", str(ECJ_JAR), "-17", "-encoding", "UTF-8", "-d", str(tmp)]
                + java_files,
                capture_output=True,
                text=True,
            )
            self.assertEqual(comp.returncode, 0, f"Java compile failed:\n{comp.stdout}\n{comp.stderr}")

            env = os.environ.copy()
            env["LC_ALL"] = "C.UTF-8"
            uploads_dir = Path("/tmp/arena-coding-uploads")
            png_dir = uploads_dir if uploads_dir.exists() else ROOT
            run = subprocess.run(
                [
                    java_bin,
                    "-Dfile.encoding=UTF-8",
                    "-cp",
                    str(tmp),
                    "RelationalSuite",
                    str(GLYPHS_BIN),
                    str(png_dir),
                ],
                capture_output=True,
                text=True,
                env=env,
            )
            self.assertEqual(run.returncode, 0, f"RelationalSuite failed:\n{run.stdout}\n{run.stderr}")
            self.assertIn("RELATIONAL_SUITE_OK", run.stdout)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
