# Community: модель доступа

Все новые таблицы имеют RLS. По умолчанию API-ролям отозваны все права на таблицы. Обычные клиенты не получают INSERT/UPDATE/DELETE: запись разрешена только RPC, которые сами формируют author/user UUID, даты, status и counters. Поэтому одних RLS-политик не требуется считать достаточной защитой — доступ определяется совместно GRANT и функциями.

| Объект | anon | authenticated |
|---|---|---|
| community_profiles | SELECT публичных полей | SELECT публичных полей |
| community_anime, community_episodes | SELECT | SELECT |
| anime/episode aliases | SELECT только verified через RLS | То же |
| community_comments, likes | Прямой доступ запрещён | Прямой доступ запрещён |
| reports, bans, roles, moderation_log | Запрещён | Запрещён |
| community_resolve, list_comments, profile_stats | EXECUTE | EXECUTE |
| save_profile, create/edit/delete_comment, set_like, report | Запрещён | EXECUTE с проверкой auth.uid() |
| moderate, ban, review_reports, review_log | Запрещён | EXECUTE, внутри требуется серверная роль |
| community_internal.* | Нет USAGE и EXECUTE | Нет USAGE и EXECUTE |

Каждая SECURITY DEFINER-функция фиксирует пустой search_path и использует квалифицированные имена. PUBLIC execute явно отозван в конце той же транзакции, включая внутренние helpers и trigger. Динамический SQL применяется только при настройке прав на фиксированный набор объектов, не к пользовательскому вводу. Публичных views нет. Роли не читаются из user_metadata.

`community_list_comments` возвращает только публичные поля автора, агрегат лайков и личный boolean `liked`. Чужие UUID лайкающих не возвращаются. Для hidden/deleted возвращается placeholder, исходный body скрыт даже от автора через обычный RPC. Модератор проверяет исходный текст в закрытом списке жалоб либо доверенным SQL. DELETE автора не уничтожает ответы. Внешние ключи гарантируют контекст серии; RPC проверяет parent chain и глубину.

Публикация берёт транзакционную advisory lock по UUID пользователя, проверяет лимиты 5/мин и 30/час и недавний дубликат текста. Проверка client_request_id выполняется под той же блокировкой. Soft deletion не обнуляет частотные лимиты. Лайк имеет составной PK и операцию desired-state; повтор безопасен. Жалобы имеют partial unique index для активного статуса и дополнительный предел 20/час. Заблокированный аккаунт не может создавать/редактировать комментарии, лайкать и жаловаться. Удаление собственного текста остаётся доступным.

Дополнительная инфраструктурная защита до публичного запуска: gateway rate limits для анонимных запросов, мониторинг медленных запросов и размеров базы, Auth rate limits, политика хранения moderation log, резервные копии, настройка SMTP и App Links. Ограниченные страницы/offset снижают стоимость запросов, но не заменяют защиту от распределённого массового трафика.

## Ручной аудит владельцем

В SQL Editor проверить:

```sql
select relname, relrowsecurity from pg_class
where relnamespace = 'public'::regnamespace and relkind = 'r' and relname like 'community_%';

select schemaname, tablename, policyname, roles, cmd, qual, with_check
from pg_policies where tablename like 'community_%';

select table_name, grantee, privilege_type from information_schema.role_table_grants
where table_schema = 'public' and table_name like 'community_%';

select p.oid::regprocedure, p.prosecdef, p.proconfig,
 has_function_privilege('anon',p.oid,'execute') as anon_execute,
 has_function_privilege('authenticated',p.oid,'execute') as authenticated_execute
from pg_proc p where p.pronamespace in ('public'::regnamespace,'community_internal'::regnamespace)
and (p.proname like 'community_%' or p.pronamespace='community_internal'::regnamespace);
```

Проверить отсутствие CREATE в public у недоверенных ролей в текущем проекте и отсутствие дополнительных inherited grants. Новая миграция не переписывает глобальные права существующих приложений. Тестировать с реальным anon key и JWT двух тестовых пользователей, не только через SQL Editor владельца, который обходит RLS.

Автотесты одноразового PostgreSQL находятся в `tools/test-community-database.py`: права/RLS, подмена автора, изменение чужого текста/лайка, повышение роли, закрытые жалобы, moderation/ban/unban, редактирование/удаление, parent context/depth, canonical mapping, сортировка/страницы, повтор операции, параллельные лимиты и удаление аккаунта. Они не являются тестом работающего production PostgREST/Auth.
