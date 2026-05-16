package io.github.b150005.skeinly.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.domain.model.DataExportBundle
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toErrorMessage
import io.github.b150005.skeinly.ui.util.formatDateStamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Pre-Phase-40 A20 Option B (docs/en/ops/data-export-sop.md §Scope
 * deferrals) — drives the in-app "Export My Data" screen.
 *
 * **Flow**: Idle → (Export tapped) → exporting → on success the bundle
 * JSON is handed to [saveBundle] (the platform share sheet fires) and
 * the screen shows a [DataExportResult.Success] summary; on failure a
 * [DataExportResult.Error] with a localized [ErrorMessage] (the user
 * can re-tap Export without leaving the screen).
 *
 * **Lambda-seam DI** (mirrors `WipeDataViewModel` / `BugReportPreviewViewModel`):
 * keeps the VM testable in commonTest without standing up supabase-kt
 * or a platform file/share API. Production DI binds [exportData] to
 * `DataExportRepository::export` and [saveBundle] to
 * `DataExportSaver::save`; tests inject recording lambdas.
 *
 * **Idempotency guard** (matches the Settings/BugReport precedent): a
 * double-tap during the round-trip does NOT queue two exports — the
 * [DataExportState.isExporting] flag short-circuits the second event.
 * Important here because each export is a full-account server
 * composition + a per-user rate-limited Edge Function call.
 *
 * **Side-effect ordering**: [saveBundle] is invoked BEFORE the success
 * state is published so the share sheet is already presenting when the
 * user sees the "export ready" summary — they don't tap a dead button
 * waiting for the sheet.
 */
data class DataExportState(
    /** True while the export round-trip + bundle composition is in flight. */
    val isExporting: Boolean = false,
    /** Outcome of the most recent export, or null before the first
     *  attempt (or after [DataExportEvent.DismissResult]). */
    val result: DataExportResult? = null,
)

/**
 * Closed result hierarchy. The screen maps each arm exhaustively so a
 * new outcome is a compile-time prompt to extend the UI copy.
 */
sealed interface DataExportResult {
    /**
     * The bundle was composed and handed to the OS share sheet.
     * [totalRows] is the headline number; [summary] is the per-table
     * breakdown (`_avatars` included) for the detail list.
     */
    data class Success(
        val totalRows: Int,
        val summary: Map<String, Int>,
    ) : DataExportResult

    /** Export failed. [message] is a localized [ErrorMessage] the
     *  screen renders via the shared error string mapping. */
    data class Error(
        val message: ErrorMessage,
    ) : DataExportResult
}

sealed interface DataExportEvent {
    /** Compose / compose the bundle and fire the share sheet. No-op
     *  when an export is already in flight (re-entry guard). */
    data object Export : DataExportEvent

    /** Clear the success / error panel so the user can export again
     *  cleanly. */
    data object DismissResult : DataExportEvent
}

class DataExportViewModel(
    private val exportData: suspend () -> UseCaseResult<DataExportBundle>,
    private val saveBundle: (jsonContent: String, fileName: String) -> Unit,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val _state = MutableStateFlow(DataExportState())
    val state: StateFlow<DataExportState> = _state.asStateFlow()

    fun onEvent(event: DataExportEvent) {
        when (event) {
            DataExportEvent.Export -> performExport()
            DataExportEvent.DismissResult ->
                _state.update { it.copy(result = null) }
        }
    }

    private fun performExport() {
        // Re-entry guard — a full-account export + rate-limited Edge
        // Function call must not be double-fired by an impatient
        // double-tap.
        if (_state.value.isExporting) return

        viewModelScope.launch {
            _state.update { it.copy(isExporting = true, result = null) }
            val result =
                when (val outcome = exportData()) {
                    is UseCaseResult.Success -> {
                        val bundle = outcome.value
                        // Fire the share sheet FIRST so it is already
                        // presenting when the success summary paints.
                        saveBundle(bundle.bundleJson, fileName())
                        DataExportResult.Success(
                            totalRows = bundle.totalRows,
                            summary = bundle.summary,
                        )
                    }
                    is UseCaseResult.Failure ->
                        DataExportResult.Error(outcome.error.toErrorMessage())
                }
            _state.update { it.copy(isExporting = false, result = result) }
        }
    }

    /** `skeinly-export-<yyyyMMdd>.json` — date stamp from the injected
     *  [clock] so commonTest pins it deterministically. */
    private fun fileName(): String = "skeinly-export-${clock.now().formatDateStamp()}.json"
}
