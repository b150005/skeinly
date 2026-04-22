package io.github.b150005.knitnote.ui.onboarding

import androidx.lifecycle.ViewModel
import io.github.b150005.knitnote.domain.usecase.CompleteOnboardingUseCase
import io.github.b150005.knitnote.domain.usecase.GetOnboardingCompletedUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class OnboardingState(
    val pages: List<OnboardingPage> = OnboardingViewModel.DEFAULT_PAGES,
    val currentPage: Int = 0,
    val isCompleted: Boolean = false,
)

sealed interface OnboardingEvent {
    data object NextPage : OnboardingEvent

    data object PreviousPage : OnboardingEvent

    data object Skip : OnboardingEvent

    data object Complete : OnboardingEvent

    data class PageChanged(
        val page: Int,
    ) : OnboardingEvent
}

class OnboardingViewModel(
    private val getOnboardingCompleted: GetOnboardingCompletedUseCase,
    private val completeOnboarding: CompleteOnboardingUseCase,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            OnboardingState(isCompleted = getOnboardingCompleted()),
        )
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun onEvent(event: OnboardingEvent) {
        when (event) {
            is OnboardingEvent.NextPage -> advancePage()
            is OnboardingEvent.PreviousPage -> goBackPage()
            is OnboardingEvent.Skip -> markComplete()
            is OnboardingEvent.Complete -> markComplete()
            is OnboardingEvent.PageChanged ->
                _state.update { current ->
                    val clamped = event.page.coerceIn(0, current.pages.lastIndex)
                    current.copy(currentPage = clamped)
                }
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
        completeOnboarding()
        _state.update { it.copy(isCompleted = true) }
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
    }
}
