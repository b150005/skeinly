package io.github.b150005.skeinly.domain.usecase

sealed interface UseCaseResult<out T> {
    data class Success<T>(
        val value: T,
    ) : UseCaseResult<T>

    data class Failure(
        val error: UseCaseError,
    ) : UseCaseResult<Nothing>
}

/**
 * Typed error categories produced by use cases. Phase G.1 stage b/c migration
 * eliminated the legacy `NotFound(message: String)` / `Validation(message: String)`
 * pair in favor of semantic typed variants that map 1:1 to localized
 * [ErrorMessage] values via [toErrorMessage]. The hardcoded English strings
 * the legacy variants carried now surface localized to ja-JP testers.
 */
sealed interface UseCaseError {
    /** Resource (project, pattern, profile, share, branch, revision, ...) not found. */
    data object ResourceNotFound : UseCaseError

    /** Caller is not signed in. */
    data object SignInRequired : UseCaseError

    /** Action requires a connected Supabase / cloud service (offline-only mode). */
    data object RequiresConnectivity : UseCaseError

    /** Required form field is blank or empty. */
    data object FieldRequired : UseCaseError

    /** Form field exceeds the maximum character length. */
    data object FieldTooLong : UseCaseError

    /** Email address fails client-side validation. */
    data object EmailInvalid : UseCaseError

    /** Password is shorter than the minimum required length. */
    data object PasswordTooShort : UseCaseError

    /** Image data is empty or fails image-format validation. */
    data object ImageInvalid : UseCaseError

    /** Image exceeds the maximum upload size. */
    data object ImageTooLarge : UseCaseError

    /**
     * Operation is not allowed given the current entity state — e.g. closing a
     * non-open PR, accepting a non-pending share, forking a non-public pattern,
     * source tip drift during merge, duplicate branch creation.
     */
    data object OperationNotAllowed : UseCaseError

    /** Caller does not own / is not the recipient of the resource being mutated. */
    data object PermissionDenied : UseCaseError

    /** Authentication failed at the auth layer. Carries the underlying cause for known-error mapping. */
    data class Authentication(
        val cause: Throwable,
    ) : UseCaseError

    /** Network operation failed. */
    data class Network(
        val cause: Throwable,
    ) : UseCaseError

    /** Unclassified failure. Carries the underlying cause for known-error mapping. */
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
