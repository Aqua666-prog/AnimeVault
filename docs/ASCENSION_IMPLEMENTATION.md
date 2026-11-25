# AnimeVault — Project Ascension

Ветка основана на `aad02e0`, HEAD `main` на момент аудита. Версия кандидата
4.3.0 (87), minSdk 24. Состояние сохранения данных, профильные измерения и
визуальные настройки разобраны отдельно.
[Аудит](ASCENSION_AUDIT.md) · [Дизайн-система](ASCENSION_DESIGN_SYSTEM.md) ·
[Измерения](ASCENSION_BENCHMARKS.md) · [QA](ASCENSION_QA.md)

## Что изменилось

| Подсистема и файлы | Реализация |
| --- | --- |
| `LibraryScanner.kt`, `LibraryRepository.kt`, `LibraryDao.kt` | Прямой cursor SAF с флагами неполной выдачи, проверкой количества строк и кооперативной отменой. Сверка начинается после полного обхода. Перенос эпизода сохраняет ID и progress. Удаления используют существующую транзакцию и ограниченные пакеты |
| `AnimeVaultBackupRepository.kt`, `AnimeVaultDatabase.kt` | Потоковая сериализация в I/O dispatcher. Backup сообщает версию фактической схемы. Формат и миграции сохранены |
| `TenraiResponseCache.kt` | Сначала вытесняются старые записи. Ответ, превышающий бюджет кэша, больше не вытесняет сохранённые записи |
| `AnimeVaultApplication.kt`, `VaultArtwork.kt` | Coil 3.5.0, прежний 128 МиБ дисковый кэш, ограничения Compose для декодирования, ограничение memory cache на low-RAM, LRU из 64 оттенков. Выборка цвета работает в Dispatchers.Default. Заголовки провайдеров сохранены |
| `GraphicsPreset*.kt`, `Theme.kt`, `SettingsScreen.kt`, `MainActivity.kt`, `PlayerActivity.kt` | Отдельный ключ DataStore, применение на лету, временный Performance при энергосбережении и уважение пользовательского и системного motion. Balanced по умолчанию. Длинная декоративная заставка доступна в Cinematic Ultra |
| `VaultCinematic.kt`, `VaultPosterCard.kt`, `VaultSurfaces.kt`, `VaultNavigationShell.kt`, `AnimeCalendarScreen.kt` | Тон и глубина поверхностей, единая карточка, локальное размытие баннера, статическое состояние загрузки и повтор запроса календаря, контрастная навигация |
| `WatchProgressBar.kt`, `DownloadsScreen.kt`, `OnlineTitleScreen.kt` | Прогресс отрисовывается без переизмерения на каждом кадре. Короткая реакция цвета сопровождает обновление текста или значка статуса |
| `PlayerScreen.kt`, `OnlinePlayerScreen.kt`, `PlaybackFirstFrameTrace.kt` | Трасса первого кадра, пропуск дублирующего seek, повтор локального воспроизведения. Существующие владельцы ExoPlayer, PiP, MediaSession и transport сохранены |
| `macrobenchmark/`, `tools/measure-ascension.py` | Профилируемая release-подобная сборка, cold/warm startup, навигация, сбор Baseline Profile и ручной сбор PSS/frame stats |
| `app/src/visualTest/`, `SafCursorTest.kt`, `GraphicsPresetTest.kt`, `TenraiFileCacheEvictionTest.kt` | Снимки на Robolectric, проверки SAF, сохранения пресета и вытеснения дискового кэша |
| `.agents/skills/`, `.github/workflows/build-apk.yml` | Инструкции по памяти, производительности, тестам, доступности и зависимостям. На ветке включена существующая сборка APK |

## Зависимости

- Production добавляет Haze 1.7.2, Android minSdk 23, Kotlin stdlib 2.2.21 в POM и
  Compose 1.10.0 в POM. Текущий BOM разрешает Compose 1.11.4. Линия Haze
  1.7.3+ потребовала бы Compose 1.12 и отдельной проверки версии.
- LeakCanary 2.14 доступен только с `-PanimeVaultLeakCanary=true` для debug.
- Roborazzi 1.79.0 и Robolectric 4.18.1 доступны в режиме визуальных тестов.
- AndroidX Benchmark 1.5.0 и UI Automator 2.3.0 нужны только opt-in тестовому
  APK. Библиотеки приложения не удалены. Для анимаций достаточно Compose.
  [Аудит источников и совместимости](ASCENSION_AUDIT.md)

## Оставшиеся риски

Нужны реальные device-замеры GPU, памяти и первого кадра, ручная проверка
TalkBack и тесты Haze с реальными баннерами. Долгий цикл проигрывания и фон
проверяются отдельно для локального и онлайн-плеера. Предзагрузка серии
остаётся предметом измерения: безопасное решение должно учитывать стоимость
повторного получения временных URL, ограничения сети и Battery Saver.

Неполный SAF cursor с признаками ошибки отклоняется. Провайдер, который
возвращает неверную, формально полную выдачу, требует наблюдения на устройстве.
Одновременное удаление папки пользователем и сканирование, повреждённые
загрузки и случаи A02–A11/A17–A19 из исходного технического аудита остаются
отдельными сценариями. Стриминговые, облачные и OAuth-интеграции требуют
регрессии с действующими аккаунтами и источниками.

На этапе Final Polish добавлены защита от формально полной выдачи SAF с массовой пропажей файлов, проверяемое возобновление MP4, ограничение HLS и атомарный commit скачивания в Room. [Финальный аудит](ASCENSION_FINAL_AUDIT.md)
