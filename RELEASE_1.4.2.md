# AnimeVault 1.4.2 — Navigation Shell & Unified History

1.4.2 переводит AnimeVault от набора связанных экранов к единому медиапространству. Главные разделы теперь живут в постоянном адаптивном navigation shell, а история локального и онлайн-просмотра впервые собрана в одном root destination.

## Navigation shell

Корневые разделы:

- Главная;
- Медиатека;
- Онлайн;
- История.

На компактной ширине используется нижняя `NavigationBar`. Начиная с 720dp она заменяется на `NavigationRail`. Detail screens, Settings, Online Library и Player не показывают root navigation, чтобы сохранить кинематографичный полноэкранный режим.

Root navigation использует `launchSingleTop`, `saveState` и `restoreState`: состояние каталога и медиатеки сохраняется при обычном переключении разделов.

Старый локальный переключатель `Медиатека / Онлайн` удалён из обоих экранов как дублирующий.

## Unified History

Новый экран `История` объединяет два источника данных:

- локальные playback events из Room `watch_progress`;
- persistent online history из `OnlineLibraryStore`.

Лента сортируется по времени последнего просмотра/открытия и поддерживает фильтры `Все`, `Локально`, `Онлайн`.

Локальная запись показывает тайтл, сезон/серию, прогресс, статус завершения и может открыть тайтл либо сразу запустить серию. Онлайн-запись показывает provider, последнюю серию и позволяет открыть релиз либо продолжить последнюю известную серию.

## Data layer

Добавлен `LocalHistoryRow` и `LibraryDao.observeLocalHistory()` с лимитом 500 последних локальных playback entries. Схема Room не менялась: query использует уже существующие `watch_progress`, `episodes`, `titles`, `title_metadata` и `offline_online_links`.

`LibraryRepository.observeHistory()` предоставляет поток UI-слою.

## Tests

Добавлен `HistoryViewModelTest`, проверяющий:

- слияние local + online по timestamp;
- фильтр Local;
- игнорирование online entries без history state.

## Проверка

```bash
bash tools/verify-source.sh
bash tools/verify-final.sh
bash tools/verify-1.4.2.sh
./gradlew testDebugUnitTest --stacktrace
./gradlew lintDebug --stacktrace
./gradlew assembleDebug --stacktrace
```
