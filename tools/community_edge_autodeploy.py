#!/usr/bin/env python3
"""Unattended, source-pinned Edge *code* deployment. Never mutates Secrets, SQL or job data.

Only the current main commit may deploy, after the workflow's tests pass. This path
intentionally does not require the manual protected-environment reviewer gate:
it uses an explicitly configured repository Actions secret instead.
"""
import json
import os
import re
import urllib.error
import urllib.request

from community_edge_deploy import (
    Blocked, CLI, CLI_VERSION, FUNCTIONS, PROJECT, REPOSITORY,
    access_token, function_status, github_json, local_check, require,
)
from community_edge_deploy import EMPTY_DIGEST
import hashlib

TRUE_DIGEST = hashlib.sha256(b'true').hexdigest()
FALSE_DIGEST = hashlib.sha256(b'false').hexdigest()
KEYS = ('MAL_CLIENT_ID', 'COMMUNITY_PUBLIC_KEY', 'COMMUNITY_AUTO_WRITE_ENABLED')
SMOKE_STATUS = {'community-ensure': 405, 'community-catalog-worker': 401}


def trusted_main():
    require(os.environ.get('GITHUB_EVENT_NAME') in {'push', 'workflow_dispatch'},
            'main_push_or_explicit_rerun_required')
    require(os.environ.get('GITHUB_REF') == 'refs/heads/main', 'main_branch_required')
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'repository_mismatch')
    sha = os.environ.get('GITHUB_SHA', '')
    require(bool(re.fullmatch('[a-f0-9]{40}', sha)), 'source_sha_missing')
    ref = github_json('/git/ref/heads/main')
    require(isinstance(ref.get('object'), dict) and ref['object'].get('sha') == sha,
            'main_advanced_skip_stale_deploy')
    return sha


def verified_secret_state(rows, expected=None):
    require(isinstance(rows, list) and all(isinstance(r, dict) for r in rows), 'secret_metadata_shape')
    selected = {}
    for name in KEYS:
        match = [row for row in rows if row.get('name') == name]
        require(len(match) == 1, 'required_supabase_secret_missing_' + name)
        digest = match[0].get('value')
        require(isinstance(digest, str) and re.fullmatch('[a-fA-F0-9]{64}', digest),
                'secret_digest_format_unknown_no_deploy')
        digest = digest.lower()
        require(digest != EMPTY_DIGEST, 'required_supabase_secret_empty_' + name)
        selected[name] = digest
    # Live auto-write may already be on: code deployments MUST preserve it as-is.
    require(selected['COMMUNITY_AUTO_WRITE_ENABLED'] in {TRUE_DIGEST, FALSE_DIGEST},
            'remote_auto_write_not_exact_boolean')
    if expected is not None:
        require(selected == expected, 'remote_secrets_changed_during_deploy')
    return selected


def safe_gate(selected):
    return 'enabled_preserved' if selected['COMMUNITY_AUTO_WRITE_ENABLED'] == TRUE_DIGEST else 'disabled_preserved'


def smoke_get(name, opener=None):
    """GET has no write path: the two handlers MUST refuse these requests."""
    require(name in SMOKE_STATUS, 'smoke_function_not_allowlisted')
    url = f'https://{PROJECT}.supabase.co/functions/v1/{name}'
    request = urllib.request.Request(url, method='GET',
                                     headers={'Accept': 'application/json', 'User-Agent': 'AnimeVault-Edge-Smoke'})
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            raise Blocked('smoke_redirect_refused')
    opener = opener or urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(request, timeout=15) as response:
            status = response.status
            # No response body read, logged or trusted.
    except urllib.error.HTTPError as error:
        status = error.code
        error.close()
    except Blocked:
        raise
    except Exception:
        raise Blocked('smoke_network_unavailable_' + name.replace('-', '_')) from None
    require(status == SMOKE_STATUS[name], 'smoke_unexpected_http_status_' + name.replace('-', '_'))
    return 'expected_read_only_rejection'


def deploy(cli, report):
    baseline = verified_secret_state(cli.secrets())
    report['remote_write_gate'] = safe_gate(baseline)
    before = cli.functions()
    report['functions'] = {name: {'before': function_status(before, name),
                                   'deployment': 'not_attempted'} for name in FUNCTIONS}
    cli.stage()
    for name in FUNCTIONS:
        verified_secret_state(cli.secrets(), baseline)
        report['functions'][name]['deployment'] = 'attempted_outcome_not_confirmed'
        cli.deploy(name)
        after = function_status(cli.functions(), name, required=True)
        prior = report['functions'][name]['before']['version']
        require(prior is None or after['version'] >= prior,
                'remote_function_version_regressed_' + name)
        report['functions'][name]['after'] = after
        report['functions'][name]['deployment'] = 'verified_active'
    verified_secret_state(cli.secrets(), baseline)
    # Never POST to either handler: no app data, job, or comment should be created.
    report['smoke'] = {name: smoke_get(name) for name in FUNCTIONS}
    verified_secret_state(cli.secrets(), baseline)
    report['status'] = 'deployed'


def run(report):
    # Reject untrusted refs and stale commits before any Supabase credential is accessed.
    sha = trusted_main()
    report['source_sha'] = sha
    report['local'] = local_check()
    token = access_token()
    with CLI(token) as cli:
        require(cli.call(['--version'], 'version') == CLI_VERSION,
                'supabase_cli_version_mismatch')
        report['cli_version'] = CLI_VERSION
        deploy(cli, report)


def main():
    report = {'status': 'blocked', 'project': PROJECT, 'action': 'automatic_deploy',
              'functions': {name: {'deployment': 'not_attempted'} for name in FUNCTIONS}}
    try:
        run(report)
    except Blocked as error:
        report['error'] = str(error)
    except Exception:
        report['error'] = 'unexpected_error_details_withheld'
    finally:
        safe = json.dumps(report, ensure_ascii=False, sort_keys=True)
        print(safe)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
                summary.write('### Community Edge Auto Deploy\n\n```json\n' + safe + '\n```\n')
    if report['status'] != 'deployed':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
