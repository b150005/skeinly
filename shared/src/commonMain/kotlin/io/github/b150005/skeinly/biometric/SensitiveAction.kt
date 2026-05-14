package io.github.b150005.skeinly.biometric

/**
 * Phase 26.6 (ADR-022 §6.5) — discriminator for "what the user is
 * about to do" passed to [BiometricGuardian.requireForAction]. The
 * Guardian uses this only to surface the per-action reason copy in the
 * OS dialog body; the gate logic itself is identical across actions.
 *
 * Closed enum. Future additions: `PurchaseConfirm`,
 * `PayoutMethodChange` (Phase 50+ marketplace per ADR-022 §6.5).
 * Adding a new variant requires a new i18n reason key paired in
 * [BiometricGuardian.reasonFor].
 */
enum class SensitiveAction {
    /** ADR-005 cascading account deletion (with [SettingsViewModel]
     *  performDeleteAccount). Irreversible — user cannot recover the
     *  account or data after the RPC. */
    AccountDeletion,

    /** ADR-022 §6.4 MFA disable. Drops the user's second factor;
     *  reduces account-security posture so a sensitive-action gate is
     *  proportionate to the risk. */
    MfaDisable,
}
