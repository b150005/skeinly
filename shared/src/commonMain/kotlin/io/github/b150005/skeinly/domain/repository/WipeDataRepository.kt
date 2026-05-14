package io.github.b150005.skeinly.domain.repository

import io.github.b150005.skeinly.domain.usecase.UseCaseResult

/**
 * Phase 27.1 (ADR-023 §3.1) — write-only contract for the
 * `public.wipe_own_data()` SECURITY DEFINER RPC.
 *
 * Account-preserved bulk content deletion: wipes the caller's patterns,
 * projects, charts, comments, suggestions, shares, activities, device
 * tokens, symbol-pack state, and outbound blocks; anonymizes outbound
 * feedback + UGC reports; preserves auth.users, profiles (display name +
 * avatar + bio), and subscriptions (Pro entitlement). Inserts exactly
 * one `data_wiped` audit row at the end.
 *
 * **Sibling primitive to [AuthRepository.deleteAccount]** — both routes
 * are destructive, both call SECURITY DEFINER RPCs scoped to `auth.uid()`,
 * but only [AuthRepository.deleteAccount] removes the `auth.users` row.
 * The wipe-vs-delete distinction is the entire feature; see ADR-023 §UX
 * for the user-facing modal copy that enumerates the preservation matrix.
 *
 * **Idempotent under retry** — migration 033's `PERFORM ... FOR UPDATE`
 * on `auth.users` serializes concurrent invocations from the same user;
 * a network-retry double-call observes an already-empty state and emits
 * one fresh audit row.
 *
 * **Never throws** — surfaces failures via [UseCaseResult.Failure] so
 * callers can route a localized error toast (Phase 27.2 ViewModel) or
 * silently no-op without try/catch boilerplate.
 */
interface WipeDataRepository {
    /**
     * Invokes the `wipe_own_data()` RPC under the caller's authenticated
     * session. Returns:
     * - [UseCaseResult.Success] when the RPC commits.
     * - [UseCaseResult.Failure] with
     *   [io.github.b150005.skeinly.domain.usecase.UseCaseError.RequiresConnectivity]
     *   when Supabase is not configured (local-only build).
     * - [UseCaseResult.Failure] with
     *   [io.github.b150005.skeinly.domain.usecase.UseCaseError.SignInRequired]
     *   when no session is active (the RPC body's `auth.uid() IS NULL`
     *   guard would `RAISE EXCEPTION`, but we short-circuit before the
     *   network round-trip).
     * - [UseCaseResult.Failure] with
     *   [io.github.b150005.skeinly.domain.usecase.UseCaseError.Network]
     *   or [io.github.b150005.skeinly.domain.usecase.UseCaseError.Unknown]
     *   on transport / RPC / decode failures.
     *
     * No parameters — the RPC reads `auth.uid()` from the JWT exclusively
     * (no user-controllable injection surface).
     */
    suspend fun wipe(): UseCaseResult<Unit>
}
