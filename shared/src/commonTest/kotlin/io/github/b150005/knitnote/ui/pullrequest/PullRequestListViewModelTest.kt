package io.github.b150005.knitnote.ui.pullrequest

import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestStatus
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import io.github.b150005.knitnote.domain.usecase.FakePullRequestRepository
import io.github.b150005.knitnote.domain.usecase.FakeUserRepository
import io.github.b150005.knitnote.domain.usecase.GetIncomingPullRequestsUseCase
import io.github.b150005.knitnote.domain.usecase.GetOutgoingPullRequestsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class PullRequestListViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var prRepo: FakePullRequestRepository
    private lateinit var authRepo: FakeAuthRepository
    private lateinit var userRepo: FakeUserRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        prRepo = FakePullRequestRepository()
        authRepo = FakeAuthRepository()
        userRepo = FakeUserRepository()
        authRepo.setAuthState(AuthState.Authenticated(userId = "owner-1", email = "o@example.com"))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makePr(
        id: String,
        authorId: String? = "user-1",
        targetPatternId: String = "pat-upstream",
        status: PullRequestStatus = PullRequestStatus.OPEN,
        createdAtIso: String = "2026-04-25T10:00:00Z",
    ) = PullRequest(
        id = id,
        sourcePatternId = "pat-fork",
        sourceBranchId = "br-source-main",
        sourceTipRevisionId = "rev-source-tip",
        targetPatternId = targetPatternId,
        targetBranchId = "br-target-main",
        commonAncestorRevisionId = "rev-ancestor",
        authorId = authorId,
        title = "Reworked sleeve",
        description = null,
        status = status,
        mergedRevisionId = null,
        mergedAt = null,
        closedAt = null,
        createdAt = Instant.parse(createdAtIso),
        updatedAt = Instant.parse(createdAtIso),
    )

    private fun makeViewModel(defaultFilter: PullRequestFilter = PullRequestFilter.INCOMING) =
        PullRequestListViewModel(
            defaultFilter = defaultFilter,
            getIncoming = GetIncomingPullRequestsUseCase(prRepo),
            getOutgoing = GetOutgoingPullRequestsUseCase(prRepo),
            authRepository = authRepo,
            userRepository = userRepo,
        )

    @Test
    fun `loads incoming list when default filter is Incoming`() =
        runTest {
            prRepo.setIncoming("owner-1", listOf(makePr("pr-in-1"), makePr("pr-in-2")))

            val vm = makeViewModel(PullRequestFilter.INCOMING)
            val state = vm.state.value

            assertEquals(PullRequestFilter.INCOMING, state.filter)
            assertEquals(listOf("pr-in-1", "pr-in-2"), state.pullRequests.map { it.id })
            assertFalse(state.isLoading)
            assertNull(state.error)
        }

    @Test
    fun `loads outgoing list when default filter is Outgoing`() =
        runTest {
            prRepo.setOutgoing("owner-1", listOf(makePr("pr-out-1")))

            val vm = makeViewModel(PullRequestFilter.OUTGOING)
            val state = vm.state.value

            assertEquals(PullRequestFilter.OUTGOING, state.filter)
            assertEquals(listOf("pr-out-1"), state.pullRequests.map { it.id })
        }

    @Test
    fun `SelectFilter switches list to outgoing`() =
        runTest {
            prRepo.setIncoming("owner-1", listOf(makePr("pr-in-1")))
            prRepo.setOutgoing("owner-1", listOf(makePr("pr-out-1")))
            val vm = makeViewModel(PullRequestFilter.INCOMING)

            vm.onEvent(PullRequestListEvent.SelectFilter(PullRequestFilter.OUTGOING))

            val state = vm.state.value
            assertEquals(PullRequestFilter.OUTGOING, state.filter)
            assertEquals(listOf("pr-out-1"), state.pullRequests.map { it.id })
        }

    @Test
    fun `SelectFilter to current filter is no-op`() =
        runTest {
            prRepo.setIncoming("owner-1", listOf(makePr("pr-in-1")))
            val vm = makeViewModel(PullRequestFilter.INCOMING)

            // Same filter — should not retrigger isLoading
            vm.onEvent(PullRequestListEvent.SelectFilter(PullRequestFilter.INCOMING))

            val state = vm.state.value
            assertEquals(PullRequestFilter.INCOMING, state.filter)
            assertFalse(state.isLoading)
        }

    @Test
    fun `repository flow emission updates list live without re-init`() =
        runTest {
            val vm = makeViewModel(PullRequestFilter.INCOMING)
            assertTrue(
                vm.state.value.pullRequests
                    .isEmpty(),
            )

            // Simulate a peer device opening a PR — the local cache learns of
            // it through Realtime, observeIncomingForOwner emits.
            prRepo.setIncoming("owner-1", listOf(makePr("pr-late")))

            assertEquals(
                "pr-late",
                vm.state.value.pullRequests
                    .first()
                    .id,
            )
        }

    @Test
    fun `surfaces error when current user is not signed in`() =
        runTest {
            authRepo.setAuthState(AuthState.Unauthenticated)

            val vm = makeViewModel()

            val state = vm.state.value
            assertFalse(state.isLoading)
            assertNotNull(state.error)
        }

    @Test
    fun `resolves user display names for prs with known authorIds`() =
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
            prRepo.setIncoming("owner-1", listOf(makePr("pr-1", authorId = "user-fork")))

            val vm = makeViewModel(PullRequestFilter.INCOMING)
            val state = vm.state.value

            assertEquals("Forker Frances", state.users["user-fork"]?.displayName)
        }

    @Test
    fun `users map stays empty when authorId is null on every row`() =
        runTest {
            prRepo.setIncoming("owner-1", listOf(makePr("pr-orphan", authorId = null)))

            val vm = makeViewModel(PullRequestFilter.INCOMING)

            assertTrue(
                vm.state.value.users
                    .isEmpty(),
            )
        }

    @Test
    fun `ClearError nulls the surfaced error message`() =
        runTest {
            authRepo.setAuthState(AuthState.Unauthenticated)
            val vm = makeViewModel()
            assertNotNull(vm.state.value.error)

            vm.onEvent(PullRequestListEvent.ClearError)

            assertNull(vm.state.value.error)
        }

    @Test
    fun `ViewModel is scoped to the signed-in ownerId only`() =
        runTest {
            prRepo.setIncoming("owner-1", listOf(makePr("pr-mine")))
            prRepo.setIncoming("owner-other", listOf(makePr("pr-not-mine")))

            val vm = makeViewModel(PullRequestFilter.INCOMING)
            val ids =
                vm.state.value.pullRequests
                    .map { it.id }

            assertEquals(listOf("pr-mine"), ids)
        }

    @Test
    fun `seed failure surfaces error in state`() =
        runTest {
            // The cold-launch seed (suspend invoke) runs first inside the
            // flatMapLatest's flow body. When it fails, the error should land
            // on _state.error before the live observe takes over.
            prRepo.nextGetIncomingError = IllegalStateException("seed boom")

            val vm = makeViewModel(PullRequestFilter.INCOMING)

            assertNotNull(vm.state.value.error)
        }

    @Test
    fun `SelectFilter clears resolved users so stale entries do not bleed across filters`() =
        runTest {
            // Seed an INCOMING author so the users map is populated.
            userRepo.addUser(
                User(
                    id = "user-incoming",
                    displayName = "Incoming Ivy",
                    avatarUrl = null,
                    bio = null,
                    createdAt = Instant.parse("2026-04-01T00:00:00Z"),
                ),
            )
            prRepo.setIncoming("owner-1", listOf(makePr("pr-1", authorId = "user-incoming")))

            val vm = makeViewModel(PullRequestFilter.INCOMING)
            assertEquals(
                "Incoming Ivy",
                vm.state.value.users["user-incoming"]
                    ?.displayName,
            )

            // Seed OUTGOING with no rows so resolveUsers does not refresh.
            prRepo.setOutgoing("owner-1", emptyList())
            vm.onEvent(PullRequestListEvent.SelectFilter(PullRequestFilter.OUTGOING))

            // The old INCOMING author entry must NOT survive the filter switch
            // — that would let a same-id collision render the wrong display
            // name on the OUTGOING surface.
            assertTrue(
                vm.state.value.users
                    .isEmpty(),
            )
        }
}
