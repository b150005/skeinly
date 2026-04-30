package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.config.SupabaseCredentials

actual object SupabaseConfig {
    actual val url: String = SupabaseCredentials.URL
    actual val anonKey: String = SupabaseCredentials.ANON_KEY
}
