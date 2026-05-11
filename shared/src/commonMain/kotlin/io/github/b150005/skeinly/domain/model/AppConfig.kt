package io.github.b150005.skeinly.domain.model

/**
 * Phase 39 (W4 / 2026-05-11) — server-side app-level config used to gate
 * client startup against `min_required_version_*`. Single row, mirrored
 * exactly from `public.app_config` (see migration 028).
 *
 * Update path: Supabase Dashboard SQL editor / service-role only —
 * client cannot mutate via PostgREST. RLS allows public SELECT so the
 * gate can read this before any auth.
 */
data class AppConfig(
    val minRequiredVersionAndroid: String,
    val minRequiredVersionIos: String,
    /**
     * Optional custom force-update message in English. When null, the
     * client falls back to a default localized copy bundled in i18n
     * resources (`force_update_default_message`) so the kill-switch
     * can fire even if copy is not configured.
     */
    val forceUpdateMessageEn: String?,
    val forceUpdateMessageJa: String?,
)

/**
 * Minimum-version requirement evaluation outcome. Three states keep
 * the failure modes explicit at the call site so we never accidentally
 * fail-CLOSED when we only meant fail-OPEN-on-unknown:
 *
 *   - [Ok]: client version satisfies the floor; show normal app.
 *   - [UpdateRequired]: client version is below the floor; show the
 *     blocking force-update screen with the optional custom message.
 *   - [Unknown]: no config available locally (first launch + offline);
 *     fail-open per the offline-first contract — the gate engages
 *     from the first successful fetch onward.
 */
sealed interface ForceUpdateRequirement {
    data object Ok : ForceUpdateRequirement

    data class UpdateRequired(
        val customMessageEn: String?,
        val customMessageJa: String?,
    ) : ForceUpdateRequirement

    data object Unknown : ForceUpdateRequirement
}

/**
 * Platform discriminator. Used to pick the matching
 * `min_required_version_*` field on the [AppConfig]. We deliberately
 * model this as a separate enum (rather than `expect/actual` plumbing)
 * because the gate's caller is platform-specific code (Android
 * MainActivity, iOS AppRootView) that already knows which platform it
 * is — passing the discriminator explicitly keeps the shared layer
 * platform-agnostic and easier to test in commonTest.
 */
enum class AppPlatform { ANDROID, IOS }

/**
 * Parse a `MAJOR.MINOR.PATCH` semver string into a comparable Triple.
 * Returns null for malformed input so the caller can fail-open
 * (treating unparseable versions as unknown rather than crashing).
 *
 * We deliberately do NOT support hyphenated prerelease identifiers
 * (`1.0.0-beta1` etc.) because:
 *   1. iOS `CFBundleShortVersionString` rejects hyphens at upload time
 *      (App Store Connect enforces digits-and-dots only), so the
 *      client `versionName` cannot contain them.
 *   2. The server `min_required_version_*` is matched against client
 *      `versionName`; if the client form is constrained to digits and
 *      dots, the parser does not need to handle anything else.
 */
private fun parseSemver(version: String): Triple<Int, Int, Int>? {
    val parts = version.split('.')
    if (parts.size !in 1..3) return null
    // Mandatory: major must always parse. Minor / patch default to 0
    // ONLY when the input is short (1 or 2 segments). When the input
    // provides a segment, it must parse — otherwise hyphenated
    // prerelease suffixes like `1.0.0-beta1` would parse as
    // `(1, 0, 0)` (with the `-beta1` discarded), defeating the
    // "digits and dots only" check.
    val major = parts[0].toIntOrNull() ?: return null
    val minor = if (parts.size >= 2) parts[1].toIntOrNull() ?: return null else 0
    val patch = if (parts.size >= 3) parts[2].toIntOrNull() ?: return null else 0
    if (major < 0 || minor < 0 || patch < 0) return null
    return Triple(major, minor, patch)
}

/**
 * Compare two semver strings. Returns negative if `current` is below
 * `required`, zero if equal, positive if above. Returns null when
 * either string is unparseable so the caller can fail-open.
 *
 * Visible for tests.
 */
internal fun compareSemver(
    current: String,
    required: String,
): Int? {
    val c = parseSemver(current) ?: return null
    val r = parseSemver(required) ?: return null
    val majorDelta = c.first - r.first
    if (majorDelta != 0) return majorDelta
    val minorDelta = c.second - r.second
    if (minorDelta != 0) return minorDelta
    return c.third - r.third
}

/**
 * Evaluate whether the current app version satisfies the
 * platform-specific minimum required version in [config]. Unparseable
 * versions degrade to [ForceUpdateRequirement.Ok] (fail-open) — the
 * gate must never block users on a config / parsing error, only on a
 * deliberate kill-switch trigger.
 */
fun AppConfig.evaluate(
    currentVersion: String,
    platform: AppPlatform,
): ForceUpdateRequirement {
    val requiredVersion =
        when (platform) {
            AppPlatform.ANDROID -> minRequiredVersionAndroid
            AppPlatform.IOS -> minRequiredVersionIos
        }
    val delta = compareSemver(currentVersion, requiredVersion) ?: return ForceUpdateRequirement.Ok
    return if (delta < 0) {
        ForceUpdateRequirement.UpdateRequired(
            customMessageEn = forceUpdateMessageEn,
            customMessageJa = forceUpdateMessageJa,
        )
    } else {
        ForceUpdateRequirement.Ok
    }
}
