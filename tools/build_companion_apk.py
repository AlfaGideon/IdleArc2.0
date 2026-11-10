#!/usr/bin/env python3
"""
Сборщик подписанного Android APK для IdleArc Druid Helper (без Gradle-демона).
Шаги:
  1. aapt2 compile + link (ресурсы, AndroidManifest.xml, assets/ocr_glyphs.bin, R.java)
  2. Eclipse Compiler for Java (ecj) -> .class
  3. Google D8 -> classes.dex (minSdk 24)
  4. 4-байтовый zipalign (resources.arsc и бинарные ассеты ZIP_STORED + выравнивание под Android 11..15+)
  5. keytool + apksigner (схемы подписи v1 + v2 + v3) + apksigner verify
"""
import os
import shutil
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path

import jdk4py


ROOT = Path(__file__).resolve().parent.parent
APP_MAIN = ROOT / "companion" / "overlay" / "app" / "src" / "main"
OUT_DIR = ROOT / "releases"
TOOLS_DIR = Path("/tmp/android-tools")


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

    # Проверяем выравнивание resources.arsc по смещению данных в файле
    raw = dst_apk.read_bytes()
    with zipfile.ZipFile(dst_apk, "r") as zcheck:
        for info in zcheck.infolist():
            if info.compress_type == zipfile.ZIP_STORED:
                off = info.header_offset
                fn_len, ex_len = struct.unpack_from("<HH", raw, off + 26)
                data_off = off + 30 + fn_len + ex_len
                assert data_off % 4 == 0, f"Не выровнен {info.filename}: offset={data_off}"


def build_apk() -> Path:
    java = str(jdk4py.JAVA)
    keytool = str(jdk4py.JAVA_HOME / "bin" / "keytool")
    aapt2 = str(TOOLS_DIR / "aapt2")
    android_jar = str(TOOLS_DIR / "android.jar")
    ecj_jar = str(TOOLS_DIR / "ecj.jar")
    d8_jar = str(TOOLS_DIR / "d8.jar")
    apksigner_jar = str(TOOLS_DIR / "apksigner.jar")

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

        # 3. Компиляция Java через ECJ (-8 -bootclasspath android.jar + compile-time LambdaMetafactory stub)
        lm_dir = gen_dir / "java" / "lang" / "invoke"
        lm_dir.mkdir(parents=True, exist_ok=True)
        (lm_dir / "LambdaMetafactory.java").write_text(
            "package java.lang.invoke;\n"
            "public class LambdaMetafactory {\n"
            "  public static CallSite metafactory(MethodHandles.Lookup l, String n, MethodType t,\n"
            "      MethodType m1, MethodHandle impl, MethodType m2) { return null; }\n"
            "}\n",
            encoding="utf-8",
        )
        java_sources = sorted(str(p) for p in (APP_MAIN / "java").rglob("*.java"))
        java_sources += sorted(str(p) for p in gen_dir.rglob("*.java"))
        subprocess.check_call([
            java, "-jar", ecj_jar,
            "-8",
            "-nowarn",
            "-encoding", "UTF-8",
            "-bootclasspath", android_jar,
            "-d", str(cls_dir),
            *java_sources,
        ])

        # 4. Сборка classes.dex через D8 (без compile-only стаба java.lang.invoke)
        shutil.rmtree(cls_dir / "java", ignore_errors=True)
        class_files = sorted(str(p) for p in cls_dir.rglob("*.class"))
        subprocess.check_call([
            java, "-cp", d8_jar, "com.android.tools.r8.D8",
            "--min-api", "24",
            "--lib", android_jar,
            "--output", str(dex_dir),
            *class_files,
        ])

        # 5. Упаковка + 4-байтовый zipalign
        aligned_apk = work / "aligned.apk"
        zipalign_4(base_apk, aligned_apk, dex_dir / "classes.dex")

        # 6. Генерация ключа релиза и подпись v1 + v2 + v3
        keystore = TOOLS_DIR / "druid_helper.jks"
        if not keystore.exists():
            subprocess.check_call([
                keytool, "-genkeypair", "-v",
                "-keystore", str(keystore),
                "-alias", "druidhelper",
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10000",
                "-storepass", "idlearc2026",
                "-keypass", "idlearc2026",
                "-dname", "CN=DruidHelper, OU=Companion, O=IdleArcHelper, L=SPB, S=SPB, C=RU",
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        OUT_DIR.mkdir(parents=True, exist_ok=True)
        final_apk = OUT_DIR / "IdleArc-DruidHelper-v1.1.0.apk"
        subprocess.check_call([
            java, "-jar", apksigner_jar, "sign",
            "--ks", str(keystore),
            "--ks-key-alias", "druidhelper",
            "--ks-pass", "pass:idlearc2026",
            "--key-pass", "pass:idlearc2026",
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--v4-signing-enabled", "false",
            "--out", str(final_apk),
            str(aligned_apk),
        ])

        verify_out = subprocess.check_output([
            java, "-jar", apksigner_jar, "verify", "--verbose", str(final_apk)
        ], stderr=subprocess.STDOUT).decode()
        print(verify_out.strip())
        print(f"APK_READY: {final_apk} ({final_apk.stat().st_size} bytes)")
        return final_apk
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    build_apk()
