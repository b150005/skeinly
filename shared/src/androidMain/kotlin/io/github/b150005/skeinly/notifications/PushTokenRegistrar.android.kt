package io.github.b150005.skeinly.notifications

import android.content.Context

/**
 * Phase 24.2b (ADR-017 §3.6) — Android **no-op stub**. 24.2d will replace
 * this with the real implementation backed by:
 *   - `NotificationManagerCompat.from(context).areNotificationsEnabled()` for
 *     the cross-API-level "is the channel enabled?" check.
 *   - `ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS)` on
 *     API 33+ for the runtime-permission gate; ungated on older APIs.
 *   - `FirebaseMessaging.getInstance().token.await()` for token acquisition.
 *
 * The no-op preserves the `expect class` shape so the rest of the app
 * (DI registration in PlatformModule, UI in 24.2c) compiles + runs
 * without crashes; opening the Settings → Notifications row simply
 * shows "Disabled" until 24.2d wires the real status read.
 *
 * @param context retained for 24.2d wiring; deliberately unused here so
 *   the stub doesn't accumulate wiring surface that later changes shape.
 */
@Suppress("UnusedPrivateProperty")
actual class PushTokenRegistrar(
    private val context: Context,
) {
    actual suspend fun queryPermissionStatus(): NotificationPermissionStatus = NotificationPermissionStatus.NOT_DETERMINED

    actual suspend fun requestPermission(): NotificationPermissionStatus = NotificationPermissionStatus.NOT_DETERMINED

    actual suspend fun registerForPushNotifications(locale: String): String? = null
}
