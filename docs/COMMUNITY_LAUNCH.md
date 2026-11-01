# Запуск настоящих комментариев — 4.1.1 (81)

Скриншот «Сервис не настроен» из 4.1.0 означает, что установленная сборка не получила включённый Community с корректным публичным ключом. Дополнительно в 4.1.0 список был спрятан за кнопкой во вкладке. В 4.1.1 комментарии, сортировка и редактор находятся непосредственно во вкладке «Обсуждение»; серия имеет отдельный экран по действию «Обсудить серию».

**Сервер ещё не готов:** владелец подтвердил, что `202611010001_community.sql` не применена. Агент не применяет её и не изменяет production. Успешная сборка/синтетические тесты не означают наличие таблиц, пользователей или комментариев на сервере. Демонстрационные сообщения в приложение не подмешиваются.

## GitHub Actions Variables

Settings → Secrets and variables → Actions → Variables:

| Имя | Значение |
|---|---|
| SUPABASE_URL | `https://kgvkxocbkklisqrqmcjx.supabase.co` |
| SUPABASE_PUBLISHABLE_KEY | `sb_publishable_OoBjNzcTQnCtzKjUz_cxpA_g39Or3Zl` |
| COMMUNITY_ENABLED | `true` |

В доверенном `build-apk.yml` уже настроены эти публичные значения как fallback, поэтому отсутствие доступа интеграции к Variables не блокирует подключённый APK. Если Variables заданы, они имеют приоритет; устаревшее `COMMUNITY_ENABLED=false` нужно изменить на true. Ключ публичный, не service_role и не серверный секрет. Он передаётся через окружение Gradle → BuildConfig; в Kotlin-исходники значение не встроено вручную. Заменить/отозвать ключ в будущем можно через Variables.

Локальная сборка по-прежнему может обходиться без Supabase: без ключа функция недоступна, приложение продолжает работать. При наличии корректного ключа и отсутствии отдельного флага Community включается автоматически. Явный false остаётся выключателем. CI проверяет конфигурацию без вывода значения ключа и отклоняет enabled-сборку с неверными параметрами. В Codex Gradle/APK/emulator не запускаются.

## Применение серверной схемы вручную

1. Сохранить backup существующего проекта и проверить восстановление в изолированной БД. Проверить отсутствие конфликтующих community-объектов.
2. Открыть **существующий** проект AnimeVault → SQL Editor. Проверить project reference `kgvkxocbkklisqrqmcjx`.
3. Открыть целиком `supabase/migrations/202611010001_community.sql`, просмотреть RLS/GRANT/RPC, выполнить вручную от `begin` до `commit`. Не использовать db reset, db push и не запускать тестовый Auth shim на настоящем Supabase.
4. При ошибке откатить незавершённую транзакцию, сохранить текст ошибки без credentials и разобраться с конфликтом. Не удалять существующие объекты вслепую. Успешную миграцию повторно не применять.
5. Проверить права запросами из [SUPABASE_SECURITY.md](SUPABASE_SECURITY.md). Новые private-таблицы закрыты; у обычных клиентов нет прямых INSERT/UPDATE/DELETE. RPC проверяют auth.uid(), владельца, статус, бан и ограничения частоты.
6. Назначить первого администратора по подтверждённому Auth UUID согласно [COMMUNITY_MODERATION.md](COMMUNITY_MODERATION.md).

## Почему после миграции нужен хотя бы один проверенный тайтл

Пустая схема не содержит canonical anime/episode mappings. Создавать обсуждение по похожему названию или номеру файла нельзя. Клиент намеренно не может назначать verified-связи: иначе любой пользователь мог бы смешать чужие сезоны и обсуждения. Для первого испытания оператор должен подтвердить один тайтл у конкретного провайдера и одну серию.

В доверенном SQL Editor адаптировать следующий шаблон. NULL/пустые значения специально блокируют выполнение, пока оператор не проверит реальные ID. Provider/release ID должны совпадать с `OnlineReleaseDetails.providerId/id`, external episode ID — с `OnlineEpisode.id` конкретного адаптера; это не отображаемое название или приблизительный номер серии.

```sql
do $$
declare
  selected_anilist bigint := null; -- подтверждённый AniList ID, если есть
  selected_mal bigint := null;     -- подтверждённый MAL ID, если есть
  selected_title text := '';       -- проверенный тайтл/сезон
  selected_provider text := '';    -- providerId в AnimeVault
  selected_release text := '';     -- точный release.id этого провайдера
  canonical uuid;
begin
  if (selected_anilist is null and selected_mal is null)
    or selected_title='' or selected_provider='' or selected_release='' then
    raise exception 'Заполните проверенные canonical/provider IDs';
  end if;
  if (select count(*) from public.community_anime
      where anilist_id=selected_anilist or mal_id=selected_mal)>1 then
    raise exception 'AniList и MAL указывают на разные записи; требуется ручная проверка';
  end if;
  select id into canonical from public.community_anime
    where anilist_id=selected_anilist or mal_id=selected_mal;
  if canonical is null then
    insert into public.community_anime(anilist_id,mal_id,canonical_title)
      values(selected_anilist,selected_mal,selected_title) returning id into canonical;
  elsif exists(select 1 from public.community_anime where id=canonical and
      ((selected_anilist is not null and anilist_id is distinct from selected_anilist)
       or (selected_mal is not null and mal_id is distinct from selected_mal))) then
    raise exception 'Идентификаторы расходятся с существующей записью';
  end if;
  insert into public.community_anime_aliases(anime_id,provider,external_id,verified)
    values(canonical,selected_provider,selected_release,true) on conflict(provider,external_id) do nothing;
  if not exists(select 1 from public.community_anime_aliases where provider=selected_provider
      and external_id=selected_release and anime_id=canonical and verified) then
    raise exception 'Существующая связь не подтверждена или принадлежит другому тайтлу';
  end if;
end $$;
```

Для серии, после проверки root alias:

```sql
do $$
declare
  selected_provider text := '';
  selected_release text := '';
  selected_external_episode text := ''; -- точный OnlineEpisode.id
  selected_key text := '';              -- например regular:12 либо special:12.5
  selected_type text := '';             -- regular/ova/special/movie/fractional
  selected_number integer := null;      -- только достоверный целый номер
  canonical uuid;
  canonical_episode uuid;
begin
  if selected_provider='' or selected_release='' or selected_external_episode=''
    or selected_key='' or selected_type='' then raise exception 'Проверьте и заполните ID серии'; end if;
  select anime_id into canonical from public.community_anime_aliases
    where provider=selected_provider and external_id=selected_release and verified;
  if canonical is null then raise exception 'Сначала подтвердите тайтл'; end if;
  insert into public.community_episodes(anime_id,episode_key,episode_type,episode_number)
    values(canonical,selected_key,selected_type,selected_number) on conflict(anime_id,episode_key) do nothing;
  select id into canonical_episode from public.community_episodes
    where anime_id=canonical and episode_key=selected_key and episode_type=selected_type
      and episode_number is not distinct from selected_number;
  if canonical_episode is null then raise exception 'Существующий episode key имеет другие свойства'; end if;
  insert into public.community_episode_aliases(provider,release_id,external_episode_id,episode_id,verified)
    values(selected_provider,selected_release,selected_external_episode,canonical_episode,true)
    on conflict(provider,release_id,external_episode_id) do nothing;
  if not exists(select 1 from public.community_episode_aliases where provider=selected_provider
      and release_id=selected_release and external_episode_id=selected_external_episode
      and episode_id=canonical_episode and verified) then raise exception 'Конфликт связи серии'; end if;
end $$;
```

Не объединять сезоны, OVA/TV/фильмы и дробные серии на основании названия. Для второго провайдера проверить его точные IDs и добавить отдельный alias к той же canonical записи, не новую ветку по названию.

## Проверка на устройстве после ручной настройки

1. Установить подписанный APK **4.1.1 (81)** из Actions этого PR поверх прежнего приложения. Проверить совпадение подписи, если ставится обновление; не удалять приложение с медиатекой ради обхода несовпадения подписи.
2. Открыть подтверждённый тайтл → «Обсуждение». Должны сразу показываться заголовок, сортировка и список/честное пустое состояние, без промежуточного «Открыть обсуждение». Гость читает и видит предложение войти для публикации.
3. Войти/зарегистрироваться через аккаунт Community, подтвердить email на том же устройстве и создать username. Встроенный SMTP ограничен двумя письмами в час на проект и тестовыми адресами команды: не устраивать массовую рассылку. Реальная доставка и callback требуют проверки; синтетические CI-тесты их не подтверждают.
4. Вернуться во вкладку: многострочный редактор, счётчик, флаг спойлера и отправка доступны. Опубликовать, обновить и прочитать сообщение гостем/вторым аккаунтом. Проверить ответы, лайки, редактирование и удаление собственного текста, запрет чужого изменения и жалобу.
5. В карточке подтверждённой серии выбрать «Обсудить серию». Проверить номер/key, отдельный список и отсутствие сообщений из общей ветки/другого сезона.
6. Включить авиарежим после загрузки: должен появиться кеш с предупреждением, а не имитация отправки. Проверить поворот, большой шрифт и TalkBack для скрытого спойлера.

До публичного запуска дополнительно нужны внешний SMTP, production HTTPS Site URL вместо localhost, App Links/Digital Asset Links, реальное QA recovery/PKCE/OTP, gateway rate limits и оператор модерации. Эти действия не выполнены автоматически.
