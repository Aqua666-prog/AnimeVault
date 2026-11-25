# Final Polish · Измерения

Единица сравнения — APK с тем же JDK 17, SDK 37, Firebase-конфигурацией и одинаковым Gradle-вариантом. APK 4.2.0 собирается из `aad02e0`, APK 4.3.0 — из финального HEAD PR #62. Debug-значения предыдущего этапа сохранены отдельно. [Исходный протокол](ASCENSION_BENCHMARKS.md)

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
./gradlew -PanimeVaultComposeMetrics=true :app:compileDebugKotlin
./gradlew -PanimeVaultBenchmarks=true :app:assembleBenchmark :macrobenchmark:assembleBenchmark
./gradlew -PanimeVaultBenchmarks=true :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sergey.animevault.benchmark.AnimeVaultBaselineProfile
./gradlew -PanimeVaultBenchmarks=true :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sergey.animevault.benchmark.AnimeVaultBenchmark
```

Для генерации можно выделить AOSP/Google APIs runtime API 33+ на Android Managed Device или в существующем GitHub Actions emulator runner с KVM. Нужны установленный профилируемый APK, пройденный onboarding и рабочий `ProfileInstaller`. Сохранить результаты `baseline-prof.txt` и `startup-prof.txt` из каталога additional test output. После проверки содержимого разместить правила в `app/src/main/baseline-prof.txt` и `app/src/main/startup-prof.txt`. Пересобрать сравниваемый APK и проверить `assets/dexopt/baseline.prof` и `baseline.profm`, затем измерить `CompilationMode.Partial` с профилем и `CompilationMode.None` без него. Результаты эмулятора показывают техническую работоспособность, устройство нужно для сравнительных чисел. Фиктивный app-specific профиль не добавлен.

Workflow `Ascension Baseline Profile` запускает сбор правил на API 35 и сохраняет текст профилей и диагностику отдельным артефактом. Onboarding завершается в тестовой установке через тот же DataStore, что и в приложении. Замеры времени этот workflow не выполняет. [Запуски профиля](https://github.com/Aqua666-prog/AnimeVault/actions/workflows/ascension-profile.yml)

Отчёты компилятора пишутся в `app/build/reports/compose/`. Статическая статистика skippability помогает локализовать кандидатов на профилирование. Она не измеряет реальные рекомпозиции и не доказывает выигрыш.
