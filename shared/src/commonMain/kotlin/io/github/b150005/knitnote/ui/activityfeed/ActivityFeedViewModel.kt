package io.github.b150005.knitnote.ui.activityfeed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.Activity
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.UserRepository
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.GetActivitiesUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class ActivityFeedState(
    val activities: List<Activity> = emptyList(),
    val users: Map<String, User> = emptyMap(),
    val isLoading: Boolean = true,
    val error: ErrorMessage? = null,
)

sealed interface ActivityFeedEvent {
    data object ClearError : ActivityFeedEvent
}

class ActivityFeedViewModel(
    private val getActivities: GetActivitiesUseCase,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
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
                it.copy(isLoading = false, error = ErrorMessage.Raw("Must be signed in to view activity feed"))
            }
            return
        }

        getActivities
            .observe(userId)
            .onEach { activities ->
                _state.update {
                    it.copy(activities = activities, isLoading = false)
                }
                resolveUsers(activities)
            }.catch { e ->
                _state.update {
                    it.copy(isLoading = false, error = ErrorMessage.Raw(e.message ?: "Failed to load activities"))
                }
            }.launchIn(viewModelScope)
    }

    private suspend fun resolveUsers(activities: List<Activity>) {
        val currentUsers = _state.value.users
        val newUserIds = activities.map { it.userId }.distinct() - currentUsers.keys
        if (newUserIds.isEmpty()) return

        val resolved = userRepository.getByIds(newUserIds).associateBy { it.id }
        _state.update { it.copy(users = currentUsers + resolved) }
    }
}
