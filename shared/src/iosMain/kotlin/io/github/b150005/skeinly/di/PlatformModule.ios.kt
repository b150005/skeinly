package io.github.b150005.skeinly.di

import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import io.github.b150005.skeinly.data.remote.ConnectivityMonitor
import io.github.b150005.skeinly.db.DriverFactory
import io.github.b150005.skeinly.notifications.OsSettingsLauncher
import io.github.b150005.skeinly.notifications.PushTokenRegistrar
import io.github.b150005.skeinly.platform.DeviceContextProvider
import io.github.b150005.skeinly.platform.StoreUrlLauncher
import io.github.b150005.skeinly.platform.SubscriptionManagementLauncher
import io.github.b150005.skeinly.platform.SupportContactLauncher
import org.koin.core.qualifier.named
import org.koin.dsl.module

val platformModule =
    module {
        single { DriverFactory() }
        single { ConnectivityMonitor() }
        // skeinly_prefs is unencrypted NSUserDefaults — suitable for non-sensitive UX flags only.
        // Auth tokens go to the qualifier("auth") binding below.
        single<Settings> { NSUserDefaultsSettings.Factory().create("skeinly_prefs") }
        // Pre-alpha A14 (HIGH severity) — encrypted Settings for Supabase
        // Auth session storage. SupabaseModule passes this instance to
        // `SettingsSessionManager` so refresh + access tokens land in the
        // iOS Keychain (`kSecClassGenericPassword`) rather than the
        // standard NSUserDefaults plist that supabase-kt 3.6.0 falls back
        // to. multiplatform-settings 1.3.0's `KeychainSettings` wraps the
        // standard `SecItemAdd` / `SecItemCopyMatching` SecItem API and
        // applies `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` by
        // default — entries are not synced to iCloud Keychain and remain
        // device-local after the first post-boot unlock.
        //
        // Service identifier is reverse-DNS app-bundle scoped so a future
        // shared-app-bundle or app-group scenario doesn't collide. Migration
        // from the prior unencrypted-NSUserDefaults default is implicit
        // per the pre-v1 breaking-change policy: existing users sign in
        // again on first launch after this change ships.
        single<Settings>(qualifier = named("auth")) {
            // multiplatform-settings 1.3.0 marks KeychainSettings as
            // @ExperimentalSettingsImplementation because the API surface
            // (initialization parameters, error handling on SecItem
            // failures) may evolve. Behavior on iOS is production-stable
            // — SecItemAdd / SecItemCopyMatching are documented public
            // APIs; the experimental tag is wrapper-API-level only.
            @OptIn(ExperimentalSettingsImplementation::class)
            KeychainSettings(service = "io.github.b150005.skeinly.auth")
        }
        // Phase 39 W5b (ADR-020) — bug-report submission moved from
        // platform `BugSubmissionLauncher` expect/actual to the
        // commonMain `BugReportProxyClient` (registered in
        // RepositoryModule). DeviceContextProvider is still
        // platform-specific (reads UIDevice / NSLocale).
        single { DeviceContextProvider() }
        // Phase 24.2e (ADR-017 §3.5, §3.6) — push-token + permission
        // registrar. 24.2e wires UNUserNotificationCenter + APNs token
        // acquisition via `UIApplication.registerForRemoteNotifications`
        // + AppDelegate bridge + the commonMain `DeviceTokenRepository`
        // upsert behind the same expect/actual surface that 24.2b
        // shipped as a stub.
        single { PushTokenRegistrar(get()) }
        // Phase 24.2c (ADR-017 §3.6) — opens the OS app-notification
        // Settings page so a denied user can re-enable.
        single { OsSettingsLauncher() }
        // Phase 39 (W4 / 2026-05-11) — App Store URL launcher for the
        // force-update gate's "Update now" CTA. Parameterless on iOS
        // (UIApplication.openURL doesn't need an injected Context).
        single { StoreUrlLauncher() }
        // Pre-alpha A30 — opens App Store → Account → Subscriptions so
        // a Pro subscriber can review / change / cancel from inside the
        // app. Apple HIG-recommended; mirrors the Android binding for
        // symmetry across platforms.
        single { SubscriptionManagementLauncher() }
        // Pre-alpha A34 — opens mail composer with mailto: pre-filled
        // with support email + diagnostic context (app version, OS,
        // device, locale). Settings → Help & Support → Contact Support.
        single { SupportContactLauncher() }
    }
