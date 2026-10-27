# AnimeVault 3.2.0 (74) — реализация, до проверки не готово к выпуску

База main: b8cb4c12a767d35bddf809cff85ee2fe0976e681, 3.1.1 (73).
Открытые PR: только исторический #1, пересечения не найдены.

## Аудит перед реализацией

Пути относительно app/src/main/java/com/sergey/animevault/.

| Механизм | Состояние | Путь и решение |
|---|---|---|
| Названия, aliases, namespaces, кеш4000 | реализовано | data/titles/AnimeTitleResolver.kt, AnimeTitleStore.kt; расширить локальным поиском |
| Debounce/generation/cancel/partial/ranking | реализовано | ui/online/OnlineCatalogViewModel.kt, data/online/OnlineSearchRanking.kt; сохранить guards после фоновой работы |
| Подтверждённые title groups/manual split | реализовано | data/online/OnlineTitleMatcher.kt, UnifiedOnlineProvider.kt, ui/preferences/UiPreferences.kt; сохранить ID/history |
| Эпизоды | частично | UnifiedOnlineProvider.mergeEpisodes использует ordinal/season; добавить provenance и отдельную классификацию, не выдавать группу за VERIFIED |
| Сессия | реализовано | data/playback/PlaybackSession.kt, PlaybackSessionStore.kt; расширить существующий reducer tokenized switch operation |
| Recovery/quality/prefetch | реализовано | data/playback/PlaybackRecovery.kt, PlaybackAdaptiveQuality.kt, PlaybackPrefetch.kt; сохранить bounds и cooldown |
| Safety gate | реализовано | ui/player/SourceSwitchPolicy.kt; расширить identity/opt-in, не обходить |
| Media3 lifecycle | реализовано | ui/player/OnlinePlayerScreen.kt, Media3PlaybackSessionBridge.kt; один прежний путь создания и release |
| Quality/voice | частично | PlayerEqualizer.kt, OnlineRepository.kt; явный Auto сейчас теряется как отсутствие настройки |
| Chooser | частично | OnlinePlayerScreen QUALITY sheet; расширить сведениями об источнике и identity |
| Локальные признаки групп | отсутствуют в карточках | OnlineCatalogScreen.kt; показывать только явные связи и прогресс конкретного source episode |

Не хватает надёжных API-данных о монтаже и глобальном episode ID: нельзя синтезировать
их из номера или названия. Непроверенные внешние сервисы, PiP/rotation/process death,
реальные subtitles/tracks и установка поверх72/73 требуют runtime QA.

Решения: без Room/backup миграции; provenance поля добавляются с default=null;
используются все существующие потребители OnlineStream/PlaybackVariant через адаптер;
новые поля не меняют provider IDs, download resolution или Room persistence.
Ручные решения хранятся отдельно от provider records. Проверки только Actions.

## Реализованная архитектура

EpisodeIdentityMatcher сохраняет provider/release/episode, namespace ID, ordinal/name/duration,
сезон/часть/тип/edition/episodeReference с origin/reliable. Результат содержит reasons,
confirmed/conflicts/origins. Текстовые признаки помечены derived, отсутствующий монтаж не
синтезируется. Общий title ID+ordinal+duration даёт максимум POSSIBLE. VERIFIED допускается
для той же исходной записи, надёжного episodeReference+edition+title ID либо ручного решения;
противоречия имеют приоритет. Ручное решение хранит SHA256 fingerprints метаданных,
не URL, отдельно от provider records. Возможность сохранять ручное подтверждение есть в диалоге.

PlaybackSession.sourceTransition — часть существующего reducer/store. Begin увеличивает token,
фиксирует snapshot позиции/play/speed и ограничивает попытки (3, явный ручной выбор начинает
новую цепочку). Последовательность SNAPSHOTTING→DISCOVERING→VALIDATING→confirmation?
→PREPARING→RESTORING→COMPLETED. Ошибка/20s timeout дают FAILED, отмена инвалидирует token,
возвращает прежний вариант и позицию с паузой. Новый MediaSource получает тот же ExoPlayer,
READY запускает clamp к фактической timeline. Callbacks подготовки/error/bridge привязаны к token;
во время перехода progress не записывается старым вариантом. Preferences сохраняются после READY,
автоматические переходы их не перезаписывают. Языковые track parameters остаются у Player,
overrides старых track groups сбрасываются, поскольку группы другого потока несовместимы.

Общий SourceSwitchPolicy используется в switchVariant. По умолчанию cross-provider требует
подтверждения. Auto возможен только с opt-in, VERIFIED, native-потоком и без запрещающей
health/auth/cooldown; смена указанной студии также требует подтверждения. POSSIBLE/UNKNOWN
переходит вручную с начала, исходная позиция сохраняется. CONFLICT блокируется. EMBED — ручная
передача веб-плееру, без обещания восстановления позиции/проверки видео по загрузке страницы.

## Поиск и каталог

Новый локальный alias index использует AnimeTitleStore (локальные, metadata и online ID),
максимум4000 записей/12000aliases/16 на запись, словари exact/roman/prefix и максимум300
кандидатов на запрос. Fuzzy — одна ошибка при длине>=5, короткие строки без fuzzy.
Индекс пересоздаётся на Default при смене snapshot. Кириллическая транслитерация служит
только поиску; японские и переводные варианты берутся из известных aliases. Подсказки
кликабельны, запускают прежний поиск; сетевых API-цепочек не добавлено. Generation проверяется
после фоновых операций, подсказки доступны и при ошибке сети. Secondary ranking использует
известные progress/favorite только после текстового score, точные совпадения не вытесняются.
Метаданные названий обогащаются из существующих ID references.

Карточки показывают число исходных релизов, явную local link и последний конкретный
provider/episode progress; проценты разных эпизодов не суммируются. Manual split сохранён.
Исходные provider records/IDs, история/оценки и избранное не объединяются и не удаляются.

Явный Auto хранится как0 в прежнем per-title ключе; отсутствие настройки продолжает
использовать global. HLS cap применяется через штатные Media3 track parameters.
Stable translation key имеет приоритет, старое сохранённое имя остаётся fallback.

## Ограничения / незакрытые acceptance criteria — PR Draft

Реальные API в текущем коде не дают надёжного cut/episodeReference для всех провайдеров;
поэтому автоматическое межпровайдерное VERIFIED чаще требует явного подтверждения пользователя.
Нет нового сетевого поиска альтернатив вне существующего playback plan: direct релиз без
известных объединённых вариантов не получает дополнительные источники автоматически.
Локальные совпадения показываются текстовыми подсказками, а не отдельными offline-навигационными
карточками; история/персональные списки без метаданных в title store не образуют самостоятельный индекс.
Нет полноценного UI удаления сохранённой episode-корректировки; fingerprint инвалидирует её при
изменении identity, но полный редактор относится к следующему шагу.
Отдельное восстановление всех нестандартных audio/subtitle overrides по семантическим ID между
источниками и доказательство process-death незавершённой операции ещё не выполнены.
Проверка локальным WAV подтверждает базовую Media3 замену item, но не весь production UI-переход,
HLS/MP4/CDN, rollback и rotation во всех состояниях. Поэтому весь релиз НЕ объявляется готовым.

## Совместимость и тесты

Версия3.2.0/74, applicationId/minSdk24, Room8/backup4, подпись/keystore, Firebase/Google
и guest, downloads, branding/updater/ручная публикация сохранены. Миграций нет. Новые поля
моделей имеют defaults и не меняют Room/backup записи. Потребители: Unified provider stamp,
OnlineStream→PlaybackVariant adapter, player identity policy, chooser; download resolution остаётся прежним.

Локально: 60 Python tests, verify-final.sh, verify-2.0.0.sh, XML/YAML/Bash, Tree-sitter и
отдельный git diff --check успешны. Gradle/SDK/APK локально не выполнялись.
Unit tests: identity conflicts/missing evidence/manual/cut, transition cancellation/late-token/
bounds/order/prepare/position, local aliases/romanization/typo/short query, auto switch opt-in.
Instrumentation: прежние onboarding6 плюс local WAV MediaItem replacement и preferences recreation,
на API24/35 в существующем workflow. Фактические CI результаты будут указаны в PR.
Итоговый SHA указан в PR (включить собственный commit SHA в содержимое того же commit невозможно).

## Обязательный физический QA — не выполнен

- Чистая установка и обновление поверх3.1.0/72 и3.1.1/73; подпись, Room, backup, SAF, downloads.
- Google Sign-In, guest, перезапуск процесса, тема и язык, ручные группы и избранное.
- HLS/MP4 и EMBED, ошибки401/403/429/timeout, expiry, доступность каждого провайдера.
- Cross-source UNKNOWN/POSSIBLE/VERIFIED/CONFLICT, opt-in, отмена, timeout, rollback, double tap.
- Pause/play, speed, audio/subtitles, quality Auto/manual, temporary downgrade/recovery.
- Rotation, PiP, background/foreground, process death во время перехода, возврат из chooser.
- Поиск RU/EN/JP/romaji, typo, offline, A→B→A, pages, partial failures, local links/history.
- TalkBack, крупный шрифт, Back, API24 и современный Android, отсутствие скрытых URL в logcat.

## Текст для будущего ручного релиза (после закрытия ограничений и QA)

AnimeVault3.2.0 — поиск по известным названиям с aliases/транслитерацией/опечатками,
признаки источников и локальной доступности, сохранение Auto качества, объяснимое сопоставление
эпизодов и подтверждаемое переключение через единую сессию Media3. Неизвестный монтаж
не означает гарантированную эквивалентность; требуется ручная проверка. Данные библиотеки,
подпись и ручная схема публикации сохраняются. GitHub Release этим PR не создаётся.

Progress routing: OnlinePlayerViewModel фиксирует variantKey/identity до coroutine и пишет
прогресс в исходный provider/episode. Релизы сохраняют собственные записи истории;
непроверенный cross-provider не синхронизирует позицию в связанную локальную серию.
При повторном открытии берётся прогресс первого предпочитаемого исходного потока,
с fallback к историческому group progress. Полное UI-восстановление выбора источника
после process death остаётся пунктом runtime QA и не считается доказанным.

## Изменённые файлы

- `.github/workflows/build-apk.yml`
- `CHANGELOG.md`
- `README.md`
- `RELEASE_3.2.0.md`
- `app/build.gradle.kts`
- `app/src/androidTest/java/com/sergey/animevault/ui/onboarding/OnboardingFlowTest.kt`
- `app/src/main/java/com/sergey/animevault/data/online/EpisodeIdentityMatcher.kt`
- `app/src/main/java/com/sergey/animevault/data/online/OnlineModels.kt`
- `app/src/main/java/com/sergey/animevault/data/online/OnlineRepository.kt`
- `app/src/main/java/com/sergey/animevault/data/online/OnlineSearchRanking.kt`
- `app/src/main/java/com/sergey/animevault/data/online/UnifiedOnlineProvider.kt`
- `app/src/main/java/com/sergey/animevault/data/playback/PlaybackSession.kt`
- `app/src/main/java/com/sergey/animevault/data/playback/PlaybackSourceTransition.kt`
- `app/src/main/java/com/sergey/animevault/data/playback/PlaybackVariant.kt`
- `app/src/main/java/com/sergey/animevault/data/titles/AnimeSearchSuggestions.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogViewModel.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlinePlayerViewModel.kt`
- `app/src/main/java/com/sergey/animevault/ui/player/OnlinePlayerScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/player/PlayerEqualizer.kt`
- `app/src/main/java/com/sergey/animevault/ui/player/SourceSwitchPolicy.kt`
- `app/src/main/java/com/sergey/animevault/ui/preferences/UiPreferences.kt`
- `app/src/test/java/com/sergey/animevault/data/online/EpisodeIdentityMatcherTest.kt`
- `app/src/test/java/com/sergey/animevault/data/playback/PlaybackSourceTransitionTest.kt`
- `app/src/test/java/com/sergey/animevault/data/titles/AnimeSearchSuggestionsTest.kt`
- `app/src/test/java/com/sergey/animevault/ui/player/SourceSwitchPolicyTest.kt`

Дополнительный safety audit: retry-delay и await refresh привязаны к token; отмена
останавливает recovery job. Прямое присваивание варианта после refresh заменено общим
switchVariant gate. Автоматический новый переход из FAILED не начинается до действия пользователя.
