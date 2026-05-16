package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.remote.DataExportException
import io.github.b150005.skeinly.data.remote.DataExportRemoteOperations
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.DataExportBundle
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pre-Phase-40 A20 Option B — locks the offline-first contract +
 * Edge-Function-code → `UseCaseError` mapping of
 * [DataExportRepositoryImpl]. Mirrors `WipeDataRepositoryImplTest`.
 */
private class DataExportFakeIOException(
    message: String,
) : Exception(message)

private class FakeDataExportRemote(
    var nextError: Throwable? = null,
    private val bundle: DataExportBundle =
        DataExportBundle(
            bundleJson = "{\"schema_version\":1}",
            summary = mapOf("patterns" to 2, "_avatars" to 0),
            totalRows = 2,
        ),
) : DataExportRemoteOperations {
    var callCount: Int = 0

    override suspend fun exportOwnData(): DataExportBundle {
        callCount++
        nextError?.let {
            nextError = null
            throw it
        }
        return bundle
    }
}

class DataExportRepositoryImplTest {
    private fun makeAuth(userId: String? = "test-user-id"): FakeAuthRepository =
        FakeAuthRepository().also { auth ->
            if (userId != null) {
                auth.setAuthState(
                    AuthState.Authenticated(userId = userId, email = "test@example.com"),
                )
            }
        }

    @Test
    fun export_returns_RequiresConnectivity_when_remote_is_null() =
        runTest {
            val repo = DataExportRepositoryImpl(remote = null, authRepository = makeAuth())

            val failure = assertIs<UseCaseResult.Failure>(repo.export())
            assertEquals(UseCaseError.RequiresConnectivity, failure.error)
        }

    @Test
    fun export_offline_guard_precedes_auth_guard() =
        runTest {
            // remote == null AND signed-out. The two failure modes are
            // distinguishable: offline-first ⇒ RequiresConnectivity;
            // auth-first ⇒ SignInRequired. Asserting RequiresConnectivity
            // proves the ordering — a reordering regression would
            // surface the misleading SignInRequired on local-only builds.
            val repo =
                DataExportRepositoryImpl(
                    remote = null,
                    authRepository = makeAuth(userId = null),
                )

            val failure = assertIs<UseCaseResult.Failure>(repo.export())
            assertEquals(UseCaseError.RequiresConnectivity, failure.error)
        }

    @Test
    fun export_returns_SignInRequired_when_not_signed_in() =
        runTest {
            val remote = FakeDataExportRemote()
            val repo =
                DataExportRepositoryImpl(
                    remote = remote,
                    authRepository = makeAuth(userId = null),
                )

            val failure = assertIs<UseCaseResult.Failure>(repo.export())
            assertEquals(UseCaseError.SignInRequired, failure.error)
            assertEquals(0, remote.callCount, "must not contact remote without auth")
        }

    @Test
    fun export_returns_Success_with_bundle_on_happy_path() =
        runTest {
            val remote = FakeDataExportRemote()
            val repo = DataExportRepositoryImpl(remote = remote, authRepository = makeAuth())

            val success = assertIs<UseCaseResult.Success<DataExportBundle>>(repo.export())
            assertEquals(2, success.value.totalRows)
            assertEquals(2, success.value.summary["patterns"])
            assertEquals(1, remote.callCount)
        }

    @Test
    fun export_maps_RATE_LIMITED_code_to_RateLimited() =
        runTest {
            val remote =
                FakeDataExportRemote(
                    nextError = DataExportException("RATE_LIMITED", "try again in 12 minute(s)"),
                )
            val repo = DataExportRepositoryImpl(remote = remote, authRepository = makeAuth())

            val failure = assertIs<UseCaseResult.Failure>(repo.export())
            assertEquals(UseCaseError.RateLimited, failure.error)
        }

    @Test
    fun export_maps_UNAUTHORIZED_code_to_SignInRequired() =
        runTest {
            val remote =
                FakeDataExportRemote(
                    nextError = DataExportException("UNAUTHORIZED", "request missing valid Bearer JWT"),
                )
            val repo = DataExportRepositoryImpl(remote = remote, authRepository = makeAuth())

            val failure = assertIs<UseCaseResult.Failure>(repo.export())
            assertEquals(UseCaseError.SignInRequired, failure.error)
        }

    @Test
    fun export_maps_codeless_platform_failure_to_Unknown() =
        runTest {
            val remote =
                FakeDataExportRemote(
                    nextError = DataExportException(null, "export-my-data returned HTTP 503: ..."),
                )
            val repo = DataExportRepositoryImpl(remote = remote, authRepository = makeAuth())

            val failure = assertIs<UseCaseResult.Failure>(repo.export())
            assertIs<UseCaseError.Unknown>(failure.error)
        }

    @Test
    fun export_maps_EXPORT_FAILED_code_to_Unknown() =
        runTest {
            val remote =
                FakeDataExportRemote(
                    nextError = DataExportException("EXPORT_FAILED", "export composition failed"),
                )
            val repo = DataExportRepositoryImpl(remote = remote, authRepository = makeAuth())

            val failure = assertIs<UseCaseResult.Failure>(repo.export())
            assertIs<UseCaseError.Unknown>(failure.error)
        }

    @Test
    fun export_returns_Network_failure_on_IOException_pattern() =
        runTest {
            val remote =
                FakeDataExportRemote(
                    nextError = DataExportFakeIOException("connection refused"),
                )
            val repo = DataExportRepositoryImpl(remote = remote, authRepository = makeAuth())

            val failure = assertIs<UseCaseResult.Failure>(repo.export())
            assertIs<UseCaseError.Network>(failure.error)
        }
}
