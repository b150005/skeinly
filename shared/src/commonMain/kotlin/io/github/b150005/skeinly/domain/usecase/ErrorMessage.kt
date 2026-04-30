package io.github.b150005.skeinly.domain.usecase

/**
 * Localizable representation of a [UseCaseError]. The UI layer resolves this
 * to a localized string via `errorMessageString` (Compose `localized()`) or
 * `localizedString` (Swift extension) so error feedback shows up in the
 * user's locale.
 *
 * Phase G.1 stage b/c migration: the transitional `Raw(text)` escape hatch and
 * the `UseCaseError.NotFound(message)` / `Validation(message)` legacy pair
 * have been removed. Every use case now emits a typed [UseCaseError] subtype
 * that maps 1:1 to a typed [ErrorMessage] subtype.
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

    /** User must be signed in to perform the action. */
    data object SignInRequired : ErrorMessage

    /** Action requires internet connectivity (offline-only mode). */
    data object RequiresConnectivity : ErrorMessage

    /** Generic load failure. */
    data object LoadFailed : ErrorMessage

    /** Resource (project, pattern, profile, ...) not found. */
    data object ResourceNotFound : ErrorMessage

    /** Required form field is blank or empty. */
    data object FieldRequired : ErrorMessage

    /** Form field exceeds the maximum character length. */
    data object FieldTooLong : ErrorMessage

    /** Password is shorter than the minimum required length. */
    data object PasswordTooShort : ErrorMessage

    /** Image data is empty or fails image-format validation. */
    data object ImageInvalid : ErrorMessage

    /** Image exceeds the maximum upload size. */
    data object ImageTooLarge : ErrorMessage

    /** Operation not allowed in the current entity state. */
    data object OperationNotAllowed : ErrorMessage

    /** Caller does not own the resource. */
    data object PermissionDenied : ErrorMessage

    /** ViewModel-level state-precondition failure (e.g. project not loaded yet). */
    data object NotReady : ErrorMessage
}

/**
 * Maps a [UseCaseError] to a localizable [ErrorMessage].
 *
 * Every variant of [UseCaseError] has a typed counterpart on [ErrorMessage].
 * `Authentication`/`Unknown` still pattern-match the known Supabase auth error
 * codes carried in their `cause.message` so server-side errors get specific
 * variants (e.g. `EmailInvalid`, `UserNotFound`) instead of the generic catch-all.
 */
fun UseCaseError.toErrorMessage(): ErrorMessage =
    when (this) {
        UseCaseError.ResourceNotFound -> ErrorMessage.ResourceNotFound
        UseCaseError.SignInRequired -> ErrorMessage.SignInRequired
        UseCaseError.RequiresConnectivity -> ErrorMessage.RequiresConnectivity
        UseCaseError.FieldRequired -> ErrorMessage.FieldRequired
        UseCaseError.FieldTooLong -> ErrorMessage.FieldTooLong
        UseCaseError.EmailInvalid -> ErrorMessage.EmailInvalid
        UseCaseError.PasswordTooShort -> ErrorMessage.PasswordTooShort
        UseCaseError.ImageInvalid -> ErrorMessage.ImageInvalid
        UseCaseError.ImageTooLarge -> ErrorMessage.ImageTooLarge
        UseCaseError.OperationNotAllowed -> ErrorMessage.OperationNotAllowed
        UseCaseError.PermissionDenied -> ErrorMessage.PermissionDenied
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
