# AnimeVault 1.0.0

AnimeVault 1.0.0 closes the 0.x development cycle and keeps the existing offline-first core intact.

## Major additions after 0.9.2

- AniList account sync with OAuth, progress/status updates and secure token storage.
- Franchise relations, release/chronology/main-story ordering and recommendations.
- Configurable next-episode behaviour with countdown for local and online playback.
- Smart local collections: in progress, untouched, completed and linked online.
- Storage accounting and safe deletion of completed local video files through SAF write grants.
- Home viewing insights for completion, watch time and local storage.
- Logical `.avb` backup/restore for local progress, metadata, online links, grouping overrides, online history/favourites and online playback progress. API credentials are excluded.
- Centralized online provider registry with duplicate-ID validation.
- CI hardening: source sanity checks, unit tests, Android lint and debug APK build.

## Upgrade notes

The Room schema remains at version 3 for this release; no new migration is required after 0.9.2.
Folders added by older builds may only have a persisted read grant. To use destructive storage cleanup, select such a folder again so Android can grant persisted write access.
