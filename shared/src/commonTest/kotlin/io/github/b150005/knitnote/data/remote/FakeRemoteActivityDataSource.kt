package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.domain.model.Activity

/**
 * In-memory fake for [ActivityDataSourceOperations] used in repository tests.
 */
class FakeRemoteActivityDataSource : ActivityDataSourceOperations {
    private val activities = mutableListOf<Activity>()

    override suspend fun getByUserId(
        userId: String,
        limit: Int,
    ): List<Activity> = activities.filter { it.userId == userId }.take(limit)

    override suspend fun insert(activity: Activity): Activity {
        activities.add(activity)
        return activity
    }

    /** Test helper: pre-populate activities. */
    fun addActivity(activity: Activity) {
        activities.add(activity)
    }
}
