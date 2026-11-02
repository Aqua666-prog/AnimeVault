"""Real CLI + PostgreSQL tests, disposable loopback databases only. No Supabase access."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit,urlunsplit
from uuid import uuid4
import psycopg
from psycopg import sql

spec=importlib.util.spec_from_file_location('manage',Path(__file__).with_name('manage.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
DSN=os.environ.get('COMMUNITY_MIGRATION_TEST_URL','')
if DSN and urlsplit(DSN).hostname not in {'127.0.0.1','localhost','::1'}:raise SystemExit('Only disposable loopback tests allowed')

@unittest.skipUnless(DSN,'Disposable PostgreSQL/CLI not configured')
class DatabaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with psycopg.connect(DSN,autocommit=True) as db:
            for role in ('anon','authenticated','service_role'):
                if not db.execute('SELECT 1 FROM pg_roles WHERE rolname=%s',(role,)).fetchone():
                    db.execute(sql.SQL('CREATE ROLE {} NOLOGIN').format(sql.Identifier(role)))

    def setUp(self):
        self.admin=psycopg.connect(DSN,autocommit=True)
        name='av_migration_'+uuid4().hex[:12]
        self.admin.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(name)))
        parsed=urlsplit(DSN);self.url=urlunsplit((parsed.scheme,parsed.netloc,'/'+name,parsed.query,''))
        self.db=psycopg.connect(self.url,autocommit=True)
        self.addCleanup(self.cleanup,name)
        self.db.execute('CREATE SCHEMA auth; CREATE TABLE auth.users(id uuid PRIMARY KEY)')
        self.db.execute("CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$")
        self.db.execute("CREATE FUNCTION auth.role() RETURNS text LANGUAGE sql STABLE AS $$ SELECT current_setting('request.jwt.claim.role',true) $$")
        self.db.execute(m.files()[m.BASE].read_text())
        self.assertFalse(m.inspect(self.db)['blockers'])

    def cleanup(self,name):
        self.db.close()
        self.admin.execute(sql.SQL('DROP DATABASE {}').format(sql.Identifier(name)))
        self.admin.close()

    def run_mode(self,mode,**kwargs):return m.run(self.db,self.url,mode,local=True,**kwargs)

    def test_check_is_read_only_and_never_creates_history(self):
        before=m.snapshot(self.db)
        with psycopg.connect(m.connection_url(self.url,True,True),autocommit=True) as readonly:
            plan=m.run(readonly,self.url,'check',local=True)
            with self.assertRaises(psycopg.errors.ReadOnlySqlTransaction):readonly.execute('CREATE TABLE public.forbidden(id int)')
        self.assertEqual([m.BASE],plan['history_to_adopt'])
        self.assertEqual([m.files()[m.SYNC].name,m.files()[m.AUTO].name],plan['pending_sql'])
        self.assertIsNone(self.db.execute("SELECT to_regclass('supabase_migrations.schema_migrations')").fetchone()[0])
        self.assertEqual(before,m.snapshot(self.db))

    def test_apply_registers_baseline_once_and_preserves_data(self):
        self.db.execute("INSERT INTO public.community_anime(anilist_id,canonical_title) VALUES(199999,'Keep me')")
        baseline=m.snapshot(self.db);plan=self.run_mode('check')
        self.assertEqual([m.BASE,m.SYNC,m.AUTO],self.run_mode('apply',expected=plan['plan_hash'])['history'])
        self.assertEqual('Keep me',self.db.execute('SELECT canonical_title FROM public.community_anime WHERE anilist_id=199999').fetchone()[0])
        self.assertTrue(all(m.snapshot(self.db)[k]==v for k,v in baseline.items()))
        self.assertEqual('complete',self.run_mode('apply')['autosync']['state'])


    def test_apply_with_readonly_control_connection(self):
        with psycopg.connect(m.connection_url(self.url,True,True),autocommit=True) as readonly:
            self.assertEqual('complete',m.run(readonly,self.url,'apply',local=True)['autosync']['state'])

    def test_adopt_complete_manual_autosync_without_reexecuting(self):
        self.db.execute(m.files()[m.SYNC].read_text());self.db.execute(m.files()[m.AUTO].read_text());before=m.snapshot(self.db)
        oids=self.db.execute("SELECT oid FROM pg_class WHERE relname IN ('community_sync_evidence','community_sync_batches') ORDER BY oid").fetchall()
        self.assertEqual([m.BASE,m.SYNC,m.AUTO],self.run_mode('apply')['history'])
        self.assertEqual(before,m.snapshot(self.db))
        self.assertEqual(oids,self.db.execute("SELECT oid FROM pg_class WHERE relname IN ('community_sync_evidence','community_sync_batches') ORDER BY oid").fetchall())

    def test_partial_table_requires_recover_and_keeps_rows(self):
        create=next(text for node,text in m.statements(m.files()[m.SYNC].read_text()) if isinstance(node,m.ast.CreateStmt) and node.relation.relname=='community_sync_batches')
        self.db.execute(create)
        bid=uuid4();self.db.execute("INSERT INTO public.community_sync_batches(id,payload_hash,processed,result) VALUES(%s,'keep',1,'{}')",(bid,))
        plan=self.run_mode('check');self.assertTrue(plan['recovery_required']);self.assertTrue(plan['autosync']['security_drift'])
        with self.assertRaises(m.Blocked):self.run_mode('apply')
        self.assertEqual('complete',self.run_mode('recover')['autosync']['state'])
        self.assertEqual('keep',self.db.execute('SELECT payload_hash FROM public.community_sync_batches WHERE id=%s',(bid,)).fetchone()[0])

    def test_structure_drift_is_not_automatically_repaired(self):
        self.db.execute(m.files()[m.SYNC].read_text());self.db.execute('ALTER TABLE public.community_sync_evidence ADD COLUMN custom_data text')
        plan=self.run_mode('check');self.assertIn('autosync_structure_drift_requires_reviewed_additive_repair',plan['blockers'])
        with self.assertRaises(m.Blocked):self.run_mode('recover')
        self.assertEqual(1,self.db.execute("SELECT count(*) FROM information_schema.columns WHERE table_name='community_sync_evidence' AND column_name='custom_data'").fetchone()[0])

    def test_history_schema_mismatch_blocks_write(self):
        self.make_history([m.BASE,m.SYNC])
        self.assertIn('history_schema_disagree_requires_review',self.run_mode('check')['blockers'])
        with self.assertRaises(m.Blocked):self.run_mode('recover')

    def make_history(self,versions):
        self.db.execute('CREATE SCHEMA supabase_migrations; CREATE TABLE supabase_migrations.schema_migrations(version text PRIMARY KEY,statements text[],name text)')
        for version in versions:self.db.execute('INSERT INTO supabase_migrations.schema_migrations(version) VALUES(%s)',(version,))

    def test_unknown_history_and_baseline_drift_block(self):
        self.make_history(['20261101000000'])
        self.assertIn('unknown_remote_migrations',self.run_mode('check')['blockers'])
        self.db.execute('ALTER TABLE public.community_profiles ADD COLUMN custom_data text')
        self.assertIn('baseline_not_exact_never_reexecute_001',self.run_mode('check')['blockers'])
        with self.assertRaises(m.Blocked):self.run_mode('apply')

    def test_column_grants_and_forced_rls_are_not_blindly_repaired(self):
        self.db.execute(m.files()[m.SYNC].read_text())
        self.db.execute('GRANT SELECT(provider) ON public.community_sync_evidence TO anon')
        self.assertTrue(self.run_mode('check')['autosync']['structure_drift'])
        with self.assertRaises(m.Blocked):self.run_mode('recover')
        self.db.execute('ALTER TABLE public.community_sync_batches FORCE ROW LEVEL SECURITY')
        self.assertIn('autosync_owner_or_force_rls_drift_requires_review',self.run_mode('check')['blockers'])

    def test_changed_plan_blocks_before_any_write(self):
        with self.assertRaisesRegex(m.Blocked,'database_changed_since_check'):self.run_mode('apply',expected='not-the-checked-hash')
        self.assertEqual([],m.history(self.db))

    def test_cli_history_failure_rolls_back_entire_new_schema(self):
        self.make_history([m.BASE]);self.db.execute("ALTER TABLE supabase_migrations.schema_migrations ADD CONSTRAINT injected_failure CHECK(version<>'202611010002')")
        before=m.snapshot(self.db)
        with self.assertRaisesRegex(m.Blocked,'cli_failed'):self.run_mode('apply')
        self.assertEqual(before,m.snapshot(self.db));self.assertEqual([m.BASE],m.history(self.db))

    def test_cli_sql_failure_rolls_back_and_retry_succeeds(self):
        original=m.stage
        def failing(path,plan,recover=False):
            original(path,plan,recover)
            file=path/'supabase/migrations'/m.files()[m.SYNC].name
            file.write_text(file.read_text()+"DO $$ BEGIN RAISE EXCEPTION 'injected local failure'; END $$;\n")
        with patch.object(m,'stage',side_effect=failing):
            with self.assertRaises(m.Blocked):self.run_mode('apply')
        self.assertEqual('absent',m.inspect(self.db)['autosync']['state'])
        self.assertEqual([m.BASE],m.history(self.db))
        self.assertEqual('complete',self.run_mode('apply')['autosync']['state'])

    def test_zero_touch_pending_drift_and_defaults_off(self):
        self.db.execute(m.files()[m.SYNC].read_text())
        self.assertEqual([m.files()[m.AUTO].name],self.run_mode('check')['pending_sql'])
        self.run_mode('apply')
        self.assertEqual((False,False),self.db.execute('SELECT on_demand_enabled,background_enabled FROM public.community_auto_settings').fetchone())
        self.db.execute('GRANT EXECUTE ON FUNCTION public.community_auto_pending() TO anon')
        self.assertIn('zero_touch_partial_or_drift_requires_review',self.run_mode('check')['blockers'])
        with self.assertRaises(m.Blocked):self.run_mode('recover')

if __name__=='__main__':unittest.main()
