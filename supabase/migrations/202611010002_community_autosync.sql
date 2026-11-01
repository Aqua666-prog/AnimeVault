-- Additive AutoSync v1. Apply ONCE after 202611010001_community.sql.
-- No Android contract changes. No catalogue writes until the separately gated worker runs.
BEGIN;
CREATE TABLE public.community_sync_evidence (
  provider text NOT NULL CHECK (length(provider) BETWEEN 1 AND 80),
  external_id text NOT NULL CHECK (length(external_id) BETWEEN 1 AND 300),
  anilist_id bigint,
  status text NOT NULL CHECK (status IN ('verified','pending','rejected')),
  evidence jsonb NOT NULL CHECK (jsonb_typeof(evidence)='object' AND octet_length(evidence::text)<=8000),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(provider,external_id),
  CHECK (status <> 'verified' OR anilist_id > 0 AND anilist_id IS NOT NULL)
);
CREATE TABLE public.community_sync_batches (
  id uuid PRIMARY KEY,
  payload_hash text NOT NULL,
  processed integer NOT NULL CHECK (processed BETWEEN 1 AND 100),
  result jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.community_sync_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_sync_batches ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.community_sync_evidence,public.community_sync_batches FROM PUBLIC,anon,authenticated;
-- Neither table is directly writable even by service_role; only this narrowly scoped RPC.
REVOKE ALL ON public.community_sync_evidence,public.community_sync_batches FROM service_role;
CREATE FUNCTION public.community_sync_batch(p_batch uuid,p_items jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  item jsonb; aid uuid; other uuid; alias_aid uuid; n integer; h text; prior public.community_sync_batches;
  al bigint; mal bigint; st text; provider_value text; external_value text;
  canonical_count integer:=0; verified_count integer:=0; pending_count integer:=0; rejected_count integer:=0; result_value jsonb;
BEGIN
  IF coalesce(auth.role(),'') <> 'service_role' THEN
    RAISE EXCEPTION 'Admin sync credentials required' USING ERRCODE='42501';
  END IF;
  IF p_batch IS NULL OR p_items IS NULL OR jsonb_typeof(p_items)<>'array' THEN
    RAISE EXCEPTION 'Invalid sync batch' USING ERRCODE='22023';
  END IF;
  n:=jsonb_array_length(p_items);
  IF n NOT BETWEEN 1 AND 100 OR octet_length(p_items::text)>1000000 THEN
    RAISE EXCEPTION 'Sync batch too large' USING ERRCODE='22023';
  END IF;
  -- Serializes canonical-ID and alias conflict checks across all worker invocations.
  PERFORM pg_catalog.pg_advisory_xact_lock(61967,410);
  h:=pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p_items::text,'UTF8')),'hex');
  SELECT * INTO prior FROM public.community_sync_batches WHERE id=p_batch;
  IF FOUND THEN
    IF prior.payload_hash<>h THEN RAISE EXCEPTION 'Batch ID reused with different content' USING ERRCODE='22023'; END IF;
    RETURN prior.result || jsonb_build_object('replayed',true);
  END IF;
  FOR item IN SELECT value FROM jsonb_array_elements(p_items) LOOP
    aid:=NULL; other:=NULL; alias_aid:=NULL;
    al:=(item->>'anilist_id')::bigint;
    IF item->>'kind'='anime' THEN
      mal:=(item->>'mal_id')::bigint;
      IF al IS NULL OR al<=0 OR (mal IS NOT NULL AND mal<=0)
        OR coalesce(length(item->>'title'),0) NOT BETWEEN 1 AND 300 THEN
        RAISE EXCEPTION 'Invalid canonical record' USING ERRCODE='22023';
      END IF;
      SELECT id INTO aid FROM public.community_anime WHERE anilist_id=al;
      SELECT id INTO other FROM public.community_anime WHERE mal_id=mal;
      IF (other IS NOT NULL AND (aid IS NULL OR aid<>other)) OR EXISTS(
        SELECT 1 FROM public.community_anime WHERE id=aid AND mal_id IS NOT NULL AND mal_id IS DISTINCT FROM mal
      ) THEN RAISE EXCEPTION 'Canonical ID conflict: review mapping' USING ERRCODE='22023'; END IF;
      INSERT INTO public.community_anime(anilist_id,mal_id,canonical_title)
        VALUES(al,mal,item->>'title') ON CONFLICT(anilist_id)
        DO UPDATE SET mal_id=excluded.mal_id,canonical_title=excluded.canonical_title;
      canonical_count:=canonical_count+1;
    ELSIF item->>'kind'='link' THEN
      st:=item->>'status'; provider_value:=item->>'provider'; external_value:=item->>'external_id';
      IF st IS NULL OR st NOT IN ('verified','pending','rejected') OR
        coalesce(length(provider_value),0) NOT BETWEEN 1 AND 80 OR
        coalesce(length(external_value),0) NOT BETWEEN 1 AND 300 OR
        coalesce(jsonb_typeof(item->'evidence'),'')<>'object' OR octet_length((item->'evidence')::text)>8000 THEN
        RAISE EXCEPTION 'Invalid identity evidence' USING ERRCODE='22023';
      END IF;
      SELECT id INTO aid FROM public.community_anime WHERE anilist_id=al;
      SELECT anime_id INTO alias_aid FROM public.community_anime_aliases
        WHERE provider=provider_value AND external_id=external_value;
      IF st='verified' THEN
        IF aid IS NULL OR coalesce(item->'evidence'->>'method','') NOT IN ('direct_id','external_link_chain') THEN
          RAISE EXCEPTION 'Verified identity requires canonical ID and proof' USING ERRCODE='22023';
        END IF;
        IF alias_aid IS NOT NULL AND alias_aid<>aid THEN
          -- Preserve the established mapping; retain a rejected candidate for operator review.
          st:='rejected';
        ELSE
          INSERT INTO public.community_anime_aliases(anime_id,provider,external_id,verified)
          VALUES(aid,provider_value,external_value,true)
          ON CONFLICT(provider,external_id) DO UPDATE SET verified=true;
        END IF;
      END IF;
      IF st='verified' THEN verified_count:=verified_count+1;
      ELSIF st='pending' THEN pending_count:=pending_count+1;
      ELSE rejected_count:=rejected_count+1; END IF;
      INSERT INTO public.community_sync_evidence(provider,external_id,anilist_id,status,evidence)
      VALUES(provider_value,external_value,al,st,item->'evidence')
      ON CONFLICT(provider,external_id) DO UPDATE SET anilist_id=excluded.anilist_id,status=excluded.status,
        evidence=excluded.evidence,updated_at=now();
    ELSE RAISE EXCEPTION 'Unknown sync item' USING ERRCODE='22023';
    END IF;
  END LOOP;
  result_value:=jsonb_build_object('processed',n,'canonical_upserted',canonical_count,'verified_applied',verified_count,'pending',pending_count,'rejected',rejected_count);
  INSERT INTO public.community_sync_batches VALUES(p_batch,h,n,result_value,now());
  RETURN result_value || jsonb_build_object('replayed',false);
END;
$$;
ALTER FUNCTION public.community_sync_batch(uuid,jsonb) OWNER TO postgres;
REVOKE ALL ON FUNCTION public.community_sync_batch(uuid,jsonb) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.community_sync_batch(uuid,jsonb) TO service_role;
COMMENT ON FUNCTION public.community_sync_batch(uuid,jsonb) IS 'Trusted AutoSync worker only. No episode-number matching or alias reassignment.';
COMMIT;
