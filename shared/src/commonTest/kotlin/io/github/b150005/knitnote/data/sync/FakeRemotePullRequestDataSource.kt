package io.github.b150005.knitnote.data.sync

import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestComment

/**
 * Recording fake for the pull-request remote sync surface (ADR-014 §7).
 *
 * Implements both the PR-row sync operations and the comment append-only
 * surface — same shape as the production [io.github.b150005.knitnote.data.remote.RemotePullRequestDataSource]
 * combined implementation.
 *
 * No `delete` recorder for PRs: PRs are kept as audit trail per ADR-014 §1
 * and the production interface does not surface delete. The SyncExecutor
 * silent-no-op DELETE branch matches `executeChartRevision`'s precedent and
 * is exercised by setting `entity.operation = DELETE` against this fake.
 */
class FakeRemotePullRequestDataSource :
    RemotePullRequestSyncOperations,
    RemotePullRequestCommentSyncOperations {
    val upsertedPullRequests = mutableListOf<PullRequest>()
    val appendedComments = mutableListOf<PullRequestComment>()
    var shouldFailUpsert = false
    var shouldFailAppendComment = false

    override suspend fun upsert(pullRequest: PullRequest): PullRequest {
        if (shouldFailUpsert) throw RuntimeException("Fake remote upsert failure")
        upsertedPullRequests.add(pullRequest)
        return pullRequest
    }

    override suspend fun appendComment(comment: PullRequestComment): PullRequestComment {
        if (shouldFailAppendComment) throw RuntimeException("Fake remote append failure")
        appendedComments.add(comment)
        return comment
    }
}
