# Anime4K Light — Galaxy A17 QA protocol

The code path is a prototype. Do not call it production-ready until this device run is completed.

## Build
Run GitHub Actions workflow `Animetka + Anime4K QA APK` after applying this package. Confirm unit tests,
lint and `assembleDebug` all pass.

## Test clips
Use the same episode/translation/seek positions for both runs. Prefer one available 720p stream
and one 1080p stream. Each measurement run must last at least 15 minutes.

## Baseline run
1. Reboot or allow the phone to return near idle temperature.
2. Anime4K Light OFF.
3. Record start battery %, device temperature if available, and time.
4. Play 15 minutes. Perform the same planned seeks as the effect run.
5. Record visible stutter/A-V sync issues, end battery %, temperature and any frame-drop data available.

## Effect run
Repeat under equivalent conditions with Anime4K Light ON. The wand button in the player is the toggle.

## Behaviour checks
- Toggle ON/OFF while playing without restarting the episode.
- Seek backward and forward several times.
- Change stream/quality and episode while the preference is ON.
- Enter/leave background and PiP.
- Confirm only one audio/video session continues.
- If the GL effect fails, confirm the app disables Anime4K and resumes the same stream near the same position.
- Reopen the title: the per-title preference should persist.

## Release decision
Accept the light mode only if playback remains smooth enough for normal viewing, A/V sync remains stable,
thermal/battery impact is acceptable, and GPU fallback is reliable. Do not add heavier Anime4K CNN presets
until this baseline is measured.
