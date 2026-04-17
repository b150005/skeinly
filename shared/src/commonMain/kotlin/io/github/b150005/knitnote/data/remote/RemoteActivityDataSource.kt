package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.domain.model.Activity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

/** Testable contract for remote activity operations. */
interface ActivityDataSourceOperations {
    suspend fun getByUserId(
        userId: String,
        limit: Int = 50,
    ): List<Activity>

    suspend fun insert(activity: Activity): Activity
}

class RemoteActivityDataSource(
    private val supabaseClient: SupabaseClient,
) : ActivityDataSourceOperations {
    private val table get() = supabaseClient.postgrest["activities"]

    override suspend fun getByUserId(
        userId: String,
        limit: Int,
    ): List<Activity> =
        table
            .select {
                filter { eq("user_id", userId) }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                limit(limit.toLong())
            }.decodeList()

    override suspend fun insert(activity: Activity): Activity =
        table
            .insert(activity) {
                select()
            }.decodeSingle()
}
