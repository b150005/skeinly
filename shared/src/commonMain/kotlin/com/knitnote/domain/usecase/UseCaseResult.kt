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
    is UseCaseError.Unknown -> cause.message?.toUserFriendlyMessage() ?: "Unknown error"
}

private fun String.toUserFriendlyMessage(): String {
    val description = lines()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?: return "Unknown error"

    return knownAuthErrors[description] ?: description
}

private val knownAuthErrors = mapOf(
    "email_address_invalid" to "Invalid email address",
    "user_already_exists" to "An account with this email already exists",
    "invalid_credentials" to "Invalid email or password",
    "over_email_send_rate_limit" to "Too many attempts. Please try again later",
    "user_not_found" to "No account found with this email",
    "email_not_confirmed" to "Please confirm your email address first",
)
