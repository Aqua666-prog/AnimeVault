# Tenrai v4-v5 final QA

This patch finishes the Tenrai upgrade in two layers.

## v4 — title enrichment
- First Tenrai episode page enriches playable episode cards with title, air date, filler/recap markers.
- Tenrai pictures are shown as an in-app gallery.
- Streaming/external links from `/full` are exposed from the title page.
- Tenrai health/stale-cache state is visible to the user.
- Episode pagination remains available through `getAllEpisodes`; the UI intentionally loads only page 1 to avoid turning long-running shows into request storms.

## v5 — discovery + final hardening
- Current season, today's schedule and upcoming season are loaded from Tenrai and shown as discovery shelves in Online.
- Tapping a Tenrai discovery card searches the title through AnimeVault's unified playable providers; Tenrai remains metadata-only.
- `sfw=true` is sent explicitly instead of a valueless query flag.
- Public Tenrai limiter remains 60/min, 3/sec, 300 ms spacing, matching the conservative public contract used by Tenrai.Net.
- Existing persistent stale cache and AniList fallback remain in place.

## Manual acceptance
1. Open several titles with MAL IDs. Confirm metadata appears and playback still comes from the selected AnimeVault provider.
2. For a TV title, confirm episode title/date appears for ordinary integer-number episodes when Tenrai has it.
3. Open a title with multiple MAL pictures and confirm the gallery scrolls and opens a full preview sheet.
4. Tap an external/streaming link and confirm Android opens it outside AnimeVault.
5. In Online with an empty search, confirm Tenrai shelves appear when data is available.
6. Tap a shelf card; AnimeVault should switch to unified search and search the title rather than trying to play Tenrai itself.
7. Temporarily block Tenrai/network after one successful load; cached metadata should remain usable where v3 cache has a matching response.
8. Run unit tests, lint and debug APK build.

## Build
`./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`
