# ADR-017 — Phase 24: Push Notifications

> **Status**: Proposed (2026-05-09)
> **Phase**: 24 (pre-Phase-40 GA, post-Phase-39-beta launch HARD-GATE)
> **Supersedes**: none
> **Superseded by**: none
> **Related**: ADR-013 (collaboration core, generates the activity events Phase 24 surfaces), ADR-014 (PR workflow, primary event source for Phase 24 MVP), ADR-015 (Phase 39 beta bug reporting — establishes consent + opt-in patterns Phase 24 reuses).
> **Tracking**: F2 = Phase 24 (Push Notifications). 6 sub-slices per §6 below. Sub-slice 24.0 (this ADR + CLAUDE.md promotion) — no code. Sub-slices 24.1 → 24.6 each ship independently.

JA summary: [../../ja/adr/017-phase-24-push-notifications.md](../../ja/adr/017-phase-24-push-notifications.md) (cut alongside this ADR).

## 1. Context

Phase 38 shipped a complete pull-request workflow (open / list / detail / comment / close / merge / conflict resolution). Phase 36 + 37 shipped fork + collaboration history. Phase 39 closed beta is the first time these collaboration surfaces will be exercised by real testers other than the maintainer.

**The collaboration loop is currently silent across processes.** A user opens a PR against a pattern they forked from someone else's; the upstream owner has no way to know unless they (a) open the Skeinly app, (b) navigate to ProjectList → overflow → "Pull requests" → Incoming, (c) check the unread badge (which Phase 38.2 explicitly deferred). For closed beta with 5–10 testers across different time zones, the median time-to-response on a PR could easily exceed 24 hours — long enough that the originating tester loses the mental model of what they were proposing and disengages.

**User policy shift 2026-05-09**: previously Phase 24 was listed in CLAUDE.md "Post-v1.0" alongside Phase 21 (macOS target). The user explicitly reprioritized Phase 24 to land BEFORE Phase 39 closed-beta tester invites go out. Rationale (per the user direction): the closed-beta is the empirical validation that the collaboration features are usable end-to-end; without push, "usable" reduces to "the maintainer + one other tester can manually coordinate via out-of-band channels", which is not a meaningful test of the feature surface.

**Vendor pillars are already prepared**:
- APNs `.p8` Auth Key generated (vendor-setup A0a-2) and registered as Supabase Edge Function secret `APPLE_APNS_KEY_P8` (release-secrets EF-1) + `APPLE_APNS_KEY_ID` (EF-2). `APPLE_TEAM_ID` reused from the iOS release pipeline.
- Firebase Service Account JSON registered as Supabase Edge Function secret `FIREBASE_SERVICE_ACCOUNT_JSON` (release-secrets EF-3) — used to mint short-lived OAuth 2.0 tokens for FCM HTTP v1.
- iOS Bundle ID `io.github.b150005.skeinly` has Push Notifications capability enabled in the App ID + the provisioning profile (vendor-setup A0a-1 + A0a-4).
- Android Firebase project `skeinly` has the FCM SDK SHA-1s registered for both debug + release signing keys.

**What's missing for F2 to land**:
- Edge Function `notify-on-write` itself (referenced across release-secrets.md but never authored).
- `device_tokens` table + RLS for per-user token storage.
- Client-side device token registration logic — KMP `expect/actual` for both platforms with `iosMain` calling APNs registration via `UNUserNotificationCenter` and `androidMain` calling `FirebaseMessaging.getInstance().token`.
- Notification permission UX — pre-permission dialog explainer + OS prompt timing.
- Deep link routing from notification tap to the appropriate screen.
- iOS notification handler (`UNUserNotificationCenterDelegate`) + Android handler (`FirebaseMessagingService`).
- Trigger source mechanism — pure SQL trigger? Database Webhook? Edge Function chain?
- Event scope decision — which collaboration events warrant a push interruption?
- Localization model — server pushes localized text, or pushes a `type` + parameters and client renders?
- Privacy policy update covering device token + locale collection.

This ADR locks in the data shape, transport choice, trigger mechanism, event scope, and integration boundary before any code lands. Same precedent shape as ADR-013 / ADR-014 / ADR-015 / ADR-016.

## 2. Decisions (high-level)

1. **Two-stack push transport**: iOS = APNs direct (`.p8` + JWT signing in Edge Function); Android = FCM HTTP v1 (Firebase Admin SDK service account → OAuth 2.0 access token → `https://fcm.googleapis.com/v1/projects/<project-id>/messages:send`). NO Firebase iOS Push SDK (avoids a Firebase iOS dependency for a feature Apple has natively).
2. **Trigger source = Supabase Database Webhooks**, not Postgres triggers + `pg_net`. Database Webhooks are a Supabase-managed feature that POST to an Edge Function URL on row INSERT/UPDATE/DELETE; the secret is auto-signed; no `pg_net` extension dependency.
3. **Event source = single Edge Function `notify-on-write`** that receives Database Webhook payloads from 3 tables: `pull_requests` (INSERT, UPDATE), `pull_request_comments` (INSERT), and reserves `activities` for Phase 24+. The Edge Function maps each row to a notification template + recipient set + dispatches.
4. **Phase 24 MVP event scope (3 events)**: (a) PR opened on a pattern the user owns (target owner gets push), (b) new comment on a PR the user is participating in (author OR target owner gets push, excluding the comment author), (c) PR merged or closed by the other party (author gets push when target owner merges/closes; target owner gets push when author closes). These are explicit collaboration moments where the OS-level interruption is justified. Other event types in `activities` table (chart edits, project updates, etc.) are deliberately out of scope — push noise erodes engagement.
5. **`device_tokens` table** with `(user_id, platform, token)` UNIQUE composite key, RLS-gated own-row SELECT/INSERT/UPDATE/DELETE. Service-role bypass for the Edge Function lookup at notification fan-out time. Token rotation handled by client upsert; invalid token cleanup handled by APNs/FCM error code → Edge Function DELETE.
6. **Permission UX = deferred prompt pattern**: notification permission is NOT requested at app launch or onboarding. Pre-permission dialog appears at the user's first PR-related action (opening a PR, opening a PR detail, posting a comment) explaining "Get notified when collaborators respond"; the OS prompt fires only after the user taps "Enable" on the in-app explainer. iOS HIG + Android Material Design pre-prompt convention.
7. **Localization = server-side based on stored locale**: `device_tokens.locale` column carries BCP-47 tag (`en-US` / `ja-JP`); Edge Function reads it and selects EN/JA from a small in-function string table per notification type. ~6 unique notification body strings × 2 locales = 12 entries hard-coded in the Edge Function. Avoids client-side rendering hop (push notifications surface in OS notification center even when app is killed; client cannot render then).
8. **Deep link routing** via notification `data` payload: each notification carries `route` field (e.g. `pull-request/<prId>`); iOS `UNUserNotificationCenterDelegate.userNotificationCenter(_:didReceive:withCompletionHandler:)` and Android `FirebaseMessagingService.onMessageReceived` parse and route to the appropriate Compose / SwiftUI screen via the existing NavGraph / `AppRouter`.
9. **NO Phase 24 in-app notification feed** — the existing `activities` table feeds the in-app activity feed surface (Phase 36.5). Push is a cross-medium amplifier of the same events, not a duplicate UI.

## 3. Decisions (detailed)

### 3.1 iOS APNs direct vs FCM-mediated

The Firebase iOS SDK supports proxying iOS push through FCM, which would unify the Edge Function code path (single FCM call, FCM forwards to APNs). Considered + rejected.

**Why APNs direct**:
- The Apple stack is native to iOS — no extra SDK dependency on the iOS app side. Phase 24.2 needs only `UserNotifications.framework` + the existing AppDelegate's `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)` callback that's been there since iOS 10. No `Firebase/Messaging` pod / SwiftPM dep.
- APNs JWT signing in Edge Function (Deno) is a tiny code path: HMAC-ES256 over `{ "iss": APPLE_TEAM_ID, "iat": <now>, ... }` with `.p8` body as the signing key. Apple publishes the cert chain; no third-party validation library needed (the JOSE library `djwt` is already in the JSR registry).
- Lower latency: APNs direct is one HTTPS hop (Edge Function → APNs); FCM-mediated is two (Edge Function → FCM → APNs).
- One fewer credential rotation: FCM-mediated still requires APNs `.p8` registered with FCM (Firebase Console → iOS app → Cloud Messaging → upload `.p8`). APNs direct skips that registration step.

**Trade-off acknowledged**: two Edge Function code paths (APNs + FCM) instead of one. The complexity is bounded — both paths share the same `notify-on-write` envelope (recipient lookup, locale resolution, body templating) and diverge only at the HTTP call. ~50 lines of platform-specific code each. Acceptable.

### 3.2 Android FCM HTTP v1 (not legacy server key API)

FCM has two transport APIs: the deprecated server key API (sending to `https://fcm.googleapis.com/fcm/send` with `Authorization: key=<SERVER_KEY>`) and HTTP v1 (sending to `https://fcm.googleapis.com/v1/projects/<project-id>/messages:send` with `Authorization: Bearer <OAuth2_ACCESS_TOKEN>` minted from a service account JWT).

The legacy server key API is deprecated since 2024 and Google has announced sunset for early 2026 (post-Phase-40 timeline). HTTP v1 is the only forward-compatible choice.

**Implementation shape**:
1. Edge Function reads `FIREBASE_SERVICE_ACCOUNT_JSON` (already registered as EF-3).
2. Mints a short-lived JWT signed with the SA's private key, audience = `https://oauth2.googleapis.com/token`, scope = `https://www.googleapis.com/auth/firebase.messaging`.
3. Exchanges the JWT for an OAuth 2.0 access token (1 hour TTL).
4. Caches the access token in-memory (Edge Function instance lifetime is short — minutes — so cache lifetime is bounded by the runtime, no LRU needed).
5. POSTs the FCM v1 payload with `Authorization: Bearer <access_token>`.

The same `djwt` library used for APNs JWT signing handles the FCM SA JWT.

### 3.3 Trigger source: Supabase Database Webhooks

Three candidate mechanisms considered:

**(A) Postgres triggers + `pg_net` extension**: a `BEFORE INSERT` trigger on `pull_request_comments` calls `net.http_post(url := 'https://<project-ref>.supabase.co/functions/v1/notify-on-write', body := jsonb_build_object(...))`. Requires `pg_net` extension enabled (extra extension surface), trigger management lives in migration files (versioned with migrations but separate from the Webhook config UI).

**(B) Supabase Database Webhooks (managed feature)**: configured via Supabase Dashboard → Database → Webhooks → "Add a new hook" → select table + event types + Edge Function URL + free-form HTTP Headers. No `pg_net` dependency. UI-driven configuration but the config can be exported via `supabase db dump` for reproducibility.

**(C) Realtime subscription within Edge Function**: not how Edge Functions work — they're request/response, not long-lived listeners. Excluded.

**Decision: (B) Supabase Database Webhooks**. Rationale:
- Less infrastructure surface (no pg_net extension to manage / patch).
- Visible in Supabase Dashboard UI — easier for the maintainer to verify "is the webhook actually wired" without running SQL.
- Authentication via custom `Authorization: Bearer <secret>` HTTP header configured in the Dashboard's HTTP Headers section, verified by the Edge Function with constant-time string compare. Mirrors the `revenuecat-webhook` Bearer pattern for consistency.
- Standard Supabase pattern (reference: <https://supabase.com/docs/guides/database/webhooks>); follows the path of least surprise for future maintainers.

> **2026-05-09 amendment**: the original ADR cut described option (B) as "Supabase auto-signs the request with `webhook-secret` header (HMAC-SHA256)". This was inaccurate — per the [Supabase Database Webhooks doc](https://supabase.com/docs/guides/database/webhooks) and verified against the Dashboard UI, Database Webhooks do NOT auto-sign payloads. The Dashboard exposes only Method / URL / Timeout / HTTP Headers / HTTP Parameters; there is no signing-secret field and no signature header. Authentication is implemented via the custom-header path described above, which is the supported boundary. The decision (option B over A or C) stands; only the auth-mechanism details changed. See §3.9 for the full amendment.

**Trade-offs acknowledged**:
- Database Webhook configuration is NOT versioned in `supabase/migrations/`. Mitigation: add `supabase/webhooks.md` doc that describes the 3 webhook configurations + Dashboard navigation steps, refreshed at every Phase 24.x slice that adds/removes webhooks.
- The Bearer secret value sits as a plaintext literal inside each webhook config row (Dashboard does not expose a secret-store reference syntax). Mitigated by Dashboard ACLs scoped to project members; rotation requires updating 3 webhook header rows (in addition to the `supabase secrets set` call). Stripe-style HMAC-of-body would defend against TLS downgrade scenarios (vanishingly rare given Supabase HTTPS enforcement); accepted as v1 trade-off.

### 3.4 Event scope (3 MVP events)

**Triggered on table writes**:

| Event | Trigger | Recipient | Body template |
|---|---|---|---|
| PR opened | `pull_requests` INSERT | `target_pattern_id`'s owner (resolved server-side via `patterns` JOIN) | "{author_display_name} opened a pull request on {pattern_title}" |
| PR comment added | `pull_request_comments` INSERT | PR participant set MINUS comment author (= `pr.author_id` + `pr.target_pattern.owner_id` − `comment.author_id`) | "{author_display_name} commented on {pr_title}" |
| PR merged or closed | `pull_requests` UPDATE WHERE old.status = 'open' AND new.status IN ('merged', 'closed') | The other party (= `pr.author_id` if `auth.uid() == target_owner`, else `target_owner` if `auth.uid() == author`) | "{actor_display_name} {merged|closed} your pull request on {pattern_title}" |

**Excluded from MVP** (deferred to Phase 24+ if real signal surfaces):
- `activities` table writes (chart edits, project updates, fork events) — too noisy, low engagement value per push.
- `chart_revisions` INSERT — same reasoning.
- `subscriptions` table writes — user usually knows they just purchased / cancelled.
- `share` table writes (Phase 4b sharing) — already low-priority workflow.

**Recipient resolution**: each event type's Edge Function branch computes a `Set<userId>` of recipients, then SELECTs `device_tokens` rows for those user_ids, then dispatches one push per (user_id, platform, token) triple.

### 3.5 `device_tokens` table

```sql
CREATE TABLE public.device_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    platform        TEXT NOT NULL CHECK (platform IN ('ios', 'android')),
    token           TEXT NOT NULL,
    locale          TEXT NOT NULL DEFAULT 'en-US' CHECK (locale IN ('en-US', 'ja-JP')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, platform, token)
);

CREATE INDEX idx_device_tokens_user_platform
    ON public.device_tokens (user_id, platform);

ALTER TABLE public.device_tokens ENABLE ROW LEVEL SECURITY;

CREATE POLICY "device_tokens_select_own"
    ON public.device_tokens FOR SELECT
    USING (auth.uid() = user_id);

CREATE POLICY "device_tokens_insert_own"
    ON public.device_tokens FOR INSERT
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "device_tokens_update_own"
    ON public.device_tokens FOR UPDATE
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "device_tokens_delete_own"
    ON public.device_tokens FOR DELETE
    USING (auth.uid() = user_id);
```

**Locale CHECK closed enum**: only EN + JA per the project's i18n supported set. Extension to a 3rd locale requires a migration AND the in-Edge-Function string table addition.

**ON DELETE CASCADE on user_id**: when a user deletes their account (per ADR-005 `delete_own_account` RPC), all their device tokens cascade out. No dangling rows.

**Token rotation**: APNs / FCM device tokens can rotate (OS reset, app reinstall, etc.). Client always upserts on token receipt — `INSERT ... ON CONFLICT (user_id, platform, token) DO UPDATE SET updated_at = now(), locale = EXCLUDED.locale`. Old token rows accumulate but become invalid; cleanup happens via APNs/FCM error code path (see §3.10).

**No `last_seen_at` / app-version column**: not needed for Phase 24 MVP. Add later if push delivery analytics surface a need.

### 3.6 Permission UX (deferred-prompt pattern)

**Anti-pattern**: requesting notification permission at first app launch or onboarding completion. Industry data (Apple HIG / Android Material Design / Localytics studies) shows pre-launch denial rates of 50–80% when the permission ask is decontextualized. Once denied, the user has to navigate OS Settings to re-enable; in-app "ask again" is silently ignored.

**Phase 24 pattern**: deferred prompt at the user's first PR-related action. Specifically:
- First time the user opens `PullRequestListScreen` (Incoming filter) AND has any PRs in the list (signals "this user is in the collaboration loop").
- First time the user opens `PullRequestDetailScreen` (signals "this user cares about this specific PR").
- First time the user posts a comment on a PR (signals "this user has expressed intent to engage").

**The trigger logic** lives in a new `NotificationPermissionPrompter` service (KMP shared) that owns the "have we asked?" state (persisted via `Preferences` / `NSUserDefaults`). Each entry point checks `prompter.shouldPrompt(at: trigger)` and shows the in-app explainer Composable / SwiftUI sheet if true.

**Pre-permission dialog content**:
- Title: "Stay in the loop"
- Body: "Get notified when collaborators open pull requests, comment on yours, or merge your changes."
- Primary CTA: "Enable" → triggers OS permission prompt
- Secondary CTA: "Not now" → records "asked + denied via in-app dismiss" + does NOT trigger OS prompt (preserves the user's ability to enable later from Settings without polluting the OS denial state)

**Settings entry**: a new "Notifications" row under Settings Beta section (pivots into the same section that Phase 39.4 added for diagnostic-data toggle). Row reads the current OS permission state (via UNUserNotificationCenter / NotificationManagerCompat) and shows "Enabled" / "Disabled (open Settings to change)". Tapping the row opens OS settings deep-link via `UIApplication.openSettingsURLString` / Android `Settings.ACTION_APP_NOTIFICATION_SETTINGS`.

### 3.7 Localization

Two candidate models:

**(A) Client-renders**: server pushes `{ type: "pr_opened", params: { actor_id, pattern_id } }`; client receives, looks up its current locale, renders body locally. Requires app to be running OR the OS Notification Service Extension (iOS) / FirebaseMessagingService (Android) to do the rendering pre-display.

**(B) Server-renders**: server reads `device_tokens.locale`, picks the appropriate string from an in-Edge-Function table, formats with parameters, sends pre-rendered body.

**Decision: (B)**. Rationale:
- Push notifications surface in OS notification center even when the app is killed. iOS Notification Service Extension can run mutating code, but it adds a separate target + bundle config. Android FirebaseMessagingService can render but requires the app process to be alive (not always).
- Locale set is small (EN + JA) and the unique notification template count is small (~6 strings). Hard-coding 12 entries in the Edge Function is bounded and grep-friendly.
- The locale field also unlocks future personalization (timezone-aware send time, tester vs production segmentation) without a client-side path.

**Trade-off acknowledged**: notification body strings live in 2 places — `composeResources/values/strings.xml` (Compose) + `iosApp/.../*.xcstrings` (iOS) for in-app surfaces, AND inside `notify-on-write/index.ts` for push surfaces. There is no automatic verification that the EN and JA Edge Function strings stay in sync. Mitigation: add a pure Deno test in `notify-on-write/strings.test.ts` that asserts `Object.keys(EN) == Object.keys(JA)` for parity.

**Initial 6 templates**:

| Template key | EN | JA |
|---|---|---|
| `pr_opened` | "{actor} opened a pull request on {pattern}" | "{actor}さんが{pattern}にプルリクエストを開きました" |
| `pr_commented` | "{actor} commented on {pr_title}" | "{actor}さんが{pr_title}にコメントしました" |
| `pr_merged_to_author` | "{actor} merged your pull request on {pattern}" | "{actor}さんがあなたの{pattern}へのプルリクエストをマージしました" |
| `pr_closed_to_author` | "{actor} closed your pull request on {pattern}" | "{actor}さんがあなたの{pattern}へのプルリクエストをクローズしました" |
| `pr_closed_to_owner` | "{actor} closed their pull request on {pattern}" | "{actor}さんが{pattern}へのプルリクエストをクローズしました" |
| `actor_someone` | "Someone" (fallback when actor's display_name is null) | "誰か" |

Locale fallback: unknown locale → `en-US`. Missing template key → log warning + send `pr_opened` placeholder so the notification still surfaces (better than silent drop).

### 3.8 Deep link routing

Each push notification carries a `data` payload alongside the visible `notification` body:

```json
{
  "notification": {
    "title": "Skeinly",
    "body": "<localized template body>"
  },
  "data": {
    "type": "pr_opened",
    "route": "pull-request/<pr_uuid>",
    "actor_id": "<user_uuid>",
    "pattern_id": "<pattern_uuid>"
  }
}
```

**iOS**: `UNUserNotificationCenterDelegate.userNotificationCenter(_:didReceive:withCompletionHandler:)` receives a `UNNotificationResponse`; the delegate parses `response.notification.request.content.userInfo["data"] as? [String: Any]`, extracts `route`, and posts a `Notification.Name("openPushRoute")` notification carrying the route string. The Skeinly `AppRootView` SwiftUI body subscribes via `.onReceive(NotificationCenter.default.publisher(for: .openPushRoute))` and calls `path.append(Route.pullRequestDetail(prId: ...))` on the `NavigationStack`'s `path` binding. Same pattern Phase 39.5 used for shake-to-bug-report (`onShake` + NotificationCenter publisher).

**Android**: `FirebaseMessagingService.onMessageReceived(RemoteMessage)` parses `remoteMessage.data["route"]`, builds an `Intent` carrying the route extra, posts a `NotificationCompat.Builder` whose `setContentIntent(PendingIntent.getActivity(...))` carries the same intent. When the user taps the notification, MainActivity's `onCreate` / `onNewIntent` reads the extra and dispatches to `navController.navigate(...)`. Standard Android FCM data-payload pattern.

**Phase 24 routes** (matches Phase 38's NavGraph):
- `pull-request/<prId>` → `PullRequestDetail(prId:)`
- (Phase 24+) `pattern/<patternId>` → `PatternDetail(...)` if other event types land

**Cold-start handling**: if the app was killed, both platforms surface the notification's data payload via the launch intent / launch options. iOS: `UIApplicationDelegate.application(_:didFinishLaunchingWithOptions:)` `launchOptions[UIApplication.LaunchOptionsKey.remoteNotification]`. Android: `Activity.intent.extras` on first onCreate. Both paths feed the same `openPushRoute` mechanism with a one-shot delay until the NavGraph is mounted (via `LaunchedEffect` + `path.append` after first composition).

### 3.9 Edge Function `notify-on-write` shape

```
POST https://<project-ref>.supabase.co/functions/v1/notify-on-write
Authorization: Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET value>
Content-Type: application/json

Body (Database Webhook payload):
{
  "type": "INSERT" | "UPDATE",
  "table": "pull_requests" | "pull_request_comments",
  "schema": "public",
  "record": { ...full row... },
  "old_record": { ...for UPDATE only... }
}
```

> **2026-05-09 amendment**: the original cut of this ADR specified HMAC-SHA256 body-signature verification against an `x-supabase-webhook-signature` header. Verification of the [Supabase Database Webhooks doc](https://supabase.com/docs/guides/database/webhooks) and the actual Dashboard UI revealed that **Supabase Database Webhooks do NOT auto-sign payloads** — the only configuration surface exposed is Method / URL / Timeout / HTTP Headers / HTTP Parameters, with no signing-secret field and no signature header. Authentication is therefore implemented as a custom `Authorization: Bearer <secret>` HTTP header, configured per-webhook in the Dashboard's HTTP Headers section, mirroring the `revenuecat-webhook` Bearer pattern (Phase 39 prep). The secret name `SKEINLY_DATABASE_WEBHOOK_SECRET` is unchanged. The Edge Function does constant-time string compare; no HMAC computation needed.

**Function code path**:
1. Verify the `Authorization: Bearer <value>` header against `SKEINLY_DATABASE_WEBHOOK_SECRET` via constant-time string compare. Reject with HTTP 401 on missing / malformed / mismatched value. (`SKEINLY_` prefix is load-bearing — Supabase reserves `SUPABASE_*` for platform-injected env vars per [Edge Function limits doc](https://supabase.com/docs/guides/functions/limits#secrets), and `supabase secrets set` rejects names starting with that prefix.)
2. Branch on `table` + `type`:
   - `pull_requests` INSERT → resolve target owner via `patterns` JOIN → call `dispatchPush(recipientUserId, "pr_opened", { actor, pattern })`.
   - `pull_request_comments` INSERT → resolve PR participants via `pull_requests` JOIN → MINUS comment.author_id → for each remaining recipient call `dispatchPush(recipient, "pr_commented", { actor, pr_title })`.
   - `pull_requests` UPDATE WHERE old.status = 'open' → branch on new.status:
     - `merged` → call `dispatchPush(pr.author_id, "pr_merged_to_author", { actor, pattern })` (target owner is the actor; actor never receives their own action).
     - `closed` → if actor == author: notify target owner with `pr_closed_to_owner`. If actor == target owner: notify author with `pr_closed_to_author`.
3. `dispatchPush(userId, templateKey, params)`:
   a. SELECT `device_tokens` WHERE user_id = userId.
   b. For each row, render the localized body using `device_tokens.locale`.
   c. POST to APNs (platform=ios) or FCM (platform=android).
   d. On 410 Gone (APNs `BadDeviceToken`) / FCM `UNREGISTERED` → DELETE the row from `device_tokens`.
   e. Other failures → log + Sentry breadcrumb.
4. Return 200 unconditionally to Supabase's webhook retry logic — push delivery failures are not webhook failures.

**Rate-limit considerations**: APNs allows ~9000 req/sec/team; FCM v1 allows ~600 req/min/project. Both are far above Phase 39 closed-beta scale (5–10 testers × handful of PR events / day). No rate-limiter needed for v1.

### 3.10 Token cleanup on send failures

APNs and FCM both have well-defined error codes for "this token is no longer valid":

- **APNs**: HTTP 410 with `reason: "BadDeviceToken"` or `"Unregistered"` → token is invalid (uninstall, OS reset, etc.).
- **FCM v1**: HTTP 404 with `error.status: "NOT_FOUND"` and `details[].errorCode: "UNREGISTERED"` → token is invalid.

On either response, the Edge Function executes `DELETE FROM device_tokens WHERE token = $1`. Bounded surface — no race condition because the token is the unique identifier even across user_ids.

Other transient errors (5xx, timeouts, throttling) → log + retry on next webhook fire that involves the same recipient. No explicit retry queue.

### 3.11 Privacy + consent

Push notifications are an opt-in surface; the user must explicitly grant OS permission. There is no implicit "we ship pushes regardless" path.

**Privacy policy update** (Phase 24.6):
- New "Push Notifications" subsection in `docs/public/privacy-policy/index.html` (EN) + JA mirror.
- Disclose: device token collected via APNs / FCM (anonymous identifier issued by Apple / Google, not directly mappable to a person); locale (BCP-47 tag) collected for localized notification body rendering; notification preferences (which event types are subscribed to — Phase 24+ if granular toggles land).
- Disclose: notification body content includes other users' display names (e.g. "Alice opened a pull request"). This is data ABOUT other Skeinly users that the recipient sees on their lock screen, not data ABOUT the recipient.
- Revocation: OS-level (Settings → Notifications → Skeinly → off). On account deletion (ADR-005), `device_tokens` cascade-deletes via FK.

**Consent model**: OS-level permission grant IS the consent. No separate in-app analytics-style opt-in toggle (the OS permission already covers the meaningful consent moment). The pre-permission explainer (§3.6) provides informed-consent context.

## 4. Non-decisions (deferred)

These were considered + explicitly deferred to Phase 24+ or beyond. Listed so future maintainers see the negative space.

- **Topic-based subscriptions** (e.g. "all PRs opened against any of my patterns" as a single FCM topic). Useful at scale (thousands of users); over-engineered at Phase 24 MVP. Direct device-token push is fine for ≤100 testers.
- **Rich notification content** (images, custom UI, action buttons inline). Requires iOS Notification Service Extension + Android NotificationCompat custom RemoteViews. Out of scope for v1 collaboration push.
- **Notification grouping / threading** (e.g. "3 new comments on your PR"). Both platforms have built-in coalescence by `thread-identifier` (iOS) / `tag` (Android). Phase 24+ if testers complain about per-comment notification spam.
- **Scheduled / cron-like server-side notifications** (e.g. "Pro subscription expires in 3 days"). No Phase 24 use case; revisit when subscription renewal reminders surface as a real need.
- **In-app notification feed driven by push events**. The existing `activities` table + Phase 36.5 ActivityFeedScreen already covers this. Push is a cross-medium amplifier, not a duplicate UI.
- **Push for non-collaboration events** (chart edits, project updates, share-acceptance). Push noise erodes engagement; collaboration moments are the high-signal subset.
- **Email push channel** (e.g. SMTP-based digest of unread comments). Out of scope; deferred until an actual user request.

## 5. Privacy + security recap

- Device tokens are anonymized by Apple / Google (not directly mappable to a person without OS-side cross-reference).
- RLS on `device_tokens` ensures user A cannot read user B's tokens; service-role bypass scoped to the `notify-on-write` Edge Function.
- Edge Function does NOT log token values (Sentry breadcrumb scrubbing — confirmed via test).
- Database Webhook secret is registered as Edge Function env var (`SKEINLY_DATABASE_WEBHOOK_SECRET`) AND mirrored as the value of the `Authorization: Bearer <…>` HTTP header on each Database Webhook in Dashboard. Constant-time string compare in the Edge Function. Never echoed in response bodies. (The 2026-05-09 amendment in §3.9 documents why this is Bearer rather than HMAC-of-body.)
- APNs `.p8` + FCM SA JSON live as Edge Function secrets only — no client-side exposure.
- Pre-permission dialog explainer ensures users understand what they're consenting to BEFORE the OS prompt fires (avoids the "decontextualized denial" anti-pattern).
- Account deletion (ADR-005) cascades device_token rows out via FK ON DELETE CASCADE.

## 6. Sub-slice plan

**24.0 — ADR cut + CLAUDE.md promotion** (this slice). No code; +0 tests; +0 i18n keys; +0 migrations.

**24.1 — Data spine**:
- Migration NNN: `device_tokens` table + RLS policies + indexes. Apply to prod.
- Edge Function `notify-on-write` shell: webhook Bearer-auth verification + body parse + table-type branch + log-only `dispatchPush` (no actual APNs/FCM calls yet) + Deno test for the parser + locale lookup.
- New Edge Function secret `SKEINLY_DATABASE_WEBHOOK_SECRET` registration doc in release-secrets.md (EF-6).
- Dashboard: Database Webhooks configured for the 3 event sources; documented in new `supabase/webhooks.md`.
- KMP: NO client wiring this slice.
- Tests: ~15 Deno tests for the Edge Function parser + locale resolver + recipient-set computation. NO commonTest delta.
- i18n: 0 new keys (push body strings live in Edge Function, not in shared resources).

**24.2 — Client device token registration + permission UX**:
- KMP `expect/actual` `PushTokenRegistrar` interface + implementations:
  - `iosMain`: `UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound])` + `UIApplication.shared.registerForRemoteNotifications()` + AppDelegate `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)` callback that invokes the registrar's `onTokenReceived(token, locale)`.
  - `androidMain`: `FirebaseMessaging.getInstance().token` (suspending via `await()`); permission via `ContextCompat.checkSelfPermission(POST_NOTIFICATIONS)` (API 33+) + `ActivityCompat.requestPermissions` for older API levels (legacy: no runtime permission, granted at install time).
- New `NotificationPermissionPrompter` service (KMP shared) tracking "have we asked at trigger X?" state via `Preferences`.
- Pre-permission Composable explainer + SwiftUI sheet.
- Settings "Notifications" row (iOS / Compose) + OS settings deep-link.
- New `device_tokens` upsert call from registrar's `onTokenReceived`.
- ~12 i18n keys (Settings row label + pre-permission dialog title/body/CTAs + 2 OS-link labels).
- ~25 commonTest (NotificationPermissionPrompter state machine + registrar contract).

**24.3 — PR comment notification end-to-end**:
- `notify-on-write` Edge Function: `pull_request_comments` branch wired with real APNs + FCM HTTP calls.
- `djwt` JSR import for APNs ES256 + FCM SA JWT signing.
- APNs HTTP/2 client (Deno's native fetch supports HTTP/2; APNs requires it).
- Recipient resolution via `pull_requests` JOIN, MINUS comment author.
- 410/404 token cleanup path.
- Deno test fakes for APNs + FCM endpoints (mock fetch).
- ~20 Deno tests for happy + token-invalid + 5xx-retry-tolerated paths.
- Smoke test doc in `notify-on-write/README.md`: "post a fake Database Webhook payload via curl, verify push lands on test device".

**24.4 — PR-opened + merged + closed events**:
- Same Edge Function, additional branches.
- Edge case: actor == recipient (don't push self). Already covered by the recipient-set computation in §3.4.
- ~10 additional Deno tests.

**24.5 — Deep link routing**:
- iOS `UNUserNotificationCenterDelegate` impl + NotificationCenter publisher → AppRootView subscribe → NavigationPath append.
- Android `FirebaseMessagingService` impl + intent extras + MainActivity onNewIntent → navController.navigate.
- Cold-start path: launch options (iOS) + intent extras (Android) on first onCreate.
- 0 new KMP code (deep-link routing is per-platform integration).
- Manual smoke test: send a real push to a test device, verify tap routes to the correct screen.

**24.6 — Privacy policy update + closed-beta validation**:
- `docs/public/privacy-policy/index.html` (EN) + JA mirror: new "Push Notifications" subsection per §3.11 disclosure.
- Closed-beta validation pass: invite 1–2 testers, verify each of the 3 event types delivers, verify cold-start tap routing, verify token cleanup on uninstall.
- HARD-GATE for Phase 39 closed-beta tester invite is satisfied at the close of this slice.

**Each sub-slice is independently shippable**. Slipping 24.4 leaves the comment notification working; slipping 24.3 leaves Phase 24 fully scaffolded but no end-to-end push delivery (acceptable to ship as alpha-tester-only). Slipping 24.5 leaves push notifications surfacing but tap-no-op (degraded but not regressive).

## 7. Open questions

These are explicitly NOT decided in this ADR; resolution lives in the implementation slice that surfaces them.

**Q1: APNs sandbox vs production environment selection.** APNs has separate `https://api.sandbox.push.apple.com` (debug builds, TestFlight via Xcode signing) and `https://api.push.apple.com` (production builds, App Store + TestFlight via App Store Connect upload). The same `.p8` works for both. Edge Function needs to know which to call per-token. Two approaches:
- (a) Try production first, fall back to sandbox on `BadDeviceToken`. Wastes one round trip per debug-build token.
- (b) Store an `environment` column on `device_tokens` and let the client declare. Adds client-side complexity (knowing whether you're a debug build or release build) but avoids the round trip.

Resolved at Phase 24.3 implementation when the actual APNs call lands. Lean toward (b) given the precedent in Phase 39 sandbox `subscriptions.environment` column.

**Q2: Notification permission OS prompt for Android 12 and below.** API < 33 grants notifications at install time (no runtime permission). Should the in-app pre-permission dialog still surface on these older devices? Resolved at Phase 24.2: yes, surface the explainer (it sets context for what kinds of notifications to expect even if the OS doesn't gate), but skip the OS prompt branch since there's no API to call.

**Q3: Notification thread-identifier strategy for grouping.** Both APNs (`thread-identifier`) and FCM (`tag`) support OS-side coalescence. Per-PR thread (e.g. `thread-identifier: "pr-<prId>"`) would group multiple comments on the same PR into one notification. Defer to Phase 24+ — the closed-beta scale is small enough that grouping is over-engineering.

**Q4: Localization for users with non-EN/JA system locales.** A user with `system_locale: fr-FR` will receive `en-US` notifications. Acceptable for v1 (Skeinly's UI is also EN/JA-only), but if Phase 40+ adds locales, the Edge Function string table grows linearly. No action needed at Phase 24.

**Q5: Edge Function cold start latency**. Supabase Edge Functions have ~100–500ms cold start. A Database Webhook that hits a cold function delays the push by that much. Acceptable for collaboration push (delay tolerance >> seconds). No mitigation needed for v1.

## 8. Alternatives considered

**A1. Don't ship push for closed beta** — let testers manually coordinate via out-of-band channels. Rejected per user policy shift 2026-05-09; closed beta is the validation moment for the collaboration loop, and silent collaboration is not a meaningful test.

**A2. FCM-mediated iOS push (Firebase iOS SDK + APNs `.p8` registered with FCM)** — single Edge Function code path, but adds Firebase iOS SDK as iOS dependency for a feature Apple has natively. Rejected per §3.1.

**A3. Postgres triggers + `pg_net`** — versionable in migrations, but adds extension surface + can't be inspected from Supabase Dashboard UI. Rejected per §3.3.

**A4. Per-event in-app feed only (no push)** — Phase 36.5 ActivityFeedScreen already exists. Doesn't satisfy "interrupt me when collaborator responds across time zones" use case. Push is the cross-medium amplifier; in-app feed remains the durable record.

**A5. Email digest** — opt-in email of unread events. Out of scope; deferred until a real signal surfaces. Push is the higher-engagement medium for collaboration pings.

**A6. Topic-based subscriptions instead of direct device-token push** — useful at scale (FCM topic = userId means one fan-out call sends to all that user's tokens). Over-engineered at ≤100 testers; direct device-token loop is fine. Phase 24+ if scale demands it.

**A7. Web Push (W3C Push API)** — Skeinly is mobile-only (no web client). Out of scope.

## 9. Consequences

**Positive**:
- Closed-beta testers get real-time collaboration awareness; PR turnaround time falls dramatically.
- Existing vendor secrets (EF-1/2/3) finally activate for their original purpose.
- Edge Function pattern reused (4th Edge Function in the repo: `revenuecat-webhook`, `request-pack-download`, `notify-on-write`, plus the deleted `verify-receipt`).
- Database Webhooks pattern introduced — reusable for future "table change → server-side action" flows.
- Privacy policy expansion is a one-time cost; subsequent push-using features inherit the existing disclosure.

**Negative**:
- Two-stack push transport (APNs + FCM) means two code paths to maintain. Bounded but real.
- Notification body strings live in 2 places (Compose/SwiftUI for in-app, Edge Function for push). String parity gap mitigated via Deno test (§3.7).
- Pre-permission dialog adds an extra modal that competes with the natural flow; PRD-style A/B test on prompt timing is post-MVP.
- Database Webhook config is NOT versioned in migrations — relies on Dashboard UI + `supabase/webhooks.md` to stay accurate.
- 1 new Edge Function secret to register (`SKEINLY_DATABASE_WEBHOOK_SECRET`).
- 1 new Postgres table (`device_tokens`) + 4 RLS policies + 1 index.
- iOS Notification Service Extension NOT shipped (Phase 24+ if rich content surfaces real demand).
- Token cleanup on uninstall is reactive (next push attempt fails → DELETE), not proactive. A user who uninstalls accumulates one stale row until their PR-related event fires; bounded by the lifetime of a typical token (~weeks to months).

**Tracking**:
- New Edge Function secret EF-6 in release-secrets.md.
- Migration NNN landing at Phase 24.1 (NNN = next available number after migration 024 — likely 025).
- 6 sub-slices over Phase 24.0 → 24.6.
- HARD-GATE for Phase 39 closed-beta tester invites: closed at end of Phase 24.6.

## 10. References

- [Apple — Sending notification requests to APNs](https://developer.apple.com/documentation/usernotifications/setting_up_a_remote_notification_server/sending_notification_requests_to_apns)
- [Firebase — Send messages with the FCM HTTP v1 API](https://firebase.google.com/docs/cloud-messaging/send-message)
- [Supabase — Database Webhooks](https://supabase.com/docs/guides/database/webhooks)
- [Apple HIG — Notifications](https://developer.apple.com/design/human-interface-guidelines/notifications)
- [Material Design — Notifications](https://m3.material.io/components/notifications/overview)
- ADR-013 (collaboration core)
- ADR-014 (PR workflow — primary event source)
- ADR-015 (Phase 39 beta bug reporting — consent + opt-in patterns Phase 24 reuses)
