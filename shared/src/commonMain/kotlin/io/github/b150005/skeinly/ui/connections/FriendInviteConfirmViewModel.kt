package io.github.b150005.skeinly.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 25.4 (ADR-024 §Phase 25.4) — single-screen redemption surface
 * for BOTH invite paths:
 *
 * - **Token mode** ([FriendInviteConfirmMode.Token]) — reached when the
 *   OS routes a `https://b150005.github.io/skeinly/friend/<token>`
 *   Universal Link / App Link tap. The token is captured at nav time
 *   and the VM auto-fires the redeem in `init` (no user action — both
 *   parties already participated in the link exchange, so direct
 *   token-tap is implicit mutual acceptance per ADR §Phase 25.4 step 4;
 *   the RPC writes the `friend_connections` row in state `accepted`).
 *
 * - **Code mode** ([FriendInviteConfirmMode.Code]) — reached from
 *   Connections → "Add by code". The user pastes/types the 8-char
 *   code, taps submit, the VM redeems via the code RPC.
 *
 * Both RPCs (`redeem_friend_invite_token` / `redeem_friend_invite_code`,
 * migration 035) return the inviter's user id on success; the VM then
 * resolves the inviter's display name (best-effort — a failed name
 * lookup falls back to the localized "Unknown user" at the screen
 * layer, same pattern as ConnectionsScreen).
 *
 * **Error surface**: the redeem RPCs raise a Postgres error for
 * expired / consumed / self-redeem / blocked, which `toUseCaseError()`
 * maps to a generic [io.github.b150005.skeinly.domain.usecase.UseCaseError.Unknown]
 * (or `.Network` for transport failures). There is no invite-specific
 * error variant; the screen surfaces a generic message (consistent
 * with the project-wide ViewModel error-i18n Tech Debt). The user can
 * retry without losing their typed code.
 *
 * **Lambda-seam DI** (mirrors [WipeDataViewModel] / ConnectionsViewModel)
 * — keeps commonTest free of the supabase-kt + UserRepository surface.
 *
 * **Re-entry guard**: [FriendInviteConfirmState.isRedeeming] short-
 * circuits a double-tap during the network round-trip (WipeData
 * precedent). `viewModelScope` defaults to Main on both Android + iOS
 * so the read-then-launch is single-threaded by construction.
 *
 * **Accepted risk — cold-start auto-redeem after login** (Phase 25.4
 * code review, considered + accepted): on an unauthenticated deep-link
 * tap the OS routes Login → (post-auth replay) → this screen, and
 * Token mode auto-redeems with no confirmation. If the authenticated
 * identity after login differs from the link-tapper's intent (shared
 * device left at the lock screen, someone else taps the link, the
 * owner then logs in), the token is redeemed under the owner's
 * account. This is the explicit ADR-024 §Phase 25.4 step 4 decision
 * ("direct token-tap = implicit mutual acceptance, no second-side
 * confirmation"). Real-world risk is minimal and reversible: the
 * attacker would need valid owner credentials to reach the redeem
 * (full account compromise dwarfs one friend edge), and the legitimate
 * owner can sever the connection from Connections → Disconnect. NOT
 * overridden here because changing it would contradict the ADR; a
 * future tightening (require an explicit "Add <name>?" tap in Token
 * mode) would be an ADR-level revision, tracked in CLAUDE.md Tech Debt.
 */
data class FriendInviteConfirmState(
    val mode: FriendInviteConfirmMode,
    /** User-typed code. Only meaningful in [FriendInviteConfirmMode.Code]
     *  mode; ignored in Token mode (the token came from the deep link). */
    val codeInput: String = "",
    /** True while a redeem RPC is in flight. */
    val isRedeeming: Boolean = false,
    /** Non-null once redemption succeeds. Carries the inviter's
     *  display name (nullable — the screen falls back to a localized
     *  "Unknown user" when the profile lookup failed). */
    val success: FriendInviteSuccess? = null,
    /** Inline error from the most-recent failed redeem; cleared via
     *  [FriendInviteConfirmEvent.ClearError] or by next retry. */
    val error: ErrorMessage? = null,
) {
    /**
     * Submit-button gate for Code mode: a non-blank code AND no redeem
     * in flight. Token mode never renders a submit button (it auto-
     * redeems), so this is only consulted by the code-entry form.
     */
    val submitEnabled: Boolean
        get() = !isRedeeming && codeInput.isNotBlank()
}

/** Closed enum: which invite path this screen instance is servicing. */
enum class FriendInviteConfirmMode {
    Token,
    Code,
}

/**
 * Redemption outcome. [inviterDisplayName] is nullable because the
 * inviter-profile lookup is best-effort — a null here means the screen
 * renders the localized "Unknown user" fallback in the success copy.
 */
data class FriendInviteSuccess(
    val inviterId: String,
    val inviterDisplayName: String?,
)

sealed interface FriendInviteConfirmEvent {
    /**
     * Token mode: fired automatically from `init` (the screen never
     * surfaces a button). Code mode: fired when the user taps the
     * submit button after typing a code.
     */
    data object Redeem : FriendInviteConfirmEvent

    data class UpdateCode(
        val value: String,
    ) : FriendInviteConfirmEvent

    data object ClearError : FriendInviteConfirmEvent
}

class FriendInviteConfirmViewModel(
    /** Token-mode payload (deep-link). Null ⇒ Code mode. The mode is
     *  derived from nullability so the nav route only needs to carry
     *  one optional param. */
    private val token: String?,
    private val redeemToken: suspend (String) -> UseCaseResult<String>,
    private val redeemCode: suspend (String) -> UseCaseResult<String>,
    /** Resolves inviter user id → display name. Null result ⇒ the
     *  screen shows the localized "Unknown user" fallback. */
    private val resolveDisplayName: suspend (String) -> String?,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            FriendInviteConfirmState(
                mode =
                    if (token != null) {
                        FriendInviteConfirmMode.Token
                    } else {
                        FriendInviteConfirmMode.Code
                    },
            ),
        )
    val state: StateFlow<FriendInviteConfirmState> = _state.asStateFlow()

    init {
        // Token mode auto-redeems — the user already expressed intent
        // by tapping the link; no second confirmation (ADR §Phase 25.4
        // step 4). Code mode waits for the user to type + submit.
        if (token != null) {
            performRedeem { redeemToken(token) }
        }
    }

    fun onEvent(event: FriendInviteConfirmEvent) {
        when (event) {
            FriendInviteConfirmEvent.Redeem -> {
                val current = _state.value
                when (current.mode) {
                    FriendInviteConfirmMode.Token ->
                        // Token already consumed by init; a manual
                        // Redeem in Token mode is a retry after a
                        // transient failure. token is non-null in this
                        // mode by construction.
                        token?.let { t -> performRedeem { redeemToken(t) } }
                    FriendInviteConfirmMode.Code -> {
                        if (!current.submitEnabled) return
                        val code = current.codeInput.trim()
                        performRedeem { redeemCode(code) }
                    }
                }
            }
            is FriendInviteConfirmEvent.UpdateCode ->
                _state.update { it.copy(codeInput = event.value, error = null) }
            FriendInviteConfirmEvent.ClearError ->
                _state.update { it.copy(error = null) }
        }
    }

    private fun performRedeem(block: suspend () -> UseCaseResult<String>) {
        if (_state.value.isRedeeming) return
        viewModelScope.launch {
            _state.update { it.copy(isRedeeming = true, error = null) }
            when (val result = block()) {
                is UseCaseResult.Success -> {
                    val inviterId = result.value
                    val name = resolveInviterName(inviterId)
                    _state.update {
                        it.copy(
                            isRedeeming = false,
                            success =
                                FriendInviteSuccess(
                                    inviterId = inviterId,
                                    inviterDisplayName = name,
                                ),
                        )
                    }
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(
                            isRedeeming = false,
                            error = result.error.toErrorMessage(),
                        )
                    }
            }
        }
    }

    /**
     * Best-effort inviter-name resolution. A throw here (network
     * timeout on the profile fetch) must NOT downgrade an already-
     * successful redemption into a failure — the friendship row is
     * already written server-side. Swallow and return null so the
     * screen shows "You and your new friend are now connected" with
     * the localized fallback rather than an error.
     */
    private suspend fun resolveInviterName(inviterId: String): String? =
        try {
            resolveDisplayName(inviterId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
}
