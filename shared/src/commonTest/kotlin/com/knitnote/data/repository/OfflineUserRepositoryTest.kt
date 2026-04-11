package com.knitnote.data.repository

import com.knitnote.domain.model.User
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class OfflineUserRepositoryTest {
    private val repo = OfflineUserRepository()
    private val now = Instant.parse("2024-06-15T10:00:00Z")

    @Test
    fun `getById returns null`() = runTest {
        assertNull(repo.getById("any-id"))
    }

    @Test
    fun `getByIds returns empty list`() = runTest {
        assertTrue(repo.getByIds(listOf("id1", "id2")).isEmpty())
    }

    @Test
    fun `searchByDisplayName returns empty list`() = runTest {
        assertTrue(repo.searchByDisplayName("query", 10).isEmpty())
    }

    @Test
    fun `update throws UnsupportedOperationException`() = runTest {
        val user = User(
            id = "user1",
            displayName = "Test",
            avatarUrl = null,
            bio = null,
            createdAt = now,
        )
        assertFailsWith<UnsupportedOperationException> {
            repo.update(user)
        }
    }
}
