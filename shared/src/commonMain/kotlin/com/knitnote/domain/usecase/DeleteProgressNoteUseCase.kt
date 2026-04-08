package com.knitnote.domain.usecase

import com.knitnote.domain.repository.ProgressRepository

class DeleteProgressNoteUseCase(private val repository: ProgressRepository) {

    suspend operator fun invoke(progressId: String) {
        repository.delete(progressId)
    }
}
