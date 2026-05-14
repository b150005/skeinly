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
import io.github.b150005.skeinly.auth.OAuthClient
import io.github.b150005.skeinly.biometric.BiometricAuthenticator
import io.github.b150005.skeinly.data.remote.ConnectivityMonitor
import io.github.b150005.skeinly.db.DriverFactory
import io.github.b150005.skeinly.notifications.OsSettingsLauncher
import io.github.b150005.skeinly.notifications.PushTokenRegistrar
import io.github.b150005.skeinly.platform.DeviceContextProvider
import io.github.b150005.skeinly.platform.StoreUrlLauncher
import io.github.b150005.skeinly.platform.SubscriptionManagementLauncher
import io.github.b150005.skeinly.platform.SupportContactLauncher
import io.github.b150005.skeinly.ui.a11y.ReduceMotionDetector
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
        // Pre-alpha A30 — opens Play Store → Account → Subscriptions so
        // a Pro subscriber can review / change / cancel from inside the
        // app. Required by Google Play subscription disclosure policy.
        single { SubscriptionManagementLauncher(get()) }
        // Pre-alpha A34 — opens mail composer with mailto: pre-filled
        // with support email + diagnostic context (app version, OS,
        // device, locale). Settings → Help & Support → Contact Support
        // entry point.
        single { SupportContactLauncher(get()) }
        // Pre-alpha A25 — reads the OS-level "Reduce Motion" / "Remove
        // animations" toggle so custom animations (HorizontalPager
        // page-scroll, animateColorAsState splash transitions, etc.)
        // can degrade to instant snaps when the user has opted out of
        // motion. Stock Material 3 transitions auto-respect; this
        // detector covers the few surfaces that bypass the stock path.
        single { ReduceMotionDetector(get()) }
        // Phase 26.6 (ADR-022 §6.5) — biometric prompt + process-level
        // lifecycle. BiometricAuthenticator takes a `FragmentActivity`
        // reference attached at MainActivity.onCreate / detached at
        // onDestroy (mirrors the PushTokenRegistrar.attachLauncher
        // pattern). AppLifecycleObserver observes ProcessLifecycleOwner
        // lazily on first subscription — registration is main-thread,
        // implemented inside the Flow's onStart.
        single { BiometricAuthenticator(get()) }
        single {
            io.github.b150005.skeinly.platform
                .AppLifecycleObserver()
        }
        // Phase 26.2 (ADR-022 §6.2) — Google Sign-In via Credential
        // Manager. The Web Client ID is generated automatically by
        // the google-services Gradle plugin into the Android
        // resource `R.string.default_web_client_id`. Resolution
        // happens lazily inside `acquireGoogleIdToken()` so a
        // missing google-services.json (local dev) degrades to a
        // `Failure(...)` rather than a class-load failure here.
        //
        // The R.string resource ID is looked up by name at runtime
        // (rather than referenced via shared/androidApp's R class)
        // because the shared module does not own a resource
        // namespace — the resource lives on androidApp's classpath
        // but is visible to the application Context.
        single {
            val context: android.content.Context = get()
            OAuthClient(
                appContext = context,
                webClientIdProvider = {
                    val resId =
                        context.resources.getIdentifier(
                            "default_web_client_id",
                            "string",
                            context.packageName,
                        )
                    if (resId != 0) context.getString(resId) else ""
                },
            )
        }
    }
