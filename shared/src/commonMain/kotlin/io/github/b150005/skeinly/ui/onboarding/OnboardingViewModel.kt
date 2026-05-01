package io.github.b150005.skeinly.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.ClickActionId
import io.github.b150005.skeinly.data.analytics.Screen
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferences
import io.github.b150005.skeinly.domain.usecase.CompleteOnboardingUseCase
import io.github.b150005.skeinly.domain.usecase.GetOnboardingCompletedUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingState(
    val pages: List<OnboardingPage> = OnboardingViewModel.DEFAULT_PAGES,
    val currentPage: Int = 0,
    val isCompleted: Boolean = false,
    /**
     * Phase 39.4 (ADR-015 §6) — diagnostic-data sharing toggle on the
     * 4th onboarding page. Default mirrors the persisted preference at
     * init so the toggle paints in its correct position when an
     * already-consented user re-enters onboarding (rare; only happens
     * via a "Reset onboarding" debug path that doesn't ship publicly).
     * Default OFF for first-run users per the no-tracking stance.
     */
    val analyticsOptIn: Boolean = false,
)

sealed interface OnboardingEvent {
    data object NextPage : OnboardingEvent

    data object PreviousPage : OnboardingEvent

    data object Skip : OnboardingEvent

    data object Complete : OnboardingEvent

    data class PageChanged(
        val page: Int,
    ) : OnboardingEvent

    /**
     * Phase 39.4 — fires when the 4th-page diagnostic-data Switch flips.
     * Writes through to [AnalyticsPreferences] so the persisted value is
     * authoritative; the [OnboardingState.analyticsOptIn] field is kept
     * in lock-step via the init-block observer so a hypothetical
     * external write (Settings toggle while onboarding is mounted —
     * impossible today, defensive for the future) reflects in this UI.
     */
    data class SetAnalyticsOptIn(
        val value: Boolean,
    ) : OnboardingEvent
}

class OnboardingViewModel(
    private val getOnboardingCompleted: GetOnboardingCompletedUseCase,
    private val completeOnboarding: CompleteOnboardingUseCase,
    // Phase F.3 — nullable + default null preserves existing test
    // call-site compat (no test currently asserts capture, so the
    // default keeps every prior `OnboardingViewModel(...)` valid).
    private val analyticsTracker: AnalyticsTracker? = null,
    // Phase 39.4 — beta-gated 4th consent page wiring. Both deps are
    // optional; production wiring threads non-null only on beta builds
    // (see ViewModelModule). Tests construct with default-null when the
    // 4th page is not under exercise.
    private val analyticsPreferences: AnalyticsPreferences? = null,
    private val includeBetaConsent: Boolean = false,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            OnboardingState(
                pages =
                    if (includeBetaConsent) {
                        DEFAULT_PAGES + DIAGNOSTIC_CONSENT_PAGE
                    } else {
                        DEFAULT_PAGES
                    },
                isCompleted = getOnboardingCompleted(),
                analyticsOptIn = analyticsPreferences?.analyticsOptIn?.value ?: false,
            ),
        )
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        // Mirror the persisted preference into state so a programmatic
        // flip (Settings toggle, debug action) propagates here without
        // requiring a full screen recreation.
        analyticsPreferences?.let { prefs ->
            viewModelScope.launch {
                prefs.analyticsOptIn.collect { value ->
                    _state.update { it.copy(analyticsOptIn = value) }
                }
            }
        }
    }

    fun onEvent(event: OnboardingEvent) {
        when (event) {
            is OnboardingEvent.NextPage -> advancePage()
            is OnboardingEvent.PreviousPage -> goBackPage()
            is OnboardingEvent.Skip -> {
                analyticsTracker?.track(
                    AnalyticsEvent.ClickAction(ClickActionId.SubmitOnboarding, Screen.Onboarding),
                )
                markComplete()
            }
            is OnboardingEvent.Complete -> {
                analyticsTracker?.track(
                    AnalyticsEvent.ClickAction(ClickActionId.SubmitOnboarding, Screen.Onboarding),
                )
                markComplete()
            }
            is OnboardingEvent.PageChanged ->
                _state.update { current ->
                    val clamped = event.page.coerceIn(0, current.pages.lastIndex)
                    current.copy(currentPage = clamped)
                }
            is OnboardingEvent.SetAnalyticsOptIn ->
                // The persisted write is authoritative; the state mirror
                // updates via the init-block observer above so a single
                // source of truth carries the value.
                analyticsPreferences?.setAnalyticsOptIn(event.value)
        }
    }

    private fun advancePage() {
        _state.update { current ->
            val lastIndex = current.pages.lastIndex
            if (current.currentPage < lastIndex) {
                current.copy(currentPage = current.currentPage + 1)
            } else {
                current
            }
        }
    }

    private fun goBackPage() {
        _state.update { current ->
            if (current.currentPage > 0) {
                current.copy(currentPage = current.currentPage - 1)
            } else {
                current
            }
        }
    }

    private fun markComplete() {
        // Capture only on the first transition to `isCompleted = true` so
        // a stray re-tap does not produce duplicate analytics rows. The
        // underlying preference write itself is idempotent.
        val wasCompleted = _state.value.isCompleted
        completeOnboarding()
        _state.update { it.copy(isCompleted = true) }
        if (!wasCompleted) {
            analyticsTracker?.track(AnalyticsEvent.OnboardingCompleted)
        }
    }

    companion object {
        // Page copy is resolved at the Screen layer from i18n resources keyed
        // by index — `title_onboarding_{track,count,library}` and
        // `body_onboarding_{track,count,library}` keys. Reordering this list
        // requires matching updates to the titleKeys / bodyKeys arrays in
        // OnboardingScreen.kt (Compose) and
        // iosApp/.../OnboardingScreen.swift (SwiftUI).
        val DEFAULT_PAGES =
            listOf(
                OnboardingPage(iconName = "home"),
                OnboardingPage(iconName = "add_circle"),
                OnboardingPage(iconName = "favorite"),
            )

        // Phase 39.4 — appended after [DEFAULT_PAGES] when [includeBetaConsent]
        // is true. The Screen layer detects this page by its iconName
        // sentinel ("diagnostic_data") rather than index so adding a 5th
        // page later does not silently break the consent toggle. See
        // OnboardingScreen.kt / .swift for the per-page renderer.
        val DIAGNOSTIC_CONSENT_PAGE = OnboardingPage(iconName = "diagnostic_data")
    }
}
