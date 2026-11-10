#!/usr/bin/env python3
"""Produce an offline APK inventory; this is not a decompiler or signature verifier."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

try:
    from androguard.core.apk import APK
    from androguard.core.axml import AXMLPrinter
    from loguru import logger
except ImportError as exc:
    raise SystemExit(
        "Не найдена зависимость для разбора APK. "
        "Установите её: python -m pip install -r requirements.txt"
    ) from exc

# Androguard's debug output can contain parsed input data. Do not print it.
logger.disable("androguard")

ANDROID = "{http://schemas.android.com/apk/res/android}"
MAX_APK_BYTES = 512 * 1024 * 1024
MAX_MANIFEST_BYTES = 8 * 1024 * 1024
MAX_EMBEDDED_APKS = 128
MAX_ZIP_ENTRIES = 250_000
DEX_PATH = re.compile(r"classes(?:[0-9]+)?\.dex\Z")
NATIVE_PATH = re.compile(r"lib/([^/]+)/([^/]+\.so)\Z")


class InspectionError(Exception):
    """A supplied package cannot be inspected safely."""


def android_attr(element, name: str) -> str | None:
    return element.get(ANDROID + name) if element is not None else None


def read_manifest(data: bytes) -> dict:
    """Decode compiled Android XML (or plain XML used by test fixtures)."""
    if len(data) > MAX_MANIFEST_BYTES:
        raise InspectionError("AndroidManifest.xml превышает допустимые 8 МиБ")
    try:
        plain = data.removeprefix(b"\xef\xbb\xbf").lstrip()
        if plain.startswith(b"<"):
            root = ET.fromstring(plain)
        else:
            root = AXMLPrinter(data).get_xml_obj()
    except Exception as exc:
        raise InspectionError("не удалось разобрать AndroidManifest.xml") from exc
    if root is None or root.tag != "manifest":
        raise InspectionError("AndroidManifest.xml не содержит корневой элемент manifest")

    uses_sdk = root.find("uses-sdk")
    application = root.find("application")
    permissions = []
    for tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
        for element in root.findall(tag):
            permissions.append({
                "name": android_attr(element, "name"),
                "declaration": tag,
                "max_sdk_version": android_attr(element, "maxSdkVersion"),
            })

    components = {}
    if application is not None:
        for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
            components[kind] = [
                {
                    "name": android_attr(component, "name"),
                    # This is the raw manifest attribute, not the effective platform default.
                    "exported_attribute": android_attr(component, "exported"),
                    "has_intent_filter": component.find("intent-filter") is not None,
                }
                for component in application.findall(kind)
            ]

    return {
        "package_name": root.get("package"),
        "split_name": root.get("split"),
        "version_code": android_attr(root, "versionCode"),
        "version_code_major": android_attr(root, "versionCodeMajor"),
        "version_name": android_attr(root, "versionName"),
        "min_sdk_version": android_attr(uses_sdk, "minSdkVersion"),
        "target_sdk_version": android_attr(uses_sdk, "targetSdkVersion"),
        "permissions": permissions,
        "application_attributes": {
            "debuggable": android_attr(application, "debuggable"),
            "allow_backup": android_attr(application, "allowBackup"),
            "uses_cleartext_traffic": android_attr(application, "usesCleartextTraffic"),
            "network_security_config": android_attr(application, "networkSecurityConfig"),
        },
        "components": components,
    }


def signing_metadata(raw: bytes) -> dict:
    """Read signing metadata only. Neither integrity nor trust is verified."""
    result = {"verified": False, "schemes": {}}
    try:
        apk = APK(raw, raw=True, skip_analysis=True)
    except Exception as exc:
        result["error"] = f"Не удалось разобрать блоки подписи ({type(exc).__name__})"
        return result

    for version in (1, 2, 3):
        name = f"v{version}"
        scheme = {"present": None, "certificate_sha256": []}
        try:
            scheme["present"] = bool(getattr(apk, f"is_signed_{name}")())
            if scheme["present"]:
                certificates = getattr(apk, f"get_certificates_{name}")()
                scheme["certificate_sha256"] = sorted({
                    hashlib.sha256(certificate.dump()).hexdigest()
                    for certificate in certificates if certificate is not None
                })
        except Exception as exc:
            # Keep an unknown result distinct from a confirmed absent signature.
            scheme["error"] = f"Не удалось прочитать данные подписи ({type(exc).__name__})"
        result["schemes"][name] = scheme
    return result


def engine_indicators(paths: list[str]) -> list[dict]:
    names = set(paths)
    markers = {
        "Flutter": lambda path: path.endswith("/libflutter.so")
        or path.startswith("assets/flutter_assets/"),
        "Unity": lambda path: path.endswith("/libunity.so")
        or path.startswith("assets/bin/Data/"),
        "Unreal Engine": lambda path: path.endswith(("/libUE4.so", "/libUnreal.so")),
        "Godot": lambda path: path.endswith("/libgodot_android.so"),
        "React Native": lambda path: path.endswith(("/libreactnativejni.so", "/index.android.bundle")),
        "Cordova": lambda path: path == "assets/www/cordova.js",
    }
    return [
        {"name": name, "evidence": sorted(path for path in names if matches(path))[:20]}
        for name, matches in markers.items()
        if any(matches(path) for path in names)
    ]


def inspect_apk(raw: bytes, label: str) -> dict:
    if len(raw) > MAX_APK_BYTES:
        raise InspectionError(f"{label}: APK превышает допустимые 512 МиБ")
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_ZIP_ENTRIES:
                raise InspectionError(f"{label}: слишком много записей в APK")
            files = [entry for entry in entries if not entry.is_dir()]
            manifests = [entry for entry in files if entry.filename == "AndroidManifest.xml"]
            if len(manifests) != 1:
                raise InspectionError(f"{label}: нужен ровно один AndroidManifest.xml в корне APK")
            if manifests[0].file_size > MAX_MANIFEST_BYTES:
                raise InspectionError(f"{label}: AndroidManifest.xml превышает допустимые 8 МиБ")
            manifest = read_manifest(archive.read(manifests[0]))

            paths = [entry.filename for entry in files]
            counts = Counter(paths)
            duplicate_paths = sorted(path for path, count in counts.items() if count > 1)
            top_level = Counter(path.split("/", 1)[0] for path in paths)
            dex_files = [
                {"path": entry.filename, "size_bytes": entry.file_size}
                for entry in files if DEX_PATH.fullmatch(entry.filename)
            ]
            native_libraries = []
            for entry in files:
                match = NATIVE_PATH.fullmatch(entry.filename)
                if match:
                    native_libraries.append({
                        "path": entry.filename,
                        "abi": match.group(1),
                        "name": match.group(2),
                        "size_bytes": entry.file_size,
                    })
            return {
                "name": label,
                "sha256": hashlib.sha256(raw).hexdigest(),
                "size_bytes": len(raw),
                "manifest": manifest,
                "contents": {
                    "file_count": len(files),
                    "uncompressed_size_bytes": sum(entry.file_size for entry in files),
                    "top_level_file_counts": dict(sorted(top_level.items())),
                    "duplicate_paths": duplicate_paths,
                    "dex_files": sorted(dex_files, key=lambda item: item["path"]),
                    "native_libraries": sorted(native_libraries, key=lambda item: item["path"]),
                    "contains_resources_arsc": "resources.arsc" in counts,
                    "asset_file_count": sum(path.startswith("assets/") for path in paths),
                },
                "engine_indicators": engine_indicators(paths),
                "signing": signing_metadata(raw),
            }
    except (zipfile.BadZipFile, zipfile.LargeZipFile, OSError, EOFError, RuntimeError) as exc:
        raise InspectionError(f"{label}: повреждённый или неподдерживаемый APK ({type(exc).__name__})") from exc


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_source(path: Path) -> dict:
    """Accept one APK or a ZIP containing APK files without extracting paths."""
    try:
        size = path.stat().st_size
        digest = sha256_file(path)
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_ZIP_ENTRIES:
                raise InspectionError(f"{path.name}: слишком много записей в архиве")
            files = [entry for entry in entries if not entry.is_dir()]
            is_apk = any(entry.filename == "AndroidManifest.xml" for entry in files)
            if is_apk:
                if size > MAX_APK_BYTES:
                    raise InspectionError(f"{path.name}: APK превышает допустимые 512 МиБ")
                # Close the outer ZIP before processing the same bytes as an APK.
                package_data = path.read_bytes()
                return {
                    "name": path.name,
                    "size_bytes": size,
                    "sha256": digest,
                    "type": "apk",
                    "apks": [inspect_apk(package_data, path.name)],
                }

            apk_entries = [entry for entry in files if entry.filename.lower().endswith(".apk")]
            if not apk_entries:
                raise InspectionError(
                    f"{path.name}: не найден APK или архив с APK; AAB не поддерживается"
                )
            if len(apk_entries) > MAX_EMBEDDED_APKS:
                raise InspectionError(f"{path.name}: в архиве больше {MAX_EMBEDDED_APKS} APK")
            if len({entry.filename for entry in apk_entries}) != len(apk_entries):
                raise InspectionError(f"{path.name}: в архиве повторяются имена APK")
            apks = []
            for entry in apk_entries:
                if entry.file_size > MAX_APK_BYTES:
                    raise InspectionError(f"{path.name}!/{entry.filename}: APK превышает 512 МиБ")
                # Reading a ZIP member into memory never writes its potentially unsafe path.
                raw = archive.read(entry)
                apks.append(inspect_apk(raw, f"{path.name}!/{entry.filename}"))
            return {
                "name": path.name,
                "size_bytes": size,
                "sha256": digest,
                "type": "apk_archive",
                "other_file_count": len(files) - len(apk_entries),
                "apks": apks,
            }
    except InspectionError:
        raise
    except (OSError, zipfile.BadZipFile, zipfile.LargeZipFile, EOFError, RuntimeError) as exc:
        raise InspectionError(f"{path.name}: невозможно прочитать файл ({type(exc).__name__})") from exc


def summarize(sources: list[dict]) -> dict:
    apks = [apk for source in sources for apk in source["apks"]]
    manifests = [apk["manifest"] for apk in apks]
    packages = sorted({item["package_name"] for item in manifests if item["package_name"]})
    version_codes = sorted({item["version_code"] for item in manifests if item["version_code"]})
    base_count = sum(item["split_name"] is None for item in manifests)
    certificate_sets = {
        frozenset(
            fingerprint
            for scheme in apk["signing"].get("schemes", {}).values()
            for fingerprint in scheme["certificate_sha256"]
        )
        for apk in apks
    }
    certificate_sets.discard(frozenset())
    warnings = []
    if not base_count:
        warnings.append("Не найден манифест без split: возможно, отсутствует base APK.")
    if len(packages) > 1:
        warnings.append("Пакеты имеют разные имена; это может быть набор разных приложений.")
    if len(version_codes) > 1:
        warnings.append("Коды версий различаются; split APK обычно должны соответствовать одной сборке.")
    if len(certificate_sets) > 1:
        warnings.append("Наборы сертификатов различаются; проверьте происхождение и ротацию ключей.")
    if any(not item["package_name"] for item in manifests):
        warnings.append("В одном из манифестов не удалось определить имя пакета.")
    return {
        "apk_count": len(apks),
        "package_names": packages,
        "version_codes": version_codes,
        "base_candidates": base_count,
        "warnings": warnings,
        "note": "Наличие base APK не доказывает полноту split-набора или пригодность к установке.",
    }


def build_report(paths: list[Path]) -> dict:
    sources = [inspect_source(path) for path in paths]
    return {
        "format_version": 1,
        "scope": "Офлайн-инвентаризация; не проверяет подписи, серверный протокол или работоспособность APK.",
        "sources": sources,
        "summary": summarize(sources),
    }


def write_report(path: Path, text: str, inputs: list[Path]) -> None:
    target = path.resolve()
    for input_path in inputs:
        if target == input_path.resolve() or (
            path.exists() and input_path.exists() and os.path.samefile(path, input_path)
        ):
            raise InspectionError("путь отчёта совпадает с входным файлом")
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_name = None
    try:
        # Atomic replacement prevents a partially written report. mkstemp uses mode 0600.
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent, prefix=".apk-inventory-", delete=False
        ) as stream:
            temp_name = stream.name
            stream.write(text)
        os.replace(temp_name, path)
    finally:
        if temp_name is not None and os.path.exists(temp_name):
            os.unlink(temp_name)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Офлайн-инвентаризация APK и контейнеров с APK")
    parser.add_argument("inputs", type=Path, nargs="+", help="APK-файлы или архивы, содержащие APK")
    parser.add_argument("--output", type=Path, help="путь к JSON-отчёту (по умолчанию stdout)")
    args = parser.parse_args(argv)
    try:
        report = build_report(args.inputs)
        text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            write_report(args.output, text, args.inputs)
            print(f"Отчёт сохранён: {args.output}")
        else:
            sys.stdout.write(text)
    except (InspectionError, OSError, ValueError) as exc:
        print(f"Ошибка: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
