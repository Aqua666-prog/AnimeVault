#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

test -f RELEASE_1.7.0.md

grep -q 'contentWindowInsets = WindowInsets(0, 0, 0, 0)' \
    app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt
grep -q 'consumeWindowInsets(shellPadding)' \
    app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt
grep -q 'val useRail = maxWidth >= 600.dp' \
    app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt
grep -q 'PlayerImmersiveEffect(enabled = !isInPictureInPictureMode)' \
    app/src/main/java/com/sergey/animevault/ui/player/PlayerScreen.kt
grep -q 'hide(WindowInsetsCompat.Type.systemBars())' \
    app/src/main/java/com/sergey/animevault/ui/player/PlayerOrientation.kt
grep -q 'show(WindowInsetsCompat.Type.systemBars())' \
    app/src/main/java/com/sergey/animevault/ui/player/PlayerOrientation.kt
grep -q 'fun pickRandomRelease()' \
    app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogViewModel.kt
grep -q 'Мне повезёт' app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt

echo 'AnimeVault 1.7.0 adaptive/fullscreen sanity: OK'
