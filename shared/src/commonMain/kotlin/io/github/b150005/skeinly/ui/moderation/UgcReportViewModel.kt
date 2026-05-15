package io.github.b150005.skeinly.ui.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.domain.model.MAX_UGC_REASON_LENGTH
import io.github.b150005.skeinly.domain.model.UgcReportCategory
import io.github.b150005.skeinly.domain.model.UgcTargetType
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toErrorMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 39 (ADR-021 §D4) — the "Report content" modal ViewModel.
 *
 * Reachable from the Discovery pattern-card overflow menu
 * (`target_type = pattern`) and Suggestion / comment threads
 * (`comment` / `suggestion` / `suggestion_comment`). The user picks a
 * [UgcReportCategory], types a free-text reason (1..[MAX_UGC_REASON_LENGTH]),
 * and submits — the repository routes through the `submit-ugc-report`
 * Edge Function which INSERTs `public.ugc_reports` + mirrors a triage
 * GitHub Issue.
 *
 * **Submit gate** ([UgcReportState.submitEnabled]): a category MUST be
 * selected, the reason MUST be non-blank and within the length cap, no
 * submit may be in flight, and the report must not already have
 * succeeded (the modal is dismissed by the screen on the
 * [UgcReportNavEvent.Submitted] one-shot, but the guard protects
 * against a late re-tap before the dismiss animation completes).
 *
 * **Lambda-seam DI** mirrors [io.github.b150005.skeinly.ui.settings.WipeDataViewModel]:
 * [targetType] + [targetId] are screen-time params resolved by the
 * NavGraph / SwiftUI presenter; [submitReport] wraps
 * `UgcModerationRepository::submitReport`. Tests inject a recording
 * lambda without standing up supabase-kt.
 *
 * Failure surfaces inline via [UgcReportState.error] so the user can
 * fix the input (or retry a rate-limited / transient failure) without
 * losing their typed reason — same UX contract as
 * [io.github.b150005.skeinly.ui.settings.WipeDataViewModel].
 */
data class UgcReportState(
    val category: UgcReportCategory? = null,
    val reason: String = "",
    val isSubmitting: Boolean = false,
    val error: ErrorMessage? = null,
) {
    /**
     * True iff the reason is non-blank and within the DB length cap.
     * Evaluated against the TRIMMED reason so this gate agrees exactly
     * with `UgcModerationRepositoryImpl.submitReport` (which validates
     * + sends the trimmed value) — no whitespace-padding daylight
     * between the enabled-button state and what the repository accepts.
     */
    val reasonValid: Boolean
        get() =
            reason.trim().let { t ->
                t.isNotEmpty() && t.length <= MAX_UGC_REASON_LENGTH
            }

    /**
     * Submit-button `enabled` binding: category chosen + reason valid +
     * no submit in flight.
     */
    fun submitEnabled(): Boolean = category != null && reasonValid && !isSubmitting
}

sealed interface UgcReportEvent {
    data class SelectCategory(
        val category: UgcReportCategory,
    ) : UgcReportEvent

    data class UpdateReason(
        val value: String,
    ) : UgcReportEvent

    /** Fire the report. No-op when [UgcReportState.submitEnabled] is
     *  false (category-missing / blank / over-long / re-entry guard). */
    data object Submit : UgcReportEvent

    data object ClearError : UgcReportEvent
}

/**
 * One-shot navigation event. The screen collects this to dismiss the
 * modal + flash a "Report submitted — thank you" confirmation. Failure
 * stays inline via [UgcReportState.error] (no nav event) so the user
 * keeps their typed reason.
 */
sealed interface UgcReportNavEvent {
    data object Submitted : UgcReportNavEvent
}

class UgcReportViewModel(
    /** What is being reported — resolved at screen-mount time. */
    private val targetType: UgcTargetType,
    /** The reported row's UUID — resolved at screen-mount time. */
    private val targetId: String,
    /** Lambda over `UgcModerationRepository::submitReport`. */
    private val submitReport: suspend (
        targetType: UgcTargetType,
        targetId: String,
        category: UgcReportCategory,
        reason: String,
    ) -> UseCaseResult<Unit>,
) : ViewModel() {
    private val _state = MutableStateFlow(UgcReportState())
    val state: StateFlow<UgcReportState> = _state.asStateFlow()

    private val _navChannel = Channel<UgcReportNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<UgcReportNavEvent> = _navChannel.receiveAsFlow()

    fun onEvent(event: UgcReportEvent) {
        when (event) {
            is UgcReportEvent.SelectCategory ->
                _state.update { it.copy(category = event.category, error = null) }
            is UgcReportEvent.UpdateReason ->
                _state.update { it.copy(reason = event.value, error = null) }
            UgcReportEvent.Submit -> performSubmit()
            UgcReportEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun performSubmit() {
        val current = _state.value
        // Combined gate: category-present + reason-valid + re-entry.
        // Silent no-op keeps the screen idle (the button is also
        // disabled).
        if (!current.submitEnabled()) return
        val category = current.category ?: return
        // Flip `isSubmitting` SYNCHRONOUSLY before launching so the
        // re-entry guard is atomic on the single-threaded Main
        // dispatcher: `onEvent` runs to completion before the next
        // `onEvent`, so a queued double-tap re-evaluates
        // `submitEnabled()` against `isSubmitting = true` and is
        // swallowed. (Setting it inside the launch body would leave a
        // dispatch-window where two taps both pass the guard.)
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            when (val result = submitReport(targetType, targetId, category, current.reason)) {
                is UseCaseResult.Success -> {
                    _state.update { it.copy(isSubmitting = false) }
                    _navChannel.send(UgcReportNavEvent.Submitted)
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            error = result.error.toErrorMessage(),
                        )
                    }
            }
        }
    }
}
