package io.github.b150005.skeinly.data.sync

import io.github.b150005.skeinly.data.local.LocalSymbolPackDataSource
import io.github.b150005.skeinly.data.remote.SymbolPackDownloadResult
import io.github.b150005.skeinly.data.remote.SymbolPackRemoteOperations
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.db.createTestDriver
import io.github.b150005.skeinly.domain.model.SymbolPack
import io.github.b150005.skeinly.domain.model.SymbolPackPayload
import io.github.b150005.skeinly.domain.model.SymbolPackPayloadEntry
import io.github.b150005.skeinly.domain.model.SymbolPackTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

class SymbolPackSyncManagerTest {
    private lateinit var local: LocalSymbolPackDataSource
    private lateinit var remote: FakeSymbolPackRemote
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        val db = SkeinlyDatabase(createTestDriver())
        local = LocalSymbolPackDataSource(db, Dispatchers.Unconfined)
        remote = FakeSymbolPackRemote()
    }

    private fun manager(remote: SymbolPackRemoteOperations? = this.remote): SymbolPackSyncManager =
        SymbolPackSyncManager(remote = remote, local = local, json = json)

    private fun pack(
        id: String = "free.knit",
        tier: SymbolPackTier = SymbolPackTier.FREE,
        version: Int = 1,
    ) = SymbolPack(
        id = id,
        tier = tier,
        version = version,
        displayName = id,
        description = null,
        payloadPath = "$id/$version/payload.json",
        payloadSize = 100,
        symbolCount = 1,
        signedUntil = null,
        createdAt = Instant.parse("2026-05-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-06T08:00:00Z"),
    )

    private fun payloadFor(
        id: String,
        version: Int,
    ): SymbolPackPayload =
        SymbolPackPayload(
            packId = id,
            version = version,
            schemaVersion = 1,
            symbols =
                listOf(
                    SymbolPackPayloadEntry(
                        id = "$id.glyph",
                        category = "KNIT",
                        tier = SymbolPackTier.FREE,
                        pathData = "M0,0 L1,1",
                        jaLabel = "ja",
                        enLabel = "en",
                    ),
                ),
        )

    // ----- Happy path --------------------------------------------------------

    @Test
    fun `sync downloads every missing pack and writes payload through to local`() =
        runTest {
            val a = pack(id = "free.a", version = 1)
            val b = pack(id = "free.b", version = 2)
            remote.manifest = listOf(a, b)
            remote.payloadFor("free.a", 1, payloadFor("free.a", 1))
            remote.payloadFor("free.b", 2, payloadFor("free.b", 2))

            val result = manager().sync()

            val completed = assertCompleted(result)
            assertEquals(2, completed.outcomes.size)
            assertTrue(completed.outcomes.all { it is PackSyncOutcome.Downloaded })
            assertEquals(2, local.getAllPacks().size)
            assertNotNull(local.getLatestPayload("free.a"))
            assertNotNull(local.getLatestPayload("free.b"))
        }

    @Test
    fun `sync short-circuits when local payload already matches manifest version`() =
        runTest {
            val a = pack(id = "free.a", version = 5)
            remote.manifest = listOf(a)
            // Pre-seed the local payload at the same version.
            local.upsertPayload("free.a", 5, """{"already":"there"}""")

            val result = manager().sync()

            val completed = assertCompleted(result)
            assertEquals(1, completed.outcomes.size)
            val outcome = completed.outcomes.single()
            assertTrue(outcome is PackSyncOutcome.AlreadyUpToDate, "expected AlreadyUpToDate, was $outcome")
            assertEquals(5, outcome.version)
            // Crucially: requestDownload was NOT called for the up-to-date pack.
            assertEquals(0, remote.downloadCallCount)
        }

    @Test
    fun `sync downloads a newer-version pack and the local store drops the older payload`() =
        runTest {
            // Seed v3 locally, manifest reports v5 — sync downloads v5 and the
            // old v3 row is dropped atomically by upsertPayload.
            local.upsertPayload("free.a", 3, """{"old":3}""")
            remote.manifest = listOf(pack(id = "free.a", version = 5))
            remote.payloadFor("free.a", 5, payloadFor("free.a", 5))

            val result = manager().sync()

            assertCompleted(result)
            val latest = local.getLatestPayload("free.a")
            assertNotNull(latest)
            assertEquals(5, latest.version)
            // v3 is gone — only one row remains for the pack.
            assertFalse(local.hasPayloadForVersion("free.a", 3))
        }

    // ----- Version-regression guard -----------------------------------------

    @Test
    fun `version regression keeps higher local payload and surfaces VersionRegression outcome`() =
        runTest {
            // Local at v5; manifest reports v3 (regression — never legitimate
            // per ADR-016 §4.3). We must NOT overwrite v5 with v3.
            local.upsertPayload("free.a", 5, """{"keeper":5}""")
            remote.manifest = listOf(pack(id = "free.a", version = 3))

            val result = manager().sync()

            val completed = assertCompleted(result)
            val outcome = completed.outcomes.single()
            assertTrue(outcome is PackSyncOutcome.VersionRegression, "expected VersionRegression, was $outcome")
            assertEquals(5, outcome.cachedVersion)
            assertEquals(3, outcome.serverVersion)
            // Crucially: the higher-version cached payload survives.
            assertEquals(5, local.getLatestPayload("free.a")?.version)
            assertEquals(0, remote.downloadCallCount)
        }

    // ----- Per-pack failure modes -------------------------------------------

    @Test
    fun `403 pro_entitlement_required surfaces SkippedProEntitlement and skips persisting`() =
        runTest {
            val pro = pack(id = "pro.advanced", tier = SymbolPackTier.PRO, version = 1)
            remote.manifest = listOf(pro)
            remote.failureFor("pro.advanced", SymbolPackDownloadResult.Failure.ProEntitlementRequired("pro.advanced"))

            val result = manager().sync()

            val completed = assertCompleted(result)
            val outcome = completed.outcomes.single()
            assertTrue(outcome is PackSyncOutcome.SkippedProEntitlement)
            assertEquals("pro.advanced", outcome.packId)
            assertEquals(0, local.getAllPayloads().size)
        }

    @Test
    fun `404 pack_not_found surfaces SkippedPackNotFound`() =
        runTest {
            remote.manifest = listOf(pack(id = "free.ghost"))
            remote.failureFor("free.ghost", SymbolPackDownloadResult.Failure.PackNotFound("free.ghost"))

            val result = manager().sync()

            val outcome = assertCompleted(result).outcomes.single()
            assertTrue(outcome is PackSyncOutcome.SkippedPackNotFound)
        }

    @Test
    fun `429 rate_limited surfaces SkippedRateLimited carrying retry_after_seconds`() =
        runTest {
            remote.manifest = listOf(pack(id = "free.rl"))
            remote.failureFor("free.rl", SymbolPackDownloadResult.Failure.RateLimited(retryAfterSeconds = 90))

            val result = manager().sync()

            val outcome = assertCompleted(result).outcomes.single()
            assertTrue(outcome is PackSyncOutcome.SkippedRateLimited)
            assertEquals(90, outcome.retryAfterSeconds)
        }

    @Test
    fun `401 unauthenticated surfaces SkippedUnauthenticated`() =
        runTest {
            remote.manifest = listOf(pack(id = "free.auth"))
            remote.failureFor("free.auth", SymbolPackDownloadResult.Failure.Unauthenticated)

            val result = manager().sync()

            assertTrue(assertCompleted(result).outcomes.single() is PackSyncOutcome.SkippedUnauthenticated)
        }

    @Test
    fun `Network failure surfaces NetworkError outcome`() =
        runTest {
            remote.manifest = listOf(pack(id = "free.net"))
            remote.failureFor("free.net", SymbolPackDownloadResult.Failure.Network(IllegalStateException("boom")))

            val result = manager().sync()

            val outcome = assertCompleted(result).outcomes.single()
            assertTrue(outcome is PackSyncOutcome.NetworkError)
            assertEquals("boom", outcome.cause.message)
        }

    @Test
    fun `Parse failure surfaces ParseError outcome`() =
        runTest {
            remote.manifest = listOf(pack(id = "free.parse"))
            remote.failureFor("free.parse", SymbolPackDownloadResult.Failure.Parse(IllegalArgumentException("not-json")))

            val result = manager().sync()

            val outcome = assertCompleted(result).outcomes.single()
            assertTrue(outcome is PackSyncOutcome.ParseError)
        }

    @Test
    fun `Unknown HTTP status surfaces UnknownError carrying status code`() =
        runTest {
            remote.manifest = listOf(pack(id = "free.weird"))
            remote.failureFor("free.weird", SymbolPackDownloadResult.Failure.Unknown(statusCode = 502, body = "Bad Gateway"))

            val result = manager().sync()

            val outcome = assertCompleted(result).outcomes.single()
            assertTrue(outcome is PackSyncOutcome.UnknownError)
            assertEquals(502, outcome.statusCode)
        }

    @Test
    fun `single pack failure does not stop other packs in the same cycle`() =
        runTest {
            // Mixed batch: pack A fails (403), pack B succeeds. Both outcomes
            // surface and pack B's payload lands locally.
            val a = pack(id = "pro.fail", tier = SymbolPackTier.PRO)
            val b = pack(id = "free.ok", version = 1)
            remote.manifest = listOf(a, b)
            remote.failureFor("pro.fail", SymbolPackDownloadResult.Failure.ProEntitlementRequired("pro.fail"))
            remote.payloadFor("free.ok", 1, payloadFor("free.ok", 1))

            val result = manager().sync()

            val outcomes = assertCompleted(result).outcomes
            assertEquals(2, outcomes.size)
            assertTrue(outcomes.any { it is PackSyncOutcome.SkippedProEntitlement && it.packId == "pro.fail" })
            assertTrue(outcomes.any { it is PackSyncOutcome.Downloaded && it.packId == "free.ok" })
            assertEquals(1, local.getAllPayloads().size)
        }

    // ----- Manifest fetch + persist failures --------------------------------

    @Test
    fun `manifest fetch failure surfaces ManifestFetchFailed without touching local cache`() =
        runTest {
            // Pre-seed something to confirm the manifest-fetch failure path
            // does NOT clear the existing local cache.
            local.upsertPack(pack(id = "preexisting.pack"))
            remote.manifestError = IllegalStateException("DNS down")

            val result = manager().sync()

            assertTrue(result is SyncCycleResult.ManifestFetchFailed, "expected ManifestFetchFailed, was $result")
            assertEquals("DNS down", result.cause.message)
            // Local cache untouched.
            assertEquals(1, local.getAllPacks().size)
        }

    // ----- Skip semantics ---------------------------------------------------

    @Test
    fun `sync without remote returns Skipped with remote_unavailable reason`() =
        runTest {
            val skipResult = manager(remote = null).sync()
            assertTrue(skipResult is SyncCycleResult.Skipped)
            assertEquals("remote_unavailable", skipResult.reason)
        }

    @Test
    fun `empty manifest produces a Completed cycle with no outcomes`() =
        runTest {
            remote.manifest = emptyList()
            val result = manager().sync()
            val completed = assertCompleted(result)
            assertTrue(completed.outcomes.isEmpty())
            assertTrue(local.getAllPacks().isEmpty())
        }

    @Test
    fun `sync replaces the manifest mirror so packs no longer in the manifest disappear`() =
        runTest {
            // Pre-seed a stale entry; manifest no longer carries it.
            local.upsertPack(pack(id = "free.gone"))
            remote.manifest = listOf(pack(id = "free.kept"))
            remote.payloadFor("free.kept", 1, payloadFor("free.kept", 1))

            manager().sync()

            val cached = local.getAllPacks().map { it.id }
            assertEquals(listOf("free.kept"), cached)
        }

    // ----- State + concurrency contract --------------------------------------

    @Test
    fun `state ends in Idle after a clean cycle with no hard failures`() =
        runTest {
            remote.manifest = listOf(pack(id = "free.a", version = 1))
            remote.payloadFor("free.a", 1, payloadFor("free.a", 1))

            val mgr = manager()
            mgr.sync()

            assertEquals(SyncState.Idle, mgr.state.value)
        }

    @Test
    fun `state ends in PartiallyFailed when at least one pack hits a hard error`() =
        runTest {
            // Network error on free.a, success on free.b — cycle completes
            // but the terminal state surfaces the partial failure for the
            // Settings observer.
            remote.manifest = listOf(pack(id = "free.a"), pack(id = "free.b"))
            remote.failureFor("free.a", SymbolPackDownloadResult.Failure.Network(IllegalStateException("flaky")))
            remote.payloadFor("free.b", 1, payloadFor("free.b", 1))

            val mgr = manager()
            mgr.sync()

            assertEquals(SyncState.PartiallyFailed, mgr.state.value)
        }

    @Test
    fun `concurrent sync call returns Skipped sync_already_in_flight while a prior cycle is suspended`() =
        runTest {
            // Gate the first cycle's fetchManifest on a deferred so we can
            // confirm the second concurrent call sees the mutex is held.
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val gatedRemote = GatedManifestRemote(gate)
            gatedRemote.manifest = listOf(pack(id = "free.a", version = 1))
            gatedRemote.payloadFor("free.a", 1, payloadFor("free.a", 1))

            val mgr = manager(remote = gatedRemote)

            // Launch the first sync; it suspends inside fetchManifest.
            // The TestScope receiver of runTest provides the structured
            // concurrency parent for `async`.
            val first = async { mgr.sync() }
            // Yield so first reaches the suspended fetchManifest call.
            kotlinx.coroutines.yield()

            val second = mgr.sync()
            assertTrue(second is SyncCycleResult.Skipped)
            assertEquals("sync_already_in_flight", second.reason)

            // Release the gate; the first cycle finishes cleanly.
            gate.complete(Unit)
            val firstResult = first.await()
            assertCompleted(firstResult)
        }

    @Test
    fun `state stays Idle when only soft-skip outcomes occur`() =
        runTest {
            // Pro entitlement + rate limit + 404 are all soft skips that next
            // sync retries naturally; they do not flip the cycle to
            // PartiallyFailed.
            remote.manifest =
                listOf(
                    pack(id = "pro.fail", tier = SymbolPackTier.PRO),
                    pack(id = "free.rl"),
                    pack(id = "free.ghost"),
                )
            remote.failureFor("pro.fail", SymbolPackDownloadResult.Failure.ProEntitlementRequired("pro.fail"))
            remote.failureFor("free.rl", SymbolPackDownloadResult.Failure.RateLimited(60))
            remote.failureFor("free.ghost", SymbolPackDownloadResult.Failure.PackNotFound("free.ghost"))

            val mgr = manager()
            mgr.sync()

            assertEquals(SyncState.Idle, mgr.state.value)
        }

    // ----- Helpers ----------------------------------------------------------

    private fun assertCompleted(result: SyncCycleResult): SyncCycleResult.Completed {
        if (result !is SyncCycleResult.Completed) {
            fail("expected Completed, was $result")
        }
        return result
    }
}

/**
 * In-memory [SymbolPackRemoteOperations] for sync orchestrator tests. Keyed
 * by `(packId, version)` so a test can assert which version was requested.
 */
private class FakeSymbolPackRemote : SymbolPackRemoteOperations {
    var manifest: List<SymbolPack> = emptyList()
    var manifestError: Throwable? = null

    private val payloads = mutableMapOf<String, SymbolPackDownloadResult.Success>()
    private val failures = mutableMapOf<String, SymbolPackDownloadResult.Failure>()

    var downloadCallCount: Int = 0
        private set

    fun payloadFor(
        packId: String,
        version: Int,
        payload: SymbolPackPayload,
    ) {
        payloads[packId] = SymbolPackDownloadResult.Success(payload, version)
    }

    fun failureFor(
        packId: String,
        failure: SymbolPackDownloadResult.Failure,
    ) {
        failures[packId] = failure
    }

    override suspend fun fetchManifest(): List<SymbolPack> {
        manifestError?.let { throw it }
        return manifest
    }

    override suspend fun requestDownload(packId: String): SymbolPackDownloadResult {
        downloadCallCount++
        failures[packId]?.let { return it }
        payloads[packId]?.let { return it }
        return SymbolPackDownloadResult.Failure.PackNotFound(packId)
    }
}

/**
 * Variant whose [fetchManifest] suspends on a caller-supplied gate. Used by
 * the concurrency-contract test so a second [SymbolPackSyncManager.sync]
 * caller predictably observes the mutex is held.
 */
private class GatedManifestRemote(
    private val gate: kotlinx.coroutines.CompletableDeferred<Unit>,
) : SymbolPackRemoteOperations {
    var manifest: List<SymbolPack> = emptyList()
    private val payloads = mutableMapOf<String, SymbolPackDownloadResult.Success>()

    fun payloadFor(
        packId: String,
        version: Int,
        payload: SymbolPackPayload,
    ) {
        payloads[packId] = SymbolPackDownloadResult.Success(payload, version)
    }

    override suspend fun fetchManifest(): List<SymbolPack> {
        gate.await()
        return manifest
    }

    override suspend fun requestDownload(packId: String): SymbolPackDownloadResult =
        payloads[packId] ?: SymbolPackDownloadResult.Failure.PackNotFound(packId)
}
