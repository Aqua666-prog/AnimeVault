#!/usr/bin/env python3
"""Explicitly approved Edge deploy only. CLI stdout/stderr and secret digests never leave memory."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import tomllib
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
PROJECT = 'kgvkxocbkklisqrqmcjx'
REPOSITORY = 'Aqua666-prog/AnimeVault'
ENVIRONMENT = 'community-edge-production'
CLI_VERSION = '2.54.11'
CONFIRMATION = 'DEPLOY COMMUNITY EDGE ' + PROJECT
FUNCTIONS = ('community-ensure', 'community-catalog-worker')
SOURCES = ('supabase/config.toml', *(f'supabase/functions/{f}/index.ts' for f in FUNCTIONS),
           'supabase/functions/_shared/community_auto.mjs')
FALSE_DIGEST = hashlib.sha256(b'false').hexdigest()
EMPTY_DIGEST = hashlib.sha256(b'').hexdigest()


class Blocked(Exception):
    pass


def require(condition, code):
    if not condition:
        raise Blocked(code)


def dispatch(command):
    require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'manual_dispatch_required')
    require(os.environ.get('GITHUB_REF') == 'refs/heads/main', 'main_branch_required')
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'repository_mismatch')
    require(re.fullmatch(r'[a-f0-9]{40}', os.environ.get('GITHUB_SHA', '')), 'source_sha_missing')
    mode = os.environ.get('EDGE_ACTION')
    require(mode in {'check', 'deploy'}, 'invalid_action')
    require(command != 'deploy' or mode == 'deploy', 'deploy_action_required')
    confirmation = os.environ.get('EDGE_CONFIRMATION', '')
    if mode == 'deploy':
        require(confirmation == CONFIRMATION, 'exact_deploy_confirmation_required')
    else:
        require(not confirmation, 'confirmation_supplied_in_check_mode')


def access_token():
    token = os.environ.get('SUPABASE_ACCESS_TOKEN', '')
    require(bool(token), 'supabase_access_token_missing')
    require(token.startswith('sbp_') and len(token) > 4 and not any(c.isspace() for c in token),
            'supabase_personal_access_token_required_not_service_role_or_db_password')
    return token


def local_check(root=None):
    root = root or ROOT
    for relative in SOURCES:
        path = root / relative
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(root.resolve()),
                'required_source_missing_or_symlink')
    with (root / 'supabase/config.toml').open('rb') as handle:
        config = tomllib.load(handle)
    require(set(config) == {'project_id', 'functions'}, 'unreviewed_project_configuration')
    require(config.get('project_id') == PROJECT, 'config_project_mismatch')
    functions = config.get('functions', {})
    require(set(functions) == set(FUNCTIONS), 'config_function_names_mismatch')
    for name in FUNCTIONS:
        require(functions[name] == {'verify_jwt': False}, 'unexpected_function_configuration_or_verify_jwt')
    # New files/imports must be reviewed explicitly before they can enter a deployment bundle.
    actual = {str(p.relative_to(root)) for p in (root / 'supabase/functions').rglob('*') if p.is_file()}
    require(actual == set(SOURCES) - {'supabase/config.toml'}, 'unreviewed_function_bundle_files')
    migration = (root / 'supabase/migrations/202611020001_community_zero_touch.sql').read_text()
    for flag in ('on_demand_enabled', 'background_enabled'):
        require(re.search(flag + r'\s+boolean\s+NOT NULL\s+DEFAULT false', migration, re.I), 'migration_auto_write_default_not_off')
    for relative in SOURCES[1:]:
        source = (root / relative).read_text()
        require(not re.search(r'''COMMUNITY_AUTO_WRITE_ENABLED\s*[:=]\s*['"]true['"]|Deno\.env\.set\s*\(''', source),
                'embedded_auto_write_override_refused')
    return {'project': PROJECT, 'functions': list(FUNCTIONS), 'verify_jwt': False,
            'source_defaults': 'auto_write_off', 'bundle': list(SOURCES)}


class GitHubMetadataFailure(Blocked):
    def __init__(self, code, stage, status, kind):
        super().__init__(code)
        self.diagnostic = {'source': 'github', 'stage': stage, 'http_status': status, 'error_type': kind}


def github_json(path):
    # This endpoint requires actions:read; main-ref lookup requires contents:read.
    # Anonymous public reads have a shared 60/hour IP budget on hosted runners.
    paths = {'/environments/' + ENVIRONMENT: 'environment', '/git/ref/heads/main': 'main_ref'}
    require(path in paths, 'github_metadata_path_not_allowlisted')
    stage = paths[path]
    token = os.environ.get('GH_METADATA_TOKEN', '')
    require(token and not any(c.isspace() for c in token), 'github_metadata_token_missing')
    request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + path,
                                     headers={'Accept': 'application/vnd.github+json',
                                              'Authorization': 'Bearer ' + token,
                                              'X-GitHub-Api-Version': '2022-11-28',
                                              'User-Agent': 'AnimeVault-Edge-Deploy'})
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            raise GitHubMetadataFailure('github_metadata_redirect_refused', stage, args[2], 'redirect_refused')
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=15) as response:
            raw = response.read(1024 * 1024 + 1)
        require(len(raw) <= 1024 * 1024, 'github_metadata_too_large')
        try:
            result = json.loads(raw)
        except (ValueError, UnicodeError):
            raise GitHubMetadataFailure('github_metadata_invalid_json_no_deploy', stage, 200, 'invalid_json') from None
        if not isinstance(result, dict):
            raise GitHubMetadataFailure('github_metadata_invalid_object_no_deploy', stage, 200, 'invalid_shape')
        return result
    except urllib.error.HTTPError as error:
        status = error.code
        if status == 429 or (status == 403 and (error.headers or {}).get('X-RateLimit-Remaining') == '0'):
            kind = 'rate_limited'
        elif status == 401:
            kind = 'unauthorized'
        elif status == 403:
            kind = 'permission_denied_actions_read_required'
        elif status == 404:
            kind = 'not_found_or_not_accessible'
        elif status >= 500:
            kind = 'server_unavailable'
        else:
            kind = 'http_error'
        raise GitHubMetadataFailure('github_metadata_http_' + str(status) + '_' + kind + '_no_deploy',
                                    stage, status, kind) from None
    except Blocked:
        raise
    except Exception:
        raise GitHubMetadataFailure('github_protection_metadata_unavailable_no_deploy', stage, None, 'network_error') from None


def validate_environment(environment):
    require(isinstance(environment, dict), 'github_environment_metadata_invalid')
    require(environment.get('name') == ENVIRONMENT, 'protected_environment_missing')
    rules = environment.get('protection_rules')
    require(isinstance(rules, list) and all(isinstance(rule, dict) for rule in rules), 'github_environment_metadata_invalid')
    required = [rule for rule in rules if rule.get('type') == 'required_reviewers']
    require(bool(required), 'environment_required_reviewers_missing')
    for rule in required:
        reviewers = rule.get('reviewers')
        require(isinstance(reviewers, list) and bool(reviewers), 'environment_required_reviewers_missing')
        # GitHub wraps each reviewer as {type: User|Team, reviewer: {id, ...}}.
        require(all(isinstance(item, dict) and item.get('type') in {'User', 'Team'}
                    and isinstance(item.get('reviewer'), dict)
                    and type(item['reviewer'].get('id')) is int and item['reviewer']['id'] > 0
                    for item in reviewers), 'github_environment_reviewers_invalid')


def protected_environment():
    validate_environment(github_json('/environments/' + ENVIRONMENT))
    branch = github_json('/git/ref/heads/main')
    require(isinstance(branch.get('object'), dict), 'github_main_ref_metadata_invalid')
    require(branch['object'].get('sha') == os.environ['GITHUB_SHA'], 'main_changed_rerun_workflow')


class CLI:
    def __init__(self, token=None):
        self.token = token
        self.temp = tempfile.TemporaryDirectory(prefix='av-edge-deploy-')
        self.work = Path(self.temp.name)
        self.home = self.work / 'home'
        self.home.mkdir()
        self.bundle = self.work / 'bundle'
        self.bundle.mkdir()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.temp.cleanup()

    def stage(self):
        # No migrations, .env, git metadata or unrelated functions copied.
        for relative in SOURCES:
            destination = self.bundle / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)

    def deploy_args(self, name):
        return ['functions', 'deploy', name, '--project-ref', PROJECT, '--use-api', '--yes',
                '--workdir', str(self.bundle)]

    def call(self, args, phase, as_json=False):
        readonly = [
            ['--version'],
            ['secrets', 'list', '--project-ref', PROJECT, '--output', 'json'],
            ['functions', 'list', '--project-ref', PROJECT, '--output', 'json'],
        ]
        require(args in readonly or any(args == self.deploy_args(name) for name in FUNCTIONS), 'cli_operation_not_allowlisted')
        require(args == ['--version'] or self.token, 'supabase_access_token_missing')
        env = {'PATH': os.environ.get('PATH', ''), 'HOME': str(self.home), 'CI': 'true', 'NO_COLOR': '1'}
        if self.token:
            env['SUPABASE_ACCESS_TOKEN'] = self.token
        # Do not inherit custom API hosts, DB credentials, profiles, debug flags or other provider keys.
        try:
            result = subprocess.run(['supabase', *args], cwd=self.work, env=env, capture_output=True,
                                    text=True, encoding='utf-8', errors='replace', timeout=240, check=False)
        except FileNotFoundError:
            raise Blocked('pinned_supabase_cli_missing') from None
        except subprocess.TimeoutExpired:
            raise Blocked('cli_timeout_' + phase + '_outcome_requires_review') from None
        require(result.returncode == 0, 'cli_failed_' + phase + '_check_token_permissions_or_api_availability')
        if not as_json:
            return result.stdout.strip()
        try:
            return json.loads(result.stdout)
        except ValueError:
            raise Blocked('cli_invalid_json_' + phase) from None

    def secrets(self):
        return self.call(['secrets', 'list', '--project-ref', PROJECT, '--output', 'json'], 'secret_preflight', True)

    def functions(self):
        return self.call(['functions', 'list', '--project-ref', PROJECT, '--output', 'json'], 'function_status', True)

    def deploy(self, name):
        require(name in FUNCTIONS, 'unapproved_function')
        self.call(self.deploy_args(name), 'deploy_' + name)


def secret_preflight(rows):
    require(isinstance(rows, list) and all(isinstance(r, dict) for r in rows), 'secret_metadata_shape')
    selected = {}
    for name in ('MAL_CLIENT_ID', 'COMMUNITY_PUBLIC_KEY', 'COMMUNITY_AUTO_WRITE_ENABLED'):
        matches = [r for r in rows if r.get('name') == name]
        require(len(matches) == 1, 'required_supabase_secret_missing_' + name)
        digest = matches[0].get('value')
        require(isinstance(digest, str) and re.fullmatch(r'[a-fA-F0-9]{64}', digest), 'secret_digest_format_unknown_no_deploy')
        require(digest.lower() != EMPTY_DIGEST, 'required_supabase_secret_empty_' + name)
        selected[name] = digest.lower()
    require(selected['COMMUNITY_AUTO_WRITE_ENABLED'] == FALSE_DIGEST, 'remote_auto_write_not_verified_exactly_false')
    # Only fixed verdicts escape this function, never digests or arbitrary response fields.
    return {'MAL_CLIENT_ID': 'present_not_runtime_validated', 'COMMUNITY_PUBLIC_KEY': 'present_not_runtime_validated',
            'COMMUNITY_AUTO_WRITE_ENABLED': 'verified_false_digest'}


def function_status(rows, name, required=False):
    require(isinstance(rows, list) and all(isinstance(r, dict) for r in rows), 'function_metadata_shape')
    matches = [r for r in rows if r.get('slug') == name]
    require(len(matches) <= 1, 'duplicate_remote_function_slug')
    if not matches:
        require(not required, 'deployed_function_missing_' + name)
        return {'status': 'absent', 'version': None, 'verify_jwt': None}
    row = matches[0]
    state = row.get('status') if row.get('status') in {'ACTIVE', 'REMOVED', 'THROTTLED'} else 'UNKNOWN'
    version = row.get('version') if type(row.get('version')) is int and row['version'] > 0 else None
    jwt = row.get('verify_jwt') if type(row.get('verify_jwt')) is bool else None
    if required:
        require(state == 'ACTIVE' and version is not None and jwt is False, 'deployed_function_not_active_or_jwt_mismatch_' + name)
    return {'status': state, 'version': version, 'verify_jwt': jwt}


def publish(cli, report):
    report['remote_secrets'] = secret_preflight(cli.secrets())
    before = cli.functions()
    report['functions'] = {name: {'before': function_status(before, name), 'deployment': 'not_attempted'} for name in FUNCTIONS}
    cli.stage()
    for name in FUNCTIONS:
        report['remote_secrets'] = secret_preflight(cli.secrets())
        report['functions'][name]['deployment'] = 'attempted_outcome_not_confirmed'
        cli.deploy(name)
        # CLI may return success for an unchanged bundle. A version increment is not required for a safe re-run.
        report['functions'][name]['after'] = function_status(cli.functions(), name, required=True)
        before_version = report['functions'][name]['before']['version']
        require(before_version is None or report['functions'][name]['after']['version'] >= before_version, 'remote_function_version_regressed_' + name)
        report['functions'][name]['deployment'] = 'verified_active'
    report['remote_secrets'] = secret_preflight(cli.secrets())
    report['status'] = 'deployed'
    report['runtime_smoke'] = 'not_invoked_no_requests_that_can_create_jobs_or_data'


def run(command, report):
    dispatch(command)
    token = access_token() if command == 'deploy' else None
    report.update(local=local_check(), source_sha=os.environ['GITHUB_SHA'])
    if os.environ['EDGE_ACTION'] == 'deploy':
        protected_environment()
        report['environment'] = 'required_reviewers_verified'
    with CLI(token) as cli:
        require(cli.call(['--version'], 'version') == CLI_VERSION, 'supabase_cli_version_mismatch')
        report['cli_version'] = CLI_VERSION
        if command == 'deploy':
            publish(cli, report)
        else:
            report.update(status='checked', remote_secrets='not_checked_no_production_credentials',
                          remote_functions='not_checked', db_settings='not_inspected_no_sql')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['check', 'deploy'])
    args = parser.parse_args()
    report = {'status': 'blocked', 'project': PROJECT, 'action': args.command,
              'functions': {name: {'deployment': 'not_attempted'} for name in FUNCTIONS}}
    try:
        run(args.command, report)
    except Blocked as error:
        report['error'] = str(error)
        if isinstance(error, GitHubMetadataFailure):
            report['github_diagnostic'] = error.diagnostic
    except Exception:
        report['error'] = 'unexpected_local_or_metadata_error_details_withheld'
    finally:
        safe = json.dumps(report, ensure_ascii=False, sort_keys=True)
        print(safe)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
                summary.write('### Community Edge Deploy\n\n```json\n' + safe + '\n```\n')
    if report['status'] not in {'checked', 'deployed'}:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
