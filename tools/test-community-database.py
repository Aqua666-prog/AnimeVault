#!/usr/bin/env python3
"""Integration tests ONLY for a disposable loopback PostgreSQL instance, never for Supabase."""
import concurrent.futures
import os
from pathlib import Path
import unittest
from urllib.parse import urlparse
from uuid import uuid4

import psycopg

DSN = os.environ.get("COMMUNITY_TEST_DATABASE_URL", "")
if urlparse(DSN).hostname not in {"localhost", "127.0.0.1", "::1"}:
    raise SystemExit("A disposable loopback COMMUNITY_TEST_DATABASE_URL is required")
ROOT = Path(__file__).resolve().parents[1]


def connect(role=None, user=None):
    connection = psycopg.connect(DSN, autocommit=True)
    if role:
        assert role in {"anon", "authenticated"}
        connection.execute("SET ROLE " + role)
    connection.execute("SELECT set_config('request.jwt.claim.sub', %s, false)", (str(user) if user else "",))
    return connection


def bootstrap():
    with connect() as db:
        # A Supabase Auth shim only for this fresh disposable database.
        db.execute("CREATE ROLE anon NOLOGIN; CREATE ROLE authenticated NOLOGIN; CREATE SCHEMA auth; CREATE TABLE auth.users(id uuid PRIMARY KEY)")
        db.execute("CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$")
        db.execute((ROOT / "supabase/migrations/202611010001_community.sql").read_text())


class CommunityDatabaseTest(unittest.TestCase):
    def setUp(self):
        self.owner = connect()
        self.addCleanup(self.owner.close)
        self.user, self.other, self.mod = uuid4(), uuid4(), uuid4()
        self.anime, self.season, self.episode, self.special = uuid4(), uuid4(), uuid4(), uuid4()
        self.anilist = uuid4().int % 1_000_000_000 + 1
        self.mal = uuid4().int % 1_000_000_000 + 1
        self.external = str(uuid4())
        for uid in [self.user,self.other,self.mod]:
            self.owner.execute("INSERT INTO auth.users VALUES (%s)",(uid,))
            self.owner.execute("INSERT INTO public.community_profiles(user_id,username,display_name) VALUES (%s,%s,'Test')",(uid,"u"+uid.hex[:20]))
        self.owner.execute("INSERT INTO public.community_private_roles(user_id,role) VALUES (%s,'admin')",(self.mod,))
        self.owner.execute("INSERT INTO public.community_anime(id,anilist_id,mal_id,canonical_title) VALUES (%s,%s,%s,'Same title'),(%s,NULL,NULL,'Same title')",(self.anime,self.anilist,self.mal,self.season))
        self.owner.execute("INSERT INTO public.community_anime_aliases(anime_id,provider,external_id,verified) VALUES (%s,'provider-a',%s,true),(%s,'provider-b',%s,true)",(self.anime,self.external,self.anime,self.external))
        self.owner.execute("INSERT INTO public.community_episodes(id,anime_id,episode_key,episode_number,episode_type) VALUES (%s,%s,'regular:12',12,'regular'),(%s,%s,'special:12.5',NULL,'fractional')",(self.episode,self.anime,self.special,self.anime))
        self.owner.execute("INSERT INTO public.community_episode_aliases VALUES ('provider-a',%s,'episode12',%s,true),('provider-a',%s,'episode12.5',%s,true)",(self.external,self.episode,self.external,self.special))
        self.db = connect("authenticated", self.user)
        self.addCleanup(self.db.close)

    def rpc(self, db, name, **params):
        keys = list(params)
        placeholders = ",".join(key + " => %s" for key in keys)
        return db.execute("SELECT public." + name + "(" + placeholders + ")",[params[k] for k in keys]).fetchone()[0]

    def create(self, db=None, body="text", parent=None, episode=None, request=None):
        return self.rpc(db or self.db,"community_create_comment",p_anime=self.anime,p_episode=episode,p_parent=parent,p_body=body,p_spoiler=False,p_request=request or uuid4())

    def page(self, db=None, **kwargs):
        return self.rpc(db or self.db,"community_list_comments",p_anime=self.anime,**kwargs)

    def error(self, code, operation):
        with self.assertRaises(psycopg.Error) as caught:
            operation()
        self.assertEqual(code,caught.exception.sqlstate,str(caught.exception))

    def test_filtered_total_and_stable_reply_order(self):
        root=self.create(body="root")
        reply=self.create(body="reply",parent=root)
        self.create(body="episode",episode=self.episode)
        self.assertEqual(1,self.page()["total"])
        self.assertEqual(1,self.page(p_parent=root)["total"])
        self.assertEqual(0,self.page(p_parent=reply)["total"])
        self.assertEqual(1,self.page(p_episode=self.episode)["total"])
        second=self.create(body="second",parent=root)
        for sort in ["new","old","popular"]:
            self.assertEqual([str(reply),str(second)],[c["id"] for c in self.page(p_parent=root,p_sort=sort)["comments"]])

    def test_invalid_targets_and_null_mutation_arguments(self):
        self.error("PT400",lambda:self.rpc(self.db,"community_create_comment",p_anime=uuid4(),p_episode=None,p_parent=None,p_body="text",p_spoiler=False,p_request=uuid4()))
        self.error("PT400",lambda:self.rpc(self.db,"community_create_comment",p_anime=self.season,p_episode=self.episode,p_parent=None,p_body="text",p_spoiler=False,p_request=uuid4()))
        comment=self.create()
        self.error("PT400",lambda:self.rpc(self.db,"community_set_like",p_id=comment,p_liked=None))
        self.error("PT400",lambda:self.rpc(self.db,"community_report",p_id=comment,p_reason=None))
        with connect("authenticated",self.mod) as mod:
            self.error("PT400",lambda:self.rpc(mod,"community_moderate",p_id=comment,p_action=None,p_reason="test"))
        with connect("authenticated",uuid4()) as deleted:
            self.error("PT401",lambda:self.create(deleted))

    def test_auth_deletion_clears_report_without_profile(self):
        comment=self.create()
        self.owner.execute("DELETE FROM public.community_profiles WHERE user_id=%s",(self.other,))
        with connect("authenticated",self.other) as other:
            self.rpc(other,"community_report",p_id=comment,p_reason="other",p_description="personal details")
            self.error("42501",lambda:other.execute("SELECT community_internal.erase_account(%s)",(self.user,)))
        self.owner.execute("DELETE FROM auth.users WHERE id=%s",(self.other,))
        row=self.owner.execute("SELECT reporter_id,description FROM public.community_comment_reports WHERE comment_id=%s",(comment,)).fetchone()
        self.assertEqual((None,None),row)

    def test_guest_read_but_not_write(self):
        self.create()
        with connect("anon") as guest:
            self.assertEqual(1,len(self.page(guest)["comments"]))
            self.error("42501",lambda:self.create(guest))
            for table in ["community_comments","community_comment_likes","community_comment_reports","community_bans","community_private_roles","community_moderation_log"]:
                self.error("42501",lambda:guest.execute("SELECT * FROM public."+table))

    def test_cannot_impersonate_edit_or_escalate(self):
        comment = self.create()
        with connect("authenticated",self.other) as other:
            self.error("PT403",lambda:self.rpc(other,"community_edit_comment",p_id=comment,p_body="stolen",p_spoiler=False))
            self.error("PT403",lambda:self.rpc(other,"community_delete_comment",p_id=comment))
            self.error("PT403",lambda:self.rpc(other,"community_moderate",p_id=comment,p_action="hide",p_reason="fake admin"))
            self.error("42501",lambda:other.execute("UPDATE public.community_private_roles SET role='admin'"))
            self.error("42501",lambda:other.execute("INSERT INTO public.community_comments(anime_id,author_id,body,client_request_id) VALUES (%s,%s,'forged',%s)",(self.anime,self.user,uuid4())))

    def test_replies_context_depth_and_soft_delete(self):
        root=self.create()
        child=self.create(body="reply",parent=root)
        grandchild=self.create(body="second reply level",parent=child)
        self.error("PT400",lambda:self.create(body="third reply level",parent=grandchild))
        self.error("PT400",lambda:self.create(body="wrong episode",parent=root,episode=self.episode))
        self.rpc(self.db,"community_delete_comment",p_id=root)
        self.assertEqual("Комментарий удалён",self.page()["comments"][0]["body"])
        self.assertEqual(str(child),self.page(p_parent=root)["comments"][0]["id"])
        self.assertEqual(str(grandchild),self.page(p_parent=child)["comments"][0]["id"])

    def test_idempotency_likes_edit_and_normalization(self):
        request=uuid4()
        comment=self.create(body="  text\r\nnext  ",request=request)
        self.assertEqual(comment,self.create(body="duplicate retry",request=request))
        self.assertEqual(1,self.page()["total"])
        self.assertEqual("text\nnext",self.page()["comments"][0]["body"])
        self.rpc(self.db,"community_set_like",p_id=comment,p_liked=True)
        self.rpc(self.db,"community_set_like",p_id=comment,p_liked=True)
        self.assertEqual(1,self.page()["comments"][0]["likes"])
        with connect("authenticated",self.other) as other:
            self.rpc(other,"community_set_like",p_id=comment,p_liked=False)
        self.assertEqual(1,self.page()["comments"][0]["likes"])
        self.rpc(self.db,"community_set_like",p_id=comment,p_liked=False)
        self.rpc(self.db,"community_edit_comment",p_id=comment,p_body="edited",p_spoiler=True)
        self.assertEqual("edited",self.page()["comments"][0]["body"])
        self.assertTrue(self.page()["comments"][0]["contains_spoiler"])
        for body in [" \n\t", "x"*2001]:
            self.error("PT400",lambda:self.create(body=body))

    def test_reports_moderation_bans_and_redaction(self):
        comment=self.create(body="private hidden text")
        for _ in range(2):
            self.rpc(self.db,"community_report",p_id=comment,p_reason="spoiler",p_description="test")
        with connect("authenticated",self.mod) as mod:
            self.assertEqual(1,len([r for r in self.rpc(mod,"community_review_reports") if r["comment_id"]==str(comment)]))
            self.rpc(mod,"community_moderate",p_id=comment,p_action="hide",p_reason="test")
            self.assertNotIn("private hidden text",str(self.page()))
            self.rpc(mod,"community_moderate",p_id=comment,p_action="restore",p_reason="appeal")
            self.rpc(mod,"community_ban",p_user=self.user,p_reason="spam")
            self.error("PT403",lambda:self.create(body="banned"))
            self.error("PT403",lambda:self.rpc(self.db,"community_set_like",p_id=comment,p_liked=True))
            self.rpc(mod,"community_ban",p_user=self.user,p_reason="appeal",p_revoke=True)
        self.create(body="unbanned")
        self.error("PT403",lambda:self.rpc(self.db,"community_review_reports"))

    def test_identity_requires_verified_links(self):
        def resolve(**kwargs): return self.rpc(self.db,"community_resolve",**kwargs)
        a=resolve(p_provider="provider-a",p_external=self.external)
        b=resolve(p_provider="provider-b",p_external=self.external)
        self.assertEqual(a["anime_id"],b["anime_id"])
        self.assertIsNone(resolve(p_provider="unknown",p_external=self.external,p_anilist=self.anilist))
        self.assertIsNone(resolve(p_anilist=self.anilist,p_mal=self.mal+1))
        self.assertIsNone(resolve(p_anilist=self.anilist+1,p_mal=self.mal))
        self.assertEqual(str(self.anime),resolve(p_mal=self.mal)["anime_id"])
        self.assertNotEqual(str(self.season),a["anime_id"])
        self.assertEqual(str(self.special),resolve(p_provider="provider-a",p_external=self.external,p_episode_external="episode12.5")["episode_id"])
        self.assertIsNone(resolve(p_provider="provider-b",p_external=self.external,p_episode_external="episode12.5"))

    def test_pagination_sort_and_request_boundaries(self):
        for i in range(25):
            self.owner.execute("INSERT INTO public.community_comments(anime_id,author_id,body,client_request_id,created_at) VALUES (%s,%s,%s,%s, now() - %s * interval '1 minute')",(self.anime,self.user,str(i),uuid4(),i+2))
        new=self.page(p_sort="new",p_limit=10)
        old=self.page(p_sort="old",p_limit=10)
        self.assertNotEqual(new["comments"][0]["id"],old["comments"][0]["id"])
        next_page=self.page(p_sort="new",p_limit=10,p_offset=10)
        self.assertFalse({c["id"] for c in new["comments"]} & {c["id"] for c in next_page["comments"]})
        for params in [dict(p_limit=100000),dict(p_offset=-1),dict(p_sort="injection"),dict(p_limit=None),dict(p_offset=None),dict(p_sort=None)]:
            self.error("PT400",lambda:self.page(**params))
        popular_id=old["comments"][0]["id"]
        self.rpc(self.db,"community_set_like",p_id=popular_id,p_liked=True)
        self.assertEqual(popular_id,self.page(p_sort="popular")["comments"][0]["id"])

    def test_profile_username_uniqueness_and_server_identity(self):
        profile=self.rpc(self.db,"community_save_profile",p_username="CaseTest"+self.user.hex[:8],p_display_name="User")
        self.assertEqual(str(self.user),profile["user_id"])
        with connect("authenticated",self.other) as other:
            self.error("23505",lambda:self.rpc(other,"community_save_profile",p_username="casetest"+self.user.hex[:8],p_display_name="Other"))
        for username in ["admin","deleted_user","xy","invalid name","1user"]:
            self.error("23514",lambda:self.rpc(self.db,"community_save_profile",p_username=username,p_display_name="User"))

    def test_atomic_rate_limits_under_concurrency(self):
        def send(index):
            with connect("authenticated",self.user) as db:
                try: self.create(db,body="concurrent-"+str(index)); return "ok"
                except psycopg.Error as exc: return exc.sqlstate
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results=list(pool.map(send,range(12)))
        self.assertEqual(5,results.count("ok"),results)
        self.assertEqual(7,results.count("PT429"),results)

    def test_hour_limit_and_duplicate_body(self):
        self.create(body="duplicate")
        self.error("PT429",lambda:self.create(body="duplicate"))
        for i in range(29):
            self.owner.execute("INSERT INTO public.community_comments(anime_id,author_id,body,client_request_id,created_at) VALUES (%s,%s,'history',%s,now()-interval '2 minutes')",(self.anime,self.user,uuid4()))
        self.error("PT429",lambda:self.create(body="hour limit"))

    def test_account_erasure_preserves_others_replies(self):
        root=self.create(body="personal information")
        with connect("authenticated",self.other) as other:
            reply=self.create(other,body="other reply",parent=root)
        self.owner.execute("SELECT community_internal.erase_account(%s)",(self.user,))
        page=self.page()["comments"][0]
        self.assertIsNone(page["author_id"])
        self.assertNotIn("personal information",str(page))
        self.assertEqual(str(reply),self.page(p_parent=root)["comments"][0]["id"])

    def test_dashboard_auth_delete_also_erases_personal_text(self):
        self.create(body="personal dashboard text")
        self.owner.execute("DELETE FROM auth.users WHERE id=%s",(self.user,))
        page=self.page()["comments"][0]
        self.assertIsNone(page["author_id"])
        self.assertEqual("Комментарий удалён",page["body"])

    def test_grants_rls_and_anonymous_rpc_inventory(self):
        tables=self.owner.execute("SELECT relname,relrowsecurity FROM pg_class WHERE relnamespace='public'::regnamespace AND relname LIKE 'community_%' AND relkind='r'").fetchall()
        self.assertTrue(all(enabled for _,enabled in tables))
        functions=self.owner.execute("SELECT oid::regprocedure::text,proname,has_function_privilege('anon',oid,'execute') FROM pg_proc WHERE pronamespace='public'::regnamespace AND proname LIKE 'community_%'").fetchall()
        self.assertEqual({"community_resolve","community_list_comments","community_profile_stats"},{name for _,name,allowed in functions if allowed})
        for table,_ in tables:
            for privilege in ["INSERT","UPDATE","DELETE"]:
                self.assertFalse(self.owner.execute("SELECT has_table_privilege('authenticated',%s,%s)",("public."+table,privilege)).fetchone()[0])

if __name__ == "__main__":
    bootstrap()
    unittest.main()
