"""Unattended Edge deploy: no real Supabase credentials or production writes in tests."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
from unittest import TestCase, main
from unittest.mock import MagicMock, Mock, patch
from contextlib import redirect_stdout

import community_edge_autodeploy as a
from community_edge_deploy import Blocked, CLI_VERSION, FUNCTIONS

SHA = 'a' * 40


def secrets(gate='true'):
    return [
        {'name': 'MAL_CLIENT_ID', 'value': hashlib.sha256(b'mal-test-id').hexdigest()},
        {'name': 'COMMUNITY_PUBLIC_KEY', 'value': hashlib.sha256(b'public-test-key').hexdigest()},
        {'name': 'COMMUNITY_AUTO_WRITE_ENABLED', 'value': hashlib.sha256(gate.encode()).hexdigest()},
    ]


def functions():
    return [{'slug': name, 'status': 'ACTIVE', 'version': 5, 'verify_jwt': False} for name in FUNCTIONS]


class FakeCLI:
    def __init__(self, token=None):
        self.token = token
        self.rows = functions()
        self.secret_rows = secrets()
        self.writes = []
        self.staged = False
        self.change_after_first = False

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def call(self, args, phase):
        return CLI_VERSION

    def secrets(self):
        rows = copy.deepcopy(self.secret_rows)
        if self.change_after_first and self.writes:
            rows[-1]['value'] = hashlib.sha256(b'false').hexdigest()
        return rows

    def functions(self):
        return copy.deepcopy(self.rows)

    def stage(self):
        self.staged = True

    def deploy(self, name):
        self.writes.append(name)
        next(row for row in self.rows if row['slug'] == name)['version'] += 1


class AutoDeployTests(TestCase):
    def setUp(self):
        self.environment = patch.dict(os.environ, {
            'GITHUB_EVENT_NAME': 'push',
            'GITHUB_REF': 'refs/heads/main',
            'GITHUB_REPOSITORY': a.REPOSITORY,
            'GITHUB_SHA': SHA,
            'SUPABASE_ACCESS_TOKEN': 'sbp_testing_only',
            'GH_METADATA_TOKEN': 'ghs_fixture_only',
        }, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def test_auto_only_from_current_main(self):
        with patch.object(a, 'github_json', return_value={'object': {'sha': SHA}}):
            self.assertEqual(a.trusted_main(), SHA)
        with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'workflow_dispatch'}), patch.object(
                a, 'github_json', return_value={'object': {'sha': SHA}}):
            self.assertEqual(a.trusted_main(), SHA)

    def test_reject_pr_tag_fork_or_stale_main_before_accessing_credentials(self):
        for key, value in (
            ('GITHUB_EVENT_NAME', 'pull_request'),
            ('GITHUB_REF', 'refs/tags/v4.1.4'),
            ('GITHUB_REPOSITORY', 'attacker/fork'),
            ('GITHUB_SHA', 'x'*40),
        ):
            with self.subTest(key=key), patch.dict(os.environ, {key: value}), patch.object(a, 'CLI') as cli:
                with self.assertRaises(Blocked):
                    a.run({})
                cli.assert_not_called()
        with patch.object(a, 'github_json', return_value={'object': {'sha': 'b'*40}}), patch.object(a, 'CLI') as cli:
            with self.assertRaisesRegex(Blocked, 'main_advanced'):
                a.run({})
            cli.assert_not_called()

    def test_secret_state_verifies_exact_true_or_false_but_never_changes_it(self):
        for gate in ('true', 'false'):
            rows = secrets(gate)
            selected = a.verified_secret_state(rows)
            self.assertEqual(a.safe_gate(selected), 'enabled_preserved' if gate == 'true' else 'disabled_preserved')
            self.assertEqual(a.verified_secret_state(rows, selected), selected)
        for bad_gate in ('TRUE', '0', ' false ', ''):
            with self.subTest(bad_gate=bad_gate), self.assertRaises(Blocked):
                a.verified_secret_state(secrets(bad_gate))
        for name in a.KEYS:
            rows = [r for r in secrets() if r['name'] != name]
            with self.assertRaises(Blocked):
                a.verified_secret_state(rows)
        with self.assertRaisesRegex(Blocked, 'changed_during'):
            a.verified_secret_state(secrets('false'), a.verified_secret_state(secrets('true')))

    def test_auto_success_true_and_false_with_read_only_smoke(self):
        for gate in ('true', 'false'):
            fake = FakeCLI()
            fake.secret_rows = secrets(gate)
            with patch.object(a, 'smoke_get', return_value='expected_read_only_rejection') as smoke:
                report = {'status': 'blocked'}
                a.deploy(fake, report)
            self.assertEqual(fake.writes, list(FUNCTIONS))
            self.assertTrue(fake.staged)
            self.assertEqual(report['status'], 'deployed')
            self.assertEqual(report['remote_write_gate'], 'enabled_preserved' if gate=='true' else 'disabled_preserved')
            self.assertEqual(smoke.call_count, 2)
            self.assertNotIn(fake.secret_rows[0]['value'], json.dumps(report))

    def test_gate_changes_during_first_function_prevents_second(self):
        fake = FakeCLI()
        fake.change_after_first = True
        with patch.object(a, 'smoke_get') as smoke:
            with self.assertRaisesRegex(Blocked, 'remote_secrets_changed_during_deploy'):
                a.deploy(fake, {'status': 'blocked'})
        self.assertEqual(fake.writes, [FUNCTIONS[0]])
        smoke.assert_not_called()

    def test_bad_function_status_fails_without_ignoring_error(self):
        fake = FakeCLI()
        fake.rows[0]['verify_jwt'] = True
        with patch.object(a, 'smoke_get') as smoke, self.assertRaises(Blocked):
            a.deploy(fake, {'status': 'blocked'})
        self.assertEqual(fake.writes, [FUNCTIONS[0]])
        smoke.assert_not_called()

    def test_smoke_only_get_and_exact_safe_rejection(self):
        for name, status in a.SMOKE_STATUS.items():
            error = a.urllib.error.HTTPError('https://fixture.invalid', status, 'expected', {}, io.BytesIO(b'PRIVATE'))
            opener = Mock()
            opener.open.side_effect = error
            self.assertEqual(a.smoke_get(name, opener), 'expected_read_only_rejection')
            request = opener.open.call_args.args[0]
            self.assertEqual(request.get_method(), 'GET')
            self.assertEqual(request.full_url, f'https://{a.PROJECT}.supabase.co/functions/v1/{name}')
            self.assertNotIn('Authorization', request.headers)
        with self.assertRaises(Blocked):
            a.smoke_get('other')
        for name in a.SMOKE_STATUS:
            opener = Mock()
            response = MagicMock()
            response.__enter__.return_value = response
            response.status = 200
            opener.open.return_value = response
            with self.assertRaisesRegex(Blocked, 'smoke_unexpected_http_status'):
                a.smoke_get(name, opener)

    def test_smoke_network_errors_do_not_expose_raw_contents(self):
        opener = Mock()
        opener.open.side_effect = RuntimeError('PRIVATE URL or token')
        with self.assertRaises(Blocked) as raised:
            a.smoke_get(FUNCTIONS[0], opener)
        self.assertNotIn('PRIVATE', str(raised.exception))

    def test_run_reads_secret_only_after_main_verification(self):
        fake = FakeCLI('sbp_testing_only')
        with patch.object(a, 'github_json', return_value={'object': {'sha': SHA}}), \
             patch.object(a, 'local_check', return_value={'functions': list(FUNCTIONS)}), \
             patch.object(a, 'CLI', return_value=fake), \
             patch.object(a, 'smoke_get', return_value='expected_read_only_rejection'):
            report = {'status': 'blocked'}
            a.run(report)
        self.assertEqual(report['status'], 'deployed')
        self.assertEqual(report['source_sha'], SHA)
        self.assertEqual(fake.token, 'sbp_testing_only')

    def test_main_sanitizes_unexpected_error(self):
        with patch.object(a, 'run', side_effect=RuntimeError('PRIVATE token')):
            output = io.StringIO()
            with redirect_stdout(output), self.assertRaises(SystemExit):
                a.main()
        self.assertIn('details_withheld', output.getvalue())
        self.assertNotIn('PRIVATE', output.getvalue())

    def test_workflow_push_main_checks_before_token_and_no_sql(self):
        source = (Path(__file__).resolve().parents[1] / '.github/workflows/community-edge-auto.yml').read_text()
        self.assertIn('  push:', source)
        self.assertIn('    branches: [main]', source)
        self.assertIn("      - 'supabase/functions/**'", source)
        self.assertIn('cancel-in-progress: false', source)
        self.assertIn('community-edge-production-deploy', source)
        checks, deploy = source.split('  check:\n', 1)[1].split('  deploy:\n', 1)
        self.assertNotIn('secrets.', checks)
        self.assertIn('secrets.SUPABASE_EDGE_AUTO_DEPLOY_PAT', deploy)
        self.assertIn('python3 tools/community_edge_autodeploy.py', deploy)
        self.assertNotIn('supabase db push', source)
        self.assertNotIn('secrets set', source)
        self.assertNotIn('SUPABASE_SERVICE_ROLE_KEY', source)
        self.assertNotIn('SUPABASE_DB_PASSWORD', source)
        self.assertNotIn('pull_request:', source)
        self.assertIn('deno check', checks)
        self.assertIn('node --test', checks)


if __name__ == '__main__':
    main()
