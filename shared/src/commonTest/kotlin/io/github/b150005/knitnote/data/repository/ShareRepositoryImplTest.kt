package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.realtime.FakeRealtimeChannelProvider
import io.github.b150005.knitnote.data.remote.FakeRemoteShareDataSource
import io.github.b150005.knitnote.domain.model.Share
import io.github.b150005.knitnote.domain.model.SharePermission
import io.github.b150005.knitnote.domain.model.ShareStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ShareRepositoryImplTest {
    private lateinit var remote: FakeRemoteShareDataSource
    private lateinit var channelProvider: FakeRealtimeChannelProvider
    private lateinit var repository: ShareRepositoryImpl

    @BeforeTest
    fun setUp() {
        remote = FakeRemoteShareDataSource()
        channelProvider = FakeRealtimeChannelProvider()
        // Unconfined dispatcher ensures scope.launch executes eagerly in tests
        repository =
            ShareRepositoryImpl(
                remote = remote,
                channelProvider = channelProvider,
                scope = CoroutineScope(Dispatchers.Unconfined),
            )
    }

    private fun testShare(
        id: String = "share-1",
        patternId: String = "pattern-1",
        fromUserId: String = "user-a",
        toUserId: String? = "user-b",
        status: ShareStatus = ShareStatus.PENDING,
    ) = Share(
        id = id,
        patternId = patternId,
        fromUserId = fromUserId,
        toUserId = toUserId,
        permission = SharePermission.VIEW,
        status = status,
        shareToken = "token-$id",
        sharedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    // -- Delegation tests --

    @Test
    fun `getById delegates to remote`() =
        runTest {
            remote.addShare(testShare())
            val result = repository.getById("share-1")
            assertNotNull(result)
            assertEquals("share-1", result.id)
        }

    @Test
    fun `getById returns null when not found`() =
        runTest {
            assertNull(repository.getById("nonexistent"))
        }

    @Test
    fun `getByPatternId delegates to remote`() =
        runTest {
            remote.addShare(testShare(id = "s1", patternId = "p1"))
            remote.addShare(testShare(id = "s2", patternId = "p2"))
            val result = repository.getByPatternId("p1")
            assertEquals(1, result.size)
            assertEquals("s1", result[0].id)
        }

    @Test
    fun `getByToken delegates to remote`() =
        runTest {
            remote.addShare(testShare())
            val result = repository.getByToken("token-share-1")
            assertNotNull(result)
        }

    @Test
    fun `create delegates to remote insert`() =
        runTest {
            val share = testShare()
            val created = repository.create(share)
            assertEquals(share.id, created.id)
            assertNotNull(remote.getById("share-1"))
        }

    @Test
    fun `delete delegates to remote delete`() =
        runTest {
            remote.addShare(testShare())
            repository.delete("share-1")
            assertNull(remote.getById("share-1"))
        }

    @Test
    fun `updateStatus delegates to remote`() =
        runTest {
            remote.addShare(testShare())
            val updated = repository.updateStatus("share-1", ShareStatus.ACCEPTED)
            assertEquals(ShareStatus.ACCEPTED, updated.status)
        }

    // -- Realtime subscription tests --

    @Test
    fun `observeReceivedByUserId seeds from remote`() =
        runTest {
            remote.addShare(testShare(id = "s1", toUserId = "user-b"))
            remote.addShare(testShare(id = "s2", toUserId = "user-b"))

            val shares = repository.observeReceivedByUserId("user-b").first()
            assertEquals(2, shares.size)
        }

    @Test
    fun `observeReceivedByUserId creates channel with correct name`() =
        runTest {
            repository.observeReceivedByUserId("user-b").first()

            val handle = channelProvider.channelFor("shares-received-user-b")
            assertNotNull(handle)
            assertEquals("shares", handle.subscribedTable)
            assertTrue(handle.subscribed)
        }

    @Test
    fun `observeReceivedByUserId filters shares for correct user`() =
        runTest {
            remote.addShare(testShare(id = "s1", toUserId = "user-b"))
            remote.addShare(testShare(id = "s2", toUserId = "user-c"))

            val shares = repository.observeReceivedByUserId("user-b").first()
            assertEquals(1, shares.size)
            assertEquals("s1", shares[0].id)
        }

    @Test
    fun `closeChannel unsubscribes and clears state`() =
        runTest {
            repository.observeReceivedByUserId("user-b").first()

            val handle = channelProvider.channelFor("shares-received-user-b")
            assertNotNull(handle)
            assertTrue(handle.subscribed)

            repository.closeChannel()

            assertTrue(handle.unsubscribed)
        }

    @Test
    fun `channel swap on user change closes old channel`() =
        runTest {
            repository.observeReceivedByUserId("user-b").first()
            val oldHandle = channelProvider.channelFor("shares-received-user-b")
            assertNotNull(oldHandle)

            // Subscribe for a different user
            repository.closeChannel()
            repository.observeReceivedByUserId("user-c").first()

            assertTrue(oldHandle.unsubscribed)

            val newHandle = channelProvider.channelFor("shares-received-user-c")
            assertNotNull(newHandle)
            assertTrue(newHandle.subscribed)
        }
}
