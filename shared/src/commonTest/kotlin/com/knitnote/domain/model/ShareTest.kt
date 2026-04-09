package com.knitnote.domain.model

import kotlin.time.Instant
import com.knitnote.testJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareTest {

    private val json = testJson

    private val now = Instant.parse("2026-01-15T10:30:00Z")

    @Test
    fun `create share with direct recipient`() {
        val share = Share(
            id = "share-001",
            patternId = "pat-001",
            fromUserId = "user-001",
            toUserId = "user-002",
            permission = SharePermission.VIEW,
            status = ShareStatus.PENDING,
            shareToken = null,
            sharedAt = now,
        )

        assertEquals("share-001", share.id)
        assertEquals("user-002", share.toUserId)
        assertNull(share.shareToken)
        assertEquals(SharePermission.VIEW, share.permission)
        assertEquals(ShareStatus.PENDING, share.status)
    }

    @Test
    fun `create share with link token and no recipient`() {
        val share = Share(
            id = "share-002",
            patternId = "pat-001",
            fromUserId = "user-001",
            toUserId = null,
            permission = SharePermission.FORK,
            status = ShareStatus.ACCEPTED,
            shareToken = "abc123token",
            sharedAt = now,
        )

        assertNull(share.toUserId)
        assertEquals("abc123token", share.shareToken)
        assertEquals(SharePermission.FORK, share.permission)
    }

    @Test
    fun `serialize and deserialize share round-trip`() {
        val share = Share(
            id = "share-001",
            patternId = "pat-001",
            fromUserId = "user-001",
            toUserId = "user-002",
            permission = SharePermission.VIEW,
            status = ShareStatus.ACCEPTED,
            shareToken = null,
            sharedAt = now,
        )

        val encoded = json.encodeToString(Share.serializer(), share)
        val decoded = json.decodeFromString(Share.serializer(), encoded)
        assertEquals(share, decoded)
    }

    @Test
    fun `share permission enum serialization`() {
        assertEquals("view", json.encodeToString(SharePermission.serializer(), SharePermission.VIEW).trim('"'))
        assertEquals("fork", json.encodeToString(SharePermission.serializer(), SharePermission.FORK).trim('"'))
    }

    @Test
    fun `share status enum serialization`() {
        assertEquals("pending", json.encodeToString(ShareStatus.serializer(), ShareStatus.PENDING).trim('"'))
        assertEquals("accepted", json.encodeToString(ShareStatus.serializer(), ShareStatus.ACCEPTED).trim('"'))
        assertEquals("declined", json.encodeToString(ShareStatus.serializer(), ShareStatus.DECLINED).trim('"'))
    }
}
