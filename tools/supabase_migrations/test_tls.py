"""Real libpq TLS against a disposable loopback TLS endpoint. No SQL/authentication."""
import importlib.util
import json
import os
from pathlib import Path
import socket
import shutil
import ssl
import struct
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

import psycopg

spec=importlib.util.spec_from_file_location('manage_tls',Path(__file__).with_name('manage.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
HOST='aws-0-eu-north-1.pooler.supabase.com'

class Endpoint:
    def __init__(self,cert,key):
        self.listener=socket.socket();self.listener.bind(('127.0.0.1',0));self.listener.listen(1)
        self.port=self.listener.getsockname()[1];self.startup=b'';self.handshake=False
        self.context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);self.context.load_cert_chain(cert,key)
        self.thread=threading.Thread(target=self.serve,daemon=True);self.thread.start()

    def serve(self):
        try:
            self.listener.settimeout(5)
            with self.listener.accept()[0] as raw:
                raw.settimeout(5)
                request=b''
                while len(request)<8:
                    part=raw.recv(8-len(request))
                    if not part:return
                    request+=part
                if request!=struct.pack('!II',8,80877103):return
                raw.sendall(b'S')
                with self.context.wrap_socket(raw,server_side=True) as secure:
                    self.handshake=True
                    self.startup=secure.recv(8192)
                    # Fixed error after a successful TLS handshake, before auth or any query.
                    payload=b'SFATAL\0C08006\0Mtls_fixture_verified\0\0'
                    secure.sendall(b'E'+struct.pack('!I',len(payload)+4)+payload)
        except (OSError,ssl.SSLError):pass
        finally:self.listener.close()

    def close(self):
        self.thread.join(6)
        if self.thread.is_alive():raise AssertionError('TLS fixture did not exit')

class TlsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory();cls.root=Path(cls.temp.name)
        def openssl(*args):
            subprocess.run(['openssl',*map(str,args)],check=True,capture_output=True,timeout=15)
        for name in ('ca','other'):
            openssl('req','-x509','-newkey','rsa:2048','-nodes','-days','2','-subj','/CN=Local TLS Fixture '+name,
                    '-addext','basicConstraints=critical,CA:TRUE','-addext','keyUsage=critical,keyCertSign,cRLSign',
                    '-keyout',cls.root/(name+'.key'),'-out',cls.root/(name+'.pem'))
        openssl('req','-newkey','rsa:2048','-nodes','-subj','/CN='+HOST,'-keyout',cls.root/'leaf.key','-out',cls.root/'leaf.csr')
        (cls.root/'extensions').write_text('basicConstraints=critical,CA:FALSE\nsubjectAltName=DNS:'+HOST+',IP:127.0.0.1\nextendedKeyUsage=serverAuth\n')
        openssl('x509','-req','-in',cls.root/'leaf.csr','-CA',cls.root/'ca.pem','-CAkey',cls.root/'ca.key',
                '-CAcreateserial','-days','1','-extfile',cls.root/'extensions','-out',cls.root/'leaf.pem')
        (cls.root/'index').write_text('');(cls.root/'serial').write_text('0100\n')
        (cls.root/'ca.conf').write_text(f"""[ca]
        default_ca=issuer
        [issuer]
        database={cls.root}/index
        serial={cls.root}/serial
        new_certs_dir={cls.root}
        private_key={cls.root}/ca.key
        certificate={cls.root}/ca.pem
        default_md=sha256
        policy=names
        x509_extensions=leaf
        [names]
        commonName=supplied
        [leaf]
        basicConstraints=critical,CA:FALSE
        subjectAltName=DNS:{HOST}
        extendedKeyUsage=serverAuth
        """)
        openssl('ca','-batch','-config',cls.root/'ca.conf','-in',cls.root/'leaf.csr','-out',cls.root/'expired.pem',
                '-startdate','20000101000000Z','-enddate','20010101000000Z','-notext')

    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()

    def trust(self,name='ca'):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        return m.prepare_trust((self.root/(name+'.pem')).read_text(),temp.name)

    def connect(self,bundle,host=HOST,cert='leaf'):
        server=Endpoint(self.root/(cert+'.pem'),self.root/'leaf.key');self.addCleanup(server.close)
        # Keep host for hostname verification, overriding ONLY TCP routing to loopback in tests.
        with self.assertRaises(psycopg.OperationalError) as error:
            psycopg.connect(host=host,hostaddr='127.0.0.1',port=server.port,dbname='postgres',user='fixture',
                            sslmode='verify-full',sslrootcert=bundle,connect_timeout=3,
                            options='-c default_transaction_read_only=on')
        server.close()
        return str(error.exception),server

    def test_supabase_ca_is_added_to_system_roots_and_libpq_verifies(self):
        bundle=self.trust()
        context=ssl.create_default_context(cafile=bundle)
        self.assertGreater(len(context.get_ca_certs()),1)
        self.assertEqual(0o600,Path(bundle).stat().st_mode&0o777)
        message,server=self.connect(bundle)
        self.assertIn('tls_fixture_verified',message);self.assertTrue(server.handshake)
        self.assertIn(b'default_transaction_read_only=on',server.startup)
        self.assertNotIn(b'password',server.startup)

    def test_wrong_ca_rejected(self):
        message,server=self.connect(self.trust('other'))
        self.assertIn('certificate verify failed',message.lower());self.assertFalse(server.handshake)

    def test_hostname_mismatch_is_not_bypassed(self):
        message,server=self.connect(self.trust(),host='another.pooler.supabase.com')
        self.assertEqual('tls_certificate_hostname_mismatch',m.error_code(message));self.assertEqual(b'',server.startup)

    def test_expired_leaf_is_not_bypassed(self):
        message,server=self.connect(self.trust(),cert='expired')
        self.assertNotIn('tls_fixture_verified',message);self.assertFalse(server.handshake)

    def test_missing_malformed_and_leaf_ca_are_actionable(self):
        for pem,code in (('', 'supabase_ca_missing'),('not PEM','supabase_ca_invalid_pem'),
                         ((self.root/'leaf.pem').read_text(),'supabase_ca_must_be_ca_certificate'),
                         ((self.root/'ca.key').read_text(),'supabase_ca_invalid_pem')):
            with self.assertRaisesRegex(m.Blocked,code):m.prepare_trust(pem,self.root)

    def test_ca_validity_checked_before_connecting(self):
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.load_verify_locations(cadata=(self.root/'ca.pem').read_text())
        expires=ssl.cert_time_to_seconds(context.get_ca_certs()[0]['notAfter'])
        with patch.object(m.time,'time',return_value=expires+1):
            with self.assertRaisesRegex(m.Blocked,'supabase_ca_expired'):self.trust()

    def test_production_url_requires_prepared_ca_and_forces_verify_full(self):
        url=f'postgresql://postgres.{m.PROJECT}:fixture@{HOST}:5432/postgres?sslmode=disable'
        with patch.dict(os.environ,{'PGSSLROOTCERT':''}):
            with self.assertRaisesRegex(m.Blocked,'supabase_ca_bundle_missing'):m.connection_url(url)
        bundle=self.trust()
        with patch.dict(os.environ,{'PGSSLROOTCERT':bundle}):
            parsed=m.parse_qs(m.urlsplit(m.connection_url(url)).query)
            self.assertEqual(['verify-full'],parsed['sslmode']);self.assertEqual([bundle],parsed['sslrootcert'])
            self.assertIn('default_transaction_read_only=on',parsed['options'][0])

    def test_cli_uses_same_bundle_without_logging_raw_output(self):
        bundle=self.trust()
        with patch.dict(os.environ,{'PGSSLROOTCERT':bundle}):
            url=m.connection_url(f'postgresql://postgres.{m.PROJECT}:PRIVATE@{HOST}/postgres')
            with patch.object(m.subprocess,'run',return_value=type('Result',(),{'returncode':1,'stderr':b'PRIVATE certificate verify failed'})()) as run:
                with self.assertRaises(m.Blocked) as error:m.cli(['migration','list'],url,self.root)
                self.assertNotIn('PRIVATE',str(error.exception))
                env=run.call_args.kwargs['env']
                self.assertEqual(bundle,env['PGSSLROOTCERT']);self.assertEqual('verify-full',env['PGSSLMODE'])
                self.assertIn('default_transaction_read_only=on',env['PGOPTIONS'])

    @unittest.skipUnless(shutil.which(os.environ.get('SUPABASE_CLI','supabase')),'Pinned CLI not installed')
    def test_real_cli_verifies_the_same_ca_bundle(self):
        bundle=self.trust()
        server=Endpoint(self.root/'leaf.pem',self.root/'leaf.key');self.addCleanup(server.close)
        with tempfile.TemporaryDirectory() as work:
            m.stage(Path(work),{'autosync':{'missing':[]}})
            url=f'postgresql://postgres:fixture@127.0.0.1:{server.port}/postgres?sslmode=verify-full'
            with patch.dict(os.environ,{'PGSSLROOTCERT':bundle}):
                # Only this loopback fixture bypasses production URL validation; TLS stays verify-full.
                query=m.parse_qs(m.urlsplit(url).query);query['sslrootcert']=[bundle]
                parsed=m.urlsplit(url)
                url=m.urlunsplit((parsed.scheme,parsed.netloc,parsed.path,m.urlencode({k:v[0] for k,v in query.items()}),''))
                with self.assertRaises(m.Blocked):m.cli(['migration','list'],url,Path(work))
        server.close();self.assertTrue(server.handshake)
        self.assertIn(b'default_transaction_read_only=on',server.startup)

    def test_real_diagnostic_sends_only_sslrequest_not_credentials(self):
        bundle=self.trust('other')
        server=Endpoint(self.root/'leaf.pem',self.root/'leaf.key');self.addCleanup(server.close)
        real_connect=socket.create_connection
        url=f'postgresql://postgres:PRIVATE@{HOST}/postgres?sslrootcert={bundle}'
        with patch.object(m.socket,'create_connection',side_effect=lambda address,timeout:real_connect(('127.0.0.1',server.port),timeout)):
            self.assertEqual('tls_ca_untrusted',m.tls_probe(url))
        server.close();self.assertEqual(b'',server.startup)

    def test_missing_ca_stops_main_before_any_database_connection(self):
        with tempfile.TemporaryDirectory() as work,patch.dict(os.environ,{'GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'workflow_dispatch',
             'SUPABASE_DB_URL':f'postgresql://postgres:PRIVATE@db.{m.PROJECT}.supabase.co/postgres'},clear=True),patch('sys.argv',['manage.py','check']),patch.object(m.psycopg,'connect') as connect:
            previous=os.getcwd();os.chdir(work)
            try:
                with self.assertRaises(SystemExit):m.main()
                report=Path('migration-report.json').read_text()
                self.assertEqual('supabase_ca_missing_download_from_database_settings',json.loads(report)['error'])
                self.assertNotIn('PRIVATE',report);connect.assert_not_called()
            finally:os.chdir(previous)

    def test_main_sanitizes_tls_error_and_deletes_bundle(self):
        env={'GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'workflow_dispatch',
             'SUPABASE_DB_URL':f'postgresql://postgres:PRIVATE@db.{m.PROJECT}.supabase.co/postgres',
             'SUPABASE_DB_CA_CERT':(self.root/'ca.pem').read_text(),'PGSSLROOTCERT':'previous-root'}
        with tempfile.TemporaryDirectory() as work,patch.dict(os.environ,env,clear=True),patch('sys.argv',['manage.py','check']),patch.object(m.psycopg,'connect',side_effect=psycopg.OperationalError('PRIVATE certificate verify failed')) as connect,patch.object(m,'tls_probe',return_value='tls_ca_untrusted'):
            previous=os.getcwd();os.chdir(work)
            try:
                with self.assertRaises(SystemExit):m.main()
                report=Path('migration-report.json').read_text()
                self.assertEqual('tls_ca_untrusted',json.loads(report)['error']);self.assertNotIn('PRIVATE',report)
                args=m.parse_qs(m.urlsplit(connect.call_args.args[0]).query)
                self.assertEqual(['verify-full'],args['sslmode'])
                self.assertIn('default_transaction_read_only=on',args['options'][0])
                self.assertFalse(Path(args['sslrootcert'][0]).exists())
                self.assertEqual('previous-root',os.environ['PGSSLROOTCERT'])
            finally:os.chdir(previous)

    def test_diagnostic_maps_openssl_codes_without_auth_or_error_text(self):
        for verify_code,expected in ((20,'tls_ca_untrusted'),(10,'tls_certificate_expired_or_not_yet_valid'),(62,'tls_certificate_hostname_mismatch')):
            error=ssl.SSLCertVerificationError('PRIVATE');error.verify_code=verify_code
            bundle=self.trust()
            url=f'postgresql://postgres:PRIVATE@{HOST}/postgres?sslrootcert={bundle}'
            with patch.object(m.socket,'create_connection',side_effect=error):self.assertEqual(expected,m.tls_probe(url))
