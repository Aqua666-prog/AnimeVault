# Повторная проверка PR #59

Дата: 2026-11-10. PR: [#59](https://github.com/Aqua666-prog/AnimeVault/pull/59).
Первоначальная ревизия: `62c1f68098f13ce4a21044cba13b3d8c811a33dd`.
Исправления выполняются только в `chore/animevault-ai-skills`, без merge.

## GitHub Actions и обязательность проверок

На исходной ревизии завершились успешно все 9 check runs:
два `validate` (push/PR), `android`, `device (24)`, `device (25)`, `device (35)`,
`edge`, `edge-metadata`, `sql`.

- [AI Skills, push](https://github.com/Aqua666-prog/AnimeVault/actions/runs/42896161627).
- [AI Skills, PR](https://github.com/Aqua666-prog/AnimeVault/actions/runs/42896223479).
- [Community PR verification](https://github.com/Aqua666-prog/AnimeVault/actions/runs/42896223576).

GitHub GraphQL `CheckRun.isRequired(pullRequestNumber:59)` вернул `false` для всех
9 checks; `mergeable=MERGEABLE`, `mergeStateStatus=CLEAN`. Effective branch rules
REST endpoint вернул `[]`. Прямой административный endpoint branch protection
недоступен интеграции (403), поэтому его конфигурация не заявляется просмотренной.
По доступным данным GitHub не помечает эти checks обязательными; все запущенные
checks всё равно проверены. Настройки защиты ветки не менялись.

После исправлений требуется снова проверить **новый head SHA**, а не полагаться
на эти исторические runs. Актуальные run links и заключение фиксируются в описании
PR после завершения всех Actions. Удалённый Community workflow запускает
compile/unit/lint/assemble и synthetic Auth/Keystore/spoiler instrumentation
на API24/25/35; это не полный playback/download E2E.

## Найденные и исправленные проблемы

1. **Средний, validator:** regex признавал `description: [unterminated` и пустой
   block scalar `description: |` корректными. Теперь обязательные поля проверяются
   как явно ограниченный YAML plain scalar, с проверкой пустоты, duplicate fields,
   ограничения длины и непустого тела. Все 10 текущих frontmatter отдельно прочитаны
   через доступный в среде `yaml.safe_load` и корректны; PyYAML не добавлен в dependencies
   или CI. Validator намеренно не реализует весь YAML.
2. **Средний, integrity:** удаление записей из upstream-lock отключало их hash check.
   Registry теперь явно содержит `upstream_bundles`; состав lock сверяется с ним,
   upstream skill обязан ссылаться на свой bundle. Пустой lock, duplicate bundles,
   schema/JSON key errors отклоняются.
3. **Низкий, проверяемость:** негативные проверки ранее были разовыми. Теперь
   [33 CLI regression tests](../tools/test_verify_ai_skills.py) запускаются в CI.
   32 повреждённых checkout должны вернуть ненулевой exit и `ERROR`, чистый — успех.
4. **Низкий, filesystem:** symlinks теперь отвергаются до чтения content; пути
   manifest/AGENTS/documents проверяются, upstream destination не может выходить
   за skills. Не допускаются executable mode bits даже у markdown-файла.

Тесты покрывают: повреждённые байты, missing skill/reference/license, неверное имя,
header/description/body, duplicate keys/names, registry/lock mismatch, broken links
и AGENTS routes, symlink, unexpected script, executable markdown, commit/hash/path.
Отдельная mutation-проверка заменила validator на `print('OK: ...')` с exit 0:
набор получил **32 failures**, то есть обнаруживает always-success заглушку.
Тесты работают в disposable copies и не повреждают реальные навыки.

Проверка целостности не защищает от намеренного одновременного изменения reviewer-ом
validator, registry, lock и тестов. Источник доверия — code review pinned snapshots;
хеши не заменяют аудит содержимого или проверку поведения будущих инструкций.

## Доступность и инструкции

Все 10 `.agents/skills/*/SKILL.md` существуют, читаются, имеют уникальное имя,
непустые description/body, соответствуют registry и ссылкам AGENTS. Два upstream
навыка и Compose CSV имеют исходные licenses и неизменённые SHA-256.
Каждый навык доступен явным чтением файла; автоматическое обновление каталога
UI текущей сессии не гарантируется и не выдаётся за проверенное.

Других AGENTS.md в checkout/его родительских каталогах не найдено. Существующий
UI-навык сохраняет Vault-токены, API24, motion/accessibility и запрет незапрошенных
build/CI/PR действий; текущий запрос такие проверки и исправления явно разрешает.
Root AGENTS согласован с ним. Дополнительно уточнён приоритет task-ветки над
глобальным ub-mode «push to main line» и исторический статус старых install records.
Upstream Compose 1.12/1.13, Intent examples и UI CSV Hilt/module defaults остаются
ограничены проектными guards; миграция стека не выполнялась.

## Скрипты, permissions и функциональность

В upstream bundles нет executable scripts/binaries/hooks: только Markdown, license
и CSV. Они не выполнялись. Новый validator использует stdlib, не сеть/exec/eval.
Test harness запускает только собственный validator через sys.executable без shell,
с timeout, в temporary checkout. Внешний SKILL.md не превращается в программу.

Workflow использует pull_request/push, не pull_request_target, `contents: read`,
checkout с persist-credentials:false. Новых secrets, OIDC/write/actions permissions,
environment bindings или deploy steps нет. Actions checkout соответствует уже
используемому v4; установка сторонних пакетов не добавлена.

Сравнение с base `12fc546e902f8d4edea2b458376d94eaad990353`: дерево `app/`,
Gradle declarations/properties/wrapper и все прежние workflows совпадают побайтово.
Меняются только docs/instructions/skill data, registry, собственные validator/tests
и новый workflow. Android manifest/permissions, resources, Room schema, runtime
dependencies, player/download/network implementation не затронуты.
Различий функциональности приложения в diff нет; это сильнее, чем один успешный build.

## Android Lint

[Полный разбор всех 66 предупреждений](AI_SKILLS_LINT_REVIEW.md):
**0 критических, 0 высоких, 9 средних, 57 низких**, отдельно 5 Hint.
По встроенной severity это 66 Warning, не 66 Error. Большая группа — обновления
зависимостей (24), KTX/style (27); medium — adaptive/window sizing, notifications,
PiP transition и updater storage checks. Проверенные места/обоснования приведены
в отдельной таблице; исправление старого Android-кода не включено в этот PR.

## Условия готовности

Блокирующие пробелы интеграции устранены; локальные validator/regression checks
прошли. Финальное заключение допускается только после завершения всех Actions на
ревизии с исправлениями. Проверки не означают полный MASVS audit, performance
benchmark или полную runtime QA приложения. Merge выполняется только по отдельному
подтверждению пользователя.
