-- Additive repair: distinguish queued/working/failed identities and bound stale failures.
-- No canonical aliases, verified evidence, comments, Auth or RLS are changed.
-- Must run AFTER 202611020001; protected service_role-only RPCs retain their ACL.
BEGIN;
CREATE OR REPLACE FUNCTION public.community_auto_claim(p_identity jsonb,p_client_hash text,p_background boolean DEFAULT false)
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
 IF FOUND AND job.next_attempt_at>now() THEN
  -- Surface safe prior failure codes. A finished failure is not an active lease.
  -- Never expose the identity, client hash, or backend response.
  RETURN jsonb_build_object(
   'status',CASE WHEN job.state='failed' THEN 'unverified' ELSE 'pending' END,
   'reason',CASE WHEN job.state IN ('failed','queued') THEN job.error_code ELSE NULL END,
   'retry_after_seconds',GREATEST(1,CEIL(EXTRACT(EPOCH FROM (job.next_attempt_at-now())))::integer));
 END IF;
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

CREATE OR REPLACE FUNCTION public.community_auto_finish(p_key text,p_lease uuid,p_code text,p_retry boolean)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF coalesce(auth.role(),'')<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
 IF p_code IS NULL OR p_code !~ '^[a-z0-9_]{1,80}$' THEN RAISE EXCEPTION 'invalid_error_code'; END IF;
 UPDATE public.community_auto_jobs SET state=CASE WHEN p_retry AND attempts<3 THEN 'queued' ELSE 'failed' END,
 next_attempt_at=now()+CASE WHEN p_retry AND attempts<3 THEN interval '2 minutes' ELSE interval '5 minutes' END,error_code=p_code,updated_at=now()
 WHERE key=p_key AND lease=p_lease AND state='working';
END $$;

-- Previously failed identities can retry within five minutes of this repair.
-- Keep existing lease/state/error and all budgets intact; never touch working jobs.
UPDATE public.community_auto_jobs
 SET next_attempt_at=LEAST(next_attempt_at,now()+interval '5 minutes')
 WHERE state='failed' AND next_attempt_at>now()+interval '5 minutes';

COMMIT;
