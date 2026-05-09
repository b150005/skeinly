package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.remote.DeviceTokenRemoteOperations
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.DeviceTokenRepository
import io.github.b150005.skeinly.domain.repository.PushPlatform
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toUseCaseError
import kotlinx.coroutines.CancellationException

/**
 * Phase 24.2e (ADR-017 §3.5) — implementation of [DeviceTokenRepository].
 *
 * Local-only mode (Supabase not configured ⇒ [remote] = null)
 * short-circuits with [UseCaseError.RequiresConnectivity];
 * sign-in-required short-circuits with [UseCaseError.SignInRequired].
 * Both happen before the network round-trip so a CI build with empty
 * `SUPABASE_URL` / `SUPABASE_PUBLISHABLE_KEY` never accidentally hits
 * a 401.
 *
 * **No local mirror.** Unlike the project / pattern / chart repositories,
 * `device_tokens` has no SQLDelight schema or sync queue — the platform
 * actual re-upserts on every app foreground after a successful permission
 * grant, so a missed write under a flaky network is recovered on the next
 * foreground cycle. The Edge Function reads `device_tokens` at fan-out
 * time via service-role bypass; clients never read their own row back.
 */
class DeviceTokenRepositoryImpl(
    private val remote: DeviceTokenRemoteOperations?,
    private val authRepository: AuthRepository,
) : DeviceTokenRepository {
    override suspend fun upsertToken(
        token: String,
        platform: PushPlatform,
        locale: String,
    ): UseCaseResult<Unit> {
        val operations =
            remote
                ?: return UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
        val userId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(UseCaseError.SignInRequired)

        return try {
            operations.upsert(
                userId = userId,
                token = token,
                platform = platform.wireValue,
                locale = locale,
            )
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
