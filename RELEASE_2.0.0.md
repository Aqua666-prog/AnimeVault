# AnimeVault 2.0.0 — Multi-source Download Engine

Крупный технический релиз, отделяющий работоспособность каталога, воспроизведения и офлайн-загрузки и превращающий загрузчик в multi-source orchestrator.

## Источники и диагностика

- runtime health теперь ведётся отдельно для `CATALOG`, `PLAYBACK` и `DOWNLOAD`;
- кнопка проверки источников проходит цепочку каталог → карточка → серия → поток и для прямого HLS/MP4 делает короткий byte-range preflight;
- provider ranking воспроизведения и загрузок использует разные health-каналы;
- удалённый provider config переведён на schema v2 с `configVersion`, сроком действия, приоритетами и APK-pinned `trustedHostSuffixes`;
- зеркала могут переключаться внутри доверенной доменной семьи (например, sibling-hosts AnimeBest), но remote config не может расширить доверие на посторонний домен;
- встроенные приоритеты остаются полезными даже если remote config недоступен или отвергнут.

## Актуализация провайдеров

- YummyAnime теперь требует собственный `X-Application` token и использует текущий API `api.yani.tv`; ключ не вшивается в приложение;
- AnimeLib переведён на `Site-Id: 5`, актуальный API family `cdnlibs.org/lib.social` и referer семейства v5; Bearer остаётся пользовательским секретом;
- AnimeON сохраняет настоящую серию `0` для special/OVA;
- AnimeVost умеет получить старый release по ID даже если его нет среди последних 100;
- Dream Cast и Jut.su снова подключены как experimental adapters;
- все network endpoints управляются логическим provider id, а не считаются идентичностью источника.

## Download Engine 2.0

- состояния очереди: `QUEUED → RESOLVING → DOWNLOADING → VERIFYING → COMPLETED`, а также `RETRY_WAIT`, `PAUSED`, `FAILED`, `MISSING`, `REMOVING`;
- перед загрузкой выполняется transport preflight;
- временный отказ одного CDN не выключает провайдер целиком: добавлен отдельный per-host circuit breaker;
- для AniLiberty автоматически пробуются `cache-rfn.libria.fun` и `cache.libria.fun`;
- 401/403/404/410 классифицируются как повод заново разрешить/обновить stream URL вместо бессмысленного повтора старой ссылки;
- при отказе прямого источника worker ищет ту же серию через Unified provider и может перейти на другой провайдер;
- пользователь может запретить автоматическое снижение качества; более высокое качество fallback-логика сама не выбирает;
- HLS качается с ограниченным параллелизмом сегментов, retry/backoff и persistent resume journal;
- progressive download защищён fingerprint-файлом от склейки partial-файла со сменившимся CDN/source URL;
- скорость и ETA считаются сглаженно и сохраняются в Room/показываются в Download Center и уведомлении;
- результат проходит `MediaExtractor` verification до статуса `COMPLETED`.

## Хранилище

- Room schema: **8**;
- миграция `7 → 8` добавляет фактический stream provider/host, speed/ETA, число попыток и классификацию последней ошибки;
- завершённая серия продолжает представлять одну логическую серию: смена качества/озвучки не создаёт дубликат записи.

## Безопасность и эксплуатация

- application/user tokens по-прежнему хранятся через `SecureSessionStore`/Android Keystore;
- `provider-config.json` не содержит секретов;
- добавлен `tools/verify-provider-config.py` и `tools/verify-2.0.0.sh` для CI/source sanity.
