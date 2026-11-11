# Технический аудит AnimeVault

Дата: 2026-11-11. Проверенная база — актуальная `main` после PR #59,
`6d17076f5b8ae9b830c1beb0ce38e311d0f96eef`. Все ссылки и номера строк ниже относятся
к этой ревизии. Аудит подготовлен в ветке `docs/animevault-technical-audit`.
Изменены только этот документ и [план модернизации](ANIMEVAULT_MODERNIZATION_ROADMAP.md).

## Метод и границы доказательств

Использованы AGENTS.md и установленные навыки: animevault-architecture,
animevault-media-downloads, animevault-network-sources, animevault-persistence,
animevault-security, animevault-testing, animevault-build-release,
animevault-ui-designer; рекомендации jetpack-compose-performance использованы
для плана измерений, с учётом проектных ограничений версий.
Для UI прочитаны проектная карта, checklist, cinematic guidance и правила источников;
историческая карта не использовалась вместо актуального кода.

Прослежены AppContainer/Activity → Compose/ViewModel → repositories →
providers/HTTP/Room/WorkManager, online/local playback, подготовка сезона,
native download → verifier → MediaStore → импорт в библиотеку, SAF scan → pruning.
Проверены auth callbacks, encrypted storage, backup, updater, Gradle и workflows.
Это обследование основных путей и целевой поиск дефектов, а не построчная формальная
верификация каждого файла и каждого внешнего сервиса.

Статусы находок:

- **Дефект, код:** конкретный нарушенный контракт следует из указанной реализации.
  Это не означает, что сценарий уже воспроизведён на Android-устройстве.
- **Дефект, воспроизведён:** дополнительно выполнен изолированный эксперимент
  на собранных классах проекта; границы эксперимента указаны.
- **Риск:** есть конкретный путь/предпосылка, но проявление или продуктовый контракт
  ещё надо проверить. Не приравнивается к подтверждённой аварии.
- **Предложение:** улучшение измеримости/поддержки; не утверждение о поломке.

Серьёзность: **высокая** — потеря состояния, неправильный медиафайл/эпизод либо
существенный пробел проверки обновления данных; **средняя** — отказ сценария,
незавершённая операция, управляемый ресурсный/конкурентный риск; **низкая** —
метаданные, поддерживаемость и улучшения UX. Подтверждённых критических дефектов
в обследованной области не установлено. Это не доказательство их отсутствия.

## Проверки и факты о сборке

| Проверка | Результат и предел вывода |
|---|---|
| `git fetch origin main` | `main` соответствует базе `6d17076`; до аудита дерево чистое |
| JDK17, SDK37; `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` | BUILD SUCCESSFUL, 56 tasks: 1 executed, 55 up-to-date; повторное использование build outputs отмечено явно |
| `:app:testDebugUnitTest --rerun` | BUILD SUCCESSFUL; 634 tests / 135 suites, 0 failures/errors/skipped; тестовая задача исполнена заново |
| Lint XML | 0 Error/Fatal, 66 Warning и отдельно 5 Hint; девять ранее средних разобраны ниже |
| CI на самой `main` | [Build APK](https://github.com/Aqua666-prog/AnimeVault/actions/runs/42969384543) и [AI Skills](https://github.com/Aqua666-prog/AnimeVault/actions/runs/42969384643) — success на `6d17076` |
| Последний полный PR CI | [Community PR verification](https://github.com/Aqua666-prog/AnimeVault/actions/runs/42898396334) — success на head PR #59; его scope не равен полному Android regression |
| `adb devices -l` | Список устройств пуст; device tests в этой сессии не запускались |
| Сетевой probe | Только собственный localhost HTTP fixture; внешние CDN/auth не нагружались |

Заявленные версии сохранены: AGP 9.3.2 / Gradle 9.5.0, Compose compiler plugin
2.4.10 / BOM 2026.06.01, Media3 1.11.0, Room 2.8.4, WorkManager 2.11.2,
DataStore 1.1.7, Retrofit 3.0.0, coroutines 1.10.2. Предыдущее dependencyInsight
зафиксировало OkHttp 4.12.0, Compose UI/runtime 1.11.4, Material 3 1.4.0;
в рамках аудита зависимости не менялись. Java fixture ниже использовал доступный
Gson 2.13.1 и Kotlin stdlib 2.4.10 из существующего Gradle cache.

Не выполнялись: инструментальные migration/download/player тесты на устройстве,
TalkBack, замеры startup/frame timing/heap/battery, реальные длительные HLS/MP4
загрузки, live smoke всех providers, тестирование аккаунтов, production pentest,
полный dependency CVE scan и полный MASVS/MASTG assessment.
Для источников это аудит адаптеров и имеющихся fixtures, а не обещание текущей
доступности сайта или CDN. Статус «ошибок не найдено» нигде не заменяет тест.

## Что уже устроено разумно

- Один `:app`, ручная DI в AppContainer, явные ViewModels/StateFlow и repositories.
  Нет основания мигрировать весь проект на Hilt, KMP или многомодульную схему.
- Есть общий контракт provider/capabilities, endpoint registry, health tracking,
  ограниченные repository request caches и разделение metadata/stream resolution.
- У плееров есть симметричный release, MediaSession cleanup и session bridge;
  online player учитывает ON_STOP и PiP. Это не похоже на необходимость замены Media3.
- Загрузки уже используют operation tokens, Room, retry policy, проверку видео,
  sequential season work и encrypted source storage. Эти механизмы нужно дополнить
  на конкретных границах, а не заменять DownloadManager-ом.
- Основная БД имеет явные migrations 1→8 без destructive fallback;
  scan/import используют транзакции. Отдельный Community cache не смешан с ней.
- Playback cache ограничен 192 MiB, Coil artwork — 128 MiB, Tenrai disk cache —
  8 MiB/160 entries. Само наличие singleton cache не является утечкой памяти.
- Community auth проверяет callback shape и PKCE, сессии хранятся encrypted в
  noBackupFilesDir; SecureSessionStore использует AES-GCM/AndroidKeyStore.
  FileProvider ограничен двумя cache subdirectories и не exported.
- Updater проверяет HTTPS hosts/redirects, размер/digest, package/version/signers;
  одна рекомендация Lint про free space не делает весь updater небезопасным.
- Vault tokens, reduced/off motion, root navigation с сохранением state и rail
  при широком окне уже существуют. Raiden splash имеет early return при отключении
  decorative motion. Не требуется переделывать темы ради новых Skills.

## Реестр находок

### A01. Неполный SAF-скан может удалить сохранённую библиотеку

- **Статус / серьёзность:** дефект, код; высокая.
- **Код:** [LibraryScanner.kt:39](../app/src/main/java/com/sergey/animevault/data/scanner/LibraryScanner.kt#L39), участок 39–45 и результат 139–146;
  [LibraryRepository.kt:173](../app/src/main/java/com/sergey/animevault/data/repository/LibraryRepository.kt#L173), участки 230–232, 253–263;
  [Entities.kt:124](../app/src/main/java/com/sergey/animevault/data/db/Entities.kt#L124), FK watch_progress → episode с CASCADE.
- **Причина:** ошибка listFiles преобразуется в пустую ветвь с warning, но полнота
  обхода не входит в контракт результата. Repository безусловно удаляет отсутствующие
  episodes/titles/grouping overrides. Provider также может вернуть пустой список
  при временной недоступности без исключения — это надо учитывать отдельно.
- **Последствия:** отключённый/ошибающийся DocumentsProvider или частичный доступ
  выглядит как удаление файлов; теряются записи, история и ручная группировка,
  хотя физические видео остаются на месте. Транзакция делает удаление атомарным,
  но не делает входной snapshot достоверным.
- **Исправление:** явный complete/partial/unavailable scan result и достоверный
  статус чтения каталогов; destructive pruning разрешать только для полного
  snapshot. При partial сохранять прежние строки и сообщать о проблемной папке.
- **Тесты:** controlled DocumentsProvider с отказом вложенной папки и partial cursor;
  прежние progress/overrides должны сохраниться. После полного успешного scan
  действительно удалённый файл должен удаляться из индекса.

### A02. Resume progressive не проверяет Content-Range и версию ресурса

- **Статус / серьёзность:** дефект, воспроизведён в engine; высокая.
- **Код:** [NativeDownloadEngine.kt:154](../app/src/main/java/com/sergey/animevault/data/download/NativeDownloadEngine.kt#L154), участки 154–195;
  [DownloadModels.kt:238](../app/src/main/java/com/sergey/animevault/data/download/DownloadModels.kt#L238), source fingerprint исключает volatile auth query.
- **Причина:** append определяется только по `existingBytes > 0 && status == 206`.
  Старт Content-Range не сверяется с запрошенным offset; ETag/Last-Modified/If-Range
  не участвуют в resume. URL fingerprint не доказывает неизменность байтов ресурса.
- **Воспроизведение:** старый partial `AAAA`, запрос Range с offset 4; localhost
  отвечает 206, `Content-Range: bytes 0-3/4`, body `BBBB`. Реальный engine успешно
  публикует `AAAABBBB`. Ожидается reject/restart, а не concatenation.
  Для входа именно в resume путь заранее записаны `probe.mp4.partial` и matching
  `probe.mp4.partial.source = downloadCacheKey("native-progressive", fixtureUrl)`.
  Эксперимент вызвал engine, а не Android MediaExtractor/полный Worker: четыре
  ASCII-байта не являются утверждением об успешном offline playback.
- **Последствия:** повреждённый или смешанный файл после CDN anomaly/замены контента.
  Worker verifier проверяет наличие видеодорожки и минимальный размер, а не полную
  корректность каждого sample; он не заменяет transport integrity.
- **Исправление:** проверять Content-Range start/length/total, валидатор ресурса,
  If-Range и правила 200/206/416; при несовпадении безопасно начинать заново.
- **Тесты:** HTTP fixtures для неверного offset/total, изменённого ETag при том же
  URL, 200 вместо Range, корректного 206, 416 и оборванного тела; дополнительно
  валидный MP4 с изменившейся серединой, а не только произвольные ASCII-байты.

### A03. Refresh загрузки может выбрать соседнюю серию

- **Статус / серьёзность:** дефект, код; высокая.
- **Код:** [DownloadWorker.kt:397](../app/src/main/java/com/sergey/animevault/data/download/DownloadWorker.kt#L397), участок 397–409.
- **Причина:** если старый episode ID исчез, выбирается `minByOrNull(abs(ordinal - expected))`
  без требования равенства/допуска. Для отсутствующего ordinal выбор также неоднозначен.
- **Последствия:** при изменении playlist IDs и отсутствии нужной серии скачивается
  ближайшая, но импортируется под сохранёнными именем/ordinal исходной записи.
  Например, ожидается 5, в ответе есть только 4 и 6 — ни одну нельзя считать серией 5.
- **Исправление:** stable ID либо подтверждённое точное ordinal mapping с проверкой
  release/translation; при отсутствии совпадения — unavailable, без silent substitution.
- **Тесты:** удалённая серия, changed ID при том же ordinal, null ordinals,
  specials/fractional ordinals, одинаковые номера в разных seasons/translations.

### A04. Миграции Room не покрыты исполняемым полным regression gate

- **Статус / серьёзность:** подтверждённый дефект конфигурации проверки; высокая.
  Ошибка production migration 7→8 этим не доказана.
- **Код:** [AnimeVaultMigrationTest.kt:23](../app/src/androidTest/java/com/sergey/animevault/data/db/AnimeVaultMigrationTest.kt#L23), тест 5→7;
  [app/build.gradle.kts:133](../app/build.gradle.kts#L133), только KSP schemaLocation;
  [AnimeVaultDatabase.kt:27](../app/src/main/java/com/sergey/animevault/data/db/AnimeVaultDatabase.kt#L27), version 8;
  [.github/workflows/community-pr.yml:140](../.github/workflows/community-pr.yml#L140).
- **Причина:** tracked schemas содержат только 1/2/4/5, отсутствуют 3/6/7/8.
  schemas не подключены как androidTest assets; MigrationTestHelper ожидает эти
  snapshots. Единственный migration test не доходит до актуальной v8, а PR device
  workflow выбирает только три Community-класса и не запускает migration/download tests.
- **Последствия:** ошибка upgrade может попасть в релиз при полностью зелёных
  существующих jobs; стандартный запуск helper сейчас не обеспечен нужными assets.
  Фактический device failure не воспроизведён: устройства нет.
- **Исправление:** восстановить достоверные исторические schemas, экспортировать
  актуальную, подключить test assets; проверить поддерживаемые upgrade paths до 8
  и включить targeted database/download instrumentation в CI.
- **Тесты:** upgrade seeded DB с progress/favorites/downloads; FK/index validation,
  5→8, 7→8 и более ранние поддерживаемые версии. Не заменять исторические schemas
  текущей схемой и не добавлять destructive fallback ради зелёного теста.

### A05. Fencing статуса не охватывает всю финализацию файла и библиотеки

- **Статус / серьёзность:** конкретный конкурентный риск; высокая.
- **Код:** [DownloadWorker.kt:297](../app/src/main/java/com/sergey/animevault/data/download/DownloadWorker.kt#L297), 297–340;
  [SharedDownloadStorage.kt:89](../app/src/main/java/com/sergey/animevault/data/download/SharedDownloadStorage.kt#L89), 89–114;
  [DownloadedMediaImporter.kt:31](../app/src/main/java/com/sergey/animevault/data/download/DownloadedMediaImporter.kt#L31), transaction импорта.
- **Причина:** token проверен до publish, но MediaStore copy и импорт выполняются
  до финального compare token. При потере token после импорта удаляется shared file,
  а compensating rollback импортированной episode/link строки в этой ветке отсутствует.
  Обычный `copyTo` также не проверяет coroutine cancellation между блоками.
- **Последствия:** pause/remove/replacement во время VERIFYING может оставить
  библиотечную запись на удалённый файл или orphan MediaStore row при остановке
  между side effects. Воспроизведение с управляемым interleaving ещё необходимо.
- **Исправление:** устойчивый operation-aware protocol финализации с сохранённым
  published URI, повторной проверкой перед DB commit и компенсацией/reconciliation;
  ownership импорта тоже должен учитывать generation. Не держать SQL-транзакцию
  открытой на всё копирование большого видео.
- **Тесты:** barriers до/после publish/import/final status; remove, pause,
  new generation, cancellation и process restart на каждой границе; проверять
  одновременно Room rows, physical files и MediaStore entries.

### A06. Durable queue имеет окно «Room QUEUED, WorkManager request отсутствует»

- **Статус / серьёзность:** дефект crash-recovery, код; средняя.
- **Код:** [DownloadRepository.kt:45](../app/src/main/java/com/sergey/animevault/data/download/DownloadRepository.kt#L45), initialize 45–62;
  enqueueSeason 133–149, enqueue 208–210.
- **Причина:** запись и token сохраняются до enqueue WorkManager. После crash в
  этом окне initialize восстанавливает только active записи с token==null,
  то есть legacy entries; новая запись с token не получает работу повторно.
- **Последствия:** отдельная серия или часть сезона остаётся QUEUED без выполняемой
  работы; UI может считать её активной и пропускать при повторном enqueue сезона.
- **Исправление:** reconciliation Room ↔ WorkInfo по id/generation или небольшой
  outbox/protocol; операция должна быть повторяемой и сохранять season order/wifi policy.
- **Тесты:** process crash после каждого store.put до chain.enqueue, перезапуск,
  отсутствие дубликатов, восстановление порядка и constraints; не только happy-path enqueue.

### A07. Пауза сезона и отмена I/O имеют фазовые пробелы

- **Статус / серьёзность:** риск отзывчивости/конкурентности; средняя.
- **Код:** [DownloadRepository.kt:214](../app/src/main/java/com/sergey/animevault/data/download/DownloadRepository.kt#L214), 214–230, 249–277;
  season unique name 42–43; [DownloadWorker.kt:229](../app/src/main/java/com/sergey/animevault/data/download/DownloadWorker.kt#L229), 229–233 и 359–361.
- **Причина:** pause/remove отменяет per-entry unique work, но season task принадлежит
  другой unique chain. Его штатный stop опирается на token check в progress callback.
  На progressive copy callback частый, на HLS — после целого сегмента; при preflight
  и публикации его нет. Это не доказательство, что пауза сезона вообще не работает:
  SeasonEpisodeStopped уже предусмотрен и обычно продвигает цепочку правильно.
- **Последствия:** UI показывает PAUSED, но I/O может продолжаться до следующего
  checkpoint; remove может пересечься с финализацией (A05).
- **Исправление:** явно определить cancellation/checkpoint protocol для каждой фазы,
  хранить request identity; не отменять всю seasonal chain по одному эпизоду.
- **Тесты:** pause в DNS/read/probe/segment/publish, большой медленный сегмент,
  resume во время chain advancement и гарантированное продолжение остальных серий.

### A08. Поддержка HLS downloads уже, чем поддержка playback Media3

- **Статус / серьёзность:** подтверждённое ограничение реализации, риск совместимости;
  средняя. Наличие такого потока у конкретного provider сейчас не подтверждено.
- **Код:** [NativeDownloadEngine.kt:636](../app/src/main/java/com/sergey/animevault/data/download/NativeDownloadEngine.kt#L636), модели 636–705,
  parseMaster 727–747, parseMedia 750–829, downloadHls 291–302.
- **Причина:** модель не хранит ENDLIST/live state, EXT-X-MEDIA audio groups или
  discontinuity semantics. Master выбирает video variant, media playlist считается
  конечным snapshot. Состояние ключа map выводится из сегмента, а не захватывается
  независимо на EXT-X-MAP; смены ключей/init segments требуют отдельных fixtures.
- **Последствия:** live/event playlist может быть принят за полную загрузку;
  separate audio rendition не будет скачан; сложный fMP4/AES поток может не
  воспроизводиться локально. Наличие видеодорожки не доказывает наличие аудио.
- **Исправление:** сначала capability detection и fail-fast для неподдерживаемых
  manifest features; затем отдельно, только при подтверждённой потребности,
  добавлять rendition/muxing поддержку. Не обещать DRM или live recording.
- **Тесты:** VOD ENDLIST, event/live без ENDLIST, separate audio, map/key rotation,
  discontinuity и concatenation/remux compatibility на настоящих media fixtures.

### A09. AnimeVost не декодирует заявленный object error envelope

- **Статус / серьёзность:** дефект, воспроизведён; средняя.
- **Код:** [AnimeVostProvider.kt:140](../app/src/main/java/com/sergey/animevault/data/animevost/AnimeVostProvider.kt#L140), 140–144;
  getRelease 75–78; [AnimeVostDtoTest.kt:9](../app/src/test/java/com/sergey/animevault/data/animevost/AnimeVostDtoTest.kt#L9).
- **Причина:** subclass ArrayList выбирает collection adapter Gson; поле `error`
  не превращает его в union array/object adapter. Единственный DTO test проверяет массив.
- **Воспроизведение:** Gson на реальном class принимает `[]`, но
  `{"error":"fixture error"}` даёт JsonSyntaxException.
- **Последствия:** теряется provider-specific причина ошибки; ответ в envelope
  не обрабатывается так, как заявлено. Это не доказывает поломку обычного массива
  или текущую недоступность AnimeVost API.
- **Исправление:** explicit adapter для двух форматов либо убрать неподдерживаемое
  обещание и нормализовать HTTP/body errors на границе API.
- **Тесты:** array, object error, HTTP failure, null/empty/malformed response;
  проверить итоговый OnlineSourceException и UI recovery.

### A10. Metadata caches AnimeVost/Animetka не имеют TTL и ограничения размера

- **Статус / серьёзность:** дефект cache policy, код; средняя.
- **Код:** [AnimeVostProvider.kt:36](../app/src/main/java/com/sergey/animevault/data/animevost/AnimeVostProvider.kt#L36), 36, 46 и 61–75.
  [AnimetkaProvider.kt:48](../app/src/main/java/com/sergey/animevault/data/animetka/AnimetkaProvider.kt#L48),
  maps 48–50, search fingerprints 74–80 и loadDetails 185–188.
- **Причина:** каждая посещённая card остаётся в ConcurrentHashMap весь lifetime
  AppContainer; getRelease всегда предпочитает cached DTO и не обновляет его по возрасту.
  У Animetka details/translation/search maps также не имеют eviction; loadDetails
  предпочитает сохранённый DTO независимо от возраста.
- **Последствия:** накопление metadata в длинной сессии, устаревшие title/year/description.
  Playlist при этом запрашивается заново — нельзя утверждать, что из-за этого
  обязательно устаревают все episode URLs.
- **Исправление:** bounded TTL/LRU metadata cache и явный refresh contract; не
  удалять authoritative playlist lookup fallback для старых history entries.
- **Тесты:** fake clock, превышение capacity, обновление metadata после TTL,
  old release вне latest feed; heap trend при длительном browse измерить отдельно.

### A11. Ошибки cancellation обрабатываются непоследовательно на HTTP-границе

- **Статус / серьёзность:** подтверждённый пробел cancellation contract, runtime-риск;
  средняя.
- **Код:** [OnlineHttp.kt:13](../app/src/main/java/com/sergey/animevault/data/online/OnlineHttp.kt#L13), blocking Call.execute без привязки Call.cancel;
  [AnimeVostProvider.kt:65](../app/src/main/java/com/sergey/animevault/data/animevost/AnimeVostProvider.kt#L65), suspend calls в runCatching;
  [AniListSyncRepository.kt:109](../app/src/main/java/com/sergey/animevault/data/anilist/AniListSyncRepository.kt#L109), state mutation в onFailure после cancellation.
- **Причина:** Dispatchers.IO переносит blocking HTTP с Main, но сам по себе не
  отменяет сокет. Обычный runCatching ловит CancellationException и может запускать
  fallback/изменять UI state после отмены. В проекте уже есть runCatchingCancellable,
  но он применён не везде. Native engine проверяет ensureActive между чтениями;
  это также не гарантирует немедленный выход из блокирующего input.read.
- **Последствия:** запросы/занятые потоки после ухода с экрана, задержки cancel,
  ошибочное состояние аккаунта вместо штатной отмены. Измерение задержки ещё требуется.
- **Исправление:** единый cancellable HTTP adapter с Call.cancel и cleanup; заменить
  только найденные suspend runCatching, сохранив error mapping и Retry-After.
- **Тесты:** медленно отвечающий локальный сервер, cancellation до headers и в body,
  отсутствие fallback после cancel, release ресурсов, неизменность account state.

### A12. Eviction Tenrai удаляет новые записи раньше старых

- **Статус / серьёзность:** дефект, код; средняя.
- **Код:** [TenraiResponseCache.kt:100](../app/src/main/java/com/sergey/animevault/data/metadata/TenraiResponseCache.kt#L100), 100–109.
- **Причина:** files сортируются descending lastModified, но при totalBytes>maxBytes
  цикл удаляет с начала. Именно последние записи/только что записанный ответ
  первыми теряются при превышении byte budget.
- **Последствия:** хуже hit rate и stale fallback для недавно открытого title;
  count limit сам по себе не нарушается, это ошибка выбора жертвы eviction.
- **Исправление:** сохранять newest prefix в budget либо удалять oldest-first;
  явно согласовать last access и storedAt semantics.
- **Тесты:** разный размер файлов, новая запись пересекает maxBytes, newest должен
  остаться, older вытесняться; отдельные count/bytes limits и oversized entry.

### A13. Backup I/O выполняется из Main и читает импорт без лимита

- **Статус / серьёзность:** дефект dispatcher/границы ввода, код; средняя.
- **Код:** [SettingsViewModel.kt:154](../app/src/main/java/com/sergey/animevault/ui/settings/SettingsViewModel.kt#L154), 154–169;
  [AnimeVaultBackupRepository.kt:152](../app/src/main/java/com/sergey/animevault/data/repository/AnimeVaultBackupRepository.kt#L152), 152–169.
- **Причина:** viewModelScope.launch использует Main; repository открывает streams,
  читает `readText()` и декодирует Gson без IO/CPU boundary и ограничения размера.
  После suspend snapshot export также возвращается в caller context.
- **Последствия:** зависание UI на медленном DocumentsProvider; большая выбранная
  backup может исчерпать heap. Размер/ANR на устройстве не измерялся.
- **Исправление:** stream I/O на IO, bounded read до Gson, лимиты объектов и
  валидация до записи. Сохранить merge policies и переносимость source keys.
- **Тесты:** slow provider + StrictMode/UI responsiveness, oversized input, malformed
  null fields, cancellation, v1/v2/current backups, отсутствие partial restore.

### A14. Backup сообщает неверную версию БД

- **Статус / серьёзность:** дефект метаданных, код; низкая.
- **Код:** [AnimeVaultBackupRepository.kt:179](../app/src/main/java/com/sergey/animevault/data/repository/AnimeVaultBackupRepository.kt#L179): databaseVersion=4 при Room v8.
- **Причина:** константа осталась после последующих миграций.
- **Последствия:** неверная диагностика и будущие ошибки, если restore станет
  принимать решения по этому полю. Текущий decode проверяет formatVersion;
  доказательств текущей потери данных именно из-за databaseVersion нет.
- **Исправление:** один источник версии schema либо фактическая версия открытой БД;
  не менять backup formatVersion только ради исправления metadata.
- **Тесты:** snapshot текущей БД, старые backups с полем 4, round trip без изменения merge policy.

### A15. Локальная playback error не имеет явного recovery UI

- **Статус / серьёзность:** дефект представления ошибки, код; средняя.
- **Код:** [Media3PlaybackSessionBridge.kt:65](../app/src/main/java/com/sergey/animevault/ui/player/Media3PlaybackSessionBridge.kt#L65) публикует Failed;
  [PlayerScreen.kt:199](../app/src/main/java/com/sergey/animevault/ui/player/PlayerScreen.kt#L199), listener 216–240 и основной UI 303–469.
- **Причина:** после перехода Ready runtime failure сохраняется в session, но
  PlayerScreen не отображает failure/retry/reselect action. ViewModel.Error покрывает
  отсутствие playback bundle, а не последующую ошибку decoder/file access.
- **Последствия:** удалённый файл, revoked SAF grant или unsupported local codec
  оставляют пользователя без понятного пути восстановления; это не утечка player.
- **Исправление:** explicit failed state поверх player с безопасной причиной,
  retry/regrant/rescan/back по типу ошибки; position и выбранные настройки сохранять.
- **Тесты:** file deleted after opening, revoked URI, unsupported codec, retry после
  восстановления доступа; Compose semantics и реальный Media3 fixture на устройстве.

### A16. Local и online имеют разные правила ухода в фон

- **Статус / серьёзность:** риск неявного продуктового/lifecycle контракта; средняя.
- **Код:** [PlayerScreen.kt:268](../app/src/main/java/com/sergey/animevault/ui/player/PlayerScreen.kt#L268): только checkpoint на stop;
  [OnlinePlayerScreen.kt:2198](../app/src/main/java/com/sergey/animevault/ui/player/OnlinePlayerScreen.kt#L2198): pause ON_STOP вне PiP и восстановление intent.
- **Причина:** у local route нет аналогичной pause/resume policy; MediaSession и
  release существуют, но сами по себе не задают одинаковое background поведение.
- **Последствия:** local playback может продолжиться после Home/перекрытия Activity,
  в то время как online остановится. Нужность фонового аудио пользователю не задана;
  поэтому это не объявляется безусловным багом или подтверждённой утечкой.
- **Исправление:** определить общий контракт foreground/PiP/background и применить
  минимальный lifecycle helper; background playback, если нужен, проектировать явно.
- **Тесты:** Home/lock/unlock/back/PiP, user pause до ухода, audio focus/noisy и
  recreation; проверить оба routes на API24/26/35, не привязываясь к auto-rotate.

### A17. Неверный AniList callback уничтожает pending OAuth state

- **Статус / серьёзность:** дефект auth state machine, код; средняя.
- **Код:** [AniListSyncRepository.kt:76](../app/src/main/java/com/sergey/animevault/data/anilist/AniListSyncRepository.kt#L76), 76–84;
  [MainActivity.kt:141](../app/src/main/java/com/sergey/animevault/MainActivity.kt#L141), 141–144.
- **Причина:** pending state удаляется до сравнения. Public custom-scheme URI с
  неверным/отсутствующим state способен прервать уже начатый легитимный login.
  После принятия AniList callback sensitive intent.data не потребляется, в отличие
  от Community callback; повторная обработка требует отдельной проверки.
- **Последствия:** login disruption/replay ошибки при позднем легитимном callback.
  Это не доказательство кражи token или обхода сравнения state: защита от принятия
  чужой сессии как раз присутствует.
- **Исправление:** проверять и атомарно потреблять только подходящий pending request,
  добавить lifetime/duplicate handling; очищать принятую sensitive intent data.
  Не закрывать launcher/OAuth callback signature permission.
- **Тесты:** invalid callback → valid callback, duplicate valid, expired pending,
  cold/warm Activity, recreation, mismatched URI shape и отсутствие token в логах.

### A18. Shared in-flight request наследует отмену первого caller

- **Статус / серьёзность:** конкурентный риск; средняя.
- **Код:** [InFlightRequestCache.kt:44](../app/src/main/java/com/sergey/animevault/data/cache/InFlightRequestCache.kt#L44), 44–68;
  [InFlightRequestCacheTest.kt:23](../app/src/test/java/com/sergey/animevault/data/cache/InFlightRequestCacheTest.kt#L23).
- **Причина:** loader исполняется в scope первого caller, остальные await-ят
  независимый CompletableDeferred. Отмена owner завершает shared result исключением;
  cleanup сам берёт suspend mutex в отменённом контексте, поэтому его завершение
  при contention требует проверки. Тесты покрывают coalescing success, но не owner cancel.
- **Последствия:** уход одного экрана может отменить запрос другого; при неудачном
  cleanup возможен stranded inFlight slot. Воспроизведения последнего пока нет.
- **Исправление:** сначала определить semantics owner/waiters, затем гарантировать
  cleanup и отделять shared loader lifetime только там, где это необходимо.
  Не вводить GlobalScope ради сохранения запроса.
- **Тесты:** owner cancel при живом waiter, cancel одного waiter, loader exception,
  mutex contention, retry после отмены, отсутствие leaked deferred.

### A19. Manifest/key response и число HLS tasks не ограничены входным budget

- **Статус / серьёзность:** ресурсный риск; средняя.
- **Код:** [NativeDownloadEngine.kt:334](../app/src/main/java/com/sergey/animevault/data/download/NativeDownloadEngine.kt#L334), 334–383;
  242–288 создаёт async для каждого item; key length проверяется только после requestBytes.
- **Причина:** playlist/key целиком накапливаются в ByteArrayOutputStream без лимита;
  semaphore ограничивает одновременный I/O, но не количество созданных coroutines.
- **Последствия:** ошибочный/слишком большой ответ CDN или огромный manifest может
  исчерпать heap/создать тысячи tasks. Нагрузочный OOM не воспроизводился.
- **Исправление:** отдельные budgets playlist bytes/segments/key bytes, раннее
  прекращение чтения, bounded worker pool при подтверждённой потребности.
  Для AES-128 достаточно bounded чтения с точной проверкой 16 bytes.
- **Тесты:** chunked unlimited body, oversized key, excessive segment count,
  лимит на resources и понятная UNSUPPORTED/NETWORK ошибка без OOM.

### A20. Прохождение CI не закреплено required checks основной ветки

- **Статус / серьёзность:** подтверждённый governance gap по доступным данным; средняя.
- **Код/настройка:** [.github/workflows/community-pr.yml:1](../.github/workflows/community-pr.yml#L1),
  [.github/workflows/ai-skills.yml:1](../.github/workflows/ai-skills.yml#L1); GitHub effective rules для `main`.
- **Доказательство:** effective rules endpoint возвращает `[]`; GraphQL
  CheckRun.isRequired для девяти checks PR #59 возвращает false.
  Административный branch-protection endpoint ранее вернул 403: его скрытая
  конфигурация не представляется как просмотренная.
- **Последствия:** по видимым данным pipeline может быть зелёным добровольным
  сигналом, а не enforced gate перед merge. Это не означает, что прошлый merge
  был сделан с красными checks — все девять были проверены и успешны.
- **Исправление:** согласовать с owner требуемые стабильные job names, PR-only checks
  и правила stale approvals/direct pushes; отдельно проверить доступность checks
  для forks и path-filtered изменений. Не делать required проверку, которая может
  никогда не запуститься из-за paths.
- **Тесты:** тестовый PR с намеренным failure должен блокировать merge, docs-only
  PR не должен зависать в pending; без включения production deploy.

## Архитектура, UI и производительность: предложения, не дефекты

### I01. Ограничить оркестрацию в крупных Compose-файлах при ближайших точечных изменениях

- **Статус / серьёзность:** предложение, низкая.
- **Код:** [OnlinePlayerScreen.kt:400](../app/src/main/java/com/sergey/animevault/ui/player/OnlinePlayerScreen.kt#L400), state/recovery closures примерно 400–817;
  NativeOnlinePlayer 2106–2390; файл имеет 2555 строк.
- **Причина и последствия:** lifecycle, source recovery и presentation собраны
  близко; это увеличивает стоимость review конкретных исправлений, но число строк
  само по себе не доказывает баг или медленную recomposition. Session/policy classes
  уже выделены — нельзя предлагать их заново как отсутствующий слой.
- **Рекомендация:** при исправлении A15/A16 либо конкретного source transition
  выделять только соответствующий coordinator/helper, опираясь на существующие
  contracts. Не начинать с общего переписывания ViewModel/navigation/DI.
- **Тесты:** сохранить текущие source-switch/resume/autoplay tests; добавить один
  regression сценарий на изменяемую границу и device lifecycle check.

### I02. Сначала измерить декоративную анимацию, затем решать об оптимизации

- **Статус / серьёзность:** предложение, низкая; измеренной регрессии нет.
- **Код:** [RaidenSplashScreen.kt:53](../app/src/main/java/com/sergey/animevault/ui/startup/RaidenSplashScreen.kt#L53), 53–87;
  [PlaybackStreamCache.kt:37](../app/src/main/java/com/sergey/animevault/data/playback/PlaybackStreamCache.kt#L37),
  [AnimeVaultApplication.kt:55](../app/src/main/java/com/sergey/animevault/AnimeVaultApplication.kt#L55).
- **Причина и последствия:** splash читает animated progress в composition и
  создаёт Path/Brush в draw; возможные аллокации видны в коде, но эффект на frames
  неизвестен. Одновременно существуют разумные cache budgets; нет оснований
  объявлять их утечкой или немедленно уменьшать.
- **Рекомендация:** profileable/release-like baseline cold start, catalog scroll,
  player start/seek, 1k-file scan и длительное browse; CPU/heap/Perfetto до/после.
  Только подтверждённый bottleneck исправлять существующими Compose API, например
  draw-phase reads/drawWithCache. Не добавлять Compose 1.12/1.13 API по upstream совету.
- **Тесты:** повторяемые traces на фиксированном устройстве/refresh rate,
  сравнение FULL/REDUCED/OFF и screenshot/state checks после оптимизации.
  Нет измеримого выигрыша — закрыть performance PR без production изменений.

## Девять предупреждений Lint, ранее отнесённых к средней серьёзности

Это девять occurrences, а не девять разных ID. Все имеют встроенную severity
Warning. Предыдущая [классификация](AI_SKILLS_LINT_REVIEW.md) была triage;
после анализа ниже два InlinedApi понижены до низкого приоритета. Эти строки
не добавляют ещё девять независимых доказанных runtime дефектов к реестру A01–A20.

| № | Место / участок | Причина и возможное последствие | Оценка после аудита | Рекомендуемая правка | Необходимая проверка |
|---|---|---|---|---|---|
| L01 | [OnlineCatalogScreen.kt:235](../app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt#L235) | Высота top controls — 55% Configuration.screenHeightDp; actual content constraints/insets/IME могут отличаться | Средняя, риск адаптивности | Считать budget от constraints контейнера с явными insets | Split screen/landscape, IME, font scale 1.0/1.5/2.0, доступны все filters |
| L02 | [PlayerChrome.kt:106](../app/src/main/java/com/sergey/animevault/ui/player/PlayerChrome.kt#L106) | Dock width из screenWidthDp-92, а не доступной ширины player | Средняя, риск обрезания/лишнего scroll | Передавать available width в dock, не ломать scroll fallback | Узкое landscape окно, gesture/3-button nav, крупный шрифт |
| L03 | [PlayerChrome.kt:115](../app/src/main/java/com/sergey/animevault/ui/player/PlayerChrome.kt#L115) | Portrait dock height — 36% configuration height | Средняя, риск перекрытия transport | Parent height budget и safe area | Compact portrait/multi-window, TalkBack focus и все dock actions |
| L04 | [PlayerChrome.kt:186](../app/src/main/java/com/sergey/animevault/ui/player/PlayerChrome.kt#L186), screenWidthDp | Pause info видимость определяется глобальной шириной, не размером панели | Средняя, риск ошибочной компоновки | Проверять constraints фактического player panel | Tablet split-pane и узкий player внутри широкого окна |
| L05 | [PlayerChrome.kt:186](../app/src/main/java/com/sergey/animevault/ui/player/PlayerChrome.kt#L186), screenHeightDp | Та же политика для высоты; относится к одному условию с L04 | Средняя, риск перекрытия | Одна общая constraints-политика с L04, не два разных fixes | Низкое окно, крупный шрифт, transport остаётся доступен |
| L06 | [NotificationAccess.kt:63](../app/src/main/java/com/sergey/animevault/ui/notifications/NotificationAccess.kt#L63) | API33 String-константа в callback rationale | Низкая после анализа: сама String инлайнится, callback следует запросу с API policy; crash не доказан | Явный version guard/helper для ясности, без подавления проблемы новым широким permission | API24/32 без request; API33 grant/deny/dismiss/permanent denial |
| L07 | [NotificationAccess.kt:101](../app/src/main/java/com/sergey/animevault/ui/notifications/NotificationAccess.kt#L101) | launch POST_NOTIFICATIONS внутри результата notificationPermissionAction | Низкая: policy не возвращает REQUEST_SYSTEM при runtimePermissionRequired=false | Сделать API33 precondition видимой рядом с launcher | Матрица из L06 и переход из system settings; без повторных автозапросов |
| L08 | [AndroidManifest.xml:21](../app/src/main/AndroidManifest.xml#L21), helper [PlayerPictureInPicture.kt:24](../app/src/main/java/com/sergey/animevault/ui/player/PlayerPictureInPicture.kt#L24) | Есть aspect ratio/seamless resize, нет sourceRectHint/autoEnter | Средняя, UX-риск, не доказанный crash; auto-enter — продуктовый выбор | Добавить sourceRectHint; auto-enter включать только при согласованной playing policy на API31+ | API26/30/31/35, Home/back/user pause, local/online, отсутствие двойного enter |
| L09 | [GitHubUpdateRepository.kt:95](../app/src/main/java/com/sergey/animevault/data/update/GitHubUpdateRepository.kt#L95) | usableSpace не учитывает reclaimable cache; возможен консервативный отказ при low storage | Средняя, доступность updater, не corruption | API26+ allocatableBytes/allocateBytes при оправданной политике; fallback API24; обработать race/IOException | Fake storage budget, API24/26+, insufficient/reclaimable bytes и disk full во время записи |

L04/L05 чинятся одним изменением условия. L06/L07 не основание повышать minSdk,
добавлять разрешения или объявлять проблему безопасности. L08 не требует принудительного
auto-enter при нажатии Back. L09 не заменяет уже существующие size/digest/signature checks.

## Покрытие запрошенных областей и остаточные проверки

| Область | Результат обследования | Что ещё нужно подтвердить |
|---|---|---|
| Kotlin/Compose architecture | AppContainer/StateFlow/repositories и cancellation boundaries; A11/A18, I01 | Scheduling races, process recreation, startup StrictMode/trace |
| Media3/local/online | Ownership/release/session bridge, A15/A16, L08; source recovery уже реализован | Decoder/tracks/audio focus/PiP на device, реальные expired URLs |
| Одна серия / сезон | Dedupe, quality/translation policies, queue, generation tokens; A02/A03/A05–A08/A19 | Concurrent finalization, network retry, long season, process death |
| AniLiberty | API/catalog/details mapper, HLS480/720/1080 и blocked flags существуют | Свежие raw API fixtures, null/schema drift, live smoke и playback каждого quality |
| AnimeVost | Catalog/playlist/metadata fallback, A09–A11 | Старые release IDs, error envelope semantics, HTTP/HTTPS CDN availability |
| Animetka | Catalog pagination/repeated page guard, translation loading, DTO fixtures, A10 caches | Свежие details/translations/stream fixtures, TTL/refresh semantics и playback |
| Kodik / AnimeLib | Kodik resolve→page→links с однократным сбросом endpoint cache и существующими parser tests; AnimeLib API Bearer/Origin/Referer wiring | Свежий player-JS/HTML contract, истёкший token/stream URL, credentialed smoke отдельно |
| Остальные источники | Registry wiring для JutSu/Dreamerscast/AniMedia/AnimeOn/SameBand/AnimeBest/Yummy; общий transport/health/Unified reviewed | Изолированный contract suite каждого adapter: search/details/episodes/resolve; отдельный credentialed smoke при наличии разрешённого аккаунта; весь parser каждого сайта построчно не верифицирован |
| Room/cache/storage | A01/A04/A12–A14; bounded caches и transaction boundaries | Реальный upgrade, partial SAF provider, grant revocation, backup rollback |
| Memory/background/performance | A06/A07/A10/A11/A18/A19; periodic scan battery/storage constraints и bounded alert retry существуют | Heap/battery/ANR measurements; stop/cancel latency; не делать выводы по debug APK |
| UI/navigation/motion | Vault tokens, root save/restore state, wide rail, reduced/off paths; L01–L08, I01/I02 | TalkBack, contrast поверх artwork, font scaling, foldable/window matrix |
| Android security | A13/A17/A19; callback/state/PKCE, encrypted sessions, FileProvider и updater checks reviewed | Merged manifest/real intents на устройстве, sensitive log capture, dependency vulnerability scan |
| Build/testing/CI | Сборка и tests успешны; A04/A20 ограничивают силу зелёного статуса | Required gates, DB/download/player instrumentation и release-like performance gate |

MASVS mapping выполнен только на уровне областей: STORAGE/PRIVACY (backup/sessions),
AUTH (A17/Community PKCE), NETWORK (A11/A19/updater), PLATFORM (intents/permissions/PiP),
CODE (parsers/queue/data integrity), CRYPTO (SecureSessionStore implementation).
RESILIENCE, root/tamper testing и полная нормативная проверка не выполнялись;
сертификация соответствия не заявляется.

## Как использовать результат

Первыми идут подтверждённые риски потери состояния и неверной загрузки, затем
границы отмены/финализации/проверок. Предложения по визуалу и общему рефакторингу
не блокируют эти исправления. Каждый следующий PR должен воспроизводить конкретный
сценарий, исправлять его минимально и сохранять работающие contracts.
Порядок, зависимости, acceptance criteria и проверки — в
[ANIMEVAULT_MODERNIZATION_ROADMAP.md](ANIMEVAULT_MODERNIZATION_ROADMAP.md).
