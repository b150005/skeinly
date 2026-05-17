package io.github.b150005.skeinly.data.oss

import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import com.mikepenz.aboutlibraries.entity.Scm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pre-Phase-40 A33 — locks the [toOssLibraries] mapping + ordering this
 * project owns (license join, website→scm fallback, blank normalisation,
 * stable case-insensitive sort).
 *
 * The JSON decode in [parseOssLibraries] is deliberately NOT re-tested
 * here: it delegates to `aboutlibraries-core`'s own (already-tested)
 * `Libs.Builder().withJson(...).build()`, and asserting against a
 * hand-crafted document would couple this suite to AboutLibraries'
 * internal JSON schema for zero signal about our code. The real
 * end-to-end check is `make ci-local` building both platforms against
 * the plugin-generated `aboutlibraries.json`.
 *
 * The upstream `Library` / `License` / `Scm` entities are imported
 * directly: `aboutlibraries-core` is an `implementation` dependency of
 * `commonMain`, which propagates onto `commonTest`'s compile classpath
 * through the KMP source-set hierarchy (no separate `commonTest`
 * dependency entry is needed — and adding one would be redundant).
 */
class OssLibraryParserTest {
    private fun library(
        uniqueId: String = "com.example:lib",
        artifactVersion: String? = "1.0.0",
        name: String = "Example",
        website: String? = "https://example.com",
        scm: Scm? = null,
        licenses: Set<License> = emptySet(),
    ): Library =
        Library(
            uniqueId = uniqueId,
            artifactVersion = artifactVersion,
            name = name,
            description = null,
            website = website,
            developers = emptyList(),
            organization = null,
            scm = scm,
            licenses = licenses,
        )

    private fun license(
        name: String,
        url: String? = null,
        spdxId: String? = null,
        hash: String = name,
    ): License = License(name = name, url = url, spdxId = spdxId, hash = hash)

    @Test
    fun `maps core fields and prefers spdxId for the license label`() {
        val out =
            listOf(
                library(
                    artifactVersion = "3.4.3",
                    name = "Ktor",
                    website = "https://ktor.io",
                    licenses =
                        setOf(
                            license(
                                name = "The Apache License, Version 2.0",
                                url = "https://apache.org/licenses/LICENSE-2.0",
                                spdxId = "Apache-2.0",
                            ),
                        ),
                ),
            ).toOssLibraries()

        assertEquals(1, out.size)
        val lib = out.first()
        assertEquals("com.example:lib", lib.uniqueId)
        assertEquals("Ktor", lib.name)
        assertEquals("3.4.3", lib.version)
        assertEquals("Apache-2.0", lib.license)
        assertEquals("https://apache.org/licenses/LICENSE-2.0", lib.licenseUrl)
        assertEquals("https://ktor.io", lib.url)
    }

    @Test
    fun `license falls back to name when spdxId is absent or blank`() {
        val out =
            listOf(
                library(licenses = setOf(license(name = "MIT License", spdxId = null))),
            ).toOssLibraries()
        assertEquals("MIT License", out.first().license)

        val blankSpdx =
            listOf(
                library(licenses = setOf(license(name = "Custom", spdxId = "  "))),
            ).toOssLibraries()
        assertEquals("Custom", blankSpdx.first().license)
    }

    @Test
    fun `dual licensed dependency joins distinct labels and is not truncated`() {
        val out =
            listOf(
                library(
                    name = "Sentry SDK",
                    licenses =
                        setOf(
                            license(name = "Apache 2", spdxId = "Apache-2.0", hash = "a"),
                            license(name = "MIT", spdxId = "MIT", hash = "b"),
                            // Duplicate SPDX must be de-duplicated, not repeated.
                            license(name = "Apache 2 again", spdxId = "Apache-2.0", hash = "c"),
                        ),
                ),
            ).toOssLibraries()
        assertEquals("Apache-2.0 / MIT", out.first().license)
    }

    @Test
    fun `licenseUrl is the first license that declares a non-blank url`() {
        val out =
            listOf(
                library(
                    licenses =
                        setOf(
                            license(name = "NoUrl", url = "  ", hash = "a"),
                            license(name = "HasUrl", url = "https://l2", hash = "b"),
                        ),
                ),
            ).toOssLibraries()
        assertEquals("https://l2", out.first().licenseUrl)
    }

    @Test
    fun `no licenses yields null license fields`() {
        val out = listOf(library(licenses = emptySet())).toOssLibraries()
        assertNull(out.first().license)
        assertNull(out.first().licenseUrl)
    }

    @Test
    fun `name falls back to uniqueId when blank and blank version becomes null`() {
        val out =
            listOf(
                library(uniqueId = "g:artifact", name = "   ", artifactVersion = "  "),
            ).toOssLibraries()
        assertEquals("g:artifact", out.first().uniqueId)
        assertEquals("g:artifact", out.first().name)
        assertNull(out.first().version)
    }

    @Test
    fun `url falls back to scm url when website is null or blank`() {
        val nullSite =
            listOf(
                library(website = null, scm = Scm(connection = null, developerConnection = null, url = "https://scm")),
            ).toOssLibraries()
        assertEquals("https://scm", nullSite.first().url)

        val blankSite =
            listOf(
                library(website = "  ", scm = Scm(connection = null, developerConnection = null, url = "https://scm2")),
            ).toOssLibraries()
        assertEquals("https://scm2", blankSite.first().url)

        val neither =
            listOf(library(website = null, scm = null)).toOssLibraries()
        assertNull(neither.first().url)
    }

    @Test
    fun `libraries are sorted case-insensitively by name and the sort is stable`() {
        val out =
            listOf(
                library(name = "zebra", artifactVersion = "9"),
                library(name = "Apple", artifactVersion = "1"),
                // Two equal names with distinct versions — a stable sort
                // keeps their input order (v2 before v3).
                library(name = "banana", artifactVersion = "2"),
                library(name = "banana", artifactVersion = "3"),
                library(name = "apple", artifactVersion = "5"),
            ).toOssLibraries()

        assertEquals(listOf("Apple", "apple", "banana", "banana", "zebra"), out.map { it.name })
        // Stability witness: the two equal-named "banana" rows must
        // retain input order, so their versions appear as 2 then 3.
        assertEquals(listOf("2", "3"), out.filter { it.name == "banana" }.map { it.version })
    }
}
