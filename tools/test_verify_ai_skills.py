"""CLI regression tests: mutate disposable checkouts, never the real skills."""
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
LOCAL = ".agents/skills/animevault-testing/SKILL.md"
UPSTREAM = ".agents/skills/android-intent-security/SKILL.md"
REGISTRY = ".agents/skills/local-skills.json"
LOCK = ".agents/skills/upstream-lock.json"


def edit_json(root, path, mutate):
    target = root / path
    data = json.loads(target.read_text())
    mutate(data)
    target.write_text(json.dumps(data))


def replace(root, path, before, after):
    target = root / path
    text = target.read_text()
    assert before in text, (path, before)
    target.write_text(text.replace(before, after, 1))


def description(root, value):
    target = root / LOCAL
    target.write_text(re.sub(r"^description:.*$", "description: " + value,
                             target.read_text(), count=1, flags=re.M))


def link_outside(root, relative):
    target = root / relative
    target.unlink()
    # Invalid UTF-8 canary: validator must reject the symlink before reading it.
    outside = root.parent / "outside-canary"
    outside.write_bytes(b"PRIVATE-CANARY-NEVER-PRINT\xff")
    target.symlink_to(outside)


def unregistered(root):
    target = root / ".agents/skills/unregistered/SKILL.md"
    target.parent.mkdir()
    target.write_text("---\nname: unregistered\ndescription: Unexpected skill\n---\nBody\n")


CASES = {
    "clean_checkout": None,
    "upstream_bytes": lambda r: (r / UPSTREAM).write_text((r / UPSTREAM).read_text() + "\ntamper\n"),
    "missing_reference": lambda r: (r / ".agents/skills/jetpack-compose-performance/references/layouts.md").unlink(),
    "missing_license": lambda r: (r / ".agents/skills/android-intent-security/LICENSE.txt").unlink(),
    "missing_skill": lambda r: (r / LOCAL).unlink(),
    "wrong_name": lambda r: replace(r, LOCAL, "name: animevault-testing", "name: wrong-name"),
    "missing_frontmatter": lambda r: replace(r, LOCAL, "---", "broken"),
    "duplicate_name_field": lambda r: replace(r, LOCAL, "name: animevault-testing", "name: animevault-testing\nname: other"),
    "invalid_yaml_description": lambda r: description(r, "[unterminated"),
    "empty_description": lambda r: description(r, ""),
    "empty_block_description": lambda r: description(r, "|"),
    "boolean_description": lambda r: description(r, "true"),
    "colon_mapping_description": lambda r: description(r, "bad: mapping"),
    "missing_description": lambda r: replace(r, LOCAL, "description:", "other:"),
    "empty_body": lambda r: (r / LOCAL).write_text((r / LOCAL).read_text().split("---", 2)[0] + "---" + (r / LOCAL).read_text().split("---", 2)[1] + "---\n"),
    "duplicate_registry": lambda r: edit_json(r, REGISTRY, lambda d: d["skills"].append(d["skills"][0])),
    "unregistered_skill": unregistered,
    "broken_route": lambda r: replace(r, "AGENTS.md", LOCAL, ".agents/skills/missing/SKILL.md"),
    "broken_local_link": lambda r: (r / LOCAL).write_text((r / LOCAL).read_text() + "\n[missing](absent.md)\n"),
    "skill_symlink": lambda r: link_outside(r, LOCAL),
    "manifest_symlink": lambda r: link_outside(r, LOCK),
    "agents_symlink": lambda r: link_outside(r, "AGENTS.md"),
    "executable_script": lambda r: (r / ".agents/skills/animevault-testing/install.sh").write_text("exit 0\n"),
    "executable_markdown": lambda r: (r / LOCAL).chmod(0o755),
    "empty_lock": lambda r: edit_json(r, LOCK, lambda d: d.update(packages=[])),
    "removed_bundle": lambda r: edit_json(r, LOCK, lambda d: d["packages"].pop()),
    "duplicate_bundle": lambda r: edit_json(r, LOCK, lambda d: d["packages"].append(d["packages"][0])),
    "bad_commit": lambda r: edit_json(r, LOCK, lambda d: d["packages"][0].update(commit="HEAD")),
    "bad_hash": lambda r: edit_json(r, LOCK, lambda d: d["packages"][0]["sha256"].update({next(iter(d["packages"][0]["sha256"])): "0" * 64})),
    "escaping_destination": lambda r: edit_json(r, LOCK, lambda d: d["packages"][0].update(destination="../outside")),
    "invalid_json": lambda r: (r / REGISTRY).write_text("{"),
    "duplicate_json_key": lambda r: replace(r, REGISTRY, '"schema_version": 1', '"schema_version": 1, "schema_version": 1'),
    "invalid_schema": lambda r: edit_json(r, REGISTRY, lambda d: d.update(skills="invalid")),
}


class VerifyAiSkillsTest(unittest.TestCase):
    def run_case(self, mutate):
        with tempfile.TemporaryDirectory(prefix="animevault-skill-test-") as temporary:
            root = Path(temporary) / "checkout"
            shutil.copytree(ROOT / ".agents", root / ".agents")
            shutil.copytree(ROOT / "docs", root / "docs")
            shutil.copy2(ROOT / "AGENTS.md", root / "AGENTS.md")
            (root / "tools").mkdir()
            shutil.copy2(ROOT / "tools/verify-ai-skills.py", root / "tools/verify-ai-skills.py")
            shutil.copy2(ROOT / "tools/test_verify_ai_skills.py", root / "tools/test_verify_ai_skills.py")
            # Review docs link to Android source. Include only those source files,
            # never app/build outputs, Firebase config or the full Android checkout.
            for document in (ROOT / "docs").glob("AI_SKILLS_*.md"):
                for link in re.findall(r"\[[^\]\n]+\]\(([^)\s]+)\)", document.read_text()):
                    if not link.startswith("../app/"):
                        continue
                    source = (document.parent / link.split("#", 1)[0]).resolve()
                    if source.is_relative_to(ROOT / "app") and source.is_file() and source.suffix in {".kt", ".kts", ".xml"}:
                        target = root / source.relative_to(ROOT)
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(source, target)
            if mutate:
                mutate(root)
            result = subprocess.run([sys.executable, str(root / "tools/verify-ai-skills.py")],
                                    capture_output=True, text=True, timeout=10)
            if mutate is None:
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("OK: 10 skills, 3 pinned upstream bundles", result.stdout)
            else:
                self.assertNotEqual(result.returncode, 0, "Invalid checkout incorrectly accepted")
                self.assertIn("ERROR:", result.stderr)
                self.assertNotIn("Traceback", result.stderr)
            self.assertNotIn("PRIVATE-CANARY", result.stdout + result.stderr)


def make_test(mutate):
    def test(self):
        self.run_case(mutate)
    return test


for case_name, mutation in CASES.items():
    setattr(VerifyAiSkillsTest, "test_" + case_name, make_test(mutation))


if __name__ == "__main__":
    unittest.main()
