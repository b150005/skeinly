# Runbook — Observability Alerts

> JA: [docs/ja/ops/observability-alerts.md](../../ja/ops/observability-alerts.md)

Configure Sentry + Google Play Console alerts so a real production failure pages the operator within minutes, not days. Covers pre-alpha audit items **A28** (Sentry crash-free SLO) and **A29** (Sentry ANR + Play Vitals alerts).

## Why bother for closed alpha

A 5–10 tester closed alpha has so few sessions per day that a single crash can move the crash-free rate by 10–20 percentage points. Without an alert, you find out about the regression three days later from the tester's WhatsApp message instead of three minutes later from your inbox. Configure alerts now and operate at production-quality observability from day one.

## Inventory — alerts to configure

| Alert | Source | Severity | Target inbox |
|---|---|---|---|
| Crash-free sessions (iOS) | Sentry | Page | Operator email |
| Crash-free sessions (Android) | Sentry | Page | Operator email |
| Crash-free users (iOS + Android) | Sentry | Warn | Operator email |
| ANR rate (Android) | Sentry | Page | Operator email |
| ANR rate (Android) | Play Console Vitals | Page | Operator email |
| Crash rate (Android) | Play Console Vitals | Warn | Operator email |
| Slow cold start (Android) | Play Console Vitals | Info | Operator email |

7 alerts total. All route to operator email (`skeinly.app@gmail.com`); upgrade to Slack / PagerDuty post-alpha when on-call rotation exists.

## SLO targets

| Metric | Alpha threshold | GA threshold (Phase 40+) |
|---|---|---|
| Crash-free sessions | ≥ 99.0% (rolling 24h) | ≥ 99.5% |
| Crash-free users | ≥ 95.0% (rolling 24h) | ≥ 99.0% |
| ANR rate (Android, daily users) | ≤ 0.47% | ≤ 0.20% |
| Crash rate (Android, daily users) | ≤ 1.09% | ≤ 0.50% |
| Cold start, slow startup rate | ≤ 30% (P50 ≤ 5 s) | ≤ 15% (P50 ≤ 3 s) |

Thresholds for alpha are intentionally loose: 5–10 testers cannot generate enough sessions to drive the rate above 99% in a single day if one tester hits a single crash. The "page" severity at alpha = "I should look at this within 2 hours"; not literal pager rotation.

Threshold rationale for Play Vitals ANR / Crash:
- **ANR ≤ 0.47%** is Google Play's [bad-behavior threshold](https://support.google.com/googleplay/android-developer/answer/9844486) — exceeding it makes the app eligible for store-listing demotion.
- **Crash ≤ 1.09%** is the same store-listing demotion threshold for crashes.
- Hitting either threshold at alpha is a regression to investigate, not a launch blocker.

Threshold rationale for Sentry:
- **Crash-free sessions ≥ 99%** is industry-standard for consumer apps. Alpha tester pool is tiny, so this is mostly aspirational; the alert primarily exists to catch "post-deploy 100% crash" regressions (where every session crashes immediately).
- **Crash-free users ≥ 95%** is more permissive than sessions because one user repeatedly hitting the same crash can drive sessions below 99% while user count holds. The dual-metric setup catches both attack patterns.

## Sentry — alert configuration steps

Run these for **each** Sentry project (`skeinly-android` and `skeinly-ios`). The DE region URL is `https://de.sentry.io`.

### 1. Crash-free sessions alert (per project)

1. Open Sentry → project `skeinly-android` → Alerts → Create Alert.
2. Choose **Metric Alert** → metric = "crash-free session rate".
3. Conditions:
   - Aggregate: `percentage()`
   - Filters: leave empty (all environments, all releases)
   - Window: 1 hour rolling
   - Threshold: triggers when crash-free session rate falls below **99.0%** for ≥ 10 minutes
4. Actions: email `skeinly.app@gmail.com` on trigger and resolve.
5. Owner: leave unassigned (single-operator setup).
6. Name: `crash-free-sessions-android-alpha`.
7. Save. Repeat for `skeinly-ios` (name `crash-free-sessions-ios-alpha`).

### 2. Crash-free users alert (per project)

Same flow, but:
- Metric = "crash-free user rate".
- Threshold: 95.0% (alpha) / 99.0% (GA).
- Name: `crash-free-users-android-alpha` and `-ios-alpha`.
- Severity in alert metadata: `warn`.

### 3. ANR alert (Android only)

ANR issues in Sentry are issues with `issue.category:performance` and `event.type:application_not_responding`. Sentry treats them as ordinary Issues, so the alert is an **Issue Alert** rather than a Metric Alert.

1. Open Sentry → project `skeinly-android` → Alerts → Create Alert.
2. Choose **Issue Alert**.
3. Conditions:
   - When: "A new issue is created"
   - Filter: `event.type:application_not_responding`
   - Filter: `environment:production`
4. Actions: email `skeinly.app@gmail.com`.
5. Frequency: at most once per ANR group per hour (avoid notification storms from a stuck thread firing repeatedly).
6. Name: `anr-fired-android-alpha`.
7. Save.

### 4. Naming + state

After all 5 Sentry alerts are configured, run a sanity check by triggering a manual test issue (Sentry Dashboard → Issues → "Create test issue"). The crash-free alerts won't fire on a single test issue (rate is computed from real session data), but the issue-alert (#3) will fire if you craft a test ANR issue. Verify the email lands in the operator inbox.

## Google Play Console — Vitals alert configuration

Run these once after the first internal-track upload lands.

1. Open Play Console → `skeinly` app → Quality → Android vitals → Overview.
2. Click "Vitals alerts" (or equivalent navigation point in the current Play Console UI).
3. Configure the following alerts (Play Console varies the exact UI labels release to release; these are the underlying metrics):
   - **User-perceived ANR rate above bad behavior threshold (0.47%)** → email.
   - **User-perceived crash rate above bad behavior threshold (1.09%)** → email.
   - **Slow cold start rate above 30%** → email (info severity).
4. Email destination: `skeinly.app@gmail.com`.
5. Save.

Play Vitals aggregates daily; alerts fire once per 24-hour window when the threshold is exceeded.

## Verification — alpha-launch readiness checklist

Before alpha tester invites go out, confirm:

- [ ] 5 Sentry alerts configured (2× crash-free sessions + 2× crash-free users + 1× ANR) — name pattern `*-{platform}-alpha`.
- [ ] 3 Play Console Vitals alerts configured (ANR + crash + cold start).
- [ ] Test email from each Sentry alert and each Play Vitals alert lands in `skeinly.app@gmail.com` (don't trust silent configs).
- [ ] Operator's email filter does NOT auto-archive Sentry / Play notification senders (`alerts@sentry.io`, `noreply@google.com`).
- [ ] `secrets-rotation.md` retention note: alert configuration is documented here; no secret rotation needed for alerts themselves (the DSN / API access is unchanged).

## When an alert fires — initial response checklist

1. Read the alert email body — it includes the project, the issue title, the affected release, and a deep link to the Sentry Issue (or Play Vitals page).
2. Open the linked Issue. Read the topmost stack frame + the affected user count.
3. If the alert was **transient** (resolved in the next 30 min auto-check), document in `incident-playbook.md` and move on.
4. If the alert **persists** beyond 1 hour:
   - Classify severity: blocker (every session crashes) vs. one-edge-case (single user, narrow stack).
   - Pull recent commits + cross-reference: `git log --since="48 hours ago"`. The last commit before the alert started firing is the suspect.
   - File an `incident-playbook.md` entry + start the recovery flow (rollback, forward-fix, or accept-and-mitigate).
5. Within 24 hours, post-incident review: write up the fix + the alert tuning lessons (was the threshold right? did the alert fire too late?).

## Alert-tuning loop

Alpha is an alert-tuning period as much as a product-validation period. Expect the following tuning cycles:

- **Week 1**: alpha launch + alert noise. Calibrate the thresholds; if an alert fires 5+ times per day for benign things, the threshold is wrong.
- **Week 2-3**: real signal vs. noise. Adjust threshold up or down based on observed false-positive vs. true-positive ratio.
- **Pre-GA**: tighten to the GA targets in the §SLO table. The "page" severity becomes literal: GA on-call rotation needs to respond to a real page in ≤ 15 minutes.

## Cross-reference

- [pre-alpha-checklist.md §46-47](pre-alpha-checklist.md) — A28 + A29 closure records
- [incident-playbook.md](incident-playbook.md) — symptom-indexed failure modes (where alert responses get logged)
- [Sentry Alert Rules docs](https://docs.sentry.io/product/alerts/create-alerts/)
- [Play Vitals docs](https://support.google.com/googleplay/android-developer/answer/9844486)

## Update history

| Date | Change | By |
|---|---|---|
| 2026-05-12 | Initial runbook — pre-alpha audit items A28 + A29 | b150005 |
