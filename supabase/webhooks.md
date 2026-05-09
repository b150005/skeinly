# Supabase Database Webhooks

This file is the source of truth for the project's Database Webhook configurations. **Database Webhooks are NOT versioned in `supabase/migrations/`** — they live in the Supabase Dashboard UI. Refresh this doc at every Phase / sub-slice that adds, removes, or modifies a webhook.

> **Why a separate doc**: Database Webhooks are a Supabase managed feature (`supabase.com/docs/guides/database/webhooks`) configured via Dashboard → Database → Webhooks. Unlike SQL migrations, they cannot be `apply_migration`'d. The maintainer's responsibility is to keep this doc in sync with the Dashboard state; PRs that wire new webhooks MUST update this file.

## Active webhooks (Phase 24.1)

3 webhooks fire `notify-on-write` for the collaboration push surface (per ADR-017 §3.4 + §3.9). All three are configured as **`Supabase Edge Functions` type** webhooks, which give the Dashboard a dropdown to pick the target Edge Function (vs. typing the URL manually). They authenticate via `Authorization: Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET>` as a custom HTTP header (release-secrets EF-6, Phase 24.1). The Edge Function does a constant-time Bearer compare against the same secret stored in Edge Function env. Mirrors the `revenuecat-webhook` Bearer pattern.

| # | Name | Source table | Events | Conditions | Notes |
|---|---|---|---|---|---|
| 1 | `notify_on_pr_insert` | `public.pull_requests` | INSERT | (none) | Fires `pr_opened` push to target owner. |
| 2 | `notify_on_pr_update` | `public.pull_requests` | UPDATE | (none — Edge Function filters via old.status='open' && new.status IN ('merged','closed')) | Fires `pr_merged_to_author` / `pr_closed_to_*` push to other party. |
| 3 | `notify_on_pr_comment_insert` | `public.pull_request_comments` | INSERT | (none) | Fires `pr_commented` push to PR participants minus comment author. |

## Configuration steps (per webhook)

For each row in the table above:

1. Open [Supabase Dashboard](https://supabase.com/dashboard) → your project → **Database** → **Webhooks**.
2. Click **Create a new hook** (or **Add a new hook**).
3. Configuration:
   - **Name**: as in the table above (e.g. `notify_on_pr_insert`).
   - **Table**: the source table (e.g. `pull_requests`).
   - **Events**: tick INSERT or UPDATE per the table.
   - **Type of webhook**: select **`Supabase Edge Functions`** (NOT `HTTP Request`).
   - **Method**: `POST`.
   - **Select which edge function to trigger**: pick **`notify-on-write`** from the dropdown.
   - **Timeout**: `5000` ms (default; raise only if Phase 24.3 send paths show timeouts).
   - **HTTP Headers** (use `+ Add a new header` for each row):
     - `Content-Type` → `application/json` (already pre-populated by Dashboard, leave as-is)
     - `Authorization` → **OVERWRITE the auto-populated value** (`Bearer eyJhbGciOi...` — that is the project anon key) with `Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET value>`. Paste the actual secret value from `supabase secrets list` (or from your password manager if you saved it at generation time). See "Why we override the auto-populated Authorization" below.
   - **HTTP Parameters**: (leave empty)
4. Click **Create webhook** / **Save**.

### Why `Supabase Edge Functions` type instead of `HTTP Request`

Both types map to the same underlying Postgres trigger function (`supabase_functions.http_request()`); the only difference is which fields the Dashboard pre-populates. Choosing the Edge Functions type:

- **Function selection by dropdown**: no full URL to type/copy → no typo risk and the binding survives a future function rename (the dropdown re-resolves the URL automatically).
- **Dashboard discoverability**: the webhook detail row clearly shows "→ Edge Function: notify-on-write" instead of an opaque URL string, easing future maintenance audits.
- **Zero runtime difference**: the trigger function passes our HTTP Headers through to `net.http_post` unchanged, so the Edge Function on the receiving side cannot tell whether the call came from `HTTP Request` or `Supabase Edge Functions` type. Same auth code, same payload shape.

### Why we override the auto-populated `Authorization` header

When you select `Supabase Edge Functions` type, the Dashboard pre-fills the `Authorization` header with `Bearer <project anon key>`. This is the **public** anon key — the same value embedded in the Skeinly mobile app and exposed to every end user. Keeping that value would mean:

- Anyone who knows the anon key (anyone with the app installed) could POST a hand-crafted Database-Webhook-shaped payload directly to `https://<project-ref>.supabase.co/functions/v1/notify-on-write` and trigger fake push notifications to arbitrary recipients (Phase 24.3+ once APNs/FCM credentials are wired).
- The Envoy gateway's `/functions/v1/` route bypasses API-key validation (per the [self-hosted Supabase Envoy gateway architecture doc](https://supabase.com/docs/guides/self-hosting/self-hosted-functions)) — the Edge Function runtime does its own JWT verification (when `verify_jwt = true`), but the anon key satisfies that check.

By overriding with our project-internal `SKEINLY_DATABASE_WEBHOOK_SECRET` and keeping `verify_jwt = false` in `supabase/config.toml`, the auth boundary becomes "did this caller hold a secret that lives only in Edge Function env + 3 Dashboard webhook config rows" — which is structurally tighter than "is this an anon-key-bearing HTTPS request".

> **No signing secret in this UI**: per the [Supabase Database Webhooks doc](https://supabase.com/docs/guides/database/webhooks), Database Webhooks do NOT have a built-in signing-secret field — the dashboard only exposes Method / URL or Edge Function / Timeout / HTTP Headers / HTTP Parameters. The Authorization header is the only authentication boundary. The literal secret value sits inside the webhook config row, which is itself protected by Dashboard ACLs and never exposed to public traffic — but it does mean rotating the secret requires updating the Authorization header on each of the 3 webhooks (in addition to re-running `supabase secrets set`).

## Verification (post-config)

After creating each webhook:

1. Trigger the source event (INSERT a test PR via SQL Editor, etc.).
2. Dashboard → Database → Webhooks → click the webhook → **Logs** tab.
3. Confirm: status `200`, body `{"ok":true,"dispatch_count":N}`.
4. Pull `notify-on-write` Edge Function logs:

```bash
mcp__supabase__get_logs service=edge-function
```

Look for `notify_on_write_dispatched` + `notify_on_write_skipped_send` (Phase 24.1 SHELL phase) structured log lines.

## Phase 24.1 SHELL note

In Phase 24.1, the webhook → Edge Function loop is wired end-to-end but **no actual push is sent** (the Edge Function logs each dispatch and returns 200). This is intentional — it lets the maintainer verify the wiring + Bearer-auth path before Phase 24.3 introduces the live APNs / FCM HTTP calls. Once Phase 24.3 lands, the same webhooks fire the same Edge Function, but the function now sends real notifications to all rows in `device_tokens` for each computed recipient.

## Removing a webhook

If a webhook needs to be retired (e.g. event source moves to a different table):

1. Dashboard → Database → Webhooks → click the webhook → **Delete**.
2. Update the table above + remove the row.
3. If the Edge Function code path is also being retired, remove the corresponding branch in `notify-on-write/index.ts` `routePayload` function in the same commit that removes this entry.
