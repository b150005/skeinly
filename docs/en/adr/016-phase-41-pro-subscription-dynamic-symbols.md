# ADR-016 — Phase 41: Pro Subscription + Dynamic Symbol Pack Delivery

> **Status**: Proposed (2026-05-04)
> **Phase**: 41 (post-Phase-39-beta, post-Phase-40-GA)
> **Supersedes**: none
> **Superseded by**: none
> **Related**: ADR-005 (account deletion), ADR-008 (structured chart data model), ADR-009 (parametric symbols), ADR-013 (collaboration core), ADR-014 (PR workflow). [docs/spec/chart-editor.md](../../../.claude/docs/spec/chart-editor.md) covers the editor surface that consumes this catalog.
> **Tracking**: F1 in [.claude/docs/active-backlog.md](../../../.claude/docs/active-backlog.md). Migration 017 (`subscriptions` table) ships in Phase 39 alpha alongside `verify-receipt` Edge Function. RevenueCat vendor wiring covered by [docs/en/vendor-setup.md](../vendor-setup.md) Phase A0d.

JA summary: [../../ja/adr/016-phase-41-pro-subscription-dynamic-symbols.md](../../ja/adr/016-phase-41-pro-subscription-dynamic-symbols.md) (to be cut alongside this ADR going to Accepted).

## 1. Context

Skeinly's structured-chart vision rests on a **bundled compile-time symbol catalog** ([DefaultSymbolCatalog](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/catalog/DefaultSymbolCatalog.kt)) that today contains 35 crochet + 30+ knit JIS symbols. Two inflexibilities surface as the project moves toward a sustainable revenue model:

**Problem 1 — symbol catalog is frozen between Store updates.** A typo in a glyph, a refined chevron form for `jis.crochet.reverse-sc`, or a knitter community request for a missing JIS symbol all require a full Store release cycle. CLAUDE.md's `Tech Debt → Future opportunistic crochet catalog additions` already documents 4 deferred symbol families (`fsc` / `fdc` / `exsc` / spike stitches / `turning-ch-N`) waiting on user demand signal. That signal can only meaningfully accumulate if shipping a new symbol takes hours, not weeks.

**Problem 2 — no monetization spine.** Phase 37 (collaboration), Phase 38 (PR workflow), and the eventual Phase 35.2 (polar editing) compose a non-trivial cross-platform feature surface. Without a revenue-aligned tiering, every advanced feature ships free and there is no way to balance feature breadth against operating cost (Supabase Edge Functions, free-tier connection caps, eventual storage growth). The agent team consensus from 2026-04-30 settled on a **subscription model gating advanced features**, with the symbol catalog as the natural first leverage point: knit symbols correlate strongly with knitter craft level, and "intermediate / advanced symbols cost money" is a familiar pattern (Drops Design, Knitting Patterns Central, etc.).

**Phase 39 alpha already prepped two of the three vendor pillars**:
- Migration [017_subscriptions.sql](../../../supabase/migrations/017_subscriptions.sql) — `subscriptions` table, RLS-gated own-row SELECT, `verify-receipt` Edge Function writes via service role.
- RevenueCat vendor account configured per [vendor-setup.md A0d](../vendor-setup.md). Public iOS / Android SDK Keys registered as GitHub Secrets §19/§20.

**What's missing for F1 to land**:
- Postgres schema for `symbol_packs` + `user_symbol_pack_state` + RPC for entitlement-aware manifest fetch.
- Supabase Storage bucket layout for SVG path payloads.
- Client `CompositeSymbolCatalog` overlaying bundled + downloaded packs.
- `EntitlementResolver` consulting RevenueCat entitlement on every Pro pack access.
- Paywall screen + pack management UI + symbol gallery integration.
- Telemetry for "user requested missing symbol" + "Pro tier conversion funnel".

This ADR locks in the data shape and integration boundary before any code lands. Same precedent shape as ADR-013 / ADR-014 / ADR-015.

## 2. Decisions (high-level)

1. **Symbol packs are first-class entities** stored in a Supabase Postgres table (`symbol_packs`) with manifest metadata + version + tier (`free` / `pro`) + signed-until expiry. Payload (SVG path data) lives in a Supabase Storage bucket keyed by `<pack_id>/<version>/payload.json`. **NOT** an external CMS (Contentful / Strapi / Sanity) — keeps vendor surface bounded, leverages existing RLS for entitlement scoping, allows agent-team direct content authoring via `apply_migration` + `execute_sql` (no admin UI needed for v1).

2. **Pack tier gating happens at runtime via `EntitlementResolver`**, not at download time. Pack files stay on disk after sub expires (no re-download cost on re-subscription). The `CompositeSymbolCatalog.get(id)` consults the resolver for Pro-tier packs on every access; an expired-sub user with a Pro symbol cell rendered in their chart sees a fallback "?" glyph at render time. **Saved charts are never invalidated** — symbol references use stable ids that survive pack revocation.

3. **Beginner / free packs use the same delivery infrastructure** as Pro packs — only the entitlement gate is configured to "always open" for `tier='free'`. This means glyph refinements for free packs ship without a Store update (closing the Tech Debt → "Future opportunistic crochet catalog additions" gap by removing the Store-release-cycle bottleneck).

4. **Manifest-driven sync model**. App boot fetches `GET /symbol_packs/manifest` (RLS-gated to packs the user is entitled to). Client diffs against local `user_symbol_pack_state` cache, downloads stale or missing payloads via Supabase Storage signed URLs. Background-only — never blocks app launch. Failure is silent (next boot retries; ApolloRuntimeError reported to Sentry without surfacing to user).

5. **`ChartCell.symbolId` remains a stable contract**. Pack version bumps refine rendering (path data, parameter slot defaults) but never change the id. Charts authored at v3 of `jis.knit.cable.6st` render identically with v4 except for whatever the v4 author intended to refine. **Breaking glyph semantics requires a NEW id** (e.g. `jis.knit.cable.6st.v2`); the old id stays in the catalog forever as a deprecation alias.

6. **User symbol requests route through GitHub Issues** with a dedicated `.github/ISSUE_TEMPLATE/symbol-request.yml` template (knit/crochet selector + JIS reference URL + intended use case + sample chart screenshot). The agent team triages, authors the symbol pack update via direct Supabase write, and closes the issue. **No in-app request form for v1** (modal infrastructure + Edge Function relay adds complexity without value gain over a pre-filled GitHub URL during alpha / closed beta).

7. **Pro tier scope (initial)**: Intermediate + Advanced symbol packs (rough split: ~50 free symbols, ~30 Pro intermediate, ~20 Pro advanced). Future Phase entries layer Pro-only feature gates (Phase 35.2 polar editing, Phase 38 PR workflow approval gates, etc.) on top of this same `EntitlementResolver`. **Initial pricing**: $3.99/mo + $24.99/yr (~48% annual discount), 7-day free trial — already encoded as IAP products in App Store Connect per Phase A0b-3.

8. **Offline tolerance**: First-launch users with no network see only the bundled compile-time `DefaultSymbolCatalog` (35 crochet + 30+ knit JIS symbols already in-binary). Pack manifests never block UI. Subsequent boots with cached packs see the merged `CompositeSymbolCatalog` immediately, then refresh in background.

## 3. Schema

### 3.1 `symbol_packs` (Postgres + Supabase Storage)

```sql
CREATE TABLE public.symbol_packs (
  id            TEXT PRIMARY KEY,           -- e.g. "jis.knit.beginner", "jis.knit.intermediate"
  tier          TEXT NOT NULL CHECK (tier IN ('free', 'pro')),
  version       INT NOT NULL,               -- monotonic; bumps on glyph refinement
  display_name  TEXT NOT NULL,              -- "Knit – Beginner", "編み物 – 初級"
  -- Locale variants live in a sibling table; this is the en fallback.
  description   TEXT,
  payload_path  TEXT NOT NULL,              -- "<pack_id>/<version>/payload.json" in symbol-packs bucket
  payload_size  INT NOT NULL,               -- bytes; surfaces "this pack will use X KB" in pack management UI
  symbol_count  INT NOT NULL,               -- denormalized for paywall/manage-packs UI
  signed_until  TIMESTAMPTZ,                -- NULL = perpetual; otherwise re-mint signed URL after this
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_symbol_packs_tier ON public.symbol_packs (tier);

-- Locale-aware display names (en is the fallback in the parent table).
CREATE TABLE public.symbol_pack_locales (
  pack_id      TEXT NOT NULL REFERENCES public.symbol_packs(id) ON DELETE CASCADE,
  locale       TEXT NOT NULL CHECK (locale ~ '^[a-z]{2}(-[A-Z]{2})?$'),
  display_name TEXT NOT NULL,
  description  TEXT,
  PRIMARY KEY (pack_id, locale)
);

-- Per-user pack state: which version of which pack does this user have downloaded.
-- Local-cache mirror lives in SQLDelight; this is the server-side source of truth
-- for "did the user start downloading this pack" observability + recovery.
CREATE TABLE public.user_symbol_pack_state (
  user_id              UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  pack_id              TEXT NOT NULL REFERENCES public.symbol_packs(id) ON DELETE CASCADE,
  downloaded_version   INT NOT NULL,
  downloaded_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_accessed_at     TIMESTAMPTZ,        -- nullable; stamped from client on use; powers "you haven't used X in N days, free up Y MB?" affordance
  PRIMARY KEY (user_id, pack_id)
);

CREATE INDEX idx_user_pack_state_user ON public.user_symbol_pack_state (user_id);
```

**Storage bucket** `symbol-packs` (Supabase Storage):
- Public-read **only** for `tier='free'` packs (RLS bucket policy: row-level READ matches `(SELECT tier FROM symbol_packs WHERE id = <pack_id>) = 'free'`).
- Pro packs use **per-user signed URLs** minted by the `request_pack_download` RPC (§3.3). Signed URL TTL: 1 hour. Client downloads, persists locally, refreshes on next sync if `version` bumped.
- Layout: `<pack_id>/<version>/payload.json` (the manifest + path data) + optional `<pack_id>/<version>/preview.png` (paywall thumbnail).

### 3.2 RLS

```sql
-- DEFAULT-DENY ENFORCEMENT NOTE: with RLS enabled and no INSERT/UPDATE/DELETE
-- policy on symbol_packs / symbol_pack_locales, the only write path is the
-- service-role bypass (used by agent team during content authoring via
-- apply_migration / execute_sql). DO NOT add a permissive write policy on
-- these tables — write surface must remain admin-only.
ALTER TABLE public.symbol_packs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.symbol_pack_locales ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_symbol_pack_state ENABLE ROW LEVEL SECURITY;

-- symbol_packs: everyone can read the catalog (paywall preview needs Pro pack
-- metadata visibility for users who haven't subscribed yet).
CREATE POLICY symbol_packs_read ON public.symbol_packs
  FOR SELECT USING (true);

-- symbol_pack_locales: same.
CREATE POLICY symbol_pack_locales_read ON public.symbol_pack_locales
  FOR SELECT USING (true);

-- user_symbol_pack_state: own-row SELECT + INSERT + UPDATE only.
CREATE POLICY own_pack_state_select ON public.user_symbol_pack_state
  FOR SELECT USING (user_id = auth.uid());

CREATE POLICY own_pack_state_insert ON public.user_symbol_pack_state
  FOR INSERT WITH CHECK (user_id = auth.uid());

CREATE POLICY own_pack_state_update ON public.user_symbol_pack_state
  FOR UPDATE USING (user_id = auth.uid())
  WITH CHECK (user_id = auth.uid());

-- No DELETE policy — user_symbol_pack_state DELETE happens via auth.users CASCADE only.
-- "Manage downloads → free up space" affordance updates downloaded_version = 0 instead of deleting.

-- INSERT / UPDATE / DELETE on symbol_packs + symbol_pack_locales is service-role only
-- (admin-time content authoring via apply_migration / execute_sql).
```

### 3.3 RPC: `request_pack_download`

```sql
CREATE OR REPLACE FUNCTION public.request_pack_download(
  p_pack_id TEXT
) RETURNS TABLE (
  payload_url      TEXT,
  payload_url_ttl  TIMESTAMPTZ,
  current_version  INT,
  payload_size     INT
)
SECURITY DEFINER
SET search_path = public, storage
LANGUAGE plpgsql
AS $$
DECLARE
  v_caller    UUID;
  v_pack      RECORD;
  v_is_pro    BOOLEAN;
BEGIN
  v_caller := auth.uid();
  IF v_caller IS NULL THEN
    RAISE EXCEPTION 'Unauthenticated';
  END IF;

  SELECT * INTO v_pack FROM public.symbol_packs WHERE id = p_pack_id;
  IF v_pack IS NULL THEN
    RAISE EXCEPTION 'Pack not found: %', p_pack_id;
  END IF;

  -- Pro packs: gate on RevenueCat entitlement (mirrored to subscriptions table by verify-receipt).
  IF v_pack.tier = 'pro' THEN
    SELECT EXISTS (
      SELECT 1 FROM public.subscriptions
      WHERE user_id = v_caller
        AND status IN ('active', 'in_grace_period')
        AND (expires_at IS NULL OR expires_at > now())
    ) INTO v_is_pro;

    IF NOT v_is_pro THEN
      RAISE EXCEPTION 'Pro entitlement required for pack: %', p_pack_id;
    END IF;
  END IF;

  -- Mint a 1-hour signed URL via the storage extension's helper.
  payload_url := storage.create_signed_url(
    bucket_id => 'symbol-packs',
    object_path => v_pack.payload_path,
    expires_in => 3600
  );
  payload_url_ttl := now() + INTERVAL '1 hour';
  current_version := v_pack.version;
  payload_size := v_pack.payload_size;

  RETURN NEXT;
END;
$$;

REVOKE ALL ON FUNCTION public.request_pack_download FROM public;
GRANT EXECUTE ON FUNCTION public.request_pack_download TO authenticated;
```

**Concurrency**: No FOR UPDATE — pack metadata reads + signed URL minting are idempotent. Concurrent calls for the same pack from the same user simply produce two valid signed URLs.

**Pack-id error-message oracle**: `RAISE EXCEPTION 'Pack not found: %'` echoes `p_pack_id` to the caller, distinguishable from the entitlement-failure error message. This lets unauthenticated probes enumerate valid Pro pack ids by error-message shape. **Accepted** because `symbol_packs` SELECT policy is open-read (every pack id is already world-readable for paywall preview metadata) — there is no information disclosure beyond what the public-read RLS already permits.

### 3.4 Pack payload format (storage)

```json
{
  "pack_id": "jis.knit.intermediate",
  "version": 7,
  "schema_version": 1,
  "symbols": [
    {
      "id": "jis.knit.cable.6st",
      "category": "KNIT",
      "tier": "pro",
      "path_data": "M0,0 L8,0 L8,4 L0,4 Z M2,2 ...",
      "fill": false,
      "width_units": 6,
      "height_units": 1,
      "parameter_slots": [],
      "ja_label": "6目交差",
      "en_label": "6-stitch cable"
    },
    ...
  ]
}
```

`schema_version` lets future changes (new SymbolDefinition fields) ship without breaking older clients — old clients ignore unknown fields. Once a `schema_version` requires breaking changes, the pack must split into a new `pack_id` (same as the symbol id contract).

## 4. Symbol pack delivery — client architecture

### 4.1 `CompositeSymbolCatalog`

```kotlin
class CompositeSymbolCatalog(
  private val bundled: SymbolCatalog,                 // DefaultSymbolCatalog (compile-time)
  private val downloadedPacks: DownloadedPackStore,   // SQLDelight-backed mirror of user_symbol_pack_state + payload
  private val entitlementResolver: EntitlementResolver,
) : SymbolCatalog {

  override fun get(id: String): SymbolDefinition? {
    // 1. Check downloaded packs (newest-version-wins across packs containing the same id)
    val downloaded = downloadedPacks.find(id)
    if (downloaded != null) {
      val pack = downloadedPacks.packForSymbol(id) ?: return downloaded
      // 2. Pro-tier gate
      if (pack.tier == "pro" && !entitlementResolver.isPro()) {
        return null  // Renderer falls back to "?" glyph
      }
      return downloaded
    }
    // 3. Fall through to bundled compile-time catalog
    return bundled.get(id)
  }

  override fun listByCategory(category: SymbolCategory): List<SymbolDefinition> {
    val merged = mutableMapOf<String, SymbolDefinition>()
    bundled.listByCategory(category).forEach { merged[it.id] = it }
    downloadedPacks.listByCategory(category).forEach { def ->
      val pack = downloadedPacks.packForSymbol(def.id)
      if (pack?.tier == "pro" && !entitlementResolver.isPro()) return@forEach
      merged[def.id] = def  // Newer downloaded version wins
    }
    return merged.values.toList()
  }

  override fun all(): List<SymbolDefinition> { /* analogous */ }
}
```

**Key invariants**:
- `bundled` is the offline fallback — first-launch users with no network see exactly what they see today.
- Pro gate runs on **every** `get()` call. Cheap — `entitlementResolver.isPro()` is a synchronous cached read of the `subscriptions` row last fetched.
- Pack files are NOT deleted on sub expiry — only gated. Re-subscription instantly unlocks without re-download.

### 4.2 `EntitlementResolver`

```kotlin
class EntitlementResolver(
  private val subscriptionRepository: SubscriptionRepository,
  private val clock: Clock = Clock.System,
) {
  fun isPro(): Boolean {
    val sub = subscriptionRepository.cachedActiveSubscription() ?: return false
    return sub.status in setOf("active", "in_grace_period") &&
      (sub.expiresAt == null || sub.expiresAt > clock.now())
  }
}
```

- Cache lives in `SubscriptionRepository` — Realtime channel `subscriptions-<userId>` keeps it warm. Supabase `verify-receipt` Edge Function writes through to the row; cache invalidates on push.
- `cachedActiveSubscription()` is a synchronous local read (SQLDelight). `isPro()` never touches the network on the hot path.
- Cold-launch with empty cache + no network = `isPro()` returns false (offline default-deny). Documented as design — alternative (offline default-allow) would let an expired user keep using Pro packs indefinitely just by staying offline.

### 4.3 Sync flow

```
App boot (background, after main UI is interactive):
  1. SymbolPackSyncManager.sync()
  2. Fetch GET symbol_packs (RLS open-read; everyone sees catalog)
  3. Diff against local cache (per-pack version comparison)
  4. For each stale-or-missing pack:
       a. Free-tier pack → fetch payload via public-read storage URL directly
       b. Pro-tier pack → call request_pack_download RPC; if RPC raises
          'Pro entitlement required', skip silently (sync resumes when sub
          activates)
  5. Persist new payload to local SQLDelight + filesystem
  6. UPSERT user_symbol_pack_state (downloaded_version)
  7. Emit SymbolPackSyncResult event for telemetry
```

Failure modes:
- Network error → silent retry on next app launch.
- 403 on signed URL → re-mint via RPC.
- Pack version regression (current.version < cached.version) → never legitimate; surfaces as Sentry warning, sync skips this pack.

## 5. Paywall + pack management UX

### 5.1 Paywall trigger points

A paywall sheet surfaces when:
- User taps a Pro symbol in `SymbolGalleryScreen` (preview always visible; tap-to-purchase on locked).
- User taps "Browse advanced symbols" CTA in the editor palette filter row.
- User restores a pattern/chart shared by another user that references Pro symbols (the "?" placeholders link to the paywall).

The paywall reads `getOfferings()` from RevenueCat SDK and renders the `default` Offering's `$rc_monthly` + `$rc_annual` packages. Confirm purchase → RevenueCat → `verify-receipt` Edge Function writes `subscriptions` row → Realtime push → client cache flips → CompositeSymbolCatalog opens.

### 5.2 Pack management screen (Settings → Symbol packs)

Lists all packs the user is entitled to, with:
- **Free packs**: download status (downloaded / not downloaded / update available) + size + symbol count.
- **Pro packs**: same + entitlement status badge (active / expired / never subscribed).
- **Manage downloads** affordance: "free up X MB" sets `downloaded_version = 0` server-side and clears local files. Re-downloads on next sync.
- **Symbol request** entry: opens the GitHub Issues prefill URL.

### 5.3 Symbol gallery integration

`SymbolGalleryScreen` (existing) extends to render Pro symbols with a small lock badge in the corner when `!entitlementResolver.isPro()`. Tap-on-locked routes to paywall. No change to E2E load-bearing testTags from [chart-editor.md](../../../.claude/docs/spec/chart-editor.md) — gallery testTags are pre-existing.

### 5.4 i18n keys (estimated ~25)

`title_pack_management`, `title_paywall`, `label_pack_size_kb`, `label_pack_size_mb`, `label_pack_symbol_count` (parametric), `label_pack_version_x` (parametric), `label_pack_status_downloaded` / `_update_available` / `_not_downloaded` / `_locked`, `action_download_pack`, `action_update_pack`, `action_subscribe_monthly` / `_yearly`, `action_restore_purchase`, `body_paywall_pitch`, `body_paywall_legal`, `label_subscription_active_until` (parametric), `label_subscription_expired`, `body_pack_locked_inline`, `action_request_symbol`, `action_manage_downloads`, `dialog_free_up_storage_title` / `_body`, `action_free_up_storage`, `body_offline_pro_locked`. JA semantic divergences expected for "Pro" terminology and renewal disclosure (Apple JA App Store template language).

## 6. Sub-slice plan

Phase 41 (post-beta-close, post-Phase-40-GA) splits into 5 atomic sub-slices, each independently shippable + reversible:

### 41.0 — ADR-016 (this doc, no code)

This document. Locks data shape, entitlement gating, sync flow, scope cuts. Pre-push: doc-only; no test delta.

### 41.1 — Schema + RPC + content authoring tooling

- Migration 020: `symbol_packs` + `symbol_pack_locales` + `user_symbol_pack_state` tables, RLS, indexes.
- Migration 021: `request_pack_download` SECURITY DEFINER RPC.
- Storage bucket `symbol-packs` provisioned (Supabase Dashboard or `apply_migration` if SQL helper available).
- Migrate the existing 35 crochet + 30+ knit symbols from `DefaultSymbolCatalog` into `jis.knit.beginner` + `jis.crochet.beginner` packs (free tier). The bundled compile-time catalog stays in-binary as offline fallback — the migration is duplicative for v1 (stays bundled AND uploaded as a free pack), so first-launch users without network still see today's catalog identically.
- Test: `+10` commonTest for migration / mapper / RPC exception paths.
- No UI delta. No client wiring yet.

### 41.2 — Client sync + `CompositeSymbolCatalog` + `EntitlementResolver`

- New `domain/symbol/CompositeSymbolCatalog.kt` + `EntitlementResolver.kt` + `DownloadedPackStore.kt` (SQLDelight-backed) + `SymbolPackSyncManager.kt`.
- New SQLDelight schema for downloaded pack mirror.
- `SubscriptionRepository.cachedActiveSubscription()` — reads from migration 017 mirror.
- Realtime channel `subscriptions-<userId>` (already in scope per migration 017's Realtime publication).
- Wire `CompositeSymbolCatalog` into Koin DI as the production `SymbolCatalog`. Existing `DefaultSymbolCatalog` becomes the `bundled` ctor parameter.
- Test: `+25` commonTest covering catalog merge order / Pro gate / sync failure modes / version-bump round-trip / EntitlementResolver branches.
- No new screens. Behavior identical to today on production builds because (a) all packs ship as `tier='free'` initially, (b) bundled fallback covers offline.

### 41.3 — Paywall screen + RevenueCat purchase flow

- `ui/paywall/PaywallScreen.kt` (Compose) + `PaywallScreen.swift` (SwiftUI).
- `PaywallViewModel` reading `Offerings` via RevenueCat SDK + handling `purchase()` / `restorePurchases()`.
- `RevenueCatService` `expect/actual` wrapping `Purchases.shared` (iOS) / `Purchases.sharedInstance` (Android).
- Auto-trigger when `CompositeSymbolCatalog.get(id)` returns null inside the editor (palette tap → paywall sheet).
- Entry from Settings → "Subscribe to Pro".
- Test: `+15` commonTest for ViewModel state transitions + `RevenueCatService` fake.
- E2E: deferred to manual sandbox-tester verification (Maestro doesn't drive StoreKit / Play Billing).

### 41.4 — Symbol gallery integration + pack management screen

- `SymbolGalleryScreen` lock badge rendering.
- New `ui/packmanagement/PackManagementScreen.kt` + `.swift`.
- `PackManagementViewModel` listing all entitled packs with download / update / free-up actions.
- `.github/ISSUE_TEMPLATE/symbol-request.yml` bilingual template (knit/crochet selector + JIS reference + use case + sample).
- i18n: ~25 keys.
- Test: `+10` commonTest for ViewModel state + pack list ordering.

### 41.5 — Pro tier feature gates beyond symbol packs (foundation only)

Future Pro-only features (Phase 35.2 polar editing, PR approval gates, etc.) reuse the same `EntitlementResolver`. Phase 41.5 lays the foundation but does NOT gate any existing feature behind Pro — that's intentional separation between "wire the resolver" and "decide which features are Pro" (the latter is a product decision per feature).

- Documented `EntitlementResolver` consumer pattern (any feature gating Pro must inject it + call `isPro()` at the gate site).
- No code beyond the doc + a couple of inline KDoc references at potential gate sites.

## 7. Telemetry / observability

PostHog `ClickAction` taxonomy (per ADR-015 §6) extends with:
- `RequestPackDownload(pack_id)` — user tapped "Download" on a free pack.
- `OpenPaywall(trigger)` — locked symbol tap / palette CTA / settings entry.
- `PurchaseSubscription(product_id)` — RevenueCat success.
- `RestorePurchases()` — manual restore tap.
- `RequestSymbol()` — GitHub Issues template entry.
- `FreeUpStorage(pack_id)` — manage-downloads action.

`AnalyticsEvent.Outcome` (typed):
- `PackDownloaded(pack_id, version, ms)` — sync success.
- `PackSyncFailed(pack_id, reason)` — sync failure (network / 403 / parse).
- `PaywallConverted(product_id, trigger)` — purchase landed.
- `PaywallDismissed(trigger, reason)` — closed without buying.

PostHog dashboards (post-launch):
- Pro conversion funnel: paywall opens → purchases by trigger source.
- Pack popularity: `PackDownloaded` counts by `pack_id`.
- Symbol request rate: `RequestSymbol` per week → triage cadence signal for the agent team.

Sentry tracks all `PackSyncFailed` reasons + `RevenueCatService` exceptions.

## 8. Negative consequences

1. **Free-tier Supabase storage budget pressure**. 30 packs × ~50 KB = 1.5 MB on day one. Each version bump consumes more (old versions retained for in-flight downloads). 1 GB free-tier limit comfortable for years at expected pack count (~50 over 18 months) and version churn (~3 versions per pack). If we cross 500 MB → migrate older versions to cold storage / cull. Tracked as a Phase 41 follow-up if signal surfaces.

2. **Client-side cache size**. ~30 Pro packs × ~50 KB = 1.5 MB on disk. Negligible vs. iOS/Android app sandbox quotas. Pack management UI exposes "free up storage" anyway as a courtesy.

3. **Sync race with editor**. User opens chart editor while pack sync is in flight — palette renders bundled symbols only until sync completes. Acceptable; sync is fast (< 5s typical) and gallery refresh is automatic via Flow observation.

4. **Pro pack fallback "?" glyph**. Charts authored by Pro user, viewed by free user, render Pro symbols as "?" placeholders. **NOT** a regression — same charts authored at v1 today (with the same Pro symbol id) would simply fail to render `jis.knit.cable.6st` because that id doesn't exist in the v1 bundled catalog. The new UX is strictly better: paywall link from the "?" placeholder gives the free user a path to unlock.

5. **Offline default-deny on `isPro()`**. Cold launch with no network + no cached subscription row → `isPro() = false`. Sub-active users on a flight see Pro packs locked until network returns. Trade-off documented; alternative (offline default-allow) lets perpetual offline users hold Pro forever post-expiry.

   **Clock-manipulation bypass window** (related concern): `EntitlementResolver.isPro()` evaluates `sub.expiresAt > clock.now()` using the device's local clock. A user who sets their device clock backward while offline keeps `isPro()` true past true expiry. Bounded by: (a) Realtime push restores server-side truth on next reconnect, (b) the `request_pack_download` RPC also re-validates via Postgres `now()` (server clock), so new pack downloads still fail correctly. Acceptable because the only thing the bypass extends is access to **already-downloaded** Pro pack files — no new entitlement-gated server resources can be obtained. If telemetry post-launch surfaces meaningful clock-manipulation abuse, mitigation options: (i) add a "last verified at" client check that requires periodic online re-verification (e.g. ≤ 14 days offline), or (ii) bind entitlement state to a server-issued JWT with shorter TTL.

6. **No client-side pack signature verification for v1**. Payload integrity relies on Supabase Storage's HTTPS + signed URL contract. A Supabase compromise that lets an attacker swap pack payloads would inject malicious SVG path data into all clients. Tradeoff: SVG is rendered through the existing `SymbolDrawing.kt` interpreter which only reads `M`/`L`/`C`/`Z` tokens — there is no script-execution surface. **Strict-token contract**: `SymbolDrawing.kt` MUST **reject** any token outside `{M, L, C, Z}` with a logged error and substitute the fallback "?" glyph, **NOT** silently ignore unknown tokens. This invariant must be re-validated whenever the SVG parser is extended; relaxing it (e.g. adding `A` arc support) requires a security review of the new token's parser surface. A future signed-pack scheme (Ed25519 signature in manifest, public key bundled at compile time) closes the upstream-payload-trust gap; deferred to v2 if the threat model changes.

7. **GitHub Issue request flow couples symbol catalog to a public repo**. Users with privacy concerns about associating their Knit Note GitHub account with a Skeinly issue trail may decline to request symbols. Mitigation: the privacy policy already names GitHub Issues as the bug-report channel for beta builds (per ADR-015 §4). Symbol requests are voluntary; users can also email support.

## 9. Scope cuts (post-v1, explicitly NOT in F1 MVP)

Each item below was considered and consciously excluded from Phase 41. Reopen via separate ADR if user signal demands.

- **In-app symbol request form** (modal infrastructure + Edge Function relay vs. GitHub URL prefill). Reopen post-v1 if external GitHub friction demonstrably blocks requests.
- **Pack signature scheme** (Ed25519 manifest signing + bundled public key). Threat model accepts Supabase Storage trust today.
- **Pack-level discounts / promo codes / referral incentives**. RevenueCat supports them; out of scope for v1 launch.
- **Family Sharing** (Apple). Off for alpha + beta + Phase 41 launch; revisit when subscriber base warrants per-family ARPU optimization.
- **Tiered Pro plans** (Pro Lite / Pro Plus). One Pro tier for v1.
- **Per-symbol IAP** (vs. pack-level entitlement). Friction without proportional revenue lift; pack-level keeps the catalog manageable.
- **Symbol pack tagging / search** (e.g. "lace patterns" / "cables"). Categories already exist via `SymbolCategory`. Refine if ~50 advanced symbols overflow the gallery.
- **A/B testing on paywall pricing**. RevenueCat supports it; out of scope for v1.
- **Web / desktop client**. Phase 41 UX assumes mobile-only.
- **Pack analytics for content authors** (per-symbol use frequency surfaced to the agent team). Adds telemetry surface; reopen if symbol curation effort outpaces user signal.
- **Pre-emptive Pro pack download on sub activation** (vs. lazy on next app boot). Adds a one-shot RPC trigger; lazy approach is simpler and converges within one app session.
- **Local pack expiry / TTL** (force re-download every N days). No threat model justifies it at v1.
- **Pack rollback** (publish v8, then revert to v7 for all clients). Pack content is immutable per version; rollback ships v9 with the v7 content. Same pattern as ADR-009 §9 for symbol id deprecation.

## 10. Open questions (to resolve before Phase 41.1 lands)

These do not block the ADR going to Accepted but must be answered before code starts.

1. **Storage bucket retention policy**: do we keep all historical pack versions forever, or prune older-than-N versions to bound storage cost? Default: keep all versions (audit + cold-cache restore use case). Revisit at 100 MB.

2. **`subscriptions` Realtime channel multiplexing**: today's `RealtimeSyncManager` runs 7 channels (5 baseline + 2 PR per Phase 38). Does `subscriptions-<userId>` collapse with another channel for free-tier connection cap headroom? Likely yes — coalesce with `patterns-<userId>` since both are user-scoped. Defer the engineering call to 41.2 implementer based on observed channel count vs. tier cap at that point.

3. **i18n key budget**: ~25 keys is initial estimate. Pack management screen + paywall together typically run 30–35 keys in similar feature surfaces (cf. Phase 38.3 PR detail screen at 14, Phase 38.4 conflict resolution at 10). Refine during 41.4.

4. **Trial-period UX disclosure**: 7-day free trial is encoded in App Store Connect IAP product. App Store JA review historically requires explicit trial disclosure before purchase — copy needs verifying with Apple JA App Review at submission time. May require an additional `body_paywall_trial_disclosure` key.

5. **Refund handling**: a user purchases yearly Pro, uses it for 6 months, requests refund through Apple. RevenueCat fires webhook → `verify-receipt` Edge Function writes `status='refunded'`. Local `EntitlementResolver.isPro()` flips false → CompositeSymbolCatalog locks Pro packs immediately. Existing chart cells referencing Pro symbols fall back to "?" glyph at render time. Confirm with Apple guidelines that this revocation behavior is permitted (it is — App Review accepts entitlement revocation on refund).

6. **Rate-limiting `request_pack_download` RPC**: the RPC has no per-caller rate cap. A subscriber could script repeated calls to mint thousands of valid signed URLs (each 1-hour TTL). Supabase Storage CDN cache absorbs the actual byte cost, but the Postgres RPC compute is unbounded. Pre-Phase 41 GA, add either (a) a Supabase Edge Function in front of the RPC enforcing per-user requests/minute, or (b) a Postgres advisory-lock + sliding-window check inside the RPC. Not blocking the ADR — track for 41.1 implementation slice. (Reported by security review 2026-05-04.)
