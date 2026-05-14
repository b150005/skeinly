package io.github.b150005.skeinly.domain.model

/**
 * Phase 26.5 (ADR-022 §6.4) — coarse 3-state MFA enrollment status,
 * derived from Supabase's `auth.mfa.retrieveFactorsForCurrentUser()` +
 * `getAuthenticatorAssuranceLevel()`. Used by the Settings → Security
 * surface to decide between:
 *
 * - [NotEnrolled]: no TOTP factor exists. Settings shows "Enable 2FA".
 * - [EnrolledUnverified]: a factor exists but the user never completed
 *   the QR-pairing verify step. Settings treats this as not-enrolled
 *   (offers "Enable 2FA" which calls unenroll-the-stub + re-enroll).
 * - [Enrolled]: a verified factor exists. Settings shows the disable
 *   action + recovery-code regeneration.
 *
 * The carried [factorId] in [Enrolled] / [EnrolledUnverified] is the
 * Supabase factor ID surfaced to disable / regenerate flows so they
 * can target the right factor without a follow-up `listFactors` call.
 */
sealed interface MfaEnrollmentStatus {
    data object NotEnrolled : MfaEnrollmentStatus

    data class EnrolledUnverified(
        val factorId: String,
    ) : MfaEnrollmentStatus

    data class Enrolled(
        val factorId: String,
    ) : MfaEnrollmentStatus
}
