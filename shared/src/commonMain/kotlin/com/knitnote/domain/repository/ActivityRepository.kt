package com.knitnote.domain.repository

import com.knitnote.domain.model.Activity
import kotlinx.coroutines.flow.Flow

interface ActivityRepository {
    suspend fun getByUserId(userId: String): List<Activity>
    fun observeByUserId(userId: String): Flow<List<Activity>>
    suspend fun create(activity: Activity): Activity
}
