package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.domain.model.Pattern

/**
 * Not thread-safe by design: the mutator helpers ([addPattern] /
 * [markHasChart] / [markFriendsOnly]) and [getPublic] are only ever
 * touched from a single test thread. All consuming tests run under
 * `runTest` with `StandardTestDispatcher`, which serializes every
 * dispatched coroutine onto the test thread — so there is no concurrent
 * access. If a future test ports to `UnconfinedTestDispatcher` or a
 * real multi-threaded dispatcher, wrap the collections (or snapshot
 * them) before relying on this fake under concurrency.
 */
class FakePublicPatternDataSource : PublicPatternDataSource {
    private val patterns = mutableListOf<Pattern>()
    private val patternsWithCharts = mutableSetOf<String>()
    private val friendsOnlyPatternIds = mutableSetOf<String>()
    var shouldFail = false

    /** The most-recent `includeFriendsOnly` value [getPublic] was
     *  called with — lets Phase 25.5 tests assert the toggle threads
     *  the flag through the use case + ViewModel without a real DB. */
    var lastIncludeFriendsOnly: Boolean = false
        private set

    override suspend fun getPublic(
        searchQuery: String,
        limit: Int,
        chartsOnly: Boolean,
        includeFriendsOnly: Boolean,
    ): PublicPatternsResult {
        if (shouldFail) throw RuntimeException("Fake remote failure")
        lastIncludeFriendsOnly = includeFriendsOnly
        val matching =
            if (searchQuery.isBlank()) {
                patterns
            } else {
                patterns.filter { it.title.contains(searchQuery, ignoreCase = true) }
            }
        // Phase 25.5: when the friends-only opt-in is OFF, the fake
        // mirrors the real query's `visibility = 'public'` filter by
        // dropping any pattern the test marked friends-only. When ON,
        // friends-only rows are included (the real RLS gating is not
        // modelled here — the fake stands in for an already-friend
        // caller, which is the scenario these tests exercise).
        val visibilityFiltered =
            if (includeFriendsOnly) {
                matching
            } else {
                matching.filter { it.id !in friendsOnlyPatternIds }
            }
        val filtered =
            if (chartsOnly) {
                visibilityFiltered.filter { it.id in patternsWithCharts }
            } else {
                visibilityFiltered
            }
        val limited = filtered.take(limit)
        // Companion set is always populated regardless of `chartsOnly`. When
        // `chartsOnly = true` it equals the returned id set; when false it
        // names which of the returned ids have charts (Phase 36.4 / ADR-012 §5).
        val companion = limited.map { it.id }.toSet().intersect(patternsWithCharts)
        return PublicPatternsResult(patterns = limited, patternsWithCharts = companion)
    }

    fun addPattern(pattern: Pattern) {
        patterns.add(pattern)
    }

    /**
     * Mark the given pattern id as having a structured chart attached. Used by
     * Phase 36.4 tests to exercise the `chartsOnly` filter and the
     * `patternsWithCharts` companion set.
     */
    fun markHasChart(patternId: String) {
        patternsWithCharts.add(patternId)
    }

    /**
     * Phase 25.5 (ADR-024 §(f)) — mark a pattern as friends-only
     * visibility. With the friends-only opt-in OFF (the Discovery
     * default), [getPublic] excludes it (mirrors the real
     * `visibility = 'public'` filter); with the opt-in ON it is
     * returned (the fake models an already-friend caller).
     */
    fun markFriendsOnly(patternId: String) {
        friendsOnlyPatternIds.add(patternId)
    }
}
