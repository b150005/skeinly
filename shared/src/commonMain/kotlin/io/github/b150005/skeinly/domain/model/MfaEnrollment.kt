package io.github.b150005.skeinly.domain.model

/**
 * Phase 26.5 (ADR-022 §6.4) — TOTP enrollment result. Returned by
 * [io.github.b150005.skeinly.domain.repository.AuthRepository.enrollMfaTotp].
 *
 * Wraps the data the UI needs to display the QR-code pairing screen +
 * the one-time recovery code:
 *
 * - [factorId]: the Supabase-issued MFA factor ID. Stored client-side
 *   (transient — in the ViewModel) so the subsequent
 *   `verifyMfaEnrollment` call can target this factor. Discarded once
 *   verification completes.
 * - [secret]: the raw TOTP shared secret in base32 form. Surfaced to
 *   the user as a "manual entry" fallback for authenticator apps that
 *   cannot scan a QR code.
 * - [otpAuthUri]: the standard `otpauth://totp/...` URI containing
 *   issuer + account label + secret + algorithm. Compose / SwiftUI
 *   render this as a QR code (no network round-trip needed — the URI
 *   alone encodes everything the authenticator app pairs against).
 * - [recoveryCode]: 16-char base32 single-use recovery code,
 *   client-generated at enrollment time + bcrypt-hashed server-side
 *   via the [register_mfa_recovery_code] RPC. Plaintext is shown
 *   ONCE on the post-enrollment screen and never persisted client-
 *   side (the user is instructed to screenshot or write down).
 *
 * Constructed entirely from the
 * [io.github.jan.supabase.auth.mfa.MfaFactor] response + the locally-
 * generated recovery-code plaintext; never serialized or persisted.
 */
data class MfaEnrollment(
    val factorId: String,
    val secret: String,
    val otpAuthUri: String,
    val recoveryCode: String,
)
