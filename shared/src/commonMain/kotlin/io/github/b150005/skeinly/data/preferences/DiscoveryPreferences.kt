package io.github.b150005.skeinly.data.preferences

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 25.5 (ADR-024 §(f)) — Discovery "show friends-only patterns"
 * opt-in preference.
 *
 * Default OFF (filter-only-public) per ADR-024 §(f) Decision Option 2:
 * Discovery defaults to the public feed; the user explicitly opts into
 * also seeing friends-only patterns. Persisted locally via the same
 * `Settings`-backed mechanism as [AnalyticsPreferences] — the toggle is
 * purely a client-side query parameter (no server roundtrip), so a
 * non-encrypted preference store is appropriate (this is a UI
 * preference, not a secret).
 *
 * **Security note**: flipping this ON does NOT itself expose any
 * friends-only content. The widened Discovery query (`visibility IN
 * ('public', 'friends')`) is still gated server-side by the Phase 25.1
 * RLS patterns-SELECT policy whose `friends` arm requires
 * `is_friend(auth.uid(), owner_id)`. The preference only controls
 * whether the client asks for the friends rows at all — RLS decides
 * which (if any) it is actually allowed to return.
 */
interface DiscoveryPreferences {
    /**
     * Reactive state. The shape (a `StateFlow`, not a plain
     * `fun getShowFriendsOnly(): Boolean`) is deliberately kept
     * consistent with the sibling [AnalyticsPreferences] /
     * [io.github.b150005.skeinly.data.preferences.BiometricPreferences]
     * interfaces so every preference reads the same way. The Phase
     * 25.5 consumer (DiscoveryViewModel) only seed-reads `.value` at
     * construction and then owns the toggle internally, so the
     * reactive contract is currently unused by that caller — but
     * keeping the `StateFlow` shape avoids a one-off divergent
     * preference API and leaves the door open for a future surface
     * (e.g. a Settings mirror of the toggle) to observe it without an
     * interface change.
     */
    val showFriendsOnly: StateFlow<Boolean>

    fun setShowFriendsOnly(value: Boolean)
}

internal class DiscoveryPreferencesImpl(
    private val settings: Settings,
) : DiscoveryPreferences {
    private val _showFriendsOnly =
        MutableStateFlow(settings.getBoolean(KEY_SHOW_FRIENDS_ONLY, false))
    override val showFriendsOnly: StateFlow<Boolean> = _showFriendsOnly.asStateFlow()

    override fun setShowFriendsOnly(value: Boolean) {
        settings.putBoolean(KEY_SHOW_FRIENDS_ONLY, value)
        _showFriendsOnly.value = value
    }

    private companion object {
        const val KEY_SHOW_FRIENDS_ONLY = "discovery_show_friends_only"
    }
}
