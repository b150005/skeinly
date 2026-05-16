package io.github.b150005.skeinly.ui.settings

import io.github.b150005.skeinly.domain.model.DataExportBundle
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pre-Phase-40 A20 Option B — locks the [DataExportViewModel] state
 * machine + the share-sheet side-effect ordering + the re-entry guard.
 * Lambda-seam recording fakes (no supabase-kt / no platform file API),
 * mirroring `WipeDataViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataExportViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val fixedClock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-05-16T10:00:00Z")
        }

    private val sampleBundle =
        DataExportBundle(
            bundleJson = "{\"schema_version\":1,\"user_id\":\"u1\"}",
            summary = mapOf("patterns" to 3, "comments" to 1, "_avatars" to 1),
            totalRows = 5,
        )

    private class SaverRecorder {
        var calls = 0
        var lastJson: String? = null
        var lastFileName: String? = null

        fun save(
            json: String,
            fileName: String,
        ) {
            calls++
            lastJson = json
            lastFileName = fileName
        }
    }

    @Test
    fun `initial state is idle with no result`() =
        runTest {
            val vm =
                DataExportViewModel(
                    exportData = { UseCaseResult.Success(sampleBundle) },
                    saveBundle = { _, _ -> },
                    clock = fixedClock,
                )
            assertFalse(vm.state.value.isExporting)
            assertNull(vm.state.value.result)
        }

    @Test
    fun `Export success hands bundle to saver then publishes Success summary`() =
        runTest {
            val saver = SaverRecorder()
            val vm =
                DataExportViewModel(
                    exportData = { UseCaseResult.Success(sampleBundle) },
                    saveBundle = saver::save,
                    clock = fixedClock,
                )

            vm.onEvent(DataExportEvent.Export)

            assertEquals(1, saver.calls, "share sheet must fire exactly once on success")
            assertEquals(sampleBundle.bundleJson, saver.lastJson)
            assertEquals("skeinly-export-20260516.json", saver.lastFileName)

            val result = assertIs<DataExportResult.Success>(vm.state.value.result)
            assertEquals(5, result.totalRows)
            assertEquals(3, result.summary["patterns"])
            assertFalse(vm.state.value.isExporting)
        }

    @Test
    fun `Export failure publishes localized Error and does NOT invoke the saver`() =
        runTest {
            val saver = SaverRecorder()
            val vm =
                DataExportViewModel(
                    exportData = {
                        UseCaseResult.Failure(UseCaseError.RateLimited)
                    },
                    saveBundle = saver::save,
                    clock = fixedClock,
                )

            vm.onEvent(DataExportEvent.Export)

            assertEquals(0, saver.calls, "no file must be shared on failure")
            val result = assertIs<DataExportResult.Error>(vm.state.value.result)
            assertEquals(ErrorMessage.RateLimitExceeded, result.message)
            assertFalse(vm.state.value.isExporting)
        }

    @Test
    fun `second Export while in flight is a no-op re-entry guard`() =
        runTest {
            val gate = CompletableDeferred<UseCaseResult<DataExportBundle>>()
            var exportCalls = 0
            val vm =
                DataExportViewModel(
                    exportData = {
                        exportCalls++
                        gate.await()
                    },
                    saveBundle = { _, _ -> },
                    clock = fixedClock,
                )

            vm.onEvent(DataExportEvent.Export)
            assertTrue(vm.state.value.isExporting)
            // Double-tap while the first round-trip is suspended.
            vm.onEvent(DataExportEvent.Export)
            assertEquals(1, exportCalls, "in-flight export must not be re-fired")

            gate.complete(UseCaseResult.Success(sampleBundle))
            assertFalse(vm.state.value.isExporting)
            assertIs<DataExportResult.Success>(vm.state.value.result)
        }

    @Test
    fun `DismissResult clears the result panel`() =
        runTest {
            val vm =
                DataExportViewModel(
                    exportData = { UseCaseResult.Success(sampleBundle) },
                    saveBundle = { _, _ -> },
                    clock = fixedClock,
                )
            vm.onEvent(DataExportEvent.Export)
            assertIs<DataExportResult.Success>(vm.state.value.result)

            vm.onEvent(DataExportEvent.DismissResult)
            assertNull(vm.state.value.result)
        }

    @Test
    fun `a fresh Export after an error clears the prior error before retrying`() =
        runTest {
            var nextResult: UseCaseResult<DataExportBundle> =
                UseCaseResult.Failure(UseCaseError.Network(RuntimeException("offline")))
            val vm =
                DataExportViewModel(
                    exportData = { nextResult },
                    saveBundle = { _, _ -> },
                    clock = fixedClock,
                )

            vm.onEvent(DataExportEvent.Export)
            assertIs<DataExportResult.Error>(vm.state.value.result)

            nextResult = UseCaseResult.Success(sampleBundle)
            vm.onEvent(DataExportEvent.Export)
            assertIs<DataExportResult.Success>(vm.state.value.result)
        }
}
