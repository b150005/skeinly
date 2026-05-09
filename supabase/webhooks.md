# Supabase Database Webhooks

This file is the source of truth for the project's Database Webhook configurations. **Database Webhooks are NOT versioned in `supabase/migrations/`** — they live in the Supabase Dashboard UI. Refresh this doc at every Phase / sub-slice that adds, removes, or modifies a webhook.

> **Why a separate doc**: Database Webhooks are a Supabase managed feature (`supabase.com/docs/guides/database/webhooks`) configured via Dashboard → Database → Webhooks. Unlike SQL migrations, they cannot be `apply_migration`'d. The maintainer's responsibility is to keep this doc in sync with the Dashboard state; PRs that wire new webhooks MUST update this file.

## Active webhooks (Phase 24.1)

3 webhooks fire `notify-on-write` for the collaboration push surface (per ADR-017 §3.4 + §3.9). All three POST to:

```
https://<project-ref>.supabase.co/functions/v1/notify-on-write
```

with HMAC-SHA256 signature in the `x-supabase-webhook-signature` header. The signature secret is `SKEINLY_DATABASE_WEBHOOK_SECRET` (release-secrets EF-6, Phase 24.1).

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
   - **Type**: `HTTP Request`.
   - **Method**: `POST`.
   - **URL**: `https://<project-ref>.supabase.co/functions/v1/notify-on-write`.
   - **HTTP Headers**:
     - `Content-Type: application/json`
   - **HTTP Params**: (leave empty)
   - **Webhook Source** / **Secret** (signing key): paste the value of `SKEINLY_DATABASE_WEBHOOK_SECRET` from `supabase secrets list`. Supabase signs every delivery with this value.
4. Click **Create webhook** / **Save**.

> **Header name caveat**: Supabase has historically shipped the signature in different header names across CLI versions (`x-supabase-webhook-signature`, `x-webhook-signature`, etc.). The Edge Function reads BOTH `x-supabase-webhook-signature` AND `x-webhook-signature` per `notify-on-write/index.ts` for forward compatibility. Confirm the actual header name at the time of webhook creation by inspecting a delivery in the Dashboard's webhook delivery log.

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

In Phase 24.1, the webhook → Edge Function loop is wired end-to-end but **no actual push is sent** (the Edge Function logs each dispatch and returns 200). This is intentional — it lets the maintainer verify the wiring + signing path before Phase 24.3 introduces the live APNs / FCM HTTP calls. Once Phase 24.3 lands, the same webhooks fire the same Edge Function, but the function now sends real notifications to all rows in `device_tokens` for each computed recipient.

## Removing a webhook

If a webhook needs to be retired (e.g. event source moves to a different table):

1. Dashboard → Database → Webhooks → click the webhook → **Delete**.
2. Update the table above + remove the row.
3. If the Edge Function code path is also being retired, remove the corresponding branch in `notify-on-write/index.ts` `routePayload` function in the same commit that removes this entry.
