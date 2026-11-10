import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools import inspect_apk  # noqa: E402

SCRIPT = ROOT / "tools" / "inspect_apk.py"
NO_INDEX = 0xFFFFFFFF
ANDROID_URI = "http://schemas.android.com/apk/res/android"


def binary_manifest():
    """Generate a small compiled Android XML document, not a real app fixture."""
    strings = [
        "manifest", "package", "org.example.binary", "android", ANDROID_URI,
        "versionCode", "versionName", "1.2", "uses-sdk", "minSdkVersion",
        "targetSdkVersion", "uses-permission", "name", "android.permission.INTERNET",
        "application", "activity", "org.example.MainActivity", "exported",
    ]
    indexes = {value: index for index, value in enumerate(strings)}
    data = b""
    offsets = []
    for value in strings:
        encoded = value.encode("utf-8")
        offsets.append(len(data))
        data += bytes([len(value), len(encoded)]) + encoded + b"\0"
    data += b"\0" * (-len(data) % 4)
    strings_start = 28 + len(offsets) * 4
    pool = struct.pack(
        "<HHIIIIII", 1, 28, strings_start + len(data), len(strings), 0, 0x100,
        strings_start, 0,
    ) + struct.pack("<" + "I" * len(offsets), *offsets) + data

    def namespace(start):
        return struct.pack(
            "<HHIIIII", 0x100 if start else 0x101, 16, 24, 1, NO_INDEX,
            indexes["android"], indexes[ANDROID_URI],
        )

    def start_element(name, attributes=()):
        encoded_attrs = b""
        for key, value, android, value_type in attributes:
            ns = indexes[ANDROID_URI] if android else NO_INDEX
            if value_type == "string":
                raw_value = indexes[value]
                typed_value, typed_data = 3, indexes[value]
            elif value_type == "int":
                raw_value, typed_value, typed_data = NO_INDEX, 0x10, value
            elif value_type == "bool":
                raw_value, typed_value, typed_data = NO_INDEX, 0x12, int(value)
            else:
                raise ValueError(value_type)
            encoded_attrs += struct.pack(
                "<IIIHBBI", ns, indexes[key], raw_value, 8, 0, typed_value, typed_data,
            )
        return struct.pack(
            "<HHIIIIIHHHHHH", 0x102, 16, 36 + len(encoded_attrs), 1, NO_INDEX,
            NO_INDEX, indexes[name], 20, 20, len(attributes), 0, 0, 0,
        ) + encoded_attrs

    def end_element(name):
        return struct.pack("<HHIIIII", 0x103, 16, 24, 1, NO_INDEX, NO_INDEX, indexes[name])

    body = b"".join([
        pool, namespace(True),
        start_element("manifest", [
            ("package", "org.example.binary", False, "string"),
            ("versionCode", 7, True, "int"),
            ("versionName", "1.2", True, "string"),
        ]),
        start_element("uses-sdk", [
            ("minSdkVersion", 21, True, "int"),
            ("targetSdkVersion", 35, True, "int"),
        ]), end_element("uses-sdk"),
        start_element("uses-permission", [
            ("name", "android.permission.INTERNET", True, "string"),
        ]), end_element("uses-permission"),
        start_element("application"),
        start_element("activity", [
            ("name", "org.example.MainActivity", True, "string"),
            ("exported", True, True, "bool"),
        ]), end_element("activity"),
        end_element("application"), end_element("manifest"), namespace(False),
    ])
    return struct.pack("<HHI", 3, 8, len(body) + 8) + body


def plain_manifest(package="org.example.game", split=None, version="4"):
    split_attribute = f' split="{split}"' if split is not None else ""
    return f'''<?xml version="1.0" encoding="utf-8"?>
    <manifest xmlns:android="{ANDROID_URI}" package="{package}"{split_attribute}
        android:versionCode="{version}" android:versionName="1.0">
      <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="35" />
      <uses-permission android:name="android.permission.INTERNET" />
      <application android:debuggable="false" android:usesCleartextTraffic="false">
        <activity android:name=".MainActivity" android:exported="true">
          <intent-filter><action android:name="android.intent.action.MAIN" /></intent-filter>
        </activity>
        <service android:name=".Worker" />
      </application>
    </manifest>'''.encode()


def apk_bytes(manifest=None, extra_files=None):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("AndroidManifest.xml", manifest if manifest is not None else plain_manifest())
        for name, content in (extra_files or {}).items():
            archive.writestr(name, content)
    return buffer.getvalue()


class InventoryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.directory = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def write_apk(self, name="base.apk", **kwargs):
        path = self.directory / name
        path.write_bytes(apk_bytes(**kwargs))
        return path

    def run_cli(self, *args):
        return subprocess.run(
            [sys.executable, str(SCRIPT), *(str(arg) for arg in args)],
            cwd=self.directory, text=True, capture_output=True, check=False,
        )

    def test_binary_manifest_with_typed_android_values(self):
        decoded = inspect_apk.read_manifest(binary_manifest())
        self.assertEqual(decoded["package_name"], "org.example.binary")
        self.assertEqual(decoded["version_code"], "7")
        self.assertEqual(decoded["version_name"], "1.2")
        self.assertEqual(decoded["min_sdk_version"], "21")
        self.assertEqual(decoded["target_sdk_version"], "35")
        self.assertEqual(decoded["permissions"][0]["name"], "android.permission.INTERNET")
        activity = decoded["components"]["activity"][0]
        self.assertEqual(activity["name"], "org.example.MainActivity")
        self.assertEqual(activity["exported_attribute"], "true")

    def test_apk_inventory_and_no_asset_contents_or_network(self):
        secret = b"https://example.invalid/api?token=DO_NOT_INCLUDE_SECRET"
        path = self.write_apk(extra_files={
            "classes.dex": b"dex file",
            "classes2.dex": b"second dex file",
            "lib/arm64-v8a/libflutter.so": b"library",
            "assets/flutter_assets/AssetManifest.bin": b"asset index",
            "assets/private.txt": secret,
            "resources.arsc": b"resources",
        })
        with mock.patch("socket.socket", side_effect=AssertionError("Network is forbidden")):
            report = inspect_apk.build_report([path])
        source = report["sources"][0]
        apk = source["apks"][0]
        self.assertEqual(source["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertEqual(apk["sha256"], source["sha256"])
        self.assertEqual(report["summary"]["base_candidates"], 1)
        self.assertEqual(apk["manifest"]["package_name"], "org.example.game")
        self.assertEqual(apk["manifest"]["application_attributes"]["uses_cleartext_traffic"], "false")
        self.assertTrue(apk["manifest"]["components"]["activity"][0]["has_intent_filter"])
        self.assertIsNone(apk["manifest"]["components"]["service"][0]["exported_attribute"])
        self.assertEqual(len(apk["contents"]["dex_files"]), 2)
        self.assertEqual(apk["contents"]["native_libraries"][0]["abi"], "arm64-v8a")
        self.assertTrue(apk["contents"]["contains_resources_arsc"])
        self.assertEqual(apk["engine_indicators"][0]["name"], "Flutter")
        self.assertFalse(apk["signing"]["verified"])
        self.assertFalse(apk["signing"]["schemes"]["v1"]["present"])
        self.assertNotIn(secret.decode(), json.dumps(report))
        self.assertNotIn("DO_NOT_INCLUDE_SECRET", json.dumps(report))

    def test_multiple_splits_and_missing_base_warning(self):
        base = self.write_apk()
        split = self.write_apk("split.apk", manifest=plain_manifest(split="config.arm64_v8a"))
        report = inspect_apk.build_report([base, split])
        self.assertEqual(report["summary"]["apk_count"], 2)
        self.assertEqual(report["summary"]["package_names"], ["org.example.game"])
        self.assertEqual(report["summary"]["base_candidates"], 1)
        self.assertEqual(report["summary"]["warnings"], [])
        missing = inspect_apk.build_report([split])
        self.assertIn("base APK", missing["summary"]["warnings"][0])

    def test_mismatched_package_and_version_are_reported(self):
        base = self.write_apk()
        split = self.write_apk(
            "split.apk", manifest=plain_manifest(package="org.example.other", split="config.en", version="5")
        )
        warnings_list = inspect_apk.build_report([base, split])["summary"]["warnings"]
        self.assertTrue(any("разные имена" in warning for warning in warnings_list))
        self.assertTrue(any("Коды версий" in warning for warning in warnings_list))

    def test_apks_archive_read_without_extracting_paths(self):
        bundle = self.directory / "bundle.apks"
        base = apk_bytes()
        split = apk_bytes(manifest=plain_manifest(split="config.en"))
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr("splits/base-master.apk", base)
            archive.writestr("../../not-extracted.apk", split)
            archive.writestr("toc.pb", b"bundle metadata")
        result = self.run_cli(bundle)
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        source = report["sources"][0]
        self.assertEqual(source["type"], "apk_archive")
        self.assertEqual(source["other_file_count"], 1)
        self.assertEqual(report["summary"]["apk_count"], 2)
        self.assertEqual(source["apks"][0]["sha256"], hashlib.sha256(base).hexdigest())
        self.assertFalse((self.directory.parent.parent / "not-extracted.apk").exists())

    def test_invalid_and_unsupported_input_do_not_write_report(self):
        bad_zip = self.directory / "broken.apk"
        bad_zip.write_bytes(b"not a zip")
        destination = self.directory / "report.json"
        result = self.run_cli(bad_zip, "--output", destination)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Ошибка", result.stderr)
        self.assertFalse(destination.exists())

        bundle = self.directory / "bundle.aab"
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr("base/manifest/AndroidManifest.xml", b"not an APK")
        result = self.run_cli(bundle)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("AAB не поддерживается", result.stderr)

    def test_duplicate_manifest_and_corrupt_manifest_rejected(self):
        path = self.directory / "duplicate.apk"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("AndroidManifest.xml", plain_manifest())
                archive.writestr("AndroidManifest.xml", plain_manifest())
        result = self.run_cli(path)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ровно один AndroidManifest.xml", result.stderr)

        corrupted = self.write_apk("corrupt.apk", manifest=b"not valid android xml")
        self.assertNotEqual(self.run_cli(corrupted).returncode, 0)

    def test_duplicate_apk_names_in_bundle_rejected(self):
        bundle = self.directory / "duplicates.apks"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("base.apk", apk_bytes())
                archive.writestr("base.apk", apk_bytes())
        result = self.run_cli(bundle)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("повторяются имена APK", result.stderr)

    def test_report_output_is_atomic_and_cannot_overwrite_an_input(self):
        source = self.write_apk()
        original = source.read_bytes()
        result = self.run_cli(source, "--output", source)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(source.read_bytes(), original)

        report_path = self.directory / "reports" / "inventory.json"
        result = self.run_cli(source, "--output", report_path)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(report_path.read_text())["summary"]["apk_count"], 1)
        self.assertFalse(list(report_path.parent.glob(".apk-inventory-*")))

    def test_manifest_and_nested_apk_size_limits(self):
        source = self.write_apk()
        with mock.patch.object(inspect_apk, "MAX_MANIFEST_BYTES", 5):
            with self.assertRaises(inspect_apk.InspectionError):
                inspect_apk.build_report([source])
        bundle = self.directory / "bundle.apks"
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr("base.apk", apk_bytes())
        with mock.patch.object(inspect_apk, "MAX_APK_BYTES", 5):
            with self.assertRaises(inspect_apk.InspectionError):
                inspect_apk.build_report([bundle])


if __name__ == "__main__":
    unittest.main()
