package io.github.b150005.skeinly.config

/**
 * Phase 41.3 (ADR-016 §6 §41.3) — platform-specific RevenueCat Public
 * SDK Key for `Purchases.configure(apiKey)`.
 *
 * iOS keys start with `appl_` (vendor-setup.md A0d-2); Android keys start
 * with `goog_` (A0d-2 Android section). Each platform reads its own
 * key from the platform's idiomatic config surface:
 * - **Android**: `BuildConfig.REVENUECAT_API_KEY` populated from
 *   `local.properties` `REVENUECAT_API_KEY_ANDROID` or CI env var
 *   `REVENUECAT_API_KEY_ANDROID` (vendor-setup §20).
 * - **iOS**: Info.plist `REVENUECAT_API_KEY` populated from xcconfig
 *   `REVENUECAT_API_KEY` matching GitHub Secret `REVENUECAT_API_KEY_IOS`
 *   (vendor-setup §19).
 *
 * Empty string means "RevenueCat not configured for this build" — common
 * in local-dev when secrets aren't wired. [isConfigured] returns false in
 * that case and [RevenueCatBootstrap.configure] short-circuits without
 * calling `Purchases.configure`. Paywall ViewModel surfaces a generic
 * "subscriptions unavailable in this build" error rather than crashing.
 *
 * Same pattern as [SupabaseConfig][io.github.b150005.skeinly.data.remote.SupabaseConfig]
 * (URL + publishable key) and `BuildFlags` (isBeta).
 */
expect object RevenueCatConfig {
    val apiKey: String
}

/** True when the platform key was injected at build time. */
val RevenueCatConfig.isConfigured: Boolean
    get() = apiKey.isNotEmpty()
