package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.PatternRepository
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
