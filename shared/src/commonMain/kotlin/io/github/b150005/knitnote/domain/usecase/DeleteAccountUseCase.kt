package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException

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
