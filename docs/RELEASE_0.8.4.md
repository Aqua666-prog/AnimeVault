# AnimeVault 0.8.4 — Aperture

Aperture is a responsive-cinema and playback-polish release built on top of 0.8.3 Lumen.
The scanner, Room data model, provider parsers, offline grouping, history, favorites,
AnimeThemes, OP/ED skipping, equalizer, PiP, preferred-stream routing and automatic
stream fallback are intentionally preserved.

## Detail screens

- Added one responsive `VaultAdaptiveHero` used by offline and online title pages.
- Compact phone windows keep the poster + metadata arrangement.
- Wide / landscape / foldable windows promote the poster and title into an editorial hero
  instead of stretching the phone layout.
- Hero entry now uses a short scale/fade continuity animation.
- Local hero additionally reports the number of completed episodes.
- Detail navigation receives a softer scale + fade transition to make card -> title movement
  visually continuous without coupling navigation to experimental shared-element APIs.

## Player

- Added responsive player chrome: a vertical action rail in portrait becomes a compact
  horizontal dock in landscape.
- Custom AnimeVault chrome now follows Media3 controller visibility for native playback,
  so it can disappear while the user is actually watching and return on interaction.
- Added local seek-frame preview during horizontal scrubbing using
  `MediaMetadataRetriever` against the already-authorized SAF content Uri.
- Preview extraction is debounced and quantized to five-second buckets to avoid hammering
  the decoder while a finger is moving.
- Scrub feedback now shows target time, duration and a compact progress rail.
- Existing double-tap seek, swipe seek, brightness and volume gestures remain intact.
- Player now-playing bars are width-aware and avoid wasting landscape space.

## Surfaces and sheets

- Added a reusable editorial `VaultSheetHeader` and applied it to player speed/stream
  sheets, library sorting and catalog genre/sort sheets.
- Media cards now have a subtle poster-accent light rail at the bottom edge.
- Existing press animation, glass surfaces, skeletons and poster accents are preserved.

## Version

- versionName: 0.8.4
- versionCode: 26
