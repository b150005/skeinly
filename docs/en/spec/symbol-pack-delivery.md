# Spec — Symbol Pack Delivery

> **Purpose**: stable feature-organized view of how knitting symbol content is delivered to the app today. Describes the *what*; [ADR-016](../adr/016-phase-41-pro-subscription-dynamic-symbols.md) carries the *why*.
>
> **Audience**: an agent or contributor extending the symbol catalog, adding a new Pro pack, debugging download failures, or wiring a new entitlement source.
>
> **Scope**: symbol pack catalog, on-device cache, download mediation, entitlement gate. Out of scope: in-pack symbol authoring conventions ([ADR-009](../adr/009-parametric-symbols.md) parametric symbols + the `symbol-review/` docs), subscription billing (RevenueCat / `subscriptions` table — see [spec/billing.md](../adr/016-phase-41-pro-subscription-dynamic-symbols.md#section-3.3) when written), chart editor symbol palette UX.

## Mental model in one sentence

A symbol pack is a **versioned bundle of symbol definitions** (~30–50 symbols per pack) stored as a single `payload.json` file in private Supabase Storage. The app's Postgres mirror table is a **manifest of what packs exist** — it carries no symbol bodies. Pro-tier packs additionally pass an entitlement check before download.

## Current shape

### Three-tier data layout

```
┌─ Postgres (Supabase) ────────────────────────────────────┐
│  symbol_packs                                            │  Metadata catalog (manifest)
│   ↳ id / tier / version / display_name / description     │  RLS: open SELECT
│   ↳ payload_path / payload_size / symbol_count           │  Write: service-role only
│  symbol_pack_locales (pack_id × locale)                  │
│   ↳ locale-specific display_name / description           │
│  user_symbol_pack_state (user × pack)                    │  RLS: own-row only
│   ↳ downloaded_version / last_accessed_at                │  Server-side mirror of what
│                                                          │  the user has on disk
└──────────────────────────────────────────────────────────┘
                  │ Postgres holds only metadata.
                  │ Symbol bodies live in Storage.
                  ▼
┌─ Storage bucket `symbol-packs` (private, 1MB/file cap) ──┐
│  jis.knit.beginner/1/payload.json    ← 35 symbols ~13 KB │
│  jis.crochet.beginner/1/payload.json ← 35 symbols ~20 KB │
│  <future-pack>/<version>/payload.json                    │
│  <future-pack>/<version>/preview.png  (optional)         │
└──────────────────────────────────────────────────────────┘
                  │ No direct client read path.
                  │ Storage REST `/object/sign/…` issues
                  │ per-call signed URLs (5-min TTL).
                  ▼
┌─ Edge Function `request-pack-download` ──────────────────┐
│  • Verify user JWT (verify_jwt: true)                    │
│  • Per-user sliding rate-limit (10 req / 60s)            │
│  • Look up pack row, gate on tier='pro' subscription     │
│  • Mint 5-min signed URL via Storage REST                │
│  • Return {payload_url, ttl, version, size}              │
└──────────────────────────────────────────────────────────┘
                  │
                  ▼
┌─ KMP client (SQLDelight) ────────────────────────────────┐
│  SymbolPackEntity            ← catalog mirror             │
│  DownloadedPackPayloadEntity ← payload.json body cache    │
└──────────────────────────────────────────────────────────┘
```

### File map

#### Postgres (Supabase)

| Artifact | Role |
|---|---|
| Migration [020_symbol_packs.sql](../../../supabase/migrations/020_symbol_packs.sql) | Creates `symbol_packs` + `symbol_pack_locales` + `user_symbol_pack_state` with RLS (open-read on catalog, own-row on user state). |
| Migration [021_symbol_packs_bucket.sql](../../../supabase/migrations/021_symbol_packs_bucket.sql) | Provisions the private Storage bucket with `file_size_limit = 1 MiB` and `allowed_mime_types = [application/json, image/png]`. |
| Migration [022_seed_symbol_pack_metadata.sql](../../../supabase/migrations/022_seed_symbol_pack_metadata.sql) | Seeds the 2 free-tier packs (`jis.knit.beginner` + `jis.crochet.beginner`) + ja-locale rows. |

#### Edge Function

| Artifact | Role |
|---|---|
| [supabase/functions/request-pack-download/index.ts](../../../supabase/functions/request-pack-download/index.ts) | Handler: verify JWT → rate-limit → pack lookup → entitlement check → mint signed URL. |
| [supabase/functions/request-pack-download/rate-limit.ts](../../../supabase/functions/request-pack-download/rate-limit.ts) | In-memory sliding window (10 req / 60s per user). Resets on cold start; acceptable at closed-beta scale. |

#### Client (KMP shared)

| Artifact | Role |
|---|---|
| [shared/.../sqldelight/.../SymbolPack.sq](../../../shared/src/commonMain/sqldelight/io/github/b150005/skeinly/db/SymbolPack.sq) | Local mirror of `symbol_packs` catalog. |
| [shared/.../sqldelight/.../DownloadedPackPayload.sq](../../../shared/src/commonMain/sqldelight/io/github/b150005/skeinly/db/DownloadedPackPayload.sq) | Local cache of decoded `payload.json` bodies, keyed by (pack_id, version). |
| [shared/.../data/local/LocalSymbolPackDataSource.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/local/LocalSymbolPackDataSource.kt) | Both tables under one repository surface; transactional manifest replace + payload upsert. |
| [shared/.../data/remote/RemoteSymbolPackDataSource.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/remote/RemoteSymbolPackDataSource.kt) | `fetchManifest()` via supabase-kt postgrest; `requestDownload(packId)` via supabase-kt functions plugin → fetch signed URL → decode payload. |
| [shared/.../data/sync/SymbolPackSyncManager.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/sync/SymbolPackSyncManager.kt) | Orchestrates one sync cycle: fetch manifest → diff against local cache → download stale-or-missing packs. Mutex-serialized. |
| [shared/.../domain/symbol/CompositeSymbolCatalog.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/CompositeSymbolCatalog.kt) | Render-time `SymbolCatalog` that overlays downloaded packs on top of the bundled compile-time catalog. Pro entries gated by [EntitlementResolver](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/EntitlementResolver.kt). |
| [shared/.../domain/model/SymbolPackPayload.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/model/SymbolPackPayload.kt) | Wire format of `payload.json`. `schema_version` forward-compat contract. |
| [shared/.../ui/packmanagement/](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/packmanagement/) | Pack management screen (paywall preview, downloaded packs, "free up space"). |
| [shared/.../tools/SymbolPackPayloadGenerator.kt](../../../shared/src/androidHostTest/kotlin/io/github/b150005/skeinly/tools/SymbolPackPayloadGeneratorTest.kt) | Build-time generator that emits `payload.json` from the bundled compile-time catalog. Always runs as a test invariant; emits files only when `skeinly.payloads.outputDir` system property is set (see `generateSymbolPackPayloads` Gradle task). |

### Data flow — cold start (user already authenticated)

1. App launch wires `CompositeSymbolCatalog`. Constructor schedules one fire-and-forget `refresh()` on `applicationScope`.
2. `SymbolPackSyncManager.sync()` fires (caller side — typically a foreground hook + a post-purchase RevenueCat callback in Phase 41.3).
3. `RemoteSymbolPackDataSource.fetchManifest()` → SELECT `symbol_packs` → returns `List<SymbolPack>` (metadata only).
4. `LocalSymbolPackDataSource.replaceManifest(packs)` upserts every supplied pack into `SymbolPackEntity` and deletes server-archived ids. The payload cache is NOT cascaded — a user keeps a locally-cached payload for a pack that the server has since archived.
5. For each pack, the manager compares `pack.version` against the locally cached payload's version:
   - **Match** → `AlreadyUpToDate` (no download).
   - **Cache missing or cache.version < pack.version** → call `request-pack-download` Edge Function.
   - **Cache.version > pack.version** → `VersionRegression` outcome (warn + keep higher cache).
6. On 200, the Edge Function returns `{payload_url, payload_url_ttl, current_version, payload_size}`. The client GETs the signed URL via a separately-injected Ktor `HttpClient` (supabase-kt's internal client cannot be reused for absolute URLs).
7. Payload JSON decoded into `SymbolPackPayload`. The `current_version` from the envelope MUST match `payload.version`; mismatch surfaces as a `Parse` failure (defense-in-depth against a stale signed URL pointing at an older version file).
8. `LocalSymbolPackDataSource.upsertPayload(packId, version, jsonString)` writes the body to `DownloadedPackPayloadEntity`.
9. `CompositeSymbolCatalog.refresh()` rebuilds its in-memory snapshot (synchronous `get(id)` on the render hot path).

### Data flow — symbol lookup at render time

```
ChartEditor.cell.draw(symbol_id)
  ↓
CompositeSymbolCatalog.get(symbol_id)
  ├─ downloaded snapshot has symbol_id?
  │   ├─ yes → check entry.tier
  │   │       ├─ FREE → return entry.definition
  │   │       └─ PRO  → entitlementResolver.isPro()?
  │   │                  ├─ true  → return entry.definition
  │   │                  └─ false → return null (cell renders as "?")
  │   └─ no → fall through to bundled compile-time catalog
  └─ bundled.get(symbol_id)
```

`EntitlementResolver.isPro()` is synchronous — it reads `SubscriptionRepository.cachedActiveSubscription(userId)` (single PK-indexed SQLDelight row) and compares `expires_at` against `Clock.System.now()`. No coroutine boundary on the render path.

### Pro-tier defense in depth

| Layer | Mechanism | Failure mode |
|---|---|---|
| **Server-side download gate** | `request-pack-download` Edge Function checks `subscriptions WHERE status IN ('active','in_grace_period') AND (expires_at IS NULL OR expires_at > now())`. Returns 403 `pro_entitlement_required` if absent. | Expired user cannot download new Pro packs at all. |
| **Signed URL TTL** | 5 minutes. After expiry the URL returns 403 from Storage. | Bounds residual access for a leaked URL or post-revocation in-flight fetch. |
| **Client-side render gate** | `EntitlementResolver.isPro()` on every `CompositeSymbolCatalog.get(id)`. Pro entries return `null` for non-subscribers. | Expired user sees Pro pack rows disappear from the palette at the next render. |
| **`subscriptions` source of truth** | `revenuecat-webhook` Edge Function writes status transitions (EXPIRATION / CANCELLATION / REFUND) via `upsert_subscription_from_webhook` RPC with a `last_verified_at` ordering guard. | Out-of-order webhook retries cannot overwrite newer state with older state. |
| **Default-deny on offline** | `EntitlementResolver.isPro()` returns false when no cached subscription row exists OR `expires_at <= now()`. | First-launch users without network see Pro packs locked until the first successful refresh. |

**Acknowledged limitation (ADR-016 §8 #5)**: an offline user who rewinds the device clock keeps `isPro()` true past the true expiry. Mitigation: `SubscriptionRepository.refresh()` re-validates against server `now()` on reconnect, and `request-pack-download` re-validates server-side for every new download — the bypass window only extends access to packs already on disk, never to new entitlement-gated downloads.

### Edge Function response shape

**Success (HTTP 200)**:
```json
{
  "payload_url":     "https://<project>.supabase.co/storage/v1/object/sign/symbol-packs/<path>?token=...",
  "payload_url_ttl": "2026-05-12T08:00:00Z",
  "current_version": 2,
  "payload_size":    13558
}
```

**Failure**:

| HTTP | `error` | Trigger | Client mapping |
|---|---|---|---|
| 400 | `invalid_json` | Body parse failed | `SymbolPackDownloadResult.Failure.Unknown` |
| 400 | `missing_pack_id` | Empty `pack_id` | `Failure.Unknown` |
| 401 | `unauthorized` | Missing / invalid Bearer JWT | `Failure.Unauthenticated` |
| 403 | `pro_entitlement_required` | Pro pack + no active subscription | `Failure.ProEntitlementRequired` → sync `SkippedProEntitlement` |
| 404 | `pack_not_found` | No `symbol_packs` row matches | `Failure.PackNotFound` |
| 429 | `rate_limited` | 10 req / 60s budget exhausted; body carries `retry_after_seconds` | `Failure.RateLimited` |
| 500 | `edge_function_misconfigured` | `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` absent at runtime | `Failure.Unknown` |
| 500 | `internal_error` | Storage sign call failed / pack lookup query errored | `Failure.Unknown` |

### Wire format of `payload.json`

Top-level shape (per [SymbolPackPayload.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/model/SymbolPackPayload.kt)):

```json
{
  "pack_id": "jis.knit.beginner",
  "version": 1,
  "schema_version": 1,
  "symbols": [
    {
      "id": "jis.knit.k",
      "category": "KNIT",
      "tier": "FREE",
      "path_data": "M 0 0 L 1 1 ...",
      "fill": false,
      "width_units": 1,
      "height_units": 1,
      "parameter_slots": [...],
      "ja_label": "表目",
      "en_label": "Knit",
      "ja_description": "...",
      "en_description": "...",
      "aliases": [],
      "jis_reference": "JIS L 0201-1995 §5.1",
      "cyc_name": null
    }
  ]
}
```

**Forward-compat contract** for `schema_version`:
- **Additive changes** to `symbols[]` entries (new optional field) do NOT bump `schema_version`. Old clients deserialize with `ignoreUnknownKeys = true`.
- **Breaking changes** (field removal, semantic change, top-level reshape) DO bump `schema_version`. When bumped, the pack MUST split into a new `pack_id` per the symbol-id-stability contract from ADR-009 §9 — older clients keep finding the older payload at the older id.
- The bundled per-symbol `tier` field allows mixing free + pro within one pack; v1 ships every entry at the parent pack's tier.

### Bundled fallback

The pre-Phase-41 compile-time `DefaultSymbolCatalog` stays in the app binary. First-launch users with no network see the same 70 JIS symbols they see today; the downloaded packs only become the catalog source-of-truth after the first successful sync. This is the offline-safety net ADR-016 §4.1 names; it costs nothing at the wire format level because the bundled catalog and the seed-migrated free packs ship identical glyph definitions today.

When a downloaded pack overlays a bundled symbol of the same id, the downloaded entry wins (newer-version-wins, see `CompositeSymbolCatalog` lookup order in the KDoc).

### Versioning + cache invalidation

- `symbol_packs.version` is monotonic per pack id. Bumping the row's `version` triggers every client to re-download on next sync.
- The local cache key is `(pack_id, version)` — the SQLDelight query `getLatestPayload(packId)` returns the highest cached version. Older versions stay on disk until the user explicitly frees space (planned for Phase 41.4 pack management screen).
- The Edge Function signs URLs against `<pack_id>/<version>/payload.json`. To patch a pack the operator uploads `<pack_id>/<new_version>/payload.json`, then `UPDATE symbol_packs SET version = new_version, payload_path = '<pack_id>/<new_version>/payload.json', payload_size = ...`. Old payload files can stay in Storage (no cost pressure at v1 cardinality); a future cleanup task may purge them.

### Current production state

As of 2026-05-12, prod has 2 rows in `symbol_packs`:

| id | tier | version | payload_path | symbol_count | payload_size |
|---|---|---|---|---|---|
| `jis.knit.beginner` | free | 1 | `jis.knit.beginner/1/payload.json` | 35 | 13,558 |
| `jis.crochet.beginner` | free | 1 | `jis.crochet.beginner/1/payload.json` | 35 | 20,492 |

No Pro packs exist yet. The end-to-end download path is verified (sync manager populates the local cache from Storage on each cold start), but **no Pro-tier pack has been authored or seeded**. The Pro authoring + seed step is open work (see [ADR-016 §5](../adr/016-phase-41-pro-subscription-dynamic-symbols.md) for the planned Pro pack lineup and the Phase 39 closed-beta polish list in CLAUDE.md for sequencing).

## How to operate this surface

**Publish or patch a pack** → [docs/en/ops/content-publishing.md](../ops/content-publishing.md).

**Diagnose a download failure** → [docs/en/ops/incident-playbook.md](../ops/incident-playbook.md#symbol-pack-download-fails).

**Rotate the Edge Function's service-role custody** → no separate action; the function reads `SUPABASE_SERVICE_ROLE_KEY` which is platform-injected by Supabase.

## Key references

- [ADR-016](../adr/016-phase-41-pro-subscription-dynamic-symbols.md) — full design rationale, 4-way decision matrix for the Edge Function path, paywall UX, telemetry.
- [ADR-009](../adr/009-parametric-symbols.md) — parametric symbols + symbol-id stability contract.
- [supabase/functions/request-pack-download/](../../../supabase/functions/request-pack-download/) — Edge Function code + README.

## Tracked tech debt

- Pack authoring + seed for the first Pro pack(s) is not yet done. Inventory the candidate pack lineup with the `knitter` agent + `monetization-strategist` agent before Phase 40 GA.
- Phase 41.4 pack management screen + "free up space" affordance not yet implemented (the `user_symbol_pack_state` table is reserved for this).
- Rate-limit map resets on Edge Function cold start. ADR-016 §10 Q6 names persistent storage (Upstash Redis or `edge_function_rate_limit` table) as the post-beta upgrade path if abuse signals appear.
