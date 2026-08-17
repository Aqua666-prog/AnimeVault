#!/usr/bin/env bash
set -euo pipefail

bad_patterns='androidx\.annotation\.OptIn|animation\.core\.animateColorAsState|layout\.matchParentSize|Forward15|onClick = null|127\.0\.0\.1:18080'
if grep -RInE "$bad_patterns" app/src/main/java settings.gradle.kts 2>/dev/null; then
  echo "Known broken source pattern found" >&2
  exit 1
fi

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
for path in Path('app/src/main').rglob('*.xml'):
    ET.parse(path)
print('Android XML: OK')
PY

git diff --check
printf 'Source sanity: OK\n'
