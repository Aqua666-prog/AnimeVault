-- AnimeVault-owned discussions. No network calls, Kodik tokens, or provider aliases.
-- Anonymous visits resolve a stable empty context without writing rows.
-- A signed-in member creates the topic only when it is first used.
BEGIN;

CREATE TABLE public.community_app_topics (
  topic_key text PRIMARY KEY CHECK (
    topic_key ~ '^av1:(mal|anilist):[1-9][0-9]{0,18}$'
    OR topic_key ~ '^av1:title:[a-f0-9]{64}$'
  ),
  anime_id uuid NOT NULL REFERENCES public.community_anime(id) ON DELETE RESTRICT,
  created_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX community_app_topics_creator_time
  ON public.community_app_topics(created_by, created_at DESC);
ALTER TABLE public.community_app_topics ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.community_app_topics FROM PUBLIC, anon, authenticated;

CREATE FUNCTION public.community_app_open(
  p_key text,
  p_title text,
  p_episode_key text DEFAULT NULL,
  p_episode_number integer DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  member uuid := auth.uid();
  title text := btrim(p_title, E' \t\n\r');
  anime uuid;
  episode uuid;
  synthetic_anime uuid;
  synthetic_episode uuid;
  id_value bigint;
  episode_type text;
BEGIN
  -- The key is a client-visible AnimeVault identity, NOT evidence from any video API.
  IF p_key IS NULL OR NOT (
    p_key ~ '^av1:(mal|anilist):[1-9][0-9]{0,18}$'
    OR p_key ~ '^av1:title:[a-f0-9]{64}$'
  ) OR title IS NULL OR char_length(title) NOT BETWEEN 1 AND 300
  THEN RAISE EXCEPTION 'community_invalid_identity' USING errcode='PT400';
  END IF;
  IF (p_episode_key IS NULL AND p_episode_number IS NOT NULL)
    OR (p_episode_key IS NOT NULL AND
       (p_episode_key !~ '^(regular|ova|special|movie|fractional):[a-z0-9][a-z0-9._-]{0,39}$'
        OR (p_episode_number IS NOT NULL AND p_episode_number NOT BETWEEN 1 AND 10000)))
  THEN RAISE EXCEPTION 'community_invalid_episode' USING errcode='PT400';
  END IF;

  synthetic_anime := md5('animevault:topic:v1:' || p_key)::uuid;
  SELECT t.anime_id INTO anime FROM public.community_app_topics t WHERE t.topic_key=p_key;

  -- A genuine pre-existing AniList/MAL canonical row may already carry comments.
  -- Never rewrite its trusted identifiers, canonical title, or provider aliases.
  IF anime IS NULL AND p_key LIKE 'av1:mal:%' THEN
    id_value := substring(p_key FROM 9)::bigint;
    SELECT a.id INTO anime FROM public.community_anime a WHERE a.mal_id=id_value;
  ELSIF anime IS NULL AND p_key LIKE 'av1:anilist:%' THEN
    id_value := substring(p_key FROM 13)::bigint;
    SELECT a.id INTO anime FROM public.community_anime a WHERE a.anilist_id=id_value;
  END IF;

  -- An anonymous visitor sees a stable (possibly empty) thread without writes.
  -- Only a signed-in, non-banned member can materialize a new topic or episode.
  IF member IS NOT NULL THEN
    PERFORM community_internal.require_user();
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('av-topic:' || p_key, 0));
    SELECT t.anime_id INTO anime FROM public.community_app_topics t WHERE t.topic_key=p_key;

    IF anime IS NULL THEN
      -- Serialize quotas per account, even across different title keys.
      PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('av-member:' || member::text, 0));
      IF (SELECT count(*) FROM public.community_app_topics
           WHERE created_by=member AND created_at>now()-interval '1 hour') >= 20
        OR (SELECT count(*) FROM public.community_app_topics
           WHERE created_by=member AND created_at>now()-interval '1 day') >= 80
      THEN RAISE EXCEPTION 'community_rate_limited' USING errcode='PT429'; END IF;
      IF p_key LIKE 'av1:mal:%' THEN
        SELECT a.id INTO anime FROM public.community_anime a
         WHERE a.mal_id=substring(p_key FROM 9)::bigint;
      ELSIF p_key LIKE 'av1:anilist:%' THEN
        SELECT a.id INTO anime FROM public.community_anime a
         WHERE a.anilist_id=substring(p_key FROM 13)::bigint;
      END IF;
      IF anime IS NULL THEN
        anime := synthetic_anime;
        INSERT INTO public.community_anime(id,canonical_title) VALUES(anime,title);
      END IF;
      INSERT INTO public.community_app_topics(topic_key,anime_id,created_by)
        VALUES(p_key,anime,member);
    END IF;
  END IF;
  anime := coalesce(anime,synthetic_anime);

  IF p_episode_key IS NOT NULL THEN
    episode_type := split_part(p_episode_key,':',1);
    synthetic_episode := md5('animevault:episode:v1:' || p_key || ':' || p_episode_key)::uuid;
    SELECT e.id INTO episode FROM public.community_episodes e
      WHERE e.anime_id=anime AND e.episode_key=p_episode_key;
    IF episode IS NULL AND member IS NOT NULL THEN
      INSERT INTO public.community_episodes(id,anime_id,episode_key,episode_number,episode_type)
        VALUES(synthetic_episode,anime,p_episode_key,p_episode_number,episode_type)
        ON CONFLICT(anime_id,episode_key) DO NOTHING;
      SELECT e.id INTO episode FROM public.community_episodes e
        WHERE e.anime_id=anime AND e.episode_key=p_episode_key;
    END IF;
    episode := coalesce(episode,synthetic_episode);
  END IF;
  RETURN jsonb_build_object('anime_id',anime,'episode_id',episode,
    'episode_key',p_episode_key,'episode_number',p_episode_number);
END;
$$;

REVOKE ALL ON FUNCTION public.community_app_open(text,text,text,integer)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.community_app_open(text,text,text,integer)
  TO anon, authenticated;

COMMIT;
