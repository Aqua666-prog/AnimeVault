import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch, Mock

spec = importlib.util.spec_from_file_location('edge_deploy', Path(__file__).with_name('community_edge_deploy.py'))
e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)
SHA = 'a' * 40


def secrets():
    return [{'name': name, 'value': hashlib.sha256(value.encode()).hexdigest()} for name, value in
            [('MAL_CLIENT_ID', 'not-a-real-client-id'), ('COMMUNITY_PUBLIC_KEY', 'not-a-real-public-key'),
             ('COMMUNITY_AUTO_WRITE_ENABLED', 'false')]]


def remote():
    return [{'slug': name, 'status': 'ACTIVE', 'version': 1, 'verify_jwt': False} for name in e.FUNCTIONS]


class FakeCLI:
    def __init__(self, token=None):
        self.token = token
        self.rows = remote()
        self.secret_rows = secrets()
        self.writes = []
        self.reads = []
        self.staged = False
        self.failure = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def call(self, args, phase):
        self.reads.append(phase)
        return e.CLI_VERSION

    def stage(self):
        self.staged = True

    def secrets(self):
        self.reads.append('secrets')
        return copy.deepcopy(self.secret_rows)

    def functions(self):
        self.reads.append('functions')
        return copy.deepcopy(self.rows)

    def deploy(self, name):
        self.writes.append(name)
        if name == self.failure:
            raise e.Blocked('cli_failed_deploy_fixture')
        for row in self.rows:
            if row['slug'] == name:
                row['version'] += 1


class EdgeDeployTest(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/main',
                                         'GITHUB_REPOSITORY': e.REPOSITORY, 'GITHUB_SHA': SHA,
                                         'EDGE_ACTION': 'check', 'EDGE_CONFIRMATION': ''}, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)

    def deploy_env(self):
        os.environ.update(EDGE_ACTION='deploy', EDGE_CONFIRMATION=e.CONFIRMATION, SUPABASE_ACCESS_TOKEN='sbp_test_fixture')

    def github(self, path):
        if path.startswith('/environments/'):
            return {'name': e.ENVIRONMENT, 'protection_rules': [{'type': 'required_reviewers', 'reviewers': [{'type': 'User'}]}]}
        return {'object': {'sha': SHA}}

    def test_local_config_existing_functions_and_disabled_defaults(self):
        report = e.local_check()
        self.assertEqual(list(e.FUNCTIONS), report['functions'])
        self.assertIs(report['verify_jwt'], False)

    def test_check_no_supabase_credentials_remote_reads_or_writes(self):
        fake = FakeCLI()
        with patch.object(e, 'CLI', return_value=fake), patch.object(e, 'access_token', side_effect=AssertionError('token read')), patch.object(e, 'github_json', side_effect=AssertionError('network read')):
            report = {'status': 'blocked'}
            e.run('check', report)
        self.assertEqual(fake.reads, ['version'])
        self.assertFalse(fake.staged)
        self.assertEqual(fake.writes, [])
        self.assertEqual(report['status'], 'checked')
        self.assertEqual(report['remote_secrets'], 'not_checked_no_production_credentials')

    def test_wrong_branch_and_non_manual_event_rejected_before_cli(self):
        for name, value in [('GITHUB_REF', 'refs/heads/feature/evil'), ('GITHUB_EVENT_NAME', 'push'), ('GITHUB_REPOSITORY', 'other/repo')]:
            with patch.dict(os.environ, {name: value}), patch.object(e, 'CLI') as cli:
                with self.assertRaises(e.Blocked):
                    e.run('check', {})
                cli.assert_not_called()

    def test_deploy_exact_confirmation_required(self):
        self.deploy_env()
        for phrase in ('', e.CONFIRMATION.lower(), e.CONFIRMATION + ' ', 'DEPLOY COMMUNITY EDGE other'):
            with patch.dict(os.environ, {'EDGE_CONFIRMATION': phrase}), patch.object(e, 'CLI') as cli:
                with self.assertRaisesRegex(e.Blocked, 'exact_deploy_confirmation'):
                    e.run('deploy', {})
                cli.assert_not_called()

    def test_deploy_command_cannot_be_used_with_check_action(self):
        with self.assertRaisesRegex(e.Blocked, 'deploy_action_required'):
            e.run('deploy', {})

    def test_missing_token_refuses_before_network_or_cli(self):
        self.deploy_env()
        os.environ.pop('SUPABASE_ACCESS_TOKEN')
        with patch.object(e, 'CLI') as cli, patch.object(e, 'github_json') as network:
            with self.assertRaisesRegex(e.Blocked, 'access_token_missing'):
                e.run('deploy', {})
            cli.assert_not_called()
            network.assert_not_called()

    def test_service_role_and_database_password_are_not_pat(self):
        for token in ('eyJhbGci.fixture', 'sb_secret_fixture', 'postgres-password', 'sbp_', 'sbp_leak\n'):
            with patch.dict(os.environ, {'SUPABASE_ACCESS_TOKEN': token}), self.assertRaises(e.Blocked):
                e.access_token()

    def test_environment_missing_or_without_reviewers_refuses(self):
        self.deploy_env()
        for value in ({}, {'name': e.ENVIRONMENT, 'protection_rules': []}, {'name': e.ENVIRONMENT, 'protection_rules': [{'type': 'required_reviewers', 'reviewers': []}]}):
            with patch.object(e, 'github_json', return_value=value), patch.object(e, 'CLI') as cli:
                with self.assertRaises(e.Blocked):
                    e.run('deploy', {})
                cli.assert_not_called()

    def test_main_advanced_after_approval_refuses(self):
        self.deploy_env()
        def changed(path):
            result = self.github(path)
            if path.startswith('/git/'):
                result['object']['sha'] = 'b' * 40
            return result
        with patch.object(e, 'github_json', side_effect=changed), self.assertRaisesRegex(e.Blocked, 'main_changed'):
            e.run('deploy', {})

    def test_deploy_only_two_functions_sequentially(self):
        self.deploy_env()
        fake = FakeCLI()
        with patch.object(e, 'CLI', return_value=fake), patch.object(e, 'github_json', side_effect=self.github):
            report = {'status': 'blocked'}
            e.run('deploy', report)
        self.assertEqual(fake.writes, list(e.FUNCTIONS))
        self.assertTrue(fake.staged)
        self.assertEqual(report['status'], 'deployed')
        self.assertNotIn('not-a-real', json.dumps(report))
        self.assertNotIn(e.FALSE_DIGEST, json.dumps(report))
        self.assertNotIn('sbp_', json.dumps(report))

    def test_second_deploy_failure_preserves_partial_outcome_and_stops(self):
        fake = FakeCLI()
        fake.failure = e.FUNCTIONS[1]
        report = {'status': 'blocked'}
        with self.assertRaises(e.Blocked):
            e.publish(fake, report)
        self.assertEqual(report['status'], 'blocked')
        self.assertEqual(report['functions'][e.FUNCTIONS[0]]['deployment'], 'verified_active')
        self.assertEqual(report['functions'][e.FUNCTIONS[1]]['deployment'], 'attempted_outcome_not_confirmed')

    def test_first_deploy_failure_never_attempts_second(self):
        fake = FakeCLI()
        fake.failure = e.FUNCTIONS[0]
        with self.assertRaises(e.Blocked):
            e.publish(fake, {})
        self.assertEqual(fake.writes, [e.FUNCTIONS[0]])

    def test_missing_mal_or_public_key_refuses_all_writes(self):
        for name in ('MAL_CLIENT_ID', 'COMMUNITY_PUBLIC_KEY', 'COMMUNITY_AUTO_WRITE_ENABLED'):
            fake = FakeCLI()
            fake.secret_rows = [r for r in fake.secret_rows if r['name'] != name]
            with self.assertRaises(e.Blocked):
                e.publish(fake, {})
            self.assertEqual(fake.writes, [])

    def test_secret_names_alone_do_not_prove_false_or_nonempty(self):
        for digest in (None, '', 'false', hashlib.sha256(b'true').hexdigest(), e.EMPTY_DIGEST):
            rows = secrets()
            rows[-1]['value'] = digest
            with self.assertRaises(e.Blocked):
                e.secret_preflight(rows)

    def test_secret_gate_changed_mid_deploy_blocks_second_function(self):
        fake = FakeCLI()
        original = fake.secrets
        def changed():
            rows = original()
            if fake.writes:
                rows[-1]['value'] = hashlib.sha256(b'true').hexdigest()
            return rows
        fake.secrets = changed
        with self.assertRaisesRegex(e.Blocked, 'remote_auto_write_not_verified'):
            e.publish(fake, {})
        self.assertEqual(fake.writes, [e.FUNCTIONS[0]])

    def test_postdeploy_requires_active_jwt_false_and_valid_version(self):
        for field, value in [('status', 'THROTTLED'), ('verify_jwt', True), ('verify_jwt', None), ('version', True), ('version', 0)]:
            rows = remote()
            rows[0][field] = value
            with self.assertRaises(e.Blocked):
                e.function_status(rows, e.FUNCTIONS[0], True)
        with self.assertRaises(e.Blocked):
            e.function_status([], e.FUNCTIONS[0], True)

    def test_redeploy_unchanged_bundle_is_allowed(self):
        fake = FakeCLI()
        fake.deploy = lambda name: fake.writes.append(name)
        report = {}
        e.publish(fake, report)
        self.assertEqual(report['status'], 'deployed')
        self.assertEqual(len(fake.writes), 2)

    def test_cli_captures_and_never_prints_error_output(self):
        with e.CLI('sbp_fixture_secret') as cli, patch.object(e.subprocess, 'run', return_value=Mock(returncode=1, stdout='PRIVATE', stderr='PRIVATE')) as execute:
            with self.assertRaises(e.Blocked) as caught:
                cli.functions()
            self.assertNotIn('PRIVATE', str(caught.exception))
            self.assertTrue(execute.call_args.kwargs['capture_output'])
            self.assertEqual(execute.call_args.kwargs['env']['SUPABASE_ACCESS_TOKEN'], 'sbp_fixture_secret')
            self.assertNotIn('SUPABASE_DB_PASSWORD', execute.call_args.kwargs['env'])

    def test_cli_uses_env_auth_only_and_fixed_named_deploy(self):
        with e.CLI('sbp_fixture_secret') as cli, patch.object(e.subprocess, 'run', return_value=Mock(returncode=0, stdout='', stderr='')) as execute:
            cli.deploy(e.FUNCTIONS[0])
            args = execute.call_args.args[0]
            self.assertEqual(args[:4], ['supabase', 'functions', 'deploy', e.FUNCTIONS[0]])
            self.assertIn('--use-api', args)
            self.assertNotIn('--prune', args)
            self.assertNotIn('sbp_fixture_secret', ' '.join(args))
            with self.assertRaises(e.Blocked):
                cli.deploy('some-other-function')

    def test_stage_excludes_migrations_env_and_unrelated_files(self):
        with e.CLI() as cli:
            cli.stage()
            actual = {str(p.relative_to(cli.bundle)) for p in cli.bundle.rglob('*') if p.is_file()}
            self.assertEqual(actual, set(e.SOURCES))

    def test_local_wrong_jwt_or_enabled_default_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            paths = (*e.SOURCES, 'supabase/migrations/202611020001_community_zero_touch.sql')
            for relative in paths:
                dest = root / relative
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(e.ROOT / relative, dest)
            config = root / 'supabase/config.toml'
            original = config.read_text()
            config.write_text(original.replace('verify_jwt = false', 'verify_jwt = true', 1))
            with self.assertRaises(e.Blocked):
                e.local_check(root)
            config.write_text(original)
            sql = root / paths[-1]
            sql.write_text(sql.read_text().replace('on_demand_enabled boolean NOT NULL DEFAULT false', 'on_demand_enabled boolean NOT NULL DEFAULT true'))
            with self.assertRaises(e.Blocked):
                e.local_check(root)

    def test_main_report_never_contains_cli_or_token_payload(self):
        self.deploy_env()
        with patch.object(e, 'run', side_effect=RuntimeError('PRIVATE sbp_secret')), patch('sys.argv', ['edge.py', 'deploy']):
            output = io.StringIO()
            with redirect_stdout(output), self.assertRaises(SystemExit):
                e.main()
            self.assertNotIn('PRIVATE', output.getvalue())
            self.assertNotIn('sbp_', output.getvalue())
            self.assertIn('details_withheld', output.getvalue())

    def test_workflow_manual_only_and_secrets_only_after_environment_gate(self):
        # Text assertions keep this test dependency-free in the existing Python CI.
        text = (e.ROOT / '.github/workflows/community-edge-deploy.yml').read_text()
        self.assertIn('  workflow_dispatch:', text)
        self.assertNotIn('  schedule:', text)
        self.assertNotIn('  push:', text)
        self.assertNotIn('  pull_request:', text)
        check, deploy = text.split('  check:\n', 1)[1].split('  deploy:\n', 1)
        self.assertNotIn('secrets.', check)
        self.assertIn('environment: community-edge-production', deploy)
        self.assertIn('contents: read', text)
        self.assertIn('cancel-in-progress: false', text)
        self.assertIn('version: ' + e.CLI_VERSION, text)
        self.assertNotIn('continue-on-error', text)
        self.assertNotIn('|| true', text)
        self.assertNotIn('SUPABASE_SERVICE_ROLE_KEY', text)
        self.assertNotIn('SUPABASE_DB_PASSWORD', text)
        self.assertNotIn('upload-artifact', text)


    def test_cli_forbids_database_secret_mutations_prune_or_bulk_deploy(self):
        with e.CLI('sbp_fixture_secret') as cli, patch.object(e.subprocess, 'run') as execute:
            for args in (['db', 'push'], ['db', 'reset'], ['secrets', 'set', 'FLAG=true'],
                         ['functions', 'deploy'], cli.deploy_args(e.FUNCTIONS[0]) + ['--prune'],
                         ['functions', 'delete', e.FUNCTIONS[0]]):
                with self.assertRaisesRegex(e.Blocked, 'cli_operation_not_allowlisted'):
                    cli.call(args, 'fixture')
            execute.assert_not_called()

if __name__ == '__main__':
    unittest.main()
