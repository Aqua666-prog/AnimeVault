# Tenrai Resilience v3 — QA

## What changed

- Persistent raw-response cache in app no-backup storage.
- Stale-on-error behavior for transport failures, 429, 502, 503 and 504.
- Fresh Tenrai responses always replace the cached copy.
- 4xx client errors such as 404 are never hidden by stale data.
- Health snapshot reports when stale cache is being served.
- AniList fallback for the main anime overview and search when Tenrai has no usable network/cache response.
- The title metadata card shows whether the overview came from Tenrai, stale Tenrai cache, or AniList fallback.
- One shared AniListMetadataRepository is reused by the app container.

## Automated checks

Run:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Important unit tests:

- `TenraiStaleCacheTest.serverFailure_usesPersistedResponseAfterRetriesExhausted`
- `TenraiStaleCacheTest.clientError_doesNotMask404WithOldCache`
- `TenraiStaleCacheTest.queryParameters_haveIndependentCacheKeys`
- `TenraiMetadataFallbackTest.overview_usesFallbackWhenTenraiIsUnavailableAndNoStaleCacheExists`
- `TenraiMetadataFallbackTest.search_usesFallbackOnlyAfterTenraiFailure`

## Manual QA

1. Open a title with a MAL id while Tenrai is reachable. Verify metadata appears normally.
2. Reopen the same title once to populate the persistent response cache.
3. Disable network or make Tenrai unavailable, then reopen the title. Cached Tenrai metadata should still appear and the metadata card should say `Tenrai · сохранённые данные`.
4. Clear app data or use a title that has never been cached, then make Tenrai unavailable. The main metadata overview should come from `AniList fallback` if AniList is reachable.
5. Restore Tenrai and reopen the title. The label should return to `Tenrai`; fresh data should replace the stale cache.
6. Verify characters, videos, recommendations and staff remain optional. Failure of one auxiliary endpoint must not blank the entire title page.
7. Search through the Tenrai metadata repository path with Tenrai unavailable. Page 1 should fall back to AniList.
8. Verify a real 404 is still surfaced rather than silently returning an old cached response.

## Cache policy

- Successful Tenrai responses are stored persistently.
- Stale data is eligible for up to 7 days.
- Maximum approximately 160 entries / 8 MiB total.
- Individual entries larger than 1.5 MiB are not cached.
- Cache I/O failures never break fresh network metadata.
