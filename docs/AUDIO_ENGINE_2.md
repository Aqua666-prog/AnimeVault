# AnimeVault Audio Engine 2.1

Audio Engine 2.1 is the realtime-performance revision of the in-player Media3 DSP pipeline.

## Regression fixed

The first prototype performed allocation-heavy multiband processing and recomputed compressor and limiter time constants inside the per-sample path. On physical Android hardware this could starve video decoding/rendering and lead to visible stutter and growing A/V desynchronization even though CI unit tests, lint and APK assembly passed.

## Realtime rules in 2.1

- No Pair/data-class allocation in the steady-state sample loop.
- Compressor attack/release coefficients are precomputed on configuration changes.
- Limiter release coefficient, ceiling and oversampling are cached.
- Meter dB conversion happens per buffer rather than per sample.
- Zero-gain EQ bands are bypassed.
- True-peak interpolation is conditional near the limiter ceiling.
- Oversampling is adaptive by preset: FLAT 1x, normal presets 2x, MAX 4x.
- Configuration exchange remains lock-free through atomic immutable snapshots.

## Media3 pipeline

PCM -> float -> PEQ/bass/3-band dynamics -> Sonic -> look-ahead limiter -> PCM16 -> AudioTrack

The custom AudioProcessorChain reports media duration through Sonic in the same manner as Media3's default chain. The limiter adds a small fixed look-ahead delay but does not change media duration.

## Base branch

Apply this revision to `feature/ui-redesign-2` (or a descendant), not to the earlier `feature/tenrai-final-v5` prototype base. This keeps the current UI 2.0 redesign and newer playback/transport work.
