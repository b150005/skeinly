package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.domain.model.Pattern

/**
 * Result envelope for [PublicPatternDataSource.getPublic] (Phase 36.4 / ADR-012 §5).
 *
 * `patterns` is the public-pattern list as filtered by [searchQuery] / [chartsOnly]
 * (see [PublicPatternDataSource.getPublic]). `patternsWithCharts` is the companion
 * set of pattern ids that have a `chart_documents` row, populated by a single
 * secondary query against the same returned id batch — kept off the [Pattern]
 * data class so the inline JSON cost of fetching public patterns stays bounded.
 *
 * The companion set is always populated, regardless of `chartsOnly`. Callers
 * (Discovery PatternCard) check `pattern.id in patternsWithCharts` to decide
 * whether to render the chart-preview thumbnail.
 *
 * The PNG-thumbnail-column alternative is explicitly deferred per ADR-012 §8.
 */
data class PublicPatternsResult(
    val patterns: List<Pattern>,
    val patternsWithCharts: Set<String>,
)

interface PublicPatternDataSource {
    /**
     * Fetch the public-pattern list plus the chart-presence companion set.
     *
     * @param searchQuery substring match against title, blank = no filter.
     * @param limit max patterns to return.
     * @param chartsOnly when true the result list is filtered server-side to
     *  patterns whose `chart_documents` row exists. The companion set is the
     *  same set of ids in that case. When false the result list is unfiltered
     *  but the companion set still names which of the returned ids have
     *  charts. See ADR-012 §4 / §5.
     * @param includeFriendsOnly Phase 25.5 (ADR-024 §(f)): when false the
     *  query filters `visibility = 'public'` (the default Discovery feed).
     *  When true it widens to `visibility IN ('public', 'friends')`. The
     *  `friends` rows are still gated server-side by the Phase 25.1
     *  patterns-SELECT RLS policy (`is_friend(auth.uid(), owner_id)` arm),
     *  so this flag only controls whether the client asks for friends
     *  rows — RLS decides which it may actually return. Default false
     *  keeps the public-only behavior unchanged for non-Phase-25.5 callers.
     */
    suspend fun getPublic(
        searchQuery: String = "",
        limit: Int = 100,
        chartsOnly: Boolean = false,
        includeFriendsOnly: Boolean = false,
    ): PublicPatternsResult
}
