# Измерения AnimeVault

Исходный коммит — `aad02e0`. APK, который лежал в workspace при запуске аудита, занимает 33 846 440 байт (debug). Метрики устройства отсутствуют с обеих сторон: ADB не показал подключённых устройств. Размер debug APK не заменяет сравнение оптимизированных вариантов.

## Протокол

На физическом устройстве выберите один и тот же источник, аккаунт и набор локальных файлов для обоих коммитов. Соберите release с одинаковой подписью и настройками, установите соответствующий APK без очистки данных, завершите onboarding. Для каждого прогона укажите сеть, API, частоту обновления, состояние энергосбережения и версию приложения. Запускайте каждый пресет отдельно после его сохранения.

```sh
python3 tools/measure-ascension.py --apk app-release.apk --serial DEVICE \
  --preset balanced --scenario startup --iterations 10 --output startup-balanced.json
python3 tools/measure-ascension.py --apk app-release.apk --serial DEVICE \
  --preset cinematic-ultra --scenario interaction --output catalog-ultra.json
```

Startup сохраняет шельный вывод `am start -W`. Проверяйте `LaunchState` в каждом образце. Повторный запуск после Home обычно HOT. Отдельный WARM измеряется после уничтожения Activity с сохранением того же PID. Не объединяйте эти группы. Для взаимодействий скрипт обнуляет статистику кадров, затем снимает `gfxinfo framestats` и PSS. GPU и CPU проверяйте через Perfetto во время идентичной прокрутки. Отчёт должен отдельно показывать percentiles и количество кадров, пропустивших дедлайн. Сохраняйте выходные данные вне git.

| Сценарий | Что фиксировать |
| --- | --- |
| Холодный, тёплый и горячий запуск | Длительность по классу запуска и момент доступности интерфейса |
| Онлайн-каталог и большая локальная библиотека | Одинаковое число жестов, список с постерами, p50/p95/p99 кадра, jank, PSS |
| Открытие тайтла и переходы по вкладкам | Повторяемый маршрут, frame metrics, задержка появления контента |
| Плеер HLS, MP4 и локальный файл | От открытия маршрута и отдельно от prepare до первого кадра, восстановление позиции |
| Длительная навигация | PSS до и после цикла, heap retention после background/foreground |

До появления результатов устройства ориентиры пользователя остаются целями. Системные frame stats не характеризуют кадры декодера видео.

## Сборка для измерений и Baseline Profile

```sh
./gradlew -PanimeVaultBenchmarks=true :app:assembleBenchmark :macrobenchmark:assembleBenchmark
./gradlew -PanimeVaultBenchmarks=true :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sergey.animevault.benchmark.AnimeVaultBenchmark
./gradlew -PanimeVaultBenchmarks=true :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sergey.animevault.benchmark.AnimeVaultBaselineProfile
```

Тестовый APK использует API 28+, целевой `benchmark` компилируется как release с
minify/shrink и manifest `profileable`. Для генерации Baseline Profile нужен
поддерживаемый ARM/Android runtime, обычно API 33+ или root. Подпись benchmark
использует debug key. Для предзаполненной библиотеки используйте выделенное
устройство и ту же подпись между сравниваемыми сборками. Установка поверх
APK с другим сертификатом не поддерживается Android.

Макробенчмарк измеряет cold/warm startup и переход между пяти основными
разделами. Завершите onboarding и системные диалоги заранее. Для чистого
сравнения профиля повторите замеры с разными режимами `CompilationMode`.
Сохраните baseline profile из результатов теста и измерьте его влияние
до упаковки в релиз. Сгенерированного Baseline Profile в этом изменении нет.

Startup-метка fully drawn относится к доступности первого интерфейса и
завершению видимой заставки. Сетевые данные Home могут загрузиться позже.
Первый кадр Media3 доступен в Perfetto на API 29+. Счётчики
`AnimeVault.firstFrameMs.local` и `AnimeVault.firstFrameMs.online`
записываются только при фактическом первом кадре. Async slice
`AnimeVault.prepare.*` закрывается также при ошибке или dispose.
