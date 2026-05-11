# Runbook — Symbol Pack Content Publishing

> **Purpose**: step-by-step procedure for publishing a new symbol pack, patching an existing pack to a new version, or rolling a pack back. Self-contained — no flipping between docs to execute the task.
>
> **Audience**: operator (typically the project owner) acting against the production Supabase project.
>
> **What this runbook does NOT cover**: per-symbol authoring conventions (glyph design, JIS reference compliance, parameter slot semantics) — see [ADR-009](../adr/009-parametric-symbols.md) and the `symbol-review/` series. RevenueCat Pro entitlement setup — see [docs/en/ops/beta-testing.md](beta-testing.md) for the sandbox flow.

## Mental model

A symbol pack is published in two coupled writes:

1. **Storage**: upload `payload.json` (the symbol bodies) to `symbol-packs/<pack_id>/<version>/payload.json` in the private Supabase Storage bucket.
2. **Postgres**: INSERT (or UPDATE) one row in `public.symbol_packs` that points to the path above and declares `version`, `tier`, `payload_size`, `symbol_count`.

Optionally:
- INSERT into `public.symbol_pack_locales` for ja-JP (or future locale) display name + description.
- Upload `preview.png` (optional thumbnail) next to the payload — same path layout, `<pack_id>/<version>/preview.png`.

Clients pick up the new pack on their next sync cycle (cold start + post-purchase + foreground hook). No app update is required.

The current architecture is documented in [docs/en/spec/symbol-pack-delivery.md](../spec/symbol-pack-delivery.md).

---

## Task: publish a new free-tier pack

### Prerequisites

- The pack's symbol definitions are added to the bundled compile-time catalog at `shared/src/commonMain/.../domain/symbol/catalog/`.
  - This is the source of truth from which `payload.json` is generated. Authoring a pack outside the bundled catalog is **out of v1 scope** — every shipped pack today is a partition of the bundled `DefaultSymbolCatalog`.
- A unique stable `pack_id` chosen following the convention `<system>.<craft>.<tier_or_qualifier>` (e.g. `jis.knit.intermediate`, `cyc.crochet.colorwork`). Once chosen the id is immutable for the life of the pack — bumping a breaking `schema_version` forces a new id per ADR-016 / ADR-009 §9.

### Steps

1. **Generate the payload + seed SQL block locally.**
    ```bash
    ./gradlew :shared:generateSymbolPackPayloads
    ```
    The task runs the always-on `SymbolPackPayloadGeneratorTest` with the `skeinly.payloads.outputDir` system property set. Output goes to `shared/build/generated/symbol-pack-payloads/`. The Gradle console prints both the manifest summary and the seed SQL block (`INSERT INTO public.symbol_packs ...`) to stdout.

    > If the generator does not yet know about your new pack you'll need to extend it first. The generator's pack-partition logic lives in `SymbolPackPayloadGeneratorTest.kt` — add a new partition keyed by `pack_id` mapping to a `List<SymbolDefinition>` selected from the bundled catalog. Tests will fail until you re-run the generator with the new partition declared.

2. **Verify the generated file locally.**
    ```bash
    ls -lh shared/build/generated/symbol-pack-payloads/
    jq '.pack_id, .version, .schema_version, (.symbols | length)' \
      shared/build/generated/symbol-pack-payloads/<pack_id>__<version>__payload.json
    ```
    Sanity checks:
    - `pack_id` matches the id you chose.
    - `version` starts at 1 for a new pack.
    - `schema_version` matches `SymbolPackPayload.CURRENT_SCHEMA_VERSION` (1 today).
    - `symbols.length` matches the intended pack inventory.

3. **Upload `payload.json` to the Storage bucket.**

    **Option A — Supabase Dashboard** (easier for one-off uploads):
    - Navigate to Storage → `symbol-packs` bucket.
    - Create the folder path `<pack_id>/<version>/` (Dashboard prompts for folder creation as you type the path).
    - Upload `payload.json` into that folder.

    **Option B — Supabase CLI** (scriptable):
    ```bash
    supabase storage cp \
      shared/build/generated/symbol-pack-payloads/<pack_id>__<version>__payload.json \
      ss:///symbol-packs/<pack_id>/<version>/payload.json
    ```

    Verify the upload:
    ```bash
    supabase storage ls ss:///symbol-packs/<pack_id>/<version>/
    ```

    Expected: one row showing `payload.json` at the matching byte size from step 2.

4. **Apply the seed metadata to Postgres.**

    The `:shared:generateSymbolPackPayloads` task printed an `INSERT INTO public.symbol_packs ...` block — copy it.

    Apply via the Supabase MCP tool (preferred — it lands as a tracked migration):
    ```
    Run mcp__supabase__apply_migration with:
      name: phase_X_X_seed_<pack_id_safe>
      query: <the printed INSERT block>
    ```

    Or via the CLI:
    ```bash
    supabase migration new phase_X_X_seed_<pack_id_safe>
    # paste the INSERT block into the new file under supabase/migrations/
    supabase db push
    ```

5. **Add the ja-JP locale row** (mandatory if the pack ships to JP users):
    ```sql
    INSERT INTO public.symbol_pack_locales (pack_id, locale, display_name, description) VALUES
      ('<pack_id>', 'ja', '<日本語表示名>', '<日本語の説明>');
    ```

    Apply the same way as step 4. en-US falls back to the parent row's `display_name` / `description` — no separate en row is needed.

6. **Verify end-to-end.**
    ```sql
    SELECT
      id, tier, version, display_name, payload_size, symbol_count,
      (SELECT display_name FROM public.symbol_pack_locales WHERE pack_id = sp.id AND locale = 'ja') AS ja_name
    FROM public.symbol_packs sp
    WHERE id = '<pack_id>';
    ```
    Apply via `mcp__supabase__execute_sql` or `supabase db query`. Expected: one row with the row data and a populated `ja_name`.

    Then trigger a smoke test of the Edge Function from a signed-in user's perspective:
    ```bash
    USER_JWT="<get from a real user session>"
    PROJECT_REF="<your supabase project ref>"
    curl -s -X POST \
      "https://${PROJECT_REF}.supabase.co/functions/v1/request-pack-download" \
      -H "Authorization: Bearer ${USER_JWT}" \
      -H "Content-Type: application/json" \
      -d "{\"pack_id\":\"<pack_id>\"}"
    ```
    Expected: HTTP 200 with `{payload_url, payload_url_ttl, current_version, payload_size}`. `curl -s ${payload_url}` then returns the JSON body you uploaded.

7. **Verify on a real client.**
    - Cold-launch the app on a TestFlight or Play Internal build.
    - The sync manager logs (`SymbolPackSyncManager.sync()` outcome) should report `Downloaded(packId=<pack_id>, version=1)`.
    - Open the chart editor — the new pack's symbols appear in the palette filtered to the pack's `category`.

### Time budget

Steps 1–6: ~10 min for someone familiar with the layout. Step 7 (real-device verification) depends on tester availability; can be deferred behind the closed beta cycle.

---

## Task: publish a Pro-tier pack

Same as the free-tier flow, with these deltas:

- **Step 4 INSERT**: `tier` column is `'pro'` instead of `'free'`.
- **Storage path** still uses the same `<pack_id>/<version>/payload.json` layout. The bucket is private for both tiers; the Edge Function is the only authorized reader and it enforces the entitlement gate before minting a signed URL.
- **Verification step 6 must use a signed-in user with an active `subscriptions` row** (status `active` or `in_grace_period`). A non-Pro user's curl should return `HTTP 403 {"error":"pro_entitlement_required","pack_id":"<id>"}` — that's the deliberate behavior.
- **Per-symbol `tier` field in `payload.json`**: today every symbol in a Pro pack carries `"tier": "PRO"`. A future "free pack with some paid symbols" arrangement is forward-compat at the wire-format level (per ADR-016) but is not in current operational practice.

### Pro pack pre-publish checklist

- [ ] RevenueCat product configured with the iOS / Android SKU.
- [ ] `subscriptions` table holds an active row for at least one tester (use `grant_alpha_pro(uid)` for quick smoke, or the sandbox tester flow per [beta-testing.md](beta-testing.md) for full E2E).
- [ ] Pro pack metadata copy reviewed by the `monetization-strategist` agent + `knitter` agent for paywall fitness.

---

## Task: patch an existing pack (bump version)

Use this when glyph refinement, copy fix, or additive symbol expansion needs to reach existing users without forcing an app update.

### Steps

1. **Decide the version bump.** Versions are monotonic integers per `pack_id`. Increment by 1. Re-using a version is forbidden — every client compares `version` numerically and a regression silently keeps the higher-versioned cache (see `SymbolPackSyncManager.VersionRegression` outcome).

2. **Regenerate the payload at the new version.**
    Same `:shared:generateSymbolPackPayloads` task. The generator's pack-partition declaration controls the version; bump it there before running.

3. **Upload the new payload.**
    ```bash
    supabase storage cp \
      shared/build/generated/symbol-pack-payloads/<pack_id>__<new_version>__payload.json \
      ss:///symbol-packs/<pack_id>/<new_version>/payload.json
    ```
    The old path (`<pack_id>/<old_version>/payload.json`) stays in Storage — leaving it is harmless (no cost pressure at current cardinality) and protects users who somehow signed an old URL just before the bump.

4. **UPDATE the catalog row.**
    ```sql
    UPDATE public.symbol_packs
       SET version       = <new_version>,
           payload_path  = '<pack_id>/<new_version>/payload.json',
           payload_size  = <bytes from the new file>,
           symbol_count  = <count from the new file>,
           updated_at    = now()  -- redundant; the BEFORE UPDATE trigger sets this anyway
     WHERE id = '<pack_id>';
    ```

5. **(Optional) Update ja-JP locale row** if the localized display name or description changed:
    ```sql
    UPDATE public.symbol_pack_locales
       SET display_name = '<new ja display name>',
           description  = '<new ja description>'
     WHERE pack_id = '<pack_id>' AND locale = 'ja';
    ```

6. **Verify.**
    Same as new-pack step 6.

### What clients do

On the next sync, each client sees `pack.version > cachedVersion` and re-downloads. The local SQLDelight cache stores `(pack_id, new_version)`; the old version is left as a peer row and is naturally orphaned (the `getLatestPayload(packId)` query returns the highest version). A future Phase 41.4 "free up space" affordance cleans these orphans.

---

## Task: roll back a published pack

This is **not a recommended operation** — bumping forward to a corrected version is structurally safer. Rolling back means setting `symbol_packs.version` to a lower number than is currently active in the wild, which clients will treat as a `VersionRegression` and refuse to apply (they keep the higher cached version).

Use only if a freshly-published pack contains a security-relevant defect (e.g. a parameter-slot payload that crashes the editor) and immediate revocation matters more than uniform clients.

### Steps

1. **Bump forward to a fixed version.** This is the safe path. Re-do the patch flow above with a higher version number that has the defect removed.

2. **(Last resort) Remove the offending row.**
    ```sql
    DELETE FROM public.symbol_packs WHERE id = '<pack_id>';
    ```
    Effect: the pack disappears from every client's manifest on next sync. `LocalSymbolPackDataSource.replaceManifest` drops the local catalog row but **does NOT touch the payload table** — users who have already downloaded the pack keep the bodies on disk per the deliberate "a server archive shouldn't silently delete cells the user already authored against" decision. Their cached symbols continue to render until they explicitly free space (Phase 41.4).

3. **Re-publish under a new id** if the design has changed enough that re-keying makes sense. `payload.json` references the symbol ids inside; existing authored charts continue to render via the bundled compile-time fallback if you keep those symbol ids in the bundled catalog.

---

## Task: add a new locale to an existing pack

Currently we ship `ja` translations alongside the en fallback on the parent row. To add a third locale (e.g. `zh-CN`, `ko-KR`):

```sql
INSERT INTO public.symbol_pack_locales (pack_id, locale, display_name, description) VALUES
  ('<pack_id>', '<bcp47>', '<localized name>', '<localized description>');
```

The `locale` CHECK constraint accepts `^[a-z]{2}(-[A-Z]{2})?$` — `en`, `ja`, `zh`, `zh-CN`, `ko-KR` are all valid. The app must also have UI strings localized for the new locale (separate work — see `docs/en/i18n-convention.md`). Pack metadata is one of several i18n surfaces.

---

## Task: upload an optional preview thumbnail

For paywall-preview UX (Phase 41.4) a `preview.png` may sit next to `payload.json`:

```
symbol-packs/<pack_id>/<version>/payload.json
symbol-packs/<pack_id>/<version>/preview.png   ← optional
```

The bucket's `allowed_mime_types` includes `image/png`. No additional Postgres row is needed; the client constructs the thumbnail URL by convention.

```bash
supabase storage cp ./preview.png ss:///symbol-packs/<pack_id>/<version>/preview.png
```

---

## Troubleshooting

### `request-pack-download` returns HTTP 422 from GitHub Issues during a smoke test

You ran the wrong Edge Function smoke test — that error belongs to `submit-bug-report`. The right smoke test is in step 6 above.

### `request-pack-download` returns HTTP 404 `pack_not_found`

Check that the seed migration actually applied: `SELECT * FROM public.symbol_packs WHERE id = '<pack_id>';`. Common causes: the migration was created locally but not pushed (`supabase db push`), or the migration name typo'd the pack_id.

### Client logs `PackSyncOutcome.SkippedProEntitlement` for a free pack

Either the catalog row's `tier` column is `'pro'` (re-check the seed INSERT — a typo on the tier value is the most common cause), or the manifest hasn't been re-fetched on the client side (force-reload by cold-launch).

### Client logs `PackSyncOutcome.VersionRegression`

The server-side `symbol_packs.version` is strictly less than what the client has locally cached. Either you rolled back the version (see "roll back" task above for why this is unusual) or a manual UPDATE accidentally lowered the version. Bump forward to a higher version than any client has seen.

### Storage upload succeeds but the Edge Function returns 500 `internal_error`

Pull the function logs:
```
Run mcp__supabase__get_logs with service: edge-function
```
Look for `storage sign failed` or `symbol_packs lookup failed`. Most common causes: the `payload_path` in the catalog row does not match the actual Storage path (case sensitivity, trailing slash, missing version segment), or the bucket policy was inadvertently changed.

### Sync goes silent — no logs from `SymbolPackSyncManager` at all

Verify the post-Phase-41.3 trigger paths are wired in the build under test:
- Foreground-resume hook (`onResume` → `manager.sync()`)
- Post-purchase RevenueCat callback (Phase 41.3)
- App launch warm-up

If only the constructor's warm-up `CompositeSymbolCatalog.refresh()` is firing and the sync manager never runs, the trigger wiring regressed — check `applicationScope` setup in the `di/` modules.

---

## Reference data — the production catalog (as of 2026-05-12)

```sql
SELECT id, tier, version, payload_path, symbol_count, payload_size FROM public.symbol_packs;
```

| id | tier | version | payload_path | symbols | bytes |
|---|---|---|---|---|---|
| `jis.knit.beginner` | free | 1 | `jis.knit.beginner/1/payload.json` | 35 | 13,558 |
| `jis.crochet.beginner` | free | 1 | `jis.crochet.beginner/1/payload.json` | 35 | 20,492 |

No Pro packs published yet.

## Related runbooks

- [docs/en/ops/incident-playbook.md](incident-playbook.md) — common failure modes including symbol pack download issues.
- [docs/en/ops/beta-testing.md](beta-testing.md) — Pro sandbox tester setup needed to verify Pro packs E2E.
- [docs/en/ops/secrets-rotation.md](secrets-rotation.md) — Edge Function service-role rotation (rare, but affects this surface).
