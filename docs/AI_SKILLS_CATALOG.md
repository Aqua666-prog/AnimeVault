# Каталог AI Skills AnimeVault

Проверено 2026-11-10. Постоянный способ установки — версионируемые
`.agents/skills/<name>/SKILL.md`, без изменения глобальной конфигурации Codex.
В checkout находятся **10 навыков: 1 существующий, 7 новых проектных и 2 upstream**.
Ещё один upstream Compose CSV подключён как справка существующего UI-навыка.

Codex может обнаруживать проектные skills при открытии новой сессии. В текущей
сессии наличие в диске проверяется validator; обновление runtime-каталога интерфейса
не гарантируется. Всегда доступен явный путь к SKILL.md через AGENTS.md.
Ни один навык не требует установки runtime-зависимостей в приложение.

## Установленные навыки

Для семи собственных навыков оригинальный репозиторий —
[Aqua666-prog/AnimeVault](https://github.com/Aqua666-prog/AnimeVault), а не сторонняя коллекция.
Прямые пути ниже содержат исполняемую агентом инструкцию и границы применения.

| Название и файл | Оригинальный репозиторий | Назначение и пример в AnimeVault | Статус / Codex | Ограничения и зависимости |
|---|---|---|---|---|
| [animevault-architecture](../.agents/skills/animevault-architecture/SKILL.md) | [AnimeVault](https://github.com/Aqua666-prog/AnimeVault) | Kotlin, AppContainer, StateFlow, cancellation; устранить гонку catalog requests | Новый проектный, установлен; SKILL.md | Существующие Kotlin/coroutines/Lifecycle; без Hilt/KMP миграции |
| [animevault-ui-designer](../.agents/skills/animevault-ui-designer/SKILL.md) | [AnimeVault](https://github.com/Aqua666-prog/AnimeVault) | Compose/Material 3, UX, motion, adaptive, TalkBack; экран downloads в landscape | Существующий, сохранён; SKILL.md | Vault-токены, dp/sp, API24 fallback; manual device QA отдельно; новые [UI-ограничения](AI_SKILLS_UI_GUIDANCE.md) |
| [animevault-media-downloads](../.agents/skills/animevault-media-downloads/SKILL.md) | [AnimeVault](https://github.com/Aqua666-prog/AnimeVault) | Media3/ExoPlayer/HLS, WorkManager/native downloads; resume после отмены | Новый проектный, установлен; SKILL.md | Media3 1.11.0, WorkManager 2.11.2, Room; реальное playback требует device/media fixtures; без Cast/смены движка |
| [animevault-network-sources](../.agents/skills/animevault-network-sources/SKILL.md) | [AnimeVault](https://github.com/Aqua666-prog/AnimeVault) | OkHttp/Retrofit/API; исправить parser Animetka с fixture | Новый проектный, установлен; SKILL.md | Retrofit 3, транзитивный OkHttp; live endpoint/network/auth только по сценарию, без нового test dependency |
| [animevault-persistence](../.agents/skills/animevault-persistence/SKILL.md) | [AnimeVault](https://github.com/Aqua666-prog/AnimeVault) | Room/DataStore/cache/SAF; безопасная миграция download state | Новый проектный, установлен; SKILL.md | Room/KSP, DataStore, DocumentFile; migration/URI tests требуют Android runtime; старые preferences сохраняются |
| [jetpack-compose-performance](../.agents/skills/jetpack-compose-performance/SKILL.md) | [android/skills](https://github.com/android/skills/tree/5815d27fda564aa9f07ebdcb5e57ed3e8d904e0d/jetpack-compose/jetpack-compose-performance) | State/layout/lazy/graphics performance; исследовать scroll jank каталога | Upstream SKILL.md + полный references subtree + Apache-2.0, установлен | Измерения на profileable/release устройстве; API 1.12/1.13 только после проверки resolved версии; baseline tooling не установлен; советы не применяются вслепую |
| [animevault-security](../.agents/skills/animevault-security/SKILL.md) | [AnimeVault](https://github.com/Aqua666-prog/AnimeVault) | MASVS evidence map: auth/storage/network/platform/privacy; аудит updater и OAuth | Новый проектный, установлен; SKILL.md | MASVS/MASTG внешние стандарты; это workflow, не сертификация/полный pentest и не MobSF/Frida |
| [android-intent-security](../.agents/skills/android-intent-security/SKILL.md) | [android/skills](https://github.com/android/skills/tree/5815d27fda564aa9f07ebdcb5e57ed3e8d904e0d/security/android-intent-security) | Intent/IPC/exported components; проверить cold/warm OAuth callback и FileProvider grants | Upstream SKILL.md + Apache-2.0, установлен | Core >=1.9 уже удовлетворён; не покрывает TLS/MASVS целиком; sample marker не auth, logs/permissions требуют [проектных guards](../AGENTS.md) |
| [animevault-testing](../.agents/skills/animevault-testing/SKILL.md) | [AnimeVault](https://github.com/Aqua666-prog/AnimeVault) | JVM/coroutine/Compose/Room/worker regression; проверить replacement скачивания | Новый проектный, установлен; SKILL.md | Существующий JUnit4/Truth/coroutines-test/Android stack; device для instrumentation/TalkBack; golden framework не добавлен |
| [animevault-build-release](../.agents/skills/animevault-build-release/SKILL.md) | [AnimeVault](https://github.com/Aqua666-prog/AnimeVault) | Gradle/Actions/APK/release: dependencyInsight, metadata/signature/version consistency | Новый проектный, установлен; SKILL.md | JDK17/SDK37/Firebase; gh auth для PR, build-tools для APK, signing owner-provided; не публикует автоматически |

## Источники, pinning и отбор

[upstream-lock.json](../.agents/skills/upstream-lock.json) хранит source path,
точный commit, destination и SHA-256 каждого стороннего файла; лицензии лежат рядом.
Хеши проверяют целостность конкретного snapshot, но не доказывают его безопасность.
Собственные skills описывают фактически изученный проект; не выдаются за скачанные пакеты.

| Запрошенный источник | Результат проверки и решение |
|---|---|
| [skillsmp.com](https://skillsmp.com/) | HTTPS CONNECT 403 от сетевой политики среды. Каталог не исследован; установка из него не заявляется. Подбор продолжен по напрямую доступным GitHub-источникам. |
| [android/skills](https://github.com/android/skills) | Commit `5815d27fda564aa9f07ebdcb5e57ed3e8d904e0d`, Apache-2.0. Выбраны два узких пакета; upstream имеет Codex plugin manifest и open-standard SKILL.md. Плагин целиком не регистрировался. |
| [nextlevelbuilder/ui-ux-pro-max-skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) | Commit `6ae86f61f520d6ca229d2f40e070410638c908c5`, MIT. Только Compose CSV с лицензией как static reference; полный skill/CLI не установлен из-за дублирования UI-навыка и defaults Hilt/modules/web. |
| [ghaylansaada/kmp-mobile-agent-skills](https://github.com/ghaylansaada/kmp-mobile-agent-skills) | `git ls-remote` вернул Repository not found. Содержимое/лицензия недоступны; не установлен. Kotlin/Android покрыты явно собственным навыком, без предположения о содержимом коллекции. |

Из android/skills исключены AGP upgrade (уже AGP9), Navigation3 (в проекте Navigation2),
Cast (нет задачи Cast), Camera/AI/XR/Wear/TV/Cars/Billing/Widgets (не относятся к цели).
Полные profiler/R8 CLI workflows и testing-setup не установлены: требуют дополнительных
tools либо дублируют проектные workflows. Адаптивность уже покрывает существующий UI-навык;
полный adaptive пакет с новыми APIs/Navigation3 избыточен для этой настройки.

Сторонние install/setup/search/auto-update скрипты не выполнялись и не поставляются.
Копирование выполнено собственным кодом с отказом при занятом destination; выбраны
только markdown, license и CSV, без symlink/executable assets. Исходные чужие AGENTS,
plugin configuration, workflows и package dependencies в AnimeVault не импортировались.

Глобальные навыки `ub-mode`, `apple-design`, `ui-design-system`, `ux-researcher-designer`
обнаружены в текущей среде и сохранены. Они не являются частью этой установки и не
требуются проектному набору; старые записи об установке других UI-пакетов отражают
историческую сессию, а не подтверждение их текущего наличия.

[Использование](AI_SKILLS_USAGE.md) · [Обследование](AI_SKILLS_PROJECT_AUDIT.md) ·
[Проверки](AI_SKILLS_VERIFICATION.md).
