import copy
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

DIRECTORY=Path(__file__).parent/'community_catalog'
sys.path.insert(0,str(DIRECTORY))
try:
    spec=importlib.util.spec_from_file_location('canary',DIRECTORY/'canary.py')
    c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
finally:sys.path.pop(0)


def pair():
    return [{'kind':'anime','anilist_id':195604,'mal_id':61967,'title':'Black Clover 2nd Season'},
            {'kind':'link','provider':'kodik','external_id':'shiki:61967','anilist_id':195604,'status':'verified','evidence':
             {'source':'kodik','method':'external_link_chain','shikimori_id':61967,'url':c.SHIKI,'mal_id':61967,
              'client_contract':'android-release-id-v1','client_identity':c.a.client_identity('kodik','shiki:61967'),
              'observed_identity':{'id':'serial-fixture','shikimori_id':'61967','kinopoisk_id':None}}}]


def live():
    return [{'data':{'Media':{'id':195604,'idMal':61967,'title':{'romaji':'Black Clover 2nd Season'}}}},
            [{'kind':'myanimelist','url':'http://myanimelist.net/anime/61967'}],
            {'results':[{'id':'serial-fixture','shikimori_id':'61967','kinopoisk_id':None}]}]


def row():return {'id':'00000000-0000-0000-0000-000000000001','anilist_id':195604,'mal_id':61967,'canonical_title':'Black Clover 2nd Season'}
def alias():return {'anime_id':row()['id'],'provider':'kodik','external_id':'shiki:61967','verified':True}
def accepted():return {'processed':2,'canonical_upserted':1,'verified_applied':1,'pending':0,'rejected':0,'replayed':False}
def context():return {'anime_id':row()['id'],'episode_id':None}

class Http:
    def __init__(self,values):self.values=iter(values);self.calls=[]
    def request(self,url,payload=None,headers=None,**kwargs):
        self.calls.append((url,payload,headers))
        result=next(self.values)
        if isinstance(result,Exception):raise result
        return copy.deepcopy(result)

class CanaryTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.state=c.a.State(Path(self.temp.name)/'state.sqlite');self.addCleanup(self.state.db.close)
        items=pair()
        self.state.anime({'id':195604,'idMal':61967,'title':{'romaji':items[0]['title']}})
        self.state.put(c.ALIAS_KEY,items[1]);self.state.db.commit()
        env={'GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'workflow_dispatch','KODIK_API_TOKEN':'PRIVATE_KODIK',
             'SUPABASE_PUBLISHABLE_KEY':'sb_publishable_fixture','SUPABASE_SERVICE_ROLE_KEY':'PRIVATE_ADMIN',
             'COMMUNITY_AUTOSYNC_WRITE_ENABLED':'true','CANARY_CONFIRMATION':c.CONFIRMATION}
        patcher=patch.dict(os.environ,env,clear=True);patcher.start();self.addCleanup(patcher.stop)

    def planned(self,before=None):
        result=c.plan(self.state,Http(live()+[[],[],[]]))
        if before is not None:
            result['before']=before;result.pop('plan_hash');result['plan_hash']=c.fingerprint(result)
        return result

    def test_plan_is_exact_and_never_mutates(self):
        # Extra queue records do not enter the plan, even when verified.
        self.state.anime({'id':1,'idMal':2,'title':{'romaji':'Other'}})
        other=pair()[1];other['anilist_id']=1;other['external_id']='shiki:2'
        self.state.put('link:kodik:shiki:2',other)
        http=Http(live()+[[],[],[]]);result=c.plan(self.state,http)
        self.assertEqual(2,len(result['items']));self.assertEqual(195604,result['items'][0]['anilist_id'])
        self.assertFalse(any(url.endswith('/community_sync_batch') for url,_,_ in http.calls))
        self.assertNotIn('PRIVATE',c.a.packed(result))

    def test_pending_rejected_and_missing_client_proof_stop(self):
        for status in ('pending','rejected'):
            items=pair();items[1]['status']=status
            with self.assertRaises(c.a.Failure):c.validate_pair(items)
        items=pair();items[1]['evidence'].pop('observed_identity')
        with self.assertRaises(c.a.Failure):c.validate_pair(items)

    def test_scope_does_not_accept_extra_items_or_other_ids(self):
        for items in (pair()+[pair()[0]],list(reversed(pair()))):
            with self.assertRaises(c.a.Failure):c.validate_pair(items)
        for key,value in (('anilist_id',1),('mal_id',1),('kind','episode')):
            items=pair();items[0][key]=value
            with self.assertRaises(c.a.Failure):c.validate_pair(items)
        for key,value in (('external_id','serial-fixture'),('provider','all'),('anilist_id',1)):
            items=pair();items[1][key]=value
            with self.assertRaises(c.a.Failure):c.validate_pair(items)

    def test_missing_discovery_target_stops_before_live_reads(self):
        self.state.db.execute('DELETE FROM queue WHERE key=?',(c.ALIAS_KEY,))
        http=Http([])
        with self.assertRaisesRegex(c.a.Failure,'discovery_target_missing'):c.plan(self.state,http)
        self.assertEqual([],http.calls)

    def test_bad_chain_and_stale_primary_observation_stop(self):
        for index,replacement in ((0,{'data':{'Media':{'id':1,'idMal':61967}}}),(1,[]),(2,{'results':[]}),(2,{'results':[{'id':'different','shikimori_id':'61967'}]})):
            values=live();values[index]=replacement
            with self.assertRaises(c.a.Failure):c.refresh_pair(pair(),Http(values))

    def test_plan_cannot_be_changed_after_approval(self):
        value=self.planned();expected=value['plan_hash'];value['items'][0]['title']='Changed'
        http=Http([])
        with self.assertRaisesRegex(c.a.Failure,'plan_hash_changed'):c.publish_canary(value,http,expected)
        self.assertEqual([],http.calls)

    def test_confirmation_and_write_variable_required(self):
        value=self.planned()
        for variable,bad in (('CANARY_CONFIRMATION','yes'),('COMMUNITY_AUTOSYNC_WRITE_ENABLED','false'),('GITHUB_REF','refs/heads/feature'),('COMMUNITY_AUTOSYNC_DAILY_WRITE','true')):
            with patch.dict(os.environ,{variable:bad}):
                http=Http([])
                with self.assertRaises(c.a.Failure):c.publish_canary(value,http,value['plan_hash'])
                self.assertEqual([],http.calls)

    def test_apply_sends_one_bounded_pair_then_real_resolve(self):
        value=self.planned();http=Http(live()+[[],[],[],[],[],[],accepted(),context(),context(),[row()]])
        result=c.publish_canary(value,http,value['plan_hash'])
        self.assertEqual('success',result['status']);self.assertEqual(1,result['write_requests'])
        writes=[call for call in http.calls if call[0].endswith('/community_sync_batch')]
        self.assertEqual(1,len(writes));self.assertEqual(pair(),writes[0][1]['p_items'])
        rpc=[call for call in http.calls if call[0].endswith('/community_resolve')]
        self.assertEqual(c.a.client_identity('kodik','shiki:61967'),rpc[1][1])
        self.assertNotIn('PRIVATE',c.a.packed(result))

    def test_admin_hidden_alias_conflict_blocks_before_write(self):
        value=self.planned();foreign={**alias(),'anime_id':'foreign'}
        http=Http(live()+[[],[],[],[row()],[row()],[foreign]])
        with self.assertRaisesRegex(c.a.Failure,'remote_alias_conflict'):c.publish_canary(value,http,value['plan_hash'])
        self.assertFalse(any(url.endswith('/community_sync_batch') for url,_,_ in http.calls))

    def test_mal_conflict_blocks_plan(self):
        foreign={**row(),'anilist_id':1}
        with self.assertRaisesRegex(c.a.Failure,'remote_mal_conflict'):c.plan(self.state,Http(live()+[[],[foreign],[]]))

    def test_admin_select_denied_never_falls_back_to_blind_write(self):
        value=self.planned();http=Http(live()+[[],[],[],c.a.Failure('http_403')])
        with self.assertRaises(c.a.Failure):c.publish_canary(value,http,value['plan_hash'])
        self.assertFalse(any(url.endswith('/community_sync_batch') for url,_,_ in http.calls))

    def test_existing_pair_is_noop_but_still_requires_post_verification(self):
        before=c.remote_snapshot(pair(),Http([[row()],[row()],[alias()]]),c.public_headers())
        value=self.planned(before)
        http=Http(live()+[[row()],[row()],[alias()],[row()],[row()],[alias()],context(),context(),[row()]])
        result=c.publish_canary(value,http,value['plan_hash'])
        self.assertEqual(0,result['write_requests']);self.assertEqual('success',result['status'])
        self.assertFalse(any(url.endswith('/community_sync_batch') for url,_,_ in http.calls))

    def test_failed_or_wrong_post_resolve_is_not_success(self):
        for post in (None,{'anime_id':'wrong','episode_id':None},{'anime_id':row()['id'],'episode_id':'episode'}):
            value=self.planned();http=Http(live()+[[],[],[],[],[],[],accepted(),context(),post])
            with self.assertRaises(c.a.Failure):c.publish_canary(value,http,value['plan_hash'])

    def test_rejected_batch_does_not_count_as_success(self):
        value=self.planned();http=Http(live()+[[],[],[],[],[],[],{**accepted(),'verified_applied':0,'rejected':1}])
        with self.assertRaisesRegex(c.a.Failure,'batch_not_accepted'):c.publish_canary(value,http,value['plan_hash'])
        self.assertFalse(any(url.endswith('/community_resolve') for url,_,_ in http.calls))

    def test_replayed_batch_still_requires_actual_correct_resolve(self):
        value=self.planned();http=Http(live()+[[],[],[],[],[],[],{**accepted(),'replayed':True},context(),context(),[row()]])
        result=c.publish_canary(value,http,value['plan_hash'])
        self.assertTrue(result['batch']['replayed']);self.assertEqual('resolved',result['resolved']['status'])

    def test_workflow_modes_are_disjoint_and_protected(self):
        workflow=(Path(__file__).parents[1]/'.github/workflows/community-catalog-autosync.yml').read_text()
        self.assertIn("inputs.mode != 'canary' && !inputs.canary_confirmation",workflow)
        self.assertIn('Reject canary phrase in ordinary mode',workflow)
        self.assertIn("inputs.mode == 'canary' && inputs.apply == true",workflow)
        self.assertIn("inputs.canary_confirmation == 'PUBLISH CANARY 195604'",workflow)
        self.assertIn("rule.type === 'required_reviewers'",workflow)
        self.assertIn('environment: community-catalog-write',workflow)
        self.assertIn('run-id: ${{ steps.discovery.outputs.run_id }}',workflow)
