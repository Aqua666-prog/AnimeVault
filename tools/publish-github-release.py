#!/usr/bin/env python3
"""Publish the validated main build using gh; never build, sign, or overwrite a public release."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

spec = importlib.util.spec_from_file_location("release_version", Path(__file__).with_name("verify-release-version.py"))
version = importlib.util.module_from_spec(spec)
spec.loader.exec_module(version)


class GitHub:
    def __init__(self, repository):
        if not re.fullmatch(r"[\w.-]+/[\w.-]+", repository):
            raise ValueError("Invalid GitHub repository")
        self.repository = repository

    def run(self, *args):
        # Token is inherited by gh, never put in command arguments or printed.
        return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout

    def api(self, path, *, method=None, **fields):
        args = ["api", f"repos/{self.repository}/{path}"]
        if method:
            args += ["--method", method]
        for key, value in fields.items():
            if type(value) is bool:
                args += ["-F", f"{key}={str(value).lower()}"]
            else:
                args += ["-f", f"{key}={value}"]
        return json.loads(self.run(*args))

    def optional_api(self, path):
        # Only an explicit HTTP 404 means absence. Auth/network failures must fail closed.
        try:
            return self.api(path)
        except subprocess.CalledProcessError as error:
            if "(HTTP 404)" in (error.stderr or ""):
                if path.startswith("releases/tags/"):
                    # The tag endpoint may hide a draft. The authenticated list includes it.
                    tag = path.removeprefix("releases/tags/")
                    pages = json.loads(self.run("api", f"repos/{self.repository}/releases?per_page=100",
                                                "--paginate", "--slurp"))
                    return next((release for page in pages for release in page
                                 if release.get("tag_name") == tag), None)
                return None
            raise


def publish(source, dist, repository, commit, github):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected a full commit SHA")
    metadata = json.loads((dist / "animevault-update.json").read_text())
    name, code = version.source_version(source)
    tag = f"v{name}"
    version.verify(source, tag, apk=dist / "AnimeVault.apk",
                   badging=(dist / "apk-badging.txt").read_text(), metadata=metadata)
    if github.api("commits/main")["sha"] != commit:
        print("Skipping superseded main build; the current main build will publish")
        return
    release = github.optional_api(f"releases/tags/{tag}")
    marker = f"<!-- animevault-release-ci:{commit} -->"
    if release and not release["draft"]:
        print(f"{tag} is already published; leaving its tag and assets unchanged")
        return
    if release and marker not in (release.get("body") or ""):
        raise ValueError("Refusing to modify a draft not owned by this exact CI commit")

    # Prevent an older version/code from replacing a newer release as 'latest'.
    latest = github.optional_api("releases/latest")
    if latest:
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / "latest.json"
            github.run("release", "download", latest["tag_name"], "--repo", repository,
                       "--pattern", "animevault-update.json", "--output", str(manifest))
            previous = json.loads(manifest.read_text())
            if type(previous.get("versionCode")) is not int or code <= previous["versionCode"]:
                raise ValueError("Version code must be newer than the latest published release")

    existing_tag = github.optional_api(f"git/ref/tags/{tag}")
    if existing_tag:
        if github.api(f"commits/{tag}")["sha"] != commit:
            raise ValueError("Existing tag points to another commit; refusing to move it")
    else:
        github.api("git/refs", ref=f"refs/tags/{tag}", sha=commit)
    if not release:
        github.run("release", "create", tag, "--repo", repository, "--verify-tag", "--draft",
                   "--title", f"AnimeVault {name} ({code})", "--generate-notes", "--notes", marker)
        release = github.optional_api(f"releases/tags/{tag}")
        if not release:
            raise ValueError("Created draft could not be found in the authenticated release list")
    current = github.api(f'releases/{release["id"]}')
    if not current["draft"] or marker not in (current.get("body") or ""):
        raise ValueError("Draft changed before upload; refusing to overwrite assets")
    # A retry may repair only this commit's still-private draft, never public assets.
    github.run("release", "upload", tag, str(dist / "AnimeVault.apk"),
               str(dist / "animevault-update.json"), "--repo", repository, "--clobber")
    github.api(f'releases/{release["id"]}', method="PATCH", draft=False, make_latest="true")
    print(f"Published {tag}: APK and update JSON from commit {commit}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("app/build.gradle.kts"))
    parser.add_argument("--dist", type=Path, default=Path("dist"))
    parser.add_argument("--print-tag", action="store_true")
    args = parser.parse_args()
    try:
        source = args.source.read_text()
        if args.print_tag:
            print("v" + version.source_version(source)[0])
            return
        # Refuse accidental execution from a feature branch/manual/local environment.
        if os.environ.get("GITHUB_EVENT_NAME") != "push" or os.environ.get("GITHUB_REF") != "refs/heads/main":
            raise ValueError("Automatic publishing is restricted to a main push")
        repository = os.environ["GITHUB_REPOSITORY"]
        publish(source, args.dist, repository, os.environ["GITHUB_SHA"], GitHub(repository))
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        # Do not dump subprocess output or remote API bodies into logs.
        parser.exit(1, f"Release publication failed: {type(error).__name__}: "
                    + (str(error) if isinstance(error, ValueError) else "check GitHub access and artifact inputs") + "\n")


if __name__ == "__main__":
    main()
