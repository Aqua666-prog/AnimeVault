#!/usr/bin/env python3
"""Bounded, approval-bound publisher using the existing queue, HTTP and sync RPC."""
import argparse
import json
import os
from pathlib import Path
import uuid
import autosync as a
import canary as c

LIMITS=(5,10,25,50,100)


def require(ok,code):
    if not ok:raise a.Failure('batch_'+code)


def checkpoint(path):
    if not Path(path).exists():return {}
    value=json.loads(Path(path).read_text())
    require(isinstance(value,dict),'checkpoint_shape')
    return value


def refresh(items,http):
    """Only primary Kodik -> explicit MAL link -> AniList; never title matching."""
    require(isinstance(items,list) and 1<=len(items)<=2,'item_shape')
    anime=items[0];require(isinstance(anime,dict) and set(anime)=={'kind','anilist_id','mal_id','title'} and anime.get('kind')=='anime','canonical_shape')
    aid=anime['anilist_id'];mal=anime['mal_id']
    require(a.positive(aid)==aid and a.positive(mal)==mal,'canonical_ids_required')
    media=c.request(http,'anilist','batch_identity','https://graphql.anilist.co',{'query':a.LOOKUP,'variables':{'id':aid}})['data']['Media']
    require(isinstance(media,dict) and a.positive(media.get('id'))==aid and a.positive(media.get('idMal'))==mal,'canonical_identity_changed')
    if len(items)==1:return items
    require(len(items)==2,'pair_size')
    link=items[1];proof=link.get('evidence',{})
    require(link.get('kind')=='link' and link.get('status')=='verified' and link.get('anilist_id')==aid and a.client_contract(link),'client_contract')
    require(proof.get('client_identity')==a.client_identity('kodik',link.get('external_id')),'client_request')
    require(link.get('provider')=='kodik' and proof.get('method')=='external_link_chain' and proof.get('mal_id')==mal,'unsupported_primary_proof')
    shiki=a.positive(proof.get('shikimori_id'))
    require(shiki and link['external_id']=='shiki:'+str(shiki),'shikimori_identity')
    url=f'https://shikimori.io/api/animes/{shiki}/external_links'
    require(proof.get('url')==url,'chain_endpoint')
    links=c.request(http,'shikimori','batch_chain',url)
    require(isinstance(links,list),'chain_shape')
    mals={a.mal_link(r.get('url','')) for r in links if isinstance(r,dict) and r.get('kind')=='myanimelist'}-{None}
    require(mals=={mal},'chain_unconfirmed')
    token=os.environ.get('KODIK_API_TOKEN','');require(bool(token),'kodik_token_missing')
    query=a.urllib.parse.urlencode({'token':token,'shikimori_id':shiki,'limit':50,'types':'anime,anime-serial'})
    result=c.request(http,'kodik','batch_primary','https://kodik-api.com/search?'+query,{},form=True)
    rows=result.get('results')
    require(isinstance(rows,list) and 1<=len(rows)<=50,'primary_missing')
    require(all(isinstance(r,dict) and a.positive(r.get('shikimori_id'))==shiki and a.kodik_reference(r)==link['external_id'] for r in rows),'primary_conflict')
    require(any(r.get('id')==proof['observed_identity'].get('id') for r in rows),'primary_observation_stale')
    return items


def snapshot(items,http,headers):
    anime=items[0];aid=anime['anilist_id'];mal=anime['mal_id']
    by_id=c.select(http,'community_anime',{'anilist_id':'eq.'+str(aid)},'id,anilist_id,mal_id',headers,'batch_preflight')
    by_mal=c.select(http,'community_anime',{'mal_id':'eq.'+str(mal)},'id,anilist_id,mal_id',headers,'batch_preflight')
    require(not by_id or by_id.get('mal_id') in {None,mal},'canonical_conflict')
    require(not by_mal or by_id and by_mal.get('id')==by_id.get('id'),'mal_conflict')
    alias=None
    if len(items)==2:
        link=items[1]
        alias=c.select(http,'community_anime_aliases',{'provider':'eq.'+link['provider'],'external_id':'eq.'+link['external_id']},'anime_id,verified',headers,'batch_preflight')
        require(not alias or by_id and alias.get('anime_id')==by_id.get('id'),'alias_conflict')
    return {'canonical':by_id,'alias':alias}


def stats(state):
    r=a.report(state)
    return {k:r.get(k,0) for k in ('verified','pending','rejected')} | {'discovered':r['canonical'],'published':0,'resolved':0,'failed':0,'errors':[]}


def plan(state,http,limit,done):
    require(type(limit) is int and limit in LIMITS,'invalid_limit')
    groups=[];used=0;report=stats(state);attempts=0;selected=set()
    # Prefer client aliases, then canonical-only records. Never publish a partial pair.
    links=state.db.execute("SELECT body FROM queue WHERE json_extract(body,'$.status')='verified' ORDER BY key")
    candidates=[];paired=set()
    for (body,) in links:
        link=json.loads(body)
        if link.get('provider')!='kodik' or not a.client_contract(link):continue
        row=state.db.execute('SELECT body FROM canonical WHERE id=?',(link.get('anilist_id'),)).fetchone()
        if row:
            anime=json.loads(row[0]);candidates.append([anime,link]);paired.add(anime['anilist_id'])
    for (body,) in state.db.execute('SELECT body FROM canonical ORDER BY id'):
        anime=json.loads(body)
        if anime['anilist_id'] not in paired and a.positive(anime.get('mal_id')):candidates.append([anime])
    for items in candidates:
        digest=c.fingerprint(items)
        if done.get(digest)=='resolved' or items[0]['anilist_id'] in selected:continue
        if used+len(items)>limit:continue
        attempts+=1
        if attempts>max(20,limit*2):break
        try:
            refresh(items,http);before=snapshot(items,http,c.public_headers())
            groups.append({'items':items,'digest':digest,'before':before});used+=len(items);selected.add(items[0]['anilist_id'])
        except (a.Failure,KeyError,TypeError,ValueError) as exc:
            report['failed']+=1;report['errors'].append({'anilist_id':items[0]['anilist_id'],'error':str(exc) if isinstance(exc,a.Failure) else 'invalid_payload','diagnostic':getattr(exc,'diagnostic',None)})
        if used==limit:break
    value={'version':1,'limit':limit,'groups':groups,'records':used,'approval_run':{'id':os.getenv('GITHUB_RUN_ID'),'attempt':os.getenv('GITHUB_RUN_ATTEMPT')},'report':report}
    value['plan_hash']=c.fingerprint(value)
    return value


def resolve(items,http):
    anime=items[0];headers=c.public_headers()
    payload=a.client_identity(None,None,anime['mal_id'],anime['anilist_id'])
    expected=c.request(http,'supabase','batch_resolve_canonical',a.PROJECT+'/rest/v1/rpc/community_resolve',payload,headers)
    require(isinstance(expected,dict) and bool(expected.get('anime_id')) and expected.get('episode_id') is None,'resolver_canonical')
    if len(items)==2:
        actual=c.request(http,'supabase','batch_resolve_android',a.PROJECT+'/rest/v1/rpc/community_resolve',items[1]['evidence']['client_identity'],headers)
        require(isinstance(actual,dict) and actual.get('anime_id')==expected['anime_id'] and actual.get('episode_id') is None,'resolver_alias')


def publish(value,http,expected,done,path):
    require(os.getenv('GITHUB_REF')=='refs/heads/main' and os.getenv('GITHUB_EVENT_NAME')=='workflow_dispatch','trusted_dispatch')
    require(os.getenv('COMMUNITY_AUTOSYNC_WRITE_ENABLED')=='true' and os.getenv('COMMUNITY_AUTOSYNC_DAILY_WRITE')!='true','writes_disabled_or_daily_enabled')
    limit=value.get('limit');require(type(limit) is int and limit in LIMITS,'invalid_limit')
    require(os.getenv('BATCH_CONFIRMATION')=='PUBLISH BATCH '+str(limit),'confirmation')
    require(value.get('approval_run')=={'id':os.getenv('GITHUB_RUN_ID'),'attempt':os.getenv('GITHUB_RUN_ATTEMPT')},'approval_run')
    require(expected and value.get('plan_hash')==expected and c.fingerprint({k:v for k,v in value.items() if k!='plan_hash'})==expected,'plan_hash')
    groups=value.get('groups');require(isinstance(groups,list) and sum(len(g['items']) for g in groups)==value.get('records')<=limit,'record_limit')
    report=dict(value['report']);report['errors']=list(report['errors']);report['status']='success';sent=0
    headers=c.admin_headers();c.public_headers()
    for group in groups:
        items=group['items'];digest=c.fingerprint(items)
        require(digest==group['digest'],'group_hash')
        if done.get(digest)=='resolved':continue
        try:
            refresh(items,http)
            require(snapshot(items,http,c.public_headers())==group['before'],'state_changed_replan')
            private=snapshot(items,http,headers)
            print(a.packed({'batch_dry_run':items,'before':private}),flush=True)
            require(sent+len(items)<=limit,'record_limit')
            # Transport retries MUST NOT multiply the actual records submitted this run.
            http.max_attempts=1
            sent+=len(items)
            result=c.request(http,'supabase','batch_publish',a.PROJECT+'/rest/v1/rpc/community_sync_batch',{'p_batch':str(uuid.uuid5(uuid.NAMESPACE_URL,'animevault-safe-batch-v1:'+digest)),'p_items':items},headers)
            require(isinstance(result,dict) and result.get('processed')==len(items) and result.get('canonical_upserted')==1 and result.get('verified_applied')==len(items)-1 and result.get('rejected')==0 and result.get('pending')==0,'rpc_rejected_or_conflict')
            report['published']+=len(items)
            resolve(items,http);report['resolved']+=len(items)
            done[digest]='resolved'
            temp=Path(str(path)+'.tmp');temp.write_text(a.packed(done));temp.replace(path)
        except (a.Failure,KeyError,TypeError,ValueError) as exc:
            report['failed']+=len(items);report['status']='blocked'
            diagnostic=getattr(exc,'diagnostic',None)
            report['errors'].append({'anilist_id':items[0]['anilist_id'],'error':str(exc) if isinstance(exc,a.Failure) else 'invalid_payload','diagnostic':diagnostic})
            # Stop on uncertainty: receipts make the next run safe to retry.
            break
    report['submitted']=sent
    return report


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['plan','publish']);p.add_argument('--max-publish-items',type=int,choices=LIMITS,default=5);p.add_argument('--expected-plan');args=p.parse_args()
    report={'status':'blocked','error':'batch_not_completed'}
    try:
        require(os.getenv('GITHUB_REF')=='refs/heads/main' and os.getenv('GITHUB_EVENT_NAME')=='workflow_dispatch','trusted_dispatch')
        done=checkpoint('batch-checkpoint.json');http=a.Http()
        if args.command=='plan':
            require(Path('autosync.sqlite').is_file(),'discovery_missing')
            state=a.State('autosync.sqlite')
            try:value=plan(state,http,args.max_publish_items,done)
            finally:state.db.close()
            Path('batch-plan.json').write_text(a.packed(value));report={**value['report'],'status':'dry-run','records':value['records'],'groups':value['groups']}
            if os.getenv('GITHUB_OUTPUT'):
                with open(os.environ['GITHUB_OUTPUT'],'a') as f:f.write('plan_hash='+value['plan_hash']+'\n')
        else:report=publish(json.loads(Path('batch-plan.json').read_text()),http,args.expected_plan,done,'batch-checkpoint.json')
    except (a.Failure,ValueError,KeyError,TypeError) as exc:report={'status':'blocked','error':str(exc) if isinstance(exc,a.Failure) else 'invalid_payload'}
    Path('batch-report.json').write_text(a.packed(report));print(a.packed(report))
    if os.getenv('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as f:f.write('```json\n'+a.packed(report)+'\n```\n')
    if report['status'] not in {'success','dry-run'}:raise SystemExit(1)

if __name__=='__main__':main()
