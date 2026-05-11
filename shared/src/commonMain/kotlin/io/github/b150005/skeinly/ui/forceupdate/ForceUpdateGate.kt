package io.github.b150005.skeinly.ui.forceupdate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.b150005.skeinly.config.BuildFlags
import io.github.b150005.skeinly.domain.model.AppConfig
import io.github.b150005.skeinly.domain.model.ForceUpdateRequirement
import io.github.b150005.skeinly.domain.model.evaluate
import io.github.b150005.skeinly.domain.repository.AppConfigRepository
import io.github.b150005.skeinly.domain.repository.AppConfigState
import io.github.b150005.skeinly.platform.DeviceContextProvider
import io.github.b150005.skeinly.platform.StoreUrlLauncher
import org.koin.compose.koinInject

/**
 * Phase 39 (W4 / 2026-05-11) — full-screen gate wrapping [content].
 * Renders [ForceUpdateScreen] (blocking, non-dismissable) when the
 * installed version is below `app_config.min_required_version_*` for
 * this platform; otherwise renders [content] unchanged.
 *
 * State machine:
 *   - On first composition: triggers [AppConfigRepository.refresh].
 *   - While the repository is [AppConfigState.Loading] (no cache, no
 *     fetch yet): renders [content] (fail-open per the offline-first
 *     contract — we never block startup on the first network round
 *     trip).
 *   - When the repository transitions to [AppConfigState.Cached] /
 *     [Live]: evaluates the current [BuildFlags.versionName] +
 *     [BuildFlags.platform] against the config. UpdateRequired →
 *     [ForceUpdateScreen]; Ok / Unknown → [content].
 *   - When the repository transitions to [AppConfigState.Unavailable]
 *     (no cache + no successful fetch): renders [content] (fail-open
 *     identically to Loading; the gate never engages without data).
 *
 * Why fail-open on Unknown:
 *   - Skeinly is offline-first. Blocking offline first-launches would
 *     violate that contract.
 *   - The kill-switch semantic depends on actively-shipped server
 *     state. A user with no cache + no network has, by definition,
 *     never been told to upgrade — we can't enforce a rule they
 *     haven't seen.
 *   - The first successful online launch will cache the config; from
 *     that launch onward, offline-with-cache uses the last-known floor.
 *
 * The gate replaces the entire NavHost on UpdateRequired (caller wraps
 * the NavHost root with this Composable). Even system back press stays
 * on the force-update screen because there's nothing in the back stack
 * to pop to.
 *
 * Locale selection: reads [DeviceContextProvider.locale] (BCP 47, e.g.
 * `ja-JP`) and picks `customMessageJa` when the language tag starts
 * with `ja`; otherwise `customMessageEn`. Both null → the bundled
 * default message in [ForceUpdateScreen] (also locale-aware via
 * Compose's strings.xml / values-ja resolution).
 */
@Composable
fun ForceUpdateGate(
    repository: AppConfigRepository = koinInject(),
    deviceContext: DeviceContextProvider = koinInject(),
    storeLauncher: StoreUrlLauncher = koinInject(),
    content: @Composable () -> Unit,
) {
    val state by repository.state.collectAsState()

    LaunchedEffect(Unit) {
        // One-shot refresh on first composition. Subsequent value
        // changes (e.g. config bumped server-side mid-session) will
        // arrive on the next app launch; we don't poll mid-session.
        repository.refresh()
    }

    val requirement = state.toRequirement()
    if (requirement is ForceUpdateRequirement.UpdateRequired) {
        val message =
            selectMessageForLocale(
                customMessageEn = requirement.customMessageEn,
                customMessageJa = requirement.customMessageJa,
                locale = deviceContext.locale,
            )
        ForceUpdateScreen(
            customMessage = message,
            onUpdateClick = { storeLauncher.open() },
        )
    } else {
        content()
    }
}

/**
 * Converts an [AppConfigState] to a [ForceUpdateRequirement] using the
 * current build's version + platform. Exposed internal-visible so
 * commonTest can pin the boundary semantics.
 */
internal fun AppConfigState.toRequirement(): ForceUpdateRequirement =
    when (this) {
        is AppConfigState.Live -> config.evaluateForBuild()
        is AppConfigState.Cached -> config.evaluateForBuild()
        is AppConfigState.Loading -> ForceUpdateRequirement.Unknown
        is AppConfigState.Unavailable -> ForceUpdateRequirement.Unknown
    }

private fun AppConfig.evaluateForBuild(): ForceUpdateRequirement =
    evaluate(
        currentVersion = BuildFlags.versionName,
        platform = BuildFlags.platform,
    )

/**
 * Locale-aware custom-message picker. JA-first when the BCP-47 tag
 * starts with `ja` (covers `ja`, `ja-JP`, `ja-Hrkt`, etc.); EN
 * otherwise (covers `en`, `en-US`, `en-GB`, and any non-ja non-en
 * tag — alpha rubric only ships en + ja). Null-or-blank falls back
 * to the bundled default copy.
 *
 * Visible for tests.
 */
internal fun selectMessageForLocale(
    customMessageEn: String?,
    customMessageJa: String?,
    locale: String,
): String? {
    val pick =
        if (locale.startsWith("ja", ignoreCase = true)) {
            customMessageJa
        } else {
            customMessageEn
        }
    return pick?.takeIf { it.isNotBlank() }
}
