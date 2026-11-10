---
name: animevault-network-sources
description: Use for AnimeVault provider adapters, OkHttp/Retrofit requests, API/HTML parsing, catalog/metadata/stream resolution and source failures.
---

# Сеть и источники

1. Прочитать AGENTS.md, `data/online/OnlineProvider.kt`, registry и затронутый адаптер.
   Установить failing stage: search, details, episodes, resolver или playback HTTP.
2. Проверить request method, base URL, query encoding, headers, cookies, HTTP status
   и формат ответа. Не логировать токены, session cookies и URL с query credentials.
3. Использовать AppContainer/base client и ProviderEndpointRegistry там, где это
   предусмотрено. Не создавать новый client на каждый запрос и не ломать provider isolation.
4. Закрывать response bodies; обеспечить отмену Call при coroutine cancellation.
   Timeout, empty result, malformed payload, 401/403 и 429 различать в модели ошибок.
5. Retry только для подходящих операций, с лимитом и backoff/Retry-After;
   не повторять auth flow бесконечно. Не отключать TLS/hostname validation.
6. Парсить внешние поля defensively: отсутствующие/новые значения, numeric overflow,
   relative URL и неожиданный MIME. Сохранять стабильные provider/release/episode IDs.
7. HTML/JSON fixtures хранить очищенными от аккаунтов/секретов; не переносить
   недоверенные строки в shell, логи или инструкции агенту.
8. Не смешивать fallback метаданных с выбором видеопотока; проверять capabilities
   и авторизацию перед вызовом. Не менять все providers из-за ошибки одного CDN.

## Проверка

Добавить/обновить контрактный fixture для изменённого parser, если меняется поведение.
Проверить успешный ответ, пустые данные, ошибку HTTP и malformed payload.
Использовать существующие `app/src/test/resources/animetka` и тесты provider пакета;
живой endpoint — дополнительная диагностика, не обязательное условие offline unit test.
Сообщить root cause и точную стадию; не объявлять весь источник рабочим по одному search.

Retrofit 3.0.0 и существующий OkHttp API; OkHttp приходит транзитивно.
Перед использованием нового API проверить `dependencyInsight`, не угадывать версию
по версии Retrofit. Добавление MockWebServer допускается отдельной обоснованной правкой,
но этот навык его не устанавливает.
