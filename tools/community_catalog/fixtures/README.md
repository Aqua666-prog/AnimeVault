# Provider contract evidence

`animetka-search.json`, `animetka-search-2.json` and `animetka-detail-2778.json` are minimal identity-only projections of live read-only responses captured 2026-11-02:

- `https://animetka.com/api/anime/search?name=&limit=2&offset=0&attributes=animetka_id&attributes=title&attributes=shikimori_id`
- the same search with `offset=2` returned different IDs;
- `https://animetka.com/api/anime/2778` returned `id=2778`, `shikimori_id=52991`, `Animes[].kodik_id`, and `AnilibriaMapping` with mapping PK `8762` but episode `release_id=9542`.

Requests used `Referer: https://animetka.com/` and `Accept: application/json, text/plain, */*`. The actual worker User-Agent was also tested: search returned HTTP200. A request to the main feed without these headers returned HTTP403; this confirms required request context, not a universal explanation for every historical 404. Streaming URLs/descriptions/artwork were removed; episode UUIDs are retained strictly as identity evidence, never episode-number matching.

Yummy API and Kodik API could not be reached from the Codex network (proxy403). Their authenticated live responses are NOT represented as captured fixtures. The request contracts are corroborated by the actual Android sources:

- `data/yummy/YummyAnimeProvider.kt`: X-Application, Lang, Referer, `/anime`, response envelope/remote_ids.
- `data/kodik/KodikProvider.kt`: exact reference priority shiki/kp/kodik; `/list`, `/search`, opaque next_page.
- The independently inspected public SDK `https://github.com/mirasayon/kodik.js/blob/main/src/interfaces/shared-types.ts` declares a `next` parameter and `next_page` response; the old importer incorrectly required only `start` and a narrow alphabet. Tests cover both parameters, padded/long opaque values, credential/host rejection, and legacy checkpoints. Inline Kodik/Yummy response objects are synthetic contract tests, not claimed live responses.

Bridge tests require a simulated primary-provider response before confirming a cross-provider link; the captured Animetka mapping alone never manufactures a Kodik/AniLiberty alias. The fixtures are not a manifest, seed data or production-write instruction.
