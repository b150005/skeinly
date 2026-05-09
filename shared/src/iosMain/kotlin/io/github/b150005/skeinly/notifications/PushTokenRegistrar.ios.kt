package io.github.b150005.skeinly.notifications

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * Phase 24.2d (ADR-017 §3.6) — iOS implementation. Bridges
 * `UNUserNotificationCenter.getNotificationSettings(completionHandler:)` and
 * `UNUserNotificationCenter.requestAuthorization(options:completionHandler:)`
 * onto Kotlin coroutines via [suspendCancellableCoroutine].
 *
 * **Token acquisition deferred to 24.2e** — `registerForPushNotifications`
 * stays a no-op until APNs setup completes (Apple Developer Push capability
 * on the App ID, `.p8` key registration, AppDelegate
 * `application:didRegisterForRemoteNotificationsWithDeviceToken:` Channel
 * pipe, `device_tokens` upsert via the planned `DeviceTokenRepository`).
 *
 * **Provisional + ephemeral collapse to GRANTED**: iOS surfaces the user's
 * permission grant in three "yes" flavors (`.authorized` for full grant,
 * `.provisional` for quiet-delivery, `.ephemeral` for App Clip lifetime).
 * From the app's perspective all three permit local notification posting
 * and (for `.authorized` / `.provisional`) APNs token acquisition, so the
 * domain enum collapses them to [NotificationPermissionStatus.GRANTED].
 */
actual class PushTokenRegistrar {
    actual suspend fun queryPermissionStatus(): NotificationPermissionStatus =
        suspendCancellableCoroutine { continuation ->
            UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
                val mapped =
                    when (settings?.authorizationStatus) {
                        UNAuthorizationStatusAuthorized,
                        UNAuthorizationStatusProvisional,
                        UNAuthorizationStatusEphemeral,
                        -> NotificationPermissionStatus.GRANTED
                        UNAuthorizationStatusDenied -> NotificationPermissionStatus.DENIED
                        UNAuthorizationStatusNotDetermined -> NotificationPermissionStatus.NOT_DETERMINED
                        else -> NotificationPermissionStatus.NOT_DETERMINED
                    }
                continuation.resume(mapped)
            }
        }

    actual suspend fun requestPermission(): NotificationPermissionStatus =
        suspendCancellableCoroutine { continuation ->
            // ADR-017 §3.6 — request the standard alert + badge + sound combo.
            // Critical / time-sensitive / provisional are deliberately NOT
            // requested in MVP: provisional bypasses the system prompt
            // entirely (silent delivery only) which conflicts with the
            // explainer UX, and critical requires a separate Apple
            // entitlement application.
            val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
            UNUserNotificationCenter
                .currentNotificationCenter()
                .requestAuthorizationWithOptions(options) { granted, error ->
                    val mapped =
                        when {
                            error != null -> NotificationPermissionStatus.NOT_DETERMINED
                            granted -> NotificationPermissionStatus.GRANTED
                            else -> NotificationPermissionStatus.DENIED
                        }
                    continuation.resume(mapped)
                }
        }

    actual suspend fun registerForPushNotifications(locale: String): String? {
        // Phase 24.2e wires:
        //   1. UIApplication.sharedApplication.registerForRemoteNotifications()
        //      on the main thread (fires the AppDelegate token callback).
        //   2. AppDelegate `application:didRegisterForRemoteNotificationsWithDeviceToken:`
        //      forwards the NSData token to a Channel here, which is read
        //      with a withTimeout(...) bound so a missing entitlement / no
        //      Apple Developer Push capability surfaces as a clean null
        //      rather than a hung suspend.
        //   3. device_tokens upsert via the new DeviceTokenRepository.
        return null
    }
}
