#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

grep -q 'versionCode = 36' app/build.gradle.kts
grep -q 'versionName = "1.4.1"' app/build.gradle.kts
grep -q 'name: AnimeVault-1.4.1' .github/workflows/build-apk.yml
grep -q 'Текущая версия разработки: \*\*1.4.1\*\*' README.md
grep -q 'fun VaultInteractivePanel' app/src/main/java/com/sergey/animevault/ui/design/VaultSurfaces.kt
grep -q 'private fun HomeContinueHero' app/src/main/java/com/sergey/animevault/ui/home/HomeScreen.kt
grep -q 'private fun SourcePickerSheet' app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt
grep -q 'val healthStates: Map<String, ProviderHealthState>' app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogViewModel.kt
grep -q 'repository.healthStates.collect' app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogViewModel.kt

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
    Path('app/src/main/java/com/sergey/animevault/ui/home/HomeScreen.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt'),
    Path('RELEASE_1.4.1.md'),
]
for path in required:
    assert path.exists() and path.stat().st_size > 100, path
print('AnimeVault 1.4.1 UI consolidation sanity: OK')
PY
