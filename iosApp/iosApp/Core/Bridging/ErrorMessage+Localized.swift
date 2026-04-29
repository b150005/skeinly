import Foundation
import Shared

/// Resolver mirroring the Compose `ErrorMessage.localized()` composable —
/// turns a Kotlin `ErrorMessage` sealed value into a localized Swift `String`
/// using `NSLocalizedString` over the iOS String Catalog.
///
/// For `ErrorMessage.Raw` (use-case-emitted text), passes through verbatim.
/// Phase G+ should migrate use cases to typed error keys.
extension ErrorMessage {
    var localizedString: String {
        if self is ErrorMessageNetworkUnavailable {
            return NSLocalizedString("error_network", comment: "")
        }
        if self is ErrorMessageAuthenticationFailed {
            return NSLocalizedString("error_authentication_failed", comment: "")
        }
        if self is ErrorMessageEmailInvalid {
            return NSLocalizedString("error_email_invalid", comment: "")
        }
        if self is ErrorMessageUserAlreadyExists {
            return NSLocalizedString("error_user_already_exists", comment: "")
        }
        if self is ErrorMessageInvalidCredentials {
            return NSLocalizedString("error_invalid_credentials", comment: "")
        }
        if self is ErrorMessageRateLimitExceeded {
            return NSLocalizedString("error_rate_limit_exceeded", comment: "")
        }
        if self is ErrorMessageUserNotFound {
            return NSLocalizedString("error_user_not_found", comment: "")
        }
        if self is ErrorMessageEmailNotConfirmed {
            return NSLocalizedString("error_email_not_confirmed", comment: "")
        }
        if self is ErrorMessageGeneric {
            return NSLocalizedString("error_generic", comment: "")
        }
        if self is ErrorMessageSignInRequired {
            return NSLocalizedString("error_sign_in_required", comment: "")
        }
        if self is ErrorMessageRequiresConnectivity {
            return NSLocalizedString("error_requires_connectivity", comment: "")
        }
        if self is ErrorMessageLoadFailed {
            return NSLocalizedString("error_load", comment: "")
        }
        if let raw = self as? ErrorMessageRaw {
            return raw.text
        }
        // IMPORTANT: when adding a new variant to ErrorMessage.kt, add a
        // matching `if self is ErrorMessageX` branch above this line.
        // Currently 12 data-object branches + 1 Raw cast = 13 total. The
        // Compose resolver (ErrorMessageRendering.kt) is exhaustive by type
        // system enforcement and will fail to compile on missing branches;
        // this Swift extension will silently fall through to "error_generic".
        return NSLocalizedString("error_generic", comment: "")
    }
}
