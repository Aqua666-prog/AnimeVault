# Обследование AnimeVault перед интеграцией Skills

Дата: 2026-11-10. Исходный commit: `12fc546e902f8d4edea2b458376d94eaad990353`.
Рабочая ветка до изменений — `work`, дерево чистое; создана `chore/animevault-ai-skills`.
Это карта архитектуры и совместимости, не полный security/performance audit приложения.

## Версии

Источник: root/app `build.gradle.kts`, `settings.gradle.kts`, wrapper properties.
Версии ниже — declarations, если явно не указано иное; каталог версий отсутствует.

| Компонент | Значение |
|---|---|
| Android / application | Один `:app`, `com.sergey.animevault`, 4.2.0 (86) |
| SDK | min 24, compile/target 37 |
| Gradle / AGP | 9.5.0 / 9.3.2 |
| Kotlin | Built-in Kotlin AGP 9; Compose compiler plugin и resolved stdlib 2.4.10; отдельный kotlin.android не объявлен |
| KSP / Java | 2.3.11 / source-target 17; CI использует JDK 17 |
| Compose / Material 3 | BOM 2026.06.01; resolved runtime/ui 1.11.4, Material 3 1.4.0 |
| Media3 | 1.11.0 (exoplayer, hls, ui, session) |
| Room / DataStore | 2.8.4 (KSP) / preferences 1.1.7 |
| WorkManager / coroutines | 2.11.2 / 1.10.2 |
| Retrofit | 3.0.0, converter-gson 3.0.0 |
| OkHttp | Транзитивная, resolved 4.12.0 через dependencyInsight |
| Coil / Okio | 3.5.0 / 3.17.0 |
| Lifecycle / Navigation / Activity | 2.11.0 / 2.9.8 / 1.13.0 |
| Core / DocumentFile | 1.18.0 / 1.1.0 |
| Firebase | BOM 34.0.0, Auth, google-services plugin 4.4.4 |
| Credentials / Google ID | 1.5.0 / 1.1.1 |
| JVM tests | JUnit 4.13.2, Truth 1.4.5, coroutines-test 1.10.2 |
| Android tests | runner/rules 1.7.0, ext:junit 1.3.0, Room/work-testing, Compose BOM |

## Архитектура и потоки данных

`AnimeVaultApplication` создаёт `AppContainer`: ручная DI, общий baseHttpClient,
provider-specific clients, repositories, Room и application CoroutineScope.
`MainActivity` и `ui/navigation/AnimeVaultApp.kt` связывают Compose screens и ViewModels.
UI сгруппирован по features, общие Vault-токены и компоненты уже реализованы.

Онлайн: UI/ViewModel → `OnlineRepository`/`UnifiedOnlineProvider` → registry и provider
adapters → Retrofit/OkHttp/API/HTML. Отдельно существуют metadata repositories,
provider health/capabilities и endpoint registry. `data/playback` и `data/transport`
связывают разрешение потока, playback session, recovery и выбор альтернативы.

Плеер: `OnlinePlayerScreen`, `PlayerScreen`, `PlayerViewModel`, отдельная
`PlayerActivity`, factory/audio engine. Есть MediaSession, PiP, lifecycle/orientation,
progress и bridge с playback session. Владение ExoPlayer нужно проверять для каждого
route; навыки не переносят ownership и не меняют текущую реализацию.

Загрузки: `DownloadRepository` → WorkManager → `DownloadWorker` → собственный
`NativeDownloadEngine` на HttpURLConnection. Есть direct/HLS, master variant,
AES-128, init maps, byte ranges, resume journal, retries и отмена.
Operation token защищает от запоздалых workers; Room хранит состояние.
Готовый файл проходит verifier и importer. Media3 DownloadManager не используется
как замена этому пути; навык Media3 Cast исключён как ненужный.

Хранение: основная `AnimeVaultDatabase` v8 с migrations 1→8 и exported schemas;
отдельный Community cache v1 (exportSchema=false). Onboarding DataStore сосуществует
с SharedPreferences UiPreferences. SAF/MediaStore scanner, grouping и scan worker
обслуживают локальную библиотеку; preferences/scanner не изменялись.

## Инструкции и установленная среда

До интеграции корневого/вложенного AGENTS.md в репозитории не найдено.
Уже версионировался `animevault-ui-designer` и пять его references — сохранены.
В пользовательском `/home/agent/.agents/skills` обнаружены `ub-mode`, `apple-design`,
`ui-design-system`, `ux-researcher-designer`; не изменялись и не копировались.
Также текущая сессия предоставляет cloud AnimeVault skills: их доступность не
гарантирует доступность в новом checkout, поэтому они не объявлены установленными в Git.

## CI/CD и поверхность изменений

`build-apk.yml`: JDK 17, restore Firebase/signing, unit tests, lint, debug APK,
signature/updater/version gates, onboarding instrumentation API 24/35. Есть
branch/tag filters; произвольная chore-ветка не запускает этот pipeline.
`animetka-qa.yml`: manual/selected feature branches, tests/lint/assemble.
Другие workflows обслуживают canary источников и Community/Supabase migrations,
edge deploy и catalog sync. Их triggers/secrets/deploy behaviour не менялись.

Изменяемая поверхность: AGENTS.md, docs, `.agents/skills`, собственный offline
validator и отдельный lightweight workflow. Код/ресурсы приложения, Gradle,
зависимости, существующие workflows и пользовательские skills сохраняются.
Потенциальные риски интеграции — устаревшие внешние рекомендации, конфликт scopes,
потерянные references и непроверенные команды. Защита: routing/ограничения в AGENTS,
pin commit/license/hash, полный выбранный subtree, отсутствие сторонних executables,
CI validator. Новые baseline/benchmark modules не добавлены.

Проверки и ограничения записаны в [AI_SKILLS_VERIFICATION.md](AI_SKILLS_VERIFICATION.md).
