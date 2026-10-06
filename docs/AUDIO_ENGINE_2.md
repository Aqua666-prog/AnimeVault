# AnimeVault Audio Engine 2

The player audio path is now owned by AnimeVault instead of Android session-scoped AudioFX.

Pipeline:

`decoder PCM -> float conversion -> 10-band PEQ + bass + 3-band linked dynamics -> Media3 Sonic -> look-ahead limiter -> PCM16 -> AudioTrack`

The final limiter is intentionally after Sonic so playback-speed processing cannot create an uncontrolled post-limiter peak.

## Presets

Legacy preset names remain valid: OFF, FLAT, DIALOGUE, BASS, BRIGHT, NIGHT.
New presets: CINEMA, LOUD, MAX.

LOUD and MAX primarily raise average level by reducing crest factor and applying controlled makeup gain. They are not implemented as a raw +dB booster.

## Safety

- DSP calculations use float until the final limiter.
- Auto Headroom estimates the combined EQ response and subtracts preamp before boosts.
- Stereo dynamics are linked.
- Limiter uses 5-6 ms look-ahead and 4x cubic inter-sample peak estimation in LOUD/MAX.
- NaN/Infinity samples are sanitized instead of propagating into AudioTrack.
- Unsupported multichannel formats are not handled by these processors; the Media3 configuration must fall back rather than crash playback.

## QA

Run:

```bash
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon lintDebug
./gradlew --no-daemon assembleDebug
```
