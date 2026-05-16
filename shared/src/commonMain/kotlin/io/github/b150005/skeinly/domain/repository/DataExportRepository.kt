package io.github.b150005.skeinly.domain.repository

import io.github.b150005.skeinly.domain.model.DataExportBundle
import io.github.b150005.skeinly.domain.usecase.UseCaseResult

/**
 * Pre-Phase-40 A20 Option B (docs/en/ops/data-export-sop.md §Scope
 * deferrals) — read-only contract for the `export-my-data` Edge
 * Function.
 *
 * In-app GDPR Article 20 / CCPA "right to know" export: the user's
 * account metadata + every owned/child `public.*` row + avatar object
 * metadata + static "not-held-here" pointers, composed server-side and
 * returned for the OS share sheet. **Non-destructive** — unlike its
 * Danger-Zone siblings [WipeDataRepository.wipe] /
 * [AuthRepository.deleteAccount], this only reads. It therefore lives
 * in the Settings *Privacy* section, not the Danger Zone.
 *
 * **Never throws** — surfaces failures via [UseCaseResult.Failure] so
 * the Phase-A20 ViewModel can route a localized error without
 * try/catch boilerplate. No parameters — identity comes from the JWT
 * `sub` exclusively (no user-controllable injection surface, same
 * invariant as [WipeDataRepository.wipe]).
 */
interface DataExportRepository {
    /**
     * Invokes `export-my-data` under the caller's authenticated
     * session. Returns:
     * - [UseCaseResult.Success] with the composed [DataExportBundle].
     * - [UseCaseResult.Failure] with
     *   [io.github.b150005.skeinly.domain.usecase.UseCaseError.RequiresConnectivity]
     *   when Supabase is not configured (local-only build).
     * - [UseCaseResult.Failure] with
     *   [io.github.b150005.skeinly.domain.usecase.UseCaseError.SignInRequired]
     *   when no session is active (short-circuited before the network
     *   round-trip; also maps a server `UNAUTHORIZED` envelope).
     * - [UseCaseResult.Failure] with
     *   [io.github.b150005.skeinly.domain.usecase.UseCaseError.RateLimited]
     *   when the per-user export quota is exhausted.
     * - [UseCaseResult.Failure] with
     *   [io.github.b150005.skeinly.domain.usecase.UseCaseError.Network]
     *   or [io.github.b150005.skeinly.domain.usecase.UseCaseError.Unknown]
     *   on transport / composition / decode failures.
     */
    suspend fun export(): UseCaseResult<DataExportBundle>
}
