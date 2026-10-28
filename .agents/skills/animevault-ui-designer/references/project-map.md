# Карта проверенного проекта

Снимок: 2026-10-28, commit `a2778ce20671f8b2eddaa21653513daac33473e6`.
Корень при проверке: `/workspace/AnimeVault`. Пути ниже относительно `app/src/main/java/com/sergey/animevault/`.
Перед будущими изменениями перечитай исходники: карта не заменяет текущий код.

| Задача | Существующий ресурс |
|---|---|
| Цвет и темы | `ui/theme/Color.kt`, `Theme.kt`, `MediaAccent.kt` |
| Типографика, формы | `ui/theme/Typography.kt`, `Shape.kt` |
| Размеры, интервалы, motion | `ui/design/Spacing.kt`, `Animation.kt` |
| Поверхности | `ui/design/VaultSurfaces.kt`: VaultPanel, VaultInteractivePanel |
| Постеры и фон | `ui/components/VaultPosterCard.kt`, `VaultArtwork.kt`, `VaultCinematic.kt`: VaultAdaptiveHero |
| Общие элементы | `ui/components/VaultComponents.kt`: VaultSearchField, VaultSectionHeader, VaultFilterChip, VaultStatusPill, VaultPrimaryButton, VaultEmptyState, VaultSkeletonBlock |
| Прогресс | `ui/components/WatchProgressBar.kt`, `VaultWatchSummary.kt` |
| Навигация | `ui/navigation/VaultNavigationShell.kt`, `AnimeVaultApp.kt`, `VaultSharedTransitions.kt` |
| Главная | `ui/home/HomeScreen.kt`, `HomeFeed.kt` |
| Тайтл | `ui/title/TitleDetailScreen.kt`, `ui/online/OnlineTitleScreen.kt` |
| Библиотека | `ui/library/LibraryHubScreen.kt`, `LibraryScreen.kt`, `ui/online/OnlineLibraryScreen.kt` |
| Загрузки | `ui/downloads/DownloadsScreen.kt`, `DownloadsPresentation.kt` |
| Поиск/каталог | `ui/online/OnlineCatalogScreen.kt`, `CatalogAdvancedControls.kt` |
| Плеер | `ui/player/PlayerScreen.kt`, `PlayerControls.kt`, `PlayerChrome.kt`, `OnlinePlayerScreen.kt` |
| Настройки | `ui/preferences/AppearanceSettings.kt`, `ui/settings/SettingsScreen.kt` |

`app/build.gradle.kts`: minSdk 24; Compose, Material 3 и Coil уже подключены.
`VaultPosterCard`: постер 2:3, радиус 12dp, titleSmall, до двух строк названия.
`VaultAdaptiveHero`: переключение композиции при 600dp; постер 98/144dp, параллакс только FULL.
Навигация уже содержит нижнюю панель и NavigationRail.

## Ограничения текущего снимка

- В `AppearanceSettings.kt` объявлены четыре темы, но `AnimeVaultTheme` ветвится по OLED; отдельная Midnight и системная Dynamic палитры в прочитанной функции не реализованы. Их наличие в меню не доказывает работу палитры. Спецификация сохраняет их как требования, не утверждает готовность.
- `effectiveMotion` переводит MINIMAL в REDUCED. Для решений используй эффективный режим из CompositionLocal, не предполагай отдельную реально достижимую MINIMAL-анимацию.
- `vaultAccentFor` возвращает постоянный VaultVioletBright; не обещай автоматическую генерацию палитры из постера.
