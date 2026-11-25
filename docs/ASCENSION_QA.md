# Project Ascension — QA

## Локально

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
./gradlew -PanimeVaultVisualTests=verify :app:testDebugUnitTest --tests '*AscensionVisualTest'
./gradlew --no-daemon :app:connectedDebugAndroidTest
python3 tools/verify-ai-skills.py
python3 -m unittest discover -s tools -p 'test_verify_ai_skills.py'
```

Визуальная проверка использует фиксированный набор тайтлов, календарных дат и
состояний проигрывания. Robolectric API 35 и native graphics дают однажды
записанные PNG. Проверка выполняется без сети со стороны приложения.
Режим `-PanimeVaultVisualTests=record` служит для явной записи эталонов.
Просмотрите diffs и новые изображения, прежде чем фиксировать их в Git.
Десять эталонов покрывают главную, каталог, поиск, тайтл, библиотеку,
загрузки, календарь, настройки и плеер. Для RenderEffect, видео и реального
системного font scaling требуется устройство.

## Устройство

| Приоритет | Проверка |
| --- | --- |
| API 24 | Установка/upgrade с сохранением Room, переключение пресетов, тональный fallback, обычный SAF и загрузки |
| API 31+ | Haze на Home и странице тайтла в трёх пресетах. Battery Saver до и после смены режима, перезапуск процесса |
| SAF | Скан внутреннего хранилища, SD и облачного провайдера. Недоступное поддерево, отмена и revoked grant сохраняют прошлый результат. Удаление в полном скане удаляет только исчезнувшие URI. Перенос серии сохраняет прогресс и субтитры |
| Playback | HLS/MP4, скачанный и SAF-файл, ошибка чтения и повтор, восстановление позиции, выбор качества/озвучки/субтитров, PiP и MediaSession |
| Удержание памяти | Цикл из 20 открытий и закрытий тайтла, каталога и плеера. LeakCanary: `./gradlew -PanimeVaultLeakCanary=true :app:assembleDebug` |
| TalkBack | Порядок фокуса, radio state пресетов, прогресс, статус загрузки, 200% шрифт, системное отключение анимации |
| Интеграции | Firebase guest/sign-in, Supabase comments, AniList, Shikimori и выбранные онлайн-источники |

## Результаты кандидата

- На базе `aad02e0` прошли `testDebugUnitTest lintDebug assembleDebug`.
- На 4.3.0 прошли 639 JVM-тестов, включая запись пресетов и вытеснение Tenrai.
  Roborazzi сравнил десять снимков. Валидатор Skills проверил 10 навыков,
  3 закреплённых upstream-набора и прошёл все 33 проверки.
- `lintDebug` завершился успешно. В отчёте 42 предупреждения, 5 подсказок,
  0 ошибок. Собраны `app-debug.apk` и APK инструментальных тестов.
  `aapt` подтвердил 4.3.0 (87), minSdk 24 и targetSdk 37.
  `apksigner` подтвердил локальную debug-подпись для API 24+.
- Debug-конфигурация LeakCanary прошла проверку AAR metadata и слияние
  манифеста. Проверка удержания объектов требует устройства.
- На локальной машине нет подключённого устройства. Инструментальные тесты
  включены в текущую ветку CI. [Проверки PR #62](https://github.com/Aqua666-prog/AnimeVault/pull/62/checks)
