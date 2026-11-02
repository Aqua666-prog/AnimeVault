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


def save_checkpoint(path,done):
    temp=Path(str(path)+'.tmp');temp.write_text(a.packed(done));temp.replace(path)


def ids(record,*fields):
    require(isinstance(record,dict),'provider_shape')
    values=[record[k] for k in fields if record.get(k) not in (None,'')]
    require(all(a.positive(v) is not None for v in values),'provider_identifier_invalid')
    parsed={a.positive(v) for v in values}
    require(len(parsed)<=1,'provider_identifier_conflict')
    return next(iter(parsed),None)


def checked_media(anime,http):
    require(isinstance(anime,dict) and set(anime)=={'kind','anilist_id','mal_id','title'} and anime.get('kind')=='anime','canonical_shape')
    aid=anime['anilist_id'];mal=anime['mal_id']
    require(isinstance(anime.get('title'),str) and 1<=len(anime['title'])<=300,'canonical_title')
    require(type(aid) is int and type(mal) is int and a.positive(aid)==aid and a.positive(mal)==mal,'canonical_ids_required')
    result=c.request(http,'anilist','batch_identity','https://graphql.anilist.co',{'query':a.LOOKUP,'variables':{'id':aid}})
    media=(result.get('data') or {}).get('Media') if isinstance(result,dict) and isinstance(result.get('data'),dict) else None
    require(isinstance(media,dict) and a.positive(media.get('id'))==aid and a.positive(media.get('idMal'))==mal,'canonical_identity_changed_or_missing')


def chain(shiki,mal,http):
    url=f'https://shikimori.io/api/animes/{shiki}/external_links'
    links=c.request(http,'shikimori','batch_chain',url)
    require(isinstance(links,list),'chain_shape')
    mals={a.mal_link(r.get('url','')) for r in links if isinstance(r,dict) and r.get('kind')=='myanimelist'}-{None}
    require(mals=={mal},'chain_unconfirmed')
    return url


def kodik_rows(shiki,http):
    token=os.environ.get('KODIK_API_TOKEN','');require(bool(token),'kodik_token_missing')
    query=a.urllib.parse.urlencode({'token':token,'shikimori_id':shiki,'limit':50,'types':'anime,anime-serial'})
    result=c.request(http,'kodik','batch_primary','https://kodik-api.com/search?'+query,{},form=True)
    rows=result.get('results') if isinstance(result,dict) else None
    require(isinstance(rows,list) and len(rows)<=50,'primary_shape')
    require(all(isinstance(r,dict) and a.positive(r.get('shikimori_id'))==shiki and a.kodik_reference(r)=='shiki:'+str(shiki) for r in rows),'primary_conflict')
    return rows


def refresh(items,http):
    require(isinstance(items,list) and 1<=len(items)<=2,'item_shape')
    anime=items[0];checked_media(anime,http)
    if len(items)==1:return items
    aid=anime['anilist_id'];mal=anime['mal_id'];link=items[1];require(isinstance(link,dict),'link_shape');proof=link.get('evidence',{});require(isinstance(proof,dict),'proof_shape')
    provider=link.get('provider');external=link.get('external_id')
    require(proof.get('source')==provider and proof.get('method') in {'direct_id','external_link_chain'} and len(a.packed(proof).encode())<=8000,'proof_contract')
    require(link.get('kind')=='link' and link.get('status')=='verified' and link.get('anilist_id')==aid and a.client_contract(link),'client_contract')
    require(provider in {'kodik','animetka','yummy','aniliberty'},'unsupported_primary_proof')
    if provider=='kodik':
        require(proof.get('client_identity')==a.client_identity('kodik',external),'client_request')
        require(proof.get('method')=='external_link_chain' and proof.get('mal_id')==mal,'unsupported_primary_proof')
        shiki=a.positive(proof.get('shikimori_id'))
        require(shiki and external=='shiki:'+str(shiki),'shikimori_identity')
        url=f'https://shikimori.io/api/animes/{shiki}/external_links'
        provenance=proof.get('provenance') or {}
        legacy=(provenance.get('method')=='animetka_kodik_material' and a.positive(provenance.get('animetka_id')) and provenance.get('material_id')==proof['observed_identity'].get('id') and proof.get('url')=='https://animetka.com/api/anime/'+str(provenance['animetka_id']))
        require(proof.get('url')==url or legacy,'chain_endpoint')
        chain(shiki,mal,http);rows=kodik_rows(shiki,http)
        require(bool(rows),'primary_missing')
        require(any(r.get('id')==proof['observed_identity'].get('id') for r in rows),'primary_observation_stale')
    elif provider=='aniliberty':
        require(a.positive(external) is not None,'provider_id')
        provenance=proof.get('provenance') or {}
        require(provenance.get('method')=='animetka_anilibria_mapping' and a.positive(provenance.get('animetka_id')) and str(provenance.get('release_id'))==external,'bridge_proof')
        detail=a.animetka_detail(c.request(http,'animetka','batch_bridge','https://animetka.com/api/anime/'+str(provenance['animetka_id']),headers={'Referer':'https://animetka.com/'}))
        require(str(detail.get('id'))==str(provenance['animetka_id']),'bridge_identity')
        shiki=ids(detail,'shikimori_id','shikimoriId');require(bool(shiki),'bridge_ids_missing')
        require(ids(detail,'mal_id','myanimelist_id','malId') in {None,mal},'bridge_mal_conflict')
        require(ids(detail,'anilist_id','anilistId') in {None,aid},'bridge_anilist_conflict')
        chain(shiki,mal,http)
        mapping=detail.get('AnilibriaMapping') or {};require(isinstance(mapping,dict),'bridge_mapping_shape');episodes=mapping.get('episodes') or [];require(isinstance(episodes,list) and all(isinstance(e,dict) for e in episodes),'bridge_episode_shape')
        require(str(mapping.get('animetka_id'))==str(provenance['animetka_id']),'bridge_owner')
        releases={a.positive(e.get('release_id')) for e in episodes if isinstance(e,dict)}-{None}
        require(releases=={int(external)},'bridge_release_conflict')
        expected={e.get('id') for e in episodes if isinstance(e,dict) and e.get('id')}
        release=c.request(http,'aniliberty','batch_primary','https://aniliberty.top/api/v1/anime/releases/'+external)
        require(isinstance(release,dict) and str(release.get('id'))==external and isinstance(release.get('episodes'),list),'bridge_primary_shape')
        actual={e.get('id') for e in release['episodes'] if isinstance(e,dict) and e.get('id')}
        require(bool(actual) and actual.issubset(expected),'bridge_episode_identity')
        require(proof.get('client_identity')==a.client_identity('aniliberty',external),'client_request')
    else:
        require(a.positive(external) is not None,'provider_id')
        if provider=='animetka':
            result=c.request(http,provider,'batch_primary','https://animetka.com/api/anime/'+external,headers={'Referer':'https://animetka.com/'})
            detail=a.animetka_detail(result)
            actual=ids(detail,'id','material_id','animetka_id');actual=str(actual) if actual else None
            require(actual==external,'provider_identity')
            direct_mal=ids(detail,'mal_id','myanimelist_id','malId')
            direct_aid=ids(detail,'anilist_id','anilistId')
            shiki=ids(detail,'shikimori_id','shikimoriId')
        else:
            key=os.environ.get('YUMMY_APPLICATION_KEY','');require(bool(key),'yummy_key_missing')
            result=c.request(http,provider,'batch_primary','https://api.yani.tv/anime/'+external,headers={'X-Application':key,'Lang':'ru','Referer':'https://yummyani.me/'})
            detail=result.get('response') if isinstance(result,dict) else None
            require(isinstance(detail,dict) and str(detail.get('anime_id'))==external,'provider_identity')
            remote=detail.get('remote_ids') or {};require(isinstance(remote,dict),'provider_shape')
            direct_mal=ids(remote,'myanimelist_id');shiki=ids(remote,'shikimori_id');direct_aid=None
        require(not direct_mal or direct_mal==mal,'provider_mal_conflict')
        require(not direct_aid or direct_aid==aid,'provider_anilist_conflict')
        require(bool(direct_mal or shiki),'provider_ids_missing')
        if shiki:chain(shiki,mal,http)
        # These are the exact fields Android passes from the primary detail response.
        expected=a.client_identity(provider,external,direct_mal,direct_aid) if provider=='animetka' else a.client_identity(provider,external,direct_mal)
        require(proof.get('client_identity')==expected,'client_request')
    return items


def discover_kodik(anime,http):
    # Querying a MAL number as a candidate Shikimori ID is NOT proof of equality.
    # A primary Kodik record AND its explicit Shikimori MAL link are mandatory.
    mal=anime['mal_id'];rows=kodik_rows(mal,http)
    if not rows:return None
    url=chain(mal,mal,http)
    observed={k:rows[0].get(k) for k in ('id','shikimori_id','kinopoisk_id')}
    external=a.kodik_reference(rows[0])
    return {'kind':'link','provider':'kodik','external_id':external,'anilist_id':anime['anilist_id'],'status':'verified','evidence':{'source':'kodik','method':'external_link_chain','shikimori_id':mal,'mal_id':mal,'url':url,'client_contract':'android-release-id-v1','client_identity':a.client_identity('kodik',external),'observed_identity':observed}}


def snapshot(items,http,headers):
    anime=items[0];aid=anime['anilist_id'];mal=anime['mal_id']
    by_id=c.select(http,'community_anime',{'anilist_id':'eq.'+str(aid)},'id,anilist_id,mal_id',headers,'batch_preflight')
    by_mal=c.select(http,'community_anime',{'mal_id':'eq.'+str(mal)},'id,anilist_id,mal_id',headers,'batch_preflight')
    require(not by_id or by_id.get('anilist_id')==aid and bool(by_id.get('id')) and by_id.get('mal_id') in {None,mal},'canonical_conflict')
    require(not by_mal or by_id and by_mal.get('id')==by_id.get('id'),'mal_conflict')
    alias=None
    if len(items)==2:
        link=items[1]
        alias=c.select(http,'community_anime_aliases',{'provider':'eq.'+link['provider'],'external_id':'eq.'+link['external_id']},'anime_id,verified',headers,'batch_preflight')
        require(not alias or by_id and alias.get('anime_id')==by_id.get('id'),'alias_conflict')
    return {'canonical':by_id,'alias':alias}


def mutations(items,before):
    canonical=before.get('canonical')
    writes=[]
    # Existing stable ID pairs do not consume a write for a title refresh.
    if not canonical or canonical.get('mal_id') is None:writes.append(items[0])
    if len(items)==2 and not (before.get('alias') or {}).get('verified'):writes.append(items[1])
    return writes


def failure(report,items,exc):
    report['failed']+=1
    diagnostic=getattr(exc,'diagnostic',None)
    code=str(exc) if isinstance(exc,a.Failure) else 'invalid_payload'
    source=(diagnostic or {}).get('source') or ('shikimori' if 'chain_' in code else items[-1].get('provider') if len(items)==2 else 'anilist')
    diagnostic=diagnostic or {'source':source,'stage':'identity_validation','http_status':None,'error_type':'validation_failed'}
    report['provider_errors'][source]=report['provider_errors'].get(source,0)+1
    report['errors'].append({'anilist_id':items[0]['anilist_id'],'provider':source,'error':code,'diagnostic':diagnostic})


def stats(state):
    r=a.report(state)
    return {k:r.get(k,0) for k in ('verified','pending','rejected')} | {'discovered':r['canonical'],'published':0,'resolved':0,'canonical_resolved':0,'android_resolved':0,'failed':0,'errors':[],'new_canonical':0,'new_aliases':0,'canonical_updates':0,'alias_updates':0,'kodik_no_match':0,'existing':0,'existing_canonical':0,'existing_aliases':0,'ambiguous':r.get('pending',0),'rejected_candidates':r.get('rejected',0),'provider_errors':{}}


def plan(state,http,limit,done):
    require(type(limit) is int and limit in LIMITS,'invalid_limit')
    groups=[];used=0;report=stats(state);attempts=0;selected=set();seen_existing=set()
    # Prefer client aliases, then canonical-only records. Never publish a partial pair.
    links=state.db.execute("SELECT body FROM queue WHERE json_extract(body,'$.status')='verified' ORDER BY key")
    candidates=[];paired=set()
    for (body,) in links:
        link=json.loads(body)
        if link.get('provider') not in {'kodik','animetka','yummy','aniliberty'} or not a.client_contract(link):continue
        row=state.db.execute('SELECT body FROM canonical WHERE id=?',(link.get('anilist_id'),)).fetchone()
        if row:
            anime=json.loads(row[0]);candidates.append([anime,link]);paired.add(anime['anilist_id'])
    for (body,) in state.db.execute('SELECT body FROM canonical ORDER BY id'):
        anime=json.loads(body)
        if anime['anilist_id'] not in paired and a.positive(anime.get('mal_id')):candidates.append([anime])
    after=done.get('_scan_after')
    keys=[str(items[0]['anilist_id'])+':'+(items[1]['provider']+':'+items[1]['external_id'] if len(items)==2 else '') for items in candidates]
    if after in keys:
        index=keys.index(after)+1;candidates=candidates[index:]+candidates[:index]
    for items in candidates:
        digest=c.fingerprint(items)
        if items[0]['anilist_id'] in selected:continue
        attempts+=1
        if attempts>max(20,limit*2):break
        done['_scan_after']=str(items[0]['anilist_id'])+':'+(items[1]['provider']+':'+items[1]['external_id'] if len(items)==2 else '')
        try:
            before=snapshot(items,http,c.public_headers())
            if len(items)==1 and os.environ.get('KODIK_API_TOKEN'):
                try:alias=discover_kodik(items[0],http)
                except a.Failure as exc:
                    failure(report,items,exc)
                    if 'conflict' in str(exc):raise
                    alias=None
                if not alias:report['kodik_no_match']+=1
                if alias:
                    items=[items[0],alias];digest=c.fingerprint(items);before=snapshot(items,http,c.public_headers())
            for kind,row in before.items():
                if row:
                    key=(kind,items[0]['anilist_id'] if kind=='canonical' else items[1]['provider']+':'+items[1]['external_id'])
                    if key not in seen_existing:
                        report['existing']+=1;report['existing_canonical' if kind=='canonical' else 'existing_aliases']+=1;seen_existing.add(key)
            writes=mutations(items,before)
            if not writes:
                resolve(items,http);report['canonical_resolved']+=1;report['android_resolved']+=int(len(items)==2);continue
            if used+len(writes)>limit:continue
            refresh(items,http)
            groups.append({'items':items,'writes':writes,'digest':digest,'before':before});used+=len(writes);selected.add(items[0]['anilist_id'])
            report['new_canonical']+=int(before.get('canonical') is None and any(i['kind']=='anime' for i in writes))
            report['new_aliases']+=int(before.get('alias') is None and any(i['kind']=='link' for i in writes))
            report['canonical_updates']+=int(before.get('canonical') is not None and any(i['kind']=='anime' for i in writes))
            report['alias_updates']+=int(before.get('alias') is not None and any(i['kind']=='link' for i in writes))
        except (a.Failure,KeyError,TypeError,ValueError) as exc:
            failure(report,items,exc)
            if isinstance(exc,a.Failure) and any(word in str(exc) for word in ('conflict','unconfirmed')):report['rejected_candidates']+=1
            if isinstance(exc,a.Failure) and 'conflict' in str(exc):
                exc.batch_report={**report,'records':used,'groups':groups};raise
        if used==limit:break
    value={'version':2,'limit':limit,'groups':groups,'records':used,'approval_run':{'id':os.getenv('GITHUB_RUN_ID'),'attempt':os.getenv('GITHUB_RUN_ATTEMPT')},'report':report}
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
    require(value.get('version')==2,'plan_version')
    limit=value.get('limit');require(type(limit) is int and limit in LIMITS,'invalid_limit')
    require(os.getenv('BATCH_CONFIRMATION')=='PUBLISH BATCH '+str(limit),'confirmation')
    require(value.get('approval_run')=={'id':os.getenv('GITHUB_RUN_ID'),'attempt':os.getenv('GITHUB_RUN_ATTEMPT')},'approval_run')
    require(expected and value.get('plan_hash')==expected and c.fingerprint({k:v for k,v in value.items() if k!='plan_hash'})==expected,'plan_hash')
    groups=value.get('groups');require(isinstance(groups,list) and sum(len(g.get('writes',g['items'])) for g in groups)==value.get('records')<=limit,'record_limit')
    report=dict(value['report']);report['errors']=list(report['errors']);report['provider_errors']=dict(report['provider_errors']);report['status']='success';sent=0
    headers=c.admin_headers();c.public_headers()
    for group in groups:
        items=group['items'];digest=c.fingerprint(items)
        require(digest==group['digest'],'group_hash')
        try:
            if done.get(digest)=='resolved' and not mutations(items,snapshot(items,http,c.public_headers())):
                resolve(items,http);continue
            refresh(items,http)
            require(snapshot(items,http,c.public_headers())==group['before'],'state_changed_replan')
            private=snapshot(items,http,headers)
            writes=mutations(items,private)
            require(bool(writes) and writes==group.get('writes',items),'write_set_changed_replan')
            print(a.packed({'batch_dry_run':writes,'before':private}),flush=True)
            require(sent+len(writes)<=limit,'record_limit')
            # Transport retries MUST NOT multiply the actual records submitted this run.
            previous_attempts=getattr(http,'max_attempts',5)
            http.max_attempts=1
            sent+=len(writes)
            try:
                result=c.request(http,'supabase','batch_publish',a.PROJECT+'/rest/v1/rpc/community_sync_batch',{'p_batch':str(uuid.uuid5(uuid.NAMESPACE_URL,'animevault-safe-batch-v2:'+c.fingerprint(writes))),'p_items':writes},headers)
            finally:http.max_attempts=previous_attempts
            require(isinstance(result,dict) and result.get('processed')==len(writes) and result.get('canonical_upserted')==sum(i['kind']=='anime' for i in writes) and result.get('verified_applied')==sum(i['kind']=='link' for i in writes) and result.get('rejected')==0 and result.get('pending')==0,'rpc_rejected_or_conflict')
            report['published']+=len(writes)
            resolve(items,http);report['resolved']+=len(writes);report['canonical_resolved']+=1;report['android_resolved']+=int(len(items)==2)
            done[digest]='resolved'
            save_checkpoint(path,done)
        except (a.Failure,KeyError,TypeError,ValueError) as exc:
            report['failed']+=len(items);report['status']='blocked'
            diagnostic=getattr(exc,'diagnostic',None) or {'source':items[-1].get('provider','anilist'),'stage':'publish_validation','http_status':None,'error_type':'validation_failed'}
            report['provider_errors'][diagnostic['source']]=report['provider_errors'].get(diagnostic['source'],0)+1
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
            Path('batch-plan.json').write_text(a.packed(value));save_checkpoint('batch-checkpoint.json',done);report={**value['report'],'status':'dry-run','records':value['records'],'groups':value['groups']}
            if os.getenv('GITHUB_OUTPUT'):
                with open(os.environ['GITHUB_OUTPUT'],'a') as f:f.write('plan_hash='+value['plan_hash']+'\n')
        else:report=publish(json.loads(Path('batch-plan.json').read_text()),http,args.expected_plan,done,'batch-checkpoint.json')
    except (a.Failure,ValueError,KeyError,TypeError) as exc:report={**getattr(exc,'batch_report',{}),'status':'blocked','error':str(exc) if isinstance(exc,a.Failure) else 'invalid_payload'}
    Path('batch-report.json').write_text(a.packed(report));print(a.packed(report))
    if os.getenv('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as f:f.write('```json\n'+a.packed(report)+'\n```\n')
    if report['status'] not in {'success','dry-run'}:raise SystemExit(1)

if __name__=='__main__':main()
