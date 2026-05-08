package io.github.b150005.skeinly.domain.symbol

import io.github.b150005.skeinly.data.local.LocalSymbolPackDataSource
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.db.createTestDriver
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.Subscription
import io.github.b150005.skeinly.domain.model.SubscriptionPlatform
import io.github.b150005.skeinly.domain.model.SubscriptionStatus
import io.github.b150005.skeinly.domain.model.SymbolPack
import io.github.b150005.skeinly.domain.model.SymbolPackPayload
import io.github.b150005.skeinly.domain.model.SymbolPackPayloadEntry
import io.github.b150005.skeinly.domain.model.SymbolPackTier
import io.github.b150005.skeinly.domain.repository.SubscriptionRepository
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class CompositeSymbolCatalogTest {
    private val now = Instant.parse("2026-05-07T12:00:00Z")
    private val frozenClock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val json = Json { ignoreUnknownKeys = true }

    private fun pack(
        id: String,
        tier: SymbolPackTier = SymbolPackTier.FREE,
        version: Int = 1,
        updatedAt: Instant = Instant.parse("2026-05-01T00:00:00Z"),
        symbolCount: Int = 1,
    ) = SymbolPack(
        id = id,
        tier = tier,
        version = version,
        displayName = id,
        description = null,
        payloadPath = "$id/$version/payload.json",
        payloadSize = 1024,
        symbolCount = symbolCount,
        signedUntil = null,
        createdAt = Instant.parse("2026-05-01T00:00:00Z"),
        updatedAt = updatedAt,
    )

    private fun entry(
        id: String,
        category: SymbolCategory = SymbolCategory.KNIT,
        tier: SymbolPackTier = SymbolPackTier.FREE,
        pathData: String = "M 0 0 L 1 1",
        jaLabel: String = id,
        enLabel: String = id,
    ) = SymbolPackPayloadEntry(
        id = id,
        category = category.name,
        tier = tier,
        pathData = pathData,
        jaLabel = jaLabel,
        enLabel = enLabel,
    )

    private fun payloadJson(
        packId: String,
        version: Int,
        entries: List<SymbolPackPayloadEntry>,
    ): String {
        val body =
            SymbolPackPayload(
                packId = packId,
                version = version,
                schemaVersion = SymbolPackPayload.CURRENT_SCHEMA_VERSION,
                symbols = entries,
            )
        return json.encodeToString(body)
    }

    private suspend fun seed(
        local: LocalSymbolPackDataSource,
        pack: SymbolPack,
        entries: List<SymbolPackPayloadEntry>,
        bodyOverride: String? = null,
    ) {
        local.upsertPack(pack)
        local.upsertPayload(
            packId = pack.id,
            version = pack.version,
            payloadJson = bodyOverride ?: payloadJson(pack.id, pack.version, entries),
        )
    }

    private fun stubSub(
        userId: String? = "user-1",
        sub: Subscription? = null,
    ): EntitlementResolver {
        val auth =
            FakeAuthRepository().apply {
                if (userId != null) setAuthState(AuthState.Authenticated(userId = userId, email = "e@x"))
            }
        val repo = StubSubscriptionRepository(cachedFor = userId, sub = sub)
        return EntitlementResolver(repo, auth, frozenClock)
    }

    private fun activeProSub() =
        Subscription(
            id = "sub-1",
            userId = "user-1",
            platform = SubscriptionPlatform.IOS,
            productId = "skeinly.pro.monthly",
            status = SubscriptionStatus.ACTIVE,
            originalTransactionId = "txn-1",
            expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
            lastVerifiedAt = now,
            createdAt = now,
            updatedAt = now,
        )

    private fun localStore(): LocalSymbolPackDataSource {
        val driver = createTestDriver()
        val db = SkeinlyDatabase(driver)
        return LocalSymbolPackDataSource(db, Dispatchers.Unconfined)
    }

    private fun composite(
        local: LocalSymbolPackDataSource,
        bundled: SymbolCatalog = StaticBundledCatalog(emptyList()),
        resolver: EntitlementResolver = stubSub(sub = null),
    ): CompositeSymbolCatalog {
        // Unconfined makes the init { applicationScope.launch { refresh() } }
        // run inline — every test sees a deterministic snapshot at construction.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        return CompositeSymbolCatalog(
            bundled = bundled,
            localSymbolPackDataSource = local,
            entitlementResolver = resolver,
            json = json,
            applicationScope = scope,
        )
    }

    // ----- Snapshot composition --------------------------------------------

    @Test
    fun `empty local store falls through to bundled`() =
        runTest {
            val bundled = StaticBundledCatalog(listOf(symbolDef("jis.knit.k")))
            val local = localStore()
            val cat = composite(local = local, bundled = bundled)

            val result = cat.get("jis.knit.k")
            assertNotNull(result)
            assertEquals("jis.knit.k", result.id)
        }

    @Test
    fun `downloaded definition overrides bundled with same id`() =
        runTest {
            val bundled = StaticBundledCatalog(listOf(symbolDef("jis.knit.k", jaLabel = "bundled-ja")))
            val local = localStore()
            seed(local, pack("jis.knit.beginner"), listOf(entry("jis.knit.k", jaLabel = "downloaded-ja")))
            val cat = composite(local = local, bundled = bundled)

            val result = cat.get("jis.knit.k")
            assertNotNull(result)
            assertEquals("downloaded-ja", result.jaLabel)
        }

    @Test
    fun `pro symbol returns null when entitlement is not pro`() =
        runTest {
            val local = localStore()
            seed(
                local,
                pack("pack.pro.cables", tier = SymbolPackTier.PRO),
                listOf(entry("jis.knit.cable.6st", tier = SymbolPackTier.PRO)),
            )
            val cat = composite(local = local, resolver = stubSub(sub = null))

            assertNull(cat.get("jis.knit.cable.6st"))
        }

    @Test
    fun `pro symbol returns definition when entitlement is pro`() =
        runTest {
            val local = localStore()
            seed(
                local,
                pack("pack.pro.cables", tier = SymbolPackTier.PRO),
                listOf(entry("jis.knit.cable.6st", tier = SymbolPackTier.PRO, jaLabel = "ケーブル")),
            )
            val cat = composite(local = local, resolver = stubSub(sub = activeProSub()))

            val result = cat.get("jis.knit.cable.6st")
            assertNotNull(result)
            assertEquals("ケーブル", result.jaLabel)
        }

    @Test
    fun `free pack symbol returns regardless of subscription`() =
        runTest {
            val local = localStore()
            seed(local, pack("jis.knit.beginner"), listOf(entry("jis.knit.k")))
            val cat = composite(local = local, resolver = stubSub(sub = null))

            assertNotNull(cat.get("jis.knit.k"))
        }

    @Test
    fun `pro symbol does not fall back to bundled on entitlement miss`() =
        runTest {
            // Defense-in-depth: a Pro symbol id should NOT silently leak through
            // a bundled catalog entry of the same id when the user is not Pro.
            // Composite returns null deliberately; the renderer paints "?".
            val bundled = StaticBundledCatalog(listOf(symbolDef("jis.knit.cable.6st", jaLabel = "bundled-leak")))
            val local = localStore()
            seed(
                local,
                pack("pack.pro.cables", tier = SymbolPackTier.PRO),
                listOf(entry("jis.knit.cable.6st", tier = SymbolPackTier.PRO)),
            )
            val cat = composite(local = local, bundled = bundled, resolver = stubSub(sub = null))

            assertNull(cat.get("jis.knit.cable.6st"))
        }

    @Test
    fun `pack with newer updatedAt wins on cross-pack id collision`() =
        runTest {
            val local = localStore()
            val older =
                pack(
                    id = "pack.older",
                    version = 1,
                    updatedAt = Instant.parse("2026-05-01T00:00:00Z"),
                )
            val newer =
                pack(
                    id = "pack.newer",
                    version = 1,
                    updatedAt = Instant.parse("2026-05-06T00:00:00Z"),
                )
            seed(local, older, listOf(entry("jis.knit.k", jaLabel = "from-older")))
            seed(local, newer, listOf(entry("jis.knit.k", jaLabel = "from-newer")))
            val cat = composite(local = local)

            val result = cat.get("jis.knit.k")
            assertNotNull(result)
            assertEquals("from-newer", result.jaLabel)
        }

    @Test
    fun `version-bump round trip surfaces newest version through the catalog`() =
        runTest {
            val local = localStore()
            val v1 = pack(id = "jis.knit.beginner", version = 1)
            seed(local, v1, listOf(entry("jis.knit.k", jaLabel = "v1-label")))
            val cat = composite(local = local)
            assertEquals("v1-label", cat.get("jis.knit.k")?.jaLabel)

            // Bump pack version: upsert the pack metadata (new version) + write a new
            // payload row. LocalSymbolPackDataSource.upsertPayload deletes the older
            // version row inside the same transaction; getLatestPayload then surfaces v2.
            val v2 = pack(id = "jis.knit.beginner", version = 2)
            seed(local, v2, listOf(entry("jis.knit.k", jaLabel = "v2-label")))
            cat.refresh()

            assertEquals("v2-label", cat.get("jis.knit.k")?.jaLabel)
        }

    @Test
    fun `listByCategory merges bundled and downloaded with stable ordering`() =
        runTest {
            val bundled =
                StaticBundledCatalog(
                    listOf(symbolDef("jis.knit.a"), symbolDef("jis.knit.k")),
                )
            val local = localStore()
            seed(
                local,
                pack("jis.knit.beginner"),
                listOf(entry("jis.knit.b"), entry("jis.knit.k", jaLabel = "downloaded-k")),
            )
            val cat = composite(local = local, bundled = bundled)

            val list = cat.listByCategory(SymbolCategory.KNIT)
            assertEquals(listOf("jis.knit.a", "jis.knit.b", "jis.knit.k"), list.map { it.id })
            assertEquals("downloaded-k", list.first { it.id == "jis.knit.k" }.jaLabel)
        }

    @Test
    fun `listByCategory drops pro entries when not entitled`() =
        runTest {
            val bundled = StaticBundledCatalog(listOf(symbolDef("jis.knit.k")))
            val local = localStore()
            seed(local, pack("jis.knit.beginner"), listOf(entry("jis.knit.b")))
            seed(
                local,
                pack("pack.pro.cables", tier = SymbolPackTier.PRO),
                listOf(entry("jis.knit.cable.6st", tier = SymbolPackTier.PRO)),
            )
            val cat = composite(local = local, bundled = bundled, resolver = stubSub(sub = null))

            val list = cat.listByCategory(SymbolCategory.KNIT)
            assertEquals(listOf("jis.knit.b", "jis.knit.k"), list.map { it.id })
        }

    @Test
    fun `listByCategory returns empty for category with no entries`() =
        runTest {
            val local = localStore()
            val cat = composite(local = local)
            assertTrue(cat.listByCategory(SymbolCategory.MACHINE).isEmpty())
        }

    @Test
    fun `all combines bundled and downloaded ordered by category then id`() =
        runTest {
            val bundled =
                StaticBundledCatalog(
                    listOf(
                        symbolDef("jis.crochet.ch", category = SymbolCategory.CROCHET),
                        symbolDef("jis.knit.k"),
                    ),
                )
            val local = localStore()
            seed(
                local,
                pack("pack.knit.extra"),
                listOf(entry("jis.knit.b"), entry("jis.crochet.dc", category = SymbolCategory.CROCHET)),
            )
            val cat = composite(local = local, bundled = bundled)

            val all = cat.all().map { it.id }
            // KNIT first (enum order), then CROCHET; each sorted by id within category.
            assertEquals(listOf("jis.knit.b", "jis.knit.k", "jis.crochet.ch", "jis.crochet.dc"), all)
        }

    @Test
    fun `all drops pro entries when not entitled`() =
        runTest {
            val bundled = StaticBundledCatalog(listOf(symbolDef("jis.knit.k")))
            val local = localStore()
            seed(
                local,
                pack("pack.pro.cables", tier = SymbolPackTier.PRO),
                listOf(entry("jis.knit.cable.6st", tier = SymbolPackTier.PRO)),
            )
            val cat = composite(local = local, bundled = bundled, resolver = stubSub(sub = null))

            val all = cat.all().map { it.id }
            assertEquals(listOf("jis.knit.k"), all)
        }

    // ----- Defensive parsing ------------------------------------------------

    @Test
    fun `broken payload skips that pack but keeps the rest`() =
        runTest {
            val local = localStore()
            seed(local, pack("pack.good"), listOf(entry("jis.knit.b")))
            seed(
                local = local,
                pack = pack("pack.bad"),
                entries = emptyList(),
                bodyOverride = "{ this is not valid json",
            )
            val cat = composite(local = local)

            // Good pack still resolves; bad pack skipped silently.
            assertNotNull(cat.get("jis.knit.b"))
        }

    @Test
    fun `payload with mismatched packId is rejected`() =
        runTest {
            val local = localStore()
            local.upsertPack(pack("pack.real"))
            local.upsertPayload(
                packId = "pack.real",
                version = 1,
                // Wrong embedded packId — the body claims a different pack.
                payloadJson = payloadJson("pack.imposter", 1, listOf(entry("jis.knit.k"))),
            )
            val cat = composite(local = local)

            assertNull(cat.get("jis.knit.k"))
        }

    @Test
    fun `payload with mismatched version is rejected`() =
        runTest {
            val local = localStore()
            local.upsertPack(pack("pack.real", version = 2))
            local.upsertPayload(
                packId = "pack.real",
                version = 2,
                // Embedded version does NOT match the row's version column.
                payloadJson = payloadJson("pack.real", 7, listOf(entry("jis.knit.k"))),
            )
            val cat = composite(local = local)

            assertNull(cat.get("jis.knit.k"))
        }

    @Test
    fun `payload entry with unknown category is dropped but rest of pack survives`() =
        runTest {
            val local = localStore()
            // The mapper hard-fails on unknown SymbolCategory wire values; Composite
            // catches the IllegalStateException and keeps the rest of the pack.
            val good = entry("jis.knit.k")
            val bad =
                SymbolPackPayloadEntry(
                    id = "jis.knit.broken",
                    category = "NOT_A_REAL_CATEGORY",
                    tier = SymbolPackTier.FREE,
                    pathData = "M 0 0",
                    jaLabel = "broken",
                    enLabel = "broken",
                )
            local.upsertPack(pack("pack.partial"))
            local.upsertPayload(
                packId = "pack.partial",
                version = 1,
                payloadJson = payloadJson("pack.partial", 1, listOf(good, bad)),
            )
            val cat = composite(local = local)

            assertNotNull(cat.get("jis.knit.k"))
            assertNull(cat.get("jis.knit.broken"))
        }

    @Test
    fun `refresh reflects newly-seeded packs without rebuilding catalog`() =
        runTest {
            val local = localStore()
            val cat = composite(local = local)
            assertNull(cat.get("jis.knit.k"))

            seed(local, pack("jis.knit.beginner"), listOf(entry("jis.knit.k")))
            cat.refresh()

            assertNotNull(cat.get("jis.knit.k"))
        }

    @Test
    fun `pack-level tier overrides per-symbol tier from wire format`() =
        runTest {
            // Per-symbol tier is forward-compat (ADR-016 §3.4); pack-level tier from
            // the metadata mirror is authoritative for v1. A FREE pack with a
            // payload entry mistakenly tagged PRO MUST still render for non-Pro users.
            val local = localStore()
            seed(
                local,
                pack("pack.free", tier = SymbolPackTier.FREE),
                listOf(entry("jis.knit.k", tier = SymbolPackTier.PRO)),
            )
            val cat = composite(local = local, resolver = stubSub(sub = null))

            assertNotNull(cat.get("jis.knit.k"))
        }

    @Test
    fun `payload with higher schemaVersion than client supports is rejected`() =
        runTest {
            // ADR-016 §3.4: server splits to a new pack id on breaking schema
            // changes. Defense-in-depth: if a payload arrives with schemaVersion
            // > CURRENT_SCHEMA_VERSION the client rejects it rather than letting
            // ignoreUnknownKeys silently produce a partially-populated entry.
            val local = localStore()
            local.upsertPack(pack("pack.future"))
            val futurePayload =
                """{"pack_id":"pack.future","version":1,"schema_version":${SymbolPackPayload.CURRENT_SCHEMA_VERSION + 1},"symbols":[]}"""
            local.upsertPayload(packId = "pack.future", version = 1, payloadJson = futurePayload)
            val cat = composite(local = local)

            assertNull(cat.get("anything"))
        }

    @Test
    fun `cross-pack id collision with equal updatedAt tie-breaks deterministically on pack id`() =
        runTest {
            // Same updatedAt timestamp on two packs is plausible when the server
            // batch-updates metadata in one transaction. Without a tie-break the
            // winner depends on SQLite scan order. Tie-break on pack.id (sort
            // ascending; later iteration wins) makes "pack.z" win over "pack.a".
            val local = localStore()
            val sameTs = Instant.parse("2026-05-06T00:00:00Z")
            seed(
                local,
                pack(id = "pack.a", updatedAt = sameTs),
                listOf(entry("jis.knit.k", jaLabel = "from-pack-a")),
            )
            seed(
                local,
                pack(id = "pack.z", updatedAt = sameTs),
                listOf(entry("jis.knit.k", jaLabel = "from-pack-z")),
            )
            val cat = composite(local = local)

            // pack.z sorts after pack.a so it overwrites in the LinkedHashMap.
            assertEquals("from-pack-z", cat.get("jis.knit.k")?.jaLabel)
        }

    @Test
    fun `listByCategory keeps bundled free entry when pro pack supplies same id and user is not pro`() =
        runTest {
            // Behavioral divergence from get(): listByCategory presents what the
            // user CAN see. A bundled FREE entry shadowed by a Pro downloaded
            // entry stays visible (the Pro override is dropped). get() in the
            // same scenario returns null because render-time lookup must not
            // silently substitute a different (free) symbol for the requested
            // (Pro) one.
            val bundled = StaticBundledCatalog(listOf(symbolDef("jis.knit.k", jaLabel = "bundled-free")))
            val local = localStore()
            seed(
                local,
                pack("pack.pro.cables", tier = SymbolPackTier.PRO),
                listOf(entry("jis.knit.k", tier = SymbolPackTier.PRO, jaLabel = "downloaded-pro")),
            )
            val cat = composite(local = local, bundled = bundled, resolver = stubSub(sub = null))

            val list = cat.listByCategory(SymbolCategory.KNIT)
            assertEquals(1, list.size)
            assertEquals("bundled-free", list.first().jaLabel)
        }

    @Test
    fun `concurrent refresh callers leave a coherent snapshot`() =
        runTest {
            // Smoke test for the Mutex.withLock contract: two concurrent
            // refresh() calls must not corrupt the snapshot — one waits behind
            // the other, both observe a consistent terminal state. A subtler
            // CompletableDeferred-gated test would lock the wait-vs-trample
            // distinction even tighter; this catches the gross "snapshot torn
            // mid-rebuild" regression.
            val local = localStore()
            seed(local, pack("jis.knit.beginner"), listOf(entry("jis.knit.k")))
            val cat = composite(local = local)

            // Rebuild concurrently; both must complete without throwing and
            // leave the snapshot resolvable. coroutineScope ensures both
            // children complete before the assertion below — top-level
            // `async` inside `runTest` is deprecated.
            coroutineScope {
                val a = async { cat.refresh() }
                val b = async { cat.refresh() }
                a.await()
                b.await()
            }

            assertNotNull(cat.get("jis.knit.k"))
        }

    // ----- DownloadedSnapshot pure ------------------------------------------

    @Test
    fun `DownloadedSnapshot from empty list returns EMPTY singleton`() {
        assertEquals(DownloadedSnapshot.EMPTY, DownloadedSnapshot.from(emptyList()))
    }

    @Test
    fun `DownloadedSnapshot from sorts byCategory entries by id`() {
        val a = DownloadedEntry(symbolDef("jis.knit.a"), SymbolPackTier.FREE)
        val b = DownloadedEntry(symbolDef("jis.knit.b"), SymbolPackTier.FREE)
        val c = DownloadedEntry(symbolDef("jis.knit.c"), SymbolPackTier.FREE)
        val snap = DownloadedSnapshot.from(listOf(c, a, b))
        assertEquals(listOf("jis.knit.a", "jis.knit.b", "jis.knit.c"), snap.byCategory[SymbolCategory.KNIT]!!.map { it.definition.id })
    }

    // ----- listLockedPro (Phase 41.4) ---------------------------------------

    @Test
    fun `listLockedPro returns empty when user is pro`() =
        runTest {
            val local = localStore()
            seed(
                local,
                pack("pack.pro.cables", tier = SymbolPackTier.PRO),
                listOf(entry("jis.knit.cable.6st", tier = SymbolPackTier.PRO)),
            )
            val cat = composite(local = local, resolver = stubSub(sub = activeProSub()))
            assertEquals(emptyList(), cat.listLockedPro(SymbolCategory.KNIT))
        }

    @Test
    fun `listLockedPro excludes pro entry that shadows a bundled free symbol`() =
        runTest {
            // A Pro pack ships an entry with the same id as a bundled FREE
            // symbol — listByCategory keeps the bundled FREE for non-Pro
            // users (per the existing divergence test), so listLockedPro
            // must NOT also surface the locked-Pro shadow. Otherwise the
            // palette would render two cells for the same id.
            val bundled = StaticBundledCatalog(listOf(symbolDef("jis.knit.k")))
            val local = localStore()
            seed(
                local,
                pack("pack.pro.cables", tier = SymbolPackTier.PRO),
                listOf(entry("jis.knit.k", tier = SymbolPackTier.PRO)),
            )
            val cat = composite(local = local, bundled = bundled, resolver = stubSub(sub = null))
            assertEquals(emptyList(), cat.listLockedPro(SymbolCategory.KNIT))
        }

    @Test
    fun `listLockedPro includes novel pro entry with no bundled counterpart`() =
        runTest {
            val local = localStore()
            seed(
                local,
                pack("pack.pro.cables", tier = SymbolPackTier.PRO),
                listOf(entry("jis.knit.cable.6st", tier = SymbolPackTier.PRO)),
            )
            val cat = composite(local = local, resolver = stubSub(sub = null))
            val locked = cat.listLockedPro(SymbolCategory.KNIT)
            assertEquals(1, locked.size)
            assertEquals("jis.knit.cable.6st", locked.first().id)
        }

    @Test
    fun `listLockedPro excludes free downloaded entry`() =
        runTest {
            val local = localStore()
            seed(
                local,
                pack("pack.free.beginner", tier = SymbolPackTier.FREE),
                listOf(entry("jis.knit.k", tier = SymbolPackTier.FREE)),
            )
            val cat = composite(local = local, resolver = stubSub(sub = null))
            assertEquals(emptyList(), cat.listLockedPro(SymbolCategory.KNIT))
        }

    @Test
    fun `listLockedPro orders results by id`() =
        runTest {
            val local = localStore()
            seed(
                local,
                pack("pack.pro.cables", tier = SymbolPackTier.PRO),
                listOf(
                    entry("jis.knit.zulu", tier = SymbolPackTier.PRO),
                    entry("jis.knit.alpha", tier = SymbolPackTier.PRO),
                    entry("jis.knit.bravo", tier = SymbolPackTier.PRO),
                ),
            )
            val cat = composite(local = local, resolver = stubSub(sub = null))
            assertEquals(
                listOf("jis.knit.alpha", "jis.knit.bravo", "jis.knit.zulu"),
                cat.listLockedPro(SymbolCategory.KNIT).map { it.id },
            )
        }

    // ----- Test fakes -------------------------------------------------------

    private class StaticBundledCatalog(
        private val defs: List<SymbolDefinition>,
    ) : SymbolCatalog {
        private val byId = defs.associateBy { it.id }

        override fun get(id: String): SymbolDefinition? = byId[id]

        override fun listByCategory(category: SymbolCategory): List<SymbolDefinition> =
            defs.filter { it.category == category }.sortedBy { it.id }

        override fun all(): List<SymbolDefinition> =
            SymbolCategory.entries.flatMap { cat -> defs.filter { it.category == cat }.sortedBy { it.id } }
    }

    private class StubSubscriptionRepository(
        private val cachedFor: String?,
        private val sub: Subscription?,
    ) : SubscriptionRepository {
        override fun cachedActiveSubscription(userId: String): Subscription? = if (userId == cachedFor) sub else null

        override fun observeActiveSubscription(userId: String): Flow<Subscription?> = flowOf(if (userId == cachedFor) sub else null)

        override suspend fun refresh(userId: String): Result<Subscription?> = Result.success(sub)

        override suspend fun clearLocalCache(userId: String) = Unit
    }

    private fun symbolDef(
        id: String,
        category: SymbolCategory = SymbolCategory.KNIT,
        jaLabel: String = id,
    ) = SymbolDefinition(
        id = id,
        category = category,
        pathData = "M 0 0 L 1 1",
        jaLabel = jaLabel,
        enLabel = id,
    )
}
