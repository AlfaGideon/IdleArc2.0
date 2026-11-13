import subprocess
import tempfile
import unittest
from pathlib import Path

import jdk4py

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "companion/overlay/app/src/main/java/com/idlearc/companion"


class PassiveTreeAdvisorTest(unittest.TestCase):
    def test_all_classes_modes_tiers_and_missing_data(self):
        harness = r'''
package com.idlearc.companion;
import java.util.*;
public class PassiveHarness {
  static void check(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
  static Map<String,Object> map(String json) { return SimpleJson.parseObject(json); }
  static String lines(Map<String,Object> cfg, int tier, int cls, String mode) {
    return String.join("\n", PassiveTreeAdvisor.recommendations(cfg,tier,cls,mode));
  }
  public static void main(String[] args) {
    check(PassiveTreeAdvisor.detectedClass(null,0)==0,"unknown must not be druid");
    check(PassiveTreeAdvisor.detectedClass(map("{\"characterClassId\":3}"),2)==2,"override");
    check(PassiveTreeAdvisor.detectedClass(map("{\"characterClassName\":\"Rogue\"}"),0)==4,"name");
    check(PassiveTreeAdvisor.detectedClass(map("{\"characterClassName\":\"future\"}"),0)==0,"new class unknown");
    check(lines(null,0,1,"PUSH").contains("недоступны"),"missing config");
    check(lines(null,0,0,"PUSH").contains("Выбери класс"),"unknown class");
    StringBuilder s = new StringBuilder("{\"skillTree\":{\"tiers\":[");
    for(int i=0;i<20;i++) { if(i>0)s.append(','); s.append("{\"start_row\":"+(i*2)+",\"end_row\":"+(i*2+1)+"}"); }
    s.append("],\"rows\":[");
    for(int i=0;i<20;i++) {
      if(i>0)s.append(',');
      s.append("{\"nodes\":[{\"stat\":\"attack_damage_flat\",\"per_point\":4,\"max_points\":3},{\"stat\":\"pet_damage_flat\",\"per_point\":3,\"max_points\":2}]},");
      s.append("{\"milestone\":true,\"nodes\":[{\"stat\":\"double_hit_chance\",\"per_point\":3.8,\"max_points\":1},{\"stat\":\"exp_gain\",\"per_point\":13.6,\"max_points\":1}]}");
    }
    Map<String,Object> cfg=map(s.append("]}}").toString());
    check(PassiveTreeAdvisor.tierCount(cfg)==20,"20 tiers");
    for(int cls=1;cls<=5;cls++) for(String mode:new String[]{"PUSH","BOSS","FARM","LEVELING"}) for(int tier=0;tier<20;tier++) {
      String out=lines(cfg,tier,cls,mode);
      check(out.contains("Строка "+(tier*2+1)),"row");
      check(out.contains("веха"),"milestone");
      check(out.contains("максимум узла"),"max");
    }
    String unknown = String.join("\n", PassiveTreeAdvisor.recommendations(cfg,0,5,"PUSH","harmony",StanceAdvisor.UNKNOWN));
    check(unknown.contains("attack_damage_flat") && unknown.contains("pet_damage_flat"),"harmony unknown shows both options");
    String heroHeavy = String.join("\n", PassiveTreeAdvisor.recommendations(cfg,0,1,"PUSH","harmony",StanceAdvisor.ATTACK_DOMINANT));
    check(heroHeavy.contains("pet_damage_flat"),"scenario favours pet comparison");
    String petHeavy = String.join("\n", PassiveTreeAdvisor.recommendations(cfg,0,5,"PUSH","harmony",StanceAdvisor.PET_DOMINANT));
    check(petHeavy.contains("attack_damage_flat"),"scenario favours attack comparison");
    check(String.join("\n", PassiveTreeAdvisor.recommendations(cfg,0,5,"PUSH","aggressive",0)).contains("attack_damage_flat"),"aggressive stance favours attack");
    check(String.join("\n", PassiveTreeAdvisor.recommendations(cfg,0,1,"PUSH","beastmaster",0)).contains("pet_damage_flat"),"beastmaster stance favours pet");
    Map<String,Object> h = map("{\"activeCombatStance\":\"harmony\",\"activeCombatStanceHarmonyMax\":8000,\"attackDamageBreakdown\":{\"multiplicative\":[{\"key\":\"stance\",\"pct\":6400}]},\"bonusStats\":{\"petDamageBreakdown\":[{\"key\":\"stance\",\"pct\":6400}],\"runeAttackDamagePercent\":15,\"runePetDamagePercent\":20}}");
    String advice = String.join("\n", StanceAdvisor.advice(h, StanceAdvisor.ATTACK_DOMINANT));
    check(advice.contains("×81.00") && advice.contains("80.0% от потолка") && advice.contains("не соотношение урона"),"bonus ratio is not damage split");
    check(advice.contains("Бонусы рун") && advice.contains("предположение"),"source bonus and user scenario");
    check(String.join("\n",StanceAdvisor.advice(map("{}"),0)).contains("неизвестна"),"unknown stance");
    check(!String.join("\n",StanceAdvisor.advice(map("{\"activeCombatStance\":\"harmony\",\"activeCombatStanceHarmonyMax\":0}"),0)).contains("от потолка"),"zero cap has no ratio");
    check(!String.join("\n",StanceAdvisor.advice(map("{\"activeCombatStance\":\"beastmaster\",\"activeCombatStanceHarmonyMax\":8000}"),0)).contains("×81.00"),"no harmony cap outside harmony");
    Map<String,Object> defenseCfg = map("{\"skillTree\":{\"tiers\":[{\"start_row\":0,\"end_row\":0}],\"rows\":[{\"nodes\":[{\"stat\":\"dodge_chance\",\"per_point\":1,\"max_points\":1},{\"stat\":\"defense\",\"per_point\":10,\"max_points\":1}]}]}}");
    check(String.join("\n",PassiveTreeAdvisor.recommendations(defenseCfg,0,1,"PUSH","bulwark",0)).contains("defense"),"bulwark avoids dodge");

    check(String.join("\n",StanceAdvisor.advice(map("{\"activeCombatStance\":\"bulwark\"}"),0)).contains("отключает уклонение"),"bulwark advice");
    Map<String,Object> measured = map("{\"activeCombatStance\":\"harmony\",\"attackDamage\":{\"minStr\":\"300\",\"maxStr\":\"500\"},\"bonusStats\":{\"petDamageFinalAvg\":100,\"petDamageFlatBreakdown\":[{\"key\":\"conversion\",\"amountStr\":\"75\"}]}}");
    HarmonyBalance compare = HarmonyBalance.fromDashboard(measured);
    check(compare != null && compare.attackShare.intValue()==80 && compare.petShare.intValue()==20,"sheet average share");
    check(compare.estimatedScenario()==StanceAdvisor.ATTACK_DOMINANT,"automatic weaker-side hypothesis");
    check(HarmonyBalance.conversionAmount(measured).intValue()==75 && compare.petAverage.intValue()==100,"do not apply conversion twice");
    check(StanceAdvisor.advice(measured,compare.estimatedScenario()).toString().contains("не DPS"),"not presenting averages as game balance");
    check(HarmonyBalance.fromDashboard(map("{\"activeCombatStance\":\"harmony\",\"attackDamage\":{\"min\":2,\"max\":1},\"bonusStats\":{\"petDamageFinalAvg\":4}}"))==null,"invalid range");
    check(HarmonyBalance.fromDashboard(map("{\"activeCombatStance\":\"beastmaster\",\"attackDamage\":{\"min\":1,\"max\":2},\"bonusStats\":{\"petDamageFinalAvg\":4}}"))==null,"no proxy outside harmony");
    Map<String,Object> large = map("{\"activeCombatStance\":\"harmony\",\"attackDamage\":{\"minStr\":\"100000000000000000000000000000000\",\"maxStr\":\"100000000000000000000000000000000\"},\"bonusStats\":{\"petDamageFinalAvgStr\":\"100000000000000000000000000000000\"}}");
    check(HarmonyBalance.fromDashboard(large).attackShare.intValue()==50,"large exact numbers");
    check(lines(cfg,0,5,"PUSH").contains("pet_damage_flat"),"druid pet");
    check(lines(cfg,0,1,"PUSH").contains("attack_damage_flat"),"warrior attack");
    check(lines(cfg,0,1,"LEVELING").contains("exp_gain"),"leveling milestone");
    check(lines(cfg,0,1,"PUSH").contains("double_hit_chance"),"push milestone");
    check(lines(cfg,19,2,"PUSH").contains("Строка 40"),"last tier");
    check(lines(cfg,20,1,"PUSH").contains("недоступны"),"invalid tier");
    check(lines(map("{\"skillTree\":{\"tiers\":[{\"start_row\":0,\"end_row\":2}],\"rows\":[]}}"),0,1,"PUSH").contains("неполная"),"incomplete tier");
    check(RuGameDictionary.statName("max_hp_percent").equals("Макс. здоровье"),"hp translation");
  }
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            source = tmp / "PassiveHarness.java"
            source.write_text(harness, encoding="utf8")
            java = str(jdk4py.JAVA_HOME / "bin/java")
            ecj = Path("/tmp/android-tools/ecj.jar")
            javac = jdk4py.JAVA_HOME / "bin/javac"
            if ecj.exists():
                compiler = [java, "-jar", str(ecj), "-17"]
            elif javac.exists():
                compiler = [str(javac)]
            else:
                self.skipTest("Java compiler unavailable")
            compile_run = subprocess.run(compiler + ["-encoding", "UTF-8", "-d", str(tmp),
                                          str(source), *(str(SRC / (n + ".java")) for n in ("PassiveTreeAdvisor", "StanceAdvisor", "HarmonyBalance", "HarmonyInfo", "SimpleJson", "RuGameDictionary"))],
                                         capture_output=True, text=True)
            self.assertEqual(compile_run.returncode, 0, compile_run.stderr)
            run = subprocess.run([java, "-cp", str(tmp), "com.idlearc.companion.PassiveHarness"], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
