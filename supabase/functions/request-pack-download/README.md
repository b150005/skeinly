# `request-pack-download` Edge Function

Mediates downloads from the `symbol-packs` Storage bucket per ADR-016 §3.3
(post-Path-A pivot, 2026-05-06). Replaces the originally-proposed Postgres
`SECURITY DEFINER` RPC because current Supabase Postgres exposes no
`storage.create_signed_url(...)` helper.

## Contract

**Endpoint**: `POST /functions/v1/request-pack-download`

**Request**:
```http
POST /functions/v1/request-pack-download
Authorization: Bearer <user_jwt>
Content-Type: application/json

{ "pack_id": "jis.knit.beginner" }
```

**Response — 200**:
```json
{
  "payload_url":     "https://<project>.supabase.co/storage/v1/object/sign/symbol-packs/jis.knit.beginner/1/payload.json?token=...",
  "payload_url_ttl": "2026-05-06T08:00:00Z",
  "current_version": 1,
  "payload_size":    13558
}
```

**Response — error (closed enum)**:
| Status | `error` body                  | Meaning |
|--------|-------------------------------|---------|
| 400    | `invalid_json`                | request body did not parse |
| 400    | `missing_pack_id`             | request body lacks a non-empty `pack_id` |
| 401    | `unauthorized`                | missing / invalid Bearer JWT |
| 403    | `pro_entitlement_required`    | pack tier is `pro` and caller has no active subscription row |
| 404    | `pack_not_found`              | no `symbol_packs` row for `pack_id` (echoed in response — accepted, see ADR-016 §3.3) |
| 405    | `method_not_allowed`          | non-POST verb |
| 429    | `rate_limited`                | per-user sliding window (10 calls / 60s) exceeded; body carries `retry_after_seconds` |
| 500    | `edge_function_misconfigured` | `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` missing from runtime env |
| 500    | `internal_error`              | unrecoverable lookup / sign / parse failure |

## Local development

### Run the rate-limiter tests

The sliding-window rate limiter is the §10 Q6 abuse-prevention surface.
Off-by-one errors here would silently widen the cap. Tests are in
`rate-limit.test.ts`.

```bash
brew install deno   # if not already installed
deno test supabase/functions/request-pack-download/
```

The Deno tests are NOT currently CI-gated (CI is JVM-only) — they're a
manual regression anchor. Run them before any change to `rate-limit.ts`
(or `index.ts`'s rate-limit call site) lands in a deploy.

### Deploy

Use the Supabase MCP `mcp__supabase__deploy_edge_function` (uploads the
file contents directly — no local Deno needed for deploy itself). Pass:

- `name`: `request-pack-download`
- `entrypoint_path`: `index.ts`
- `verify_jwt`: `true` (Supabase enforces a valid Bearer JWT before the
  function body runs; defense-in-depth on top of the in-body
  `auth.getUser()` check)
- `files`: `index.ts` + `rate-limit.ts`

After deploy, smoke-test:

```bash
# Replace USER_JWT with a real user's access_token from supabase.auth.signIn.
# Replace PROJECT_REF with the linked project ref.

curl -X POST \
  "https://PROJECT_REF.supabase.co/functions/v1/request-pack-download" \
  -H "Authorization: Bearer USER_JWT" \
  -H "Content-Type: application/json" \
  -d '{"pack_id":"jis.knit.beginner"}'
```

Expected on success: a 200 with the envelope shape above. Pull
function logs via `mcp__supabase__get_logs service=edge-function` and
look for the `pack_download_signed` structured log line.

## Refund-revocation semantics

A `subscriptions.status='refunded'` write through `verify-receipt` causes
the *next* invocation by that user to return 403, even if the user's
local `EntitlementResolver` cache hasn't yet processed the Realtime
push. The 5-minute signed-URL TTL bounds residual access through any
in-flight URL the user already holds.

## Rate-limit caveat (v1 closed-beta scope)

The in-memory `Map` resets on Edge Function cold-start (~minutes between
cold-starts in low-traffic conditions). This is acceptable for v1 alpha
+ closed beta scale (subscriber count ~10s); revisit with Upstash Redis
or a Postgres `edge_function_rate_limit` table once subscriber count
justifies. See ADR-016 §10 Q6 for the full discussion.
