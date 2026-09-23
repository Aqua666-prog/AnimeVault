# AnimeVault 0.8.5 — Zenith

Zenith is the final 0.8.x polish pass. It keeps the scanner, Room schema, provider parsers,
offline grouping, history, favorites, AnimeThemes, preferred-stream routing and automatic
failover untouched while finishing the playback and title-detail experience.

## Custom AnimeVault player chrome

- Native Media3 controller chrome is no longer the primary playback UI for local files and
  native HLS/MP4 streams. Media3 still owns decoding and the video surface; AnimeVault now
  draws its own transport controls above it.
- Added large center play/pause plus dedicated 10-second rewind and 15-second forward actions.
- Added a custom cinematic timeline with current time, duration and buffered position.
- OP and ED ranges configured in Auto-skip are drawn directly on the timeline, so skip regions
  are visible even when automatic skipping is disabled.
- Player chrome auto-hides after a short idle period and returns on a single tap.
- Existing double-tap seeking, horizontal scrubbing, brightness/volume gestures, PiP,
  equalizer, speed control and local seek-frame preview remain available.

## Viewport controls

- Added per-title video scale preference with Fit, Fill and Zoom modes.
- Added a player action and bottom sheet for explicit scale selection.
- Added pinch gesture switching between scale modes directly on the video surface.
- Scale preference is stored separately for each local or online title.

## Audio and subtitle tracks

- Added an AnimeVault track sheet for native Media3 playback.
- Available audio and text tracks are read from the current Media3 `Tracks` model after media
  preparation.
- Individual supported audio/subtitle tracks can be selected from the sheet.
- Subtitle rendering can be disabled completely without changing the media file.
- Track rows expose useful codec/bitrate/channel metadata where the stream reports it.

## Detail-page polish

- Added a compact viewing dashboard to local and online title pages.
- It shows completed, in-progress and remaining episode counts plus a title-level completion rail.
- The dashboard uses the same poster-derived accent system as the existing cinematic hero.

## Safety of the update

The following systems were intentionally not refactored in Zenith:

- SAF folder permissions and Media Scanner
- Room entities/DAO/schema
- local filename parsing and season grouping
- online provider parsers and source routing
- history and favorites persistence
- AnimeThemes integration
- preferred stream selection and automatic stream failover

## Version

- versionName: 0.8.5
- versionCode: 27
