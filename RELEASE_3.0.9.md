# AnimeVault 3.0.9 (69) — Предпочитаемый язык названий

База: `main`, `d28c830`. Ветка: `feature/title-language`.

## Архитектура и поведение

Настройка: Настройки → Интерфейс → «Предпочитаемый язык названий».
Варианты: Русский (по умолчанию), Международный, Японский.
Выбор сохраняется существующим `UiPreferences` в SharedPreferences с ключом
`anime_title_language`, публикуется через StateFlow. Это механизм реального
проекта для UI-настроек; отдельная миграция в DataStore не вводилась.
MainActivity собирает настройку и индекс метаданных через
`collectAsStateWithLifecycle` и передаёт единый `LocalAnimeTitles` в Compose.

`AnimeTitleResolver` выбирает строку, сохраняя отдельно AnimeNames:
`russian`, `name` (ромадзи), `english`, `japanese`, `synonyms`.

| Выбор | Последовательность fallback |
| --- | --- |
| Русский | russian → english → name → japanese → оригинал → synonyms |
| Международный | english → name → russian → japanese → оригинал → synonyms |
| Японский | japanese → name → english → russian → оригинал → synonyms |

Пустые строки и строковый `null` пропускаются. Переводы не генерируются.
Для неразмеченных оригиналов источников кириллица используется как fallback
русского имени, существующее альтернативное/английское имя — как name.
Названия не заменяют provider/release/local ID и не записываются в файлы,
прогресс, очереди загрузок или синхронизацию.

Область подключения: главная, продолжение просмотра, каталог и рекомендации,
результаты поиска, полная и свёрнутая шапка аниме, избранное/библиотека,
локальная библиотека, история, загрузки, календарь, импортированные коллекции
Shikimori. Новые уведомления о сериях и очередные обновления уведомлений
загрузки получают названия из того же resolver. Уже опубликованные уведомления
не пересоздаются при переключении настройки.

## Кеш и безопасность сопоставления

`AnimeTitleStore` — отдельный восстанавливаемый кеш до 4000 записей. Он хранит
исходные варианты и явные ID-связи, публикует StateFlow; запись JSON на IO
сгруппирована с debounce 300 мс. Кеш переживает обычный перезапуск; повреждённый
JSON отбрасывается. Последняя запись при аварийном завершении может не успеть
сохраниться; исходная библиотека от этого не зависит.

Обогащение происходит из уже выполняемых запросов AniList, Shikimori и Tenrai.
У AniLiberty дополнительно сохранено поле alternative. Текущие поля name и
englishName остальных источников остаются исходными данными. Локальные связи
восстанавливаются из `observeAllTitleMetadata` Room; удалённые локальные
привязки снимаются. Новые Room-таблицы и миграции не нужны.

Пространства ключей разделены: `local:`, `provider:`, `anilist:`, `mal:`,
`shikimori:`. Совпадение чисел либо текста не связывает аниме. Связь источника
с переводами допускается только по его существующим externalIds. Сезоны и
ремейки не объединяются на основании названия. У AniList fallback без MAL ID
добавлена отдельная метка, чтобы его числовой AniList ID не использовать как
MAL-ключ в механизме названий.

В календаре русское имя применяется по **возвращённому malId**, проверенному
против запрошенных MAL ID, а не по предположению о равенстве Shikimori/MAL ID.
Коллекции сохраняют новые языковые поля рядом со старым title в прежнем JSON;
старый формат читается с fallback на сохранённое название.

Переключение языка не вызывает запросов. Если у источника нет подтверждённого
ID или перевод не загружен, показывается доступный fallback. Автоматический
поиск соответствия по тексту при отрисовке запрещён.

## Поиск

Поиск локальной библиотеки проверяет все известные поля и synonyms независимо
от языка интерфейса. Исходный поисковый запрос у провайдера не локализуется.
Если он успешно вернул пустой список, допускаются до двух альтернативных
запросов по точным известным названиям. Если вариантов нет, выполняется
пользовательский поиск метаданных в Shikimori и при необходимости AniList;
полученные названия сохраняются под их собственными ID. Это не создаёт
привязку результатов источника по текстовому сходству.

Ошибки основного запроса продолжают передаваться вызывающему коду. Ошибки
дополнительного поиска не заменяют успешный результат; отмена корутин
пробрасывается. Поиск неизвестных/частичных альтернативных названий зависит
от возможностей внешнего API; офлайн доступны уже сохранённые варианты.

## Проверки и ограничения

Выполнены только лёгкие статические проверки:

- `bash tools/verify-final.sh`: XML, шаблоны исходников, provider config и
  контроль версии/Room — успешно.
- `python3 tools/verify-makima-branding.py`: ресурсы, XML и ссылки — успешно.
- CLI версии исходников против `v3.0.9`: 3.0.9 (69), успешно.
- `git diff --check`: без замечаний.
- Tree-sitter Kotlin: изменённые/новые Kotlin-файлы разобраны без новых ошибок.
  У EpisodeAlertWorker есть ограничение грамматики парсера на существующем
  WorkManager builder; тот же участок main даёт ту же ошибку. Это не компиляция.
- Просмотр diff: applicationId, SDK, signing, Room schema 8, backup format 4,
  Media3/mpv, обновления APK и workflows не изменены. DownloadWorker изменён
  только в формировании текста уведомления, не в скачивании.

Добавлены **10 JUnit-регрессий** в AnimeTitleResolverTest: три языка, fallback,
null/пустые поля, альтернативный поиск, default/сохранённое значение языка,
коллизии ID разных API, сезоны, JSON round-trip метаданных/локальной связи,
частичное обогащение, ограничение поисковых вариантов.

**JUnit не запускались.** Gradle, Android SDK, эмуляторы, APK/AAB, lint и
Android unit/instrumentation tests не запускались по условию задачи.
Компиляция, реальное переключение Compose, cold restart, работа ОС-уведомлений,
поиск у живых API и восстановление существующей библиотеки требуют GitHub
Actions и устройства. Не утверждается, что эти сценарии уже проверены runtime.
Пользовательский backup не расширен новым UI-предпочтением и кешем: после
переноса данных на новую установку язык по умолчанию русский, кеш заполняется
по мере использования. Существующие данные не мигрируются и не удаляются.

Версия повышена с 3.0.8 (68) до **3.0.9 (69)**. Целевой тег: `v3.0.9`.
APK собирает только прежний GitHub Actions workflow; ручной запуск не сделан.

## Изменённые файлы

- `CHANGELOG.md`
- `README.md`
- `RELEASE_3.0.9.md`
- `app/build.gradle.kts`
- `app/src/main/java/com/sergey/animevault/AnimeVaultApplication.kt`
- `app/src/main/java/com/sergey/animevault/MainActivity.kt`
- `app/src/main/java/com/sergey/animevault/data/aniliberty/AniLibertyProvider.kt`
- `app/src/main/java/com/sergey/animevault/data/download/DownloadWorker.kt`
- `app/src/main/java/com/sergey/animevault/data/metadata/AiringScheduleClient.kt`
- `app/src/main/java/com/sergey/animevault/data/metadata/AniListMetadataRepository.kt`
- `app/src/main/java/com/sergey/animevault/data/metadata/ShikimoriExtrasRepository.kt`
- `app/src/main/java/com/sergey/animevault/data/metadata/TenraiAniListFallback.kt`
- `app/src/main/java/com/sergey/animevault/data/metadata/TenraiMetadataRepository.kt`
- `app/src/main/java/com/sergey/animevault/data/notifications/EpisodeAlertWorker.kt`
- `app/src/main/java/com/sergey/animevault/data/online/OnlineModels.kt`
- `app/src/main/java/com/sergey/animevault/data/online/OnlineRepository.kt`
- `app/src/main/java/com/sergey/animevault/data/titles/AnimeTitleResolver.kt`
- `app/src/main/java/com/sergey/animevault/data/titles/AnimeTitleStore.kt`
- `app/src/main/java/com/sergey/animevault/data/titles/TitleIngestion.kt`
- `app/src/main/java/com/sergey/animevault/ui/calendar/AnimeCalendarScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/downloads/DownloadsScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/history/HistoryScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/home/HomeScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/library/LibraryHubScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/library/LibraryScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/library/LibraryViewModel.kt`
- `app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineLibraryScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleExtrasSection.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/preferences/UiPreferences.kt`
- `app/src/main/java/com/sergey/animevault/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/shikimori/ShikimoriCollectionRepository.kt`
- `app/src/main/java/com/sergey/animevault/ui/shikimori/ShikimoriCollectionsScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/title/AnimeTitlePresentation.kt`
- `app/src/main/java/com/sergey/animevault/ui/title/TitleDetailScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/title/UnifiedTitleUi.kt`
- `app/src/main/res/values-en/strings.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/sergey/animevault/data/titles/AnimeTitleResolverTest.kt`
