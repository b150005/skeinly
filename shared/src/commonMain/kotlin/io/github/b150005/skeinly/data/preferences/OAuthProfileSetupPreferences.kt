package io.github.b150005.skeinly.data.preferences

import com.russhwolf.settings.Settings

/**
 * Phase 26.6 (ADR-022 §6.6) — tracks whether the post-OAuth profile
 * setup gate has been surfaced (and either completed OR explicitly
 * skipped) for the current installation. Once flipped to true the
 * NavGraph short-circuits the gate, so a user who tapped Skip on the
 * setup screen does NOT see it again on every subsequent sign-in.
 *
 * Scoping: this is a per-install flag, NOT per-user. Sign-out + sign-in
 * with a different account does NOT re-surface the prompt because the
 * authoritative skip signal is "did the user complete or skip this
 * already?". Account deletion does NOT clear it either — the user
 * already saw the prompt; a fresh account from a different email goes
 * through the trigger-derived empty display_name path which the user
 * can still complete from Settings → Profile.
 *
 * Backed by [Settings] (SharedPreferences on Android, NSUserDefaults on
 * iOS); the non-encrypted `skeinly_prefs` instance is appropriate
 * because this is a UX state bit, not a credential.
 */
interface OAuthProfileSetupPreferences {
    /**
     * True when the setup gate has been completed OR explicitly skipped
     * by the user. The NavGraph reads this synchronously to decide
     * whether to route a fresh Authenticated transition through the
     * setup screen.
     */
    val isCompleted: Boolean

    /**
     * Idempotent — call when the user taps Save or Skip on the setup
     * screen. Subsequent reads of [isCompleted] return true.
     */
    fun markCompleted()
}

internal class OAuthProfileSetupPreferencesImpl(
    private val settings: Settings,
) : OAuthProfileSetupPreferences {
    override val isCompleted: Boolean
        get() = settings.getBoolean(KEY_OAUTH_PROFILE_SETUP_COMPLETED, false)

    override fun markCompleted() {
        settings.putBoolean(KEY_OAUTH_PROFILE_SETUP_COMPLETED, true)
    }

    private companion object {
        const val KEY_OAUTH_PROFILE_SETUP_COMPLETED = "oauth_profile_setup_completed"
    }
}
