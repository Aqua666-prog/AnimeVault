# Community AutoSync v3: создание обсуждения по требованию

## Архитектура и границы

Android использует прежний `community_resolve`. При null для поддерживаемой общей ветки вызывает `functions/v1/community-ensure`; Edge проверяет primary ID, явную цепочку Shikimori→MAL, независимый официальный MAL API и взаимное соответствие AniList ID/MAL ID. Затем закрытый `community_auto_complete` атомарно проверяет конфликты и вызывает существующий `community_sync_batch`. Android повторно читает UUID через прежний resolver и загружает комментарии. UUID из ответа Edge напрямую не используется.

При открытии карточки LaunchedEffect асинхронно подготавливает только identity; комментарии/редактор загружаются при открытии вкладки обсуждения. Предзагрузка и вкладка используют один resolver/Mutex, без дублирования серверного создания. Загрузка использует существующий loading UI; просмотр аниме/плеер не ждёт Community. Mutex, повторный resolve после ожидания, cooldown 5 минут на identity и LRU 128 записей предотвращают циклическое создание. Если другой запрос держит lease, Android делает максимум два дополнительных read-only resolve с задержками 750/1500мс. При долгой недоступности следующий resume/ручное обновление снова читает resolver; бесконечного фонового polling нет. Ошибка/отсутствующая Edge Function совместимы со старым сервером: недоступное обсуждение, работа остальных функций сохранена. Эпизоды не создаются автоматически без отдельного доказательства их идентичности.

Batch v2/Canary остаются административными инструментами. Общие комментарии/лайки/ответы/Auth/Firebase/AniList OAuth и существующие RLS не изменены. Android API24, версия 4.1.1(81); это PR исходников, не выпуск APK.

## Реальное покрытие источников

| Источник | Доказательство для новой связи | Что не подтверждается автоматически |
|---|---|---|
| Kodik | Реальный `/search` material, exact Android `shiki:<id>` group → явная ссылка Shikimori на MAL → официальный MAL → AniList и reciprocal MAL lookup | `kp:*`, material-only группы без проверенного Shikimori ID; номера сезонов/эпизодов не используются |
| AniMetka | Primary detail с точным числовым ID; явные MAL/AniList/Shikimori поля, проверенные на конфликты → независимые MAL/AniList | Похожие названия и пустые внешние ID |
| Yummy | Detail с точным anime_id и remote_ids.myAnimeList/shikimori → MAL/AniList | Нет application key, нет ID или API недоступен |
| AniLiberty | Доверенный bridge из server-only sync_evidence; fresh AniMetka mapping + точные UUID эпизодов primary release → явная связь Shikimori/MAL/AniList | Новый release без существующего независимого моста. Reverse endpoint не выдумывается; нужен дальнейший импорт проверенного моста |
| AnimeVost/HTML/Unified | Существующие подтверждённые связи читаются прежним RPC | Новые aliases без независимых primary ID не создаются |

Ни названия, ни год, ни ordinal эпизода не доказывают идентичность. Разные сезоны сохраняют разные ID. Клиентские MAL/AniList — только проверяемые hints; они не могут назначить canonical/verified alias. Для каждого нового anime оба ID обязательны. `MAL_CLIENT_ID` нужен для официального `api.myanimelist.net/v2/anime/{id}`, не для входа пользователя. Без него новые связи не создаются.

## Новая миграция и безопасность

Применяется **только новая** `supabase/migrations/202611020001_community_zero_touch.sql`, после существующих 001/002. Три закрытые RLS-таблицы: `community_auto_settings`, `community_auto_jobs`, `community_auto_budget`. У anon/authenticated/service_role нет прямых SELECT/INSERT/UPDATE/DELETE. Четыре SECURITY DEFINER RPC принадлежат postgres, `search_path=''`, EXECUTE только service_role; SQL не обращается к Auth-пользователям и не изменяет их роли.

`community_auto_claim` — атомарная аренда identity на 90 секунд, единый глобальный budget и client-HMAC budget. По умолчанию 10 проверок/минуту, 200/сутки и 3/client/минуту. Это лимит всех claim, включая неуспешные; он ограничивает расход primary APIs. Дубликаты identity не тратят ещё один claim. Client HMAC вычисляется сервером, исходный IP не сохраняется и не логируется; доверенность forwarded header зависит от gateway, поэтому **глобальный** budget — обязательная защита независимо от IP. Один процесс проверки имеет 20 секунд, не более 16 HTTP-запросов, каждый ≤6 секунд, ответ ≤1MiB; HTTP redirects запрещены, outbound hosts фиксированы.

`community_auto_complete` проверяет актуальный lease, enable flag, соответствие пары identity и конфликт ID/alias под тем же advisory lock, что Batch. Конфликт откатывает обе записи. Старые lease/попытки не могут завершить другую задачу. Новая связь никогда не перепривязывает существующий alias. Хранилище jobs содержит только публичные идентификаторы/коды ошибок, без email/токенов; budgets хранятся не более 2 дней, jobs удаляются после 7 дней при следующем claim. Existing resolver fast path не вызывает внешние API.

`verify_jwt=false` для двух Edge handlers нужен потому, что `sb_publishable_*` не является JWT. Public handler **сам** требует `apikey == COMMUNITY_PUBLIC_KEY` (публичный ключ, не авторизация на запись), worker — отдельный серверный Bearer secret. Все права на запись остаются за доверенным Edge+RPC и независимым доказательством, а не за публичным ключом. Не переносите service_role/MAL credentials/worker secret в APK.

## Безопасное развёртывание — только после approval владельца

Для двух Edge Functions доступен отдельный ручной [Community Edge Deploy](COMMUNITY_EDGE_DEPLOY.md): CHECK без секретов, DEPLOY с PAT и защищённым Environment. Он не запускает миграции и не включает auto-write.

В этом PR ничего не развёртывается и не включается. Обычные новые тайтлы после настройки не требуют Batch/ZIP/SQL/Actions; ниже только однократные административные действия.

1. Сделайте backup проекта и проверьте PR CI (SQL, Node, Edge typecheck, Android). Сначала используйте изолированный Supabase staging с теми же Auth/RLS. Edge берёт backend URL только из доверенного штатного SUPABASE_URL своего deployment, не из клиента; допустим только project.supabase.co. Проверьте, что staging secrets/runtime относятся к staging. Android staging-конфигурация должна также указывать на staging, production APK не перенастраивать. Выполните tests с fixtures/loopback.
2. Через существующий workflow **Supabase migrations** выполните `check`, убедитесь что pending только новая 003. Baseline 001 нельзя повторять. При ручной/частичной 003 runner останавливается на drift; автоматически удалять/восстанавливать её нельзя. После отдельного подтверждения владельца выполните `apply` с прежней точной фразой. Миграция атомарна; контрольные fingerprint baseline/sync сохранены. Если 002 ещё pending, сначала закончите её проверку.
3. Установите Supabase Edge Secrets через Dashboard/CLI, не чат/репозиторий: `COMMUNITY_AUTO_WRITE_ENABLED=false`, `COMMUNITY_PUBLIC_KEY` = текущий Android publishable key, `MAL_CLIENT_ID` официального MAL developer application, существующие `KODIK_API_TOKEN`, при необходимости `YUMMY_APPLICATION_KEY`. `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY` — штатные серверные env Edge (не Android). Не используйте Supabase secret API key вместо JWT service_role для PostgREST Authorization. Полные команды с ключами не сохраняйте в shell history/артефакты.
4. Разверните **две конкретные функции**, например `supabase functions deploy community-ensure --project-ref kgvkxocbkklisqrqmcjx` и `supabase functions deploy community-catalog-worker --project-ref kgvkxocbkklisqrqmcjx`, используя добавленный `supabase/config.toml`. Не выполняйте db reset или deploy всего проекта. Сначала проверьте existing resolve/disabled path: новые таблицы имеют оба flags=false.
5. После успешного staging и отдельного разрешения владельца включите только on-demand: Edge secret `COMMUNITY_AUTO_WRITE_ENABLED=true`, затем как владелец БД `UPDATE public.community_auto_settings SET on_demand_enabled=true WHERE id;`. Это одноразовая настройка, не действие при каждом аниме. Фон `background_enabled` пока false. Начните с малых budgets, убедитесь в сохранности комментариев и exact provider resolve. Android новый APK собрать только Actions.
6. Реальные проверки: первый новый тайтл → UUID/пустой список; повтор → тот же UUID без новых claim; два устройства одновременно; другое озвучивание Kodik → тот же shiki ID/UUID; тот же тайтл в AniMetka/Yummy → тот же AniList/MAL и UUID; другой сезон → другой UUID; гость читает без Community Auth. При ошибке API новые данные не создаются; существующие обсуждения доступны прежним resolver. Проверьте fake hints, конфликт MAL/AniList и карточку без alias (чужая ветка не открывается).

## Фоновая обработка без GitHub Actions

Worker — **ограниченная очередь восстановления on-demand запросов**, а не новый неограниченный импорт всего интернета. Это durable checkpoint в jobs: identity, lease, attempts, next_attempt_at, safe error. Новые задания приоритетнее повторов; temporary failure получает queued и задержку 2 минуты, максимум 3 фоновые попытки. Постоянная ошибка/конфликт изолируется на 6 часов. Выборка ≤5 за вызов; actual global/client budgets могут уменьшить число обработанных. Рабочий процесс не перебирает названия и не добавляет непроверенные новые задания. Долгосрочное массовое административное заполнение остаётся за Batch v2.

Только после отдельного разрешения и проверки on-demand:

- Создайте случайный `COMMUNITY_WORKER_SECRET` непосредственно в Supabase Secrets и Vault. Не используйте publishable key как worker credential.
- В Supabase Dashboard Cron создайте HTTP POST к `/functions/v1/community-catalog-worker` каждые 2 минуты. Authorization должен браться из Vault, не быть литералом в SQL/логах. Поддерживаемый Supabase способ: Cron → pg_net HTTP → `Authorization: Bearer <decrypted Vault worker secret>`. Не включайте логирование заголовков. Настройте расписание самостоятельно после approval; этот PR не создаёт cron/pg_net/extensions автоматически.
- Как owner включите `background_enabled=true`. Edge global flag также должен быть true. `COMMUNITY_AUTOSYNC_DAILY_WRITE=false` оставить без изменений: GitHub массовая запись не включается.
- Мониторинг: structured Edge logs `community_ensure`/`community_worker` (status/source/safe reason, без URL query, ключей, body ответа); owner-only jobs `state,error_code,attempts,next_attempt_at` и budgets. Настройте уведомление при росте http_429/http_5xx, failed и отсутствии successful worker; публичного endpoint просмотра jobs нет. Dashboard платформа может иметь собственные служебные логи доступа, ограничьте их аудитории.

## Отключение и откат

1. Немедленно как owner установите **оба** DB flags=false: это блокирует completion даже уже выполняющейся проверки. Затем `COMMUNITY_AUTO_WRITE_ENABLED=false` и отключите Cron. Не нужен откат миграций и удаление обсуждений.
2. При проблеме Edge можно удалить/переразвернуть только новые handlers. Старый APK и новый fallback продолжают читать существующий resolver. Все ранее подтверждённые canonical/aliases/комментарии сохраняются.
3. Миграцию 003 не откатывайте DROP/TRUNCATE в рабочем проекте. После устранения причины примените отдельную reviewed additive repair. При ошибочной связи сначала отключите auto-write, расследуйте evidence/receipt с администратором и сделайте адресное исправление; не удаляйте чужие ответы/историю. Backup восстанавливайте в отдельном проекте для проверки.

## Проверки и ограничения

Локально не выполнялись Gradle/APK/emulator/deployment/production SQL. Node fixtures проверяют сам движок, PostgreSQL17 — ACL/RLS/RPC/races/rollback/rate limits, migration runner — read-only check и реальный CLI только на loopback. Deno typecheck, Kotlin/JUnit, lint/APK и эмуляторы — только PR CI. Реальная MAL/Kodik credential/SMTP/device работа не объявляется проверенной до owner-run staging. Гостевой public API всё равно может подвергаться нагрузке: limits защищают создание и внешние API, а общие DDoS/egress ограничения настраиваются в Supabase. Неизвестные эпизоды и непроверяемые провайдеры сознательно не создают обсуждений автоматически.
