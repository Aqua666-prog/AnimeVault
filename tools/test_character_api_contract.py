"""Lightweight API contract regression using captured responses; no Kotlin/Android execution."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "app/src/test/resources/animetka"
EVIDENCE = json.loads((ROOT / "docs/character-loading-http-evidence.json").read_text(encoding="utf-8"))


def production_search_attributes():
    source = (ROOT / "app/src/main/java/com/sergey/animevault/data/animetka/AnimetkaApi.kt").read_text(encoding="utf-8")
    projection = source.split("internal val ANIMETKA_SEARCH_ATTRIBUTES = listOf(", 1)[1].split(")", 1)[0]
    return re.findall(r'"([^"]+)"', projection)


class FakeAnimetka:
    """Reproduces the observed server contract rather than accepting arbitrary attributes."""
    def search(self, title, attributes):
        if set(attributes) - set(EVIDENCE["supportedSearchProjection"]):
            return 500, json.loads((FIXTURES / "search-http500.json").read_text(encoding="utf-8"))
        return 200, json.loads((FIXTURES / f"{title.lower()}-search.json").read_text(encoding="utf-8"))


class CharacterApiContractTest(unittest.TestCase):
    def test_production_projection_is_accepted_for_bleach_and_naruto(self):
        attributes = production_search_attributes()
        self.assertIn("animetka_id", attributes)
        self.assertIn("shikimori_id", attributes)
        for title in ["Bleach", "Naruto"]:
            with self.subTest(title=title):
                status, payload = FakeAnimetka().search(title, attributes)
                self.assertEqual(200, status)
                self.assertIsInstance(payload, list)
                self.assertTrue(payload)

    def test_fake_rejects_both_broken_fields_individually_and_old_projection(self):
        for attributes in [["animetka_id", "shikimori_id", "mal_id"],
                           ["animetka_id", "shikimori_id", "anilist_id"], EVIDENCE["brokenSearchProjection"]]:
            with self.subTest(attributes=attributes):
                status, payload = FakeAnimetka().search("Bleach", attributes)
                self.assertEqual(500, status)
                self.assertFalse(payload["success"])
                self.assertNotIsInstance(payload, list)

    def test_fixtures_require_id_mapping_instead_of_first_search_hit(self):
        for title, shiki, animetka in [("Bleach", 269, 2529), ("Naruto", 20, 3061)]:
            with self.subTest(title=title):
                _, candidates = FakeAnimetka().search(title, EVIDENCE["supportedSearchProjection"])
                matches = [row for row in candidates if row["shikimori_id"] == shiki]
                self.assertEqual(1, len(matches))
                self.assertEqual(animetka, matches[0]["animetka_id"])
                self.assertNotEqual(shiki, animetka)
                self.assertNotEqual(animetka, candidates[0]["animetka_id"])
                characters = json.loads((FIXTURES / f"{title.lower()}-characters.json").read_text(encoding="utf-8"))
                self.assertEqual(2, len(characters))
                self.assertTrue(all(row["character_shikimori_id"] > 0 for row in characters))
                self.assertTrue(all(row["poster_url"].startswith("https://shikimori.io/") for row in characters))
                self.assertTrue(all("mal_id" not in row for row in characters))


if __name__ == "__main__":
    unittest.main()
