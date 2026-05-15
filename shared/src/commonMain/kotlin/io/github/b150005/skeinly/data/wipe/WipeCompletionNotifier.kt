package io.github.b150005.skeinly.data.wipe

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Phase 27.2 (ADR-023 §UX) — singleton event bus that carries a single
 * `Unit` signal from the post-wipe submit handler in
 * [io.github.b150005.skeinly.ui.settings.WipeDataViewModel] to the
 * Pattern Library banner state.
 *
 * **Why a singleton notifier vs direct VM coupling**: the wipe-data
 * flow's success path lives on a `WipeDataConfirmPhrase` route whose
 * lifetime ends with `navController.popBackStack()`. The banner needs
 * to surface on a sibling route (`PatternLibrary`) whose VM was created
 * earlier and survives the wipe round-trip. A SharedFlow registered as
 * a Koin singleton bridges the two without forcing either VM to know
 * about the other (no parent-child VM scoping, no NavGraph-level event
 * channel).
 *
 * **Replay = 0, extraBufferCapacity = 1**: the banner should NOT
 * surface for a freshly-mounted Pattern Library that arrives AFTER a
 * prior wipe completed (the user already saw it, or is on a fresh
 * launch). A second emission in quick succession (e.g. double-tap on
 * the submit button — guarded at the ViewModel layer but defended
 * structurally here too) coalesces into one buffer slot rather than
 * piling up.
 *
 * **Suspending [notify]**: the emit is suspending so callers thread
 * through `viewModelScope` rather than firing-and-forgetting. Drop
 * behavior is irrelevant because there is at most one emission per
 * successful wipe RPC.
 */
class WipeCompletionNotifier {
    private val _events =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
        )
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    suspend fun notify() {
        _events.emit(Unit)
    }
}
