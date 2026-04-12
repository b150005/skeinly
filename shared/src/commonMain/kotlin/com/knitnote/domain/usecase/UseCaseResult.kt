package com.knitnote.domain.usecase

sealed interface UseCaseResult<out T> {
    data class Success<T>(
        val value: T,
    ) : UseCaseResult<T>

    data class Failure(
        val error: UseCaseError,
    ) : UseCaseResult<Nothing>
}

sealed interface UseCaseError {
    data class NotFound(
        val message: String,
    ) : UseCaseError

    data class Validation(
        val message: String,
    ) : UseCaseError

    data class Authentication(
        val cause: Throwable,
    ) : UseCaseError

    data class Network(
        val cause: Throwable,
    ) : UseCaseError

    data class Unknown(
        val cause: Throwable,
    ) : UseCaseError
}

fun UseCaseError.toMessage(): String =
    when (this) {
        is UseCaseError.NotFound -> message
        is UseCaseError.Validation -> message
        is UseCaseError.Authentication -> cause.message?.toUserFriendlyMessage() ?: "Authentication failed"
        is UseCaseError.Network -> "Network error. Please check your connection and try again."
        is UseCaseError.Unknown -> cause.message?.toUserFriendlyMessage() ?: "Unknown error"
    }

/**
 * Maps a caught [Exception] to the most specific [UseCaseError] subtype.
 *
 * Detection strategy (platform-agnostic for KMP commonMain):
 * - Auth errors: matched by known Supabase error message strings
 * - Network errors: matched by exception class name heuristics (IOException, Timeout, etc.)
 * - Everything else: [UseCaseError.Unknown]
 */
fun Exception.toUseCaseError(): UseCaseError {
    val msg = message.orEmpty()

    // Auth errors — matched by known Supabase error message strings
    if (knownAuthErrors.keys.any { key -> msg.contains(key) }) {
        return UseCaseError.Authentication(this)
    }

    // Network errors — matched by class name heuristics (IO, timeout, connectivity)
    val className = this::class.simpleName.orEmpty()
    if (networkExceptionPatterns.any { pattern -> className.contains(pattern, ignoreCase = true) }) {
        return UseCaseError.Network(this)
    }

    return UseCaseError.Unknown(this)
}

private fun String.toUserFriendlyMessage(): String {
    val description =
        lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return "Unknown error"

    return knownAuthErrors[description] ?: description
}

private val knownAuthErrors =
    mapOf(
        "email_address_invalid" to "Invalid email address",
        "user_already_exists" to "An account with this email already exists",
        "invalid_credentials" to "Invalid email or password",
        "over_email_send_rate_limit" to "Too many attempts. Please try again later",
        "user_not_found" to "No account found with this email",
        "email_not_confirmed" to "Please confirm your email address first",
    )

private val networkExceptionPatterns =
    listOf(
        "IOException",
        "Timeout",
        "ConnectException",
        "SocketException",
        "UnresolvedAddress",
        "NoRouteToHost",
        "NetworkError",
        "ConnectionRefused",
    )
