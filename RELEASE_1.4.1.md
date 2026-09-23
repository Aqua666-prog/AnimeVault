# AnimeVault 1.4.1 — UI Consolidation

1.4.1 continues the Vault redesign by making the new visual system authoritative instead of optional. The release focuses on shared surfaces, the Home continue-watching hero and a cleaner Online source-selection flow.

## Design-system consolidation

- Added `VaultInteractivePanel`, the clickable counterpart to `VaultPanel`.
- Quiet, Card and Elevated roles now rely on tone/elevation rather than a border around every block.
- Glass retains a restrained edge for artwork overlays; Accent retains an outline because it communicates selection/state.
- App-bar and quick-action surfaces use the same semantic depth model.
- Main Home and Library media cards no longer invent their own outer borders.

## Home dashboard

- The most recent unfinished local/online episode is promoted to a large cinematic continue-watching hero.
- The hero uses poster aura, source/local status, episode metadata, progress, timecode and a primary Continue action.
- Remaining unfinished episodes move to an `Ещё в процессе` shelf without duplicating the hero item.
- The Home brand subtitle changes between morning/day/evening/night greetings.

## Online catalog

- The long provider chip strip has been replaced with one Source action surface and a bottom-sheet picker.
- Provider selection now exposes runtime health state already tracked by `ProviderHealthTracker`.
- Health score is shown as a compact 0–100 pill; status text includes latency when available.
- Layout toggle, refresh and personal online library moved into the overflow menu to reduce app-bar crowding.

## Compatibility

- Database version and Room migrations are unchanged.
- SAF permissions/scanner behavior are unchanged.
- Online provider protocols, stream extraction and playback policies are unchanged.
- Existing user data and progress remain compatible with 1.4.0.

## Verification

Run:

```bash
bash tools/verify-final.sh
bash tools/verify-1.4.1.sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The source-level scripts do not replace the full Android/Gradle build.
