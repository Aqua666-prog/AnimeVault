#!/usr/bin/env python3
"""Advisory public-source diagnosis. No GitHub/Supabase Secrets or Supabase requests.
Prints observations, NOT a successful canary result; canary keeps its own mandatory gates.
"""
import json
from pathlib import Path
import re
import urllib.request

import autosync as a
import canary as c

SCRIPT='https://kodik-add.com/add-players.min.js?v=2'


def observe(http,source,stage,url,payload=None,**kwargs):
    endpoint=url.split('?',1)[0]
    try:
        value=http.request(url,payload,**kwargs)
        return {'source':source,'stage':stage,'method':'POST' if payload is not None else 'GET','endpoint':endpoint,
                'http_status':http.last_http_status,'status':'response_received'},value
    except a.Failure as exc:
        return {**c.failure_diagnostic(exc,source,stage,'POST' if payload is not None else 'GET',endpoint),'status':'blocked'},None


def probe(http=None,fetch_public_token=True):
    http=http or a.Http(max_attempts=1)
    rows=[]
    for stage,variables in (('reproduce_explicit_null',{'id':None,'mal':c.MAL}),('lookup_by_mal',a.anilist_lookup_variables(mal=c.MAL)),('lookup_by_anilist',a.anilist_lookup_variables(anilist=c.ANILIST))):
        entry,result=observe(http,'anilist',stage,'https://graphql.anilist.co',{'query':a.LOOKUP,'variables':variables})
        if result is not None:
            data=result.get('data') if isinstance(result,dict) else None
            media=data.get('Media') if isinstance(data,dict) else None
            identity={k:media.get(k) for k in ('id','idMal')} if isinstance(media,dict) else None
            entry['identity']=identity
            entry['status']='confirmed' if identity=={'id':c.ANILIST,'idMal':c.MAL} else 'blocked'
            if entry['status']=='blocked':entry['error_type']='identity_unconfirmed'
        rows.append(entry)
    entry,result=observe(http,'shikimori','external_links',c.SHIKI)
    if isinstance(result,list):
        mals={a.mal_link(r.get('url','')) for r in result if isinstance(r,dict) and r.get('kind')=='myanimelist'}-{None}
        entry['mal_ids']=sorted(mals);entry['status']='confirmed' if mals=={c.MAL} else 'blocked'
        if entry['status']=='blocked':entry['error_type']='identity_unconfirmed'
    elif result is not None:entry.update(status='blocked',error_type='invalid_payload')
    rows.append(entry)
    if fetch_public_token:
        token=None
        token_entry={'source':'kodik','stage':'public_application_token','endpoint':SCRIPT.split('?',1)[0],'method':'GET','http_status':None,'status':'blocked'}
        try:
            opener=urllib.request.build_opener(a.NoRedirect())
            req=urllib.request.Request(SCRIPT,headers={'User-Agent':'AnimeVault-Catalog-Public-Diagnostic/1','Referer':'https://kodik.info/'})
            with opener.open(req,timeout=20) as response:
                token_entry['http_status']=response.status
                raw=response.read(2*1024*1024+1)
            if len(raw)>2*1024*1024:raise a.Failure('response_too_large')
            candidates=set(re.findall(r'''token\s*[=:]\s*["']([0-9a-f]+)["']''',raw.decode('utf-8')))
            if len(candidates)!=1:raise a.Failure('public_application_token_unavailable')
            token=next(iter(candidates))
        except a.urllib.error.HTTPError as exc:
            token_entry.update(c.failure_diagnostic(a.Failure('http_'+str(exc.code),http_status=exc.code),'kodik','public_application_token','GET',SCRIPT.split('?',1)[0]))
        except Exception:token_entry['error_type']='public_application_token_unavailable'
        if token:
            query=a.urllib.parse.urlencode({'token':token,'shikimori_id':c.MAL,'limit':50,'types':'anime,anime-serial'})
            entry,result=observe(http,'kodik','primary_record','https://kodik-api.com/search?'+query,{},form=True)
            items=result.get('results') if isinstance(result,dict) else None
            if isinstance(items,list):
                valid=bool(items) and all(isinstance(item,dict) and a.positive(item.get('shikimori_id'))==c.MAL and a.kodik_reference(item)==c.EXTERNAL for item in items)
                entry['status']='confirmed' if valid else 'blocked'
                entry['matching_records']=len(items) if valid else 0
                entry['client_external_id']=c.EXTERNAL if valid else None
                if not valid:entry['error_type']='identity_unconfirmed'
            elif result is not None:entry.update(status='blocked',error_type='invalid_payload')
            rows.append(entry)
        else:rows.append(token_entry)
    return {'purpose':'public_diagnostic_only_not_a_canary_success','supabase_requests':0,'observations':rows}


def main():
    report=probe()
    Path('canary-public-probe.json').write_text(a.packed(report)+'\n')
    print(a.packed(report))
    # External availability is an observation, not a code-test result. No canary gate consumes this output.

if __name__=='__main__':main()
