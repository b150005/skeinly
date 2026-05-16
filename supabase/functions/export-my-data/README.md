# export-my-data Edge Function

Pre-Phase-40 A20 Option B — in-app GDPR Article 20 / CCPA "right to know" data
export. Upgrades the operator-driven SOP
([docs/en/ops/data-export-sop.md](../../../docs/en/ops/data-export-sop.md)) to a
self-service in-app flow. The SOP remains the documented fallback for users who
cannot complete the in-app path.

## What it does

POST /functions/v1/export-my-data from an authenticated mobile client:

1. Validates the JWT (Supabase platform — `verify_jwt = true` in
   `supabase/config.toml`).
2. Decodes the `sub` claim → caller `userId`. **The request body is ignored
   entirely.** There is no caller-supplied identifier; an IDOR is structurally
   impossible.
3. Applies a per-user rate limit (5 exports / hour per `auth.uid()`).
4. Composes the caller's full data bundle server-side (`query.ts`):
   - `account` — `auth.users` projection (id / email / created_at /
     last_sign_in_at / raw_user_meta_data)
   - 17 owned/child `public.*` tables (the SOP step-4 enumeration)
   - `storage.avatars` — object metadata for the user's avatar folder
   - `notes` — static pointers to data Skeinly does NOT hold directly (GitHub
     bug-report Issues / RevenueCat-Apple-Google IAP / Sentry-PostHog analytics)
     so the in-app export is not silently incomplete vs. the SOP bundle
5. Returns the bundle inline (`{ ok, bundle, summary, total_rows }`) for the
   client to hand to the OS share sheet.

## Why service role + code-level scoping

The bundle needs `auth.users` + `storage.objects`, which a user-JWT PostgREST
client cannot read. The handler therefore uses the service-role client and
scopes **every** query by the JWT-derived `userId` in code (`query.ts`). This is
the SOP's per-table `WHERE`, translated to an explicit predicate — **the
predicate is the access control**. `query.ts` is a pure module over a small
`DataExportPort` so the scoping invariant is unit-tested without supabase-js.

## Distinct from submit-ugc-report / submit-bug-report

|             | submit-bug-report    | submit-ugc-report    | export-my-data           |
| ----------- | -------------------- | -------------------- | ------------------------ |
| Auth        | `verify_jwt = false` | `verify_jwt = true`  | `verify_jwt = true`      |
| New secrets | GitHub App trio      | Reuses the trio      | **None** (auto env only) |
| Rate limit  | 5/hr per source-hash | 10/hr per auth.uid() | 5/hr per auth.uid()      |
| DB          | None                 | INSERT ugc_reports   | SELECT-only (17 tables)  |
| Body        | title/body/labels    | report fields        | **ignored**              |

## Required env

All auto-injected by the Supabase platform — **no manual `supabase secrets set`
step**. Returns `CONFIG_MISSING` if absent.

| Env                         | Source | Notes                              |
| --------------------------- | ------ | ---------------------------------- |
| `SUPABASE_URL`              | auto   | Supabase platform-injected         |
| `SUPABASE_SERVICE_ROLE_KEY` | auto   | Service-role client (auth/storage) |

## Request / response envelope

### Request

```http
POST /functions/v1/export-my-data
Authorization: Bearer <user-jwt>
Content-Type: application/json

{}
```

The body is ignored; send `{}`.

### Success (HTTP 200)

```json
{
  "ok": true,
  "bundle": {
    "schema_version": 1,
    "exported_at": "2026-05-16T12:00:00.000Z",
    "user_id": "<uuid>",
    "account": { "id": "...", "email": "...", "...": "..." },
    "tables": { "patterns": [], "projects": [], "...": [] },
    "storage": { "avatars": [] },
    "notes": { "bug_reports": "...", "iap_revenuecat": "...", "...": "..." }
  },
  "summary": { "patterns": 3, "projects": 2, "_avatars": 1, "...": 0 },
  "total_rows": 6
}
```

### Failure (HTTP 200, `ok: false`)

`code` ∈ `UNAUTHORIZED | RATE_LIMITED | CONFIG_MISSING | EXPORT_FAILED`. Non-200
is reserved for Supabase-platform breakage (function undeployed / mid-rotation /
5xx) so the client distinguishes "function refused" from "function down" — same
contract as submit-ugc-report.

## Tests

```bash
cd supabase/functions/export-my-data
deno test --allow-net --allow-env
```

20 tests: `query.test.ts` (9 — bundle composition + the IDOR invariant that no
port call ever receives a non-caller id) + `index.test.ts` (11 —
method/auth/rate-limit, EXPORT_FAILED on both an early and a last-step port
throw, the body-supplied-id-is-ignored invariant, and the non-UUID-sub
PostgREST-injection defense). Not run in CI (no Deno step in the workflows;
manual per repo convention).

## Deploy (operator-side, at GA or earlier)

```bash
git pull origin main
supabase functions deploy export-my-data
# No new secrets. Smoke test:
curl -sS -X POST "$SUPABASE_URL/functions/v1/export-my-data" \
  -H "Authorization: Bearer <a-real-user-jwt>" \
  -H "Content-Type: application/json" -d '{}' | jq '.ok, .total_rows'
```

`github_app.ts`-style `_shared/` consolidation is **not** applicable here — this
function has no shared crypto/GitHub code. The `extractUserIdFromAuthHeader` +
base64url helpers are duplicated from submit-ugc-report by deliberate choice (a
~20-line pure decoder; sharing it would couple a new function's deploy to the
live beta submit-ugc-report bundle for no functional gain — same risk-batching
rationale as the github_app.ts GA-batch note in CLAUDE.md).
