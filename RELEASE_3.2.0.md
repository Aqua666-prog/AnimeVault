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

## Закрытие интеграционных пробелов

- Известные альтернативы ищутся по существующим namespace edges, не по похожему названию.
  Максимум3 релиза,2 одновременных резолвера,5s на источник; выключенные/cooldown источники
  исключаются, сохранённый провайдер приоритетен. Discovery доступен вручную и однократно
  после исчерпания recovery; отмена останавливает job и инвалидирует generation. При401/403
  автоматическое переключение отключается, источник не получает чужие headers/токены.
- Локальная выдача теперь содержит реальные local/provider keys и открывает сохранённый
  релиз/Room title непосредственно без сети. Не привязанные к релизу metadata-ID запускают
  прежний поиск. Коллекции и raw local titles без metadata попадают в тот же индекс;
  batch updates исключают N копирований карты на snapshot, stale local ID edges удаляются.
- Ручное соответствие можно подтвердить, разделить и сбросить из chooser; оригинальные
  records и история не меняются. Fingerprints инвалидируют решение при изменении метаданных.
- Snapshot содержит play/pause/speed, языки аудио/текста, состояние субтитров, quality и voice key.
  Эти предпочтения сохраняются в прежнем per-title SharedPreferences. При отсутствии сохранённой
  студии/источника запрашивается явный выбор. При неподтверждённой нужной аудиодорожке новый
  поток не начинает играть молча. Ручной выбор текущего готового потока тоже сохраняется.
- Media3 callbacks проверяют token и фактическую установку нового MediaSource. Старое READY
  не завершает новую операцию. Cancel возвращает прежний источник на том же Player, с паузой,
  перенастраивает listener epoch. UI пересоздаёт ожидающее подтверждение из session snapshot;
  process restart использует сохранённые предпочтения/исходный progress, без автоприменения
  незавершённого target. Background preparation не включает проигрывание скрыто; PiP сохранён.
- Refresh разрешает URL фактически выбранного источника, сохраняет остальные варианты,
  проверяет token перед изменением ViewModel и проходит общий safety gate. Поздние результаты
  отменённой операции не меняют MediaItem.
- Special/OVA/recap, явно составные эпизоды, отличающийся cut или противоречащая duration
  не скрываются в обычной ordinal-группе. Derived labels не выдаются за распознавание контента.
- Прогресс пишется в source provider/episode. Старые групповые записи72/73 сохраняются;
  без source proof их позиция показывается как историческая и не переносится автоматически.
  Проценты локальной и другой online-серии не объединяются по названию/ordinal.
- В chooser есть реальная диагностика каналов, время проверки, Retry/выбор другого/возврат.
  READY stamp относится только к конкретному текущему потоку, не ко всему провайдеру.

## Неустранимые из кода границы данных и runtime QA

Внешние API часто не содержат cut/global episode reference. Такой случай намеренно
POSSIBLE/UNKNOWN; пользователь может проверить и подтвердить соответствие. Duration
не является fingerprint видеоконтента. Это безопасное поведение, а не обещание автоматического
распознавания одинакового монтажа. Неизвестные/неподдерживаемые семантические IDs дорожек
не выдумываются. EMBED остаётся ручным web handoff без гарантии seek или проверки всего видео.
Индекс ограничен известными/кешированными тайтлами, не всем интернет-каталогом.
Physical install, все сторонние CDN, codecs, orientation/PiP и power/process death
на реальном устройстве остаются обязательным QA; локальные fixtures их не заменяют.

## Совместимость и тесты

Версия3.2.0/74, applicationId/minSdk24, Room8/backup4, подпись/keystore, Firebase/Google
и guest, downloads, branding/updater/ручная публикация сохранены. Миграций нет. Новые поля
моделей имеют defaults и не меняют Room/backup записи. Потребители: Unified provider stamp,
OnlineStream→PlaybackVariant adapter, player identity policy, chooser; download resolution остаётся прежним.

Локально: 60 Python tests, verify-final.sh, verify-2.0.0.sh, XML/YAML/Bash, Tree-sitter и
отдельный git diff --check успешны. Gradle/SDK/APK локально не выполнялись.
Unit tests: identity conflicts/missing evidence/manual/cut, transition cancellation/late-token/
bounds/order/prepare/position, local aliases/romanization/typo/short query, auto switch opt-in.
Instrumentation: прежние onboarding6 плюс local WAV MediaItem replacement, production chooser
cancel→manual verified switch→background/foreground, preferences recreation и cache batch regression:
всего10 тестов на API24/35 в существующем workflow. Фактические CI результаты последнего SHA будут указаны в PR; прошлый прогон базовой
реализации прошёл compile/unit/lint/APK и API24/35, но не подтверждает новый массовый набор.
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

## Итог интеграционного закрытия

Сохранённый provider ID учитывается при следующем открытии; если его нет в исходном плане,
выполняется ограниченное разрешение уже известных ID связей. Прогресс читается из того же
источника. Auto/смена студии с HLS master без quality не стирает ручное ограничение качества.
Refresh фактического источника сохраняет другие варианты, не возвращается скрыто к исходному
провайдеру экрана. Неизвестная студия/401/403 требует подтверждения, права доступа не обходятся.
После30s PLAYING с движущейся позицией можно начать новую recovery-цепочку; краткие неудачные
циклы не сбрасывают лимит3. Состояния ROLLING_BACK не имитируются: Cancel атомарно
инвалидирует token и выбирает прежний MediaSource с паузой.

UI диагностики не запускает дополнительные probes, различает resolver/channel и конкретный
READY stream, показывает время/класс ошибки без URL/headers. Source chooser доступен после
ошибки; есть Retry, выбор другого и возврат. Индекс raw local titles/metadata/сохранённых online
и коллекций обновляется пакетно; уже существующие библиотека/SharedPreferences не заменены.
Дополнительные проверки: специальный/составной/короткий эпизод отдельно от обычного ordinal,
bounded discovery и namespace priority, повторное создание source chooser, настоящий переход
на локальном media fixture и сохранение паузы после background/foreground, cache batch без
удаления provider records, source-scoped resume после72/73, аудио/субтитры/provider/Auto prefs.

Последний массовый набор требует нового CI. До его фактического результата PR остаётся Draft.
Физический device QA не выполнялся. Никаких новых релизов/тегов или публикации APK.

## Изменённые файлы

- `.github/workflows/build-apk.yml`
- `CHANGELOG.md`
- `README.md`
- `RELEASE_3.2.0.md`
- `app/build.gradle.kts`
- `app/src/androidTest/java/com/sergey/animevault/ui/onboarding/OnboardingFlowTest.kt`
- `app/src/main/java/com/sergey/animevault/AnimeVaultApplication.kt`
- `app/src/main/java/com/sergey/animevault/data/online/EpisodeIdentityMatcher.kt`
- `app/src/main/java/com/sergey/animevault/data/online/OnlineModels.kt`
- `app/src/main/java/com/sergey/animevault/data/online/OnlineRepository.kt`
- `app/src/main/java/com/sergey/animevault/data/online/OnlineSearchRanking.kt`
- `app/src/main/java/com/sergey/animevault/data/online/OnlineTitleMatcher.kt`
- `app/src/main/java/com/sergey/animevault/data/online/UnifiedOnlineProvider.kt`
- `app/src/main/java/com/sergey/animevault/data/playback/PlaybackSession.kt`
- `app/src/main/java/com/sergey/animevault/data/playback/PlaybackSourceTransition.kt`
- `app/src/main/java/com/sergey/animevault/data/playback/PlaybackVariant.kt`
- `app/src/main/java/com/sergey/animevault/data/titles/AnimeSearchSuggestions.kt`
- `app/src/main/java/com/sergey/animevault/data/titles/AnimeTitleStore.kt`
- `app/src/main/java/com/sergey/animevault/ui/navigation/AnimeVaultApp.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogViewModel.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlinePlayerViewModel.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/player/OnlinePlayerScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/player/PlayerControls.kt`
- `app/src/main/java/com/sergey/animevault/ui/player/PlayerEqualizer.kt`
- `app/src/main/java/com/sergey/animevault/ui/player/SourceSwitchPolicy.kt`
- `app/src/main/java/com/sergey/animevault/ui/preferences/UiPreferences.kt`
- `app/src/main/java/com/sergey/animevault/ui/shikimori/ShikimoriCollectionRepository.kt`
- `app/src/main/java/com/sergey/animevault/ui/shikimori/ShikimoriCollectionsScreen.kt`
- `app/src/test/java/com/sergey/animevault/data/online/EpisodeIdentityMatcherTest.kt`
- `app/src/test/java/com/sergey/animevault/data/online/OnlineTitleMatcherTest.kt`
- `app/src/test/java/com/sergey/animevault/data/online/UnifiedOnlineProviderTest.kt`
- `app/src/test/java/com/sergey/animevault/data/playback/PlaybackSourceTransitionTest.kt`
- `app/src/test/java/com/sergey/animevault/data/titles/AnimeSearchSuggestionsTest.kt`
- `app/src/test/java/com/sergey/animevault/ui/online/ScopedOnlineResumeProgressTest.kt`
- `app/src/test/java/com/sergey/animevault/ui/player/SourceSwitchPolicyTest.kt`
