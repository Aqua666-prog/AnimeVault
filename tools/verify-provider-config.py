#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "provider-config.json"
MAX_ENDPOINTS = 8
MAX_SUFFIXES = 8
MAX_LIFETIME_MS = 30 * 24 * 60 * 60 * 1000

TRUST = {
    "aniliberty": {"aniliberty.top", "anilibria.top"},
    "kodik": {"kodik-api.com"},
    "animelib": {"cdnlibs.org", "lib.social"},
    "animevost": {"animevost.org"},
    "jutsu": {"jut.su"},
    "dreamerscast": {"dreamerscast.com"},
    "animedia": {"amd.online"},
    "animeon": {"animeon.club"},
    "sameband": {"sameband.studio"},
    "animebest": {"animebesst.org"},
    "yummy": {"yani.tv"},
}


def normalized_suffix(value: str) -> str:
    value = value.strip().strip(".").lower()
    assert value and "." in value and "/" not in value and ":" not in value and "@" not in value, value
    return value


def inside(host: str, suffixes: set[str]) -> bool:
    host = host.strip().strip(".").lower()
    return any(host == suffix or host.endswith("." + suffix) for suffix in suffixes)


config = json.loads(CONFIG.read_text(encoding="utf-8"))
assert config.get("schemaVersion") == 2, "provider config must use schemaVersion 2"
assert int(config.get("configVersion", 0)) > 0
issued = int(config.get("issuedAt", 0))
expires = int(config.get("expiresAt", 0))
assert issued > 0 and expires > issued
assert expires - issued <= MAX_LIFETIME_MS, "remote config lifetime exceeds 30 days"

providers = config.get("providers") or []
assert providers, "provider list is empty"
ids = [str(item.get("id", "")).strip() for item in providers]
assert all(ids) and len(ids) == len(set(ids)), "blank or duplicate provider id"
assert set(ids).issubset(TRUST), f"unknown provider ids: {set(ids) - set(TRUST)}"
assert any(item.get("enabled", True) for item in providers), "every provider is disabled"

for item in providers:
    provider_id = item["id"].strip()
    apk_trust = TRUST[provider_id]
    requested = [normalized_suffix(v) for v in item.get("trustedHostSuffixes", [])]
    assert len(requested) <= MAX_SUFFIXES
    effective = set(requested) if requested else apk_trust
    assert all(any(s == root or s.endswith("." + root) for root in apk_trust) for s in effective), (
        provider_id,
        effective,
        apk_trust,
    )
    endpoints = item.get("endpoints", [])
    assert len(endpoints) <= MAX_ENDPOINTS
    for endpoint in endpoints:
        parsed = urlparse(endpoint)
        assert parsed.scheme == "https" and parsed.hostname and parsed.path in ("", "/") and not parsed.query and not parsed.fragment, endpoint
        assert inside(parsed.hostname, effective), f"{provider_id}: untrusted endpoint {endpoint} for {effective}"
    priority = int(item.get("priority", 0))
    assert -1000 <= priority <= 1000

print(f"Provider config v2: OK ({len(providers)} providers, configVersion={config['configVersion']})")
