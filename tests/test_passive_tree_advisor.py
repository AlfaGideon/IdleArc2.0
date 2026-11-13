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
                                          str(source), *(str(SRC / (n + ".java")) for n in ("PassiveTreeAdvisor", "SimpleJson", "RuGameDictionary"))],
                                         capture_output=True, text=True)
            self.assertEqual(compile_run.returncode, 0, compile_run.stderr)
            run = subprocess.run([java, "-cp", str(tmp), "com.idlearc.companion.PassiveHarness"], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
