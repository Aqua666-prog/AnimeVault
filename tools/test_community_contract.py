"""Cheap source invariants. These checks are not database/Android runtime verification."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SQL = (ROOT / "supabase/migrations/202611010001_community.sql").read_text()
DATA = ROOT / "app/src/main/java/com/sergey/animevault/data/community"

class CommunitySourceContract(unittest.TestCase):
    def test_every_definer_pins_empty_search_path(self):
        functions = re.findall(r"create function (.*?)(?=\ncreate function|\n--|\ndo \$\$|\ncommit;)", SQL, re.S | re.I)
        self.assertGreaterEqual(len(functions), 15)
        for function in functions:
            if "security definer" in function.lower():
                self.assertIn("set search_path = ''", function.lower())

    def test_role_and_token_boundaries(self):
        self.assertIn("revoke all on function %s from public, anon, authenticated", SQL)
        self.assertNotIn("user_metadata", SQL)
        self.assertIn("pg_advisory_xact_lock", SQL)
        self.assertIn("unique(author_id, client_request_id)", SQL)
        self.assertNotRegex(SQL.lower(),r"grant\s+(insert|update|delete|all)\s+on public\.community_private_roles")

    def test_manifest_retains_both_callbacks(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        ns = "{http://schemas.android.com/apk/res/android}"
        links = [(e.get(ns+"scheme"), e.get(ns+"host"), e.get(ns+"path")) for e in manifest.iter("data")]
        self.assertIn(("animevault","anilist-auth",None),links)
        self.assertIn(("animevault","auth","/callback"),links)

    def test_no_new_sdk_or_min_sdk_bump(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn("minSdk = 24",gradle)
        self.assertNotIn("supabase-kt",gradle)
        self.assertNotIn("io.ktor",gradle)

    def test_session_and_cache_outside_backup(self):
        self.assertIn("context.noBackupFilesDir",(DATA/"CommunitySessionManager.kt").read_text())
        self.assertIn("AES/GCM/NoPadding",(DATA/"CommunitySessionManager.kt").read_text())
        self.assertIn("context.noBackupFilesDir",(DATA/"CommunityCache.kt").read_text())
        self.assertNotIn("access_token",(DATA/"CommunityCache.kt").read_text())

    def test_no_untrusted_callback_token_import(self):
        auth = (DATA/"CommunityAuthRepository.kt").read_text()
        self.assertIn('"code_verifier" to pending.verifier',auth)
        self.assertNotIn('getQueryParameter("access_token")',auth)
        self.assertNotIn('getQueryParameter("refresh_token")',auth)

if __name__ == "__main__":
    unittest.main()
