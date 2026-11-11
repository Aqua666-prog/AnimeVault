---
name: animevault-media-downloads
description: Use for AnimeVault Media3/ExoPlayer playback, HLS/direct downloads, WorkManager queueing, resume, cancellation, stream recovery and offline finalization.
---

# Media3 и надёжные загрузки

## Карта и диагностика

Прочитать AGENTS.md, затем конкретный путь `ui/player` → `data/playback` /
`data/transport` либо `DownloadRepository` → `DownloadWorker` → `NativeDownloadEngine`.
Сначала разделить resolver, HTTP transport, manifest parsing, decoder и UI failures.
Указать provider/тип потока/HTTP status без cookies и подписанных URL.

## Playback

- Найти владельца ExoPlayer в текущем route: не создавать второй экземпляр при recomposition.
  Сохранять симметрию listeners, MediaSession, audio focus, release и lifecycle effects.
- Сверить `Media3PlaybackSessionBridge`, session store, progress и идентичность эпизода.
  При refresh URL не сбрасывать позицию, выбранный перевод, качество или completion.
- Проверять MediaItem и headers, master/media playlist, relative URI, MIME и redirect.
  Таймаут transport не равен unsupported codec; fallback должен учитывать причину.
- Проверять local/online playback, seek, PiP, background/foreground, orientation и
  отключённый auto-rotate. Не менять configChanges только ради устранения симптома.

## WorkManager и NativeDownloadEngine

- Room хранит состояние, WorkManager исполняет; UI progress не является источником истины.
- Не обходить operation token: старый worker не должен завершить/удалить новую операцию.
  Проверять unique work, цепочки сезона, replacement, remove и retry после process death.
- Сохранять foreground dataSync/notification contract с учётом версии Android;
  не удалять concurrent-futures-ktx из зависимостей CoroutineWorker.
- Resume direct-файла сверять с Range/Content-Range и изменением содержимого;
  ответ 200 на Range нельзя дописывать к старым байтам без проверки.
- HLS: master variant, относительные segment/key URI, AES-128 key/IV и sequence,
  EXT-X-MAP, byte ranges, TS/fMP4, resume journal, bounded concurrency и cancellation.
  При неизвестном/DRM-шифровании вернуть понятную ошибку; не изобретать поддержку.
- Retry ограничивать; refresh истёкшего URL отделять от disk full/permission revoked.
  Сохранять partial данные по существующей политике и не помечать partial как COMPLETED.
- Завершать через verifier/importer; целевой файл должен соответствовать записи эпизода.

## Проверка и результат

Запустить подходящие download/HLS/playback JVM tests; для Room/WorkManager —
`LogicalDownloadReplacementTest`, `DownloadReconciliationTest` при наличии устройства.
На устройстве проверить seek, поворот, PiP, отмену/возобновление и offline файл.
Сообщить проверенные форматы и непроверенные сценарии.

Зависимости уже есть: Media3, WorkManager, Room, coroutines, HttpURLConnection.
Не устанавливать Media3 Cast/DownloadManager, Retrofit или новый downloader ради навыка.
