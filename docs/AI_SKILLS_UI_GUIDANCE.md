# Дополнение к animevault-ui-designer

Основной навык: [animevault-ui-designer](../.agents/skills/animevault-ui-designer/SKILL.md).
Его исходные инструкции и references сохранены без изменений.

Из [UI UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill)
зафиксирован только [Compose CSV](../.agents/skills/animevault-ui-designer/references/ui-ux-pro-max/jetpack-compose.csv)
с MIT license; commit и hashes в [upstream-lock.json](../.agents/skills/upstream-lock.json).
Это справочные данные существующего навыка, не установка полного CLI/нового навыка.
Python search/design-system scripts и npm installer не копировались и не запускались.

## Как применять

Прочитать CSV для нужной области (State, Lifecycle, Layout, Animation, Accessibility),
сверить с текущими компонентами и фактически resolved API. Поле «current stable»
отражает upstream snapshot, а не обещание совместимости с BOM проекта.

- State hoisting, lifecycle collection, cleanup effects, MaterialTheme и semantics
  применимы с учётом существующей архитектуры и реального сценария.
- Строка 38 предлагает Hilt, строка 44 — feature modules: в AnimeVault остаются
  AppContainer и `:app`. Эти рекомендации не являются задачей миграции.
- Строка 16 про Channel/navigation не определяет универсальную гарантию доставки.
  Сохранять существующий navigation contract и проверять lifecycle/back stack.
- Строка 8 не требует `derivedStateOf` для каждого вычисления; использовать только
  при полезном сокращении частоты обновлений, измерять результат.
- Stateless by default не запрещает локальное UI state; mutable internals допустимы
  при корректном observable state и lifetime. `@Immutable` не исправляет mutable model.
- Lazy layouts нужны для больших списков; маленькие фиксированные группы не требуют
  виртуализации. Не запрещать nested scrolling, Box или fillMaxSize без анализа constraints.
- Тесты выбирать по пользовательским semantics; testTag не обязателен в каждом компоненте.
- Motion следует VaultMotion и reduced/off settings; themes VAULT/MIDNIGHT/OLED/DYNAMIC
  сохраняются, размер touch target — минимум 48dp.

Не импортировать web/RN/SwiftUI ресурсы, шрифты, новую палитру или navigation framework
из полной коллекции. Качество адаптивности подтверждается проверкой window size,
landscape, font scale и TalkBack; CSV сам по себе такую проверку не выполняет.
