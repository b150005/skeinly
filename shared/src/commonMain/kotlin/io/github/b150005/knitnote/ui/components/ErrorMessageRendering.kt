package io.github.b150005.knitnote.ui.components

import androidx.compose.runtime.Composable
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.error_authentication_failed
import io.github.b150005.knitnote.generated.resources.error_email_invalid
import io.github.b150005.knitnote.generated.resources.error_email_not_confirmed
import io.github.b150005.knitnote.generated.resources.error_field_required
import io.github.b150005.knitnote.generated.resources.error_field_too_long
import io.github.b150005.knitnote.generated.resources.error_generic
import io.github.b150005.knitnote.generated.resources.error_image_invalid
import io.github.b150005.knitnote.generated.resources.error_image_too_large
import io.github.b150005.knitnote.generated.resources.error_invalid_credentials
import io.github.b150005.knitnote.generated.resources.error_load
import io.github.b150005.knitnote.generated.resources.error_network
import io.github.b150005.knitnote.generated.resources.error_not_ready
import io.github.b150005.knitnote.generated.resources.error_operation_not_allowed
import io.github.b150005.knitnote.generated.resources.error_password_too_short
import io.github.b150005.knitnote.generated.resources.error_permission_denied
import io.github.b150005.knitnote.generated.resources.error_rate_limit_exceeded
import io.github.b150005.knitnote.generated.resources.error_requires_connectivity
import io.github.b150005.knitnote.generated.resources.error_resource_not_found
import io.github.b150005.knitnote.generated.resources.error_sign_in_required
import io.github.b150005.knitnote.generated.resources.error_user_already_exists
import io.github.b150005.knitnote.generated.resources.error_user_not_found
import org.jetbrains.compose.resources.stringResource

/**
 * Compose-side resolver: turns an [ErrorMessage] into a localized [String]
 * for display in Snackbars, dialogs, or error banners.
 *
 * The `when` is exhaustive by sealed interface enforcement — adding a new
 * variant to [ErrorMessage] is a compile-time error here until handled.
 *
 * Mirror: iOS `ErrorMessage+Localized.swift` `localizedString` property.
 */
@Composable
fun ErrorMessage.localized(): String =
    when (this) {
        ErrorMessage.NetworkUnavailable -> stringResource(Res.string.error_network)
        ErrorMessage.AuthenticationFailed -> stringResource(Res.string.error_authentication_failed)
        ErrorMessage.EmailInvalid -> stringResource(Res.string.error_email_invalid)
        ErrorMessage.UserAlreadyExists -> stringResource(Res.string.error_user_already_exists)
        ErrorMessage.InvalidCredentials -> stringResource(Res.string.error_invalid_credentials)
        ErrorMessage.RateLimitExceeded -> stringResource(Res.string.error_rate_limit_exceeded)
        ErrorMessage.UserNotFound -> stringResource(Res.string.error_user_not_found)
        ErrorMessage.EmailNotConfirmed -> stringResource(Res.string.error_email_not_confirmed)
        ErrorMessage.Generic -> stringResource(Res.string.error_generic)
        ErrorMessage.SignInRequired -> stringResource(Res.string.error_sign_in_required)
        ErrorMessage.RequiresConnectivity -> stringResource(Res.string.error_requires_connectivity)
        ErrorMessage.LoadFailed -> stringResource(Res.string.error_load)
        ErrorMessage.ResourceNotFound -> stringResource(Res.string.error_resource_not_found)
        ErrorMessage.FieldRequired -> stringResource(Res.string.error_field_required)
        ErrorMessage.FieldTooLong -> stringResource(Res.string.error_field_too_long)
        ErrorMessage.PasswordTooShort -> stringResource(Res.string.error_password_too_short)
        ErrorMessage.ImageInvalid -> stringResource(Res.string.error_image_invalid)
        ErrorMessage.ImageTooLarge -> stringResource(Res.string.error_image_too_large)
        ErrorMessage.OperationNotAllowed -> stringResource(Res.string.error_operation_not_allowed)
        ErrorMessage.PermissionDenied -> stringResource(Res.string.error_permission_denied)
        ErrorMessage.NotReady -> stringResource(Res.string.error_not_ready)
    }
