#!/usr/bin/env python3
"""
Сборщик подписанного Android APK + OTA-манифеста (ota-manifest.json) для IdleArc Druid Helper.
Поддерживает как локальную песочницу (/tmp/android-tools), так и GitHub Actions CI/CD с секретами:
  - ANDROID_KEYSTORE_BASE64
  - ANDROID_KEYSTORE_PASSWORD
  - ANDROID_KEY_ALIAS
  - ANDROID_KEY_PASSWORD
"""
import base64
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path

try:
    import jdk4py
    DEFAULT_JAVA = str(jdk4py.JAVA)
    DEFAULT_JAVAC = str(jdk4py.JAVA_HOME / "bin" / "javac")
    DEFAULT_KEYTOOL = str(jdk4py.JAVA_HOME / "bin" / "keytool")
except ImportError:
    DEFAULT_JAVA = shutil.which("java") or "java"
    DEFAULT_JAVAC = shutil.which("javac") or "javac"
    DEFAULT_KEYTOOL = shutil.which("keytool") or "keytool"


ROOT = Path(__file__).resolve().parent.parent
APP_MAIN = ROOT / "companion" / "overlay" / "app" / "src" / "main"
OUT_DIR = ROOT / "releases"
TOOLS_DIR = Path(os.environ.get("ANDROID_TOOLS_DIR", "/tmp/android-tools"))

DEFAULT_CHANGELOG = (
    "v2.2.1: 1) Исправлено скачивание OTA-обновлений в РФ без VPN (прямая раздача через raw.githubusercontent.com "
    "без редиректа на блокируемый blob.core.windows.net и без рассинхрона SHA-256); "
    "2) Прямая синхронизация с официальным API игры (https://idlearc.com/api/dashboard) в 1 клик («🌐 API»): "
    "все 31 стат, все 10 надетых предметов, все 20 камней, питомцы и талисманы с 0% погрешности OCR."
)


def read_manifest_version() -> tuple[int, str]:
    text = (APP_MAIN / "AndroidManifest.xml").read_text(encoding="utf-8")
    mc = re.search(r'android:versionCode="(\d+)"', text)
    mn = re.search(r'android:versionName="([^"]+)"', text)
    code = int(mc.group(1)) if mc else 210
    name = mn.group(1) if mn else "2.1.0"
    return code, name


def resolve_android_toolchain() -> tuple[str, str, str | None, str, str]:
    if (TOOLS_DIR / "aapt2").exists() and (TOOLS_DIR / "android.jar").exists():
        ecj = str(TOOLS_DIR / "ecj.jar") if (TOOLS_DIR / "ecj.jar").exists() else None
        return (
            str(TOOLS_DIR / "aapt2"),
            str(TOOLS_DIR / "android.jar"),
            ecj,
            str(TOOLS_DIR / "d8.jar"),
            str(TOOLS_DIR / "apksigner.jar"),
        )

    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "/usr/local/lib/android/sdk")
    bt_dirs = sorted((sdk / "build-tools").glob("*"), reverse=True)
    plat_dirs = sorted((sdk / "platforms").glob("android-*"), reverse=True)
    if not bt_dirs or not plat_dirs:
        raise FileNotFoundError(f"Android SDK toolchain not found in {TOOLS_DIR} or {sdk}")
    bt = bt_dirs[0]
    plat = plat_dirs[0]
    return (
        str(bt / "aapt2"),
        str(plat / "android.jar"),
        None,
        str(bt / "lib" / "d8.jar"),
        str(bt / "lib" / "apksigner.jar"),
    )


def zipalign_4(src_apk: Path, dst_apk: Path, extra_dex: Path) -> None:
    """Добавляет classes.dex и выравнивает все STORED-записи (включая resources.arsc) по 4 байта."""
    entries = []
    with zipfile.ZipFile(src_apk, "r") as zin:
        for info in zin.infolist():
            if info.filename == "classes.dex" or info.filename.startswith("META-INF/"):
                continue
            data = zin.read(info.filename)
            compress = zipfile.ZIP_STORED if info.filename in ("resources.arsc", "assets/ocr_glyphs.bin") else info.compress_type
            entries.append((info.filename, data, compress))

    entries.append(("classes.dex", extra_dex.read_bytes(), zipfile.ZIP_DEFLATED))

    with zipfile.ZipFile(dst_apk, "w") as zout:
        for fname, data, compress in entries:
            zi = zipfile.ZipInfo(fname, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = compress
            zi.create_system = 3
            zi.external_attr = 0o644 << 16
            if compress == zipfile.ZIP_STORED:
                offset = zout.fp.tell()
                fname_bytes = fname.encode("utf-8")
                data_start = offset + 30 + len(fname_bytes)
                pad = (4 - (data_start % 4)) % 4
                zi.extra = b"\x00" * pad
            else:
                zi.extra = b""
            zout.writestr(zi, data)

    raw = dst_apk.read_bytes()
    with zipfile.ZipFile(dst_apk, "r") as zcheck:
        for info in zcheck.infolist():
            if info.compress_type == zipfile.ZIP_STORED:
                off = info.header_offset
                fn_len, ex_len = struct.unpack_from("<HH", raw, off + 26)
                data_off = off + 30 + fn_len + ex_len
                assert data_off % 4 == 0, f"Не выровнен {info.filename}: offset={data_off}"


def build_apk() -> Path:
    ver_code, ver_name = read_manifest_version()
    aapt2, android_jar, ecj_jar, d8_jar, apksigner_jar = resolve_android_toolchain()

    work = Path(tempfile.mkdtemp(prefix="druid_apk_build_"))
    try:
        res_zip = work / "compiled_res.zip"
        gen_dir = work / "gen"
        cls_dir = work / "classes"
        dex_dir = work / "dex"
        gen_dir.mkdir()
        cls_dir.mkdir()
        dex_dir.mkdir()

        # 1. aapt2 compile
        subprocess.check_call([
            aapt2, "compile", "--dir", str(APP_MAIN / "res"), "-o", str(res_zip)
        ])

        # 2. aapt2 link
        base_apk = work / "base_unaligned.apk"
        subprocess.check_call([
            aapt2, "link",
            "-I", android_jar,
            "--manifest", str(APP_MAIN / "AndroidManifest.xml"),
            "-A", str(APP_MAIN / "assets"),
            "--java", str(gen_dir),
            "-o", str(base_apk),
            str(res_zip),
        ])

        # 3. Компиляция Java -> .class (с заглушкой LambdaMetafactory для -bootclasspath android.jar,
        #    так как дешугаринг лямбд выполняет D8 на шаге 4)
        java_sources = sorted(str(p) for p in (APP_MAIN / "java").rglob("*.java"))
        java_sources += sorted(str(p) for p in gen_dir.rglob("*.java"))
        lm_dir = gen_dir / "java" / "lang" / "invoke"
        lm_dir.mkdir(parents=True, exist_ok=True)
        (lm_dir / "LambdaMetafactory.java").write_text(
            "package java.lang.invoke;\n"
            "public class LambdaMetafactory {\n"
            "  public static CallSite metafactory(MethodHandles.Lookup l, String n, MethodType t,\n"
            "      MethodType m1, MethodHandle impl, MethodType m2) { return null; }\n"
            "  public static CallSite altMetafactory(MethodHandles.Lookup l, String n, MethodType t,\n"
            "      Object... args) { return null; }\n"
            "}\n",
            encoding="utf-8",
        )
        java_sources_with_lm = java_sources + [str(lm_dir / "LambdaMetafactory.java")]

        if ecj_jar:
            subprocess.check_call([
                DEFAULT_JAVA, "-jar", ecj_jar,
                "-8", "-nowarn", "-encoding", "UTF-8",
                "-bootclasspath", android_jar,
                "-d", str(cls_dir),
                *java_sources_with_lm,
            ])
        elif (TOOLS_DIR / "tools.jar").exists():
            subprocess.check_call([
                DEFAULT_JAVA, "-cp", str(TOOLS_DIR / "tools.jar"),
                "com.sun.tools.javac.Main",
                "-source", "8", "-target", "8",
                "-encoding", "UTF-8",
                "-bootclasspath", android_jar,
                "-d", str(cls_dir),
                *java_sources_with_lm,
            ])
        else:
            subprocess.check_call([
                DEFAULT_JAVAC,
                "-source", "8", "-target", "8",
                "-encoding", "UTF-8",
                "-bootclasspath", android_jar,
                "-d", str(cls_dir),
                *java_sources_with_lm,
            ])
        shutil.rmtree(cls_dir / "java", ignore_errors=True)

        # 4. Сборка classes.dex через D8
        class_files = sorted(str(p) for p in cls_dir.rglob("*.class"))
        subprocess.check_call([
            DEFAULT_JAVA, "-cp", d8_jar, "com.android.tools.r8.D8",
            "--min-api", "24",
            "--lib", android_jar,
            "--output", str(dex_dir),
            *class_files,
        ])

        # 5. Упаковка + 4-байтовый zipalign
        aligned_apk = work / "aligned.apk"
        zipalign_4(base_apk, aligned_apk, dex_dir / "classes.dex")

        # 6. Подпись из GitHub Secrets (ANDROID_KEYSTORE_BASE64) либо локального ключа
        ks_b64 = os.environ.get("ANDROID_KEYSTORE_BASE64", "").strip()
        ks_pass = os.environ.get("ANDROID_KEYSTORE_PASSWORD", "idlearc2026").strip()
        key_alias = os.environ.get("ANDROID_KEY_ALIAS", "druidhelper").strip()
        key_pass = os.environ.get("ANDROID_KEY_PASSWORD", ks_pass).strip()

        if ks_b64:
            keystore = work / "release_signing.jks"
            keystore.write_bytes(base64.b64decode(ks_b64))
        else:
            TOOLS_DIR.mkdir(parents=True, exist_ok=True)
            keystore = TOOLS_DIR / "druid_helper.jks"
            if not keystore.exists():
                subprocess.check_call([
                    DEFAULT_KEYTOOL, "-genkeypair", "-v",
                    "-keystore", str(keystore),
                    "-alias", key_alias,
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "10000",
                    "-storepass", ks_pass,
                    "-keypass", key_pass,
                    "-dname", "CN=DruidHelper, OU=Companion, O=IdleArcHelper, L=SPB, S=SPB, C=RU",
                ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        OUT_DIR.mkdir(parents=True, exist_ok=True)
        apk_filename = f"IdleArc-DruidHelper-v{ver_name}.apk"
        final_apk = OUT_DIR / apk_filename
        subprocess.check_call([
            DEFAULT_JAVA, "-jar", apksigner_jar, "sign",
            "--ks", str(keystore),
            "--ks-key-alias", key_alias,
            "--ks-pass", f"pass:{ks_pass}",
            "--key-pass", f"pass:{key_pass}",
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--v4-signing-enabled", "false",
            "--out", str(final_apk),
            str(aligned_apk),
        ])

        verify_out = subprocess.check_output([
            DEFAULT_JAVA, "-jar", apksigner_jar, "verify", "--verbose", str(final_apk)
        ], stderr=subprocess.STDOUT).decode()
        print(verify_out.strip())

        # 7. Генерация ota-manifest.json для обновления по воздуху (OTA)
        apk_bytes = final_apk.read_bytes()
        sha256_hex = hashlib.sha256(apk_bytes).hexdigest()
        repo_slug = os.environ.get("GITHUB_REPOSITORY", "AlfaGideon/IdleArc2.0")
        ota_apk_url = os.environ.get(
            "OTA_APK_URL",
            f"https://raw.githubusercontent.com/{repo_slug}/v{ver_name}/releases/{apk_filename}",
        )
        changelog = os.environ.get("OTA_CHANGELOG") or DEFAULT_CHANGELOG
        manifest_payload = {
            "versionCode": ver_code,
            "versionName": ver_name,
            "apkUrl": ota_apk_url,
            "sha256": sha256_hex,
            "sizeBytes": len(apk_bytes),
            "changelog": changelog,
        }
        manifest_path = OUT_DIR / "ota-manifest.json"
        manifest_path.write_text(
            json.dumps(manifest_payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

        print(f"APK_READY: {final_apk} ({len(apk_bytes)} bytes, sha256={sha256_hex})")
        print(f"OTA_MANIFEST_READY: {manifest_path}")
        return final_apk
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    build_apk()
