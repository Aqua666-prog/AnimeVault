#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bash tools/verify-source.sh

if [ -d ".git" ]; then
  git diff --check
else
  echo "Skipping git diff --check: no git working tree"
fi

python3 - <<'PY'
import json, pathlib, re
root = pathlib.Path('.')
config = json.loads((root / 'provider-config.json').read_text())
assert config.get('schemaVersion') == 1
providers = config.get('providers') or []
ids = [p['id'].strip() for p in providers]
assert ids and len(ids) == len(set(ids))
assert any(p.get('enabled', True) for p in providers)
for provider in providers:
    assert len(provider.get('endpoints', [])) <= 8
    for endpoint in provider.get('endpoints', []):
        assert endpoint.startswith('https://')

gradle = (root / 'app/build.gradle.kts').read_text()
assert 'versionCode = 41' in gradle
assert 'versionName = "1.5.0"' in gradle
print('Final config/version sanity: OK')
PY

echo "Final source verification: OK"
