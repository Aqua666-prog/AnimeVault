# Подключение PlayerActivity

`PlayerActivity` — отдельный полноэкранный экран на AndroidX Media3. Он умеет
получать готовый HLS либо ссылку iframe Kodik. Ссылки, начинающиеся с `//`,
автоматически получают схему `https:`.

## Готовая m3u8-ссылка

```kotlin
startActivity(
    PlayerActivity.directIntent(
        context = this,
        title = "Название аниме · Серия 1",
        m3u8Url = "https://cdn.example.org/anime/episode-01/master.m3u8",
        voice = "Ancord",
        quality = 720,
        referer = "https://example.org/",
        userAgent = "AnimeVault/${BuildConfig.VERSION_NAME} (Android; Media3)",
    ),
)
```

Параметры `referer`, `userAgent`, `voice` и `quality` необязательны. Если
сервер не проверяет заголовки, достаточно `title` и `m3u8Url`.

## Ссылка Kodik

```kotlin
startActivity(
    PlayerActivity.directIntent(
        context = this,
        title = "Название аниме · Серия 1",
        kodikLink = "//kodik.info/seria/...",
        voice = "AniDUB",
    ),
)
```

В этом режиме `KodikStreamResolver` загружает iframe и актуальный player-JS,
получает прямые HLS-потоки, а затем открывает их в Media3. Доступные качества
появляются в меню с иконкой `HQ`. Если Kodik изменит протокол, прямой режим
покажет понятную ошибку и Toast; запуск Kodik через штатный экран AnimeVault
дополнительно откатится к WebView.

## Штатный экран серии

Экран онлайн-релиза уже вызывает Activity через идентификаторы источника:

```kotlin
startActivity(
    PlayerActivity.onlineIntent(
        context = context,
        providerId = providerId,
        releaseId = releaseId,
        episodeId = episodeId,
    ),
)
```

Activity сама получает список потоков из `OnlineRepository`, восстанавливает
прогресс, запускает выбранную озвучку и при завершении переходит к следующей
серии. Плеер освобождается через `player.release()` в `DisposableEffect`.

## Intent extras

Для интеграций без helper-метода доступны константы:

- `PlayerActivity.EXTRA_TITLE`;
- `PlayerActivity.EXTRA_M3U8_URL`;
- `PlayerActivity.EXTRA_KODIK_LINK`;
- `PlayerActivity.EXTRA_VOICE`;
- `PlayerActivity.EXTRA_QUALITY`;
- `PlayerActivity.EXTRA_REFERER`;
- `PlayerActivity.EXTRA_USER_AGENT`.

`PlayerActivity` не экспортируется из приложения. Вызывать её можно только из
кода AnimeVault, что не позволяет сторонним приложениям незаметно подсовывать
свои URL.
