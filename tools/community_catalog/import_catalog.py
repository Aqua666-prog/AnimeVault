#!/usr/bin/env python3
"""Read AniList -> reviewable plan -> SQL file. Never connects/writes to Supabase."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse

PROVIDERS={'aniliberty','kodik','animelib','animevost','jutsu','dreamerscast','animedia','animeon','sameband','animebest','yummy','animetka','all'}
QUERY='query($id:Int,$mal:Int){Media(id:$id,idMal:$mal,type:ANIME){id idMal title{romaji english native} format seasonYear episodes}}'

class Invalid(Exception):
    pass

class SafeParser(argparse.ArgumentParser):
    def error(self,message):
        self.exit(2,"Invalid CLI arguments. Use --help; never pass credentials.\n")

def require(ok,code):
    if not ok: raise Invalid(code)

def positive(value):
    return type(value) is int and 0<value<2147483648

def string(value,maximum=300):
    return isinstance(value,str) and 0<len(value)<=maximum and bool(value.strip()) and '\x00' not in value

def exact_keys(obj,allowed):
    require(isinstance(obj,dict) and set(obj)<=set(allowed),'unknown_or_private_fields')

def encoded(value):
    return json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()

def digest(value):return hashlib.sha256(encoded(value)).hexdigest()

def source_url(url):
    require(string(url,1000),'evidence_url')
    parsed=urlparse(url)
    require(parsed.scheme=='https' and parsed.hostname and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment,'evidence_url')

def fetch(ids):
    data=encoded({'query':QUERY,'variables':{'id':ids.get('anilist_id'),'mal':ids.get('mal_id') if not ids.get('anilist_id') else None}})
    request=urllib.request.Request('https://graphql.anilist.co',data=data,headers={'Content-Type':'application/json','User-Agent':'AnimeVault-Catalog-Importer/1'})
    try:
        with urllib.request.urlopen(request,timeout=25) as response:
            raw=response.read(1024*1024+1)
        require(len(raw)<=1024*1024,'upstream_size')
        result=json.loads(raw)
        require(isinstance(result,dict),'upstream_payload')
        require(not result.get('errors'),'upstream_graphql_error')
        require(isinstance(result.get('data'),dict),'upstream_payload')
        return result['data']['Media']
    except urllib.error.HTTPError as exc:
        raise Invalid('upstream_rate_limited' if exc.code==429 else 'upstream_http_'+str(exc.code)) from None
    except (OSError,KeyError,ValueError,TypeError):
        raise Invalid('upstream_unavailable') from None

def build_record(item,media):
    exact_keys(item,['anilist_id','mal_id','aliases','episodes'])
    require(positive(item.get('anilist_id')) or positive(item.get('mal_id')),'canonical_id_required')
    for key in ['anilist_id','mal_id']:
        require(item.get(key) is None or positive(item[key]),'invalid_canonical_id')
    require(isinstance(media,dict) and positive(media.get('id')),'upstream_not_found')
    require(media.get('idMal') is None or positive(media['idMal']),'upstream_mal_id')
    require(item.get('anilist_id') in (None,media['id']) and item.get('mal_id') in (None,media.get('idMal')),'canonical_id_conflict')
    require(isinstance(media.get('title'),dict),'upstream_title')
    title=next((media.get('title',{}).get(k) for k in ['romaji','english','native'] if media.get('title',{}).get(k)),None)
    require(string(title),'upstream_title')
    out={'anilist_id':media['id'],'mal_id':media.get('idMal'),'title':title,'format':media.get('format'),'year':media.get('seasonYear'),'aliases':[],'episodes':[]}
    aliases=item.get('aliases',[])
    require(isinstance(aliases,list) and len(aliases)<=50,'aliases_size')
    seen=set()
    for alias in aliases:
        exact_keys(alias,['provider','external_id','anilist_id','mal_id','evidence_url','review_note'])
        require(alias.get('provider') in PROVIDERS and string(alias.get('external_id')),'provider_identity')
        require(positive(alias.get('anilist_id')) or positive(alias.get('mal_id')),'provider_metadata_id_required')
        require(alias.get('anilist_id') in (None,out['anilist_id']) and alias.get('mal_id') in (None,out['mal_id']),'provider_metadata_conflict')
        source_url(alias.get('evidence_url'))
        require(string(alias.get('review_note'),500),'review_note_required')
        key=(alias['provider'],alias['external_id']);require(key not in seen,'duplicate_alias');seen.add(key)
        out['aliases'].append(alias)
    episodes=item.get('episodes',[])
    require(isinstance(episodes,list) and len(episodes)<=2000,'episodes_size')
    keys=set();external=set()
    for ep in episodes:
        exact_keys(ep,['key','type','number','title','aliases'])
        require(string(ep.get('key'),100) and ep.get('type') in ['regular','ova','special','movie','fractional'],'episode_identity')
        require(ep.get('number') is None or positive(ep['number']),'episode_number')
        require(ep.get('title') is None or string(ep['title']),'episode_title')
        require(ep['key'] not in keys,'duplicate_episode_key');keys.add(ep['key'])
        require(isinstance(ep.get('aliases'),list) and 0<len(ep['aliases'])<=50,'episode_evidence_required')
        for a in ep['aliases']:
            exact_keys(a,['provider','release_id','external_episode_id','canonical_key','evidence_url','review_note'])
            require((a.get('provider'),a.get('release_id')) in seen,'episode_release_not_verified')
            require(string(a.get('external_episode_id')) and a.get('canonical_key')==ep['key'],'episode_mapping_conflict')
            source_url(a.get('evidence_url'));require(string(a.get('review_note'),500),'review_note_required')
            k=(a['provider'],a['release_id'],a['external_episode_id']);require(k not in external,'duplicate_external_episode');external.add(k)
        # Keys are explicit operator mappings, NEVER derived from episode numbers or AniList episode count.
        out['episodes'].append(ep)
    return out

SQL_BODY=r'''
DO $catalog$
DECLARE data jsonb := __DATA__::jsonb; x jsonb; a jsonb; e jsonb; ea jsonb; c uuid; ep uuid; n integer;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('animevault.catalog.import',1));
 FOR x IN SELECT value FROM jsonb_array_elements(data) LOOP
  SELECT count(*) INTO n FROM public.community_anime WHERE anilist_id=(x->>'anilist_id')::bigint OR mal_id=(x->>'mal_id')::bigint;
  IF n>1 THEN RAISE EXCEPTION 'catalog_canonical_conflict'; END IF;
  SELECT id INTO c FROM public.community_anime WHERE anilist_id=(x->>'anilist_id')::bigint OR mal_id=(x->>'mal_id')::bigint;
  IF c IS NULL THEN
   INSERT INTO public.community_anime(anilist_id,mal_id,canonical_title) VALUES((x->>'anilist_id')::bigint,(x->>'mal_id')::bigint,x->>'title') RETURNING id INTO c;
  ELSIF EXISTS(SELECT 1 FROM public.community_anime WHERE id=c AND (anilist_id IS DISTINCT FROM (x->>'anilist_id')::bigint OR mal_id IS DISTINCT FROM (x->>'mal_id')::bigint)) THEN
   RAISE EXCEPTION 'catalog_existing_ids_conflict';
  END IF;
  FOR a IN SELECT value FROM jsonb_array_elements(x->'aliases') LOOP
   INSERT INTO public.community_anime_aliases(anime_id,provider,external_id,verified) VALUES(c,a->>'provider',a->>'external_id',true) ON CONFLICT(provider,external_id) DO NOTHING;
   IF NOT EXISTS(SELECT 1 FROM public.community_anime_aliases WHERE provider=a->>'provider' AND external_id=a->>'external_id' AND anime_id=c AND verified) THEN RAISE EXCEPTION 'catalog_alias_conflict'; END IF;
  END LOOP;
  FOR e IN SELECT value FROM jsonb_array_elements(x->'episodes') LOOP
   INSERT INTO public.community_episodes(anime_id,episode_key,episode_type,episode_number,title) VALUES(c,e->>'key',e->>'type',(e->>'number')::integer,e->>'title') ON CONFLICT(anime_id,episode_key) DO NOTHING;
   SELECT id INTO ep FROM public.community_episodes WHERE anime_id=c AND episode_key=e->>'key' AND episode_type=e->>'type' AND episode_number IS NOT DISTINCT FROM (e->>'number')::integer;
   IF ep IS NULL THEN RAISE EXCEPTION 'catalog_episode_conflict'; END IF;
   FOR ea IN SELECT value FROM jsonb_array_elements(e->'aliases') LOOP
    INSERT INTO public.community_episode_aliases(provider,release_id,external_episode_id,episode_id,verified) VALUES(ea->>'provider',ea->>'release_id',ea->>'external_episode_id',ep,true) ON CONFLICT(provider,release_id,external_episode_id) DO NOTHING;
    IF NOT EXISTS(SELECT 1 FROM public.community_episode_aliases WHERE provider=ea->>'provider' AND release_id=ea->>'release_id' AND external_episode_id=ea->>'external_episode_id' AND episode_id=ep AND verified) THEN RAISE EXCEPTION 'catalog_episode_alias_conflict'; END IF;
   END LOOP;
  END LOOP;
 END LOOP;
END $catalog$;
'''

def render(plan,approval):
    exact_keys(plan,['version','records','errors'])
    require(plan.get('version')==1 and not plan.get('errors') and isinstance(plan.get('records'),list) and 0<len(plan['records'])<=100,'invalid_plan')
    require(approval==digest(plan),'approval_hash_mismatch')
    # Revalidate even a modified plan; approval is operator sign-off, not a signature of AniList authenticity.
    for r in plan['records']:
        media={'id':r['anilist_id'],'idMal':r['mal_id'],'title':{'romaji':r['title']},'format':r.get('format'),'seasonYear':r.get('year')}
        expected=build_record({'anilist_id':r['anilist_id'],'mal_id':r['mal_id'],'aliases':r['aliases'],'episodes':r['episodes']},media)
        require(r==expected,'plan_fields')
    literal="'"+encoded(plan['records']).decode().replace("'","''")+"'"
    delimiter='$catalog$'
    while delimiter in literal: delimiter=delimiter[:-1]+'_x$'
    body=SQL_BODY.replace('$catalog$',delimiter).replace('__DATA__',literal)
    return '-- Operator-approved plan SHA256: '+approval+'\nBEGIN;\nSET LOCAL standard_conforming_strings=on;\n'+body+'\nCOMMIT;\n'

def main():
    parser=SafeParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('plan');p.add_argument('manifest',type=Path);p.add_argument('--out',type=Path,required=True);p.add_argument('--journal',type=Path,required=True)
    p=sub.add_parser('sql');p.add_argument('plan',type=Path);p.add_argument('--approve-plan-sha256',required=True);p.add_argument('--out',type=Path,required=True)
    args=parser.parse_args()
    try:
        path=args.manifest if args.command=='plan' else args.plan
        require(path.stat().st_size<=2*1024*1024,'input_too_large')
        value=json.loads(path.read_text())
        if args.command=='sql':
            args.out.write_text(render(value,args.approve_plan_sha256));print('SQL prepared only; nothing sent to Supabase.');return
        require(isinstance(value,list) and 0<len(value)<=100,'manifest_size')
        result={'version':1,'records':[],'errors':[]}
        for i,item in enumerate(value):
            try:
                exact_keys(item,['anilist_id','mal_id','aliases','episodes'])
                require(positive(item.get('anilist_id')) or positive(item.get('mal_id')),'canonical_id_required')
                record=build_record(item,fetch(item))
                require(not any(r['anilist_id']==record['anilist_id'] or (record['mal_id'] is not None and r['mal_id']==record['mal_id']) for r in result['records']),'duplicate_canonical')
                result['records'].append(record)
            except Invalid as exc:result['errors'].append({'index':i,'code':str(exc)})
            time.sleep(0.8)
        args.out.write_text(json.dumps(result,ensure_ascii=False,indent=2))
        with args.journal.open('a') as log:
            log.write(json.dumps({'plan_sha256':digest(result),'records':len(result['records']),'errors':result['errors']})+'\n')
        print('Plan SHA256: '+digest(result));print('Records: '+str(len(result['records']))+'; errors: '+str(len(result['errors'])))
        if result['errors']:sys.exit(1)
    except (Invalid,OSError,ValueError,KeyError,TypeError) as exc:
        print('Import stopped: '+(str(exc) if isinstance(exc,Invalid) else 'invalid_input_or_io') ,file=sys.stderr);sys.exit(1)

if __name__=='__main__':main()
