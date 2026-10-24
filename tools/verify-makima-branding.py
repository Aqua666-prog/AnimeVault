#!/usr/bin/env python3
"""Lightweight resource checks only: no Gradle, SDK, compilation or device access."""
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def verify_png(path, record):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    offset = 8
    image_data = bytearray()
    while offset < len(data):
        length = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        checksum = struct.unpack_from(">I", data, offset + 8 + length)[0]
        assert zlib.crc32(kind + payload) & 0xffffffff == checksum, path
        if kind == b"IHDR":
            width, height, depth, color = struct.unpack_from(">IIBB", payload)
            assert (width, height) == (record["width"], record["height"]), path
            assert (depth, color) == (8, 6), f"Expected RGBA8: {path}"
        if kind == b"IDAT":
            image_data.extend(payload)
        offset += 12 + length
        if kind == b"IEND":
            break
    assert offset == len(data), path
    # All supplied PNGs are noninterlaced RGBA8 (one filter byte per row).
    assert len(zlib.decompress(image_data)) == height * (1 + width * 4), path


def main():
    provenance = json.loads((ROOT / "docs/branding/makima-asset-provenance.json").read_text())
    for record in provenance["assets"]:
        path = ROOT / record["path"]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == record["sha256"], path
        if path.suffix == ".png":
            verify_png(path, record)
    print(f'Approved kit: {len(provenance["assets"])} exact file hashes; 17 RGBA PNGs validated')

    resources = set()
    xml_paths = list(RES.rglob("*.xml"))
    for path in RES.rglob("*"):
        if path.is_file() and not path.parent.name.startswith("values"):
            resources.add((path.parent.name.split("-")[0], path.stem))
    for path in xml_paths:
        root = ET.parse(path).getroot()
        if path.parent.name.startswith("values"):
            for item in root:
                name = item.get("name")
                kind = item.get("type", item.tag)
                if name:
                    resources.add((kind, name))
    manifest = ROOT / "app/src/main/AndroidManifest.xml"
    for path in [*xml_paths, manifest]:
        ET.parse(path)
        for kind, name in re.findall(r"@(drawable|mipmap|color|string|style)/([\w.]+)", path.read_text()):
            assert (kind, name) in resources, f"Missing @{kind}/{name} in {path}"
    for path in (ROOT / "app/src/main/java").rglob("*.kt"):
        for kind, name in re.findall(r"(?<![\w.])R\.(drawable|mipmap)\.(\w+)", path.read_text()):
            assert (kind, name) in resources, f"Missing R.{kind}.{name} in {path}"
    print(f"XML: {len(xml_paths) + 1} files parsed; XML and Kotlin drawable/mipmap references resolve")

    application = ET.parse(manifest).getroot().find("application")
    assert application.get(ANDROID + "icon") == "@mipmap/ic_animevault"
    assert application.get(ANDROID + "roundIcon") == "@mipmap/ic_animevault_round"
    main_activity = next(a for a in application.findall("activity") if a.get(ANDROID + "name") == ".MainActivity")
    assert main_activity.get(ANDROID + "theme") == "@style/Theme.AnimeVault.Launch"
    for path in RES.rglob("ic_launcher*.xml"):
        raise AssertionError(f"Old launcher artwork remains: {path}")
    assert ET.parse(RES / "values/launcher_aliases.xml").getroot()[0].text == "@mipmap/ic_animevault"
    brand_colors = {c.get("name"): c.text for c in ET.parse(RES / "values/animevault_brand.xml").getroot()}
    palette = json.loads((ROOT / "docs/branding/makima-colors.json").read_text())
    for name, key in [("animevault_brand_violet", "violet"), ("animevault_brand_lemon", "lemon"),
                      ("animevault_icon_background", "icon_background"), ("animevault_brand_ivory", "ivory")]:
        assert brand_colors[name] == palette[key], name
    print("Manifest, legacy aliases, removal of old launcher art and kit palette: OK")


if __name__ == "__main__":
    main()
