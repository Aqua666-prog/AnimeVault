# Final Community SQL audit — Android 4.1.1 (81)

Base main: 4ec82c2e81632d4c448b3db6e62eac76120826e7, including previous audit PR #31.

Fixed: list_comments no longer forces chronological replies regardless of p_sort. Both roots and replies now honor new (date descending), old (date ascending), popular (likes descending then date ascending); UUID ascending breaks ties. Parent-filtered total and page bounds remain unchanged. Android RPC names, parameter types and JSON fields are unchanged; the existing client passes its selected sort into reply requests. No Android code or version changes.

Fixed: community_ban checks a past p_until only when creating/updating an active ban. p_revoke=true works with a past timestamp. Server-side protection of admins/moderators remains active.

Hardened: mass REVOKE still removes PUBLIC/anon/authenticated function privileges, but authenticated EXECUTE is granted only to the explicit reviewed RPC allowlist rather than every public community_* function. Internal helpers/triggers receive no API grants. All SECURITY DEFINER functions keep empty search_path; their owner is the migration-executing trusted role (use Supabase SQL Editor postgres, not an API user). No function grants role membership or accepts client-assigned administrative roles. Access to auth.users is schema-qualified and runs under trusted function ownership. RLS/direct-write restrictions remain unchanged.

Verified account deletion through erase_account and direct auth.users deletion: profile removal, body redaction/anonymisation, like cascades, reporter identity/description erasure (also without a profile), preservation of other authors' replies. Operational retention of administrative free-text reasons/backups still requires the owner's policy; automatic erasure does not scan arbitrary other users' text for mentions of the deleted person.

Executed locally against two fresh disposable PostgreSQL 17 databases using the repository Auth shim and roles: final suite 19/19 SQL tests passed. Includes creation, isolation, depth, retry, sort/ties/pages, likes, reports, moderation, bans/revocation, role/ACL/owner checks and deletion. 71 lightweight Python tests and PostgreSQL/PLpgSQL syntax parsing also passed. The Auth shim verifies database behavior, not hosted GoTrue/PostgREST/JWT validation or the exact hosted service_role/default ACL environment. No production connection or migration application. No Gradle, APK or release.

Ready for reviewed manual INITIAL installation in Supabase, with existing auth.users and API roles but no Community objects. Open the complete file:
/workspace/AnimeVault/supabase/migrations/202611010001_community.sql
Repository path: supabase/migrations/202611010001_community.sql
Run its single begin/commit transaction; do not run an older variant first or reapply over existing Community tables. Review and back up first. Confirm hosted owners/grants/schema cache afterward using SUPABASE_SECURITY.md.

After installation: register/confirm a test Community account, create a profile, assign the first admin through trusted SQL, then seed community_anime with verified AniList/MAL IDs, community_anime_aliases with verified provider/release IDs, community_episodes with canonical episode keys and community_episode_aliases with verified provider episode IDs. Follow COMMUNITY_LAUNCH.md templates. Empty tables do not automatically populate the catalog. SMTP, production redirect/App Links and real device/Auth QA remain separate launch prerequisites.
