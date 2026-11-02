import importlib.util
import json
import os
from pathlib import Path
import tempfile
import io
import urllib.error
from unittest.mock import Mock
import unittest
from unittest.mock import patch

SPEC=importlib.util.spec_from_file_location('autosync',Path(__file__).parent/'community_catalog/autosync.py')
a=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(a)

class Fake:
    def __init__(self,values): self.values=iter(values); self.calls=[]
    def request(self,*args,**kwargs):
        self.calls.append((args,kwargs)); result=next(self.values)
        if isinstance(result,Exception): raise result
        return result

def media(aid=1,mal=2): return {'id':aid,'idMal':mal,'title':{'romaji':'Same title'}}

def page(rows,more): return {'data':{'Page':{'media':rows,'pageInfo':{'hasNextPage':more}}}}

class AutoSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/'state.sqlite'; self.state=a.State(self.path)
        self.addCleanup(self.state.db.close)

    def items(self): return [json.loads(r[0]) for r in self.state.db.execute('SELECT body FROM queue ORDER BY key')]

    def test_page_checkpoint_resume(self):
        s=a.Sync(self.state,Fake([page([media()],True),a.Failure('http_429')]))
        with self.assertRaises(a.Failure): s.anilist(2)
        self.assertEqual(2,self.state.cursor('anilist'))
        s.http=Fake([page([media(3,4)],False)]);s.anilist(1)
        self.assertEqual(2,len(self.items()));self.assertEqual(1,self.state.cursor('anilist'))

    def test_failed_page_rolls_back(self):
        with self.assertRaises(a.Failure): a.Sync(self.state,Fake([page([media(),{}],True)])).anilist(1)
        self.assertEqual([],self.items());self.assertEqual(1,self.state.cursor('anilist'))

    def test_no_title_or_ordinal_matching(self):
        self.state.anime(media());a.Sync(self.state,Fake([])).link('aniliberty','123')
        self.assertEqual('pending',self.items()[1]['status'])

    def test_direct_mal(self):
        self.state.anime(media());a.Sync(self.state,Fake([])).link('yummy','88',mal=2)
        self.assertEqual('verified',self.items()[1]['status'])

    def test_conflicting_ids(self):
        self.state.anime(media());self.state.anime(media(3,4))
        a.Sync(self.state,Fake([])).link('animetka','88',mal=4,anilist=1)
        self.assertEqual('rejected',self.items()[2]['status'])

    def test_real_shikimori_http_mal_link(self):
        self.state.anime(media()); http=Fake([[{'kind':'myanimelist','url':'http://myanimelist.net/anime/2'}]])
        a.Sync(self.state,http).link('kodik','shiki:998',shiki=998)
        self.assertEqual('verified',self.items()[1]['status'])
        self.assertEqual(2,self.items()[1]['evidence']['mal_id'])

    def test_shiki_number_is_not_mal_id(self):
        self.state.anime(media());a.Sync(self.state,Fake([[]])).link('kodik','shiki:2',shiki=2)
        self.assertEqual('pending',self.items()[1]['status'])

    def test_ambiguous_links_pending(self):
        links=[{'kind':'myanimelist','url':f'https://myanimelist.net/anime/{n}'} for n in (2,4)]
        a.Sync(self.state,Fake([links])).link('kodik','shiki:2',shiki=2)
        self.assertEqual('pending',self.items()[0]['status'])

    def test_link_parser_rejects_tricks(self):
        for url in ('https://evil.test/anime/2','https://myanimelist.net@evil.test/anime/2','https://myanimelist.net/anime/2?token=secret','https://myanimelist.net/anime/2#bad'):
            self.assertIsNone(a.mal_link(url))

    def test_repeated_discovery_keeps_ack(self):
        self.state.anime(media());self.state.db.execute("UPDATE queue SET ack='yes'")
        self.state.anime(media());self.assertEqual('yes',self.state.db.execute('SELECT ack FROM queue').fetchone()[0])
        changed=media();changed['title']['romaji']='Changed';self.state.anime(changed)
        self.assertIsNone(self.state.db.execute('SELECT ack FROM queue').fetchone()[0])

    def test_writes_default_off(self):
        with patch.dict(os.environ,{},clear=True):
            with self.assertRaisesRegex(a.Failure,'writes_disabled'):a.publish(self.state,Fake([]))

    def test_publish_resume_and_deterministic_batch_id(self):
        self.state.anime(media());self.state.db.commit()
        bad=Fake([a.Failure('network_or_json')])
        with patch.dict(os.environ,{'COMMUNITY_AUTOSYNC_WRITE_ENABLED':'true','SUPABASE_SERVICE_ROLE_KEY':'not-real'}):
            with self.assertRaises(a.Failure):a.publish(self.state,bad)
            good=Fake([{'processed':1}]);self.assertEqual(1,a.publish(self.state,good))
            self.assertEqual(bad.calls[0][0][1]['p_batch'],good.calls[0][0][1]['p_batch'])
            self.assertEqual(0,a.publish(self.state,Fake([])))
        self.assertNotIn('not-real',self.path.read_bytes().decode(errors='ignore'))

    def test_kodik_cursor_has_no_token_and_exact_app_id(self):
        result={'results':[{'id':'serial-real','shikimori_id':'9'}],'next_page':'https://kodik-api.com/list?token=SECRET&start=abc_2'}
        http=Fake([result,[]])
        with patch.dict(os.environ,{'KODIK_API_TOKEN':'SECRET'}):a.Sync(self.state,http).provider('kodik',1)
        self.assertEqual({'parameter':'start','value':'abc_2','anime_only':True},self.state.cursor('kodik'))
        self.assertEqual('shiki:9',self.items()[0]['external_id'])
        self.assertNotIn('SECRET',self.path.read_bytes().decode(errors='ignore'))

    def test_kodik_refuses_cross_host(self):
        with patch.dict(os.environ,{'KODIK_API_TOKEN':'SECRET'}):
            with self.assertRaisesRegex(a.Failure,'kodik_cursor_host'):
                a.Sync(self.state,Fake([{'results':[],'next_page':'https://evil.test/list?start=x'}])).provider('kodik',1)

    def test_host_and_redirect_guard(self):
        with self.assertRaises(a.Failure):a.Http().request('https://evil.test/')
        with self.assertRaises(a.Failure):a.NoRedirect().redirect_request(None,None,302,None,None,'https://evil.test')

    def test_no_episodes_published(self):
        self.state.anime(media()); self.assertFalse(any(x['kind']=='episode' for x in self.items()))

    def test_http_rate_limit_retry_and_response(self):
        response=Mock();response.__enter__=Mock(return_value=response);response.__exit__=Mock(return_value=False)
        response.read.return_value=b'{"ok":true}'
        http=a.Http(sleeper=Mock());http.opener=Mock()
        http.opener.open.side_effect=[urllib.error.HTTPError('https://graphql.anilist.co',429,'Too many',{'Retry-After':'7'},None),response]
        self.assertEqual({'ok':True},http.request('https://graphql.anilist.co',{}))
        self.assertEqual(2,http.opener.open.call_count)
        http.sleep.assert_any_call(7)

    def test_http_error_body_not_disclosed(self):
        http=a.Http(sleeper=Mock());http.opener=Mock()
        http.opener.open.side_effect=urllib.error.HTTPError('https://kodik-api.com/list?token=SECRET',403,'SECRET',{},io.BytesIO(b'SECRET'))
        with self.assertRaisesRegex(a.Failure,'^http_403$'):http.request('https://kodik-api.com/list?token=SECRET')

    def test_poison_record_does_not_block_good_record(self):
        self.state.anime(media());self.state.anime(media(3,4));self.state.db.commit()
        http=Fake([a.Failure('http_400'),a.Failure('http_400'),{'processed':1}])
        with patch.dict(os.environ,{'COMMUNITY_AUTOSYNC_WRITE_ENABLED':'true','SUPABASE_SERVICE_ROLE_KEY':'fake'}):
            self.assertEqual(1,a.publish(self.state,http))
        self.assertEqual(1,self.state.db.execute('SELECT count(*) FROM queue WHERE ack IS NULL').fetchone()[0])
        self.assertEqual('record_rejected',self.state.db.execute('SELECT code FROM errors').fetchone()[0])

    def test_animetka_detail_identity_must_match_feed(self):
        http=Fake([[{'animetka_id':88}],{'data':{'id':99,'mal_id':2}}])
        a.Sync(self.state,http).provider('animetka',1)
        self.assertEqual('rejected',self.items()[0]['status'])
        self.assertEqual(1,self.state.db.execute('SELECT count(*) FROM retry_links').fetchone()[0])

    def test_direct_id_lookup_without_full_catalog(self):
        http=Fake([{'data':{'Media':media()}}]);a.Sync(self.state,http).link('yummy','88',mal=2)
        self.assertEqual('verified',self.items()[1]['status'])

    def test_mal_probe_never_invents_kodik_records(self):
        # Run only the dedicated probe by replacing other bounded scans.
        http=Fake([{'data':{'Media':media(7,61967)}},[{'kind':'myanimelist','url':'http://myanimelist.net/anime/61967'}],{'results':[]}])
        sync=a.Sync(self.state,http);sync.anilist=Mock();sync.provider=Mock()
        with patch.dict(os.environ,{'KODIK_API_TOKEN':'fake'}):sync.run(1)
        self.assertEqual(1,len(self.items()));self.assertEqual('anime',self.items()[0]['kind'])

    def test_kodik_next_is_opaque_and_roundtrips_without_token(self):
        cursor='eyJpZCI6IjEifQ==+/opaque:cursor.'
        url='https://kodik-api.com/list?'+a.urllib.parse.urlencode({'token':'SECRET','next':cursor})
        self.assertEqual({'parameter':'next','value':cursor},a.kodik_cursor(url,'SECRET'))
        result={'results':[],'next_page':url}
        with patch.dict(os.environ,{'KODIK_API_TOKEN':'SECRET'}):
            sync=a.Sync(self.state,Fake([result,{'results':[],'next_page':None}]))
            sync.provider('kodik',1);sync.provider('kodik',1)
        query=a.urllib.parse.parse_qs(a.urllib.parse.urlsplit(sync.http.calls[1][0][0]).query)
        self.assertEqual([cursor],query['next']);self.assertNotIn('start',query)
        self.assertNotIn('SECRET',self.path.read_bytes().decode(errors='ignore'))

    def test_legacy_kodik_checkpoint_not_reset(self):
        self.state.advance('kodik','legacy_start');self.state.db.commit()
        http=Fake([{'results':[],'next_page':None}])
        with patch.dict(os.environ,{'KODIK_API_TOKEN':'SECRET'}):a.Sync(self.state,http).provider('kodik',1)
        self.assertIn('start=legacy_start',http.calls[0][0][0])

    def test_cursor_rejects_ambiguity_controls_and_credentials(self):
        for query in ('next=a&start=b','next=a&next=b','next=','next=%0a','next=SECRET'):
            with self.assertRaises(a.Failure):a.kodik_cursor('https://kodik-api.com/list?'+query,'SECRET')
        cursor='a'*1000+'=='
        self.assertEqual(cursor,a.kodik_cursor('https://kodikapi.com/list?next='+cursor)['value'])

    def test_yummy_requires_application_header_and_preserves_client_mal(self):
        with patch.dict(os.environ,{},clear=True):
            with self.assertRaisesRegex(a.Failure,'application_key_not_configured'):a.Sync(self.state,Fake([])).provider('yummy',1)
        self.state.anime(media())
        http=Fake([{'response':[{'anime_id':88,'remote_ids':{'myanimelist_id':2}}]},{'response':{'anime_id':88,'remote_ids':{'myanimelist_id':2}}}])
        with patch.dict(os.environ,{'YUMMY_APPLICATION_KEY':'PRIVATE'}):a.Sync(self.state,http).provider('yummy',1)
        headers=http.calls[0][1]['headers'];self.assertEqual('PRIVATE',headers['X-Application']);self.assertEqual('ru',headers['Lang'])
        item=self.items()[1];self.assertTrue(a.client_contract(item));self.assertEqual(2,item['evidence']['client_identity']['p_mal'])
        self.assertNotIn('PRIVATE',self.path.read_bytes().decode(errors='ignore'))

    def test_animetka_real_search_projections_headers_and_bridge_jobs(self):
        fixture=Path(__file__).parent/'community_catalog/fixtures'
        details=json.loads((fixture/'animetka-detail-2778.json').read_text())
        self.state.anime(media(1,52991))
        http=Fake([[{'animetka_id':2778,'shikimori_id':52991}],details,[{'kind':'myanimelist','url':'http://myanimelist.net/anime/52991'}]])
        a.Sync(self.state,http).provider('animetka',1)
        query=a.urllib.parse.parse_qs(a.urllib.parse.urlsplit(http.calls[0][0][0]).query,keep_blank_values=True)
        self.assertEqual([''],query['name']);self.assertEqual(['0'],query['offset'])
        self.assertEqual(['animetka_id','shikimori_id'],query['attributes'])
        self.assertEqual('https://animetka.com/',http.calls[0][1]['headers']['Referer'])
        item=self.items()[1];self.assertTrue(a.client_contract(item));self.assertIsNone(item['evidence']['client_identity']['p_mal'])
        body=self.state.db.execute('SELECT body FROM bridge_jobs').fetchone()[0]
        self.assertNotIn('hls',body);self.assertNotIn('https:',body)

    def test_failed_detail_is_retried_without_rewinding_provider(self):
        http=Fake([[{'animetka_id':88}],a.Failure('http_404')])
        a.Sync(self.state,http).provider('animetka',1)
        self.assertEqual(1,self.state.db.execute('SELECT count(*) FROM retry_links').fetchone()[0])
        self.state.anime(media())
        sync=a.Sync(self.state,Fake([{'id':88,'mal_id':2}]))
        sync.retry(5)
        self.assertEqual(0,self.state.db.execute('SELECT count(*) FROM retry_links').fetchone()[0])
        self.assertEqual('verified',self.items()[1]['status'])

    def test_aniliberty_mapping_pk_is_never_used_as_release_id(self):
        fixture=Path(__file__).parent/'community_catalog/fixtures'
        details=json.loads((fixture/'animetka-detail-2778.json').read_text())
        details['Animes']=[]
        self.state.anime(media());sync=a.Sync(self.state,Fake([]))
        sync.link('animetka','2778',mal=2,observed={'id':2778})
        release={'id':9542,'episodes':[{'id':e['id']} for e in details['AnilibriaMapping']['episodes']]}
        sync.http=Fake([release]);self.assertTrue(sync.bridge_animetka('2778',details))
        self.assertTrue(sync.http.calls[0][0][0].endswith('/9542'))
        aliases={i['external_id']:i for i in self.items() if i['kind']=='link'}
        self.assertIn('9542',aliases);self.assertNotIn('8762',aliases)
        self.assertIsNone(aliases['9542']['evidence']['client_identity']['p_anilist'])
        self.assertTrue(a.client_contract(aliases['9542']))

    def test_episode_numbers_are_not_bridge_proof(self):
        self.state.anime(media());sync=a.Sync(self.state,Fake([]));sync.link('animetka','2778',mal=2,observed={'id':2778})
        details={'AnilibriaMapping':{'id':8762,'animetka_id':2778,'episodes':[{'id':'uuid-a','release_id':9542,'ordinal':1}]}}
        sync.http=Fake([{'id':9542,'episodes':[{'id':'uuid-b','ordinal':1}]}])
        self.assertFalse(sync.bridge_animetka('2778',details))
        self.assertFalse(any(i.get('provider')=='aniliberty' for i in self.items()))

    def test_kodik_reference_matches_actual_android_mapper(self):
        root=Path(__file__).parents[1]/'app/src/main/java/com/sergey/animevault'
        mapper=(root/'data/kodik/KodikProvider.kt').read_text()
        ui=(root/'ui/online/OnlineTitleScreen.kt').read_text()
        self.assertIn('SHIKIMORI("shiki", "shikimori_id")',mapper)
        self.assertIn('id = item.releaseReference().toString()',mapper)
        self.assertIn('external=release.id',ui)
        self.assertEqual('shiki:61967',a.kodik_reference({'id':'serial-real','shikimori_id':'61967','kinopoisk_id':'1'}))
        self.assertEqual('shiki:0',a.kodik_reference({'id':'serial-real','shikimori_id':'0','kinopoisk_id':'1'}))
        self.assertEqual('kp:1',a.kodik_reference({'id':'serial-real','kinopoisk_id':'1'}))
        self.assertEqual('kodik:serial-real',a.kodik_reference({'id':'serial-real'}))

    def test_unknown_legacy_proof_not_claimed_resolvable_or_published(self):
        self.state.anime(media());a.Sync(self.state,Fake([])).link('kodik','shiki:2',mal=2)
        summary=a.report(self.state);self.assertEqual(1,summary['verified_without_client_contract'])
        self.assertEqual(0,summary['verified_resolves_community_resolve'])
        with patch.dict(os.environ,{'COMMUNITY_AUTOSYNC_WRITE_ENABLED':'true','SUPABASE_SERVICE_ROLE_KEY':'fake'}):
            self.assertEqual(1,a.publish(self.state,Fake([{'processed':1}]))) # Only canonical record, alias held.

    def test_remote_counts_require_actual_resolve_and_expected_canonical(self):
        self.state.anime(media());a.Sync(self.state,Fake([])).link('yummy','88',mal=2,observed={'id':88})
        http=Fake([{'anime_id':'canonical','episode_id':None},{'anime_id':'canonical','episode_id':None}])
        with patch.dict(os.environ,{'SUPABASE_PUBLISHABLE_KEY':'sb_publishable_fixture'}):a.verify_resolvers(self.state,http)
        self.assertEqual(1,a.report(self.state)['verified_resolves_community_resolve'])
        self.assertEqual(a.client_identity('yummy','88',2),http.calls[1][0][1])
        self.assertTrue(all(c[0][0].endswith('/rpc/community_resolve') for c in http.calls))
        changed=Fake([{'anime_id':'canonical','episode_id':None},{'anime_id':'wrong','episode_id':None}])
        with patch.dict(os.environ,{'SUPABASE_PUBLISHABLE_KEY':'sb_publishable_fixture'}):a.verify_resolvers(self.state,changed)
        self.assertEqual(1,a.report(self.state)['resolver_results']['conflict'])
        self.assertEqual(0,a.report(self.state)['verified_resolves_community_resolve'])

    def test_unpublished_alias_is_unresolved_not_verified_by_format(self):
        self.state.anime(media());a.Sync(self.state,Fake([])).link('yummy','88',mal=2,observed={'id':88})
        with patch.dict(os.environ,{'SUPABASE_PUBLISHABLE_KEY':'sb_publishable_fixture'}):a.verify_resolvers(self.state,Fake([None,None]))
        summary=a.report(self.state)
        self.assertEqual(1,summary['verified_client_contract']);self.assertEqual(0,summary['verified_resolves_community_resolve'])
        self.assertEqual(1,summary['resolver_results']['unresolved'])

    def test_missing_public_key_makes_no_supabase_calls(self):
        http=Fake([])
        with patch.dict(os.environ,{},clear=True):a.verify_resolvers(self.state,http)
        self.assertEqual([],http.calls);self.assertEqual('not_run_missing_publishable_key',a.report(self.state)['resolver_verification']['status'])

    def test_strong_proof_survives_weak_catalog_observation(self):
        self.state.anime(media());sync=a.Sync(self.state,Fake([]))
        sync.link('aniliberty','9542',anilist=1,observed={'id':9542});self.state.db.execute("UPDATE queue SET ack='keep' WHERE key='link:aniliberty:9542'")
        sync.link('aniliberty','9542',observed={'id':9542})
        self.assertEqual('verified',self.items()[1]['status']);self.assertEqual('keep',self.state.db.execute("SELECT ack FROM queue WHERE key='link:aniliberty:9542'").fetchone()[0])

    def test_bridge_uses_primary_kodik_reference_not_nested_material_id(self):
        self.state.anime(media(1,52991));sync=a.Sync(self.state,Fake([]));sync.link('animetka','2778',mal=52991,observed={'id':2778})
        details={'Animes':[{'kodik_id':'serial-54181'}]}
        sync.http=Fake([{'results':[{'id':'serial-54181','shikimori_id':'52991'}]},[{'kind':'myanimelist','url':'http://myanimelist.net/anime/52991'}]])
        with patch.dict(os.environ,{'KODIK_API_TOKEN':'SECRET'}):self.assertTrue(sync.bridge_animetka('2778',details))
        alias=next(i for i in self.items() if i.get('provider')=='kodik')
        self.assertEqual('shiki:52991',alias['external_id']);self.assertTrue(a.client_contract(alias))
        self.assertIsNone(alias['evidence']['client_identity']['p_anilist'])

    def test_bridge_cannot_confirm_unverified_or_conflicting_shiki(self):
        self.state.anime(media(1,52991));sync=a.Sync(self.state,Fake([]));sync.link('animetka','2778',mal=52991,observed={'id':2778})
        details={'Animes':[{'kodik_id':'serial-54181'}]}
        sync.http=Fake([{'results':[{'id':'serial-54181','shikimori_id':'52991'}]},[]])
        with patch.dict(os.environ,{'KODIK_API_TOKEN':'SECRET'}):self.assertFalse(sync.bridge_animetka('2778',details))
        self.assertFalse(any(i.get('provider')=='kodik' and i.get('status')=='verified' for i in self.items()))

    def test_old_cache_restore_is_explicitly_preserved(self):
        workflow=(Path(__file__).parents[1]/'.github/workflows/community-catalog-autosync.yml').read_text()
        self.assertIn('path: autosync.sqlite\n          key: catalog-v1-',workflow)
        self.assertIn('restore-keys: catalog-v2-',workflow)
        self.assertIn("if: steps.state.outputs.cache-matched-key == ''",workflow)

    def test_report_separates_old_404_from_current_errors(self):
        self.state.error('animetka','http_404');self.state.db.close()
        self.state=a.State(self.path);self.addCleanup(self.state.db.close)
        self.assertEqual([],a.report(self.state)['errors_this_run'])
        self.state.error('yummy','application_key_not_configured')
        self.assertEqual(1,len(a.report(self.state)['errors_this_run']))
        self.assertEqual(2,len(a.report(self.state)['errors']))
