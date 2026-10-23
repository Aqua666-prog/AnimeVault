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
import re
root = pathlib.Path('.')
gradle = (root / 'app/build.gradle.kts').read_text()
code = re.search(r'^\s*versionCode\s*=\s*([1-9][0-9]*)\s*$', gradle, re.MULTILINE)
assert code and int(code[1]) <= 2_147_483_647, 'Invalid Android versionCode'
name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', gradle, re.MULTILINE)
assert name and name[1].strip() and len(name[1]) <= 80, 'Invalid Android versionName'
db = (root / 'app/src/main/java/com/sergey/animevault/data/db/AnimeVaultDatabase.kt').read_text()
assert 'version = 8' in db
assert 'MIGRATION_7_8' in db
print('Final version/database sanity: OK')
PY

echo "Final source verification: OK"
