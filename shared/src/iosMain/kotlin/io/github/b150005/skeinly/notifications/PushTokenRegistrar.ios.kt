package io.github.b150005.skeinly.notifications

/**
 * Phase 24.2b (ADR-017 §3.6) — iOS **no-op stub**. 24.2d will replace
 * this with the real implementation backed by:
 *   - `UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { ... }`
 *     for the permission-status read; bridged to suspend via `suspendCancellableCoroutine`.
 *   - `UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(...)`
 *     for the OS prompt; same bridge shape.
 *   - `UIApplication.sharedApplication.registerForRemoteNotifications()` plus the
 *     AppDelegate's `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)`
 *     callback piped through a Channel so the suspend function can return the token.
 *
 * The no-op preserves the `expect class` shape so the rest of the app
 * (DI registration in PlatformModule, UI in 24.2c) compiles + runs
 * without crashes; opening the Settings → Notifications row simply
 * shows "Disabled" until 24.2d wires the real status read.
 */
actual class PushTokenRegistrar {
    actual suspend fun queryPermissionStatus(): NotificationPermissionStatus = NotificationPermissionStatus.NOT_DETERMINED

    actual suspend fun requestPermission(): NotificationPermissionStatus = NotificationPermissionStatus.NOT_DETERMINED

    actual suspend fun registerForPushNotifications(locale: String): String? = null
}
