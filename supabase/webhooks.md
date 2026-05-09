# Supabase Database Webhooks

This file is the source of truth for the project's Database Webhook configurations. **Database Webhooks are NOT versioned in `supabase/migrations/`** — they live in the Supabase Dashboard UI. Refresh this doc at every Phase / sub-slice that adds, removes, or modifies a webhook.

> **Why a separate doc**: Database Webhooks are a Supabase managed feature (`supabase.com/docs/guides/database/webhooks`) configured via Dashboard → Database → Webhooks. Unlike SQL migrations, they cannot be `apply_migration`'d. The maintainer's responsibility is to keep this doc in sync with the Dashboard state; PRs that wire new webhooks MUST update this file.

## Active webhooks (Phase 24.1)

3 webhooks fire `notify-on-write` for the collaboration push surface (per ADR-017 §3.4 + §3.9). All three POST to:

```
https://<project-ref>.supabase.co/functions/v1/notify-on-write
```

with `Authorization: Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET>` as a custom HTTP header (release-secrets EF-6, Phase 24.1). Per the [Supabase Database Webhooks doc](https://supabase.com/docs/guides/database/webhooks), Database Webhooks do NOT auto-sign payloads — the only authentication options exposed by the Dashboard UI are **HTTP Headers** (free-form key/value pairs) and **HTTP Parameters** (query string). The Authorization header is the supported boundary; the Edge Function does a constant-time Bearer compare against the same secret stored in Edge Function env. Mirrors the `revenuecat-webhook` Bearer pattern.

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
   - **Type of webhook**: `HTTP Request`.
   - **Method**: `POST`.
   - **URL**: `https://<project-ref>.supabase.co/functions/v1/notify-on-write`.
   - **Timeout**: `5000` ms (default; raise only if Phase 24.3 send paths show timeouts).
   - **HTTP Headers** (use `+ Add a new header` for each row):
     - `Content-Type` → `application/json`
     - `Authorization` → `Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET value>` (paste the actual value from `supabase secrets list` — there is no Dashboard-side secret store, so the literal value lives in the webhook config row)
   - **HTTP Parameters**: (leave empty)
4. Click **Create webhook** / **Save**.

> **No signing secret in this UI**: per the [Supabase Database Webhooks doc](https://supabase.com/docs/guides/database/webhooks), Database Webhooks do NOT have a built-in signing-secret field — the dashboard only exposes Method / URL / Timeout / HTTP Headers / HTTP Parameters. The Authorization header is the only authentication boundary. The literal secret value sits inside the webhook config row, which is itself protected by Dashboard ACLs and never exposed to public traffic — but it does mean rotating the secret requires updating the Authorization header on each of the 3 webhooks (in addition to re-running `supabase secrets set`).

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
