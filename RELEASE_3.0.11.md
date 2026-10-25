# AnimeVault 3.0.11 (71) — Stability & Reliability

База: `origin/main` **98c8176a7b1ec05bb1de9b1a491d8846d9828fec**,
3.0.10 (70). Ветка `fix/3.0.11-stability-reliability`.
Открытые PR проверены перед правками: только исторический #1 (редизайн 1.5.0),
дублирующей разработки стабильности не найдено. Итоговый SHA — коммит этого PR.

## Матрица предварительного аудита

| Подсистема | Уже реализовано | Доказанный пробел | Действие |
| --- | --- | --- | --- |
| Релизы | publisher, draft list fallback, version/hash gate, pinned tags, retry own draft, serialized publish | publish() без проверки assets пропускает любой public release; subprocess gh не ограничен по времени | Проверять наличие единственных непустых APK/JSON, не перезаписывать; timeout 120 с на gh |
| Updater | versionCode > installed, hash/размер/package/версия/SDK/signers, повтор verify перед installer, busy gate, очистка part | cancelDownload можно нажать несколько раз и создать несколько join-callback; повреждённый JSON даёт общее сообщение | Один cancellation job; отдельное сообщение о JSON |
| Источники | capabilities, каналы каталог/плеер/download, runtime health, ручная диагностика, preflight | OnlineHttp теряет HTTP-код как данные; вставляет тело ответа; Retry-After не учитывается, cooldown только после серии ошибок | Типизированный IOException, безопасное сообщение, Retry-After seconds/date, пауза с первого 429; ручная проверка соблюдает её |
| Поиск | debounce, cancel job, provider/query snapshots, supervisor partial results, alias lookup/resolver, history | snapshot по строке принимает устаревший ответ повторного одинакового запроса (A→B→A); pagination может стартовать в debounce | Поколение запросов; запрет loadMore при активном job; первая страница dedup по provider/id |
| Плеер | Media3 owner/release, lifecycle, refresh/fallback, ограниченные retry, restore/skip/autoplay policies | onPlayerError вызывает recovery без немедленного сохранения позиции и пишет URL + exception; failed timeline может дать 0 при disposal/capture | Сохранить позицию до callback, удержать snapshot при zero/error, логировать только errorCodeName |

Это выводы чтения кода, не воспроизведение на устройстве. Успешная сборка
3.0.10 не означает доступность всех внешних сервисов сегодня.

## Аудит 12 адаптеров

Ниже **контракт исходников**, а не результат живых сетевых проверок.
«Да» для прямого потока означает наличие resolver, а не гарантию для любого
релиза. Общее воспроизведение EMBED обслуживается существующим WebView fallback.

| Адаптер / файл в data | Каталог | Текстовый поиск | Эпизоды | Прямой resolver | EMBED в адаптере | Скачивание | Намеренные условия |
| --- | --- | --- | --- | --- | --- | --- | --- |
| aniliberty/AniLibertyProvider.kt | да | да | да | да | нет явного EMBED | да | API, без отдельного аккаунта |
| animevost/AnimeVostProvider.kt | да | да | да | да | нет явного EMBED | да | разрешение ссылок провайдера |
| animetka/AnimetkaProvider.kt | да | да | да | да | нет явного EMBED | **нет** | ANIMETKA_DOWNLOADS_ENABLED=false, осознанное ограничение |
| kodik/KodikProvider.kt | да | да | да | да | да | для direct | experimental, optional token / bundled fallback |
| animelib/AnimeLibProvider.kt | да | да | да | да, в том числе Kodik | да | для direct | experimental, Site-Id и заголовки |
| dreamerscast/DreamerscastProvider.kt | да | да | да | да | нет явного EMBED | да | experimental |
| animedia/AniMediaProvider.kt | да | да | да | да | нет явного EMBED | да | зависит от внешних страниц/потоков |
| animeon/AnimeOnProvider.kt | **нет** | да | да | да | да | для direct | общий каталог отключён |
| sameband/SameBandProvider.kt | да | да | да | да | нет явного EMBED | да | experimental |
| animebest/AnimeBestProvider.kt | **нет** | да | да | да | да | для direct | общий каталог отключён |
| jutsu/JutSuProvider.kt | **нет** | **нет** | да | MP4 | нет явного EMBED | да | experimental; поиск по URL/slug, не общий текст |
| yummy/YummyAnimeProvider.kt | да | да | да | HLS / Kodik | да | для direct | experimental, X-Application/настройка доступа |

Для всех сохраняются ошибки транспортного слоя, parser/API и отсутствие потоков.
Общий путь OnlineHttp теперь сохраняет HTTP 401/403/404/429/5xx, не раскрывая body;
Retrofit HttpException остаётся отдельным поддержанным путём с Retry-After.
DNS/timeout/TLS уже классифицируются через цепочку cause. Пустая успешная выдача
не превращается в исключение. Parser/unsupported-stream остаются существующими
ошибками адаптера/PlaybackFailureClassifier; новый набор категорий не создавался.
EMBED не объявляется пригодным для offline download. Источники не удалены.

## Релизы и updater: что сохраняется

GitHub 404 означает отсутствие только для соответствующего endpoint; поиск draft
через пагинированный список сохранён. 401/403/429/5xx и timeout не считаются
отсутствием релиза. Неполный public release теперь даёт ошибку с запретом
перезаписи; автоматическое исправление опубликованных assets не выполняется.
Ошибочный SHA/JSON/versionCode отклоняются существующим verifier до GitHub API.
Сохраняются pin точного SHA, проверка устаревшего main и code latest,
возобновление собственного draft, запрет чужого draft, concurrency publish-job.

Updater не меняет JSON schema 1. Проверки hash, APK identity и signers не
ослаблены. Missing manifest/APK, неполный файл, неверный размер/hash, новый SDK
дают существующий отказ; installed versionCode защищает от downgrade.
Повторные downloads блокируются busy-state. Основные операции IO ограничены
имеющимися OkHttp timeout, редиректы ограничены и разрешены только GitHub HTTPS.
Новая загрузка идёт от исходного URL GitHub asset, а не сохранённой CDN-подписи.
После отказа install готовый файл не принимается без повторной проверки.

## Диагностика и поиск

Раздел «Источники» уже содержит «Диагностику источников», ручную проверку,
capabilities и независимые каналы каталог/плеер/download. Нового экрана нет.
Добавлена дата/время самой свежей проверки каналов. Успешный каталог не
приравнивается к рабочему потоку. Ручная проверка не проигрывает видео: она
использует существующий resolver/preflight. Пауза Retry-After не обходится
повторным нажатием ручной проверки. Остальные провайдеры не блокируются.

Поиск из календаря и Shikimori коллекций продолжает передавать исходный запрос.
AnimeTitleResolver, язык по умолчанию, namespaces ID, сохранённые настройки и
предел двух alias-попыток не изменены. Unified provider сохраняет supervisorScope,
ограниченный параллелизм и успешные ответы при отказе соседнего источника.
Отмена остаётся CancellationException; новый generation проверяется и для
успеха, и для ошибки. Ни сезоны, ни разные provider/id не объединяются новой
логикой dedup. Повтор/история запросов сохраняются.

## Плеер

Единственный Media3 instance и DisposableEffect-release сохраняются. До
передачи ошибки в refresh/fallback записывается незавершённый progress. Если
timeline failed player сообщает ноль, disposal использует snapshot ошибки;
положительная позиция (включая перемотку назад) имеет приоритет. Существующий
credibility gate завершения серии, policy выбора вариантов, ограничения retries,
prefetch, tracks/subtitles и OAuth/Room не переписывались. Лог этой ошибки
больше не содержит signed URL, заголовки или stack trace с URL.

## Проверки

В Codex выполнены: 18 publisher + 14 version-gate + 7 manifest Python tests =
**39 успешных тестов**; verify-final.sh; синтаксис Kotlin через tree-sitter;
XML/ресурсы брендинга; Python/YAML/Bash syntax; git diff --check.

Добавлены Kotlin-регрессии HTTP classification/Retry-After/date, изоляции каналов,
поколений поиска, позиции failed player, повреждённого updater JSON и повторной
отмены/загрузки. Они **не запускались**: Gradle, SDK, эмуляторы, APK/AAB в Codex
запрещены. Существующие тесты checksum/identity, partial unified failure,
unsupported capabilities, refresh, progress и natural-end сохранены.

В существующий build workflow добавлена только эта точная ветка, чтобы после
единственного push выполнить в GitHub Actions compile, unit, lint, APK и
onboarding API 24/35 **до слияния**. По дополнительному требованию владельца job publish-release
удалён полностью: релизы и теги создаёт владелец вручную, Actions только
собирает и проверяет артефакты. Релиз/тег не создавались.
CI-status и итоговый SHA указаны в PR/ответе.

Для готовности к слиянию нужен зелёный CI. Для окончательной runtime-проверки:
реальное обновление с 3.0.10 с сохранением данных, повреждённый/недоступный APK,
сеть offline/429/timeout, русские/английские/японские запросы и A→B→A,
HLS master/segment errors, refresh истёкшей ссылки, pause/resume/rotate,
quality/audio/subtitles, decoder failure и следующий эпизод на устройстве.
Доступность всех 12 сервисов и поведение декодеров в этом PR не объявляются
проверенными. API 24+, applicationId, подпись, Makima branding/intro,
AniList OAuth, Room schema 8, backup format 4 не меняются.

## Дополнение по CI и ручным релизам

Первый запуск 40160732894: build (compile/unit/lint/APK/signature/JSON) и API35
прошли; API24 получил timeout 300 с в четвёртом instrumentation-тесте.
Причина зависания по доступному логу не подтверждена. Проверки не отключены и
таймаут не увеличен. Архив диагностики доступен в Actions, но его загрузка в
Codex заблокирована сетью. Этот сбой не объявляется исправленным.

После первого push владелец отдельно потребовал убрать автопубликацию.
Дополнительный push выполняется для этого нового требования; версия остаётся
3.0.11 (71). Исходный коммит 13677fc будет заменён amended-коммитом, один PR
и один итоговый коммит сохраняются.

## Изменённые файлы

- `.github/workflows/build-apk.yml`
- `CHANGELOG.md`
- `README.md`
- `RELEASE_3.0.11.md`
- `app/build.gradle.kts`
- `app/src/main/java/com/sergey/animevault/data/online/OnlineHttp.kt`
- `app/src/main/java/com/sergey/animevault/data/online/OnlineRepository.kt`
- `app/src/main/java/com/sergey/animevault/data/online/ProviderHealthTracker.kt`
- `app/src/main/java/com/sergey/animevault/data/online/ProviderHttpException.kt`
- `app/src/main/java/com/sergey/animevault/data/playback/PlaybackCore.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogViewModel.kt`
- `app/src/main/java/com/sergey/animevault/ui/player/OnlinePlayerScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/update/UpdaterViewModel.kt`
- `app/src/test/java/com/sergey/animevault/data/online/ProviderHttpFailureTest.kt`
- `app/src/test/java/com/sergey/animevault/ui/online/CatalogRequestGenerationTest.kt`
- `app/src/test/java/com/sergey/animevault/ui/player/OnlinePlayerRefreshTest.kt`
- `app/src/test/java/com/sergey/animevault/ui/update/UpdaterViewModelTest.kt`
- `tools/publish-github-release.py`
- `tools/test_publish_release.py`

- `docs/AUTOMATIC_RELEASES.md` (обновлён после требования ручных релизов)
