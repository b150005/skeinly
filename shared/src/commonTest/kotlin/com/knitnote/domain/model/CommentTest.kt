package com.knitnote.domain.model

import kotlin.time.Instant
import com.knitnote.testJson
import kotlin.test.Test
import kotlin.test.assertEquals

class CommentTest {

    private val json = testJson

    private val now = Instant.parse("2026-01-15T10:30:00Z")

    @Test
    fun `create comment on pattern`() {
        val comment = Comment(
            id = "comment-001",
            authorId = "user-002",
            targetType = CommentTargetType.PATTERN,
            targetId = "pat-001",
            body = "Beautiful pattern! What yarn did you use?",
            createdAt = now,
        )

        assertEquals("comment-001", comment.id)
        assertEquals(CommentTargetType.PATTERN, comment.targetType)
        assertEquals("pat-001", comment.targetId)
    }

    @Test
    fun `create comment on project`() {
        val comment = Comment(
            id = "comment-002",
            authorId = "user-003",
            targetType = CommentTargetType.PROJECT,
            targetId = "proj-001",
            body = "Great progress!",
            createdAt = now,
        )

        assertEquals(CommentTargetType.PROJECT, comment.targetType)
        assertEquals("proj-001", comment.targetId)
    }

    @Test
    fun `serialize and deserialize comment round-trip`() {
        val comment = Comment(
            id = "comment-001",
            authorId = "user-002",
            targetType = CommentTargetType.PATTERN,
            targetId = "pat-001",
            body = "Nice work!",
            createdAt = now,
        )

        val encoded = json.encodeToString(Comment.serializer(), comment)
        val decoded = json.decodeFromString(Comment.serializer(), encoded)
        assertEquals(comment, decoded)
    }

    @Test
    fun `comment target type enum serialization`() {
        assertEquals("pattern", json.encodeToString(CommentTargetType.serializer(), CommentTargetType.PATTERN).trim('"'))
        assertEquals("project", json.encodeToString(CommentTargetType.serializer(), CommentTargetType.PROJECT).trim('"'))
    }
}
