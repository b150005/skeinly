package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.config.SupabaseCredentials

actual object SupabaseConfig {
    actual val url: String = SupabaseCredentials.URL
    actual val anonKey: String = SupabaseCredentials.ANON_KEY
}
