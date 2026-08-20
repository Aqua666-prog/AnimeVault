#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

grep -q 'versionCode = 40' app/build.gradle.kts
grep -q 'versionName = "1.4.5"' app/build.gradle.kts
grep -q 'name: AnimeVault-1.4.5' .github/workflows/build-apk.yml
grep -q 'Текущая версия разработки: \*\*1.4.5\*\*' README.md

grep -q 'enum class VaultThemeMode' app/src/main/java/com/sergey/animevault/ui/preferences/AppearanceSettings.kt
grep -q 'VAULT("Vault"' app/src/main/java/com/sergey/animevault/ui/preferences/AppearanceSettings.kt
grep -q 'MIDNIGHT("Midnight"' app/src/main/java/com/sergey/animevault/ui/preferences/AppearanceSettings.kt
grep -q 'OLED("OLED"' app/src/main/java/com/sergey/animevault/ui/preferences/AppearanceSettings.kt
grep -q 'DYNAMIC("Dynamic"' app/src/main/java/com/sergey/animevault/ui/preferences/AppearanceSettings.kt
grep -q 'dynamicDarkColorScheme' app/src/main/java/com/sergey/animevault/ui/theme/Theme.kt
grep -q 'systemAccent' app/src/main/java/com/sergey/animevault/ui/theme/Theme.kt
grep -q 'LocalVaultVisualSettings' app/src/main/java/com/sergey/animevault/ui/theme/Theme.kt
grep -q 'vaultMotionDuration' app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt
grep -q 'vaultBlurEnabled' app/src/main/java/com/sergey/animevault/ui/components/VaultCinematic.kt

grep -q 'private enum class SettingsCategory' app/src/main/java/com/sergey/animevault/ui/settings/SettingsScreen.kt
grep -q 'SettingsCategory.APPEARANCE' app/src/main/java/com/sergey/animevault/ui/settings/SettingsScreen.kt
grep -q 'SettingsCategory.PLAYER' app/src/main/java/com/sergey/animevault/ui/settings/SettingsScreen.kt
grep -q 'playbackDefaultsState' app/src/main/java/com/sergey/animevault/ui/preferences/UiPreferences.kt
grep -q 'defaultSubtitlesEnabled' app/src/main/java/com/sergey/animevault/ui/player/PlayerEqualizer.kt
grep -q 'globalPreferences.playbackDefaults' app/src/main/java/com/sergey/animevault/ui/player/PlayerEqualizer.kt
grep -q 'class AppearanceSettingsTest' app/src/test/java/com/sergey/animevault/ui/preferences/AppearanceSettingsTest.kt

python3 - <<'PY'
from pathlib import Path
for path in [
    Path('app/src/main/java/com/sergey/animevault/ui/preferences/AppearanceSettings.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/preferences/UiPreferences.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/theme/Theme.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/theme/AnimeBackdrop.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/settings/SettingsScreen.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/settings/SettingsViewModel.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/player/PlayerEqualizer.kt'),
]:
    text = path.read_text()
    assert text.count('{') == text.count('}'), path
    assert text.count('(') == text.count(')'), path
assert Path('RELEASE_1.4.5.md').exists()
print('AnimeVault 1.4.5 settings/personalization sanity: OK')
PY
