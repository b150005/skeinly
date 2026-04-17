package io.github.b150005.knitnote.data.remote

expect object SupabaseConfig {
    val url: String
    val anonKey: String
}

val SupabaseConfig.isConfigured: Boolean
    get() = url.isNotBlank() && anonKey.isNotBlank()
