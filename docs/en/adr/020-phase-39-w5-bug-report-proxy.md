# ADR-020: Phase 39 W5 — GitHub App Bug Report Proxy

## Status

Accepted (2026-05-11), consolidated 2026-05-12 (see §Revision history at end).

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

**Scope beyond beta.** The GitHub App, Edge Function, secrets, and
Issue label are **NOT beta-only**. The same channel carries over to
Phase 40 GA unchanged for all users (bug reports, feature requests,
general feedback). Only the **in-app entry points** stay
`BuildFlags.isBeta`-gated for Phase 39 closed-beta operation —
Phase 40 GA will open at least the Settings → "Send Feedback" entry
to all users. The shake / 3-finger-long-press gestures may remain
Beta-only as power-user affordances. The vendor artifacts therefore
carry no "beta" branding: a tester finishing Phase 39 sees the same
"Skeinly Feedback" channel they will continue to use in GA, and a
post-GA user's report does not surface in Issues with a stale
`[Beta]` prefix or `beta-bug` label.

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
- **Supabase-layer auth: deliberately disabled** (see Q4 below). The
  function runs with `verify_jwt = false`; the real auth that gives
  the function meaning is the GitHub App's three secrets (App ID,
  Installation ID, Private Key PEM) which the function uses
  downstream when calling the GitHub API.
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
1. https://github.com/settings/apps/new — create "Skeinly Feedback"
2. Repository permissions → **Issues: Read & write**; everything else None.
3. Where can this GitHub App be installed? → **Only on this account**
4. Webhook section → uncheck "Active" (no webhooks needed).
5. Save — note **App ID** displayed at top of the settings page.
6. Generate a **private key** (.pem) on the App settings page; download.
7. Install App → select `b150005/skeinly` only → note the **Installation
   ID** from the install URL
   (`github.com/settings/installations/<INSTALL_ID>`).
8. On `b150005/skeinly`, ensure the `feedback` Issue label exists
   (Issues → Labels → New). The Edge Function default-applies this
   label; if the label is absent GitHub returns 422
   `VALIDATION_FAILED`.
9. Register Supabase Edge Function secrets:
   - `SKEINLY_BUGREPORT_APP_ID` — the App ID number
   - `SKEINLY_BUGREPORT_INSTALLATION_ID` — the Installation ID number
   - `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM` — full PEM contents
     (multi-line, starts with `-----BEGIN RSA PRIVATE KEY-----`)
10. Edge Function deploy (autonomous via the Skeinly side):
    `git checkout main && git pull && supabase functions deploy submit-bug-report`
11. Smoke test (manual): trigger a bug report from a TestFlight /
    Play Internal build; verify Issue lands in `b150005/skeinly/issues`.

Steps 1–9 are user-attended (GitHub UI + 2FA + secret rotation outside
autonomous reach). Step 10 can be autonomous. Step 11 requires a real
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

**Q4: What auth model gates Edge Function invocation?**
Resolved: **(c) Unauthenticated client (`verify_jwt = false`); real
auth lives downstream at the GitHub API call.**

Considered:
- **(a) `verify_jwt = true` + Supabase user JWT in `Authorization`.**
  Rejected. A user reporting a bug may not be signed in to Supabase
  Auth — in fact the sign-in flow itself could be the bug they want
  to report. Gating on Supabase user auth would block a legitimate
  use case.
- **(b) `verify_jwt = true` + publishable key in `Authorization`.**
  Rejected. As of the 2025-11-01 Supabase API-key transition, the
  project ships a `sb_publishable_*` key which is NOT a JWT. The
  Supabase edge layer rejects non-JWT values in `Authorization` with
  HTTP 401 `UNAUTHORIZED_INVALID_JWT_FORMAT` before reaching the
  function. Moving the publishable key to the `apikey` header (the
  supported channel per [Supabase docs](https://supabase.com/docs/guides/functions/auth))
  still fails `verify_jwt = true` because `apikey` identifies the
  project but does not satisfy the JWT check
  (`UNAUTHORIZED_NO_AUTH_HEADER`).
- **(c) `verify_jwt = false` (chosen).** Matches the
  Supabase-documented pattern for **unauthenticated client-invoked
  functions** and the existing repo precedent (`notify-on-write` and
  `revenuecat-webhook` both `verify_jwt = false`, both authenticate
  via custom Bearer shared-secrets read by the function itself).
  Real auth is provided by the GitHub App's three secrets used in
  the downstream GitHub API call — that is the function's meaningful
  auth boundary. The publishable key is public anyway, so a
  Supabase-layer gate on it would have been performative.

The client still sends `apikey: <publishable_key>` (defensive +
forward-compat with `supabase-js`'s standard pattern for a future
build that wants to layer a user-session JWT on top via
`Authorization: Bearer <user_jwt>`). The Edge Function does not
gate on `apikey`; it only reads the tail of `apikey` (with
`Authorization` fallback) as a seed for `computeSourceHash` to
differentiate rate-limit windows per caller.

Abuse prevention restated: per §2 below, the in-memory rate limit
(5 reports/hour per source-hash on `x-real-ip` + auth-tail) is the
actual defense. At ≤10-tester closed-beta scale and even at Phase 40
GA scale (≤O(10K) installs), an Edge Function instance handling at
worst N×5 requests/hour where N is the number of unique source IPs
sits comfortably within Supabase's function-invocation budget.

## Decision

### 1. Architecture

```
┌──────────────┐         ┌──────────────────────────────┐
│ App build    │ POST    │ Supabase Edge Function       │
│ (iOS/Android)│ ───────▶│ submit-bug-report            │
│              │         │ (verify_jwt = false)         │
│ BugReport    │         │                              │
│ PreviewVM    │         │ 1. apikey header → source    │
│              │         │    hash seed (not gated)     │
│ Ktor client  │         │ 2. Rate limit check          │
│              │         │ 3. Validate length / shape   │
│ apikey:      │         │ 4. JWT sign with App PEM     │
│   <publishable>│       │ 5. Exchange for install token│
└──────────────┘         │ 6. POST /repos/.../issues    │
                         │ 7. Return {number, html_url} │
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

Registered in `supabase/config.toml` as:

```toml
[functions.submit-bug-report]
verify_jwt = false
```

See Q4 above for the rationale; the function is the
unauthenticated-client-invocation sibling of `notify-on-write` and
`revenuecat-webhook`. Real auth happens downstream at the GitHub API
call via the App's three secrets.

#### Request

```http
POST /functions/v1/submit-bug-report HTTP/1.1
apikey: <SUPABASE_PUBLISHABLE_KEY>
Content-Type: application/json

{
  "title": "tap Save crashes on iOS 26.4",
  "body": "## Description\n…\n## Reproduction context\n…",
  "labels": ["feedback"]
}
```

`apikey` carries the project's `sb_publishable_*` key. The Edge
Function does not validate the key (Supabase-layer auth is off); it
only uses the tail of `apikey` (with `Authorization` fallback for
forward-compat) as a seed for the per-caller rate-limit hash.

`labels` is optional (defaults to `["feedback"]`). Phase 39 W5
hardcodes the single label; future slices may extend (e.g.
screen-tagged labels).

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
SHA-256 of the `apikey` (or `Authorization`) header tail. Window:
1 hour sliding (record array of timestamps, drop entries > 1h, reject
if count ≥ 5).

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

{ "title": "...", "body": "...", "labels": ["feedback"] }
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
        // Header: apikey: <SupabaseConfig.publishableKey>
        // (NOT Authorization: Bearer — see ADR-020 Q4.)
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

The client sends the publishable key in the `apikey` header (per
Q4). It does NOT send `Authorization: Bearer` because the Edge
Function runs with `verify_jwt = false` and any caller-supplied
JWT would be ignored anyway. A future build that wants to attribute
reports to signed-in users adds a session JWT in `Authorization`
without touching the `apikey` header.

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

The title sent to the proxy is the description itself (no
`[Beta]` prefix — Phase 40 GA reuses this surface unchanged). Empty
descriptions default to `Bug report`.

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
// commonMain di/RepositoryModule.kt
single { BugReportProxyClient(get(qualifier = symbolPackHttpClient), get<SupabaseConfig>()) }

// ViewModel factory passes the suspend lambda
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

`docs/public/privacy-policy/index.html` and the JA mirror replace
Phase 39.5's "URL prefill" disclosure with proxy-based wording:

```html
<h3>Bug Reports</h3>
<p>When you submit a bug report from a beta build, the in-app reporter
sends the report content to a server-side proxy (Supabase Edge Function)
which creates a GitHub Issue on the Skeinly repository on your behalf.
The proxy runs under a GitHub App ("Skeinly Feedback") with permissions
limited to creating and updating Issues on the
<code>b150005/skeinly</code> repository only.</p>
<p>The data sent to the proxy is the same data shown in the in-app
preview screen before you tap Send: your description, a list of the
last 10 actions you took in the app, app version, OS version, device
model, locale, and your anonymous PostHog distinct ID. The proxy
forwards this verbatim to GitHub Issues. GitHub processes the data
according to its own privacy policy.</p>
<p>The transit metadata (your IP address, request timestamp) is
visible to Supabase but is not persisted by the proxy code. We do not
correlate transit metadata with the reported content.</p>
<p>The Issue itself does not carry your GitHub identity — the proxy
creates it as the Skeinly Feedback GitHub App, not as you
personally.</p>
```

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
  with `verify_jwt = false`
- `docs/{en,ja}/adr/020-phase-39-w5-bug-report-proxy.md` (this ADR)
- `docs/{en,ja}/release-secrets.md` — new EF-7 entry for the GitHub
  App trio (`SKEINLY_BUGREPORT_APP_ID` /
  `SKEINLY_BUGREPORT_INSTALLATION_ID` /
  `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM`)
- `CLAUDE.md` — W5a entry under `### Completed`; W5b under
  `### Planned`

User-attended at W5a close: GitHub App creation, `feedback` label
creation, secret registration, Edge Function deploy, curl-based
smoke test against the deployed function. Clients still use URL
prefill — no client-visible behavior change.

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
- Koin wiring (`ViewModelModule.kt`, `RepositoryModule.kt`,
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
- **Issue assignment / reviewer setup.** Single label `feedback`
  applied; assignment happens in repo via existing automation if any.
- **Comment threading.** Submitted Issues are read-only from the app
  side; testers can comment via the GitHub UI if signed in.
- **Multi-repo target.** Hardcoded `b150005/skeinly`.
- **Auth via the tester's GitHub identity.** Out of scope; proxy acts
  as the GitHub App.
- **Persistent rate-limit storage.** In-memory only.
- **Issue update / close from app.** Create-only.
- **Phase 40 GA in-app entry-point opening.** The
  `BuildFlags.isBeta` gate on Settings → "Send Feedback" stays in
  W5b. Tech Debt Backlog → `Bug-report Settings entry GA opening`
  tracks the GA gate removal.

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
  pattern (`notify-on-write`, `revenuecat-webhook`,
  `submit-bug-report`) — all three are `verify_jwt = false` with
  real auth happening at the downstream API call.
- Scope-broad branding ("Skeinly Feedback") carries over to Phase 40
  GA unchanged; the channel survives the beta-to-GA transition with
  no vendor-artifact churn.

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

### `verify_jwt = true` + Supabase user session JWT
Rejected per Q4 deliberation. Bug reporting must work for unauthenticated
callers — the sign-in flow itself can be the bug the user wants to
report. Gating on Supabase user JWT would block a legitimate use
case. Real auth lives at the GitHub API call downstream.

### `verify_jwt = true` + publishable key in `Authorization`
Rejected per Q4 deliberation. As of the 2025-11-01 Supabase API-key
transition, the project's publishable key is `sb_publishable_*` — not
a JWT. `verify_jwt = true` rejects non-JWT values in `Authorization`
at the edge layer (`UNAUTHORIZED_INVALID_JWT_FORMAT`) before reaching
the function. Moving the key to the `apikey` header fixes that error
but still fails `verify_jwt = true` because `apikey` identifies the
project, not the caller (`UNAUTHORIZED_NO_AUTH_HEADER`). The
Supabase-documented pattern for client-invoked functions without a
user session is `verify_jwt = false` — same shape as
`notify-on-write` and `revenuecat-webhook`.

### "Skeinly Beta Bug Reporter" GitHub App name + `[Beta]` title prefix + `beta-bug` label
Rejected on pre-deployment review. The GitHub App + Edge Function +
Issue label are reused unchanged by general users post-Phase 40 GA;
only the in-app entry points are `BuildFlags.isBeta`-gated. Vendor
artifacts therefore carry no "beta" branding so the channel survives
the GA transition without rename + secret-recreate churn.

## Revision history

- 2026-05-11 — Initial draft and acceptance. Original naming was
  "Skeinly Beta Bug Reporter" + `beta-bug` label + `[Beta]` title
  prefix; client auth proposed as `Authorization: Bearer <anon_jwt>`
  with `verify_jwt = true`.
- 2026-05-12 — Pre-deployment review: vendor artifacts renamed to
  "Skeinly Feedback" + `feedback` label + no title prefix (scope
  beyond beta).
- 2026-05-12 — Smoke-test fix: client auth header changed to
  `apikey: <publishable_key>` after the 2025-11-01 Supabase
  `sb_publishable_*` transition broke the JWT-in-Authorization
  assumption.
- 2026-05-12 — Smoke-test fix: `verify_jwt = false` adopted; real
  auth lives at the downstream GitHub API call. Q4 added and §1 /
  §2 / §3 updated to reflect the unauthenticated-client pattern.
- 2026-05-12 — ADR consolidated: three amendment sections folded
  into the main body; this revision history block is the
  authoritative trail.

## References

- ADR-015 (Phase 39 F2 beta bug reporting): the URL prefill shape this ADR replaces
- ADR-017 (Phase 24 push notifications): pattern reuse for Edge Function third-party-credential custody
- ADR-018 (Phase 24.3 push send paths): pattern reuse for JWT-based GitHub-style API authentication
- GitHub Apps documentation:
  - https://docs.github.com/en/apps/creating-github-apps
  - https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#generate-an-installation-access-token-for-an-app
  - https://docs.github.com/en/rest/issues/issues?apiVersion=2022-11-28#create-an-issue
- Supabase Edge Functions auth doc: https://supabase.com/docs/guides/functions/auth
- `notify-on-write` Edge Function (Phase 24.1 / commit 1ed59e2 forward) — code shape precedent + `verify_jwt = false` precedent
- `revenuecat-webhook` Edge Function (Phase 39.0.1 / commit 2752a30) — Deno test shape precedent + `verify_jwt = false` precedent
