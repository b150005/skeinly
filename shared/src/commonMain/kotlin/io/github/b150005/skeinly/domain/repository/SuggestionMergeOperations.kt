package io.github.b150005.skeinly.domain.repository

import kotlinx.serialization.json.JsonElement

/**
 * Phase 38.4 (ADR-014 §5) — thin port over the SECURITY DEFINER
 * `apply_suggestion` RPC.
 *
 * Lives in the domain layer so
 * [io.github.b150005.skeinly.domain.usecase.ApplySuggestionUseCase] can be
 * unit tested without standing up a Supabase client. Production wiring is
 * `RemoteSuggestionDataSource.merge` which routes through
 * `supabaseClient.postgrest.rpc(...)`; tests provide an in-memory fake.
 *
 * Same layering convention as the other ports under this package
 * ([ChartVersionRepository], [ChartRepository], etc.) — the
 * domain layer defines the contract; data-layer adapters implement it.
 *
 * **Naming note.** The interface + method names retain the "merge" verb for
 * Batch 1.5 of the post-v1 cleanup to flip in a separate commit (interface
 * rename + Swift bridge update + Koin re-registration is wider in scope
 * than this batch's KDoc-and-channel-name pass).
 */
interface SuggestionMergeOperations {
    /**
     * Invoke the apply RPC. Returns the new version id the RPC minted
     * (matches [resolvedRevisionId] on success). Throws on RPC errors which
     * the use case translates to
     * [io.github.b150005.skeinly.domain.usecase.UseCaseError] subtypes.
     */
    suspend fun merge(
        suggestionId: String,
        strategy: String,
        mergedDocument: JsonElement,
        mergedContentHash: String,
        resolvedRevisionId: String,
    ): String
}
