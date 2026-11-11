---
name: animevault-testing
description: Use to choose and run AnimeVault regression checks, Kotlin/coroutine JVM tests, Compose semantics tests, Room migrations and WorkManager/device scenarios.
---

# Проверка AnimeVault

1. Прочитать AGENTS.md и diff. Составить краткую карту риск → сценарий → уровень теста.
2. Использовать существующий test stack: JUnit4, Truth, coroutines-test; Android runner,
   Compose UI, Room testing, work-testing. Не вводить новую test platform автоматически.
3. Для чистых parser/policy/identity функций запускать JVM-тесты с deterministic fixtures.
   Для coroutines применять `runTest`/test scheduler; не лечить flaky tests sleep-ами.
4. Для Room/worker использовать Android tests: проверить уникальность, migration,
   cancellation/replacement, retry и process-restorable state. Не мокать всю систему
   так, чтобы проверялся только тестовый double.
5. Compose: проверить semantics, callbacks, доступность, back stack, крупный шрифт,
   состояния loading/empty/error/offline. TestTag — при необходимости, semantics
   пользователя предпочтительны; не добавлять production код ради удобства теста.
6. Для player/download проверить реальный файл и lifecycle на устройстве. Сценарии:
   online → seek → поворот/PiP → возврат; download → cancel/resume → offline playback.
   Записывать API/device/build, не считать успешный resolve проверкой playback.
7. TalkBack, contrast поверх poster и reduced motion требуют ручной проверки;
   Compose semantics test не заменяет TalkBack.

## Команды

Из корня с настроенными JDK 17, SDK и Firebase config:

```sh
python3 tools/verify-ai-skills.py
./gradlew --no-daemon :app:testDebugUnitTest --tests 'полное.ИмяТеста'
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

Последняя команда требует доступного устройства/эмулятора. Подбирать test class
через фактический поиск `app/src/androidTest`, не угадывать имя.
Для documentation-only правок достаточно структуры/ссылок; не писать тесты,
которые повторяют текст инструкции. При изменении validator проверить негативный случай.

## Результат

Указать команду, результат/количество тестов, причину пропуска и ограничения.
При failure сначала прочитать report/stacktrace, отделить инфраструктуру от regression.
Не подавлять lint, не удалять тест и не менять versions ради зелёного статуса.
