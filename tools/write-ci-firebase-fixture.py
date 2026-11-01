#!/usr/bin/env python3
"""Nonfunctional Firebase fixture for secret-free PR compilation; never distribute this APK."""
import json
from pathlib import Path

config = {
    "project_info": {"project_number": "123456789000", "project_id": "animevault-ci-invalid", "storage_bucket": "animevault-ci-invalid.invalid"},
    "client": [{"client_info": {"mobilesdk_app_id": "1:123456789000:android:0123456789abcdef", "android_client_info": {"package_name": "com.sergey.animevault"}},
                "api_key": [{"current_key": "AIzaSyCIOnlyInvalidKey0000000000000000000"}],
                "oauth_client": [{"client_id": "123456789000-ci-invalid.apps.googleusercontent.com", "client_type": 3}]}],
    "configuration_version": "1",
}
if __name__ == "__main__":
    target = Path(__file__).resolve().parents[1] / "app/google-services.json"
    if target.exists():
        raise SystemExit("Refusing to overwrite existing Firebase configuration")
    target.write_text(json.dumps(config))
