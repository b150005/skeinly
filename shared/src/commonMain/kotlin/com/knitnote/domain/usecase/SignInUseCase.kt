package com.knitnote.domain.usecase

import com.knitnote.domain.repository.AuthRepository

class SignInUseCase(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(email: String, password: String): UseCaseResult<Unit> =
        try {
            authRepository.signInWithEmail(email, password)
            UseCaseResult.Success(Unit)
        } catch (e: Exception) {
            UseCaseResult.Failure(UseCaseError.Unknown(e))
        }
}
