package com.knitnote.domain.usecase

import com.knitnote.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException

class SignOutUseCase(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(): UseCaseResult<Unit> =
        try {
            authRepository.signOut()
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(UseCaseError.Unknown(e))
        }
}
