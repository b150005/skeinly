package io.github.b150005.skeinly.di

import com.russhwolf.settings.Settings
import io.github.b150005.skeinly.data.remote.SupabaseConfig
import io.github.b150005.skeinly.data.remote.isConfigured
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.Duration.Companion.seconds

/**
 * Client-side request timeout for every Supabase API call routed through the
 * shared SupabaseClient (Auth, Postgrest, Realtime, Storage, Functions).
 *
 * Why 20 seconds: pre-alpha 2026-05-13 diagnosis tied 11 Sentry App Hang
 * issues + reproducible "sign-up button hangs forever" UX to an unconfigured
 * Ktor HTTP timeout. Without an explicit timeout, the Darwin (iOS) engine
 * defaults to NSURLSession's per-resource timeout (60 s) AND can stall
 * indefinitely on connect for unreachable hosts. The cascade:
 *   1. user taps Sign Up
 *   2. `viewModelScope.launch { signUp(...) }` calls Supabase Auth
 *   3. Supabase Auth resolves DNS / opens TCP / sends POST /auth/v1/signup
 *   4. Underlying NSURLSession (or OkHttp on Android) blocks waiting for a
 *      response that never arrives
 *   5. ViewModel state stays `isSubmitting = true` forever; user sees a
 *      disabled button + small spinner, perceives "nothing happens"
 *   6. Sentry App Hang Detector reports >2s main-thread blocking from the
 *      iOS side
 *
 * 20 s is a balance:
 *   - Long enough for legitimate slow connections (TestFlight tester on
 *     hotel WiFi, mobile-data dead zone, etc.) to complete a single
 *     auth request which is typically <1 s on the median.
 *   - Short enough that the user gets visible failure feedback within
 *     "feels broken" tolerance (<30 s industry rule of thumb).
 *   - Long enough that legitimate slow paths (cold-start Edge Function
 *     warm-up on first hit, large storage upload chunk) do not false-fail.
 *
 * A timeout fires as a `HttpRequestTimeoutException` which the
 * `SignUpUseCase` (and every other use case wrapping a Supabase call) catches
 * via `catch (e: Exception)` and converts to `UseCaseResult.Failure`. The
 * existing `AuthViewModel.submit` `Failure` branch sets `state.error` and the
 * SwiftUI / Compose alert renders.
 */
private val SUPABASE_REQUEST_TIMEOUT = 20.seconds

val supabaseModule =
    module {
        // Only register SupabaseClient when configured.
        // Consumers use getOrNull<SupabaseClient>() to handle its absence.
        if (SupabaseConfig.isConfigured) {
            single<SupabaseClient> {
                val authSettings = get<Settings>(qualifier = named("auth"))
                createSupabaseClient(
                    supabaseUrl = SupabaseConfig.url,
                    supabaseKey = SupabaseConfig.publishableKey,
                ) {
                    // Pre-alpha 2026-05-13 — explicit request timeout on the
                    // SupabaseClientBuilder. supabase-kt 3.6 exposes this as
                    // a top-level property that propagates into Ktor's
                    // `HttpTimeout` plugin internally. See the KDoc on the
                    // `SUPABASE_REQUEST_TIMEOUT` constant above for the full
                    // diagnostic trail.
                    requestTimeout = SUPABASE_REQUEST_TIMEOUT

                    install(Auth) {
                        flowType = FlowType.PKCE
                        // Pre-alpha A14 (HIGH severity) — explicit
                        // SessionManager backed by encrypted Settings
                        // (Android EncryptedSharedPreferences / iOS
                        // Keychain). Without this override, supabase-kt
                        // 3.6.0 falls back to
                        // `SettingsSessionManager(createDefaultSettings())`
                        // which resolves to unencrypted SharedPreferences
                        // (`PreferenceManager.getDefaultSharedPreferences`)
                        // on Android and unencrypted NSUserDefaults on
                        // iOS — i.e. refresh tokens at rest in plaintext.
                        // The qualifier("auth") Settings comes from
                        // `PlatformModule.{android,ios}.kt`.
                        sessionManager = SettingsSessionManager(authSettings)
                    }
                    install(Postgrest)
                    install(Realtime)
                    install(Storage)
                    // Phase 41.2b (ADR-016 §3.3, §4.3): consumed by
                    // RemoteSymbolPackDataSource for the
                    // `request-pack-download` Edge Function.
                    install(Functions)
                }
            }
        }
    }
