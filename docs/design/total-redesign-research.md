# GitHub research for AnimeVault redesign

Inspected 2026-11-11 with authenticated GitHub REST API (`gh api`), including repository trees, source content, LICENSE files, issue bodies and merged PR metadata. These are engineering references, not user studies or evidence that an upstream app is defect-free. No third-party code, images, fonts, dependencies or install scripts were copied or run.

## Source snapshots and licensing

| Project | Inspected commit | License read from repository | Scope |
|---|---|---|---|
| [Now in Android](https://github.com/android/nowinandroid) | `2982e63cb691ad49cd009aab43dc206fee6699c2` | [Apache-2.0](https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/LICENSE) | Material navigation wrappers, app state, offline feedback |
| [Jellyfin Android](https://github.com/jellyfin/jellyfin-android) | `fbf4bb07db6246065867ba1f855aefdc0bd8fd9d` | [GPL-2.0](https://github.com/jellyfin/jellyfin-android/blob/fbf4bb07db6246065867ba1f855aefdc0bd8fd9d/LICENSE.md) | Fullscreen/system bars, gesture exclusions, practical media regressions |
| [AniHyou Android](https://github.com/axiel7/AniHyou-android) | `570d9cd2bf72da7654cf0981022d2350875b86e6` | [GPL-3.0](https://github.com/axiel7/AniHyou-android/blob/570d9cd2bf72da7654cf0981022d2350875b86e6/LICENSE) | Anime cards, metadata hierarchy, scrolling rail, route transitions |
| [MusicApp-KMP](https://github.com/SEAbdulbasit/MusicApp-KMP) | `3048f024f6670bc05a509772dd9461a6c189ce37` | [MIT](https://github.com/SEAbdulbasit/MusicApp-KMP/blob/3048f024f6670bc05a509772dd9461a6c189ce37/LICENSE) | Smaller project: compact/full player and explicit playback state |

Licenses describe inspected snapshots, not permission to import all resources or transitive libraries. No imported materials means no new attribution/license payload is required. MusicApp issue #36 specifically raises dependency-license concerns; its MIT root license does not resolve licenses of unrelated bundled dependencies. Do not add its player dependencies.

## Concrete observations and applicable decisions

### Now in Android

[Navigation.kt](https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/core/designsystem/src/main/kotlin/com/google/samples/apps/nowinandroid/core/designsystem/component/Navigation.kt) wraps Material NavigationBarItem/RailItem in the design system, aligns selected/unselected colors and labels, and uses currentWindowAdaptiveInfo for layout selection. Idea for AnimeVault: a single labeled root navigation contract across bar and rail; use the existing Compose stack and width constraints rather than adding Navigation 3 or adaptive libraries just to imitate upstream.

[NiaAppState.kt](https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/app/src/main/kotlin/com/google/samples/apps/nowinandroid/ui/NiaAppState.kt) derives offline/badge state from repositories with WhileSubscribed StateFlows and attaches navigation diagnostics. Idea: keep UI state derived from existing ViewModels, not duplicate state inside redesigned cards. No architecture migration is implied.

[Issue #350](https://github.com/android/nowinandroid/issues/350), open at inspection: permanent offline snackbar described as too naggy and consuming screen space for offline content. Concrete caution: offline/error recovery belongs beside the affected content, with persistent library access; a permanent app-wide banner is not automatically good feedback.

### Jellyfin Android

[PlayerFullscreenHelper.kt](https://github.com/jellyfin/jellyfin-android/blob/fbf4bb07db6246065867ba1f855aefdc0bd8fd9d/app/src/main/java/org/jellyfin/mobile/player/ui/PlayerFullscreenHelper.kt) owns window insets behavior; determines fullscreen from status bars because navigation bars do not exist on every device. [PlayerGestureHelper.kt](https://github.com/jellyfin/jellyfin-android/blob/fbf4bb07db6246065867ba1f855aefdc0bd8fd9d/app/src/main/java/org/jellyfin/mobile/player/ui/PlayerGestureHelper.kt) excludes vertical/horizontal edges from gestures, locks gesture direction and displays seek feedback. Idea: preserve AnimeVault's existing player/insets owner; controls must not replace Android edge gestures. Keep explicit buttons as alternatives to gestures.

[Merged PR #2107](https://github.com/jellyfin/jellyfin-android/pull/2107) adds navigationBarStyle to an existing statusBarStyle setup. Practical lesson: status-bar-only visual verification misses navigation-bar seams, especially across navigation modes. Check both bars, portrait/landscape and player entry/exit.

[Issue #1742](https://github.com/jellyfin/jellyfin-android/issues/1742), open: TalkBack cannot announce category headers, titles in thumbnail lists, seasons/chapters, and player controls do not function while TalkBack is enabled. These are upstream reported defects, not reproduced AnimeVault defects. Acceptance implications: semantic headings; one meaningful title/card node; labeled series/source/actions; explicit state and actions; real TalkBack testing remains necessary.

[Issue #2256](https://github.com/jellyfin/jellyfin-android/issues/2256), open: selected audio track ignored with many embedded tracks plus external subtitles. Lesson: polished track selectors cannot prove playback correctness. Redesign must retain track identities, subtitle integration and Media3 lifecycle, and avoid asserting a selected state that differs from active playback.

### AniHyou

[MediaItemVertical.kt](https://github.com/axiel7/AniHyou-android/blob/570d9cd2bf72da7654cf0981022d2350875b86e6/core/ui/src/main/java/com/axiel7/anihyou/core/ui/composables/media/MediaItemVertical.kt) splits poster, title and optional subtitle; title stays off artwork, uses two lines; status badge has a localized content description. [MediaItemHorizontal.kt](https://github.com/axiel7/AniHyou-android/blob/570d9cd2bf72da7654cf0981022d2350875b86e6/core/ui/src/main/java/com/axiel7/anihyou/core/ui/composables/media/MediaItemHorizontal.kt) provides slots for multiple metadata rows. Idea: use one poster-card language, readable type below artwork, and horizontal rows for management-heavy lists. Do not import its fixed poster dimensions or long-press-only actions; AnimeVault must scale text and expose secondary actions explicitly.

[MainNavigationRail.kt](https://github.com/axiel7/AniHyou-android/blob/570d9cd2bf72da7654cf0981022d2350875b86e6/app/src/main/java/com/axiel7/anihyou/ui/screens/main/composables/MainNavigationRail.kt) makes rail destinations vertically scrollable, with labels. Idea: short landscape windows and large font scale require reachable rail items; width alone is insufficient if rail has too many items. Its animated icons do not establish reduced-motion compliance for AnimeVault.

[MainNavigation.kt](https://github.com/axiel7/AniHyou-android/blob/570d9cd2bf72da7654cf0981022d2350875b86e6/app/src/main/java/com/axiel7/anihyou/ui/screens/main/MainNavigation.kt) distinguishes root fades from nested route transitions and accepts compact-screen padding. Idea: root switching should feel immediate; hierarchy transitions should communicate direction without unnecessary decoration. Keep AnimeVault's existing state restore/back stack and motion preferences.

[Closed PR #589](https://github.com/axiel7/AniHyou-android/pull/589) is titled “Changed Navigation, to use deeplink as root if provided”; issue endpoint provides no body. This establishes a historical deep-link navigation change, but not detailed rationale or a measured outcome. Avoid claiming stronger evidence. Preserve cold/warm deep-link contracts in AnimeVault.

### Smaller Compose media project: MusicApp-KMP

[PlayerViewState.kt](https://github.com/SEAbdulbasit/MusicApp-KMP/blob/3048f024f6670bc05a509772dd9461a6c189ce37/shared/src/commonMain/kotlin/musicapp/playerview/PlayerViewState.kt) explicitly separates playing/buffering/error/position/duration. [PlayerView.kt](https://github.com/SEAbdulbasit/MusicApp-KMP/blob/3048f024f6670bc05a509772dd9461a6c189ce37/shared/src/commonMain/kotlin/musicapp/playerview/PlayerView.kt) passes callbacks into compact/full player presentations. Useful general idea: shared playback state supports multiple control surfaces. Rejected details: 24dp clickable transport icons, endless marquee/equalizer and hardcoded translucent surface colors; not suitable as AnimeVault defaults for accessibility/reduced motion.

[Issue #33](https://github.com/SEAbdulbasit/MusicApp-KMP/issues/33), closed: asks for Android/iOS media notification controls and return-to-player behavior. A beautiful in-app player is incomplete without system integration; preserve AnimeVault MediaSession/PiP/background behavior.

[Issue #36](https://github.com/SEAbdulbasit/MusicApp-KMP/issues/36), closed: asks about root license and vlcj/GPL. Licensing lesson above; no copied code or dependencies.

## Independent critique of existing AnimeVault visuals

Read current VaultPosterCard, VaultCinematic, RaidenEffects, VaultPalettes and HomeScreen; viewed tracked `docs/screenshots/01-home.png`. The screenshot is historical evidence, not a fresh device run of the current branch.

Confirmed in code: UI components carry alternate Raiden decorations (`raidenEdge`, `electroGlow`) including gold strokes, radial washes and selection ornament. Navigation and cards therefore change more than semantic color across appearance themes; visual complexity can multiply across lists. Current adaptive hero already has solid engineering: larger-font stacking, dynamic content height, gradients/readability plane and shared image components. Preserve those mechanisms while reconsidering the composition.

Visual judgment: historic home has a very tall promotional hero; discovery takes precedence over resume on the first viewport. Bright lemon CTA and purple navigation compete with vivid artwork. Icon-only next-featured and favorite actions require learned interpretation; the CTA receives much stronger hierarchy than the title's context. These are design judgments; no timing study or measured user preference was conducted.

Recommended independent identity: editorial media library with warm/neutral dark reading surfaces, restrained single action accent, strong titles, consistent compact management rows and large artwork only where it aids selection. Prefer card/row hierarchy and whitespace over outline/glow ornament. Home should foreground returning-to-play when progress exists; feature discovery remains accessible. Download statuses must use words and progress, never color alone. A coherent redesign requires shared component changes plus screen-specific restructuring; palette replacement alone is insufficient.

## Evidence limitations

No GitHub screenshots were treated as usability measurements. Issues are reported upstream experiences, not local reproduced defects. No access to user analytics or interviews. Licensing checked at root snapshots, not a complete legal audit of every asset/dependency. Final adoption must be recorded against the actual implementation; recommendations above are not a claim that every idea was implemented.
