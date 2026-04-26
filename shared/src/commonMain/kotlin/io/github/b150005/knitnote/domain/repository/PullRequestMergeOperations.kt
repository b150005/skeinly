package io.github.b150005.knitnote.domain.repository

import kotlinx.serialization.json.JsonElement

/**
 * Phase 38.4 (ADR-014 §5) — thin port over the SECURITY DEFINER
 * `merge_pull_request` RPC.
 *
 * Lives in the domain layer so
 * [io.github.b150005.knitnote.domain.usecase.MergePullRequestUseCase] can be
 * unit tested without standing up a Supabase client. Production wiring is
 * `RemotePullRequestDataSource.merge` which routes through
 * `supabaseClient.postgrest.rpc(...)`; tests provide an in-memory fake.
 *
 * Same layering convention as the other ports under this package
 * ([ChartRevisionRepository], [StructuredChartRepository], etc.) — the
 * domain layer defines the contract; data-layer adapters implement it.
 */
interface PullRequestMergeOperations {
    /**
     * Invoke the merge RPC. Returns the new revision id the RPC minted
     * (matches [resolvedRevisionId] on success). Throws on RPC errors which
     * the use case translates to
     * [io.github.b150005.knitnote.domain.usecase.UseCaseError] subtypes.
     */
    suspend fun merge(
        pullRequestId: String,
        strategy: String,
        mergedDocument: JsonElement,
        mergedContentHash: String,
        resolvedRevisionId: String,
    ): String
}
