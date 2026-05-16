package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.remote.DataExportException
import io.github.b150005.skeinly.data.remote.DataExportRemoteOperations
import io.github.b150005.skeinly.data.remote.RemoteDataExportDataSource
import io.github.b150005.skeinly.domain.model.DataExportBundle
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.DataExportRepository
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toUseCaseError
import kotlinx.coroutines.CancellationException

/**
 * Pre-Phase-40 A20 Option B (docs/en/ops/data-export-sop.md §Scope
 * deferrals) — implementation of [DataExportRepository].
 *
 * Offline-first contract identical to [WipeDataRepositoryImpl] /
 * [UgcModerationRepositoryImpl]: local-only mode ([remote] = null)
 * short-circuits with [UseCaseError.RequiresConnectivity]; signed-out
 * short-circuits with [UseCaseError.SignInRequired] BEFORE the network
 * round-trip (a CI build with empty Supabase creds, or a stray tap on
 * the Settings entry from a signed-out screen, never reaches the
 * network). The `export-my-data` body's identity check would catch the
 * signed-out case too, but the pre-flight avoids a misleading
 * network-error toast and saves the round-trip.
 *
 * **No retry loop** — an export is idempotent server-side (pure
 * SELECT), but a client-side retry on transient failure would mask the
 * failure from the user; the A20 ViewModel surfaces an error and lets
 * the user re-tap deliberately.
 */
class DataExportRepositoryImpl(
    private val remote: DataExportRemoteOperations?,
    private val authRepository: AuthRepository,
) : DataExportRepository {
    override suspend fun export(): UseCaseResult<DataExportBundle> {
        val operations =
            remote
                ?: return UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
        authRepository.getCurrentUserId()
            ?: return UseCaseResult.Failure(UseCaseError.SignInRequired)

        return try {
            UseCaseResult.Success(operations.exportOwnData())
        } catch (e: CancellationException) {
            throw e
        } catch (e: DataExportException) {
            UseCaseResult.Failure(mapExportCode(e))
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }

    /**
     * Maps the Edge Function's closed `code` envelope to a typed
     * [UseCaseError] (mirrors `UgcModerationRepositoryImpl.mapSubmissionCode`):
     * - `RATE_LIMITED` → [UseCaseError.RateLimited] (5/hr per user)
     * - `UNAUTHORIZED` → [UseCaseError.SignInRequired] (token expired
     *   between the pre-flight check and the request; or a non-200 401)
     * - else (`CONFIG_MISSING` / `EXPORT_FAILED` / null platform
     *   failure) → [UseCaseError.Unknown] (operator-side / transient;
     *   the screen shows a generic retryable error)
     */
    private fun mapExportCode(e: DataExportException): UseCaseError =
        when (e.code) {
            RemoteDataExportDataSource.CODE_RATE_LIMITED -> UseCaseError.RateLimited
            RemoteDataExportDataSource.CODE_UNAUTHORIZED -> UseCaseError.SignInRequired
            else -> UseCaseError.Unknown(e)
        }
}
