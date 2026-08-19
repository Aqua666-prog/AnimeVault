# Local recognition, source resilience and audio pipeline

## Local recognition pipeline

`SAF -> LibraryScanner -> EpisodeNameParser -> DiscoveredTitle -> LocalTitleRecognizer -> AniListMetadataRepository -> TitleMetadata`

Automatic metadata application is intentionally stricter than manual suggestions. Search relevance alone is never enough. The winner must have an exact normalized local/candidate title, score >= 94, at least 7 points over the runner-up and an episode-count-compatible shape.

## Source resilience pipeline

`provider request -> ProviderHealthTracker -> circuit breaker -> health score -> ProviderStreamRanker -> UnifiedOnlineProvider`

Three consecutive failures open a temporary circuit. Network/server failures cool down for 90 seconds, rate limits for 5 minutes, auth/forbidden failures for 10 minutes. Manual health diagnostics bypass the breaker.

## Audio pipeline

`Media3 ExoPlayer -> audioSessionId -> Equalizer + LoudnessEnhancer + BassBoost`

All effects are session-scoped. They are recreated when Media3 changes audio session and released when playback leaves the screen. The implementation does not install a global Android audio effect.
