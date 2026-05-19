# ADR-027: UGC Moderation Triage Automation via GitHub Issue Webhook

## Status

Proposed (2026-05-19). Operator approval pending. Implementation phased in a separate worker batch (Z2 placeholder; doc-only Z1 lands this ADR, ADR-021 §D5 cross-link, and JA mirrors).

## Context

ADR-021 §D5 — "Operator triage workflow (foundation, pre-alpha)" — codifies the current UGC report triage flow as **manual dual-sync** between Supabase and GitHub:

1. A UGC report Issue lands on `b150005/skeinly` with label `ugc-report` (posted by Edge Function `submit-ugc-report` per ADR-021 §D3).
2. The operator opens the Issue, resolves `target_id` to the offending row via Supabase Dashboard SQL Editor.
3. Within Apple's 24-hour SLA for objectionable content (Apple App Store Review Guideline 1.2), the operator decides one of three resolutions: `resolved_remove` / `resolved_keep` / `dismissed`.
4. The operator manually `UPDATE public.ugc_reports SET state = '<resolution>', operator_notes = '…', resolved_at = now() WHERE id = '<report_id>';` in Dashboard SQL Editor.
5. The operator manually closes the GitHub Issue with a one-line summary referencing the `ugc_reports.id`.

The detailed runbook lives in `docs/en/ops/ugc-moderation-sop.md` (Steps 1–5 in that file plus an optional Step 6 reporter-courtesy email). The flow is correct at pre-alpha low volume, but the dual-sync carries three structural risks that automation can eliminate independent of volume:

- **Human-error mode**: forgetting the SQL UPDATE, forgetting to close the Issue, typoing the state value (`'resolved-remove'` vs `'resolved_remove'`), or transposing operator_notes between two simultaneous triages. Each failure mode silently breaks the SLA accounting that the runbook depends on for compliance posture.
- **No structural SLA guarantee**: the 24-hour Apple SLA is measured against `resolved_at`, which is the operator's `SET resolved_at = now()` value. If the UPDATE is delayed or missed, SLA monitoring queries (`docs/en/ops/ugc-moderation-sop.md` `## Monitoring queries`) read stale `resolved_at IS NULL` and the report appears open past 24 hours when the *actual* moderation decision already shipped on the GitHub side. The mismatch surfaces only on audit.
- **Volume-independent automation value**: ADR-021 §D5 closes with "If volume grows past ~10 reports per week, revisit." That threshold framing is about *moderator-pool scale-out* (build a dashboard or contract a moderator). The automation value addressed in this ADR is **orthogonal**: even at 1 report/week the structural human-error guard + audit-trail auto-population pays back. We adopt automation at the current volume; the moderator-pool threshold from §D5 still applies independently.

ADR-027 evolves ADR-021 §D5 by **automating the dual-sync** in one direction: GitHub Issue close (the operator's *natural* terminal action on the Issue) triggers a Supabase Edge Function that performs the corresponding `ugc_reports.state` UPDATE. The manual SQL UPDATE path stays available as a fallback when the webhook is unavailable.

This is a new ADR (not an in-place amendment to ADR-021 §D5) because the pre-alpha low-volume "manual is fine" judgment in §D5 is itself an artifact worth preserving — the dual-sync friction is a finding *after* §D5 shipped, not a flaw in §D5's original reasoning. Keeping §D5 intact + cross-linking to ADR-027 keeps both decisions auditable. The ADR-021 Revision history records the cross-link addition; this ADR carries the new design.

## Decision

### D1 — GitHub Webhook configuration (repo-level)

GitHub webhook subscription is at **repository level**, registered at `https://github.com/b150005/skeinly/settings/hooks`. This is operator-confirmed (2026-05-19) and discussed against the alternative in §A1 below.

| Setting | Value |
|---|---|
| Payload URL | `<SUPABASE_FUNCTIONS_URL>/github-webhook` |
| Content type | `application/json` |
| Secret | `GITHUB_WEBHOOK_SECRET` (new; 32+ byte hex; HMAC SHA-256 source) |
| SSL verification | Enabled (default) |
| Events | `Let me select individual events.` → `Issues` only (no `Issue comment`, no `Pull request`, nothing else) |
| Active | ✅ |

The repo-level scope:

- Keeps the existing "Skeinly Feedback" GitHub App (ADR-020 §D6 step 4) configured with `Webhook section → uncheck Active`. The App is the *outbound* (Supabase → GitHub) path for `submit-bug-report` + `submit-ugc-report`; this webhook is the *inbound* (GitHub → Supabase) path for triage automation. The two concerns stay on separate config surfaces with disjoint secrets.
- Limits the webhook subscription to one repo (`b150005/skeinly`) explicitly. ADR-020 §Q2 already established that single-repo concentration is the boundary for UGC + bug-report flows.
- Lets the secret rotate independently from the App's PEM. Rotation procedure: generate new hex → register on GitHub webhook page → register on Supabase Edge Function secrets → redeploy `github-webhook` → delete old value on GitHub. PEM secret rotation (ADR-020 referenced as "annual private-key rotation") is untouched.

The `Issues` event scope means GitHub posts on every issue lifecycle action (`opened` / `edited` / `closed` / `reopened` / `labeled` / `assigned` / etc.). The Edge Function filters to `action === 'closed'` on the function side. This is intentional rather than subscribing to `Issue closed` only because GitHub's event payload reuses the same shape across actions, and shipping a single subscription is simpler runbook-wise than maintaining N narrow ones.

### D2 — Edge Function `github-webhook` (Deno)

Registered in `supabase/config.toml` as:

```toml
[functions.github-webhook]
verify_jwt = false
```

`verify_jwt = false` matches the ADR-020 §Q4 precedent rationale: the caller (GitHub Webhook Delivery) has no Supabase Auth context, so a Supabase-layer JWT check would either reject every legitimate delivery or be performative. Real authentication is the **HMAC SHA-256 signature** on the `X-Hub-Signature-256` header, verified against `GITHUB_WEBHOOK_SECRET` inside the function. This is the same shape as `notify-on-write` and `revenuecat-webhook` from earlier ADRs (third-party-credential-bearing Edge Functions; verify_jwt = false; real auth downstream-or-shared-secret).

#### Code structure

```
supabase/functions/github-webhook/
├── index.ts        — Deno.serve handler: HMAC verify → event filter → payload validate → state map → DB UPDATE
├── hmac.ts         — X-Hub-Signature-256 verification (timing-safe compare against HMAC-SHA-256 of raw body)
├── mapping.ts      — Issue label → ugc_reports.state closed-enum map (see D3)
├── _fakes.ts       — globalThis.fetch + Supabase service-role client mocks for Deno tests
├── index.test.ts   — handler suite (HMAC pass/fail, event filter, payload shapes, idempotency)
├── deno.json       — runtime + test config
└── README.md       — deploy + smoke-test recipe + secret-rotation procedure
```

This shape mirrors `submit-bug-report/` and `submit-ugc-report/` so the Edge Function fleet stays uniform.

#### Request flow

```
┌────────────┐  POST /functions/v1/github-webhook
│ GitHub     │  Content-Type: application/json
│ Webhook    │  X-Hub-Signature-256: sha256=<hex HMAC of raw body>
│ Delivery   │  X-GitHub-Event: issues
└─────┬──────┘  X-GitHub-Delivery: <UUID>
      │         { action, issue, repository, sender, … }
      ▼
┌─────────────────────────────────────────────────────┐
│ Edge Function github-webhook (verify_jwt = false)   │
│                                                     │
│  1. Read raw body (Uint8Array; signature is over    │
│     the raw bytes, NOT JSON-reparsed bytes).        │
│  2. HMAC-SHA-256(GITHUB_WEBHOOK_SECRET, body) →     │
│     constant-time compare against X-Hub-            │
│     Signature-256 tail. Mismatch ⇒ 401, drop.       │
│  3. JSON.parse(body). action !== 'closed' ⇒ 200     │
│     {ok: true, code: 'IGNORED_EVENT'} and stop.     │
│  4. Validate payload (see "Payload validation"      │
│     below). Validation fail ⇒ 200 {ok: false,       │
│     code: '<reason>'} and stop. (GitHub does NOT    │
│     re-deliver on 2xx.)                             │
│  5. Map decision label → ugc_reports.state value.   │
│  6. UPDATE public.ugc_reports SET state = $1,       │
│     resolved_at = $2, operator_notes = $3 WHERE     │
│     id = $4 AND resolved_at IS NULL.                │
│     (Service-role client; idempotent + no-clobber.) │
│  7. Return 200 {ok: true, code: 'UPDATED',          │
│     ugc_report_id, new_state}.                      │
└─────────────────────────────────────────────────────┘
```

The function always returns HTTP 200 to GitHub. Application-level failures are encoded in the response body (`ok: false, code: '<enum>'`). Non-200 is reserved for Supabase-platform issues that would benefit from GitHub's webhook-redelivery mechanism. The trade-off: GitHub will *not* retry on `code: 'INVALID_HMAC'` etc., so debugging relies on the function logs + GitHub's webhook delivery history page. This is the right default — most validation failures indicate misconfiguration or a malformed (likely malicious) delivery, not a transient error.

#### Payload validation

Run *after* HMAC verify passes:

| Check | Failure code | Outcome |
|---|---|---|
| `action === 'closed'` | `IGNORED_EVENT` | 200, no DB write |
| `issue.labels` includes `ugc-report` (parent label set by `submit-ugc-report`) | `NOT_UGC_REPORT` | 200, no DB write |
| `issue.body` matches `/Report ID:\s*`([0-9a-f-]{36})`/i` (UUID extract; see SOP body template) | `MISSING_REPORT_ID` | 200, no DB write + log |
| `issue.labels` contains exactly one of the three decision labels (`state-resolved-remove` / `state-resolved-keep` / `state-dismissed`) | `MISSING_DECISION_LABEL` (0) / `AMBIGUOUS_DECISION_LABEL` (≥2) | 200, no DB write + log |
| `ugc_reports` row exists with id = extracted UUID | `REPORT_NOT_FOUND` | 200, no DB write + log |

The `MISSING_REPORT_ID` and `REPORT_NOT_FOUND` cases preserve the manual fallback: the operator can still UPDATE the row by hand from Dashboard SQL Editor without the webhook auto-completing. Logging makes diagnosis a function-logs grep instead of a silent failure.

#### Idempotency

The UPDATE statement carries `AND resolved_at IS NULL`. If the operator already manually-set `resolved_at` (per ADR-021 §D5 step 4), the webhook UPDATE is a no-op. The reverse — webhook lands first, operator later tries to manual-set — also no-ops because `resolved_at` is no longer NULL. There is no "last-write wins" race; the first writer (auto or manual) is canonical.

#### Operator_notes auto-population

The function writes a deterministic operator_notes string:

```
Auto-resolved on <issue.closed_at ISO 8601> by github-webhook (delivery <X-GitHub-Delivery>). Label: <state-resolved-…>. GitHub Issue: #<issue.number>.
```

This preserves the audit trail in the Supabase row without depending on the operator re-typing the closing-comment text into `operator_notes`. The SOP can be updated (Z2) to record only *additional* operator context (e.g. recusal reasoning) when needed.

### D3 — Decision mapping (label-based)

The operator signals their decision by applying **exactly one** of three new GitHub labels to the Issue before closing:

| GitHub label | `ugc_reports.state` value | Maps to SOP Step 4 outcome |
|---|---|---|
| `state-resolved-remove` | `'resolved_remove'` | Content was removed (pattern → `visibility = 'private'`, or comment/suggestion → DELETE) |
| `state-resolved-keep` | `'resolved_keep'` | Report was false-positive; content stays |
| `state-dismissed` | `'dismissed'` | Report itself was abusive / false; reporter pattern check follows |

The `state-` prefix disambiguates from the existing `triaging` label (ADR-021 §D5 step 1: operator applies `triaging` when handling starts) and the existing `ugc-report` parent label. Alternatives evaluated: `triage-*` would collide naming with `triaging`; `resolution-*` is verbose. The chosen prefix is short, alphabetizes cleanly in the GitHub Labels picker, and groups in the autocomplete list.

The operator-side workflow becomes:

1. Issue lands with `ugc-report` label → operator opens, runs SQL to inspect target.
2. Operator applies `triaging` label (existing behavior; not automated by this ADR).
3. Operator decides (`resolved_remove` / `resolved_keep` / `dismissed`).
4. Operator picks the matching `state-…` label.
5. Operator does the *content action* manually: SQL to set `patterns.visibility = 'private'` or DELETE the offending row. (This step stays manual — see §Out of scope.)
6. Operator closes the Issue.
7. Webhook fires → Edge Function UPDATEs `ugc_reports.state` + `resolved_at` + auto-`operator_notes`.

Steps 1, 2, 3, 5 are unchanged from ADR-021 §D5. Step 4 is new. Step 6 is unchanged. Step 7 is the new automation. If the operator forgets step 4 (no `state-…` label) or accidentally applies two (`state-resolved-keep` + `state-dismissed` simultaneously), the webhook logs `MISSING_DECISION_LABEL` or `AMBIGUOUS_DECISION_LABEL` and *does not* UPDATE; the operator can re-open the Issue, fix the labels, and re-close to retrigger the webhook, or fall back to manual SQL.

The comment-keyword alternative (operator types `resolved_remove` in the closing comment) is rejected in §A2.

### D4 — SLA accounting (reuse existing `resolved_at`)

The Edge Function writes `issue.closed_at` (from the webhook payload; ISO 8601 string the function parses to TIMESTAMPTZ) into the existing `ugc_reports.resolved_at` column from migration 031. **No new column is introduced; no new migration is required.**

This is operator-decided 2026-05-19 via agent-team deliberation (architect / security-reviewer / code-reviewer / implementer / product-manager voices), settling against the alternative of a new `state_resolved_at` column. Rationale captured in `Implementation notes for Z2` below.

SLA monitoring queries (`docs/en/ops/ugc-moderation-sop.md` `## Monitoring queries`) already use `resolved_at`; the auto-write keeps them working unchanged. The Z2 sop.md update can add a SLA-compliance query along the lines of:

```sql
-- SLA compliance window: resolution within 24 hours
SELECT id, state, created_at, resolved_at,
       EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600 AS hours_to_resolve
FROM public.ugc_reports
WHERE resolved_at IS NOT NULL
ORDER BY created_at DESC;
```

The `AND resolved_at IS NULL` guard in the UPDATE (D2) prevents the webhook from clobbering a manual entry. If the operator manually set `resolved_at` via Dashboard SQL Editor before the webhook fires (rare but possible if the webhook is delayed or the operator pre-empted it), the manual value wins.

### D5 — Backward-compatible fallback

ADR-021 §D5 stays runnable end-to-end as the fallback path. The webhook is a best-effort automation layer; every operator action it automates can still be performed manually. Failure modes that surface the fallback:

1. **HMAC verification fail** — secret rotation in flight, or a malicious POST. Edge Function returns 401 (the *only* non-200 case; signaled-attack response). No UPDATE.
2. **Edge Function unavailable** — Supabase deploy in flight, Supabase regional outage, network partition. GitHub's webhook redelivery retries are bounded (8 attempts over ~14h per GitHub docs); after that the delivery is dropped and the operator must finish the dual-sync manually.
3. **Decision label missing / ambiguous** — operator forgot or double-tagged. Function logs + returns 200 IGNORED-like response; operator either retags + retriggers (re-open + re-close) or manual-SQLs.
4. **Report row deleted before triage close** (rare; ADR-021 §D5 + sop.md `## Edge cases` "target row no longer exists" — but this is about target, not report. Report deletion would require operator action; not part of normal flow.) — function returns 200 `REPORT_NOT_FOUND`; operator addresses manually.

In every fallback case, the SOP's existing instructions for steps 1–5 work without modification. The Z2 sop.md update will mark each step with "auto when webhook is reachable" / "manual fallback path" pairs, so the operator knows which mode they're in for any given report.

### D6 — Runbook

Z2 (implementation slice) ships:

- `supabase/functions/github-webhook/` per D2 structure (NEW Edge Function).
- `supabase/config.toml` registration of `[functions.github-webhook]` with `verify_jwt = false`.
- `docs/en/ops/ugc-moderation-sop.md` + JA mirror — Step 1–5 updated to mark the auto vs manual fallback path on each step; the existing 24-hour SLA wording is unchanged; an SLA-compliance monitoring query is added.
- `docs/en/ops/release-secrets.md` + JA mirror — new slot entry for `GITHUB_WEBHOOK_SECRET` with the same format as existing EF-6+ entries (slot ID, secret name, rotation procedure, registration steps).
- `docs/en/adr/021-pre-alpha-ugc-moderation.md` §D5 — Z1 lands a brief evolution-note cross-link; Z2 may detail it further if the wording needs refinement after sop.md updates.
- Migration: **not required**. The existing `resolved_at` column from migration 031 carries SLA accounting; no schema change. (See `Implementation notes for Z2`.)

Operator-attended steps (Z2):

1. On `b150005/skeinly` Settings → Webhooks → Add webhook. Configure per D1 table (URL, content-type, secret, events = Issues, Active).
2. Generate the secret (32-byte hex from `openssl rand -hex 32`) and paste it into both the GitHub webhook page **and** Supabase Edge Function secrets (`GITHUB_WEBHOOK_SECRET`). Do not commit the value to the repo.
3. On `b150005/skeinly` Issues → Labels → New label. Create three labels: `state-resolved-remove`, `state-resolved-keep`, `state-dismissed`. (The parent `ugc-report` and the existing `triaging` labels already exist.)
4. Edge Function deploy: `supabase functions deploy github-webhook` (autonomous, Skeinly side).
5. Smoke test (manual): open a throwaway UGC report Issue (or use a recent real one in a sandbox state), apply a `state-…` label, close the Issue, then verify in Dashboard SQL Editor that `ugc_reports.state` updated. The webhook delivery history page on GitHub provides the request/response trail for diagnosis if the row didn't update.

Steps 1, 2, 3 are user-attended (GitHub UI + secret handling). Step 4 is autonomous. Step 5 requires either a sandbox report or coordinating with a real report.

## Alternatives considered

### A1 — App-level webhook on Skeinly Feedback App (vs repo-level)

Rejected (operator-confirmed 2026-05-19). Configuring the "Skeinly Feedback" GitHub App's Webhook section (currently `Active = unchecked` per ADR-020 §D6) to receive these events would let the App's own secret double as the webhook secret. Pros: single secret surface; install scope auto-aligns to the App's installation (currently `b150005/skeinly` only).

Cons that drove rejection:

- ADR-020 explicitly established the App with no webhook subscription. Activating it now mixes the App's outbound concern (creating Issues on behalf of `submit-bug-report` / `submit-ugc-report`) with an inbound triage concern (UGC moderation closing). The two concerns become coupled at the App level; rotating one breaks the other.
- Bug-report Issues (ADR-020) and UGC-report Issues (ADR-021) both close, but only UGC-report closure should trigger this automation. App-level webhook would deliver both, requiring stricter event filtering and a higher false-positive rate in the function.
- Operator-side, the App config page is a different runbook surface than the repo Settings → Webhooks page. Repo-level keeps UGC triage as a repo-level concern with a repo-level config trail.

Repo-level (D1) is the chosen scope.

### A2 — Comment-keyword-based decision parsing (vs label-based)

Rejected. Alternative: operator types `resolved_remove` (or one of the three keywords) in the closing-comment text; the function regex-parses the most recent comment by the issue closer.

Pros: no separate label-application step; the operator types their notes once.

Cons:

- Typo/case variance: `resolved_remove` vs `Resolved Remove` vs `resolved-remove`. Strict parsing rejects all but one; lenient parsing reintroduces silent typo bugs.
- Multi-line comments + free-form prose make extraction brittle. The operator routinely writes summary sentences with the keyword embedded (e.g. "I'm resolving this as resolved_keep because…"), which a naive regex catches but a more conservative parser misses, leading to inconsistent behavior.
- No GitHub Labels UI affordance. Labels show up in the Issue header strip with their colors; a comment-keyword decision is buried in the comment thread and hard to spot when scanning the Issues list.
- Audit trail is split between the close event + the comment. Labels are part of the Issue's identity (the Issue's `labels` list); the resolution record stays in one place.

Label-based (D3) wins on parser stability + UI affordance + audit trail compactness.

### A3 — GitHub Actions `workflow_dispatch` + service-role direct write (instead of Edge Function)

Rejected. Alternative: a workflow on `b150005/skeinly` triggered on `issues.closed` writes directly to Supabase using the service-role key passed as a GitHub repository secret.

Pros: no Supabase Edge Function to deploy or maintain; runner spin-up is < 10 s on average; GitHub-side end-to-end visibility.

Cons:

- Supabase service-role key becomes a GitHub repository secret. The blast radius if the secret leaks expands from "Supabase function internals" to "any workflow that can read repo secrets" — including `pull_request_target` style triggers where the secret could be exfiltrated by a malicious PR if `permissions:` is misconfigured. Edge Function keeps the service-role key inside Supabase only.
- The function-vs-workflow choice is precedented: ADR-020 §Q4 chose Edge Function over Action `workflow_dispatch` for the same reasoning (secret surface minimization + cold-start + locality with the Supabase row write). Reusing the same pattern keeps the architecture uniform.
- Workflow cold start is 10–30 s in practice (queue + runner provision + checkout). Edge Function cold start on Supabase is < 1 s typical. Latency is secondary at < 10 reports/week, but the Edge Function path is faster regardless.

Edge Function (D2) is the chosen execution surface. ADR-020 §Q4's precedent applies here verbatim.

### A4 — Skip automation; keep ADR-021 §D5 manual + add operator monitoring queries

Rejected. Alternative: do not build the webhook automation; instead, expand `docs/en/ops/ugc-moderation-sop.md` `## Monitoring queries` with a daily-job SQL that flags reports without `resolved_at` more than 12 hours after `created_at` (early warning at 50% of SLA budget). Volume is low; the human-error mode is rare enough to absorb.

Pros: zero implementation cost — operator-time-only addition.

Cons that override:

- Human-error mode is exactly the structural risk this ADR targets. Adding a *catch* (monitoring query) for the failure instead of *preventing* the failure (automation) leaves the SLA accounting un-audited until someone manually runs the query.
- Phase 40 GA's open-distribution rollout multiplies volume, and the volume-independent automation argument from the Context section above does not weaken with scale.
- Apple/Google review on moderation timeliness benefits from a *structural* guarantee (audit-trail auto-population) more than from a *procedural* one (operator runs queries on schedule). The structural framing is also clearer to explain in App Store Connect review notes.

A4 is the "do nothing" pole. Rejected. (Per Skeinly project policy, cost is not a deferral reason; this ADR's adoption is a scope choice, not a cost-driven one.)

### A5 — Bidirectional sync (Supabase row UPDATE → GitHub Issue auto-close)

Rejected for ADR-027 scope. The symmetric direction — operator runs `UPDATE ugc_reports SET state = …` directly in Dashboard SQL Editor and the system auto-closes the GitHub Issue — would eliminate the operator's manual Issue-close step (step 6 in D3 workflow) and let the SQL-first workflow be canonical.

Pros: the operator who prefers SQL Editor as their primary surface doesn't have to context-switch to GitHub UI for close.

Cons that drove deferral:

- Requires Supabase Database Webhook → Edge Function → GitHub API (close Issue) — a second function to maintain, a second secret rotation cadence, and a second failure mode to document.
- Inverts the ADR-027 closure-trigger model (close-then-update) to update-then-close. The two models can coexist with careful idempotency (`AND resolved_at IS NULL` already handles the case where webhook lands first; symmetric guards would be needed on the GitHub-close side to avoid re-closing an already-closed Issue), but the design surface doubles.
- The current operator workflow already touches the GitHub UI at SOP Step 1 (acknowledge by applying `triaging`) and Step 5 (close). Step 5 close is not a high-friction surface; the value of automating it specifically is lower than the close → SQL direction.

Deferred to a future ADR if/when SQL-first workflow becomes the operator's stated preference. Listed in §Out of scope.

## Compliance posture

### Apple App Store Review Guideline 1.2 — UGC Safety

ADR-021's Compliance posture table is unchanged at the *requirement* level (Filter / Report / Block / Published contact all stay ✅). ADR-027 strengthens the moderation-timeliness *structural guarantee*:

- Triage decision → SLA accounting is now automated end-to-end at the SoT (`ugc_reports.state` + `resolved_at`).
- The GitHub Issue thread remains as immutable audit trail; the webhook delivery history page on GitHub adds a second audit trail (request/response per delivery, retained per GitHub's webhook retention policy — currently 30 days).
- Manual fallback path remains; the SLA commitment in ADR-021 §D5 step 3 is unchanged.

App Store Connect Review Notes update (Z2 owns the wording finalization):

> Skeinly's UGC triage now writes `ugc_reports.state` automatically when the operator closes the corresponding GitHub Issue with a labeled decision. The Supabase Edge Function performs HMAC-verified UPDATE on `resolved_at` and the canonical state column. The manual SQL UPDATE path remains as fallback if the webhook is unavailable. The 24-hour SLA commitment is unchanged.

### Google Play UGC Policy

Identical mapping; Google Play's policy is a subset of Apple's. The structural guarantee benefits are the same.

## Out of scope

The following are *not* included in ADR-027; future ADRs or Z2-or-later worker batches may revisit:

- **Reporter notification on resolution** — emailing the reporter when their report is `resolved_remove` is a courtesy already regulated in `docs/en/ops/ugc-moderation-sop.md` Step 6 (optional). Programmatic notification (in-app or push) is a separate ADR (notification surface design + i18n + opt-out — outside UGC triage automation scope).
- **Multi-repo webhook subscription** — single-repo concentration is fixed at `b150005/skeinly` per ADR-020 §Q2. Skeinly-fork / mirror repos are explicitly out of scope.
- **Auto-content-removal** — when `state` transitions to `resolved_remove`, the operator still manually executes `UPDATE patterns SET visibility = 'private' WHERE id = '<target_id>'` (or the DELETE equivalent for comments). Chaining this from the webhook is rejected for safety reasons: the content removal action is irreversible (DELETE) or sensitive (visibility flip), and keeping operator-in-the-loop is the right boundary. May revisit post-Phase-40 GA if a high-volume regime warrants the additional automation.
- **Bidirectional sync (Supabase → GitHub auto-close)** — see §A5. Future ADR if SQL-first workflow becomes preferred.
- **Ambiguous decision-label auto-comment** — D3 currently logs and no-ops if the operator applies 0 or ≥2 `state-…` labels. Auto-posting a comment to the Issue asking the operator to fix the label set is scope creep; out of scope for ADR-027 (Z2 may add this if it becomes a frequent failure mode in production).
- **Moderator-pool recusal handling** — ADR-021 §Out-of-scope already noted single-moderator design is sufficient pre-GA. Multi-moderator + recusal is unchanged.
- **Trust-and-safety ML classifiers** — same as ADR-021 §A3 / §Out-of-scope; unchanged.

## Implementation phasing

| Slice | Scope | Wave |
|---|---|---|
| 027.0 — ADR draft + ADR-021 §D5 cross-link | This ADR + JA mirror + ADR-021 EN+JA §D5 cross-link + Revision history append + Z1.md task file | Z1 (this worker) |
| 027.1 — Edge Function + config | `supabase/functions/github-webhook/{index,hmac,mapping,_fakes}.ts` + tests + README + `supabase/config.toml` registration | Z2 (separate worker batch) |
| 027.2 — sop.md update | `docs/en/ops/ugc-moderation-sop.md` + JA mirror with auto/manual fallback marks on Step 1–5 + SLA-compliance query addition + Z1 cross-link refinement | Z2 |
| 027.3 — release-secrets.md | New slot entry for `GITHUB_WEBHOOK_SECRET` (EN + JA), matching existing EF-6+ entry format | Z2 |
| 027.4 — Operator-attended config | GitHub Repo Settings → Webhooks new entry + 3 new Labels + Supabase Edge Function secret registration + EF deploy + smoke test | Z2 (user-attended) |

### Implementation notes for Z2

These notes capture facts Z1 surfaced during ADR drafting that Z2 needs at the start of implementation:

- **Migration is not required.** The existing `ugc_reports.resolved_at TIMESTAMPTZ NULL` column from migration 031 (ADR-021 §D1) serves as the SLA accounting timestamp. The Edge Function writes `issue.closed_at` into this existing column under the `AND resolved_at IS NULL` idempotency guard. Z2 should *not* add a new migration unless a downstream design need arises.
- **Migration numbering, if needed**: at the time of Z1 drafting, migration 037 is the most recent (`037_phase_25_1_wipe_friend_graph.sql`). If Z2 later identifies a schema need (e.g., a foreign-key column from `ugc_reports` to an `issues_closed_log` table), it should claim the next sequential number after re-checking `ls supabase/migrations/`.
- **`resolved_at` vs `state_resolved_at` choice**: the operator decided 2026-05-19 (agent-team deliberation: architect / security-reviewer / code-reviewer / implementer / product-manager) to reuse the existing `resolved_at` rather than introducing a separate `state_resolved_at`. Rationale: schema duplication avoided, no migration risk, monitoring queries unchanged, idempotency clear with `WHERE resolved_at IS NULL`, single source of truth for resolution timestamp. The worker prompt that spawned Z1 referenced a hypothetical `state_resolved_at` new column; that hypothesis is obsolete and Z2 should follow the decision recorded here.
- **Label names are fixed**: `state-resolved-remove`, `state-resolved-keep`, `state-dismissed`. The operator-attended Step 3 in §D6 creates these on the repo before EF deploy. Renaming would require a coordinated change in the labels + `mapping.ts` + sop.md.
- **`triaging` label is not automated**: the existing SOP Step 1 ("operator applies `triaging` when handling starts") stays manual. The webhook only fires on close; opening + acknowledging is operator-side.
- **HMAC implementation detail**: GitHub uses HMAC-SHA-256 over the *raw request body bytes* (not a JSON-roundtripped re-serialization). The Deno handler must read the body as Uint8Array before JSON.parse for signature verification to work. Deno's standard library `crypto.subtle.importKey` + `crypto.subtle.verify` with `{name: 'HMAC', hash: 'SHA-256'}` supports this; reference [GitHub Docs — Validating webhook deliveries](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries) at implementation time.
- **Issue body template stability**: the UUID extraction in payload validation depends on the `Report ID: \`<UUID>\`` line emitted by `supabase/functions/submit-ugc-report/mapping.ts`. If Z2 (or any future change) modifies that template, the `github-webhook` regex must update in lockstep. Worth a one-line comment in both places.

## Cross-reference

- [ADR-021](021-pre-alpha-ugc-moderation.md) — base ADR; §D5 evolved by this ADR
- [ADR-020](020-phase-39-w5-bug-report-proxy.md) — Edge Function `verify_jwt = false` precedent (Q4) + GitHub App + secret custody pattern (D6)
- [supabase/functions/submit-ugc-report/](../../../supabase/functions/submit-ugc-report/) — Issue body template (`mapping.ts`) the webhook regex anchors on
- [supabase/functions/submit-bug-report/](../../../supabase/functions/submit-bug-report/) — Edge Function code-shape precedent for `github-webhook` Deno layout
- [docs/en/ops/ugc-moderation-sop.md](../ops/ugc-moderation-sop.md) — runbook updated in Z2 with auto/manual fallback marks
- [docs/en/ops/release-secrets.md](../ops/release-secrets.md) — `GITHUB_WEBHOOK_SECRET` slot added in Z2
- [Apple App Store Review Guideline 1.2 — User Generated Content](https://developer.apple.com/app-store/review/guidelines/#user-generated-content)
- [Google Play UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937)
- [GitHub Docs — Repository webhooks](https://docs.github.com/en/webhooks)
- [GitHub Docs — Validating webhook deliveries](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries)
- [GitHub Docs — Webhook events: Issues](https://docs.github.com/en/webhooks/webhook-events-and-payloads#issues)

## Revision history

| Date | Change | Author |
|---|---|---|
| 2026-05-19 | Initial proposal (Z1 worker, post-Y1–Y4 batch consolidation). Implementation phased in Z2. Webhook scope (repo-level) and SLA column (reuse existing `resolved_at`) operator-confirmed via agent-team deliberation. | b150005 |
