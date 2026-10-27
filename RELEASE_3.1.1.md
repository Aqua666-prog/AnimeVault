# AnimeVault 3.1.1 (73) — Stability, Regression Audit & Runtime Reliability

База: `22fe597c2fdc2ccad4a2ced75969ac350cf464df` (актуальный main, 3.1.0/72).
Открыт только старый PR #1 (redesign 1.5.0), пересечений с hotfix нет.
Предыдущие незавершённые функции сохранены в отдельном checkout и сюда не перенесены.

## Границы доказательств

Ниже перечислены дефекты, подтверждённые трассировкой кода, а не воспроизведением
на физическом Android. Статический аудит не доказывает работу внешних сервисов.
Исторический main UI/API24 прерывался с `System has crashed` во время теста MainActivity;
повтор без изменения кода прошёл 6 тестов. Первопричина системного сбоя не установлена;
таймауты и instrumentation не отключаются и не меняются.

Пути в матрице относительно `app/src/main/java/com/sergey/animevault/`.

| Подсистема | Существующая реализация / путь | Подтверждённый дефект или непокрытый сценарий | Минимальное действие | Регрессионный тест | Только устройство |
|---|---|---|---|---|---|
| Startup/onboarding/Sign-In/guest | MainActivity.kt, ui/onboarding/, data/onboarding/ | Риск: системный обрыв старого API24 CI, устойчивой регрессии не доказано | Оставить, сохранить API24/35 suite | OnboardingFlowTest (6 сценариев) | Реальный Google OAuth, чистая установка, restart |
| Каталог/фильтры/поиск/страницы | ui/online/OnlineCatalogViewModel.kt, OnlineCatalogScreen.kt | A→B→A защищён generation, debounce/cancel, partial results уже есть | Не переписывать | CatalogRequestGenerationTest, CatalogAdvancedDiscoveryTest, OnlineSearchRankingTest | Медленная сеть, скролл и смена источника |
| Unified title | data/online/UnifiedOnlineProvider.kt, OnlineTitleMatcher.kt | Дефект: persisted composite ID открывает изменившиеся конфликтующие ID без проверки details | Повторить complete-link ID проверку до mergeEpisodes; ошибка предлагает исходный релиз | UnifiedOnlineProviderTest: conflicting current metadata, источник остаётся доступен | Старые сохранённые группы и история |
| Media3 progress | ui/player/OnlinePlayerScreen.kt, data/playback/PlaybackCore.kt, PlaybackSession.kt | Дефект: dispose/stop до подготовки timeline записывает currentPosition=0 поверх resume | При невалидном timeline применять существующее удержание позиции; валидный seek 0 сохранять | OnlinePlayerRefreshTest: unprepared/failed/valid-zero | Поворот, PiP, background, реальный HLS/MP4 |
| Recovery/quality/refresh | OnlinePlayerScreen.kt, SourceSwitchPolicy.kt, data/playback/PlaybackRecovery.kt, PlaybackPrefetch.kt | Дефект: подтверждение cross-provider есть только в одной recovery ветке, down/upshift и refresh обходят её | Перенести gate в общий switchVariant; ручной выбор/подтверждённый переход остаются допустимы | SourceSwitchPolicyTest, существующие PlaybackRecovery/RouteHealth tests | Ошибка сегмента, CDN, восстановление позиции |
| Логи | OnlinePlayerScreen.kt (direct prepare catch) | Дефект: полное исключение сетевой подготовки может включать signed URL/headers | Логировать только класс исключения | Статический просмотр вызова Log.e, без throwable/message | Проверка logcat на реальном сбое |
| 12 провайдеров | data/online/OnlineProviderRegistry.kt; соответствующие data/<provider>/*Provider.kt | HTTP/DNS/timeout/TLS и channel isolation реализованы; доступность живых сервисов не проверена | Сохранить capabilities и намеренные ограничения | ProviderHttpFailureTest, ProviderHealthTrackerTest, UnifiedOnlineProviderTest | Авторизация, реальная доступность/потоки |
| Downloads | data/download/DownloadRepository.kt, DownloadWorker.kt, NativeDownloadEngine.kt, DownloadStore.kt, SharedDownloadStorage.kt, SeasonDownloadPolicy.kt | Mutex/unique work/operation tokens, bounds retry, media verification существуют; process death/SAF/место требуют runtime QA | Не менять форматы/очередь/движок без доказанного дефекта | DownloadModelsTest, NativeDownloadEngineTest, NativeDownloadRedirectTest, SeasonDownloadPolicyTest | Kill process, resume, Range/HLS/AES, полный диск |
| Local/SAF/Room/grouping/history | data/repository/LibraryRepository.kt, data/scanner/, data/db/ | Scan mutex, overrides, связи и Room8 существуют; физическая сохранность не подтверждена статикой | Без миграций, не объединять и не удалять историю | Существующий набор scanner/library/backup tests | Rescan, отзыв SAF grant, update-install |
| Shikimori/AniList/персонажи | ui/shikimori/ShikimoriCollectionRepository.kt, data/metadata/TitleExtrasRepository.kt, data/titles/ | cancellation и cacheIf=!hasLoadErrors, проверенный Animetka fallback существуют | Не добавлять дубликаты и сетевые проверки в unit | CharacterLoadingRegressionTest, TitleExtrasCharactersTest | Реальные API, постеры, OAuth |
| Updater | data/update/GitHubUpdateRepository.kt, ui/update/UpdaterViewModel.kt | SHA256/размер/package/version/minSdk/signers, single operation и cancel реализованы | Сохранить | UpdateProtocolTest, UpdaterViewModelTest, Python manifest/version tests | Installer и update поверх debug-signed 3.1.0 |
| CI/release | .github/workflows/build-apk.yml, tools/verify-release-version.py | Точная новая ветка отсутствовала в push triggers | Добавить только её; 3.1.1/73; ручная публикация | Python release tests, existing APK/JSON/signing gates | Установка артефакта владельцем |

Все 12 адаптеров сохранены: AniLiberty, AnimeVost, Animetka, Kodik, AnimeLib,
Dreamerscast, AniMedia, AnimeON, SameBand, AnimeBest, Jut.su, YummyAnime.
Матрица намеренных capabilities в RELEASE_3.0.11.md остаётся применимой:
нет общего каталога у AnimeON/AnimeBest/Jut.su, Jut.su не принимает общий текстовый
поиск, скачивание Animetka отключено намеренно. Это не дефекты hotfix.

## Уже реализовано, не переписывалось

Resolver/store/aliases; namespaces ID; partial multi-provider search, debounce,
cancellation и generation tokens; HTTP 429 Retry-After и независимые cooldown каналов;
ограниченные retries/refresh, prefetch и completion policy; per-release настройки;
WorkManager unique work и operation tokens; verifier файла перед COMPLETED;
SAF grant/сканирование и ручные overrides; updater с полной проверкой APK;
ручной выпуск, keystore restoration и APK/JSON gates.

## Совместимость и проверки

Версия 3.1.1/73. applicationId com.sergey.animevault, minSdk24, Room8, backup4,
постоянный keystore, Firebase/Google Sign-In secrets и оформление не менялись.
Миграций нет, destructive migration не добавлена. Установка поверх 3.1.0 требует
физической проверки; неизменённая конфигурация подписи не заменяет такой тест.
Никаких тегов/GitHub Releases/публикации/скачивания release APK в Codex.
Gradle/SDK/эмуляторы/сборки локально не выполняются.

Локально успешно: 60 Python unittest, verify-final.sh, verify-2.0.0.sh, XML/YAML,
Bash-синтаксис, Tree-sitter изменённых Kotlin и отдельный git diff --check.
Verify-скрипты распознают .git только как каталог и пропускают собственный git-check
в worktree; поэтому git diff --check выполнен отдельно. Статус Actions указан в PR.
Kotlin unit и instrumentation выполняются только в Actions.

## Ручная проверка на физическом устройстве — всё НЕ ПРОВЕРЕНО

| Сценарий | Ожидаемая проверка | Статус |
|---|---|---|
| Чистая установка | Launch, onboarding, guest, Google OAuth | Не проверено |
| Обновление поверх 3.1.0 | Та же подпись, версия73, все данные сохранены | Не проверено |
| Room/SAF/settings | Библиотека, ручные группы, grants, тема, язык, озвучка | Не проверено |
| Поиск | RU/EN/romaji/JP, A→B→A, страницы, частичный отказ | Не проверено |
| HLS/MP4 | Запуск, качество, звук/субтитры, ошибка сегмента | Не проверено |
| Позиция | Ошибка/refresh/выход до ready, пауза, seek0, продолжение | Не проверено |
| Источники | Диалог в recovery/адаптивном качестве, отмена, исходный релиз | Не проверено |
| PiP/поворот/фон | Один активный плеер, корректный resume и stop | Не проверено |
| Downloads/offline | Duplicate enqueue, cancel/resume, kill process, HLS/AES/Range | Не проверено |
| Storage | Полный диск, отзыв grants, rescan без потери overrides/history | Не проверено |
| Permissions/updater | Уведомления, installer, offline/HTTP error, плохой APK | Не проверено |

## За пределами hotfix — 3.2.0

Подтверждённое сопоставление эпизодов/монтажа/длительности между провайдерами;
полная state machine seamless switching и opt-in автоматический cross-provider fallback;
расширенный chooser с проверенными возможностями; fuzzy-подсказки по локальному индексу,
транслитерация и новые ranking signals. Ничего из прежнего незавершённого checkout не перенесено.

## Текст для будущего ручного GitHub Release

AnimeVault 3.1.1 (73) — исправления стабильности версии 3.1.0.

- Позиция продолжения не сбрасывается при выходе до готовности видеопотока.
- Автоматическое изменение качества и обновление ссылки не обходят подтверждение смены провайдера.
- Сохранённые объединённые карточки проверяют актуальные внешние ID перед объединением серий;
  при конфликте можно открыть исходный релиз, данные истории не удаляются.
- В лог подготовки потока не передаётся сетевое исключение с возможными signed URL.
- Сохранены база, настройки, офлайн-файлы, Android7+, подпись и ручная публикация.

Автотесты не гарантируют доступность внешних сервисов. Физическая проверка обновления,
воспроизведения, фоновых загрузок и SAF остаётся обязательной перед ручным выпуском.
