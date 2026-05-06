-- Phase 41.1.1b: Symbol pack data spine (ADR-016 §3.1 + §3.2).
--
-- Three new tables form the catalog spine for dynamic symbol-pack
-- delivery:
--
--   symbol_packs           — pack-version metadata, payload path, tier.
--   symbol_pack_locales    — locale-specific display_name/description
--                            (en fallback lives on the parent row).
--   user_symbol_pack_state — per-user "this user has downloaded
--                            version V of pack P" mirror; the SQLDelight
--                            local cache is the hot read path, this
--                            table is the server-side source of truth
--                            + recovery anchor.
--
-- Note: Phase 41.1.1a pivoted ADR-016 §3.3 from a Postgres
-- SECURITY DEFINER RPC to a Supabase Edge Function
-- (`request-pack-download`). The Edge Function deploys in Phase
-- 41.1.5. This migration deliberately does NOT create the RPC —
-- the original migration 021 plan was withdrawn during the pivot.
--
-- Storage bucket `symbol-packs` is private for BOTH tiers per
-- §3.1 — provisioned via the Supabase Storage UI / `storage.buckets`
-- INSERT in Phase 41.1.2. The bucket is the only payload sink; this
-- migration only carries the metadata schema.

-- =============================================================================
-- symbol_packs
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.symbol_packs (
    -- Stable string id, not UUID — pack ids are user-meaningful
    -- (paywall preview lists them by id-prefix-style grouping in
    -- §5.2). e.g. "jis.knit.beginner" / "jis.knit.intermediate" /
    -- "jis.crochet.beginner". ADR-009 §9 symbol-id stability contract
    -- carries through: a breaking schema_version forces a new pack id.
    id TEXT PRIMARY KEY,

    -- 'free' = no entitlement gate at the Edge Function.
    -- 'pro'  = `subscriptions` active-row check at the Edge Function.
    tier TEXT NOT NULL CHECK (tier IN ('free', 'pro')),

    -- Monotonic. Bumps on glyph refinement / new symbols added.
    -- Client compares cached_version vs. server version; downloads on
    -- mismatch. Regression (current.version < cached.version) is never
    -- legitimate — clients surface a Sentry warning + skip per §4.3.
    version INT NOT NULL CHECK (version > 0),

    -- en fallback display name. Locale-specific overrides live in
    -- `symbol_pack_locales`. The Edge Function reads neither; this is
    -- only consumed by client-side paywall + pack management UI.
    display_name TEXT NOT NULL,
    description TEXT,

    -- Storage path inside the `symbol-packs` bucket. ADR §3.1 layout:
    -- `<pack_id>/<version>/payload.json`. Storage REST sign endpoint
    -- consumes this verbatim.
    payload_path TEXT NOT NULL,

    -- Bytes. Surfaces "this pack will use X KB" in the management UI
    -- (§5.2) and powers the §10 Q1 retention monitor
    -- (`SELECT SUM(payload_size) FROM symbol_packs`).
    payload_size INT NOT NULL CHECK (payload_size >= 0),

    -- Denormalized for paywall + pack management UI without forcing a
    -- payload fetch just to count symbols.
    symbol_count INT NOT NULL CHECK (symbol_count >= 0),

    -- Reserved for future "this URL has a server-known TTL" hint;
    -- nullable today because all signed URLs are minted on demand by
    -- the Edge Function. Kept in the schema so a future Phase can
    -- pre-mint URLs server-side without a migration.
    signed_until TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Hot path for paywall + sync diff: "list packs by tier" and
-- "fetch all packs of tier=free" both filter on this column.
CREATE INDEX IF NOT EXISTS idx_symbol_packs_tier
    ON public.symbol_packs (tier);

-- Auto-touch updated_at on UPDATE so client cache invalidation can
-- key off it. Mirrors the trigger pattern from migration 017.
--
-- `SET search_path = public` locks the resolution of `now()` to the
-- pg_catalog → public chain so the function is immune to a malicious
-- schema injection in the caller's search_path (closes the
-- supabase/lints/0011 advisor warning). The 017
-- touch_subscriptions_updated_at peer carries the same pattern in
-- its idiomatic-but-unlocked form; addressing that drift is a
-- separate function-family cleanup commit, not part of 41.1.1b.
CREATE OR REPLACE FUNCTION public.touch_symbol_packs_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS symbol_packs_set_updated_at ON public.symbol_packs;
CREATE TRIGGER symbol_packs_set_updated_at
    BEFORE UPDATE ON public.symbol_packs
    FOR EACH ROW
    EXECUTE FUNCTION public.touch_symbol_packs_updated_at();

-- =============================================================================
-- symbol_pack_locales
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.symbol_pack_locales (
    pack_id TEXT NOT NULL REFERENCES public.symbol_packs(id) ON DELETE CASCADE,

    -- Two-letter language code optionally suffixed with two-letter
    -- region. Matches BCP 47 truncated form used elsewhere in the
    -- codebase (Phase 33 i18n parity script).
    locale TEXT NOT NULL CHECK (locale ~ '^[a-z]{2}(-[A-Z]{2})?$'),

    display_name TEXT NOT NULL,
    description TEXT,

    PRIMARY KEY (pack_id, locale)
);

-- =============================================================================
-- user_symbol_pack_state
-- =============================================================================
--
-- One row per (user, pack) the user has ever downloaded. The
-- "manage downloads → free up space" affordance updates
-- downloaded_version = 0 (and the client clears the local file)
-- rather than DELETE-ing this row, so we keep the audit trail of
-- what the user has historically been entitled to / interacted with.

CREATE TABLE IF NOT EXISTS public.user_symbol_pack_state (
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    pack_id TEXT NOT NULL REFERENCES public.symbol_packs(id) ON DELETE CASCADE,

    -- 0 = "previously downloaded, now freed". > 0 = the version
    -- currently on disk. Versions are monotonic on the parent so
    -- this is comparable directly with `symbol_packs.version`.
    downloaded_version INT NOT NULL CHECK (downloaded_version >= 0),

    downloaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Stamped from the client on each pack consumption. Powers the
    -- "you haven't used X in N days, free up Y MB?" affordance per
    -- §5.2. Nullable because the row is created before the user
    -- ever opens the editor + uses a symbol from the pack.
    last_accessed_at TIMESTAMPTZ,

    PRIMARY KEY (user_id, pack_id)
);

-- Per-user "what packs do I have downloaded" query — gallery
-- composition + pack management screen.
CREATE INDEX IF NOT EXISTS idx_user_pack_state_user
    ON public.user_symbol_pack_state (user_id);

-- =============================================================================
-- Row-Level Security
-- =============================================================================
--
-- DEFAULT-DENY ENFORCEMENT NOTE: with RLS enabled and no
-- INSERT/UPDATE/DELETE policy on `symbol_packs` /
-- `symbol_pack_locales`, the only write path is the service-role
-- bypass (used during admin-time content authoring via
-- `apply_migration` / `execute_sql` / the 41.1.4 seed task).
-- DO NOT add a permissive write policy on these tables — the write
-- surface MUST remain admin-only. Future agent team members should
-- treat any PR that adds INSERT/UPDATE/DELETE policy on these
-- tables as a security review trigger.

ALTER TABLE public.symbol_packs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.symbol_pack_locales ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_symbol_pack_state ENABLE ROW LEVEL SECURITY;

-- symbol_packs: open-read. Paywall preview needs to see Pro pack
-- metadata (display name + symbol count + size) for users who haven't
-- subscribed yet. The actual payload is gated by the Edge Function;
-- only metadata is world-visible.
CREATE POLICY "symbol_packs_read"
    ON public.symbol_packs FOR SELECT
    USING (true);

-- symbol_pack_locales: same.
CREATE POLICY "symbol_pack_locales_read"
    ON public.symbol_pack_locales FOR SELECT
    USING (true);

-- user_symbol_pack_state: own-row only.
CREATE POLICY "user_symbol_pack_state_select_own"
    ON public.user_symbol_pack_state FOR SELECT
    USING (user_id = auth.uid());

CREATE POLICY "user_symbol_pack_state_insert_own"
    ON public.user_symbol_pack_state FOR INSERT
    WITH CHECK (user_id = auth.uid());

CREATE POLICY "user_symbol_pack_state_update_own"
    ON public.user_symbol_pack_state FOR UPDATE
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());

-- INTENTIONALLY NO DELETE POLICY on user_symbol_pack_state. The
-- "free up space" affordance updates downloaded_version = 0; rows
-- only disappear via auth.users CASCADE (account deletion per
-- ADR-005). DO NOT add a permissive DELETE policy without revisiting
-- the audit-trail invariant.

-- =============================================================================
-- Realtime publication
-- =============================================================================
-- Symbol pack metadata is pull-on-cold-launch per ADR-016 §4.3
-- (sync manager fetches symbol_packs once at app boot and again on
-- explicit refresh). user_symbol_pack_state could in principle be
-- pushed via Realtime to keep multi-device users' "what's
-- downloaded" mirrors in sync, but ADR-016 §4 does not require
-- multi-device pack state coherence in v1. NOT adding any of these
-- tables to supabase_realtime publication for v1.
