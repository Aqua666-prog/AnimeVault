#!/usr/bin/env bash
set -euo pipefail

grep -q 'versionCode = 34' app/build.gradle.kts
grep -q 'versionName = "1.3.0"' app/build.gradle.kts
grep -q 'object LocalTitleRecognizer' app/src/main/java/com/sergey/animevault/data/scanner/LocalTitleRecognizer.kt
grep -q 'cooldownUntilMs' app/src/main/java/com/sergey/animevault/data/online/OnlineModels.kt
grep -q 'object ProviderStreamRanker' app/src/main/java/com/sergey/animevault/data/online/ProviderStreamRanker.kt
grep -q 'LoudnessEnhancer' app/src/main/java/com/sergey/animevault/ui/player/PlayerEqualizer.kt
grep -q 'BassBoost' app/src/main/java/com/sergey/animevault/ui/player/PlayerEqualizer.kt
! grep -RInE '<<<<<<<|=======|>>>>>>>' app/src/main app/src/test >/dev/null

echo 'AnimeVault 1.3.0 source sanity: OK'
