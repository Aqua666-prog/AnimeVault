#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

grep -q 'versionCode = 38' app/build.gradle.kts
grep -q 'versionName = "1.4.3"' app/build.gradle.kts
grep -q 'name: AnimeVault-1.4.3' .github/workflows/build-apk.yml
grep -q 'Текущая версия разработки: \*\*1.4.3\*\*' README.md

grep -q 'data class UnifiedTitleUiModel' app/src/main/java/com/sergey/animevault/ui/title/UnifiedTitleModel.kt
grep -q 'enum class UnifiedTitleOrigin' app/src/main/java/com/sergey/animevault/ui/title/UnifiedTitleModel.kt
grep -q 'fun UnifiedTitleOverview' app/src/main/java/com/sergey/animevault/ui/title/UnifiedTitleUi.kt
grep -q 'text = "Доступность"' app/src/main/java/com/sergey/animevault/ui/title/UnifiedTitleUi.kt

grep -q 'media-title/local/{titleId}' app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt
grep -q 'media-title/online/{providerId}/{releaseId}' app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt
! grep -q 'const val TitlePattern = "title/{titleId}"' app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt
! grep -q '"online-title/' app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt

grep -q 'findLinkedLocalTitleSummary' app/src/main/java/com/sergey/animevault/data/repository/LibraryRepository.kt
grep -q 'linkedLocalTitle: LinkedLocalTitleSummary' app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleViewModel.kt
grep -q 'onOpenLocalTitle' app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleScreen.kt
grep -q 'UnifiedTitleOverview' app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleScreen.kt
grep -q 'UnifiedTitleOverview' app/src/main/java/com/sergey/animevault/ui/title/TitleDetailScreen.kt
grep -q 'UnifiedTitleOrigin.HYBRID' app/src/test/java/com/sergey/animevault/ui/title/UnifiedTitleUiTest.kt

python3 - <<'PY'
from pathlib import Path
for path in [
    Path('app/src/main/java/com/sergey/animevault/ui/title/UnifiedTitleUi.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/title/TitleDetailScreen.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleScreen.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleViewModel.kt'),
    Path('app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt'),
]:
    text = path.read_text()
    assert text.count('{') == text.count('}'), path
    assert text.count('(') == text.count(')'), path

assert Path('RELEASE_1.4.3.md').exists()
print('AnimeVault 1.4.3 unified-title sanity: OK')
PY
