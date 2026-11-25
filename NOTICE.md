# Источники сведений для интеграций

При реализации адаптеров использовались публично доступные описания запросов,
ответов и тестовые примеры из открытых проектов:

- `anicli-api` (MIT): форматы нескольких аниме-сервисов, SameBand и YummyAnime;
- `AnimeParsers` (MIT): структура API/плееров отдельных источников;
- `kodikwrapper`: публично наблюдаемый контракт Kodik;
- `Lampac`: открытые реализации адаптеров Dream Cast, AniMedia, AnimeON,
  AnimeBest и других источников;
- `Helandy/YummyTV`: актуальная структура Yani API, заголовка `X-Application`,
  каталогов, карточек и списка видео.

Сторонний исходный код в приложение не копируется как библиотека: адаптеры
AnimeVault реализованы на Kotlin поверх собственного `OnlineProvider` контракта,
OkHttp и Media3. Контракты внешних сайтов могут меняться без предупреждения.
Названия, постеры и видео принадлежат их правообладателям. Пользователь отвечает
за соблюдение правил выбранного сервиса и применимого законодательства.

## Project Ascension

- [Haze 1.7.2](https://github.com/chrisbanes/haze), Apache 2.0, размывает
  ограниченный слой баннера на поддерживаемом устройстве.
- [LeakCanary](https://github.com/square/leakcanary), Apache 2.0, опциональная
  debug-диагностика.
- [Roborazzi](https://github.com/takahirom/roborazzi) и
  [Robolectric](https://github.com/robolectric/robolectric), Apache 2.0,
  зависимости визуальных тестов.
- [Android Benchmark](https://github.com/android/performance-samples) и AndroidX
  UI Automator, Apache 2.0, зависимости тестового APK.
