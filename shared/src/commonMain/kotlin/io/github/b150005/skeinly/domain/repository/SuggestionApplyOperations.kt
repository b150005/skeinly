package io.github.b150005.skeinly.domain.repository

import kotlinx.serialization.json.JsonElement

/**
 * Phase 38.4 (ADR-014 §5) — thin port over the SECURITY DEFINER
 * `apply_suggestion` RPC.
 *
 * Lives in the domain layer so
 * [io.github.b150005.skeinly.domain.usecase.ApplySuggestionUseCase] can be
 * unit tested without standing up a Supabase client. Production wiring is
 * `RemoteSuggestionDataSource.apply` which routes through
 * `supabaseClient.postgrest.rpc(...)`; tests provide an in-memory fake.
 *
 * Same layering convention as the other ports under this package
 * ([ChartVersionRepository], [ChartRepository], etc.) — the
 * domain layer defines the contract; data-layer adapters implement it.
 */
interface SuggestionApplyOperations {
    /**
     * Invoke the apply RPC. Returns the new version id the RPC minted
     * (matches [resolvedRevisionId] on success). Throws on RPC errors which
     * the use case translates to
     * [io.github.b150005.skeinly.domain.usecase.UseCaseError] subtypes.
     */
    suspend fun apply(
        suggestionId: String,
        strategy: String,
        appliedDocument: JsonElement,
        appliedContentHash: String,
        resolvedRevisionId: String,
    ): String
}
