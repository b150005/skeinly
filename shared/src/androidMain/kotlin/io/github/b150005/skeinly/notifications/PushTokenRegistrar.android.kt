package io.github.b150005.skeinly.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import io.github.b150005.skeinly.domain.repository.DeviceTokenRepository
import io.github.b150005.skeinly.domain.repository.PushPlatform
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 24.2e (ADR-017 §3.5, §3.6) — Android implementation. Wires:
 *   1. Pure platform APIs for permission status reads (no androidx.core
 *      dep on the shared module).
 *   2. Activity-scoped runtime POST_NOTIFICATIONS prompt via a callback
 *      that [MainActivity] registers in `onCreate` / clears in `onDestroy`.
 *      The host Activity owns the `ActivityResultLauncher<String>`
 *      registration; this class only knows how to ask for it to fire and
 *      receive the post-result callback ([onPermissionResult]).
 *   3. FCM registration token acquisition via
 *      [FirebaseMessaging.getInstance().token] with `kotlinx-coroutines-play-services`
 *      `await()` on the Google Play Services `Task<String>`.
 *   4. `device_tokens` upsert on success via the injected
 *      [DeviceTokenRepository] commonMain port.
 *
 * **API < 33 fast path**: `POST_NOTIFICATIONS` is install-time-granted on
 * Android 12L and below, so [requestPermission] short-circuits to the
 * channels-enabled query without firing any launcher. Our `minSdk = 26`
 * floor sits well above the 24+ threshold for `areNotificationsEnabled()`.
 *
 * **Activity launcher null path**: when the prompt is requested but no
 * Activity is attached (background process, Application-context-only call
 * site, MainActivity tear-down race), we fall back to the cached query
 * status. This keeps the suspend bounded — the prompt cannot fire without
 * an Activity, so blocking forever on a `CompletableDeferred` would hang
 * the calling coroutine.
 *
 * **Awaiting the OS prompt**: bounded by [PERMISSION_PROMPT_TIMEOUT_MS]
 * via `withTimeoutOrNull` so a missed callback (Activity destroyed
 * mid-prompt by configuration change) returns DENIED rather than hanging
 * the in-app explainer. The `ActivityResultLauncher` API guarantees the
 * callback fires once the Activity recreates and re-registers, but a
 * stale `CompletableDeferred` from the prior MainActivity instance is
 * unrecoverable; the timeout closes that loop cleanly.
 */
actual class PushTokenRegistrar(
    private val context: Context,
    private val deviceTokenRepository: DeviceTokenRepository,
) {
    /**
     * Set by [MainActivity.onCreate] to the closure that fires the
     * Activity's `ActivityResultLauncher.launch(POST_NOTIFICATIONS)`.
     * Cleared in `onDestroy`. Only mutated from the Main thread.
     */
    @Volatile
    private var permissionLauncher: (() -> Unit)? = null

    /**
     * In-flight prompt result. Captured before [permissionLauncher]
     * fires; completed by [onPermissionResult] when the user dismisses
     * the system dialog.
     */
    @Volatile
    private var pendingResult: CompletableDeferred<Boolean>? = null

    actual suspend fun queryPermissionStatus(): NotificationPermissionStatus {
        // Channels-enabled gate (API 24+). minSdk = 26 so the call is always available.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val channelsEnabled = nm?.areNotificationsEnabled() ?: false

        // API 33+ runtime POST_NOTIFICATIONS gate. Below 33 the install-time
        // grant is implicit so `runtimeGranted` collapses to true.
        val runtimeGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        return if (channelsEnabled && runtimeGranted) {
            NotificationPermissionStatus.GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !runtimeGranted) {
            // checkSelfPermission cannot distinguish "never asked" from
            // "denied" without an Activity. NOT_DETERMINED is the
            // correct return when the launcher is attached (we can still
            // surface the system prompt); DENIED otherwise so the UI
            // surfaces the OS Settings deep-link instead of an
            // unreachable Enable CTA.
            if (permissionLauncher != null) NotificationPermissionStatus.NOT_DETERMINED else NotificationPermissionStatus.DENIED
        } else {
            // API < 33 + channels disabled, or API 33+ + runtime granted but channels disabled.
            // Either way the user has explicitly opted out at the OS layer; only the
            // OS Settings deep-link recovers.
            NotificationPermissionStatus.DENIED
        }
    }

    actual suspend fun requestPermission(): NotificationPermissionStatus {
        // API < 33 has no runtime gate — short-circuit to the cached query.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return queryPermissionStatus()
        }
        // Already granted — re-query to surface the freshest combined
        // (channels-enabled && runtime-granted) state without firing a
        // redundant prompt.
        val current = queryPermissionStatus()
        if (current == NotificationPermissionStatus.GRANTED) return current

        // In-flight guard — a second concurrent caller would overwrite
        // `pendingResult` before the first deferred resolves, leaving
        // the first caller hanging on an orphaned deferred for the full
        // PERMISSION_PROMPT_TIMEOUT_MS. The Android OS allows only one
        // permission dialog visible at a time so concurrent calls are
        // unlikely in normal use, but a future ViewModel that triggers
        // on both `TriggerEncountered` + a lifecycle-resume signal could
        // hit this. The whole method runs on Main (launcher.invoke +
        // ActivityResultCallback both fire from Main), so a simple
        // null-check is sufficient — no Mutex needed.
        if (pendingResult != null) return current

        // No Activity attached (background process, MainActivity in tear-down) —
        // fall back to the cached query. The user re-enters the explainer
        // path on the next Activity foreground.
        val launcher = permissionLauncher ?: return current

        val deferred = CompletableDeferred<Boolean>()
        pendingResult = deferred
        try {
            launcher.invoke()
        } catch (e: CancellationException) {
            pendingResult = null
            throw e
        } catch (_: Throwable) {
            // Launcher invocation itself failed (Activity in transitional
            // state, IllegalStateException from registerForActivityResult).
            // Surface as the cached query rather than hanging on the deferred.
            pendingResult = null
            return current
        }

        val granted = withTimeoutOrNull(PERMISSION_PROMPT_TIMEOUT_MS) { deferred.await() }
        pendingResult = null

        return if (granted == true) {
            NotificationPermissionStatus.GRANTED
        } else {
            // Re-read so we surface DENIED vs NOT_DETERMINED accurately
            // (a timeout where the Activity was destroyed mid-prompt is
            // recoverable on the next foreground; a true denial isn't).
            queryPermissionStatus()
        }
    }

    actual suspend fun registerForPushNotifications(locale: String): String? {
        if (queryPermissionStatus() != NotificationPermissionStatus.GRANTED) return null

        val token =
            try {
                FirebaseMessaging.getInstance().token.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // FCM acquisition fails on:
                //   - emulators without Google Play Services (not our CI surface, but local dev)
                //   - network errors during the FCM handshake
                //   - misconfigured google-services.json (developer setup oversight)
                // The OS retries on its own schedule; a future foreground will pick up the rotation.
                return null
            }

        // Best-effort upsert. Failure here is non-fatal: the token is in
        // the OS, the next foreground will retry the upsert, and the
        // permission status stays GRANTED. Surfacing the failure to the
        // VM would force a misleading "permission denied"-flavored error.
        // Exhaustive `when` over [UseCaseResult] keeps a future variant
        // addition surfacing as a compile error — both branches end in
        // empty bodies because both outcomes share the "return token"
        // tail behavior.
        when (deviceTokenRepository.upsertToken(token, PushPlatform.ANDROID, locale)) {
            is UseCaseResult.Success -> { /* recorded server-side */ }
            is UseCaseResult.Failure -> { /* best-effort: see KDoc above */ }
        }
        return token
    }

    /**
     * Called by [MainActivity.onCreate] to wire the Activity-owned
     * `ActivityResultLauncher<String>`. The closure MUST call
     * `launcher.launch(POST_NOTIFICATIONS)` so the system prompt fires.
     */
    fun attachLauncher(launcher: () -> Unit) {
        permissionLauncher = launcher
    }

    /**
     * Called by [MainActivity.onDestroy] to clear the launcher reference
     * — keeps the registrar from holding a destroyed Activity.
     */
    fun detachLauncher() {
        permissionLauncher = null
        // Defensive: complete any pending result with `false` so a
        // requestPermission() suspended over a config-change tear-down
        // resolves cleanly rather than waiting on the next attachment.
        pendingResult?.complete(false)
        pendingResult = null
    }

    /**
     * Called by [MainActivity]'s `ActivityResultCallback` after the
     * system dialog dismisses. Idempotent on a stale callback (e.g. a
     * delayed dispatch arriving after a config-change tear-down) —
     * [pendingResult] is null, so the second call no-ops.
     */
    fun onPermissionResult(granted: Boolean) {
        pendingResult?.complete(granted)
        pendingResult = null
    }

    private companion object {
        // Generous bound. The OS prompt usually completes in <5s of user
        // interaction time; the longest realistic case is the user
        // switching apps and returning to dismiss the dialog, which is
        // still well under 30s. A timeout here only protects against an
        // Activity destroyed mid-prompt where the callback never fires.
        const val PERMISSION_PROMPT_TIMEOUT_MS = 30_000L
    }
}
