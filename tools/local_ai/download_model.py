#!/usr/bin/env python3
"""Download the audited official revision for SAF import or a real runtime probe."""
import hashlib
import json
from pathlib import Path
import sys
from urllib.request import urlopen

manifest_path = Path(__file__).resolve().parents[2] / "app/src/main/assets/local-ai/manifest.json"
manifest = json.loads(manifest_path.read_text())
output = Path(sys.argv[1] if len(sys.argv) > 1 else "pocket-model")
output.mkdir(parents=True, exist_ok=True)
for spec in manifest["files"]:
    url = f'https://huggingface.co/{manifest["repository"]}/resolve/{manifest["revision"]}/{spec["name"]}'
    target = output / spec["name"]
    partial = target.with_suffix(target.suffix + ".part")
    digest = hashlib.sha256()
    count = 0
    with urlopen(url, timeout=60) as response, partial.open("wb") as stream:
        while chunk := response.read(65536):
            count += len(chunk)
            if count > spec["size"]:
                raise ValueError(f"Oversized file: {url}")
            digest.update(chunk)
            stream.write(chunk)
    if count != spec["size"] or digest.hexdigest() != spec["sha256"]:
        raise ValueError(f"Integrity failure: {url}")
    partial.replace(target)
    print(spec["name"], count, digest.hexdigest(), flush=True)
print("Verified official model:", output.resolve())
