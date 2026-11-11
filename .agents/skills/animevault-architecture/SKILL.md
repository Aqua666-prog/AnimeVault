---
name: animevault-architecture
description: Use for AnimeVault Kotlin architecture, ViewModel state, coroutines, dependency wiring and data-flow changes. Avoid build-only and visual-only work.
---

# Kotlin и архитектура AnimeVault

Прочитать корневой AGENTS.md и актуальные Gradle declarations до изменения кода.
Источник этого проектного навыка — AnimeVault; это не копия недоступной KMP-коллекции.

## Порядок работы

1. Найти точку входа, ViewModel, repository, authoritative storage и фоновых writers.
2. Прочитать `AnimeVaultApplication.kt`: зависимости создаются в AppContainer;
   сохранить существующий lifetime. Не вводить DI framework или use-case слой без причины.
3. Записать контракт операции: вход, результат, loading/error/cancel, сохранение состояния.
4. Переиспользовать существующие модели идентичности тайтла/эпизода и parser helpers.
5. Сделать минимальную правку в правильном слое. Чистые преобразования отделять от I/O,
   но не переносить весь проект на новую архитектуру.
6. Проверить конкурирующие запросы: запоздалый ответ старого поиска, повторный tap,
   смена источника, уничтожение ViewModel и перезапуск процесса.

## Kotlin и coroutines

- `val` по умолчанию; nullable значения проверять на границе, не маскировать `!!`.
- UI публикует immutable state, события идут через существующий контракт экрана.
- Scope принадлежит lifecycle/компоненту; не использовать GlobalScope и detached jobs.
- Не глотать CancellationException в `runCatching`/общем catch; изучить
  `util/CancellableResult.kt`. Cancellation отменяет дочернюю сеть и I/O.
- Не выполнять blocking network/filesystem на Main. Для CPU и I/O выбирать
  соответствующий dispatcher; injection добавлять только при реальной потребности тестов.
- Для согласованной записи нескольких строк использовать transaction; mutex не заменяет
  persistent fencing, когда состояние меняет Worker после перезапуска процесса.

## Проверка и результат

Проверить пользовательский сценарий и cancellation/race при затронутой конкурентности.
Запустить подходящие JVM-тесты и lint; для lifecycle добавить проверку на устройстве,
если она доступна. Сообщить изменённый контракт, затронутые owners и результаты.
Не заявлять отсутствие lifecycle-регрессий только по успешной компиляции.

Зависимости: существующие Kotlin, Lifecycle, coroutines; новых нет.
Смежные навыки: persistence для транзакций, media-downloads для player/worker lifetime.
