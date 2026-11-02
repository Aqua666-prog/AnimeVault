# Community Catalog AutoSync v1

Для первой записи в Supabase используйте [Safe Canary Publish](COMMUNITY_CANARY.md): только AniList195604 и kodik/shiki:61967, с dry-run и отдельным подтверждением. Обычный discovery/apply публикует накопленную очередь; pages не ограничивает размер публикации.

AutoSync removes the need to write a manifest for bulk discovery/import. It does **not** make title similarity a trustworthy identity. Unknown mappings remain pending and do not unlock discussion branches. Existing Android 4.1.1 (81), Community RPC contracts, and the manual reviewed importer from PR #34 are unchanged.

## Data flow and guarantees

`daily/dispatch → public APIs → SQLite checkpoint + queue → report → owner-gated service-role RPC → Community tables`

`tools/community_catalog/autosync.py` uses Python's standard library, HTTPS, bounded responses, host allowlists, no redirects, 1.5 seconds between requests to each host, and five attempts for network errors/429/temporary 5xx responses. Retry-After seconds are honored up to 120 seconds; subsequent runs can resume after a longer outage. GraphQL errors and provider errors never become catalogue data. No upstream error bodies, request URLs containing tokens, or credential values are logged.

AniList GraphQL `Page(page, perPage:50)`, sorted by ID, provides the canonical AniList ID, optional MAL ID and title. Title/year/season/format are not matching keys. A complete scan resets its cursor to page 1 for the next cycle; bounded runs continue unfinished scans. An exact MAL lookup can import a canonical record before the full scan reaches it. Upstream ID conflicts roll back the page and are logged; existing canonical IDs are never silently reassigned.

SQLite commits each completed provider page, its retry records and next-page cursor together. Individual failed records use savepoints and a durable retry queue, so one 404 does not discard the successful identities in that page. An interrupted page is retried. The file contains public catalogue metadata, evidence, cursors, receipt hashes and error codes only. Queued records are keyed by actual provider + release ID. A weaker pending observation does not erase a previous verified proof or its acknowledgment. SQLite schema upgrades are additive; existing catalogue-v1 caches and legacy start cursors are retained. Changed records clear their acknowledgment; unchanged records are not republished. Publishing orders anime before links, uses batches of at most 100 and deterministic UUIDs derived from their content, and acknowledges only successful RPC responses. RPC commits an entire batch, records its hash/result, and safely replays it after a lost response. A rejected batch is split to isolate bad records; rejected items stay unacknowledged for review/retry while others proceed. Transport/auth failures stop publication and retain progress.

GitHub cache retains progress between runs; artifacts keep a 14-day recovery copy. Caches are best-effort and may be evicted. Losing a checkpoint restarts discovery safely because canonical IDs, alias keys, and server receipts enforce idempotency. Do not run competing local publishers. Workflow concurrency serializes scheduled/manual runs. SQLite is not a secret store and is safe to inspect as a public artifact.

## Actual provider contracts inspected

These refer to `app/src/main/java/com/sergey/animevault/data/<provider>/` at main `aa670e1bad2620ead0120de0b02febdbaf5fae2b`, including merged PR #34. Fields below come from Kotlin adapters, not assumed API compatibility. Live responses can change and then fail closed.

| Provider | Existing contract / app release ID | AutoSync v1 |
|---|---|---|
| AniLiberty | `aniliberty.top/api/v1/anime/catalog/releases?page&limit`; `data`, `meta.pagination.total_pages`; numeric `id` | Paginated discovery. Current DTO declares no MAL/AniList ID: pending. Episode UUID/ordinal alone does not establish cross-source identity. |
| AnimeVost | `api.animevost.org/v1/last?page&quantity`; `data`, `state`; numeric `id` | Paginated discovery until short/empty page. Current DTO has no external canonical IDs: pending. Playlist index is not canonical identity. |
| Kodik | `kodik-api.com/list`, `/search`; `results`, `next_page`, `shikimori_id`, `kinopoisk_id`, `id` | Optional `KODIK_API_TOKEN`; preserves exact app priority `shiki:<id>`, `kp:<id>`, `kodik:<id>`. Only an explicit Shikimori → MAL external link → AniList crosswalk verifies a Shikimori reference. Persists only the opaque `next` (or legacy `start`) parameter/value plus its known filter mode from next_page, never the token-bearing URL. New scans use Android’s anime/anime-serial filter; existing v1 cursors finish their original scan before switching filters. |
| YummyAnime | `api.yani.tv/anime?limit&offset`; `response`, `anime_id`, `remote_ids.myanimelist_id`, `remote_ids.shikimori_id` | Requires owner-configured `YUMMY_APPLICATION_KEY` (X-Application) plus Lang/Referer, matching Android. Paginated direct MAL matches or the explicit Shikimori chain. No login/session is imported from Android. |
| Animetka | `animetka.com/api/anime/main`, `/api/anime/{id}`; app accepts `id/material_id/animetka_id`, optional detail MAL/AniList/Shikimori fields | Paginate the live-verified blank-name `/api/anime/search` endpoint with limit/offset and supported attributes; validate details against search IDs. Send Android Referer/Accept headers. It does not request unsupported mal_id/anilist_id search projections. Main feed remains only a limited UI feed. |
| Jut.su | `jut.su`, URL_OR_SLUG adapter; no bulk getCatalog, slug release ID | Not scanned: no invented bulk endpoint or guessed slug. |
| AnimeLib | `api.cdnlibs.org/api/anime`, slug_url; no external canonical ID declared in current DTO | Inspected; no automatic verified matching in v1. |
| Dreamerscast / AniMedia | HTML adapters, source-specific paths | Inspected; no declared authoritative ID chain implemented. Not scanned. |
| AnimeOn | `animeon.club/api/anime/search`, player API IDs | Inspected; no declared MAL/AniList fields. Not scanned. |
| SameBand / AnimeBest | HTML/DLE search and source paths | Inspected; no authoritative ID chain implemented. Not scanned. |
| all | Synthetic unified catalogue IDs | Never constructed from an individual provider alias. Open the concrete provider's title to test a newly imported alias. |

Sources not scanned and sources with unavailable credentials appear explicitly in reports. This v1 automatically imports the canonical catalogue and supported proven matches; it cannot promise full verified coverage of every provider. New adapters should add captured contract fixtures before publication is enabled.

## Evidence and episodes

Each observed mapping gets `verified`, `pending`, or `rejected`, a proof method, source, IDs and, where relevant, the authoritative external-link endpoint. `verified` needs a direct external ID corroborated by AniList or an explicit chain. The worker never assumes Shikimori numeric IDs equal MAL IDs. HTTP MAL links returned *as data over the HTTPS Shikimori API* are parsed without fetching HTTP pages. Multiple contradictory links are pending; conflicting canonical IDs are rejected. An existing alias is never moved to another anime, including an unverified alias created earlier. The server retains a rejected candidate instead.

`community_sync_evidence` holds the latest candidate decision, not an immutable audit history. Public aliases remain untouched on a later pending/rejected observation; automatically revoking an established identity would require a separate reviewed policy. Reports include server-side rejected counts, which may differ from local candidate counts when the server detects a pre-existing alias conflict.

No currently inspected provider exposes a proven cross-source canonical episode identity. Therefore v1 **does not automatically insert or change** `community_episodes` or `community_episode_aliases`. Evidence records pending episode identity; previously reviewed episode mappings continue to work. Equal episode numbers, season numbers, playlist order, and guessed episode keys are insufficient. Anime discussions can become available while episode discussions correctly remain unavailable.

## One-time owner setup from an Android browser

No Termux, manifest.json or local credentials are needed.

1. Merge the AutoSync PR. Existing `202611010001_community.sql` must already be applied; **do not run it again**.
2. Use [Supabase migrations via GitHub Actions](SUPABASE_MIGRATIONS.md): run `check`, review history/object state, then explicitly authorize `apply` (or `recover` for a supported partial state). This registers the already-deployed 001 without replaying it and safely handles 002. No SQL Editor copy/paste is required. Schema deployment alone imports **zero anime**.
3. GitHub → repository → Actions → **Community Catalog AutoSync** → Run workflow → main. Start with `pages=10`, **apply unchecked**. Scheduled runs also only discover by default. Download `catalog-discovery` or read the job summary. For a faster initial bulk pass, use pages=100; repeat until the AniList cursor completes its cycle. A partial page resumes on the next run.
4. Optional: Settings → Secrets and variables → Actions → Secrets → add `KODIK_API_TOKEN` for your authorized Kodik access and `YUMMY_APPLICATION_KEY` for your authorized Yani application access (X-Application). Neither is a Community login token. Missing keys are reported as not configured before API calls; no token is scraped or invented. For actual read-only resolver counts, set repository Actions **Variable** `SUPABASE_PUBLISHABLE_KEY` to the same project publishable key used by Android; no service_role is passed to discovery.
5. Review the report and SQLite public evidence, pending/rejected records, provider coverage, and the MAL 61967 result. **Stop here until you explicitly authorize production writes.** Creating the migration and read-only discovery does not enable writes.
6. After authorization: Settings → Environments → create **community-catalog-write**, restrict it to main and require an owner/reviewer approval if your GitHub plan supports protected environments. Add environment Secret **SUPABASE_SERVICE_ROLE_KEY** containing the project's legacy service_role JWT from Supabase API settings. It is an administrative secret: enter it directly in GitHub's secret input, never into chat, JSON, SQL, Android build variables, or screenshots/logs. The worker uses it only for the admin RPC. A publishable key is insufficient; this worker expects the legacy role JWT, not an SMTP password or Management API token.
7. Settings → Secrets and variables → Actions → Variables → set **COMMUNITY_AUTOSYNC_WRITE_ENABLED=true**. Default absent/false means no publication. Run the workflow again on main with **apply checked**, then approve the publish environment if protected. Inspect `catalog-publish-report` and errors. This is your explicit owner-controlled production-write action; Codex has not performed it.
8. Optional, only after a successful first publication: set **COMMUNITY_AUTOSYNC_DAILY_WRITE=true** to allow the daily 03:23 UTC run to publish. Otherwise daily runs remain discovery-only. GitHub schedule timing is best-effort and inactive repositories may need scheduled workflows re-enabled.
9. To stop writes immediately, remove/set false both write variables. Keep the reports and cache for investigation. Removing admin credentials also prevents publication. Do not roll back by deleting shared anime/comment rows.

No Android code, APK configuration changes or Supabase schema migration are required for this update. Discovery reads the existing SUPABASE_PUBLISHABLE_KEY Actions variable only for anonymous stable community_resolve calls. The existing Android Supabase URL/publishable key remain separate. Administrative credentials are not passed to the Android build jobs. There are no pull_request or pull_request_target triggers on this workflow.

## Reading the report and checking the first discussion

`canonical`, `verified`, `pending`, `rejected` are **current local discovery** totals. `canonical_acknowledged` counts canonical queue entries acknowledged by the backend, not newly inserted rows in one run. `published_this_run` counts acknowledged operations. `server_batch_totals` contains cumulative accepted batch operations and server-side rejected candidate counts; updates can count again after metadata changes, so it is not a distinct-row census. `errors` groups fixed error codes by source; rejected publication sources identify the queue key. Reports never claim unexecuted production writes succeeded.

After publication, run these read-only SQL queries in Supabase SQL Editor:

```sql
select count(*) as canonical_anime from public.community_anime;
select count(*) as verified_aliases from public.community_anime_aliases where verified;
select status,count(*) from public.community_sync_evidence group by status;
select a.anilist_id,a.mal_id,l.provider,l.external_id
from public.community_anime a join public.community_anime_aliases l on l.anime_id=a.id
where l.verified order by a.anilist_id limit 20;
select a.anilist_id,a.mal_id,l.provider,l.external_id,l.verified
from public.community_anime a left join public.community_anime_aliases l on l.anime_id=a.id
where a.mal_id=61967;
```

Pick a verified row, open that **concrete provider** and exact release in AnimeVault, then refresh/open Discussion. Guest reading should resolve; sign into the separate Community account with a profile to post, reply and like. Do not use the synthetic `all` release as proof of a concrete alias match. Episode discussion remains unavailable until an authoritative episode mapping exists.

For MAL 61967, the worker performs an exact AniList MAL lookup. If Shikimori's actual `61967` external-links response explicitly points to MAL 61967 and a Kodik token is configured, it queries Kodik `/search?shikimori_id=61967`, validates returned fields, and only then derives the same `shiki:` reference the Android adapter uses. No Kodik record is synthesized if the API returns no result. The report lists actual verified aliases; an empty list is a valid outcome.

On 2026-11-01, a live read-only request to `https://shikimori.io/api/animes/61967/external_links` returned a `myanimelist` link to `http://myanimelist.net/anime/61967`. No supported provider links appeared in that response. AniList could not be reached from the Codex network (proxy 403), and no Kodik token was available. Therefore the live AniList/provider connection for this title is **not claimed verified**. First GitHub discovery supplies that evidence or reports an error.

## Verification and recovery

Python fixture tests cover checkpoint atomicity, retries/guards, direct IDs/chains, ambiguity, duplicate acknowledgments and credentials not entering state. PostgreSQL 17 tests use disposable loopback databases with minimal Supabase role/auth shims; they verify batch replay/hash conflict, atomic rollback, alias preservation, pending invisibility, role spoof denial, private tables and the unchanged Android resolver. This is not a production Supabase migration test or a live end-to-end provider guarantee.

If schema application fails, its transaction rolls back; inspect the error and existing objects rather than editing the old migration. The additive migration is not designed to be applied twice. If cache is damaged, retain the report/artifact and start with a fresh cache key: all canonical and alias operations are repeatable. If an RPC batch conflicts, inspect its queue record and server mappings; never repair by guessing an ID or dropping tables. Admin credentials can bypass RLS throughout Supabase: protect and rotate them through Supabase/GitHub after suspected exposure.


## Fixes after the first real discovery (501 canonical / 1 verified / 540 pending)

Those counts were discovery counts, not records already published to Supabase: published_this_run=0 is expected with apply disabled. Do not clear the SQLite cache or manually recreate its manifest. After merging this fix, run **Community Catalog AutoSync** on main with **apply unchecked**, initially pages=1–2 to inspect provider behavior. The next runs continue existing cursors; canonical IDs, aliases and receipts are not reset. Cache v2 includes SQLite rollback journals and explicitly restores the old exact-path catalog-v1 cache if no v2 cache exists; changing a cache path without this fallback would lose access to the old cache version.

The fixes address:

- **Animetka:** real responses verified on 2026-11-02: main/details need proper Referer/Accept; blank search supports actual offset pagination. Distinct offset=0/2 responses and detail2778 were captured as minimal identity fixtures. A missing/bad detail becomes a retained retry, not a dropped page or a guessed alias.
- **Yummy:** the old worker omitted X-Application, Lang and Referer. Configure the authorized application key Secret. Authenticated requests could not be tested live in Codex because the host is blocked by the network proxy; a configured-but-rejected key still appears as a provider error, never a verified mapping.
- **Kodik:** next_page is opaque; support both `next` and legacy `start`, padding and long values, and retain only the cursor parameter/value. Reject foreign hosts, ambiguous cursor parameters, controls, leaked tokens and stalled cursors. The actual public SDK declares `next`; the old importer assumed only `start`. Direct live Kodik validation remains for the owner’s next discovery because this environment cannot reach the host or read the GitHub Secret.
- **Android reference identity:** KodikItemDto.releaseReference() in the app returns shiki:<shikimori_id> before kp:<kinopoisk_id> and kodik:<id>. OnlineTitleScreen sends release.providerId and release.id into AnimeIdentityResolver, which passes them unchanged as p_provider/p_external. Thus **shiki:61967 is a real Android ID format**, but is verified only after observing a real matching Kodik result and a corroborated canonical ID chain. Do not replace it with a raw serial ID, and do not construct a Unified `all` ID.

### Additional verified identifier chains

Verified Animetka details can queue bounded, resumable bridge jobs:

1. `Animes[].kodik_id` is looked up using the real Kodik API `/search?id=...`. The returned material ID must match. The importer derives the client reference from the *primary API response*, and cross-checks a Shikimori chain when present. Kinopoisk-only grouped references remain pending because a series-level KP ID can cover multiple seasons.
2. `AnilibriaMapping.id` is never used as an AniLiberty ID: it is a mapping-table PK. Require one unambiguous `episodes[].release_id`, fetch that real AniLiberty release, require matching release.id and actual episode UUID membership before assigning the whole-title alias. No episode-number inference or episode-table writes occur.

Only identity fields enter deferred jobs; stream links, tokens, images and descriptions do not. Bridge requests are bounded to 50 records/run, four Kodik materials per record/pass. Remaining materials, transient failures and retries rotate through persistent queues, preventing one failure from starving the rest. This does not guarantee every provider exposes a safe chain.

### New report fields

| Field | Meaning |
|---|---|
| verified | Local canonical evidence is verified; this is not a claim of production publication |
| verified_client_contract | Verified links with observed primary-source IDs matching Android’s exact release-ID contract |
| verified_without_client_contract | Legacy/unobserved links held from future publication until rescanned |
| verified_resolves_community_resolve | Fresh (≤24h), successful read-only RPC checks using the actual Android parameters AND returning the same anime UUID as a canonical-ID RPC |
| verified_other | All verified links not confirmed by that actual RPC check |
| resolver_results | resolved / unresolved / conflict / error / not_checked |
| resolver_verification | Whether verification ran, missing key reason, checked count and timestamp |
| retry_links / bridge_jobs | Outstanding durable work, not discarded progress |
| errors_this_run | New errors from this process; errors remains retained historical totals, so an old 404 can remain there after a fix |

If the public key is absent, verification makes **no Supabase requests**. A zero resolved count with not_run/not_checked means no successful checks were established; it does not claim that every alias fails. Requests are limited to 500 verified aliases/run with a rotating cursor, and checks are invalidated when their exact queued evidence changes. With unpublished data, unresolved is expected. A conflicting server alias is reported; it is never reassigned automatically. Canonical and provider RPCs are the existing STABLE community_resolve only; no administrative key, write RPC or production migration is used by verification.

Tests include existing-cache resumption, opaque cursors, missing auth headers, conflicting detail IDs, UUID-based bridges, held legacy aliases, and a real isolated PostgreSQL anon community_resolve for Kodik/Animetka/Yummy/AniLiberty. A raw Kodik serial ID correctly fails where the client reference is shiki:61967. Synthetic database tests do not establish any new live canonical mapping. User-supplied discovery established MAL61967→AniList195604; this update preserves that cache record rather than replacing it with a guessed ID.
