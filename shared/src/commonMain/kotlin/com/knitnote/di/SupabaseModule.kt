package com.knitnote.di

import com.knitnote.data.remote.SupabaseConfig
import com.knitnote.data.remote.isConfigured
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import org.koin.dsl.module

val supabaseModule = module {
    single<SupabaseClient?> {
        if (!SupabaseConfig.isConfigured) return@single null

        createSupabaseClient(
            supabaseUrl = SupabaseConfig.url,
            supabaseKey = SupabaseConfig.anonKey,
        ) {
            install(Auth) {
                flowType = FlowType.PKCE
            }
            install(Postgrest)
            install(Realtime)
        }
    }
}
