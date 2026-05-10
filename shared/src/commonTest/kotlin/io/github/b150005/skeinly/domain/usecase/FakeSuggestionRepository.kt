package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionComment
import io.github.b150005.skeinly.domain.model.SuggestionStatus
import io.github.b150005.skeinly.domain.repository.SuggestionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * In-memory fake of [SuggestionRepository] keyed per ownerId. Each
 * `MutableStateFlow` is the live source the observe() use case subscribes to,
 * so test-side calls to [setIncoming] / [setOutgoing] simulate Realtime peer
 * appends landing through the repository's local cache.
 *
 * Phase 38.3 widens this fake from "read-side only" to cover getById /
 * comment Flows / open / close / postComment so the new use cases can be
 * exercised end-to-end. Unused-by-current-tests methods stay as `error()` so
 * accidental cross-test reach surfaces immediately rather than silently
 * passing through a stub.
 */
class FakeSuggestionRepository : SuggestionRepository {
    private val incoming = mutableMapOf<String, MutableStateFlow<List<Suggestion>>>()
    private val outgoing = mutableMapOf<String, MutableStateFlow<List<Suggestion>>>()
    private val byId = mutableMapOf<String, Suggestion>()
    private val commentFlows = mutableMapOf<String, MutableStateFlow<List<SuggestionComment>>>()

    var nextGetIncomingError: Throwable? = null
    var nextGetOutgoingError: Throwable? = null
    var nextGetByIdError: Throwable? = null
    var nextOpenError: Throwable? = null
    var nextCloseError: Throwable? = null
    var nextPostCommentError: Throwable? = null
    var nextGetCommentsError: Throwable? = null

    /**
     * Last argument received by [openSuggestion]. Lets tests assert that the
     * use case computed `commonAncestorRevisionId` correctly without reading
     * a return-only field.
     */
    var lastOpened: Suggestion? = null
        private set

    var lastClosed: Suggestion? = null
        private set

    var lastPosted: SuggestionComment? = null
        private set

    var subscribeCount: Int = 0
        private set

    var closeCount: Int = 0
        private set

    var lastSubscribedPrId: String? = null
        private set

    fun setIncoming(
        ownerId: String,
        prs: List<Suggestion>,
    ) {
        incoming.getOrPut(ownerId) { MutableStateFlow(emptyList()) }.value = prs
        prs.forEach { byId[it.id] = it }
    }

    fun setOutgoing(
        ownerId: String,
        prs: List<Suggestion>,
    ) {
        outgoing.getOrPut(ownerId) { MutableStateFlow(emptyList()) }.value = prs
        prs.forEach { byId[it.id] = it }
    }

    fun seedById(suggestion: Suggestion) {
        byId[suggestion.id] = suggestion
    }

    fun setComments(
        prId: String,
        comments: List<SuggestionComment>,
    ) {
        commentFlows.getOrPut(prId) { MutableStateFlow(emptyList()) }.value = comments
    }

    override suspend fun getById(id: String): Suggestion? {
        nextGetByIdError?.let {
            nextGetByIdError = null
            throw it
        }
        return byId[id]
    }

    override suspend fun getIncomingForOwner(ownerId: String): List<Suggestion> {
        nextGetIncomingError?.let {
            nextGetIncomingError = null
            throw it
        }
        return incoming[ownerId]?.value.orEmpty()
    }

    override suspend fun getOutgoingForOwner(ownerId: String): List<Suggestion> {
        nextGetOutgoingError?.let {
            nextGetOutgoingError = null
            throw it
        }
        return outgoing[ownerId]?.value.orEmpty()
    }

    override fun observeIncomingForOwner(ownerId: String): Flow<List<Suggestion>> =
        incoming.getOrPut(ownerId) { MutableStateFlow(emptyList()) }

    override fun observeOutgoingForOwner(ownerId: String): Flow<List<Suggestion>> =
        outgoing.getOrPut(ownerId) { MutableStateFlow(emptyList()) }

    override suspend fun getCommentsForSuggestion(suggestionId: String): List<SuggestionComment> {
        nextGetCommentsError?.let {
            nextGetCommentsError = null
            throw it
        }
        return commentFlows[suggestionId]?.value.orEmpty()
    }

    override fun observeCommentsForSuggestion(suggestionId: String): Flow<List<SuggestionComment>> =
        commentFlows.getOrPut(suggestionId) { MutableStateFlow(emptyList()) }

    override suspend fun openSuggestion(suggestion: Suggestion): Suggestion {
        nextOpenError?.let {
            nextOpenError = null
            throw it
        }
        lastOpened = suggestion
        byId[suggestion.id] = suggestion
        return suggestion
    }

    override suspend fun closeSuggestion(suggestion: Suggestion): Suggestion {
        nextCloseError?.let {
            nextCloseError = null
            throw it
        }
        val now: Instant = Clock.System.now()
        val closed =
            suggestion.copy(
                status = SuggestionStatus.CLOSED,
                closedAt = suggestion.closedAt ?: now,
                updatedAt = now,
            )
        lastClosed = closed
        byId[closed.id] = closed
        return closed
    }

    override suspend fun postComment(comment: SuggestionComment): SuggestionComment {
        nextPostCommentError?.let {
            nextPostCommentError = null
            throw it
        }
        lastPosted = comment
        val flow = commentFlows.getOrPut(comment.suggestionId) { MutableStateFlow(emptyList()) }
        flow.value = flow.value + comment
        return comment
    }

    override suspend fun subscribeToCommentsChannel(suggestionId: String) {
        subscribeCount += 1
        lastSubscribedPrId = suggestionId
    }

    override suspend fun closeCommentsChannel() {
        closeCount += 1
    }
}
