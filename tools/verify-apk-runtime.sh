#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"

test -f "$APK"

DEX_FILES="$(unzip -Z1 "$APK" | grep -E '^classes([0-9]+)?\.dex$')"
test -n "$DEX_FILES"

contains_descriptor() {
    local descriptor="$1"
    local dex
    while IFS= read -r dex; do
        if unzip -p "$APK" "$dex" | strings | grep -Fq "$descriptor"; then
            return 0
        fi
    done <<< "$DEX_FILES"
    return 1
}

contains_descriptor 'Landroidx/concurrent/futures/ListenableFutureKt;'
contains_descriptor 'Lcom/sergey/animevault/data/download/DownloadWorker;'
contains_descriptor 'Lcom/sergey/animevault/data/scanner/OfflineLibraryScanWorker;'
contains_descriptor 'Lcom/sergey/animevault/ui/player/PlayerOrientationKt;'

echo 'AnimeVault APK runtime classes: OK'
