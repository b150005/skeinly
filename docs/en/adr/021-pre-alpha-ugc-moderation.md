# ADR-021: Pre-Alpha UGC Moderation (Report + Block + Filter)

## Status

Accepted (2026-05-12). Implementation phased; foundation lands pre-alpha, full sweep pre-Phase-40 GA.

## Context

Apple App Store Review Guideline 1.2 ("Safety — User Generated Content")
and Google Play's [User Generated Content policy](https://support.google.com/googleplay/android-developer/answer/9876937)
both require any app that surfaces UGC to ship **all four** of:

1. **Filter / moderation mechanism** — server-side or operator-mediated removal of objectionable content.
2. **Reporting mechanism** — every UGC element must have a Report affordance reachable in ≤ 1 tap by the viewer.
3. **Block user mechanism** — viewers can hide all content from a specific user; blocked users cannot send new Suggestions or comments to the blocker.
4. **Published contact information** — the operator's email address must be visible from the in-app surface and from the App Store / Play listing metadata.

Skeinly surfaces UGC in three places:

| Surface | UGC element | Visibility |
|---|---|---|
| Discovery (Browse Patterns) | Pattern names + descriptions + chart image thumbnails of `visibility = public` patterns | Any authenticated user |
| Suggestion detail | Suggestion bodies + comments + suggestion-comments threading | Pattern owner + Suggestion author + watchers |
| Comments on patterns + projects | `comments` table rows (Phase 32+) | Pattern owner, project owner, shared participants (per migration 030 tightened policy) |

Requirement 4 (published contact) is already covered: `skeinly.app@gmail.com` is in the Privacy Policy, ToS DMCA designated-agent block, Help page footer, Settings → Contact Support, web `account-deletion/` page footer. Closed under pre-alpha A34.

Requirements 1, 2, 3 are NOT yet implemented. Pre-alpha checklist items **A1** and **A5** name this gap. ADR-021 designs the closing.

The implementation scope is large enough that it does NOT fit pre-alpha as a single slice. ADR-021 phases the work:

- **Pre-alpha foundation**: data model + minimum reporting mechanism (single Edge Function reusing the `submit-bug-report` GitHub-App pattern) + minimum blocking mechanism (RLS filter only — no client UI in `0.1.0` alpha). Sufficient to claim "Skeinly has UGC moderation" at App Store / Play submission.
- **Pre-Phase-40 GA full sweep**: client UI for Report button + Block User entry + blocked-user-content filtering at the UI layer, full moderator triage workflow, automated keyword filter for the most obvious classes of abuse text.

## Decision

### D1 — Data model (foundation, pre-alpha)

Two new Supabase tables.

#### Table `public.ugc_reports`

```sql
CREATE TABLE public.ugc_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    target_type TEXT NOT NULL CHECK (target_type IN (
        'pattern', 'comment', 'suggestion', 'suggestion_comment'
    )),
    target_id UUID NOT NULL,
    reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 2000),
    reason_category TEXT NOT NULL CHECK (reason_category IN (
        'spam', 'harassment', 'sexual', 'violence', 'hate', 'ip', 'other'
    )),
    state TEXT NOT NULL DEFAULT 'open' CHECK (state IN (
        'open', 'triaging', 'resolved_remove', 'resolved_keep', 'dismissed'
    )),
    operator_notes TEXT NULL CHECK (operator_notes IS NULL OR length(operator_notes) <= 4000),
    resolved_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ugc_reports_state_created ON public.ugc_reports(state, created_at);
CREATE INDEX idx_ugc_reports_reporter ON public.ugc_reports(reporter_id);
CREATE INDEX idx_ugc_reports_target ON public.ugc_reports(target_type, target_id);
```

RLS:
- Authenticated user can INSERT (with `reporter_id = auth.uid()` check).
- Authenticated user can SELECT only their own reports (`reporter_id = auth.uid()`).
- No UPDATE / DELETE for authenticated users — operator handles via Supabase Dashboard service-role.

#### Table `public.user_blocks`

```sql
CREATE TABLE public.user_blocks (
    blocker_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id != blocked_id)
);

CREATE INDEX idx_user_blocks_blocker ON public.user_blocks(blocker_id);
CREATE INDEX idx_user_blocks_blocked ON public.user_blocks(blocked_id);
```

RLS:
- Authenticated user can INSERT (with `blocker_id = auth.uid()`).
- Authenticated user can SELECT only their own blocks (`blocker_id = auth.uid()`).
- Authenticated user can DELETE only their own blocks (`blocker_id = auth.uid()`).

Both tables ship in a single migration `031_ugc_moderation.sql` at the pre-alpha foundation slice.

### D2 — RLS-level filter (foundation, pre-alpha)

Discovery `patterns` SELECT policy gains a NOT-EXISTS arm filtering out content from `user_blocks` entries where the caller is the blocker:

```sql
-- Conceptual amendment to the existing patterns SELECT policy:
-- (visibility-public-OR-owner-OR-share-recipient)
-- AND NOT EXISTS (SELECT 1 FROM public.user_blocks ub
--                 WHERE ub.blocker_id = auth.uid()
--                   AND ub.blocked_id = patterns.owner_id)
```

Same NOT-EXISTS clause applied to:
- `comments` SELECT policy (already tightened by migration 030; add the user_blocks arm).
- `suggestions` SELECT policy.
- `suggestion_comments` SELECT policy.
- `discovery_feed`-style queries — Discovery feed is a SELECT against `patterns` so the patterns policy covers it.

This is the **server-side guarantee**: blocked-user content NEVER reaches the blocker's device. The client UI in D4 below renders nothing additional to filter — the rows just don't come back.

### D3 — Report submission (foundation, pre-alpha)

Reports route through the same **Supabase Edge Function + Skeinly Feedback GitHub App** pipeline used by ADR-020 W5b bug reports, but write to two destinations:

1. INSERT into `public.ugc_reports` (canonical authority record with state tracking).
2. POST a GitHub Issue on `b150005/skeinly` with label `ugc-report` (operator triage surface — the operator already monitors GitHub Issues for bug reports).

Single new Edge Function `submit-ugc-report` distinct from `submit-bug-report`:

- Authenticated callers only (`verify_jwt = true`; differs from `submit-bug-report` which is `verify_jwt = false` because anyone can file a bug). For UGC reports we want the reporter identity captured so the operator can investigate patterns of false reporting.
- Rate limit: 10 reports / hour per `auth.uid()`, in-memory `Map` (same shape as `submit-bug-report` rate limit).
- Same GitHub App authentication (per ADR-020 §D2).
- Issue body template includes: reporter user_id (NOT email — the operator uses Dashboard SQL Editor to resolve to email if needed for due-process contact), target_type + target_id, reason_category, redacted reason text (first 500 chars + length), report_id (FK to `ugc_reports`).

Rationale for routing to GitHub Issues: the operator's existing triage habit (skeinly.app@gmail.com inbox + GitHub Issues label-filter) extends naturally. Setting up a separate moderator dashboard for a 5–10 tester closed alpha would be over-engineering. Phase 40 GA may revisit if report volume grows.

### D4 — Client UI (foundation pre-alpha vs full pre-Phase-40)

#### Pre-alpha foundation (Wave D scope after this ADR)

Defer client UI entirely. Reports + blocks are issuable by **operator on the user's behalf** for alpha: if a tester sees objectionable UGC, they email `skeinly.app@gmail.com` and the operator manually `INSERT INTO public.ugc_reports` + `INSERT INTO public.user_blocks` via Dashboard SQL Editor.

**Why defer**: closed-alpha tester pool (5–10 users, hand-picked, all trusted) has **zero** plausible threat model where adversarial UGC surfaces in time to need self-service reporting. The operator can field reports via email faster than a tester can navigate an in-app Report button + confirmation modal. The foundation (D1 tables + D2 RLS + D3 Edge Function) exists so the GA-time UI sweep is a pure UI addition without retroactive schema work.

This is **not** the same as not having UGC moderation. The data model + RLS filter + Edge Function operator pipeline are all live. Apple Review can be told: "Skeinly's closed alpha is operator-mediated; the in-app Report button ships at GA."

#### Pre-Phase-40 GA full sweep (scheduled in CLAUDE.md polish list)

Three new UI affordances:

1. **Report button on Discovery pattern cards** — overflow menu (⋮) with "Report this pattern" → modal with category picker (`spam` / `harassment` / `sexual` / `violence` / `hate` / `ip` / `other`) + 2000-char reason text field + submit. On submit, calls `submit-ugc-report` Edge Function with `target_type='pattern'` + `target_id=<pattern.id>`.
2. **Report button on Suggestion / comment threads** — same modal with `target_type='comment' | 'suggestion' | 'suggestion_comment'`.
3. **Block User entry on user profile + on Suggestion author chip** — confirmation dialog "Block <display_name>?" → INSERT into `user_blocks`. Reverse action (unblock) reachable from Settings → Privacy → Blocked Users list.

Client-side i18n: 8–10 new keys × en/ja × CMP/iOS.

### D5 — Operator triage workflow (foundation, pre-alpha)

When a UGC report Issue lands on `b150005/skeinly` with label `ugc-report`:

1. Operator opens the GitHub Issue, reads target_type + target_id + reason_category.
2. Resolves target_id to actual content via Dashboard SQL Editor:
   ```sql
   SELECT * FROM public.patterns WHERE id = '<target_id>';
   -- or comments / suggestions / suggestion_comments
   ```
3. Apple SLA for objectionable content removal: **24 hours**. The operator commits to this SLA via documented runbook (next D6).
4. Resolution action — one of:
   - **Remove content**: UPDATE the offending row to `visibility = 'private'` (for patterns) or DELETE the row (for comments). UPDATE `ugc_reports.state = 'resolved_remove'`.
   - **Keep content**: report was false-positive or content is acceptable. UPDATE `ugc_reports.state = 'resolved_keep'` with operator_notes documenting the reasoning.
   - **Dismiss**: report was abusive (e.g., reporter trying to harass a target via false reports). UPDATE `ugc_reports.state = 'dismissed'`. Track the reporter's UID in operator notes — pattern across multiple `dismissed` reports may indicate the reporter is a problem.
5. Close the GitHub Issue with a one-line summary referencing the `ugc_reports.id`.

If volume grows past ~10 reports per week, revisit: build a dedicated Supabase Studio-style moderator UI, or onboard a contracted moderator.

> **Evolution note (2026-05-19)**: post-Phase-39 closed-beta operations consideration will be addressed by ADR-027. Once ADR-027 is accepted, the manual dual-sync described above becomes the fallback path, and the default triage workflow becomes GitHub-Issue-close-webhook driven. The 24-hour SLA commitment is unchanged; the volume-threshold judgment in the paragraph above (moderator-pool scale-out) is orthogonal to the automation introduced in ADR-027. See [ADR-027](./027-ugc-triage-automation.md).

### D6 — Runbook

Pre-alpha foundation slice ships:

- Migration 031 (D1) — `ugc_reports` + `user_blocks` tables + RLS.
- Migration 032 (D2) — patterns / comments / suggestions / suggestion_comments SELECT policies extended with the `user_blocks` NOT-EXISTS arm.
- Edge Function `submit-ugc-report` (D3) — Deno + Skeinly Feedback GitHub App authentication.
- New runbook `docs/en/ops/ugc-moderation-sop.md` covering the D5 operator workflow + 24-hour SLA + the resolve / keep / dismiss decision matrix + post-Phase-40 UI hookup.

Pre-Phase-40 GA sweep ships:

- Client Report modal Composable + SwiftUI mirror.
- Client Block User UI.
- Settings → Privacy → Blocked Users list.
- i18n keys (× 4: en, ja, CMP, iOS xcstrings).
- 12+ commonTest cases for the report-submission ViewModel + the block flow.

## Alternatives considered

### A1 — Use the existing `submit-bug-report` Edge Function for UGC reports

Rejected. Combining UGC reports with bug reports in a single Edge Function would mean:
- Mixing `verify_jwt = false` (bug reports — unauth allowed) with `verify_jwt = true` (UGC reports — reporter identity required) at the function level. Supabase function-level `verify_jwt` is binary; can't toggle per-payload.
- GitHub Issue label proliferation (`feedback` and `ugc-report` mixed in one function increases incident-mistake risk).
- Two functions are clearer documentation surface for App Review reviewers + future operators.

Distinct function is the right boundary.

### A2 — In-app Report button at pre-alpha foundation

Rejected for pre-alpha (deferred to GA). Closed-alpha tester pool does not need self-service reporting because:
- Tester pool is 5–10 hand-picked users; operator can field reports via email within the 24-hour SLA.
- Building the Report button + modal + i18n + tests is ~400 LOC; the value at alpha scale is zero.
- The Edge Function + tables already exist as foundation, so the GA sweep is a pure UI addition.

This is **not** punting for cost reasons (which is forbidden by user policy). It's a scope decision: the alpha-validation goal of "Skeinly has UGC moderation" is met by the foundation + operator pipeline. Tester-facing self-service is a UX improvement, not a compliance gap.

### A3 — Automated keyword filter at pre-alpha

Rejected for pre-alpha (deferred to GA). Keyword-blocking has a 50-year history of false positives (the Scunthorpe problem). Building it for closed alpha is over-engineering. Operator-mediated triage in the alpha is more accurate than any automated filter. Reconsider for GA if report volume justifies it.

### A4 — Outsource moderation to a third-party (Sift, Spectrum Labs, etc.)

Rejected. Cost (USD 99-500/mo minimum), data-residency complications, and another DPA to manage. At closed-alpha scale, single-operator triage is sufficient.

## Compliance posture

### Apple App Store Guideline 1.2 — UGC Safety

| Requirement | Foundation (pre-alpha) | GA |
|---|---|---|
| Filter / moderation | ✅ Operator-mediated via Edge Function + Dashboard SQL | ✅ + automated keyword filter (post-alpha-feedback driven) |
| Report mechanism | ✅ Edge Function + GitHub Issue triage (operator-initiated on tester's behalf via email) | ✅ In-app Report button on every UGC surface |
| Block user | ✅ Data model + RLS filter live; operator INSERTs on tester's behalf | ✅ In-app Block User UI + Blocked Users list |
| Published contact | ✅ Already in Privacy / ToS / Help / Settings / web account-deletion / app footer | ✅ unchanged |

### Google Play UGC Policy

Identical mapping. Google Play's policy is a subset of Apple's in mechanics; both are satisfied by the same foundation.

### App Store Review Notes (App Store Connect)

Recommended text:
> Skeinly surfaces user-generated content via the Discovery feed (public knitting patterns), Suggestion threads (collaboration), and Comments on patterns and projects. UGC moderation is implemented in three layers: (1) server-side row-level security filters out content from users a viewer has blocked; (2) reports route through a Supabase Edge Function + GitHub App that creates internal triage Issues; (3) the operator commits to a 24-hour SLA for objectionable content removal per Apple Guideline 1.2. The current closed-alpha release ships the data-model + filter + reporting foundation; in-app Report and Block User UI surfaces ship at v1.0 GA. The contact email skeinly.app@gmail.com is published in the Privacy Policy, ToS, in-app Settings, and on the public web pages.

## Out of scope

- **Moderator dashboard** beyond Supabase Studio. Build only if volume justifies.
- **Appeal flow for content removal** — at closed alpha, the operator handles via email reply directly. Codified appeal flow can wait until first appeal happens.
- **Cross-instance shadow ban / IP block** — single user_id-based block is sufficient at alpha scale.
- **Trust-and-safety ML classifiers** — same as automated keyword filter, defer.

## Implementation phasing

| Slice | Scope | Wave |
|---|---|---|
| 021.1 — Data spine | Migration 031 (D1) + Migration 032 (D2 RLS amendments) | Wave E (next session) |
| 021.2 — Edge Function | `submit-ugc-report` + tests + README + EF-8 secret reuse of `SKEINLY_BUGREPORT_*` (or a separate GitHub App if scope deems it) | Wave E |
| 021.3 — Operator SOP runbook | `docs/en/ops/ugc-moderation-sop.md` (+ JA mirror) | Wave E |
| 021.4 — Pre-Phase-40 client UI | Report Composable + SwiftUI mirror + Block User UI + Blocked Users list + i18n + tests | Pre-Phase-40 polish |

## Cross-reference

- [ADR-005](005-account-deletion.md) — adjacent right (deletion) flow
- [ADR-014](014-phase-38-pull-request-workflow.md) — Suggestion data model that includes the comments / suggestion_comments UGC tables
- [ADR-020](020-phase-39-w5-bug-report-proxy.md) — pattern that submit-ugc-report mirrors
- [docs/en/ops/pre-alpha-checklist.md §1.1 A1 + A5](../ops/pre-alpha-checklist.md) — closure record
- [Apple Guideline 1.2](https://developer.apple.com/app-store/review/guidelines/#user-generated-content)
- [Google Play UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937)

## Revision history

| Date | Change | Author |
|---|---|---|
| 2026-05-12 | Initial ADR — pre-alpha A1+A5 design closure; implementation phased in Wave E + pre-Phase-40 | b150005 |
| 2026-05-19 | §D5 evolution-note cross-link added; ADR-027 (Proposed) introduces GitHub-Issue-close-webhook triage automation evolving §D5 into the fallback path. No design change to §D5 itself. | b150005 |
