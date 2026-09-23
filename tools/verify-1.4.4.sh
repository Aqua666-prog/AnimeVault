#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

grep -q 'versionCode = 39' app/build.gradle.kts
grep -q 'versionName = "1.4.4"' app/build.gradle.kts
grep -q 'name: AnimeVault-1.4.4' .github/workflows/build-apk.yml
grep -q 'Текущая версия разработки: \*\*1.4.4\*\*' README.md

grep -q 'enum class LibraryLayout' app/src/main/java/com/sergey/animevault/ui/library/LibraryViewModel.kt
grep -q 'POSTER_GRID' app/src/main/java/com/sergey/animevault/ui/library/LibraryViewModel.kt
grep -q 'COMPACT_GRID' app/src/main/java/com/sergey/animevault/ui/library/LibraryViewModel.kt
grep -q 'LibraryLayout.LIST' app/src/main/java/com/sergey/animevault/ui/library/LibraryScreen.kt
grep -q 'fun LibraryLayoutMenu' app/src/main/java/com/sergey/animevault/ui/library/LibraryScreen.kt
grep -q 'fun LibraryListCard' app/src/main/java/com/sergey/animevault/ui/library/LibraryScreen.kt

grep -q 'class UiPreferences' app/src/main/java/com/sergey/animevault/ui/preferences/UiPreferences.kt
grep -q 'fun mergeSearchHistory' app/src/main/java/com/sergey/animevault/ui/preferences/SearchHistory.kt
grep -q 'searchHistory:' app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogViewModel.kt
grep -q 'recentlyOpened:' app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogViewModel.kt
grep -q 'fun SearchAssistPanel' app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt
grep -q 'Недавние запросы' app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt
grep -q 'Недавно открывали' app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt

grep -q 'episodeTechnicalLabel' app/src/main/java/com/sergey/animevault/ui/title/TitleDetailScreen.kt
grep -q 'bestQuality' app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleScreen.kt
grep -q 'class SearchHistoryTest' app/src/test/java/com/sergey/animevault/ui/preferences/SearchHistoryTest.kt

grep -q 'media-title/local/{titleId}' app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt
grep -q 'media-title/online/{providerId}/{releaseId}' app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt

python3 - <<'PY'
from pathlib import Path
for path in [
    Path('app/src/main/java/com/sergey/animevault/ui/library/LibraryScreen.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/library/LibraryViewModel.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogViewModel.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/title/TitleDetailScreen.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleScreen.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/preferences/UiPreferences.kt'),
]:
    text = path.read_text()
    assert text.count('{') == text.count('}'), path
    assert text.count('(') == text.count(')'), path

assert Path('RELEASE_1.4.4.md').exists()
print('AnimeVault 1.4.4 library/online polish sanity: OK')
PY
