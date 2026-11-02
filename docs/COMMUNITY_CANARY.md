# Community AutoSync — Safe Canary Publish

Цель этого режима — только каноническая запись AniList **195604** / MAL **61967** и один alias **kodik / shiki:61967**. Ни один другой тайтл, pending/rejected, серия, комментарий или профиль не отправляется на запись. Ограничение зашито в Python, а не задаётся произвольным workflow input. Миграции и Android-код не меняются.

## Что считается доказательством

Значения из задания сами по себе недостаточны. Canary требует, чтобы последний успешный discovery из main содержал:

- согласованные canonical и queue записи 195604/61967;
- `status=verified`, прямую цепочку Shikimori → MAL61967 и наблюдение реального Kodik material ID;
- маркер текущего клиентского контракта и точный Android request: provider=kodik, external=shiki:61967, MAL/AniList/episode=null.

`KodikItemDto.releaseReference()` в Android действительно выбирает `shiki:<shikimori_id>` перед `kp:<kinopoisk_id>` и `kodik:<id>`. `OnlineTitleScreen` передаёт release.id в AnimeIdentityResolver → p_external. Поэтому префикс shiki реален, но наличие строки с префиксом не заменяет источник доказательства.

Plan получает catalog-discovery напрямую средствами GitHub Actions из последнего успешного main-запуска этого workflow (до 20 последних успешных запусков). PR-артефакты, canary-plan вместо discovery и просроченные артефакты не используются. Затем выполняются свежие **целевые** запросы AniList, Shikimori и Kodik. Все ID должны подтвердиться; наблюдавшийся material ID должен ещё существовать. Title не используется для сопоставления. При недостаточных/устаревших доказательствах публикация останавливается: сначала выполните обычный discovery с apply выключенным.

На этапе разработки подтверждены main `fbf1cb3a25041f49808f9f0e44f23fdf65074d49`, merged PR #35–38 и успешный discovery `41395977782` (publish skipped). Скачать его ZIP в среду Codex не удалось из-за ограничений транспорта артефактов, поэтому содержимое и актуальность доказательств не объявлены проверенными локально. Их обязательно читает и проверяет plan на GitHub runner, а не оператор вручную и не доверенный текст задания.

## Секреты и переменные — без новых типов доступа

Используются существующие настройки AutoSync:

| Настройка | Назначение |
|---|---|
| Secret `KODIK_API_TOKEN` | Повторная проверка реального Kodik результата в plan и непосредственно перед записью |
| Variable `SUPABASE_PUBLISHABLE_KEY` | Публичный ключ **этого** Supabase-проекта для read-only SELECT/resolver; тот же, что у Android |
| Secret `SUPABASE_SERVICE_ROLE_KEY` | Уже предусмотренный AutoSync legacy **service_role JWT**; только защищённый canary_publish job |
| Variable `COMMUNITY_AUTOSYNC_WRITE_ENABLED` | Для применения требуется точное `true`; dry-run не требует включения |
| Variable `COMMUNITY_AUTOSYNC_DAILY_WRITE` | Canary apply требует отсутствующую/false; при true публикация блокируется, чтобы включение общего WRITE_ENABLED не активировало ежедневный bulk |

Не требуются PostgreSQL URL/пароль, Supabase Management token, SMTP, migration CA или новый personal access token. `YUMMY_APPLICATION_KEY` canary также не использует. Publishable key не заменяет service_role. Секреты вводятся непосредственно в GitHub Secrets, не в confirmation, Issue, JSON, исходники или чат. В логах нет ключей, полного URL с Kodik token или необработанного текста ошибки сервера.

В `Settings → Environments` создайте/проверьте **community-catalog-write**, включите **Required reviewers** (владельца/доверенного reviewer) и ограничьте deployment branch до main. Self-review может быть разрешён, если владелец сам запускает и подтверждает workflow. Перед canary apply workflow проверяет наличие действительного правила required_reviewers; просто имя environment без защиты недостаточно. Если API не может подтвердить защиту, запись блокируется. В Codex наличие/защиту environment подтвердить не удалось (API вернул 404); настройку должен проверить владелец. При необходимости service_role Secret можно хранить непосредственно в этом environment с тем же именем.

Административный ключ должен иметь доступ к существующему RPC `community_sync_batch` и SELECT таблиц `community_anime`, `community_anime_aliases`, включая скрытые от anon aliases. Стандартные grants Supabase обычно это дают; BYPASSRLS сам по себе не заменяет SELECT. При отказе admin preflight canary **не** переходит к слепой записи. Проверьте правильность service_role JWT и существующие права через доверенные инструменты владельца; не добавляйте новые секреты и не отключайте RLS. Автоматических GRANT в canary нет.

## Запуск с Android-телефона

1. Объедините PR Safe Canary. Откройте GitHub в браузере; для Settings при необходимости включите «Версия для ПК».
2. Проверьте настройки выше. Не включайте DAILY_WRITE. Сначала глобальное WRITE_ENABLED можно оставить false.
3. **Actions → Community Catalog AutoSync → Run workflow → Branch main → mode canary → apply выключен**, confirmation пустой. Pages игнорируется: массовый scan/publisher в этом режиме не запускается.
4. В Summary и артефакте **canary-plan** проверьте точный список из двух объектов, title, IDs, доказательства и plan_hash. Это настоящий dry-run, без admin key и без RPC записи. Он использует публичное чтение, поэтому может не видеть существующий unverified alias; окончательная административная проверка выполняется ещё до записи.
5. Если план правильный и вы явно разрешаете запись: установите **COMMUNITY_AUTOSYNC_WRITE_ENABLED=true**. Запустите **новый** workflow на main: **mode canary**, **apply включён**, confirmation строго **`PUBLISH CANARY 195604`**. Не выбирайте discovery: в нём apply означает существующую массовую публикацию. Если canary confirmation введён в обычном режиме, workflow специально остановится до scan/bulk.
6. Новый запуск снова построит dry-run. После его успеха откройте **Review deployments**, изучите Summary и подтвердите защищённый environment. При изменении данных между планом/approval запись остановится и потребуется новый план.
7. Защищённый job повторно проверяет live evidence, публичное состояние и скрытые aliases через admin SELECT. Он печатает окончательный точный dry-run и отправляет **один** ограниченный RPC batch с **двумя** элементами. Общий publisher не вызывается. Если пара уже существует и совпадает, запись пропускается.
8. Смотрите **canary-result** и Summary. Успех — только `status: success`, `resolved.status: resolved`, AniList195604, MAL61967, kodik/shiki:61967 и episode=null. Один только HTTP200/processed=2 не является успехом. После теста верните WRITE_ENABLED=false, если других публикаций пока не разрешаете.

Re-run старого workflow использует его старый commit; для первого canary после merge нужен новый Run workflow. Живой production canary из Codex не запускался. Успешные локальные/CI тесты не заменяют ваш canary-result.

## Проверка обсуждения в AnimeVault

Откройте **конкретный источник Kodik**, найдите «Чёрный клевер 2 / Black Clover 2nd Season» и перейдите в «Обсуждение». Обновите экран/войдите заново на страницу, если прежде была закеширована недоступная связь. Не используйте «Все источники»: synthetic Unified ID не заменяется этим alias. Общая ветка должна разрешиться; пустой список комментариев допустим. Для редактора нужна отдельная Community-сессия и профиль; Google/Firebase сам по себе не авторизует Community. Эпизодные ветки canary не создаёт.

После записи worker автоматически делает read-only `community_resolve` с **точными Android параметрами** provider=kodik/external=shiki:61967. UUID должен совпасть с отдельным canonical resolver запросом по обоим заданным ID; дополнительный SELECT проверяет, что UUID действительно принадлежит 195604/61967.

## Повторный запуск, конфликты и неизвестный исход

Уникальные ID сервера и детерминированный batch UUID предотвращают дубли. Новый повторный workflow строит новый план и делает no-op, если данные уже совпадают, но проверяет resolver ещё раз. Canary не помечает остальную bulk-очередь обработанной и не переписывает discovery-cache.

Чужой alias, включая скрытый unverified, блокирует применение до RPC. Расхождения MAL/AniList, изменившиеся proof/plan, отсутствие источника/ключей и pending/rejected также блокируются. Нет автоматического удаления/перепривязки или destructive rollback.

Важная граница существующего `community_sync_batch`: он выполняется одной транзакцией и никогда не перепривязывает чужой alias, но при **конкурентном** создании конфликта после preflight может сохранить **один собственный canonical upsert**, записать rejected candidate в служебный журнал и вернуть rejected вместо ошибки SQL. Canary требует verified_applied=1 и rejected=0; такой результат **не** объявляется успехом. Полного all-or-nothing для принятия пары при внешних конкурентных сессиях без изменения серверного RPC здесь не обещается. Не запускайте параллельно ручные изменения каталога. Если после запроса потерялась сеть или post-resolve не подтвердился, считайте исход неподтверждённым, изучите состояние и новый dry-run. Ничего автоматически не удаляется.

Помимо максимум одной canonical и одного alias, сервер ведёт свои уже существующие private evidence/batch receipts; это не публикация других тайтлов. Тесты на изолированной PostgreSQL проверяют эту семантику, включая гонку конфликта, потерянный ответ, повторный no-op и отказ post-resolve.

## После успешного canary — безопасное масштабирование

Не включайте bulk автоматически. Сначала сохраните canary-result, проверьте экран Kodik и отсутствие конфликтов. Затем рассмотрите небольшой **явный allowlist** из 5–10 verified записей с теми же live proofs, точным dry-run, protected approval и resolver для каждой. Следующий шаг — ограниченные партии 25/50 с остановкой при конфликте/ошибке, сохранённым checkpoint и отдельным отчётом реально разрешаемых aliases. DAILY_WRITE включать только после нескольких проверенных партий.

Текущий обычный `publish` отправляет **всю подходящую накопленную очередь**: `pages=1` ограничивает discovery, а не количество записей публикации. Поэтому не используйте обычный mode=discovery/apply как «маленькую партию» без предварительного отдельного ограничения/проверки очереди. Этот PR добавляет только строго ограниченный canary, не автоматический переход к bulk.


## Диагностика HTTP404 и повторный dry-run

Failed canary `41400916720` на main `12ec1b1b27185c9fb158b120997a250dd1c78a9d` завершился за ~0,22 секунды после старта Python с общей ошибкой http_404. Старый отчёт не сохранял источник/этап, поэтому сам лог не доказывает, какой запрос упал. В коде первой сетевой операцией был **POST https://graphql.anilist.co**, Media lookup с `variables={"id":null,"mal":61967}`. Работающий discovery использовал `{"mal":61967}`. Это разные GraphQL аргументы: отсутствующий variable не равен явно переданному null. Теперь неиспользуемые ID-фильтры опускаются и в canary, и в общем точечном lookup AutoSync; проверка возвращённых id=195604 и idMal=61967 остаётся обязательной.

Проверка GitHub Actions [41402077004](https://github.com/Aqua666-prog/AnimeVault/actions/runs/41402077004) воспроизвела причину: исходный AniList запрос с `id:null` получил HTTP404, запрос только с `mal:61967` и независимый запрос только с `id:195604` получили HTTP200 с одной и той же парой `195604/61967`. Shikimori external_links вернул HTTP200 и MAL61967. Kodik `/search` с публичным application token вернул HTTP401: свежая связь `kodik/shiki:61967` этой проверкой **не подтверждена**. Повторный canary обязан подтвердить её с существующим Secret `KODIK_API_TOKEN`; 401/отсутствие записи сохраняет блокировку. Не заменяйте этот Secret публичным токеном из диагностического JS.

Перед каждым запросом появляется безопасный `canary_request`: источник, этап, метод, endpoint **без query string**. В canary-report добавлен `diagnostic` с source/stage/method/endpoint/http_status/error_type. Пример HTTP404 от AniList:

```json
{"error":"canary_source_request_failed","status":"blocked","target_anilist":195604,"diagnostic":{"source":"anilist","stage":"refresh_identity","method":"POST","endpoint":"https://graphql.anilist.co","http_status":404,"error_type":"not_found"}}
```

Отдельные этапы предусмотрены для Shikimori external_links, Kodik primary_record, публичных/административных Supabase SELECT и post-resolve. HTTP200 с GraphQL errors, сетевой сбой, некорректный JSON, rate limit и identity mismatch не смешиваются с HTTP404. Ни query token, ни headers, ни body ошибки сервера в отчёт не попадают. Сбой любого обязательного источника сохраняет blocked; переезда на похожее название, другого ID или незащищённый endpoint нет.

Новый PR workflow **Canary public source diagnostics** делает advisory read-only наблюдения: исходный запрос с null, lookup только по MAL, lookup только по AniList и Shikimori links. Для Kodik он может использовать только публичный application token из официального JS, как существующий Android public-token механизм; GitHub Secrets и Supabase ему недоступны. Ключ остаётся в памяти и не выводится. Статусы/только публичные ID сохраняются в canary-public-probe.json. Успешное выполнение диагностического job означает завершение наблюдения, **не** успешную проверку источника и не успешный canary: нужно читать статусы отдельных observations. Эти наблюдения никогда не подменяют обязательную canary-проверку с настроенным KODIK_API_TOKEN.

Повтор с телефона: после merge откройте **Actions → Community Catalog AutoSync → Run workflow → main → mode canary**, apply **выключен**, confirmation пустой. Нужен новый запуск, не Re-run старого commit. Менять Secrets из-за формата AniList запроса не требуется. Если источник остаётся недоступным, отправьте только diagnostic из canary-report/код этапа — не ключи. До успешного dry-run не переходите к apply.
