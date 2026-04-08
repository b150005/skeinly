package com.knitnote.domain.usecase

import com.knitnote.domain.repository.AuthRepository

class SignOutUseCase(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(): UseCaseResult<Unit> =
        try {
            authRepository.signOut()
            UseCaseResult.Success(Unit)
        } catch (e: Exception) {
            UseCaseResult.Failure(UseCaseError.Unknown(e))
        }
}
