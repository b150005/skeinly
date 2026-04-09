package com.knitnote.ui.activityfeed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knitnote.domain.model.Activity
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.usecase.GetActivitiesUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class ActivityFeedState(
    val activities: List<Activity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

sealed interface ActivityFeedEvent {
    data object ClearError : ActivityFeedEvent
}

class ActivityFeedViewModel(
    private val getActivities: GetActivitiesUseCase,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ActivityFeedState())
    val state: StateFlow<ActivityFeedState> = _state.asStateFlow()

    init {
        observeActivities()
    }

    fun onEvent(event: ActivityFeedEvent) {
        when (event) {
            ActivityFeedEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun observeActivities() {
        val userId = authRepository.getCurrentUserId()
        if (userId == null) {
            _state.update {
                it.copy(isLoading = false, error = "Must be signed in to view activity feed")
            }
            return
        }

        getActivities.observe(userId)
            .onEach { activities ->
                _state.update {
                    it.copy(activities = activities, isLoading = false)
                }
            }
            .catch { e ->
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to load activities")
                }
            }
            .launchIn(viewModelScope)
    }
}
