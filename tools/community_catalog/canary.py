#!/usr/bin/env python3
"""One hard-coded canary only. No bulk publisher, SQL, episodes or fallback IDs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import uuid

import autosync as a

ANILIST=195604
MAL=61967
PROVIDER='kodik'
EXTERNAL='shiki:61967'
CONFIRMATION='PUBLISH CANARY 195604'
SHIKI=f'https://shikimori.io/api/animes/{MAL}/external_links'
CANON_KEY='anime:'+str(ANILIST)
ALIAS_KEY='link:'+PROVIDER+':'+EXTERNAL


def require(ok,code):
    if not ok:raise a.Failure('canary_'+code)


def fingerprint(value):return hashlib.sha256(a.packed(value).encode()).hexdigest()


def evidence_from_discovery(state):
    rows={key:json.loads(body) for key,body in state.db.execute('SELECT key,body FROM queue WHERE key IN (?,?)',(CANON_KEY,ALIAS_KEY))}
    require(set(rows)=={CANON_KEY,ALIAS_KEY},'discovery_target_missing_run_discovery_first')
    anime,alias=rows[CANON_KEY],rows[ALIAS_KEY]
    validate_pair([anime,alias])
    cached=state.db.execute('SELECT id,mal FROM canonical WHERE id=? OR mal=?',(ANILIST,MAL)).fetchall()
    require(cached==[(ANILIST,MAL)],'discovery_canonical_conflict')
    return [anime,alias]


def validate_pair(items):
    require(isinstance(items,list) and len(items)==2,'exactly_two_items_required')
    anime,alias=items
    require(isinstance(anime,dict) and set(anime)=={'kind','anilist_id','mal_id','title'},'canonical_fields')
    require(anime['kind']=='anime' and type(anime['anilist_id']) is int and anime['anilist_id']==ANILIST and type(anime['mal_id']) is int and anime['mal_id']==MAL,'canonical_identity')
    require(isinstance(anime['title'],str) and 1<=len(anime['title'])<=300,'canonical_title')
    require(isinstance(alias,dict) and set(alias)=={'kind','provider','external_id','anilist_id','status','evidence'},'alias_fields')
    require(alias['kind']=='link' and alias['provider']==PROVIDER and alias['external_id']==EXTERNAL and type(alias['anilist_id']) is int and alias['anilist_id']==ANILIST and alias['status']=='verified','alias_identity_or_status')
    proof=alias['evidence']
    require(isinstance(proof,dict) and a.client_contract(alias),'android_contract_evidence_missing')
    require(proof.get('source')==PROVIDER and proof.get('method')=='external_link_chain' and proof.get('shikimori_id')==MAL and proof.get('mal_id')==MAL and proof.get('url')==SHIKI,'direct_chain_evidence_missing')
    require(proof.get('client_identity')==a.client_identity(PROVIDER,EXTERNAL),'android_request_mismatch')
    observed=proof.get('observed_identity')
    require(isinstance(observed,dict) and isinstance(observed.get('id'),str) and bool(observed['id'].strip()) and a.positive(observed.get('shikimori_id'))==MAL and a.kodik_reference(observed)==EXTERNAL,'primary_kodik_observation_missing')
    require(len(a.packed(proof).encode())<=8000,'proof_too_large')


def refresh_pair(items,http):
    """Fresh, narrowly targeted public reads. Cached evidence alone is insufficient to write."""
    validate_pair(items)
    token=os.environ.get('KODIK_API_TOKEN','')
    require(bool(token),'kodik_token_missing')
    media=http.request('https://graphql.anilist.co',{'query':a.LOOKUP,'variables':{'id':None,'mal':MAL}})['data']['Media']
    require(isinstance(media,dict) and a.positive(media.get('id'))==ANILIST and a.positive(media.get('idMal'))==MAL,'live_anilist_identity_mismatch')
    title=next((media.get('title',{}).get(k) for k in ('romaji','english','native') if media.get('title',{}).get(k)),None)
    require(isinstance(title,str) and 1<=len(title)<=300,'live_title_invalid')
    links=http.request(SHIKI)
    require(isinstance(links,list),'live_shikimori_shape')
    mals={a.mal_link(link.get('url','')) for link in links if isinstance(link,dict) and link.get('kind')=='myanimelist'}-{None}
    require(mals=={MAL},'live_shikimori_chain_unconfirmed')
    query=a.urllib.parse.urlencode({'token':token,'shikimori_id':MAL,'limit':50,'types':'anime,anime-serial'})
    result=http.request('https://kodik-api.com/search?'+query,{},form=True)
    rows=result.get('results')
    require(isinstance(rows,list) and 1<=len(rows)<=50,'live_kodik_results_missing')
    require(all(isinstance(row,dict) and a.positive(row.get('shikimori_id'))==MAL and a.kodik_reference(row)==EXTERNAL for row in rows),'live_kodik_identity_mismatch')
    observed_id=items[1]['evidence']['observed_identity']['id']
    matching=[row for row in rows if row.get('id')==observed_id]
    require(bool(matching),'discovery_observation_stale_run_discovery_again')
    observed={key:matching[0].get(key) for key in ('id','shikimori_id','kinopoisk_id')}
    proof={'source':PROVIDER,'method':'external_link_chain','shikimori_id':MAL,'url':SHIKI,'mal_id':MAL,
           'client_contract':'android-release-id-v1','client_identity':a.client_identity(PROVIDER,EXTERNAL),'observed_identity':observed}
    fresh=[{'kind':'anime','anilist_id':ANILIST,'mal_id':MAL,'title':title},
           {'kind':'link','provider':PROVIDER,'external_id':EXTERNAL,'anilist_id':ANILIST,'status':'verified','evidence':proof}]
    validate_pair(fresh)
    return fresh


def public_headers():
    key=os.environ.get('SUPABASE_PUBLISHABLE_KEY','')
    require(key.startswith('sb_publishable_'),'publishable_key_missing')
    return {'apikey':key}


def admin_headers():
    key=os.environ.get('SUPABASE_SERVICE_ROLE_KEY','')
    require(bool(key),'service_role_secret_missing')
    return {'apikey':key,'Authorization':'Bearer '+key}


def select(http,table,filters,columns,headers):
    query=a.urllib.parse.urlencode({**filters,'select':columns,'limit':'2'})
    result=http.request(a.PROJECT+'/rest/v1/'+table+'?'+query,headers=headers)
    require(isinstance(result,list) and len(result)<=1,'remote_duplicate_or_response_shape')
    return result[0] if result else None


def remote_snapshot(items,http,headers,admin=False):
    validate_pair(items)
    by_id=select(http,'community_anime',{'anilist_id':'eq.'+str(ANILIST)},'id,anilist_id,mal_id,canonical_title',headers)
    by_mal=select(http,'community_anime',{'mal_id':'eq.'+str(MAL)},'id,anilist_id,mal_id,canonical_title',headers)
    alias=select(http,'community_anime_aliases',{'provider':'eq.'+PROVIDER,'external_id':'eq.'+EXTERNAL},'anime_id,provider,external_id,verified',headers)
    if by_id:
        require(by_id.get('anilist_id')==ANILIST and by_id.get('mal_id') in {None,MAL} and bool(by_id.get('id')),'remote_canonical_conflict')
    if by_mal:require(by_id is not None and by_id['id']==by_mal.get('id') and by_mal.get('anilist_id')==ANILIST,'remote_mal_conflict')
    if alias:require(by_id is not None and alias.get('anime_id')==by_id['id'] and alias.get('provider')==PROVIDER and alias.get('external_id')==EXTERNAL,'remote_alias_conflict')
    canonical_action='insert' if by_id is None else 'no-op' if by_id.get('mal_id')==MAL and by_id.get('canonical_title')==items[0]['title'] else 'update-own-canonical'
    alias_action='insert' if alias is None else 'no-op' if alias.get('verified') is True else 'verify-existing-same-anime'
    return {'canonical':by_id,'alias':alias,'visibility':'service-role-all-aliases' if admin else 'public-verified-aliases-only',
            'changes':[{'table':'community_anime','anilist_id':ANILIST,'mal_id':MAL,'canonical_title':items[0]['title'],'action':canonical_action},
                       {'table':'community_anime_aliases','provider':PROVIDER,'external_id':EXTERNAL,'anilist_id':ANILIST,'verified':True,'action':alias_action}]}


def plan(state,http):
    discovery=evidence_from_discovery(state)
    items=refresh_pair(discovery,http)
    before=remote_snapshot(items,http,public_headers())
    result={'version':1,'approval_run':{'id':os.environ.get('GITHUB_RUN_ID'),'attempt':os.environ.get('GITHUB_RUN_ATTEMPT')},'target_anilist':ANILIST,'max_canonical':1,'max_aliases':1,'items':items,
            'discovery_hash':fingerprint(discovery),'discovery_run':os.environ.get('DISCOVERY_RUN_ID'),'before':before,'confirmation':CONFIRMATION}
    result['plan_hash']=fingerprint(result)
    return result


def validate_plan(value,expected):
    require(isinstance(value,dict) and value.get('version')==1 and value.get('target_anilist')==ANILIST and value.get('max_canonical')==1 and value.get('max_aliases')==1,'plan_scope')
    validate_pair(value.get('items'))
    require(value.get('approval_run')=={'id':os.environ.get('GITHUB_RUN_ID'),'attempt':os.environ.get('GITHUB_RUN_ATTEMPT')},'approval_run_mismatch')
    stored=value.get('plan_hash');unsigned={k:v for k,v in value.items() if k!='plan_hash'}
    require(isinstance(expected,str) and stored==expected and fingerprint(unsigned)==expected,'plan_hash_changed')


def resolve_target(items,http):
    headers=public_headers()
    canonical={'p_anilist':ANILIST,'p_mal':MAL,'p_provider':None,'p_external':None,'p_episode_external':None}
    expected=http.request(a.PROJECT+'/rest/v1/rpc/community_resolve',canonical,headers)
    actual=http.request(a.PROJECT+'/rest/v1/rpc/community_resolve',items[1]['evidence']['client_identity'],headers)
    require(isinstance(expected,dict) and isinstance(actual,dict) and bool(expected.get('anime_id')) and actual.get('anime_id')==expected['anime_id'] and actual.get('episode_id') is None,'post_resolve_not_correct_canonical')
    row=select(http,'community_anime',{'id':'eq.'+expected['anime_id']},'id,anilist_id,mal_id,canonical_title',headers)
    require(row is not None and row.get('anilist_id')==ANILIST and row.get('mal_id')==MAL,'post_canonical_identity_mismatch')
    return {'status':'resolved','anime_id':expected['anime_id'],'anilist_id':ANILIST,'mal_id':MAL,'provider':PROVIDER,'external_id':EXTERNAL,'episode_id':None}


def publish_canary(value,http,expected):
    require(os.environ.get('GITHUB_REF')=='refs/heads/main' and os.environ.get('GITHUB_EVENT_NAME')=='workflow_dispatch','trusted_main_dispatch_required')
    require(os.environ.get('COMMUNITY_AUTOSYNC_WRITE_ENABLED')=='true','writes_disabled')
    require(os.environ.get('CANARY_CONFIRMATION')==CONFIRMATION,'explicit_confirmation_required')
    require(os.environ.get('COMMUNITY_AUTOSYNC_DAILY_WRITE')!='true','disable_daily_bulk_before_canary')
    validate_plan(value,expected)
    private_headers=admin_headers();public_headers()
    require(refresh_pair(value['items'],http)==value['items'],'live_evidence_changed_replan')
    public_before=remote_snapshot(value['items'],http,public_headers())
    require(public_before==value['before'],'remote_state_changed_replan')
    # Fail before ANY mutation if the admin key cannot SELECT hidden/unverified aliases.
    try:before=remote_snapshot(value['items'],http,private_headers,admin=True)
    except a.Failure as exc:
        if str(exc) in {'http_401','http_403'}:raise a.Failure('canary_admin_preflight_denied_check_service_role_and_select_grants') from None
        raise
    # This is the exact, final dry-run. It contains public metadata only, no credentials.
    print(a.packed({'canary_dry_run':before['changes'],'scope':{'anilist_id':ANILIST,'max_canonical':1,'max_aliases':1}}),flush=True)
    batch_result=None
    writes_sent=0
    if any(change['action']!='no-op' for change in before['changes']):
        batch_id=str(uuid.uuid5(uuid.NAMESPACE_URL,'animevault-canary-v1:'+expected))
        # One atomic server batch, never the general publisher or chunking/fallback operations.
        batch_result=http.request(a.PROJECT+'/rest/v1/rpc/community_sync_batch',{'p_batch':batch_id,'p_items':value['items']},private_headers)
        writes_sent=1
        require(isinstance(batch_result,dict) and batch_result.get('processed')==2 and batch_result.get('canonical_upserted')==1 and batch_result.get('verified_applied')==1 and batch_result.get('pending')==0 and batch_result.get('rejected')==0,'batch_not_accepted_as_exact_pair_recheck_state')
    resolved=resolve_target(value['items'],http)
    return {'status':'success','plan_hash':expected,'write_requests':writes_sent,'batch':batch_result,'resolved':resolved,'scope':{'canonical':1,'aliases':1,'episodes':0}}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['plan','publish'])
    parser.add_argument('--state',default='autosync.sqlite')
    parser.add_argument('--plan',default='canary-plan.json')
    parser.add_argument('--expected-plan')
    args=parser.parse_args();report={'status':'blocked','target_anilist':ANILIST}
    try:
        require(os.environ.get('GITHUB_REF')=='refs/heads/main' and os.environ.get('GITHUB_EVENT_NAME')=='workflow_dispatch','trusted_main_dispatch_required')
        http=a.Http()
        if args.command=='plan':
            require(Path(args.state).is_file(),'discovery_cache_missing_run_discovery_first')
            state=a.State(args.state)
            try:value=plan(state,http)
            finally:state.db.close()
            Path(args.plan).write_text(a.packed(value)+'\n')
            report={'status':'dry-run','plan_hash':value['plan_hash'],'changes':value['before']['changes'],'evidence':value['items'][1]['evidence'],
                    'note':'Public preflight cannot see unverified aliases. Protected apply repeats an admin dry-run and refuses conflicts before writes.'}
            if os.environ.get('GITHUB_OUTPUT'):
                with open(os.environ['GITHUB_OUTPUT'],'a') as f:f.write('plan_hash='+value['plan_hash']+'\n')
        else:report=publish_canary(json.loads(Path(args.plan).read_text()),http,args.expected_plan)
    except a.Failure as exc:
        report['error']=str(exc)
        if args.command=='publish':report['write_outcome']='not_confirmed_inspect_remote_state_before_retry'
    except Exception:
        report['error']='canary_request_or_payload_failed_details_withheld'
        if args.command=='publish':report['write_outcome']='not_confirmed_inspect_remote_state_before_retry'
    finally:
        Path('canary-report.json').write_text(a.packed(report)+'\n')
        print(a.packed(report))
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as f:f.write('```json\n'+a.packed(report)+'\n```\n')
    if report.get('status') not in {'dry-run','success'}:raise SystemExit(1)

if __name__=='__main__':main()
