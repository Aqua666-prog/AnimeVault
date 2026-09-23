#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bash tools/verify-source.sh
python3 tools/verify-provider-config.py

grep -q 'versionCode = 50' app/build.gradle.kts
grep -q 'versionName = "2.0.0"' app/build.gradle.kts
grep -q 'version = 8' app/src/main/java/com/sergey/animevault/data/db/AnimeVaultDatabase.kt
grep -q 'MIGRATION_7_8' app/src/main/java/com/sergey/animevault/data/db/AnimeVaultDatabase.kt

grep -q 'DOWNLOAD_PROBE' app/src/main/java/com/sergey/animevault/data/online/ProviderHealthTracker.kt
grep -q 'ProviderHealthChannel.DOWNLOAD' app/src/main/java/com/sergey/animevault/data/online/ProviderStreamRanker.kt
grep -q 'trustedHostSuffixes' app/src/main/java/com/sergey/animevault/data/online/ProviderEndpointRegistry.kt
grep -q 'MAX_HEALTH_DOWNLOAD_PROBES' app/src/main/java/com/sergey/animevault/data/online/OnlineRepository.kt

grep -q 'class DownloadRouteHealthTracker' app/src/main/java/com/sergey/animevault/data/download/DownloadRouteHealthTracker.kt
grep -q 'class DownloadProgressEstimator' app/src/main/java/com/sergey/animevault/data/download/DownloadDiagnostics.kt
grep -q 'object DownloadedMediaVerifier' app/src/main/java/com/sergey/animevault/data/download/DownloadedMediaVerifier.kt
grep -q 'MAX_RESOLUTION_ROUNDS' app/src/main/java/com/sergey/animevault/data/download/DownloadWorker.kt
grep -q 'withKnownCdnAlternatives' app/src/main/java/com/sergey/animevault/data/download/DownloadWorker.kt
grep -q 'hlsParallelism' app/src/main/java/com/sergey/animevault/data/download/NativeDownloadEngine.kt
grep -q 'NativeDownloadProbeResult' app/src/main/java/com/sergey/animevault/data/download/NativeDownloadEngine.kt

grep -q 'Application token YummyAnime' app/src/main/java/com/sergey/animevault/ui/settings/SettingsScreen.kt
grep -q 'Автоматически снижать качество' app/src/main/java/com/sergey/animevault/ui/settings/SettingsScreen.kt

test -f app/src/test/java/com/sergey/animevault/data/download/DownloadDiagnosticsTest.kt
test -f app/src/test/java/com/sergey/animevault/data/download/DownloadRouteHealthTrackerTest.kt
test -f RELEASE_2.0.0.md
test -f BUILD_READY_2.0.0.md

! grep -RInE '<<<<<<<|=======|>>>>>>>' app/src/main app/src/test >/dev/null

echo 'AnimeVault 2.0.0 source/provider/download sanity: OK'
