package com.knitnote.data.remote

import com.knitnote.shared.BuildConfig

actual object SupabaseConfig {
    actual val url: String = BuildConfig.SUPABASE_URL
    actual val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
}
