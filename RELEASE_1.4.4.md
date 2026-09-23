# AnimeVault 1.4.4 — Library & Online Polish

1.4.4 завершает следующий слой редизайна после Navigation Shell и Unified Title: медиатека становится управляемой по плотности, онлайн-поиск запоминает полезные запросы, а карточки каталога и серий содержат больше информации без новых экранов.

## Медиатека

Добавлены три режима отображения:

- `POSTER_GRID` — крупная визуальная сетка;
- `COMPACT_GRID` — более плотная сетка для больших библиотек;
- `LIST` — постер, название, прогресс и связанный online state в одной строке.

Режим выбирается через bottom sheet `Вид медиатеки` и сохраняется в `UiPreferences`.

## Online search assist

Поле поиска теперь поддерживает focus-aware assist panel:

- недавние успешные запросы;
- удаление одного запроса;
- очистку истории;
- недавно открытые online releases с постером и источником.

История обновляется только после успешной первой страницы поиска. Промежуточные строки debounce-ввода в неё не попадают.

## Persistent UI preferences

Добавлен небольшой UI-level preferences store. Он сохраняет:

- layout локальной медиатеки;
- grid/list layout онлайн-каталога;
- последние восемь успешных online search queries.

Room schema и backup format не менялись.

## Более насыщенные карточки

Каталог показывает provider name рядом с метаданными релиза.

Локальная episode card показывает `LOCAL`, расширение файла и размер. Online episode card показывает лучшее известное качество, озвучку/число озвучек и source name, если провайдер передал эти данные.

## Regression constraints

Не менялись:

- Room schema/version;
- SAF scanning;
- Unified Title routes;
- online provider contracts;
- playback/failover policy;
- watch-progress persistence;
- AniList sync.

## Проверки

```bash
bash tools/verify-source.sh
bash tools/verify-final.sh
bash tools/verify-1.4.4.sh
./gradlew testDebugUnitTest --stacktrace
./gradlew lintDebug --stacktrace
./gradlew assembleDebug --stacktrace
```
