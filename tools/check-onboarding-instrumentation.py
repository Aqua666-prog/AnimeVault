#!/usr/bin/env python3
"""Validate real AndroidJUnitRunner output and required screenshots; never run Android tools."""
import argparse
import re
import sys
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET

PNG_HEADER = b"\x89PNG\r\n\x1a\n"
PNG_END = b"\x00\x00\x00\x00IEND\xaeB\x60\x82"


def expected_tests(source):
    package = re.search(r"(?m)^package\s+([\w.]+)", source).group(1)
    suite = re.search(r"(?m)^class\s+(\w+)", source).group(1)
    names = re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source)
    if not names:
        raise ValueError("No onboarding @Test methods found")
    return {f"{package}.{suite}.{name}" for name in names}


def completed_tests(output):
    bundle = {}
    completed = []
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator:
                bundle[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(": ", 1)[1])
            if code <= 0:
                completed.append((bundle.get("class", ""), bundle.get("test", ""), code, bundle.get("stack", "")))
            bundle = {}
    return completed


def assess(output, source, screenshot_directory):
    expected = expected_tests(source)
    completed = completed_tests(output)
    errors = []
    identifiers = [f"{suite}.{name}" for suite, name, _, _ in completed]
    if set(identifiers) != expected or any(count != 1 for count in Counter(identifiers).values()):
        errors.append(f"Expected exactly these tests once: {sorted(expected)}; completed: {identifiers}")
    if any(code != 0 for _, _, code, _ in completed):
        errors.append("A test failed, crashed, was ignored or failed an assumption")
    summaries = re.findall(r"(?m)^\s*OK \((\d+) tests?\)\s*$", output)
    if summaries != [str(len(expected))]:
        errors.append("Missing or inconsistent AndroidJUnitRunner success summary")
    codes = re.findall(r"(?m)^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", output)
    if codes != ["-1"] or "INSTRUMENTATION_FAILED:" in output:
        errors.append("Instrumentation did not finish normally")
    screenshot_names = set(re.findall(r'screenshot\("([^"]+)"\)', source))
    if not screenshot_names:
        errors.append("No expected screenshot calls found in test source")
    for name in sorted(screenshot_names):
        for filename in (name + ".png", "preview-" + name + ".png"):
            path = screenshot_directory / filename
            if not path.is_file():
                errors.append(f"Missing screenshot: {filename}")
                continue
            data = path.read_bytes()
            if len(data) < 33 or not data.startswith(PNG_HEADER) or not data.endswith(PNG_END):
                errors.append(f"Invalid or incomplete PNG: {filename}")
    return completed, errors


def write_report(path, completed, errors, output):
    suite = ET.Element("testsuite", name="OnboardingFlowTest", tests=str(len(completed) + bool(errors)),
                       failures=str(sum(code != 0 for _, _, code, _ in completed) + bool(errors)))
    for classname, name, code, detail in completed:
        case = ET.SubElement(suite, "testcase", classname=classname, name=name)
        if code != 0:
            ET.SubElement(case, "failure", message=f"Instrumentation status {code}").text = detail
    if errors:
        case = ET.SubElement(suite, "testcase", classname="CI", name="instrumentation-and-screenshots")
        ET.SubElement(case, "failure", message=errors[0]).text = "\n".join(errors)
    ET.SubElement(suite, "system-out").text = output
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--screenshots", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    output = args.log.read_text(encoding="utf-8", errors="replace")
    completed, errors = assess(output, args.source.read_text(encoding="utf-8"), args.screenshots)
    write_report(args.report, completed, errors, output)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Validated {len(completed)} onboarding tests and all required PNG files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
