package io.github.b150005.knitnote.data.realtime

import io.github.jan.supabase.realtime.PostgresAction
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over Supabase Realtime channel creation and subscription.
 * Enables unit-testing of Realtime consumers without a live SupabaseClient.
 */
interface RealtimeChannelProvider {
    fun createChannel(name: String): ChannelHandle
}

/**
 * Handle for a single Realtime channel supporting postgres change subscriptions.
 */
interface ChannelHandle {
    /**
     * Returns a [Flow] of [PostgresAction] events for the given table.
     * Must be collected before calling [subscribe].
     */
    fun postgresChangeFlow(
        table: String,
        schema: String = "public",
        filter: ChangeFilter? = null,
    ): Flow<PostgresAction>

    suspend fun subscribe()

    suspend fun unsubscribe()
}

/**
 * Filter configuration for Realtime postgres change subscriptions.
 * Maps to `filter(column, FilterOperator.EQ, value)` in the Supabase SDK.
 */
data class ChangeFilter(
    val column: String,
    val value: String,
)
