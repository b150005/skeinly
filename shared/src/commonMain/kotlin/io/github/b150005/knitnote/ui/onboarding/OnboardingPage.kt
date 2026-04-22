package io.github.b150005.knitnote.ui.onboarding

/**
 * Represents a single page in the onboarding carousel.
 *
 * Title and body copy are resolved at the Screen layer from i18n resources
 * keyed by the page's position in [OnboardingState.pages] (see
 * `title_onboarding_{track,count,library}` and `body_onboarding_{track,count,library}`
 * keys), so the ViewModel stays string-free and both platforms localize natively.
 *
 * @property iconName Platform-neutral icon identifier. The Screen layer maps this to
 *   a Compose [ImageVector] (Android) or SF Symbol name (iOS).
 */
data class OnboardingPage(
    val iconName: String,
)
