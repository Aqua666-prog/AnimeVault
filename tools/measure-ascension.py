#!/usr/bin/env python3
"""Record raw adb performance evidence from an installed release-like AnimeVault APK."""

import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import subprocess

PACKAGE = "com.sergey.animevault"
ACTIVITY = f"{PACKAGE}/.MainActivity"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--preset", choices=("performance", "balanced", "cinematic-ultra"), required=True)
    parser.add_argument("--scenario", choices=("startup", "interaction"), required=True)
    parser.add_argument("--iterations", type=int, default=10)
    args = parser.parse_args()
    if args.iterations < 1:
        parser.error("--iterations must be positive")
    if args.output.exists():
        parser.error("--output already exists")

    def adb(*command):
        target = ["-s", args.serial] if args.serial else []
        return subprocess.check_output([args.adb, *target, *command], text=True).strip()

    apk_hash = sha256()
    with args.apk.open("rb") as apk:
        while chunk := apk.read(1024 * 1024):
            apk_hash.update(chunk)
    version_lines = adb("shell", "dumpsys", "package", PACKAGE).splitlines()
    evidence = {
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "sdk": adb("shell", "getprop", "ro.build.version.sdk"),
        "model": adb("shell", "getprop", "ro.product.model"),
        "installed_apk": adb("shell", "pm", "path", PACKAGE),
        "local_apk_bytes": args.apk.stat().st_size,
        "local_apk_sha256": apk_hash.hexdigest(),
        "installed_app_version": [
            line.strip() for line in version_lines if line.strip().startswith(("versionName=", "versionCode="))
        ],
        "operator_selected_preset": args.preset,
        "scenario": args.scenario,
    }
    if not evidence["installed_apk"].startswith("package:"):
        parser.error("AnimeVault is not installed on the target device")

    if args.scenario == "startup":
        samples = []
        for _ in range(args.iterations):
            cold = adb("shell", "am", "start", "-W", "-S", "-n", ACTIVITY)
            adb("shell", "input", "keyevent", "KEYCODE_HOME")
            foreground = adb("shell", "am", "start", "-W", "-n", ACTIVITY)
            samples.append({"cold_request": cold, "resume_request": foreground})
        evidence["samples"] = samples
    else:
        adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
        input("Run the documented interaction now, then press Enter to capture evidence. ")
    evidence["frame_stats"] = adb("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")
    evidence["memory"] = adb("shell", "dumpsys", "meminfo", PACKAGE)
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(evidence, output, ensure_ascii=False, indent=2)
        output.write("\n")


if __name__ == "__main__":
    main()
