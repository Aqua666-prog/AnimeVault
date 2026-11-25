#!/usr/bin/env python3
"""Record raw adb performance evidence from an installed release-like AnimeVault APK."""

import argparse
import csv
from datetime import datetime, timezone
from hashlib import sha256
import json
import math
import re
from shlex import quote
from pathlib import Path
import subprocess

PACKAGE = "com.sergey.animevault"
ACTIVITY = f"{PACKAGE}/.MainActivity"


def startup_metrics(output):
    values = dict(re.findall(r"^(LaunchState|Status|ThisTime|TotalTime|WaitTime):\s*(.*?)\s*$", output, re.M))
    return {
        "state": values.get("LaunchState"),
        "status": values.get("Status"),
        "total_ms": int(values["TotalTime"]) if values.get("TotalTime", "").isdigit() else None,
        "wait_ms": int(values["WaitTime"]) if values.get("WaitTime", "").isdigit() else None,
    }


def frame_metrics(output):
    durations_ms = []
    deadline_frames = missed = 0
    for profile in output.split("---PROFILEDATA---")[1::2]:
        rows = csv.DictReader(profile.strip().splitlines())
        for row in rows:
            try:
                if int(row["Flags"]) != 0:
                    continue
                start = int(row["IntendedVsync"])
                end = int(row["FrameCompleted"])
                if not 0 < start <= end < 2**63 - 1:
                    continue
                durations_ms.append((end - start) / 1_000_000)
                deadline = int(row.get("FrameDeadline") or 0)
                if start < deadline < 2**63 - 1:
                    deadline_frames += 1
                    missed += end > deadline
            except (KeyError, ValueError, TypeError):
                continue
    durations_ms.sort()
    percentiles = {
        f"p{int(p * 100)}_ms": durations_ms[math.ceil(p * len(durations_ms)) - 1] if durations_ms else None
        for p in (0.50, 0.95, 0.99)
    }
    return dict(percentiles, sample_count=len(durations_ms), frames_with_deadline=deadline_frames,
                missed_deadline=missed, missed_fraction=missed / deadline_frames if deadline_frames else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--preset", choices=("legacy", "performance", "balanced", "cinematic-ultra"), required=True)
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
    installed_paths = adb("shell", "pm", "path", PACKAGE).splitlines()
    if len(installed_paths) != 1 or not installed_paths[0].startswith("package:"):
        parser.error("A single standalone AnimeVault APK must be installed")
    installed_path = installed_paths[0].removeprefix("package:")
    installed_hash = adb("shell", "sha256sum", quote(installed_path)).split()[0]
    if installed_hash != apk_hash.hexdigest():
        parser.error("Installed APK SHA-256 differs from --apk")

    version_lines = adb("shell", "dumpsys", "package", PACKAGE).splitlines()
    evidence = {
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "sdk": adb("shell", "getprop", "ro.build.version.sdk"),
        "model": adb("shell", "getprop", "ro.product.model"),
        "installed_apk": installed_path,
        "installed_apk_sha256": installed_hash,
        "battery_saver": adb("shell", "settings", "get", "global", "low_power"),
        "display_refresh_hz": adb("shell", "settings", "get", "system", "peak_refresh_rate"),
        "local_apk_bytes": args.apk.stat().st_size,
        "local_apk_sha256": apk_hash.hexdigest(),
        "installed_app_version": [
            line.strip() for line in version_lines if line.strip().startswith(("versionName=", "versionCode="))
        ],
        "operator_selected_preset": args.preset,
        "scenario": args.scenario,
    }
    if args.scenario == "startup":
        samples = []
        for _ in range(args.iterations):
            cold = adb("shell", "am", "start", "-W", "-S", "-n", ACTIVITY)
            adb("shell", "input", "keyevent", "KEYCODE_HOME")
            foreground = adb("shell", "am", "start", "-W", "-n", ACTIVITY)
            samples.append({
                "cold_request": cold,
                "foreground_request": foreground,
                "cold_observed": startup_metrics(cold),
                "foreground_observed": startup_metrics(foreground),
            })
        evidence["samples"] = samples
    else:
        adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
        input("Run the documented interaction now, then press Enter to capture evidence. ")
    evidence["frame_stats"] = adb("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")
    evidence["frames"] = frame_metrics(evidence["frame_stats"])
    evidence["memory"] = adb("shell", "dumpsys", "meminfo", PACKAGE)
    pss = re.search(r"TOTAL PSS:\s+(\d+)", evidence["memory"])
    evidence["total_pss_kib"] = int(pss.group(1)) if pss else None
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(evidence, output, ensure_ascii=False, indent=2)
        output.write("\n")


if __name__ == "__main__":
    main()
