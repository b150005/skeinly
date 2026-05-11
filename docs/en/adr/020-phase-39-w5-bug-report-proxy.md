# ADR-020: Phase 39 W5 — GitHub App Bug Report Proxy

## Status

Accepted (2026-05-11)

## Context

Phase 39.5 (ADR-015 §3) shipped beta bug reporting via **client-side URL
prefill**: `BugSubmissionLauncher` opens
`https://github.com/b150005/skeinly/issues/new?template=beta-bug.yml&title=...&body=...`
in the system browser. The tester lands on the GitHub Issue form with
the diagnostic payload populated and taps "Submit new issue" themselves.

This works but has four friction points that surface in real closed-beta
operation:

1. **GitHub account required.** TestFlight / Play Internal testers are
   not necessarily GitHub users. A non-developer beta tester who finds
   a bug cannot complete the submission flow.
2. **Browser context-switch.** The tester leaves the app, lands on
   GitHub.com (signed-in state varies), reviews the prefilled body
   again, then taps Submit. Two screens of friction per report.
3. **URL ~8KB length limit.** Body budget after URL chrome ≈ 6.5KB.
   The ring buffer payload + device meta + description already fills
   ~5KB at N=10 events; long descriptions or future enrichment (≥20
   events, structured logs, screenshots-as-base64) hit the ceiling.
4. **PostHog `distinct_id` visibility.** Phase 39.5's body formatter
   includes `posthog_distinct_id` for cross-referencing reports with
   the PostHog dashboard. URL prefill posts this verbatim into the
   public Issue body. Phase 39.2 disclosed this in the privacy policy
   so consent is covered, but a server-side path can keep the identifier
   in transit metadata only.

Phase 39 W5 swaps URL prefill for a **server-side GitHub App proxy**
hosted as a Supabase Edge Function. The Edge Function authenticates as
a GitHub App with `Issues: Read & write` permission on the
`b150005/skeinly` repository only, creating Issues on the tester's
behalf with the same template+body payload Phase 39.5 already produces.

This ADR records the full design, the agent-team deliberation that
shaped it, the user-attended steps for GitHub App creation and secret
registration, and the rollout plan.

## Agent team deliberation

### Voices

**product-manager** — W5 motivation breakdown:
- **(1) GitHub-account-free submission** — pragmatically minor at 10
  testers (most likely all GitHub users) but de-risks invitation
  outreach to non-developer testers. Required for any future expansion
  beyond GitHub-savvy crowd.
- **(2) In-app completion** — measurable QOL improvement. Reduces
  "started but never submitted" reports.
- **(3) Headroom for future enrichment** — Phase 39 W5+ may grow
  payload (state snapshot, network log tail). Server-side path
  removes the URL ceiling.
- **(4) Identifier hygiene** — minor privacy improvement; Phase 39.2
  already disclosed.

W5 is **net QOL + privacy refinement, not a blocker** for Phase 39
closed beta launch. Phase 39.5 remains viable as fallback if W5
deployment slips.

**architect** — host options:

| Option | Verdict |
|---|---|
| **A**: Supabase Edge Function (`notify-on-write` / `revenuecat-webhook` siblings) | ✅ Existing pattern. Deno + djwt + WebCrypto. `mcp__supabase__deploy_edge_function` deploys autonomously. |
| B: Cloudflare Worker / Vercel Edge | New vendor surface. Overkill. |
| C: GitHub Actions `workflow_dispatch` | Workflow startup latency (~30s) makes UX bad; needs a token to even trigger. |
| D: GitHub App key bundled in app | Static-analysis leakage; rejected immediately. |

→ **Option A**.

**security-reviewer** — GitHub App permission and key custody:
- **Permissions: `Issues: Read & write` only.** No `Contents`, no
  `Pull Requests`, no metadata beyond what GitHub auto-grants. If the
  Edge Function or its secrets leak, blast radius is bounded to
  creating noise Issues on the one installed repo.
- **Install scope: `b150005/skeinly` only.** Not the whole account.
  Prevents accidental cross-repo blast.
- **Private key custody: Supabase Edge Function secret (PEM blob).**
  Same custody surface as `APPLE_APNS_KEY_P8` (Phase 24.1) and
  `FIREBASE_SERVICE_ACCOUNT_JSON` (Phase 24.3). No new threat model.
- **Client auth: Supabase anon key** (same posture as `notify-on-write`
  webhook auth — actually the inverse: `notify-on-write` uses a custom
  Bearer secret because Supabase webhooks don't auto-sign payloads;
  here the client *is* the app, which already carries the anon key, so
  reusing it costs nothing in security and saves a secret to manage).
- **Abuse prevention: in-memory rate limit, 5 reports/hour per request
  source.** Phase 39 closed beta = ≤10 testers; abuse risk is
  effectively zero. Per-Deno-instance Map keyed by the request's
  source identifier is sufficient. Persistent table is overkill.
- **Input validation: title ≤ 256 chars, body ≤ 65,536 chars
  (GitHub's own Issue body limit).** Reject above ceiling with 400.
- **HTML/script content**: GitHub renders Issue bodies through their
  own sanitizer; we do not need to strip on the way in. Markdown
  injection ("bold text") is intended; XSS / arbitrary script is
  GitHub's responsibility.

**implementer** — KMP wiring shape:

The current `BugSubmissionLauncher` is an `expect class` because URL
opening needs platform APIs (`Intent` on Android, `UIApplication` on
iOS). HTTP POST has no platform-specific path — Ktor MultiplatformClient
runs in commonMain. The KMP-idiomatic shape is:

- **Delete `expect/actual` entirely.** New `commonMain` `class
  BugReportProxyClient(httpClient, supabaseConfig)` constructed once,
  injected via Koin.
- **`suspend fun submit(title, body): Result<SubmitOutcome>`** — proper
  suspend signature, returns success outcome (Issue number + URL) or
  typed failure (network error, rate-limited, server error). Phase
  39.5's fire-and-forget `(title, body) -> Unit` becomes
  `suspend (title, body) -> Result<SubmitOutcome>`.
- **`BugReportPreviewViewModel.submit` callback shape changes to
  suspend.** ViewModel awaits the result, updates state with success
  banner ("Report submitted: #123") or error message. The existing
  `isSubmitting` flag now reflects the actual round-trip, not a
  flicker.

The lambda-seam DI pattern (Phase 24.2c-1 precedent) keeps the
ViewModel testable: tests pass a recording lambda; production wires
`proxyClient::submit`.

**devops-engineer** — user-attended steps:
1. https://github.com/settings/apps/new — create "Skeinly Beta Bug Reporter"
2. Repository permissions → **Issues: Read & write**; everything else None.
3. Where can this GitHub App be installed? → **Only on this account**
4. Webhook section → uncheck "Active" (no webhooks needed).
5. Save — note **App ID** displayed at top of the settings page.
6. Generate a **private key** (.pem) on the App settings page; download.
7. Install App → select `b150005/skeinly` only → note the **Installation
   ID** from the install URL
   (`github.com/settings/installations/<INSTALL_ID>`).
8. Register Supabase Edge Function secrets:
   - `SKEINLY_BUGREPORT_APP_ID` — the App ID number
   - `SKEINLY_BUGREPORT_INSTALLATION_ID` — the Installation ID number
   - `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM` — full PEM contents
     (multi-line, starts with `-----BEGIN RSA PRIVATE KEY-----`)
9. Edge Function deploy (autonomous via the Skeinly side):
   `git checkout main && git pull && supabase functions deploy submit-bug-report`
10. Smoke test (manual): trigger a bug report from a TestFlight /
    Play Internal build; verify Issue lands in `b150005/skeinly/issues`.

Steps 1–8 are user-attended (GitHub UI + 2FA + secret rotation outside
autonomous reach). Step 9 can be autonomous. Step 10 requires a real
device with a Beta build.

### Decision points resolved by the team

**Q1: How to handle the existing URL prefill code path?**
Resolved: **(a) Complete replacement.** Delete `BugSubmissionLauncher`
`expect/actual` entirely. URL prefill code is removed in the same
commit. Rationale: maintaining two parallel paths doubles surface for
no real benefit at 10-tester scale. If the proxy is broken, the bug
report flow is broken — which is the same state Phase 39.5 would be
in if URL prefill broke.

**Q2: Issue visibility — public or private?**
Resolved: **(a) Public Issues on `b150005/skeinly`.** Reasoning:
- The repo is already public; private-repo Issues would require
  creating a separate `b150005/skeinly-bug-reports` repo, splitting
  triage between two trackers — concrete UX cost for marginal privacy
  gain.
- PostHog `distinct_id` is an anonymous SDK-generated UUID with no
  account or persistent-identifier linkage. Phase 39.2 privacy policy
  disclosed its appearance in bug reports; consent is already
  captured.
- User-supplied description PII risk is independent of Issue
  visibility (any repo collaborator sees it); template wording warns
  testers not to include personal data.
- Open-source transparency: when Skeinly transitions to open source,
  public Issue history is an asset, not a liability.
- One-repo concentration matches the Phase 38 Suggestion / Pull Request
  vocabulary that's already public-by-default.

**Q3: Rate limit implementation?**
Resolved: **(a) In-memory Map keyed by request source identifier.**
- Closed beta scale (≤10 testers) makes Edge Function instance
  recycling acceptable — rate limit resets on cold start but the
  budget per warm-instance window (5/hour) is generous.
- Persistent storage adds a table, a migration, and DB roundtrip
  latency per request for no measurable benefit at this scale.
- Phase 40 GA (open distribution) can revisit with a `bug_report_throttle`
  table if abuse signals warrant; YAGNI for closed beta.

## Decision

### 1. Architecture

```
┌──────────────┐         ┌──────────────────────────────┐
│ Beta build   │ POST    │ Supabase Edge Function       │
│ (iOS/Android)│ ───────▶│ submit-bug-report            │
│              │         │                              │
│ BugReport    │         │ 1. Auth: Bearer <anon>       │
│ PreviewVM    │         │ 2. Rate limit check          │
│              │         │ 3. Validate length / shape   │
│ Ktor client  │         │ 4. JWT sign with App PEM     │
│              │         │ 5. Exchange for install token│
│              │         │ 6. POST /repos/.../issues    │
└──────────────┘         │ 7. Return {number, html_url} │
                         └──────────────┬───────────────┘
                                        │
                                        │ HTTPS + JWT/installation token
                                        ▼
                              ┌──────────────────────┐
                              │ GitHub API           │
                              │ /repos/b150005/      │
                              │   skeinly/issues     │
                              └──────────────────────┘
```

### 2. Edge Function `submit-bug-report`

#### Request

```http
POST /functions/v1/submit-bug-report HTTP/1.1
Authorization: Bearer <SUPABASE_ANON_KEY>
Content-Type: application/json

{
  "title": "[Beta] tap Save crashes on iOS 26.4",
  "body": "## Description\n…\n## Reproduction context\n…",
  "labels": ["beta-bug"]
}
```

`labels` is optional (defaults to `["beta-bug"]`). Phase 39 W5 hardcodes
the single label; future slices may extend (e.g. screen-tagged labels).

#### Response (success)

```json
{
  "ok": true,
  "issue_number": 123,
  "html_url": "https://github.com/b150005/skeinly/issues/123"
}
```

#### Response (failure)

```json
{ "ok": false, "code": "RATE_LIMITED", "message": "Try again in 47 minutes" }
{ "ok": false, "code": "VALIDATION_FAILED", "message": "title exceeds 256 chars" }
{ "ok": false, "code": "GITHUB_AUTH_FAILED", "message": "..." }
{ "ok": false, "code": "GITHUB_API_FAILED", "message": "..." }
{ "ok": false, "code": "CONFIG_MISSING", "message": "..." }
```

`code` is a closed enum so the client can localize the user-facing
message; `message` is the raw English text suitable for logs but NOT
for end-user display directly.

#### Code structure

```
supabase/functions/submit-bug-report/
├── index.ts           — Deno.serve handler: validate, rate-limit, dispatch
├── github_app.ts      — JWT signing, installation token exchange, issue create
├── _fakes.ts          — globalThis.fetch monkey-patch for tests
├── index.test.ts      — handler / rate-limit / validation suite
├── github_app.test.ts — JWT signing / token cache / issue create suite
└── README.md          — deploy + smoke test recipe
```

#### Rate limit

In-memory `Map<string, RateWindow>` keyed by a stable per-request hash
derived from `x-real-ip` (Supabase's edge sets this) **plus** the
SHA-256 of the anon key tail (defense against a single tester behind
NAT — though at 10-tester scale this is academic). Window: 1 hour
sliding (record array of timestamps, drop entries > 1h, reject if
count ≥ 5).

The Map is per-Deno-instance, so a cold start clears it. This is
acceptable — cold-starts are rare on warm Supabase projects, and a
clearing event hands the abuser at most 5 extra reports before the
next instance restart. Closed beta scale absorbs that.

#### Input validation

| Field | Constraint | On violation |
|---|---|---|
| `title` | 1 ≤ length ≤ 256, no `\r`, no `\n` | 400 VALIDATION_FAILED |
| `body` | 1 ≤ length ≤ 65,536 (GitHub Issue body cap) | 400 VALIDATION_FAILED |
| `labels` | optional, ≤ 5 entries, each ≤ 50 chars | 400 VALIDATION_FAILED |
| (top-level shape) | exactly the keys above | 400 VALIDATION_FAILED |

We do **not** sanitize Markdown. GitHub's Issue body renderer handles
HTML/script stripping; emitting raw Markdown is the intended channel.

#### JWT signing (RS256, GitHub App spec)

```typescript
// Header
{ "alg": "RS256", "typ": "JWT" }

// Claims
{
  "iat": now - 60,       // 1 min skew window per GitHub docs
  "exp": now + 540,      // max 600s per GitHub docs; 9 min keeps margin
  "iss": APP_ID          // numeric App ID
}
```

The PEM private key is imported via `crypto.subtle.importKey('pkcs8',
…, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' })`. The standard
GitHub-issued .pem is PKCS#1 (`-----BEGIN RSA PRIVATE KEY-----`) which
WebCrypto cannot import directly; we convert PKCS#1 → PKCS#8 inline
(2-line ASN.1 prefix concatenation).

In-instance cache: JWT signed once per ~9 minutes (refresh at 8 min
to stay under the 10-min hard cap). Cuts ~50ms of asymmetric signing
off every call.

#### Installation token exchange

```
POST https://api.github.com/app/installations/<INSTALLATION_ID>/access_tokens
Authorization: Bearer <JWT>
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2022-11-28
```

Returns `{ token, expires_at }`. Installation tokens last 1 hour. We
cache in-instance with a 5-minute refresh margin.

#### Issue creation

```
POST https://api.github.com/repos/b150005/skeinly/issues
Authorization: Bearer <INSTALLATION_TOKEN>
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2022-11-28

{ "title": "...", "body": "...", "labels": ["beta-bug"] }
```

GitHub returns the created Issue object. We pass back `number` and
`html_url` to the client.

#### Error handling

All `fetch` calls go through a single helper that interprets the
response status:
- 2xx → success.
- 401 / 403 from token-exchange → CONFIG_MISSING (invalid App ID or
  bad PEM); the cache is cleared so the next call re-attempts from
  scratch.
- 401 / 403 from issue create → GITHUB_AUTH_FAILED (installation may
  have been revoked); same cache clear.
- 422 from issue create → VALIDATION_FAILED (GitHub rejected the
  payload — e.g. unknown label).
- 5xx → GITHUB_API_FAILED with the GitHub response body excerpt in
  `message`.
- Network exceptions → GITHUB_API_FAILED with the exception message.

The Edge Function always returns 200 to Supabase's invocation, with
`ok: false` for application-level errors. Non-200 is reserved for
Supabase-platform problems (unreachable function, deploy failure).

### 3. KMP client wiring

#### Delete `expect/actual` `BugSubmissionLauncher`

Phase 39.5's `BugSubmissionLauncher` (`expect class` + Android/iOS
actuals) is removed entirely. Justification:

- HTTP POST is a commonMain operation via Ktor. No platform-specific
  API is needed.
- The expect/actual was Phase 39.5's correct shape because
  `Intent.startActivity` / `UIApplication.shared.open` ARE platform
  APIs; W5's `httpClient.post(...)` is not.
- Keeping the expect/actual would force the Ktor call into the
  actuals, duplicating identical code on both platforms — anti-KMP.

#### New `commonMain` `class BugReportProxyClient`

```kotlin
package io.github.b150005.skeinly.data.bug

class BugReportProxyClient(
    private val httpClient: HttpClient,
    private val supabaseConfig: SupabaseConfig,
) {
    suspend fun submit(title: String, body: String): Result<SubmitOutcome> = runCatching {
        // POST .../functions/v1/submit-bug-report with title + body
        // Parse the {ok, issue_number, html_url} envelope
        // Throw a typed BugReportProxyException on ok: false
    }
}

data class SubmitOutcome(
    val issueNumber: Int,
    val htmlUrl: String,
)

sealed class BugReportProxyException(message: String) : Exception(message) {
    object Offline : BugReportProxyException("Network unavailable")
    object RateLimited : BugReportProxyException("Rate limit exceeded")
    object ValidationFailed : BugReportProxyException("Payload validation failed")
    object ConfigMissing : BugReportProxyException("Proxy is not configured")
    class Server(message: String) : BugReportProxyException(message)
    class Unknown(message: String) : BugReportProxyException(message)
}
```

#### `BugReportPreviewViewModel` signature change

Phase 39.5's `submit: (title, body) -> Unit` becomes
`submit: suspend (title, body) -> Result<SubmitOutcome>`. State
extends:

```kotlin
data class BugReportPreviewState(
    val description: String = "",
    val previewBody: String = "",
    val isSubmitting: Boolean = false,
    val submitResult: SubmitResultState? = null,
)

sealed interface SubmitResultState {
    data class Success(val issueNumber: Int, val htmlUrl: String) : SubmitResultState
    data class Error(val code: BugReportProxyException) : SubmitResultState
}
```

Submission flow:

1. User taps "Send" → `isSubmitting = true`, `submitResult = null`.
2. `submit(title, body)` awaited.
3. On success: `isSubmitting = false`, `submitResult = Success(...)`.
   UI shows toast "Bug report submitted: #123" and dismisses the
   preview screen after a short delay.
4. On error: `isSubmitting = false`, `submitResult = Error(...)`. UI
   shows an inline error banner ("Couldn't submit — try again later")
   and leaves the user on the preview screen so they can retry or
   cancel.

#### Koin wiring

```kotlin
// commonMain di/ViewModelModule.kt (or NetworkModule if more appropriate)
single { BugReportProxyClient(get(), get()) }

// ViewModel factory updates to pass the suspend lambda
viewModelOf {
    BugReportPreviewViewModel(
        ringBuffer = get(),
        deviceContext = get(),
        submit = get<BugReportProxyClient>()::submit,
    )
}
```

#### iOS bridge

The Swift `BugReportPreviewScreen` already observes
`BugReportPreviewState` via the established `ScopedViewModel` pattern.
The new `submitResult` field is a sealed interface, which the K/N
ObjC bridge surfaces as a base class with discriminated subclasses.
The Swift screen adds a switch over `SubmitResultStateSuccess` /
`SubmitResultStateError` to render the post-submit banner.

### 4. Privacy policy update

`docs/public/privacy-policy/index.html` and the JA mirror gain a new
subsection inside "Diagnostic Data (Beta builds only)":

```html
<h3>Bug Reports</h3>
<p>When you submit a bug report from a beta build, the in-app reporter
sends the report content to a server-side proxy (Supabase Edge Function)
which creates a GitHub Issue on the Skeinly repository on your behalf.
The proxy runs under a GitHub App with permissions limited to creating
and updating Issues on the <code>b150005/skeinly</code> repository
only.</p>
<p>The data sent to the proxy is the same data shown in the in-app
preview screen before you tap Send: your description, a list of the
last 10 actions you took in the app, app version, OS version, device
model, locale, and your anonymous PostHog distinct ID. The proxy
forwards this verbatim to GitHub Issues. GitHub processes the data
according to its own privacy policy.</p>
<p>The transit metadata (your IP address, request timestamp) is
visible to Supabase but is not persisted by the proxy code. We do not
correlate transit metadata with the reported content.</p>
<p>You can submit reports anonymously by signing out of your GitHub
account before viewing the resulting Issue page — the Issue itself
does not carry your GitHub identity since the proxy creates it as the
Skeinly Beta Bug Reporter GitHub App, not as you personally.</p>
```

JA mirror with localized phrasing. Replaces (does NOT add to) the
existing Phase 39.5 wording about "URL prefill opens GitHub in your
browser" — that's no longer accurate.

### 5. Sub-slice plan

W5 lands across two sub-slices to keep each PR's review surface
shallow enough to inspect carefully. The split is safe because
**Phase 39.5's URL prefill path stays live through W5a** — only W5b
deletes it. There is no half-cutover state: every commit ships a
working bug-report flow.

#### W5a (Edge Function landing)

- `supabase/functions/submit-bug-report/` — `index.ts`,
  `github_app.ts`, `_fakes.ts`, `index.test.ts`,
  `github_app.test.ts`, `deno.json`, `README.md`
- `supabase/config.toml` — register `[functions.submit-bug-report]`
- `docs/{en,ja}/adr/020-phase-39-w5-bug-report-proxy.md` (this ADR)
- `docs/{en,ja}/release-secrets.md` — new EF-7 entry for the GitHub
  App trio (`SKEINLY_BUGREPORT_APP_ID` /
  `SKEINLY_BUGREPORT_INSTALLATION_ID` /
  `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM`)
- `CLAUDE.md` — W5a entry under `### Completed`; W5b under
  `### Planned`

User-attended at W5a close: GitHub App creation, secret registration,
Edge Function deploy, curl-based smoke test against the deployed
function. Clients still use URL prefill — no client-visible behavior
change.

#### W5b (KMP client cutover)

- `BugReportProxyClient` (commonMain) — new
- `BugSubmissionLauncher` (commonMain expect + Android/iOS actuals)
  — deleted
- `BugReportPreviewViewModel` — submit signature suspend-ified, state
  extended with `submitResult`
- `BugReportPreviewScreen.kt` (Compose) — banner/toast on
  `submitResult`
- `iosApp/iosApp/Screens/BugReportPreviewScreen.swift` — submitResult
  switch
- Koin wiring (`ViewModelModule.kt`,
  `PlatformModule.{android,ios}.kt`)
- `docs/public/privacy-policy/index.html` + JA mirror — replaces the
  "URL prefill" wording with proxy-based description
- `BugReportPreviewViewModelTest` — rewritten for suspend submit
- New `BugReportProxyClientTest` — Ktor MockEngine
- CLAUDE.md `### Completed` entry for W5b

User-attended at W5b close: TestFlight + Play Internal smoke test
from a real beta build, verifying tap-submit creates an Issue without
opening a browser.

### 6. Explicitly NOT in W5

- **Attachments (screenshots, logs).** GitHub Issues API supports
  attachments only via a separate `/uploads/` endpoint with
  `multipart/form-data`. Phase 39 W5 keeps the body-only shape; W5+
  may add. Phase 39 already documented "testers drag-and-drop
  screenshots on the Issue page after submit" — that workflow still
  works for testers with GitHub accounts; testers without accounts
  lose screenshot attachment but can describe in body.
- **Issue assignment / reviewer setup.** Single label `beta-bug`
  applied; assignment happens in repo via existing automation if any.
- **Comment threading.** Submitted Issues are read-only from the app
  side; testers can comment via the GitHub UI if signed in.
- **Multi-repo target.** Hardcoded `b150005/skeinly`.
- **Auth via the tester's GitHub identity.** Out of scope; proxy acts
  as the GitHub App.
- **Persistent rate-limit storage.** In-memory only.
- **Issue update / close from app.** Create-only.

## Consequences

### Positive

- In-app completion replaces 2-screen friction; submission rate
  measurably improves.
- GitHub-account-free reporting unblocks future non-developer tester
  outreach.
- URL length ceiling lifted — Phase 39+ can grow the diagnostic body
  without browser-compat concerns.
- `distinct_id` stays in the body only because the same body is sent
  end-to-end; transit metadata stays on Supabase, not in the GitHub
  Issue.
- Single-PR landing keeps the tree green; no half-cutover state.
- Standardizes the third-party-credential-bearing Edge Function
  pattern (notify-on-write, revenuecat-webhook, submit-bug-report) —
  future server-side integrations follow the same shape.

### Negative

- Deploy is now in the critical path. If the Edge Function is down,
  bug reporting is down. Mitigation: monitoring via Supabase
  function logs; on extended outage, testers fall back to direct
  GitHub Issue creation through the web UI (still available, just
  no auto-payload).
- One more vendor surface (GitHub App). Annual private-key rotation
  recommended; PEM secret rotation is a 4-step manual operation
  (generate new key on App page, register in Supabase, deploy,
  delete old key on App page).
- KMP code refactor — `BugSubmissionLauncher` delete + caller
  changes. Modest scope; one PR.

### Neutral

- ~250 LOC Edge Function + ~60 LOC KMP client + ~80 LOC test = ~400
  LOC net add.
- One new Edge Function = +1 Supabase project surface but no migration
  or table.
- Privacy policy diff is descriptive (no new data collected; the
  data was already disclosed for the URL prefill path).

## Considered alternatives

### URL prefill retention (Phase 39.5 keep-as-is)
Rejected per Q1 deliberation. Friction is real at non-developer tester
scale; URL ceiling will hit Phase 39+ payload growth; single-path
keeps maintenance simple.

### Client-side GitHub OAuth in-app
Rejected per ADR-015 §7. Adds OAuth flow + token storage; tester must
authorize a new OAuth app on first submission; doesn't solve
non-GitHub-user case. Rejected at Phase 39.5 design time; still
rejected.

### Separate `b150005/skeinly-bug-reports` private repo
Rejected per Q2 deliberation. Splits triage between two trackers for
marginal privacy gain that's already covered by the existing privacy
policy disclosure and the closed-beta consent.

### Persistent rate-limit storage (`bug_report_throttle` table)
Rejected per Q3 deliberation. Overkill at 10-tester scale. YAGNI;
revisit at Phase 40 GA if abuse signals appear.

## References

- ADR-015 (Phase 39 F2 beta bug reporting): the URL prefill shape this ADR replaces
- ADR-017 (Phase 24 push notifications): pattern reuse for Edge Function third-party-credential custody
- ADR-018 (Phase 24.3 push send paths): pattern reuse for JWT-based GitHub-style API authentication
- GitHub Apps documentation:
  - https://docs.github.com/en/apps/creating-github-apps
  - https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#generate-an-installation-access-token-for-an-app
  - https://docs.github.com/en/rest/issues/issues?apiVersion=2022-11-28#create-an-issue
- `notify-on-write` Edge Function (Phase 24.1 / commit 1ed59e2 forward) — code shape precedent
- `revenuecat-webhook` Edge Function (Phase 39.0.1 / commit 2752a30) — Deno test shape precedent
