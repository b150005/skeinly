package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.realtime.FakeRealtimeChannelProvider
import io.github.b150005.knitnote.data.remote.FakeRemoteCommentDataSource
import io.github.b150005.knitnote.domain.model.Comment
import io.github.b150005.knitnote.domain.model.CommentTargetType
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
class CommentRepositoryImplTest {
    private lateinit var remote: FakeRemoteCommentDataSource
    private lateinit var channelProvider: FakeRealtimeChannelProvider
    private lateinit var repository: CommentRepositoryImpl

    @BeforeTest
    fun setUp() {
        remote = FakeRemoteCommentDataSource()
        channelProvider = FakeRealtimeChannelProvider()
        repository =
            CommentRepositoryImpl(
                remote = remote,
                channelProvider = channelProvider,
                scope = CoroutineScope(Dispatchers.Unconfined),
            )
    }

    private fun testComment(
        id: String = "comment-1",
        targetType: CommentTargetType = CommentTargetType.PATTERN,
        targetId: String = "target-1",
    ) = Comment(
        id = id,
        authorId = "author-1",
        targetType = targetType,
        targetId = targetId,
        body = "Test comment body",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    // -- Delegation tests --

    @Test
    fun `getById delegates to remote`() =
        runTest {
            remote.addComment(testComment())
            val result = repository.getById("comment-1")
            assertNotNull(result)
            assertEquals("comment-1", result.id)
        }

    @Test
    fun `getById returns null when not found`() =
        runTest {
            assertNull(repository.getById("nonexistent"))
        }

    @Test
    fun `getByTarget delegates to remote`() =
        runTest {
            remote.addComment(testComment(id = "c1", targetId = "t1"))
            remote.addComment(testComment(id = "c2", targetId = "t2"))
            val result = repository.getByTarget(CommentTargetType.PATTERN, "t1")
            assertEquals(1, result.size)
            assertEquals("c1", result[0].id)
        }

    @Test
    fun `create delegates to remote insert`() =
        runTest {
            val comment = testComment()
            val created = repository.create(comment)
            assertEquals(comment.id, created.id)
            assertNotNull(remote.getById("comment-1"))
        }

    @Test
    fun `delete delegates to remote delete`() =
        runTest {
            remote.addComment(testComment())
            repository.delete("comment-1")
            assertNull(remote.getById("comment-1"))
        }

    // -- Realtime subscription tests --

    @Test
    fun `observeByTarget seeds from remote`() =
        runTest {
            remote.addComment(testComment(id = "c1", targetId = "t1"))
            remote.addComment(testComment(id = "c2", targetId = "t1"))

            val comments = repository.observeByTarget(CommentTargetType.PATTERN, "t1").first()
            assertEquals(2, comments.size)
        }

    @Test
    fun `observeByTarget creates channel with correct name`() =
        runTest {
            repository.observeByTarget(CommentTargetType.PATTERN, "target-1").first()

            val handle = channelProvider.channelFor("comments-PATTERN:target-1")
            assertNotNull(handle)
            assertEquals("comments", handle.subscribedTable)
            assertTrue(handle.subscribed)
        }

    @Test
    fun `observeByTarget filters comments for correct target`() =
        runTest {
            remote.addComment(testComment(id = "c1", targetId = "t1"))
            remote.addComment(testComment(id = "c2", targetId = "t2"))

            val comments = repository.observeByTarget(CommentTargetType.PATTERN, "t1").first()
            assertEquals(1, comments.size)
            assertEquals("c1", comments[0].id)
        }

    @Test
    fun `closeChannel unsubscribes and clears state`() =
        runTest {
            repository.observeByTarget(CommentTargetType.PATTERN, "target-1").first()

            val handle = channelProvider.channelFor("comments-PATTERN:target-1")
            assertNotNull(handle)
            assertTrue(handle.subscribed)

            repository.closeChannel()

            assertTrue(handle.unsubscribed)
        }

    @Test
    fun `channel swap on target change closes old channel`() =
        runTest {
            repository.observeByTarget(CommentTargetType.PATTERN, "target-1").first()
            val oldHandle = channelProvider.channelFor("comments-PATTERN:target-1")
            assertNotNull(oldHandle)

            repository.closeChannel()
            repository.observeByTarget(CommentTargetType.PROJECT, "target-2").first()

            assertTrue(oldHandle.unsubscribed)

            val newHandle = channelProvider.channelFor("comments-PROJECT:target-2")
            assertNotNull(newHandle)
            assertTrue(newHandle.subscribed)
        }
}
