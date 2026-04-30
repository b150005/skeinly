package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.data.sync.RemotePatternSyncOperations
import io.github.b150005.skeinly.domain.model.Pattern
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class RemotePatternDataSource(
    private val supabaseClient: SupabaseClient,
) : RemotePatternSyncOperations,
    PublicPatternDataSource {
    private val table get() = supabaseClient.postgrest["patterns"]

    suspend fun getByOwnerId(ownerId: String): List<Pattern> =
        table
            .select {
                filter { eq("owner_id", ownerId) }
            }.decodeList()

    suspend fun getById(id: String): Pattern? =
        table
            .select {
                filter { eq("id", id) }
            }.decodeSingleOrNull()

    /**
     * Phase 36.4 (ADR-012 §4 / §5):
     * - When `chartsOnly = true` we add `chart_documents!inner(pattern_id)` to
     *   the column projection so PostgREST emits an INNER JOIN, filtering the
     *   list to public patterns whose `chart_documents` row exists. Index on
     *   `chart_documents.pattern_id` (migration 012) bounds the cost. The
     *   companion-set is the same as the returned id set — derived locally,
     *   no second round trip.
     * - When `chartsOnly = false` we issue a secondary `chart_documents`
     *   query naming which of the returned ids have charts so PatternCard
     *   can decide per-row whether to render the live thumbnail.
     * - The nested `chart_documents` field on each pattern row is dropped
     *   silently by [io.github.b150005.skeinly.di.SyncModule]'s
     *   `Json { ignoreUnknownKeys = true }` so [Pattern]'s deserializer does
     *   not need to learn about it.
     */
    override suspend fun getPublic(
        searchQuery: String,
        limit: Int,
        chartsOnly: Boolean,
    ): PublicPatternsResult {
        val columns =
            if (chartsOnly) {
                Columns.raw("*, chart_documents!inner(pattern_id)")
            } else {
                Columns.ALL
            }
        val patterns: List<Pattern> =
            table
                .select(columns) {
                    filter {
                        eq("visibility", "public")
                        if (searchQuery.isNotBlank()) {
                            ilike("title", "%$searchQuery%")
                        }
                    }
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(limit.toLong())
                }.decodeList()

        val patternIds = patterns.map { it.id }
        val patternsWithCharts: Set<String> =
            when {
                patternIds.isEmpty() -> emptySet()
                // INNER JOIN already filtered the list to chartful rows; the
                // companion set equals the returned id set. Skip the round trip.
                chartsOnly -> patternIds.toSet()
                else ->
                    supabaseClient.postgrest["chart_documents"]
                        .select(Columns.raw("pattern_id")) {
                            filter { isIn("pattern_id", patternIds) }
                        }.decodeList<ChartDocPatternIdRow>()
                        .map { it.patternId }
                        .toSet()
            }
        return PublicPatternsResult(patterns = patterns, patternsWithCharts = patternsWithCharts)
    }

    override suspend fun upsert(pattern: Pattern): Pattern =
        table
            .upsert(pattern) {
                select()
            }.decodeSingle()

    override suspend fun update(pattern: Pattern): Pattern =
        table
            .update(pattern) {
                select()
                filter { eq("id", pattern.id) }
            }.decodeSingle()

    override suspend fun delete(id: String) {
        table.delete {
            filter { eq("id", id) }
        }
    }

    @Serializable
    private data class ChartDocPatternIdRow(
        @SerialName("pattern_id") val patternId: String,
    )
}
