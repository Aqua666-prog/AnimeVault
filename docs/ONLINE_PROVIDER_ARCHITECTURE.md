# AnimeVault online provider architecture

AnimeVault treats every online source as an adapter behind one provider-neutral contract.
UI, catalogue and playback code must not branch on a concrete provider id when a capability can describe the difference.

## Contract

Every adapter exposes `OnlineProviderDescriptor` and `ProviderCapabilities`.

Capabilities currently describe:

- browse catalogue;
- search;
- search mode (`TEXT`, `URL_OR_SLUG`, `NONE`);
- minimum search length;
- release details;
- episodes;
- streams;
- translations;
- subtitles;
- native/direct playback.

The repository validates an operation before making a network request. Unsupported operations fail with a readable `OnlineSourceException` instead of leaking provider-specific behavior into Compose UI.

## Unified catalogue

`UnifiedOnlineProvider` chooses source adapters dynamically:

- blank query -> only providers with catalogue browse support;
- text query -> only providers with ordinary text search support;
- URL/slug adapters stay selectable directly but are excluded from global text search;
- provider-specific minimum query lengths are respected before dispatch.

Requests run independently under `supervisorScope`; failure of one source does not erase successful results from the others.

## Identity and deduplication

Cards are merged in this order:

1. AniList ID;
2. MyAnimeList ID;
3. Shikimori ID;
4. conservative normalized-title matching with year/season/part checks.

Explicit conflicting IDs in the same namespace are a hard no-merge signal even when titles are identical.

A merged card stores reconstructable `(providerId, releaseId)` references. Opening it reloads each surviving source independently and merges episodes into one episode graph.

## Playback

Unified episodes keep `OnlineEpisodeSource` members. Stream resolution delegates back to the original adapters and collects their streams into one provider-neutral list. A failing lazy resolver falls back to already-known direct streams from that source while other sources continue resolving.

The existing `PlaybackVariantResolver` then applies voice, quality and fallback policy without knowing which site produced the URL.

## Adding a provider

A new provider should:

1. implement `OnlineProvider` (or account/token subtype);
2. publish an accurate descriptor and capabilities;
3. map source data into `OnlineReleaseCard`, `OnlineReleaseDetails`, `OnlineEpisode`, and `OnlineStream`;
4. avoid provider-specific UI branches;
5. add parser/contract tests;
6. register through `OnlineProviderRegistry` only after its basic search/release/playback path is stable.

Provider endpoints and runtime enable/disable state remain separate in `ProviderEndpointRegistry` and `provider-config.json`.
