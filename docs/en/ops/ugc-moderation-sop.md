# UGC Moderation — Operator SOP

> Source of truth (English). Japanese mirror: [docs/ja/ops/ugc-moderation-sop.md](../../ja/ops/ugc-moderation-sop.md).

Operational runbook for processing UGC reports filed via the `submit-ugc-report` Edge Function (ADR-021 §D5). Closes pre-alpha checklist items **A1** (UGC moderation) and **A5** (Block User mechanism foundation).

## Scope

This SOP covers the **foundation slice** shipped at pre-alpha:

- Data spine: `public.ugc_reports` + `public.user_blocks` (migration 031).
- RLS-level block filter (migration 032).
- Reporting via `submit-ugc-report` Edge Function → GitHub Issue mirror on [b150005/skeinly](https://github.com/b150005/skeinly) with label `ugc-report`.
- Operator-mediated triage via Supabase Dashboard SQL Editor.

The in-app Report button + Block User UI + Blocked Users list ship at pre-Phase-40 GA (ADR-021 §D4). Until then, alpha testers email `skeinly.app@gmail.com` to report objectionable content; the operator runs the SOP on their behalf.

## SLA

**24 hours** from report receipt to resolution decision. Apple Guideline 1.2 sets this floor for "objectionable content removal"; we adopt it for every report regardless of category.

## When a report lands

Trigger: a new GitHub Issue appears on [b150005/skeinly with label `ugc-report`](https://github.com/b150005/skeinly/issues?q=is%3Aopen+label%3Augc-report).

The Issue body follows this shape (rendered by `submit-ugc-report/mapping.ts`):

```text
## UGC report

**Report ID**: `<UUID>`
**Reporter**: `<UUID>`
**Target**: `<target_type>` / `<target_id>`
**Reason category**: `<category>`

### Reason

<first 500 chars of reason; truncated with annotation if longer>

---

Operator triage: see docs/en/ops/ugc-moderation-sop.md.

Resolve target_id to its row in the Dashboard SQL Editor:
```sql
SELECT * FROM public.<target_type>s WHERE id = '<target_id>';
```

When resolved, UPDATE public.ugc_reports SET state = 'resolved_remove' | 'resolved_keep' | 'dismissed', operator_notes = '…', resolved_at = now() WHERE id = '<report_id>';
```

### Step 1 — Acknowledge

Within 1 hour of the Issue landing (or first thing in the morning if filed overnight):

1. Add the `triaging` label to the GitHub Issue.
2. UPDATE the DB row state to track in-flight handling:
   ```sql
   UPDATE public.ugc_reports
   SET state = 'triaging',
       operator_notes = 'Triage started <YYYY-MM-DD HH:MM> JST.'
   WHERE id = '<report_id>';
   ```

### Step 2 — Resolve the target

Read the full Issue body and run the embedded Dashboard SQL stub:

```sql
SELECT * FROM public.<target_type>s WHERE id = '<target_id>';
```

The `<target_type>s` mapping (pluralized table name):

| target_type | Table |
|---|---|
| `pattern` | `public.patterns` |
| `comment` | `public.comments` |
| `suggestion` | `public.suggestions` |
| `suggestion_comment` | `public.suggestion_comments` |

If the row no longer exists (deleted before triage), record the absence and dismiss the report — no further action possible. See "Edge cases" below.

### Step 3 — Read the full reason

The Issue body shows only the first 500 chars. For the full reason text:

```sql
SELECT reason FROM public.ugc_reports WHERE id = '<report_id>';
```

### Step 4 — Decide

Pick one of three resolutions per the category + content:

#### resolved_remove — the content violates policy

Per Apple Guideline 1.2 we remove or hide the offending row.

- **Pattern**: set `visibility = 'private'` (not DELETE — preserve evidence for appeal).
  ```sql
  UPDATE public.patterns SET visibility = 'private' WHERE id = '<target_id>';
  ```
- **Comment / suggestion / suggestion_comment**: DELETE the row (comments don't have a `visibility` field).
  ```sql
  DELETE FROM public.comments WHERE id = '<target_id>';
  -- or suggestions / suggestion_comments
  ```

Then close the report:

```sql
UPDATE public.ugc_reports
SET state = 'resolved_remove',
    operator_notes = 'Hidden / removed on <YYYY-MM-DD>. Reason: <one-line summary>.',
    resolved_at = now()
WHERE id = '<report_id>';
```

#### resolved_keep — false-positive, content is acceptable

Reporter misinterpreted the content, or the content does not actually violate policy.

```sql
UPDATE public.ugc_reports
SET state = 'resolved_keep',
    operator_notes = 'Reviewed <YYYY-MM-DD>. Content does not violate policy because <one-line reasoning>.',
    resolved_at = now()
WHERE id = '<report_id>';
```

#### dismissed — abusive report

Reporter is filing fake / harassing reports against a target user. Track the reporter UUID; multiple `dismissed` reports from the same UUID = likely problem reporter.

```sql
UPDATE public.ugc_reports
SET state = 'dismissed',
    operator_notes = 'Dismissed <YYYY-MM-DD> — apparent false report. Reporter abuse pattern check follows.',
    resolved_at = now()
WHERE id = '<report_id>';

-- Audit the reporter's history:
SELECT id, state, reason_category, target_type, created_at
FROM public.ugc_reports
WHERE reporter_id = '<reporter_uuid>'
ORDER BY created_at DESC;
```

If the reporter has ≥3 `dismissed` reports in the last 30 days, escalate: consider account suspension, or at minimum cap their report submissions (manually, by deleting their rate-limit-relevant evidence).

### Step 5 — Close the GitHub Issue

Add a closing comment referencing the resolution:

```text
Closed: <state> — see ugc_reports.id <report_id>.
<one-line summary matching operator_notes>
```

Then close the Issue and remove the `triaging` label.

### Step 6 — Optional notification to reporter

For `resolved_remove` only, the operator MAY email the reporter to acknowledge the action (this is a courtesy, not a contractual obligation in pre-alpha). Get the reporter email:

```sql
SELECT u.email
FROM auth.users u
WHERE u.id = '<reporter_uuid>';
```

For `resolved_keep` and `dismissed`, NO notification — explaining why a report was rejected often invites argument and is not productive at the alpha scale.

## Block User — operator-mediated

A tester emails `skeinly.app@gmail.com` requesting a block of `<blocked-display-name>` because of repeated bad interactions.

### Resolve the blocked user

```sql
-- Resolve the requesting tester (the blocker) to UUID:
SELECT id FROM auth.users WHERE email = '<blocker-email>';
-- Resolve the target user (the blocked) — usually by display_name:
SELECT id FROM public.profiles WHERE display_name = '<blocked-display-name>';
-- If display_name is ambiguous, narrow by recent interactions visible
-- to the blocker (e.g. comment authors on the blocker's patterns):
SELECT DISTINCT c.author_id, p.display_name
FROM public.comments c
JOIN public.patterns pat ON pat.id = c.target_id AND c.target_type = 'pattern'
JOIN public.profiles p ON p.id = c.author_id
WHERE pat.owner_id = '<blocker-uuid>';
```

### Insert the block

```sql
INSERT INTO public.user_blocks (blocker_id, blocked_id)
VALUES ('<blocker-uuid>', '<blocked-uuid>')
ON CONFLICT DO NOTHING;
```

Migration 031 enforces `blocker_id <> blocked_id` and FK cascade on `auth.users` deletion (ADR-005 account deletion path stays clean). Migration 032's RLS NOT-EXISTS arm immediately kicks in — the blocked user's content disappears from the blocker's queries on patterns / comments / suggestions / suggestion_comments.

### Email confirmation back to the tester

> "Block applied: <blocked-display-name> will no longer appear in your Discovery or Suggestion threads. To unblock later, email skeinly.app@gmail.com — at GA, the Blocked Users list ships in Settings → Privacy."

## Monitoring queries

Daily / weekly operator dashboard queries:

```sql
-- Outstanding reports (oldest first — closest to SLA breach):
SELECT id, target_type, reason_category, created_at,
       extract(epoch FROM (now() - created_at))/3600 AS hours_open
FROM public.ugc_reports
WHERE state IN ('open', 'triaging')
ORDER BY created_at ASC;

-- Reports filed but the GitHub Issue mirror failed (Edge Function
-- best-effort fallback per ADR-021 §D3):
SELECT id, target_type, reason_category, created_at
FROM public.ugc_reports
WHERE github_issue_url IS NULL AND state = 'open'
ORDER BY created_at ASC;

-- Block list size (sanity check — large numbers may indicate a brigading
-- attempt against a single user):
SELECT blocked_id, count(*) AS times_blocked
FROM public.user_blocks
GROUP BY blocked_id
HAVING count(*) >= 3
ORDER BY times_blocked DESC;
```

## Edge cases

### The target row no longer exists

The reporter filed against a row that was deleted before triage. The Edge Function does not validate target_id existence at submission (intentional — see `mapping.ts` `validateInput` rationale). Resolve as `dismissed` with operator_notes "Target row no longer exists at triage time."

### Report against a row I (operator) own

This can happen if the operator is also a tester. Recuse: resolve as `dismissed` with operator_notes referencing the conflict of interest. Future GA may add a moderator-pool of 2+ to handle this without recusal.

### Same content reported multiple times

The `idx_ugc_reports_target` index supports this query:

```sql
SELECT * FROM public.ugc_reports
WHERE target_type = '<t>' AND target_id = '<id>'
ORDER BY created_at ASC;
```

Resolve the first report on the merits. Subsequent reports for the same content can be closed as `resolved_keep` if the first was `resolved_keep` (point at the prior report_id in operator_notes), or `resolved_remove` references the already-removed content. No re-investigation needed.

### Blocked-then-unblocked cycles

Each unblock is a DELETE on `public.user_blocks`. The RLS filter sees the absence of the row and immediately restores visibility — there is no "soft block" intermediate state. If a tester reports cyclic block/unblock abuse, document the pattern in operator_notes on the original report; the GA UI may need a cool-down period before unblock takes effect.

## Compliance posture (Apple Guideline 1.2 / Google Play UGC policy)

| Requirement | Status | Notes |
|---|---|---|
| Filter / moderation mechanism | ✅ (this SOP + RLS filter) | Operator-mediated at pre-alpha; automated keyword filter is a post-GA reconsideration if volume justifies (ADR-021 §A3). |
| Report mechanism | ✅ (foundation) | Edge Function + GitHub Issue triage. In-app Report button at GA. |
| Block User | ✅ (foundation) | Data model + RLS filter live. Operator INSERTs on tester request. In-app UI at GA. |
| Published contact | ✅ | `skeinly.app@gmail.com` visible in Privacy / ToS / Help / Settings / web account-deletion / app footer. |

## References

- [ADR-021 — Pre-Alpha UGC Moderation](../adr/021-pre-alpha-ugc-moderation.md)
- [Migration 031 — `ugc_reports` + `user_blocks`](../../../supabase/migrations/031_ugc_moderation.sql)
- [Migration 032 — RLS block filter](../../../supabase/migrations/032_ugc_block_filter.sql)
- [Edge Function `submit-ugc-report`](../../../supabase/functions/submit-ugc-report/README.md)
- [Apple App Store Review Guideline 1.2 — User Generated Content](https://developer.apple.com/app-store/review/guidelines/#user-generated-content)
- [Google Play UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937)

## Revision history

| Date | Change | Author |
|---|---|---|
| 2026-05-12 | Initial — foundation slice (pre-alpha A1+A5 closure) | b150005 |
