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
                        stringsObj.keys.toSortedSet()
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
