#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bash tools/verify-source.sh
python3 tools/verify-provider-config.py

if [ -d ".git" ]; then
  git diff --check
else
  echo "Skipping git diff --check: no git working tree"
fi

python3 - <<'PY'
import pathlib
root = pathlib.Path('.')
gradle = (root / 'app/build.gradle.kts').read_text()
assert 'versionCode = 50' in gradle
assert 'versionName = "2.0.0"' in gradle
db = (root / 'app/src/main/java/com/sergey/animevault/data/db/AnimeVaultDatabase.kt').read_text()
assert 'version = 8' in db
assert 'MIGRATION_7_8' in db
print('Final version/database sanity: OK')
PY

echo "Final source verification: OK"
