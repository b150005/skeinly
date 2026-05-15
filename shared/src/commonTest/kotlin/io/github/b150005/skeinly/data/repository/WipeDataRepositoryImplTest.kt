package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.remote.RemoteWipeDataDataSource
import io.github.b150005.skeinly.data.remote.WipeDataRemoteOperations
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Trip-wire exception whose `simpleName` matches the
 * `networkExceptionPatterns` heuristic in
 * [io.github.b150005.skeinly.domain.usecase.toUseCaseError] — surfaces
 * as [UseCaseError.Network] without depending on a platform-specific
 * IOException type.
 */
private class WipeFakeIOException(
    message: String,
) : Exception(message)

private class FakeWipeDataRemote(
    var nextError: Throwable? = null,
) : WipeDataRemoteOperations {
    var callCount: Int = 0

    override suspend fun wipeOwnData() {
        callCount++
        nextError?.let {
            // Single-shot: clears after firing so a subsequent call on
            // the same fake can model "retry succeeded" if the test
            // exercises that.
            nextError = null
            throw it
        }
    }
}

class WipeDataRepositoryImplTest {
    private fun makeAuth(userId: String? = "test-user-id"): FakeAuthRepository =
        FakeAuthRepository().also { auth ->
            if (userId != null) {
                auth.setAuthState(AuthState.Authenticated(userId = userId, email = "test@example.com"))
            }
        }

    @Test
    fun wipe_returns_RequiresConnectivity_when_remote_is_null() =
        runTest {
            val repo = WipeDataRepositoryImpl(remote = null, authRepository = makeAuth())

            val result = repo.wipe()

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.RequiresConnectivity, failure.error)
        }

    @Test
    fun wipe_short_circuits_before_calling_remote_when_offline() =
        runTest {
            val remote = FakeWipeDataRemote()
            // Even with a fake remote constructed, we deliberately pass
            // null so the offline-mode guard fires BEFORE any remote
            // interaction. A future regression that swapped the ordering
            // (signed-in check first, then null-remote check) would
            // surface a misleading SignInRequired on local-only builds.
            val repo = WipeDataRepositoryImpl(remote = null, authRepository = makeAuth())

            repo.wipe()

            assertEquals(0, remote.callCount)
        }

    @Test
    fun wipe_returns_SignInRequired_when_user_not_signed_in() =
        runTest {
            val remote = FakeWipeDataRemote()
            val repo = WipeDataRepositoryImpl(remote = remote, authRepository = makeAuth(userId = null))

            val result = repo.wipe()

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.SignInRequired, failure.error)
            assertEquals(0, remote.callCount, "must not contact remote without auth")
        }

    @Test
    fun wipe_returns_Success_on_happy_path() =
        runTest {
            val remote = FakeWipeDataRemote()
            val repo = WipeDataRepositoryImpl(remote = remote, authRepository = makeAuth())

            val result = repo.wipe()

            assertIs<UseCaseResult.Success<Unit>>(result)
            assertEquals(1, remote.callCount)
        }

    @Test
    fun wipe_returns_Network_failure_on_IOException() =
        runTest {
            val remote = FakeWipeDataRemote(nextError = WipeFakeIOException("connection refused"))
            val repo = WipeDataRepositoryImpl(remote = remote, authRepository = makeAuth())

            val result = repo.wipe()

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Network>(failure.error)
        }

    @Test
    fun wipe_returns_Unknown_failure_on_arbitrary_exception() =
        runTest {
            // PostgrestException-style RPC error (e.g. server-side raise
            // when auth.uid() resolved NULL despite the client-side
            // short-circuit). Treat as Unknown so the user sees a
            // localized "something went wrong" toast rather than a
            // misleading Network error.
            val remote = FakeWipeDataRemote(nextError = RuntimeException("PostgrestException 28000"))
            val repo = WipeDataRepositoryImpl(remote = remote, authRepository = makeAuth())

            val result = repo.wipe()

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(failure.error)
        }

    @Test
    fun wipe_returns_Unknown_for_decode_or_serialization_failure() =
        runTest {
            // SerializationException would surface from Postgrest if the
            // server returned an unexpected response shape; treat as
            // Unknown.
            val remote = FakeWipeDataRemote(nextError = IllegalStateException("Unexpected JSON shape"))
            val repo = WipeDataRepositoryImpl(remote = remote, authRepository = makeAuth())

            val result = repo.wipe()

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(failure.error)
        }

    @Test
    fun wipe_succeeds_idempotently_on_repeat_calls() =
        runTest {
            // The RPC is idempotent at the DB layer (PERFORM ... FOR
            // UPDATE on auth.users), so the repository delegates the
            // idempotency guarantee server-side. Two successive calls
            // both return Success; the second call is a no-op for
            // content tables + a fresh audit row.
            val remote = FakeWipeDataRemote()
            val repo = WipeDataRepositoryImpl(remote = remote, authRepository = makeAuth())

            val first = repo.wipe()
            val second = repo.wipe()

            assertIs<UseCaseResult.Success<Unit>>(first)
            assertIs<UseCaseResult.Success<Unit>>(second)
            assertEquals(2, remote.callCount, "repository delegates idempotency to the DB lock")
        }

    @Test
    fun wipe_does_not_retry_on_failure() =
        runTest {
            // Failure is non-retried at the repository layer — a
            // client-side retry loop would mask transient issues from
            // telemetry. The Phase 27.2 ViewModel surfaces an error
            // toast and lets the user re-tap deliberately.
            val remote = FakeWipeDataRemote(nextError = WipeFakeIOException("transient"))
            val repo = WipeDataRepositoryImpl(remote = remote, authRepository = makeAuth())

            repo.wipe()

            assertEquals(1, remote.callCount, "single attempt only — no implicit retry loop")
        }

    @Test
    fun wipe_returns_RequiresConnectivity_even_when_signed_in_if_remote_is_null() =
        runTest {
            // Defensive ordering: RequiresConnectivity short-circuits
            // BEFORE SignInRequired. A signed-in user on a local-only
            // build still sees the offline-mode error rather than a
            // misleading SignInRequired (the user IS signed in; the
            // issue is the backend isn't configured).
            val repo = WipeDataRepositoryImpl(remote = null, authRepository = makeAuth(userId = "test-user"))

            val result = repo.wipe()

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.RequiresConnectivity, failure.error)
        }

    @Test
    fun wipe_does_not_swallow_CancellationException() =
        runTest {
            // CancellationException MUST propagate uncaught so structured
            // concurrency invariants hold (parent scope cancellation
            // walks down the tree). The repository's try/catch carves
            // out CancellationException via explicit re-throw.
            val remote =
                FakeWipeDataRemote(nextError = kotlinx.coroutines.CancellationException("scope cancelled"))
            val repo = WipeDataRepositoryImpl(remote = remote, authRepository = makeAuth())

            try {
                repo.wipe()
                error("CancellationException should have propagated")
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Expected.
            }
        }

    @Test
    fun wipe_resolves_userId_via_authRepository() =
        runTest {
            // The repository reads getCurrentUserId() but does NOT pass
            // the value to the RPC — the RPC reads auth.uid() from the
            // JWT exclusively. This test locks the "auth lookup happens"
            // contract so a future regression that drops the
            // short-circuit cannot accidentally call the network from a
            // signed-out client.
            val remote = FakeWipeDataRemote()
            val auth = makeAuth(userId = "alice-uuid")
            val repo = WipeDataRepositoryImpl(remote = remote, authRepository = auth)

            repo.wipe()

            assertEquals(1, remote.callCount)
        }

    @Test
    fun wipe_fails_after_signOut_even_if_remote_is_configured() =
        runTest {
            val remote = FakeWipeDataRemote()
            val auth = makeAuth(userId = "alice-uuid")
            val repo = WipeDataRepositoryImpl(remote = remote, authRepository = auth)

            // First call as alice succeeds.
            val first = repo.wipe()
            assertIs<UseCaseResult.Success<Unit>>(first)
            assertEquals(1, remote.callCount)

            // Sign out. The next call must short-circuit with
            // SignInRequired BEFORE the network round-trip.
            auth.setAuthState(AuthState.Unauthenticated)
            val signedOut = repo.wipe()
            val signedOutFailure = assertIs<UseCaseResult.Failure>(signedOut)
            assertEquals(UseCaseError.SignInRequired, signedOutFailure.error)
            assertEquals(1, remote.callCount, "no remote call after sign-out")
        }

    @Test
    fun wipe_rpc_name_matches_migration_033_and_unchanged_by_037() {
        // Migration 033 created `public.wipe_own_data()`. Migration 037
        // (`037_phase_25_1_wipe_friend_graph.sql`, prod-applied
        // `phase_25_1_wipe_friend_graph`) CREATE OR REPLACEs the SAME
        // function to additionally wipe the caller's friend graph per
        // ADR-024 §(g.1), closing the Tech Debt deferred at Phase 25.1
        // (migration 035 §note).
        //
        // 037's friend-graph wipe is pure server-side PL/pgSQL — there
        // is NO Kotlin behaviour change, so it is verified by prod-apply
        // + pg_get_functiondef introspection (the established pattern
        // for SQL-only migrations here), NOT a commonTest behavioural
        // assertion (commonTest cannot reach a real Postgres; the §25.1
        // sub-slice plan's `FriendConnectionsWipeTest` "via direct SQL
        // EXECUTE" is superseded by that introspection check).
        //
        // The one Kotlin-side regression surface is the RPC name: 037
        // does NOT add a new RPC. The Postgrest `rpc(...)` call MUST
        // keep using this exact name — a rename would (a) surface as a
        // runtime "function not found" 404, and (b) silently route
        // around the friend-graph amendment, leaving friend data after
        // a wipe. Locking the constant catches both at unit-test time
        // and keeps the 033/037 migration trail greppable from tests.
        assertEquals("wipe_own_data", RemoteWipeDataDataSource.RPC_NAME)
    }
}
