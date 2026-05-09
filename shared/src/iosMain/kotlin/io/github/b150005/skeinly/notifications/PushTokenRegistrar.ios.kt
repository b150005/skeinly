package io.github.b150005.skeinly.notifications

import io.github.b150005.skeinly.domain.repository.DeviceTokenRepository
import io.github.b150005.skeinly.domain.repository.PushPlatform
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIApplication
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.resume

/**
 * Phase 24.2e (ADR-017 §3.5, §3.6) — iOS implementation. Wires:
 *   1. `UNUserNotificationCenter.getNotificationSettings(completionHandler:)`
 *      and `requestAuthorization(options:completionHandler:)` for the
 *      permission lifecycle (already shipped in 24.2d).
 *   2. `UIApplication.sharedApplication.registerForRemoteNotifications()`
 *      on the main thread to trigger the APNs token callback.
 *   3. The token bridge from `AppDelegate.didRegisterForRemoteNotificationsWithDeviceToken`
 *      into the in-flight `Channel<String?>` opened by
 *      [registerForPushNotifications]. Swift forwards via
 *      `KoinHelperKt.handleApnsTokenReceived(token:)` which resolves the
 *      Koin-singleton instance and calls [handleApnsToken].
 *   4. `device_tokens` upsert via the injected [DeviceTokenRepository]
 *      port on token success.
 *
 * **Provisional + ephemeral collapse to GRANTED**: iOS surfaces the
 * user's permission grant in three "yes" flavors (`.authorized` for
 * full grant, `.provisional` for quiet-delivery, `.ephemeral` for App
 * Clip lifetime). All three permit local notification posting and (for
 * `.authorized` / `.provisional`) APNs token acquisition, so the
 * domain enum collapses them to [NotificationPermissionStatus.GRANTED].
 *
 * **Channel(CONFLATED)**: in flight at most one token request at a
 * time. A second concurrent caller would observe the same channel and
 * is by construction safe — we replace any unread value (which can
 * only happen if the prior caller never receive()'d, e.g. cancelled
 * before resume), and the new caller receives the freshest value once
 * the AppDelegate dispatches.
 */
actual class PushTokenRegistrar(
    private val deviceTokenRepository: DeviceTokenRepository,
) {
    private val tokenChannel = Channel<String?>(Channel.CONFLATED)

    /**
     * Compare-and-set in-flight guard. The CONFLATED channel delivers
     * the AppDelegate-forwarded token to exactly one waiter; a second
     * concurrent caller would block on `receive()` and time out 30s
     * later. Kotlin/Native lacks `AtomicBoolean` so we use `AtomicInt`
     * (0 = idle, 1 = registration in flight) — see kotlin.concurrent.AtomicInt
     * KDoc for the CAS contract.
     */
    private val registrationInFlight = AtomicInt(0)

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

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun registerForPushNotifications(locale: String): String? {
        if (queryPermissionStatus() != NotificationPermissionStatus.GRANTED) return null

        // CAS-based in-flight guard. A second concurrent caller would
        // race on the single CONFLATED slot and time out 30s later;
        // returning null on the loser path keeps both callers' suspend
        // bounded.
        if (!registrationInFlight.compareAndSet(0, 1)) return null

        return try {
            registerForPushNotificationsInternal(locale)
        } finally {
            registrationInFlight.value = 0
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun registerForPushNotificationsInternal(locale: String): String? {
        // Drain any stale token from a prior call that resolved after
        // its caller cancelled. CONFLATED replaces unread values so at
        // most one stale element exists.
        tokenChannel.tryReceive()

        // `registerForRemoteNotifications` MUST be invoked on the main
        // thread per Apple docs (UIKit non-thread-safety). The
        // AppDelegate callback dispatches asynchronously when the OS
        // resolves; we suspend on the channel below.
        //
        // Kotlin/Native's `platform.UIKit` cinterop does not expose
        // `UIApplication.registerForRemoteNotifications()` directly
        // (the symbol is omitted from the generated Kotlin metadata
        // despite being public Obj-C since iOS 8), so we route through
        // `NSObject.performSelector(SEL)` — the runtime dispatches to
        // the real Obj-C method by selector name. Defense-in-depth
        // alternative would be a Swift accessor, but that requires
        // exposing an `@objc` class from the iosApp target which the
        // shared framework cannot import (the dependency arrow points
        // the wrong way).
        withContext(Dispatchers.Main) {
            UIApplication.sharedApplication.performSelector(
                NSSelectorFromString("registerForRemoteNotifications"),
            )
        }

        val token =
            try {
                withTimeoutOrNull(APNS_REGISTRATION_TIMEOUT_MS) { tokenChannel.receive() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return null

        // Best-effort upsert — see PushTokenRegistrar.android.kt KDoc
        // for the rationale. Exhaustive `when` over [UseCaseResult]
        // keeps a future variant addition surfacing as a compile error;
        // both branches end in empty bodies because both outcomes share
        // the "return token" tail behavior.
        when (deviceTokenRepository.upsertToken(token, PushPlatform.IOS, locale)) {
            is UseCaseResult.Success -> { /* recorded server-side */ }
            is UseCaseResult.Failure -> { /* best-effort: see KDoc above */ }
        }
        return token
    }

    /**
     * Called by `KoinHelperKt.handleApnsTokenReceived` (forwarded from
     * Swift `AppDelegate.application(_:didRegisterFor...)` /
     * `application(_:didFailToRegisterFor...)`) when the OS resolves
     * the APNs registration.
     *
     * The CONFLATED channel ensures the value is delivered to the
     * single in-flight `registerForPushNotifications` caller without
     * blocking on send. A delayed callback arriving after the caller
     * timed out replaces the stale slot; the next call's `tryReceive`
     * drain at the top of `registerForPushNotifications` clears it.
     */
    fun handleApnsToken(token: String?) {
        tokenChannel.trySend(token)
    }

    private companion object {
        // The APNs round-trip is typically <2s on physical devices; the
        // simulator never resolves on real APNs (bypassed at the OS
        // layer). 30s gives slack for cold-launch + network jitter
        // without leaving the suspend hung indefinitely on a missing
        // `aps-environment` entitlement (which would otherwise wedge
        // the in-app explainer's spinner).
        const val APNS_REGISTRATION_TIMEOUT_MS = 30_000L
    }
}
