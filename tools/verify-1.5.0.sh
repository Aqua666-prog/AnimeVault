#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

grep -q 'versionCode = 41' app/build.gradle.kts
grep -q 'versionName = "1.5.0"' app/build.gradle.kts
grep -q 'name: AnimeVault-1.5.0' .github/workflows/build-apk.yml
grep -q 'verify-1.5.0.sh' .github/workflows/build-apk.yml
grep -q 'Текущая версия разработки: \*\*1.5.0\*\*' README.md

test -f RELEASE_1.5.0.md
test -f app/src/main/java/com/sergey/animevault/ui/navigation/VaultSharedTransitions.kt
test -f app/src/main/java/com/sergey/animevault/ui/statistics/StatisticsModel.kt
test -f app/src/main/java/com/sergey/animevault/ui/statistics/StatisticsViewModel.kt
test -f app/src/main/java/com/sergey/animevault/ui/statistics/StatisticsScreen.kt

grep -q 'SharedTransitionLayout' app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt
grep -q '@OptIn(ExperimentalSharedTransitionApi::class)' app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt
grep -q 'vaultSharedPoster' app/src/main/java/com/sergey/animevault/ui/title/UnifiedTitleUi.kt
grep -q 'VaultMotionMode.MINIMAL' app/src/main/java/com/sergey/animevault/ui/navigation/VaultSharedTransitions.kt
grep -q 'Routes.Statistics' app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt

grep -q 'fun observeAllTitleMetadata' app/src/main/java/com/sergey/animevault/data/repository/LibraryRepository.kt
grep -q 'abstract fun observeAllTitleMetadata' app/src/main/java/com/sergey/animevault/data/db/LibraryDao.kt

grep -q 'EqualizerSheet' app/src/main/java/com/sergey/animevault/ui/player/PlayerScreen.kt
grep -q 'SkipSettingsSheet' app/src/main/java/com/sergey/animevault/ui/player/PlayerScreen.kt
grep -q 'PlayerPauseInfoOverlay' app/src/main/java/com/sergey/animevault/ui/player/OnlinePlayerScreen.kt
! grep -Rqs 'EqualizerDialog\|SkipSettingsDialog' app/src/main/java/com/sergey/animevault/ui/player

grep -q 'KEY_OAUTH_STATE' app/src/main/java/com/sergey/animevault/data/anilist/AniListSyncRepository.kt
grep -q 'oauthStateMatches' app/src/main/java/com/sergey/animevault/data/anilist/AniListSyncRepository.kt
grep -q 'isTrustedProviderEndpointHost' app/src/main/java/com/sergey/animevault/data/online/ProviderEndpointRegistry.kt
grep -q 'providerEnabled' app/src/main/java/com/sergey/animevault/data/online/UnifiedOnlineProvider.kt
grep -q 'providerPriority' app/src/main/java/com/sergey/animevault/data/online/ProviderStreamRanker.kt
grep -q 'providerEnabled' app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogViewModel.kt

python3 - <<'PY'
from pathlib import Path
import re
root=Path('.')
app=(root/'app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt').read_text()
assert 'media-title/local/{titleId}' in app
assert 'media-title/online/{providerId}/{releaseId}' in app

# Database schema must remain unchanged in this release.
db_text='\n'.join(p.read_text() for p in (root/'app/src/main/java').rglob('*.kt') if 'Database' in p.name)
versions=[int(v) for v in re.findall(r'version\s*=\s*(\d+)', db_text)]
assert not versions or max(versions) == 4, versions

remote=(root/'app/src/main/java/com/sergey/animevault/data/online/ProviderEndpointRegistry.kt').read_text()
assert 'normalizeEndpointForProvider' in remote
assert 'candidate.endsWith(".$trusted")' in remote

print('AnimeVault 1.5.0 final polish/security sanity: OK')
PY
