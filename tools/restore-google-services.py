#!/usr/bin/env python3
"""Restore Firebase Android config from CI secret without logging its contents."""
import base64
import binascii
import json
import os
from pathlib import Path
import sys

target = Path(__file__).resolve().parent.parent / "app" / "google-services.json"
encoded = os.environ.get("GOOGLE_SERVICES_JSON_B64", "").strip()
if not encoded:
    print("::warning::GOOGLE_SERVICES_JSON_B64 is not set; building with Google Sign-In unavailable.")
    sys.exit(0)

try:
    raw = base64.b64decode("".join(encoded.split()), validate=True)
    config = json.loads(raw)
    if config.get("project_info", {}).get("project_id") != "animevault-60b01":
        raise ValueError("Unexpected Firebase project")
    clients = [
        client for client in config.get("client", [])
        if client.get("client_info", {}).get("android_client_info", {}).get("package_name")
        == "com.sergey.animevault"
    ]
    if not clients:
        raise ValueError("Android client com.sergey.animevault is missing")
    if not any(
        oauth.get("client_type") == 3 and oauth.get("client_id")
        for client in clients for oauth in client.get("oauth_client", [])
    ):
        raise ValueError("Web OAuth client is missing; download a fresh config after enabling Google Sign-In")
except (ValueError, TypeError, AttributeError, binascii.Error):
    # Do not echo parser errors: they may contain portions of the supplied file.
    print("::error::Invalid Firebase config. Use the Android google-services.json for animevault-60b01 with a Web OAuth client.", file=sys.stderr)
    sys.exit(1)

target.parent.mkdir(parents=True, exist_ok=True)
descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(descriptor, "wb") as output:
    output.write(raw)
print("Firebase Android configuration restored.")
