# Add project specific ProGuard rules here.

# ---- kotlinx.serialization ----
# Keep @Serializable classes and their generated serializer companions
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class io.github.b150005.skeinly.**$$serializer { *; }
-keepclassmembers class io.github.b150005.skeinly.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.b150005.skeinly.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Domain models (all @Serializable) ----
-keep class io.github.b150005.skeinly.domain.model.** { *; }

# ---- Navigation route classes (@Serializable) ----
-keep class io.github.b150005.skeinly.ui.navigation.Onboarding { *; }
-keep class io.github.b150005.skeinly.ui.navigation.Login { *; }
-keep class io.github.b150005.skeinly.ui.navigation.ProjectList { *; }
-keep class io.github.b150005.skeinly.ui.navigation.ProjectDetail { *; }
-keep class io.github.b150005.skeinly.ui.navigation.SharedWithMe { *; }
-keep class io.github.b150005.skeinly.ui.navigation.ActivityFeed { *; }
-keep class io.github.b150005.skeinly.ui.navigation.Profile { *; }
-keep class io.github.b150005.skeinly.ui.navigation.Settings { *; }
-keep class io.github.b150005.skeinly.ui.navigation.PatternLibrary { *; }
-keep class io.github.b150005.skeinly.ui.navigation.PatternEdit { *; }
-keep class io.github.b150005.skeinly.ui.navigation.Discovery { *; }
-keep class io.github.b150005.skeinly.ui.navigation.SharedContent { *; }

# ---- Koin DI ----
-keep class org.koin.core.** { *; }
-keep class org.koin.mp.** { *; }
-keep class org.koin.android.** { *; }
-keep class org.koin.androidx.** { *; }
-keep class org.koin.compose.** { *; }
-dontwarn org.koin.android.**
-dontwarn org.koin.androidx.**

# ---- Ktor (used by Supabase) ----
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ---- Supabase ----
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**
