package io.github.b150005.skeinly.data.sync

import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionComment

/**
 * Recording fake for the pull-request remote sync surface (ADR-014 §7).
 *
 * Implements both the PR-row sync operations and the comment append-only
 * surface — same shape as the production [io.github.b150005.skeinly.data.remote.RemoteSuggestionDataSource]
 * combined implementation.
 *
 * No `delete` recorder for PRs: PRs are kept as audit trail per ADR-014 §1
 * and the production interface does not surface delete. The SyncExecutor
 * silent-no-op DELETE branch matches `executeChartVersion`'s precedent and
 * is exercised by setting `entity.operation = DELETE` against this fake.
 */
class FakeRemoteSuggestionDataSource :
    RemoteSuggestionSyncOperations,
    RemoteSuggestionCommentSyncOperations {
    val upsertedSuggestions = mutableListOf<Suggestion>()
    val appendedComments = mutableListOf<SuggestionComment>()
    var shouldFailUpsert = false
    var shouldFailAppendComment = false

    override suspend fun upsert(suggestion: Suggestion): Suggestion {
        if (shouldFailUpsert) throw RuntimeException("Fake remote upsert failure")
        upsertedSuggestions.add(suggestion)
        return suggestion
    }

    override suspend fun appendComment(comment: SuggestionComment): SuggestionComment {
        if (shouldFailAppendComment) throw RuntimeException("Fake remote append failure")
        appendedComments.add(comment)
        return comment
    }
}
