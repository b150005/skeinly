package io.github.b150005.skeinly.biometric

import io.github.b150005.skeinly.data.preferences.BiometricPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Phase 26.6 (ADR-022 §6.5) — orchestrates biometric re-auth on
 * foreground transitions + sensitive-action gates.
 *
 * Two entry points:
 *
 *  1. [requireForResume] — called by the app-lifecycle observer on
 *     each Background→Foreground transition. Fires the OS biometric
 *     prompt iff (a) the user has opted in via Settings
 *     ([BiometricPreferences.biometricEnabled]); AND (b) the time
 *     elapsed since the most recent Background event exceeds the
 *     configured threshold; AND (c) the OS reports
 *     [BiometricAvailability.Available]. Returns the resulting
 *     [BiometricResult] so the caller (root navigator) can decide
 *     whether to invalidate the session (Failed / Cancelled) or
 *     continue normal navigation (Success). Short-circuits to
 *     [BiometricResult.Success] when the gate is skipped (disabled /
 *     under threshold / cold-start without prior background) — the
 *     caller never needs to differentiate "gate passed" from "gate
 *     not required".
 *
 *  2. [requireForAction] — called by sensitive-action call sites
 *     (DeleteAccountUseCase, SettingsViewModel.performDisableMfa) on
 *     the action-confirm click. Fires the OS biometric prompt IF the
 *     OS reports availability. When unavailable (no hardware / not
 *     enrolled / lockout), returns [BiometricResult.Success] without
 *     prompting — users without enrolled biometric or PIN are not
 *     locked out of destructive operations; the in-app confirmation
 *     dialog at the call site remains the sole gate. Does NOT update
 *     the re-auth timestamp.
 *
 * **Mutex serialization**: both methods acquire [authMutex] so a
 * concurrent `requireForResume` (lifecycle thread) and
 * `requireForAction` (UI thread) don't surface two OS prompts.
 *
 * **Lambda-seam DI**: [queryAvailability] + [performAuthentication]
 * lift the actual `BiometricAuthenticator` `expect class` out of the
 * Guardian's direct dependency graph so commonTest can inject stubs
 * (the `expect class` surfaces as `final` in commonTest and cannot be
 * subclassed for fakes — same constraint
 * [io.github.b150005.skeinly.ui.notifications.NotificationPermissionViewModel]
 * works around).
 *
 * **i18n strings**: resolved lazily via the CMP runtime's `getString`
 * suspend API. The Guardian's own suspend signature makes this natural
 * — no precomputed Strings struct, no DI of a string-provider seam.
 *
 * **Clock injection**: [clock] defaults to [Clock.System] but tests
 * inject a controllable clock (see `BiometricGuardianTest`) to make
 * threshold evaluation deterministic.
 */
@OptIn(ExperimentalTime::class)
class BiometricGuardian(
    private val queryAvailability: () -> BiometricAvailability,
    private val performAuthentication: suspend (title: String, reason: String) -> BiometricResult,
    private val preferences: BiometricPreferences,
    /**
     * Phase 26.6 — i18n seam. The Guardian needs to resolve four
     * CMP-resource strings (prompt title + resume reason +
     * account-deletion reason + MFA-disable reason); resolving via
     * the suspend `getString(Res.string.*)` API directly couples the
     * Guardian to the CMP resources runtime, which isn't initialized
     * in JVM unit tests by default. Threading the resolver as a
     * lambda keeps the Guardian pure commonMain — tests pass a stub
     * that returns the enum name; production wires the actual
     * `getString(...)` calls via [io.github.b150005.skeinly.di.biometricModule].
     */
    private val resolveString: suspend (BiometricPromptCopy) -> String,
    private val clock: Clock = Clock.System,
) {
    /**
     * Last observed Background timestamp. Set by [onBackgrounded];
     * read + compared in [requireForResume]. `null` until the first
     * background transition — cold-start path through
     * [requireForResume] structurally no-ops because there's no prior
     * background to measure against.
     */
    @Volatile
    private var lastBackgroundedAt: Instant? = null

    private val authMutex = Mutex()

    /**
     * Called by the app-lifecycle observer when the OS reports
     * `Backgrounded`. Captures the current clock instant for use by
     * the next [requireForResume].
     */
    fun onBackgrounded() {
        lastBackgroundedAt = clock.now()
    }

    /**
     * Test seam — read-only accessor for `lastBackgroundedAt` so unit
     * tests can assert reset behavior after a successful prompt.
     */
    internal fun lastBackgroundedAtForTest(): Instant? = lastBackgroundedAt

    suspend fun requireForResume(): BiometricResult =
        authMutex.withLock {
            if (!preferences.biometricEnabled.value) return@withLock BiometricResult.Success
            val backgroundedAt = lastBackgroundedAt ?: return@withLock BiometricResult.Success
            val threshold = preferences.reauthThresholdSeconds.value
            val elapsedSeconds = (clock.now() - backgroundedAt).inWholeSeconds
            if (elapsedSeconds < threshold) return@withLock BiometricResult.Success
            val availability = queryAvailability()
            if (availability != BiometricAvailability.Available) {
                // Opted-in user on a device that can no longer satisfy
                // the gate. Failed surfaces to the navigator which
                // signs out per ADR §3.8. Recoverable by toggling off
                // in Settings.
                lastBackgroundedAt = null
                return@withLock BiometricResult.Failed
            }
            val outcome =
                performAuthentication(
                    resolveString(BiometricPromptCopy.Title),
                    resolveString(BiometricPromptCopy.ResumeReason),
                )
            // Reset on any outcome so a second Foregrounded right after
            // doesn't immediately re-trigger. On Cancelled/Failed the
            // caller signs out; subsequent re-sign-in re-establishes
            // session before the next Foregrounded.
            lastBackgroundedAt = null
            outcome
        }

    suspend fun requireForAction(action: SensitiveAction): BiometricResult =
        authMutex.withLock {
            val availability = queryAvailability()
            if (availability != BiometricAvailability.Available) {
                // See class KDoc: bypass to Success when the OS cannot
                // satisfy the gate. In-app destructive-confirm dialog
                // at the call site remains the gate on those devices.
                return@withLock BiometricResult.Success
            }
            performAuthentication(
                resolveString(BiometricPromptCopy.Title),
                resolveString(reasonCopyFor(action)),
            )
        }

    private fun reasonCopyFor(action: SensitiveAction) =
        when (action) {
            SensitiveAction.AccountDeletion -> BiometricPromptCopy.AccountDeletionReason
            SensitiveAction.MfaDisable -> BiometricPromptCopy.MfaDisableReason
        }
}

/**
 * Closed enum naming the four BiometricGuardian-facing string keys.
 * Production `resolveString` lambda translates each variant to the
 * corresponding `getString(Res.string.*)` call (see
 * [io.github.b150005.skeinly.di.biometricModule]); tests pass a stub
 * that returns the variant name.
 */
enum class BiometricPromptCopy {
    Title,
    ResumeReason,
    AccountDeletionReason,
    MfaDisableReason,
}
