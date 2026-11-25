# Project Ascension — исходный аудит

База — `aad02e0`, подтверждённый HEAD удалённой `main`. Версия 4.2.0 (86), API 24–37. Подробный пофайловый аудит и реестр из 20 находок входят в базовый коммит. [Технический аудит](ANIMEVAULT_TECHNICAL_AUDIT.md) · [Дорожная карта](ANIMEVAULT_MODERNIZATION_ROADMAP.md)

| Критичность | Наблюдение | План |
| --- | --- | --- |
| P0 · сохранность | `LibraryScanner` принимает пустой или частичный ответ `DocumentFile.listFiles`; `LibraryRepository` удаляет отсутствующие эпизоды, субтитры, тайтлы и overrides | Считать пригодной для сверки только полную выдачу SAF, отклонять ошибку или незавершённый cursor до Room |
| P1 · стабильность | Проблемы Range, refresh идентичности, финализации и durable queue описаны в A02–A08 | Проверять отдельными изменениями с тестами прерванной загрузки и гонок |
| P1 · главный поток | Экспорт/импорт читает и пишет через ContentResolver из настроек; импорт не ограничен (A13) | Перенести I/O, бюджетировать вход |
| P1 · сборка | APK workflow не покрывает обычную ветку PR и нет доказательства required checks | Запустить JVM, Lint, APK единым проверяемым контуром |
| P2 · Compose/GPU | Приложение уже использует lifecycle-сбор, Vault-токены, adaptive rail, shared transitions, анимации и skeleton. В hero слой применяется даже при отключённом движении; фиксированный размер запроса и software bitmap повышают стоимость | Использовать ограничения Compose и разрешать эффекты по уровню качества и состоянию питания |
| P2 · Coil | Singleton, 128 МиБ диска, специальные заголовки Shikimori; повторный расчёт оттенка каждого backdrop | Ограничить кэш оттенков и бюджет памяти на low-RAM, сохранить URL и путь диска |
| P2 · Media3 | Online и local имеют отдельных владельцев; recovery/watchdog, position snapshots, MediaSession и PiP уже реализованы. Local recovery ограничен, время до кадра не записывается | Добавить точку измерения первого кадра, проверить cleanup на устройстве, сохранить transport и выбор треков |
| P2 · сеть | A09–A12/A18 описывают TTL, LRU и наследование отмены. Снижение фактических запросов требует счётчика при одинаковом сценарии | Точечные правки по контрактам провайдеров |
| P2 · память | В исходниках имеются cleanup для Media3/MediaSession и process scopes в AppContainer. Удержание Activity, WebView, отложенных callback и Navigation требует heap evidence | Диагностическая сборка, повторная навигация и heap dump |
| P3 · ресурсы | R8 и shrinkResources включены для release. Ресурсы тем, previews и динамические обращения требуют проверки resolved usage | Lint и анализ APK перед удалениями |

Один модуль, ручная композиция зависимостей, Room v8 с явными миграциями и отдельный Community cache v1. WorkManager использует operation token для скачиваний, уникальную периодическую задачу сканирования и ограничения батареи/диска. Настройки оформления в SharedPreferences, DataStore используется для onboarding. AGP 9.3.2, Gradle 9.5, Kotlin 2.4.10, Compose BOM 2026.06.01, Coil 3.5.0, Media3 1.11.0, Room 2.8.4 и Work 2.11.2. 146 файлов Android/JVM-тестов. Контуры Actions покрывают AI Skills, APK, источники и Community/Supabase. Внешние agent skills версионируются в `.agents/skills`; встроенные функции ИИ остаются в приложении.

## Исследованные проекты

Лицензии десяти проектов — Apache 2.0. Используются идеи и публичные API.

| Источник | Решение |
| --- | --- |
| [Performance Samples](https://github.com/android/performance-samples/tree/main/MacrobenchmarkSample) | Измерять startup и кадры извне приложения, в release-подобной сборке. Sample использует minSdk 28 для тестового модуля |
| [Haze](https://github.com/chrisbanes/haze/blob/main/docs/migrating-2.0.md) | Модульные эффекты, source capture на ограниченной площади, fallback. HEAD использует Kotlin 2.4.21, требуется отдельная проверка стабильной версии и GPU |
| [Compose Samples](https://github.com/android/compose-samples/tree/main/Jetcaster) | Переиспользование компонентов медиа, ключи в ленивых контейнерах |
| [Coil](https://github.com/coil-kt/coil/blob/main/docs/image_loaders.md) | Сохранить singleton и дисковый кэш, использовать constraints для decode |
| [Android Skills](https://github.com/android/skills) | Инструкции малыми модулями, сценарии и доказательства. Сохранить существующий upstream lock |
| [Now in Android](https://github.com/android/nowinandroid/blob/main/docs/ArchitectureLearningJourney.md) | Однонаправленный поток состояния и общее оформление navigation |
| [LeakCanary](https://github.com/square/leakcanary/blob/main/docs/fundamentals-how-leakcanary-works.md) | Debug-only heap diagnostics; время заморозки dump не входит в performance baseline |
| [Lottie](https://github.com/airbnb/lottie-android/tree/master/lottie-compose) | Compose API учитывает шкалу аниматора, minSdk 21. Для коротких переходов достаточно Compose, отдельные ресурсы анимации не нужны |
| [Media3](https://github.com/androidx/media/tree/release/libraries/exoplayer) | Существующий ExoPlayer, callback первого кадра. PreloadManager требует общего владельца источников и бюджета трафика |
| [Roborazzi](https://github.com/takahirom/roborazzi) | Детерминированные фикстуры, явные record/verify, совместимость Robolectric с AGP проверяется отдельно |

ADB не обнаружил устройств. CPU/GPU, время кадра, память во время прокрутки, startup и первый кадр видео не измерены. Проверка сборки на базе запущена до изменений. Результаты фиксируются в документах QA и Benchmarks.
