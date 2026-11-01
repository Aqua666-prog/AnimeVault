# Community Catalog AutoSync v1

AutoSync removes the need to write a manifest for bulk discovery/import. It does **not** make title similarity a trustworthy identity. Unknown mappings remain pending and do not unlock discussion branches. Existing Android 4.1.1 (81), Community RPC contracts, and the manual reviewed importer from PR #34 are unchanged.

## Data flow and guarantees

`daily/dispatch → public APIs → SQLite checkpoint + queue → report → owner-gated service-role RPC → Community tables`

`tools/community_catalog/autosync.py` uses Python's standard library, HTTPS, bounded responses, host allowlists, no redirects, 1.5 seconds between requests to each host, and five attempts for network errors/429/temporary 5xx responses. Retry-After seconds are honored up to 120 seconds; subsequent runs can resume after a longer outage. GraphQL errors and provider errors never become catalogue data. No upstream error bodies, request URLs containing tokens, or credential values are logged.

AniList GraphQL `Page(page, perPage:50)`, sorted by ID, provides the canonical AniList ID, optional MAL ID and title. Title/year/season/format are not matching keys. A complete scan resets its cursor to page 1 for the next cycle; bounded runs continue unfinished scans. An exact MAL lookup can import a canonical record before the full scan reaches it. Upstream ID conflicts roll back the page and are logged; existing canonical IDs are never silently reassigned.

SQLite commits the records and next-page cursor together. An interrupted page is retried. The file contains public catalogue metadata, evidence, cursors, receipt hashes and error codes only. Queued records are keyed by actual provider + release ID. Changed records clear their acknowledgment; unchanged records are not republished. Publishing orders anime before links, uses batches of at most 100 and deterministic UUIDs derived from their content, and acknowledges only successful RPC responses. RPC commits an entire batch, records its hash/result, and safely replays it after a lost response. A rejected batch is split to isolate bad records; rejected items stay unacknowledged for review/retry while others proceed. Transport/auth failures stop publication and retain progress.

GitHub cache retains progress between runs; artifacts keep a 14-day recovery copy. Caches are best-effort and may be evicted. Losing a checkpoint restarts discovery safely because canonical IDs, alias keys, and server receipts enforce idempotency. Do not run competing local publishers. Workflow concurrency serializes scheduled/manual runs. SQLite is not a secret store and is safe to inspect as a public artifact.

## Actual provider contracts inspected

These refer to `app/src/main/java/com/sergey/animevault/data/<provider>/` at main `aa670e1bad2620ead0120de0b02febdbaf5fae2b`, including merged PR #34. Fields below come from Kotlin adapters, not assumed API compatibility. Live responses can change and then fail closed.

| Provider | Existing contract / app release ID | AutoSync v1 |
|---|---|---|
| AniLiberty | `aniliberty.top/api/v1/anime/catalog/releases?page&limit`; `data`, `meta.pagination.total_pages`; numeric `id` | Paginated discovery. Current DTO declares no MAL/AniList ID: pending. Episode UUID/ordinal alone does not establish cross-source identity. |
| AnimeVost | `api.animevost.org/v1/last?page&quantity`; `data`, `state`; numeric `id` | Paginated discovery until short/empty page. Current DTO has no external canonical IDs: pending. Playlist index is not canonical identity. |
| Kodik | `kodik-api.com/list`, `/search`; `results`, `next_page`, `shikimori_id`, `kinopoisk_id`, `id` | Optional `KODIK_API_TOKEN`; preserves exact app priority `shiki:<id>`, `kp:<id>`, `kodik:<id>`. Only an explicit Shikimori → MAL external link → AniList crosswalk verifies a Shikimori reference. Persists only `start` from next_page, never the token-bearing URL. |
| YummyAnime | `api.yani.tv/anime?limit&offset`; `response`, `anime_id`, `remote_ids.myanimelist_id`, `remote_ids.shikimori_id` | Paginated direct MAL matches or the explicit Shikimori chain. No login/session is imported from Android. |
| Animetka | `animetka.com/api/anime/main`, `/api/anime/{id}`; app accepts `id/material_id/animetka_id`, optional detail MAL/AniList/Shikimori fields | Discover main feed and validate each detail ID. Direct IDs or explicit chain. The app's blank catalogue is **one page**, so v1 does not pretend this is a complete Animetka bulk API. It does not request unsupported mal_id/anilist_id search projections. |
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
2. In GitHub open `supabase/migrations/202611010002_community_autosync.sql` → Raw. Copy the complete file once (or download/open it if your SQL Editor offers file import). In the Supabase project `kgvkxocbkklisqrqmcjx`, open SQL Editor in the browser's desktop mode and run it as the project administrator. This is an additive transaction, not a replacement of the old migration. Never run only selected fragments. It creates two private RLS tables and one service-role-only RPC. It imports **zero anime** by itself.
3. GitHub → repository → Actions → **Community Catalog AutoSync** → Run workflow → main. Start with `pages=10`, **apply unchecked**. Scheduled runs also only discover by default. Download `catalog-discovery` or read the job summary. For a faster initial bulk pass, use pages=100; repeat until the AniList cursor completes its cycle. A partial page resumes on the next run.
4. Optional: Settings → Secrets and variables → Actions → Secrets → add `KODIK_API_TOKEN` for your authorized Kodik API access. Without it Kodik is reported as not configured; no token is scraped or invented.
5. Review the report and SQLite public evidence, pending/rejected records, provider coverage, and the MAL 61967 result. **Stop here until you explicitly authorize production writes.** Creating the migration and read-only discovery does not enable writes.
6. After authorization: Settings → Environments → create **community-catalog-write**, restrict it to main and require an owner/reviewer approval if your GitHub plan supports protected environments. Add environment Secret **SUPABASE_SERVICE_ROLE_KEY** containing the project's legacy service_role JWT from Supabase API settings. It is an administrative secret: enter it directly in GitHub's secret input, never into chat, JSON, SQL, Android build variables, or screenshots/logs. The worker uses it only for the admin RPC. A publishable key is insufficient; this worker expects the legacy role JWT, not an SMTP password or Management API token.
7. Settings → Secrets and variables → Actions → Variables → set **COMMUNITY_AUTOSYNC_WRITE_ENABLED=true**. Default absent/false means no publication. Run the workflow again on main with **apply checked**, then approve the publish environment if protected. Inspect `catalog-publish-report` and errors. This is your explicit owner-controlled production-write action; Codex has not performed it.
8. Optional, only after a successful first publication: set **COMMUNITY_AUTOSYNC_DAILY_WRITE=true** to allow the daily 03:23 UTC run to publish. Otherwise daily runs remain discovery-only. GitHub schedule timing is best-effort and inactive repositories may need scheduled workflows re-enabled.
9. To stop writes immediately, remove/set false both write variables. Keep the reports and cache for investigation. Removing admin credentials also prevents publication. Do not roll back by deleting shared anime/comment rows.

No Android Actions variables or APK configuration changes are required for this importer. The existing Android Supabase URL/publishable key remain separate. Administrative credentials are not passed to the Android build jobs. There are no pull_request or pull_request_target triggers on this workflow.

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
