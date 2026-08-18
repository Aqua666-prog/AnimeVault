# AnimeVault 1.1.0 implementation report

This release was implemented as a sequence of isolated stages. Each stage was checked before the next one was started.

## Stage 1: playback reliability and provider health

Implemented explicit watch states, centralized playback-failure classification, provider health telemetry from real operations, Media3 stream cache, and audio-route safety.

Verification: Android XML/source sanity, `git diff --check`, pure Kotlin playback-core smoke checks.

## Stage 2: provider-neutral playback model

Implemented `EpisodePlaybackPlan`, `PlaybackVariant`, preference ranking and fallback selection for local/HLS/MP4/embed variants.

Verification: source sanity, diff check, playback resolver/state smoke tests.

## Stage 3: playback sessions and local fallback

Connected playback session state to Media3, preserved position across variant changes, merged local/online progress and added local-file fallback to linked online episodes.

Verification: source sanity, diff check, playback session/progress smoke tests.

## Stage 4: overlay coordinator

Replaced independent player menu booleans with one exclusive `PlayerOverlayState` reducer.

Verification: source sanity, diff check, reducer smoke tests and structural checks of both player screens.

## Stage 5: endpoint failover and remote provider configuration

Added HTTPS-only provider endpoint registry, retry/failover for safe requests, persisted last-known-good remote configuration and asynchronous refresh.

Verification: `provider-config.json` schema/HTTPS/uniqueness validation, source sanity, diff check, structural Kotlin checks.

## Stage 6: richer progress semantics

Added first-played/completed timestamps, play count, smarter completion policy and Room schema migration 3 -> 4.

Verification: source sanity, diff check, pure Kotlin completion-policy smoke test, migration source checks.

## Stage 7: versioned backup and safe merge

Added backup schema v2 and merge policies. `NEWER_WINS` is the default so old backups do not roll back newer playback state.

Verification: source sanity, diff check, backup structure checks, pure Kotlin merge-policy smoke test.

## Stage 8: catalog discovery

Added year/type/status/episode-count filters plus grid/list layouts.

Verification: source sanity, diff check, pure catalog-filter smoke test and Compose/ViewModel structural checks.

## Stage 9: platform media controls and player comfort

Added MediaSession, session-only sleep timer, temporary 2x long-press playback and nonlinear horizontal scrubbing.

Verification: source sanity, diff check, player structural checks and sleep/seek unit-test sources.

## Stage 10: request de-duplication

Added coroutine-safe in-flight request coalescing and bounded TTL caches for provider releases/catalog and AniList metadata/franchise requests.

Verification: source sanity, diff check, cache structural checks and dedicated unit-test sources.

## Stage 11: release hardening

Rejected malformed/duplicate/oversized/all-disabled provider remote configurations, preserved the active endpoint across valid config refreshes, limited remote config payload size and bumped the app to 1.1.0 / versionCode 32.

Verification: `tools/verify-final.sh`, source sanity, diff check, provider config/version validation.

## Stage 12: regression hardening

Removed mutable preference values from `DisposableEffect` keys that own/release ExoPlayer. This prevents changing sleep/autoplay state from releasing a remembered player instance. Also closed a tiny zero-TTL in-flight cache race and protected long-press speed mode from turning into a scrub gesture.

Verification: final source verification, player brace checks, lifecycle-key assertions and diff check.

## Build boundary

The implementation environment could not reach the Gradle distribution service, so a full Android Gradle compile was deliberately not claimed. The final release gate remains the repository GitHub Actions workflow followed by a physical-device smoke test described in `FINAL_QA.md`.
