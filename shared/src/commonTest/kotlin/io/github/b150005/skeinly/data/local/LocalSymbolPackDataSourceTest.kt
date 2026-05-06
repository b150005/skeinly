package io.github.b150005.skeinly.data.local

import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.db.createTestDriver
import io.github.b150005.skeinly.domain.model.SymbolPack
import io.github.b150005.skeinly.domain.model.SymbolPackTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LocalSymbolPackDataSourceTest {
    private lateinit var dataSource: LocalSymbolPackDataSource

    @BeforeTest
    fun setUp() {
        val driver = createTestDriver()
        val db = SkeinlyDatabase(driver)
        dataSource = LocalSymbolPackDataSource(db, Dispatchers.Unconfined)
    }

    private fun pack(
        id: String = "jis.knit.beginner",
        tier: SymbolPackTier = SymbolPackTier.FREE,
        version: Int = 1,
        symbolCount: Int = 30,
    ) = SymbolPack(
        id = id,
        tier = tier,
        version = version,
        displayName = "Knit – Beginner",
        description = null,
        payloadPath = "$id/$version/payload.json",
        payloadSize = 12345,
        symbolCount = symbolCount,
        signedUntil = null,
        createdAt = Instant.parse("2026-05-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-06T08:00:00Z"),
    )

    // ----- Catalog metadata --------------------------------------------------

    @Test
    fun `upsertPack persists row and getPackById returns it`() =
        runTest {
            val p = pack()
            dataSource.upsertPack(p)

            val fetched = dataSource.getPackById("jis.knit.beginner")
            assertNotNull(fetched)
            assertEquals(p, fetched)
        }

    @Test
    fun `getAllPacks returns rows ordered by id`() =
        runTest {
            dataSource.upsertPack(pack(id = "z.last"))
            dataSource.upsertPack(pack(id = "a.first"))
            dataSource.upsertPack(pack(id = "m.middle"))

            val all = dataSource.getAllPacks()
            assertEquals(listOf("a.first", "m.middle", "z.last"), all.map { it.id })
        }

    @Test
    fun `replaceManifest upserts new and removes packs no longer in the manifest`() =
        runTest {
            // Seed three packs, then "manifest" only lists two of them — the
            // third must disappear.
            dataSource.upsertPack(pack(id = "free.a"))
            dataSource.upsertPack(pack(id = "free.b"))
            dataSource.upsertPack(pack(id = "pro.archived", tier = SymbolPackTier.PRO))

            dataSource.replaceManifest(listOf(pack(id = "free.a", version = 2), pack(id = "free.b")))

            val cached = dataSource.getAllPacks().map { it.id to it.version }
            assertEquals(listOf("free.a" to 2, "free.b" to 1), cached)
        }

    @Test
    fun `replaceManifest with empty list clears every cached pack`() =
        runTest {
            dataSource.upsertPack(pack(id = "free.a"))
            dataSource.upsertPack(pack(id = "free.b"))

            dataSource.replaceManifest(emptyList())

            assertTrue(dataSource.getAllPacks().isEmpty())
        }

    @Test
    fun `replaceManifest preserves downloaded payloads even when the metadata row is dropped`() =
        runTest {
            // Pack archived server-side; user's existing payload survives so
            // any chart cells referencing the archived pack's symbols still
            // render. Class-KDoc invariant.
            dataSource.upsertPack(pack(id = "pro.archived", tier = SymbolPackTier.PRO))
            dataSource.upsertPayload(packId = "pro.archived", version = 1, payloadJson = """{"v":1}""")

            dataSource.replaceManifest(emptyList())

            assertNull(dataSource.getPackById("pro.archived"))
            val payload = dataSource.getLatestPayload("pro.archived")
            assertNotNull(payload)
            assertEquals(1, payload.version)
        }

    // ----- Downloaded payload ------------------------------------------------

    @Test
    fun `getLatestPayload returns null when no payload is downloaded`() =
        runTest {
            assertNull(dataSource.getLatestPayload("free.a"))
        }

    @Test
    fun `upsertPayload writes a row that getLatestPayload returns`() =
        runTest {
            dataSource.upsertPayload(packId = "free.a", version = 3, payloadJson = """{"x":3}""")

            val payload = dataSource.getLatestPayload("free.a")
            assertNotNull(payload)
            assertEquals(3, payload.version)
            assertEquals("""{"x":3}""", payload.payloadJson)
        }

    @Test
    fun `upsertPayload version-bump deletes strictly older versions of the same pack`() =
        runTest {
            // Sync wrote v3 originally; a later cycle bumps to v5 and the
            // post-write delete drops v3 atomically. v4 (intermediate
            // version we never materialized) is untouched because no row
            // exists for it.
            dataSource.upsertPayload(packId = "free.a", version = 3, payloadJson = """{"x":3}""")
            dataSource.upsertPayload(packId = "free.a", version = 5, payloadJson = """{"x":5}""")

            val all = dataSource.getAllPayloads().filter { it.packId == "free.a" }
            assertEquals(listOf(5), all.map { it.version })
        }

    @Test
    fun `upsertPayload does not delete same-or-newer-version rows of OTHER packs`() =
        runTest {
            dataSource.upsertPayload(packId = "pack.a", version = 1, payloadJson = """{"a":1}""")
            dataSource.upsertPayload(packId = "pack.b", version = 1, payloadJson = """{"b":1}""")
            dataSource.upsertPayload(packId = "pack.a", version = 2, payloadJson = """{"a":2}""")

            val all = dataSource.getAllPayloads().map { it.packId to it.version }
            assertEquals(setOf("pack.a" to 2, "pack.b" to 1), all.toSet())
        }

    @Test
    fun `hasPayloadForVersion answers true only for materialized version`() =
        runTest {
            dataSource.upsertPayload(packId = "free.a", version = 3, payloadJson = """{"x":3}""")

            assertTrue(dataSource.hasPayloadForVersion("free.a", 3))
            assertFalse(dataSource.hasPayloadForVersion("free.a", 2))
            assertFalse(dataSource.hasPayloadForVersion("free.a", 4))
            assertFalse(dataSource.hasPayloadForVersion("other.pack", 3))
        }

    @Test
    fun `deletePayloadsForPack drops every version of the pack but leaves others alone`() =
        runTest {
            dataSource.upsertPayload(packId = "pack.a", version = 1, payloadJson = """{"a":1}""")
            dataSource.upsertPayload(packId = "pack.b", version = 1, payloadJson = """{"b":1}""")

            dataSource.deletePayloadsForPack("pack.a")

            val remaining = dataSource.getAllPayloads().map { it.packId }
            assertEquals(listOf("pack.b"), remaining)
        }

    @Test
    fun `clearAll empties both metadata and payload tables in one transaction`() =
        runTest {
            dataSource.upsertPack(pack(id = "free.a"))
            dataSource.upsertPayload(packId = "free.a", version = 1, payloadJson = """{}""")

            dataSource.clearAll()

            assertTrue(dataSource.getAllPacks().isEmpty())
            assertTrue(dataSource.getAllPayloads().isEmpty())
        }
}
