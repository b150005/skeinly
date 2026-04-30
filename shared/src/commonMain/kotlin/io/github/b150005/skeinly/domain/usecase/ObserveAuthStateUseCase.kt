package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow

class ObserveAuthStateUseCase(
    private val authRepository: AuthRepository,
) {
    operator fun invoke(): Flow<AuthState> = authRepository.observeAuthState()
}
