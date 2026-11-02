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
    def __init__(self,code,*,http_status=None,error_type=None):
        super().__init__(code)
        self.http_status=http_status
        self.error_type=error_type

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
        raise Failure('redirect_refused',http_status=code,error_type='redirect_refused')

class Http:
    def __init__(self, pause=1.5, sleeper=time.sleep, max_attempts=5):
        self.pause,self.sleep,self.last = pause,sleeper,{}
        self.max_attempts=max_attempts
        self.last_http_status=None
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self,url,payload=None,headers=None,form=False):
        self.last_http_status=None
        host = urllib.parse.urlsplit(url).hostname
        if urllib.parse.urlsplit(url).scheme != 'https' or host not in HOSTS | {urllib.parse.urlsplit(PROJECT).hostname}:
            raise Failure('host_refused')
        for attempt in range(self.max_attempts):
            self.sleep(max(0,self.pause-(time.monotonic()-self.last.get(host,0))))
            self.last[host] = time.monotonic()
            data = (urllib.parse.urlencode(payload).encode() if form else packed(payload).encode()) if payload is not None else None
            request = urllib.request.Request(url,data=data,headers={'User-Agent':'AnimeVault-Catalog-AutoSync/1','Accept':'application/json',**({'Content-Type':'application/x-www-form-urlencoded' if form else 'application/json'} if data is not None else {}),**(headers or {})})
            try:
                with self.opener.open(request,timeout=30) as response:
                    self.last_http_status=getattr(response,'status',200)
                    if type(self.last_http_status) is not int:self.last_http_status=200
                    raw = response.read(8*1024*1024+1)
                if len(raw)>8*1024*1024: raise Failure('response_too_large',http_status=self.last_http_status,error_type='response_too_large')
                try:result=json.loads(raw)
                except (ValueError,UnicodeError):raise Failure('invalid_json',http_status=self.last_http_status,error_type='invalid_json') from None
                if isinstance(result,dict) and (result.get('errors') or result.get('error')):
                    raise Failure('upstream_error',http_status=self.last_http_status,error_type='graphql_error' if host=='graphql.anilist.co' else 'provider_error')
                return result
            except urllib.error.HTTPError as exc:
                self.last_http_status=exc.code
                retry = exc.code in {429,500,502,503,504}
                delay = exc.headers.get('Retry-After','')
                if not retry or attempt==self.max_attempts-1: raise Failure('http_'+str(exc.code),http_status=exc.code,error_type='http_error') from None
                self.sleep(min(120,int(delay)) if delay.isdigit() else min(60,2**(attempt+1)))
            except (OSError,ValueError):
                if attempt==self.max_attempts-1: raise Failure('network_or_json',error_type='network_error') from None
                self.sleep(2**(attempt+1))
        raise Failure('retry_exhausted')

def anilist_lookup_variables(anilist=None,mal=None):
    # GraphQL omitted variable != explicit null. AniList may treat id:null as an ID filter.
    if positive(anilist):return {'id':positive(anilist)}
    if positive(mal):return {'mal':positive(mal)}
    raise Failure('invalid_canonical_lookup')

class State:
    def __init__(self,path):
        self.db=sqlite3.connect(path)
        self.db.executescript('''CREATE TABLE IF NOT EXISTS queue(key TEXT PRIMARY KEY, body TEXT NOT NULL, ack TEXT);
        CREATE TABLE IF NOT EXISTS cursor(source TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS errors(source TEXT, code TEXT, at TEXT DEFAULT CURRENT_TIMESTAMP);
        CREATE TABLE IF NOT EXISTS canonical(id INTEGER PRIMARY KEY, mal INTEGER UNIQUE, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS receipts(id TEXT PRIMARY KEY, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS chains(shiki INTEGER PRIMARY KEY, mal INTEGER NOT NULL, checked REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS resolver_checks(key TEXT PRIMARY KEY, request_hash TEXT NOT NULL, status TEXT NOT NULL, checked REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS retry_links(key TEXT PRIMARY KEY, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS bridge_jobs(key TEXT PRIMARY KEY, body TEXT NOT NULL);''')
        self.error_start=self.db.execute('SELECT coalesce(max(rowid),0) FROM errors').fetchone()[0]

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

def kodik_reference(row):
    # Exact KodikItemDto.releaseReference() priority, not an importer-only synthetic ID.
    for field,prefix in (('shikimori_id','shiki'),('kinopoisk_id','kp'),('id','kodik')):
        value=row.get(field)
        if value is not None and str(value).strip():
            value=str(value)
            if len(value)>280 or any(ord(c)<32 or ord(c)==127 for c in value):raise Failure('kodik_identity_shape')
            return prefix+':'+value
    raise Failure('kodik_identity_shape')

def kodik_cursor(url,token=''):
    parsed=urllib.parse.urlsplit(url)
    if parsed.scheme!='https' or parsed.hostname not in {'kodik-api.com','kodikapi.com'} or parsed.username or parsed.password or parsed.port not in {None,443} or parsed.path.rstrip('/')!='/list' or parsed.fragment:
        raise Failure('kodik_cursor_host')
    query=urllib.parse.parse_qs(parsed.query,keep_blank_values=True)
    names=[name for name in ('next','start') if name in query]
    if len(names)!=1 or len(query[names[0]])!=1:raise Failure('kodik_cursor_shape')
    value=query[names[0]][0]
    if not value or len(value)>4096 or any(ord(c)<32 or ord(c)==127 for c in value):raise Failure('kodik_cursor_shape')
    if token and token in value:raise Failure('kodik_cursor_contains_credential')
    return {'parameter':names[0],'value':value}

def client_identity(provider,external,mal=None,anilist=None):
    return {'p_provider':provider,'p_external':external,'p_mal':positive(mal),'p_anilist':positive(anilist),'p_episode_external':None}

def client_contract(item):
    proof=item.get('evidence') or {};client=proof.get('client_identity');observed=proof.get('observed_identity')
    if proof.get('client_contract')!='android-release-id-v1' or not isinstance(client,dict) or not isinstance(observed,dict):return False
    provider=item.get('provider');external=item.get('external_id')
    if client.get('p_provider')!=provider or client.get('p_external')!=external or client.get('p_episode_external') is not None:return False
    if provider=='kodik':
        try:return kodik_reference(observed)==external
        except Failure:return False
    if provider in {'animetka','animevost','aniliberty','yummy'}:
        return positive(observed.get('id')) is not None and str(observed['id'])==external
    return False

def animetka_rows(result):
    for _ in range(3):
        if isinstance(result,list):return result
        if not isinstance(result,dict):break
        nested=next((result[k] for k in ('response','data','items','results','anime','list') if isinstance(result.get(k),(dict,list))),None)
        if nested is None:break
        result=nested
    raise Failure('animetka_catalog_shape')

def animetka_detail(result):
    for _ in range(3):
        if not isinstance(result,dict):break
        nested=next((result[k] for k in ('response','data') if isinstance(result.get(k),dict)),None)
        if nested is None:return result
        result=nested
    raise Failure('animetka_detail_shape')

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
            cached=self.state.db.execute('SELECT mal,checked FROM chains WHERE shiki=?',(shiki,)).fetchone()
            if cached and time.time()-cached[1]<86400:
                self.chains[shiki]=(cached[0],url);return self.chains[shiki]
            links=self.http.request(url)
            if not isinstance(links,list): raise Failure('shikimori_shape')
            ids={mal_link(link.get('url','')) for link in links if link.get('kind')=='myanimelist'}-{None}
            self.chains[shiki]=(next(iter(ids)),url) if len(ids)==1 else (None,url)
            if len(ids)==1:self.state.db.execute('INSERT OR REPLACE INTO chains VALUES(?,?,?)',(shiki,next(iter(ids)),time.time()))
        return self.chains[shiki]

    def link(self,provider,external,mal=None,anilist=None,shiki=None,observed=None,provenance=None,client=None):
        if not isinstance(external,str) or not 0<len(external)<=300: raise Failure('provider_id')
        client=client or client_identity(provider,external,mal,anilist)
        mal,anilist,shiki=positive(mal),positive(anilist),positive(shiki)
        proof={'source':provider,'method':'none','episode_status':'pending_no_canonical_episode_identity'}
        if mal or anilist: proof={'source':provider,'method':'direct_id','mal_id':mal,'anilist_id':anilist,'url':{'yummy':'https://api.yani.tv/anime/'+external,'animetka':'https://animetka.com/api/anime/'+external,'kodik':'https://kodik-api.com/list'}.get(provider,'https://aniliberty.top/api/v1/anime/catalog/releases')}
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
            media=self.http.request('https://graphql.anilist.co',{'query':LOOKUP,'variables':anilist_lookup_variables(anilist,mal)})['data']['Media']
            if media and (not mal or positive(media.get('idMal'))==mal) and (not anilist or positive(media.get('id'))==anilist):
                self.state.anime(media)
                found=[(positive(media['id']),positive(media.get('idMal')))]
        if len(found)>1 or (found and ((anilist and found[0][0]!=anilist) or (mal and found[0][1]!=mal))):
            self.evidence(provider,external,None,'rejected',{'source':provider,'method':'conflicting_ids'})
        elif found and (mal or anilist):
            if observed is not None:proof.update({'client_contract':'android-release-id-v1','client_identity':client,'observed_identity':observed})
            if provenance:
                proof['provenance']=provenance;proof['method']='external_link_chain';proof['url']='https://animetka.com/api/anime/'+provenance['animetka_id']
            self.evidence(provider,external,found[0][0],'verified',proof)
        else: self.evidence(provider,external,None,'pending',proof)

    def evidence(self,provider,external,aid,status,proof):
        key='link:'+provider+':'+external
        prior=self.state.db.execute('SELECT body FROM queue WHERE key=?',(key,)).fetchone()
        if prior:
            previous=json.loads(prior[0])
            if previous['status']=='verified' and status=='pending':return # Weak observations do not erase confirmed proof/ack.
            if previous['status']=='verified' and status=='verified' and previous['anilist_id']!=aid:
                status='rejected';proof={'source':provider,'method':'conflicting_observations'}
        self.state.put('link:'+provider+':'+external,{'kind':'link','provider':provider,'external_id':external,'anilist_id':aid,'status':status,'evidence':proof})

    def process(self,source,external,mal=None,anilist=None,shiki=None,observed=None,provenance=None,client=None):
        args={'provider':source,'external':external,'mal':mal,'anilist':anilist,'shiki':shiki,'observed':observed,'provenance':provenance,'client':client}
        key='link:'+source+':'+external
        self.state.db.execute('SAVEPOINT link_item')
        try:
            effective=dict(args);details=None
            if source=='animetka' and observed and observed.get('needs_detail'):
                details=animetka_detail(self.http.request('https://animetka.com/api/anime/'+external,headers={'Referer':'https://animetka.com/','Accept':'application/json, text/plain, */*'}))
                detail_id=next((str(details[k]) for k in ('id','material_id','animetka_id') if positive(details.get(k))),None)
                if detail_id!=external or (positive(observed.get('search_shikimori_id')) and positive(details.get('shikimori_id'))!=positive(observed['search_shikimori_id'])):raise Failure('animetka_detail_identity')
                effective.update(mal=details.get('mal_id') or details.get('myanimelist_id') or details.get('malId'),anilist=details.get('anilist_id') or details.get('anilistId'),shiki=details.get('shikimori_id') or details.get('shikimoriId'),observed={'id':external})
            if source=='yummy' and observed and observed.get('needs_detail'):
                key_value=os.environ.get('YUMMY_APPLICATION_KEY','')
                if not key_value:raise Failure('application_key_not_configured')
                response=self.http.request('https://api.yani.tv/anime/'+external,headers={'X-Application':key_value,'Lang':'ru','Referer':'https://yummyani.me/'})
                detail=response.get('response')
                if not isinstance(detail,dict):raise Failure('yummy_detail_shape')
                if detail.get('anime_id') is not None and str(detail['anime_id'])!=external:raise Failure('yummy_detail_identity')
                remote=detail.get('remote_ids') or {};detail_mal=positive(remote.get('myanimelist_id'));detail_shiki=positive(remote.get('shikimori_id'))
                if (detail_mal and positive(mal) and detail_mal!=positive(mal)) or (detail_shiki and positive(shiki) and detail_shiki!=positive(shiki)):raise Failure('yummy_detail_identity')
                effective.update(mal=detail_mal or mal,shiki=detail_shiki or shiki,observed={'id':external},client=client_identity('yummy',external,detail_mal))
            self.link(**effective)
            if details is not None:
                mapping=details.get('AnilibriaMapping') or {}
                slim={'AnilibriaMapping':{'animetka_id':mapping.get('animetka_id'),'episodes':[{k:e.get(k) for k in ('id','release_id')} for e in (mapping.get('episodes') or []) if isinstance(e,dict)]},'Animes':[{'kodik_id':e.get('kodik_id')} for e in (details.get('Animes') or []) if isinstance(e,dict)]}
                if slim['Animes'] or slim['AnilibriaMapping']['episodes']:self.state.db.execute('INSERT OR REPLACE INTO bridge_jobs VALUES(?,?)',(external,packed(slim)))

            self.state.db.execute('DELETE FROM retry_links WHERE key=?',(key,))
            self.state.db.execute('RELEASE SAVEPOINT link_item')
        except (Failure,KeyError,TypeError,ValueError,AttributeError,sqlite3.IntegrityError) as exc:
            self.state.db.execute('ROLLBACK TO SAVEPOINT link_item');self.state.db.execute('RELEASE SAVEPOINT link_item')
            code=str(exc) if isinstance(exc,Failure) else 'invalid_payload_or_conflict'
            self.state.db.execute('INSERT INTO errors(source,code) VALUES(?,?)',(source,code))
            self.state.db.execute('INSERT OR REPLACE INTO retry_links VALUES(?,?)',(key,packed(args)))
            previous=self.state.db.execute('SELECT body FROM queue WHERE key=?',(key,)).fetchone()
            if code in {'animetka_detail_identity','yummy_detail_identity'}:self.evidence(source,external,None,'rejected',{'source':source,'method':'identity_conflict'})
            elif not previous:self.evidence(source,external,None,'pending',{'source':source,'method':'upstream_unavailable'})

    def retry(self,limit):
        after=self.state.cursor('retry_after');after=after if isinstance(after,str) else ''
        rows=self.state.db.execute('SELECT key,body FROM retry_links WHERE key>? ORDER BY key LIMIT ?',(after,limit)).fetchall()
        if not rows:rows=self.state.db.execute('SELECT key,body FROM retry_links ORDER BY key LIMIT ?',(limit,)).fetchall()
        for key,body in rows:
            args=json.loads(body);args['source']=args.pop('provider')
            with self.state.db:
                if not self.state.db.in_transaction:self.state.db.execute('BEGIN')
                self.process(**args);self.state.advance('retry_after',key)

    def bridge_animetka(self,external,details):
        row=self.state.db.execute('SELECT body FROM queue WHERE key=?',('link:animetka:'+external,)).fetchone()
        if not row or json.loads(row[0])['status']!='verified':return False
        complete=True
        link=json.loads(row[0]);aid=link['anilist_id']
        mapping=details.get('AnilibriaMapping')
        # Mapping.id is the mapping table PK (8762 in the captured response), NOT release.id (9542).
        if isinstance(mapping,dict) and str(mapping.get('animetka_id'))==external:
            episodes=mapping.get('episodes') or []
            releases={positive(e.get('release_id')) for e in episodes if isinstance(e,dict)}-{None}
            if len(releases)==1:
                release=next(iter(releases))
                try:
                    actual=self.http.request(f'https://aniliberty.top/api/v1/anime/releases/{release}')
                    if positive(actual.get('id'))!=release:raise Failure('aniliberty_bridge_identity')
                    expected={e.get('id') for e in episodes if positive(e.get('release_id'))==release}
                    returned={e.get('id') for e in actual.get('episodes',[]) if isinstance(e,dict)}-{None}
                    if not returned or not expected or not returned.issubset(expected):raise Failure('aniliberty_bridge_episode_identity')
                    self.process('aniliberty',str(release),anilist=aid,observed={'id':release},client=client_identity('aniliberty',str(release)),provenance={'method':'animetka_anilibria_mapping','animetka_id':external,'release_id':release,'checked_episode_uuids':len(returned)})
                    details['AnilibriaMapping']={'animetka_id':external,'episodes':[]}
                except (Failure,KeyError,TypeError,ValueError,AttributeError) as exc:
                    complete=False
                    self.state.db.execute('INSERT INTO errors(source,code) VALUES(?,?)',('aniliberty_bridge',str(exc) if isinstance(exc,Failure) else 'bridge_shape'))
        token=os.environ.get('KODIK_API_TOKEN','')
        if token:
            materials=details.get('Animes') or []
            remaining=materials[4:]
            for item in materials[:4]:
                if not isinstance(item,dict) or not re.fullmatch(r'(?:serial|movie)-[0-9]+',str(item.get('kodik_id',''))):continue
                mid=item['kodik_id']
                try:
                    result=self.http.request('https://kodik-api.com/search?'+urllib.parse.urlencode({'token':token,'id':mid,'limit':50}),{},form=True)
                    if not result['results']:raise Failure('kodik_bridge_not_found')
                    for actual in result['results']:
                        if actual.get('id')!=mid:raise Failure('kodik_bridge_identity')
                        ref=kodik_reference(actual)
                        if ref.startswith('kp:') or ref.startswith('shiki:') and not positive(actual.get('shikimori_id')):
                            self.evidence('kodik',ref,None,'pending',{'source':'kodik','method':'kinopoisk_group_not_disambiguated'});continue
                        if positive(actual.get('shikimori_id')) and not self.chain(positive(actual['shikimori_id']))[0]:raise Failure('kodik_bridge_unverified_shikimori')
                        self.process('kodik',ref,anilist=aid,shiki=actual.get('shikimori_id'),observed={k:actual.get(k) for k in ('id','shikimori_id','kinopoisk_id')},client=client_identity('kodik',ref),provenance={'method':'animetka_kodik_material','animetka_id':external,'material_id':mid})
                except (Failure,KeyError,TypeError,ValueError,AttributeError) as exc:
                    complete=False;remaining.append(item)
                    self.state.db.execute('INSERT INTO errors(source,code) VALUES(?,?)',('kodik_bridge',str(exc) if isinstance(exc,Failure) else 'bridge_shape'))

            details['Animes']=remaining
            if remaining:complete=False
        elif details.get('Animes'):complete=False
        return complete

    def drain_bridges(self,limit):
        after=self.state.cursor('bridge_after');after=after if isinstance(after,str) else ''
        rows=self.state.db.execute('SELECT key,body FROM bridge_jobs WHERE key>? ORDER BY key LIMIT ?',(after,limit)).fetchall()
        if not rows:rows=self.state.db.execute('SELECT key,body FROM bridge_jobs ORDER BY key LIMIT ?',(limit,)).fetchall()
        for external,body in rows:
            with self.state.db:
                if not self.state.db.in_transaction:self.state.db.execute('BEGIN')
                details=json.loads(body)
                if self.bridge_animetka(external,details):self.state.db.execute('DELETE FROM bridge_jobs WHERE key=?',(external,))
                else:self.state.db.execute('UPDATE bridge_jobs SET body=? WHERE key=?',(packed(details),external))
                self.state.advance('bridge_after',external)


    def provider(self,source,pages):
        for _ in range(pages):
            page=self.state.cursor(source);items=[]
            if source=='aniliberty':
                result=self.http.request(f'https://aniliberty.top/api/v1/anime/catalog/releases?page={page}&limit=50')
                rows=result['data'];more=page<result['meta']['pagination']['total_pages'];next_page=page+1
                items=[(str(row['id']),None,None,None,{'id':row['id']}) for row in rows if positive(row.get('id'))]
            elif source=='animevost':
                result=self.http.request(f'https://api.animevost.org/v1/last?page={page}&quantity=50')
                rows=result['data'];more=len(rows)==50;next_page=page+1
                items=[(str(row['id']),None,None,None,{'id':row['id']}) for row in rows if positive(row.get('id'))]
            elif source=='yummy':
                key=os.environ.get('YUMMY_APPLICATION_KEY','')
                if not key:raise Failure('application_key_not_configured')
                result=self.http.request(f'https://api.yani.tv/anime?limit=50&offset={(page-1)*50}&sort=rating&sort_forward=false',headers={'X-Application':key,'Lang':'ru','Referer':'https://yummyani.me/'})
                rows=result['response'];more=len(rows)==50;next_page=page+1
                items=[(str(row['anime_id']),(row.get('remote_ids') or {}).get('myanimelist_id'),None,(row.get('remote_ids') or {}).get('shikimori_id'),{'id':row['anime_id'],'needs_detail':True}) for row in rows if positive(row.get('anime_id'))]
            elif source=='kodik':
                token=os.environ.get('KODIK_API_TOKEN','')
                if not token:raise Failure('token_not_configured')
                params={'token':token,'limit':50,'with_material_data':'true'}
                if page==1 or isinstance(page,dict) and page.get('anime_only') is True:params['types']='anime,anime-serial'
                if isinstance(page,dict):
                    if page.get('parameter') not in {'next','start'} or not isinstance(page.get('value'),str):raise Failure('kodik_cursor_shape')
                    params[page['parameter']]=page['value']
                elif page!=1:params['start']=page # Preserve existing v1 SQLite checkpoints.
                result=self.http.request('https://kodik-api.com/list?'+urllib.parse.urlencode(params),{},form=True)
                rows=result['results'];next_url=result.get('next_page');more=bool(next_url)
                next_page=kodik_cursor(next_url,token) if more else 1
                if more and 'types' in params:next_page['anime_only']=True
                if more and next_page==page:raise Failure('kodik_cursor_stalled')
                items=[(kodik_reference(row),None,None,row.get('shikimori_id'),{k:row.get(k) for k in ('id','shikimori_id','kinopoisk_id')}) for row in rows]
            elif source=='animetka':
                # Blank search with limit/offset was verified live; do not confuse the 24-item main feed with a full catalogue.
                query=urllib.parse.urlencode([('name',''),('limit',50),('offset',(page-1)*50),('attributes','animetka_id'),('attributes','shikimori_id')])
                headers={'Referer':'https://animetka.com/','Accept':'application/json, text/plain, */*'}
                rows=animetka_rows(self.http.request('https://animetka.com/api/anime/search?'+query,headers=headers))
                more=len(rows)==50;next_page=page+1
                for row in rows:
                    external=next((str(row[k]) for k in ('id','material_id','animetka_id') if positive(row.get(k))),None)
                    if not external:raise Failure('animetka_identity_shape')
                    items.append((external,None,None,None,{'id':external,'needs_detail':True,'search_shikimori_id':row.get('shikimori_id')}))
            else:raise Failure('unsupported_source')
            if not isinstance(rows,list) or len(rows)>100:raise Failure('provider_shape')
            with self.state.db:
                if not self.state.db.in_transaction:self.state.db.execute('BEGIN')
                for external,mal,anilist,shiki,observed in items:self.process(source,external,mal,anilist,shiki,observed)
                self.state.advance(source,next_page if more else 1)
            if not more:break

    def run(self,pages):
        self.retry(pages*50)
        for source in ('anilist','aniliberty','animevost','yummy','kodik','animetka'):
            try:
                self.anilist(pages) if source=='anilist' else self.provider(source,pages)
            except (Failure,KeyError,TypeError,ValueError,AttributeError,sqlite3.IntegrityError) as exc:
                self.state.db.rollback()
                self.state.error(source,str(exc) if isinstance(exc,Failure) else 'invalid_payload_or_conflict')
        self.drain_bridges(min(pages*5,50))
        # Probe the requested real MAL record, without fabricating any provider alias.
        try:
            media=self.http.request('https://graphql.anilist.co',{'query':LOOKUP,'variables':{'mal':61967}})['data']['Media']
            if media and positive(media.get('idMal'))==61967:
                with self.state.db: self.state.anime(media)
                mal,_=self.chain(61967)
                token=os.environ.get('KODIK_API_TOKEN','')
                if mal==61967 and token:
                    query=urllib.parse.urlencode({'token':token,'shikimori_id':61967,'limit':50,'types':'anime,anime-serial'})
                    result=self.http.request('https://kodik-api.com/search?'+query,{},form=True)
                    rows=result['results']
                    if not isinstance(rows,list) or len(rows)>50: raise Failure('provider_shape')
                    with self.state.db:
                        for row in rows:
                            if positive(row.get('shikimori_id'))!=61967: raise Failure('probe_identity_conflict')
                            self.process('kodik',kodik_reference(row),shiki=61967,observed={k:row.get(k) for k in ('id','shikimori_id','kinopoisk_id')})
            else: self.state.error('mal:61967','not_found')
        except (Failure,KeyError,TypeError,ValueError,AttributeError,sqlite3.IntegrityError):
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
            after=batch[-1][0]
            accepted=[row for row in batch if kind!='link' or json.loads(row[1]).get('status')!='verified' or client_contract(json.loads(row[1]))]
            if accepted:send(accepted)
    return count

def verify_resolvers(state,http,limit=500):
    """Anon read-only RPCs, with EXACT Android identity fields and canonical corroboration."""
    key=os.environ.get('SUPABASE_PUBLISHABLE_KEY','')
    if not key:
        with state.db:state.advance('resolver_run',{'status':'not_run_missing_publishable_key','at':time.time()})
        return
    if not key.startswith('sb_publishable_'):
        state.error('resolver','publishable_key_format')
        with state.db:state.advance('resolver_run',{'status':'not_run_invalid_publishable_key','at':time.time()})
        return
    after=state.cursor('resolver_after');after=after if isinstance(after,str) else ''
    rows=state.db.execute("SELECT key,body FROM queue WHERE key>? AND json_extract(body,'$.status')='verified' ORDER BY key LIMIT ?",(after,limit)).fetchall()
    if not rows:
        after='';rows=state.db.execute("SELECT key,body FROM queue WHERE json_extract(body,'$.status')='verified' ORDER BY key LIMIT ?",(limit,)).fetchall()
    processed=0
    for row_key,body in rows:
        item=json.loads(body)
        if not client_contract(item):
            with state.db:state.advance('resolver_after',row_key)
            continue
        request=item['evidence']['client_identity']
        canonical={'p_anilist':item['anilist_id'],'p_mal':None,'p_provider':None,'p_external':None,'p_episode_external':None}
        stop=False
        try:
            expected=http.request(PROJECT+'/rest/v1/rpc/community_resolve',canonical,{'apikey':key})
            actual=http.request(PROJECT+'/rest/v1/rpc/community_resolve',request,{'apikey':key})
            if actual is None:status='unresolved'
            elif isinstance(actual,dict) and isinstance(expected,dict) and actual.get('anime_id') and actual['anime_id']==expected.get('anime_id') and actual.get('episode_id') is None:status='resolved'
            elif not isinstance(actual,dict) or not actual.get('anime_id'):raise Failure('resolver_response_shape')
            else:status='conflict'
        except Failure as exc:
            status='error';stop=True
            state.db.execute('INSERT INTO errors(source,code) VALUES(?,?)',('resolver',str(exc)))
        with state.db:
            state.db.execute('INSERT OR REPLACE INTO resolver_checks VALUES(?,?,?,?)',(row_key,hashlib.sha256(body.encode()).hexdigest(),status,time.time()))
            state.advance('resolver_after',row_key)
        processed+=1
        if stop:break
    with state.db:state.advance('resolver_run',{'status':'checked','at':time.time(),'checked_this_run':processed,'limit':limit})

def report(state,published=0):
    counts={'canonical':0,'verified':0,'pending':0,'rejected':0,'unpublished':0,'published_this_run':published}
    for body,ack in state.db.execute('SELECT body,ack FROM queue'):
        item=json.loads(body); counts['canonical' if item['kind']=='anime' else item['status']]+=1
        if ack is None: counts['unpublished']+=1
    verified=[(key,body,json.loads(body)) for key,body in state.db.execute("SELECT key,body FROM queue WHERE json_extract(body,'$.status')='verified'")]
    counts['verified_client_contract']=sum(client_contract(item) for _,_,item in verified)
    counts['verified_without_client_contract']=counts['verified']-counts['verified_client_contract']
    checks={key:(request_hash,status,at) for key,request_hash,status,at in state.db.execute('SELECT key,request_hash,status,checked FROM resolver_checks')}
    remote={'resolved':0,'unresolved':0,'conflict':0,'error':0,'not_checked':0}
    for key,body,item in verified:
        check=checks.get(key)
        if not check or check[0]!=hashlib.sha256(body.encode()).hexdigest() or time.time()-check[2]>86400 or not client_contract(item):remote['not_checked']+=1
        else:remote[check[1]]+=1
    counts['verified_resolves_community_resolve']=remote['resolved']
    counts['verified_other']=counts['verified']-remote['resolved']
    counts['resolver_results']=remote
    counts['resolver_verification']=state.cursor('resolver_run')
    if counts['resolver_verification']==1:counts['resolver_verification']={'status':'not_run'}
    counts['bridge_jobs']=state.db.execute('SELECT count(*) FROM bridge_jobs').fetchone()[0]
    counts['retry_links']=state.db.execute('SELECT count(*) FROM retry_links').fetchone()[0]
    counts['withheld_verified_without_client_contract']=counts['verified_without_client_contract']
    counts['canonical_acknowledged']=state.db.execute("SELECT count(*) FROM queue WHERE ack IS NOT NULL AND json_extract(body,'$.kind')='anime'").fetchone()[0]
    counts['server_batch_totals']={k:sum(json.loads(row[0]).get(k,0) for row in state.db.execute('SELECT body FROM receipts')) for k in ('canonical_upserted','verified_applied','pending','rejected')}
    counts['errors']=[{'source':s,'code':c,'count':n} for s,c,n in state.db.execute('SELECT source,code,count(*) FROM errors GROUP BY source,code')]
    counts['errors_this_run']=[{'source':s,'code':c,'count':n} for s,c,n in state.db.execute('SELECT source,code,count(*) FROM errors WHERE rowid>? GROUP BY source,code',(state.error_start,))]
    counts['mal_61967']= [json.loads(row[0]) for row in state.db.execute('SELECT body FROM canonical WHERE mal=61967')]
    counts['sources_not_scanned']={'jutsu':'URL_OR_SLUG only; no bulk API in current adapter','animelib':'No declared cross-catalog IDs in current DTOs','dreamerscast':'HTML adapter; no declared cross-catalog IDs','animedia':'HTML adapter; no declared cross-catalog IDs','animeon':'No declared cross-catalog IDs in current DTOs','sameband':'HTML adapter; no declared cross-catalog IDs','animebest':'HTML adapter; no declared cross-catalog IDs','all':'Synthetic aggregate release IDs; no provider-ID construction allowed'}
    counts['mal_61967_verified_aliases']=[json.loads(r[0]) for r in state.db.execute("SELECT body FROM queue WHERE json_extract(body,'$.status')='verified' AND json_extract(body,'$.anilist_id') IN (SELECT id FROM canonical WHERE mal=61967)")]
    counts['episode_policy']='No automatic episode matches: authoritative cross-source episode identity unavailable.'
    return counts

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['scan','publish','report','verify-resolve'])
    parser.add_argument('--state',default='autosync.sqlite')
    parser.add_argument('--pages',type=int,default=10)
    parser.add_argument('--report',default='autosync-report.json')
    args=parser.parse_args()
    if not 1<=args.pages<=100: parser.error('pages must be 1..100')
    state=State(args.state); http=Http(); published=0
    try:
        if args.command=='scan': Sync(state,http).run(args.pages)
        if args.command=='publish': published=publish(state,http)
        if args.command in {'scan','publish','verify-resolve'}:verify_resolvers(state,http)
    except Failure as exc:
        state.error(args.command,str(exc)); raise SystemExit(str(exc)) from None
    finally:
        Path(args.report).write_text(packed(report(state,published))+'\n')
        state.db.close()

if __name__=='__main__': main()
