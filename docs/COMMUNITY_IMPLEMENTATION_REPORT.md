# Отчёт реализации Community 4.1.0

Ветка: `feature/4.1.0-community-comments`; цель PR — `main`. Базовый SHA: `2833c5b4322c0cf9dad8470c35d66557c69052e6`. Исходная версия 4.0.0/79; кандидат 4.1.0/80. minSdk 24 сохранён.

## Результат и проверки

Compose → ViewModel → отдельные Community repositories → OkHttp Auth/PostgREST → PostgreSQL; Keystore session/PKCE, изолированный Room cache. Архитектура, матрица аудита, ограничения и все сценарии: [COMMUNITY_COMMENTS.md](COMMUNITY_COMMENTS.md).

Миграция: `supabase/migrations/202611010001_community.sql`. RLS включён для всех новых таблиц; публичное чтение профилей и подтверждённых canonical records, комментарии только через редактирующий поля read RPC. Запись только через защищённые RPC. Reports/roles/bans/logs закрыты; права EXECUTE перечислены явно. [Полный аудит прав](SUPABASE_SECURITY.md).

В Codex выполнены: `git diff --check`; проверка версии 4.1.0/80; 66 Python-тестов — успешно; синтаксический разбор 47 SQL statements и PL/pgSQL через pglast; разбор нового Kotlin через tree-sitter; XML main и workflow YAML. Это статические проверки, не компиляция. Вывод о публикации в Python-тестах — mock-fixtures существующего release-теста, реальной публикации не было.

Gradle/SDK/emulator/APK install/build в Codex не запускались. Написаны JVM CommunityContractTest, Android CommunityAuthTest/CommunitySpoilerTest, реальные SQL/RLS/concurrency tests на disposable PostgreSQL; их выполнение поручено GitHub Actions. Единственный [PR #29](https://github.com/Aqua666-prog/AnimeVault/pull/29), draft.

Первый CI на `43c75ba`: PostgreSQL — 12/12 успешно; Kotlin compile успешен; JVM — 619/620, один новый configuration-тест обнаружил пустую SUPABASE_URL repository variable в полном workflow. Добавлен fallback URL и независимый от BuildConfig fixture теста. Остальные проверки первоначального запуска и проверки окончательного SHA отмечаются ниже после завершения. [Первый build](https://github.com/Aqua666-prog/AnimeVault/actions/runs/41330619721), [PR suite](https://github.com/Aqua666-prog/AnimeVault/actions/runs/41330640294). Production SQL не выполнялся, Auth Secrets не менялись, Release/tag не создавались.

## Подтверждённые результаты Actions до объединения истории

На снимке `14d311286aa541046481fbde716116f27b93858d`:

- [Community PR verification](https://github.com/Aqua666-prog/AnimeVault/actions/runs/41330961796) — успешно: 13 SQL/RLS/concurrency tests, 620 JVM tests, compile, lint, assemble; по 9 synthetic Community instrumentation tests на API 24, 25 и 35.
- [Полный workflow](https://github.com/Aqua666-prog/AnimeVault/actions/runs/41330955504): compile, 620 JVM tests и lint успешно; onboarding API 24 успешно. Onboarding API 35 оборвался после двух тестов: ADB сообщил `device offline`, затем порт эмулятора был недоступен. Это не прохождение onboarding и не основание отключать его.
- Подписанный APK/metadata и окончательные результаты нового объединённого HEAD проверяются отдельным запуском. Проверять актуальные checks PR, а не считать старый SHA доказательством нового запуска.

По просьбе владельца история feature-ветки объединяется в один коммит. Накопленная дополнительная проверка резервирует используемое системой имя `deleted_user`; после неё SQL suite запускается повторно. Отдельных PR, tags и Releases нет.

## Действия владельца и ограничения

Миграция/GRANT, verified canonical/episode mappings, первый администратор, build variables, внешний SMTP (встроенный лимит 2 письма/час не годится для публичной регистрации), production Site URL, HTTPS App Links и реальная проверка recovery/PKCE/OTP на устройстве ещё нужны. Регистрация не объявляется production-ready. Realtime, Android moderation panel и загрузка аватаров отсутствуют. Локальная серия без подтверждённой онлайн-связи недоступна; имена/номера файлов не используются как доказательство. Кеш может хранить ранее опубликованный текст до обновления/TTL, удалённый кеш на офлайн-устройстве нельзя отозвать мгновенно.

Порядок безопасного применения/отката: [SUPABASE_SETUP.md](SUPABASE_SETUP.md). Модерация, назначение админа, отдельное удаление Auth/персонального текста: [COMMUNITY_MODERATION.md](COMMUNITY_MODERATION.md). Пошаговая проверка регистрации/комментариев на Android и тестовая матрица: [COMMUNITY_QA.md](COMMUNITY_QA.md).

## Новые файлы

- `.github/workflows/community-pr.yml`
- `app/src/androidTest/java/com/sergey/animevault/community/CommunityAuthTest.kt`
- `app/src/androidTest/java/com/sergey/animevault/community/CommunitySpoilerTest.kt`
- `app/src/main/java/com/sergey/animevault/data/community/CommunityApi.kt`
- `app/src/main/java/com/sergey/animevault/data/community/CommunityAuthRepository.kt`
- `app/src/main/java/com/sergey/animevault/data/community/CommunityCache.kt`
- `app/src/main/java/com/sergey/animevault/data/community/CommunityModels.kt`
- `app/src/main/java/com/sergey/animevault/data/community/CommunityRepositories.kt`
- `app/src/main/java/com/sergey/animevault/data/community/CommunitySessionManager.kt`
- `app/src/main/java/com/sergey/animevault/ui/community/CommentsViewModel.kt`
- `app/src/main/java/com/sergey/animevault/ui/community/CommunityAccountViewModel.kt`
- `app/src/main/java/com/sergey/animevault/ui/community/CommunityUi.kt`
- `app/src/test/java/com/sergey/animevault/data/community/CommunityContractTest.kt`
- `docs/COMMUNITY_COMMENTS.md`
- `docs/COMMUNITY_MODERATION.md`
- `docs/COMMUNITY_QA.md`
- `docs/SUPABASE_SECURITY.md`
- `docs/SUPABASE_SETUP.md`
- `supabase/migrations/202611010001_community.sql`
- `tools/test-community-database.py`
- `tools/test_community_contract.py`
- `tools/write-ci-firebase-fixture.py`
- `docs/COMMUNITY_IMPLEMENTATION_REPORT.md`

## Изменённые файлы

- `.github/workflows/build-apk.yml`
- `CHANGELOG.md`
- `README.md`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/sergey/animevault/AnimeVaultApplication.kt`
- `app/src/main/java/com/sergey/animevault/MainActivity.kt`
- `app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/title/TitleDetailScreen.kt`
