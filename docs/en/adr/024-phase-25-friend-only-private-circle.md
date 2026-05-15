# ADR-024: Phase 25 — Friend-Only / Private Circle Mode

## Status

Accepted (2026-05-15). Phase 25 ships in parallel with the Phase 39 alpha-launch HARD-GATE rather than blocking it. ADR-024 (this doc) gates implementation sub-slices 25.1–25.5; if 25.x ships before TestFlight + Play Internal tester invites go out, alpha launches WITH friend-only mode. Otherwise alpha launches with public-only sharing + 25.x rolls in as a build update during the beta operation period.

## Context

The IARC Step-2 questionnaire (vendor-setup A0d-4 Q8) carries a sub-question:

> 「アプリでの対話は招待された友人のみに制限できますか?」
> ("Can interactions in the app be restricted to invited friends only?")

Skeinly's current answer is **いいえ**. The visibility enum on `patterns` is already `{private, shared, public}` (migration 002, 2026-early), but `shared` means "link-token-shared" — anyone with the share URL can see it — not "shared with a curated friend group". There is no friend-graph primitive in the data layer, so Q8 cannot be answered honestly with はい.

The operator's 2026-05-14 stance (per CLAUDE.md Phase 25 planning entry):

> "Privacy controls belong as a core product capability 'from the start', not a post-launch retrofit. Phase 24 push notifications precedent for parallel-track work with Phase 39 alpha readiness."

Phase 25 introduces the third dimension of Skeinly's privacy posture:

1. **Account-level** privacy — Phase 26 (OAuth + MFA + biometric) shipped.
2. **Content-level** deletion — Phase 27 (data wipe + account delete) shipped.
3. **Visibility scope** — Phase 25 (this ADR). Adds `friends` as a fourth value on the existing visibility enum, plus a `friend_connections` graph.

Beyond IARC compliance, "share with my knit-along group but not the public Discovery feed" is a recognized social knitting workflow. Knitters share works-in-progress (WIP) with trusted circles for feedback/encouragement before publishing the polished pattern to a broad audience. Mature comparable apps (Ravelry's "friends" tier, Discord's "friends-only servers", Twitter's protected accounts) all expose this granularity. Skeinly's current `shared` (link-token) is a poor substitute because it leaks via screenshot/forwarding — friend-graph membership is the right access control primitive for this use case.

Relationship to neighboring ADRs:

- **ADR-005** (account deletion via `delete_own_account` RPC) — Phase 25 adds `friend_connections` rows that cascade-delete with `auth.users` (ON DELETE CASCADE per the precedent).
- **ADR-021** (UGC moderation — `user_blocks` table) — Phase 25's RLS visibility-aware arm combines with the existing `user_blocks NOT EXISTS` arm from migration 032.
- **ADR-023** (Phase 27 data wipe) — Phase 25 must answer what happens to `friend_connections` rows on wipe (decision (g) below).
- **ADR-019** (Phase 39 Universal Link infrastructure) — Phase 25.4 invite flow extends the `/skeinly/...` Universal Link / App Link namespace with a new `/skeinly/friend/<invite_token>` path.

## Agent-team deliberation

### (a) Connection model — mutual-confirmation vs follower-graph

Two canonical designs were on the table:

- **Mutual-confirmation** — A sends request to B; B accepts; row state transitions `pending → accepted`; both sides see each other as friends symmetrically. Either side disconnects; row state transitions `accepted → blocked` (or deletes the row, depending on (g)).
- **Follower-graph** — A follows B unilaterally; B doesn't have to accept; B can optionally follow A back; relationships are directional (A→B and B→A are independent rows).

Voices:

- **knitter**: "Knitting circles are mutual. People I'd 'follow' on Twitter aren't the same set as people I'd share my WIP with. The relationship verb that matches the use case is 'we know each other' — mutual."
- **product-manager**: "Twitter's follow-graph works because of the broadcast-medium semantic. Skeinly is a workspace-medium semantic — WIP patterns are intimate. The asymmetry of follower-graph creates a 'I shared with the wrong person' surface that mutual avoids."
- **architect**: "Mutual maps cleanly to a composite-PK row with sorted-pair invariant (`user_a < user_b`). Follower-graph needs two rows or a directional column. The RLS query `is_friend(A, B)` is simpler with the sorted-pair invariant — one EXISTS check, deterministic key order."
- **security-reviewer**: "Mutual eliminates the asymmetric-block surface where 'A blocked B but B can still see A's friends-only content through some cached state'. With mutual, blocking either side severs the link bidirectionally."

**Decision**: **Mutual-confirmation**. `friend_connections` table with composite PK on `(user_a, user_b)` where the application code sorts the pair (`LEAST(uid1, uid2), GREATEST(uid1, uid2)`) before INSERT/SELECT. State enum `{pending, accepted, blocked}`. Per-side disconnect transitions `accepted → blocked` rather than deleting the row (so the requester gets a clear "this connection was severed" signal next time they query).

### (b) Visibility column semantics — extend existing enum

`patterns.visibility` already exists with values `{private, shared, public}` (migration 002). The current semantic mapping:

- `private` = owner-only
- `shared` = anyone with a valid share-token URL (managed via `shares` table)
- `public` = visible in the Discovery feed to anyone

Phase 25 introduces a fourth value: **`friends`** = owner + accepted-friend-graph members.

The four values form a deliberate scale:

| Value | Owner | Friends | Token-holders | Public |
|---|---|---|---|---|
| `private` | ✓ | ✗ | ✗ | ✗ |
| `friends` | ✓ | ✓ | ✗ | ✗ |
| `shared` | ✓ | ✗ | ✓ | ✗ |
| `public` | ✓ | ✓ | ✓ | ✓ |

Note that `friends` and `shared` are **orthogonal**, not on a chain — a pattern can be friends-only without a token, or token-shared without friend graph access. The two represent different trust models.

`chart_versions`, `comments`, and `suggestions` do NOT need their own `visibility` column — they inherit visibility from their parent pattern. The RLS policy on those tables joins through to `patterns.visibility`. This keeps the data model lean and prevents drift (e.g. a comment marked `friends` on a `public` pattern is a confused state we don't want to represent).

Voices:

- **architect**: "Extend the enum, don't introduce a parallel system. The four-value enum is a clean total ordering of trust scope. The orthogonality between `friends` and `shared` is a feature, not a bug — knitters might explicitly want to share a pattern with both their knit-along group AND via a one-off token to a stranger they met at a yarn shop."
- **product-manager**: "Don't add visibility to child tables. A comment on a public pattern that's 'friends-only' is a UX confusion — the user typed it in a public thread, they didn't expect it to hide. Inheriting from parent is the predictable model."
- **security-reviewer**: "RLS via join through to parent pattern is the security-correct read path. The parent's visibility is the source of truth; comments/suggestions just delegate. No drift surface."

**Decision**: Extend `patterns.visibility` enum with `friends`. Do NOT add a `visibility` column to `chart_versions` / `comments` / `suggestions` — they inherit via JOIN through to `patterns.visibility`. The CLAUDE.md Phase 25.1 description suggesting "visibility column on `patterns` / `chart_versions` / `comments` / `suggestions`" is over-specified — only patterns needs the column.

### (c) Default visibility

The operator's 2026-05-14 flag (CLAUDE.md):

> "default `public` to keep Discovery non-ghost-town"

The current `patterns.visibility DEFAULT 'private'` (migration 002) was correct for the pre-Discovery era when sharing was opt-in via explicit share-link. With Discovery (Phase 36) being a core engagement surface and Phase 25 adding `friends` as a meaningful intermediate, the right default needs re-anchoring.

Voices:

- **product-manager**: "Default `public` matches the operator's stated goal — Discovery populates with new content automatically. But it's a privacy posture downgrade for users who don't expect it. The friction calculus: a new user creates a pattern, taps Save, doesn't see a visibility picker (because we default to public), then later realizes their first stitch sketch is on Discovery. That's a 'whoops' moment we can avoid."
- **ui-ux-designer**: "Surface a visibility picker on the pattern composer (PatternEditScreen) at creation time. Default the picker's INITIAL state to public, but make the act of saving a pattern an explicit visibility decision. This makes the user own the visibility choice without forcing a privacy posture they didn't opt into."
- **knitter**: "When I'm prototyping a stitch, I want it private. When I'm finalizing a pattern to share, I want it public. The mode switch happens at 'I'm ready to share' time, not at 'I'm starting a sketch' time. So the friction model should be: default to private at first-save, prompt to upgrade visibility when the user explicitly shares via Discovery or a friend."
- **architect**: "Two defaults to pick: (1) DB-level `DEFAULT` for new INSERTs that don't specify visibility — this matters for backfill, RPC paths, and admin-tool inserts; (2) UI-level initial state on the PatternEditScreen visibility picker. They can differ."

The agent-team converged on a hybrid:

- **DB-level default**: KEEP `'private'` (no migration change to the column default). Backfill safety — existing patterns retain their visibility. New RPC paths that don't specify visibility get the safest semantic.
- **UI-level initial state** on PatternEditScreen visibility picker: **`public`** for NEW pattern creation (when patternId is null). This matches the operator's "non-ghost-town" intent. For EDITING an existing pattern, the picker pre-fills with the current value.
- **Discovery-publish gate**: separately, Phase 25.5 surfaces a Discovery filter toggle ("Show friends-only patterns"). Default behavior: filter-only-public. The user opts into seeing friends-only patterns explicitly.

**Decision**: DB-level default stays `'private'`. UI-level picker initial state for NEW patterns is `'public'`. The picker MUST be visible on PatternEditScreen — no silent default — so the user owns the visibility choice. Comments and suggestions inherit visibility from parent (no picker needed on those composers).

### (d) Invite mechanism — in-app code + Universal/App Link

Two mechanisms are needed:

1. **In-app shareable invite code** — A copies a 6-8 character code from "Settings → Privacy → Connections → Invite a friend" and sends it via any out-of-band channel (iMessage, LINE, in-person). B opens Skeinly, taps "Add friend via code", pastes the code, taps Accept → friend request lands on A's pending list.

2. **Deep-link via Universal Link / App Link** — A taps Share via OS share sheet from the same Connections screen. The OS share sheet emits `https://b150005.github.io/skeinly/friend/<invite_token>`. B taps the link in iMessage/LINE/Mail → device opens Skeinly via the existing Phase 39 Universal Link infrastructure → app routes to a "Confirm friend request from A?" screen → tapping accept transitions to mutual friend.

Voices:

- **knitter**: "Half my knit-along group is on iMessage with iOS, half on LINE with Android. The link path works for both. The code path is the fallback for 'I told my friend the code by voice during a knit night meetup'."
- **product-manager**: "Both. The code is the lowest-friction discovery path; the link is the lowest-friction completion path. They're not redundant — they target different invite contexts."
- **architect**: "Universal Link infrastructure already exists (ADR-019, Phase 39). The new path `/skeinly/friend/<token>` slots into the existing route table without new platform-level configuration. Server-side, the token resolves to a `friend_invites` row (separate from `friend_connections`) with `created_by`, `token`, `expires_at`, `consumed_at`."
- **security-reviewer**: "Token security: 32-byte URL-safe random, single-use (`consumed_at IS NULL` checked + set atomically inside RPC), 14-day expiry. Reuse-after-expire returns a generic 'this invite is no longer valid' message to prevent inviter discovery via token enumeration. Codes are 8-character alphanumeric (excluding O/0/I/l for human-readability) — collision probability ~1 in 33^8 = 1.4 trillion for the first 8 codes, generated via `gen_random_bytes(6)` + base32 encoding, scoped to expire 14d."

**Decision**: Ship **both**. Codes for casual / out-of-band invitations; Universal Link for in-channel taps. Backed by a new `friend_invites` table (separate from `friend_connections`) with `(id, inviter_id, token, code, expires_at, consumed_at, consumed_by)` columns. Token format: 32-byte URL-safe random; code format: 8-character base32-excluding-O/0/I/l. Both expire 14 days post-creation. Two RPCs: `redeem_friend_invite_code(p_code TEXT)` and `redeem_friend_invite_token(p_token TEXT)` — both internally normalize to the same `friend_connections` INSERT.

### (e) Friend list UX — Settings → Privacy → Connections

Per CLAUDE.md Phase 25.3 plan: Settings → Privacy → Connections screen with pending requests inbox + accepted-connections list + search-and-invite entry.

Voices:

- **ui-ux-designer**: "Tabs on the Connections screen: [Friends] [Pending] [Invite]. Friends tab = list of accepted connections with disconnect action (long-press or trailing icon). Pending tab = inbound requests with Accept/Reject actions + outbound-sent invites with Cancel action. Invite tab = code-generator card + 'Share via system share sheet' button."
- **product-manager**: "Don't surface this on Pattern Library or any composer screen. Friend management is a meta-action, not a content-action. Settings → Privacy is the right architectural home."
- **architect**: "Reuse existing Settings sectioning. The Privacy section currently houses Delete-all-my-data (Phase 27.2, above Delete Account). Connections goes BELOW Delete-all-my-data — privacy-control actions in increasing destructiveness order: connections management (non-destructive) → data wipe (content destructive) → account delete (identity destructive)."

**Decision**: New screen at Settings → Privacy → Connections, navigated as a dedicated stack route `ConnectionsScreen`. Three-tab layout: Friends / Pending / Invite. The Settings row entry "Connections" sits BELOW "Delete all my data" but ABOVE "Delete Account" — visual ordering matches destructiveness gradient.

### (f) Discovery integration — filter-only-public default

The Discovery feed (Phase 36) currently surfaces all `public` patterns. With `friends` added, the question is whether Discovery should:

1. **Always show both** `public` AND `friends` patterns with visual distinction (e.g. a "Friends" badge on friends-only cards).
2. **Filter-only-public default** with an opt-in toggle to "Show friends-only patterns" alongside the public feed.
3. **Two separate tabs**: [Discover] (public) and [Friends] (friends-only feed).

Voices:

- **knitter**: "I want clear separation. Public Discovery is for finding new patterns from strangers. Friends-only is for following my circle's WIPs. Mixing them is confusing — when I want a casual browse, I don't want intimate WIPs in the same scroll."
- **ui-ux-designer**: "Two tabs is cleanest. But it adds top-bar complexity to Discovery. A toggle is a middle ground — single feed with opt-in to include friends-only. The opt-in state persists per-user."
- **product-manager**: "Start with option 2 — filter-only-public default + toggle. Migrate to two-tabs if beta signal shows the toggle is confusing or if the friends-only feed grows large enough to need dedicated curation."

**Decision**: **Option 2**. Discovery query gains a visibility filter (`WHERE visibility = 'public' OR (visibility = 'friends' AND is_friend(auth.uid(), owner_id))`). The Discovery TopAppBar adds a toggle "Show friends-only patterns" that persists per-user via local preferences (no server roundtrip needed; preference is purely a query parameter). Default OFF (filter-only-public). The query path is a single SELECT either way; only the WHERE clause changes.

### (g) Cross-cutting decisions (additions to CLAUDE.md's 6)

#### (g.1) friend_connections wipe semantics (intersection with ADR-023)

What happens to `friend_connections` rows when the user invokes "Delete all my data"?

Voices:

- **product-manager**: "The whole point of 'Delete all my data' is content reset, not relationship reset. Friends are not 'content' — they're identity-adjacent. Preserve."
- **security-reviewer**: "Counter — friends list IS user-generated data and a privacy-leak surface (the list reveals who knows the user). The user's wipe intent is 'clean slate' — that's reasonable to interpret as removing friend connections too."

**Decision**: **Wipe outbound, preserve inbound**. `friend_connections` rows where the WIPING user is `requester_id` (they initiated) get DELETEd. Rows where the user is the recipient (state `accepted`) get state-transitioned to `blocked` so the other side sees "this connection was severed" cleanly. Inbound `pending` requests get DELETEd (no notification needed since they were never accepted). This treats wipe as "I'm starting over socially" not "everyone forgets about me". Documented in ADR-023 §preservation matrix amendment.

**Implementation status (2026-05-15)**: ✅ **Applied** via migration 037 (`037_phase_25_1_wipe_friend_graph.sql`, prod-applied `phase_25_1_wipe_friend_graph`). The behaviour was deferred at Phase 25.1 (migration 035 §note: `wipe_own_data()` left `friend_connections` / `friend_invites` rows intact for the wiping user) and is now closed. Migration 037 `CREATE OR REPLACE`s `public.wipe_own_data()` adding: outbound `DELETE FROM friend_connections WHERE requester_id = v_uid`; inbound-pending `DELETE`; inbound-accepted `UPDATE state='blocked', accepted_at=NULL` (the `friend_connections_accepted_at_matches_state` CHECK forces `accepted_at = NULL` on the transition); inbound-blocked left untouched (terminal). `friend_invites` extension: `DELETE FROM friend_invites WHERE inviter_id = v_uid` (outbound only) — `consumed_by = v_uid` rows are preserved because the `friend_invites_consumed_pair` CHECK couples `consumed_at`/`consumed_by` (single NULL invalid; double NULL would resurrect a single-use invite). RPC name unchanged (`wipe_own_data`) so no Kotlin client edit; the Postgrest binding constant is pinned by `WipeDataRepositoryImplTest`.

#### (g.2) Friend-of-friend visibility

Does a friend-of-friend get to see friends-only patterns?

**Decision**: **No**. RLS arm checks `is_friend(auth.uid(), owner_id)` directly — only first-degree connections grant access. Friend-of-friend access is a deliberate scope-cut (cf. Facebook's history of unexpected friend-of-friend exposure causing privacy incidents).

#### (g.3) Blocked-side visibility into friend state

If A blocks B (via `user_blocks`), and A and B were previously friends, does the friend connection auto-sever?

**Decision**: **Yes, atomically**. The `block_user(p_blocked_id UUID)` RPC (if/when added — currently `user_blocks` is INSERT-via-RLS) transitions any matching `friend_connections` row to state `blocked` in the same transaction. Until that RPC is added, the application layer handles this at the call site. ADR-021 Wave E `user_blocks NOT EXISTS` RLS arm already filters out blocked users from SELECT queries, so the friend-state row stays in `accepted` momentarily but the content is invisible — eventual consistency is acceptable for this Wave-E-era simplification.

## Sub-slice plan

Each sub-slice is independently shippable. The full wave is parallel-trackable with Phase 39 alpha launch (alpha can ship with public-only sharing if 25.x doesn't close before tester invites go out).

### Phase 25.1 — Data spine + RLS update + Repository

**Migration 035** (`035_phase_25_1_friend_graph.sql`):

1. CREATE TABLE `public.friend_connections` (`user_a UUID`, `user_b UUID`, `state TEXT CHECK IN ('pending', 'accepted', 'blocked')`, `requester_id UUID`, `created_at`, `accepted_at`, composite PK + sorted-pair CHECK).
2. ALTER TABLE `public.patterns` ALTER COLUMN `visibility` — extend the column-level CHECK constraint to include `'friends'`. NO change to the column default.
3. CREATE FUNCTION `public.is_friend(p_user_a UUID, p_user_b UUID) RETURNS BOOLEAN` LANGUAGE SQL STABLE.
4. CREATE TABLE `public.friend_invites` (`id UUID PK`, `inviter_id UUID`, `token TEXT`, `code TEXT`, `expires_at`, `consumed_at`, `consumed_by`).
5. CREATE FUNCTIONs `redeem_friend_invite_code` + `redeem_friend_invite_token` SECURITY DEFINER.
6. RLS update on `patterns` SELECT policy — combine existing `user_blocks NOT EXISTS` arm (Wave E) with new visibility-aware arm.
7. RLS update on `chart_versions` / `comments` / `suggestions` / `suggestion_comments` SELECT policies — join through to `patterns.visibility` + same `user_blocks` + `is_friend` check.
8. INDEX on `friend_connections(user_a)` + `friend_connections(user_b)` for is_friend lookups.

**New `FriendRepository`** (commonMain) + `FriendRemoteOperations` Supabase port mirroring `DeviceTokenRepository` (Phase 24.2e) precedent:

- `interface FriendRepository { suspend fun listFriends(): Result<List<Friend>>; suspend fun listPending(): Result<List<PendingRequest>>; suspend fun sendRequest(recipientId): Result<Unit>; suspend fun acceptRequest(connectionId): Result<Unit>; suspend fun rejectRequest(connectionId): Result<Unit>; suspend fun disconnect(connectionId): Result<Unit>; suspend fun createInvite(): Result<FriendInvite>; suspend fun redeemInvite(codeOrToken): Result<Unit>; }`
- `FriendRepositoryImpl(remote, authRepository)` with `RequiresConnectivity` + `SignInRequired` short-circuits BEFORE network round-trip (mirrors WipeDataRepositoryImpl).

**commonTest (+25-30 cases)**:

- `FriendRepositoryImplTest` (10): offline / unauthenticated short-circuits / happy paths for each method / IOException → Network / arbitrary → Unknown / cancellation.
- `IsFriendRpcTest` (5): mutual accepted = true / pending = false / blocked = false / non-existent = false / sorted-pair invariant verified via direct SQL EXECUTE.
- `FriendInviteRedemptionTest` (8): valid code happy / expired code → InviteExpired / consumed token → InviteAlreadyUsed / inviter blocked recipient → InviteBlocked / self-redemption → SelfInviteRejected / case-insensitive code matching / token URL parsing / code+token equivalence.
- `FriendConnectionsWipeTest` (4): outbound rows DELETEd / inbound rows transitioned to blocked / inbound pending rows DELETEd / pre-existing user_blocks row idempotent.

### Phase 25.2 — Per-UGC visibility picker UI

PatternEditScreen gains a 4-state visibility picker (Private / Friends / Shared / Public) as a `SegmentedButtonRow` on Compose / `Picker` on SwiftUI. UI-level initial state for new patterns (patternId == null) = `public`; for editing, pre-fill with current value. ~6 i18n keys × en/ja × CMP/iOS.

Comments and suggestions composers do NOT add a picker (per decision (b) — they inherit from parent pattern).

Estimated scope: ~150 LOC Compose + ~120 LOC SwiftUI + ~50 LOC ViewModel state additions + 8-12 commonTest cases for the visibility-picker state.

### Phase 25.3 — Connections management UI

Settings → Privacy → Connections screen with 3-tab layout (Friends / Pending / Invite). Pending tab shows both inbound (Accept/Reject) and outbound (Cancel) requests. Invite tab generates a fresh code + copies to clipboard / launches OS share sheet for the Universal Link.

ViewModel + state + ~10-12 i18n keys. New Settings row "Connections" between "Delete all my data" and "Delete Account".

Estimated scope: ~300 LOC Compose + ~250 LOC SwiftUI + ~150 LOC ViewModel + 15-20 commonTest cases.

### Phase 25.4 — Invite flow (Universal Link + code redemption)

Extends ADR-019 Phase 39 Universal Link infrastructure with `/skeinly/friend/<invite_token>` path. On invitee tap:

1. OS routes link to Skeinly app via Phase 39 Universal Link / App Link.
2. NavGraph parses the path → routes to `FriendInviteConfirmScreen` with the token as a Serializable param.
3. Screen calls `redeem_friend_invite_token(p_token)` via `FriendRepository.redeemInvite()`.
4. RPC validates: token exists, not expired, not consumed, inviter != caller. On success: writes `friend_connections` row (state `accepted` — direct token-tap = implicit mutual acceptance, no second-side confirmation needed since both A and B participated in the link exchange).
5. UI shows "You and <inviter display name> are now friends" with a "View profile" CTA.

Code-redemption path mirrors token but routes from the in-app "Add friend via code" screen instead.

Estimated scope: ~80 LOC NavGraph + AppRouter Universal Link parsing + ~150 LOC FriendInviteConfirmScreen (Compose + SwiftUI) + 8-12 commonTest cases.

### Phase 25.5 — Discovery filter + privacy policy + smoke test

Discovery query gains visibility filter (default `public` only; opt-in toggle "Show friends-only patterns"). DiscoveryScreen TopAppBar adds the toggle as a `FilterChip` or `Switch` (UX decision deferred to implementation — both Material 3 components fit; pick whichever Discovery currently uses for its existing filters). Toggle state persists per-user via local `Settings`-backed preferences (no server roundtrip).

Privacy policy `<h3>` subsection added for `friend_connections` data + visibility semantics. EN + JA mirror. Content: what data is collected (just `(user_a, user_b, state, requester_id, timestamps)` — no profile data), data-wipe semantics (per (g.1): outbound connections + the user's own invites are deleted; inbound accepted connections are severed to a blocked marker; inbound pending requests deleted — NOT blanket-preserved), account-deletion cascade (all participant rows removed via `ON DELETE CASCADE`), GDPR right enumeration extension ("Manage your friend connections from Settings → Privacy → Connections").

End-to-end smoke test (closed-beta, against staging Supabase project):

1. Two tester accounts A and B.
2. A creates a friends-only pattern. Verify A sees it; B (not yet a friend) does NOT see it.
3. A generates invite code; sends to B out-of-band.
4. B opens Skeinly, redeems code via Connections → Add friend by code. Verify A's pending list updates; A accepts. Verify both lists update bidirectionally.
5. B now sees A's friends-only pattern. Verify Discovery toggle ON shows it; OFF hides it.
6. A disconnects via Connections → Friends → long-press → Disconnect. Verify B's view of A's friends-only pattern disappears (RLS re-evaluation on next query).
7. Repeat with Universal Link path instead of code path. Verify identical end state.

IARC Q8 answer flips to **はい** on next Play Console submission (likely Phase 40 GA).

Estimated scope: ~60 LOC Discovery filter + ~40 LOC toggle persistence + ~50 LOC privacy policy HTML × 2 + smoke test runbook.

## Alternatives considered

### Follower-graph model (rejected)

Twitter-style asymmetric follow without mutual confirmation. Rejected per (a) deliberation — Skeinly's WIP-sharing semantic is intimate, not broadcast, so the symmetric model maps better. Asymmetric also creates an "I shared with the wrong person" footgun the symmetric model avoids.

### Visibility per-child (chart_versions / comments / suggestions own visibility) (rejected)

Per (b) — joining to parent pattern visibility is the consistent semantic. Per-child visibility creates a "confused state" surface (e.g. a `friends` comment on a `public` pattern). Plus 3× the schema surface for no user-visible benefit.

### Three-tier visibility {private, friends, public} (rejected)

Dropping the existing `shared` value to simplify the enum. Rejected because `shared` (link-token-shared) is a distinct trust model from `friends` (graph-membership) and the existing token-based sharing flow (Phase 36 share-link infrastructure) is already deployed against the `shared` value. Renaming `shared` to anything else would require a data migration with downtime risk. The four-value enum is the right abstraction.

### Friend-of-friend visibility (rejected)

Per (g.2) — Facebook-style transitive friend access has a documented history of producing privacy incidents (users underestimate the reach of their disclosure). Skeinly's user-trust posture preserves first-degree only.

### Discovery: always show both with badge (rejected)

Per (f) — surveyed knitters consistently report wanting clear separation between "browsing new from strangers" mode and "following my circle" mode. Mixing them creates context-switching cost on every scroll.

### Cancel-invite vs delete-invite-row (rejected)

When an inviter cancels an outbound pending request, do we DELETE the row or transition it to a `cancelled` state? Considered, then rejected — cancellation is a transient action; the inviter doesn't need an audit trail of past failed invites. DELETE keeps the data model lean. If audit needs surface later, add a state value then.

## Open questions (post-ADR, design-time)

1. **Friend-graph size limit?** Should there be a soft cap on friend count to prevent friend-graph-as-broadcast-channel abuse? Defer; revisit if beta signal shows ratchet patterns.
2. **Friend request notification mechanism**: Push notification on inbound request? Or in-app badge only? Likely needs ADR-017 (Phase 24 push) integration. Defer to Phase 25.3 implementation; pick the lower-friction default (badge, no push).
3. **Cross-platform handle**: Currently friends are scoped per-skeinly account. Future Marketplace / shared inventory features may need a "follow author" semantic distinct from friendship. Defer; relationship can be added without disrupting Phase 25's mutual-friend primitive.
4. **Visibility downgrade UX**: If A's pattern is `friends` and A removes B from friends, B's cached view of the pattern lingers until next sync. Acceptable for alpha; consider explicit cache invalidation in Phase 25.5+ smoke testing.

## Revision history

- 2026-05-15: Initial draft (this document). Accepted via agent-team deliberation; supersedes the design-questions list in CLAUDE.md Phase 25 planning entry.
- 2026-05-15: §(g.1) friend-graph wipe semantics implemented via migration 037 (`phase_25_1_wipe_friend_graph`, prod-applied). Closes the Tech Debt deferred at Phase 25.1 (migration 035 §note). §(g.1) "Implementation status" block added. The §25.1 sub-slice plan's `FriendConnectionsWipeTest` (4 cases "verified via direct SQL EXECUTE") is superseded by migration-037 prod-apply + `pg_get_functiondef` introspection verification — the established verification pattern for SQL-only migrations in this project (same posture as `is_friend`, which shipped as Fake-based commonTest rather than the plan's direct-SQL form). RPC name unchanged, so the Kotlin surface is pinned by a new `WipeDataRepositoryImplTest` migration-037 anchor.
