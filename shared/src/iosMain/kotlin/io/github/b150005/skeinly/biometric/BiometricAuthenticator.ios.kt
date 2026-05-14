package io.github.b150005.skeinly.biometric

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAuthenticationFailed
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * Phase 26.6 (ADR-022 §6.5) — iOS implementation.
 *
 *  - [canAuthenticate]: `LAContext.canEvaluatePolicy(
 *      .deviceOwnerAuthentication, error: &error)`. The PASSCODE-or-
 *    biometric policy is the right pick because we want PIN fallback
 *    on devices without enrolled Face ID/Touch ID. Distinguishes
 *    `BiometryNotAvailable` / `BiometryNotEnrolled` /
 *    `BiometryLockout` so the Settings screen can surface the
 *    correct status copy.
 *  - [authenticate]: `LAContext.evaluatePolicy(.deviceOwnerAuthentication,
 *    localizedReason:reply:)`. The callback fires off the main
 *    queue; we hop back to the main thread before resuming the
 *    Continuation so any UI-state mutation downstream is dispatcher-safe.
 *
 * The [title] parameter from the [BiometricAuthenticator] contract has
 * no iOS equivalent — iOS shows a system-localized title ("Skeinly
 * wants to use Face ID"). We accept the parameter to match the
 * cross-platform surface and only use [reason] (mapped to
 * `localizedReason`).
 *
 * `BetaInteropApi` opt-in is required because [ObjCObjectVar] is part
 * of the Kotlin/Native Objective-C interop surface that's still marked
 * Beta. The pattern (memScoped + alloc + ptr) is the documented
 * Kotlin/Native idiom for `&error` out-parameters; production-stable
 * across all Kotlin/Native targets we ship.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class BiometricAuthenticator {
    actual fun canAuthenticate(): BiometricAvailability {
        val context = LAContext()
        return memScoped {
            val errorVar: ObjCObjectVar<NSError?> = alloc<ObjCObjectVar<NSError?>>()
            val canEvaluate =
                context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, errorVar.ptr)
            if (canEvaluate) {
                BiometricAvailability.Available
            } else {
                when (errorVar.value?.code) {
                    LAErrorBiometryNotEnrolled ->
                        BiometricAvailability.NotEnrolled
                    LAErrorBiometryLockout -> BiometricAvailability.Lockout
                    LAErrorBiometryNotAvailable,
                    LAErrorPasscodeNotSet,
                    ->
                        BiometricAvailability.NoHardware
                    // Unknown code — collapse to NoHardware so the
                    // BiometricGuardian's availability check
                    // short-circuits and the Settings UI can surface
                    // the generic unavailable state.
                    else -> BiometricAvailability.NoHardware
                }
            }
        }
    }

    actual suspend fun authenticate(
        title: String,
        reason: String,
    ): BiometricResult {
        // Fresh LAContext per evaluation — Apple's docs explicitly say a
        // single LAContext should be reused only for the same logical
        // authentication; resetting between calls avoids stale internal
        // state when the user has e.g. just dismissed a prior prompt.
        val context = LAContext()
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                context.evaluatePolicy(
                    policy = LAPolicyDeviceOwnerAuthentication,
                    localizedReason = reason,
                ) { success, error ->
                    // Callback fires on an unspecified queue; hop to
                    // main before resuming so callers downstream of the
                    // suspendCancellableCoroutine that mutate UI state
                    // don't dispatch off-main.
                    dispatch_async(dispatch_get_main_queue()) {
                        if (!continuation.isActive) return@dispatch_async
                        val outcome =
                            when {
                                success -> BiometricResult.Success
                                else -> mapError(error?.code)
                            }
                        continuation.resume(outcome)
                    }
                }
                continuation.invokeOnCancellation {
                    // LAContext.invalidate cancels any pending
                    // evaluation and forces the callback to fire with
                    // an error of LAErrorAppCancel. Safe to call from
                    // any queue.
                    runCatching { context.invalidate() }
                }
            }
        }
    }

    private fun mapError(code: Long?): BiometricResult =
        when (code) {
            LAErrorUserCancel,
            LAErrorUserFallback,
            LAErrorSystemCancel,
            ->
                BiometricResult.Cancelled
            LAErrorBiometryNotAvailable,
            LAErrorBiometryNotEnrolled,
            LAErrorPasscodeNotSet,
            ->
                BiometricResult.Unavailable
            LAErrorBiometryLockout,
            LAErrorAuthenticationFailed,
            ->
                BiometricResult.Failed
            else -> BiometricResult.Failed
        }
}
