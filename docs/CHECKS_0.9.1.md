# Checks — AnimeVault 0.9.1 Matchmaker

## Static source validation

- Kotlin PSI parse: 112 source/test files, 0 syntax errors.
- Android XML parse: 13 files, 0 parse errors.
- GitHub Actions YAML parse: OK.

## Matcher smoke checks

The pure Kotlin matcher was compiled separately with `kotlinc` and exercised without Android dependencies.

Checked:

- exact linked MAL ID => score 100, `VERIFIED`, auto-apply allowed;
- exact online alias + matching episode count => `HIGH`, confirmation still required;
- unrelated title => `LOW`;
- candidate ranking can override raw AniList result order;
- major episode-count mismatch lowers an otherwise exact title from high confidence;
- punctuation/separators are normalized before comparison.

## Regression boundaries

No changes were made to:

- Room schema version or migration 2 -> 3;
- SAF scanner and folder permissions;
- episode grouping/overrides;
- watch progress;
- local Media3 playback;
- online providers and stream failover.

Automatic metadata lookup runs only from a local title screen. It is not part of SAF scanning and cannot block an offline library refresh.

## Full Android build

A full Gradle build cannot be executed in this environment because `services.gradle.org` is not DNS-resolvable here. The repository CI remains the release gate and runs `testDebugUnitTest` before `assembleDebug`.
