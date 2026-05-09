package io.github.b150005.skeinly.notifications

/**
 * Phase 24.2 (ADR-017 §3.6) — platform abstraction for the OS push-token
 * acquisition + notification-permission lifecycle.
 *
 * Each `actual` implementation owns:
 *   1. **Native permission status query** — iOS reads
 *      `UNUserNotificationCenter.getNotificationSettings(completionHandler:)`;
 *      Android uses `NotificationManagerCompat.areNotificationsEnabled()` plus
 *      `ContextCompat.checkSelfPermission(POST_NOTIFICATIONS)` on API 33+.
 *   2. **Native permission request flow** — iOS calls
 *      `UNUserNotificationCenter.requestAuthorization(options:)`;
 *      Android API 33+ goes through `ActivityCompat.requestPermissions`
 *      via the Activity's permission launcher; legacy API < 33 returns
 *      [NotificationPermissionStatus.GRANTED] structurally because no
 *      runtime permission gate exists.
 *   3. **Token acquisition once permission is granted** — iOS calls
 *      `UIApplication.shared.registerForRemoteNotifications()` and waits
 *      for the AppDelegate's `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)`
 *      callback; Android fetches `FirebaseMessaging.getInstance().token`
 *      via `await()`.
 *   4. **Persisting the freshly acquired token** through a
 *      `device_tokens` upsert. (Phase 24.2d wires the actual upsert;
 *      24.2b ships only the no-op interface scaffolding.)
 *
 * **24.2b ships no-op stubs** that always report
 * [NotificationPermissionStatus.NOT_DETERMINED] and never return a token.
 * 24.2d swaps in the native implementations behind the same `expect`
 * surface; the UI layer (24.2c) calls these methods unchanged.
 *
 * **Why `expect class` and not `interface`:** the Android actual carries a
 * non-trivial `Context` dependency that doesn't generalize to iOS. Using
 * `expect class` lets each `actual` declare its own constructor surface
 * (Android: `(context: Context)`, iOS: `()`) without forcing the common
 * layer to hold a platform type. This is the same pattern
 * [io.github.b150005.skeinly.platform.BugSubmissionLauncher] establishes.
 */
expect class PushTokenRegistrar {
    /**
     * Reads the current OS notification-permission state without prompting.
     * Safe to call at any time; returns [NotificationPermissionStatus.NOT_DETERMINED]
     * if the user has never been asked.
     */
    suspend fun queryPermissionStatus(): NotificationPermissionStatus

    /**
     * Triggers the OS permission prompt if the current state is
     * [NotificationPermissionStatus.NOT_DETERMINED]; otherwise returns the
     * existing state without re-prompting (the OS does not allow re-asking
     * once a user has explicitly denied — they must navigate to Settings).
     *
     * Caller is expected to have already shown the in-app pre-permission
     * explainer (via [NotificationPermissionPrompter]) before invoking
     * this method.
     */
    suspend fun requestPermission(): NotificationPermissionStatus

    /**
     * Acquires a fresh push token from the OS and upserts it into the
     * `device_tokens` table (Phase 24.2d wires the upsert side; 24.2b
     * stub just returns `null`).
     *
     * The OS may rotate tokens at any time (uninstall/reinstall, OS
     * update, etc.) — callers should treat the returned value as
     * opaque and trust the device_tokens row as the source of truth.
     *
     * @param locale BCP-47 tag (`"en-US"` / `"ja-JP"`) recorded on the
     *   device_tokens row so the [io.github.b150005.skeinly] notify-on-write
     *   Edge Function can render localized push bodies (ADR-017 §3.7).
     * @return the freshly acquired token, or `null` if acquisition
     *   failed (no permission, OS error, network, etc.). Callers should
     *   not retry aggressively; the OS retries on its own schedule.
     */
    suspend fun registerForPushNotifications(locale: String): String?
}

/**
 * Closed enum mirroring the cross-platform notification-permission
 * states the OS surfaces. Maps to:
 *   - iOS `UNAuthorizationStatus`: `.notDetermined` / `.authorized` / `.denied` (+ `.provisional` / `.ephemeral` collapse to `GRANTED`).
 *   - Android `NotificationManagerCompat.areNotificationsEnabled()` plus
 *     API 33+ `ContextCompat.checkSelfPermission(POST_NOTIFICATIONS)`:
 *     legacy API < 33 always reports `GRANTED` (install-time grant).
 */
enum class NotificationPermissionStatus {
    /** User has never been asked. iOS `.notDetermined` or Android pre-prompt. */
    NOT_DETERMINED,

    /** User has granted notification permission. iOS `.authorized` / Android allowed. */
    GRANTED,

    /** User has explicitly denied. Recoverable only via OS Settings deep-link. */
    DENIED,
}
