package io.github.b150005.skeinly.domain.model

/**
 * Phase 39 (ADR-021 §D4) — closed reason taxonomy for a UGC report.
 *
 * [wireValue] matches the `reason_category` CHECK on
 * `public.ugc_reports` (migration 031) and the `REASON_CATEGORIES`
 * closed enum in `supabase/functions/submit-ugc-report/mapping.ts`.
 * The three must move together — a renamed category is a coupled edit
 * across the migration, the Edge Function, and this enum.
 *
 * Closed enum so the Compose / SwiftUI category pickers stay
 * exhaustive — adding a category is a deliberate UI act, not a silent
 * string passing through.
 */
enum class UgcReportCategory(
    val wireValue: String,
) {
    Spam("spam"),
    Harassment("harassment"),
    Sexual("sexual"),
    Violence("violence"),
    Hate("hate"),
    IntellectualProperty("ip"),
    Other("other"),
    ;

    companion object {
        fun fromWire(value: String): UgcReportCategory =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unknown UgcReportCategory wire value: $value")
    }
}

/**
 * Phase 39 (ADR-021 §D4) — the kind of UGC element a report names.
 *
 * [wireValue] matches the `target_type` CHECK on
 * `public.ugc_reports` (migration 031) and the `TARGET_TYPES` closed
 * enum in `submit-ugc-report/mapping.ts`. The Edge Function's
 * operator-triage SQL template interpolates `public.<wireValue>s`
 * (note the trailing `s`) so the values must stay table-name aligned.
 */
enum class UgcTargetType(
    val wireValue: String,
) {
    Pattern("pattern"),
    Comment("comment"),
    Suggestion("suggestion"),
    SuggestionComment("suggestion_comment"),
    ;

    companion object {
        fun fromWire(value: String): UgcTargetType =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unknown UgcTargetType wire value: $value")
    }
}

/**
 * Phase 39 (ADR-021 §D4) — a row of the Settings → Privacy → Blocked
 * Users list. [userId] is the blocked party's `auth.users.id`;
 * [displayName] is resolved from `public.profiles` at list time so the
 * UI can render a human-readable row + an Unblock affordance.
 *
 * The blocker side is always the caller (`auth.uid()`); the
 * `user_blocks` SELECT RLS policy (migration 031) scopes the list to
 * `blocker_id = auth.uid()` so this model never carries the blocker.
 */
data class BlockedUser(
    val userId: String,
    val displayName: String,
)

/**
 * ADR-021 §D1 — `reason` CHECK on `public.ugc_reports` is
 * `length(reason) BETWEEN 1 AND 2000`. Mirrored client-side so the
 * report modal can disable Submit + show a counter without a
 * round-trip, and so `UgcModerationRepositoryImpl` rejects an
 * over-long / blank reason before the network call. Must equal
 * `MAX_REASON_LENGTH` in `submit-ugc-report/mapping.ts`.
 */
const val MAX_UGC_REASON_LENGTH: Int = 2000
