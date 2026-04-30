package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.LocalUser
import io.github.b150005.skeinly.domain.model.Pattern
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.PatternRepository
import kotlinx.coroutines.flow.Flow

class GetPatternsUseCase(
    private val repository: PatternRepository,
    private val authRepository: AuthRepository,
) {
    operator fun invoke(): Flow<List<Pattern>> {
        val ownerId = authRepository.getCurrentUserId() ?: LocalUser.ID
        return repository.observeByOwnerId(ownerId)
    }
}
