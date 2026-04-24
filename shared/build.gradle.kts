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
        val dir = outputDir.get().asFile.resolve("io/github/b150005/knitnote/config")
        dir.mkdirs()
        val escapedUrl = url.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        val escapedKey = key.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        dir.resolve("SupabaseCredentials.kt").writeText(
            buildString {
                appendLine("package io.github.b150005.knitnote.config")
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
        namespace = "io.github.b150005.knitnote.shared"
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
            implementation(compose.components.resources)
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
                // Required for androidx.activity.compose.BackHandler used by
                // ui/platform/SystemBackHandler.android.kt
                implementation(libs.androidx.activity.compose)
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
            packageName.set("io.github.b150005.knitnote.db")
        }
    }
}

compose.resources {
    // Explicit package so common-module consumers get a stable import path
    // (`io.github.b150005.knitnote.generated.resources.Res`). Without this
    // the package is derived from the module name and can shift if we rename
    // `shared`. `publicResClass` exposes `Res` to platform modules that embed
    // the framework (androidApp, iosApp).
    publicResClass = true
    packageOfResClass = "io.github.b150005.knitnote.generated.resources"
    generateResClass = always
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // Generated / infra
                    "io.github.b150005.knitnote.db.*",
                    "io.github.b150005.knitnote.di.*",
                    "io.github.b150005.knitnote.ui.navigation.*",
                    "io.github.b150005.knitnote.config.*",
                    // Generated by org.jetbrains.compose.components.resources
                    // — the `Res` class and per-key accessors are static
                    // lookup plumbing with no behavior worth covering.
                    "io.github.b150005.knitnote.generated.resources.*",
                    // Remote data sources — thin Supabase SDK wrappers, untestable without MockEngine.
                    // Security validation (auth, input sanitization, size limits) is covered at the UseCase layer.
                    "io.github.b150005.knitnote.data.remote.Remote*DataSource",
                    "io.github.b150005.knitnote.data.remote.SupabaseConfig",
                    "io.github.b150005.knitnote.data.remote.SupabaseConfigKt",
                    "io.github.b150005.knitnote.data.remote.ConnectivityMonitor",
                    // Compose UI — untestable on JVM (covered by Android instrumented tests)
                    "*.ComposableSingletons\$*",
                    "io.github.b150005.knitnote.ui.chartviewer.*",
                    "io.github.b150005.knitnote.ui.imagepicker.*",
                    "io.github.b150005.knitnote.ui.activityfeed.ActivityFeedScreenKt*",
                    "io.github.b150005.knitnote.ui.auth.LoginScreenKt*",
                    "io.github.b150005.knitnote.ui.discovery.DiscoveryScreenKt*",
                    "io.github.b150005.knitnote.ui.comments.CommentSectionKt*",
                    "io.github.b150005.knitnote.ui.profile.ProfileScreenKt*",
                    "io.github.b150005.knitnote.ui.projectdetail.ProjectDetailScreenKt*",
                    "io.github.b150005.knitnote.ui.projectdetail.ShareLinkDialogKt*",
                    "io.github.b150005.knitnote.ui.projectdetail.UserPickerDialogKt*",
                    "io.github.b150005.knitnote.ui.projectlist.CreateProjectDialogKt*",
                    "io.github.b150005.knitnote.ui.projectlist.ProjectListScreenKt*",
                    "io.github.b150005.knitnote.ui.sharedcontent.SharedContentScreenKt*",
                    "io.github.b150005.knitnote.ui.sharedwithme.SharedWithMeScreenKt*",
                    "io.github.b150005.knitnote.ui.patternlibrary.PatternLibraryScreenKt*",
                    "io.github.b150005.knitnote.ui.patternedit.PatternEditScreenKt*",
                    "io.github.b150005.knitnote.ui.settings.SettingsScreenKt*",
                    "io.github.b150005.knitnote.ui.onboarding.OnboardingScreenKt*",
                    "io.github.b150005.knitnote.ui.components.EmptyStateViewKt*",
                    "io.github.b150005.knitnote.ui.symbol.SymbolGalleryScreenKt*",
                    "io.github.b150005.knitnote.ui.chart.ChartViewerScreenKt*",
                    "io.github.b150005.knitnote.ui.chart.ChartEditorScreenKt*",
                    "io.github.b150005.knitnote.ui.chart.SymbolDrawingKt*",
                    "io.github.b150005.knitnote.ui.chart.PolarDrawingKt*",
                    "io.github.b150005.knitnote.ui.platform.*",
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
