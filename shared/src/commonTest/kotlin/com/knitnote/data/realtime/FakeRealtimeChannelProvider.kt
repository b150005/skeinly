package com.knitnote.data.realtime

import io.github.jan.supabase.realtime.PostgresAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Fake [RealtimeChannelProvider] for unit tests.
 * Records channel creation and provides [FakeChannelHandle]s
 * that can emit [PostgresAction] events via [FakeChannelHandle.events].
 */
class FakeRealtimeChannelProvider : RealtimeChannelProvider {
    private val _createdChannels = mutableMapOf<String, FakeChannelHandle>()
    val createdChannels: Map<String, FakeChannelHandle> get() = _createdChannels

    override fun createChannel(name: String): ChannelHandle {
        val handle = FakeChannelHandle()
        _createdChannels[name] = handle
        return handle
    }

    /** Returns the [FakeChannelHandle] created for [name], or null. */
    fun channelFor(name: String): FakeChannelHandle? = _createdChannels[name]
}

/**
 * Fake [ChannelHandle] for unit tests.
 * Emit events via [events] to drive repository logic under test.
 */
class FakeChannelHandle : ChannelHandle {
    val events = MutableSharedFlow<PostgresAction>(extraBufferCapacity = 10)
    var subscribed = false
        private set
    var unsubscribed = false
        private set
    var subscribedTable: String? = null
        private set
    var subscribedFilter: ChangeFilter? = null
        private set

    override fun postgresChangeFlow(
        table: String,
        schema: String,
        filter: ChangeFilter?,
    ): Flow<PostgresAction> {
        subscribedTable = table
        subscribedFilter = filter
        return events
    }

    override suspend fun subscribe() {
        subscribed = true
        unsubscribed = false
    }

    override suspend fun unsubscribe() {
        unsubscribed = true
        subscribed = false
    }
}
