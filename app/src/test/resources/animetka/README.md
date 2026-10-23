# Animetka character fixtures

Captured from the public API on 2026-10-23, without authentication:

- `frieren-characters.json`: first four entries (of 62) from `GET https://animetka.com/api/anime/2778/characters`.
- `fern-details.json`: full response from `GET https://animetka.com/api/anime/characters/188176`.

Anime 2778 is Animetka's catalogue ID; its Shikimori ID is 52991. List entries do not
provide a MAL ID, while the detailed character card does. Preserve that distinction.


Additional captures for the 3.0.5 loading investigation (2026-10-23):

- `bleach-search.json` and `naruto-search.json`: all 12 search hits, projected onto
  ID/name/year/type fields from the successful supported projection requests.
  The target TV title is deliberately not the first result.
- `bleach-characters.json` and `naruto-characters.json`: first two characters,
  projected onto IDs, names, posters and roles. The complete real responses had
  293 and 268 entries respectively. Descriptions are omitted from these fixtures.
- `search-http500.json`: actual public error body from unsupported search projections.

HTTP status, request URLs, timestamps, full response sizes and SHA-256 digests
are in `docs/character-loading-http-evidence.json`. These are public catalogue
responses; no authentication, request headers or sensitive data are included.
