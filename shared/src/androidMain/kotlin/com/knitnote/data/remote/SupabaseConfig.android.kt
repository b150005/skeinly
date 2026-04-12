package com.knitnote.data.remote

import com.knitnote.config.SupabaseCredentials

actual object SupabaseConfig {
    actual val url: String = SupabaseCredentials.URL
    actual val anonKey: String = SupabaseCredentials.ANON_KEY
}
