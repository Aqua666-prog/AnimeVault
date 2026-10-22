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
    print("::error::GOOGLE_SERVICES_JSON_B64 is required. Add the Base64 Android google-services.json for animevault-60b01 / com.sergey.animevault to repository Actions secrets.", file=sys.stderr)
    sys.exit(1)

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
    if len(clients) != 1:
        raise ValueError("Expected one Android client for com.sergey.animevault")
    project_number = str(config.get("project_info", {}).get("project_number", ""))
    if not project_number.isdigit():
        raise ValueError("Firebase project number is missing")
    mobile_app_id = clients[0].get("client_info", {}).get("mobilesdk_app_id", "")
    if not mobile_app_id.startswith("1:" + project_number + ":android:"):
        raise ValueError("Firebase Android application ID is missing or inconsistent")
    if not any(key.get("current_key") for key in clients[0].get("api_key", [])):
        raise ValueError("Firebase Android API key is missing")
    if not any(
        oauth.get("client_type") == 3 and
        str(oauth.get("client_id", "")).endswith(".apps.googleusercontent.com")
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
