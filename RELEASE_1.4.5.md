# AnimeVault 1.4.5 — Settings & Personalization

1.4.5 превращает настройки из длинного технического списка в отдельную систему и подключает персонализацию непосредственно к дизайн-системе AnimeVault.

## Категорийная структура

Корневой экран настроек теперь содержит десять разделов:

- Плеер;
- Аудио;
- Субтитры;
- Библиотека;
- Онлайн;
- Источники;
- Оформление;
- Данные;
- Экспериментальные;
- О приложении.

При входе в категорию верхняя кнопка «Назад» сначала возвращает к списку категорий, а затем уже закрывает настройки.

## Темы и акценты

Добавлены режимы `VAULT`, `MIDNIGHT`, `OLED`, `DYNAMIC`.

`DYNAMIC` использует Material 3 `dynamicDarkColorScheme()` на Android 12+; на более старых версиях Android используется фирменная схема Vault. Акцент может быть Violet, Blue, Red или System. На Android 12+ `System` берёт `primary/primaryContainer` из системной dynamic palette и может использоваться не только с полной Dynamic-темой, но и с Vault/Midnight/OLED.

`AnimeBackdrop` больше не зависит от жёстко заданных Vault-цветов и строится от текущего `MaterialTheme.colorScheme`. OLED-mode отключает декоративные ауры корневого backdrop, сохраняя настоящий чёрный фон.

## Blur и motion

Appearance settings передаются через собственный `CompositionLocal` дизайн-системы.

- Blur выключает реальное размытие poster aura в cinematic hero.
- Full motion сохраняет текущие длительности.
- Reduced сокращает переходы.
- Minimal почти мгновенно завершает декоративные переходы и отключает бесконечную skeleton-пульсацию.

Навигационные переходы, reveal hero, press motion и watch-progress animation используют общий motion scale.

## Глобальные defaults плеера

Добавлены настройки по умолчанию:

- playback speed;
- video scale;
- next episode behavior;
- equalizer preset;
- subtitles enabled.

`PlayerPreferences` читает эти значения только если для конкретного тайтла ещё нет собственного сохранённого выбора. Таким образом новая персонализация не перезаписывает привычки уже настроенных тайтлов.

## Persistence

`UiPreferences` теперь публикует `StateFlow` для appearance и playback defaults, поэтому изменения темы применяются без перезапуска Activity.

Room schema/version и backup format не изменялись.

## Проверки

```bash
bash tools/verify-source.sh
bash tools/verify-final.sh
bash tools/verify-1.4.5.sh
./gradlew testDebugUnitTest --stacktrace
./gradlew lintDebug --stacktrace
./gradlew assembleDebug --stacktrace
```
