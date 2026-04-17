package io.github.b150005.knitnote.domain.repository

import io.github.b150005.knitnote.domain.model.Activity
import kotlinx.coroutines.flow.Flow

interface ActivityRepository {
    suspend fun getByUserId(userId: String): List<Activity>

    fun observeByUserId(userId: String): Flow<List<Activity>>

    suspend fun create(activity: Activity): Activity

    /** Release Realtime subscription and clear cached state. Call on user logout. */
    suspend fun closeChannel() {}
}
