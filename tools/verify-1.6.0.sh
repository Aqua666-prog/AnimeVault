#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

grep -q 'verify-1.6.0.sh' .github/workflows/build-apk.yml

test -f RELEASE_1.6.0.md
test -f app/src/main/java/com/sergey/animevault/data/download/DownloadEntity.kt
test -f app/src/main/java/com/sergey/animevault/data/download/DownloadRepository.kt
test -f app/src/main/java/com/sergey/animevault/data/download/DownloadWorker.kt
test -f app/src/main/java/com/sergey/animevault/data/download/DownloadedMediaImporter.kt
test -f app/src/main/java/com/sergey/animevault/data/download/NativeDownloadEngine.kt
test -f app/src/main/java/com/sergey/animevault/ui/downloads/DownloadsScreen.kt
test -f app/schemas/com.sergey.animevault.data.db.AnimeVaultDatabase/5.json

grep -q '#EXT-X-STREAM-INF' app/src/main/java/com/sergey/animevault/data/download/NativeDownloadEngine.kt
grep -q '#EXT-X-MAP' app/src/main/java/com/sergey/animevault/data/download/NativeDownloadEngine.kt
grep -q '#EXT-X-KEY' app/src/main/java/com/sergey/animevault/data/download/NativeDownloadEngine.kt
grep -q 'AES/CBC/PKCS5Padding' app/src/main/java/com/sergey/animevault/data/download/NativeDownloadEngine.kt
grep -q 'ResumeJournal' app/src/main/java/com/sergey/animevault/data/download/NativeDownloadEngine.kt
grep -q 'operationToken' app/src/main/java/com/sergey/animevault/data/download/DownloadWorker.kt
grep -q 'SecureSessionStore' app/src/main/java/com/sergey/animevault/data/download/DownloadStore.kt
grep -q 'EpisodeEntity' app/src/main/java/com/sergey/animevault/data/download/DownloadedMediaImporter.kt
grep -q 'version = 7' app/src/main/java/com/sergey/animevault/data/db/AnimeVaultDatabase.kt
grep -q 'MIGRATION_4_5' app/src/main/java/com/sergey/animevault/data/db/AnimeVaultDatabase.kt

grep -q 'android.permission.FOREGROUND_SERVICE_DATA_SYNC' app/src/main/AndroidManifest.xml
grep -q 'android.permission.POST_NOTIFICATIONS' app/src/main/AndroidManifest.xml
grep -q 'androidx.work.impl.foreground.SystemForegroundService' app/src/main/AndroidManifest.xml
grep -q 'androidx.work:work-runtime-ktx:2.11.2' app/build.gradle.kts
if grep -q 'exclude(group = "androidx.concurrent", module = "concurrent-futures-ktx")' app/build.gradle.kts; then
    echo 'concurrent-futures-ktx must not be excluded: CoroutineWorker requires ListenableFutureKt' >&2
    exit 1
fi
grep -q 'override fun onConfigurationChanged' app/src/main/java/com/sergey/animevault/ui/player/PlayerActivity.kt
grep -q 'LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES' app/src/main/java/com/sergey/animevault/ui/player/PlayerActivity.kt
grep -q 'window.decorView.post(::applyImmersivePlayerWindow)' app/src/main/java/com/sergey/animevault/ui/player/PlayerActivity.kt
grep -q 'SCREEN_ORIENTATION_LANDSCAPE' app/src/main/java/com/sergey/animevault/ui/player/PlayerOrientation.kt
test "$(rg -l 'R.string.player_rotate_screen' app/src/main/java/com/sergey/animevault/ui/player/{OnlinePlayerScreen,PlayerScreen}.kt | wc -l)" -eq 2

echo 'AnimeVault native downloads regression sanity: OK'
