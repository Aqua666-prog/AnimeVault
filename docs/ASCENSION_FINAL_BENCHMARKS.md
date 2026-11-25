# Final Polish · Измерения

APK собраны с одним JDK 17, SDK 37, конфигурацией Firebase и вариантом Gradle. APK 4.2.0 собран из `aad02e0`. Для 4.3.0 отдельно измерены сборки до и после добавления профилей приложения. Debug-значения предыдущего этапа сохранены отдельно. [Исходный протокол](ASCENSION_BENCHMARKS.md)

| `assembleRelease`, unsigned APK, R8 + shrinkResources | Размер | Изменение к 4.2.0 |
| --- | ---: | ---: |
| 4.2.0 (86), `aad02e0` | 6 996 733 байта | |
| 4.3.0 (87), профили зависимостей | 7 059 582 байта | +62 849 байт (+0,90%) |
| 4.3.0 (87), профили зависимостей и приложения | 7 141 560 байт | +144 827 байт (+2,07%) |

Сборки прошли R8, shrinkResources и `lintVitalRelease`. `aapt` подтвердил единый пакет, minSdk 24 и targetSdk 37. Применение обоих профилей приложения изменило размер APK на 81 978 байт. В итоговом release APK файл `assets/dexopt/baseline.prof` занимает 15 216 байт, `baseline.profm` — 1 650 байт. Проверены заголовки обоих файлов и совпадение промежуточного `startup-prof.txt` с исходным.

## Доступность измерений

В рабочей среде `adb devices -l` не нашёл устройств, `/dev/kvm` отсутствует. Метрики времени запуска, TTID/TTFD, кадров, PSS, удержания объектов, CPU/GPU и первого кадра видео не измерены. Модуль Macrobenchmark поддерживает cold, warm и hot. Скрипт adb собирает холодный запрос и возврат из Home, фиксируя фактически сообщённый Android LaunchState. Если версия Android не сообщает тип запуска, поле остаётся пустым. Доля просроченных кадров считается только для записей с реальным FrameDeadline.

## Повторяемый прогон

1. Собрать один и тот же `:app:assembleRelease` для обоих коммитов. Для экспериментальных установок использовать одинаковую подпись и выделенное устройство. До замеров завершить onboarding, зафиксировать версию ОС, частоту дисплея, состояние батареи, энергосбережение и разрешения.
2. Для каждого отдельного APK убедиться, что SHA-256 установленного файла совпадает с `--apk`. На 4.2.0 использовать `--preset legacy`, на 4.3.0 прогнать каждый пресет. Отдельно повторить Performance при системном энергосбережении. Зафиксировать порядок чередования запусков и один одинаковый локальный набор тайтлов и постеров.
3. Снимать cold и возврат из Home через `tools/measure-ascension.py --scenario startup`, warm через Macrobenchmark. Для TTFD дождаться `reportFullyDrawn`, который в 4.3.0 относится к готовности оболочки и заставки. Сетевой ответ каталога записывать отдельно, с одним и тем же состоянием кэша.
4. Для каталога, большой локальной коллекции, открытия тайтла и длительной навигации использовать `--scenario interaction`, одинаковую последовательность действий и Perfetto. Сохранять сырые `framestats` и PSS. Сравнить p50/p95/p99 и выборку с дедлайном.
5. Для локального видео, HLS и MP4 фиксировать отдельные таймеры до разрешения ссылки и `AnimeVault.prepare.*` / `AnimeVault.firstFrameMs.*`. Счётчик публикуется только при доставленном первом кадре. Проверять вызов повторного prepare, уход в фон и закрытие. Для 4.2.0 использовать внешнюю визуальную/trace-метку, у сравниваемых серий должны быть одинаковый формат, разрешение и качество.
6. Для памяти включить `-PanimeVaultLeakCanary=true` в debug и выполнить минимум 20 циклов каждого экрана. Увести приложение в фон, проверить retained Activity, WebView, PlayerView, ExoPlayer, MediaSession, ViewModel и коллекции Flow. Сохранять результаты между циклами и отдельно от замера FPS.

## Baseline Profile и отчёт компилятора

```sh
./gradlew -PanimeVaultComposeMetrics=true -Pkotlin.incremental=false :app:compileDebugKotlin
./gradlew -PanimeVaultBenchmarks=true :app:assembleBenchmark :macrobenchmark:assembleBenchmark
./gradlew -PanimeVaultBenchmarks=true -PanimeVaultProfileGeneration=true \
  :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sergey.animevault.benchmark.AnimeVaultBaselineProfile
./gradlew -PanimeVaultBenchmarks=true :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sergey.animevault.benchmark.AnimeVaultBenchmark
```

Генерация требует профилируемый APK, пройденный onboarding и рабочий `ProfileInstaller`. Подходит Android Managed Device с API 33+ или GitHub Actions emulator runner с KVM. На Google APIs API 35 прошли сбор правил запуска и переходов между разделами. В `app/src/main/baselineProfiles/baseline-prof.txt` включены 4 353 правила навигации, в `app/src/main/baselineProfiles/startup-prof.txt` — 3 351 правило запуска. Для обоих файлов оставлены сгенерированные правила пакета приложения. Полные снимки AndroidX с правилами библиотек доступны в артефакте. [Профили API 35](https://github.com/Aqua666-prog/AnimeVault/actions/runs/45718254080/artifacts/15071921815)

Для обновления запустите `Ascension Baseline Profile` и распакуйте его артефакт. Onboarding завершается в тестовой установке через штатный DataStore. Флаг сбора правил отключает R8 для benchmark-варианта, чтобы сохранить исходные имена классов. Перенесите строки с дескриптором `Lcom/sergey/animevault/` после необязательных флагов `H`, `S`, `P` в начале строки. Файл `AnimeVaultBaselineProfile_mainNavigation-baseline-prof.txt` соответствует `baseline-prof.txt`, а `AnimeVaultBaselineProfile_startup-startup-prof.txt` — `startup-prof.txt`. Сохраняйте порядок исходных правил и удаляйте точные дубликаты. [Запуски профиля](https://github.com/Aqua666-prog/AnimeVault/actions/workflows/ascension-profile.yml)

После замены правил соберите `:app:assembleRelease`, проверьте `assets/dexopt/baseline.prof` и `baseline.profm` и убедитесь, что AGP принял `startup-prof.txt`. Для замера влияния используйте обычный benchmark с R8 и shrinkResources. Сравните `CompilationMode.Partial` с профилем и `CompilationMode.None` без него на одном физическом устройстве.

Отчёты компилятора пишутся в `app/build/reports/compose/`. Полная компиляция debug зарегистрировала 1 878 composable-функций, 1 846 restartable и 1 300 skippable при включённом Strong Skipping. Эта статистика помогает выбирать участки для профилирования. Частота реальных рекомпозиций и выигрыш во времени требуют замеров.
