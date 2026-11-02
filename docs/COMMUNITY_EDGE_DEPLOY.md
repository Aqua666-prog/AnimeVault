# Community Edge Deploy — запуск с Android-смартфона

Workflow: [Community Edge Deploy](https://github.com/Aqua666-prog/AnimeVault/actions/workflows/community-edge-deploy.yml). После merge этот адрес появится в Actions. Развёртываются **только** `community-ensure` и `community-catalog-worker` проекта `kgvkxocbkklisqrqmcjx`. Версия официальной Supabase CLI зафиксирована: `2.54.11`, как в workflow миграций.

## Что уже настроено и что ещё нужно проверить

Владелец сообщил, что `MAL_CLIENT_ID`, `COMMUNITY_PUBLIC_KEY` и `COMMUNITY_AUTO_WRITE_ENABLED=false` уже настроены **в Supabase**. Workflow не принимает это на веру:

- CHECK без production-секретов проверяет только исходники/TOML/defaults, typecheck, существующие тесты. При выбранном action=deploy preflight дополнительно читает GitHub metadata с автоматическим GITHUB_TOKEN. Удалённые Secrets, DB flags и факт deployment в этом режиме **не проверены** — отчёт явно это указывает.
- DEPLOY через read-only `supabase secrets list --output json` проверяет наличие трёх имён, непустые digest и точный SHA256 digest значения `false` у `COMMUNITY_AUTO_WRITE_ENABLED`. CLI `2.54.11` называет эти данные Secret Digests. Полный вывод остаётся только в памяти; имена посторонних Secrets, значения и digest не печатаются и не сохраняются в artifacts. Неизвестный формат metadata, отсутствующий/пустой secret или любое неподтверждённое значение gate блокируют deployment; обхода нет.
- Наличие digest не доказывает валидность MAL credentials или совпадение Community public key с Android. Эти runtime-проверки остаются отдельной задачей. Workflow не вызывает функции с запросами, способными создать jobs/данные, не выполняет SQL и не читает/меняет `community_auto_settings`.

Supabase project Secrets и GitHub `SUPABASE_ACCESS_TOKEN` — разные настройки. Не переносите service_role, DB password или Supabase secret API key в поле Access Token. Никакие существующие Supabase Secrets этот workflow не меняет.

## Однократная настройка GitHub

1. В [Supabase Account → Access Tokens](https://supabase.com/dashboard/account/tokens) создайте Personal Access Token учётной записи с правами развёртывать функции именно этого проекта. PAT имеет префикс `sbp_`. Скопируйте его непосредственно в GitHub, не в Issue, чат, workflow input или исходники. Впоследствии отзовите/замените его при необходимости.
2. GitHub → репозиторий → **Settings → Environments → New environment** → точное имя **community-edge-production**.
3. В Environment включите **Required reviewers** и добавьте владельца/доверенного reviewer. Для единственного владельца разрешите self-review, если он сам запускает и подтверждает deployment; иначе понадобится второй reviewer. Ограничьте deployment branches до **main**. Не используйте admin bypass.
4. В этом Environment → **Environment secrets → Add secret** → имя **SUPABASE_ACCESS_TOKEN**, значение — созданный PAT. Можно использовать repository Secret с тем же именем, но Environment Secret лучше ограничивает выдачу до approval. CHECK его не получает.
5. Environment должен иметь реальное reviewer-правило: workflow читает GitHub metadata с автоматическим GITHUB_TOKEN (внутреннее имя env: GH_METADATA_TOKEN). Нужны минимальные permissions contents:read для main ref и actions:read для Get an environment; deployments:read/write и административные права не нужны. Если правило отсутствует, metadata недоступна или main изменился после запуска, выполнение останавливается. Дополнительный GitHub PAT/Secret не требуется. Старый транспорт посылал анонимные запросы: публичные Environment доступны без авторизации, но используют общий IP-бюджет GitHub API hosted runner. Теперь обе фазы получают github.token, отправляют Bearer + фиксированную API version и не переходят на анонимное чтение при отказе. Формат protection_rules/required_reviewers проверяется строго, включая вложенный reviewer.id.

## CHECK с телефона

1. После merge откройте **Actions → Community Edge Deploy → Run workflow**.
2. Ветка **main**, `action=check`, `confirmation` оставьте пустым. Запустите.
3. Дождитесь job `check`. Он проверяет entrypoints, имена и `project_id`, ровно два `verify_jwt=false`, выключенные defaults миграции, отсутствие встроенного включения gate, версию CLI, Deno typecheck, Node fixtures и Python-тесты deployment.
4. Откройте **Summary → Community Edge Deploy**. `status=checked` — успешный локальный preflight; дождитесь также `CHECK job result: success` и зелёного job после typecheck/тестов. Это **не** заявление, что Supabase Secrets/функции уже проверены или развёрнуты. Production-запросов CLI в CHECK нет. CHECK на иной ветке получает `main_branch_required`.

Параметр confirmation технически необязателен для формы CHECK; при `action=deploy` скрипт делает его обязательным и сравнивает побайтно.

## DEPLOY с телефона

1. После успешного CHECK запустите новый workflow, снова ветка **main**.
2. `action=deploy`, confirmation **точно**:

   `DEPLOY COMMUNITY EDGE kgvkxocbkklisqrqmcjx`

3. Job `check` повторит проверки без production-секретов и подтвердит наличие reviewer-защиты Environment. Неправильная фраза сразу блокирует дальнейшие действия.
4. Нажмите **Review deployments → community-edge-production → Approve and deploy**, предварительно проверив source commit в текущем запуске. Checkout закреплён за SHA запуска; если main успел измениться до защищённого шага, запустите workflow заново.
5. Только защищённый шаг получает PAT через env. `supabase login` с записью токена на диск не выполняется. CLI использует временный HOME и отдельный bundle из config, двух entrypoints и общего engine. Нет `db push/reset`, SQL, `secrets set`, `--prune`, Cron и импорта каталога.
6. Перед записью CLI читает Secrets и существующие function metadata. Затем по очереди выполняет **только** named deploy `community-ensure`, затем `community-catalog-worker`, с фиксированным project ref и `--use-api`. Docker/Android-сборка для deployment не нужны.
7. После каждого deploy читается список функций: нужный slug должен быть `ACTIVE`, иметь положительную version и `verify_jwt=false`. Повтор с неизменённым bundle допустим — CLI может не увеличивать version. Неподтверждённый статус останавливает процесс.
8. Gate `false` проверяется перед обоими deploy и после них. Итог `status=deployed` и `deployment=verified_active` для обоих имён подтверждают CLI + metadata. Это **не runtime smoke**, не включение on-demand/background и не доказательство работоспособности внешних API.

`verify_jwt=false` здесь ожидаем: publishable key не является JWT. Existing public handler сам проверяет Community public key, worker — отдельный server secret. Workflow сохраняет эту архитектуру, не добавляет публичных обходных маршрутов и не ослабляет MAL/AniList verification.

## Если произошла ошибка

- `exact_deploy_confirmation_required` / `main_branch_required`: выберите main и повторите точную фразу в новом запуске.
- `supabase_access_token_missing`: проверьте имя Secret и правильный Environment. `...personal_access_token_required...`: нужен PAT `sbp_`, не service_role/DB password/API key.
- `environment_required_reviewers_missing`: reviewer-правило отсутствует или пустое; не убирайте защиту. `github_environment_reviewers_invalid`: ответ API имеет неверную форму.
- `github_metadata_token_missing`: обе фазы должны получать автоматический github.token как GH_METADATA_TOKEN.
- `github_metadata_http_401_*` / `github_metadata_http_403_permission_denied_actions_read_required_*`: проверьте права GITHUB_TOKEN actions:read и contents:read, а также ограничения политики организации. Не выдавайте write/admin вместо read и не подставляйте Supabase PAT.
- `github_metadata_http_403_rate_limited_*` / `github_metadata_http_429_*`: исчерпан API budget; повторите позже, не отключайте проверку.
- `github_metadata_http_404_*`: Environment/ref отсутствует либо недоступен токену; публичность репозитория не считается доказательством наличия правил.
- `github_protection_metadata_unavailable_no_deploy`: теперь означает транспортный сбой, с безопасным github_diagnostic (stage/http_status/error_type). HTTP5xx, неверный JSON/объект и redirects имеют отдельные коды; тела ответа и токены не выводятся.
- `remote_auto_write_not_verified_exactly_false`: остановитесь. Отдельно проверьте/отключите gate в Supabase; workflow не меняет его автоматически. Не подставляйте hash вместо реального значения `false` в Secret.
- `secret_digest_format_unknown_no_deploy`: формат ответа CLI/API не соответствует проверенному контракту. Нужна отдельная проверка/исправление runner, не отключение проверки.
- `cli_failed_*`: CLI output скрыт, поскольку может содержать чувствительные сведения. Проверьте PAT/project permissions и доступность Supabase через Dashboard; не публикуйте токен/полный CLI debug.
- Timeout/ошибка второго deploy: два deployment не являются общей транзакцией. Первый мог быть уже обновлён; `attempted_outcome_not_confirmed` означает неопределённый результат, не rollback. Проверьте Dashboard перед новым подтверждённым запуском. Workflow ничего автоматически не удаляет и не откатывает.

## Проверка в Supabase Dashboard

Откройте проект **AnimeVault → Edge Functions**. Убедитесь, что оба имени присутствуют и активны; сравните versions со Summary. Проверьте Verify JWT=false и конфигурацию Secrets; не включайте gate ради проверки deployment. Просмотрите служебные logs без копирования заголовков/секретов. Не запускайте worker и не посылайте новые title identity, пока отдельное разрешение на auto-write не получено.

Глобальный gate должен остаться `COMMUNITY_AUTO_WRITE_ENABLED=false`. DB flags workflow намеренно не инспектирует и не меняет. Отсутствие Cron также не объявляется проверенным: workflow его не создаёт. Если owner меняет flags/Secrets вручную параллельно, настройка может измениться между metadata reads; нет транзакционной блокировки чужих административных действий. При обнаружении несоответствия процесс останавливается, не изменяя чужие настройки.

## Возврат к предыдущей проверенной версии

Workflow не принимает произвольный ref и не развёртывает ветки/теги. Найдите SHA предыдущего успешного deployment в Summary. Подготовьте reviewed PR, возвращающий **только исходники этих двух функций/общего engine** к проверенному состоянию, сохранив актуальные workflow/защитные проверки и config. После CI и merge этого PR в main выполните новый CHECK и отдельный подтверждённый DEPLOY. Это создаёт новую историю deployment, не стирая старую. Если прежний код не совместим с текущей архитектурой/проверками, сначала отдельный review; обходить typecheck/gates нельзя.

## Безопасное отключение Zero-Touch

Держите Supabase Secret `COMMUNITY_AUTO_WRITE_ENABLED=false`. Для аварийного отключения уже включённого сервиса владелец дополнительно выключает оба DB flags и Cron по [плану Zero-Touch](COMMUNITY_ZERO_TOUCH.md#отключение-и-откат). Этот workflow **не выполняет** SQL/такое отключение за владельца. Не удаляйте canonical/aliases/комментарии и не откатывайте RLS: существующий `community_resolve` продолжает читать проверенные обсуждения.

Развёртывание кода и разрешение автоматической записи — отдельные решения. Этот workflow выполняет только первое после явного подтверждения и Environment approval.

## Исправление protection metadata

В failed run `41463137215` исходный HTTP-статус не был сохранён: старый catch скрывал и HTTP-ошибки, и network/JSON failures за одним кодом. Аудит подтвердил, что Environment существует и имеет required_reviewers; отсутствие Environment не является установленной причиной. Обнаруженная причина ненадёжного preflight — анонимный GitHub API transport без переданного workflow token, зависящий от IP-квоты runner. Точный статус старого запроса по сохранённым логам восстановить нельзя.

[Get an environment](https://docs.github.com/en/rest/deployments/environments#get-an-environment) требует Actions(read) для fine-grained/installation tokens и допускает anonymous public reads. [GitHub API rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api) разделяют anonymous IP budget и GITHUB_TOKEN budget. PR CI делает отдельную **read-only GitHub** проверку authenticated required_reviewers и advisory замер legacy anonymous HTTP status; последний никогда не служит fallback/разрешением deployment. Supabase credentials и вызовы deploy в этот CI job не передаются.
