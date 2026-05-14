package io.github.b150005.skeinly.biometric

/**
 * Phase 26.6 (ADR-022 §6.5) — platform abstraction for OS biometric
 * (fingerprint / Face ID) authentication.
 *
 * Each `actual` implementation owns:
 *  1. **Hardware + enrollment query** — Android
 *     `BiometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`;
 *     iOS `LAContext.canEvaluatePolicy(.deviceOwnerAuthentication)`.
 *  2. **Authentication prompt** — Android
 *     `BiometricPrompt.PromptInfo.Builder` against a `FragmentActivity`;
 *     iOS `LAContext.evaluatePolicy(.deviceOwnerAuthentication,
 *     localizedReason:)`.
 *
 * Both platforms allow device PIN/passcode fallback by default
 * (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL` on Android,
 * `.deviceOwnerAuthentication` on iOS). The Settings toggle
 * "Require biometric only" is deliberately deferred — alpha scope is
 * "any device-owner unlock satisfies the gate", which keeps the feature
 * usable on devices where the user hasn't enrolled biometric but has set
 * a PIN.
 *
 * **Why `expect class` and not `interface`:** the Android `actual`
 * carries a `FragmentActivity` reference attached at Activity onCreate
 * and detached at onDestroy (mirrors
 * [io.github.b150005.skeinly.notifications.PushTokenRegistrar]'s
 * launcher-attachment lifecycle). Modeling as interface would force the
 * commonMain layer to expose an Activity-typed seam that doesn't
 * generalize to iOS. The `expect class` lets each actual declare its
 * own attach/detach surface independently.
 *
 * **Android Activity-reference lifecycle**: the Android actual stores a
 * `@Volatile`-marked nullable strong reference cleared in
 * `detachActivity()` (NOT a `WeakReference`). The single-Activity
 * architecture + the deterministic onCreate/onDestroy attach/detach
 * pair guarantees the reference is cleared before the Activity becomes
 * unreachable, so no memory leak risk. Future multi-Activity scenarios
 * (deep-linked NavHost into a secondary Activity, etc.) would need to
 * reconsider this.
 *
 * Thread safety: [authenticate] suspends until the OS dialog resolves;
 * concurrent calls are not expected and not guarded — callers MUST
 * serialize at the call site (the [BiometricGuardian] uses a Mutex to
 * enforce single-flight semantics across `requireForResume` /
 * `requireForAction`).
 */
expect class BiometricAuthenticator {
    /**
     * Reads the current OS biometric capability without prompting.
     * Cheap (boolean read from a system cache); safe to call from
     * Composable composition setup.
     */
    fun canAuthenticate(): BiometricAvailability

    /**
     * Triggers the OS biometric prompt with [reason] as the dialog
     * subtitle / body. Returns when the user resolves the dialog or the
     * OS cancels (Activity destroyed, app backgrounded mid-prompt).
     *
     * Negative-button label is NOT a parameter because both platforms
     * provide their own OS-localized cancel UI under the configuration
     * we use (Android `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` makes
     * `setNegativeButtonText` mutually exclusive with
     * `setAllowedAuthenticators`; iOS shows its own "Cancel" /
     * "Use Passcode" buttons in `LAContext.evaluatePolicy`).
     *
     * @param title the dialog title; localized at the call site.
     * @param reason the dialog body / subtitle explaining why the
     *   prompt is shown. Localized at the call site.
     */
    suspend fun authenticate(
        title: String,
        reason: String,
    ): BiometricResult
}

/**
 * Closed enum mirroring the cross-platform biometric availability
 * states.
 */
enum class BiometricAvailability {
    /** Hardware present, user enrolled, ready to prompt. */
    Available,

    /** Hardware absent or unusable (covers BIOMETRIC_ERROR_NO_HARDWARE
     * / BIOMETRIC_ERROR_HW_UNAVAILABLE on Android,
     * LAErrorBiometryNotAvailable on iOS). */
    NoHardware,

    /** Hardware present but no biometric enrolled (user hasn't set up
     *  Face ID / fingerprint). On Android this also covers
     *  BIOMETRIC_ERROR_NONE_ENROLLED. */
    NotEnrolled,

    /** Hardware present + biometric enrolled but the OS has temporarily
     *  disabled biometric auth (e.g. too many failed attempts ⇒ lockout
     *  on Android; LAErrorBiometryLockout on iOS). User-recoverable
     *  only via a successful PIN entry. */
    Lockout,
}

/**
 * Outcome of a [BiometricAuthenticator.authenticate] call. Closed enum;
 * unknown OS error codes collapse to [Failed].
 */
sealed interface BiometricResult {
    /** User passed the OS prompt (biometric or PIN fallback). */
    data object Success : BiometricResult

    /** User explicitly tapped Cancel / dismissed the prompt. */
    data object Cancelled : BiometricResult

    /** OS reported failure (locked-out, hardware unavailable, biometric
     *  template mismatch after the configured retry threshold, etc.).
     *  Distinct from [Cancelled] because Cancelled is user intent
     *  (don't surface error UI) and Failed is a system condition
     *  (surface "Biometric not available — try again or use PIN"). */
    data object Failed : BiometricResult

    /** Caller requested authentication while hardware is absent /
     *  unusable / no enrollment. Returned without firing the OS
     *  prompt — same shape as the [BiometricAvailability] read. */
    data object Unavailable : BiometricResult
}
