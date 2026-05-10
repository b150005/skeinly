@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.playPublisher)
    // Phase 24.2e (ADR-017 §3.5) — Firebase Gradle plugin declared but
    // NOT applied here. Auto-applied below when a `google-services.json`
    // exists in any variant slot. This guards against:
    //   - Local dev builds without the secret decoded (the plugin
    //     hard-fails configuration if the file is missing).
    //   - CI builds where the `FIREBASE_GOOGLE_SERVICES_JSON_BASE64`
    //     secret is not configured (e.g. external fork PRs without
    //     access to GitHub Environment secrets).
    // When the plugin is absent, `firebase-messaging` is still on the
    // classpath but `FirebaseApp` is not initialized at process boot;
    // `FirebaseMessaging.getInstance().token.await()` throws which
    // `PushTokenRegistrar.android.kt`'s catch handler maps to a null
    // return (graceful local-only mode).
    alias(libs.plugins.googleServices) apply false
}

val versionProps =
    Properties().also { props ->
        val versionFile = rootProject.file("version.properties")
        if (versionFile.exists()) props.load(FileInputStream(versionFile))
    }

// Phase 24.2e (ADR-017 §3.5) — apply the google-services plugin only
// when a config file is present. See `plugins {}` block above for the
// rationale. The check covers all 3 possible slots (debug variant
// override, release variant override, project-level fallback) so any
// of the three layouts works without extra wiring.
val googleServicesConfigPresent =
    listOf(
        file("src/debug/google-services.json"),
        file("src/release/google-services.json"),
        file("google-services.json"),
    ).any { it.exists() }

if (googleServicesConfigPresent) {
    apply(plugin = "com.google.gms.google-services")
}

val localProps =
    Properties().also { props ->
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) props.load(FileInputStream(localFile))
    }

android {
    namespace = "io.github.b150005.skeinly"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.b150005.skeinly"
        minSdk = 26
        targetSdk = 36
        versionCode = versionProps.getProperty("VERSION_CODE", "1").toInt()
        versionName = versionProps.getProperty("VERSION_NAME", "0.1.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Sentry DSN (Phase F1) — read from local.properties, fall back to
        // env var (CI). Empty string means no Sentry init (local-only mode).
        val sentryDsn =
            localProps.getProperty("SENTRY_DSN_ANDROID")
                ?: System.getenv("SENTRY_DSN_ANDROID")
                ?: ""
        buildConfigField("String", "SENTRY_DSN_ANDROID", "\"$sentryDsn\"")

        // PostHog API key + host (Phase F2) — same precedence as Sentry. Empty
        // string means no PostHog init (local-only mode). Init also requires
        // the user to have toggled analytics ON in Settings.
        val posthogApiKey =
            localProps.getProperty("POSTHOG_API_KEY")
                ?: System.getenv("POSTHOG_API_KEY")
                ?: ""
        val posthogHost =
            localProps.getProperty("POSTHOG_HOST")
                ?: System.getenv("POSTHOG_HOST")
                ?: "https://us.i.posthog.com"
        buildConfigField("String", "POSTHOG_API_KEY", "\"$posthogApiKey\"")
        buildConfigField("String", "POSTHOG_HOST", "\"$posthogHost\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Opt into AGP's per-app locale support: AGP scans `values-*/` resource
    // directories and generates `xml/_generated_res_locale_config.xml`, then
    // auto-injects `android:localeConfig="@xml/_generated_res_locale_config"`
    // into the merged manifest. Without this, Android 13+'s LocaleManagerService
    // stores per-app locale values but the runtime Activity.Configuration does
    // not honor them — CMP `Res.string.*` and native `R.string.*` both fall back
    // to the system locale regardless of the user's Settings > App Info > Language
    // selection. `localeFilters` constrains generation to locales we actually ship.
    androidResources {
        generateLocaleConfig = true
        // `localeFilters` uses aapt2 resource-qualifier format (bare language
        // tag, or `<lang>-r<REGION>` for region-qualified), NOT BCP 47 —
        // `listOf("en-US", "ja-JP")` fails with "invalid config 'en-US'".
        // Keep these in sync with the `values-*/` directories we actually ship.
        // A future region-specific variant would be added as e.g. `"en-rGB"`.
        localeFilters += listOf("en", "ja")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("keystore.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = localProps.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = localProps.getProperty("KEY_ALIAS", "")
                keyPassword = localProps.getProperty("KEY_PASSWORD", "")
            } else {
                val ciKeystorePath = System.getenv("KEYSTORE_FILE")
                if (ciKeystorePath != null) {
                    storeFile = file(ciKeystorePath)
                    storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                    keyAlias = System.getenv("KEY_ALIAS") ?: ""
                    keyPassword = System.getenv("KEY_PASSWORD") ?: ""
                }
            }
        }
    }

    buildTypes {
        debug {
            // Phase 24.2 (ADR-017 §3.5) — full dev/prod separation between
            // the `Skeinly` (Blaze, prod) and `Skeinly-Dev` (Spark, debug) Firebase
            // projects. The `.dev` applicationIdSuffix changes the Android
            // package name to `io.github.b150005.skeinly.dev` for debug builds,
            // which:
            //   1. Allows debug + release builds to coexist on the same device
            //      (Android forbids two installed APKs with the same package).
            //   2. Matches the `Skeinly-Dev` Firebase project's registered
            //      Android client (package `io.github.b150005.skeinly.dev`),
            //      so `androidApp/src/debug/google-services.json` (decoded from
            //      the `development` GitHub Environment secret) wires through
            //      cleanly without "package mismatch" Gradle errors.
            //   3. Surfaces "0.1.0-dev" in the About row + system Settings,
            //      making it visually obvious which build variant the device
            //      is running. Avoids tester confusion during QA cycles.
            //
            // Maestro flows under `e2e/flows/android/` reference the runtime
            // appId via `${APP_ID}` env-var substitution; `e2e/run-android.sh`
            // exports `APP_ID=io.github.b150005.skeinly.dev` so all flows
            // target the debug variant uniformly.
            //
            // Play Console: deliberately NOT registering a Skeinly-Dev app on
            // Play Console — debug builds are local + CI Maestro only, never
            // distributed via Play. `gradle-play-publisher` only handles the
            // release variant (`publishBundleRelease`); skipping a Dev Play
            // listing avoids duplicate keystore management + tester list
            // maintenance + listing chrome. Reopen if internal-track tester
            // distribution of debug builds becomes a real ask.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Use release signing when keystore is available, otherwise fall back to debug
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig =
                if (releaseSigning.storeFile?.exists() == true) {
                    releaseSigning
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The launcher icons are intentional fill-the-square brand artwork,
        // not the silhouetted Material Design recommendation. Adaptive-icon
        // adoption (mipmap-anydpi-v26) is a brand-asset deliverable, not an
        // engineering one — disabling the rule here keeps the warning floor
        // at zero without hiding the real design follow-up.
        disable += "IconLauncherShape"
    }
}

// Compose Multiplatform resources workaround for AGP 9.x KMP library plugin.
// The `com.android.kotlin.multiplatform.library` plugin (used in :shared) does
// not emit `assembledResources/androidMain/…` for the CMP resource generator,
// so `composeResources/<package>/…` is never packaged into the APK and
// `stringResource(Res.string.*)` throws MissingResourceException at runtime on
// Android. Until upstream integration lands (JetBrains upstream gap), we
// relocate :shared's `preparedResources/commonMain/composeResources/values*`
// into each Android variant's assets via the AGP Variant API.
@CacheableTask
abstract class CopyComposeResourcesForAndroid : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:Input
    abstract val resourcePackage: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        // Full delete + copy: if a key is removed from strings.xml, the stale
        // CVR must disappear from the APK too. Relying on copyRecursively
        // overwrite alone would leave orphaned files behind.
        out.deleteRecursively()
        val dest = out.resolve("composeResources/${resourcePackage.get()}")
        dest.mkdirs()
        sourceDir.get().asFile.copyRecursively(dest, overwrite = true)
    }
}

val copyComposeResourcesForAndroid =
    tasks.register<CopyComposeResourcesForAndroid>("copyComposeResourcesForAndroid") {
        dependsOn(":shared:prepareComposeResourcesTaskForCommonMain")
        // Anchor to rootProject rather than this module's projectDirectory so
        // that reorganizing `:androidApp`'s path does not silently break the
        // relocation.
        sourceDir.set(
            rootProject.layout.projectDirectory
                .dir("shared/build/generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"),
        )
        resourcePackage.set("io.github.b150005.skeinly.generated.resources")
        outputDir.set(layout.buildDirectory.dir("generated/composeResourcesForAndroid"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyComposeResourcesForAndroid,
            CopyComposeResourcesForAndroid::outputDir,
        )
        // Instrumented tests have their own asset pipeline — mirror the wiring
        // so any UI test rendering a Compose screen with Res.string.* works.
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(
            copyComposeResourcesForAndroid,
            CopyComposeResourcesForAndroid::outputDir,
        )
    }
}

dependencies {
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.sentry.android)
    implementation(libs.posthog.android)
    implementation(projects.shared)
    // Phase 24.2e (ADR-017 §3.5) — `firebase-messaging` and
    // `kotlinx-coroutines-play-services` are declared on the shared
    // module's androidMain dependencies (where `PushTokenRegistrar.android.kt`
    // imports them). The shared module's runtime classpath propagates to
    // the assembled APK, so the SDK is on the final binary. The
    // google-services Gradle plugin still applies HERE (the application
    // module) because it emits Android string resources from
    // google-services.json that firebase-messaging reads at process
    // boot. See plugins {} block above for the apply-conditional logic.
    //
    // Phase 24.5 (ADR-017 §3.8) — `firebase-messaging` declared again
    // here as a *direct* compile dep so `SkeinlyMessagingService` can
    // subclass `FirebaseMessagingService` from the androidApp module
    // (shared's `implementation` scope does NOT propagate the API to
    // app-module call sites). androidx.core supplies
    // NotificationCompat / NotificationManagerCompat / ContextCompat
    // for the foreground notification builder.
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.core.ktx)
    debugImplementation(compose.uiTooling)

    // Android UI Testing
    androidTestImplementation(compose.uiTest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

// Phase 39 closed beta — Google Play Internal track auto-publishing.
//
// Plugin: gradle-play-publisher 4.0.0 (Triple-T/gradle-play-publisher).
// Triggered by `release.yml` on tag push via
// `./gradlew :androidApp:publishBundleInternal`.
//
// Authentication: the plugin reads from the `ANDROID_PUBLISHER_CREDENTIALS`
// env var when `serviceAccountCredentials` is unset (per upstream README's
// CI guidance). `release.yml` decodes `GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64`
// (production-Environment-scoped GitHub secret) and exports
// ANDROID_PUBLISHER_CREDENTIALS for the gradle invocation. Without the
// secret the plugin would fail at task execution time, but the plugin
// itself loads at configuration time without credentials, so local-dev
// + non-tag CI runs are unaffected.
//
// Why COMPLETED release status (full auto-distribution to Internal
// testers on tag push): for closed beta we want the user's only
// post-tag-push role to be observing tester feedback — no manual
// "Send to testers" click in Play Console. Pivoted from DRAFT on
// 2026-05-10 once the wave of pre-tag-push verification (CI hardening,
// version-code reset, README cleanup, JDK upgrade) settled, exposing
// the manual gate as the last source of unattended-release friction.
//
// The previous DRAFT setting parked AABs as "Draft" so the user
// manually clicked "Send to testers" before any tester saw the build.
// At ≤10 closed beta testers who already opted in, the manual gate's
// value (a ~30-second review window before tester visibility) is
// outweighed by the loss of full automation: the user has to interrupt
// other work for every tag push.
//
// Defense in depth (preserves "no accidental production rollout"):
// the `google-play-publisher@...` Service Account's Play Console
// permissions are scoped to "Release to testing tracks" only.
// Production rollout is structurally impossible regardless of
// releaseStatus value here. The `track = "internal"` line and the
// SA permission boundary are both load-bearing — neither alone
// suffices.
//
// Trade-off accepted: with COMPLETED, a stray tag push or accidental
// `git push --tags` directly distributes a build to Internal testers.
// Mitigations: (a) `make release-tag-validate` + `release-tag-publish`
// pre-flight rejects unclean state, (b) `CONFIRM=yes` env var gates
// the actual tag command, (c) Internal testers are humans who can
// surface "huh, that's not a real release" through the bug-report
// channel, (d) version_code is mandatorily strictly increasing so a
// re-pushed tag cannot collide.
play {
    track.set("internal")
    defaultToAppBundles.set(true)
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED)
}
