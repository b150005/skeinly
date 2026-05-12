package io.github.b150005.skeinly.platform

/**
 * Pre-alpha A34 — opens the user's default mail composer with a
 * pre-filled mailto: URL targeting Skeinly support. The pre-fill
 * includes a structured Diagnostic Context block (app version, OS,
 * device model, locale) so the user can submit reports without
 * needing to copy-paste any technical details — and so the operator
 * has actionable triage info on first receipt.
 *
 * Pattern mirrors [StoreUrlLauncher] / [SubscriptionManagementLauncher]:
 * constructor-injected Context on Android, parameterless on iOS;
 * fire-and-forget contract; failure is swallowed.
 *
 * **NOT** a bug-report path — that's the in-app GitHub-Issue proxy
 * (Phase 39 W5, ADR-020). This is the simpler email fallback for
 * general questions, account issues, refund requests, DMCA notices,
 * etc. Both surfaces co-exist intentionally:
 *   - Bug Report (Settings → Beta → Send Feedback): structured, fast,
 *     anonymous-ish, goes to a public GitHub Issue.
 *   - Support Contact (Settings → Help & Support → Contact Support):
 *     unstructured, private, goes to the support inbox via mailto.
 */
expect class SupportContactLauncher {
    /**
     * Opens the default mail composer with a pre-filled mailto: URL.
     * [deviceContext] supplies the values for the Diagnostic Context
     * block in the body so the user doesn't have to look them up.
     */
    fun openSupportEmail(deviceContext: io.github.b150005.skeinly.platform.DeviceContextProvider)
}

/** Skeinly support inbox — single source of truth. Matches Privacy Policy + ToS contact lines. */
internal const val SKEINLY_SUPPORT_EMAIL = "skeinly.app@gmail.com"

/** Default subject (pre-fillable; user is free to overwrite in their mail composer). */
internal const val SKEINLY_SUPPORT_SUBJECT_DEFAULT = "Skeinly support"

/**
 * Builds a `mailto:` URL with the support inbox + pre-filled subject +
 * pre-filled body containing the Diagnostic Context block. Pure
 * function; tested in commonTest.
 *
 * URL-encoding: per RFC 6068 (the mailto URI scheme spec), the `subject`
 * and `body` query parameter values are percent-encoded per RFC 3986.
 * Spaces inside the parameter values become `%20` (NOT `+` — the `+`
 * substitution is `application/x-www-form-urlencoded` semantics, which
 * mailto does NOT use). Newlines in the body become `%0A`. Critical
 * characters (`?`, `&`, `=`, `#`) inside parameter values must also be
 * encoded; this implementation handles all of them via a single
 * [percentEncode] helper.
 */
internal fun composeSupportMailtoUrl(
    deviceContext: io.github.b150005.skeinly.platform.DeviceContextProvider,
    supportEmail: String = SKEINLY_SUPPORT_EMAIL,
    subject: String = SKEINLY_SUPPORT_SUBJECT_DEFAULT,
): String =
    composeSupportMailtoUrlFromFields(
        appVersion = deviceContext.appVersion,
        osVersion = deviceContext.osVersion,
        deviceModel = deviceContext.deviceModel,
        platformName = deviceContext.platformName,
        locale = deviceContext.locale,
        supportEmail = supportEmail,
        subject = subject,
    )

/**
 * Pure URL composer with field-list parameters. The `DeviceContextProvider`
 * overload above delegates here. Kept as a separate entry-point so
 * commonTest can substitute deterministic fields without needing an
 * actual `expect class` instance (Kotlin forbids test-side subclassing
 * of `expect class`, so the property-surface refactor falls through to
 * named parameters instead). Visible-for-tests.
 */
internal fun composeSupportMailtoUrlFromFields(
    appVersion: String,
    osVersion: String,
    deviceModel: String,
    platformName: String,
    locale: String,
    supportEmail: String = SKEINLY_SUPPORT_EMAIL,
    subject: String = SKEINLY_SUPPORT_SUBJECT_DEFAULT,
): String {
    val body =
        buildString {
            // Leave room above the diagnostic block so the user can
            // type their question/issue without scrolling past
            // boilerplate first. Three blank lines = enough vertical
            // room on iOS compose + Android compose without being
            // obtrusive.
            append("\n\n\n")
            append("---\n")
            append("Diagnostic Context (please do not edit):\n")
            append("App version: $appVersion\n")
            append("Platform: $platformName\n")
            append("OS: $osVersion\n")
            append("Device: $deviceModel\n")
            append("Locale: $locale\n")
        }
    return buildString {
        append("mailto:")
        append(supportEmail)
        append("?subject=")
        append(percentEncode(subject))
        append("&body=")
        append(percentEncode(body))
    }
}

/**
 * Percent-encodes a string per RFC 3986 unreserved characters. Anything
 * not in the unreserved set (`A-Z`, `a-z`, `0-9`, `-`, `.`, `_`, `~`)
 * is encoded as `%HH`. Encodes the input as UTF-8 bytes first so
 * non-ASCII characters (e.g. Japanese subject lines) survive correctly.
 *
 * Visible-for-tests (kept `internal`) so commonTest can pin the
 * encoding contract.
 */
internal fun percentEncode(input: String): String =
    buildString {
        for (byte in input.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            val isUnreserved =
                (c in 'A'..'Z') ||
                    (c in 'a'..'z') ||
                    (c in '0'..'9') ||
                    c == '-' ||
                    c == '.' ||
                    c == '_' ||
                    c == '~'
            if (isUnreserved) {
                append(c)
            } else {
                append('%')
                append(b.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
