package io.github.b150005.skeinly.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Phase 24.2d (ADR-017 §3.6) — Android implementation. The
 * permission-status read uses pure platform APIs (no androidx.core
 * dependency) so the shared module's Android dependency surface
 * doesn't grow.
 *
 * **Permission read path**:
 *   1. **`NotificationManager.areNotificationsEnabled()`** (API 24+) is
 *      the cross-API "is the channel enabled?" gate; below `minSdk = 26`
 *      this would not exist, but our floor is 26 so the call is always
 *      available.
 *   2. **API 33+ runtime POST_NOTIFICATIONS**: starting Android 13 the
 *      app must hold the runtime permission BEFORE notifications can
 *      show. `Context.checkSelfPermission` returns the cached state
 *      without prompting.
 *   3. The two reads compose: a user can have the runtime permission
 *      granted but channels disabled (or vice versa). `GRANTED` requires
 *      both legs; the partial-grant cases collapse to `DENIED` because
 *      the user has explicitly opted out somewhere along the way.
 *
 * **`requestPermission` is Activity-scoped**, but this class is Koin-
 * registered with the application Context (no Activity at construction
 * time). For 24.2d we delegate `requestPermission` to a fresh
 * `queryPermissionStatus` read — this is the post-prompt-resolved status,
 * which is also the correct state to surface in the UI when the
 * Activity-launcher path is wired in 24.2e.
 *
 * The 24.2e Activity-scoped path looks like:
 * ```
 * fun MainActivity.requestNotificationPermission() {
 *     val launcher = registerForActivityResult(RequestPermission()) { granted ->
 *         lifecycleScope.launch { registrar.onPermissionResult(granted) }
 *     }
 *     launcher.launch(POST_NOTIFICATIONS)
 * }
 * ```
 * with `onPermissionResult` exposed as a new method on this class. 24.2d
 * keeps the contract narrow — UI dispatching `RequestEnable` calls the
 * VM, which calls `requestPermission()`, which today reads the post-
 * prompt cache and returns the resolved state. On a fresh 24.2d build
 * (no real launcher wired) this means the user sees the OS prompt only
 * after the host Activity wires the launcher in 24.2e.
 *
 * **Token acquisition deferred to 24.2e** — the FCM SDK isn't on the
 * classpath yet. `registerForPushNotifications` returns null until then.
 */
@Suppress("UnusedPrivateProperty")
actual class PushTokenRegistrar(
    private val context: Context,
) {
    actual suspend fun queryPermissionStatus(): NotificationPermissionStatus {
        // Channels-enabled gate (API 24+).
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val channelsEnabled = nm?.areNotificationsEnabled() ?: false

        // API 33+ runtime POST_NOTIFICATIONS gate.
        val runtimeGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                // API 26..32 — POST_NOTIFICATIONS doesn't exist; install-time
                // grant is implicit.
                true
            }

        return when {
            channelsEnabled && runtimeGranted -> NotificationPermissionStatus.GRANTED
            // API 33+: not yet asked (runtime cache returns DENIED until the
            // first prompt). We can't tell "never asked" from "denied" via
            // checkSelfPermission alone — `shouldShowRequestPermissionRationale`
            // would distinguish the two but requires an Activity.
            // 24.2d's defensive choice: treat any not-fully-granted state on
            // API 33+ as DENIED so the UI surfaces the OS Settings deep-link.
            // 24.2e refines this with the activity-scoped launcher.
            !runtimeGranted -> NotificationPermissionStatus.DENIED
            else -> NotificationPermissionStatus.DENIED
        }
    }

    actual suspend fun requestPermission(): NotificationPermissionStatus {
        // Activity-scoped permission launcher lands in 24.2e. For 24.2d the
        // request path is a query — under the existing UI flow the user has
        // already crossed the in-app explainer (which records the
        // "asked" bit globally), so the OS prompt should fire next, but the
        // host Activity needs to wire the ActivityResultLauncher first.
        return queryPermissionStatus()
    }

    actual suspend fun registerForPushNotifications(locale: String): String? {
        // Phase 24.2e wires:
        //   1. `FirebaseMessaging.getInstance().token.await()` for the FCM
        //      token. Requires `firebase-messaging` on the classpath +
        //      `google-services.json` decoded from the
        //      `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` GitHub Secret per
        //      docs/en/release-secrets.md.
        //   2. `device_tokens` upsert via the planned `DeviceTokenRepository`
        //      (commonMain), which calls `supabaseClient.from("device_tokens").upsert(...)`
        //      with platform = "android" and the BCP-47 locale.
        return null
    }
}
