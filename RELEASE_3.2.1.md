# AnimeVault 3.2.1 (75) — Search & Playback Stability

Базовый main: `a2778ce20671f8b2eddaa21653513daac33473e6` (3.2.0/74, PR #24).
Проверены последние 8 коммитов, RELEASE_3.2.0.md, RELEASE_3.1.1.md и существующие тесты.
Открыт только исторический PR #1; пересечения нет. Базовый Actions:
https://github.com/Aqua666-prog/AnimeVault/actions/runs/40570202853 — success.

## Аудит до изменений

Пути относительно app/src/main/java/com/sergey/animevault/.

| Подсистема | Уже реализовано | Подтверждённый дефект | Исправление | Тест | Runtime QA |
|---|---|---|---|---|---|
| AnimeSearchSuggestions | exact/alias/romanization, one-edit, bounds, Default dispatcher | prefix первых двух букв исключает Maruto; prefix-кандидаты могут заполнить весь пул | ограниченный bigram index перед one-edit | first/middle insert/delete/substitute, 4000, порядок, rebuild, bounds | RU/EN/JP |
| OnlineCatalogViewModel/Screen, Repository | debounce450ms, generation, cancellation, partial results, реальные local/provider keys, metadata fallback | canRequest=false пропускает local; local ждёт сеть; stale выдача видна в debounce и при смене provider | local до network gate, немедленный сброс, guards после suspension | fake pipeline: offline/search=false/min length/provider/A→B→A/partial/empty | offline, открыть Room/provider карточку |
| AnimeTitleStore/Resolver | отдельный восстанавливаемый кеш, batch, namespaces, Gson fallback | takeLast вытесняет local; removeAbsentLocals оставляет входящие local references | local priority, общий bounded normalizer и prune, восстановление через ту же policy | overflow/reload/corrupt/duplicate/deleted ID | restart/rescan/SAF |
| OnlineSearchRanking/OnlineTitleMatcher/UnifiedOnlineProvider | точные результаты выше fuzzy; ID complete-link, split, partial failure | новых доказанных дефектов нет | сохранить | существующие ranking/unified tests | частичный отказ провайдеров |
| PlaybackRecovery/SourceSwitchPolicy/AdaptiveQuality | bounded retry, 3 attempts, cooldown, auth gate, opt-in VERIFIED | новых доказанных дефектов policy нет | сохранить | существующие recovery/adaptive/policy tests | HLS↔MP4,401/403 |
| PlaybackSourceTransition/Session/Store, Native Media3 | tokens, snapshot, cancel/rollback,20s timeout, один ExoPlayer | после FAILED поздний READY того же token может запустить target; periodic save не проверяет epoch | общий guard callbacks/progress, pause после failure | failed/timeout/stale/cancel/new episode | ошибки нового MediaSource, rotation/PiP |
| Media3PlaybackSessionBridge | timeline polling, session reducer | неготовый timeline публикует0 поверх snapshot/resume | credible timeline flag, reducer удерживает позицию | unprepared/error/valid seek0 | выход до READY |
| OnlinePlayerViewModel/OnlineRepository | source-scoped progress, legacy retained, Room mutex, AniList sync | recordPlayback состоит из двух suspend writes; конкурентные save могут оставить library старее progress | сериализовать пары записей и захватить snapshot до launch | очередь с задержанным save, legacy scope tests | kill process/restart/completion |
| EpisodeIdentityMatcher/UiPreferences | VERIFIED/POSSIBLE/UNKNOWN/CONFLICT, manual decisions/fingerprints | duration участвует в conflict, но отсутствует в fingerprint: старое approval переживает изменение duration | включить duration в fingerprint (решения переутверждаются) | duration invalidation, season/type/cut/manual | ручная проверка монтажа |
| Source Chooser | diagnostics, cooldown/auth/unverified/failed, Retry/Back/другой источник | из failed transition нет прямого действия diagnostics | добавить в существующий dialog | existing chooser instrumentation + callback guards | Makima, TalkBack |
| Совместимость/CI | Room8,backup4,SDK24/37,existing signing,API24/35 | ветка3.2.1 отсутствует в push triggers | добавить ветку, версия75 | Python version/XML/YAML/verify; Actions Kotlin/lint/APK/API24/35 | upgrade72/73/74 |

Потенциальные lifecycle/provider риски не объявляются исправленными дефектами. Менеджеры,
Room IDs, download engine и архитектура3.2.0 сохраняются.

## Изменения и ограничения

Bigram postings ограничены 948000 integer entries (12000 aliases ×79); сами строки —
2..80 символов,16 aliases на тайтл,300 кандидатов/100 fuzzy и8 результатов. Первое имя
получает каждая из4000 записей, затем распределяется остаток alias budget.
Дополнительные структуры не участвуют в title/episode identity. Индекс строится и ищет на
Dispatchers.Default существующего repository, сеть для подсказок не вызывается.

Единая cache policy применяется к put/putBatch/removeAbsentLocals/restore. Локальные
записи приоритетнее provider cache и metadata; при более4000 локальных записей кеш всё равно
содержит максимум4000, Room и пользовательские списки не сокращаются. Внешние ID без
cached names остаются допустимыми edges; local references требуют существующей записи.
Повреждённый/чрезмерный JSON сбрасывает только восстанавливаемый кеш.

Media3 bridge отмечает недостоверную timeline, reducer удерживает последнюю позицию
и duration. Валидный seek0 разрешён. Guard проверяет episode/variant/token и FAILED;
timeout/error не превращаются в успешную подготовку поздним READY. Отмена использует
прежний rollback и паузу. Retry, диагностика и выбор другого доступны из ошибки.
Сохранения пары progress/library сериализованы, ViewModel фиксирует source snapshot до
launch и не публикует результат в UI другого transition. Это не новая транзакция Room:
аварийное завершение между двумя DB writes всё ещё требует runtime QA.

Длительность включена в fingerprint manual identity approval, поскольку её изменение
может свидетельствовать о другом монтаже. Старые ручные решения остаются сохранёнными,
но требуют повторного подтверждения; история и прогресс не удаляются.

Сохранены: EpisodeIdentityMatcher и4 класса, source discovery, safety gate, snapshots,
rollback,20s timeout,3 attempts,health/auth/cooldown,adaptive quality,единственный player,
per-title prefs,legacy progress guards,Room/WorkManager/SAF/downloads/updater,Makima.
Новые внешние зависимости не добавлялись. Функции3.3.0 не реализовывались.

## Автоматические проверки

Новые/расширенные Kotlin unit tests:
- AnimeSearchSuggestionsTest: first/middle edits, RU/EN,exact priority,4000 entries,
  bounded postings/candidates/results,stable ordering,rebuild.
- AnimeTitleCachePolicyTest: overflow,live locals,incoming/outgoing stale edges,
  duplicate aliases,JSON corruption и reload.
- IndependentCatalogSearchTest: fake provider без сети,local before request,
  search=false,min length,empty,partial,provider иA→B→A,stale/cancel.
- PlaybackSourceTransitionTest: failed target,late callbacks,rollback,new episode,
  virtual20s timeout/cancel,unprepared timeline иvalid seek0; прежние success/budget tests сохранены.
- PlaybackPersistenceStabilityTest: delayed DAO save ordering,repository recreation,
  quality/voice иreal provider/episode keys.
- PlaybackPreferencesStabilityTest: explicit Auto/manual cap,recreation,global prefs,
  persisted manual approval и invalidation после изменения duration.
- LocalTitleNavigationTest: реальные Room/provider keys,colon-containing release IDs,
  metadata-only и malformed-key fallback без выдуманных ID.
- EpisodeIdentityMatcherTest: duration fingerprint,cut/structure/movie/recap;
  существующие VERIFIED/POSSIBLE/UNKNOWN/CONFLICT/manual tests сохранены.

Прежние ScopedOnlineResumeProgressTest,SourceSwitchPolicyTest,OnlinePreferredStreamTest,
recovery/adaptive/progress/unified/provider tests и Media3 fixtures instrumentation сохранены.
Тесты используют fixtures/fake providers/DAO,без реальных сетевых запросов.

Локально:60 Python unit tests PASS; verify-final.sh PASS; verify-2.0.0.sh PASS;
git diff --check PASS. XML/YAML,API24/35 matrix и version/SDK invariants PASS.
Сообщения «Published v3.0.9» в Python tests — вывод mock fixtures; реальной публикации нет.
Gradle,Kotlin compilation/unit,lint,APK,instrumentation локально НЕ запускались.

PR: https://github.com/Aqua666-prog/AnimeVault/pull/25 (Draft).
Первый прогон62c9de3: https://github.com/Aqua666-prog/AnimeVault/actions/runs/40670005036.
Прогон завершён: **SUCCESS**. Фактически PASS: Kotlin compilation,unit tests,lint,
APK build,signature verification,APK/JSON/version consistency,instrumentationAPI24/35.
Условные Build81-only steps skipped; они не выдаются за пройденное сравнение с3.2.0.
После первого прогона уточнены source epoch guards и добавлены navigation/approval tests.
Итоговый статус точного head SHA и прямая ссылка на финальный прогон фиксируются в PR
и финальном отчёте после завершения Actions. Живой статус ветки:

![CI](https://github.com/Aqua666-prog/AnimeVault/actions/workflows/build-apk.yml/badge.svg?branch=fix%2F3.2.1-search-playback-stability)

Ссылка на актуальные запуски:
https://github.com/Aqua666-prog/AnimeVault/actions/workflows/build-apk.yml?query=branch%3Afix%2F3.2.1-search-playback-stability
Сохранены compile,unit,lint,APK,signature,APK/JSON/version иAPI24/35 instrumentation;
workflow добавляет только push trigger новой ветки. Автопубликация не добавлена.

## Совместимость обновления

3.2.1/75 новее3.1.0/72,3.1.1/73,3.2.0/74. applicationId=com.sergey.animevault,
minSdk24,targetSdk37,compileSdk37,Room8,backup4 и signing configuration неизменны.
Нет миграций,destructive fallback,изменений Firebase/Google/guest/provider IDs.
Legacy progress сохраняется,неизвестный source/cut не получает автоматический перенос.
Неизменённая signing configuration не доказывает физическую установку поверх опубликованного APK.
APK в Codex не скачивался и не устанавливался,keystore не менялся.

## Матрица ручного Android QA владельца

Все пункты ниже имеют статус **NOT VERIFIED**. Пройти на Android7.0/API24 и современном
Android; PiP — на поддерживаемом устройстве API26+. CI не заменяет телефон.

| Сценарий | Проверка | Статус |
|---|---|---|
| Чистая установка | Launch,onboarding,guest/Google,основная навигация | NOT VERIFIED |
| Update3.2.0/74 | Та же подпись,установка без удаления данных,версия75 | NOT VERIFIED |
| Update3.1.1/73 | Библиотека,история,legacy progress,settings | NOT VERIFIED |
| Update3.1.0/72 | Те же данные и безопасный legacy resume | NOT VERIFIED |
| Библиотека/прогресс/настройки | Favorite,groups,lists,voice,Auto/manual,subtitles,speed после restart | NOT VERIFIED |
| Offline search | Airplane mode,search=false,min length,реальная Room/provider карточка | NOT VERIFIED |
| Опечатки/транслитерация | Maruto,Blech,Bleech,RU/EN aliases,exact выше fuzzy | NOT VERIFIED |
| Быстрый поиск | A→B→A,смена provider,partial failure,нет старых результатов | NOT VERIFIED |
| HLS/MP4 | HLS→HLS,HLS→MP4,MP4→HLS,quality,voice | NOT VERIFIED |
| Source switch | Позиция,pause/play,speed,subtitle/quality cap,VERIFIED/manual | NOT VERIFIED |
| Отмена/ошибка/timeout | Rollback,pause,Retry,другой источник,диагностика,нет spinner loop | NOT VERIFIED |
| Identity | Разные сезоны,OVA/special/movie/recap,combined,cut,conflicting IDs | NOT VERIFIED |
| Пауза/продолжение | Валидный seek0,выход до READY,завершение/следующая серия | NOT VERIFIED |
| Поворот | Подготовка/играющий поток/пауза,один player,позиция | NOT VERIFIED |
| Background/foreground | Не играет скрыто,восстановление пользовательского intent | NOT VERIFIED |
| PiP | Вход/выход,пауза,позиция,поддерживаемое устройство | NOT VERIFIED |
| Закрытие/restart/process death | В просмотре и switching,старый callback не меняет новую серию | NOT VERIFIED |
| Скачанные серии | Проигрывание/resume,online linked progress,файлы/очередь сохранены | NOT VERIFIED |
| SAF | Persisted grant,scan,rescan,отзыв доступа,файлы сохранены | NOT VERIFIED |
| Обновления | Проверка версии,metadata,installer,offline/error | NOT VERIFIED |
| Доступность | TalkBack,крупный шрифт,Makima chooser,Back | NOT VERIFIED |

## Текст для ручной публикации Release

AnimeVault3.2.1 (75) — Search & Playback Stability.

- Локальный поиск работает до ответа сети и у источников без текстового поиска.
- Подсказки учитывают опечатку первой буквы; точные названия остаются первыми.
- Кеш сохраняет приоритет локальных записей и удаляет устаревшие ссылки.
- Поздние ответы после ошибки смены потока не запускают неуспешный источник.
- Защищены позиция до READY,порядок сохранений и ручные подтверждения эпизодов.
- Библиотека,история,настройки,загрузки и оформление Makima сохранены.

Публиковать вручную только после проверки CI и физического QA. GitHub Release v3.2.0
не изменялся; новый Release,tag и опубликованный APK не создавались.

## Отложено на3.3.0

Новые крупные функции,перестройка архитектуры,расширение поискового корпуса сверх кеша,
новые провайдеры и автоматическое распознавание неизвестного монтажа — вне этого патча.

## Изменённые файлы

Полный diff: https://github.com/Aqua666-prog/AnimeVault/pull/25/files.

- `.github/workflows/build-apk.yml`
- `CHANGELOG.md`
- `README.md`
- `RELEASE_3.2.1.md`
- `app/build.gradle.kts`
- `app/src/main/java/com/sergey/animevault/data/online/EpisodeIdentityMatcher.kt`
- `app/src/main/java/com/sergey/animevault/data/online/OnlineRepository.kt`
- `app/src/main/java/com/sergey/animevault/data/playback/PlaybackSession.kt`
- `app/src/main/java/com/sergey/animevault/data/playback/PlaybackSourceTransition.kt`
- `app/src/main/java/com/sergey/animevault/data/titles/AnimeSearchSuggestions.kt`
- `app/src/main/java/com/sergey/animevault/data/titles/AnimeTitleCachePolicy.kt`
- `app/src/main/java/com/sergey/animevault/data/titles/AnimeTitleStore.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogViewModel.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlinePlayerViewModel.kt`
- `app/src/main/java/com/sergey/animevault/ui/player/Media3PlaybackSessionBridge.kt`
- `app/src/main/java/com/sergey/animevault/ui/player/OnlinePlayerScreen.kt`
- `app/src/test/java/com/sergey/animevault/data/online/EpisodeIdentityMatcherTest.kt`
- `app/src/test/java/com/sergey/animevault/data/online/PlaybackPersistenceStabilityTest.kt`
- `app/src/test/java/com/sergey/animevault/data/playback/PlaybackSourceTransitionTest.kt`
- `app/src/test/java/com/sergey/animevault/data/titles/AnimeSearchSuggestionsTest.kt`
- `app/src/test/java/com/sergey/animevault/data/titles/AnimeTitleCachePolicyTest.kt`
- `app/src/test/java/com/sergey/animevault/testing/MemoryOnlineStateDao.kt`
- `app/src/test/java/com/sergey/animevault/testing/MemoryPreferencesContext.kt`
- `app/src/test/java/com/sergey/animevault/ui/online/IndependentCatalogSearchTest.kt`
- `app/src/test/java/com/sergey/animevault/ui/online/LocalTitleNavigationTest.kt`
- `app/src/test/java/com/sergey/animevault/ui/player/PlaybackPreferencesStabilityTest.kt`
