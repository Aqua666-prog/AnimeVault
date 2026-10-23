#!/usr/bin/env python3
"""Create public updater metadata from an APK and its aapt dump badging output.

This script never builds, signs, uploads, tags or publishes anything.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

PACKAGE = "com.sergey.animevault"
MAX_APK_BYTES = 300 * 1024 * 1024


def prepare(apk: Path, badging: str) -> dict:
    package_line = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    values = dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)='([^']*)'", package_line))
    if values.get("name") != PACKAGE:
        raise ValueError("APK must use package com.sergey.animevault")
    raw_code = values.get("versionCode", "")
    if not re.fullmatch(r"[1-9][0-9]*", raw_code) or int(raw_code) > 2_147_483_647:
        raise ValueError("APK must have a positive Android versionCode")
    version = values.get("versionName", "")
    if not version.strip() or len(version) > 80:
        raise ValueError("APK must have a versionName")
    sdk_match = re.search(r"^sdkVersion:'([0-9]+)'$", badging, re.MULTILINE)
    if not sdk_match or not 24 <= int(sdk_match[1]) <= 2_147_483_647:
        raise ValueError("APK must declare minSdk >= 24")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,140}\.apk", apk.name):
        raise ValueError("Unsupported APK asset name")
    if not apk.is_file() or not 0 < apk.stat().st_size <= MAX_APK_BYTES:
        raise ValueError("APK is missing, empty or too large")
    digest = hashlib.sha256()
    with apk.open("rb") as source:
        for block in iter(lambda: source.read(64 * 1024), b""):
            digest.update(block)
    return {
        "schemaVersion": 1,
        "packageName": PACKAGE,
        "versionCode": int(raw_code),
        "versionName": version,
        "minSdk": int(sdk_match[1]),
        "apkFileName": apk.name,
        "sha256": digest.hexdigest(),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--badging", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    metadata = prepare(args.apk, args.badging.read_text(encoding="utf-8"))
    args.output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared update metadata: {metadata['versionName']} ({metadata['versionCode']})")


if __name__ == "__main__":
    main()
