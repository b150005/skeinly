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
