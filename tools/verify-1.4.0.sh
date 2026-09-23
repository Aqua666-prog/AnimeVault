#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

grep -q 'versionCode = 35' app/build.gradle.kts
grep -q 'versionName = "1.4.0"' app/build.gradle.kts
grep -q 'name: AnimeVault-1.4.0' .github/workflows/build-apk.yml
grep -q 'object VaultSpacing' app/src/main/java/com/sergey/animevault/ui/design/VaultTokens.kt
grep -q 'object VaultMotion' app/src/main/java/com/sergey/animevault/ui/design/VaultTokens.kt
grep -q 'enum class VaultSurfaceRole' app/src/main/java/com/sergey/animevault/ui/design/VaultSurfaces.kt
grep -q 'fun VaultPanel' app/src/main/java/com/sergey/animevault/ui/design/VaultSurfaces.kt
grep -q 'fun VaultLogoMark' app/src/main/java/com/sergey/animevault/ui/components/AnimeBrandTitle.kt
grep -q 'fun VaultFilterChip' app/src/main/java/com/sergey/animevault/ui/components/VaultComponents.kt
grep -q 'Текущая версия разработки: \*\*1.4.0\*\*' README.md

if grep -RIn 'REGENERATE_WITH_ROOM_EXPORT_SCHEMA' app/schemas >/dev/null 2>&1; then
  echo 'Invalid placeholder Room schema found' >&2
  exit 1
fi

if grep -RInE '<<<<<<<|=======|>>>>>>>' app/src/main app/src/test >/dev/null 2>&1; then
  echo 'Merge marker found' >&2
  exit 1
fi

python3 - <<'PY'
from pathlib import Path
required = [
    Path('app/src/main/java/com/sergey/animevault/ui/design/VaultTokens.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/design/VaultSurfaces.kt'),
    Path('RELEASE_1.4.0.md'),
]
for path in required:
    assert path.exists() and path.stat().st_size > 100, path
print('AnimeVault 1.4.0 design-system sanity: OK')
PY
