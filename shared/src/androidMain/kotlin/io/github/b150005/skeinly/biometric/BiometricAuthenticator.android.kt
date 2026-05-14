package io.github.b150005.skeinly.biometric

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Phase 26.6 (ADR-022 §6.5) — Android implementation. Wires:
 *  1. `BiometricManager.canAuthenticate(BIOMETRIC_STRONG or
 *     DEVICE_CREDENTIAL)` for the availability query — accepts class-3
 *     biometric OR device PIN/passcode so users without an enrolled
 *     fingerprint/face but with a PIN are still able to satisfy the
 *     gate.
 *  2. `BiometricPrompt.PromptInfo.Builder` + `BiometricPrompt(
 *     FragmentActivity, Executor, AuthenticationCallback)` for the
 *     prompt. Requires a `FragmentActivity` reference attached at
 *     Activity onCreate and detached at onDestroy (mirrors
 *     [io.github.b150005.skeinly.notifications.PushTokenRegistrar]'s
 *     launcher attachment pattern). When no Activity is attached
 *     (background process, mid tear-down), [authenticate] returns
 *     [BiometricResult.Unavailable] structurally — caller must
 *     interpret as "couldn't prompt" rather than "user cancelled".
 *
 * **Authenticator combo `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`**: this
 * is the most permissive supported combo. Class-3 (Strong) biometric
 * is FIPS-equivalent (fingerprint sensors that passed ASR ≤7%, FAR
 * ≤1/50000, SAR per Android spec); class-2 (Weak) is excluded. Device
 * credential is the OS PIN/passcode/pattern. Both authentication
 * modalities are gated by the same OS lockout cooldown when the user
 * fails repeatedly.
 *
 * Thread safety: the `BiometricPrompt` API requires main-thread
 * construction. [authenticate] runs the construction inside
 * `suspendCancellableCoroutine` which preserves the caller's dispatcher
 * (typically `Dispatchers.Main.immediate` via the application-scope
 * coroutine on Android — the BiometricGuardian collects from
 * `AppLifecycleObserver.events` on this dispatcher).
 */
actual class BiometricAuthenticator(
    private val context: Context,
) {
    @Volatile
    private var activity: FragmentActivity? = null

    /**
     * Called by `MainActivity.onCreate` with the host `FragmentActivity`
     * reference. The Guardian's `requireForAction` / `requireForResume`
     * may suspend across configuration changes; on a config-change
     * tear-down the in-flight prompt is OS-cancelled and the
     * Continuation receives Cancelled (see [createCallback]).
     */
    fun attachActivity(activity: FragmentActivity) {
        this.activity = activity
    }

    /**
     * Called by `MainActivity.onDestroy`. Subsequent calls to
     * [authenticate] return [BiometricResult.Unavailable] until the
     * next attach.
     */
    fun detachActivity() {
        activity = null
    }

    actual fun canAuthenticate(): BiometricAvailability {
        val mgr = BiometricManager.from(context)
        val status = mgr.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        return when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            ->
                BiometricAvailability.NoHardware
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NotEnrolled
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricAvailability.Lockout
            else -> BiometricAvailability.NoHardware
        }
    }

    actual suspend fun authenticate(
        title: String,
        reason: String,
    ): BiometricResult {
        val host = activity ?: return BiometricResult.Unavailable
        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(context)
            val callback = createCallback(continuation)
            val prompt = BiometricPrompt(host, executor, callback)
            val info =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(reason)
                    // Note: setAllowedAuthenticators(STRONG | DEVICE_CREDENTIAL)
                    // is mutually exclusive with setNegativeButtonText —
                    // when device credential is allowed, the OS provides
                    // its own "Use PIN" / "Cancel" controls. So we set
                    // the allowed authenticators here and leave the
                    // negative-button label unset; the OS surfaces a
                    // localized "Use PIN" fallback button instead.
                    .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                    .build()
            try {
                prompt.authenticate(info)
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resume(BiometricResult.Failed)
            }
            continuation.invokeOnCancellation {
                // OS cancels the prompt when the Continuation is
                // cancelled (e.g. caller scope cancelled, Activity
                // recreate). No explicit dismiss API needed —
                // BiometricPrompt auto-cancels on Activity destroy.
                runCatching { prompt.cancelAuthentication() }
            }
        }
    }

    private fun createCallback(continuation: CancellableContinuation<BiometricResult>): BiometricPrompt.AuthenticationCallback =
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(BiometricResult.Success)
            }

            override fun onAuthenticationError(
                errorCode: Int,
                errString: CharSequence,
            ) {
                if (!continuation.isActive) return
                val mapped =
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED,
                        ->
                            BiometricResult.Cancelled
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE,
                        ->
                            BiometricResult.Unavailable
                        else -> BiometricResult.Failed
                    }
                continuation.resume(mapped)
            }

            // Note: onAuthenticationFailed (transient template mismatch
            // pre-retry-exhaustion) is intentionally NOT terminal — the
            // OS keeps the prompt visible and continues retrying until
            // the user cancels (ERROR_USER_CANCELED) or the retry limit
            // is hit (ERROR_LOCKOUT). No Continuation resume here.
        }
}
