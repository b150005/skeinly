package io.github.b150005.skeinly.ui.moderation

import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 39 (ADR-021 §D4) — locks the BlockUserViewModel single-shot
 * confirm action: success nav, error surface, re-entry guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockUserViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val blockedId = "bbbb2222-2222-2222-2222-222222222222"

    @Test
    fun `initial state is idle`() =
        runTest {
            val v = BlockUserViewModel(blockedId) { UseCaseResult.Success(Unit) }
            assertFalse(v.state.value.isBlocking)
            assertNull(v.state.value.error)
        }

    @Test
    fun `Confirm success emits Blocked nav and clears blocking`() =
        runTest {
            var passed: String? = null
            val v =
                BlockUserViewModel(blockedId) { id ->
                    passed = id
                    UseCaseResult.Success(Unit)
                }
            v.onEvent(BlockUserEvent.Confirm)
            assertEquals(BlockUserNavEvent.Blocked, v.navEvents.first())
            assertEquals(blockedId, passed)
            assertFalse(v.state.value.isBlocking)
        }

    @Test
    fun `Confirm failure surfaces error and no nav`() =
        runTest {
            val v =
                BlockUserViewModel(blockedId) {
                    UseCaseResult.Failure(UseCaseError.Network(RuntimeException("offline")))
                }
            v.onEvent(BlockUserEvent.Confirm)
            assertEquals(ErrorMessage.NetworkUnavailable, v.state.value.error)
            assertFalse(v.state.value.isBlocking)
        }

    @Test
    fun `Confirm self-block OperationNotAllowed surfaces error`() =
        runTest {
            val v =
                BlockUserViewModel(blockedId) {
                    UseCaseResult.Failure(UseCaseError.OperationNotAllowed)
                }
            v.onEvent(BlockUserEvent.Confirm)
            assertEquals(ErrorMessage.OperationNotAllowed, v.state.value.error)
        }

    @Test
    fun `Confirm re-entry guard collapses double tap to one call`() =
        runTest {
            val gate = CompletableDeferred<UseCaseResult<Unit>>()
            var calls = 0
            val v =
                BlockUserViewModel(blockedId) {
                    calls++
                    gate.await()
                }
            v.onEvent(BlockUserEvent.Confirm)
            v.onEvent(BlockUserEvent.Confirm)
            assertTrue(v.state.value.isBlocking)
            assertEquals(1, calls, "second tap swallowed while in flight")
            gate.complete(UseCaseResult.Success(Unit))
            assertEquals(BlockUserNavEvent.Blocked, v.navEvents.first())
        }

    @Test
    fun `ClearError drops the error`() =
        runTest {
            val v =
                BlockUserViewModel(blockedId) {
                    UseCaseResult.Failure(UseCaseError.Unknown(RuntimeException("x")))
                }
            v.onEvent(BlockUserEvent.Confirm)
            assertEquals(ErrorMessage.Generic, v.state.value.error)
            v.onEvent(BlockUserEvent.ClearError)
            assertNull(v.state.value.error)
        }
}
