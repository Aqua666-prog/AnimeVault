import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch,Mock

spec=importlib.util.spec_from_file_location('manage',Path(__file__).with_name('manage.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class UnitTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        bundle=Path(self.temp.name)/'test-ca.pem';bundle.write_text('URL-shape fixture only')
        env=patch.dict(os.environ,{'PGSSLROOTCERT':str(bundle)})
        env.start();self.addCleanup(env.stop)

    def test_project_and_tls_guard(self):
        url=f'postgresql://postgres:example@db.{m.PROJECT}.supabase.co:5432/postgres?sslmode=disable'
        safe=m.connection_url(url)
        self.assertIn('sslmode=verify-full',safe);self.assertIn('default_transaction_read_only',safe)
        for bad in (url.replace(m.PROJECT,'anotherproject'),url.replace(':5432',':6543'),url.replace('/postgres','/another'),url+'&options=unsafe',url.replace('postgres:example','anon:example'),url.replace('postgresql:','http:')):
            with self.assertRaises(m.Blocked):m.connection_url(bad)

    def test_password_secret_uri_encoding(self):
        url=f'postgresql://postgres:[YOUR-PASSWORD]@db.{m.PROJECT}.supabase.co/postgres'
        result=m.secret_url(url,'a@b:/%#?')
        self.assertIn('a%40b%3A%2F%25%23%3F',result)
        self.assertIn('verify-full',m.connection_url(result))

    def test_session_pooler(self):
        url=f'postgresql://postgres.{m.PROJECT}:example@aws-0-eu-north-1.pooler.supabase.com:5432/postgres'
        self.assertIn('verify-full',m.connection_url(url))
        with self.assertRaises(m.Blocked):m.connection_url(url.replace('supabase.com','supabase.com.evil.test'))

    def test_strip_only_outer_transaction(self):
        text="-- comment\nBEGIN; CREATE FUNCTION public.test() RETURNS text LANGUAGE sql AS $$ SELECT 'BEGIN; COMMIT; Привет'; $$; COMMIT;"
        parts=m.statements(text)
        self.assertEqual(1,len(parts));self.assertIn('Привет',parts[0][1]);self.assertIn("'BEGIN; COMMIT;",parts[0][1])
        for invalid in ('CREATE TABLE t(id int);','BEGIN; SAVEPOINT s; SELECT 1; COMMIT;','BEGIN; SELECT 1; ROLLBACK;'):
            with self.assertRaises(m.Blocked):m.statements(invalid)

    def test_baseline_can_never_be_pushed(self):
        with tempfile.TemporaryDirectory() as tmp:
            m.stage(Path(tmp),{'autosync':{'missing':[]}})
            base=(Path(tmp)/'supabase/migrations'/m.files()[m.BASE].name).read_text()
            self.assertIn('must NEVER be replayed',base);self.assertNotIn('CREATE TABLE',base.upper())
            sync=(Path(tmp)/'supabase/migrations'/m.files()[m.SYNC].name).read_text()
            self.assertFalse(any(isinstance(n.stmt,m.ast.TransactionStmt) for n in m.parse_sql(sync)))

    def test_cli_hides_credentials_and_enforces_tls_twice(self):
        url=f'postgresql://postgres:PRIVATE@db.{m.PROJECT}.supabase.co/postgres'
        with patch.object(m.subprocess,'run',return_value=Mock(returncode=1,stdout=b'PRIVATE',stderr=b'PRIVATE')) as call:
            with self.assertRaises(m.Blocked) as error:m.cli(['migration','list'],url,Path('/tmp'))
            self.assertNotIn('PRIVATE',str(error.exception));self.assertEqual('verify-full',call.call_args.kwargs['env']['PGSSLMODE'])
            self.assertIn('default_transaction_read_only',call.call_args.kwargs['env']['PGOPTIONS'])

    def test_error_classification_never_returns_raw_details(self):
        self.assertEqual('database_password_rejected',m.error_code('password authentication failed for SECRET'))
        self.assertNotIn('SECRET',m.error_code('Unexpected SECRET'))

    def test_untrusted_dispatch_and_confirmation(self):
        with tempfile.TemporaryDirectory() as tmp,patch.object(m,'ROOT',Path(tmp)),patch.dict(os.environ,{'GITHUB_REF':'refs/heads/evil','GITHUB_EVENT_NAME':'workflow_dispatch'},clear=True),patch('sys.argv',['manage.py','check']):
            previous=os.getcwd();os.chdir(tmp)
            try:
                with self.assertRaises(SystemExit):m.main()
                self.assertEqual('trusted_main_dispatch_required',json.loads(Path('migration-report.json').read_text())['error'])
            finally:os.chdir(previous)

if __name__=='__main__':unittest.main()
