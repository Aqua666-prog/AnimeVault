"""Lightweight fixture tests: no Android SDK, Gradle or APK compilation."""
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("prepare_update_manifest", Path(__file__).with_name("prepare-update-manifest.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
BADGING = "package: name='com.sergey.animevault' versionCode='65' versionName='3.0.5'\nsdkVersion:'24'\n"


class UpdateManifestTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "AnimeVault.apk"
        self.apk.write_bytes(b"synthetic fixture bytes; this is not an APK")

    def test_reads_apk_metadata_and_hashes_exact_bytes(self):
        result = module.prepare(self.apk, BADGING)
        self.assertEqual(65, result["versionCode"])
        self.assertEqual(24, result["minSdk"])
        self.assertEqual("3.0.5", result["versionName"])
        self.assertEqual("AnimeVault.apk", result["apkFileName"])
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), result["sha256"])
        self.assertEqual(1, result["schemaVersion"])

    def test_cli_derives_version_from_each_apk_badging_without_pinned_release(self):
        badging_file = Path(self.directory.name) / "badging.txt"
        output = Path(self.directory.name) / "animevault-update.json"
        for version_code, version_name in [(64, "3.0.4"), (65, "3.0.5"), (66, "3.0.6")]:
            with self.subTest(version_code=version_code, version_name=version_name):
                badging_file.write_text(
                    BADGING.replace("versionCode='65'", f"versionCode='{version_code}'")
                    .replace("versionName='3.0.5'", f"versionName='{version_name}'"),
                    encoding="utf-8",
                )
                subprocess.run(
                    [sys.executable, str(Path(module.__file__)), "--apk", str(self.apk),
                     "--badging", str(badging_file), "--output", str(output)],
                    check=True, capture_output=True, text=True,
                )
                metadata = json.loads(output.read_text(encoding="utf-8"))
                self.assertEqual(version_code, metadata["versionCode"])
                self.assertEqual(version_name, metadata["versionName"])
                self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), metadata["sha256"])

    def test_rejects_other_package(self):
        with self.assertRaises(ValueError):
            module.prepare(self.apk, BADGING.replace("com.sergey.animevault", "com.other.app"))

    def test_rejects_invalid_codes(self):
        for value in ["0", "-1", "1.5", "2147483648", "x"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                module.prepare(self.apk, BADGING.replace("versionCode='65'", f"versionCode='{value}'"))

    def test_rejects_missing_sdk_and_version(self):
        for badging in [BADGING.replace("sdkVersion:'24'", ""), BADGING.replace("3.0.5", ""), BADGING.replace("sdkVersion:'24'", "sdkVersion:'23'")]:
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                module.prepare(self.apk, badging)

    def test_rejects_empty_or_missing_apk(self):
        self.apk.write_bytes(b"")
        with self.assertRaises(ValueError):
            module.prepare(self.apk, BADGING)
        self.apk.unlink()
        with self.assertRaises(ValueError):
            module.prepare(self.apk, BADGING)

    def test_rejects_unsafe_asset_name(self):
        unsafe = self.apk.with_name("Anime Vault.apk")
        self.apk.rename(unsafe)
        with self.assertRaises(ValueError):
            module.prepare(unsafe, BADGING)


if __name__ == "__main__":
    unittest.main()
