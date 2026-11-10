# Проверка интеграции AI Skills

Дата: 2026-11-10. База: `12fc546e902f8d4edea2b458376d94eaad990353` (`origin/main`
после fetch), ветка `chore/animevault-ai-skills`. Функциональность приложения не менялась.

## Выполненные проверки

- `python3 tools/verify-ai-skills.py`: 10 навыков, 3 pinned upstream bundles
  (2 навыка + 1 CSV reference); имена, frontmatter, AGENTS routes, relative links,
  лицензии, SHA-256 и отсутствие неожиданных executable assets.
- Негативные проверки validator в отдельной временной копии: изменённые upstream
  bytes, отсутствующий reference, неправильное имя, незарегистрированный навык
  и symlink должны приводить к ненулевому exit code.
- `git diff --check`: whitespace validation.
- JDK 17 (`/workspace/.jdk17`), SDK `/workspace/.android-sdk`, Gradle user home
  `/workspace/.gradle`: `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`
  — **BUILD SUCCESSFUL**, 56 tasks: 3 executed, 53 up-to-date. Firebase config уже
  существовал, не восстанавливался и не выводился. Android build files не менялись.
- Дополнительный принудительный запуск JVM-тестов (`:app:testDebugUnitTest --rerun`)
  вместе с `:app:dependencies --configuration debugRuntimeClasspath` —
  **BUILD SUCCESSFUL**, 30 tasks: 2 executed, 28 up-to-date.
  XML reports: **135 suites, 634 tests, 0 failures, 0 errors, 0 skipped**.
- Lint report: **0 errors, 66 warnings, 5 hints**. Существующие предупреждения
  не подавлялись; исходный Android-код не менялся.
- `:app:dependencyInsight --dependency okhttp --configuration debugRuntimeClasspath`
  — **BUILD SUCCESSFUL**, resolved `com.squareup.okhttp3:okhttp:4.12.0`.
  Dependency report также подтверждает Kotlin stdlib 2.4.10, Compose UI/runtime
  1.11.4 и Material 3 1.4.0. Советы upstream для Compose 1.12/1.13 неприменимы
  без отдельной проверки/задачи обновления.

## Сохранение конфигурации и безопасность установки

Только добавления: AGENTS.md, проектные навыки/справка/manifest, docs, validator и
`.github/workflows/ai-skills.yml`. Исходные tracked файлы не изменены, включая
существующий UI-навык и references, root/app Gradle, wrapper, manifest, source code,
ресурсы, старые workflows и dependencies. Пользовательский каталог навыков сохранён.

Выбранные upstream markdown/CSV/license скопированы без изменения байтов на commits
из upstream-lock.json. Сторонние scripts/installers/hooks/search CLI не запускались;
никакие новые runtime библиотеки или permissions не добавлялись.
Собственный validator использует Python stdlib, читает только локальные файлы;
SKILL.md не выполняется как программа, сеть не используется.

## CI и границы доказательств

Новый workflow: PR/push с path filters и manual dispatch, contents:read,
checkout с persist-credentials:false, Python stdlib. Существующие workflows не менялись.
Проверка навыков независима от Firebase/signing secrets. APK pipeline имеет прежние
branch/tag filters; открытие chore PR само по себе не запускает Android build CI.
Результат GitHub Actions для нового workflow проверяется после push/PR; локальный
успех не выдаётся за удалённый CI success.

Автоматическое обнаружение новых навыков интерфейсом текущей сессии не проверено;
файлы доступны, явное чтение через AGENTS работает. Для новой runtime-регистрации
может понадобиться новая сессия. Постоянный глобальный install не выполнялся.

В рамках этой настройки не запускались device instrumentation, TalkBack, фактический
playback/HLS/download E2E и performance profiling. Они требуют отдельного сценария
и не нужны для изменения только инструкций; успешный assemble не доказывает их работу.
MASVS certification и полный аудит сторонних репозиториев не выполнялись.

skillsmp.com: сетевой CONNECT 403. Указанный KMP repository: Repository not found.
Эти источники не установлены и не представлены как проверенные. Полный UI UX Pro Max
CLI также не установлен; поставляется только Compose reference с лицензией.
