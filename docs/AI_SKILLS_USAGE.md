# Применение AI Skills в Codex

Открыть checkout AnimeVault, прочитать AGENTS.md и выбрать один основной навык из
[каталога](AI_SKILLS_CATALOG.md). Навыки находятся в Git и не требуют глобального
installer, MCP server, npm CLI или установки Android-зависимостей.
Для обновления автоматического обнаружения может понадобиться новая сессия Codex.
Если имя отсутствует в runtime-каталоге, дать Codex прямой путь к SKILL.md:
это поддерживаемый fallback, а не подтверждение автоматической активации.

## Примеры заданий

| Сценарий | Задание Codex |
|---|---|
| Kotlin/architecture | «Прочитай `.agents/skills/animevault-architecture/SKILL.md`. Найди причину старых результатов после смены поискового запроса, сохрани AppContainer и API, проверь cancellation». |
| Compose/Material 3 | «Используй animevault-ui-designer и `docs/AI_SKILLS_UI_GUIDANCE.md`. Подготовь адаптивную спецификацию Downloads для compact/wide и landscape, с Vault-токенами, TalkBack и reduced motion. Пока без реализации». |
| Playback/HLS | «Прочитай `.agents/skills/animevault-media-downloads/SKILL.md`. Воспроизведи потерю seek position после refresh HLS URL, проверь владельца ExoPlayer и session bridge; исправь причину и проверь lifecycle». |
| WorkManager/downloads | «Используй animevault-media-downloads. Проверь гонку cancel → enqueue того же эпизода: старый worker не должен менять новую operation token; проверь Room и готовый файл». |
| API/provider | «Прочитай `.agents/skills/animevault-network-sources/SKILL.md`. Раздели search/details/resolver failure Animetka. Исправь только failing stage, добавь очищенный fixture». |
| Room/DataStore/cache | «Используй animevault-persistence. Подготовь миграцию Room с сохранением downloads/progress, проверь upgrade, indexes и rollback; не мигрируй UiPreferences без необходимости». |
| Performance | «Прочитай `.agents/skills/jetpack-compose-performance/SKILL.md` и guards AGENTS.md. Исследуй jank каталога: сначала измерения, затем нужные references и минимальная правка. Не добавляй новые Compose APIs без проверки версии». |
| MASVS/security | «Прочитай `.agents/skills/animevault-security/SKILL.md`. Проверь updater/auth diff, составь MASVS evidence map, укажи проверенные области и недоступные проверки. Для intents используй android-intent-security». |
| Tests | «Используй animevault-testing. Выбери минимальный regression набор для Room/WorkManager diff и запусти доступные проверки. Отдельно укажи, что требует device». |
| Gradle/CI/APK | «Используй animevault-build-release. Установи причину Gradle failure через stacktrace/dependencyInsight. Сохрани версии и existing CI gates, затем проверь APK metadata/signature без публикации». |

Если Codex уже обнаружил навык, допустим синтаксис `$animevault-architecture` или
`$animevault-testing` в prompt. Явный путь надёжнее для сессии, открытой до установки.
Cloud skills текущего аккаунта могут дополнять набор, но не обязательны для нового checkout.

## Локальные команды

```sh
# Offline: структура, frontmatter, relative links, license/hash и routing.
python3 tools/verify-ai-skills.py

# Android: JDK 17, SDK 37, существующий app/google-services.json.
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug

# Транзитивные зависимости: не угадывать версию OkHttp по Retrofit.
./gradlew --no-daemon :app:dependencyInsight --dependency okhttp --configuration debugRuntimeClasspath

# Только при наличии устройства/эмулятора.
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

JDK/SDK задавать путями своей среды, не коммитить локальные absolute paths.
Firebase config при отсутствии восстанавливать существующим проверенным
`tools/restore-google-services.py` из настроенного environment, не печатать его
содержимое и не перезаписывать готовый файл автоматически.

## Обновление и сопровождение

1. Создать task-ветку и проверить чистоту/чужие изменения. Изучить installed names
   в `.agents/skills` и пользовательском каталоге; не заменять их целиком.
2. Скачать источник в отдельный временный каталог. Зафиксировать commit, прочитать
   выбранный SKILL.md, ресурсы, license и diff; не исполнять installer/hooks.
3. Проверить совместимость с реальными версиями и scope. Сначала дополнить текущий
   навык/справку, если новый пакет дублирует назначение. Не вводить одинаковые имена.
4. Для upstream обновить только выбранный subtree после review. Сохранить лицензию,
   обновить commit и SHA-256 в `.agents/skills/upstream-lock.json`. Не пересчитывать
   хеши просто ради устранения failure: сначала установить причину изменения.
5. Добавить новый SKILL.md в `local-skills.json`, router AGENTS.md и каталог.
   Авторские инструкции не выдавать за upstream; описать собственные guards отдельно.
6. Запустить validator, `git diff --check`, relevant Gradle checks; PR должен
   содержать источники/pins, новые команды/dependencies и реальные результаты.
   Не переносить source AGENTS.md/plugin manifest/workflows в корень проекта.

`AI Skills validation` запускается на PR/push соответствующих файлов и вручную,
с Python stdlib, без secrets, Android SDK и сети на этапе validation.
Это проверка структуры и байтов, не sandbox для произвольных будущих scripts.

## Ограничения установки

skillsmp.com недоступен (сетевой 403), указанная KMP-коллекция не найдена.
Доступность этих источников не подтверждена и их установка не заявляется.
Фактические результаты Gradle/CI и сохранения конфигурации — в
[AI_SKILLS_VERIFICATION.md](AI_SKILLS_VERIFICATION.md).
