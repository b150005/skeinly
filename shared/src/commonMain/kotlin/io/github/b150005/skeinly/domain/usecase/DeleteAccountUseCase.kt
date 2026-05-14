package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException

/**
 * Phase 17 — irreversible account-deletion. The destructive-confirm
 * dialog at the call site is the first gate; Phase 26.6 (ADR-022 §6.5)
 * adds a second biometric gate, but that gate lives at the call site
 * (SettingsViewModel.performDeleteAccount) rather than inside this
 * use case. Pushing the gate down into the UseCase loses the
 * Cancelled-vs-Failed distinction the UI needs (Cancelled is user
 * intent → silent UI reset; Failed is a system condition → error
 * toast) — the lambda-seam approach surfaced as a less-than-clean
 * shape during the Phase 26.6 code review (mapped Cancelled to
 * `PermissionDenied` which rendered "Permission denied" toast on
 * user-cancel). Keeping this use case pure RPC + leaving the gate at
 * the VM layer mirrors the MFA-disable path.
 */
class DeleteAccountUseCase(
    private val authRepository: AuthRepository,
    private val closeRealtimeChannels: CloseRealtimeChannelsUseCase,
) {
    suspend operator fun invoke(): UseCaseResult<Unit> =
        try {
            closeRealtimeChannels()
            authRepository.deleteAccount()
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
