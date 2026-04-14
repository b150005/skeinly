import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kover)
}

val localProps =
    Properties().also { props ->
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) props.load(FileInputStream(localFile))
        // CI fallback: read from environment variables when local.properties is absent
        System.getenv("SUPABASE_URL")?.let { props.setProperty("SUPABASE_URL", it) }
        System.getenv("SUPABASE_ANON_KEY")?.let { props.setProperty("SUPABASE_ANON_KEY", it) }
    }

val generateSupabaseConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/supabaseConfig")
    val url = localProps.getProperty("SUPABASE_URL", "")
    val key = localProps.getProperty("SUPABASE_ANON_KEY", "")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("com/knitnote/config")
        dir.mkdirs()
        val escapedUrl = url.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        val escapedKey = key.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        dir.resolve("SupabaseCredentials.kt").writeText(
            buildString {
                appendLine("package com.knitnote.config")
                appendLine()
                appendLine("internal object SupabaseCredentials {")
                appendLine("    const val URL: String = \"$escapedUrl\"")
                appendLine("    const val ANON_KEY: String = \"$escapedKey\"")
                appendLine("}")
            },
        )
    }
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "com.knitnote.shared"
        compileSdk = 36
        minSdk = 26

        withHostTestBuilder {}.configure {}

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.navigation.compose)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.foundation)
            implementation(compose.ui)
            // Supabase
            implementation(project.dependencies.platform(libs.supabase.bom))
            implementation(libs.supabase.auth)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.compose.auth)
            implementation(libs.supabase.compose.auth.ui)
            implementation(libs.supabase.realtime)
            implementation(libs.supabase.storage)
            // Image loading
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            // Preferences
            implementation(libs.multiplatform.settings)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        androidMain {
            kotlin.srcDir(
                generateSupabaseConfig.flatMap {
                    layout.buildDirectory.dir("generated/supabaseConfig")
                },
            )
            dependencies {
                implementation(libs.koin.android)
                implementation(libs.sqldelight.android.driver)
                implementation(libs.ktor.client.android)
                implementation(libs.multiplatform.settings.no.arg)
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
            implementation(libs.ktor.client.darwin)
            implementation(libs.multiplatform.settings.no.arg)
        }
        iosTest.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}

// Ensure the generated SupabaseCredentials source is available before any task that
// consumes the androidMain source set. The flatMap-based srcDir wiring should carry
// the task dependency, but the AGP 9.x KMP plugin does not propagate it to compile
// or ktlint tasks on CI (Gradle 9.x implicit-dependency validation catches this).
tasks.configureEach {
    if (name.contains("AndroidMain", ignoreCase = true)) {
        dependsOn(generateSupabaseConfig)
    }
}

sqldelight {
    databases {
        create("KnitNoteDatabase") {
            packageName.set("com.knitnote.db")
        }
    }
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // Generated / infra
                    "com.knitnote.db.*",
                    "com.knitnote.di.*",
                    "com.knitnote.ui.navigation.*",
                    "com.knitnote.config.*",
                    // Remote data sources — thin Supabase SDK wrappers, untestable without MockEngine.
                    // Security validation (auth, input sanitization, size limits) is covered at the UseCase layer.
                    "com.knitnote.data.remote.Remote*DataSource",
                    "com.knitnote.data.remote.SupabaseConfig",
                    "com.knitnote.data.remote.SupabaseConfigKt",
                    "com.knitnote.data.remote.ConnectivityMonitor",
                    // Compose UI — untestable on JVM (covered by Android instrumented tests)
                    "*.ComposableSingletons\$*",
                    "com.knitnote.ui.chartviewer.*",
                    "com.knitnote.ui.imagepicker.*",
                    "com.knitnote.ui.activityfeed.ActivityFeedScreenKt*",
                    "com.knitnote.ui.auth.LoginScreenKt*",
                    "com.knitnote.ui.comments.CommentSectionKt*",
                    "com.knitnote.ui.profile.ProfileScreenKt*",
                    "com.knitnote.ui.projectdetail.ProjectDetailScreenKt*",
                    "com.knitnote.ui.projectdetail.ShareLinkDialogKt*",
                    "com.knitnote.ui.projectdetail.UserPickerDialogKt*",
                    "com.knitnote.ui.projectlist.CreateProjectDialogKt*",
                    "com.knitnote.ui.projectlist.ProjectListScreenKt*",
                    "com.knitnote.ui.sharedcontent.SharedContentScreenKt*",
                    "com.knitnote.ui.sharedwithme.SharedWithMeScreenKt*",
                    "com.knitnote.ui.patternlibrary.PatternLibraryScreenKt*",
                    "com.knitnote.ui.patternedit.PatternEditScreenKt*",
                    "com.knitnote.ui.settings.SettingsScreenKt*",
                    "com.knitnote.ui.onboarding.OnboardingScreenKt*",
                    "com.knitnote.ui.components.EmptyStateViewKt*",
                )
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}
