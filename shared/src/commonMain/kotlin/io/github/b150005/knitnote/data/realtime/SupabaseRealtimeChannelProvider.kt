package io.github.b150005.knitnote.data.realtime

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.flow.Flow

/**
 * Production [RealtimeChannelProvider] backed by a live [SupabaseClient].
 */
class SupabaseRealtimeChannelProvider(
    private val supabaseClient: SupabaseClient,
) : RealtimeChannelProvider {
    override fun createChannel(name: String): ChannelHandle {
        val channel = supabaseClient.channel(name)
        return SupabaseChannelHandle(channel)
    }
}

private class SupabaseChannelHandle(
    private val channel: RealtimeChannel,
) : ChannelHandle {
    override fun postgresChangeFlow(
        table: String,
        schema: String,
        filter: ChangeFilter?,
    ): Flow<PostgresAction> =
        channel.postgresChangeFlow<PostgresAction>(schema) {
            this.table = table
            filter?.let { this.filter(it.column, FilterOperator.EQ, it.value) }
        }

    override suspend fun subscribe() = channel.subscribe()

    override suspend fun unsubscribe() = channel.unsubscribe()
}
