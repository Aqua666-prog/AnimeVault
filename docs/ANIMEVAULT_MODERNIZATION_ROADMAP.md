# План модернизации AnimeVault небольшими PR

Основание: [технический аудит](ANIMEVAULT_TECHNICAL_AUDIT.md), база `main`
`6d17076f5b8ae9b830c1beb0ce38e311d0f96eef`, 2026-11-11.
Это план, а не выполненная модернизация: production-код и зависимости в рамках
аудита не менялись. IDs A01–A20, I01/I02 и L01–L09 определены в аудите.

## Правила выполнения

1. Каждый PR создаётся от актуальной main, имеет один проверяемый результат,
   regression scenario, ограниченный diff и самостоятельный rollback.
2. Перед исправлением подтвердить finding на новой базе: соседний PR мог уже
   устранить причину. Риск сначала воспроизводить; если предпосылка не подтвердилась,
   закрывать гипотезу с evidence, а не создавать лишнюю абстракцию.
3. Не начинать с migration на Hilt/KMP/Navigation3, разбиения всего приложения на
   modules, замены native downloader или переписывания player. Работающие pieces
   сохраняются. Зависимости обновляются только отдельной обоснованной задачей.
4. Не менять versionCode/versionName, schema version, permissions, signing или
   server/RLS вне scope конкретного PR. Добавление regression tests не является
   разрешением использовать production accounts/data для destructive экспериментов.
5. Для изменения поведения: targeted JVM tests, `testDebugUnitTest lintDebug
   assembleDebug`, подходящий device test. При изменении persistence — seeded
   upgrade/rollback proof; при изменении playback — реальный media fixture.
6. В PR указывать подтверждённый результат и ограничения, а не «все ошибки устранены».
   Не подавлять предупреждения и не удалять failing tests ради зелёного CI.

Подтверждённых Critical findings нет; первой идёт группа High. Если будущий
repro выявит эксплуатацию/массовую потерю данных, соответствующий PR поднимается
выше. Порядок ниже — приоритет, не требование строить длинную цепочку веток.

## Этап 1. Данные и правильность скачиваемого контента

### PR-01 — Сделать upgrade regression исполняемым (A04)

- **Scope:** восстановить достоверные missing schema snapshots, подключить
  androidTest assets, расширить migration tests до v8. Без изменения runtime migrations,
  пока тест не покажет их дефект. Не подменять historical schema текущей.
- **Приёмка:** MigrationTestHelper работает на устройстве, поддерживаемые старые
  версии доходят до 8 с сохранёнными progress/favorites/downloads/indexes/FK.
- **Проверки:** seeded 5→8/7→8 и более ранние поддерживаемые paths. Изменения реальных
  migrations при обнаружении failure выделить в отдельный дополнительный PR.
- **Зависимости / rollback:** независим от production fixes; откат только test wiring.

### PR-02 — Запретить destructive pruning после неполного SAF scan (A01)

- **Scope:** typed completeness в scan result, явная ошибка чтения provider;
  repository сохраняет прежние строки на partial/unavailable. UI сообщает о папке.
- **Приёмка:** transient folder failure не удаляет историю/overrides/episodes;
  полный scan по-прежнему удаляет реально исчезнувшие записи.
- **Проверки:** controlled DocumentsProvider и Room integration, revoke/regrant,
  partial subtree, empty-but-complete folder, cancellation и repeated scan.
- **Зависимости / rollback:** production не зависит от PR-01; его инфраструктура
  полезна для тестов. Не делать DB migration, если новый статус не хранится в schema.

### PR-03 — Проверять resume identity и Content-Range (A02)

- **Scope:** только progressive transport/partial metadata; точные 200/206/416
  transitions, resource validator и reset policy. Не менять quality selection/UI.
- **Приёмка:** fixture `AAAA + wrong 206/BBBB` больше не завершается склейкой;
  корректный resume сохраняет уже скачанные байты и результат совпадает с original.
- **Проверки:** локальный HTTP server, ETag change, same URL/different content,
  expired auth query, short body, malformed Content-Range, disk full и cancellation.
- **Зависимости / rollback:** независим; формат partial metadata совместим либо
  умеет безопасно сбросить старый partial. Готовые файлы не удалять при rollout.

### PR-04 — Устранить silent nearest-episode fallback (A03)

- **Scope:** matching в source refresh и его focused tests; использовать уже
  существующие identity/matcher contracts вместо новой параллельной системы.
- **Приёмка:** отсутствующая серия 5 не превращается в 4/6; changed ID при точном
  подтверждённом ordinal/release/translation продолжает работать.
- **Проверки:** null/fractional ordinals, specials, два сезона, другая озвучка,
  single и season queue. Неподтверждённый match возвращает понятное unavailable.
- **Зависимости / rollback:** независим, не требует переписывать providers.

### PR-05 — Протокол финализации с generation ownership (A05)

- **Scope:** Worker ↔ MediaStore ↔ importer; durable ownership/compensation для
  published URI и imported episode. Не объединять всю файловую работу с длительной SQL transaction.
- **Приёмка:** после pause/remove/replacement/restart нет orphan файла/row и ни
  одна старая generation не публикует результат новой операции.
- **Проверки:** deterministic barriers на каждой границе publish/import/complete,
  process death, API24 private file и API29+ shared MediaStore.
- **Зависимости / rollback:** не обязан ждать PR-03/04; если потребуются новые
  persisted поля, применить требования PR-01 и backward-compatible recovery.

## Этап 2. Очередь, отмена и границы недоверенного ввода

### PR-06 — Восстановление enqueue после process crash (A06)

- **Scope:** reconciliation active Room entries с WorkInfo/request generation,
  idempotent enqueue, сохранение season order/wifi/quality policy.
- **Приёмка:** crash после store.put до enqueue не оставляет вечный QUEUED;
  уже исполняющийся request не дублируется.
- **Проверки:** single/season, crash в середине batch, reboot, pause/resume,
  существующая WorkManager chain. Не использовать только in-memory mutex как recovery.
- **Зависимости:** независим от остальных исправлений; выбранный persisted protocol
  согласовать с PR-05, если оба затрагивают operation metadata.

### PR-07 — Остановка сезонной серии в каждой фазе (A07)

- **Scope:** request identity/checkpoints, фазовая отмена отдельной series task;
  не отменять все последующие эпизоды chain.
- **Приёмка:** PAUSED/REMOVING прекращает ненужное чтение в определённый проверяемый
  срок, остальные серии продолжаются; cancelled generation не попадает в importer.
- **Проверки:** preflight, slow segment, direct read, verifying/publish, retry-wait,
  resume при занятой цепочке. Согласовать с протоколом PR-05, не дублировать ownership.
- **Зависимости:** transport часть может быть независима; finalization сценарии
  проверяются вместе с PR-05. Deadline выбирать по измерению, не выдумывать SLA.

### PR-08 — Ограничить HLS manifest/key input budgets (A19)

- **Scope:** bytes/items limits, bounded чтение key, predictable failure без OOM.
  Worker pool вместо per-item async вводить только при подтверждённом overhead.
- **Приёмка:** oversized/chunked reply завершается ошибкой до исчерпания heap;
  штатные длинные VOD и AES-128 продолжают скачиваться.
- **Проверки:** body без Content-Length, excessive segments, key 15/16/17 bytes,
  bounded resource usage, cancellation и fallback на другой источник.
- **Зависимости:** независим; cap values обосновать реальными fixtures, не случайными числами.

### PR-09 — Единая cancellation-aware HTTP граница (A11)

- **Scope:** executeText/Call.cancel и точно найденные suspend runCatching;
  сохранить provider error mapping, response closing и Retry-After.
- **Приёмка:** cancel запроса закрывает Call, не запускает fallback и не заменяет
  account state на сетевую ошибку отменённой операции.
- **Проверки:** localhost delayed headers/body, owner cancellation, socket cleanup,
  timeout/HTTP401/429 и coroutine cancellation как разные исходы.
- **Зависимости:** независим от visual/DB PR; не заменять Retrofit/OkHttp.

### PR-10 — Уточнить cancellation semantics in-flight cache (A18)

- **Scope:** сначала tests owner/waiter cancellation, затем минимальный lifetime/cleanup fix.
- **Приёмка:** один ушедший caller не ломает независимого waiter по выбранному
  контракту; после любой отмены inFlightCount возвращается к нулю и retry возможен.
- **Проверки:** contention/scheduler tests, loader failure, all waiters cancel,
  TTL0 и forceRefresh. Не вводить GlobalScope, не сохранять ненужные запросы навсегда.
- **Зависимости:** независим; результаты PR-09 помогают проверить реальный HTTP cancel.

### PR-11 — Bounded backup I/O и точные metadata (A13/A14)

- **Scope:** явные IO/CPU boundaries, read/object limits, корректный databaseVersion.
  Один небольшой repository PR; не менять формат/merge policies без необходимости.
- **Приёмка:** slow provider не блокирует Main, oversized input отклоняется до
  restore, snapshot сообщает актуальную schema, старые backups читаются.
- **Проверки:** StrictMode/UI responsiveness, malformed/oversized/cancel,
  v1/v2/current round trip и отсутствие partial writes.
- **Зависимости:** независим, migrations не нужны, если меняется только snapshot metadata.

### PR-12 — Потребление только валидного AniList OAuth callback (A17)

- **Scope:** pending state lifecycle, matching/expiry/duplicate handling, очистка
  принятого intent data. Без смены grant, пока поддержка альтернативы у provider не проверена.
- **Приёмка:** чужой invalid callback не уничтожает начатый login; valid callback
  принимается однократно; tokens не появляются в логах/повторной Activity обработке.
- **Проверки:** invalid→valid, duplicate, expired, cold/warm/recreation;
  Community PKCE flow остаётся неизменным.
- **Зависимости:** независим. App Links/смена OAuth grant — отдельная будущая задача,
  не обязательная часть этого локального исправления.

## Этап 3. Источники, cache и явные ошибки плеера

### PR-13 — AnimeVost array/error contract (A09)

- **Scope:** один Gson/Retrofit response boundary, DTO tests, нормализованная ошибка.
- **Приёмка:** оба заявленных shape обрабатываются намеренно; обычный array и
  старые history IDs не ломаются. Не менять URL/host только из-за parsing error.
- **Проверки:** array/object/null/empty/malformed/HTTP error; fixture из аудита должен
  перейти из JsonSyntaxException в ожидаемый provider error.
- **Зависимости:** независим; live smoke advisory, не обязательный offline unit gate.

### PR-14 — Ограничить provider metadata caches (A10)

- **Scope:** TTL/LRU и refresh; не трогать authoritative playlist lookup.
  AnimeVost и Animetka делать двумя независимыми мини-PR, не общим переписыванием
  всех адаптеров; у Animetka учесть также translation/search fingerprint maps.
- **Приёмка:** capacity bounded, старые DTO обновляются, release вне latest остаётся доступным.
- **Проверки:** fake clock, eviction, stale metadata refresh, long browse heap trend.
- **Зависимости:** независим от PR-13; не заменять все provider caches одной новой framework.

### PR-15 — Исправить Tenrai eviction order (A12)

- **Scope:** только выбор удаляемых disk-cache records и focused tests.
- **Приёмка:** byte limit вытесняет old, не newest; count и maxEntryBytes сохраняются.
- **Проверки:** разные размеры, access-order/age semantics, новый большой record,
  disk write failure; stale fallback по-прежнему явно маркируется.
- **Зависимости:** независим, runtime DB migration и обновления dependencies не нужны.

### PR-16 — Локальный player: ошибка и recovery action (A15)

- **Scope:** отображение существующего session failure, retry/regrant/rescan/back;
  не переносить player owner в новый слой без причины.
- **Приёмка:** revoked/deleted/unsupported media даёт понятное действие, повторное
  открытие сохраняет нужную позицию и не создаёт два player instances.
- **Проверки:** Compose state/semantics + Media3 device fixtures, release/listener
  cleanup, успешный playback без error state.
- **Зависимости:** независим от downloads; использовать уже имеющиеся classifier/session contracts.

### PR-17 — Согласовать local/online background policy (A16)

- **Scope:** сначала продуктовый контракт, затем один небольшой lifecycle helper.
- **Приёмка:** user pause, Home и PiP имеют одинаковые оговорённые semantics;
  background audio не включается/не выключается неявно.
- **Проверки:** local/online API24/26/35, focus loss/noisy, PiP/lock/unlock,
  recreation и повторный foreground без самовольного play.
- **Зависимости:** независим от error UI. Полноценный background media service,
  если он нужен, — отдельный feature PR после решения, а не «рефакторинг для порядка».

### PR-18 — Явные capabilities HLS downloads (A08)

- **Scope:** распознавать unsupported live/external-audio/сложные map-key cases
  и корректно отказывать или выбирать поддерживаемую альтернативу.
- **Приёмка:** download не сообщает «полная серия» для неподдерживаемого snapshot;
  штатные muxed VOD сохраняют поведение.
- **Проверки:** VOD/live/event/separate audio/key rotation/init maps на fixtures.
- **Зависимости:** независим от fixes progressive. Реальную поддержку separate
  audio/remux добавлять отдельным feature PR только по подтверждённым provider samples.

## Этап 4. Защитить результат проверками CI

### PR-19 — Добавить targeted DB/download/player instrumentation (A04)

- **Scope:** отдельная job или явный class list с migration/download/lifecycle tests;
  не расширять случайно synthetic Community test до production auth.
- **Приёмка:** новая job действительно исполняет выбранные tests, публикует reports,
  не скрывает missing schema/assets за filtering.
- **Проверки:** PR-01 обязательно готов; seed fixtures, negative run на временном
  checkout, timeout/flaky triage. API matrix начать компактно, расширять по рискам.
- **Зависимости:** PR-01; для новых regression классов добавлять их по мере merge
  соответствующих fixes, а не ждать завершения всей программы модернизации.

### Операционная задача CI-01 — Required checks и защита main (A20)

- **Scope:** согласованные repository settings, не изменение приложения.
- **Приёмка:** намеренно красная required job блокирует merge; stale/missing checks
  не обходятся; docs-only/path-filtered PR не остаётся без ожидаемого required status.
- **Проверки:** тестовые PR, fork behaviour, stable unique names и permissions.
- **Зависимости:** выбрать набор gates после PR-19; существующие android/skills
  checks можно защищать раньше, если их trigger semantics подходят.
- **Ограничение:** изменение branch rules требует отдельного разрешения/прав owner;
  в рамках этого аудита настройки не меняются. Не включать auto-merge/deploy.

## Этап 5. Lint, адаптивность и визуальная проверка

### PR-20 — Actual constraints для chrome/catalog (L01–L05)

- **Scope:** передача window/container budgets в три UI helpers; сохранить темы,
  компоненты, insets ownership и scroll fallback. L04/L05 — одно условие.
- **Приёмка:** filters/transport доступны в compact/landscape/split screen при
  fontScale 2.0; ошибки не лечатся фиксированным global screen size.
- **Проверки:** targeted Compose layout tests, screenshots compact/wide и manual
  TalkBack. Не обновлять Navigation/Compose ради этой задачи.
- **Зависимости:** независим; выполняется после рисков данных/playback.

### PR-21 — Notification API guard и PiP geometry: два независимых мини-PR (L06–L08)

- **Scope A:** явный API33 guard рядом с permission launcher без новых permissions;
  это читаемость/version safety, а не исправление доказанного API24 crash.
- **Scope B:** sourceRectHint для PiP; auto-enter только после принятия playback
  policy, без автоперехода по Back или на paused player.
- **Приёмка / тесты:** notifications API24/32/33 grant/deny/dismiss/settings;
  PiP API26/30/31/35 local/online, geometry и отсутствие двойного enter.
- **Зависимости:** мини-PR A независим; B согласован с PR-17. Не объединять две
  подсистемы в один diff только ради закрытия количества предупреждений.

### PR-22 — Storage budget updater (L09)

- **Scope:** учитывать allocatable space на подходящих API и реальные allocation
  errors, сохраняя private-cache cleanup, digest/signature/version gates.
- **Приёмка:** достаточно reclaimable bytes — обновление возможно; недостаточно
  — понятная ошибка без неполного «готового» APK и без удаления media downloads.
- **Проверки:** fake budgets, API24 fallback/API26+, race disk full during write,
  отмена и повторная попытка; без реального release publication.
- **Зависимости:** независим. Не расширять storage permissions.

### PR-23 — Измерить performance baseline; визуальные правки только по evidence (I02)

- **Scope:** reproducible profileable/release-like сценарии startup/catalog/player/scan,
  traces/heap и FULL/REDUCED/OFF. Отдельно от любых style изменений.
- **Приёмка:** опубликованы устройство/API/build, сценарий, вариативность и baseline;
  никакие проценты ускорения не берутся из debug/recomposition count без frame evidence.
- **Последующий мини-PR:** только измеренный bottleneck — например Path/Brush allocation
  или draw-phase reads; либо никаких production изменений, если выигрыша нет.
- **Тесты:** A/B trace, screenshots/state/semantics, reduced motion и API24 fallback.
- **Зависимости:** независимая диагностика может идти раньше; production optimization
  не отвлекает от high correctness fixes. Benchmark dependencies, если нужны,
  предлагаются отдельным обоснованным изменением.

### По потребности — Ограниченное выделение player coordinator (I01)

- Делать только при конкретной правке сложной границы recovery/lifecycle.
  Не создавать отдельную «переписать 2555 строк» инициативу без измеримой цели.
- Acceptance: прежние source switch/resume/autoplay/track/PiP contracts сохраняются,
  новая граница лучше тестируется, diff не меняет соседнюю функциональность.

## Что сознательно не входит в программу по умолчанию

Массовое обновление всех библиотек по version advisories; автоматическая смена DI,
архитектуры или навигации; замена Media3/native engine; React Native/WebView;
редизайн утверждённых тем; произвольная чистка legacy code без анализа callers;
новые permissions ради обхода ошибок. Каждый такой шаг требует отдельной
пользовательской задачи и доказанного преимущества для AnimeVault.

## Определение завершённого PR

В описании указаны finding ID, воспроизводимый trigger, до/после, точные tests и
пределы device проверки. Нет новых данных/permissions/schema/dependencies без
обоснования. Готовность определяется regression evidence, а не количеством
закрытых Lint warnings. Merge/deploy выполняются только в разрешённом scope.
