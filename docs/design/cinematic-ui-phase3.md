# Cinematic UI Phase 3 — карта возможностей и handoff

База: origin/main 521f294, Phase 2 включена, версия 3.4.0 (77).
Главный сценарий: найти сохранённый тайтл/серию, открыть результат поиска или управлять настоящей загрузкой.

## Существующая функциональность до изменений

| Экран/владелец | Что сохраняется |
|---|---|
| LibraryHubScreen / LibraryHubViewModel | Local/online join с разными ключами, продолжение, избранное, planned/watching/on-hold/watched/dropped/rated/local/all, личные списки; persisted tab, CSV с preview, подписки, история, папки |
| LibraryScreen | SAF-папки, поиск, SmartCollection, сортировка, list/grid/compact, локальная библиотека и progress |
| OnlineLibraryScreen | История/избранное онлайн, фильтры, progress, переходы, удаление записей |
| OnlineCatalogScreen / ViewModel | Provider/all sources, generation/cancellation/debounce, cache/local matches, alias/fuzzy suggestions, history/recent, genre/year/type/status/rating/episodes, thematic/advanced filters, presets, reset, sort, grid/list, load more, diagnostics |
| CatalogAdvancedDiscovery | Только client-side фильтрация уже загруженных страниц; не серверный поиск |
| SearchHistory | Trim, case-insensitive dedupe, bounded history и removal; не меняется |
| DownloadsScreen / Presentation / ViewModel | Поток DownloadEntry, stable id, фильтры, реальные bytes/speed/ETA/status, pause/resume/remove, play completed, подтверждение удаления; engine остаётся владельцем файлов и очереди |

Персональные/импортированные коллекции открываются прежними маршрутами; новые типы списков и новые бизнес-операции не создаются. CSV и папки не заменяются декоративными кнопками.

## Решения

Использовать VaultPosterCard/Panel/SearchField/FilterChip/EmptyState/SkeletonBlock/WatchProgressBar и существующие VaultSpacing/Size/Radius. Предпочитать lazy grids/lists, стабильные ключи, сохранённый scroll state, memoized filters и callback identities. Не выполнять сортировку при каждом scroll frame и не анимировать сотни элементов.

Состояния различаются по существующим сообщениям/health/error данным. Нельзя объявлять «нет сети» или «нужен вход» только потому, что список пуст; неизвестная ошибка остаётся неизвестной. Частичная выдача остаётся доступной. Нельзя обещать работу внешних источников после одной UI-проверки.

## Проверка на телефоне

320/360/600/840dp, landscape, fontScale 1–2, TalkBack, четыре настройки тем, FULL/REDUCED/OFF, открытая клавиатура. Проверить query→filter→Back, историю/подсказки, provider/all sources, быстрый A→B→A поиск, пагинацию, reset без очистки query, long titles, большие библиотеки, CSV, папки и коллекции. Загрузки: все десять статусов, pause/resume/retry/play/remove, подтверждение и реальное удаление файлов.

Отдельные Midnight/Dynamic палитры, измеренная производительность и доступность источников не подтверждаются этой работой. Preview создаются с локальными моделями; рендер и device QA в Codex не выполняются. Локально разрешены статические проверки; Gradle/APK/tests выполняет GitHub Actions.
