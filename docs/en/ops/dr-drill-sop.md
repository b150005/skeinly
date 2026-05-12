# Runbook — Disaster Recovery (DR) Drill

> JA: [docs/ja/ops/dr-drill-sop.md](../../ja/ops/dr-drill-sop.md)

How to recover from a non-migration data-loss incident on the prod Supabase project (accidental destructive SQL, Dashboard compromise, faulty admin action). Distinct from [migration-rollback.md](migration-rollback.md), which covers migration-induced rollback.

## RTO / RPO targets

| Metric | Target | Rationale |
|---|---|---|
| **RTO** (Recovery Time Objective) | ≤ 1 hour | Operator notices incident → starts restore → service restored within 1 hour. Bounded by Supabase PITR restore time (typically 5–15 minutes for the Skeinly DB size) plus 30–45 minutes of operator response + verification overhead. |
| **RPO** (Recovery Point Objective) | ≤ 5 minutes | Data loss bounded to 5 minutes by Supabase PITR granularity. Anything written in the 5 minutes leading up to the incident is at risk of being lost. |

These targets apply to closed alpha (≤10 testers). Tighten for post-GA when scale demands.

## Prerequisites

1. Supabase project on **Pro tier or higher** (Free tier has daily snapshot only — no PITR). Verify via Dashboard → Project Settings → General. As of 2026-05-12 this is **user-side action V17** — confirm before alpha launch.
2. PITR retention window: 7 days (Pro) / 14 days (Team) / 28 days (Enterprise). Alpha runs on Pro = 7-day window. Any incident discovered later than 7 days post-occurrence cannot be PITR-recovered.
3. Operator has Owner role on the Supabase project (Dashboard access + SQL Editor privilege).

## DR scenarios — drill catalog

These are the scenarios to walk through during the pre-alpha DR drill. Each one represents a real failure mode that has happened to other projects; running the drill once means you have the muscle memory before it happens to you.

### Scenario 1: Accidental `DELETE` without `WHERE`

Operator runs `DELETE FROM patterns WHERE owner_id = 'abc'` in SQL Editor, but the WHERE clause was incomplete — the actual SQL executed was `DELETE FROM patterns` (someone selected only the first half by accident).

**Symptom**: All rows in `patterns` are gone. App Discovery feed empty. Cascade: all child `chart_documents` / `chart_versions` / etc. also gone.

**Recovery**: PITR restore to 1 minute before the bad query. Verify via post-restore SELECT that `patterns` is populated.

### Scenario 2: `DROP TABLE` on a live table

Operator drops a table thinking it's the staging copy when it's actually prod.

**Symptom**: App-side queries against the table return `relation does not exist` errors. Sentry / app logs flood with 500s.

**Recovery**: PITR restore.

### Scenario 3: Faulty migration

A migration file is committed and applied to prod but contains an unintended destructive statement (e.g., `DROP COLUMN` on a column that was supposed to be renamed).

**Symptom**: App-side queries selecting the dropped column return errors. Existing rows have lost data in that column.

**Recovery**: PITR restore. See also [migration-rollback.md](migration-rollback.md) for migration-specific recovery logic.

### Scenario 4: Account compromise — Dashboard access taken over

An attacker obtains the Owner's Supabase Dashboard credentials and runs `DROP TABLE`, `TRUNCATE`, or `DELETE` on tables.

**Symptom**: Same as scenarios 1–3 + suspicious activity in `audit logs` (Supabase Dashboard → Project Settings → Audit Logs).

**Recovery**:
1. Rotate Supabase Owner credentials (password + any leaked PAT tokens) BEFORE restore — restoring while attacker still has access lets them redo the damage.
2. Review the Dashboard Audit Log for the full extent of attacker activity (other tables touched, RLS policies edited, secrets reset).
3. PITR restore to 1 minute before the earliest attacker action.
4. Force-rotate all secrets stored in Supabase that might have been exposed (Edge Function secrets, RevenueCat / GitHub App / FCM / APNs trio per `secrets-rotation.md`).
5. File a post-incident report in `docs/en/ops/incident-playbook.md`.

### Scenario 5: RLS policy bug allows mass overwrite

A new RLS policy has an OR clause that accidentally allows authenticated user A to UPDATE user B's rows. Mass overwrite is discovered.

**Symptom**: User reports they see foreign content in their projects, or data integrity check via SQL Editor reveals row-version drift.

**Recovery**:
1. Diagnose first (do NOT panic-restore — restoring with the broken policy still live lets the damage recur). Open SQL Editor:
   ```sql
   -- Find policy:
   SELECT polname, pg_get_expr(polqual, polrelid) FROM pg_policy
   WHERE polrelid = 'public.<table>'::regclass;
   ```
2. DROP / fix the policy (forward-fix migration).
3. PITR restore to 1 minute before the policy was applied (or before the first mass-overwrite event).
4. Apply the corrected policy after restore.

## Drill procedure (run quarterly, first time pre-alpha)

1. **Schedule**: pick a low-traffic time (pre-alpha = any time; post-alpha = off-peak hours).
2. **Notify**: write a one-line message in the operator's private notebook: "DR drill 2026-MM-DD HH:MM — exercising scenarios N, N, N."
3. **Setup**: create a throwaway side-project on Supabase (free tier is fine for drill purposes) OR use the staging project if one exists. **Never run a drill against prod that involves destructive SQL.**
4. **Walk through each scenario**:
   1. Read the scenario steps above.
   2. Simulate the disaster (e.g., `DELETE FROM patterns` on the staging project).
   3. Note the timestamp of the simulated disaster.
   4. Open Dashboard → Database → Backups → Point-in-Time Recovery.
   5. Pick a timestamp 1 minute before the simulated disaster.
   6. Click Restore.
   7. Wait for restore to complete; time-stamp it.
   8. Verify via SELECT that the staging project's data matches pre-disaster state.
   9. Note RTO (time from simulated disaster → restore complete + verified).
5. **Compute aggregate RTO**: average across the 5 scenarios. If average > 1 hour, refine the runbook (clearer steps, pre-staged credentials, etc.) and re-drill.
6. **Document outcomes**: append a row to the drill log table below in this runbook.

## Drill log

| Date | Operator | Scenarios drilled | Average RTO | Notes / refinements |
|---|---|---|---|---|
| _Not yet drilled_ | — | — | — | First drill scheduled pre-alpha launch (target: 2026-05-XX). |

## PITR procedure (in-prod, during real incident)

When a real incident occurs (not a drill):

1. **Stay calm, stop the bleeding first**: if the incident is ongoing (attacker still in session, bad SQL running on a cron, etc.), terminate the source BEFORE attempting restore.
   - For Dashboard compromise: rotate credentials + revoke active sessions via Supabase → Project Settings → Security.
   - For runaway SQL: kill the connection via `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE state = 'active' AND pid <> pg_backend_pid();` (Dashboard SQL Editor).
   - For bad migration mid-rollout: see [migration-rollback.md](migration-rollback.md).

2. **Identify the target restore timestamp**: trace the incident's start time from logs (Sentry, PostHog, Supabase audit logs, application telemetry). Choose target = (incident start) − 1 minute as the safety margin.

3. **Communicate downtime**: Skeinly has no in-app status page (alpha scale). Notify alpha testers via email or the closed-tester Slack/Discord channel if one exists.

4. **Trigger the restore**:
   - Supabase Dashboard → Database → Backups → Point-in-Time Recovery → pick timestamp.
   - Click Restore. The project will be **read-only and then unavailable** during the restore window.
   - Restore time is typically 5–15 minutes for the Skeinly DB size at alpha.

5. **Verify after restore**:
   - SELECT counts on affected tables match pre-incident expectations.
   - Test a representative app flow (sign in → load Discovery → open a pattern) on a TestFlight build.
   - Verify no migrations were "un-applied" by the restore: `SELECT version FROM supabase_migrations.schema_migrations ORDER BY version DESC LIMIT 5;` should match the pre-incident state.

6. **Apply forward-fixes** (if needed): if the restore reverted a corrected migration that was needed AFTER the incident, re-apply it now. If the restore reverted a faulty migration that caused the incident, do NOT re-apply.

7. **Post-incident report**: append an entry to `docs/en/ops/incident-playbook.md`:
   - Incident date / time
   - Symptom + how detected
   - Root cause
   - Recovery action taken (restore target time, restore duration, RTO, RPO)
   - Lessons learned + runbook refinements

## Daily-snapshot fallback (Free tier only)

If V17 was NOT done and the project is still on Free tier when an incident strikes:

1. Supabase Dashboard → Database → Backups → "Restore from daily backup".
2. Latest daily snapshot is the only target; data loss bounded to 24 hours.
3. RPO = 24 hours (worst case), much worse than the Pro PITR 5-minute target.

**Conclusion**: V17 (upgrade to Pro) is effectively mandatory before alpha. The $25/month Pro tier cost is trivial against the unrecoverable data loss risk on Free tier.

## What this runbook does NOT cover

- **Migration-induced rollback** — see [migration-rollback.md](migration-rollback.md).
- **App-side incidents** (Crash storm, ANR spike, push failure cascade) — see [incident-playbook.md](incident-playbook.md).
- **Secret-leak rotation procedures** — see [secrets-rotation.md](secrets-rotation.md).
- **Storage-only data loss** (Supabase Storage files deleted but DB intact). Storage is not covered by PITR; lost Storage objects are unrecoverable. Mitigation: app uploads avatars / chart images to user-owned folders; loss is per-user not global.

## Cross-reference

- [migration-rollback.md](migration-rollback.md) — migration rollback (different incident class)
- [secrets-rotation.md](secrets-rotation.md) — secret rotation procedures
- [incident-playbook.md](incident-playbook.md) — symptom-indexed failure modes (mostly app-side)
- [pre-alpha-checklist.md §26](pre-alpha-checklist.md) — A17 closure record
- [Supabase PITR docs](https://supabase.com/docs/guides/platform/backups#point-in-time-recovery)

## Update history

| Date | Change | By |
|---|---|---|
| 2026-05-12 | Initial DR drill runbook — pre-alpha audit item A17 | b150005 |
