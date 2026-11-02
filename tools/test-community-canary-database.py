#!/usr/bin/env python3
"""Canary RPC integration in fresh disposable loopback databases, never Supabase."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit,parse_qs,urlunsplit
from uuid import uuid4
import psycopg
from psycopg import sql

DSN=os.environ.get('COMMUNITY_TEST_DATABASE_URL','')
if urlsplit(DSN).hostname not in {'localhost','127.0.0.1','::1'}:raise SystemExit('Disposable loopback database required')
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('canary_fixture',ROOT/'tools/test_community_canary.py')
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
c=fixture.c

class Rpc:
    def __init__(self,db):self.db=db;self.writes=0;self.before_write=None;self.bad_resolve=False;self.lose_response=False
    def request(self,url,payload=None,headers=None,**kwargs):
        if url=='https://graphql.anilist.co':return fixture.live()[0]
        if url==c.SHIKI:return fixture.live()[1]
        if url.startswith('https://kodik-api.com/search?'):return fixture.live()[2]
        parsed=urlsplit(url);role='service_role' if (headers or {}).get('Authorization') else 'anon'
        if parsed.path.endswith('/community_sync_batch') and self.before_write:
            self.before_write();self.before_write=None
        self.db.execute('SET ROLE '+role)
        self.db.execute("SELECT set_config('request.jwt.claim.role',%s,false)",(role,))
        try:
            if parsed.path.endswith('/community_sync_batch'):
                self.writes+=1
                result=self.db.execute('SELECT public.community_sync_batch(%s,%s::jsonb)',(payload['p_batch'],json.dumps(payload['p_items']))).fetchone()[0]
                if self.lose_response:
                    self.lose_response=False;raise c.a.Failure('network_or_json')
                return result
            if parsed.path.endswith('/community_resolve'):
                if self.bad_resolve and payload['p_provider']:return None
                return self.db.execute('SELECT public.community_resolve(p_anilist=>%s,p_mal=>%s,p_provider=>%s,p_external=>%s,p_episode_external=>%s)',tuple(payload[k] for k in ('p_anilist','p_mal','p_provider','p_external','p_episode_external'))).fetchone()[0]
            table=parsed.path.rsplit('/',1)[1];query=parse_qs(parsed.query)
            if table=='community_anime':allowed={'id','anilist_id','mal_id','canonical_title'}
            elif table=='community_anime_aliases':allowed={'anime_id','provider','external_id','verified'}
            else:raise AssertionError('Unexpected table')
            columns=query['select'][0].split(',');filters={k:v[0] for k,v in query.items() if k not in {'select','limit'}}
            if not set(columns)<=allowed or not set(filters)<=allowed:raise AssertionError('Unexpected column')
            conditions=[];args=[]
            for key,value in filters.items():
                if not value.startswith('eq.'):raise AssertionError('Unexpected filter')
                conditions.append(sql.SQL('{}=%s').format(sql.Identifier(key)));args.append(value[3:])
            statement=sql.SQL('SELECT {} FROM public.{} WHERE {} LIMIT 2').format(sql.SQL(',').join(map(sql.Identifier,columns)),sql.Identifier(table),sql.SQL(' AND ').join(conditions))
            return [{k:str(v) if hasattr(v,'hex') and not isinstance(v,str) else v for k,v in zip(columns,row)} for row in self.db.execute(statement,args)]
        finally:self.db.execute('RESET ROLE')

class CanaryDatabaseTest(unittest.TestCase):
    def setUp(self):
        self.master=psycopg.connect(DSN,autocommit=True)
        for role in ('anon','authenticated','service_role'):
            if not self.master.execute('SELECT 1 FROM pg_roles WHERE rolname=%s',(role,)).fetchone():
                self.master.execute(sql.SQL('CREATE ROLE {} NOLOGIN {}').format(sql.Identifier(role),sql.SQL('BYPASSRLS' if role=='service_role' else '')))
        self.name='av_canary_'+uuid4().hex[:12];self.master.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(self.name)))
        parsed=urlsplit(DSN);url=urlunsplit((parsed.scheme,parsed.netloc,'/'+self.name,parsed.query,''))
        self.db=psycopg.connect(url,autocommit=True);self.addCleanup(self.cleanup)
        self.db.execute('CREATE SCHEMA auth; CREATE TABLE auth.users(id uuid PRIMARY KEY)')
        self.db.execute("CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT NULL::uuid $$; CREATE FUNCTION auth.role() RETURNS text LANGUAGE sql STABLE AS $$ SELECT current_setting('request.jwt.claim.role',true) $$")
        for version in ('202611010001_community.sql','202611010002_community_autosync.sql'):self.db.execute((ROOT/'supabase/migrations'/version).read_text())
        # Supabase normally grants service_role table access via platform defaults; minimal Auth shim does not.
        self.db.execute('GRANT SELECT ON public.community_anime,public.community_anime_aliases TO service_role')
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.state=c.a.State(Path(self.temp.name)/'state.sqlite');self.addCleanup(self.state.db.close)
        self.state.anime({'id':195604,'idMal':61967,'title':{'romaji':'Black Clover 2nd Season'}})
        self.state.put(c.ALIAS_KEY,fixture.pair()[1]);self.state.db.commit()
        env={'GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'workflow_dispatch','KODIK_API_TOKEN':'fixture','SUPABASE_SERVICE_ROLE_KEY':'fixture',
             'SUPABASE_PUBLISHABLE_KEY':'sb_publishable_fixture','CANARY_CONFIRMATION':c.CONFIRMATION,'COMMUNITY_AUTOSYNC_WRITE_ENABLED':'true'}
        patcher=patch.dict(os.environ,env,clear=True);patcher.start();self.addCleanup(patcher.stop)
        self.http=Rpc(self.db)

    def cleanup(self):
        self.db.close();self.master.execute(sql.SQL('DROP DATABASE {}').format(sql.Identifier(self.name)));self.master.close()

    def other(self):
        return self.db.execute("INSERT INTO public.community_anime(anilist_id,mal_id,canonical_title) VALUES(1,2,'Unrelated') RETURNING id").fetchone()[0]

    def apply(self):
        value=c.plan(self.state,self.http)
        return c.publish_canary(value,self.http,value['plan_hash'])

    def test_one_pair_then_repeat_noop_and_unrelated_untouched(self):
        other=self.other();self.db.execute("INSERT INTO public.community_anime_aliases(anime_id,provider,external_id,verified) VALUES(%s,'kodik','shiki:2',true)",(other,))
        self.assertEqual('success',self.apply()['status']);self.assertEqual(1,self.http.writes)
        self.assertEqual(2,self.db.execute('SELECT count(*) FROM public.community_anime').fetchone()[0])
        self.assertEqual(2,self.db.execute('SELECT count(*) FROM public.community_anime_aliases').fetchone()[0])
        self.assertEqual(0,self.db.execute('SELECT count(*) FROM public.community_episodes').fetchone()[0])
        self.assertEqual('Unrelated',self.db.execute('SELECT canonical_title FROM public.community_anime WHERE id=%s',(other,)).fetchone()[0])
        self.assertEqual('success',self.apply()['status']);self.assertEqual(1,self.http.writes)
        self.assertEqual(1,self.db.execute('SELECT count(*) FROM public.community_sync_batches').fetchone()[0])

    def test_hidden_foreign_alias_blocks_all_mutations(self):
        other=self.other();self.db.execute("INSERT INTO public.community_anime_aliases(anime_id,provider,external_id,verified) VALUES(%s,'kodik','shiki:61967',false)",(other,))
        with self.assertRaisesRegex(c.a.Failure,'remote_alias_conflict'):self.apply()
        self.assertEqual(0,self.http.writes)
        self.assertEqual(1,self.db.execute('SELECT count(*) FROM public.community_anime').fetchone()[0])
        self.assertEqual((other,False),self.db.execute("SELECT anime_id,verified FROM public.community_anime_aliases WHERE external_id='shiki:61967'").fetchone())

    def test_concurrent_alias_conflict_never_reassigns_or_reports_success(self):
        other=self.other()
        def race():self.db.execute("INSERT INTO public.community_anime_aliases(anime_id,provider,external_id,verified) VALUES(%s,'kodik','shiki:61967',false)",(other,))
        self.http.before_write=race
        with self.assertRaisesRegex(c.a.Failure,'batch_not_accepted'):self.apply()
        self.assertEqual((other,False),self.db.execute("SELECT anime_id,verified FROM public.community_anime_aliases WHERE external_id='shiki:61967'").fetchone())
        # Existing RPC rejects the alias candidate but may commit its ONE canonical upsert. Never claim rollback/success.
        self.assertEqual(1,self.db.execute('SELECT count(*) FROM public.community_anime WHERE anilist_id=195604').fetchone()[0])

    def test_lost_response_then_new_run_does_not_duplicate(self):
        self.http.lose_response=True
        with self.assertRaises(c.a.Failure):self.apply()
        self.assertEqual('success',self.apply()['status'])
        self.assertEqual(1,self.http.writes)
        self.assertEqual(1,self.db.execute('SELECT count(*) FROM public.community_anime').fetchone()[0])
        self.assertEqual(1,self.db.execute('SELECT count(*) FROM public.community_anime_aliases').fetchone()[0])
        self.assertEqual(1,self.db.execute('SELECT count(*) FROM public.community_sync_batches').fetchone()[0])

    def test_post_resolver_failure_is_not_a_successful_canary(self):
        self.http.bad_resolve=True
        with self.assertRaisesRegex(c.a.Failure,'post_resolve'):self.apply()
        self.assertEqual(1,self.http.writes)

if __name__=='__main__':unittest.main()
