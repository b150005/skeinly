package io.github.b150005.skeinly.config

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 39.2 (rev. 2026-05-05): smoke-tests the Android actual generated
 * by `generateBuildFlagsAndroid` from `version.properties`.
 *
 * Detection rule: `isBeta = (semver major == 0)`. Pre-1.0 closed beta
 * uses semver `0.X.Y`; v1.0 GA bumps the major to 1. Hyphen-suffixed
 * prerelease identifiers are deliberately NOT used (iOS
 * `CFBundleShortVersionString` rejects them). See the rationale block in
 * `version.properties` and `iosApp/project.yml`.
 *
 * Since the generated actual is a build-time constant, test stability
 * is tied to whatever `version.properties` says when the test compiles.
 * Today `VERSION_NAME=0.1.0` (major==0) so `isBeta == true` is the
 * load-bearing assertion. When v1.0.0 GA ships, this test must flip to
 * `assertFalse(BuildFlags.isBeta)` in the same commit that bumps
 * `version.properties` — the failing CI is the intentional reminder that
 * the iOS `iosApp/project.yml` `IS_BETA: "YES"` setting must also be
 * flipped (it has no codegen path). See the COUPLED EDIT note in
 * `version.properties`.
 */
class BuildFlagsTest {
    @Test
    fun isBeta_matches_versionProperties_majorZero() {
        // version.properties currently declares VERSION_NAME=0.1.0
        // (major component == 0), so the codegen-derived actual must be
        // true. Phase 40 GA bumps the major to 1 and flips this
        // assertion + version.properties + iosApp/project.yml IS_BETA
        // together.
        assertTrue(
            BuildFlags.isBeta,
            "BuildFlags.isBeta should be true while version.properties carries a major==0 semver",
        )
    }

    @Test
    fun versionName_is_non_empty_and_has_dotted_form() {
        // Phase 39 (W4): the force-update gate compares this against
        // `app_config.min_required_version_android`. Empty / non-dotted
        // values would cause `compareSemver` to fail-open (return null),
        // silently disabling the gate. Catching that here keeps the
        // assumption explicit.
        val v = BuildFlags.versionName
        assertTrue(v.isNotEmpty(), "BuildFlags.versionName must not be empty")
        assertTrue(
            v.contains('.'),
            "BuildFlags.versionName='$v' should contain a dot (semver X.Y.Z form expected)",
        )
        // Defensive parse: every segment is digit-only (no hyphenated
        // prerelease tag — App Store Connect rejects them).
        val segments = v.split('.')
        segments.forEach { seg ->
            assertTrue(
                seg.all { it.isDigit() },
                "BuildFlags.versionName segment '$seg' must be digits only (got '$v')",
            )
        }
    }
}
