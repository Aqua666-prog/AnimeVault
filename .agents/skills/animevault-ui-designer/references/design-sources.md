# Проверенные источники и установка

Проверка: 2026-10-28. Источники скачаны заново по точным URL пользователя в `/home/agent/animevault-design-tools`. Первоначальные временные каталоги и ub-mode в этой среде отсутствовали; существующие Skills не удалялись и не перезаписывались.

| Источник | Проверенный commit | Результат |
|---|---|---|
| [Stark](https://github.com/f0d010c/stark) | `ff94e5b4e1c98d259f3cde9f806406c4528deed4` | Нативный SKILL.md и Codex plugin manifest, Apache-2.0 |
| [StyleSeed](https://github.com/bitjaru/styleseed) | `ce66f1dc1d14609ec43bc48eed1d97abb51489c8` | Нативный skills/styleseed/SKILL.md, Codex в README, MIT |
| [Claude Skills](https://github.com/alirezarezvani/claude-skills) | `5434623a788ec3105d238328cfe554e1e2242ff7` | ui-design-system и ux-researcher-designer имеют нативный frontmatter, MIT |
| [Apple Design](https://github.com/emilkowalski/skills/tree/main/skills/apple-design) | `2d228630ba1446365f614a108cb8252f1feda202` | Нативный SKILL.md, MIT; содержание преимущественно для web |

Прочитаны README, соответствующие SKILL.md, инструкции установки; для Stark также `.codex-plugin/plugin.json` и Android route, для StyleSeed — AGENTS.md и update preflight. Это проверка формата и выбранных инструкций, не полный аудит всех вспомогательных программ.

## Установленные пути в этой среде

- `/home/agent/.agents/skills/stark`: корневой router, внутренние маршруты, references и ресурсы с сохранением относительной структуры. Это ручная установка Skill, не регистрация плагина/команд в Codex.
- `/home/agent/.agents/skills/styleseed`: опубликованный физический skill bundle, включая 22 внутренних workflow и их зависимости; не зарегистрированы отдельные ss-* навыки или learning extension.
- `/home/agent/.agents/skills/ui-design-system`.
- `/home/agent/.agents/skills/ux-researcher-designer`.
- `/home/agent/.agents/skills/apple-design`.
- Постоянный проектный навык: `.agents/skills/animevault-ui-designer` внутри AnimeVault, без второй глобальной копии.

Копирование выполнено собственным кодом с отказом при занятом destination. Лицензии сохранены. В глобальных пакетах `INSTALL-PROVENANCE.json` фиксирует commit и SHA-256 файлов. Сторонние install/setup/validation/update скрипты не запускались, зависимости не устанавливались, hooks не регистрировались.
Глобальные пакеты и скачанные репозитории временные: в PR входят только новый проектный навык и его документация. Этот навык самодостаточен и работает без глобальных пакетов.
Для обнаружения новых глобальных навыков может понадобиться новая сессия Codex. Проверена структура на диске, не фактическая активация в следующей сессии.

## Заимствованные принципы и Android-адаптация

| Источник | Применяемый принцип | Реализация в AnimeVault |
|---|---|---|
| Stark | Сначала product job, объект, действие, platform idiom, состояния и доказательство результата | Краткий decision brief, branded native Compose, карта компонентов и проверка состояний |
| StyleSeed | Сохранение утверждённых проектных решений, стабильные семантические роли и render → inspect → revise | Vault tokens как design lock; реальная доступность и состояния важнее числового design score |
| Apple Design | Немедленная обратная связь, непрерывность и прерываемость движения, пространственная согласованность, иерархия типографики | Compose interaction/animation APIs и существующие Material controls; reduced/off motion |
| UI Design System | Токены, API вариантов компонентов, state coverage, developer handoff | Карта повторного использования; никакой второй шкалы цветов или размеров |
| UX Researcher Designer | Journey mapping, сценарии исследования и usability testing | Отдельный план проверки реальных пользователей; предположения не выдаются за результаты исследований |

## Разрешение конфликтов

Пользовательский scope и существующий проект имеют приоритет над чужими defaults.
Stark Android route предлагает M3 Expressive, новые версии, Hilt, шрифты и навигацию: это не разрешение менять стек AnimeVault. Выбран branded native Kotlin/Compose/Material 3; повторно спрашивать платформу не требуется.
Apple Design переводит WWDC в CSS/JS: переносить можно принципы, не web-компоненты, JS physics или обязательный blur. Системный SansSerif, Android 48dp и Material sheets остаются основой.
UI Design System содержит px/HTML/CSS и touch target 44px: в AnimeVault применять sp/dp, Compose semantics и минимум 48dp.
StyleSeed web code/pixel gates не доказывают качество Android. Его правило против случайного flat black не отменяет намеренный AMOLED. Фиолетовый здесь утверждённый бренд, не «невыбранный default accent».
StyleSeed update preflight предлагает запуск Node checker: он не выполнялся из-за запрета пользователя на непроверенные скрипты. Нельзя заявлять freshness gate или score pass. Для этой настройки использованы прочитанные файлы на указанных commits; workflows не запускались.

## Повторная установка без повреждения окружения

1. Найти существующие skill names в проектной и пользовательских папках Codex.
2. Скачать указанный источник на проверенном commit; прочитать README, SKILL.md, LICENSE и относящиеся инструкции.
3. Проверить симлинки, пути ресурсов и команды. Само наличие SKILL.md не доказывает безопасность scripts.
4. Копировать полный необходимый subtree только в свободный путь; для Stark сохранять root-relative references и внутренние маршруты, для StyleSeed использовать `skills/styleseed` с workflows. Не разносить внутренние маршруты Stark или StyleSeed в отдельные глобальные Skills: так можно создать конфликты имён, в частности apple-design.
5. Сохранить LICENSE/NOTICE и source/commit/hash manifest. Не выполнять npx/install/hooks/auto-update ради копирования.
6. Проверить frontmatter, относительные ссылки и хеши. При совпадающем пакете пропустить; при конфликте версий сохранить старый и сообщить о конфликте.
7. Не копировать сторонние AGENTS.md в корень AnimeVault и не добавлять runtime-зависимости приложения.
