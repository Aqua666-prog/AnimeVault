# Ограниченная массовая публикация Community

Режим `batch` использует существующие очередь discovery, HTTP-клиент, `community_sync_batch` и `community_resolve`. Canary остаётся отдельным. Новых миграций, изменений Android и новых Secrets нет. Discovery теперь только читает источники; прежний неограниченный job публикации удалён из workflow. Расписание запускает только discovery, не batch. Оставьте `COMMUNITY_AUTOSYNC_DAILY_WRITE=false`.

## Что разрешено

`max_publish_items`: **5, 10, 25, 50, 100**. Это сумма элементов `p_items`, фактически отправленных за весь запуск: anime и alias считаются отдельно. Пара занимает 2 элемента, каноническая запись без alias — 1. Поэтому 5 — не пять аниме с пятью aliases. При остатке 1 пара переносится на следующий запуск. Повторные HTTP-отправки записывающего RPC внутри одного запуска отключены: при неопределённом результате запуск останавливается.

Канонические записи требуют обоих ID и свежего ответа AniList, подтверждающего их вместе. Aliases допускаются только verified, с точным Android-контрактом и свежей цепочкой Kodik material → Shikimori external_links → MAL → AniList. Сохранённый material ID должен находиться в свежем ответе Kodik. Совпадение названий/номеров серий нигде не является доказательством. Серии, pending/rejected и aliases других провайдеров не публикуются этим режимом. Они сохраняются в discovery; расширение покрытия требует отдельного проверенного primary-source refresher.

## Запуск с Android-телефона

1. После merge PR откройте GitHub → AnimeVault → **Actions → Community Catalog AutoSync → Run workflow**, ветка **main**.
2. Если нет свежего discovery: сначала `mode=discovery`, apply выключен. Дождитесь успешного `catalog-discovery`. Batch выбирает артефакт успешного main-запуска, никогда PR.
3. Выберите `mode=batch`, `max_publish_items=5`, **apply выключен**, confirmation пустой. Запустите новый workflow. В Summary/artifact `batch-plan` проверьте `batch-report.json`: точные `groups.items`, исходное состояние, ошибки и количество `records`. Public dry-run не видит скрытые/unverified aliases.
4. Для записи задайте Repository Variable `COMMUNITY_AUTOSYNC_WRITE_ENABLED=true`; `COMMUNITY_AUTOSYNC_DAILY_WRITE=false`. Существующие `SUPABASE_PUBLISHABLE_KEY`, Secrets `KODIK_API_TOKEN` и `SUPABASE_SERVICE_ROLE_KEY` остаются теми же. Не вводите ключи в workflow inputs, Issues или чат.
5. В Environment **community-catalog-write** должны быть Required reviewers и разрешена только main. Workflow проверяет наличие reviewer-правила до записи; недоступная проверка блокирует запуск.
6. Новый запуск: mode=batch, лимит 5, apply включён, `batch_confirmation=PUBLISH BATCH 5`. Для другого лимита число в фразе обязано совпадать. Canary confirmation оставьте пустым.
7. Сначала job plan создаст новый dry-run без service_role. Просмотрите план **этого запуска** перед approval Environment. Затем одобрите deployment с телефона. Publish привязан к hash плана, run ID и attempt; подмена артефакта/повтор старого approval не допускается.
8. Publish повторяет свежие доказательства и public preflight, затем выполняет admin preflight, видящий все aliases. При изменении состояния/конфликте останавливается. Перед каждой RPC в логе показан финальный dry-run. Ключи, query parameters и тела ошибок источников не выводятся.
9. Скачайте `batch-result`. `published` — элементы, принятые RPC в этом запуске (включая безопасный replay, не обязательно новые INSERT); `resolved` — элементы групп, прошедших read-only проверку. Проверяются **все**, а не только выборка: canonical resolver и для каждой пары точный Android resolver должны вернуть один UUID без episode.
10. После успешной партии проверьте обсуждения в приложении. Увеличивайте лимит постепенно: 5 → 10 → 25 → 50 → 100. Это ручные подтверждаемые партии, не автоматическое разрешение ежедневной записи.

## Checkpoint, ошибки и повтор

Checkpoint `batch-checkpoint.json` содержит только digest подтверждённых групп, без токенов. Сохраняется после каждой успешной resolver-проверки, атомарной заменой файла; переносится через отдельный GitHub cache `batch-v1-*` и artifacts. `if: always()` сохраняет прогресс при штатной ошибке. Cache может быть удалён GitHub; при жёсткой остановке runner последний checkpoint может не загрузиться. Серверный журнал RPC остаётся защитой: стабильный UUID от содержимого группы возвращает прежний receipt вместо дубликатов. Истечение cache может вызвать повторные проверки/отправки уже существующих записей, но не дубликаты.

После transport error неизвестно, завершилась ли серверная транзакция: публикация останавливается; не выполняйте ручное удаление. Следующий **новый** запуск повторит preflight и идемпотентную RPC. Сервер сохраняет конфликтующий alias и возвращает rejected, который не считается успехом и не попадает в checkpoint. Каноническая часть такой пары могла быть принята — `failed` не означает rollback всего запуска. Каждый RPC транзакционен; весь запуск состоит из отдельных групп. Ранее успешные группы сохраняются при поздней ошибке.

Отчёт: `discovered` — число канонических записей discovery; `verified/pending/rejected` — состояния aliases discovery; `failed` и `errors` включают проверки кандидатов и ошибки публикации; `published/resolved/submitted` относятся к текущей публикации. Поля не являются взаимоисключающими итогами одной таблицы. Ошибка preflight в plan пропускает кандидата с причиной; число проверяемых кандидатов ограничено `max(20, limit*2)` для ограничения нагрузки. Конфликт/ошибка в publish останавливает остальные группы. Пустой план допустим и ничего не отправляет.

Настройка Environment и реальный publish требуют проверки владельцем после merge. Codex production не изменяет. Миграции 001/002 повторно не применять.
