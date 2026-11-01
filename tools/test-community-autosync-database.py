#!/usr/bin/env python3
"""Run after test-community-database.py, only on that disposable loopback database."""
import importlib.util
import json
import os
from pathlib import Path
import unittest
from urllib.parse import urlparse
from uuid import uuid4
import psycopg

DSN=os.environ.get('COMMUNITY_TEST_DATABASE_URL','')
if urlparse(DSN).hostname not in {'localhost','127.0.0.1','::1'}:
    raise SystemExit('Disposable loopback database required')
ROOT=Path(__file__).resolve().parents[1]

class AutoSyncDatabaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with psycopg.connect(DSN,autocommit=True) as db:
            db.execute('CREATE ROLE service_role NOLOGIN BYPASSRLS')
            db.execute("CREATE FUNCTION auth.role() RETURNS text LANGUAGE sql STABLE AS $$ SELECT current_setting('request.jwt.claim.role',true) $$")
            db.execute((ROOT/'supabase/migrations/202611010002_community_autosync.sql').read_text())

    def setUp(self):
        self.db=psycopg.connect(DSN,autocommit=True);self.addCleanup(self.db.close)
        self.al=uuid4().int%1000000000+1000000000;self.mal=uuid4().int%1000000000+1000000000
        self.anime={'kind':'anime','anilist_id':self.al,'mal_id':self.mal,'title':'AutoSync test'}
        self.external=str(uuid4())
        self.link={'kind':'link','provider':'yummy','external_id':self.external,'anilist_id':self.al,'status':'verified','evidence':{'method':'direct_id','mal_id':self.mal}}

    def rpc(self,items,batch=None,role='service_role'):
        self.db.execute('SET ROLE '+role)
        self.db.execute("SELECT set_config('request.jwt.claim.role',%s,false)",(role,))
        try:return self.db.execute('SELECT public.community_sync_batch(%s,%s::jsonb)',(batch or uuid4(),json.dumps(items))).fetchone()[0]
        finally:self.db.execute('RESET ROLE')

    def test_replay_and_existing_android_resolver(self):
        batch=uuid4();self.assertFalse(self.rpc([self.anime,self.link],batch)['replayed'])
        self.assertTrue(self.rpc([self.anime,self.link],batch)['replayed'])
        self.assertEqual(1,self.db.execute('SELECT count(*) FROM public.community_anime WHERE anilist_id=%s',(self.al,)).fetchone()[0])
        self.db.execute('SET ROLE anon')
        result=self.db.execute("SELECT public.community_resolve(p_provider=>'yummy',p_external=>%s)",(self.external,)).fetchone()[0]
        self.assertIsNotNone(result['anime_id'])

    def test_private_tables_and_execute(self):
        for role in ('anon','authenticated'):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): self.rpc([self.anime],role=role)
            for table in ('community_sync_evidence','community_sync_batches'):
                for privilege in ('SELECT','INSERT','UPDATE','DELETE'):
                    self.assertFalse(self.db.execute('SELECT has_table_privilege(%s,%s,%s)',(role,'public.'+table,privilege)).fetchone()[0])
                self.assertTrue(self.db.execute('SELECT relrowsecurity FROM pg_class WHERE oid=%s::regclass',('public.'+table,)).fetchone()[0])
        self.assertFalse(self.db.execute("SELECT has_table_privilege('service_role','public.community_sync_evidence','INSERT')").fetchone()[0])
        owner,path=self.db.execute("SELECT pg_get_userbyid(proowner),proconfig FROM pg_proc WHERE oid='public.community_sync_batch(uuid,jsonb)'::regprocedure").fetchone()
        self.assertEqual('postgres',owner);self.assertIn('search_path=""',path)

    def test_atomic_rollback_on_conflict(self):
        self.rpc([self.anime]);bad={**self.anime,'anilist_id':self.al+1}
        other={**self.anime,'anilist_id':self.al+2,'mal_id':None}
        with self.assertRaises(psycopg.errors.InvalidParameterValue): self.rpc([other,bad])
        self.assertEqual(0,self.db.execute('SELECT count(*) FROM public.community_anime WHERE anilist_id=%s',(self.al+2,)).fetchone()[0])

    def test_no_alias_reassignment(self):
        self.rpc([self.anime,self.link]);other={**self.anime,'anilist_id':self.al+1,'mal_id':None}
        self.rpc([other,{**self.link,'anilist_id':self.al+1}])
        self.assertEqual(self.al,self.db.execute('SELECT a.anilist_id FROM public.community_anime a JOIN public.community_anime_aliases l ON l.anime_id=a.id WHERE l.external_id=%s',(self.external,)).fetchone()[0])
        self.assertEqual('rejected',self.db.execute('SELECT status FROM public.community_sync_evidence WHERE external_id=%s',(self.external,)).fetchone()[0])

    def test_pending_never_published(self):
        self.rpc([{**self.link,'status':'pending','anilist_id':None}])
        self.assertEqual(0,self.db.execute('SELECT count(*) FROM public.community_anime_aliases WHERE external_id=%s',(self.external,)).fetchone()[0])

    def test_cannot_self_declare_worker_role(self):
        self.db.execute('SET ROLE authenticated')
        self.db.execute("SELECT set_config('request.jwt.claim.role','service_role',false)")
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            self.db.execute('SELECT public.community_sync_batch(%s,%s::jsonb)',(uuid4(),json.dumps([self.anime])))
        self.db.execute('RESET ROLE')

    def test_batch_reuse_different_payload(self):
        batch=uuid4();self.rpc([self.anime],batch)
        with self.assertRaises(psycopg.errors.InvalidParameterValue):self.rpc([{**self.anime,'title':'Different'}],batch)

    def test_limits_and_proof(self):
        for items in ([],[self.anime]*101,[{**self.link,'evidence':{'method':'title'}}]):
            with self.assertRaises(psycopg.errors.InvalidParameterValue):self.rpc(items)

if __name__=='__main__':unittest.main()
