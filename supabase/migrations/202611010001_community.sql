-- Review and apply manually to the existing project. Never run automatically from Android/CI.
begin;
create schema if not exists community_internal;
revoke all on schema community_internal from public, anon, authenticated;
grant usage on schema public to anon, authenticated;

create table public.community_profiles (
 user_id uuid primary key references auth.users(id) on delete cascade,
 username text not null check (username ~ '^[a-zA-Z][a-zA-Z0-9_]{2,23}$'
  and lower(username) not in ('admin','administrator','moderator','support','system','animevault','supabase','deleted','deleted_user')),
 display_name text not null check (char_length(btrim(display_name)) between 1 and 60),
 avatar_path text check (avatar_path in ('default','moon','star','cat')),
 bio text check (char_length(bio) <= 300),
 created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create unique index community_username_unique on public.community_profiles (lower(username));
create table public.community_anime (
 id uuid primary key default gen_random_uuid(), anilist_id bigint unique check (anilist_id > 0),
 mal_id bigint unique check (mal_id > 0), canonical_title text not null check (char_length(canonical_title) between 1 and 300),
 created_at timestamptz not null default now()
);
create table public.community_anime_aliases (
 id uuid primary key default gen_random_uuid(), anime_id uuid not null references public.community_anime(id),
 provider text not null check (char_length(provider) between 1 and 80),
 external_id text not null check (char_length(external_id) between 1 and 300),
 verified boolean not null default false, created_at timestamptz not null default now(), unique(provider, external_id)
);
create table public.community_episodes (
 id uuid primary key default gen_random_uuid(), anime_id uuid not null references public.community_anime(id),
 episode_key text not null check (char_length(episode_key) between 1 and 100),
 episode_number integer check (episode_number > 0),
 episode_type text not null check (episode_type in ('regular','ova','special','movie','fractional')),
 title text check (char_length(title) <= 300), unique(anime_id, episode_key), unique(id, anime_id)
);
-- Provider episode numbers are not enough to establish a canonical episode.
create table public.community_episode_aliases (
 provider text not null, release_id text not null, external_episode_id text not null,
 episode_id uuid not null references public.community_episodes(id), verified boolean not null default false,
 primary key(provider, release_id, external_episode_id),
 check (char_length(provider) between 1 and 80 and char_length(release_id) between 1 and 300
  and char_length(external_episode_id) between 1 and 300)
);
create table public.community_comments (
 id uuid primary key default gen_random_uuid(), anime_id uuid not null references public.community_anime(id),
 episode_id uuid, parent_id uuid references public.community_comments(id),
 author_id uuid references public.community_profiles(user_id) on delete set null,
 body text not null check (char_length(btrim(body, E' \t\n\r')) between 1 and 2000),
 contains_spoiler boolean not null default false,
 status text not null default 'visible' check (status in ('visible','hidden','deleted')),
 client_request_id uuid not null, created_at timestamptz not null default now(),
 updated_at timestamptz not null default now(), deleted_at timestamptz,
 foreign key(episode_id, anime_id) references public.community_episodes(id, anime_id),
 unique(author_id, client_request_id)
);
create index community_comments_thread on public.community_comments(anime_id, episode_id, parent_id, created_at, id);
create index community_comments_author_time on public.community_comments(author_id, created_at);
create table public.community_comment_likes (
 user_id uuid references auth.users(id) on delete cascade, comment_id uuid references public.community_comments(id),
 created_at timestamptz not null default now(), primary key(user_id, comment_id)
);
create index community_likes_comment on public.community_comment_likes(comment_id);
create table public.community_comment_reports (
 id uuid primary key default gen_random_uuid(), reporter_id uuid references auth.users(id) on delete set null,
 comment_id uuid not null references public.community_comments(id),
 reason text not null check (reason in ('spam','insult','harassment','illegal','spoiler','other')),
 description text check (char_length(description) <= 500),
 status text not null default 'open' check (status in ('open','reviewed','dismissed')),
 created_at timestamptz not null default now()
);
create unique index community_reports_active on public.community_comment_reports(reporter_id, comment_id) where status = 'open';
create table public.community_private_roles (
 user_id uuid primary key references auth.users(id) on delete cascade,
 role text not null check (role in ('admin','moderator')), granted_by uuid references auth.users(id) on delete set null,
 created_at timestamptz not null default now()
);
create table public.community_bans (
 user_id uuid primary key references auth.users(id) on delete cascade, reason text not null check (char_length(reason) between 1 and 500),
 issued_by uuid references auth.users(id) on delete set null, created_at timestamptz not null default now(),
 expires_at timestamptz, revoked_at timestamptz
);
create table public.community_moderation_log (
 id uuid primary key default gen_random_uuid(), moderator_id uuid references auth.users(id) on delete set null,
 target_type text not null, target_id uuid not null, action text not null, reason text not null,
 created_at timestamptz not null default now()
);
-- Only RPCs write. No table write privilege is given to API roles.
do $$ declare t text; begin
 foreach t in array array['community_profiles','community_anime','community_anime_aliases','community_episodes',
 'community_episode_aliases','community_comments','community_comment_likes','community_comment_reports',
 'community_private_roles','community_bans','community_moderation_log'] loop
 execute format('alter table public.%I enable row level security', t);
 execute format('revoke all on public.%I from public, anon, authenticated', t);
 end loop;
end $$;
create policy public_profiles on public.community_profiles for select to anon, authenticated using (true);
create policy public_anime on public.community_anime for select to anon, authenticated using (true);
create policy verified_aliases on public.community_anime_aliases for select to anon, authenticated using (verified);
create policy public_episodes on public.community_episodes for select to anon, authenticated using (true);
create policy verified_episodes on public.community_episode_aliases for select to anon, authenticated using (verified);
grant select on public.community_profiles, public.community_anime, public.community_anime_aliases,
 public.community_episodes, public.community_episode_aliases to anon, authenticated;
-- Comments, likes, reports, roles, bans and logs have no direct API access, including SELECT.
-- Redacted comment reads and aggregate counts are returned by bounded RPCs below.

create function community_internal.require_user() returns uuid language plpgsql security definer set search_path = '' as $$
declare u uuid := auth.uid(); begin
 if u is null or not exists(select 1 from auth.users where id=u) then raise exception 'community_unauthorized' using errcode='PT401'; end if;
 if exists(select 1 from public.community_bans where user_id=u and revoked_at is null
  and (expires_at is null or expires_at>now())) then
  raise exception 'community_banned' using errcode='PT403'; end if;
 return u;
end $$;
create function community_internal.require_moderator() returns uuid language plpgsql security definer set search_path = '' as $$
declare u uuid := community_internal.require_user(); begin
 if not exists(select 1 from public.community_private_roles where user_id=u and role in ('admin','moderator')) then
  raise exception 'community_forbidden' using errcode='PT403'; end if;
 return u;
end $$;

create function public.community_save_profile(p_username text, p_display_name text, p_bio text default '', p_avatar text default 'default')
returns jsonb language plpgsql security definer set search_path = '' as $$
declare u uuid := community_internal.require_user(); result public.community_profiles; begin
 insert into public.community_profiles(user_id,username,display_name,bio,avatar_path)
 values(u,btrim(p_username),btrim(p_display_name),btrim(p_bio),p_avatar)
 on conflict(user_id) do update set username=excluded.username, display_name=excluded.display_name,
 bio=excluded.bio,avatar_path=excluded.avatar_path,updated_at=now() returning * into result;
 return to_jsonb(result);
end $$;

-- Resolve only records seeded by a trusted operator; title text never participates.
create function public.community_resolve(p_anilist bigint default null, p_mal bigint default null,
 p_provider text default null, p_external text default null, p_episode_external text default null)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare a uuid; alias_anime uuid; e public.community_episodes; begin
 if char_length(p_provider)>80 or char_length(p_external)>300 or char_length(p_episode_external)>300 then
 raise exception 'community_invalid_identity' using errcode='PT400'; end if;
 select anime_id into alias_anime from public.community_anime_aliases
 where provider=p_provider and external_id=p_external and verified;
 -- For provider pages the verified alias must corroborate any supplied metadata ID.
 if p_provider is not null then
  a:=alias_anime;
  if a is null then return null; end if;
  if exists(select 1 from public.community_anime where id=a and
   ((p_anilist is not null and anilist_id is distinct from p_anilist)
   or (p_mal is not null and mal_id is distinct from p_mal))) then return null; end if;
 else
  select id into a from public.community_anime where anilist_id=p_anilist;
  if a is null and p_anilist is null then select id into a from public.community_anime where mal_id=p_mal; end if;
  if a is not null and p_mal is not null and not exists(select 1 from public.community_anime where id=a and mal_id=p_mal) then return null; end if;
 end if;
 if a is null then return null; end if;
 if p_episode_external is not null then
  select ep.* into e from public.community_episodes ep join public.community_episode_aliases ea on ea.episode_id=ep.id
  where ea.provider=p_provider and ea.release_id=p_external and ea.external_episode_id=p_episode_external and ea.verified and ep.anime_id=a;
  if e.id is null then return null; end if;
 end if;
 return jsonb_build_object('anime_id',a,'episode_id',e.id,'episode_key',e.episode_key,'episode_number',e.episode_number);
end $$;

create function public.community_create_comment(p_anime uuid,p_episode uuid,p_parent uuid,p_body text,p_spoiler boolean,p_request uuid)
returns uuid language plpgsql security definer set search_path = '' as $$
declare u uuid := community_internal.require_user(); result uuid; par public.community_comments; ancestor uuid; depth integer:=0; clean text;
begin
 perform pg_advisory_xact_lock(hashtextextended(u::text, 2410));
 if p_anime is null or not exists(select 1 from public.community_anime where id=p_anime) then
 raise exception 'community_invalid_anime' using errcode='PT400'; end if;
 if p_episode is not null and not exists(select 1 from public.community_episodes where id=p_episode and anime_id=p_anime) then
 raise exception 'community_invalid_episode' using errcode='PT400'; end if;
 if p_request is null or p_spoiler is null then raise exception 'community_invalid_request' using errcode='PT400'; end if;
 select id into result from public.community_comments where author_id=u and client_request_id=p_request;
 if result is not null then return result; end if;
 if not exists(select 1 from public.community_profiles where user_id=u) then raise exception 'community_profile_required' using errcode='PT403'; end if;
 clean:=btrim(replace(replace(p_body,E'\r\n',E'\n'),E'\r',E'\n'),E' \t\n');
 if clean is null or char_length(clean) not between 1 and 2000 then raise exception 'community_invalid_body' using errcode='PT400'; end if;
 if (select count(*) from public.community_comments where author_id=u and created_at>now()-interval '1 minute')>=5
 or (select count(*) from public.community_comments where author_id=u and created_at>now()-interval '1 hour')>=30 then
 raise exception 'community_rate_limited' using errcode='PT429'; end if;
 if exists(select 1 from public.community_comments where author_id=u and body=clean and created_at>now()-interval '1 minute') then
 raise exception 'community_duplicate' using errcode='PT429'; end if;
 ancestor:=p_parent;
 while ancestor is not null loop
  depth:=depth+1;
  if depth>2 then raise exception 'community_depth' using errcode='PT400'; end if;
  select * into par from public.community_comments where id=ancestor;
  if par.id is null or par.anime_id<>p_anime or par.episode_id is distinct from p_episode or par.status<>'visible' then
   raise exception 'community_invalid_parent' using errcode='PT400'; end if;
  ancestor:=par.parent_id;
 end loop;
 insert into public.community_comments(anime_id,episode_id,parent_id,author_id,body,contains_spoiler,client_request_id)
 values(p_anime,p_episode,p_parent,u,clean,p_spoiler,p_request) returning id into result;
 return result;
end $$;
create function public.community_edit_comment(p_id uuid,p_body text,p_spoiler boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare u uuid := community_internal.require_user(); clean text; begin
 clean:=btrim(replace(replace(p_body,E'\r\n',E'\n'),E'\r',E'\n'),E' \t\n');
 if clean is null or char_length(clean) not between 1 and 2000 then raise exception 'community_invalid_body' using errcode='PT400'; end if;
 update public.community_comments set body=clean,contains_spoiler=p_spoiler,updated_at=now() where id=p_id and author_id=u and status='visible';
 if not found then raise exception 'community_forbidden' using errcode='PT403'; end if;
end $$;
create function public.community_delete_comment(p_id uuid) returns void language plpgsql security definer set search_path = '' as $$
declare u uuid := auth.uid(); begin
 if u is null then raise exception 'community_unauthorized' using errcode='PT401'; end if;
 update public.community_comments set body='Комментарий удалён',contains_spoiler=false,status='deleted',deleted_at=now(),updated_at=now()
 where id=p_id and author_id=u and status<>'deleted';
 if not found and not exists(select 1 from public.community_comments where id=p_id and author_id=u and status='deleted') then
 raise exception 'community_forbidden' using errcode='PT403'; end if;
end $$;
-- Desired state makes retries safe (a network retry must never toggle twice).
create function public.community_set_like(p_id uuid,p_liked boolean) returns void language plpgsql security definer set search_path = '' as $$
declare u uuid := community_internal.require_user(); begin
 perform 1 from public.community_comments where id=p_id and status='visible' for share;
 if not found then raise exception 'community_forbidden' using errcode='PT403'; end if;
 if p_liked is null then raise exception 'community_invalid_like' using errcode='PT400'; end if;
 if p_liked then insert into public.community_comment_likes(user_id,comment_id) values(u,p_id) on conflict do nothing;
 else delete from public.community_comment_likes where user_id=u and comment_id=p_id; end if;
end $$;
create function public.community_report(p_id uuid,p_reason text,p_description text default '') returns void
language plpgsql security definer set search_path = '' as $$
declare u uuid := community_internal.require_user(); begin
 if p_reason is null or p_reason not in ('spam','insult','harassment','illegal','spoiler','other') or char_length(p_description)>500 then
 raise exception 'community_invalid_report' using errcode='PT400'; end if;
 perform pg_advisory_xact_lock(hashtextextended(u::text,2411));
 if not exists(select 1 from public.community_comments where id=p_id and status='visible') then raise exception 'community_forbidden' using errcode='PT403'; end if;
 if exists(select 1 from public.community_comment_reports where reporter_id=u and comment_id=p_id and status='open') then return; end if;
 if (select count(*) from public.community_comment_reports where reporter_id=u and created_at>now()-interval '1 hour')>=20 then
 raise exception 'community_rate_limited' using errcode='PT429'; end if;
 insert into public.community_comment_reports(reporter_id,comment_id,reason,description) values(u,p_id,p_reason,p_description);
end $$;

create function public.community_list_comments(p_anime uuid,p_episode uuid default null,p_parent uuid default null,
 p_sort text default 'new',p_offset integer default 0,p_limit integer default 20)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare result jsonb; total bigint; begin
 if p_sort is null or p_offset is null or p_limit is null or p_sort not in ('new','old','popular') or p_offset not between 0 and 10000 or p_limit not between 1 and 50 then
 raise exception 'community_invalid_page' using errcode='PT400'; end if;
 select count(*) into total from public.community_comments where anime_id=p_anime and episode_id is not distinct from p_episode and parent_id is not distinct from p_parent;
 select coalesce(jsonb_agg(row_data order by position),'[]'::jsonb) into result from (
 select jsonb_build_object('id',c.id,'parent_id',c.parent_id,'author_id',c.author_id,
 'username',coalesce(pr.username,'deleted_user'),'display_name',coalesce(pr.display_name,'Удалённый аккаунт'),
 'avatar_path',pr.avatar_path,'created_at',c.created_at,'updated_at',c.updated_at,'status',c.status,
 'body',case c.status when 'visible' then c.body when 'deleted' then 'Комментарий удалён' else 'Комментарий скрыт модератором' end,
 'contains_spoiler',c.contains_spoiler and c.status='visible','likes',lc.n,
 'liked',exists(select 1 from public.community_comment_likes where comment_id=c.id and user_id=auth.uid()),
 'reply_count',(select count(*) from public.community_comments where parent_id=c.id)) row_data,
 row_number() over(order by
 case when p_sort='popular' and p_parent is null then lc.n end desc,
 case when p_sort='new' and p_parent is null then c.created_at end desc,
 c.created_at asc,c.id asc) position
 from public.community_comments c left join public.community_profiles pr on pr.user_id=c.author_id
 cross join lateral (select count(*) n from public.community_comment_likes where comment_id=c.id and c.status='visible') lc
 where c.anime_id=p_anime and c.episode_id is not distinct from p_episode and c.parent_id is not distinct from p_parent
 order by position limit p_limit offset p_offset
 ) page;
 return jsonb_build_object('comments',result,'total',total);
end $$;
create function public.community_profile_stats(p_user uuid) returns jsonb language sql stable security definer set search_path = '' as $$
 select jsonb_build_object('comments',(select count(*) from public.community_comments where author_id=p_user and status='visible'),
 'likes',(select count(*) from public.community_comment_likes l join public.community_comments c on c.id=l.comment_id where c.author_id=p_user and c.status='visible'));
$$;
create function public.community_moderate(p_id uuid,p_action text,p_reason text) returns void language plpgsql security definer set search_path = '' as $$
declare u uuid := community_internal.require_moderator(); begin
 if p_action is null or p_reason is null or p_action not in ('hide','restore') or char_length(btrim(p_reason)) not between 1 and 500 then raise exception 'community_invalid_action' using errcode='PT400'; end if;
 update public.community_comments set status=case p_action when 'hide' then 'hidden' else 'visible' end,updated_at=now()
 where id=p_id and status<>'deleted';
 if not found then raise exception 'community_not_found' using errcode='PT404'; end if;
 update public.community_comment_reports set status='reviewed' where comment_id=p_id and status='open';
 insert into public.community_moderation_log(moderator_id,target_type,target_id,action,reason) values(u,'comment',p_id,p_action,p_reason);
end $$;
create function public.community_ban(p_user uuid,p_reason text,p_until timestamptz default null,p_revoke boolean default false) returns void
language plpgsql security definer set search_path = '' as $$
declare u uuid := community_internal.require_moderator(); begin
 if p_user is null or p_reason is null or p_revoke is null or not exists(select 1 from auth.users where id=p_user) or char_length(btrim(p_reason)) not between 1 and 500 or (p_until is not null and p_until<=now()) then raise exception 'community_invalid_ban' using errcode='PT400'; end if;
 if exists(select 1 from public.community_private_roles where user_id=p_user) then raise exception 'community_forbidden' using errcode='PT403'; end if;
 insert into public.community_bans(user_id,reason,issued_by,expires_at,revoked_at) values(p_user,p_reason,u,p_until,case when p_revoke then now() end)
 on conflict(user_id) do update set reason=excluded.reason,issued_by=u,created_at=now(),expires_at=p_until,revoked_at=excluded.revoked_at;
 insert into public.community_moderation_log(moderator_id,target_type,target_id,action,reason)
 values(u,'user',p_user,case when p_revoke then 'unban' else 'ban' end,p_reason);
end $$;
create function public.community_review_reports(p_offset integer default 0) returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
 perform community_internal.require_moderator();
 if p_offset is null or p_offset not between 0 and 10000 then raise exception 'community_invalid_page' using errcode='PT400'; end if;
 return (select coalesce(jsonb_agg(to_jsonb(r)),'[]') from (
 select cr.*,c.body,c.author_id from public.community_comment_reports cr join public.community_comments c on c.id=cr.comment_id
 where cr.status='open' order by cr.created_at,cr.id limit 50 offset p_offset) r);
end $$;
create function public.community_review_log(p_offset integer default 0) returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
 perform community_internal.require_moderator();
 if p_offset is null or p_offset not between 0 and 10000 then raise exception 'community_invalid_page' using errcode='PT400'; end if;
 return (select coalesce(jsonb_agg(to_jsonb(l)),'[]') from (select * from public.community_moderation_log order by created_at desc,id limit 50 offset p_offset) l);
end $$;
-- Trusted SQL owner only: reauthenticate/verify ownership outside this function, then erase Auth identity.
-- FK actions anonymise comment attribution and reporter/moderator identities without deleting replies.
create function community_internal.erase_account(p_user uuid) returns void language plpgsql security definer set search_path = '' as $$
begin
 update public.community_comments set body='Комментарий удалён',contains_spoiler=false,status='deleted',deleted_at=now(),updated_at=now() where author_id=p_user;
 update public.community_comment_reports set description=null where reporter_id=p_user;
 delete from auth.users where id=p_user;
end $$;
-- Erase personal free text even if an operator deletes the Auth user through the Dashboard.
create function community_internal.before_profile_delete() returns trigger language plpgsql security definer set search_path = '' as $$
begin
 update public.community_comments set body='Комментарий удалён',contains_spoiler=false,status='deleted',deleted_at=now(),updated_at=now() where author_id=old.user_id;
 update public.community_comment_reports set description=null where reporter_id=old.user_id;
 return old;
end $$;
create function community_internal.before_auth_delete() returns trigger language plpgsql security definer set search_path = '' as $$
begin
 update public.community_comment_reports set description=null where reporter_id=old.id;
 return old;
end $$;
create trigger community_auth_erasure before delete on auth.users for each row execute function community_internal.before_auth_delete();
create trigger community_profile_erasure before delete on public.community_profiles for each row execute function community_internal.before_profile_delete();
-- PostgreSQL gives PUBLIC execute on functions by default: explicitly remove it for every new function.
do $$ declare f record; begin
 for f in select p.oid::regprocedure signature,p.proname,p.pronamespace from pg_proc p
 where p.pronamespace='community_internal'::regnamespace or (p.pronamespace='public'::regnamespace and p.proname like 'community\_%' escape '\') loop
 execute format('revoke all on function %s from public, anon, authenticated',f.signature);
 if f.pronamespace='public'::regnamespace then
 execute format('grant execute on function %s to authenticated',f.signature);
 if f.proname in ('community_resolve','community_list_comments','community_profile_stats') then
 execute format('grant execute on function %s to anon',f.signature); end if;
 end if;
 end loop;
end $$;
commit;
