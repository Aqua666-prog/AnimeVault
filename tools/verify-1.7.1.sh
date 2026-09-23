#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

test -f RELEASE_1.7.1.md

grep -q 'enum class DownloadFilter' \
    app/src/main/java/com/sergey/animevault/ui/downloads/DownloadsPresentation.kt
grep -q 'summarizeDownloads(entries)' \
    app/src/main/java/com/sergey/animevault/ui/downloads/DownloadsScreen.kt
grep -q 'Удалить офлайн-копию?' \
    app/src/main/java/com/sergey/animevault/ui/downloads/DownloadsScreen.kt
grep -q 'collectIsPressedAsState' \
    app/src/main/java/com/sergey/animevault/ui/design/VaultSurfaces.kt
grep -q 'motion != VaultMotionMode.MINIMAL' \
    app/src/main/java/com/sergey/animevault/ui/design/VaultSurfaces.kt

echo 'AnimeVault 1.7.1 download center/design sanity: OK'
