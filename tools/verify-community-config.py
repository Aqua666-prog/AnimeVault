#!/usr/bin/env python3
"""Check build configuration without sending requests or printing API key values."""
import argparse
import os
import re
import sys

PROJECT_URL = "https://kgvkxocbkklisqrqmcjx.supabase.co"

def inspect_config(environment):
    url = environment.get("SUPABASE_URL", "").strip() or PROJECT_URL
    key = environment.get("SUPABASE_PUBLISHABLE_KEY", "").strip()
    flag = environment.get("COMMUNITY_ENABLED", "").strip().lower()
    valid_key = bool(re.fullmatch(r"sb_publishable_[A-Za-z0-9_-]+", key))
    enabled = (flag == "true") if flag else valid_key
    return enabled, url == PROJECT_URL and valid_key

if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-enabled",action="store_true",help="Reject a distribution APK with Community disabled")
    arguments=parser.parse_args()
    enabled, configured = inspect_config(os.environ)
    if arguments.require_enabled and not enabled:
        sys.exit("Distribution build must enable Community. Set COMMUNITY_ENABLED=true; no APK will be presented as connected while disabled.")
    if enabled and not configured:
        sys.exit("Community enabled but public project configuration is invalid; refusing a misleading build.")
    print("Community: " + ("enabled; public build configuration valid" if enabled else "disabled explicitly or publishable key missing"))
