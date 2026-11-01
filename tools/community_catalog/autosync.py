#!/usr/bin/env python3
"""Public-source discovery; durable SQLite queue; explicitly gated admin RPC publisher.
No credentials, request URLs or upstream error bodies are ever logged/persisted.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

PROJECT = 'https://kgvkxocbkklisqrqmcjx.supabase.co'
QUERY = '''query($page:Int!){Page(page:$page,perPage:50){pageInfo{hasNextPage} media(type:ANIME,sort:ID){id idMal title{romaji english native} format season seasonYear}}}'''
LOOKUP = '''query($id:Int,$mal:Int){Media(id:$id,idMal:$mal,type:ANIME){id idMal title{romaji english native} format season seasonYear}}'''
HOSTS = {'graphql.anilist.co','aniliberty.top','api.animevost.org','api.yani.tv','kodik-api.com','shikimori.one','shikimori.io','animetka.com'}

class Failure(Exception):
    pass

def packed(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False)

def positive(value):
    try:
        return int(value) if not isinstance(value,bool) and str(int(value)) == str(value) and 0 < int(value) < 2147483648 else None
    except (ValueError,TypeError):
        return None

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        # Credentials must never follow an upstream redirect to another host.
        raise Failure('redirect_refused')

class Http:
    def __init__(self, pause=1.5, sleeper=time.sleep):
        self.pause,self.sleep,self.last = pause,sleeper,{}
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self,url,payload=None,headers=None,form=False):
        host = urllib.parse.urlsplit(url).hostname
        if urllib.parse.urlsplit(url).scheme != 'https' or host not in HOSTS | {urllib.parse.urlsplit(PROJECT).hostname}:
            raise Failure('host_refused')
        for attempt in range(5):
            self.sleep(max(0,self.pause-(time.monotonic()-self.last.get(host,0))))
            self.last[host] = time.monotonic()
            data = (urllib.parse.urlencode(payload).encode() if form else packed(payload).encode()) if payload is not None else None
            request = urllib.request.Request(url,data=data,headers={'User-Agent':'AnimeVault-Catalog-AutoSync/1','Accept':'application/json',**({'Content-Type':'application/x-www-form-urlencoded' if form else 'application/json'} if data is not None else {}),**(headers or {})})
            try:
                with self.opener.open(request,timeout=30) as response:
                    raw = response.read(8*1024*1024+1)
                if len(raw)>8*1024*1024: raise Failure('response_too_large')
                result=json.loads(raw)
                if isinstance(result,dict) and (result.get('errors') or result.get('error')):
                    raise Failure('upstream_error')
                return result
            except urllib.error.HTTPError as exc:
                retry = exc.code in {429,500,502,503,504}
                delay = exc.headers.get('Retry-After','')
                if not retry or attempt==4: raise Failure('http_'+str(exc.code)) from None
                self.sleep(min(120,int(delay)) if delay.isdigit() else min(60,2**(attempt+1)))
            except (OSError,ValueError):
                if attempt==4: raise Failure('network_or_json') from None
                self.sleep(2**(attempt+1))
        raise Failure('retry_exhausted')

class State:
    def __init__(self,path):
        self.db=sqlite3.connect(path)
        self.db.executescript('''CREATE TABLE IF NOT EXISTS queue(key TEXT PRIMARY KEY, body TEXT NOT NULL, ack TEXT);
        CREATE TABLE IF NOT EXISTS cursor(source TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS errors(source TEXT, code TEXT, at TEXT DEFAULT CURRENT_TIMESTAMP);
        CREATE TABLE IF NOT EXISTS canonical(id INTEGER PRIMARY KEY, mal INTEGER UNIQUE, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS receipts(id TEXT PRIMARY KEY, body TEXT NOT NULL);''')

    def put(self,key,body):
        self.db.execute('INSERT INTO queue VALUES(?,?,NULL) ON CONFLICT(key) DO UPDATE SET body=excluded.body,ack=CASE WHEN queue.body=excluded.body THEN queue.ack ELSE NULL END',(key,packed(body)))

    def anime(self,media):
        aid,mal=positive(media.get('id')),positive(media.get('idMal'))
        title=next((media.get('title',{}).get(k) for k in ('romaji','english','native') if media.get('title',{}).get(k)),None)
        if not aid or not isinstance(title,str) or not 0<len(title)<=300: raise Failure('invalid_canonical')
        old=self.db.execute('SELECT mal FROM canonical WHERE id=?',(aid,)).fetchone()
        if old and old[0] is not None and old[0]!=mal: raise Failure('canonical_conflict')
        record={'kind':'anime','anilist_id':aid,'mal_id':mal,'title':title}
        self.db.execute('INSERT INTO canonical VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET mal=excluded.mal,body=excluded.body',(aid,mal,packed(record)))
        self.put('anime:'+str(aid),record)
        return aid

    def cursor(self,source):
        row=self.db.execute('SELECT value FROM cursor WHERE source=?',(source,)).fetchone()
        return json.loads(row[0]) if row else 1

    def advance(self,source,value):
        self.db.execute('INSERT INTO cursor VALUES(?,?) ON CONFLICT(source) DO UPDATE SET value=excluded.value',(source,packed(value)))

    def error(self,source,code):
        # Only fixed internal codes. Never exception text or request URLs.
        self.db.execute('INSERT INTO errors(source,code) VALUES(?,?)',(source,code))
        self.db.execute('DELETE FROM errors WHERE rowid NOT IN (SELECT rowid FROM errors ORDER BY rowid DESC LIMIT 10000)')
        self.db.commit()

def mal_link(value):
    parsed=urllib.parse.urlsplit(value)
    match=re.fullmatch(r'/anime/(\d+)(?:/[^/?#]*)?/?',parsed.path)
    return positive(match[1]) if parsed.scheme in {'http','https'} and parsed.hostname in {'myanimelist.net','www.myanimelist.net'} and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment and match else None

class Sync:
    def __init__(self,state,http):
        self.state,self.http=state,http
        self.chains={}

    def anilist(self,pages):
        for _ in range(pages):
            page=self.state.cursor('anilist')
            result=self.http.request('https://graphql.anilist.co',{'query':QUERY,'variables':{'page':page}})['data']['Page']
            rows=result['media']
            if not isinstance(rows,list) or len(rows)>50: raise Failure('anilist_shape')
            with self.state.db:
                for media in rows: self.state.anime(media)
                self.state.advance('anilist',page+1 if result['pageInfo']['hasNextPage'] else 1)
            if not result['pageInfo']['hasNextPage']: break

    def chain(self,shiki):
        if shiki not in self.chains:
            url=f'https://shikimori.io/api/animes/{shiki}/external_links'
            links=self.http.request(url)
            if not isinstance(links,list): raise Failure('shikimori_shape')
            ids={mal_link(link.get('url','')) for link in links if link.get('kind')=='myanimelist'}-{None}
            self.chains[shiki]=(next(iter(ids)),url) if len(ids)==1 else (None,url)
        return self.chains[shiki]

    def link(self,provider,external,mal=None,anilist=None,shiki=None):
        if not isinstance(external,str) or not 0<len(external)<=300: raise Failure('provider_id')
        mal,anilist,shiki=positive(mal),positive(anilist),positive(shiki)
        proof={'source':provider,'method':'none','episode_status':'pending_no_canonical_episode_identity'}
        if mal or anilist: proof={'source':provider,'method':'direct_id','mal_id':mal,'anilist_id':anilist,'url':{'yummy':'https://api.yani.tv/anime','animetka':'https://animetka.com/api/anime/','kodik':'https://kodik-api.com/list'}.get(provider,'https://aniliberty.top/api/v1/anime/catalog/releases')}
        if shiki:
            try:
                linked,url=self.chain(shiki)
            except Failure as exc:
                self.state.db.execute('INSERT INTO errors(source,code) VALUES(?,?)',('shikimori',str(exc)))
                linked,url=None,None
                self.chains[shiki]=(None,None)
                if not mal and not anilist: proof={'source':provider,'method':'chain_unavailable','shikimori_id':shiki}
            if linked and mal and linked!=mal:
                return self.evidence(provider,external,None,'rejected',{'source':provider,'method':'conflicting_ids'})
            if linked and not mal:
                mal=linked
                proof={'source':provider,'method':'external_link_chain','shikimori_id':shiki,'url':url,'mal_id':mal}
        found=self.state.db.execute('SELECT id,mal FROM canonical WHERE id=? OR mal=?',(anilist,mal)).fetchall()
        if not found and (mal or anilist):
            media=self.http.request('https://graphql.anilist.co',{'query':LOOKUP,'variables':{'id':anilist,'mal':mal if not anilist else None}})['data']['Media']
            if media and (not mal or positive(media.get('idMal'))==mal) and (not anilist or positive(media.get('id'))==anilist):
                self.state.anime(media)
                found=[(positive(media['id']),positive(media.get('idMal')))]
        if len(found)>1 or (found and ((anilist and found[0][0]!=anilist) or (mal and found[0][1]!=mal))):
            self.evidence(provider,external,None,'rejected',{'source':provider,'method':'conflicting_ids'})
        elif found and (mal or anilist): self.evidence(provider,external,found[0][0],'verified',proof)
        else: self.evidence(provider,external,None,'pending',proof)

    def evidence(self,provider,external,aid,status,proof):
        key='link:'+provider+':'+external
        prior=self.state.db.execute('SELECT body FROM queue WHERE key=?',(key,)).fetchone()
        if prior:
            previous=json.loads(prior[0])
            if previous['status']=='verified' and status=='verified' and previous['anilist_id']!=aid:
                status='rejected';proof={'source':provider,'method':'conflicting_observations'}
        self.state.put('link:'+provider+':'+external,{'kind':'link','provider':provider,'external_id':external,'anilist_id':aid,'status':status,'evidence':proof})

    def provider(self,source,pages):
        for _ in range(pages):
            page=self.state.cursor(source)
            if source=='aniliberty':
                result=self.http.request(f'https://aniliberty.top/api/v1/anime/catalog/releases?page={page}&limit=50')
                rows=result['data']; more=page<result['meta']['pagination']['total_pages']
                items=[(str(row['id']),None,None,None) for row in rows if positive(row.get('id'))]
                next_page=page+1
            elif source=='animevost':
                result=self.http.request(f'https://api.animevost.org/v1/last?page={page}&quantity=50')
                rows=result['data']; more=len(rows)==50
                items=[(str(row['id']),None,None,None) for row in rows if positive(row.get('id'))]
                next_page=page+1
            elif source=='yummy':
                result=self.http.request(f'https://api.yani.tv/anime?limit=50&offset={(page-1)*50}&sort=rating&sort_forward=false')
                rows=result['response']; more=len(rows)==50; next_page=page+1
                items=[(str(row['anime_id']),(row.get('remote_ids') or {}).get('myanimelist_id'),None,(row.get('remote_ids') or {}).get('shikimori_id')) for row in rows if positive(row.get('anime_id'))]
            elif source=='kodik':
                token=os.environ.get('KODIK_API_TOKEN','')
                if not token: raise Failure('token_not_configured')
                params={'token':token,'limit':50,'with_material_data':'true'}
                if page!=1: params['start']=page
                result=self.http.request('https://kodik-api.com/list?'+urllib.parse.urlencode(params),{},form=True)
                rows=result['results']; next_url=result.get('next_page'); more=bool(next_url); next_page=1
                if next_url:
                    u=urllib.parse.urlsplit(next_url)
                    if u.scheme!='https' or u.netloc!='kodik-api.com' or u.path!='/list': raise Failure('kodik_cursor_host')
                    starts=urllib.parse.parse_qs(u.query).get('start',[])
                    if len(starts)!=1 or not re.fullmatch(r'[A-Za-z0-9_-]{1,200}',starts[0]): raise Failure('kodik_cursor_shape')
                    next_page=starts[0] # Persist only start, NEVER the URL containing token.
                items=[]
                for row in rows:
                    shiki=positive(row.get('shikimori_id')); kp=positive(row.get('kinopoisk_id'))
                    rid='shiki:'+str(shiki) if shiki else 'kp:'+str(kp) if kp else 'kodik:'+str(row['id'])
                    items.append((rid,None,None,shiki))
            elif source=='animetka':
                # The Android adapter has a one-page main feed, not a proven bulk endpoint.
                result=self.http.request('https://animetka.com/api/anime/main')
                def unwrap(value):
                    if isinstance(value,dict):
                        for key in ('response','data'):
                            if isinstance(value.get(key),(dict,list)): return value[key]
                    return value
                result=unwrap(result)
                rows=result if isinstance(result,list) else next((result[k] for k in ('items','results','anime','list','data') if isinstance(result.get(k),list)),[])
                more=False; next_page=1; items=[]
                for row in rows:
                    external=next((str(row[k]) for k in ('id','material_id','animetka_id') if positive(row.get(k))),None)
                    if not external: continue
                    details=unwrap(self.http.request('https://animetka.com/api/anime/'+external))
                    detail_id=next((str(details[k]) for k in ('id','material_id','animetka_id') if positive(details.get(k))),None)
                    if detail_id!=external: raise Failure('animetka_detail_identity')
                    items.append((external,details.get('mal_id') or details.get('myanimelist_id') or details.get('malId'),details.get('anilist_id') or details.get('anilistId'),details.get('shikimori_id') or details.get('shikimoriId')))
            else: raise Failure('unsupported_source')
            if not isinstance(rows,list) or len(rows)>100: raise Failure('provider_shape')
            with self.state.db:
                for external,mal,anilist,shiki in items: self.link(source,external,mal,anilist,shiki)
                self.state.advance(source,next_page if more else 1)
            if not more: break

    def run(self,pages):
        for source in ('anilist','aniliberty','animevost','yummy','kodik','animetka'):
            try:
                self.anilist(pages) if source=='anilist' else self.provider(source,pages)
            except (Failure,KeyError,TypeError,ValueError,sqlite3.IntegrityError) as exc:
                self.state.db.rollback()
                self.state.error(source,str(exc) if isinstance(exc,Failure) else 'invalid_payload_or_conflict')
        # Probe the requested real MAL record, without fabricating any provider alias.
        try:
            media=self.http.request('https://graphql.anilist.co',{'query':LOOKUP,'variables':{'mal':61967}})['data']['Media']
            if media and positive(media.get('idMal'))==61967:
                with self.state.db: self.state.anime(media)
                mal,_=self.chain(61967)
                token=os.environ.get('KODIK_API_TOKEN','')
                if mal==61967 and token:
                    query=urllib.parse.urlencode({'token':token,'shikimori_id':61967,'limit':50})
                    result=self.http.request('https://kodik-api.com/search?'+query,{},form=True)
                    rows=result['results']
                    if not isinstance(rows,list) or len(rows)>50: raise Failure('provider_shape')
                    with self.state.db:
                        for row in rows:
                            if positive(row.get('shikimori_id'))!=61967: raise Failure('probe_identity_conflict')
                            self.link('kodik','shiki:'+str(row['shikimori_id']),shiki=61967)
            else: self.state.error('mal:61967','not_found')
        except (Failure,KeyError,TypeError,ValueError,sqlite3.IntegrityError):
            self.state.db.rollback(); self.state.error('mal:61967','probe_unavailable')

def publish(state,http):
    if os.environ.get('COMMUNITY_AUTOSYNC_WRITE_ENABLED')!='true': raise Failure('writes_disabled')
    key=os.environ.get('SUPABASE_SERVICE_ROLE_KEY','')
    if not key: raise Failure('admin_secret_missing')
    count=0
    def send(batch):
        nonlocal count
        bodies=[json.loads(row[1]) for row in batch]
        digest=hashlib.sha256(packed(bodies).encode()).hexdigest()
        batch_id=str(uuid.uuid5(uuid.NAMESPACE_URL,'animevault-autosync-v1:'+digest))
        try:
            result=http.request(PROJECT+'/rest/v1/rpc/community_sync_batch',{'p_batch':batch_id,'p_items':bodies},{'apikey':key,'Authorization':'Bearer '+key})
        except Failure as exc:
            # Isolate a rejected record without discarding other records in its atomic batch.
            # Transport/auth/rate failures remain resumable and stop the publisher.
            if str(exc)!='http_400': raise
            if len(batch)>1:
                middle=len(batch)//2;send(batch[:middle]);send(batch[middle:])
            else: state.error('publisher:'+batch[0][0],'record_rejected')
            return
        if not isinstance(result,dict) or result.get('processed')!=len(batch): raise Failure('rpc_response')
        with state.db:
            state.db.execute('INSERT OR IGNORE INTO receipts VALUES(?,?)',(batch_id,packed(result)))
            for row in batch: state.db.execute('UPDATE queue SET ack=? WHERE key=? AND body=?',(digest,*row))
        count+=len(batch)
    # Keyset traversal bounds memory and skips a quarantined item until the next run.
    for kind in ('anime','link'):
        after=''
        while True:
            batch=state.db.execute("SELECT key,body FROM queue WHERE ack IS NULL AND json_extract(body,'$.kind')=? AND key>? ORDER BY key LIMIT 100",(kind,after)).fetchall()
            if not batch: break
            after=batch[-1][0];send(batch)
    return count

def report(state,published=0):
    counts={'canonical':0,'verified':0,'pending':0,'rejected':0,'unpublished':0,'published_this_run':published}
    for body,ack in state.db.execute('SELECT body,ack FROM queue'):
        item=json.loads(body); counts['canonical' if item['kind']=='anime' else item['status']]+=1
        if ack is None: counts['unpublished']+=1
    counts['canonical_acknowledged']=state.db.execute("SELECT count(*) FROM queue WHERE ack IS NOT NULL AND json_extract(body,'$.kind')='anime'").fetchone()[0]
    counts['server_batch_totals']={k:sum(json.loads(row[0]).get(k,0) for row in state.db.execute('SELECT body FROM receipts')) for k in ('canonical_upserted','verified_applied','pending','rejected')}
    counts['errors']=[{'source':s,'code':c,'count':n} for s,c,n in state.db.execute('SELECT source,code,count(*) FROM errors GROUP BY source,code')]
    counts['mal_61967']= [json.loads(row[0]) for row in state.db.execute('SELECT body FROM canonical WHERE mal=61967')]
    counts['sources_not_scanned']={'jutsu':'URL_OR_SLUG only; no bulk API in current adapter','animelib':'No declared cross-catalog IDs in current DTOs','dreamerscast':'HTML adapter; no declared cross-catalog IDs','animedia':'HTML adapter; no declared cross-catalog IDs','animeon':'No declared cross-catalog IDs in current DTOs','sameband':'HTML adapter; no declared cross-catalog IDs','animebest':'HTML adapter; no declared cross-catalog IDs','all':'Synthetic aggregate release IDs; no provider-ID construction allowed'}
    counts['mal_61967_verified_aliases']=[json.loads(r[0]) for r in state.db.execute("SELECT body FROM queue WHERE json_extract(body,'$.status')='verified' AND json_extract(body,'$.anilist_id') IN (SELECT id FROM canonical WHERE mal=61967)")]
    counts['episode_policy']='No automatic episode matches: authoritative cross-source episode identity unavailable.'
    return counts

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['scan','publish','report'])
    parser.add_argument('--state',default='autosync.sqlite')
    parser.add_argument('--pages',type=int,default=10)
    parser.add_argument('--report',default='autosync-report.json')
    args=parser.parse_args()
    if not 1<=args.pages<=100: parser.error('pages must be 1..100')
    state=State(args.state); http=Http(); published=0
    try:
        if args.command=='scan': Sync(state,http).run(args.pages)
        if args.command=='publish': published=publish(state,http)
    except Failure as exc:
        state.error('publisher',str(exc)); raise SystemExit(str(exc)) from None
    finally:
        Path(args.report).write_text(packed(report(state,published))+'\n')
        state.db.close()

if __name__=='__main__': main()
