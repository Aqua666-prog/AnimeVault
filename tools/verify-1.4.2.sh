#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

grep -q 'versionCode = 37' app/build.gradle.kts
grep -q 'versionName = "1.4.2"' app/build.gradle.kts
grep -q 'name: AnimeVault-1.4.2' .github/workflows/build-apk.yml
grep -q 'Текущая версия разработки: \*\*1.4.2\*\*' README.md
grep -q 'const val History = "history"' app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt
grep -q 'fun VaultBottomNavigation' app/src/main/java/com/sergey/animevault/ui/navigation/VaultNavigationShell.kt
grep -q 'fun VaultNavigationRail' app/src/main/java/com/sergey/animevault/ui/navigation/VaultNavigationShell.kt
grep -q 'maxWidth >= 720.dp' app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt
grep -q 'abstract fun observeLocalHistory' app/src/main/java/com/sergey/animevault/data/db/LibraryDao.kt
grep -q 'fun observeHistory' app/src/main/java/com/sergey/animevault/data/repository/LibraryRepository.kt
grep -q 'class HistoryViewModel' app/src/main/java/com/sergey/animevault/ui/history/HistoryViewModel.kt
grep -q 'fun HistoryScreen' app/src/main/java/com/sergey/animevault/ui/history/HistoryScreen.kt

if grep -RIn 'LibrarySectionTabs' app/src/main/java/com/sergey/animevault/ui/library app/src/main/java/com/sergey/animevault/ui/online >/dev/null 2>&1; then
  echo 'Legacy LibrarySectionTabs still wired into root screens' >&2
  exit 1
fi

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
    Path('app/src/main/java/com/sergey/animevault/ui/navigation/VaultNavigationShell.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/history/HistoryViewModel.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/history/HistoryScreen.kt'),
    Path('app/src/test/java/com/sergey/animevault/ui/history/HistoryViewModelTest.kt'),
    Path('RELEASE_1.4.2.md'),
]
for path in required:
    assert path.exists() and path.stat().st_size > 100, path
print('AnimeVault 1.4.2 navigation/history sanity: OK')
PY
