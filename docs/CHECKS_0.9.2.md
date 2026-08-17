# Checks — AnimeVault 0.9.2 Home

## Static source validation

- Kotlin PSI parse: 116 source/test files, 0 syntax errors.
- Android XML parse: 13 files, 0 parse errors.
- GitHub Actions YAML parse: OK.
- `git diff --check`: OK.

## Home feed smoke checks

Pure Kotlin `HomeFeed.kt` was compiled independently with `kotlinc` and exercised without Android dependencies.

Checked:

- local and online continue-watching cards are merged into one feed;
- newest `lastWatchedAt` wins regardless of storage source;
- feed limit is respected;
- entries without a valid watch timestamp are excluded;
- progress is safe for unknown duration and clamped to `0..1`.

## Room query smoke check

The new read-only Home query was executed against an in-memory SQLite schema.

Checked:

- only the latest unfinished local episode per title is returned;
- completed episodes are excluded;
- zero-position rows are excluded;
- metadata poster fallback works;
- the query does not require a Room schema migration.

## Regression boundaries

No changes were made to:

- Room entities/schema version or migrations;
- SAF scanner and persisted folder permissions;
- episode grouping and manual overrides;
- metadata Matchmaker logic;
- online provider parsers/resolvers;
- Media3 player, OP/ED skip, equalizer, PiP or stream failover.

Home is a read-only aggregation layer over existing Room and `OnlineLibraryStore` state.

## Full Android build

A full Gradle Android compile cannot be completed in this container because the Gradle distribution/dependency network is unavailable here. Repository CI remains the final release gate and should run `testDebugUnitTest` followed by `assembleDebug` before installation.
