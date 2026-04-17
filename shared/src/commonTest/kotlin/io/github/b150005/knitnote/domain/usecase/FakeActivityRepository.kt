package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Activity
import io.github.b150005.knitnote.domain.repository.ActivityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeActivityRepository : ActivityRepository {
    private val activities = MutableStateFlow<List<Activity>>(emptyList())
    var closeChannelCalled = false

    override suspend fun closeChannel() {
        closeChannelCalled = true
    }

    override suspend fun getByUserId(userId: String): List<Activity> = activities.value.filter { it.userId == userId }

    override fun observeByUserId(userId: String): Flow<List<Activity>> = activities.map { list -> list.filter { it.userId == userId } }

    override suspend fun create(activity: Activity): Activity {
        activities.value = activities.value + activity
        return activity
    }

    fun addActivity(activity: Activity) {
        activities.value = activities.value + activity
    }
}
