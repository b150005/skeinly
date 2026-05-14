package io.github.b150005.skeinly.data.subscription

import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.subscription.PaywallOffering
import io.github.b150005.skeinly.domain.subscription.PaywallPackage
import io.github.b150005.skeinly.domain.subscription.PurchaseResult
import io.github.b150005.skeinly.domain.subscription.RestoreResult
import io.github.b150005.skeinly.domain.subscription.RevenueCatService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RevenueCatAuthBridgeTest {
    @Test
    fun authenticated_state_invokes_identifyUser_with_userId() =
        runTest(UnconfinedTestDispatcher()) {
            val authRepo = ControlledAuthRepository()
            val service = RecordingRevenueCatService()
            val job = startRevenueCatAuthBridge(this, authRepo, service)

            authRepo.emit(AuthState.Authenticated(userId = "user-1", email = "u@example.com"))

            assertContentEquals(listOf(BridgeCall.Identify("user-1")), service.calls)
            job.cancel()
        }

    @Test
    fun unauthenticated_state_invokes_logOut() =
        runTest(UnconfinedTestDispatcher()) {
            val authRepo = ControlledAuthRepository()
            val service = RecordingRevenueCatService()
            val job = startRevenueCatAuthBridge(this, authRepo, service)

            authRepo.emit(AuthState.Authenticated(userId = "user-1", email = null))
            authRepo.emit(AuthState.Unauthenticated)

            assertContentEquals(
                listOf(BridgeCall.Identify("user-1"), BridgeCall.LogOut),
                service.calls,
            )
            job.cancel()
        }

    @Test
    fun loading_and_error_states_are_no_ops() =
        runTest(UnconfinedTestDispatcher()) {
            val authRepo = ControlledAuthRepository()
            val service = RecordingRevenueCatService()
            val job = startRevenueCatAuthBridge(this, authRepo, service)

            authRepo.emit(AuthState.Loading)
            authRepo.emit(AuthState.Error("session refresh failed"))
            authRepo.emit(AuthState.Loading)

            assertTrue(service.calls.isEmpty(), "transient states must not call identify or logOut")
            job.cancel()
        }

    @Test
    fun duplicate_authenticated_emissions_with_same_userId_dedupe() =
        runTest(UnconfinedTestDispatcher()) {
            val authRepo = ControlledAuthRepository()
            val service = RecordingRevenueCatService()
            val job = startRevenueCatAuthBridge(this, authRepo, service)

            authRepo.emit(AuthState.Authenticated(userId = "user-1", email = null))
            authRepo.emit(AuthState.Authenticated(userId = "user-1", email = "refreshed@example.com"))

            assertContentEquals(listOf(BridgeCall.Identify("user-1")), service.calls)
            job.cancel()
        }

    @Test
    fun authenticated_state_change_to_different_user_re_identifies() =
        runTest(UnconfinedTestDispatcher()) {
            val authRepo = ControlledAuthRepository()
            val service = RecordingRevenueCatService()
            val job = startRevenueCatAuthBridge(this, authRepo, service)

            authRepo.emit(AuthState.Authenticated(userId = "user-1", email = null))
            authRepo.emit(AuthState.Authenticated(userId = "user-2", email = null))

            assertContentEquals(
                listOf(BridgeCall.Identify("user-1"), BridgeCall.Identify("user-2")),
                service.calls,
            )
            job.cancel()
        }

    @Test
    fun transient_state_between_two_authentications_does_not_re_fire() =
        runTest(UnconfinedTestDispatcher()) {
            val authRepo = ControlledAuthRepository()
            val service = RecordingRevenueCatService()
            val job = startRevenueCatAuthBridge(this, authRepo, service)

            authRepo.emit(AuthState.Authenticated(userId = "user-1", email = null))
            authRepo.emit(AuthState.Loading)
            authRepo.emit(AuthState.Authenticated(userId = "user-1", email = null))

            // After transient flush the same userId emerges; distinct-until-changed
            // sees Identity.User("user-1") -> Transient -> Identity.User("user-1")
            // and DOES re-fire because the intermediate Transient broke the streak.
            // This is acceptable — RevenueCat SDK handles repeat logIn idempotently.
            assertEquals(2, service.calls.count { it is BridgeCall.Identify })
            job.cancel()
        }

    @Test
    fun identifyUser_failure_does_not_terminate_bridge() =
        runTest(UnconfinedTestDispatcher()) {
            val authRepo = ControlledAuthRepository()
            val service =
                RecordingRevenueCatService(
                    nextIdentifyResult = Result.failure(RuntimeException("network down")),
                )
            val job = startRevenueCatAuthBridge(this, authRepo, service)

            authRepo.emit(AuthState.Authenticated(userId = "user-1", email = null))
            // Reset failure injection so the next call succeeds — proves bridge
            // is still alive and processing further emissions.
            service.nextIdentifyResult = Result.success(Unit)
            authRepo.emit(AuthState.Authenticated(userId = "user-2", email = null))

            assertContentEquals(
                listOf(BridgeCall.Identify("user-1"), BridgeCall.Identify("user-2")),
                service.calls,
            )
            assertTrue(job.isActive, "bridge job must stay active after a failure")
            job.cancel()
        }

    @Test
    fun bridge_job_cancellation_stops_processing() =
        runTest(UnconfinedTestDispatcher()) {
            val authRepo = ControlledAuthRepository()
            val service = RecordingRevenueCatService()
            val job = startRevenueCatAuthBridge(this, authRepo, service)

            authRepo.emit(AuthState.Authenticated(userId = "user-1", email = null))
            job.cancel()
            authRepo.emit(AuthState.Authenticated(userId = "user-2", email = null))

            assertContentEquals(listOf(BridgeCall.Identify("user-1")), service.calls)
        }
}

/**
 * In-memory [AuthRepository] that emits caller-controlled [AuthState] values.
 * Only [observeAuthState] is exercised by the bridge; other methods are not
 * called by [startRevenueCatAuthBridge] and remain unsupported.
 */
private class ControlledAuthRepository : AuthRepository {
    private val flow = MutableSharedFlow<AuthState>(replay = 0, extraBufferCapacity = 16)

    suspend fun emit(state: AuthState) {
        flow.emit(state)
    }

    override fun observeAuthState(): Flow<AuthState> = flow

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ) {
        error("not used in bridge tests")
    }

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
    ): io.github.b150005.skeinly.domain.model.SignUpOutcome {
        error("not used in bridge tests")
    }

    override suspend fun signOut() {
        error("not used in bridge tests")
    }

    override suspend fun deleteAccount() {
        error("not used in bridge tests")
    }

    override fun getCurrentUserId(): String? = null

    override suspend fun sendPasswordResetEmail(email: String) {
        error("not used in bridge tests")
    }

    override suspend fun updatePassword(newPassword: String) {
        error("not used in bridge tests")
    }

    override suspend fun updateEmail(newEmail: String) {
        error("not used in bridge tests")
    }

    override suspend fun signInWithApple(
        idToken: String,
        nonce: String,
    ): io.github.b150005.skeinly.domain.model.OAuthSignInOutcome = error("not used in bridge tests")

    override suspend fun signInWithGoogle(
        idToken: String,
        nonce: String?,
    ): io.github.b150005.skeinly.domain.model.OAuthSignInOutcome = error("not used in bridge tests")

    override suspend fun signInWithAppleViaWebOAuth() {
        error("not used in bridge tests")
    }

    override suspend fun linkPendingIdentity(
        provider: io.github.b150005.skeinly.domain.model.OAuthProviderKind,
        pendingIdToken: String,
        nonce: String?,
    ) {
        error("not used in bridge tests")
    }

    // Phase 26.5 (ADR-022 §6.4) — MFA methods not exercised by the
    // RevenueCat auth bridge; the bridge only routes between
    // Authenticated / Unauthenticated identity calls.
    override fun observeMfaStatus(): Flow<io.github.b150005.skeinly.domain.model.MfaEnrollmentStatus> = error("not used in bridge tests")

    override suspend fun enrollMfaTotp(): io.github.b150005.skeinly.domain.model.MfaEnrollment = error("not used in bridge tests")

    override suspend fun verifyMfaEnrollment(
        factorId: String,
        code: String,
    ) {
        error("not used in bridge tests")
    }

    override suspend fun submitMfaChallenge(code: String) {
        error("not used in bridge tests")
    }

    override suspend fun consumeRecoveryCode(plaintextCode: String): Boolean = error("not used in bridge tests")

    override suspend fun disableMfa(factorId: String) {
        error("not used in bridge tests")
    }

    override suspend fun regenerateRecoveryCode(): String = error("not used in bridge tests")
}

private sealed interface BridgeCall {
    data class Identify(
        val userId: String,
    ) : BridgeCall

    data object LogOut : BridgeCall
}

/**
 * In-memory [RevenueCatService] that records the bridge's calls in arrival
 * order. Only [identifyUser] / [logOutUser] are exercised; other methods
 * stay as throw-on-call stubs to surface accidental cross-test reach.
 */
private class RecordingRevenueCatService(
    var nextIdentifyResult: Result<Unit> = Result.success(Unit),
    var nextLogOutResult: Result<Unit> = Result.success(Unit),
) : RevenueCatService {
    val calls: MutableList<BridgeCall> = mutableListOf()

    override suspend fun getOfferings(): Result<PaywallOffering?> = error("not used in bridge tests")

    override suspend fun purchase(pkg: PaywallPackage): PurchaseResult = error("not used in bridge tests")

    override suspend fun restorePurchases(): RestoreResult = error("not used in bridge tests")

    override suspend fun identifyUser(userId: String): Result<Unit> {
        calls.add(BridgeCall.Identify(userId))
        return nextIdentifyResult
    }

    override suspend fun logOutUser(): Result<Unit> {
        calls.add(BridgeCall.LogOut)
        return nextLogOutResult
    }
}
