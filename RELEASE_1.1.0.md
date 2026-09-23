# AnimeVault 1.1.0

AnimeVault 1.1.0 focuses on playback reliability and a provider-neutral foundation.

## Playback Engine 2.0

- Unified `EpisodePlaybackPlan` and `PlaybackVariant` model for local files and online streams.
- Playback sessions survive stream/quality variant changes without losing the current position.
- Centralized playback, timeline and overlay state instead of independent UI flags.
- Local files are preferred when the requested episode is already available offline.
- Automatic quality/source fallback for retryable playback failures.
- Media3 stream cache with safe cache-error bypass.
- MediaSession integration and correct handling of disconnected audio devices.
- Sleep timer: 15/30/60/90 minutes or end of the current episode.
- Hold the video surface for temporary 2x playback; release restores the previous speed.
- More precise nonlinear horizontal scrubbing.

## Providers

- Provider endpoint registry with HTTPS-only failover.
- Remote `provider-config.json` support with persisted last-known-good configuration.
- Invalid, duplicate, oversized or all-disabled remote configurations are rejected.
- Runtime provider health scores use real catalog/release/stream operations.
- Concurrent provider and metadata requests for the same resource are coalesced.

## Library and progress

- Explicit NOT_STARTED / IN_PROGRESS / COMPLETED semantics.
- Rich progress timestamps and play count.
- Smarter completion policy near the end of normal-length episodes.
- Versioned backup format and NEWER_WINS merge policy by default.
- Expanded catalog filtering and grid/list layouts.

## Database

Database schema version: 4. Migration 3 -> 4 preserves existing watch history while adding richer progress fields.
