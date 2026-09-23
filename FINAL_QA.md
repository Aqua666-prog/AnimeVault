# AnimeVault 1.5.0 final QA

## Source checks

Run:

- `tools/verify-source.sh`;
- `tools/verify-final.sh`;
- `tools/verify-1.5.0.sh`.

## Required Gradle checks

Publish an APK only after these succeed in Android-capable CI:

1. `./gradlew testDebugUnitTest --stacktrace`
2. `./gradlew lintDebug --stacktrace`
3. `./gradlew assembleDebug --stacktrace`

## Shared transition smoke-test

- open a local title from grid/list and verify its poster transitions into the detail hero;
- open an online title from grid/list and verify the same behavior;
- set animations to `Минимальные` and verify shared poster motion is disabled;
- back navigation must restore the catalog/library normally.

## Statistics smoke-test

- open `Главная -> Статистика`;
- verify local title/episode totals match the library;
- verify favorites/history values match Online Library;
- verify the 28-day activity panel renders with and without recent activity;
- metadata-less libraries must not crash the genres section.

## Player smoke-test

- pause local and native online playback and verify the context overlay;
- open EQ and autoseek/autoskip settings and verify both are bottom sheets;
- verify save/dismiss behavior and per-title settings persistence;
- EMBED playback must keep its existing WebView fallback behavior.

## Provider/security smoke-test

- disable a provider through provider config and verify Source Picker marks it disabled;
- `Все источники` must stop querying a disabled provider without an app restart;
- re-enable it and verify it becomes selectable again;
- an unrelated remote endpoint host must be rejected and fallback to the built-in endpoint family;
- AniList OAuth callback with wrong/missing state must not store a token.

## Regression

- Room database version remains unchanged;
- no migration is required from 1.4.5;
- SAF scan/rescan and persisted folder grants remain intact;
- local/online Unified Title linkage works both directions;
- bottom navigation/rail, History, search history and personalization survive restart;
- local and online playback progress persists.
