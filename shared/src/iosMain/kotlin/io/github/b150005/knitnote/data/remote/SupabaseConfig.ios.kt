package io.github.b150005.knitnote.data.remote

import platform.Foundation.NSBundle

actual object SupabaseConfig {
    actual val url: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("SUPABASE_URL") as? String ?: ""
    actual val anonKey: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("SUPABASE_ANON_KEY") as? String ?: ""
}
