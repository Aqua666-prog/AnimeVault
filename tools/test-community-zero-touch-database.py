#!/usr/bin/env python3
"""Disposable PostgreSQL17 only; runs after existing base + autosync SQL suites."""
import concurrent.futures
import json
import os
from pathlib import Path
from urllib.parse import urlparse
from uuid import uuid4
import unittest
import psycopg
DSN=os.environ.get('COMMUNITY_TEST_DATABASE_URL','')
if urlparse(DSN).hostname not in {'localhost','127.0.0.1','::1'}:raise SystemExit('Disposable loopback database required')
ROOT=Path(__file__).resolve().parents[1]

class ZeroTouchDatabaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with psycopg.connect(DSN,autocommit=True) as db:
            if not db.execute("SELECT to_regclass('public.community_auto_settings')").fetchone()[0]:db.execute((ROOT/'supabase/migrations/202611020001_community_zero_touch.sql').read_text())
    def setUp(self):
        self.db=psycopg.connect(DSN,autocommit=True);self.addCleanup(self.db.close)
        self.db.execute('UPDATE public.community_auto_settings SET on_demand_enabled=false,background_enabled=false,per_minute=10,per_day=200')
        self.db.execute('DELETE FROM public.community_auto_budget')
        self.al=uuid4().int%100000000+1000000000;self.mal=self.al+100000000
        self.identity={'p_provider':'animetka','p_external':str(self.al),'p_anilist':None,'p_mal':None,'p_episode_external':None}
    def rpc(self,name,args,role='service_role',db=None):
        db=db or self.db;db.execute('SET ROLE '+role);db.execute("SELECT set_config('request.jwt.claim.role',%s,false)",(role,))
        try:
            placeholders=','.join('%s::jsonb' if isinstance(v,(dict,list)) else '%s' for v in args)
            return db.execute('SELECT public.'+name+'('+placeholders+')',[json.dumps(v) if isinstance(v,(dict,list)) else v for v in args]).fetchone()[0]
        finally:db.execute('RESET ROLE')
    def enable(self):self.db.execute('UPDATE public.community_auto_settings SET on_demand_enabled=true')
    def claim(self,identity=None,**kwargs):return self.rpc('community_auto_claim',[identity or self.identity,'a'*64,False],**kwargs)
    def items(self,identity=None):
        i=identity or self.identity
        return [{'kind':'anime','anilist_id':self.al,'mal_id':self.mal,'title':'Zero touch fixture'},{'kind':'link','provider':i['p_provider'],'external_id':i['p_external'],'anilist_id':self.al,'status':'verified','evidence':{'method':'direct_id','mal_id':self.mal}}]
    def complete(self,claim,items=None,**kwargs):return self.rpc('community_auto_complete',[claim['key'],claim['lease'],items or self.items()],**kwargs)
    def test_defaults_off_no_api_budget_consumption(self):
        self.assertEqual(self.claim()['status'],'disabled');self.assertEqual(0,self.db.execute('SELECT count(*) FROM public.community_auto_budget').fetchone()[0])
    def test_anon_cannot_claim_complete_finish_pending_or_read_jobs(self):
        for role in ('anon','authenticated'):
            for name,args in [('community_auto_claim',[self.identity,'a'*64,False]),('community_auto_complete',['k',uuid4(),self.items()]),('community_auto_finish',['k',uuid4(),'test',False]),('community_auto_pending',[])]:
                with self.assertRaises(psycopg.errors.InsufficientPrivilege):self.rpc(name,args,role=role)
            for table in ('community_auto_settings','community_auto_jobs','community_auto_budget'):
                self.assertFalse(self.db.execute('SELECT has_table_privilege(%s,%s,\'SELECT\')',(role,'public.'+table)).fetchone()[0])
                self.assertTrue(self.db.execute('SELECT relrowsecurity FROM pg_class WHERE oid=%s::regclass',('public.'+table,)).fetchone()[0])
    def test_claim_deduplicates_simultaneous_open(self):
        self.enable();claim=self.claim();self.assertEqual(claim['status'],'claimed');self.assertEqual(self.claim()['status'],'pending')
    def test_first_create_and_repeated_completion_no_duplicate(self):
        self.enable();claim=self.claim();context=self.complete(claim)
        with self.assertRaises(psycopg.errors.InvalidParameterValue):self.complete(claim)
        self.assertEqual(1,self.db.execute('SELECT count(*) FROM public.community_anime WHERE anilist_id=%s',(self.al,)).fetchone()[0])
        actual=self.rpc('community_resolve',[None,None,self.identity['p_provider'],self.identity['p_external'],None],role='anon')
        self.assertEqual(actual['anime_id'],context['anime_id'])
    def test_emergency_off_between_verification_and_write(self):
        self.enable();claim=self.claim();self.db.execute('UPDATE public.community_auto_settings SET on_demand_enabled=false')
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):self.complete(claim)
        self.assertEqual(0,self.db.execute('SELECT count(*) FROM public.community_anime WHERE anilist_id=%s',(self.al,)).fetchone()[0])
    def test_expired_lease_cannot_complete(self):
        self.enable();claim=self.claim();self.db.execute("UPDATE public.community_auto_jobs SET next_attempt_at=now()-interval '1 second' WHERE key=%s",(claim['key'],))
        with self.assertRaises(psycopg.errors.InvalidParameterValue):self.complete(claim)
    def test_client_metadata_conflict_never_writes(self):
        self.identity['p_mal']=self.mal+1;self.enable();claim=self.claim()
        with self.assertRaises(psycopg.errors.InvalidParameterValue):self.complete(claim)
    def test_foreign_alias_conflict_is_atomic_and_preserved(self):
        self.enable();claim=self.claim();first=self.complete(claim)
        second={**self.identity,'p_anilist':self.al+1};claim2=self.claim(second)
        bad=self.items();bad[0]['anilist_id']=self.al+1;bad[0]['mal_id']=self.mal+1;bad[1]['anilist_id']=self.al+1
        with self.assertRaises(psycopg.errors.InvalidParameterValue):self.complete(claim2,bad)
        self.assertEqual(0,self.db.execute('SELECT count(*) FROM public.community_anime WHERE anilist_id=%s',(self.al+1,)).fetchone()[0])
        self.assertEqual(first['anime_id'],self.rpc('community_resolve',[None,None,self.identity['p_provider'],self.identity['p_external'],None],role='anon')['anime_id'])
    def test_global_and_client_rate_budgets(self):
        self.enable();self.db.execute('UPDATE public.community_auto_settings SET per_minute=1')
        self.assertEqual(self.claim()['status'],'claimed')
        other={**self.identity,'p_external':str(self.al+1)};self.assertEqual(self.claim(other)['status'],'rate_limited')
    def test_transient_failure_is_durably_queued_and_background_off(self):
        self.enable();claim=self.claim();self.rpc('community_auto_finish',[claim['key'],claim['lease'],'http_503',True])
        self.db.execute("UPDATE public.community_auto_jobs SET next_attempt_at=now()-interval '1 second' WHERE key=%s",(claim['key'],))
        self.assertEqual(self.rpc('community_auto_pending',[]),[])
        self.db.execute('UPDATE public.community_auto_settings SET background_enabled=true')
        self.assertIn(self.identity,self.rpc('community_auto_pending',[]))
    def test_new_lease_invalidates_stale_response(self):
        self.enable();old=self.claim();self.db.execute("UPDATE public.community_auto_jobs SET next_attempt_at=now()-interval '1 second' WHERE key=%s",(old['key'],));new=self.claim()
        with self.assertRaises(psycopg.errors.InvalidParameterValue):self.complete(old)
        self.complete(new)
    def test_different_providers_concurrent_same_canonical(self):
        self.enable();a=self.claim();other={**self.identity,'p_provider':'yummy'};b=self.claim(other)
        def finish(claim,items):
            with psycopg.connect(DSN,autocommit=True) as db:return self.complete(claim,items,db=db)
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            one=pool.submit(finish,a,self.items());two=pool.submit(finish,b,self.items(other));self.assertEqual(one.result()['anime_id'],two.result()['anime_id'])
    def test_episode_requests_and_unsupported_provider_refused(self):
        self.enable()
        for value in ({**self.identity,'p_episode_external':'12'},{**self.identity,'p_provider':'all'}):
            with self.assertRaises(psycopg.errors.InvalidParameterValue):self.claim(value)
    def test_owner_search_path_and_no_direct_service_write(self):
        for name in ('community_auto_claim','community_auto_complete','community_auto_finish','community_auto_pending'):
            owner,path=self.db.execute("SELECT pg_get_userbyid(proowner),proconfig FROM pg_proc WHERE proname=%s",(name,)).fetchone();self.assertEqual(owner,'postgres');self.assertIn('search_path=""',path)
        self.assertFalse(self.db.execute("SELECT has_table_privilege('service_role','public.community_auto_settings','UPDATE')").fetchone()[0])

    def test_background_retires_jobs_completed_by_another_path(self):
        self.enable();claim=self.claim();self.complete(claim)
        self.db.execute("UPDATE public.community_auto_jobs SET state='queued',next_attempt_at=now()-interval '1 second' WHERE key=%s",(claim['key'],))
        self.db.execute('UPDATE public.community_auto_settings SET background_enabled=true')
        self.assertNotIn(self.identity,self.rpc('community_auto_pending',[]))
        self.assertEqual('resolved',self.db.execute('SELECT state FROM public.community_auto_jobs WHERE key=%s',(claim['key'],)).fetchone()[0])
    def test_existing_comments_replies_and_likes_survive_new_alias(self):
        self.enable()
        canonical=self.db.execute("INSERT INTO public.community_anime(anilist_id,mal_id,canonical_title) VALUES(%s,%s,'Existing') RETURNING id",(self.al,self.mal)).fetchone()[0]
        user=uuid4();self.db.execute('INSERT INTO auth.users VALUES(%s)',(user,));self.db.execute("INSERT INTO public.community_profiles(user_id,username,display_name) VALUES(%s,%s,'Fixture')",(user,'u'+uuid4().hex[:20]))
        self.db.execute("SELECT set_config('request.jwt.claim.sub',%s,false)",(str(user),))
        root=self.rpc('community_create_comment',[canonical,None,None,'Keep root',False,uuid4()],role='authenticated')
        self.rpc('community_create_comment',[canonical,None,root,'Keep reply',False,uuid4()],role='authenticated')
        self.rpc('community_set_like',[root,True],role='authenticated')
        before=self.rpc('community_list_comments',[canonical,None,None,'new',0,20],role='anon')
        self.complete(self.claim())
        after=self.rpc('community_list_comments',[canonical,None,None,'new',0,20],role='anon')
        self.assertEqual(before,after);self.assertEqual('Keep root',after['comments'][0]['body']);self.assertEqual(1,after['comments'][0]['likes'])
    def test_different_seasons_cannot_collapse(self):
        self.enable();first=self.complete(self.claim())
        self.al+=1;self.mal+=1;self.identity['p_external']=str(self.al)
        second=self.complete(self.claim());self.assertNotEqual(first['anime_id'],second['anime_id'])
    def test_client_limit_and_day_budget(self):
        self.enable()
        for index in range(3):self.assertEqual(self.claim({**self.identity,'p_external':str(self.al+index)})['status'],'claimed')
        self.assertEqual(self.claim({**self.identity,'p_external':str(self.al+10)})['status'],'rate_limited')
        self.db.execute("UPDATE public.community_auto_budget SET used=200 WHERE client_hash=''")
        self.assertEqual(self.claim({**self.identity,'p_external':str(self.al+20)})['status'],'rate_limited')

if __name__=='__main__':unittest.main()
