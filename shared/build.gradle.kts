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
        System.getenv("SUPABASE_PUBLISHABLE_KEY")?.let { props.setProperty("SUPABASE_PUBLISHABLE_KEY", it) }
        // Phase 41.3 (ADR-016 §6 §41.3): RevenueCat Public Android SDK Key.
        // Vendor-setup §20 / GitHub Secret REVENUECAT_API_KEY_ANDROID.
        // Empty fallback ⇒ RevenueCatConfig.isConfigured = false ⇒
        // RevenueCatBootstrap.configure short-circuits with no SDK init.
        System
            .getenv("REVENUECAT_API_KEY_ANDROID")
            ?.let { props.setProperty("REVENUECAT_API_KEY_ANDROID", it) }
    }

val generateSupabaseConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/supabaseConfig")
    val url = localProps.getProperty("SUPABASE_URL", "")
    val key = localProps.getProperty("SUPABASE_PUBLISHABLE_KEY", "")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("io/github/b150005/skeinly/config")
        dir.mkdirs()
        val escapedUrl = url.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        val escapedKey = key.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        dir.resolve("SupabaseCredentials.kt").writeText(
            buildString {
                appendLine("package io.github.b150005.skeinly.config")
                appendLine()
                appendLine("internal object SupabaseCredentials {")
                appendLine("    const val URL: String = \"$escapedUrl\"")
                appendLine("    const val PUBLISHABLE_KEY: String = \"$escapedKey\"")
                appendLine("}")
            },
        )
    }
}

// Phase 41.3 (ADR-016 §6 §41.3): RevenueCat Public Android SDK Key
// codegen — same shape as `generateSupabaseConfig`. The KMP shared
// android source set cannot read `BuildConfig` (the AGP 9.x KMP plugin
// does not expose `buildFeatures { buildConfig = true }`), so we
// generate a const object instead. Empty key ⇒ no Purchases.configure
// call at runtime.
val generateRevenueCatConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/revenueCatConfig")
    val key = localProps.getProperty("REVENUECAT_API_KEY_ANDROID", "")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("io/github/b150005/skeinly/config")
        dir.mkdirs()
        val escapedKey = key.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        dir.resolve("RevenueCatCredentials.kt").writeText(
            buildString {
                appendLine("package io.github.b150005.skeinly.config")
                appendLine()
                appendLine("internal object RevenueCatCredentials {")
                appendLine("    const val API_KEY: String = \"$escapedKey\"")
                appendLine("}")
            },
        )
    }
}

// Phase 39.2: derive `BuildFlags.isBeta` for shared/androidMain from
// `version.properties` `VERSION_NAME`. Beta builds carry semver
// `0.X.Y` (major == 0 = pre-stable API); v1.0 GA bumps the major
// component to 1 (per agent-team deliberation 2026-05-05; see
// `version.properties` header for the full rationale). Codegen pattern
// mirrors `generateSupabaseConfig` because the AGP 9.x KMP plugin
// (`com.android.kotlin.multiplatform.library`) does not expose the
// standard `buildFeatures { buildConfig = true }` knob to shared android
// source sets, so `BuildConfig.IS_BETA` is unavailable here. The iOS
// actual reads from Info.plist at runtime — see `BuildFlags.ios.kt` and
// `iosApp/project.yml`'s `IS_BETA` setting.
//
// Defensive default: malformed/missing VERSION_NAME → major defaults to
// 0 → isBeta = true. Failing safe (treat unknown as beta) avoids
// accidentally shipping a "production" binary if a parser regression
// or upstream config error blanks the version string.
val versionProps =
    Properties().also { props ->
        val versionFile = rootProject.file("version.properties")
        if (versionFile.exists()) props.load(FileInputStream(versionFile))
    }

val generateBuildFlagsAndroid by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildFlags")
    val versionName = versionProps.getProperty("VERSION_NAME", "")
    val major = versionName.substringBefore('.').toIntOrNull() ?: 0
    val isBeta = major == 0
    outputs.dir(outputDir)
    inputs.property("isBeta", isBeta)
    // Phase 39 (W4 / 2026-05-11) — emit versionName alongside isBeta so the
    // force-update gate's commonMain semver comparison can read the current
    // app version without an extra `expect/actual` plumbing layer.
    inputs.property("versionName", versionName)
    doLast {
        val dir = outputDir.get().asFile.resolve("io/github/b150005/skeinly/config")
        dir.mkdirs()
        dir.resolve("BuildFlags.android.kt").writeText(
            buildString {
                appendLine("package io.github.b150005.skeinly.config")
                appendLine()
                appendLine("import io.github.b150005.skeinly.domain.model.AppPlatform")
                appendLine()
                appendLine("/** Generated by `generateBuildFlagsAndroid` from version.properties. */")
                appendLine("actual object BuildFlags {")
                appendLine("    actual val isBeta: Boolean = $isBeta")
                appendLine("    actual val versionName: String = \"$versionName\"")
                appendLine("    actual val platform: AppPlatform = AppPlatform.ANDROID")
                appendLine("}")
            },
        )
    }
}

kotlin {
    applyDefaultHierarchyTemplate()

    compilerOptions {
        // Suppress KT-61573 Beta warning for expect/actual classes — the
        // feature is stable in practice and our KMP architecture relies on
        // expect/actual class hierarchies (DriverFactory, ConnectivityMonitor,
        // SupabaseConfig, ImagePicker). Tracked upstream for stabilization.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "io.github.b150005.skeinly.shared"
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
            implementation(libs.supabase.functions)
            // Image loading
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            // Preferences
            implementation(libs.multiplatform.settings)
            // RevenueCat KMP (Phase 41.3) — subscription / IAP orchestration.
            // `core` carries Purchases.sharedInstance + the offerings/purchase
            // surface; `result` adds suspend Result<T> extensions used by
            // PaywallViewModel for await-style call sites that surface
            // structured errors instead of callbacks. The `RevenueCatService`
            // domain interface wraps these so tests can inject a fake.
            // iOS app additionally links PurchasesHybridCommon via SwiftPM
            // (see iosApp/project.yml) — required at runtime for the iOS
            // actual binding to resolve native StoreKit symbols.
            implementation(libs.purchases.kmp.core)
            implementation(libs.purchases.kmp.result)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            // Phase 24.2 — Settings interface visibility for
            // InMemorySettings (commonTest helper). multiplatform-settings is
            // declared `implementation` in commonMain, which does not propagate
            // to commonTest by default; explicit re-declaration is required so
            // tests can implement the Settings interface directly without
            // pulling in the multiplatform-settings-test artifact (which the
            // project does not currently consume).
            implementation(libs.multiplatform.settings)
            // Phase 39 W5b — Ktor MockEngine for BugReportProxyClient tests.
            implementation(libs.ktor.client.mock)
        }
        androidMain {
            kotlin.srcDir(
                generateSupabaseConfig.flatMap {
                    layout.buildDirectory.dir("generated/supabaseConfig")
                },
            )
            kotlin.srcDir(
                generateBuildFlagsAndroid.flatMap {
                    layout.buildDirectory.dir("generated/buildFlags")
                },
            )
            kotlin.srcDir(
                generateRevenueCatConfig.flatMap {
                    layout.buildDirectory.dir("generated/revenueCatConfig")
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
                // Phase 24.2e (ADR-017 §3.5) — FCM token acquisition.
                // `PushTokenRegistrar.android.kt` calls
                // `FirebaseMessaging.getInstance().token.await()`. The
                // `kotlinx-coroutines-play-services` artifact provides
                // the `await()` extension on `Task<T>` (Google Play
                // Services Tasks API). `firebase-messaging` is pinned
                // directly (no BOM) because the KMP DSL deprecates
                // `platform(notation)` under Kotlin 2.3 (KT-58759).
                // Without these declarations the AGP `compileAndroidMain`
                // task fails because the shared module's classpath is
                // independent from `androidApp`'s.
                implementation(libs.firebase.messaging)
                implementation(libs.kotlinx.coroutines.play.services)
                // Pre-alpha A14 (HIGH security) — EncryptedSharedPreferences
                // for Supabase Auth session storage. PlatformModule.android.kt
                // wraps an `EncryptedSharedPreferences` instance in
                // `SharedPreferencesSettings` and registers it under the
                // Koin qualifier `named("auth")`, which SupabaseModule
                // passes to `SettingsSessionManager` so refresh + access
                // tokens are stored AES256-GCM encrypted with the key
                // managed by the Android Keystore via Tink. Replaces the
                // prior implicit default of unencrypted SharedPreferences
                // (`PreferenceManager.getDefaultSharedPreferences`).
                implementation(libs.androidx.security.crypto)
                // Phase 26.2 (ADR-022 §6.2) — Android Google Sign-In.
                // `OAuthClient.android.kt` lives in shared:androidMain
                // (Koin singleton; expect/actual seam from commonMain)
                // and imports `androidx.credentials.*` +
                // `com.google.android.libraries.identity.googleid.*`.
                // Both must live on the shared module's classpath, NOT
                // only on androidApp's, because the cross-module
                // resolution at the consumer end requires the type
                // hierarchy to be visible at compile time.
                implementation(libs.androidx.credentials)
                implementation(libs.androidx.credentials.play.services.auth)
                implementation(libs.google.identity.googleid)
                // Phase 26.6 (ADR-022 §6.5) — biometric re-auth + sensitive-
                // action gates. `BiometricAuthenticator.android.kt` calls
                // `BiometricPrompt.PromptInfo.Builder` against the host
                // FragmentActivity. `AppLifecycleObserver.android.kt` uses
                // `ProcessLifecycleOwner.get().lifecycle` to surface the
                // single Background→Foreground transition that drives
                // BiometricGuardian.requireForResume(). Both deps must live
                // on the shared module's classpath (not only androidApp's)
                // because the actual classes import the symbols directly.
                implementation(libs.androidx.biometric)
                implementation(libs.androidx.lifecycle.process)
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

// Ensure the generated SupabaseCredentials + BuildFlags sources are available
// before any task that consumes the androidMain source set. The flatMap-based
// srcDir wiring should carry the task dependency, but the AGP 9.x KMP plugin
// does not propagate it to compile or ktlint tasks on CI (Gradle 9.x
// implicit-dependency validation catches this).
tasks.configureEach {
    if (name.contains("AndroidMain", ignoreCase = true)) {
        dependsOn(generateSupabaseConfig)
        dependsOn(generateBuildFlagsAndroid)
        dependsOn(generateRevenueCatConfig)
    }
}

// Wire root-defined verifyI18nKeys + verifyIosBetaFlag tasks into :shared:check
// so the pre-push invariant chain (./gradlew :shared:check) automatically
// covers i18n key parity AND the iOS IS_BETA / Android BuildFlags.isBeta
// consistency check (Phase 40 GA prep — version-bump regression guard).
// Path-based references are configuration-cache safe — equivalent to the
// rootProject.tasks lookup but evaluated lazily.
tasks.named("check") {
    dependsOn(":verifyI18nKeys")
    dependsOn(":verifyIosBetaFlag")
}

sqldelight {
    databases {
        create("SkeinlyDatabase") {
            packageName.set("io.github.b150005.skeinly.db")
        }
    }
}

compose.resources {
    // Explicit package so common-module consumers get a stable import path
    // (`io.github.b150005.skeinly.generated.resources.Res`). Without this
    // the package is derived from the module name and can shift if we rename
    // `shared`. `publicResClass` exposes `Res` to platform modules that embed
    // the framework (androidApp, iosApp).
    publicResClass = true
    packageOfResClass = "io.github.b150005.skeinly.generated.resources"
    generateResClass = always
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // Generated / infra
                    "io.github.b150005.skeinly.db.*",
                    "io.github.b150005.skeinly.di.*",
                    "io.github.b150005.skeinly.ui.navigation.*",
                    "io.github.b150005.skeinly.config.*",
                    // Generated by org.jetbrains.compose.components.resources
                    // — the `Res` class and per-key accessors are static
                    // lookup plumbing with no behavior worth covering.
                    "io.github.b150005.skeinly.generated.resources.*",
                    // Remote data sources — thin Supabase SDK wrappers, untestable without MockEngine.
                    // Security validation (auth, input sanitization, size limits) is covered at the UseCase layer.
                    "io.github.b150005.skeinly.data.remote.Remote*DataSource",
                    "io.github.b150005.skeinly.data.remote.SupabaseConfig",
                    "io.github.b150005.skeinly.data.remote.SupabaseConfigKt",
                    "io.github.b150005.skeinly.data.remote.ConnectivityMonitor",
                    // Compose UI — untestable on JVM (covered by Android instrumented tests)
                    "*.ComposableSingletons\$*",
                    "io.github.b150005.skeinly.ui.chartviewer.*",
                    "io.github.b150005.skeinly.ui.imagepicker.*",
                    "io.github.b150005.skeinly.ui.activityfeed.ActivityFeedScreenKt*",
                    "io.github.b150005.skeinly.ui.auth.LoginScreenKt*",
                    "io.github.b150005.skeinly.ui.discovery.DiscoveryScreenKt*",
                    "io.github.b150005.skeinly.ui.comments.CommentSectionKt*",
                    "io.github.b150005.skeinly.ui.profile.ProfileScreenKt*",
                    "io.github.b150005.skeinly.ui.projectdetail.ProjectDetailScreenKt*",
                    "io.github.b150005.skeinly.ui.projectdetail.ShareLinkDialogKt*",
                    "io.github.b150005.skeinly.ui.projectdetail.UserPickerDialogKt*",
                    "io.github.b150005.skeinly.ui.projectlist.CreateProjectDialogKt*",
                    "io.github.b150005.skeinly.ui.projectlist.ProjectListScreenKt*",
                    "io.github.b150005.skeinly.ui.sharedcontent.SharedContentScreenKt*",
                    "io.github.b150005.skeinly.ui.sharedwithme.SharedWithMeScreenKt*",
                    "io.github.b150005.skeinly.ui.patternlibrary.PatternLibraryScreenKt*",
                    "io.github.b150005.skeinly.ui.patternedit.PatternEditScreenKt*",
                    "io.github.b150005.skeinly.ui.settings.SettingsScreenKt*",
                    "io.github.b150005.skeinly.ui.onboarding.OnboardingScreenKt*",
                    "io.github.b150005.skeinly.ui.components.EmptyStateViewKt*",
                    "io.github.b150005.skeinly.ui.symbol.SymbolGalleryScreenKt*",
                    "io.github.b150005.skeinly.ui.chart.ChartViewerScreenKt*",
                    "io.github.b150005.skeinly.ui.chart.ChartEditorScreenKt*",
                    "io.github.b150005.skeinly.ui.chart.ChartHistoryScreenKt*",
                    "io.github.b150005.skeinly.ui.chart.ChartComparisonScreenKt*",
                    "io.github.b150005.skeinly.ui.chart.ChartVariationPickerSheetKt*",
                    "io.github.b150005.skeinly.ui.chart.SymbolDrawingKt*",
                    "io.github.b150005.skeinly.ui.chart.PolarDrawingKt*",
                    "io.github.b150005.skeinly.ui.chart.ChartThumbnailKt*",
                    "io.github.b150005.skeinly.ui.pullrequest.SuggestionListScreenKt*",
                    "io.github.b150005.skeinly.ui.pullrequest.SuggestionDetailScreenKt*",
                    "io.github.b150005.skeinly.ui.pullrequest.ChartConflictResolutionScreenKt*",
                    "io.github.b150005.skeinly.ui.paywall.PaywallScreenKt*",
                    "io.github.b150005.skeinly.ui.packmanagement.PackManagementScreenKt*",
                    // Phase 26.5 (ADR-022 §6.4) — MFA Compose screens.
                    // Untestable on JVM (same rationale as other
                    // *ScreenKt excludes). The driving ViewModels +
                    // repository methods are exercised via commonTest.
                    "io.github.b150005.skeinly.ui.auth.MfaEnrollmentScreenKt*",
                    "io.github.b150005.skeinly.ui.auth.MfaChallengeScreenKt*",
                    // Phase 26.5 (ADR-022 §6.4) — AuthRepositoryImpl
                    // companion's `generateRecoveryCode` is a private
                    // helper that exercises platform RNG; the broader
                    // class is covered via null-client paths but the
                    // companion lambda surface + the observeAuthState/
                    // observeMfaStatus combine-flow inner classes
                    // aren't directly reachable from commonTest without
                    // a supabase-kt integration harness. Excluding the
                    // companion + the synthetic inner classes (NOT the
                    // outer class) preserves coverage signal on the
                    // public AuthRepository surface.
                    "io.github.b150005.skeinly.data.repository.AuthRepositoryImpl\$Companion",
                    "io.github.b150005.skeinly.data.repository.AuthRepositoryImpl\$observeAuthState\$*",
                    "io.github.b150005.skeinly.data.repository.AuthRepositoryImpl\$observeMfaStatus\$*",
                    "io.github.b150005.skeinly.ui.platform.*",
                    // Phase 26.2 (ADR-022 §6.2) — `OAuthClient`
                    // platform actuals wrap Android Credential Manager
                    // / iOS GIDSignIn (Phase 26.3+), both untestable
                    // on JVM. The shared expect class and the
                    // AuthViewModel integration are exercised via
                    // the lambda-seam in `AuthViewModelTest`.
                    "io.github.b150005.skeinly.auth.OAuthClient",
                    // Phase 26.6 (ADR-022 §6.5) — biometric Compose
                    // screen untestable on JVM. The driving ViewModel
                    // + the BiometricGuardian are exercised via
                    // commonTest. The `BiometricAuthenticator`
                    // platform actuals (Android BiometricPrompt / iOS
                    // LAContext) similarly can't run on JVM; the
                    // shared expect class surfaces via lambda seams
                    // in BiometricGuardian + BiometricSettingsViewModel
                    // tests. AppLifecycleObserver actuals wrap
                    // ProcessLifecycleOwner / NSNotificationCenter —
                    // same JVM-untestable shape.
                    "io.github.b150005.skeinly.ui.biometric.BiometricSettingsScreenKt*",
                    "io.github.b150005.skeinly.biometric.BiometricAuthenticator*",
                    "io.github.b150005.skeinly.platform.AppLifecycleObserver*",
                    // The lifecycle bridge is a single launched
                    // collector behind a Koin singleton — uncovered
                    // by commonTest (no Koin setup) and structurally
                    // trivial (forward two enum branches to two
                    // Guardian methods). The Guardian's methods are
                    // exhaustively tested elsewhere.
                    "io.github.b150005.skeinly.biometric.BiometricLifecycleBridgeKt*",
                    // Phase 26.6 (ADR-022 §6.6) — post-OAuth profile setup
                    // gate. The Compose + SwiftUI screens are untestable on
                    // JVM (same rationale as other *ScreenKt excludes); the
                    // backing OAuthProfileSetupViewModel + OAuthOnboarding-
                    // Metadata invariants are exercised via commonTest.
                    // OAuthProfileSetupPreferencesImpl wraps multiplatform-
                    // settings under a Settings-backed bool — trivial pass-
                    // through covered by integration when the gate fires;
                    // dedicated unit coverage is low-signal.
                    "io.github.b150005.skeinly.ui.onboarding.OAuthProfileSetupScreenKt*",
                    "io.github.b150005.skeinly.data.preferences.OAuthProfileSetupPreferencesImpl",
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

// Phase 41.1.4: regenerate `symbol-packs/<pack_id>/<version>/payload.json`
// for the seed packs (`jis.knit.beginner` + `jis.crochet.beginner`) by
// running the always-on `SymbolPackPayloadGeneratorTest` test with the
// `skeinly.payloads.outputDir` system property set. The test asserts pack
// invariants on every CI run; setting the system property additionally
// emits the JSON files + prints the seed metadata SQL block to stdout.
//
// Output directory: `<projectRoot>/build/generated/symbol-pack-payloads/`.
//
// Manual upload follows (per ADR-016 §3.1, the bucket is private and
// payloads are mediated by the 41.1.5 Edge Function — no public-read
// path exists): use the Supabase Dashboard or `supabase storage cp` to
// place the files at the path matching the printed `payloadPath`. The
// printed seed SQL is then applied via `mcp__supabase__apply_migration`.
val generateSymbolPackPayloads by tasks.registering(Test::class) {
    description =
        "Regenerates seed symbol pack payload.json files + prints metadata SQL for upload."
    group = "skeinly content authoring"

    val testTaskProvider = tasks.named("testAndroidHostTest", Test::class)
    testClassesDirs = testTaskProvider.get().testClassesDirs
    classpath = testTaskProvider.get().classpath
    dependsOn("compileAndroidHostTest")

    filter {
        includeTestsMatching(
            "io.github.b150005.skeinly.tools.SymbolPackPayloadGeneratorTest.generatePayloadsAndSeedSql",
        )
    }
    systemProperty(
        "skeinly.payloads.outputDir",
        layout.buildDirectory
            .dir("generated/symbol-pack-payloads")
            .get()
            .asFile.absolutePath,
    )

    // Always re-run when invoked — the test side effect is the JSON file
    // emission, not a Gradle output we can declare.
    outputs.upToDateWhen { false }

    // Forward the test's stdout to the Gradle console so the manifest +
    // seed SQL block surface to the dev directly without digging into
    // build/reports/tests/.
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
