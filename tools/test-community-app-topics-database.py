#!/usr/bin/env python3
"""Contract tests for app-owned community RPC. Disposable local PostgreSQL ONLY."""
import concurrent.futures
import os
from pathlib import Path
import unittest
from urllib.parse import urlsplit
from uuid import uuid4

import psycopg

DSN = os.environ.get('COMMUNITY_TEST_DATABASE_URL', '')
if urlsplit(DSN).hostname not in {'127.0.0.1', 'localhost', '::1'}:
    raise SystemExit('Refusing to run outside disposable loopback PostgreSQL')
ROOT = Path(__file__).resolve().parents[1]
SQL = ROOT / 'supabase/migrations/202611020003_community_app_topics.sql'


def connect(role=None, user=None):
    db = psycopg.connect(DSN, autocommit=True)
    if role:
        assert role in ('anon', 'authenticated')
        db.execute('SET ROLE ' + role)
    db.execute("SELECT set_config('request.jwt.claim.sub',%s,false)", (str(user) if user else '',))
    return db


def bootstrap():
    with connect() as db:
        db.execute('CREATE ROLE anon NOLOGIN; CREATE ROLE authenticated NOLOGIN; CREATE SCHEMA auth; CREATE TABLE auth.users(id uuid PRIMARY KEY)')
        db.execute("CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$")
        db.execute((ROOT / 'supabase/migrations/202611010001_community.sql').read_text())
        db.execute(SQL.read_text())


class AppTopicDatabaseTests(unittest.TestCase):
    def setUp(self):
        self.admin = connect()
        self.addCleanup(self.admin.close)
        self.member = uuid4()
        self.admin.execute('INSERT INTO auth.users(id) VALUES(%s)', (self.member,))
        self.guest = connect('anon')
        self.user = connect('authenticated', self.member)
        self.addCleanup(self.guest.close)
        self.addCleanup(self.user.close)
        self.addCleanup(lambda: self.admin.execute('DELETE FROM auth.users WHERE id=%s', (self.member,)))

    def open(self, db, key='av1:mal:61967', title='AnimeVault sample', ep=None, number=None):
        return db.execute('SELECT public.community_app_open(%s,%s,%s,%s)',
                          (key,title,ep,number)).fetchone()[0]

    def count(self, table):
        return self.admin.execute('SELECT count(*) FROM public.'+table).fetchone()[0]

    def test_guest_read_does_not_mutate_catalog_or_comments(self):
        before=(self.count('community_anime'),self.count('community_episodes'),self.count('community_app_topics'))
        context=self.open(self.guest)
        self.assertIsNotNone(context['anime_id'])
        self.assertEqual(before,(self.count('community_anime'),self.count('community_episodes'),self.count('community_app_topics')))
        self.assertEqual(0,self.guest.execute('SELECT (public.community_list_comments(%s,NULL,NULL,%s,0,20)->>\'total\')::integer',
                                              (context['anime_id'],'new')).fetchone()[0])

    def test_authenticated_first_visit_materializes_topic_and_guest_reuses(self):
        phantom=self.open(self.guest,key='av1:mal:654321')
        actual=self.open(self.user,key='av1:mal:654321')
        self.assertEqual(phantom,actual)
        self.assertEqual(actual,self.open(self.guest,key='av1:mal:654321'))
        self.assertEqual(1,self.admin.execute("SELECT count(*) FROM public.community_app_topics WHERE topic_key='av1:mal:654321'").fetchone()[0])
        self.assertEqual(1,self.admin.execute('SELECT count(*) FROM public.community_anime WHERE id=%s',(actual['anime_id'],)).fetchone()[0])

    def test_existing_canonical_is_preserved_without_alias_changes(self):
        original=uuid4()
        self.admin.execute("INSERT INTO public.community_anime(id,anilist_id,mal_id,canonical_title) VALUES(%s,195604,61967,'Verified name')",(original,))
        context=self.open(self.user,key='av1:mal:61967',title='Untrusted client title')
        self.assertEqual(str(original),context['anime_id'])
        self.assertEqual('Verified name',self.admin.execute('SELECT canonical_title FROM public.community_anime WHERE id=%s',(original,)).fetchone()[0])
        self.assertEqual(0,self.count('community_anime_aliases'))

    def test_source_independent_episode_threads_can_receive_real_comments(self):
        self.admin.execute("INSERT INTO public.community_profiles(user_id,username,display_name) VALUES(%s,%s,'Member')",
                           (self.member,'av'+self.member.hex[:16]))
        guest=self.open(self.guest,key='av1:title:'+'a'*64,ep='regular:12',number=12)
        thread=self.open(self.user,key='av1:title:'+'a'*64,ep='regular:12',number=12)
        self.assertEqual(guest,thread)
        self.assertNotEqual(thread['anime_id'],thread['episode_id'])
        comment=self.user.execute('SELECT public.community_create_comment(%s,%s,NULL,%s,false,%s)',
                                  (thread['anime_id'],thread['episode_id'],'Первый комментарий',uuid4())).fetchone()[0]
        self.assertTrue(comment)
        page=self.guest.execute("SELECT public.community_list_comments(%s,%s,NULL,'new',0,20)",
                                (thread['anime_id'],thread['episode_id'])).fetchone()[0]
        self.assertEqual(1,page['total'])
        self.assertEqual(1,self.count('community_episodes'))
        self.assertEqual(thread,self.open(self.user,key='av1:title:'+'a'*64,ep='regular:12',number=12))

    def test_invalid_identity_and_episode_rejected_without_writes(self):
        for key,ep,number in [
            ('kodik:1',None,None),
            ('av1:mal:0001',None,None),
            ('av1:title:'+'z'*64,None,None),
            ('av1:mal:5','regular:../../bad',None),
            ('av1:mal:5',None,1),
            ('av1:mal:5','regular:1',10001),
        ]:
            with self.subTest(key=key,ep=ep):
                with self.assertRaises(psycopg.Error) as err:self.open(self.user,key,ep=ep,number=number)
                self.assertEqual('PT400',err.exception.sqlstate)
        self.assertEqual(0,self.count('community_app_topics'))

    def test_authenticated_user_has_no_direct_table_mutations(self):
        for db in (self.user,self.guest):
            with self.assertRaises(psycopg.Error):db.execute("INSERT INTO public.community_app_topics(topic_key,anime_id) VALUES('av1:mal:12',%s)",(uuid4(),))
            with self.assertRaises(psycopg.Error):db.execute('SELECT * FROM public.community_app_topics')
        self.assertTrue(self.admin.execute("SELECT relrowsecurity FROM pg_class WHERE oid='public.community_app_topics'::regclass").fetchone()[0])
        self.assertTrue(self.admin.execute("SELECT prosecdef FROM pg_proc WHERE oid='public.community_app_open(text,text,text,integer)'::regprocedure").fetchone()[0])

    def test_per_account_quota_and_existing_thread_still_readable(self):
        for n in range(1,21):self.open(self.user,key=f'av1:mal:{800000+n}')
        with self.assertRaises(psycopg.Error) as err:self.open(self.user,key='av1:mal:900000')
        self.assertEqual('PT429',err.exception.sqlstate)
        self.assertEqual(self.open(self.user,key='av1:mal:800001'),self.open(self.guest,key='av1:mal:800001'))
        self.assertEqual(20,self.count('community_app_topics'))

    def test_banned_account_cannot_create_but_guest_can_read(self):
        self.admin.execute("INSERT INTO public.community_bans(user_id,reason) VALUES(%s,'spam')",(self.member,))
        with self.assertRaises(psycopg.Error) as err:self.open(self.user,key='av1:mal:123')
        self.assertEqual('PT403',err.exception.sqlstate)
        self.assertTrue(self.open(self.guest,key='av1:mal:123')['anime_id'])
        self.assertEqual(0,self.count('community_app_topics'))


if __name__=='__main__':
    bootstrap()
    unittest.main()
