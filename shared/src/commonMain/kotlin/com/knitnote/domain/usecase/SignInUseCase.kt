package com.knitnote.domain.usecase

import com.knitnote.domain.repository.AuthRepository
import kotlin.coroutines.cancellation.CancellationException

class SignInUseCase(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(
        email: String,
        password: String,
    ): UseCaseResult<Unit> =
        try {
            authRepository.signInWithEmail(email, password)
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
