package io.github.b150005.knitnote.domain.usecase

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
    if (knownAuthErrors.any { key -> msg.contains(key) }) {
        return UseCaseError.Authentication(this)
    }

    // Network errors — matched by class name heuristics (IO, timeout, connectivity)
    val className = this::class.simpleName.orEmpty()
    if (networkExceptionPatterns.any { pattern -> className.contains(pattern, ignoreCase = true) }) {
        return UseCaseError.Network(this)
    }

    return UseCaseError.Unknown(this)
}

private val knownAuthErrors =
    setOf(
        "email_address_invalid",
        "user_already_exists",
        "invalid_credentials",
        "over_email_send_rate_limit",
        "user_not_found",
        "email_not_confirmed",
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
