package io.github.b150005.skeinly.domain.repository

import io.github.b150005.skeinly.domain.model.BlockedUser
import io.github.b150005.skeinly.domain.model.UgcReportCategory
import io.github.b150005.skeinly.domain.model.UgcTargetType
import io.github.b150005.skeinly.domain.usecase.UseCaseResult

/**
 * Phase 39 (ADR-021 §D4) — UGC moderation contract: report content +
 * block / unblock a user + list the caller's blocks.
 *
 * All methods return [UseCaseResult.Failure] with
 * [io.github.b150005.skeinly.domain.usecase.UseCaseError.RequiresConnectivity]
 * when Supabase is not configured (local-only build), and
 * [io.github.b150005.skeinly.domain.usecase.UseCaseError.SignInRequired]
 * when no session is active. Both short-circuit BEFORE the network
 * round-trip — same offline-first contract as
 * [FriendRepository] (Phase 25.1) and [WipeDataRepository] (Phase 27.1).
 *
 * **Never throws** — failures route via [UseCaseResult.Failure].
 *
 * The server foundation (migrations 031 / 032 + Edge Function
 * `submit-ugc-report`) is already live; this contract is the pure
 * client surface promised at ADR-021 §D4 "Pre-Phase-40 GA full sweep".
 */
interface UgcModerationRepository {
    /**
     * Files a report against a UGC element via the `submit-ugc-report`
     * Edge Function (`verify_jwt = true` — supabase-kt's Functions
     * plugin auto-attaches the caller's session JWT). The Edge
     * Function INSERTs into `public.ugc_reports` (RLS-enforced
     * `reporter_id = auth.uid()`) and mirrors a triage GitHub Issue.
     *
     * [reason] is validated client-side (non-blank, ≤
     * [io.github.b150005.skeinly.domain.model.MAX_UGC_REASON_LENGTH])
     * BEFORE the round-trip — a blank reason returns
     * [io.github.b150005.skeinly.domain.usecase.UseCaseError.FieldRequired],
     * an over-long one
     * [io.github.b150005.skeinly.domain.usecase.UseCaseError.FieldTooLong].
     * The Edge Function's own validation is the server-side backstop.
     *
     * A per-user Edge Function rate limit (10/hr) surfaces as
     * [io.github.b150005.skeinly.domain.usecase.UseCaseError.RateLimited].
     */
    suspend fun submitReport(
        targetType: UgcTargetType,
        targetId: String,
        category: UgcReportCategory,
        reason: String,
    ): UseCaseResult<Unit>

    /**
     * Blocks [blockedUserId] for the caller. INSERTs a `user_blocks`
     * row (RLS-enforced `blocker_id = auth.uid()`). The migration-032
     * SELECT-policy amendments then filter all of the blocked user's
     * patterns / comments / suggestions / suggestion-comments out of
     * the caller's queries server-side.
     *
     * Self-block is rejected client-side with
     * [io.github.b150005.skeinly.domain.usecase.UseCaseError.OperationNotAllowed]
     * before the round-trip (the DB `CHECK (blocker_id != blocked_id)`
     * is the backstop). Re-blocking an already-blocked user is
     * idempotent (PK conflict is swallowed as success).
     */
    suspend fun blockUser(blockedUserId: String): UseCaseResult<Unit>

    /**
     * Removes the caller's block on [blockedUserId] (DELETE scoped by
     * the `user_blocks` DELETE RLS policy to `blocker_id = auth.uid()`).
     * Unblocking a user who was not blocked is idempotent (no row
     * deleted is still success).
     */
    suspend fun unblockUser(blockedUserId: String): UseCaseResult<Unit>

    /**
     * Lists the caller's blocked users with display names resolved
     * from `public.profiles`. Drives Settings → Privacy → Blocked
     * Users. Empty list is a success, not a failure.
     */
    suspend fun listBlockedUsers(): UseCaseResult<List<BlockedUser>>
}
