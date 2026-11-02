# Community Edge Auto Deploy (main)

## What is automated

After a push to `main` that changes either Edge handler, the shared engine, Supabase function configuration or the auto-deploy tooling, [Community Edge Auto Deploy](../.github/workflows/community-edge-auto.yml) runs automatically. You may also rerun it with `workflow_dispatch` on `main`.

1. The credential-free `check` job validates the explicit bundle allowlist, Deno entrypoints, Node AutoSync fixtures and Python deployment regression tests.
2. Only after `check` passes, the `deploy` job checks that `GITHUB_SHA` is still the live head of `main`, obtains the **repository-scoped** PAT, stages only `community-ensure`, `community-catalog-worker`, config.toml and their shared engine, then deploys those two names using pinned Supabase CLI 2.54.11.
3. Before, during and after deployment it verifies exact remote SHA256 digests of required Secrets. `COMMUNITY_AUTO_WRITE_ENABLED` must be exactly `true` or exactly `false` and must **remain unchanged**. Automatic deployment neither switches the gate nor invokes `secrets set`.
4. It checks both functions are ACTIVE with `verify_jwt=false`, a positive version, no version regression. It then performs a **non-writing GET smoke** to both endpoints, requiring `community-ensure:405` and `community-catalog-worker:401`. These probes cannot create a job, sync a title or touch comments. They are not an end-to-end comment/write verification.
5. Serialized with the existing manual protected-deploy workflow via a shared GitHub Actions concurrency group; stale commits never deploy after a newer main.

No SQL migration, service-role credential, DB connection, background worker schedule, secrets, comments, aliases, Auth, or existing Community data is modified by this workflow.

## One-time credential setup

The original manually approved workflow uses `community-edge-production` Environment secrets; a job without that Environment **cannot** read an Environment Secret. To run unattended, configure an appropriately limited Supabase Personal Access Token as an **Actions repository secret**:

1. In GitHub, open **Settings → Secrets and variables → Actions → New repository secret** for `Aqua666-prog/AnimeVault`.
2. Create `SUPABASE_EDGE_AUTO_DEPLOY_PAT` with a Supabase Personal Access Token (prefix `sbp_`) authorized to deploy only this project's functions, to the extent permissions permit. As a compatibility fallback, the workflow also accepts an existing **repository** secret named `SUPABASE_ACCESS_TOKEN`. It cannot read the similarly named secret from the protected Environment.
3. Keep this credential out of issues, commits, APK, chat messages and artifact uploads. Never substitute `SUPABASE_SERVICE_ROLE_KEY`, a database password or a publishable key. Rotate/revoke on suspected exposure.
4. Protect the `main` branch with mandatory PR reviews and passing checks: anyone able to commit arbitrary workflows to main could otherwise read repository-scoped secrets. Automatic production deployment trades the manual approval step for tightly controlled branch merges and a narrower code-only operation.

If neither repository secret exists, the deploy job fails safely with `supabase_access_token_missing`. The `check` job can still succeed. After setting the secret, rerun the auto workflow manually once on `main`; subsequent relevant pushes deploy without further action.

## Remaining manual operations

**SQL migrations stay manually approved** in [Supabase migrations](../.github/workflows/supabase-migrations.yml). Deploying a new Edge version will not install or apply `202611020002_community_pending_repair.sql`; complete the migration first when required by the code. The existing [protected manual Edge deployment](../.github/workflows/community-edge-deploy.yml) remains available for explicit, reviewer-approved recovery. It keeps its deliberate `COMMUNITY_AUTO_WRITE_ENABLED=false` requirement.

If a function fails in the middle of the two sequential deploys, the job reports the partial state and stops. No automatic destructive rollback, SQL mutation or bypass of identity verification follows. Fix the problem through a reviewed PR or rerun the latest main once the environment is healthy.

The GET smoke confirms routing/handler availability only. To assert discussions actually exist for a specific Kodik title, use independent read-only Community resolve and/or inspect authenticated logs after deployment.
