package io.github.b150005.skeinly.ui.settings

import io.github.b150005.skeinly.domain.model.OssLibrary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pre-Phase-40 A33 — locks the [OssLicensesViewModel] auto-load on init,
 * the error path with Retry recovery, and the in-flight re-entry guard.
 * Lambda-seam recording fakes (no Compose-resources runtime), mirroring
 * `DataExportViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OssLicensesViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val sample =
        listOf(
            OssLibrary(
                uniqueId = "io.coil-kt.coil3:coil",
                name = "Coil",
                version = "3.4.0",
                license = "Apache-2.0",
                licenseUrl = "https://l",
                url = "https://coil",
            ),
            OssLibrary(
                uniqueId = "io.ktor:ktor-client-core",
                name = "Ktor",
                version = "3.4.3",
                license = "Apache-2.0",
                licenseUrl = null,
                url = null,
            ),
        )

    @Test
    fun `initial state defaults to loading before the load settles`() =
        runTest {
            // A loader that never completes — the VM is observed in its
            // post-init, pre-settle state. Locks the `isLoading = true`
            // default that makes both screens show the spinner from the
            // first frame (no error/empty flash).
            val gate = CompletableDeferred<List<OssLibrary>>()
            val vm = OssLicensesViewModel(loadLibraries = { gate.await() })

            val state = vm.state.value
            assertTrue(state.isLoading)
            assertFalse(state.hasError)
            assertTrue(state.libraries.isEmpty())
        }

    @Test
    fun `init auto-loads libraries and clears loading`() =
        runTest {
            val vm = OssLicensesViewModel(loadLibraries = { sample })

            assertFalse(vm.state.value.isLoading)
            assertFalse(vm.state.value.hasError)
            assertEquals(sample, vm.state.value.libraries)
        }

    @Test
    fun `loadLibraries is invoked exactly once on init`() =
        runTest {
            var calls = 0
            OssLicensesViewModel(
                loadLibraries = {
                    calls++
                    sample
                },
            )
            assertEquals(1, calls)
        }

    @Test
    fun `load failure sets hasError with an empty list and no loading`() =
        runTest {
            val vm =
                OssLicensesViewModel(
                    loadLibraries = { throw RuntimeException("missing bundled resource") },
                )

            val state = vm.state.value
            assertFalse(state.isLoading)
            assertTrue(state.hasError)
            assertTrue(state.libraries.isEmpty())
        }

    @Test
    fun `Retry after a failure reloads successfully and clears the error`() =
        runTest {
            var shouldFail = true
            val vm =
                OssLicensesViewModel(
                    loadLibraries = {
                        if (shouldFail) throw RuntimeException("boom") else sample
                    },
                )
            assertTrue(vm.state.value.hasError)

            shouldFail = false
            vm.onEvent(OssLicensesEvent.Retry)

            assertFalse(vm.state.value.hasError)
            assertEquals(sample, vm.state.value.libraries)
        }

    @Test
    fun `Retry while a load is in flight is a no-op re-entry guard`() =
        runTest {
            val gate = CompletableDeferred<List<OssLibrary>>()
            var calls = 0
            val vm =
                OssLicensesViewModel(
                    loadLibraries = {
                        calls++
                        gate.await()
                    },
                )

            // init's load is suspended on the gate.
            assertTrue(vm.state.value.isLoading)
            vm.onEvent(OssLicensesEvent.Retry)
            assertEquals(1, calls, "an in-flight load must not be re-fired")

            gate.complete(sample)
            assertFalse(vm.state.value.isLoading)
            assertEquals(sample, vm.state.value.libraries)
        }
}
