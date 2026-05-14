package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.remote.WipeDataRemoteOperations
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.WipeDataRepository
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toUseCaseError
import kotlinx.coroutines.CancellationException

/**
 * Phase 27.1 (ADR-023 §3.1) — implementation of [WipeDataRepository].
 *
 * Local-only mode (Supabase not configured ⇒ [remote] = null)
 * short-circuits with [UseCaseError.RequiresConnectivity]; signed-out
 * short-circuits with [UseCaseError.SignInRequired]. Both happen
 * BEFORE the network round-trip so a CI build with empty
 * `SUPABASE_URL` / `SUPABASE_PUBLISHABLE_KEY` never accidentally hits a
 * 401, and a stray click on the (currently unreachable) Settings entry
 * from a signed-out screen never reaches the network either.
 *
 * **No local mirror.** Wipe is server-authoritative; the local
 * SQLDelight cache stays populated until the next sync converges it to
 * the empty server state. Phase 27.2 may add a forced local-cache
 * eviction on success to match the user's expected "everything is gone"
 * mental model immediately; the repository contract here returns as
 * soon as the RPC commits.
 *
 * **No retry loop** — `wipe_own_data` is idempotent at the DB layer
 * (PERFORM ... FOR UPDATE on auth.users serializes concurrent
 * invocations), but a client-side retry on transient failure would mask
 * the failure from telemetry. The Phase 27.2 ViewModel surfaces an
 * error toast and lets the user re-tap deliberately.
 */
class WipeDataRepositoryImpl(
    private val remote: WipeDataRemoteOperations?,
    private val authRepository: AuthRepository,
) : WipeDataRepository {
    override suspend fun wipe(): UseCaseResult<Unit> {
        val operations =
            remote
                ?: return UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
        // Short-circuit before the network round-trip. The RPC body's
        // `IF v_uid IS NULL THEN RAISE EXCEPTION` would catch this too,
        // but doing the check here avoids a misleading network-error
        // toast on a signed-out client and saves the round-trip.
        authRepository.getCurrentUserId()
            ?: return UseCaseResult.Failure(UseCaseError.SignInRequired)

        return try {
            operations.wipeOwnData()
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
