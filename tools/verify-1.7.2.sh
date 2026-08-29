#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

grep -q 'versionCode = 45' app/build.gradle.kts
grep -q 'versionName = "1.7.2"' app/build.gradle.kts
grep -q 'name: AnimeVault-1.7.2' .github/workflows/build-apk.yml
grep -q 'MIGRATION_5_6' app/src/main/java/com/sergey/animevault/data/db/AnimeVaultDatabase.kt
grep -q 'MIGRATION_6_7' app/src/main/java/com/sergey/animevault/data/db/AnimeVaultDatabase.kt
grep -q 'fun observeAll(): Flow<List<DownloadEntity>>' app/src/main/java/com/sergey/animevault/data/download/DownloadEntity.kt
! grep -q 'runBlocking' app/src/main/java/com/sergey/animevault/data/download/DownloadStore.kt
grep -q 'PlayerOrientationEffect()' app/src/main/java/com/sergey/animevault/ui/player/PlayerScreen.kt
grep -q 'HTTP_RANGE_NOT_SATISFIABLE' app/src/main/java/com/sergey/animevault/data/download/NativeDownloadEngine.kt
test -f RELEASE_1.7.2.md

echo 'AnimeVault 1.7.2 reliability sanity: OK'
