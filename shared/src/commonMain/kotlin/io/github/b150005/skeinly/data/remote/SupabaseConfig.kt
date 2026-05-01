package io.github.b150005.skeinly.data.remote

expect object SupabaseConfig {
    val url: String
    val publishableKey: String
}

val SupabaseConfig.isConfigured: Boolean
    get() = url.isNotBlank() && publishableKey.isNotBlank()
