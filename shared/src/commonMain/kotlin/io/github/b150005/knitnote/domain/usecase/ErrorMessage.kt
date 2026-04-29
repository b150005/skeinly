package io.github.b150005.knitnote.domain.usecase

/**
 * Localizable representation of a [UseCaseError]. The UI layer resolves this
 * to a localized string via [errorMessageString] (Compose) or `localizedString`
 * (Swift extension) so error feedback shows up in the user's locale.
 *
 * Phase G scope (alpha1): covers the common error paths — Network, Authentication
 * (with known Supabase error code mapping), Unknown — with localized resources.
 *
 * `Raw(text)` exists as a transition path for use-case-emitted [UseCaseError.NotFound]
 * and [UseCaseError.Validation] errors whose `message` is composed at the
 * use-case layer (e.g. "Project not found", "Title cannot be blank"). These
 * surface as English to ja-JP users today; Phase G+ will migrate use cases
 * to typed error keys, at which point [Raw] should disappear.
 */
sealed interface ErrorMessage {
    data object NetworkUnavailable : ErrorMessage

    data object AuthenticationFailed : ErrorMessage

    data object EmailInvalid : ErrorMessage

    data object UserAlreadyExists : ErrorMessage

    data object InvalidCredentials : ErrorMessage

    data object RateLimitExceeded : ErrorMessage

    data object UserNotFound : ErrorMessage

    data object EmailNotConfirmed : ErrorMessage

    data object Generic : ErrorMessage

    /** User must be signed in to perform the action. Phase G.1 typed variant. */
    data object SignInRequired : ErrorMessage

    /** Action requires internet connectivity (offline-only mode). Phase G.1 typed variant. */
    data object RequiresConnectivity : ErrorMessage

    /** Generic load failure (replaces "Failed to load X" Raw strings). Phase G.1 typed variant. */
    data object LoadFailed : ErrorMessage

    data class Raw(
        val text: String,
    ) : ErrorMessage
}

/**
 * Maps a [UseCaseError] to a localizable [ErrorMessage].
 *
 * - `Network` always maps to [ErrorMessage.NetworkUnavailable]
 * - `Authentication` matches the known Supabase error code in `cause.message`;
 *   falls back to [ErrorMessage.AuthenticationFailed]
 * - `Unknown` also runs the known-error-code matcher (Supabase wraps some
 *   auth errors in non-Auth exceptions); falls back to [ErrorMessage.Generic]
 * - `NotFound` and `Validation` pass through their use-case-supplied `message`
 *   as [ErrorMessage.Raw] — these are use-case-specific strings that lose
 *   semantic precision if collapsed to a generic key.
 */
fun UseCaseError.toErrorMessage(): ErrorMessage =
    when (this) {
        is UseCaseError.NotFound -> ErrorMessage.Raw(message)
        is UseCaseError.Validation -> ErrorMessage.Raw(message)
        is UseCaseError.Authentication ->
            mapKnownAuthError(cause) ?: ErrorMessage.AuthenticationFailed
        is UseCaseError.Network -> ErrorMessage.NetworkUnavailable
        is UseCaseError.Unknown ->
            mapKnownAuthError(cause) ?: ErrorMessage.Generic
    }

private fun mapKnownAuthError(cause: Throwable): ErrorMessage? {
    val description =
        cause.message
            ?.lines()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return null

    return when {
        description.contains("email_address_invalid") -> ErrorMessage.EmailInvalid
        description.contains("user_already_exists") -> ErrorMessage.UserAlreadyExists
        description.contains("invalid_credentials") -> ErrorMessage.InvalidCredentials
        description.contains("over_email_send_rate_limit") -> ErrorMessage.RateLimitExceeded
        description.contains("user_not_found") -> ErrorMessage.UserNotFound
        description.contains("email_not_confirmed") -> ErrorMessage.EmailNotConfirmed
        else -> null
    }
}
