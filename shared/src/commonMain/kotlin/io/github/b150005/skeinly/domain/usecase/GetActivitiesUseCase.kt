package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.Activity
import io.github.b150005.skeinly.domain.repository.ActivityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class GetActivitiesUseCase(
    private val activityRepository: ActivityRepository?,
) {
    fun observe(userId: String): Flow<List<Activity>> {
        if (activityRepository == null) return flowOf(emptyList())
        return activityRepository.observeByUserId(userId)
    }

    suspend operator fun invoke(userId: String): UseCaseResult<List<Activity>> {
        if (activityRepository == null) {
            return UseCaseResult.Failure(
                UseCaseError.RequiresConnectivity,
            )
        }
        return UseCaseResult.Success(activityRepository.getByUserId(userId))
    }
}
