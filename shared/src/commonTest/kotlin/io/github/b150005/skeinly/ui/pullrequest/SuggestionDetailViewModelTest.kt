package io.github.b150005.skeinly.ui.pullrequest

import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.RecordingAnalyticsTracker
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.Difficulty
import io.github.b150005.skeinly.domain.model.Pattern
import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionComment
import io.github.b150005.skeinly.domain.model.SuggestionStatus
import io.github.b150005.skeinly.domain.model.User
import io.github.b150005.skeinly.domain.model.Visibility
import io.github.b150005.skeinly.domain.usecase.CloseSuggestionUseCase
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.domain.usecase.FakePatternRepository
import io.github.b150005.skeinly.domain.usecase.FakeSuggestionRepository
import io.github.b150005.skeinly.domain.usecase.FakeUserRepository
import io.github.b150005.skeinly.domain.usecase.GetSuggestionCommentsUseCase
import io.github.b150005.skeinly.domain.usecase.GetSuggestionUseCase
import io.github.b150005.skeinly.domain.usecase.PostSuggestionCommentUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SuggestionDetailViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var prRepo: FakeSuggestionRepository
    private lateinit var patternRepo: FakePatternRepository
    private lateinit var userRepo: FakeUserRepository
    private lateinit var authRepo: FakeAuthRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        prRepo = FakeSuggestionRepository()
        patternRepo = FakePatternRepository()
        userRepo = FakeUserRepository()
        authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated(userId = "owner-upstream", email = "u@example.com"))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeUpstreamPattern() =
        Pattern(
            id = "pat-upstream",
            ownerId = "owner-upstream",
            title = "Upstream pattern",
            description = null,
            difficulty = Difficulty.BEGINNER,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = Visibility.PUBLIC,
            createdAt = Instant.parse("2026-04-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-04-01T00:00:00Z"),
            parentPatternId = null,
        )

    private fun makePr(
        id: String = "pr-1",
        authorId: String? = "user-fork",
        status: SuggestionStatus = SuggestionStatus.OPEN,
    ) = Suggestion(
        id = id,
        sourcePatternId = "pat-fork",
        sourceBranchId = "br-source-main",
        sourceTipRevisionId = "rev-source-tip",
        targetPatternId = "pat-upstream",
        targetBranchId = "br-target-main",
        commonAncestorRevisionId = "rev-ancestor",
        authorId = authorId,
        title = "Reworked sleeve",
        description = "Adjusted decreases.",
        status = status,
        mergedRevisionId = null,
        mergedAt = null,
        closedAt = null,
        createdAt = Instant.parse("2026-04-25T10:00:00Z"),
        updatedAt = Instant.parse("2026-04-25T10:00:00Z"),
    )

    private fun makeComment(
        id: String,
        authorId: String? = "user-fork",
        body: String = "Looks good",
    ) = SuggestionComment(
        id = id,
        suggestionId = "pr-1",
        authorId = authorId,
        body = body,
        createdAt = Instant.parse("2026-04-25T11:00:00Z"),
    )

    private fun makeViewModel(
        prId: String = "pr-1",
        analyticsTracker: AnalyticsTracker? = null,
    ) = SuggestionDetailViewModel(
        prId = prId,
        getSuggestion = GetSuggestionUseCase(prRepo),
        getComments = GetSuggestionCommentsUseCase(prRepo),
        postComment = PostSuggestionCommentUseCase(prRepo, authRepo),
        closeSuggestion = CloseSuggestionUseCase(prRepo, authRepo),
        suggestionRepository = prRepo,
        patternRepository = patternRepo,
        userRepository = userRepo,
        authRepository = authRepo,
        analyticsTracker = analyticsTracker,
    )

    @Test
    fun `loads PR plus target owner plus seeded comments on init`() =
        runTest {
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())
            prRepo.setComments("pr-1", listOf(makeComment("c1")))

            val vm = makeViewModel()
            val state = vm.state.value

            assertEquals("pr-1", state.suggestion?.id)
            assertEquals("owner-upstream", state.targetOwnerId)
            assertEquals(listOf("c1"), state.comments.map { it.id })
            assertFalse(state.isLoading)
            assertNull(state.error)
        }

    @Test
    fun `surfaces NotFound error in state when PR id is unknown`() =
        runTest {
            val vm = makeViewModel(prId = "unknown")
            val state = vm.state.value

            assertNotNull(state.error)
            assertNull(state.suggestion)
        }

    @Test
    fun `subscribes to per-PR comments channel exactly once on init`() =
        runTest {
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())

            makeViewModel()

            assertEquals(1, prRepo.subscribeCount)
            assertEquals("pr-1", prRepo.lastSubscribedPrId)
        }

    @Test
    fun `canMerge gate is true when current user is target owner and PR is open`() =
        runTest {
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())

            val vm = makeViewModel()

            assertTrue(vm.state.value.canMerge)
        }

    @Test
    fun `canMerge gate is false when current user is the source author not the target owner`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated(userId = "user-fork", email = "f@example.com"))
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())

            val vm = makeViewModel()

            assertFalse(vm.state.value.canMerge)
        }

    @Test
    fun `canClose gate is true for source author`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated(userId = "user-fork", email = "f@example.com"))
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())

            val vm = makeViewModel()

            assertTrue(vm.state.value.canClose)
        }

    @Test
    fun `canClose gate is false for an unrelated user`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated(userId = "user-other", email = "o@example.com"))
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())

            val vm = makeViewModel()

            assertFalse(vm.state.value.canClose)
        }

    @Test
    fun `canClose is false on a merged PR even for a participant`() =
        runTest {
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr(status = SuggestionStatus.APPLIED))

            val vm = makeViewModel()

            assertFalse(vm.state.value.canClose)
        }

    @Test
    fun `CommentDraftChanged updates draft in state`() =
        runTest {
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())
            val vm = makeViewModel()

            vm.onEvent(SuggestionDetailEvent.CommentDraftChanged("Draft text"))

            assertEquals("Draft text", vm.state.value.commentDraft)
        }

    @Test
    fun `PostComment writes through and clears the draft on success`() =
        runTest {
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())
            val vm = makeViewModel()
            vm.onEvent(SuggestionDetailEvent.CommentDraftChanged("Looks good"))

            vm.onEvent(SuggestionDetailEvent.PostComment)

            val state = vm.state.value
            assertEquals("", state.commentDraft)
            assertNotNull(prRepo.lastPosted)
        }

    @Test
    fun `PostComment success emits CommentPosted nav event for notification trigger`() =
        runTest {
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())
            val vm = makeViewModel()
            vm.onEvent(SuggestionDetailEvent.CommentDraftChanged("Nice work"))

            vm.onEvent(SuggestionDetailEvent.PostComment)

            // Phase 24.2c-3 — the screen layer routes this nav event to
            // NotificationPermissionViewModel as the PR_COMMENT_POSTED
            // engagement-moment trigger for the in-app pre-permission
            // explainer.
            val event = vm.navEvents.first()
            assertEquals(SuggestionDetailNavEvent.CommentPosted, event)
        }

    @Test
    fun `RequestClose then ConfirmClose flips PR status to CLOSED and emits PrClosed nav event`() =
        runTest {
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())
            val vm = makeViewModel()

            vm.onEvent(SuggestionDetailEvent.RequestClose)
            assertTrue(vm.state.value.pendingCloseConfirmation)
            vm.onEvent(SuggestionDetailEvent.ConfirmClose)

            assertEquals(
                SuggestionStatus.CLOSED,
                vm.state.value.suggestion
                    ?.status,
            )
            // Channel emits the PrClosed event.
            val event = vm.navEvents.first()
            assertEquals(SuggestionDetailNavEvent.PrClosed, event)
        }

    @Test
    fun `DismissCloseConfirmation clears the pending dialog without closing`() =
        runTest {
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())
            val vm = makeViewModel()

            vm.onEvent(SuggestionDetailEvent.RequestClose)
            vm.onEvent(SuggestionDetailEvent.DismissCloseConfirmation)

            assertFalse(vm.state.value.pendingCloseConfirmation)
            assertEquals(
                SuggestionStatus.OPEN,
                vm.state.value.suggestion
                    ?.status,
            )
        }

    @Test
    fun `ClearError nulls a surfaced error message`() =
        runTest {
            val vm = makeViewModel(prId = "missing")
            assertNotNull(vm.state.value.error)

            vm.onEvent(SuggestionDetailEvent.ClearError)

            assertNull(vm.state.value.error)
        }

    @Test
    fun `resolves comment author display name when author is known`() =
        runTest {
            userRepo.addUser(
                User(
                    id = "user-fork",
                    displayName = "Forker Frances",
                    avatarUrl = null,
                    bio = null,
                    createdAt = Instant.parse("2026-04-01T00:00:00Z"),
                ),
            )
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())
            prRepo.setComments("pr-1", listOf(makeComment("c1", authorId = "user-fork")))

            val vm = makeViewModel()

            assertEquals(
                "Forker Frances",
                vm.state.value.users["user-fork"]
                    ?.displayName,
            )
        }

    @Test
    fun `successful PostComment captures pull_request_commented`() =
        runTest {
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())
            val tracker = RecordingAnalyticsTracker()
            val vm = makeViewModel(analyticsTracker = tracker)
            vm.onEvent(SuggestionDetailEvent.CommentDraftChanged("Looks good"))
            vm.onEvent(SuggestionDetailEvent.PostComment)

            assertEquals(listOf<AnalyticsEvent>(AnalyticsEvent.SuggestionCommented), tracker.outcomeEvents)
        }

    @Test
    fun `successful ConfirmClose captures pull_request_closed`() =
        runTest {
            patternRepo.create(makeUpstreamPattern())
            prRepo.seedById(makePr())
            val tracker = RecordingAnalyticsTracker()
            val vm = makeViewModel(analyticsTracker = tracker)
            vm.onEvent(SuggestionDetailEvent.RequestClose)
            vm.onEvent(SuggestionDetailEvent.ConfirmClose)

            assertEquals(listOf<AnalyticsEvent>(AnalyticsEvent.SuggestionClosed), tracker.outcomeEvents)
        }
}
