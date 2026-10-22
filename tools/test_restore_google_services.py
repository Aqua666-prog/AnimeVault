"""Tests of config restoration only. Synthetic fixtures are never used to build an APK."""
import base64
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

FIXTURE = {
    "project_info": {"project_id": "animevault-60b01", "project_number": "123456789"},
    "client": [{
        "client_info": {
            "mobilesdk_app_id": "1:123456789:android:fixture",
            "android_client_info": {"package_name": "com.sergey.animevault"},
        },
        "oauth_client": [{"client_type": 3, "client_id": "fixture.apps.googleusercontent.com"}],
        "api_key": [{"current_key": "unit-test-fixture-not-a-real-api-key"}],
    }],
}

class RestoreGoogleServicesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.target = root / "app" / "google-services.json"
        self.script = root / "tools" / "restore-google-services.py"
        self.script.parent.mkdir()
        shutil.copyfile(Path(__file__).with_name("restore-google-services.py"), self.script)

    def run_restore(self, encoded):
        environment = os.environ.copy()
        environment["GOOGLE_SERVICES_JSON_B64"] = encoded
        return subprocess.run(
            [sys.executable, str(self.script)], env=environment,
            capture_output=True, text=True, check=False,
        )

    def assert_rejected(self, encoded):
        result = self.run_restore(encoded)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("::error::", result.stderr)
        self.assertFalse(self.target.exists())
        if encoded:
            self.assertNotIn(encoded, result.stdout + result.stderr)

    @staticmethod
    def encode(config):
        return base64.b64encode(json.dumps(config).encode()).decode()

    def test_missing_secret_stops_build(self):
        self.assert_rejected("")

    def test_invalid_base64_is_rejected_without_echoing_input(self):
        self.assert_rejected("invalid-base64-do-not-echo!")

    def test_wrong_project_is_rejected(self):
        config = copy.deepcopy(FIXTURE)
        config["project_info"]["project_id"] = "another-project"
        self.assert_rejected(self.encode(config))

    def test_wrong_package_is_rejected(self):
        config = copy.deepcopy(FIXTURE)
        config["client"][0]["client_info"]["android_client_info"]["package_name"] = "another.package"
        self.assert_rejected(self.encode(config))

    def test_missing_web_client_is_rejected(self):
        config = copy.deepcopy(FIXTURE)
        config["client"][0]["oauth_client"] = [{"client_type": 1, "client_id": "android-client"}]
        self.assert_rejected(self.encode(config))

    def test_incomplete_firebase_options_are_rejected(self):
        for field in ("api_key", "client_info"):
            with self.subTest(field=field):
                config = copy.deepcopy(FIXTURE)
                config["client"][0].pop(field)
                self.assert_rejected(self.encode(config))

    def test_valid_structure_is_restored_privately_without_logging_values(self):
        encoded = self.encode(FIXTURE)
        result = self.run_restore("\n" + encoded + "\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.target.read_text()), FIXTURE)
        self.assertEqual(self.target.stat().st_mode & 0o777, 0o600)
        self.assertNotIn(encoded, result.stdout + result.stderr)
        self.assertNotIn("unit-test-fixture-not-a-real-api-key", result.stdout + result.stderr)

if __name__ == "__main__":
    unittest.main()
