@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val versionProps =
    Properties().also { props ->
        val versionFile = rootProject.file("version.properties")
        if (versionFile.exists()) props.load(FileInputStream(versionFile))
    }

val localProps =
    Properties().also { props ->
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) props.load(FileInputStream(localFile))
    }

android {
    namespace = "io.github.b150005.knitnote"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.b150005.knitnote"
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
        resourcePackage.set("io.github.b150005.knitnote.generated.resources")
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
    debugImplementation(compose.uiTooling)

    // Android UI Testing
    androidTestImplementation(compose.uiTest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
