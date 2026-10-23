#!/usr/bin/env python3
"""Validate release source, APK badging and updater JSON; never invoke Android tools or build an APK."""
import argparse
import importlib.util
import json
import re
from pathlib import Path

spec = importlib.util.spec_from_file_location("prepare_update_manifest", Path(__file__).with_name("prepare-update-manifest.py"))
manifest_generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manifest_generator)


def source_version(source: str) -> tuple[str, int]:
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', source, re.MULTILINE)
    codes = re.findall(r'^\s*versionCode\s*=\s*([1-9][0-9]*)\s*$', source, re.MULTILINE)
    if len(names) != 1 or len(codes) != 1 or int(codes[0]) > 2_147_483_647:
        raise ValueError("Expected exactly one Android versionName and versionCode in source")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", names[0]):
        raise ValueError("Expected a stable x.y.z versionName")
    return names[0], int(codes[0])


def verify(source: str, release_tag: str | None = None, *, apk: Path | None = None,
           badging: str | None = None, metadata: dict | None = None) -> tuple[str, int]:
    version_name, version_code = source_version(source)
    if release_tag is not None:
        # No stripping/suffix removal: a mismatched or missing release target must fail closed.
        if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", release_tag):
            raise ValueError("Release tag must have the exact form vX.Y.Z")
        if release_tag != f"v{version_name}":
            raise ValueError(f"Release tag {release_tag} does not match source version {version_name}")
    if any(value is not None for value in (apk, badging, metadata)):
        if apk is None or badging is None or metadata is None:
            raise ValueError("APK, badging and JSON must be verified together")
        if not isinstance(metadata, dict):
            raise ValueError("Updater JSON must be an object")
        expected = manifest_generator.prepare(apk, badging)
        if (expected["versionName"], expected["versionCode"]) != (version_name, version_code):
            raise ValueError("APK version does not match source version")
        for key, value in expected.items():
            if type(metadata.get(key)) is not type(value) or metadata.get(key) != value:
                raise ValueError(f"Updater JSON field {key} does not match built APK")
    return version_name, version_code


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--release-tag", default=None)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--badging", type=Path)
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    try:
        name, code = verify(
            args.source.read_text(encoding="utf-8"), args.release_tag,
            apk=args.apk,
            badging=args.badging.read_text(encoding="utf-8") if args.badging else None,
            metadata=json.loads(args.manifest.read_text(encoding="utf-8")) if args.manifest else None,
        )
    except (ValueError, OSError) as error:
        parser.exit(1, f"Release validation failed: {error}\n")
    scope = "APK/JSON/source" if args.apk else "source"
    print(f"Verified {scope}: {name} ({code}); release tag: {args.release_tag or 'not a release build'}")


if __name__ == "__main__":
    main()
