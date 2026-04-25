package io.github.b150005.knitnote.domain.repository

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
}
