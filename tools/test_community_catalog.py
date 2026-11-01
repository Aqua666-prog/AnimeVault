import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('catalog_import',Path(__file__).parent/'community_catalog/import_catalog.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class CatalogImportTest(unittest.TestCase):
    def media(self):return {'id':20,'idMal':20,'title':{'romaji':'Naruto'},'format':'TV','seasonYear':2002}
    def item(self):return {'anilist_id':20,'mal_id':20,'aliases':[{'provider':'animevost','external_id':'operator-reviewed-id','mal_id':20,'evidence_url':'https://example.invalid/release','review_note':'Fixture only; not real provider evidence'}],'episodes':[{'key':'regular:1','type':'regular','number':1,'aliases':[{'provider':'animevost','release_id':'operator-reviewed-id','external_episode_id':'exact-episode-id','canonical_key':'regular:1','evidence_url':'https://example.invalid/episode','review_note':'Fixture explicit mapping'}]}]}
    def plan(self):return {'version':1,'records':[m.build_record(self.item(),self.media())],'errors':[]}
    def test_unknown_cli_arguments_do_not_echo_credentials(self):
        import io
        import contextlib
        output=io.StringIO()
        with contextlib.redirect_stderr(output),self.assertRaises(SystemExit):
            m.SafeParser().parse_args(['--service-role=DO_NOT_PRINT'])
        self.assertNotIn('DO_NOT_PRINT',output.getvalue())
    def test_upstream_timeout_has_safe_journal_code(self):
        from unittest.mock import patch
        with patch.object(m.urllib.request,'urlopen',side_effect=TimeoutError('DO_NOT_ECHO')):
            with self.assertRaisesRegex(m.Invalid,'upstream_unavailable'):m.fetch({'anilist_id':20})
    def test_missing_upstream_title_is_rejected_cleanly(self):
        media=self.media();media['title']=None
        with self.assertRaises(m.Invalid):m.build_record(self.item(),media)
    def test_exact_ids_and_explicit_episode_map(self):
        r=self.plan()['records'][0];self.assertEqual(20,r['anilist_id']);self.assertEqual('regular:1',r['episodes'][0]['key'])
    def test_title_is_not_identity(self):
        item=self.item();item['mal_id']=21
        with self.assertRaises(m.Invalid):m.build_record(item,self.media())
        item={'title':'Naruto'}
        with self.assertRaises(m.Invalid):m.build_record(item,self.media())
    def test_provider_conflicting_metadata_is_rejected(self):
        item=self.item();item['aliases'][0]['mal_id']=21
        with self.assertRaises(m.Invalid):m.build_record(item,self.media())
    def test_episode_number_alone_is_rejected(self):
        item=self.item();item['episodes'][0]['aliases']=[]
        with self.assertRaises(m.Invalid):m.build_record(item,self.media())
        item=self.item();item['episodes'][0]['aliases'][0]['canonical_key']='regular:2'
        with self.assertRaises(m.Invalid):m.build_record(item,self.media())
    def test_duplicates_and_unknown_fields_are_rejected(self):
        item=self.item();item['aliases']*=2
        with self.assertRaises(m.Invalid):m.build_record(item,self.media())
        item=self.item();item['service_role']='never'
        with self.assertRaises(m.Invalid):m.build_record(item,self.media())
    def test_no_sql_without_explicit_current_plan_approval(self):
        plan=self.plan()
        with self.assertRaises(m.Invalid):m.render(plan,'wrong')
        sha=m.digest(plan);plan['records'][0]['title']='changed'
        with self.assertRaises(m.Invalid):m.render(plan,sha)
    def test_error_plan_is_not_rendered(self):
        plan=self.plan();plan['errors']=[{'index':0,'code':'bad'}]
        with self.assertRaises(m.Invalid):m.render(plan,m.digest(plan))
    def test_sql_quotes_and_dollar_delimiters_are_safe(self):
        plan=self.plan();plan['records'][0]['title']="x'; DROP TABLE auth.users; -- $catalog$"
        sql=m.render(plan,m.digest(plan))
        self.assertIn("x''; DROP TABLE auth.users; -- $catalog$",sql)
        self.assertIn('DO $catalog_x$',sql)
        self.assertIn('pg_advisory_xact_lock',sql)
        self.assertIn('ON CONFLICT',sql)
    def test_no_credentials_in_evidence_url(self):
        for url in ['http://example.invalid','https://user:pass@example.invalid/path','https://example.invalid/?token=x']:
            with self.assertRaises(m.Invalid):m.source_url(url)

if __name__=='__main__':unittest.main()
