package com.knitnote.data.realtime

import io.github.jan.supabase.realtime.PostgresAction
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Fake [RealtimeChannelProvider] for unit tests.
 * Records channel creation and provides [FakeChannelHandle]s
 * that can emit [PostgresAction] events via [FakeChannelHandle.emit].
 */
class FakeRealtimeChannelProvider : RealtimeChannelProvider {
    private val _createdChannels = mutableMapOf<String, FakeChannelHandle>()
    val createdChannels: Map<String, FakeChannelHandle> get() = _createdChannels

    /** Total number of createChannel invocations (tracks re-creates). */
    var createCount: Int = 0
        private set

    override fun createChannel(name: String): ChannelHandle {
        createCount++
        val handle = FakeChannelHandle()
        _createdChannels[name] = handle
        return handle
    }

    /** Returns the [FakeChannelHandle] created for [name], or null. */
    fun channelFor(name: String): FakeChannelHandle? = _createdChannels[name]
}

/**
 * Fake [ChannelHandle] for unit tests.
 * Emit events via [emit] to drive repository logic under test.
 * Call [completeWithError] to simulate channel flow termination.
 */
class FakeChannelHandle : ChannelHandle {
    private val channel = Channel<PostgresAction>(Channel.BUFFERED)
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
        return channel.receiveAsFlow()
    }

    /** Emit a [PostgresAction] event to the flow. */
    suspend fun emit(action: PostgresAction) {
        channel.send(action)
    }

    /** Simulate a channel flow error, causing the flow to terminate with [error]. */
    fun completeWithError(error: Throwable) {
        channel.close(error)
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
