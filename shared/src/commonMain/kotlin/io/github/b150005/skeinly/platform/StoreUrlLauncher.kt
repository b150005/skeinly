package io.github.b150005.skeinly.platform

/**
 * Phase 39 (W4 / 2026-05-11) — opens the platform store listing for
 * Skeinly. Triggered by the force-update gate's "Update now" CTA.
 *
 * Behavior:
 *   - Android: launches `Intent(VIEW, Uri.parse("market://details?id=io.github.b150005.skeinly"))`
 *     which the Play Store app intercepts. Fallback to the web Play
 *     Store listing if no Play app is installed (e.g. Amazon Fire devices).
 *   - iOS: opens `https://apps.apple.com/app/idXXXX` via
 *     `UIApplication.sharedApplication.openURL(...)`. Apple's URL
 *     handles app-installed redirection to the App Store app
 *     automatically. The app's bundle ID surfaces in the URL via the
 *     `apps.apple.com/<region>/app/<slug>/id<appleAppId>` shape — for
 *     Skeinly, the appleAppId is captured in [APPLE_APP_STORE_URL].
 *
 * **Why a separate class instead of a top-level expect fun**: Android's
 * actual needs Application `Context` (constructor-injected via Koin),
 * which doesn't reach into commonMain naturally. Wrapping in a class
 * with constructor-injected Context mirrors the existing
 * [BugSubmissionLauncher] / [io.github.b150005.skeinly.notifications.OsSettingsLauncher]
 * precedents and keeps the DI surface uniform.
 *
 * Fire-and-forget contract: `open` returns immediately; the platform
 * intent / openURL runs asynchronously. Failure (e.g. no store app
 * installed AND web fallback also unreachable on offline iOS) is
 * swallowed — the user is on a force-update screen, can re-tap, and
 * the failure mode is genuinely transient.
 */
expect class StoreUrlLauncher {
    fun open()
}

/**
 * Apple App Store URL for Skeinly. **Placeholder for alpha** — the
 * real `id` segment is assigned when the app is first created in App
 * Store Connect. Update this constant in the same commit that the
 * Apple App ID lands in the iosApp Bundle ID configuration.
 *
 * Format reference:
 * https://developer.apple.com/documentation/storekit/sklerklerk-overview
 *
 * Until then, the placeholder URL resolves to a generic search-results
 * page on the App Store, which is the gentlest possible failure mode
 * for an end-user on a force-update screen.
 */
internal const val APPLE_APP_STORE_URL = "https://apps.apple.com/app/skeinly"

/**
 * Google Play Store URL for Skeinly. Uses the `market://` scheme as
 * the primary so the Play Store app handles the deep-link directly;
 * falls back to `https://play.google.com/...` if no Play app is
 * installed.
 *
 * The package ID is structural — change this only if the Android
 * application ID in [androidApp/build.gradle.kts] also changes.
 */
internal const val PLAY_STORE_PACKAGE_ID = "io.github.b150005.skeinly"
internal const val PLAY_STORE_DEEP_LINK = "market://details?id=$PLAY_STORE_PACKAGE_ID"
internal const val PLAY_STORE_WEB_URL = "https://play.google.com/store/apps/details?id=$PLAY_STORE_PACKAGE_ID"
