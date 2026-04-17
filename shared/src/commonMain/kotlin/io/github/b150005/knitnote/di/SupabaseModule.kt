package io.github.b150005.knitnote.di

import io.github.b150005.knitnote.data.remote.SupabaseConfig
import io.github.b150005.knitnote.data.remote.isConfigured
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import org.koin.dsl.module

val supabaseModule =
    module {
        // Only register SupabaseClient when configured.
        // Consumers use getOrNull<SupabaseClient>() to handle its absence.
        if (SupabaseConfig.isConfigured) {
            single<SupabaseClient> {
                createSupabaseClient(
                    supabaseUrl = SupabaseConfig.url,
                    supabaseKey = SupabaseConfig.anonKey,
                ) {
                    install(Auth) {
                        flowType = FlowType.PKCE
                    }
                    install(Postgrest)
                    install(Realtime)
                    install(Storage)
                }
            }
        }
    }
