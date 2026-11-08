# AnimeVault: Plane of Euthymia

Optional editions live in the existing `AnimeVaultTheme` rather than a second app shell.
`VaultThemeMode` keeps stable stored names and adds EUTHYMIA/MUSOU. `UiPreferences`
uses the existing SharedPreferences and StateFlow, applying selections immediately.
Unknown stored themes fall back to VAULT; the previous non-OLED reset is removed.
Legacy Midnight/OLED/Dynamic palettes remain available in settings.

`RaidenTokens` supplies semantic surfaces, gold, electro and sakura roles; Material 3
roles also theme dialogs, sheets, switches, chips and text inputs throughout the app.
Existing Vault spacing/elevation/motion tokens are reused, with asymmetric Raiden
shapes and serif display headings. Body text stays sans serif. Shared Vault panels,
poster cards, title heroes, filters, progress, root navigation, Community comment
cards and small player panels consume these roles. Media3, providers, networking,
Room schemas, downloads, authentication and updates are unchanged.

The 1600 ms Compose overlay runs after the native system splash. The navigation
is mounted underneath. Tap, Back, backgrounding, reduced app motion and disabled
system animations bypass/end it. The finite sequence reveals an original crest,
a broad low-opacity light wave, seven deterministic Musou petals, title and gold
line, then fades out. Musou selections receive one 700 ms pulse. There are no
continuous decorative animators, fullscreen bitmaps, shaders or blur dependencies.
All new graphics are original Canvas paths/gradients created for this change.
No third-party art or fonts were downloaded. Android minSdk remains 24.

## Verification

Only GitHub Actions runs Gradle. The existing build workflow includes this branch;
its compile/JVM/lint/APK checks remain intact. API 24/35 emulator jobs run the existing
onboarding suite then five appearance tests for persistence, unknown values,
decoration OFF and live theme selection. JVM tests cover the motion policy and
fallback parsing; existing palette contrast tests cover every new enum entry.
Preview files cover home, catalog, title/episodes, library, downloads, player,
components, theme settings, Community comments and deterministic splash frames.
Synthetic preview data never requires real accounts.

## Remaining manual validation

Preview declarations and automated checks do not establish TalkBack quality,
actual cold-process startup, playback performance or GPU/battery behaviour on
physical devices. Device screenshots and visual inspection at large font sizes
are still required. Android 12's system starting-window icon stays the existing
AnimeVault brand; the selected edition appears in the Compose overlay afterwards.

## 4.2.0 polish

Panel edges now use directional gradients, selection uses a finite radial pulse
and a gold marker, and Musou buttons compress gently on press. Crest geometry
is cached by size. A bounded static architectural horizon and a soft expanding
ring add depth without continuous effects. Sakura petals rotate during the
existing 1600 ms timeline. Splash underline follows text layout, including in
landscape; the compact crest uses less space in short windows.

Version source is 4.2.0 / 86; README and changelog match it. No release/tag
is published by the feature branch. Room and network contracts are unchanged.
