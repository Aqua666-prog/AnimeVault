# Tenrai Metadata v2 QA

This patch builds on Tenrai Core v1 and is cumulative: it can be applied to the clean AnimeVault Animetka/Anime4K base or after Tenrai Core v1.

## Added Tenrai surface

- `/anime/{id}/full`
- `/anime/{id}/staff`
- `/anime/{id}/recommendations`
- `/anime/{id}/statistics`
- `/anime/{id}/episodes` with pagination
- `/anime/{id}/episodes/{episode}`
- `/anime/{id}/pictures`
- `/anime/{id}/themes`
- `/anime` search
- `/schedules`
- `/seasons/now`
- `/seasons/upcoming`

The title Extras screen now surfaces a compact Tenrai overview, production/studio data, MAL score/rank/popularity, staff, relations and recommendations. Heavy lists such as all episodes and pictures remain lazy repository calls and are not downloaded just because the title page opened.

## Reliability rules

- All Tenrai requests use one shared `TenraiClient` in `AppContainer`, therefore one process-wide limiter and one health state.
- Public requests stay conservatively below Tenrai's documented 120 RPM / 4 RPS ceiling.
- Query parameters are encoded as query parameters; pagination never becomes part of the URL path.
- 429 honors `Retry-After`; 502/503/504 and transport failures use bounded retries from Core v1.
- Coroutine cancellation cancels the OkHttp call.
- Endpoint results use in-flight deduplication and TTL caches.
- SFW-capable discovery methods request Tenrai's `sfw` mode by default.

## Automated checks

Run:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Tests cover:

- full-details mapping
- relation cover images / media type
- themes and streaming/external links
- episode pagination metadata
- staff / recommendations / statistics
- season/schedule catalogue shape
- correct query-string encoding in `TenraiClient`
- Core v1 retry / 429 / transport / protocol behavior

## Manual Android QA

1. Open a title with a MAL ID (Frieren is a useful smoke test).
2. Extras should still show existing characters/videos.
3. A Tenrai overview card should appear when Tenrai is reachable.
4. Check score/rank/studio/status data for obviously malformed values.
5. Staff cards should render without blocking title playback.
6. Relations and recommendations should render posters when Tenrai supplies them.
7. Disable network, reopen a title, and confirm failure is contained to Extras rather than breaking the whole title page.
8. Restore network and use Retry.
9. Rapidly reopen the same title: requests should be served from memory cache / coalesced rather than hammering Tenrai.

## Deliberately not done in v2

- Room-backed stale cache (planned separately)
- AniList automatic fallback for Tenrai metadata (planned separately)
- dedicated Schedule/Season UI (repository methods are ready, UI comes later)
- replacing video providers with Tenrai (Tenrai is metadata, not AnimeVault's episode video transport)
