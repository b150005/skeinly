package io.github.b150005.skeinly.ui.moderation

import io.github.b150005.skeinly.domain.model.BlockedUser
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 39 (ADR-021 §D4) — locks the BlockedUsersViewModel list +
 * per-row unblock behaviour: init auto-load, empty state, optimistic
 * row removal, per-row spinner + re-tap guard, error surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockedUsersViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val alice = BlockedUser("aaaa1111-1111-1111-1111-111111111111", "Alice")
    private val bob = BlockedUser("bbbb2222-2222-2222-2222-222222222222", "Bob")

    @Test
    fun `init auto-loads the block list`() =
        runTest {
            val v =
                BlockedUsersViewModel(
                    loadBlocked = { UseCaseResult.Success(listOf(alice, bob)) },
                    unblock = { UseCaseResult.Success(Unit) },
                )
            assertEquals(listOf(alice, bob), v.state.value.users)
            assertFalse(v.state.value.isLoading)
            assertNull(v.state.value.error)
        }

    @Test
    fun `init load failure surfaces error`() =
        runTest {
            val v =
                BlockedUsersViewModel(
                    loadBlocked = { UseCaseResult.Failure(UseCaseError.Network(RuntimeException("offline"))) },
                    unblock = { UseCaseResult.Success(Unit) },
                )
            assertEquals(ErrorMessage.NetworkUnavailable, v.state.value.error)
            assertTrue(
                v.state.value.users
                    .isEmpty(),
            )
            assertFalse(v.state.value.isLoading)
        }

    @Test
    fun `empty load reports isEmpty true and auto-load actually fired`() =
        runTest {
            var loadCalls = 0
            val v =
                BlockedUsersViewModel(
                    loadBlocked = {
                        loadCalls++
                        UseCaseResult.Success(emptyList())
                    },
                    unblock = { UseCaseResult.Success(Unit) },
                )
            // Proves the `init { load() }` path ran (not just that the
            // pristine default state happens to be isEmpty == true).
            assertEquals(1, loadCalls, "init must auto-load exactly once")
            assertTrue(v.state.value.isEmpty)
        }

    @Test
    fun `pristine state before load shows loading not empty`() =
        runTest {
            // Lock the BlockedUsersState default isLoading = true fix:
            // the very first frame must render the loading branch, not
            // a one-frame empty-state flash.
            assertTrue(BlockedUsersState().isLoading)
            assertFalse(BlockedUsersState().isEmpty)
        }

    @Test
    fun `Unblock removes the row on success`() =
        runTest {
            var unblocked: String? = null
            val v =
                BlockedUsersViewModel(
                    loadBlocked = { UseCaseResult.Success(listOf(alice, bob)) },
                    unblock = { id ->
                        unblocked = id
                        UseCaseResult.Success(Unit)
                    },
                )
            v.onEvent(BlockedUsersEvent.Unblock(alice.userId))
            assertEquals(alice.userId, unblocked)
            assertEquals(listOf(bob), v.state.value.users)
            assertFalse(v.state.value.isUnblocking(alice.userId))
        }

    @Test
    fun `Unblock failure keeps the row and surfaces error`() =
        runTest {
            val v =
                BlockedUsersViewModel(
                    loadBlocked = { UseCaseResult.Success(listOf(alice)) },
                    unblock = { UseCaseResult.Failure(UseCaseError.Unknown(RuntimeException("boom"))) },
                )
            v.onEvent(BlockedUsersEvent.Unblock(alice.userId))
            assertEquals(listOf(alice), v.state.value.users, "row stays on failure")
            assertEquals(ErrorMessage.Generic, v.state.value.error)
            assertFalse(v.state.value.isUnblocking(alice.userId))
        }

    @Test
    fun `Unblock per-row spinner and re-tap guard`() =
        runTest {
            val gate = CompletableDeferred<UseCaseResult<Unit>>()
            var calls = 0
            val v =
                BlockedUsersViewModel(
                    loadBlocked = { UseCaseResult.Success(listOf(alice, bob)) },
                    unblock = {
                        calls++
                        gate.await()
                    },
                )
            v.onEvent(BlockedUsersEvent.Unblock(alice.userId))
            v.onEvent(BlockedUsersEvent.Unblock(alice.userId)) // same-row re-tap
            assertTrue(v.state.value.isUnblocking(alice.userId))
            assertFalse(v.state.value.isUnblocking(bob.userId))
            assertEquals(1, calls, "same-row re-tap swallowed")
            gate.complete(UseCaseResult.Success(Unit))
            assertEquals(listOf(bob), v.state.value.users)
        }

    @Test
    fun `explicit Load re-fetches`() =
        runTest {
            var loads = 0
            val v =
                BlockedUsersViewModel(
                    loadBlocked = {
                        loads++
                        UseCaseResult.Success(if (loads == 1) listOf(alice) else listOf(alice, bob))
                    },
                    unblock = { UseCaseResult.Success(Unit) },
                )
            assertEquals(listOf(alice), v.state.value.users)
            v.onEvent(BlockedUsersEvent.Load)
            assertEquals(listOf(alice, bob), v.state.value.users)
            assertEquals(2, loads)
        }

    @Test
    fun `ClearError drops the error`() =
        runTest {
            val v =
                BlockedUsersViewModel(
                    loadBlocked = { UseCaseResult.Failure(UseCaseError.Unknown(RuntimeException("x"))) },
                    unblock = { UseCaseResult.Success(Unit) },
                )
            assertEquals(ErrorMessage.Generic, v.state.value.error)
            v.onEvent(BlockedUsersEvent.ClearError)
            assertNull(v.state.value.error)
        }
}
