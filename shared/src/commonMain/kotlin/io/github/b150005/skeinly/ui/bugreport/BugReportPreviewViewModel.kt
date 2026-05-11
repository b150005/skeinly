package io.github.b150005.skeinly.ui.bugreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.EventRingBuffer
import io.github.b150005.skeinly.data.bug.BugReportProxyException
import io.github.b150005.skeinly.data.bug.SubmitOutcome
import io.github.b150005.skeinly.data.bug.formatBugReportBody
import io.github.b150005.skeinly.platform.DeviceContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 39 W5b (ADR-020) — drives the bug-report preview screen.
 *
 * Surfaced from the shake / 3-finger-long-press gesture and the
 * Settings → Beta → "Send Feedback" entry. Phase 39.5 invoked
 * `BugSubmissionLauncher` (which opened a prefilled GitHub Issue URL
 * in the system browser); Phase 39 W5b POSTs to the `submit-bug-report`
 * Supabase Edge Function which creates the Issue server-side.
 *
 * Responsibilities:
 * - Maintain the user-edited freeform description in [BugReportPreviewState].
 * - On `RefreshPreview`, snapshot the [EventRingBuffer] and format the
 *   Markdown body via [formatBugReportBody]. Re-runs on every
 *   description edit so the preview stays in sync.
 * - On `Submit`, suspend on the [submit] callback and surface the
 *   round-trip result via [SubmitResultState] in state. The UI reads
 *   this to render a Success banner ("Bug report submitted: #N") or
 *   an Error banner (typed by [BugReportProxyException] subclass).
 * - On `DismissResult`, clear `submitResult` so the user can edit the
 *   description and retry without leaving the screen.
 *
 * **Why a suspend callback instead of injecting the proxy client
 * directly:** the lambda seam keeps the ViewModel testable in
 * `commonTest` without standing up an HTTP layer. Production DI binds
 * `BugReportProxyClient::submit` at the Koin site; tests pass a
 * recording lambda or one that returns canned `Result` values.
 *
 * **PostHog distinct_id:** Phase 39.5 shipped a stub passing `null`;
 * W5b retains that until the iOS / Android PostHog SDKs' `distinctId`
 * lookup is plumbed through the shared layer. The body formatter
 * handles `null` gracefully ("_Not available_").
 */
data class BugReportPreviewState(
    /** User-edited freeform description. Capped at 4000 chars at the UI layer. */
    val description: String = "",
    /** Pre-rendered Markdown body — refreshes on description edits + initial load. */
    val previewBody: String = "",
    /** True while the HTTP round-trip is in flight (Submit tap → response). */
    val isSubmitting: Boolean = false,
    /** Outcome of the most recent submission, or null if the user hasn't
     *  submitted yet (or dismissed a previous result). UI reads this to
     *  show the post-submit banner. */
    val submitResult: SubmitResultState? = null,
)

/**
 * Post-submit banner state. The UI exhaustively maps over the sealed
 * subclasses so adding a new failure mode (Offline, RateLimited, etc.)
 * is a compile-time prompt to extend the user-facing copy.
 */
sealed interface SubmitResultState {
    data class Success(
        val issueNumber: Int,
        val htmlUrl: String,
    ) : SubmitResultState

    data class Error(
        val kind: ErrorKind,
        val rawMessage: String,
    ) : SubmitResultState
}

/** Closed enum reflecting [BugReportProxyException]'s subclass surface.
 *  ViewModel-level abstraction so the UI doesn't import the data layer
 *  exception classes directly. */
enum class ErrorKind {
    OFFLINE,
    RATE_LIMITED,
    VALIDATION_FAILED,
    CONFIG_MISSING,
    SERVER,
    UNKNOWN,
}

sealed interface BugReportPreviewEvent {
    data class DescriptionChanged(
        val value: String,
    ) : BugReportPreviewEvent

    /** Re-runs `EventRingBuffer.snapshot()` + `formatBugReportBody`.
     *  Fired from `init` and (forward-compat) on demand. */
    data object RefreshPreview : BugReportPreviewEvent

    data object Submit : BugReportPreviewEvent

    /** Clear the success/error banner so the user can retry or continue
     *  editing. Called from the banner's Dismiss / OK button. */
    data object DismissResult : BugReportPreviewEvent
}

class BugReportPreviewViewModel(
    private val ringBuffer: EventRingBuffer,
    private val deviceContext: DeviceContext,
    private val submit: suspend (title: String, body: String) -> Result<SubmitOutcome>,
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
            BugReportPreviewEvent.Submit -> submitBugReport()
            BugReportPreviewEvent.DismissResult -> {
                _state.update { it.copy(submitResult = null) }
            }
        }
    }

    private fun refreshPreview(currentDescription: String) {
        viewModelScope.launch {
            val events = ringBuffer.snapshot()
            val body = renderBody(currentDescription, events)
            _state.update { it.copy(previewBody = body) }
        }
    }

    private fun submitBugReport() {
        // Idempotency guard — double-tap on the Submit button would
        // otherwise queue two HTTP round-trips. The Edge Function has
        // its own rate limit but tripping it on the user's own retries
        // is poor UX.
        if (_state.value.isSubmitting) return

        viewModelScope.launch {
            val events = ringBuffer.snapshot()
            val description = _state.value.description
            val title = computeTitle(description)
            val body = renderBody(description, events)
            _state.update { it.copy(isSubmitting = true, submitResult = null) }

            val result = submit(title, body)
            val resultState =
                result.fold(
                    onSuccess = { outcome ->
                        SubmitResultState.Success(
                            issueNumber = outcome.issueNumber,
                            htmlUrl = outcome.htmlUrl,
                        )
                    },
                    onFailure = { error ->
                        val (kind, message) = classifyError(error)
                        SubmitResultState.Error(kind = kind, rawMessage = message)
                    },
                )
            _state.update { it.copy(isSubmitting = false, submitResult = resultState) }
        }
    }

    private fun classifyError(error: Throwable): Pair<ErrorKind, String> =
        when (error) {
            is BugReportProxyException.Offline ->
                ErrorKind.OFFLINE to error.message.orEmpty()
            is BugReportProxyException.RateLimited ->
                ErrorKind.RATE_LIMITED to error.message.orEmpty()
            is BugReportProxyException.ValidationFailed ->
                ErrorKind.VALIDATION_FAILED to error.message.orEmpty()
            is BugReportProxyException.ConfigMissing ->
                ErrorKind.CONFIG_MISSING to error.message.orEmpty()
            is BugReportProxyException.Server ->
                ErrorKind.SERVER to error.message.orEmpty()
            is BugReportProxyException.Unknown ->
                ErrorKind.UNKNOWN to error.message.orEmpty()
            else ->
                ErrorKind.UNKNOWN to (error.message ?: error::class.simpleName.orEmpty())
        }

    private fun renderBody(
        description: String,
        events: List<AnalyticsEvent>,
    ): String =
        formatBugReportBody(
            description = description,
            events = events,
            // W5b retains the Phase 39.5 stub — distinct_id plumbing is
            // a follow-up if cross-referencing reports against PostHog
            // dashboards surfaces a need.
            posthogDistinctId = null,
            appVersion = deviceContext.appVersion,
            osVersion = deviceContext.osVersion,
            deviceModel = deviceContext.deviceModel,
            locale = deviceContext.locale,
            platformName = deviceContext.platformName,
        )

    private fun computeTitle(description: String): String {
        // 2026-05-12 amendment: the "[Beta] " prefix introduced in
        // Phase 39.5 was a closed-beta artifact and did not survive
        // the W5 GA-readiness review (the GitHub App / Edge Function
        // are reused by general users post-GA, where a "[Beta]"
        // prefix would be wrong). The first non-blank line of the
        // description stands on its own; empty descriptions get the
        // generic "Bug report" placeholder. Triage labels at the
        // GitHub-side classify bug-vs-feature-vs-feedback.
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
        return truncated.ifEmpty { "Bug report" }
    }

    companion object {
        const val MAX_DESCRIPTION_LENGTH: Int = 4000
        const val MAX_TITLE_LENGTH: Int = 80
    }
}
