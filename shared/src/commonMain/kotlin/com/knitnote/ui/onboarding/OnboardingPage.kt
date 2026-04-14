package com.knitnote.ui.onboarding

/**
 * Represents a single page in the onboarding carousel.
 *
 * @property iconName Platform-neutral icon identifier. The Screen layer maps this to
 *   a Compose [ImageVector] (Android) or SF Symbol name (iOS).
 * @property title Headline text displayed prominently on the page.
 * @property body Descriptive text explaining the feature.
 */
data class OnboardingPage(
    val iconName: String,
    val title: String,
    val body: String,
)
