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
        self.assertEqual('abc_2',self.state.cursor('kodik'))
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
        with self.assertRaisesRegex(a.Failure,'animetka_detail_identity'):a.Sync(self.state,http).provider('animetka',1)
        self.assertEqual([],self.items())

    def test_direct_id_lookup_without_full_catalog(self):
        http=Fake([{'data':{'Media':media()}}]);a.Sync(self.state,http).link('yummy','88',mal=2)
        self.assertEqual('verified',self.items()[1]['status'])

    def test_mal_probe_never_invents_kodik_records(self):
        # Run only the dedicated probe by replacing other bounded scans.
        http=Fake([{'data':{'Media':media(7,61967)}},[{'kind':'myanimelist','url':'http://myanimelist.net/anime/61967'}],{'results':[]}])
        sync=a.Sync(self.state,http);sync.anilist=Mock();sync.provider=Mock()
        with patch.dict(os.environ,{'KODIK_API_TOKEN':'fake'}):sync.run(1)
        self.assertEqual(1,len(self.items()));self.assertEqual('anime',self.items()[0]['kind'])
