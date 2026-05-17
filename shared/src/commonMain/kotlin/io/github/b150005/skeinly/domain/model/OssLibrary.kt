package io.github.b150005.skeinly.domain.model

/**
 * Pre-Phase-40 A33 — one open-source dependency entry for the in-app
 * "Open Source Licenses" attribution screen.
 *
 * This is the flattened UI model the shared `OssLibraryParser` produces
 * from the build-time-generated `aboutlibraries.json` (via
 * `aboutlibraries-core`'s `Libs.Builder`). It is deliberately small and
 * free of nested collections so it bridges cleanly across the Kotlin/iOS
 * framework boundary — the SwiftUI `OssLicensesView` consumes the exact
 * same list the Compose `OssLicensesScreen` renders (platform parity; no
 * Compose-on-iOS, no `aboutlibraries-compose-m3`).
 *
 * @property uniqueId the Maven coordinate (`group:artifact`) —
 *  AboutLibraries de-duplicates by this (`DuplicateMode.MERGE`), so it
 *  is a stable, unique key for `LazyColumn` items / SwiftUI `ForEach`
 *  (vs a fragile positional index). Not shown in the UI.
 * @property name display name (falls back to [uniqueId] when the POM
 *  supplies no `<name>`).
 * @property version resolved artifact version, or null for
 *  BOM-/platform-managed dependencies that pin no explicit version.
 * @property license joined SPDX ids (or license names when no SPDX id is
 *  present), e.g. `"Apache-2.0"` or `"Apache-2.0 / MIT"`; null when the
 *  POM declares no license.
 * @property licenseUrl the first declared license's URL (the tappable
 *  link target), or null.
 * @property url project website, falling back to the SCM URL, or null.
 */
data class OssLibrary(
    val uniqueId: String,
    val name: String,
    val version: String?,
    val license: String?,
    val licenseUrl: String?,
    val url: String?,
)
