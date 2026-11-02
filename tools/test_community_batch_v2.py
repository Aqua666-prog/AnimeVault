"""Captured discovery regression + safe mutation planning and Android resolver contracts."""
import copy
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import urllib.error
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent/'community_catalog'))
import batch as b
sys.path.pop(0)

def anime():return {'kind':'anime','anilist_id':212,'mal_id':212,'title':'Project A-Ko'}
def pair(provider='kodik'):
    external='shiki:212' if provider=='kodik' else '608'
    proof={'source':provider,'method':'external_link_chain','shikimori_id':212,'mal_id':212,'url':'https://shikimori.io/api/animes/212/external_links','client_contract':'android-release-id-v1','client_identity':b.a.client_identity(provider,external),'observed_identity':{'id':'movie-94408','shikimori_id':'212','kinopoisk_id':'56046'} if provider=='kodik' else {'id':'608'}}
    return [anime(),{'kind':'link','provider':provider,'external_id':external,'anilist_id':212,'status':'verified','evidence':proof}]

def media():return {'data':{'Media':{'id':212,'idMal':212}}}
def links():return [{'kind':'myanimelist','url':'http://myanimelist.net/anime/212'}]
def primary():return {'results':[{'id':'movie-94408','shikimori_id':'212','kinopoisk_id':'56046'}]}
def existing():return {'canonical':{'id':'canonical','anilist_id':212,'mal_id':212},'alias':None}

class V2Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.state=b.a.State(str(Path(self.tmp.name)/'state'));self.addCleanup(self.state.db.close)
        self.state.anime({'id':212,'idMal':212,'title':{'romaji':'Project A-Ko'}});self.state.db.commit()
        p=patch.dict(os.environ,{'KODIK_API_TOKEN':'fixture','GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_RUN_ID':'v2','GITHUB_RUN_ATTEMPT':'1','COMMUNITY_AUTOSYNC_WRITE_ENABLED':'true','COMMUNITY_AUTOSYNC_DAILY_WRITE':'false','BATCH_CONFIRMATION':'PUBLISH BATCH 100'});p.start();self.addCleanup(p.stop)
        p=patch.multiple(b.c,public_headers=lambda:{},admin_headers=lambda:{});p.start();self.addCleanup(p.stop)
    def test_212_actual_legacy_artifact_endpoint_preserves_primary_verification(self):
        items=pair();proof=items[1]['evidence']
        proof['url']='https://animetka.com/api/anime/608'
        proof['provenance']={'method':'animetka_kodik_material','animetka_id':'608','material_id':'movie-94408'}
        with patch.object(b.c,'request',side_effect=[media(),links(),primary()]) as request:
            b.refresh(items,None)
            self.assertEqual(request.call_args_list[1].args[3],'https://shikimori.io/api/animes/212/external_links')
        proof['provenance']['material_id']='movie-other'
        with patch.object(b.c,'request',return_value=media()),self.assertRaisesRegex(b.a.Failure,'chain_endpoint'):b.refresh(items,None)
    def test_new_discovery_keeps_chain_url_and_separate_provenance(self):
        sync=b.a.Sync(self.state,None)
        with patch.object(sync,'chain',return_value=(212,'https://shikimori.io/api/animes/212/external_links')):
            sync.link('kodik','shiki:212',shiki=212,observed=pair()[1]['evidence']['observed_identity'],provenance={'method':'animetka_kodik_material','animetka_id':'608','material_id':'movie-94408'},client=b.a.client_identity('kodik','shiki:212'))
        item=json.loads(self.state.db.execute("SELECT body FROM queue WHERE key='link:kodik:shiki:212'").fetchone()[0])
        self.assertEqual(item['evidence']['url'],'https://shikimori.io/api/animes/212/external_links')
        self.assertEqual(item['evidence']['provenance']['url'],'https://animetka.com/api/anime/608')
    def test_http_failure_reports_source_and_status(self):
        class Http:
            def request(self,*args,**kwargs):raise b.a.Failure('http_503',http_status=503)
        with self.assertRaises(b.c.RequestFailure) as error:b.refresh([anime()],Http())
        self.assertEqual(error.exception.diagnostic['http_status'],503);self.assertEqual(error.exception.diagnostic['source'],'anilist')
    def test_missing_and_incompatible_media(self):
        for value in ({},[],{'data':None},{'data':{'Media':None}},{'data':{'Media':[]}}):
            with self.subTest(value=value),patch.object(b.c,'request',return_value=value),self.assertRaises(b.a.Failure):b.refresh([anime()],None)
    def test_invalid_canonical_identifiers_fail_before_network(self):
        for value in (0,-1,True,'212',None):
            item=anime();item['anilist_id']=value
            with patch.object(b.c,'request') as request,self.assertRaises(b.a.Failure):
                b.refresh([item],None)
            request.assert_not_called()
    def test_conflicting_anilist_mal(self):
        with patch.object(b.c,'request',return_value={'data':{'Media':{'id':212,'idMal':213}}}),self.assertRaisesRegex(b.a.Failure,'identity_changed'):b.refresh(pair(),None)
    def test_shikimori_wrong_mal_is_not_accepted(self):
        with patch.object(b.c,'request',side_effect=[media(),[{'kind':'myanimelist','url':'https://myanimelist.net/anime/213'}]]),self.assertRaisesRegex(b.a.Failure,'chain_unconfirmed'):b.refresh(pair(),None)
    def test_incompatible_kodik_and_missing_data(self):
        for response in ([],{}, {'results':None},{'results':[{'id':'other','shikimori_id':213}]}):
            with self.subTest(response=response),patch.object(b.c,'request',side_effect=[media(),links(),response]),self.assertRaises(b.a.Failure):b.refresh(pair(),None)
    def test_discovery_kodik_does_not_assume_mal_equals_shiki(self):
        with patch.object(b.c,'request',side_effect=[primary(),[{'kind':'myanimelist','url':'https://myanimelist.net/anime/213'}]]),self.assertRaises(b.a.Failure):b.discover_kodik(anime(),None)
    def test_existing_catalog_does_not_spend_limit_without_checkpoint(self):
        before={**existing(),'alias':{'anime_id':'canonical','verified':True}}
        self.state.put('link:kodik:shiki:212',pair()[1])
        with patch.object(b,'snapshot',return_value=before),patch.object(b,'resolve') as resolve,patch.object(b,'refresh') as refresh:
            value=b.plan(self.state,None,100,{})
        self.assertEqual(value['records'],0);self.assertEqual(value['report']['existing'],2);self.assertEqual(value['report']['android_resolved'],1)
        resolve.assert_called_once();refresh.assert_not_called()
    def test_new_alias_existing_canonical_costs_one_even_with_old_checkpoint(self):
        done={b.c.fingerprint([anime()]):'resolved'}
        with patch.object(b,'snapshot',return_value=existing()),patch.object(b,'discover_kodik',return_value=pair()[1]),patch.object(b,'refresh'):
            value=b.plan(self.state,None,100,done)
        self.assertEqual(value['records'],1);self.assertEqual(value['groups'][0]['writes'],[pair()[1]])
        self.assertEqual(value['report']['new_aliases'],1);self.assertEqual(value['report']['new_canonical'],0)
    def test_alias_only_rpc_and_retry_policy_restored(self):
        with patch.object(b,'snapshot',return_value=existing()),patch.object(b,'discover_kodik',return_value=pair()[1]),patch.object(b,'refresh'):
            value=b.plan(self.state,None,100,{})
        class Http:max_attempts=5
        http=Http()
        def send(h,*args):
            self.assertEqual(h.max_attempts,1);self.assertEqual(args[-2]['p_items'],[pair()[1]])
            return {'processed':1,'canonical_upserted':0,'verified_applied':1,'pending':0,'rejected':0}
        with patch.object(b,'snapshot',return_value=existing()),patch.object(b,'refresh'),patch.object(b,'resolve'),patch.object(b.c,'request',side_effect=send):
            result=b.publish(value,http,value['plan_hash'],{},Path(self.tmp.name)/'cp')
        self.assertEqual(result['submitted'],1);self.assertEqual(result['android_resolved'],1);self.assertEqual(http.max_attempts,5)
    def test_checkpoint_rotation_prevents_existing_prefix_starvation(self):
        for i in range(1,22):self.state.anime({'id':i,'idMal':i,'title':{'romaji':'Fixture'}})
        done={}
        def snapshot(items,*args):return {'canonical':{'id':str(items[0]['anilist_id']),'anilist_id':items[0]['anilist_id'],'mal_id':items[0]['mal_id']},'alias':None}
        with patch.object(b,'snapshot',side_effect=snapshot),patch.object(b,'discover_kodik',return_value=None),patch.object(b,'resolve'):
            b.plan(self.state,None,5,done);after=done['_scan_after'];b.plan(self.state,None,5,done)
        self.assertNotEqual(after,done['_scan_after'])
    def test_animetka_primary_id_chain(self):
        with patch.object(b.c,'request',side_effect=[media(),{'id':608,'shikimori_id':212},links()]):b.refresh(pair('animetka'),None)
    def test_animetka_missing_or_foreign_id_blocked(self):
        for detail in ({'id':608},{'id':609,'shikimori_id':212}):
            with patch.object(b.c,'request',side_effect=[media(),detail]),self.assertRaises(b.a.Failure):b.refresh(pair('animetka'),None)
    def test_yummy_exact_android_mal_request(self):
        items=pair('yummy');items[1]['evidence']['client_identity']=b.a.client_identity('yummy','608',212)
        with patch.dict(os.environ,{'YUMMY_APPLICATION_KEY':'fixture'}),patch.object(b.c,'request',side_effect=[media(),{'response':{'anime_id':608,'remote_ids':{'myanimelist_id':212,'shikimori_id':212}}},links()]):b.refresh(items,None)
    def test_android_alias_null_and_foreign_uuid_never_resolve(self):
        for actual in (None,{'anime_id':'foreign','episode_id':None}):
            with patch.object(b.c,'request',side_effect=[{'anime_id':'canonical','episode_id':None},actual]),self.assertRaisesRegex(b.a.Failure,'resolver_alias'):b.resolve(pair(),None)
    def test_android_resolver_uses_exact_provider_request(self):
        for provider in ('kodik','animetka','yummy'):
            with patch.object(b.c,'request',side_effect=[{'anime_id':'canonical','episode_id':None}]*2) as request:b.resolve(pair(provider),None)
            self.assertEqual(request.call_args_list[-1].args[4],pair(provider)[1]['evidence']['client_identity'])
    def test_temporary_read_http_503_retries_without_mutation(self):
        class Response:
            status=200
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def read(self,*args):return b'{"data":{"Media":{"id":212,"idMal":212}}}'
        http=b.a.Http(pause=0,sleeper=lambda _:None)
        error=urllib.error.HTTPError('https://graphql.anilist.co',503,'unavailable',{},None)
        with patch.object(http.opener,'open',side_effect=[error,Response()]) as send:b.refresh([anime()],http)
        self.assertEqual(send.call_count,2)
    def test_100_existing_aliases_only_counts_alias_writes(self):
        # Independent counts are enforced by mutations, not search page size.
        groups=[]
        for i in range(100):
            items=pair();items[0]['anilist_id']=i+1;items[1]['anilist_id']=i+1
            writes=b.mutations(items,existing());self.assertEqual(len(writes),1);groups.extend(writes)
        self.assertEqual(len(groups),100);self.assertTrue(all(x['kind']=='link' for x in groups))

    def test_aniliberty_uuid_bridge_requires_explicit_cross_catalog_identity(self):
        items=pair('aniliberty');items[1]['external_id']='9542'
        items[1]['evidence'].update(client_identity=b.a.client_identity('aniliberty','9542'),observed_identity={'id':9542},provenance={'method':'animetka_anilibria_mapping','animetka_id':'608','release_id':9542})
        detail={'id':608,'shikimori_id':212,'AnilibriaMapping':{'animetka_id':608,'episodes':[{'id':'uuid-a','release_id':9542}]}}
        release={'id':9542,'episodes':[{'id':'uuid-a','ordinal':1}]}
        with patch.object(b.c,'request',side_effect=[media(),detail,links(),release]):b.refresh(items,None)
        release['episodes'][0]['id']='uuid-foreign'
        with patch.object(b.c,'request',side_effect=[media(),detail,links(),release]),self.assertRaisesRegex(b.a.Failure,'episode_identity'):b.refresh(items,None)
    def test_conflicting_provider_fields_do_not_get_silently_ignored(self):
        for detail in ({'id':608,'mal_id':212,'myanimelist_id':213},{'id':608,'mal_id':212,'shikimori_id':'bad'}):
            with patch.object(b.c,'request',side_effect=[media(),detail]),self.assertRaises(b.a.Failure):b.refresh(pair('animetka'),None)
    def test_partial_discovery_failure_keeps_canonical_without_inventing_alias(self):
        with patch.object(b,'snapshot',return_value={'canonical':None,'alias':None}),patch.object(b,'discover_kodik',side_effect=b.a.Failure('http_503',http_status=503)),patch.object(b,'refresh'):
            value=b.plan(self.state,None,100,{})
        self.assertEqual(value['records'],1);self.assertEqual(value['groups'][0]['writes'],[anime()]);self.assertEqual(value['report']['failed'],1)
    def test_partial_receipt_never_marked_resolved_and_read_retry_policy_recovers(self):
        with patch.object(b,'snapshot',return_value=existing()),patch.object(b,'discover_kodik',return_value=pair()[1]),patch.object(b,'refresh'):
            value=b.plan(self.state,None,100,{})
        class Http:max_attempts=5
        http=Http();done={}
        with patch.object(b,'snapshot',return_value=existing()),patch.object(b,'refresh'),patch.object(b.c,'request',side_effect=b.a.Failure('http_503')):
            result=b.publish(value,http,value['plan_hash'],done,Path(self.tmp.name)/'cp')
        self.assertEqual(result['status'],'blocked');self.assertEqual(done,{});self.assertEqual(http.max_attempts,5)
    def test_android_card_and_sql_require_provider_alias(self):
        root=Path(__file__).parents[1]
        screen=(root/'app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleScreen.kt').read_text()
        resolver=(root/'app/src/main/java/com/sergey/animevault/data/community/CommunityRepositories.kt').read_text()
        sql=(root/'supabase/migrations/202611010001_community.sql').read_text()
        self.assertIn('provider=release.providerId,external=release.id',screen)
        self.assertIn('"p_provider" to identity.provider',resolver)
        self.assertIn('"p_external" to identity.external',resolver)
        self.assertIn('a:=alias_anime;',sql);self.assertIn('if a is null then return null; end if;',sql)

    def test_100_alias_only_groups_send_exactly_100_records(self):
        for aid in range(1,101):
            self.state.anime({'id':aid,'idMal':1000+aid,'title':{'romaji':'Fixture'}})
            item=pair()[1];item['anilist_id']=aid;item['external_id']='shiki:'+str(1000+aid)
            item['evidence']['client_identity']=b.a.client_identity('kodik',item['external_id']);item['evidence']['observed_identity']['shikimori_id']=1000+aid
            self.state.put('link:kodik:'+item['external_id'],item)
        self.state.db.execute('DELETE FROM canonical WHERE id=212');self.state.db.execute("DELETE FROM queue WHERE key='anime:212'")
        def before(items,*args):return {'canonical':{'id':str(items[0]['anilist_id']),'anilist_id':items[0]['anilist_id'],'mal_id':items[0]['mal_id']},'alias':None}
        class Http:max_attempts=5
        payloads=[]
        def send(http,source,stage,url,payload,headers):
            payloads.append(payload['p_items'])
            return {'processed':1,'canonical_upserted':0,'verified_applied':1,'pending':0,'rejected':0}
        with patch.object(b,'snapshot',side_effect=before),patch.object(b,'refresh'),patch.object(b,'resolve'),patch.object(b.c,'request',side_effect=send):
            value=b.plan(self.state,None,100,{})
            result=b.publish(value,Http(),value['plan_hash'],{},Path(self.tmp.name)/'checkpoint')
        self.assertEqual(result['submitted'],100);self.assertEqual(result['published'],100);self.assertEqual(result['android_resolved'],100)
        self.assertEqual(sum(map(len,payloads)),100);self.assertTrue(all(items[0]['kind']=='link' for items in payloads))

if __name__=='__main__':unittest.main()
