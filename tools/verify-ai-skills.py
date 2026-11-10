#!/usr/bin/env python3
"""Offline validation of AnimeVault's versioned skills; Python standard library only.

Reads repository files, prints metadata/errors, never executes skill content or
downloads dependencies. Upstream hashes establish integrity, not trust.
"""
import hashlib
import json
from pathlib import Path
import re
import sys
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent.parent


def verify(root=ROOT):
    root = root.resolve()
    skills = root / ".agents/skills"
    errors = []

    def require(condition, message):
        if not condition:
            errors.append(message)

    def safe_file(relative):
        initial_errors = len(errors)
        path = root / relative
        require(not Path(relative).is_absolute(), f"Absolute path: {relative}")
        require(path.resolve().is_relative_to(root), f"Path escapes checkout: {relative}")
        for part in (path, *path.parents):
            if part == root:
                break
            require(not part.is_symlink(), f"Symlink is not permitted: {relative}")
        require(path.is_file(), f"Missing file: {relative}")
        return path if len(errors) == initial_errors else None

    registry = json.loads((skills / "local-skills.json").read_text())
    lock = json.loads((skills / "upstream-lock.json").read_text())
    require(registry["schema_version"] == lock["schema_version"] == 1, "Unsupported schema")
    discovered = {p.parent.name for p in skills.glob("*/SKILL.md")}
    registered = [entry["name"] for entry in registry["skills"]]
    require(len(registered) == len(set(registered)), "Duplicate registry name")
    require(discovered == set(registered), "Registry differs from installed SKILL.md directories")
    names = set()
    agent_text = (root / "AGENTS.md").read_text()
    for entry in registry["skills"]:
        name = entry["name"]
        require(bool(re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name)), f"Invalid name: {name}")
        relative = f".agents/skills/{name}/SKILL.md"
        path = safe_file(relative)
        if path is None:
            continue
        content = path.read_text()
        frontmatter = re.match(r"\A---\r?\n(.*?)\r?\n---(?:\r?\n|$)", content, re.S)
        require(frontmatter is not None, f"Missing frontmatter: {relative}")
        if frontmatter:
            fields = frontmatter.group(1)
            match = re.search(r"^name:\s*([a-z0-9-]+)\s*$", fields, re.M)
            actual = match.group(1) if match else None
            require(actual == name, f"Name mismatch: {relative}")
            require(actual not in names, f"Duplicate skill: {actual}")
            names.add(actual)
            require(bool(re.search(r"^description:\s*\S", fields, re.M)), f"Missing description: {relative}")
        require(relative in agent_text, f"Missing AGENTS route: {name}")

    for package in lock["packages"]:
        require(bool(re.fullmatch(r"[0-9a-f]{40}", package["commit"])), f"Invalid commit: {package['name']}")
        destination = root / package["destination"]
        require(destination.resolve().is_relative_to(skills.resolve()), "Upstream destination outside skills")
        hashes = package["sha256"]
        files = {str(p.relative_to(root)) for p in destination.rglob("*") if p.is_file()}
        require(files == set(hashes), f"Upstream file set changed: {package['name']}")
        require(any(Path(p).name in {"LICENSE", "LICENSE.txt"} for p in hashes), f"Missing license: {package['name']}")
        for relative, expected in hashes.items():
            path = safe_file(relative)
            if path is None:
                continue
            require(path.is_relative_to(destination), f"Hash outside package: {relative}")
            require(hashlib.sha256(path.read_bytes()).hexdigest() == expected, f"Hash mismatch: {relative}")

    # No third-party executable assets are part of this installation.
    for path in skills.rglob("*"):
        require(not path.is_symlink(), f"Skill symlink: {path.relative_to(root)}")
        if path.is_file():
            require(path.suffix in {".md", ".txt", ".csv", ".json"} or path.name == "LICENSE",
                    f"Unexpected skill asset: {path.relative_to(root)}")

    documents = [root / "AGENTS.md", *sorted((root / "docs").glob("AI_SKILLS_*.md")), *sorted(skills.rglob("*.md"))]
    for document in documents:
        for target in re.findall(r"\[[^\]\n]+\]\(([^)\s]+)\)", document.read_text()):
            target = target.strip("<>")
            if re.match(r"[a-zA-Z][a-zA-Z0-9+.-]*:", target) or target.startswith("#"):
                continue
            target = unquote(target.split("#", 1)[0])
            resolved = (document.parent / target).resolve()
            require(resolved.is_relative_to(root) and resolved.exists(),
                    f"Broken local link: {document.relative_to(root)} -> {target}")

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"OK: {len(discovered)} skills, {len(lock['packages'])} pinned upstream bundles; names, routes, links, licenses and SHA-256 verified.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(verify())
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"ERROR: Invalid skills configuration ({type(error).__name__})", file=sys.stderr)
        sys.exit(1)
