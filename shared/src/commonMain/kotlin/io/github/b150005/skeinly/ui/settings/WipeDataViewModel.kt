package io.github.b150005.skeinly.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * Phase 27.1 (ADR-023 §6.2 / §UX) — drives the two-step "Delete all
 * my data" gate (preservation-matrix modal → phrase-typing confirmation
 * → submit).
 *
 * **State machine** (ADR §UX): `Modal` → `PhraseEntry` → (submit fires)
 * → on success emit [WipeDataNavEvent.WipeCompleted] and the screen
 * pops to root + flashes the post-wipe banner via PatternLibrary; on
 * failure stay on `PhraseEntry` with [WipeDataState.error] populated so
 * the user can retry without losing their phrase input.
 *
 * **Lambda-seam DI** (mirrors `MfaEnrollmentViewModel` / `BugReportPreviewViewModel`
 * precedent) — keeps the VM testable in commonTest without standing up
 * supabase-kt. Production DI binds [wipeData] to
 * `WipeDataRepository::wipe` at the Koin site.
 *
 * **Phrase-match gating** (ADR §UX, decision (c)): the required phrase
 * is locale-dependent (EN: `delete my data`, JA: `データを削除`).
 * The active locale is captured at modal-open time and passed into the
 * VM constructor as [requiredPhrase] — mid-flow locale change is NOT
 * supported (out-of-scope for the alpha gate; matches the ADR's "two-
 * language matching: the active locale at modal-open time selects which
 * phrase is required" closing).
 *
 * **Submit re-entry guard** (matches `SettingsViewModel.performDeleteAccount`
 * pattern): a quick double-tap during the network round-trip does NOT
 * queue two `wipe()` calls — the [WipeDataState.isSubmitting] flag
 * short-circuits the second event.
 */
data class WipeDataState(
    /** Current step in the two-step flow. */
    val step: WipeDataStep = WipeDataStep.Modal,
    /** User-typed phrase. Compared (trimmed, case-insensitive) against
     *  [WipeDataViewModel]'s `requiredPhrase` to gate submit. */
    val phraseInput: String = "",
    /** True while the wipe RPC is in flight. */
    val isSubmitting: Boolean = false,
    /** Inline error from the most-recent submit failure. The screen
     *  surfaces this as a snackbar/alert; the user can retry without
     *  re-typing the phrase. Cleared via [WipeDataEvent.ClearError] or
     *  on the next [Submit]. */
    val error: ErrorMessage? = null,
) {
    /**
     * True when (a) the typed phrase matches the required phrase
     * after trimming + case-folding AND (b) no submit is in flight.
     * The screen binds Submit-button `enabled` to this computed flag.
     */
    fun submitEnabled(requiredPhrase: String): Boolean =
        !isSubmitting &&
            phraseInput.trim().equals(requiredPhrase.trim(), ignoreCase = true)
}

/**
 * Visible step in the wipe-data flow. Closed enum so the screen's
 * `when` is exhaustive — adding a future "Success-confirmation" step
 * (currently routed via nav event + banner on PatternLibrary instead)
 * is a deliberate UI act.
 */
enum class WipeDataStep {
    Modal,
    PhraseEntry,
}

sealed interface WipeDataEvent {
    /** From Modal → PhraseEntry (user tapped "Continue"). */
    data object Continue : WipeDataEvent

    /** PhraseEntry → Modal (user tapped back / "Cancel"). Clears the
     *  typed phrase + any prior error so the next round is fresh. */
    data object BackToModal : WipeDataEvent

    data class UpdatePhrase(
        val value: String,
    ) : WipeDataEvent

    /** Fire the wipe RPC. No-op when [WipeDataState.submitEnabled]
     *  would return false (re-entry guard + phrase-mismatch guard). */
    data object Submit : WipeDataEvent

    data object ClearError : WipeDataEvent
}

/**
 * One-shot navigation event surfaced after a successful wipe. The
 * screen collects this to pop back to root + flip the
 * PatternLibrary-side banner. Failure is signaled inline via
 * [WipeDataState.error] (per ADR §UX — user can retry without leaving
 * the phrase-entry screen).
 */
sealed interface WipeDataNavEvent {
    data object WipeCompleted : WipeDataNavEvent
}

class WipeDataViewModel(
    /**
     * Locale-resolved phrase the user must type to confirm. Resolved
     * at screen mount time from the active i18n locale — `delete my data`
     * on EN, `データを削除` on JA. Mid-flow locale change is not
     * supported (out-of-scope for the alpha gate per ADR §UX).
     */
    private val requiredPhrase: String,
    /** Lambda over `WipeDataRepository::wipe`. Production wiring binds
     *  through Koin; tests inject a recording lambda. */
    private val wipeData: suspend () -> UseCaseResult<Unit>,
) : ViewModel() {
    private val _state = MutableStateFlow(WipeDataState())
    val state: StateFlow<WipeDataState> = _state.asStateFlow()

    private val _navChannel = Channel<WipeDataNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<WipeDataNavEvent> = _navChannel.receiveAsFlow()

    fun onEvent(event: WipeDataEvent) {
        when (event) {
            WipeDataEvent.Continue ->
                _state.update {
                    it.copy(step = WipeDataStep.PhraseEntry, error = null)
                }
            WipeDataEvent.BackToModal ->
                _state.update {
                    it.copy(
                        step = WipeDataStep.Modal,
                        phraseInput = "",
                        error = null,
                    )
                }
            is WipeDataEvent.UpdatePhrase ->
                _state.update { it.copy(phraseInput = event.value, error = null) }
            WipeDataEvent.Submit -> performSubmit()
            WipeDataEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    /**
     * Public accessor for the locale-resolved phrase so the screen can
     * render the helper text without re-resolving the i18n key
     * (matches the "active locale at modal-open time" contract — the
     * screen cannot deviate from the VM's snapshot).
     */
    fun requiredPhrase(): String = requiredPhrase

    private fun performSubmit() {
        val current = _state.value
        // Combined gate: phrase-match + re-entry guard. Silent no-op
        // on mismatch keeps the screen idle (button is also disabled);
        // silent no-op on re-entry guards against the network-window
        // double-tap.
        if (!current.submitEnabled(requiredPhrase)) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            when (val result = wipeData()) {
                is UseCaseResult.Success -> {
                    _state.update { it.copy(isSubmitting = false) }
                    _navChannel.send(WipeDataNavEvent.WipeCompleted)
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
