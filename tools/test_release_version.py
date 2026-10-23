"""Release gate tests with synthetic bytes/badging; no Gradle, SDK, emulators or APK build."""
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "tools/verify-release-version.py"
spec = importlib.util.spec_from_file_location("verify_release_version", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
SOURCE = 'android {\n    versionName = "3.0.6"\n    versionCode = 66\n}\n'
BADGING = "package: name='com.sergey.animevault' versionName='3.0.6' versionCode='66'\nsdkVersion:'24'\n"


def workflow_shell(name):
    workflow = (ROOT / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")
    step = workflow.split(f"      - name: {name}\n", 1)[1].split("\n      - name:", 1)[0]
    return textwrap.dedent(step.split("        run: |\n", 1)[1])


class ReleaseVersionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.apk = self.root / "AnimeVault.apk"
        self.apk.write_bytes(b"synthetic fixture; not an Android APK")
        self.metadata = module.manifest_generator.prepare(self.apk, BADGING)

    def verify(self, **overrides):
        args = dict(source=SOURCE, release_tag="v3.0.6", apk=self.apk, badging=BADGING, metadata=self.metadata)
        args.update(overrides)
        return module.verify(**args)

    def test_matching_apk_json_source_and_tag_pass(self):
        self.assertEqual(("3.0.6", 66), self.verify())

    def test_stale_source_cannot_be_relabelled_v306(self):
        with self.assertRaisesRegex(ValueError, "tag.*source"):
            module.verify(SOURCE.replace('3.0.6', '3.0.5').replace('66', '65'), "v3.0.6")

    def test_old_apk_and_matching_old_json_are_rejected(self):
        old_badging = BADGING.replace('3.0.6', '3.0.5').replace("'66'", "'65'")
        old_json = module.manifest_generator.prepare(self.apk, old_badging)
        with self.assertRaisesRegex(ValueError, "APK version"):
            self.verify(badging=old_badging, metadata=old_json)

    def test_json_name_or_code_mismatch_is_rejected(self):
        for field, value in [("versionName", "3.0.5"), ("versionCode", 65), ("versionCode", "66"), ("versionCode", 66.0)]:
            with self.subTest(field=field, value=value), self.assertRaisesRegex(ValueError, "Updater JSON"):
                self.verify(metadata={**self.metadata, field: value})

    def test_stale_apk_code_is_rejected_even_if_name_matches(self):
        badging = BADGING.replace("'66'", "'65'")
        with self.assertRaisesRegex(ValueError, "APK version"):
            self.verify(badging=badging, metadata=module.manifest_generator.prepare(self.apk, badging))

    def test_missing_malformed_or_different_release_tag_is_rejected(self):
        for tag in ["", "main", "3.0.6", "v3.0.5", "v3.0.7", "v3.0.6-rc1", "v3.0.6\n"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.verify(release_tag=tag)

    def test_tampered_hash_asset_or_package_is_rejected(self):
        for field, value in [("sha256", "0" * 64), ("apkFileName", "other.apk"), ("packageName", "com.other")]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.verify(metadata={**self.metadata, field: value})

    def test_partial_inputs_or_non_object_json_are_rejected(self):
        for override in [dict(apk=None), dict(badging=None), dict(metadata=None), dict(metadata=[])]:
            with self.subTest(override=override), self.assertRaises(ValueError):
                self.verify(**override)

    def test_ambiguous_source_version_is_rejected(self):
        with self.assertRaises(ValueError):
            module.verify(SOURCE + '\nversionCode = 67\n', "v3.0.6")

    def test_branch_build_compares_apk_and_json_without_inventing_a_release_tag(self):
        self.assertEqual(("3.0.6", 66), self.verify(release_tag=None))
        with self.assertRaises(ValueError):
            self.verify(release_tag=None, metadata={**self.metadata, "versionCode": 65})

    def test_cli_returns_nonzero_on_tag_mismatch(self):
        source = self.root / "build.gradle.kts"
        source.write_text(SOURCE, encoding="utf-8")
        result = subprocess.run([sys.executable, str(SCRIPT), "--source", str(source),
                                 "--release-tag", "v3.0.5"], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Release validation failed", result.stderr)

    def prepare_workflow_fixture(self):
        (self.root / "tools").mkdir()
        for name in ["verify-release-version.py", "prepare-update-manifest.py"]:
            shutil.copyfile(ROOT / "tools" / name, self.root / "tools" / name)
        (self.root / "app").mkdir()
        (self.root / "app/build.gradle.kts").write_text(SOURCE, encoding="utf-8")
        (self.root / "dist").mkdir()
        shutil.copyfile(self.apk, self.root / "dist/AnimeVault.apk")
        (self.root / "update-badging.txt").write_text(BADGING, encoding="utf-8")
        (self.root / "dist/animevault-update.json").write_text(json.dumps(self.metadata), encoding="utf-8")

    def run_workflow_gate(self, name, tag="v3.0.6", release=True, ref_tag=""):
        return subprocess.run(["bash", "-c", workflow_shell(name)], cwd=self.root,
                              env={**os.environ, "RELEASE_BUILD": str(release).lower(),
                                   "RELEASE_TAG": tag, "RELEASE_REF_TAG": ref_tag, "RUNNER_TEMP": str(self.root)},
                              capture_output=True, text=True)

    def test_actual_workflow_preflight_rejects_missing_or_wrong_tag(self):
        self.prepare_workflow_fixture()
        name = "Validate release target before building"
        self.assertEqual(0, self.run_workflow_gate(name).returncode)
        self.assertNotEqual(0, self.run_workflow_gate(name, tag="").returncode)
        self.assertNotEqual(0, self.run_workflow_gate(name, tag="v3.0.5").returncode)
        self.assertEqual(0, self.run_workflow_gate(name, tag="", release=False).returncode)
        self.assertEqual(0, self.run_workflow_gate(name, ref_tag="v3.0.6").returncode)
        self.assertNotEqual(0, self.run_workflow_gate(name, ref_tag="v3.0.5").returncode)

    def test_actual_workflow_post_build_gate_rejects_bad_json_and_tag(self):
        self.prepare_workflow_fixture()
        name = "Require matching APK, JSON, source and release tag"
        self.assertEqual(0, self.run_workflow_gate(name).returncode)
        self.assertNotEqual(0, self.run_workflow_gate(name, tag="v3.0.7").returncode)
        self.assertNotEqual(0, self.run_workflow_gate(name, tag="").returncode)
        (self.root / "dist/animevault-update.json").write_text(
            json.dumps({**self.metadata, "versionCode": 65}), encoding="utf-8")
        self.assertNotEqual(0, self.run_workflow_gate(name).returncode)


if __name__ == "__main__":
    unittest.main()
