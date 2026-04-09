package com.knitnote.data.remote

import com.knitnote.domain.model.Activity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class RemoteActivityDataSource(
    private val supabaseClient: SupabaseClient,
) {
    private val table get() = supabaseClient.postgrest["activities"]

    suspend fun getByUserId(userId: String, limit: Int = 50): List<Activity> =
        table.select {
            filter { eq("user_id", userId) }
            order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
            limit(limit.toLong())
        }.decodeList()

    suspend fun insert(activity: Activity): Activity {
        table.insert(activity)
        return activity
    }
}
