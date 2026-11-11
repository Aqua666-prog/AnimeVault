---
name: animevault-persistence
description: Use for AnimeVault Room migrations and DAOs, DataStore/preferences, offline SAF/MediaStore scanning, cache invalidation and durable user state.
---

# Room, preferences, кэш и offline library

1. Прочитать AGENTS.md; определить authoritative store и всех writers.
   Основная Room БД v8 и Community cache v1 независимы. Проверить актуальную версию.
2. Для schema change прочитать entities, DAO, `app/schemas` и миграции в
   `AnimeVaultDatabase.kt`. Сохранить downloads, favorites, progress и grouping.
3. Добавлять миграцию от текущей версии; не применять destructive fallback.
   Несколько зависимых записей менять транзакционно, учитывать unique indexes/FK.
4. Не путать Flow emission с завершённой записью; не запускать scan/query на Main.
   Проверять idempotency после повторного выполнения Worker/импорта.
5. Preferences: onboarding уже использует DataStore, `UiPreferences` —
   SharedPreferences. Не мигрировать существующие ключи/enum в рамках соседней задачи.
   При явно запрошенной миграции определить precedence и однократность переноса.
6. Cache: определить key, TTL, stale policy, размер/eviction, account/provider isolation;
   не кэшировать expired stream URL как бессрочный. Logout должен учитывать приватные данные.
7. SAF: persisted URI permissions, revoked grant, недоступный документ и recursion.
   Не превращать content URI в произвольный filesystem path. MediaStore и SAF имеют
   разные модели доступа; rescan не должен терять пользовательскую группировку.
8. Импорт/backup: versioned format, предел размера, validation до записи,
   rollback/transaction и отсутствие path traversal; проверять совместимость старых backup.

## Проверка

Для DAO/migration использовать `AnimeVaultMigrationTest`, `OnlineStateRoomTest`
и сценарии downloads при наличии Android runtime. JVM-тесты parser/grouping/cache
запускать отдельно; `unitTests.isReturnDefaultValues` не доказывает корректность Room.
Проверить upgrade с данными, дубликаты, rollback, rescan и revoked permission
по затронутому scope. Не выполнять разрушительные проверки на пользовательской БД.

Зависимости существующие: Room/KSP, DataStore, DocumentFile, Android SDK.
Новые SQL drivers, encrypted database или preference wrappers не требуются установкой навыка.
