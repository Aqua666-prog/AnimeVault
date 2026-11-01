# Audit of unapplied Community migration

Base: main including PR #30, Android 4.1.1 (81). This is a replacement initial migration, not an incremental upgrade. Apply only to a project without existing Community objects, with Supabase auth.users and API roles present.

Findings and fixes:
- total counted all descendants instead of the requested parent slice. It now uses the same anime/episode/parent predicate as the page.
- Unknown anime and mismatched episodes leaked FK errors. create_comment now returns PT400 before insertion; null request/spoiler inputs are rejected.
- NULL like state silently removed a like; NULL moderation action could restore content. Explicit validation now rejects these inputs.
- Report reason/description and ban targets lacked RPC validation; these now return PT400 rather than constraint failures.
- A still-valid JWT for a deleted Auth identity passed require_user. The helper now verifies auth.users existence.
- Report description erasure depended on a profile trigger and Auth cascade ordering. A BEFORE DELETE Auth trigger clears descriptions while reporter IDs still exist, including accounts without profiles.

Reviewed: table keys and composite episode FK, soft deletion and parent references, two-level reply traversal, per-author request uniqueness/advisory lock, desired-state likes, report uniqueness, private moderator roles, RLS and schema/function grants. Replies remain chronological in every sort mode; root modes retain UUID tie breakers. No client wire fields or function signatures changed. Android total now describes the current root/reply list, as requested; pagination remains bounded to 50 rows and offset 10000.

Private helpers retain empty search_path, no API schema USAGE and no API EXECUTE. Direct comment/like/report/role/ban/log writes remain denied. Deleted/hidden comments remain uneditable; deleted parents preserve other authors' replies. Account erasure and Dashboard Auth deletion retain existing anonymisation paths, with the additional Auth trigger protecting report text.

Checks: 71 lightweight Python tests passed locally. SQL integration results are recorded in the PR; do not infer database execution from source checks. No Gradle, APK build, remote Supabase change or deployment was performed by the agent.

Open the complete file `/workspace/AnimeVault/supabase/migrations/202611010001_community.sql` (repository path `supabase/migrations/202611010001_community.sql`) in Supabase SQL Editor. Review and back up first; execute the single begin/commit transaction manually. No older migration must be applied first. Actual hosted Supabase/PostgREST/Auth verification remains an owner task. This initial migration is not safe to reapply to existing Community tables.
