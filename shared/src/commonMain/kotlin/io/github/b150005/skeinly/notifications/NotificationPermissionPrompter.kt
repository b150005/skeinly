package io.github.b150005.skeinly.notifications

import com.russhwolf.settings.Settings

/**
 * Phase 24.2 (ADR-017 §3.6) — entry points where the deferred-prompt
 * pre-permission explainer fires for the FIRST time the user encounters
 * a meaningful collaboration moment.
 *
 * The enum exists so call sites read intent without consulting boolean
 * flags ("at first PR-list entry" rather than `if (!hasAsked && hasPRs)`).
 * State today is global (one bit), so the trigger argument is forward-
 * compat margin for per-trigger personalization without churning call
 * sites if Phase 24+ ever needs it.
 */
enum class NotificationPromptTrigger {
    /** First time PullRequestListScreen (Incoming filter) opens AND the list is non-empty. */
    PR_LIST_INCOMING_WITH_PRS,

    /** First time PullRequestDetailScreen opens. */
    PR_DETAIL_OPENED,

    /** First time the user posts a comment on a PR. */
    PR_COMMENT_POSTED,
}

/**
 * Phase 24.2 (ADR-017 §3.6) — gates the in-app pre-permission explainer.
 *
 * The OS-level permission state is intentionally NOT tracked here — that
 * lives in the platform `PushTokenRegistrar` (§3.6 + sub-slice 24.2b),
 * which queries `UNUserNotificationCenter` / `NotificationManagerCompat`
 * at call time. This service answers only "have we ever shown the in-app
 * explainer to this user?" — once they tap either CTA we mark them as
 * 'prompted' and the explainer stops surfacing.
 *
 * Why a single global bit instead of a per-trigger map: ADR §3.6 reads
 * "Not now" as recording 'asked + denied via in-app dismiss' in the
 * absolute sense. A user who tapped Not now at the first entry point
 * has expressed a clear preference; surfacing the same explainer at the
 * second + third entry point would be nag-style anti-pattern. If real
 * tester signal asks for per-trigger re-prompt later (Phase 24+), the
 * trigger arg already exists at every call site.
 */
interface NotificationPermissionPrompter {
    /**
     * True when neither [recordInAppDismiss] nor [recordPermissionAsked]
     * has fired yet for this user. Call sites SHOULD also gate on the
     * OS permission state ("not already enabled") before showing the
     * explainer; that gate lives in the platform layer because it
     * requires `UNUserNotificationCenter` / `NotificationManagerCompat`.
     */
    fun shouldPrompt(trigger: NotificationPromptTrigger): Boolean

    /**
     * Records that the explainer was shown AND the user tapped "Not now".
     * The OS prompt is NOT fired (that's the whole point of the in-app
     * explainer — preserve the user's ability to enable later from
     * Settings without polluting the OS denial state).
     */
    fun recordInAppDismiss(trigger: NotificationPromptTrigger)

    /**
     * Records that the user tapped "Enable" on the explainer. The caller
     * is responsible for actually firing the OS permission prompt via
     * the platform `PushTokenRegistrar`. Subsequent [shouldPrompt] calls
     * return false regardless of the OS-prompt outcome.
     */
    fun recordPermissionAsked()
}

internal class NotificationPermissionPrompterImpl(
    private val settings: Settings,
) : NotificationPermissionPrompter {
    override fun shouldPrompt(trigger: NotificationPromptTrigger): Boolean = !settings.getBoolean(KEY_PROMPTED, false)

    override fun recordInAppDismiss(trigger: NotificationPromptTrigger) {
        settings.putBoolean(KEY_PROMPTED, true)
    }

    override fun recordPermissionAsked() {
        settings.putBoolean(KEY_PROMPTED, true)
    }

    private companion object {
        const val KEY_PROMPTED = "notification_permission_prompted"
    }
}
