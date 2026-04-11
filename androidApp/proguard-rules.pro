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

-keep,includedescriptorclasses class com.knitnote.**$$serializer { *; }
-keepclassmembers class com.knitnote.** {
    *** Companion;
}
-keepclasseswithmembers class com.knitnote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Domain models (all @Serializable) ----
-keep class com.knitnote.domain.model.** { *; }

# ---- Navigation route classes (@Serializable) ----
-keep class com.knitnote.ui.navigation.Login { *; }
-keep class com.knitnote.ui.navigation.ProjectList { *; }
-keep class com.knitnote.ui.navigation.ProjectDetail { *; }
-keep class com.knitnote.ui.navigation.SharedWithMe { *; }
-keep class com.knitnote.ui.navigation.ActivityFeed { *; }
-keep class com.knitnote.ui.navigation.Profile { *; }
-keep class com.knitnote.ui.navigation.SharedContent { *; }

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
