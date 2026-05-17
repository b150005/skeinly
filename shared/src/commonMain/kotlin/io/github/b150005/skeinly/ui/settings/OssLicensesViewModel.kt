package io.github.b150005.skeinly.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.domain.model.OssLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pre-Phase-40 A33 — drives the in-app "Open Source Licenses" screen
 * (Settings → About → Open Source Licenses).
 *
 * **Flow**: the library list is build-time-static (parsed from the
 * bundled `aboutlibraries.json`), so unlike the user-triggered
 * [DataExportViewModel] this loads automatically on [init]. A
 * parse/resource failure surfaces [OssLicensesState.hasError] with a
 * Retry affordance rather than crashing the screen.
 *
 * **Lambda-seam DI** (same precedent as [DataExportViewModel] /
 * `WipeDataViewModel`): the production binding in `ViewModelModule`
 * wires [loadLibraries] to the resource-reading + `OssLibraryParser`
 * loader, which touches the Compose-resources runtime; the seam keeps
 * commonTest off that runtime by injecting a recording/throwing lambda.
 *
 * **Re-entry guard** ([OssLicensesState.isLoading]): a Retry tapped
 * while the first load is still in flight is a no-op, mirroring the
 * Export/BugReport double-tap precedent.
 */
data class OssLicensesState(
    /** True while [loadLibraries] is in flight. Defaults to `true`
     *  because the VM kicks off the load in [init]; starting in the
     *  loading state makes both platform screens render the spinner from
     *  the first frame instead of momentarily flashing the empty/error
     *  branch before the coroutine publishes `isLoading = true`. */
    val isLoading: Boolean = true,
    /** Parsed, name-sorted dependency list. Empty before the first
     *  successful load or after a failure. */
    val libraries: List<OssLibrary> = emptyList(),
    /** True when the most recent load threw (malformed JSON / missing
     *  bundled resource). The screen shows a Retry button. */
    val hasError: Boolean = false,
)

sealed interface OssLicensesEvent {
    /** Re-attempt the load after a failure. No-op while a load is
     *  already in flight (re-entry guard). */
    data object Retry : OssLicensesEvent
}

class OssLicensesViewModel(
    private val loadLibraries: suspend () -> List<OssLibrary>,
) : ViewModel() {
    private val _state = MutableStateFlow(OssLicensesState())
    val state: StateFlow<OssLicensesState> = _state.asStateFlow()

    /**
     * Re-entry guard, kept independent of [OssLicensesState.isLoading]:
     * the displayed `isLoading` defaults to `true` (so [init]'s load
     * would be guarded out if the guard read the state flag), so a
     * dedicated in-flight latch is used instead. Only ever touched from
     * the Main dispatcher ([onEvent] and the [viewModelScope] coroutine
     * body), so a plain `var` is race-free here — same threading model
     * as `DataExportViewModel`'s `isExporting` state read.
     */
    private var inFlight = false

    init {
        load()
    }

    fun onEvent(event: OssLicensesEvent) {
        when (event) {
            OssLicensesEvent.Retry -> load()
        }
    }

    private fun load() {
        // Re-entry guard — a Retry tap while the initial load (or a
        // prior Retry) is still suspended must not start a second load.
        if (inFlight) return
        inFlight = true

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, hasError = false) }
            val result =
                try {
                    Result.success(loadLibraries())
                } catch (cancellation: CancellationException) {
                    // Structured-concurrency cancellation must propagate,
                    // never be reported as a load error.
                    inFlight = false
                    throw cancellation
                } catch (t: Throwable) {
                    Result.failure(t)
                }
            _state.update { prev ->
                result.fold(
                    onSuccess = { libs ->
                        prev.copy(
                            isLoading = false,
                            libraries = libs,
                            hasError = false,
                        )
                    },
                    onFailure = {
                        prev.copy(
                            isLoading = false,
                            libraries = emptyList(),
                            hasError = true,
                        )
                    },
                )
            }
            inFlight = false
        }
    }
}
