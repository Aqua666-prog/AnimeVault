# Cinematic UI 4.0 — Phase 1

Base: main `9802b60`; PR #26 was merged before implementation.
Primary guidance: `.agents/skills/animevault-ui-designer/SKILL.md` and all its references.

## Decisions and reuse

| Decision | Existing component | Extension |
|---|---|---|
| Content-first Home hero | VaultAdaptiveHero, VaultArtwork | Content-driven height, stacked narrow/large-font layout, protected text contrast |
| Resume featured release | HomeContinueItem, WatchProgressBar | Match existing provider/release and dispatch its saved episode callback |
| Prominent continue shelf | ContinueWatchingCard, vaultClickable | Tonal container, larger title, scalable width, progress and accessible name |
| Discovery grid | VaultPosterCard, VaultSectionHeader | Lazy adaptive cells, existing favorite/continue/ongoing status, semantic headings |
| Empty/error/loading | VaultEmptyState, VaultSkeletonBlock | Catalog action, retry and static placeholders; partial content remains visible |
| Navigation hierarchy | VaultBottomNavigation, VaultNavigationRail | Violet selection, larger labels, unchanged routes/actions |
| Motion | VaultMotion, LocalVaultVisualSettings, existing shared elements | One bounded entrance fade, no route translation/scale outside FULL |

No new dependency or alternative design system. HomeFeed, HomeViewModel, persistence and playback callbacks are unchanged. No source adapter, API, Media3, download worker, Room schema, auth, updater, CI, signing or version changes.
Shared poster keys remain unique: ongoing shelf owns ongoing shared elements, discovery grid owns non-ongoing ones. Shared transitions stay FULL-only through the existing modifier.

## State and accessibility contract

Loading uses static placeholders; no looping shimmer. Empty catalog offers discovery, failed catalog offers retry, partial data preserves working shelves and local progress. Missing artwork keeps its geometry and a quiet movie glyph. Preview mode never requests images.

Hero height follows text rather than a fixed screen percentage. At width <360dp or font scale >1.3 its poster stacks above the title. At >=600dp it uses a larger poster; Home grid adapts to available width and font scale. Main CTA and existing icon actions retain >=48dp targets. Descriptive status words supplement color; heading semantics and merged card labels expose full titles.

FULL retains press scale and existing shared transitions; REDUCED has opacity feedback without decorative movement; OFF uses final opacity/progress and zero-duration navigation. The app theme still handles system animation settings on resume. No carousel timer, continuous effect or new blur.

## Themes

Vault and AMOLED retain current color roles and surface implementation. MIDNIGHT and DYNAMIC remain selectable settings, but the current AnimeVaultTheme only branches for OLED; separate Midnight/system Dynamic palettes are not implemented by this change. No claim of complete theme support or runtime verification.

## Verification

Local only: `git diff --check`, lexical delimiter balance, duplicate import review, comparison of public component signatures and callback call sites, preview fixtures and changed-path scope inspection. No Gradle, APK build, instrumentation, screenshots or device checks were run locally. Static checks do not establish compilation.

HomePreviews provides synthetic normal/missing-poster/long-title/resume/empty/error/loading/partial data at 320/360/600/840dp, landscape and fontScale=2. Dedicated reduced/OFF and media-component previews are included. Preview functions instantiate no ViewModel or repository; VaultArtwork suppresses AsyncImage in LocalInspectionMode. Previews are authored, not rendered or accepted as visual proof.

GitHub Actions must compile, run its existing checks and produce the APK for the PR branch. Real-device QA: API 24 and 31+, 320/360dp and tablet, portrait/landscape, font scales 1/1.3/2, all theme settings, FULL/REDUCED/OFF/system animation scale 0, TalkBack order/labels/progress, contrast over bright posters, touch targets, resume of local and online episodes, favorites and retry, back/scroll preservation and rapid interrupted transitions. Shared hero/artwork changes also require title-page smoke checks. Measure scrolling before any performance claim.

## Next phases

1. Title detail and episode lists: clear season/episode hierarchy and consistent actions.
2. Library, downloads and search: state-driven layouts with shared poster/status components.
3. Player controls and dedicated theme completion, each under a separate agreed scope and verification plan.
