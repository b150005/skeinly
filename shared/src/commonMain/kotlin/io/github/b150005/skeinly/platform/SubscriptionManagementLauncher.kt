package io.github.b150005.skeinly.platform

/**
 * Pre-alpha A30 — opens the platform's native subscription-management UI
 * so the user can review, change, or cancel a Skeinly Pro subscription.
 *
 * Required by Google Play Subscription Disclosure policy (must provide
 * an in-app path to manage / cancel subscriptions; see
 * `pre-alpha-checklist.md` §2.1 A8) and recommended by Apple HIG
 * (mirrors the App Store's own subscription management page).
 *
 * Behavior:
 *   - Android: opens
 *     `https://play.google.com/store/account/subscriptions?package=io.github.b150005.skeinly`.
 *     The Play Store app intercepts this https URL via verified App
 *     Link and routes to the Subscriptions UI. Falls back to a browser
 *     when the Play Store app is unavailable. Per Google docs we
 *     deliberately omit the `&sku=<product_id>` parameter so the
 *     destination is the user's full subscription list, not a single
 *     product page — gives the user context if they hold multiple
 *     subscriptions and matches the App Store's symmetric "all
 *     subscriptions" landing.
 *   - iOS: opens `https://apps.apple.com/account/subscriptions` via
 *     `UIApplication.openURL`. iOS routes this URL to the App Store
 *     app's subscription management section.
 *
 * Mirrors the [StoreUrlLauncher] pattern (constructor-injected
 * Application Context on Android; parameterless on iOS) so the Koin
 * DI surface stays uniform with the existing platform launchers.
 *
 * Fire-and-forget contract: `open` returns immediately. Failure is
 * swallowed — the user can navigate to subscription management
 * manually via Settings.app / Play Store as a last resort.
 */
expect class SubscriptionManagementLauncher {
    fun open()
}

/**
 * Apple subscriptions URL — universal across regions. iOS routes
 * `https://apps.apple.com/account/subscriptions` directly to the
 * native subscription-management screen of the App Store app (or
 * Settings.app's Subscriptions panel on newer iOS versions).
 */
internal const val APPLE_SUBSCRIPTIONS_URL = "https://apps.apple.com/account/subscriptions"

/**
 * Google Play subscription management URL. The `package` query
 * parameter scopes the destination to Skeinly's subscriptions
 * specifically. The `market://` deep-link equivalent for the same
 * destination is `market://account/subscriptions?package=...` —
 * tried first, with web fallback when the Play Store app is absent.
 */
internal const val PLAY_SUBSCRIPTIONS_PACKAGE_ID = "io.github.b150005.skeinly"
internal const val PLAY_SUBSCRIPTIONS_DEEP_LINK =
    "market://account/subscriptions?package=$PLAY_SUBSCRIPTIONS_PACKAGE_ID"
internal const val PLAY_SUBSCRIPTIONS_WEB_URL =
    "https://play.google.com/store/account/subscriptions?package=$PLAY_SUBSCRIPTIONS_PACKAGE_ID"
