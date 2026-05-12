# Runbook — Migration Rollback

> JA: [docs/ja/ops/migration-rollback.md](../../ja/ops/migration-rollback.md)

How to recover from a destructive Supabase migration that landed on prod and turned out to be wrong. Covers the **forward-only** principle, the destructive-migration matrix and its recovery paths, and the practice-drill discipline.

## Mental model

Supabase migrations are **forward-only**. There is no `down` step that the platform runs for you, and you cannot in general "uninstall" a migration. Every recovery is one of:

- **Forward-fix** — author a NEW migration that undoes the prior change at the schema level. Works for purely structural changes (adding back a dropped column, restoring a dropped function, reversing a policy edit). Data lost during the original migration is **not** recovered by the forward-fix.
- **PITR restore** — Supabase Point-in-Time Recovery rolls the entire database back to a snapshot before the bad migration. Recovers data but throws away every other write that happened in the interval. Requires Supabase Pro (PITR retains 7 days by default).
- **Both** — for a destructive migration with concurrent user activity, the only complete recovery is PITR restore + author the corrected migration after.

The choice depends on the migration class. Use the matrix below.

## Destructive migration matrix

| Migration class | Data lost? | Schema reversible? | Recovery path |
|---|---|---|---|
| `DROP TABLE` | ✅ yes (everything in the table) | ❌ no (data is gone) | **PITR restore** is the only path that recovers data. Forward-fix can rebuild the table schema but cannot recover the rows. |
| `DROP COLUMN` | ✅ yes (the column's data) | ✅ yes (schema-wise) | **PITR restore** for data + forward-fix migration to re-add the column with the same type/constraints. If the column was never used in production, forward-fix alone is fine. |
| `ALTER COLUMN ... TYPE` (lossy cast) | ✅ yes (precision / format lost) | ✅ yes (schema-wise) | **PITR restore** for data. Forward-fix alone restores the column type but the cast already truncated the values. |
| `ALTER COLUMN ... TYPE` (widening / lossless) | ❌ no | ✅ yes | Forward-fix migration `ALTER COLUMN ... TYPE` back to the narrower type. Verify all values fit the narrower type before applying. |
| `DROP FUNCTION` | ❌ no (no data) | ✅ yes | Forward-fix migration recreates the function from its definition. Keep the `pg_get_functiondef` output for every SECURITY DEFINER function in version control (see `supabase/migrations/` history). |
| `ALTER FUNCTION ... SET search_path = ''` | ❌ no | ✅ yes | Forward-fix `ALTER FUNCTION ... RESET search_path` (or set to the prior value). Note: this should never be necessary — locking `search_path` is a security improvement. |
| `REVOKE EXECUTE` | ❌ no | ✅ yes | Forward-fix `GRANT EXECUTE ON FUNCTION ... TO <role>` to restore the prior ACL. |
| `DROP POLICY` / `CREATE POLICY` replace | ❌ no | ✅ yes | Forward-fix migration `DROP POLICY IF EXISTS` + `CREATE POLICY` with the prior expression. Keep the `pg_get_expr(polqual, polrelid)` output in the original migration comment so the reverse is easy to author. |
| `DELETE FROM <table> WHERE ...` (data-only migration) | ✅ yes (the deleted rows) | n/a | **PITR restore** is the only path. Avoid data-only migrations in `supabase/migrations/`; use Dashboard SQL editor with explicit approval for one-shot deletes. |
| `RLS DISABLE` then re-`ENABLE` | depends — between disable and re-enable, any service-role or postgres-role write that violated RLS is now persisted | ✅ schema-wise | If the RLS-bypass window allowed bad writes, **PITR restore**. Otherwise forward-fix to re-`ENABLE` is fine. |

## Pre-migration safety discipline

Every migration that DROPs a column / table / function, changes RLS, or changes a CHECK / FK constraint MUST:

1. **Be tagged `BREAKING` in the migration comment header** at the top of the SQL file. Reviewers / future operators scanning `supabase/migrations/` for risk should be able to identify destructive migrations by header grep.
2. **Have a documented rollback plan in the same commit**:
   - If reversible at the schema level: include a comment block at the bottom of the migration with the literal SQL for the reverse migration. Example:
     ```sql
     -- Rollback plan (forward-fix):
     -- ALTER TABLE public.foo ADD COLUMN bar TEXT NOT NULL DEFAULT '';
     -- UPDATE public.foo SET bar = ... WHERE ...;
     ```
   - If irreversible (DROP TABLE, lossy ALTER): document PITR target time + the data-loss boundary (which other writes are in-window). Owner is responsible for deciding whether to take PITR or accept loss.
3. **Be applied during a low-write window** so PITR has a tight blast radius if rollback is needed. For pre-alpha, all hours are "low-write" because there are no users; for post-alpha, prefer off-peak hours.

## Pre-v1 breaking-change policy

Per project memory `pre_v1_breaking_changes.md`, until Phase 40 GA, destructive migrations are permitted without backward-compat shims when they produce a better v1.0 outcome (e.g., schema rename to align user-facing terminology, RLS tightening). The two existing precedents:

- Phase D terminology audit: renamed `pull_requests` → `suggestions` family (migrations 026, 027). No backward-compat view; existing code switched in the same PR.
- Phase 39 pre-alpha hardening (this wave): migration 030 changed `comments` SELECT policy semantics, removing the share-token leak arm. Pre-existing app-side queries continue to work; clients depending on the leak arm see fewer rows (none today because the share-token UX never advertised comment visibility).

Phase 40 GA tightens this policy. Destructive migrations after GA require:

- Explicit ADR documenting why backward-compat is acceptable
- Coupled client-side compatibility window (e.g., two app releases between schema removal and migration apply)
- Double-staffed review

## Rollback drill — practice procedure

The first time you need to roll back will not be the time to figure out how. Run this drill once before alpha launch and once per quarter thereafter.

1. Pick a recent reversible migration (e.g., `030_pre_alpha_security_hardening.sql`).
2. Open Supabase Dashboard → SQL Editor.
3. Author the reverse migration **locally only** (do NOT apply to prod) — for migration 030:
   ```sql
   -- Reverse of 030 (drill — do not apply).
   ALTER FUNCTION public.handle_new_user()              RESET search_path;
   ALTER FUNCTION public.set_progress_owner_id()        RESET search_path;
   ALTER FUNCTION public.touch_subscriptions_updated_at() RESET search_path;
   ALTER FUNCTION public.update_updated_at()            RESET search_path;
   GRANT EXECUTE ON FUNCTION public.grant_alpha_pro(uuid) TO anon, authenticated;
   -- ... + 8 more GRANT EXECUTE statements ...
   -- + restore `Anyone can read avatars` policy
   -- + restore prior `comments` SELECT policy with the share-token arm
   ```
4. Verify the reverse migration is syntactically valid: paste it into the SQL Editor with a leading `BEGIN; ... ROLLBACK;` so the changes are not committed.
5. If the reverse migration trips on a constraint or dependency you forgot about, that's the lesson — update the original migration's "Rollback plan" comment block with the corrected reverse.
6. Discard the drill output. Do NOT commit a reverse migration unless an actual rollback is needed.

## PITR procedure

When forward-fix is not sufficient and a real PITR restore is needed:

1. Identify the target time. Look at the migration's apply timestamp in `supabase_migrations.schema_migrations.version` (or the Dashboard Migrations page) — restore to **1 minute before** that timestamp to ensure the bad migration is excluded.
2. Decide on the data-loss boundary. Every write between the target time and the current moment will be lost. For pre-alpha there is no user data to lose; for post-alpha, communicate the loss window to affected users.
3. Open Supabase Dashboard → Database → Backups → Point-in-Time Recovery.
4. Pick the target timestamp.
5. Click "Restore" — Supabase initiates an in-place restore.
6. **WAIT** — the project is unavailable during restore. For a small DB the wait is minutes; for a larger DB it can be longer.
7. After restore, verify:
   - The bad migration is NOT in `supabase_migrations.schema_migrations`.
   - Affected schema state matches pre-migration expectation.
   - Application traffic resumes correctly.
8. Author a corrected forward migration if the original intent was right but the implementation was wrong; if the original intent was wrong, archive it without re-applying.

## When to choose forward-fix vs PITR

| Symptom | Choose |
|---|---|
| Schema is wrong but no production data has flowed through the wrong schema | Forward-fix |
| Wrong RLS policy was live but you can prove no leaked reads occurred | Forward-fix |
| Wrong RLS policy was live AND leaked reads may have occurred | PITR — restore + post-mortem |
| DROP TABLE on a table with rows that mattered | PITR |
| DROP COLUMN on a column whose data mattered | PITR |
| Function dropped or replaced incorrectly, no data implication | Forward-fix |
| `ALTER COLUMN ... TYPE` truncated values | PITR |
| Bad data-only migration deleted user rows | PITR |

When in doubt, **PITR first** then apply forward-fix on top. PITR is a 10-minute operation; trying to reconstruct lost data from logs is unbounded.

## Cross-reference

- [release.md](release.md) — app-release operations (separate from DB migrations)
- [secrets-rotation.md](secrets-rotation.md) — secret rotation (no DB-rollback connection)
- [incident-playbook.md](incident-playbook.md) — symptom-indexed failure modes
- [supabase/migrations/](../../../supabase/migrations/) — the migration history itself
- [pre-alpha-checklist.md §27.2](pre-alpha-checklist.md) — pre-alpha audit item A18 closure

## Update history

| Date | Change | By |
|---|---|---|
| 2026-05-12 | Initial runbook — pre-alpha audit item A18 | b150005 |
