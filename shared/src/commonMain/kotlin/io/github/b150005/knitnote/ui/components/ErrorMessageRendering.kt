package io.github.b150005.knitnote.ui.components

import androidx.compose.runtime.Composable
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.error_authentication_failed
import io.github.b150005.knitnote.generated.resources.error_email_invalid
import io.github.b150005.knitnote.generated.resources.error_email_not_confirmed
import io.github.b150005.knitnote.generated.resources.error_generic
import io.github.b150005.knitnote.generated.resources.error_invalid_credentials
import io.github.b150005.knitnote.generated.resources.error_network
import io.github.b150005.knitnote.generated.resources.error_rate_limit_exceeded
import io.github.b150005.knitnote.generated.resources.error_user_already_exists
import io.github.b150005.knitnote.generated.resources.error_user_not_found
import io.github.b150005.knitnote.ui.components.localized
import org.jetbrains.compose.resources.stringResource

/**
 * Compose-side resolver: turns an [ErrorMessage] into a localized [String]
 * for display in Snackbars, dialogs, or error banners.
 *
 * For [ErrorMessage.Raw] (use-case-emitted text), passes through verbatim —
 * Phase G+ should migrate use cases to typed error keys.
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
        is ErrorMessage.Raw -> text
    }
