package com.knitnote.domain.model

import kotlin.time.Instant
import com.knitnote.testJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserTest {

    private val json = testJson

    @Test
    fun `create user with all fields`() {
        val now = Instant.parse("2026-01-15T10:30:00Z")
        val user = User(
            id = "user-001",
            displayName = "Knitter42",
            avatarUrl = "https://example.com/avatar.png",
            bio = "I love knitting socks",
            createdAt = now,
        )

        assertEquals("user-001", user.id)
        assertEquals("Knitter42", user.displayName)
        assertEquals("https://example.com/avatar.png", user.avatarUrl)
        assertEquals("I love knitting socks", user.bio)
        assertEquals(now, user.createdAt)
    }

    @Test
    fun `create user with nullable fields as null`() {
        val now = Instant.parse("2026-01-15T10:30:00Z")
        val user = User(
            id = "user-002",
            displayName = "MinimalUser",
            avatarUrl = null,
            bio = null,
            createdAt = now,
        )

        assertNull(user.avatarUrl)
        assertNull(user.bio)
    }

    @Test
    fun `serialize and deserialize user round-trip`() {
        val now = Instant.parse("2026-01-15T10:30:00Z")
        val user = User(
            id = "user-001",
            displayName = "Knitter42",
            avatarUrl = "https://example.com/avatar.png",
            bio = "I love knitting socks",
            createdAt = now,
        )

        val encoded = json.encodeToString(User.serializer(), user)
        val decoded = json.decodeFromString(User.serializer(), encoded)

        assertEquals(user, decoded)
    }

    @Test
    fun `copy preserves immutability`() {
        val now = Instant.parse("2026-01-15T10:30:00Z")
        val original = User(
            id = "user-001",
            displayName = "OldName",
            avatarUrl = null,
            bio = null,
            createdAt = now,
        )

        val updated = original.copy(displayName = "NewName")

        assertEquals("OldName", original.displayName)
        assertEquals("NewName", updated.displayName)
        assertEquals(original.id, updated.id)
    }
}
