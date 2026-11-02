import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent/'community_catalog'))
import batch as b
sys.path.pop(0)

class BatchTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.state=b.a.State(str(Path(self.tmp.name)/'state'));self.addCleanup(self.state.db.close)
        self.done={};self.path=Path(self.tmp.name)/'checkpoint'
        self.env={'GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_RUN_ID':'1','GITHUB_RUN_ATTEMPT':'1','COMMUNITY_AUTOSYNC_WRITE_ENABLED':'true','COMMUNITY_AUTOSYNC_DAILY_WRITE':'false','BATCH_CONFIRMATION':'PUBLISH BATCH 5'}
        self.headers=patch.multiple(b.c,public_headers=lambda:{},admin_headers=lambda:{})
        self.headers.start();self.addCleanup(self.headers.stop)
        self.context=patch.dict(os.environ,self.env);self.context.start();self.addCleanup(self.context.stop)
        for i in range(1,110):self.state.anime({'id':i,'idMal':1000+i,'title':{'romaji':'Fixture'}})
        self.state.db.commit()
    def plan(self,n=5):
        with patch.object(b,'refresh',side_effect=lambda x,h:x),patch.object(b,'snapshot',return_value={}):return b.plan(self.state,None,n,{})
    def apply(self,value,responses=None,resolve=None):
        class Http:
            max_attempts=5
        calls=[]
        def send(http,source,stage,url,payload,headers):
            calls.append(payload)
            if responses:
                response=responses.pop(0)
                if isinstance(response,Exception):raise response
                return response
            return {'processed':len(payload['p_items']),'canonical_upserted':1,'verified_applied':0,'rejected':0,'pending':0}
        def snapshot(items,*args):
            if getattr(self,'remote_complete',False):return {'canonical':{'id':str(items[0]['anilist_id']),'anilist_id':items[0]['anilist_id'],'mal_id':items[0]['mal_id']},'alias':None}
            return {}
        with patch.object(b,'refresh',side_effect=lambda x,h:x),patch.object(b,'snapshot',side_effect=snapshot),patch.object(b.c,'request',side_effect=send),patch.object(b,'resolve',side_effect=resolve):
            result=b.publish(value,Http(),value['plan_hash'],self.done,self.path)
        return result,calls
    def test_all_real_record_limits(self):
        for n in b.LIMITS:
            with self.subTest(n=n):
                value=self.plan(n);self.assertEqual(value['records'],n)
                os.environ['BATCH_CONFIRMATION']='PUBLISH BATCH '+str(n)
                result,calls=self.apply(value)
                self.assertEqual(sum(len(c['p_items']) for c in calls),n)
                self.assertEqual(result['published'],n);self.done.clear()
    def test_repeat_checkpoint_no_requests(self):
        value=self.plan();self.apply(value)
        self.remote_complete=True
        result,calls=self.apply(value);self.assertEqual(calls,[])
        self.assertEqual(len(b.checkpoint(self.path)),5)
    def test_partial_failure_checkpoints_only_confirmed(self):
        value=self.plan();ok={'processed':1,'canonical_upserted':1,'verified_applied':0,'pending':0,'rejected':0}
        result,calls=self.apply(value,[ok,b.a.Failure('http_503')])
        self.assertEqual(result['status'],'blocked');self.assertEqual(len(calls),2);self.assertEqual(len(self.done),1)
    def test_rejected_receipt_not_acknowledged(self):
        value=self.plan();result,_=self.apply(value,[{'processed':1,'canonical_upserted':1,'verified_applied':0,'pending':0,'rejected':1}])
        self.assertEqual(result['resolved'],0);self.assertEqual(self.done,{})
    def test_failed_resolver_not_checkpointed(self):
        result,_=self.apply(self.plan(),resolve=b.a.Failure('resolver_wrong'))
        self.assertEqual(result['status'],'blocked');self.assertEqual(self.done,{})
    def test_untrusted_and_missing_confirmation_no_write(self):
        value=self.plan()
        for key in ('GITHUB_REF','GITHUB_EVENT_NAME','BATCH_CONFIRMATION','COMMUNITY_AUTOSYNC_WRITE_ENABLED'):
            with patch.dict(os.environ,{key:'invalid'}),self.assertRaises(b.a.Failure):self.apply(value)
    def test_plan_mutation_and_oversize(self):
        value=self.plan();value['groups'].append(value['groups'][0])
        with self.assertRaises(b.a.Failure):self.apply(value)
        value['records']=6;value['plan_hash']=b.c.fingerprint({k:v for k,v in value.items() if k!='plan_hash'})
        with self.assertRaises(b.a.Failure):self.apply(value)
    def test_canonical_conflict_prevents_write(self):
        value=self.plan()
        with patch.object(b,'snapshot',side_effect=b.a.Failure('batch_alias_conflict')),patch.object(b,'refresh'):
            with patch.object(b.c,'request') as request:
                result=b.publish(value,object(),value['plan_hash'],{},self.path)
                request.assert_not_called();self.assertEqual(result['status'],'blocked')
    def test_no_title_matching_and_no_episodes(self):
        anime={'kind':'anime','anilist_id':1,'mal_id':1001,'title':'Same name'}
        class Http:
            def request(self,*args,**kwargs):return {'data':{'Media':{'id':1,'idMal':999}}}
        with self.assertRaises(b.a.Failure):b.refresh([anime],Http())
    def test_pending_rejected_not_selected(self):
        for status in ('pending','rejected'):
            self.state.put(status,{'kind':'link','status':status,'anilist_id':1})
        value=self.plan();self.assertTrue(all(i['kind']=='anime' for g in value['groups'] for i in g['items']))
    def test_missing_mal_excluded(self):
        self.state.anime({'id':999,'title':{'romaji':'Unknown'}})
        value=self.plan(100);self.assertNotIn(999,[g['items'][0]['anilist_id'] for g in value['groups']])

    def test_pairs_count_both_records_and_pending_never_sent(self):
        for i in range(1,8):
            external='shiki:'+str(1000+i)
            link={'kind':'link','provider':'kodik','external_id':external,'anilist_id':i,'status':'verified','evidence':{'client_contract':'android-release-id-v1','client_identity':b.a.client_identity('kodik',external),'observed_identity':{'id':'serial-'+str(i),'shikimori_id':1000+i}}}
            self.state.put('link:kodik:'+external,link)
        value=self.plan(5)
        self.assertEqual([2,2,1],[len(g['items']) for g in value['groups']])
        # Exercise real submitted record limit with valid synthetic receipts.
        responses=[{'processed':len(g['items']),'canonical_upserted':1,'verified_applied':len(g['items'])-1,'pending':0,'rejected':0} for g in value['groups']]
        result,calls=self.apply(value,responses)
        self.assertEqual(result['submitted'],5);self.assertEqual(result['resolved'],5)

    def test_actual_preflight_rejects_foreign_alias(self):
        items=[{'kind':'anime','anilist_id':1,'mal_id':1001,'title':'Fixture'},{'provider':'kodik','external_id':'shiki:1001'}]
        with patch.object(b.c,'select',side_effect=[{'id':'own','anilist_id':1,'mal_id':1001},{'id':'own'},{'anime_id':'foreign','verified':True}]):
            with self.assertRaisesRegex(b.a.Failure,'alias_conflict'):b.snapshot(items,None,{})

    def test_primary_chain_refuses_missing_material_even_with_same_title(self):
        items=[{'kind':'anime','anilist_id':1,'mal_id':1001,'title':'Fixture'},
               {'kind':'link','provider':'kodik','external_id':'shiki:1001','anilist_id':1,'status':'verified','evidence':{'source':'kodik','method':'external_link_chain','mal_id':1001,'shikimori_id':1001,'url':'https://shikimori.io/api/animes/1001/external_links','client_contract':'android-release-id-v1','client_identity':b.a.client_identity('kodik','shiki:1001'),'observed_identity':{'id':'serial-123','shikimori_id':1001}}}]
        values=[{'data':{'Media':{'id':1,'idMal':1001}}},[{'kind':'myanimelist','url':'https://myanimelist.net/anime/1001'}],{'results':[{'id':'serial-other','shikimori_id':1001,'title':'Fixture'}]}]
        with patch.dict(os.environ,{'KODIK_API_TOKEN':'fixture'}),patch.object(b.c,'request',side_effect=values):
            with self.assertRaisesRegex(b.a.Failure,'observation_stale'):b.refresh(items,None)

    def test_workflow_batch_requires_protected_main_dispatch_and_never_daily(self):
        workflow=(Path(__file__).parents[1]/'.github/workflows/community-catalog-autosync.yml').read_text()
        self.assertNotIn('\n  publish:\n',workflow)
        self.assertIn("inputs.mode != 'batch'",workflow)
        plan,job=workflow.split('  batch_plan:',1)[1].split('  batch_publish:',1)
        for text in ("inputs.mode == 'batch'","workflow_dispatch","refs/heads/main","inputs.apply == true","inputs.batch_confirmation == format", "COMMUNITY_AUTOSYNC_DAILY_WRITE != 'true'"):
            self.assertIn(text,job)
        self.assertIn('environment: community-catalog-write',job)
        self.assertIn("rule.type === 'required_reviewers'",plan)
        self.assertNotIn('SUPABASE_SERVICE_ROLE_KEY',plan)

if __name__=='__main__':unittest.main()
