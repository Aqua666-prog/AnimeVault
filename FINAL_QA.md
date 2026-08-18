# AnimeVault 1.1.0 final QA

## Automated/static checks performed during implementation

- Android resource XML validation.
- Source sanity checks (`tools/verify-source.sh`).
- `git diff --check` after every implementation stage.
- Provider remote-config JSON validation.
- Pure Kotlin smoke checks for playback completion/merge policies where the local toolchain permitted them.
- Structural brace checks for the large Compose player screens after player refactors.

## Required CI gate before release APK

The final source must pass the repository GitHub Actions workflow on an Android-capable runner:

1. Unit tests.
2. Android lint.
3. Debug APK compilation.
4. Install and smoke-test the APK on a physical Android device.

Local full Gradle/Android compilation was not used as a substitute when the working environment could not reach the Gradle distribution service.

## Device smoke-test checklist

- Local episode opens, seeks, pauses and restores progress.
- Online HLS opens and stream fallback retains position.
- Controls reappear after auto-hide on a single tap.
- Long press temporarily plays at 2x and restores the selected speed.
- Sleep timer pauses playback.
- Pulling headphones pauses/handles audio route correctly.
- Changing quality/voice does not restart the episode from zero.
- Local file wins over online stream when both represent the same linked episode.
- Provider health screen updates after real requests.
- Catalog advanced filters and grid/list toggle work.
- Backup export/import preserves newer progress.
