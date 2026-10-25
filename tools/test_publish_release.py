"""Offline publication tests. Synthetic artifact bytes and fake gh; never publish/build anything."""
import importlib.util
import json
import os
import shutil
import textwrap
import sys
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-github-release.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
SHA = "a" * 40
SOURCE = 'versionName = "3.0.9"\nversionCode = 69\n'
MARKER = f"<!-- animevault-release-ci:{SHA} -->"


class FakeGitHub:
    def __init__(self):
        self.head = SHA
        self.release = None
        self.latest = None
        self.tag = None
        self.tag_commit = SHA
        self.operations = []
        self.fail_upload = False

    def api(self, path, *, method=None, **fields):
        self.operations.append((path, fields))
        if path == "commits/main": return {"sha": self.head}
        if path.startswith("commits/"): return {"sha": self.tag_commit}
        if path == "git/refs": self.tag = {"ref": fields["ref"]}; return self.tag
        if path == "releases/1" and fields:
            self.release["draft"] = fields["draft"]
        return self.release

    def optional_api(self, path):
        if path.startswith("releases/tags/"): return self.release
        if path == "releases/latest": return self.latest
        return self.tag

    def run(self, *args):
        self.operations.append(args)
        if args[:2] == ("release", "create"):
            self.release = {"id": 1, "draft": True, "body": args[-1]}
        if args[:2] == ("release", "upload") and self.fail_upload:
            raise subprocess.CalledProcessError(1, ["gh"])
        if args[:2] == ("release", "download"):
            Path(args[-1]).write_text(json.dumps({"versionCode": self.latest["code"]}))
        return ""


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dist = Path(self.tmp.name)
        apk = self.dist / "AnimeVault.apk"
        apk.write_bytes(b"synthetic artifact, not a real APK")
        badging = "package: name='com.sergey.animevault' versionName='3.0.9' versionCode='69'\nsdkVersion:'24'\n"
        (self.dist / "apk-badging.txt").write_text(badging)
        metadata = publisher.version.manifest_generator.prepare(apk, badging)
        (self.dist / "animevault-update.json").write_text(json.dumps(metadata))
        self.gh = FakeGitHub()

    def publish(self):
        publisher.publish(SOURCE, self.dist, "owner/repo", SHA, self.gh)

    def test_creates_tag_then_draft_uploads_and_publishes(self):
        self.publish()
        self.assertFalse(self.gh.release["draft"])
        self.assertIn(("git/refs", {"ref": "refs/tags/v3.0.9", "sha": SHA}), self.gh.operations)
        upload = next(op for op in self.gh.operations if op[:2] == ("release", "upload"))
        self.assertIn(str(self.dist / "AnimeVault.apk"), upload)
        self.assertIn(str(self.dist / "animevault-update.json"), upload)
        self.assertNotIn(str(self.dist / "apk-badging.txt"), upload)
        self.assertEqual(("releases/1", {"draft": False, "make_latest": "true"}), self.gh.operations[-1])

    def test_published_release_is_never_mutated(self):
        self.gh.release = {"draft": False, "assets": [{"name": "AnimeVault.apk", "size": 42}, {"name": "animevault-update.json", "size": 100}]}
        self.publish()
        self.assertEqual([("commits/main", {})], self.gh.operations)

    def test_incomplete_public_release_fails_without_upload(self):
        for assets in [[], [{"name": "AnimeVault.apk", "size": 42}],
                       [{"name": "AnimeVault.apk", "size": 0}, {"name": "animevault-update.json", "size": 100}]]:
            self.gh.release = {"draft": False, "assets": assets}
            with self.assertRaises(ValueError): self.publish()
            self.assertFalse(any(op[:2] == ("release", "upload") for op in self.gh.operations))

    def test_superseded_build_does_not_publish(self):
        self.gh.head = "b" * 40
        self.publish()
        self.assertIsNone(self.gh.release)

    def test_tag_for_another_commit_is_not_moved(self):
        self.gh.tag = {"ref": "refs/tags/v3.0.9"}
        self.gh.tag_commit = "b" * 40
        with self.assertRaises(ValueError): self.publish()
        self.assertFalse(any(op[0] == "git/refs" for op in self.gh.operations))

    def test_foreign_draft_is_not_modified(self):
        self.gh.release = {"id": 1, "draft": True, "body": "manual draft"}
        with self.assertRaises(ValueError): self.publish()

    def test_own_draft_can_resume_after_failed_upload(self):
        self.gh.fail_upload = True
        with self.assertRaises(subprocess.CalledProcessError): self.publish()
        self.assertTrue(self.gh.release["draft"])
        self.gh.fail_upload = False
        self.publish()
        self.assertFalse(self.gh.release["draft"])
        self.assertEqual(1, sum(op[:2] == ("release", "create") for op in self.gh.operations))

    def test_stale_json_and_tampered_apk_fail_before_github_access(self):
        for field, value in [("versionCode", 68), ("versionName", "3.0.8"), ("sha256", "0" * 64)]:
            p = self.dist / "animevault-update.json"
            previous = p.read_text(); obj = json.loads(previous); obj[field] = value
            p.write_text(json.dumps(obj))
            with self.assertRaises(ValueError): self.publish()
            self.assertEqual([], self.gh.operations)
            p.write_text(previous)
        (self.dist / "AnimeVault.apk").write_bytes(b"changed")
        with self.assertRaises(ValueError): self.publish()

    def test_older_or_equal_version_code_cannot_become_latest(self):
        for code in (69, 70):
            self.gh.latest = {"tag_name": "v4.0.0", "code": code}
            with self.assertRaises(ValueError): self.publish()
            self.assertIsNone(self.gh.release)

    def test_newer_version_code_can_publish(self):
        self.gh.latest = {"tag_name": "v3.0.8", "code": 68}
        self.publish()
        self.assertFalse(self.gh.release["draft"])

    def test_optional_api_only_treats_404_as_absent(self):
        gh = publisher.GitHub("owner/repo")
        for stderr in ("HTTP 401", "HTTP 403", "HTTP 429", "HTTP 503", "network unavailable"):
            with patch.object(gh, "run", side_effect=subprocess.CalledProcessError(1, ["gh"], stderr=stderr)):
                with self.assertRaises(subprocess.CalledProcessError): gh.optional_api("releases/latest")
        with patch.object(gh, "run", side_effect=subprocess.CalledProcessError(1, ["gh"], stderr="gh: Not Found (HTTP 404)")):
            self.assertIsNone(gh.optional_api("releases/latest"))

    def test_main_preflight_derives_tag_and_exports_release_gate(self):
        root = self.dist
        (root / "app").mkdir()
        (root / "tools").mkdir()
        (root / "app/build.gradle.kts").write_text('versionName = "3.1.0"\nversionCode = 70\n')
        for name in ("publish-github-release.py", "verify-release-version.py", "prepare-update-manifest.py"):
            shutil.copyfile(Path(__file__).parent / name, root / "tools" / name)
        workflow = (Path(__file__).parent.parent / ".github/workflows/build-apk.yml").read_text()
        step = workflow.split("      - name: Validate release target before building\n")[1].split("\n      - name:")[0]
        shell = textwrap.dedent(step.split("        run: |\n")[1])
        env_file = root / "github-env"
        result = subprocess.run(["bash", "-c", shell], cwd=root, capture_output=True, text=True,
            env={**os.environ, "AUTO_RELEASE": "true", "RELEASE_BUILD": "false", "RELEASE_TAG": "",
                 "RELEASE_REF_TAG": "", "GITHUB_ENV": str(env_file), "PYTHONDONTWRITEBYTECODE": "1"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("RELEASE_TAG=v3.1.0\nRELEASE_BUILD=true\n", env_file.read_text())

    def test_cli_refuses_manual_or_feature_branch_publication(self):
        source = self.dist / "source.gradle.kts"
        source.write_text(SOURCE)
        for event, ref in [("workflow_dispatch", "refs/heads/main"), ("push", "refs/heads/feature/test")]:
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("publish-github-release.py")),
                                     "--source", str(source)], capture_output=True, text=True,
                                    env={**os.environ, "GITHUB_EVENT_NAME": event, "GITHUB_REF": ref})
            self.assertNotEqual(0, result.returncode)
            self.assertIn("restricted to a main push", result.stderr)

    def test_draft_hidden_by_tag_endpoint_is_found_on_second_list_page(self):
        gh = publisher.GitHub("owner/repo")
        missing = subprocess.CalledProcessError(1, ["gh"], stderr="gh: Not Found (HTTP 404)")
        draft = {"id": 1, "draft": True, "tag_name": "v3.0.9", "body": MARKER}
        with patch.object(gh, "run", side_effect=[missing, json.dumps([[{"tag_name": "v3.0.8"}], [draft]])]) as run:
            self.assertEqual(draft, gh.optional_api("releases/tags/v3.0.9"))
            self.assertIn("--paginate", run.call_args.args)
            self.assertIn("--slurp", run.call_args.args)

    def test_tag_404_then_list_access_failure_is_not_treated_as_missing(self):
        gh = publisher.GitHub("owner/repo")
        missing = subprocess.CalledProcessError(1, ["gh"], stderr="gh: Not Found (HTTP 404)")
        denied = subprocess.CalledProcessError(1, ["gh"], stderr="gh: Forbidden (HTTP 403)")
        with patch.object(gh, "run", side_effect=[missing, denied]):
            with self.assertRaises(subprocess.CalledProcessError): gh.optional_api("releases/tags/v3.0.9")

    def test_publish_survives_realistic_hidden_draft_response(self):
        fake = self.gh
        gh = publisher.GitHub("owner/repo")
        def api(path, **fields):
            if path.startswith("releases/tags/"):
                if fake.release and not fake.release["draft"]: return fake.release
                raise subprocess.CalledProcessError(1, ["gh"], stderr="gh: Not Found (HTTP 404)")
            if path == "releases/latest" or path.startswith("git/ref/"):
                raise subprocess.CalledProcessError(1, ["gh"], stderr="gh: Not Found (HTTP 404)")
            return fake.api(path, **fields)
        def run(*args):
            if args[0] == "api":
                return json.dumps([[dict(fake.release, tag_name="v3.0.9")] if fake.release else []])
            return fake.run(*args)
        with patch.object(gh, "api", side_effect=api), patch.object(gh, "run", side_effect=run):
            publisher.publish(SOURCE, self.dist, "owner/repo", SHA, gh)
        self.assertFalse(fake.release["draft"])
        self.assertTrue(any(op[:2] == ("release", "upload") for op in fake.operations))

    def test_network_timeout_is_bounded_and_never_treated_as_missing(self):
        gh = publisher.GitHub("owner/repo")
        with patch.object(publisher.subprocess, "run", side_effect=subprocess.TimeoutExpired(["gh"], 120)) as run:
            with self.assertRaises(subprocess.TimeoutExpired): gh.optional_api("releases/latest")
            self.assertEqual(120, run.call_args.kwargs["timeout"])

    def test_api_sends_draft_as_boolean(self):
        gh = publisher.GitHub("owner/repo")
        with patch.object(gh, "run", return_value="{}") as run:
            gh.api("releases/1", method="PATCH", draft=False, make_latest="true")
            self.assertIn("PATCH", run.call_args.args)
            self.assertIn("--method", run.call_args.args)
            self.assertIn("-F", run.call_args.args)
            self.assertIn("draft=false", run.call_args.args)


if __name__ == "__main__": unittest.main()
