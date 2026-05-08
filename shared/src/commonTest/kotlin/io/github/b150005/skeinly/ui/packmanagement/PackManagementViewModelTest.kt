package io.github.b150005.skeinly.ui.packmanagement

import io.github.b150005.skeinly.data.sync.PackSyncOutcome
import io.github.b150005.skeinly.data.sync.SyncCycleResult
import io.github.b150005.skeinly.domain.model.SymbolPackTier
import io.github.b150005.skeinly.domain.symbol.PackInventory
import io.github.b150005.skeinly.domain.symbol.PackRow
import io.github.b150005.skeinly.domain.symbol.PackStatus
import io.github.b150005.skeinly.domain.symbol.SymbolPackCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PackManagementViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Phase 41.5 (ADR-016 §41.5.3) — VM tests now exercise the state
     * machine + sync dispatch wiring against a [FakeSymbolPackCatalog].
     * The actual gate-vs-status fold + ordering + total-bytes math
     * lives in [DefaultSymbolPackCatalogTest] now that the catalog
     * owns the gate per §41.5.1.
     */
    private class FakeSymbolPackCatalog(
        var inventory: PackInventory = PackInventory(rows = emptyList(), totalDownloadedBytes = 0L),
    ) : SymbolPackCatalog {
        var listInventoryCallCount: Int = 0
        var nextError: Throwable? = null

        override suspend fun listInventory(): PackInventory {
            listInventoryCallCount++
            nextError?.let {
                nextError = null
                throw it
            }
            return inventory
        }
    }

    private fun row(
        packId: String,
        tier: SymbolPackTier = SymbolPackTier.FREE,
        serverVersion: Int = 1,
        downloadedVersion: Int? = 1,
        status: PackStatus = PackStatus.Downloaded,
        payloadSize: Int = 1024,
    ) = PackRow(
        packId = packId,
        displayName = packId,
        description = null,
        tier = tier,
        serverVersion = serverVersion,
        symbolCount = 4,
        payloadSize = payloadSize,
        downloadedVersion = downloadedVersion,
        status = status,
    )

    // ----- load --------------------------------------------------------------

    @Test
    fun `init dispatches a single load and forwards inventory verbatim`() =
        runTest {
            val rows =
                listOf(
                    row("free.alpha", payloadSize = 100),
                    row("free.beta", downloadedVersion = null, status = PackStatus.NotDownloaded, payloadSize = 200),
                )
            val catalog = FakeSymbolPackCatalog(PackInventory(rows = rows, totalDownloadedBytes = 100L))
            val vm = PackManagementViewModel(symbolPackCatalog = catalog)
            advanceUntilIdle()

            val state = vm.state.value
            assertFalse(state.isLoading)
            assertEquals(rows, state.rows)
            assertEquals(100L, state.totalDownloadedBytes)
            assertNull(state.error)
            assertEquals(1, catalog.listInventoryCallCount)
        }

    @Test
    fun `init load surfacing an exception sets error and clears loading`() =
        runTest {
            val catalog = FakeSymbolPackCatalog().apply { nextError = RuntimeException("boom") }
            val vm = PackManagementViewModel(symbolPackCatalog = catalog)
            advanceUntilIdle()

            val state = vm.state.value
            assertFalse(state.isLoading)
            assertEquals("boom", state.error)
            assertTrue(state.rows.isEmpty())
        }

    @Test
    fun `init load with empty inventory produces empty rows and zero total`() =
        runTest {
            val vm = PackManagementViewModel(symbolPackCatalog = FakeSymbolPackCatalog())
            advanceUntilIdle()

            val state = vm.state.value
            assertFalse(state.isLoading)
            assertTrue(state.rows.isEmpty())
            assertEquals(0L, state.totalDownloadedBytes)
            assertNull(state.error)
        }

    // ----- refresh -----------------------------------------------------------

    @Test
    fun `refresh dispatches sync and re-reads the catalog`() =
        runTest {
            val initial = PackInventory(rows = listOf(row("free.a", serverVersion = 1)), totalDownloadedBytes = 1024L)
            val updated =
                PackInventory(
                    rows = listOf(row("free.a", serverVersion = 2, downloadedVersion = 2)),
                    totalDownloadedBytes = 1024L,
                )
            val catalog = FakeSymbolPackCatalog(initial)
            var syncCallCount = 0
            val dispatch: PackSyncDispatch = {
                syncCallCount++
                catalog.inventory = updated
                SyncCycleResult.Completed(outcomes = listOf(PackSyncOutcome.Downloaded("free.a", version = 2)))
            }
            val vm = PackManagementViewModel(symbolPackCatalog = catalog, syncDispatch = dispatch)
            advanceUntilIdle()

            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()

            assertEquals(1, syncCallCount)
            assertEquals(updated.rows, vm.state.value.rows)
            assertFalse(vm.state.value.isRefreshing)
            // 1 init load + 1 refresh re-read = 2 catalog calls.
            assertEquals(2, catalog.listInventoryCallCount)
        }

    @Test
    fun `refresh without sync dispatch still re-reads the catalog`() =
        runTest {
            // Local-only dev path: no sync manager wired. The catalog's
            // backing store just got updated by a hypothetical foreground
            // hook, and the user taps Refresh to see the result. We must
            // NOT throw or surface an error.
            val catalog = FakeSymbolPackCatalog(PackInventory(rows = listOf(row("free.a")), totalDownloadedBytes = 1024L))
            val vm = PackManagementViewModel(symbolPackCatalog = catalog, syncDispatch = null)
            advanceUntilIdle()

            // Mutate the underlying inventory to simulate a downstream change.
            catalog.inventory =
                PackInventory(
                    rows = listOf(row("free.a"), row("free.b", payloadSize = 250)),
                    totalDownloadedBytes = 1024L + 250L,
                )

            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()

            val state = vm.state.value
            assertFalse(state.isRefreshing)
            assertEquals(2, state.rows.size)
            assertNull(state.error)
            assertEquals(2, catalog.listInventoryCallCount)
        }

    @Test
    fun `refresh surfaces manifest fetch failure as error`() =
        runTest {
            val catalog = FakeSymbolPackCatalog(PackInventory(rows = listOf(row("free.a")), totalDownloadedBytes = 1024L))
            val dispatch: PackSyncDispatch = {
                SyncCycleResult.ManifestFetchFailed(cause = RuntimeException("network down"))
            }
            val vm = PackManagementViewModel(symbolPackCatalog = catalog, syncDispatch = dispatch)
            advanceUntilIdle()

            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()

            val error = vm.state.value.error
            assertNotNull(error)
            assertTrue(error.contains("network down"))
            assertFalse(vm.state.value.isRefreshing)
        }

    @Test
    fun `refresh surfaces manifest persist failure as error`() =
        runTest {
            val catalog = FakeSymbolPackCatalog(PackInventory(rows = listOf(row("free.a")), totalDownloadedBytes = 1024L))
            val dispatch: PackSyncDispatch = {
                SyncCycleResult.ManifestPersistFailed(cause = RuntimeException("disk full"))
            }
            val vm = PackManagementViewModel(symbolPackCatalog = catalog, syncDispatch = dispatch)
            advanceUntilIdle()

            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()

            val error = vm.state.value.error
            assertNotNull(error)
            assertTrue(error.contains("disk full"))
            assertFalse(vm.state.value.isRefreshing)
        }

    @Test
    fun `refresh ignores re-entry while a cycle is in flight`() =
        runTest {
            // Gate the first dispatch so it suspends until we explicitly
            // complete the deferred — without this, UnconfinedTestDispatcher
            // runs the entire refresh synchronously and isRefreshing flips
            // back before the second tap is observable. This is the same
            // pattern as `concurrent refresh callers leave coherent
            // snapshot` in CompositeSymbolCatalogTest.
            val catalog = FakeSymbolPackCatalog(PackInventory(rows = listOf(row("free.a")), totalDownloadedBytes = 1024L))
            var syncCallCount = 0
            val gate = CompletableDeferred<SyncCycleResult>()
            val dispatch: PackSyncDispatch = {
                syncCallCount++
                gate.await()
            }
            val vm = PackManagementViewModel(symbolPackCatalog = catalog, syncDispatch = dispatch)
            advanceUntilIdle()

            // First tap — suspends inside the dispatch.
            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()
            assertTrue(vm.state.value.isRefreshing)
            assertEquals(1, syncCallCount)

            // Second tap while the first is suspended — short-circuits via
            // the in-flight guard, dispatch is NOT called again.
            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()
            assertEquals(1, syncCallCount)

            // Release the first refresh and let it complete cleanly.
            gate.complete(SyncCycleResult.Skipped(reason = "test"))
            advanceUntilIdle()
            assertFalse(vm.state.value.isRefreshing)
        }

    @Test
    fun `refresh surfaces unexpected exception as error and clears refreshing`() =
        runTest {
            val catalog = FakeSymbolPackCatalog(PackInventory(rows = listOf(row("free.a")), totalDownloadedBytes = 1024L))
            val dispatch: PackSyncDispatch = {
                throw RuntimeException("boom")
            }
            val vm = PackManagementViewModel(symbolPackCatalog = catalog, syncDispatch = dispatch)
            advanceUntilIdle()

            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()

            assertEquals("boom", vm.state.value.error)
            assertFalse(vm.state.value.isRefreshing)
        }

    @Test
    fun `refresh after sync re-read failure surfaces error and clears refreshing`() =
        runTest {
            // The sync dispatch returned Completed (no error from the
            // sync layer), but the post-sync catalog re-read raised —
            // either a transient SQLDelight failure or a payload parse
            // regression. Should land error + clear refreshing.
            val catalog = FakeSymbolPackCatalog(PackInventory(rows = listOf(row("free.a")), totalDownloadedBytes = 1024L))
            val dispatch: PackSyncDispatch = { SyncCycleResult.Completed(outcomes = emptyList()) }
            val vm = PackManagementViewModel(symbolPackCatalog = catalog, syncDispatch = dispatch)
            advanceUntilIdle()

            catalog.nextError = RuntimeException("re-read failed")
            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()

            assertEquals("re-read failed", vm.state.value.error)
            assertFalse(vm.state.value.isRefreshing)
        }

    @Test
    fun `clear error nulls the error field without touching other state`() =
        runTest {
            val rows = listOf(row("free.a"))
            val catalog = FakeSymbolPackCatalog(PackInventory(rows = rows, totalDownloadedBytes = 1024L))
            val dispatch: PackSyncDispatch = {
                SyncCycleResult.ManifestFetchFailed(cause = RuntimeException("nope"))
            }
            val vm = PackManagementViewModel(symbolPackCatalog = catalog, syncDispatch = dispatch)
            advanceUntilIdle()
            vm.onEvent(PackManagementEvent.Refresh)
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)

            val rowsBefore = vm.state.value.rows
            vm.onEvent(PackManagementEvent.ClearError)

            assertNull(vm.state.value.error)
            assertEquals(rowsBefore, vm.state.value.rows)
        }
}
