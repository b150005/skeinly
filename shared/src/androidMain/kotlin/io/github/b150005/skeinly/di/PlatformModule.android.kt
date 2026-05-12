// `EncryptedSharedPreferences` + `MasterKey` were deprecated in
// `androidx.security:security-crypto` 1.1.0 with no concrete in-package
// replacement; Google's stated direction is direct Android Keystore +
// AES-GCM key handling for new code. For Skeinly pre-alpha A14 we keep
// the well-trodden EncryptedSharedPreferences path — it still encrypts at
// rest via Android Keystore-managed Tink keys and remains compatible with
// the multiplatform-settings `SharedPreferencesSettings` adapter without
// requiring a new wire format. Post-alpha (or post-GA) migration to a
// Keystore-direct + DataStore approach is tracked in CLAUDE.md Tech Debt.
@file:Suppress("DEPRECATION")

package io.github.b150005.skeinly.di

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.b150005.skeinly.data.remote.ConnectivityMonitor
import io.github.b150005.skeinly.db.DriverFactory
import io.github.b150005.skeinly.notifications.OsSettingsLauncher
import io.github.b150005.skeinly.notifications.PushTokenRegistrar
import io.github.b150005.skeinly.platform.DeviceContextProvider
import io.github.b150005.skeinly.platform.StoreUrlLauncher
import org.koin.core.qualifier.named
import org.koin.dsl.module

val platformModule =
    module {
        single { DriverFactory(get()) }
        single { ConnectivityMonitor(get()) }
        // skeinly_prefs is unencrypted — suitable for non-sensitive UX flags only.
        // Auth tokens go to the qualifier("auth") binding below.
        single<Settings> { SharedPreferencesSettings.Factory(get()).create("skeinly_prefs") }
        // Pre-alpha A14 (HIGH severity) — encrypted Settings for Supabase
        // Auth session storage. SupabaseModule passes this instance to
        // `SettingsSessionManager` so refresh + access tokens are AES256-GCM
        // encrypted at rest. Backed by EncryptedSharedPreferences whose
        // master key is managed by the Android Keystore via Google's Tink
        // library — non-extractable without root + keystore compromise.
        //
        // File name `skeinly_auth_secure.xml` is intentionally distinct from
        // `skeinly_prefs.xml` so a future audit-grep for auth storage hits
        // a load-bearing identifier. Migration from the prior unencrypted
        // default (`PreferenceManager.getDefaultSharedPreferences`) is
        // implicit per the pre-v1 breaking-change policy: existing users
        // re-authenticate on first launch after this change ships.
        single<Settings>(qualifier = named("auth")) {
            val context = get<android.content.Context>()
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            val prefs =
                EncryptedSharedPreferences.create(
                    context,
                    "skeinly_auth_secure",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            SharedPreferencesSettings(prefs)
        }
        // Phase 39 W5b (ADR-020) — bug-report submission moved from
        // a platform `BugSubmissionLauncher` expect/actual to the
        // commonMain `BugReportProxyClient` (registered in
        // RepositoryModule). DeviceContextProvider is still
        // platform-specific (reads Android Build.* / Locale).
        single { DeviceContextProvider(get()) }
        // Phase 24.2e (ADR-017 §3.5, §3.6) — push-token + permission
        // registrar. 24.2e swaps in FirebaseMessaging.getInstance().token
        // + Activity-scoped POST_NOTIFICATIONS launcher wiring + the
        // commonMain `DeviceTokenRepository` upsert path behind the same
        // expect/actual surface that 24.2b shipped as a stub.
        single { PushTokenRegistrar(get(), get()) }
        // Phase 24.2c (ADR-017 §3.6) — opens the OS app-notification
        // Settings page so a denied user can re-enable.
        single { OsSettingsLauncher(get()) }
        // Phase 39 (W4 / 2026-05-11) — Play Store deep-link / web fallback
        // launcher for the force-update gate's "Update now" CTA. Takes
        // application Context for `Intent(VIEW, market://) + startActivity`.
        single { StoreUrlLauncher(get()) }
    }
