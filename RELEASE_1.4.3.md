# AnimeVault 1.4.3 — Unified Title

1.4.3 объединяет локальный и онлайн-detail на уровне UI-модели, навигации и доступности контента. Локальная копия и сетевые источники больше не выглядят как два независимых объекта.

## Unified title model

Добавлены `UnifiedTitleUiModel`, `UnifiedTitleSourceUi` и `UnifiedTitleOrigin`. Общий shell получает название, постер, метаданные, прогресс, локальную доступность и онлайн-источники вне зависимости от происхождения тайтла.

Origin вычисляется как:

- `LOCAL` — есть локальный тайтл, но нет связанного online release;
- `ONLINE` — открыт сетевой релиз без локальной связи;
- `HYBRID` — локальная копия и online source существуют одновременно.

## Unified availability

Под hero появился общий блок `Доступность`.

Для локального тайтла он показывает число серий на устройстве и все связанные online releases. Источник можно открыть непосредственно из этой зоны.

Для онлайн-релиза AnimeVault использует существующую Room-связь `offline_online_links`, выполняет reverse lookup по `(provider_id, online_release_id)` и показывает связанную локальную копию, количество серий и переход в неё.

Схема Room не менялась, миграция не требуется.

## Navigation

Detail destinations перенесены в один route-family:

- `media-title/local/{titleId}`;
- `media-title/online/{providerId}/{releaseId}`.

Это сохраняет разные идентификаторы источника, но визуально и архитектурно делает detail одной подсистемой.

## Regression constraints

Не менялись:

- SAF scanning;
- Room schema/version;
- local playback;
- online provider contracts;
- online stream selection/failover;
- progress persistence;
- AniList OAuth/sync.

## Проверки

Запустить:

```bash
bash tools/verify-source.sh
bash tools/verify-final.sh
bash tools/verify-1.4.3.sh
./gradlew testDebugUnitTest --stacktrace
./gradlew lintDebug --stacktrace
./gradlew assembleDebug --stacktrace
```
