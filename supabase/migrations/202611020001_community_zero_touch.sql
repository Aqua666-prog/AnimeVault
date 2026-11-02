-- Additive: trusted Edge provisioning. All automatic writes OFF until owner opt-in.
BEGIN;
CREATE TABLE public.community_auto_settings (
 id boolean PRIMARY KEY DEFAULT true CHECK(id),
 on_demand_enabled boolean NOT NULL DEFAULT false,
 background_enabled boolean NOT NULL DEFAULT false,
 per_minute integer NOT NULL DEFAULT 10 CHECK(per_minute BETWEEN 1 AND 60),
 per_day integer NOT NULL DEFAULT 200 CHECK(per_day BETWEEN 1 AND 5000)
);
INSERT INTO public.community_auto_settings DEFAULT VALUES;
CREATE TABLE public.community_auto_jobs (
 key text PRIMARY KEY,
 identity jsonb NOT NULL,
 state text NOT NULL CHECK(state IN ('working','queued','failed','resolved')),
 lease uuid NOT NULL DEFAULT gen_random_uuid(),
 background boolean NOT NULL DEFAULT false,
 attempts integer NOT NULL DEFAULT 1,
 next_attempt_at timestamptz NOT NULL DEFAULT now()+interval '90 seconds',
 error_code text,
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX community_auto_pending ON public.community_auto_jobs(next_attempt_at) WHERE state='queued';
CREATE TABLE public.community_auto_budget (
 bucket timestamptz NOT NULL,
 client_hash text NOT NULL,
 used integer NOT NULL DEFAULT 0,
 PRIMARY KEY(bucket,client_hash)
);
ALTER TABLE public.community_auto_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_auto_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_auto_budget ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.community_auto_settings,public.community_auto_jobs,public.community_auto_budget FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION public.community_auto_claim(p_identity jsonb,p_client_hash text,p_background boolean DEFAULT false)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE cfg public.community_auto_settings; job public.community_auto_jobs; k text; minute timestamptz; used integer; bridge jsonb;
BEGIN
 IF coalesce(auth.role(),'')<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
 IF p_identity IS NULL OR jsonb_typeof(p_identity)<>'object' OR octet_length(p_identity::text)>2000
 OR coalesce(p_identity->>'p_provider','') NOT IN ('kodik','animetka','aniliberty','yummy')
 OR coalesce(p_identity->>'p_external','') !~ '^[a-zA-Z0-9:_-]{1,100}$'
 OR p_identity->>'p_episode_external' IS NOT NULL OR p_client_hash IS NULL OR p_client_hash !~ '^[a-f0-9]{64}$'
 THEN RAISE EXCEPTION 'invalid_identity' USING ERRCODE='22023'; END IF;
 SELECT * INTO cfg FROM public.community_auto_settings WHERE id FOR SHARE;
 IF NOT coalesce(CASE WHEN p_background THEN cfg.background_enabled ELSE cfg.on_demand_enabled END,false) THEN RETURN jsonb_build_object('status','disabled'); END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(61967,412);
 k:=pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p_identity::text,'UTF8')),'hex');
 SELECT * INTO job FROM public.community_auto_jobs WHERE key=k;
 IF FOUND AND job.next_attempt_at>now() THEN RETURN jsonb_build_object('status','pending'); END IF;
 minute:=date_trunc('minute',now());
 SELECT coalesce(sum(b.used),0) INTO used FROM public.community_auto_budget b WHERE b.client_hash='' AND b.bucket>=date_trunc('day',now());
 IF used>=cfg.per_day OR coalesce((SELECT b.used FROM public.community_auto_budget b WHERE b.bucket=minute AND b.client_hash=''),0)>=cfg.per_minute
 OR coalesce((SELECT b.used FROM public.community_auto_budget b WHERE b.bucket=minute AND b.client_hash=p_client_hash),0)>=3
 THEN RETURN jsonb_build_object('status','rate_limited'); END IF;
 DELETE FROM public.community_auto_budget WHERE bucket<date_trunc('day',now())-interval '1 day';
 DELETE FROM public.community_auto_jobs WHERE updated_at<now()-interval '7 days';
 INSERT INTO public.community_auto_budget VALUES(minute,'',1) ON CONFLICT(bucket,client_hash) DO UPDATE SET used=public.community_auto_budget.used+1;
 INSERT INTO public.community_auto_budget VALUES(minute,p_client_hash,1) ON CONFLICT(bucket,client_hash) DO UPDATE SET used=public.community_auto_budget.used+1;
 INSERT INTO public.community_auto_jobs(key,identity,state,background) VALUES(k,p_identity,'working',p_background)
 ON CONFLICT(key) DO UPDATE SET lease=gen_random_uuid(),state='working',background=p_background,attempts=public.community_auto_jobs.attempts+1,next_attempt_at=now()+interval '90 seconds',updated_at=now(),error_code=NULL
 RETURNING * INTO job;
 SELECT evidence->'provenance' INTO bridge FROM public.community_sync_evidence WHERE provider=p_identity->>'p_provider' AND external_id=p_identity->>'p_external' AND status IN ('verified','pending');
 RETURN jsonb_build_object('status','claimed','key',k,'lease',job.lease,'bridge',bridge);
END $$;

CREATE FUNCTION public.community_auto_complete(p_key text,p_lease uuid,p_items jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE job public.community_auto_jobs; cfg public.community_auto_settings; item jsonb; al bigint; mal bigint; canonical uuid; other uuid; linked uuid; receipt jsonb;
BEGIN
 IF coalesce(auth.role(),'')<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
 SELECT * INTO cfg FROM public.community_auto_settings WHERE id FOR SHARE;
 SELECT * INTO job FROM public.community_auto_jobs WHERE key=p_key FOR UPDATE;
 IF NOT FOUND OR job.lease<>p_lease OR job.state<>'working' OR job.next_attempt_at<now() THEN RAISE EXCEPTION 'stale_lease' USING ERRCODE='22023'; END IF;
 IF NOT coalesce(CASE WHEN job.background THEN cfg.background_enabled ELSE cfg.on_demand_enabled END,false) THEN RAISE EXCEPTION 'auto_disabled' USING ERRCODE='42501'; END IF;
 IF jsonb_typeof(p_items)<>'array' OR jsonb_array_length(p_items)<>2 OR octet_length(p_items::text)>16000 THEN RAISE EXCEPTION 'invalid_verified_pair' USING ERRCODE='22023'; END IF;
 item:=p_items->1;al:=(p_items->0->>'anilist_id')::bigint;mal:=(p_items->0->>'mal_id')::bigint;
 IF al IS NULL OR mal IS NULL OR al<=0 OR mal<=0 OR p_items->0->>'kind'<>'anime' OR item->>'kind'<>'link' OR item->>'status'<>'verified'
 OR item->>'provider' IS DISTINCT FROM job.identity->>'p_provider' OR item->>'external_id' IS DISTINCT FROM job.identity->>'p_external'
 OR (item->>'anilist_id')::bigint IS DISTINCT FROM al
 OR (job.identity->>'p_anilist' IS NOT NULL AND (job.identity->>'p_anilist')::bigint<>al)
 OR (job.identity->>'p_mal' IS NOT NULL AND (job.identity->>'p_mal')::bigint<>mal)
 THEN RAISE EXCEPTION 'verified_identity_conflict' USING ERRCODE='22023'; END IF;
 -- Same lock as existing batch importer; conflict preflight and both writes are atomic.
 PERFORM pg_catalog.pg_advisory_xact_lock(61967,410);
 SELECT id INTO canonical FROM public.community_anime WHERE anilist_id=al;
 SELECT id INTO other FROM public.community_anime WHERE mal_id=mal;
 SELECT anime_id INTO linked FROM public.community_anime_aliases WHERE provider=item->>'provider' AND external_id=item->>'external_id';
 IF (other IS NOT NULL AND other IS DISTINCT FROM canonical) OR (linked IS NOT NULL AND linked IS DISTINCT FROM canonical)
 OR EXISTS(SELECT 1 FROM public.community_anime WHERE id=canonical AND mal_id IS NOT NULL AND mal_id<>mal)
 THEN RAISE EXCEPTION 'canonical_alias_conflict' USING ERRCODE='22023'; END IF;
 receipt:=public.community_sync_batch((md5(p_items::text))::uuid,p_items);
 IF (receipt->>'verified_applied')::integer<>1 OR (receipt->>'rejected')::integer<>0 THEN RAISE EXCEPTION 'pair_not_accepted' USING ERRCODE='22023'; END IF;
 UPDATE public.community_auto_jobs SET state='resolved',next_attempt_at=now()+interval '1 hour',updated_at=now(),error_code=NULL WHERE key=p_key;
 SELECT id INTO canonical FROM public.community_anime WHERE anilist_id=al;
 RETURN jsonb_build_object('anime_id',canonical,'episode_id',NULL);
END $$;

CREATE FUNCTION public.community_auto_finish(p_key text,p_lease uuid,p_code text,p_retry boolean)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF coalesce(auth.role(),'')<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
 IF p_code IS NULL OR p_code !~ '^[a-z0-9_]{1,80}$' THEN RAISE EXCEPTION 'invalid_error_code'; END IF;
 UPDATE public.community_auto_jobs SET state=CASE WHEN p_retry AND attempts<3 THEN 'queued' ELSE 'failed' END,
 next_attempt_at=now()+CASE WHEN p_retry AND attempts<3 THEN interval '2 minutes' ELSE interval '6 hours' END,error_code=p_code,updated_at=now()
 WHERE key=p_key AND lease=p_lease AND state='working';
END $$;

CREATE FUNCTION public.community_auto_pending()
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF coalesce(auth.role(),'')<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.community_auto_settings WHERE id AND background_enabled) THEN RETURN '[]'::jsonb; END IF;
 -- A different provider/request or administrative Batch may have completed the mapping.
 -- Retire those jobs so repeated background fast-path reads cannot starve new work.
 UPDATE public.community_auto_jobs j SET state='resolved',updated_at=now(),next_attempt_at=now()+interval '1 hour',error_code=NULL
 WHERE j.state IN ('queued','working') AND EXISTS(
   SELECT 1 FROM public.community_anime_aliases l JOIN public.community_anime a ON a.id=l.anime_id
   WHERE l.provider=j.identity->>'p_provider' AND l.external_id=j.identity->>'p_external' AND l.verified
   AND (j.identity->>'p_anilist' IS NULL OR a.anilist_id=(j.identity->>'p_anilist')::bigint)
   AND (j.identity->>'p_mal' IS NULL OR a.mal_id=(j.identity->>'p_mal')::bigint));
 RETURN coalesce((SELECT jsonb_agg(identity) FROM (SELECT identity FROM public.community_auto_jobs WHERE (state='queued' OR state='working') AND next_attempt_at<=now() AND attempts<3 ORDER BY attempts,next_attempt_at,key LIMIT 5) q),'[]'::jsonb);
END $$;

ALTER FUNCTION public.community_auto_claim(jsonb,text,boolean) OWNER TO postgres;
ALTER FUNCTION public.community_auto_complete(text,uuid,jsonb) OWNER TO postgres;
ALTER FUNCTION public.community_auto_finish(text,uuid,text,boolean) OWNER TO postgres;
ALTER FUNCTION public.community_auto_pending() OWNER TO postgres;
REVOKE ALL ON FUNCTION public.community_auto_claim(jsonb,text,boolean),public.community_auto_complete(text,uuid,jsonb),public.community_auto_finish(text,uuid,text,boolean),public.community_auto_pending() FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.community_auto_claim(jsonb,text,boolean),public.community_auto_complete(text,uuid,jsonb),public.community_auto_finish(text,uuid,text,boolean),public.community_auto_pending() TO service_role;
COMMIT;
