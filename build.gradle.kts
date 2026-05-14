plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover) apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter {
            exclude { element ->
                val path = element.file.path
                path.contains("/build/") || path.contains("${File.separator}build${File.separator}")
            }
            exclude("**/build/**")
        }
    }
}

// Verifies that the 3 user-facing i18n source files share the same key set:
//   - shared/src/commonMain/composeResources/values/strings.xml     (English, shared Compose Res.string.*)
//   - shared/src/commonMain/composeResources/values-ja/strings.xml  (Japanese, shared Compose Res.string.*)
//   - iosApp/iosApp/Resources/Localizable.xcstrings                 (both locales, iOS SwiftUI)
//
// Pivot label is "shared composeResources values" (English shared). Drift
// between any two of the three sources fails the build.
//
// `androidApp/src/main/res/values/strings.xml` is intentionally not part of
// the parity set — it now holds only Android-platform-specific resources
// (`@string/app_name` referenced from AndroidManifest), not user-facing
// translations. All Compose UI consumes `Res.string.*` / `Res.plurals.*`
// from the shared composeResources, so the previous five-file mirror was
// dead duplication that lint flagged as `UnusedResources`.
//
// Wired into :shared:check so pre-push `./gradlew :shared:check` covers
// i18n parity automatically. Replaces the previous scripts/verify-i18n-keys.sh
// bash invocation; the regex extraction logic mirrors the bash version exactly.
val verifyI18nKeys by tasks.registering {
    description = "Verifies i18n key parity across shared composeResources (en/ja) and iosApp .xcstrings."
    group = "verification"

    val sharedEn = rootProject.file("shared/src/commonMain/composeResources/values/strings.xml")
    val sharedJa = rootProject.file("shared/src/commonMain/composeResources/values-ja/strings.xml")
    val iosCatalog = rootProject.file("iosApp/iosApp/Resources/Localizable.xcstrings")

    val sources =
        linkedMapOf(
            "shared composeResources values" to sharedEn,
            "shared composeResources values-ja" to sharedJa,
            "iosApp xcstrings" to iosCatalog,
        )

    inputs.files(sources.values)
    val markerFile = layout.buildDirectory.file("verify-i18n-keys/verified.marker")
    outputs.file(markerFile)

    // iOS-only xcstrings entries that Xcode 26 auto-extracts from SwiftUI Text(...)
    // literals at build time. These intentionally have no shared composeResources
    // counterpart and would otherwise drift the parity check on every clean build.
    //
    // Two sub-categories:
    //   (a) System / format / glyph literals — no translation needed (placeholder
    //       templates, SwiftUI runtime-supplied NavigationBar back button, etc.).
    //   (b) SwiftUI Text(...) literals that DO have semantic equivalents in shared
    //       composeResources (e.g. "Crochet" should be Res.string.mode_crochet) —
    //       tracked as Tech Debt for migration in CLAUDE.md "Phase 40 GA release prep".
    //
    // Allow list is applied to the xcstrings key set BEFORE drift comparison so a
    // freshly Xcode-canonicalized .xcstrings does not block CI on every iOS rebuild.
    val xcstringsAllowedOrphanKeys =
        setOf(
            // (a) System / format / glyph
            "%@",
            "%@ / %@",
            "%@ · %@ · %@",
            "?",
            "Back",
            // (b) SwiftUI Text(...) literal — Tech Debt: migrate to Res.string semantic keys
            "Crochet",
            "Crochet flat (L→R)",
            "Failed to load image",
            "Knit",
            "Knit flat (RS →, WS ←)",
            "Round (center out)",
            "You have unsaved changes. Discard them?",
        )

    doLast {
        // XML: extracts every name="..." attribute regardless of position on the line.
        val xmlKeyRegex = Regex("""name="([^"]+)"""")

        val keysByLabel =
            sources.mapValues { (label, file) ->
                check(file.exists()) { "Missing i18n source for '$label': $file" }
                val text = file.readText()
                @Suppress("UNCHECKED_CAST")
                val keys =
                    if (file.extension == "xcstrings") {
                        // Robust JSON parsing via Groovy's JsonSlurper (bundled with Gradle).
                        // The bash version preferred jq + regex fallback; the regex fallback
                        // mistakenly extracted the top-level "strings" key. Routing through a
                        // real JSON parser eliminates that ambiguity and is structurally
                        // immune to indent changes by Xcode.
                        val json = groovy.json.JsonSlurper().parseText(text) as Map<String, Any?>
                        val stringsObj =
                            json["strings"] as? Map<String, Any?>
                                ?: error("xcstrings file missing top-level 'strings' object: $file")
                        // Subtract the allow list before parity check so iOS-only Xcode-extracted
                        // literals don't surface as drift.
                        (stringsObj.keys - xcstringsAllowedOrphanKeys).toSortedSet()
                    } else {
                        xmlKeyRegex.findAll(text).map { it.groupValues[1] }.toSortedSet()
                    }
                check(keys.isNotEmpty()) { "Extracted zero keys from '$label' — file format may have changed: $file" }
                keys
            }

        val canonicalLabel = "shared composeResources values"
        val canonical = keysByLabel.getValue(canonicalLabel)

        var hasDrift = false
        keysByLabel.forEach { (label, keys) ->
            if (label == canonicalLabel) return@forEach
            val onlyInCanonical = canonical - keys
            val onlyInOther = keys - canonical
            if (onlyInCanonical.isNotEmpty() || onlyInOther.isNotEmpty()) {
                hasDrift = true
                logger.error("DRIFT [$canonicalLabel vs $label]")
                if (onlyInCanonical.isNotEmpty()) {
                    logger.error("  keys only in $canonicalLabel:")
                    onlyInCanonical.forEach { logger.error("    $it") }
                }
                if (onlyInOther.isNotEmpty()) {
                    logger.error("  keys only in $label:")
                    onlyInOther.forEach { logger.error("    $it") }
                }
            }
        }

        if (hasDrift) {
            throw GradleException(
                "i18n key drift detected. Add or remove the missing keys in all three files.",
            )
        }

        markerFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("OK ${canonical.size} keys\n")
        }
        logger.lifecycle(
            "OK: ${canonical.size} keys synchronized across shared composeResources (en/ja) " +
                "and iOS String Catalog.",
        )
    }
}

// Verifies the iOS `IS_BETA` xcconfig flag stays consistent with the Android
// codegen rule `BuildFlags.isBeta = (major == 0)` driven by `version.properties`.
//
// Why this is needed: Android derives `BuildFlags.isBeta` automatically from
// `version.properties` `VERSION_NAME`'s major component (== 0 ⇒ beta) via the
// `generateBuildFlagsAndroid` codegen task in `shared/build.gradle.kts`. iOS
// has no equivalent codegen path — `iosApp/project.yml`'s `settings.base`
// hardcodes `IS_BETA: "YES"` (or `"NO"`), which xcodegen evaluates at project
// generation time and threads through Info.plist's `IsBetaBuild` key. A Phase
// 40 GA bump that flips `VERSION_NAME` from `0.X.Y` to `1.0.0` without also
// editing `iosApp/project.yml` would ship a production binary that behaves
// as a beta build (PostHog initializes, bug-reporter gesture active). The
// inverse mismatch (Android pre-stable + iOS production-flagged) is also a
// regression.
//
// Rule: `(major == 0) <=> (IS_BETA == "YES")`. Mismatch fails the build.
//
// Wired into :shared:check via the same `dependsOn` hook as `verifyI18nKeys`
// in `shared/build.gradle.kts`, so pre-push `make ci-local` and CI's
// `shared-checks` step both cover it.
val verifyIosBetaFlag by tasks.registering {
    description = "Verifies iOS IS_BETA xcconfig flag matches Android BuildFlags.isBeta codegen rule (major==0 <=> IS_BETA=YES)."
    group = "verification"

    val versionPropsFile = rootProject.file("version.properties")
    val projectYmlFile = rootProject.file("iosApp/project.yml")

    inputs.files(versionPropsFile, projectYmlFile)
    val markerFile = layout.buildDirectory.file("verify-ios-beta-flag/verified.marker")
    outputs.file(markerFile)

    doLast {
        check(versionPropsFile.exists()) { "Missing version.properties at $versionPropsFile" }
        check(projectYmlFile.exists()) { "Missing iosApp/project.yml at $projectYmlFile" }

        val versionNameRegex = Regex("""^\s*VERSION_NAME\s*=\s*(\S+)\s*$""", RegexOption.MULTILINE)
        val versionName =
            versionNameRegex.find(versionPropsFile.readText())?.groupValues?.get(1)
                ?: error("VERSION_NAME not found in version.properties")

        val majorComponent =
            versionName.substringBefore('.').toIntOrNull()
                ?: error("Could not parse major component from VERSION_NAME='$versionName'")

        // Match `IS_BETA: "YES"` or `IS_BETA: "NO"` (with optional whitespace).
        // The single-quoted form is also accepted defensively against future
        // xcodegen YAML style changes.
        val isBetaRegex = Regex("""^\s*IS_BETA\s*:\s*["']?(YES|NO)["']?\s*$""", RegexOption.MULTILINE)
        val isBetaValue =
            isBetaRegex.find(projectYmlFile.readText())?.groupValues?.get(1)
                ?: error("IS_BETA not found in iosApp/project.yml — expected `IS_BETA: \"YES\"` or `IS_BETA: \"NO\"` in settings.base")

        val androidIsBeta = majorComponent == 0
        val iosIsBeta = isBetaValue == "YES"

        if (androidIsBeta != iosIsBeta) {
            val androidLabel = if (androidIsBeta) "BETA (major==0)" else "PRODUCTION (major>=1)"
            val iosLabel = if (iosIsBeta) "BETA (IS_BETA=YES)" else "PRODUCTION (IS_BETA=NO)"
            throw GradleException(
                buildString {
                    appendLine("iOS IS_BETA flag is out of sync with Android BuildFlags.isBeta codegen rule.")
                    appendLine("  Android (version.properties VERSION_NAME='$versionName' → major=$majorComponent): $androidLabel")
                    appendLine("  iOS (iosApp/project.yml IS_BETA='$isBetaValue'): $iosLabel")
                    appendLine()
                    appendLine("Fix one of:")
                    if (androidIsBeta) {
                        appendLine("  - Edit iosApp/project.yml settings.base: set `IS_BETA: \"YES\"` to match the pre-stable VERSION_NAME.")
                    } else {
                        appendLine("  - Edit iosApp/project.yml settings.base: set `IS_BETA: \"NO\"` to match the GA VERSION_NAME.")
                    }
                    appendLine("  - OR adjust version.properties VERSION_NAME so its major component matches the iOS flag.")
                    appendLine()
                    appendLine("Background: Android derives BuildFlags.isBeta automatically from version.properties via")
                    appendLine("generateBuildFlagsAndroid codegen. iOS has no equivalent — IS_BETA is a manual coupled edit")
                    appendLine("documented in version.properties' header. Phase 40 GA flips both: VERSION_NAME 0.X.Y → 1.0.0")
                    appendLine("AND IS_BETA YES → NO in the same commit.")
                },
            )
        }

        markerFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("OK VERSION_NAME=$versionName major=$majorComponent IS_BETA=$isBetaValue\n")
        }
        val mode = if (androidIsBeta) "BETA" else "PRODUCTION"
        logger.lifecycle(
            "OK: iOS IS_BETA matches Android BuildFlags.isBeta — both = $mode (VERSION_NAME=$versionName, IS_BETA=$isBetaValue).",
        )
    }
}
