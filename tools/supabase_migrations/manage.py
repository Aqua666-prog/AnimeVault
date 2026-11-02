#!/usr/bin/env python3
"""Fail-closed Supabase CLI orchestration. Never prints DSNs, CLI output or SQL data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import socket
import struct
import time
import subprocess
import tempfile
from urllib.parse import urlsplit, parse_qs, urlencode, urlunsplit, quote

import psycopg
from pglast import parse_sql, ast

ROOT=Path(__file__).resolve().parents[2]
PROJECT='kgvkxocbkklisqrqmcjx'
BASE='202611010001'
SYNC='202611010002'
AUTO='202611020001'
REPAIR='202611020002'
CLI_VERSION='2.54.11'
REFERENCE=Path(__file__).with_name('reference.json')

class Blocked(Exception): pass

def encoded(value): return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False)
def digest(value): return hashlib.sha256(encoded(value).encode()).hexdigest()

def files():
    result={}
    for path in sorted((ROOT/'supabase/migrations').glob('*.sql')):
        match=re.fullmatch(r'(\d{12})_[a-z0-9_]+\.sql',path.name)
        if not match or match[1] in result: raise Blocked('invalid_migration_filename')
        result[match[1]]=path
    if set(result)!={BASE,SYNC,AUTO,REPAIR}: raise Blocked('unreviewed_migrations_update_reference_and_runner')
    return result

def repair_bodies():
    """Source-locked replacement bodies, used to verify applied repair without blessing drift."""
    source=files()[REPAIR].read_text()
    signatures={
        'community_auto_claim':'public.community_auto_claim(jsonb,text,boolean)',
        'community_auto_finish':'public.community_auto_finish(text,uuid,text,boolean)',
    }
    result={}
    for name,signature in signatures.items():
        found=re.search(r'CREATE OR REPLACE FUNCTION public\.'+name+r'\([^)]*\).*? AS \$\$(.*?)\$\$;',source,re.S)
        if not found:raise Blocked('missing_reviewed_repair_body')
        result[signature]=found.group(1)
    return result

def statements(text):
    """Only outer transaction markers may be removed. Parse SQL, not dollar-body semicolons."""
    nodes=parse_sql(text)
    if len(nodes)<3 or not isinstance(nodes[0].stmt,ast.TransactionStmt) or not isinstance(nodes[-1].stmt,ast.TransactionStmt):
        raise Blocked('expected_outer_transaction')
    if nodes[0].stmt.kind.name!='TRANS_STMT_BEGIN' or nodes[-1].stmt.kind.name!='TRANS_STMT_COMMIT':
        raise Blocked('expected_begin_commit')
    if any(isinstance(n.stmt,ast.TransactionStmt) for n in nodes[1:-1]): raise Blocked('nested_transaction_refused')
    raw=text
    result=[]
    for node in nodes[1:-1]:
        segment=raw[node.stmt_location:node.stmt_location+node.stmt_len] if node.stmt_len else raw[node.stmt_location:]
        result.append((node.stmt,segment.strip().rstrip(';')+';'))
    return result

def snapshot(db):
    """No application data. Catalog definitions + effective API privileges only."""
    db.execute('SET search_path=pg_catalog')
    result={}
    tables=db.execute("SELECT n.nspname,c.relname,c.oid,c.relrowsecurity,c.relforcerowsecurity,pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname LIKE 'community\\_%' ESCAPE '\\' AND c.relkind IN ('r','p','v','m') ORDER BY 1,2").fetchall()
    for schema,name,oid,rls,force,owner in tables:
        key='table:'+schema+'.'+name
        columns=db.execute("SELECT attname,format_type(atttypid,atttypmod),attnotnull,attidentity,attgenerated,(a.attacl IS NOT NULL),pg_get_expr(d.adbin,d.adrelid) FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid=%s AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum",(oid,)).fetchall()
        constraints=db.execute('SELECT conname,contype,pg_get_constraintdef(oid),convalidated FROM pg_constraint WHERE conrelid=%s ORDER BY conname',(oid,)).fetchall()
        indexes=db.execute('SELECT pg_get_indexdef(indexrelid),indisvalid FROM pg_index WHERE indrelid=%s ORDER BY pg_get_indexdef(indexrelid)',(oid,)).fetchall()
        policies=db.execute('SELECT polname,polcmd,polpermissive,ARRAY(SELECT CASE WHEN r=0 THEN \'PUBLIC\' ELSE pg_get_userbyid(r) END FROM unnest(polroles) r ORDER BY 1),pg_get_expr(polqual,polrelid),pg_get_expr(polwithcheck,polrelid) FROM pg_policy WHERE polrelid=%s ORDER BY polname',(oid,)).fetchall()
        roles=['anon','authenticated']+(['service_role'] if name.startswith(('community_sync_','community_auto_')) else [])
        privileges=[(role,priv,db.execute('SELECT has_table_privilege(%s,%s,%s)',(role,oid,priv)).fetchone()[0]) for role in roles for priv in ('SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER')]
        columns_acl=[(role,priv,db.execute('SELECT has_any_column_privilege(%s,%s,%s)',(role,oid,priv)).fetchone()[0]) for role in roles for priv in ('SELECT','INSERT','UPDATE','REFERENCES')]
        public_acl=db.execute("SELECT privilege_type FROM pg_class c, LATERAL aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid=%s AND grantee=0 ORDER BY privilege_type",(oid,)).fetchall()
        result[key]={'structure':digest([columns,constraints,indexes,policies]),'security':digest([rls,force,owner,privileges,columns_acl,public_acl]),'owner':owner,'force_rls':force}
    funcs=db.execute("SELECT p.oid,n.nspname,p.proname,pg_get_function_identity_arguments(p.oid),l.lanname,p.prosrc,p.probin,pg_get_function_result(p.oid),p.provolatile,p.proisstrict,p.prosecdef,p.proconfig,pg_get_userbyid(p.proowner),pg_get_expr(p.proargdefaults,0) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang WHERE n.nspname='community_internal' OR (n.nspname='public' AND p.proname LIKE 'community\\_%' ESCAPE '\\') ORDER BY 2,3,4").fetchall()
    for oid,schema,name,args,*props in funcs:
        roles=['anon','authenticated']+(['service_role'] if name=='community_sync_batch' or name.startswith('community_auto_') else [])
        privileges=[(r,db.execute('SELECT has_function_privilege(%s,%s,\'EXECUTE\')',(r,oid)).fetchone()[0]) for r in roles]
        public_acl=db.execute("SELECT privilege_type FROM pg_proc p,LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE p.oid=%s AND grantee=0 ORDER BY privilege_type",(oid,)).fetchall()
        # Owner, SECURITY DEFINER, search_path and EXECUTE are a separate security component.
        result['function:'+schema+'.'+name+'('+args+')']={'structure':digest(props[:6]+[props[9]]),'security':digest([props[6:9],privileges,public_acl])}
    for schema,table,name,definition in db.execute("SELECT n.nspname,c.relname,t.tgname,pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE NOT t.tgisinternal AND t.tgname LIKE 'community\\_%' ESCAPE '\\' ORDER BY 1,2,3"):
        result['trigger:'+schema+'.'+table+'.'+name]={'structure':digest(definition),'security':digest([])}
    public_permissions=[(role,priv,db.execute("SELECT has_schema_privilege(%s,'public',%s)",(role,priv)).fetchone()[0]) for role in ('anon','authenticated') for priv in ('USAGE','CREATE')]
    result['schema:public']={'structure':digest('schema'),'security':digest(public_permissions)}
    ns=db.execute("SELECT oid FROM pg_namespace WHERE nspname='community_internal'").fetchone()
    if ns:
        permissions=[(role,priv,db.execute('SELECT has_schema_privilege(%s,%s,%s)',(role,ns[0],priv)).fetchone()[0]) for role in ('anon','authenticated') for priv in ('USAGE','CREATE')]
        result['schema:community_internal']={'structure':digest('schema'),'security':digest(permissions)}
    return result

def history(db):
    if not db.execute("SELECT to_regclass('supabase_migrations.schema_migrations')").fetchone()[0]:return []
    versions=[r[0] for r in db.execute('SELECT version FROM supabase_migrations.schema_migrations ORDER BY version')]
    if any(not re.fullmatch(r'\d{1,20}',v) for v in versions): raise Blocked('unknown_remote_history')
    return versions

def inspect(db):
    reference=json.loads(REFERENCE.read_text())
    paths=files()
    if reference['files']!={v:hashlib.sha256(p.read_bytes()).hexdigest() for v,p in paths.items()}:raise Blocked('reference_source_mismatch')
    current=snapshot(db); remote=history(db)
    unknown=sorted(set(remote)-set(paths))
    baseline=reference['baseline']; sync=reference['sync']
    missing_base=sorted(set(baseline)-set(current))
    drift_base=sorted(k for k in baseline.keys() & current.keys() if baseline[k]!=current[k])
    missing_sync=sorted(set(sync)-set(current))
    structure_sync=sorted(k for k in sync.keys() & current.keys() if sync[k]['structure']!=current[k]['structure'])
    security_sync=sorted(k for k in sync.keys() & current.keys() if sync[k]['security']!=current[k]['security'])
    unrepairable=sorted(k for k in sync.keys() & current.keys() if k.startswith('table:') and any(current[k].get(field)!=sync[k].get(field) for field in ('owner','force_rls')))
    auto=reference['auto']
    missing_auto=sorted(set(auto)-set(current))
    repair_applied=REPAIR in remote
    repair_targets=repair_bodies()
    repair_names={
        'function:public.community_auto_claim(p_identity jsonb, p_client_hash text, p_background boolean)':'public.community_auto_claim(jsonb,text,boolean)',
        'function:public.community_auto_finish(p_key text, p_lease uuid, p_code text, p_retry boolean)':'public.community_auto_finish(text,uuid,text,boolean)',
    }
    drift_auto=[]
    for key in sorted(auto.keys() & current.keys()):
        if repair_applied and key in repair_names:
            signature=repair_names[key]
            row=db.execute('SELECT prosrc FROM pg_proc WHERE oid=to_regprocedure(%s)',(signature,)).fetchone()
            if current[key]['security']!=auto[key]['security'] or not row or row[0]!=repair_targets[signature]:
                drift_auto.append(key)
        elif auto[key]!=current[key]:
            drift_auto.append(key)
    auto_state='absent' if len(missing_auto)==len(auto) else 'complete' if not missing_auto and not drift_auto else 'partial_or_drift'
    extras=sorted(set(current)-set(baseline)-set(sync)-set(auto))
    state='absent' if len(missing_sync)==len(sync) else 'complete' if not missing_sync and not structure_sync and not security_sync else 'partial_or_drift'
    blockers=[]
    inherited=db.execute("SELECT 1 FROM pg_auth_members am JOIN pg_roles r ON r.oid=am.member WHERE r.rolname IN ('anon','authenticated','service_role') LIMIT 1").fetchone()
    if auto_state=='partial_or_drift':blockers.append('zero_touch_partial_or_drift_requires_review')
    if AUTO in remote and auto_state!='complete':blockers.append('zero_touch_history_schema_disagree')
    if REPAIR in remote and (AUTO not in remote or auto_state!='complete'):blockers.append('pending_repair_history_schema_disagree')
    if inherited:blockers.append('api_role_inheritance_requires_review')
    if unknown:blockers.append('unknown_remote_migrations')
    if extras:blockers.append('unexpected_community_objects')
    if missing_base or drift_base:blockers.append('baseline_not_exact_never_reexecute_001')
    if unrepairable:blockers.append('autosync_owner_or_force_rls_drift_requires_review')
    if structure_sync:blockers.append('autosync_structure_drift_requires_reviewed_additive_repair')
    if SYNC in remote and state!='complete':blockers.append('history_schema_disagree_requires_review')
    return {'project':PROJECT,'zero_touch':{'state':auto_state,'missing':missing_auto,'drift':drift_auto},'baseline':{'missing':missing_base,'drift':drift_base},'autosync':{'state':state,'missing':missing_sync,'structure_drift':structure_sync,'security_drift':security_sync},'history':remote,'history_to_adopt':([BASE] if BASE not in remote and not missing_base and not drift_base else [])+([SYNC] if SYNC not in remote and state=='complete' else [])+([AUTO] if AUTO not in remote and auto_state=='complete' else []),'pending_sql':([paths[SYNC].name] if SYNC not in remote and state=='absent' else [])+([paths[AUTO].name] if AUTO not in remote and auto_state=='absent' else [])+([paths[REPAIR].name] if REPAIR not in remote else []),'recovery_required':state=='partial_or_drift','unexpected_objects':extras,'unknown_history':unknown,'blockers':blockers,'plan_hash':digest([reference['files'],current,remote])}

def secret_url(raw,password=''):
    # Separate raw-password Secret avoids manual URI escaping on a phone.
    if not password:return raw
    try:
        u=urlsplit(raw.replace('[YOUR-PASSWORD]',''))
        if not u.username or not u.hostname:raise ValueError()
        host=u.hostname+(':'+str(u.port) if u.port else '')
        netloc=u.username+':'+quote(password,safe='')+'@'+host
        return urlunsplit((u.scheme,netloc,u.path,u.query,u.fragment))
    except ValueError:raise Blocked('invalid_database_secret_or_project_host') from None

def prepare_trust(certificate,directory):
    """Owner-supplied Supabase CA + public system roots; never replace one with the other."""
    if not certificate.strip():raise Blocked('supabase_ca_missing_download_from_database_settings')
    pem=certificate.replace('\r\n','\n').strip()
    pattern=r'-----BEGIN CERTIFICATE-----\s+[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----'
    blocks=re.findall(pattern,pem)
    if not blocks or re.sub(pattern,'',pem).strip():raise Blocked('supabase_ca_invalid_pem')
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    try:
        context.load_verify_locations(cadata=pem)
        authorities=context.get_ca_certs()
    except (ssl.SSLError,ValueError):raise Blocked('supabase_ca_invalid_pem') from None
    if len(authorities)!=len(set(blocks)):raise Blocked('supabase_ca_must_be_ca_certificate')
    now=time.time()
    for authority in authorities:
        if ssl.cert_time_to_seconds(authority['notAfter'])<=now:raise Blocked('supabase_ca_expired')
        if ssl.cert_time_to_seconds(authority['notBefore'])>now:raise Blocked('supabase_ca_not_yet_valid')
    # Export the OS trust store so libpq and the Go CLI consume exactly the same bundle.
    roots=ssl.create_default_context().get_ca_certs(binary_form=True)
    public_pem=''.join(ssl.DER_cert_to_PEM_cert(cert) for cert in roots)
    path=Path(directory)/'supabase-and-system-ca.pem'
    path.write_text(public_pem+'\n'+pem+'\n');path.chmod(0o600)
    # Validate the finished bundle before attempting a connection.
    ssl.create_default_context(cafile=str(path))
    return str(path)

def tls_probe(url):
    """On a TLS failure only, check the same endpoint without sending Auth/startup/SQL."""
    try:
        u=urlsplit(url);q=parse_qs(u.query)
        context=ssl.create_default_context(cafile=q['sslrootcert'][0])
        with socket.create_connection((u.hostname,u.port or 5432),timeout=5) as raw:
            raw.sendall(struct.pack('!II',8,80877103)) # PostgreSQL SSLRequest, no credentials.
            if raw.recv(1)!=b'S':return 'tls_server_refused_encryption'
            with context.wrap_socket(raw,server_hostname=u.hostname):pass
        return 'tls_certificate_verification_failed' # Do not reinterpret the original libpq failure.
    except ssl.SSLCertVerificationError as exc:
        if exc.verify_code in {18,19,20,21}:return 'tls_ca_untrusted'
        if exc.verify_code in {9,10}:return 'tls_certificate_expired_or_not_yet_valid'
        if exc.verify_code==62:return 'tls_certificate_hostname_mismatch'
        return 'tls_certificate_verification_failed'
    except (OSError,ValueError,KeyError):return 'tls_certificate_verification_failed'

def connection_url(raw,readonly=True,local=False):
    try:
        u=urlsplit(raw); q=parse_qs(u.query,keep_blank_values=True)
        if u.scheme not in {'postgres','postgresql'} or not u.password or u.fragment or u.path!='/postgres' and not local:raise ValueError()
        if local:
            if u.hostname not in {'127.0.0.1','localhost','::1'}:raise ValueError()
        elif not ((u.hostname==f'db.{PROJECT}.supabase.co' and u.username=='postgres') or (u.hostname and re.fullmatch(r'aws-[a-z0-9-]+\.pooler\.supabase\.com',u.hostname) and u.username==f'postgres.{PROJECT}')) or u.port not in {None,5432}:raise ValueError()
        if set(q)-{'sslmode','sslrootcert'}:raise ValueError()
        q['connect_timeout']=['15']
        if not local:
            q['sslmode']=['verify-full']
            bundle=os.environ.get('PGSSLROOTCERT','')
            if not bundle or not Path(bundle).is_file():raise Blocked('supabase_ca_bundle_missing')
            q['sslrootcert']=[bundle]
        q['options']=['-c default_transaction_read_only=on -c statement_timeout=20000' if readonly else '-c statement_timeout=120000 -c lock_timeout=15000']
        return urlunsplit((u.scheme,u.netloc,u.path,urlencode({k:v[0] for k,v in q.items()},quote_via=quote),''))
    except (ValueError,TypeError):raise Blocked('invalid_database_secret_or_project_host') from None

def error_code(detail):
    # Classify known failures without ever returning untrusted exception text.
    text=str(detail).lower()
    for fragment,code in (
        ('does not match host name','tls_certificate_hostname_mismatch'),
        ('hostname mismatch','tls_certificate_hostname_mismatch'),
        ('certificate has expired','tls_certificate_expired_or_not_yet_valid'),
        ('certificate is not yet valid','tls_certificate_expired_or_not_yet_valid'),
        ('unable to get local issuer certificate','tls_ca_untrusted'),
        ('self-signed certificate','tls_ca_untrusted'),
        ('certificate signed by unknown authority','tls_ca_untrusted'),
        ('certificate verify failed','tls_certificate_verification_failed'),
        ('password authentication failed','database_password_rejected'),
        ('read-only transaction','unexpected_write_during_readonly_check'),
        ('permission denied','database_privilege_denied'),
        ('timeout','database_timeout_recheck_before_retry'),
    ):
        if fragment in text:return code
    return 'connection_or_cli_error_details_withheld'

def cli(args,url,workdir,readonly=True):
    env={**os.environ,'PGOPTIONS':'-c default_transaction_read_only=on' if readonly else '', 'SUPABASE_TELEMETRY_DISABLED':'true'}
    env.pop('SUPABASE_ACCESS_TOKEN',None)
    # This pinned CLI reconstructs DSNs internally: enforce TLS through PG env as well.
    parsed=urlsplit(url);query=parse_qs(parsed.query)
    if readonly:
        query['options']=[query.get('options',[''])[0]+' -c default_transaction_read_only=on']
        url=urlunsplit((parsed.scheme,parsed.netloc,parsed.path,urlencode({k:v[0] for k,v in query.items()},quote_via=quote),''))
    env['PGSSLMODE']=query.get('sslmode',['verify-full'])[0]
    if query.get('sslrootcert'):env['PGSSLROOTCERT']=query['sslrootcert'][0]
    try:
        r=subprocess.run([os.environ.get('SUPABASE_CLI','supabase'),*args,'--db-url',url,'--workdir',str(workdir),'--yes'],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=240,check=False)
    except (OSError,subprocess.TimeoutExpired):raise Blocked('cli_unavailable_or_timeout_recheck_before_retry') from None
    # Never print CLI stderr: it can contain DSNs, passwords, SQL and database error detail.
    if r.returncode:raise Blocked('cli_failed_'+error_code(r.stderr.decode(errors='replace')))

def stage(path,plan,recover=False):
    directory=path/'supabase/migrations';directory.mkdir(parents=True)
    (path/'supabase/config.toml').write_text('project_id = "animevault-migrations"\n[db]\nmajor_version = 17\n[db.seed]\nenabled = false\n')
    for version,file in files().items():
        if version==BASE:
            # This already-deployed migration is structurally impossible to execute from this workflow.
            text="DO $$ BEGIN RAISE EXCEPTION 'Baseline 001 must NEVER be replayed'; END $$;\n"
        else:
            pieces=[]
            for node,sql in statements(file.read_text()):
                key=None
                if isinstance(node,ast.CreateStmt):key='table:'+node.relation.schemaname+'.'+node.relation.relname
                if isinstance(node,ast.CreateFunctionStmt):
                    key='function:public.community_sync_batch(p_batch uuid, p_items jsonb)'
                if recover and version==SYNC and key and key not in plan['autosync']['missing']:
                    if isinstance(node,ast.CreateStmt):continue
                    sql=re.sub(r'(?i)\bCREATE\s+FUNCTION\b','CREATE OR REPLACE FUNCTION',sql,count=1)
                pieces.append(sql)
            text='\n'.join(pieces)+'\n'
        (directory/file.name).write_text(text)

def run(db,url,mode,expected=None,local=False):
    plan=inspect(db)
    if expected and expected!=plan['plan_hash']:raise Blocked('database_changed_since_check_rerun_workflow')
    if mode!='check' and plan['blockers']:raise Blocked('schema_or_history_blocked_see_check_report')
    if mode=='apply' and plan['recovery_required']:raise Blocked('partial_autosync_choose_recover_after_review')
    with tempfile.TemporaryDirectory(prefix='av-migrations-') as tmp:
        work=Path(tmp);stage(work,plan,recover=mode=='recover')
        cli(['migration','list'],connection_url(url,True,local),work)
        if mode=='check':
            if not plan['blockers'] and not plan['history_to_adopt'] and not plan['recovery_required']:
                cli(['db','push','--dry-run'],connection_url(url,True,local),work)
            return plan
        # Session advisory lock serializes this workflow's rechecks/CLI commands, released on disconnect.
        if not db.execute('SELECT pg_try_advisory_lock(61967,411)').fetchone()[0]:raise Blocked('migration_runner_busy')
        try:
            again=inspect(db)
            if again['plan_hash']!=plan['plan_hash']:raise Blocked('database_changed_during_preflight')
            write_url=connection_url(url,False,local)
            for version in plan['history_to_adopt']:
                # Explicit, single-version applied-only repair. Never revert/truncate history.
                baseline_path=work/'supabase/migrations'/files()[BASE].name
                sentinel=baseline_path.read_text()
                try:
                    if version==BASE: baseline_path.write_text(files()[BASE].read_text())
                    cli(['migration','repair',version,'--status','applied'],write_url,work,False)
                finally: baseline_path.write_text(sentinel)
            if BASE not in history(db):raise Blocked('baseline_history_not_registered')
            if any(v not in history(db) for v in (SYNC,AUTO,REPAIR)):
                cli(['db','push','--dry-run'],connection_url(url,True,local),work)
                cli(['db','push'],write_url,work,False)
            after=inspect(db)
            if after['blockers'] or after['autosync']['state']!='complete' or after['zero_touch']['state']!='complete' or REPAIR not in after['history'] or after['history_to_adopt']:raise Blocked('postflight_mismatch_no_automatic_rollback')
            return after
        finally:db.execute('SELECT pg_advisory_unlock(61967,411)')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=['check','apply','recover']);p.add_argument('--expected-plan');args=p.parse_args()
    report={'status':'blocked'}
    safe=None
    trust=tempfile.TemporaryDirectory(prefix='av-supabase-tls-')
    previous_root=os.environ.get('PGSSLROOTCERT')
    try:
        if os.environ.get('GITHUB_REF')!='refs/heads/main' or os.environ.get('GITHUB_EVENT_NAME')!='workflow_dispatch':raise Blocked('trusted_main_dispatch_required')
        if args.mode!='check' and not args.expected_plan:raise Blocked('preflight_plan_required')
        if args.mode!='check' and os.environ.get('MIGRATION_CONFIRMATION')!=f'APPLY {PROJECT}':raise Blocked('explicit_confirmation_required')
        url=secret_url(os.environ.get('SUPABASE_DB_URL',''),os.environ.get('SUPABASE_DB_PASSWORD',''))
        os.environ['PGSSLROOTCERT']=prepare_trust(os.environ.get('SUPABASE_DB_CA_CERT',''),trust.name)
        safe=connection_url(url)
        with psycopg.connect(safe,autocommit=True) as db:
            report=inspect(db)
            report=run(db,url,args.mode,args.expected_plan)
            report['status']='blocked' if report['blockers'] else 'checked' if args.mode=='check' else 'completed'
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'],'a') as f:f.write('plan_hash='+report['plan_hash']+'\n')
        print(encoded(report))
    except Blocked as exc:report['status']='blocked';report['error']=str(exc);print('Stopped: '+str(exc))
    except Exception as exc:
        report['status']='blocked';report['error']=error_code(exc)
        if report['error']=='tls_certificate_verification_failed' and safe:report['error']=tls_probe(safe)
        print('Stopped: '+report['error'])
    finally:
        trust.cleanup()
        if previous_root is None:os.environ.pop('PGSSLROOTCERT',None)
        else:os.environ['PGSSLROOTCERT']=previous_root
        if report.get('error','').startswith(('supabase_ca_','tls_')):
            report['help']='Use the full CA PEM from Supabase Database Settings > SSL Configuration in SUPABASE_DB_CA_CERT; confirm the Session pooler URI in Connect. Keep verify-full. Do not paste credentials into logs or issues.'
        Path('migration-report.json').write_text(encoded(report)+'\n')
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as f:f.write('```json\n'+encoded(report)+'\n```\n')
    if report.get('status')=='blocked' or report.get('error'):raise SystemExit(1)

if __name__=='__main__':main()
