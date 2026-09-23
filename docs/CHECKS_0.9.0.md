# AnimeVault 0.9.0 — проверки этапа Metadata Foundation

Перед передачей этапа выполнены следующие проверки без изменения существующей медиатеки:

- Kotlin PSI syntax parse: все Kotlin-файлы `app/` разбираются без синтаксических ошибок.
- Android XML parse: все XML-файлы `app/` валидны.
- GitHub Actions YAML parse: workflow валиден.
- Проверка известных ошибок реальной сборки 0.8.5: нет ссылок на локальный Maven proxy `127.0.0.1:18080`, старого импорта `animation.core.animateColorAsState`, импортированного `layout.matchParentSize`, `Forward15` и `androidx.annotation.OptIn`.
- Smoke-test миграции Room 2 → 3 в SQLite: таблица `title_metadata` создаётся, индекс создаётся, FK `ON DELETE CASCADE` удаляет метаданные вместе с локальным тайтлом.
- Smoke-test выбора поискового запроса: онлайн-алиас имеет приоритет, затем title hint из имени серии, затем локальное название.
- Smoke-test очистки описания AniList: markdown-ссылки/декораторы и варианты `<br>` обрабатываются ожидаемо.
- Добавлены JVM unit tests для разбора ответа AniList, GraphQL errors, очистки описания и выбора поискового запроса.

Полный Android compile локально не запускался, потому что рабочая среда не может разрешить `services.gradle.org`. Поэтому CI теперь является обязательным gate: сначала `testDebugUnitTest`, затем `assembleDebug`. Этап считается готовым к следующей фазе только после зелёного GitHub Actions run и ручной проверки APK на устройстве.
