package com.knitnote.domain.usecase

sealed interface UseCaseResult<out T> {
    data class Success<T>(val value: T) : UseCaseResult<T>
    data class Failure(val error: UseCaseError) : UseCaseResult<Nothing>
}

sealed interface UseCaseError {
    data class NotFound(val message: String) : UseCaseError
    data class Validation(val message: String) : UseCaseError
    data class Unknown(val cause: Throwable) : UseCaseError
}

fun UseCaseError.toMessage(): String = when (this) {
    is UseCaseError.NotFound -> message
    is UseCaseError.Validation -> message
    is UseCaseError.Unknown -> cause.message ?: "Unknown error"
}
