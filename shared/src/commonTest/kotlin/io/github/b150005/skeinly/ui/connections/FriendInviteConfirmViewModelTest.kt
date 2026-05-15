package io.github.b150005.skeinly.ui.connections

import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/**
 * Phase 25.4 (ADR-024 §Phase 25.4) — locks the
 * [FriendInviteConfirmViewModel] token/code state machine. Lambda-seam
 * stubs for the redeem RPCs + inviter-name resolver keep these tests
 * free of supabase-kt + UserRepository (mirrors
 * [ConnectionsViewModelTest] / WipeDataViewModelTest precedent).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FriendInviteConfirmViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val inviterId = "00000000-0000-0000-0000-0000000000aa"

    private fun build(
        token: String? = null,
        redeemToken: suspend (String) -> UseCaseResult<String> = { UseCaseResult.Success(inviterId) },
        redeemCode: suspend (String) -> UseCaseResult<String> = { UseCaseResult.Success(inviterId) },
        resolveDisplayName: suspend (String) -> String? = { "Alice" },
    ) = FriendInviteConfirmViewModel(
        token = token,
        redeemToken = redeemToken,
        redeemCode = redeemCode,
        resolveDisplayName = resolveDisplayName,
    )

    @Test
    fun `null token init is Code mode and does not auto-redeem`() =
        runTest {
            var redeemCalls = 0
            val vm =
                build(
                    token = null,
                    redeemCode = {
                        redeemCalls++
                        UseCaseResult.Success(inviterId)
                    },
                )
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(FriendInviteConfirmMode.Code, state.mode)
            assertNull(state.success)
            assertFalse(state.isRedeeming)
            assertEquals(0, redeemCalls, "Code mode must NOT auto-redeem on init")
        }

    @Test
    fun `non-null token init is Token mode and auto-redeems`() =
        runTest {
            val captured = mutableListOf<String>()
            val vm =
                build(
                    token = "tok-xyz",
                    redeemToken = { t ->
                        captured += t
                        UseCaseResult.Success(inviterId)
                    },
                )
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(FriendInviteConfirmMode.Token, state.mode)
            assertEquals(listOf("tok-xyz"), captured)
            val success = assertNotNull(state.success)
            assertEquals(inviterId, success.inviterId)
            assertEquals("Alice", success.inviterDisplayName)
        }

    @Test
    fun `token-mode redeem failure surfaces error and no success`() =
        runTest {
            val vm =
                build(
                    token = "tok-bad",
                    redeemToken = {
                        UseCaseResult.Failure(UseCaseError.Unknown(RuntimeException("expired")))
                    },
                )
            advanceUntilIdle()
            val state = vm.state.value
            assertNotNull(state.error)
            assertNull(state.success)
            assertFalse(state.isRedeeming)
        }

    @Test
    fun `code-mode submitEnabled requires non-blank code and not redeeming`() =
        runTest {
            val vm = build(token = null)
            advanceUntilIdle()
            assertFalse(vm.state.value.submitEnabled, "blank code disables submit")
            vm.onEvent(FriendInviteConfirmEvent.UpdateCode("  "))
            assertFalse(vm.state.value.submitEnabled, "whitespace-only disables submit")
            vm.onEvent(FriendInviteConfirmEvent.UpdateCode("ABCD2345"))
            assertTrue(vm.state.value.submitEnabled)
        }

    @Test
    fun `code-mode Redeem trims and dispatches to redeemCode`() =
        runTest {
            val captured = mutableListOf<String>()
            val vm =
                build(
                    token = null,
                    redeemCode = { c ->
                        captured += c
                        UseCaseResult.Success(inviterId)
                    },
                )
            advanceUntilIdle()
            vm.onEvent(FriendInviteConfirmEvent.UpdateCode("  ABCD2345  "))
            vm.onEvent(FriendInviteConfirmEvent.Redeem)
            advanceUntilIdle()
            assertEquals(listOf("ABCD2345"), captured, "code trimmed before redeem")
            assertNotNull(vm.state.value.success)
        }

    @Test
    fun `code-mode Redeem with blank code is silently swallowed`() =
        runTest {
            var redeemCalls = 0
            val vm =
                build(
                    token = null,
                    redeemCode = {
                        redeemCalls++
                        UseCaseResult.Success(inviterId)
                    },
                )
            advanceUntilIdle()
            // No UpdateCode → codeInput is blank → submitEnabled false.
            vm.onEvent(FriendInviteConfirmEvent.Redeem)
            advanceUntilIdle()
            assertEquals(0, redeemCalls)
        }

    @Test
    fun `re-entry guard prevents concurrent redeem dispatches`() =
        runTest {
            var calls = 0
            val gate = CompletableDeferred<Unit>()
            val vm =
                build(
                    token = null,
                    redeemCode = {
                        calls++
                        gate.await()
                        UseCaseResult.Success(inviterId)
                    },
                )
            advanceUntilIdle()
            vm.onEvent(FriendInviteConfirmEvent.UpdateCode("ABCD2345"))
            vm.onEvent(FriendInviteConfirmEvent.Redeem)
            assertTrue(vm.state.value.isRedeeming)
            // Second tap during the in-flight window is swallowed.
            vm.onEvent(FriendInviteConfirmEvent.Redeem)
            assertEquals(1, calls)
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(vm.state.value.isRedeeming)
        }

    @Test
    fun `inviter-name resolution failure does not downgrade a successful redeem`() =
        runTest {
            val vm =
                build(
                    token = "tok-ok",
                    redeemToken = { UseCaseResult.Success(inviterId) },
                    resolveDisplayName = {
                        throw RuntimeException("profile fetch timeout")
                    },
                )
            advanceUntilIdle()
            val state = vm.state.value
            // Friendship is written server-side; a failed name lookup
            // must NOT turn success into an error.
            val success = assertNotNull(state.success)
            assertNull(success.inviterDisplayName)
            assertNull(state.error)
        }

    @Test
    fun `inviter-name null result is preserved as null so screen falls back`() =
        runTest {
            val vm =
                build(
                    token = "tok-ok",
                    resolveDisplayName = { null },
                )
            advanceUntilIdle()
            val success = assertNotNull(vm.state.value.success)
            assertNull(success.inviterDisplayName)
        }

    @Test
    fun `UpdateCode clears a prior error`() =
        runTest {
            val vm =
                build(
                    token = null,
                    redeemCode = {
                        UseCaseResult.Failure(UseCaseError.Network(RuntimeException("net")))
                    },
                )
            advanceUntilIdle()
            vm.onEvent(FriendInviteConfirmEvent.UpdateCode("ABCD2345"))
            vm.onEvent(FriendInviteConfirmEvent.Redeem)
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)
            vm.onEvent(FriendInviteConfirmEvent.UpdateCode("ABCD2346"))
            assertNull(vm.state.value.error)
        }

    @Test
    fun `ClearError drops the error`() =
        runTest {
            val vm =
                build(
                    token = "tok-bad",
                    redeemToken = {
                        UseCaseResult.Failure(UseCaseError.Unknown(RuntimeException("x")))
                    },
                )
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)
            vm.onEvent(FriendInviteConfirmEvent.ClearError)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `token-mode manual Redeem retries after a transient failure`() =
        runTest {
            var attempt = 0
            val vm =
                build(
                    token = "tok-retry",
                    redeemToken = {
                        attempt++
                        if (attempt == 1) {
                            UseCaseResult.Failure(UseCaseError.Network(RuntimeException("flaky")))
                        } else {
                            UseCaseResult.Success(inviterId)
                        }
                    },
                )
            advanceUntilIdle()
            // init's auto-redeem failed.
            assertNotNull(vm.state.value.error)
            assertNull(vm.state.value.success)
            // Manual retry in Token mode re-fires redeemToken.
            vm.onEvent(FriendInviteConfirmEvent.Redeem)
            advanceUntilIdle()
            assertEquals(2, attempt)
            assertNotNull(vm.state.value.success)
            assertNull(vm.state.value.error)
        }
}
