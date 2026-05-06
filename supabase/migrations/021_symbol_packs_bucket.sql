-- Phase 41.1.2: Storage bucket `symbol-packs` (ADR-016 §3.1 post-Path-A pivot).
--
-- PRIVATE bucket for both `tier='free'` and `tier='pro'` packs. All payload
-- reads (free + pro) are mediated by the `request-pack-download` Edge
-- Function (deploys in Phase 41.1.5) which mints a per-call 5-minute signed
-- URL after an entitlement check. The Edge Function uses the service-role
-- key for the Storage REST sign call, so no `storage.objects` RLS policy
-- is needed — bucket privacy + signed-URL JWT validation handle access:
--
--   1. Direct bucket access (no signed token) → 403 (private bucket).
--   2. Signed URL with valid token → Storage validates JWT → grants access.
--
-- file_size_limit: 1 MiB (1,048,576 bytes). Per ADR-016 §10 Q1, v1
-- cardinality is ≤10 packs × ≤10 versions × ≤1 MB each ≈ 100 MB total
-- bucket size. The 1 MiB per-payload cap is a safety margin — the actual
-- `jis.knit.beginner` payload is expected at ~30 KB (35+30 symbols ×
-- ~400 byte average per symbol). Re-evaluate if a pack legitimately
-- needs to exceed this (e.g. a future high-density colorwork pack).
--
-- allowed_mime_types: `application/json` (mandatory `payload.json`) +
-- `image/png` (optional `preview.png` thumbnail per §3.1 layout). PNG
-- allowed up-front so 41.4 paywall thumbnails can land without changing
-- bucket config (changing it on a populated bucket is operationally
-- noisier than a one-shot setup).
--
-- DELIBERATELY NO `storage.objects` POLICY for this bucket. Adding one
-- would create a redundant gate next to the Edge Function's entitlement
-- check, and any future policy bug would widen attack surface beyond
-- "service-role mints, JWT verifies." Future agent team members should
-- treat any PR adding a bucket policy here as a security review trigger.

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('symbol-packs', 'symbol-packs', false, 1048576, ARRAY['application/json', 'image/png'])
ON CONFLICT (id) DO NOTHING;
