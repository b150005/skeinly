package io.github.b150005.skeinly.data.oss

import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import io.github.b150005.skeinly.domain.model.OssLibrary

/**
 * Pre-Phase-40 A33 — turns the build-time-generated `aboutlibraries.json`
 * (emitted by the `com.mikepenz.aboutlibraries.plugin` Gradle plugin into
 * `commonMain/composeResources/files/`) into the flat [OssLibrary] list
 * the OSS-licenses screen renders.
 *
 * Split into two pieces so the mapping logic is unit-testable WITHOUT
 * coupling the test to AboutLibraries' on-disk JSON schema (which is the
 * library's contract, not ours):
 * - [parseOssLibraries] is the thin production entry point — it delegates
 *   JSON decoding to `aboutlibraries-core`'s own (already-tested)
 *   `Libs.Builder().withJson(...)` and then maps the result.
 * - [toOssLibraries] is the pure mapping + ordering this project owns;
 *   commonTest exercises it directly against hand-built [Library]
 *   instances.
 */
fun parseOssLibraries(json: String): List<OssLibrary> =
    Libs
        .Builder()
        .withJson(json)
        .build()
        .libraries
        .toOssLibraries()

/**
 * Maps AboutLibraries' rich [Library] entities to the flat [OssLibrary]
 * UI model and sorts case-insensitively by display name (a stable sort,
 * so equal names keep their input order).
 *
 * Mapping rules:
 * - **name**: the POM `<name>`, falling back to the Maven coordinate
 *   (`uniqueId`) when blank — some libraries publish no display name.
 * - **license**: every declared license's SPDX id (or its name when no
 *   SPDX id), de-duplicated and joined with `" / "` so dual-licensed
 *   dependencies (e.g. Sentry `Apache-2.0 / MIT`) are not silently
 *   truncated to the first license.
 * - **licenseUrl**: the first license that declares a non-blank URL —
 *   the single tappable link target the row exposes.
 * - **url**: project website, falling back to the SCM URL.
 *
 * Blank strings are normalised to null so the UI never renders an empty
 * link or an empty license chip.
 */
internal fun List<Library>.toOssLibraries(): List<OssLibrary> =
    map { lib ->
        OssLibrary(
            uniqueId = lib.uniqueId,
            name = lib.name.ifBlank { lib.uniqueId },
            version = lib.artifactVersion?.ifBlank { null },
            license =
                lib.licenses
                    .mapNotNull { license ->
                        license.spdxId?.ifBlank { null } ?: license.name.ifBlank { null }
                    }.distinct()
                    .joinToString(" / ")
                    .ifBlank { null },
            // `Library.licenses` is a `Set<License>`, but `Libs.Builder`
            // decodes it via kotlinx.serialization into a `LinkedHashSet`
            // whose iteration order is the JSON insertion order. Since
            // the committed `aboutlibraries.json` is the sole, fixed data
            // source, "the first license that declares a URL" is
            // deterministic. (If the upstream ever switches to an
            // unordered `HashSet`, revisit — pick by SPDX precedence.)
            licenseUrl =
                lib.licenses
                    .firstOrNull { !it.url.isNullOrBlank() }
                    ?.url,
            url =
                lib.website?.ifBlank { null }
                    ?: lib.scm?.url?.ifBlank { null },
        )
    }.sortedBy { it.name.lowercase() }
