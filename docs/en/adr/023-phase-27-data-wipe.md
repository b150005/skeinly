# ADR-023: Phase 27 — Data Wipe (Account-Preserved)

## Status

Accepted (2026-05-14). Phase 27 is a HARD-GATE for alpha launch — all four implementation sub-slices (27.1–27.4) ship before TestFlight / Play Internal Testing invitations are issued.

## Context

The Play Console "Data Safety" form (vendor-setup A0d-6) carries an optional sub-question:

> アカウントの削除を必要とすることなく、一部またはすべてのデータの削除をリクエストする方法をユーザーに提供していますか？
> ("Do you provide users a way to request deletion of some or all of their data WITHOUT requiring account deletion?")

Skeinly's current answer is **いいえ**. We ship two destructive primitives today:

1. **Full account deletion** — `delete_own_account()` SECURITY DEFINER RPC (ADR-005, migration 007) deletes the row in `auth.users`; everything downstream CASCADEs out. The user loses their sign-in identity entirely.
2. **Per-item CRUD deletion** — each table that stores user-authored content carries an authenticated DELETE policy. The user can hand-delete one pattern, one project, one comment at a time. Bulk content reset is not feasible at the UX level.

Neither primitive matches the privacy-control semantic the Data Safety form is asking about. Phase 27 introduces a third primitive — **bulk content deletion that preserves the account** — flipping the Data Safety answer to **はい**.

Beyond the compliance trigger, "delete all my data but keep my account" is a recognized privacy control category. Mature consumer apps (GitHub, Google, Discord) all expose it distinct from account closure. The semantic distinction matters at user-trust level: a user may want a fresh canvas (e.g. they shared the app with a family member and want to start over, they joined the beta with throwaway content, they want to redo all their pattern naming) without forfeiting Pro entitlement, sign-in identity, or display name.

Relationship to neighboring ADRs:

- **ADR-005** (account deletion via `delete_own_account` RPC) — Phase 27 mirrors the SECURITY DEFINER RPC shape but stops short of `auth.users` deletion.
- **ADR-016** (Phase 41 Pro subscription) — Phase 27 must answer whether the `subscriptions` row survives wipe.
- **ADR-017** (Phase 24 push notifications) — Phase 27 must answer whether `device_tokens` rows survive wipe.
- **ADR-021** (UGC moderation) — `user_blocks` and `ugc_reports` introduce blocker/reporter rows whose wipe semantics must be settled.

## First decision — new ADR (not amendment to ADR-005)

This document is **ADR-023**, a new ADR. ADR-005 is left untouched.

Rationale (agent-team deliberation, 2026-05-14):

- **architect**: Data wipe is structurally distinct from account deletion — different RPC name, different preservation matrix, different web fallback slug, different UX confirmation copy. Bundling both into ADR-005 muddies a narrative about CASCADE-driven account purge.
- **product-manager**: Future readers will search "data wipe" or "delete my data" — a dedicated ADR address surfaces faster than a buried Amendment block.
- **technical-writer**: ADR-005 is 35 lines and intentionally narrow. Appending a wipe spec triples it and changes its scope mid-document; that's a smell.
- **implementer**: ADR-023 cross-references ADR-005 for the SECURITY DEFINER pattern; no precedent re-derivation is needed. The two ADRs read cleanly side-by-side.

**Decision**: ADR-023 is a new ADR. ADR-005 stays as-is.

## Agent-team deliberation

### product-manager — UX clarity (wipe vs. delete)

Users must be able to pick "wipe data" vs. "delete account" confidently. The two are commonly confused. Settings → プライバシー surfaces two adjacent rows:

- **"Delete all my data"** — keeps account, sign-in, Pro state; clears content.
- **"Delete account"** — destroys everything including sign-in.

Each row's tap opens a modal that exhaustively names what is deleted vs. preserved. Copy mirrors GitHub's "Delete all of your repositories" vs. "Delete your account" model — explicit enumeration, not hand-wavy "this will reset your data". Tester signal in Phase 39 may reveal users still confuse the two; if so, we add a leading comparison table inside the modal. Post-wipe, the user lands on Pattern Library (now empty) with a confirmation banner. They do NOT see a sign-out screen — that's the account-deletion path.

**Decision**: Two clearly-distinguished Settings rows. Modal copy enumerates preservation matrix in user-facing language. Post-wipe lands on Pattern Library (empty state).

### architect — RPC shape + transaction + FK order

The wipe RPC must:

1. Run inside a single transaction so a partial wipe is structurally impossible (either all user content goes, or none does).
2. Be idempotent under retry — if the RPC is called twice in close succession (network retry, double-tap), the second call observes an already-empty state and returns success without error.
3. Order DELETEs by FK dependency: descendant rows first, then ancestors. Postgres' implicit FK-resolution within a transaction handles this when ON DELETE CASCADE is wired (and most of our FKs are), but we explicitly list the descendant DELETEs first to make audit reading easier and to side-step any non-CASCADE FK gotcha.
4. End with a single audit-row INSERT into `activities` (see decision (e)) so the audit trail records "user wiped at 2026-05-14T12:34:56Z" even though every other `activities` row was just nuked in the same transaction.

Reentrancy hazard: if RPC is in flight, a second concurrent call could see partial state. PostgreSQL row-level locks on `auth.users WHERE id = auth.uid()` (`SELECT ... FOR UPDATE`) at the top of the RPC body serialize concurrent invocations from the same user. The second caller blocks until the first transaction commits, then runs against the now-empty state (idempotent no-op for content tables, fresh audit-row INSERT).

**Decision**: Single-transaction SECURITY DEFINER `public.wipe_own_data()`. Acquires a row-level lock on `auth.users WHERE id = auth.uid()` at the top of the body. DELETEs in descendant-first order. Final INSERT writes the audit row. Returns `void`.

### security-reviewer — auth posture + CSRF on web fallback + idempotency

**In-app path** runs against an authenticated Supabase session. `auth.uid()` is the sole identifier the RPC trusts; no user-controllable parameters reach the SQL. This matches `delete_own_account` precedent.

**Re-authentication** — the question is whether `wipe_own_data` should require the user to re-enter their password / pass MFA challenge / use biometric before the RPC fires. Stance:

- **Wipe is destructive but reversible at zero cost only via cloud-side restore (which we don't operate).** The user's local SQLDelight cache survives the RPC call, but that's "by accident" — sync-down resolves the empty server state into the local cache on next sync, wiping it too.
- **Same destructive surface as account deletion**, which today does NOT require re-auth (ADR-005 path is a direct RPC from an authenticated client). We are not regressing the security floor here, but neither do we get to claim a stronger floor.
- **MFA gate (Phase 26)**: once MFA enrollment is wired (Phase 26.5), the wipe path SHOULD include the MFA challenge step if the user has enrolled a TOTP factor. Until Phase 26 lands, the second-confirmation step is text-typing only (matching account-deletion UX).
- **Biometric gate (Phase 26.6)**: same — once `LocalAuthentication` / `BiometricPrompt` are wired for "sensitive actions", `wipe_own_data` is on the list alongside account deletion + MFA disable + (future) marketplace payout-method change.

Phase 27 ships text-typed-phrase confirmation. Phase 26 retrofits the MFA + biometric gates onto both `wipe_own_data` and `delete_own_account` in the same slice.

**Web fallback page CSRF** — the `/skeinly/data-deletion/` page (Phase 27.3) mirrors the existing `/skeinly/account-deletion/` page architecture: web form authenticates against Supabase Auth (email/password or OAuth post-Phase-26), then calls an Edge Function `wipe-my-data-web` (`verify_jwt = true`) which invokes the same `wipe_own_data` RPC via service-role-with-impersonated-user pattern. Supabase Auth's own session cookies carry CSRF protection via SameSite; the Edge Function additionally requires the access token in the `Authorization: Bearer` header on the POST (matching the existing account-deletion-web pattern). No new attack surface beyond the established account-deletion-web shape.

**Idempotency** — already covered in architect's section: row-level lock on `auth.users`. Result: double-tap is safe; concurrent-tab is safe; retry on transient network error is safe.

**Decision**: No new re-auth requirement at Phase 27 (parity with account-deletion floor). Phase 26 retrofits MFA + biometric gates onto both destructive RPCs in one pass. Web fallback page reuses the account-deletion-web architecture (`verify_jwt = true` Edge Function + Bearer token POST).

### ui-ux-designer — two-step confirmation, post-wipe empty state, copy

**Step 1 — explanation modal**: opens on Settings row tap. Layout matches account-deletion modal's two-column "Preserved | Deleted" table with concrete user-facing terms (NOT table names). Bottom CTA: "Continue" (red destructive). Cancel: "Keep my data" (top-left).

**Step 2 — confirmation phrase typing**: user types the fixed phrase `delete my data` (EN) / `データを削除` (JA) into a text field. Matching uses case-insensitive trim. The "Delete all my data" submit button is disabled until the phrase matches. Display-name typing is rejected as the phrase mechanism: in MFA-enabled accounts the display name is sometimes the same as the username, which weakens the "deliberate action" check. A fixed phrase is unambiguous.

**Post-wipe UX**: on RPC success, navigate to Pattern Library (root entry of the bottom-tab nav). Empty state shows the default "Create your first pattern" CTA. A dismissible banner reads "Your data has been deleted. Your account is still active." — auto-dismisses after 8 seconds OR on tap.

**Copy clarity**: explanation modal's "Preserved" column names: sign-in identity, display name, Pro membership (if applicable), avatar, blocked-user list (Phase 27 carries forward — see preservation matrix). "Deleted" column names: patterns, projects, charts, comments, suggestions, photos, push notification settings. We don't use the literal table names; we use the user-facing labels matching existing Settings copy.

**Decision**: Two-step modal — exhaustive enumeration of preserved vs. deleted in user-facing language, fixed-phrase confirmation typing. Post-wipe routes to Pattern Library with auto-dismissing banner.

### implementer — RPC body + Edge Function pattern

The RPC body is a top-to-bottom list of DELETE statements scoped to `auth.uid()`. Each DELETE targets a specific table; even where ON DELETE CASCADE would handle the row, we still issue the explicit DELETE for two reasons: (a) audit clarity at the migration-file level — a reader doesn't have to mentally trace which tables CASCADE-fall through, and (b) defensive consistency in case future migrations change a CASCADE to SET NULL on a downstream table.

Web fallback Edge Function `wipe-my-data-web` mirrors `delete-my-account-web` (the existing Edge Function backing the account-deletion page; see `supabase/functions/delete-my-account-web/`). The Edge Function authenticates the user via the Bearer JWT in the POST, calls `wipe_own_data()` RPC under the user's session (NOT service-role bypass — we want RLS to enforce the same `auth.uid()` semantic), returns 200 on success / 401 on missing-or-invalid token / 500 on RPC error.

**Decision**: Explicit DELETE per table inside one transaction. Web Edge Function mirrors `delete-my-account-web` architecture. Function returns `void`; client treats absence of error as success.

---

## Decisions

### (a) Preservation matrix

Complete inventory of `public.*` tables (verified against `supabase/migrations/001`–`032`). Every production table receives an explicit wipe vs. preserve choice.

**Wipe** = `DELETE FROM <table> WHERE <user-scope column> = auth.uid()` inside the `wipe_own_data` transaction.

**Preserve** = the row is left untouched.

**Cascade** = the row is not directly DELETE'd; the FK cascade from a wiped parent removes it. (Listed separately so readers see exactly which removals are first-order vs. transitive.)

**Anonymize** = the row stays but identity-hint columns reset (only applies to `profiles` — see note below).

| Table | Action | Owner column | Rationale |
|---|---|---|---|
| `auth.users` | **Preserve** | `id` (== `auth.uid()`) | Definitionally preserved — wipe-not-delete distinction is the entire feature. |
| `public.profiles` | **Preserve** (display_name reset to empty string optional, see note) | `id` | Holds the sign-in display_name + avatar_url + bio. Display name + avatar are part of the user's identity that survives wipe (matches "Pro user still wants to be recognized in PR threads post-wipe"). `bio` is content-ish but UX-light; keep for parity. **Optional reset**: if user-research surfaces that a fresh canvas should also clear display_name, a Phase 27+ slice can reopen — not Phase 27 scope. |
| `public.patterns` | **Wipe** | `owner_id` | Core content — every pattern the user authored. CASCADE-removes `chart_documents`, `chart_versions`, `chart_variations`, `progress` (via `projects`), `project_segments` (via `projects`), `shares`, `comments` (target_type = 'pattern' with the wiped pattern_id — but comments are stored owner-side as well, see below), `suggestions`, `suggestion_comments`. |
| `public.projects` | **Wipe** | `owner_id` | Core content. CASCADE-removes `progress` and `project_segments`. |
| `public.progress` | **Cascade** | (via `projects.id`) | FK from `projects` is `ON DELETE CASCADE`; wiping `projects` removes these. Explicit DELETE redundantly issued first inside the RPC for audit clarity. |
| `public.project_segments` | **Cascade** | `owner_id` (also direct) | FK from `projects.id` is `ON DELETE CASCADE`. Direct `owner_id` exists too — RPC issues the direct DELETE first. |
| `public.chart_documents` | **Cascade** | `owner_id` (also direct) | FK from `patterns` cascades. Direct `owner_id` exists — RPC issues the direct DELETE first. |
| `public.chart_versions` (was `chart_revisions`) | **Wipe** | `owner_id` | Append-only history of user-authored revisions. User asked for a clean slate — history goes too. `author_id` is `ON DELETE SET NULL` so revisions written into OTHER users' patterns by this user (Phase 38 merge path) are anonymized, NOT removed. |
| `public.chart_variations` (was `chart_branches`) | **Wipe** | `owner_id` | Branches the user authored. Cascades via patterns too — direct DELETE is the audit-clear path. |
| `public.shares` | **Wipe** | `from_user_id` | Outgoing shares the user authored. Incoming shares (`to_user_id = auth.uid()`) are also wiped — the user is "leaving" the share relationship symmetrically. |
| `public.comments` | **Wipe** | `author_id` | Every comment authored by the user, anywhere. Cross-pattern: a comment the user left on someone else's pattern goes too. |
| `public.activities` | **Wipe + 1 audit insert** | `user_id` | All prior activity rows go. RPC's final statement INSERTs ONE new row with `type = 'data_wiped'` (new enum value — see decision (e)). |
| `public.suggestions` (was `pull_requests`) | **Wipe (source-side) / Anonymize (target-side via FK SET NULL)** | `author_id` (SET NULL FK) | Wipe rows where `source_pattern_id.owner_id = auth.uid()`. Suggestions the user OPENED against another user's pattern: `author_id` is `ON DELETE SET NULL`, so anonymization happens naturally when we wipe at the row-source level. Explicit RPC behaviour: delete suggestions where the user owns source AND/OR target; leave third-party suggestions, only `author_id` clears. |
| `public.suggestion_comments` (was `pull_request_comments`) | **Wipe** | `author_id` (SET NULL FK) | Comments authored by the user on Suggestion threads. Same pattern as `comments`. |
| `public.subscriptions` | **Preserve** | `user_id` | User paid for Pro. Wipe is about CONTENT data; subscription state is transactional. Treating wipe as cancellation would surprise users (they didn't ask to cancel) and would force a paywall surface post-wipe. Pro entitlement persists. |
| `public.device_tokens` | **Wipe** | `user_id` | Push notification routing. Fresh-start semantic — push registration is regenerated on next launch via `PushTokenRegistrar` (ADR-017 §3.5, Phase 24.2e). Wipe + auto-re-register on next foreground = clean state without losing the future-push capability. |
| `public.feedback` | **Anonymize (FK SET NULL)** | `user_id` (SET NULL FK) | Phase F3 feedback log is append-only and operator-facing. `user_id` is `ON DELETE SET NULL` by design (migration 018, ADR-021 §D1 precedent). Wipe-don't-delete: operator history is preserved; user identity is severed. RPC does NOT issue DELETE on feedback; instead issues `UPDATE feedback SET user_id = NULL WHERE user_id = auth.uid()`. |
| `public.ugc_reports` | **Anonymize via FK** | `reporter_id` (CASCADE FK!) | Migration 031 wires `reporter_id` as `ON DELETE CASCADE` because the reporter table assumes account-deletion semantics. For wipe we want the operator's audit trail preserved with reporter anonymized — same rationale as feedback. RPC issues `UPDATE ugc_reports SET reporter_id = ...` would violate the NOT NULL constraint. **Decision**: alter `ugc_reports.reporter_id` to nullable + `ON DELETE SET NULL` in migration 033 (part of Phase 27.1) so wipe preserves the operator's investigation thread. The same migration carries the explicit UPDATE on the wipe-RPC path. |
| `public.user_blocks` | **Wipe (both legs)** | `blocker_id` AND `blocked_id` | Blocker side: user is purging their content + relationship state; their block list goes. Blocked side: if other users have blocked the wiping user, those rows are NOT removed (other users' choices stay intact). RPC issues `DELETE FROM user_blocks WHERE blocker_id = auth.uid()` only. |
| `public.symbol_packs` | **Preserve** | (no owner column) | Global catalog. Not user-owned. |
| `public.symbol_pack_locales` | **Preserve** | (no owner column) | Global catalog. Not user-owned. |
| `public.user_symbol_pack_state` | **Wipe** | `user_id` | Per-user "I have downloaded pack X version V" mirror. Fresh-start: user re-downloads packs on next paywall interaction. Wipes free Pro tester packs from local cache pointer, but the global `symbol_packs` rows themselves stay (preserved above). |
| `public.app_config` | **Preserve** | (no owner column) | Global maintenance-mode + feature-flag config. Not user-owned. |

Storage buckets:

| Bucket | Action | Rationale |
|---|---|---|
| `chart_images` (private) | **Best-effort wipe** | List under `{owner_id}/` prefix → batch delete. Failure of the storage call does NOT abort the RPC (orphaned files are unreachable without auth — same posture as account-deletion ADR-005 §Consequences). |
| `avatars` (public) | **Preserve** | Avatar is identity; identity is preserved. |
| `symbol-packs` (private) | **Preserve** | Global catalog payload. Not user-owned. |

**Audit `activities` row**: after every DELETE/UPDATE above completes, RPC INSERTs:

```sql
INSERT INTO public.activities (user_id, type, target_type, target_id, metadata)
VALUES (auth.uid(), 'data_wiped', 'project', auth.uid(), NULL);
```

(`target_type` = 'project' is a placeholder to satisfy the NOT NULL check on the existing column; `target_id = auth.uid()` is the self-reference convention. The new `data_wiped` enum value carries the semantic; downstream UI hides the target reference for this type. Alternative — relax `activities.target_type` constraint to allow NULL — was rejected because it would require migrating every downstream consumer.)

### (b) RPC shape

```sql
CREATE OR REPLACE FUNCTION public.wipe_own_data()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_uid UUID := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'wipe_own_data requires an authenticated session';
    END IF;

    -- Serialize concurrent invocations from the same user.
    PERFORM 1 FROM auth.users WHERE id = v_uid FOR UPDATE;

    -- Descendant-first DELETE order. Each statement is also CASCADE-safe;
    -- explicit DELETE is for audit clarity.
    DELETE FROM public.suggestion_comments WHERE author_id = v_uid;
    DELETE FROM public.suggestions WHERE source_pattern_id IN (
        SELECT id FROM public.patterns WHERE owner_id = v_uid
    ) OR target_pattern_id IN (
        SELECT id FROM public.patterns WHERE owner_id = v_uid
    );
    DELETE FROM public.comments WHERE author_id = v_uid;
    DELETE FROM public.shares WHERE from_user_id = v_uid OR to_user_id = v_uid;
    DELETE FROM public.project_segments WHERE owner_id = v_uid;
    DELETE FROM public.progress WHERE project_id IN (
        SELECT id FROM public.projects WHERE owner_id = v_uid
    );
    DELETE FROM public.projects WHERE owner_id = v_uid;
    DELETE FROM public.chart_versions WHERE owner_id = v_uid;
    DELETE FROM public.chart_variations WHERE owner_id = v_uid;
    DELETE FROM public.chart_documents WHERE owner_id = v_uid;
    DELETE FROM public.patterns WHERE owner_id = v_uid;
    DELETE FROM public.activities WHERE user_id = v_uid;
    DELETE FROM public.device_tokens WHERE user_id = v_uid;
    DELETE FROM public.user_symbol_pack_state WHERE user_id = v_uid;
    DELETE FROM public.user_blocks WHERE blocker_id = v_uid;

    -- Anonymize-but-keep rows.
    UPDATE public.feedback SET user_id = NULL WHERE user_id = v_uid;
    UPDATE public.ugc_reports SET reporter_id = NULL WHERE reporter_id = v_uid;

    -- Final audit row.
    INSERT INTO public.activities (user_id, type, target_type, target_id, metadata)
    VALUES (v_uid, 'data_wiped', 'project', v_uid, NULL);
END;
$$;

REVOKE ALL ON FUNCTION public.wipe_own_data() FROM public;
GRANT EXECUTE ON FUNCTION public.wipe_own_data() TO authenticated;
```

`SECURITY DEFINER` + `search_path = ''` matches the established convention from `delete_own_account` and `upsert_subscription_from_webhook`. `LANGUAGE plpgsql` (vs. `sql`) because we need the `BEGIN ... END` block, the `v_uid` variable, and the `PERFORM ... FOR UPDATE` row lock.

The full statement list runs in one implicit transaction (RPC body). If any statement fails, the entire body rolls back — partial wipe is structurally impossible.

### (c) UI gating

**Settings entry**: under the existing "Privacy" / 「プライバシー」 section. Row label: "Delete all my data" / 「すべてのデータを削除」. Sits adjacent to "Delete account" / 「アカウントを削除」, ABOVE it (less-destructive option listed first matches Material + HIG sectioning).

**Step 1 — preservation matrix modal**: Compose `AlertDialog` (full-screen on small phones via `Modifier.fillMaxWidth(0.95f)`) + SwiftUI `.sheet` parity. Two-column "Preserved | Deleted" enumeration in user-facing terms. Bottom CTAs: "Continue" (red destructive) + "Keep my data" (secondary).

**Step 2 — phrase typing**: dedicated screen pushed onto the nav stack. Single text field, helper text "Type `delete my data` to confirm", submit button disabled until trimmed lowercased input == fixed phrase. JA phrase: `データを削除`. Two-language matching: the active locale at modal-open time selects which phrase is required; mid-flow locale change is not supported (closed-beta scope).

**Submit handler**: ViewModel `wipeDataInternal` calls `WipeDataRepository.wipe()` → Supabase `postgrest.rpc("wipe_own_data")`. On success: emit `WipeDataNavEvent.WipeCompleted`. On error: emit `WipeDataNavEvent.WipeFailed(throwable.message)` and show a snack/alert.

**Post-wipe navigation**: nav controller pops back to root, deep-links to Pattern Library (root tab). Banner state in `PatternLibraryViewModel` flips to `wipeBannerVisible = true` for 8 s.

### (d) Pro entitlement edge case

`public.subscriptions` is in the Preserve column.

Counter-argument considered: wiping the subscription row alongside other content is "more thorough" and aligns with the user's "fresh start" mental model. Rejected because:

1. The user did not ask to cancel. Cancellation is its own destructive action with its own UX in RevenueCat-managed paywall paths.
2. Cancelling on wipe would force a paywall surface post-wipe, contradicting the "you're still you, just with no content" feedback we're trying to give.
3. RevenueCat continues to bill the user platform-side until they cancel through the App Store / Play subscription manager. Tearing down `subscriptions` row server-side without coordinated platform-side cancellation creates a "paying but no Pro" mismatch.

Pro features (Pro symbol packs, future Pro-only Discovery filters, etc.) keep working post-wipe.

**Decision**: `subscriptions` row is preserved. Documented in the "Preserved" column of the in-modal copy as "Pro membership (if applicable)".

### (e) Audit-log discipline — new `activities.type` enum value

The existing CHECK constraint on `public.activities.type` (migration 004):

```sql
CHECK (type IN ('shared', 'commented', 'forked', 'completed', 'started'))
```

Phase 27.1 migration adds `'data_wiped'`:

```sql
ALTER TABLE public.activities DROP CONSTRAINT activities_type_check;
ALTER TABLE public.activities ADD CONSTRAINT activities_type_check
    CHECK (type IN ('shared', 'commented', 'forked', 'completed', 'started', 'data_wiped'));
```

The audit row's `target_type = 'project'` and `target_id = auth.uid()` are sentinels to satisfy NOT NULL — UI consumers of `activities` (Phase 36.5 Activity Feed) ignore `target_*` columns when `type = 'data_wiped'` and render a self-contained "You deleted all your data on <timestamp>" entry.

Constraint widening migration is forward-compatible (existing rows still valid). Reverting would require both the constraint flip and a DELETE of any `data_wiped` rows — flagged in the migration's comment block.

## Sub-slice plan

Each sub-slice is independently shippable. The full wave gates alpha tester invites.

### Phase 27.1 — RPC + migration + commonTest

**Migration 033** (`033_wipe_own_data.sql`):

1. Widen `activities.type` CHECK to include `'data_wiped'`.
2. Alter `ugc_reports.reporter_id` to nullable + `ON DELETE SET NULL` (preservation-matrix decision; current constraint is NOT NULL + CASCADE).
3. Create `public.wipe_own_data()` SECURITY DEFINER function (body from decision (b)).
4. `REVOKE ALL ... FROM public; GRANT EXECUTE ... TO authenticated`.

**New `WipeDataRepository`** in `shared/src/commonMain/.../data/wipe/`:

- `interface WipeDataRepository { suspend fun wipe(): Result<Unit> }`
- `WipeDataRepositoryImpl(supabaseClient)` calls `postgrest.rpc("wipe_own_data")`, maps PostgrestException to `Result.failure`.

**commonTest (+15 cases)**:

- `WipeDataRepositoryImplTest` (9): offline → failure with `NetworkUnavailable`; unauthenticated → failure with `NotAuthenticated`; happy path → success; transient PostgrestException → failure with mapped message; cancellation propagates; idempotent double-call (two `wipe()` invocations both return success); concurrent-call test via two parallel coroutines; locale-independent (RPC body is locale-free).
- `WipeDataViewModelTest` (6): initial state, phrase-typing match enables submit, submit fires repository, success emits `WipeCompleted` nav event, failure emits `WipeFailed` with message, idempotency guard (double-tap on submit fires once).

No Deno tests (no Edge Function in 27.1).

### Phase 27.2 — Compose + SwiftUI + ViewModel + i18n

**`WipeDataViewModel`** (commonMain): state machine — `ModalVisible` → `PhraseEntryVisible` → `Submitting` → `Done` / `Error`. Lambda-seam DI for the `wipe` repository call (matches Phase 24.2c-1 / Phase 39.5 `BugReportPreviewViewModel` precedent).

**Compose**:

- `SettingsScreen.kt` adds the "Delete all my data" row in the Privacy section.
- `WipeDataExplanationDialog.kt` — preservation matrix in two-column layout, "Continue" + "Keep my data" CTAs.
- `WipeDataConfirmPhraseScreen.kt` — phrase-typing screen, submit button gated.

**SwiftUI** mirrors:

- `SettingsScreen.swift` row addition.
- `WipeDataExplanationSheet.swift` `.sheet` presentation.
- `WipeDataConfirmPhraseView.swift`.

Koin: `WipeDataRepository` in `RepositoryModule`; `WipeDataViewModel` in `ViewModelModule`.

**i18n (+13 keys, en + ja × CMP + iOS xcstrings)**:

| Key | EN | JA |
|---|---|---|
| `title_wipe_data_settings_row` | Delete all my data | すべてのデータを削除 |
| `body_wipe_data_settings_row` | Reset your patterns, projects, and comments. Keeps your account and Pro membership. | パターン・プロジェクト・コメントをリセットします。アカウントと Pro メンバーシップは保持されます。 |
| `title_wipe_data_explanation` | Delete all your data? | すべてのデータを削除しますか？ |
| `subtitle_wipe_data_explanation_preserved` | Preserved | 保持されるもの |
| `subtitle_wipe_data_explanation_deleted` | Deleted | 削除されるもの |
| `body_wipe_data_preserved_list` | Sign-in, display name, avatar, Pro membership, push notification preferences (re-registered automatically) | サインイン、表示名、アバター、Pro メンバーシップ、プッシュ通知設定（自動的に再登録されます） |
| `body_wipe_data_deleted_list` | Patterns, projects, charts, comments, suggestions, shared links, activity history, downloaded symbol packs | パターン、プロジェクト、編み図、コメント、提案、共有リンク、アクティビティ履歴、ダウンロード済みシンボルパック |
| `action_wipe_data_continue` | Continue | 続行 |
| `action_wipe_data_keep` | Keep my data | データを保持 |
| `title_wipe_data_confirm_phrase` | Confirm data deletion | データ削除を確認 |
| `body_wipe_data_confirm_phrase_helper` | Type `delete my data` to confirm. | 確認のため「データを削除」と入力してください。 |
| `action_wipe_data_submit` | Delete all my data | すべてのデータを削除 |
| `state_wipe_data_completed_banner` | Your data has been deleted. Your account is still active. | データを削除しました。アカウントは引き続きご利用いただけます。 |

JA phrasing prefers natural Japanese over literal translation (`保持されるもの` / `削除されるもの` over `保存される項目` / `削除される項目`; `データを削除` over the more literal `データ削除`).

### Phase 27.3 — Web fallback page + Edge Function

**Web page**: `docs/public/data-deletion/index.html` (EN) + `docs/public/ja/data-deletion/index.html` (JA mirror). Architectural mirror of `docs/public/account-deletion/`. Layout:

1. Header: "Delete all your Skeinly data" / 「Skeinly のデータをすべて削除」.
2. Distinction box: cross-link to `/skeinly/account-deletion/` with explicit "Want to delete your account entirely? Use this page instead."
3. Preservation matrix in plain language.
4. Sign-in form (Supabase Auth email + password; OAuth post-Phase-26).
5. Phrase-typing confirmation (matches in-app step 2).
6. Submit → POST to `wipe-my-data-web` Edge Function with `Authorization: Bearer <access_token>`.
7. Success message + cross-link to App Store / Play listing for reinstall.

Slug **`data-deletion`** chosen over `data-wipe` / `clear-data` / `delete-data` / `wipe-data` for:

- Plain-language naming — "deletion" is more recognizable to non-technical users than "wipe".
- Parallelism with `account-deletion` (same root verb, different qualifier).
- Search-engine discoverability — "delete my data" queries the user types on a desktop browser when they can't access the app.

**Edge Function** `supabase/functions/wipe-my-data-web/` (`verify_jwt = true`):

- `index.ts` — POST handler validates Bearer token, creates an authed Supabase client under the user's session, calls `wipe_own_data()` RPC, returns 200 / 401 / 500.
- Mirrors `delete-my-account-web` architecture exactly. No new third-party dependencies. ~80 LOC Deno + ~50 LOC tests.
- ~6 Deno tests: missing Bearer → 401; invalid Bearer → 401; happy path → 200; RPC error → 500 (envelope `{ ok: false, message }`); double-call idempotency → 200 / 200; non-POST → 405.

### Phase 27.4 — Privacy policy + smoke test

**Privacy policy `<h3>` subsection** in `docs/public/privacy-policy/index.html` (EN) + JA mirror. Placed adjacent to the existing "Account Deletion" subsection. Content:

- What "data deletion (account preserved)" means in this product.
- The preservation matrix in plain language (mirror of the in-app modal copy).
- Which storage media are affected (Supabase Postgres, Supabase Storage `chart_images` bucket — best-effort).
- Audit trail: a single `activities` row is retained for operator audit; this row is NOT visible to the user.
- Reversibility: zero. Data is gone after the RPC commits.
- How to invoke: in-app Settings → Privacy → "Delete all my data", or via the web page at `/skeinly/data-deletion/`.

**Smoke test** (closed-beta, against staging Supabase project):

1. Sign in as a test account with content (≥ 3 patterns + 2 projects + 5 comments + a Pro grant via `assign-customer-offering`).
2. Settings → Delete all my data → Continue → type phrase → submit.
3. Verify: Pattern Library empty; banner visible 8 s; Pro chip still visible in Settings; push notification arrives on next foreground (= `device_tokens` re-registered cleanly); display name unchanged in user menu.
4. Re-sign-in cycle: log out, log back in, verify state matches step 3.
5. Web fallback parity: same account, log in via `/skeinly/data-deletion/`, verify identical end-state.
6. Twin-tab idempotency: open Settings on two devices, submit on both — second submission returns success (idempotent), no error toast.

## Alternatives considered

### Soft-delete with restore window

Mark rows `deleted_at IS NOT NULL` instead of issuing DELETE; expose a 30-day restore button. Rejected:

- Massive RLS complication — every `WHERE deleted_at IS NULL` filter on every read policy across 18 tables, plus equivalent filter on every JOIN. Phase 25 friend-only adds visibility-aware WHERE clauses; combining the two yields hard-to-reason-about read paths.
- Storage cost grows with churn rather than steady-state; the Supabase free tier is the closed-beta target.
- User signal from "restore my wipe" is rare in product analytics from comparable apps. The cost-benefit doesn't justify the architecture change.
- Conflicts with the "fresh start" UX semantic the user is explicitly asking for.

### Opt-in per-category UI ("just delete my photos")

Granular category picker — checkboxes for patterns / projects / comments / etc., user submits a subset. Rejected:

- The all-or-nothing wipe is the answer to Data Safety Q "delete some OR all of their data" — once we have the bulk path, granular deletion is already available via per-item CRUD (long-press a pattern → delete). No middle tier is structurally required to flip the Data Safety answer to はい.
- UI complexity is substantial — checkboxes, dependency hints ("deleting patterns also deletes related projects"), partial-success states.
- Phase 27+ slice could reopen if user research surfaces a real need.

### Don't preserve Pro entitlement (treat wipe as cancellation)

Considered in decision (d); rejected.

### Hide the feature behind a `BuildFlags.isBeta` gate

Considered. The Data Safety form requires the feature to exist for the answer to flip; gating it Beta-only would not satisfy the disclosure. Phase 27 ships as a general-purpose Privacy feature from day 1.

## Privacy implications

- **Wipe is irrecoverable.** Once `wipe_own_data` commits, no cloud-side restore exists. Local SQLDelight cache survives until next sync, at which point it converges to the empty server state. Document this in the in-app modal copy + privacy policy.
- **Sentry event history (Phase 39.2 F1)** — Sentry retains crash events keyed by `user.id`. Phase 27 does NOT call Sentry's user-purge API. Sentry's retention policy (90 days for free tier) handles the eventual deletion. If a user wants Sentry purge, the existing account-deletion path is the right primitive; we don't proliferate purge surfaces.
- **PostHog event history (Phase 39.3 F2)** — same posture as Sentry. PostHog Person Profiles tied to `distinct_id` are NOT explicitly purged on wipe. Phase 27+ could call PostHog's `posthog.deletePersonProfile` if user research surfaces a need; not Phase 27 scope.
- **Storage `chart_images` bucket** — best-effort wipe (see preservation matrix). Failure-to-delete leaves orphaned files unreachable without auth (same posture as ADR-005). Acceptable for closed beta.

## Security implications

- **Authentication**: Phase 27 ships at parity with `delete_own_account` — authenticated session is the sole gate. Re-auth + MFA + biometric retrofit lands in Phase 26.
- **CSRF (web path)**: handled via Supabase Auth's SameSite cookies + `Authorization: Bearer` POST requirement at the Edge Function. No new surface.
- **Idempotency under retry**: row-level lock on `auth.users WHERE id = auth.uid()` serializes concurrent invocations. Safe under network retry, double-tap, twin-tab.
- **No user-controllable parameters**: RPC body uses only `auth.uid()`. No injection surface.
- **Operator-side**: a service-role admin issuing `wipe_own_data` on a user's behalf is structurally impossible — the RPC reads `auth.uid()` from the JWT, not from a parameter. The only impersonation path is the established Supabase Dashboard "Impersonate user" feature, which is auditable.

## i18n + commonTest summary

- **i18n**: +13 keys × 4 surfaces (en CMP `string_resources.xml`, ja CMP `string_resources.xml`, en iOS `Localizable.xcstrings`, ja iOS `Localizable.xcstrings`) = 52 new strings. `verifyI18nKeys` parity gate catches any miss.
- **commonTest**: +15 cases (9 repository + 6 ViewModel). No iOS XCUITest delta in Phase 27 (the new screens are exercised via commonTest + manual smoke; XCUITest can be added pre-Phase-40 GA if regression risk surfaces).
- **Deno tests**: +6 for `wipe-my-data-web`.

## Revision history

| Date | Change | Author |
|---|---|---|
| 2026-05-14 | Initial cut. Phase 27 promoted to alpha-launch HARD-GATE per Data Safety A0d-6 sub-question. | architect (agent team) |

## Cross-references

- ADR-005 — Account deletion via `delete_own_account` RPC (sibling primitive).
- ADR-016 — Pro subscription (`subscriptions` preservation rationale).
- ADR-017 — Push notifications (`device_tokens` wipe + re-register rationale).
- ADR-021 — UGC moderation (`ugc_reports`, `user_blocks` preservation-matrix entries).
- CLAUDE.md `### Planned — Phase 27 Data Wipe` — sub-slice index + HARD-GATE positioning.
- `docs/en/ops/data-export-sop.md` — operator export SOP (A20 Option A); Phase 27 is the user-facing companion privacy primitive on the deletion side.
