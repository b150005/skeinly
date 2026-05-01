package io.github.b150005.skeinly.ui.bugreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.EventRingBuffer
import io.github.b150005.skeinly.data.bug.formatBugReportBody
import io.github.b150005.skeinly.platform.DeviceContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 39.5 (ADR-015 §3, §6) — drives the bug-report preview screen
 * surfaced from the shake / 3-finger-long-press gesture and the
 * Settings → Beta → "Send Feedback" entry.
 *
 * Responsibilities:
 * - Maintain the user-edited freeform description in [BugReportPreviewState].
 * - On `RefreshPreview`, snapshot the [EventRingBuffer] under its lock and
 *   format the full Markdown body via
 *   [io.github.b150005.skeinly.data.bug.formatBugReportBody]. Re-runs
 *   whenever the description changes so the preview stays in sync.
 * - On `Submit`, format the final body and dispatch through the
 *   [submit] callback (typically `BugSubmissionLauncher::launch` at the
 *   DI boundary, or a recording lambda in tests).
 *
 * **Why a callback instead of the expect class directly:** the
 * [io.github.b150005.skeinly.platform.BugSubmissionLauncher] is an
 * `expect class` per ADR-015 §3 — it cannot be subclassed in
 * `commonTest` because the actuals declare it as a `final class`. The
 * lambda seam keeps the production wiring trivial (`launcher::launch` at
 * the DI site) while letting tests pass any `(String, String) -> Unit`
 * to capture submission calls.
 *
 * **PostHog distinct_id:** Phase 39.5 ships a stub that always passes
 * `null` — the iOS / Android PostHog SDKs expose `distinctId` but the
 * lookup path is not yet plumbed through the shared layer. The body
 * formatter handles `null` gracefully ("_Not available_"). When telemetry
 * surfaces a need to cross-reference reports against PostHog dashboards,
 * a follow-up slice can extend [DeviceContext] (or add a sibling
 * `TelemetrySessionId` accessor) without touching this ViewModel.
 */
data class BugReportPreviewState(
    /** User-edited freeform description. Capped at 4000 chars at the UI layer. */
    val description: String = "",
    /** Pre-rendered Markdown body — refreshes on description edits + initial load. */
    val previewBody: String = "",
    /** True between `Submit` dispatch and the next ViewModel state emission. */
    val isSubmitting: Boolean = false,
)

sealed interface BugReportPreviewEvent {
    data class DescriptionChanged(
        val value: String,
    ) : BugReportPreviewEvent

    /**
     * Re-runs `EventRingBuffer.snapshot()` + `formatBugReportBody`. Fired
     * from `init` exactly once and (in principle) any time the buffer
     * mutates underneath the screen. The shape is reserved for forward
     * compat — current Compose / SwiftUI surfaces do not re-fire post-
     * init since the buffer is monotonic during a preview session and
     * re-rendering on every emission is indistinguishable from "stable"
     * to the user.
     */
    data object RefreshPreview : BugReportPreviewEvent

    data object Submit : BugReportPreviewEvent
}

class BugReportPreviewViewModel(
    private val ringBuffer: EventRingBuffer,
    private val deviceContext: DeviceContext,
    private val submit: (title: String, body: String) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(BugReportPreviewState())
    val state: StateFlow<BugReportPreviewState> = _state.asStateFlow()

    init {
        // Initial preview render. Description is empty at this point so
        // the body's "## Description" section renders as a blank line,
        // which is the intended "the user has not typed anything yet"
        // rendering — editing the description re-runs `refreshPreview`
        // and the body updates in-place.
        refreshPreview(currentDescription = "")
    }

    fun onEvent(event: BugReportPreviewEvent) {
        when (event) {
            is BugReportPreviewEvent.DescriptionChanged -> {
                val capped =
                    if (event.value.length > MAX_DESCRIPTION_LENGTH) {
                        event.value.substring(0, MAX_DESCRIPTION_LENGTH)
                    } else {
                        event.value
                    }
                _state.update { it.copy(description = capped) }
                refreshPreview(currentDescription = capped)
            }
            BugReportPreviewEvent.RefreshPreview -> {
                refreshPreview(currentDescription = _state.value.description)
            }
            BugReportPreviewEvent.Submit -> {
                submitBugReport()
            }
        }
    }

    private fun refreshPreview(currentDescription: String) {
        viewModelScope.launch {
            // snapshot() takes a Mutex — non-blocking from the
            // viewModelScope coroutine but cannot run on the main thread
            // synchronously since it's `suspend`. Hence the launch.
            val events = ringBuffer.snapshot()
            val body = renderBody(currentDescription, events)
            _state.update { it.copy(previewBody = body) }
        }
    }

    private fun submitBugReport() {
        viewModelScope.launch {
            // Re-snapshot the buffer at submission time so any events
            // captured between `init` and the user's tap are included.
            // The cost is negligible against the user-driven cadence.
            val events = ringBuffer.snapshot()
            val description = _state.value.description
            val title = computeTitle(description)
            val body = renderBody(description, events)
            _state.update { it.copy(isSubmitting = true) }
            submit(title, body)
            // The launcher is fire-and-forget — we cannot observe browser
            // dismissal, so we flip isSubmitting back off immediately.
            // The flag exists only to disable the Submit button briefly
            // so a double-fire on a stuck render does not produce two
            // browser launches.
            _state.update { it.copy(isSubmitting = false) }
        }
    }

    private fun renderBody(
        description: String,
        events: List<AnalyticsEvent>,
    ): String =
        formatBugReportBody(
            description = description,
            events = events,
            // Phase 39.5 stub: PostHog distinct_id resolution not yet
            // plumbed through the shared layer. See class KDoc.
            posthogDistinctId = null,
            appVersion = deviceContext.appVersion,
            osVersion = deviceContext.osVersion,
            deviceModel = deviceContext.deviceModel,
            locale = deviceContext.locale,
            platformName = deviceContext.platformName,
        )

    private fun computeTitle(description: String): String {
        // First non-empty trimmed line of the description, capped to
        // MAX_TITLE_LENGTH so GitHub's title field stays readable. Empty
        // description yields the default "[Beta] Bug report" — the body
        // still carries the full diagnostic block so that case is
        // recoverable.
        val firstLine =
            description
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        val truncated =
            if (firstLine.length > MAX_TITLE_LENGTH) {
                firstLine.substring(0, MAX_TITLE_LENGTH).trimEnd() + "..."
            } else {
                firstLine
            }
        return if (truncated.isEmpty()) "[Beta] Bug report" else "[Beta] $truncated"
    }

    companion object {
        const val MAX_DESCRIPTION_LENGTH: Int = 4000
        const val MAX_TITLE_LENGTH: Int = 80
    }
}
