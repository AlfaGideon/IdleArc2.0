"""Adversarial offline regression tests; no remote services are contacted."""
import subprocess
import tempfile
import unittest
from pathlib import Path
import jdk4py

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'companion/overlay/app/src/main/java/com/idlearc/companion'
ECJ = Path('/tmp/android-tools/ecj.jar')

class TestSecurityControls(unittest.TestCase):
    @unittest.skipUnless(ECJ.exists() or (jdk4py.JAVA_HOME / 'bin/javac').exists(), 'Java compiler unavailable')
    def test_attack_urls_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            source = temp / 'PolicyTest.java'
            source.write_text('''package com.idlearc.companion;
public class PolicyTest {
  public static void main(String[] args) {
    String[] allowed = {"https://github.com/a/b/releases/download/v1/a.apk",
      "https://api.github.com/repos/a/b/releases/latest", "https://raw.githubusercontent.com/a/b/main/x",
      "https://release-assets.githubusercontent.com/a?signature=foo", "https://objects.githubusercontent.com/a",
      "https://github.com:443/a"};
    String[] denied = {"http://github.com/a", "file:///etc/passwd", "https://127.0.0.1/a",
      "https://[::1]/a", "https://169.254.169.254/latest/meta-data", "https://evil.example/github.com",
      "https://github.com.evil.example/a", "https://raw.githubusercontent.com.evil.example/a",
      "https://github.com@evil.example/a", "https://evil.example@github.com/a",
      "https://github.com:8443/a", "https://github.com./a", "https://github.com\\\\@evil.example/a",
      "https://github.com%2eevil.example/a", "//github.com/a", "not a URL"};
    for (String u : allowed) if (!OtaUrlPolicy.isAllowed(u)) throw new AssertionError("rejected " + u);
    for (String u : denied) if (OtaUrlPolicy.isAllowed(u)) throw new AssertionError("accepted " + u);
  }
}''', encoding='utf-8')
            if ECJ.exists():
                cmd = [str(jdk4py.JAVA), '-jar', str(ECJ), '-17', '-d', str(temp)]
            else:
                cmd = [str(jdk4py.JAVA_HOME / 'bin/javac'), '-d', str(temp)]
            subprocess.run(cmd + [str(SRC / 'OtaUrlPolicy.java'), str(source)], check=True, capture_output=True)
            subprocess.run([str(jdk4py.JAVA), '-cp', str(temp), 'com.idlearc.companion.PolicyTest'], check=True, capture_output=True)

    def test_manifest_components_and_widget_version(self):
        import xml.etree.ElementTree as ET
        main = SRC.parent.parent.parent.parent / 'AndroidManifest.xml'
        manifest = ET.parse(main).getroot()
        ns = '{http://schemas.android.com/apk/res/android}'
        gradle = (ROOT / 'companion/overlay/app/build.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('versionCode = ' + manifest.get(ns + 'versionCode'), gradle)
        self.assertIn('versionName = "' + manifest.get(ns + 'versionName') + '"', gradle)
        app = manifest.find('application')
        self.assertEqual(app.get(ns + 'allowBackup'), 'false')
        self.assertEqual(app.get(ns + 'usesCleartextTraffic'), 'false')
        for tag in ('provider', 'service'):
            for item in app.findall(tag):
                self.assertEqual(item.get(ns + 'exported'), 'false')
        widget = (SRC / 'OverlayService.java').read_text(encoding='utf-8')
        self.assertEqual(widget.count('OtaUpdater.getCurrentVersionName(this)'), 2)
        self.assertNotIn('IdleArcHelper v4.1', widget)

    @unittest.skipUnless(ECJ.exists() or (jdk4py.JAVA_HOME / 'bin/javac').exists(), 'Java compiler unavailable')
    def test_ota_freshness_and_asset_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            source = temp / 'FreshnessTest.java'
            source.write_text('''package com.idlearc.companion;
public class FreshnessTest {
  public static void main(String[] args) {
    if (!OtaFreshness.cannotConfirm(false, 510, 410)) throw new AssertionError("old mirror cannot establish freshness");
    if (!OtaFreshness.cannotConfirm(false, 510, 510)) throw new AssertionError("same mirror cannot establish freshness");
    if (OtaFreshness.cannotConfirm(false, 510, 540)) throw new AssertionError("newer mirror is usable");
    if (OtaFreshness.cannotConfirm(true, 510, 510)) throw new AssertionError("valid release metadata confirms current");
    String digest="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    if (!OtaFreshness.sha256FromAssetDigest("sha256:" + digest).equals(digest)) throw new AssertionError("digest");
    if (!OtaFreshness.sha256FromAssetDigest("SHA256:" + digest.toUpperCase()).equals(digest)) throw new AssertionError("case");
    for(String bad : new String[]{"", "sha1:"+digest, "sha256:1234", "sha256:"+digest+"0", "garbage"})
      if(!OtaFreshness.sha256FromAssetDigest(bad).isEmpty()) throw new AssertionError("bad digest accepted");
  }
}''', encoding='utf-8')
            cmd = ([str(jdk4py.JAVA), '-jar', str(ECJ), '-17', '-d', str(temp)] if ECJ.exists()
                   else [str(jdk4py.JAVA_HOME / 'bin/javac'), '-d', str(temp)])
            subprocess.run(cmd + [str(SRC / 'OtaFreshness.java'), str(source)], check=True, capture_output=True)
            subprocess.run([str(jdk4py.JAVA), '-cp', str(temp), 'com.idlearc.companion.FreshnessTest'], check=True, capture_output=True)
