package io.github.b150005.skeinly.data.remote

import platform.Foundation.NSBundle

actual object SupabaseConfig {
    actual val url: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("SUPABASE_URL") as? String ?: ""
    actual val publishableKey: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("SUPABASE_PUBLISHABLE_KEY") as? String ?: ""
}
