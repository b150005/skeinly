package io.github.b150005.knitnote.domain.repository

import io.github.b150005.knitnote.domain.model.ChartRevision
import io.github.b150005.knitnote.domain.model.StructuredChart
import kotlinx.coroutines.flow.Flow

interface StructuredChartRepository {
    suspend fun getByPatternId(patternId: String): StructuredChart?

    fun observeByPatternId(patternId: String): Flow<StructuredChart?>

    suspend fun existsByPatternId(patternId: String): Boolean

    suspend fun create(chart: StructuredChart): StructuredChart

    suspend fun update(chart: StructuredChart): StructuredChart

    suspend fun delete(id: String)

    /**
     * Clone the chart document attached to [sourcePatternId] under [newPatternId]
     * with [newOwnerId] as the new owner.
     *
     * The cloned envelope has fresh `id`, `patternId`, `ownerId`, `revisionId`,
     * and `createdAt`/`updatedAt`. `parentRevisionId` is set to the source's
     * `revisionId` to seed commit-rooted lineage (ADR-012 §2). The `document`
     * payload (extents + layers + craft/reading metadata), `schemaVersion`,
     * `storageVariant`, `coordinateSystem`, and `contentHash` are preserved
     * byte-for-byte — drawing identity is unchanged on a fork (ADR-008 §7).
     *
     * Returns null if the source pattern has no structured chart attached.
     * Throws on storage failure (caller is expected to surface the error
     * via [io.github.b150005.knitnote.domain.usecase.ForkPublicPatternUseCase]'s
     * best-effort wrapper per ADR-012 §7).
     */
    suspend fun forkFor(
        sourcePatternId: String,
        newPatternId: String,
        newOwnerId: String,
    ): StructuredChart?

    /**
     * Materialize [targetRevision] as the new tip pointer for [patternId]
     * WITHOUT appending a revision to the commit history. Used by
     * `SwitchBranchUseCase` (ADR-013 §7) to point `chart_documents.revision_id`
     * at a branch's tip — switching branches is pointer movement, not a new
     * commit.
     *
     * Distinct from [update], which both appends a revision AND advances the
     * tip. Distinct from [create], which only runs on first save.
     *
     * The implementation reads the current tip pointer row inside its write
     * mutex and rewrites it in place with [targetRevision]'s drawing payload.
     * This is atomic: a concurrent `update()` from the chart editor cannot
     * interleave between the read and the write, so a user's in-flight save
     * will never be silently rolled back by a switch landing on a stale
     * payload.
     *
     * Returns null if no tip pointer row exists for [patternId] (caller
     * should treat as a hard error — a branch tip pointing at a chart with
     * no `chart_documents` row indicates a sync tear).
     */
    suspend fun setTip(
        patternId: String,
        targetRevision: ChartRevision,
    ): StructuredChart?
}
