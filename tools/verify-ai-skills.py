#!/usr/bin/env python3
"""Offline validation of AnimeVault's versioned skills; Python standard library only.

Reads repository files, prints metadata/errors, never executes skill content or
downloads dependencies. Upstream hashes establish integrity, not trust.
"""
import hashlib
import json
import os
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

    def safe_path(relative, directory=False):
        initial_errors = len(errors)
        if not isinstance(relative, str) or not relative:
            raise ValueError("Expected nonempty relative path")
        path = root / relative
        require(not Path(relative).is_absolute(), f"Absolute path: {relative}")
        require(path.resolve().is_relative_to(root), f"Path escapes checkout: {relative}")
        for part in (path, *path.parents):
            if part == root:
                break
            require(not part.is_symlink(), f"Symlink is not permitted: {relative}")
        require(path.is_dir() if directory else path.is_file(), f"Missing path: {relative}")
        return path if len(errors) == initial_errors else None

    # Reject links before any content reads, including manifests and documents.
    for base, directories, files in os.walk(skills, followlinks=False):
        for name in directories + files:
            path = Path(base) / name
            require(not path.is_symlink(), f"Skill symlink: {path.relative_to(root)}")
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    def load_manifest(filename, list_key):
        path = safe_path(f".agents/skills/{filename}")
        if path is None:
            raise ValueError("Missing or unsafe manifest")
        def unique_pairs(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError("Duplicate JSON key")
                result[key] = value
            return result
        data = json.loads(path.read_text(), object_pairs_hook=unique_pairs)
        if (not isinstance(data, dict) or data.get("schema_version") != 1
                or not isinstance(data.get(list_key), list) or not data[list_key]
                or not all(isinstance(item, dict) for item in data[list_key])):
            raise ValueError("Invalid manifest schema")
        return data

    registry = load_manifest("local-skills.json", "skills")
    lock = load_manifest("upstream-lock.json", "packages")
    discovered = {p.parent.name for p in skills.glob("*/SKILL.md")}
    registered = [entry["name"] for entry in registry["skills"]]
    require(len(registered) == len(set(registered)), "Duplicate registry name")
    require(discovered == set(registered), "Registry differs from installed SKILL.md directories")
    names = set()
    agent_path = safe_path("AGENTS.md")
    if agent_path is None:
        raise ValueError("Missing or unsafe AGENTS.md")
    agent_text = agent_path.read_text()
    expected_bundles = []
    for entry in registry["skills"]:
        name = entry["name"]
        if not isinstance(name, str) or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) or len(name) > 64:
            raise ValueError("Invalid skill name")
        require(entry["origin"] in {"upstream", "project"}, f"Invalid origin: {name}")
        require(entry["status"] in {"installed", "preserved"}, f"Invalid status: {name}")
        bundles = entry["upstream_bundles"]
        if not isinstance(bundles, list) or not all(isinstance(b, str) for b in bundles):
            raise ValueError("Invalid bundle references")
        require(len(bundles) == len(set(bundles)), f"Duplicate bundle reference: {name}")
        require(entry["origin"] != "upstream" or name in bundles, f"Unpinned upstream skill: {name}")
        expected_bundles.extend(bundles)
        relative = f".agents/skills/{name}/SKILL.md"
        path = safe_path(relative)
        if path is None:
            continue
        content = path.read_text()
        frontmatter = re.match(r"\A---\r?\n(.*?)\r?\n---(?:\r?\n|$)", content, re.S)
        require(frontmatter is not None, f"Missing frontmatter: {relative}")
        if frontmatter:
            fields = {}
            current = None
            for line in frontmatter.group(1).splitlines():
                if not line.strip():
                    continue
                if line[0].isspace():
                    require(current is not None and "\t" not in line, f"Invalid header continuation: {relative}")
                    if current is not None:
                        fields[current].append(line.strip())
                    continue
                match = re.fullmatch(r"([a-z][a-z0-9_-]*):(?:[ ]+(.*))?", line)
                require(match is not None, f"Invalid header field: {relative}")
                if match:
                    current = match.group(1)
                    require(current not in fields, f"Duplicate header field: {relative}")
                    fields[current] = [match.group(2) or ""]
            actual = " ".join(fields.get("name", [])).strip()
            require(actual == name, f"Name mismatch: {relative}")
            require(actual not in names, f"Duplicate skill: {actual}")
            names.add(actual)
            description = " ".join(fields.get("description", [])).strip()
            # Supported subset: nonempty YAML plain scalar, optionally folded over
            # indented lines. Reject block/quoted/collection/tag/alias syntax rather
            # than pretending to implement a general-purpose YAML parser.
            scalar_ok = (bool(description) and len(description) <= 1024
                         and description[0].isalpha()
                         and not re.search(r":(?:\s|$)|\s#|[\t\r\n]", description)
                         and description.lower() not in {"null", "true", "false", "yes", "no", "on", "off"}
                         and not re.fullmatch(r"[+-]?[0-9.]+", description))
            require(scalar_ok, f"Invalid or empty description plain scalar: {relative}")
            if entry["origin"] == "project":
                require(set(fields) == {"name", "description"}, f"Unsupported project header fields: {relative}")
            require(bool(content[frontmatter.end():].strip()), f"Empty skill body: {relative}")
        routes = re.findall(r"\[[^\]\n]+\]\(([^)\s]+)\)", agent_text)
        require(relative in routes, f"Missing AGENTS route: {name}")

    locked_names = [package["name"] for package in lock["packages"]]
    require(len(locked_names) == len(set(locked_names)), "Duplicate upstream bundle")
    require(set(locked_names) == set(expected_bundles), "Registry upstream bundles differ from lock")

    for package in lock["packages"]:
        require(bool(re.fullmatch(r"[0-9a-f]{40}", package["commit"])), f"Invalid commit: {package['name']}")
        destination = safe_path(package["destination"], directory=True)
        if destination is None:
            continue
        if not destination.resolve().is_relative_to(skills.resolve()):
            require(False, "Upstream destination outside skills")
            continue
        require(bool(re.fullmatch(r"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", package["repository"])), "Invalid upstream repository")
        hashes = package["sha256"]
        require(isinstance(hashes, dict) and bool(hashes), f"Empty hashes: {package['name']}")
        files = {str(p.relative_to(root)) for p in destination.rglob("*") if p.is_file()}
        require(files == set(hashes), f"Upstream file set changed: {package['name']}")
        require(any(Path(p).name in {"LICENSE", "LICENSE.txt"} for p in hashes), f"Missing license: {package['name']}")
        for relative, expected in hashes.items():
            path = safe_path(relative)
            if path is None:
                continue
            if not path.is_relative_to(destination):
                require(False, f"Hash outside package: {relative}")
                continue
            require(bool(re.fullmatch(r"[0-9a-f]{64}", expected)), f"Invalid SHA-256: {relative}")
            require(hashlib.sha256(path.read_bytes()).hexdigest() == expected, f"Hash mismatch: {relative}")

    # No third-party executable assets are part of this installation.
    for path in skills.rglob("*"):
        require(not path.is_symlink(), f"Skill symlink: {path.relative_to(root)}")
        if path.is_file():
            require(not path.stat().st_mode & 0o111, f"Executable skill asset: {path.relative_to(root)}")
            require(path.suffix in {".md", ".txt", ".csv", ".json"} or path.name == "LICENSE",
                    f"Unexpected skill asset: {path.relative_to(root)}")

    documents = [root / "AGENTS.md", *sorted((root / "docs").glob("AI_SKILLS_*.md")), *sorted(skills.rglob("*.md"))]
    for document in documents:
        if safe_path(str(document.relative_to(root))) is None:
            continue
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
    except (OSError, ValueError, KeyError, TypeError, AttributeError, RuntimeError) as error:
        print(f"ERROR: Invalid skills configuration ({type(error).__name__})", file=sys.stderr)
        sys.exit(1)
