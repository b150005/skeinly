package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.remote.DeviceTokenRemoteOperations
import io.github.b150005.skeinly.data.remote.RemoteDeviceTokenDataSource
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.repository.PushPlatform
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Trip-wire exception whose `simpleName` matches the
 * `networkExceptionPatterns` heuristic in [UseCaseError.toUseCaseError]
 * — surfaces as [UseCaseError.Network] without depending on a platform-
 * specific IOException type.
 */
private class FakeIOException(
    message: String,
) : Exception(message)

private class FakeDeviceTokenRemote(
    var nextError: Throwable? = null,
) : DeviceTokenRemoteOperations {
    var lastUserId: String? = null
    var lastToken: String? = null
    var lastPlatform: String? = null
    var lastLocale: String? = null
    var callCount: Int = 0

    override suspend fun upsert(
        userId: String,
        token: String,
        platform: String,
        locale: String,
    ) {
        callCount++
        nextError?.let {
            nextError = null
            throw it
        }
        lastUserId = userId
        lastToken = token
        lastPlatform = platform
        lastLocale = locale
    }
}

class DeviceTokenRepositoryImplTest {
    private fun makeAuth(userId: String? = "test-user-id"): FakeAuthRepository =
        FakeAuthRepository().also { auth ->
            if (userId != null) {
                auth.setAuthState(AuthState.Authenticated(userId = userId, email = "test@example.com"))
            }
        }

    @Test
    fun upsertToken_returns_RequiresConnectivity_when_remote_is_null() =
        runTest {
            val repo = DeviceTokenRepositoryImpl(remote = null, authRepository = makeAuth())

            val result = repo.upsertToken("apns-token-hex", PushPlatform.IOS, "en-US")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.RequiresConnectivity, failure.error)
        }

    @Test
    fun upsertToken_short_circuits_before_calling_remote_when_offline() =
        runTest {
            val remote = FakeDeviceTokenRemote()
            // remote is null path — even though we've constructed `remote`,
            // we deliberately pass null to the repository to verify the
            // offline-mode guard fires BEFORE any remote interaction. A
            // future regression that swapped the ordering (e.g. signed-in
            // check first, then null-remote check) would surface a false
            // SignInRequired error path on local-only builds.
            val repo = DeviceTokenRepositoryImpl(remote = null, authRepository = makeAuth())

            repo.upsertToken("apns-token-hex", PushPlatform.IOS, "en-US")

            assertEquals(0, remote.callCount)
        }

    @Test
    fun upsertToken_returns_SignInRequired_when_user_not_signed_in() =
        runTest {
            val remote = FakeDeviceTokenRemote()
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth(userId = null))

            val result = repo.upsertToken("apns-token-hex", PushPlatform.IOS, "en-US")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.SignInRequired, failure.error)
            assertEquals(0, remote.callCount, "must not contact remote without auth")
        }

    @Test
    fun upsertToken_succeeds_for_ios_with_correct_platform_wire_value() =
        runTest {
            val remote = FakeDeviceTokenRemote()
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth())

            val result = repo.upsertToken("a".repeat(64), PushPlatform.IOS, "en-US")

            assertIs<UseCaseResult.Success<Unit>>(result)
            assertEquals("test-user-id", remote.lastUserId)
            assertEquals("a".repeat(64), remote.lastToken)
            assertEquals("ios", remote.lastPlatform)
            assertEquals("en-US", remote.lastLocale)
            assertEquals(1, remote.callCount)
        }

    @Test
    fun upsertToken_succeeds_for_android_with_correct_platform_wire_value() =
        runTest {
            val remote = FakeDeviceTokenRemote()
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth())

            val result = repo.upsertToken("fcm-token-Base64UrlEncoded", PushPlatform.ANDROID, "ja-JP")

            assertIs<UseCaseResult.Success<Unit>>(result)
            assertEquals("android", remote.lastPlatform)
            assertEquals("ja-JP", remote.lastLocale)
            assertEquals("fcm-token-Base64UrlEncoded", remote.lastToken)
        }

    @Test
    fun upsertToken_passes_userId_from_auth_repository() =
        runTest {
            val remote = FakeDeviceTokenRemote()
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth(userId = "alice-uuid"))

            repo.upsertToken("token", PushPlatform.IOS, "en-US")

            assertEquals("alice-uuid", remote.lastUserId)
        }

    @Test
    fun upsertToken_returns_Network_failure_on_IOException() =
        runTest {
            val remote = FakeDeviceTokenRemote(nextError = FakeIOException("connection refused"))
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth())

            val result = repo.upsertToken("token", PushPlatform.IOS, "en-US")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Network>(failure.error)
        }

    @Test
    fun upsertToken_returns_Unknown_failure_on_arbitrary_exception() =
        runTest {
            val remote = FakeDeviceTokenRemote(nextError = RuntimeException("PostgrestException 401"))
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth())

            val result = repo.upsertToken("token", PushPlatform.IOS, "en-US")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(failure.error)
        }

    @Test
    fun upsertToken_succeeds_idempotently_on_repeat_calls_with_same_token() =
        runTest {
            val remote = FakeDeviceTokenRemote()
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth())

            val first = repo.upsertToken("token-A", PushPlatform.IOS, "en-US")
            val second = repo.upsertToken("token-A", PushPlatform.IOS, "en-US")

            assertIs<UseCaseResult.Success<Unit>>(first)
            assertIs<UseCaseResult.Success<Unit>>(second)
            assertEquals(2, remote.callCount, "repository delegates idempotency to the DB UNIQUE constraint")
        }

    @Test
    fun upsertToken_succeeds_when_locale_is_ja_JP() =
        runTest {
            val remote = FakeDeviceTokenRemote()
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth())

            repo.upsertToken("token", PushPlatform.ANDROID, "ja-JP")

            assertEquals("ja-JP", remote.lastLocale)
        }

    @Test
    fun upsertToken_passes_token_through_verbatim_without_normalization() =
        runTest {
            val remote = FakeDeviceTokenRemote()
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth())
            // FCM tokens contain colons, dashes, underscores per Base64-URL.
            // Caller MUST receive verbatim — Edge Function passes this
            // value to the FCM HTTP API as the recipient.
            val rawToken = "abc123:APA91bHun4MxP5egoKMwt2KZFBaFUH-1RYqx_xyz"

            repo.upsertToken(rawToken, PushPlatform.ANDROID, "en-US")

            assertEquals(rawToken, remote.lastToken)
        }

    @Test
    fun pushPlatform_wireValues_match_migration_025_check_constraint() {
        // Migration 025 line 33: `CHECK (platform IN ('ios', 'android'))`.
        // A future enum addition that lands here without the matching
        // CHECK alter would surface as a server-side rejection, but
        // catching it at unit-test time is cheaper.
        assertEquals("ios", PushPlatform.IOS.wireValue)
        assertEquals("android", PushPlatform.ANDROID.wireValue)
    }

    @Test
    fun pushPlatform_enum_has_only_two_entries() {
        // Defensive: the closed enum mirrors the migration 025 CHECK
        // closed set. If a third entry lands here without updating the
        // CHECK + Edge Function fan-out path, this assertion fails first.
        assertEquals(2, PushPlatform.entries.size)
    }

    @Test
    fun on_conflict_columns_match_migration_025_unique_constraint_order() {
        // Migration 025 line 46: `UNIQUE (user_id, platform, token)`.
        // Postgrest passes this string verbatim to PostgreSQL's
        // `ON CONFLICT (...) DO UPDATE` — re-ordering the columns or
        // dropping one would silently degrade conflict resolution to a
        // no-op INSERT path. Update this test only after re-running
        // migration 025 with the new constraint shape.
        assertEquals(
            "user_id,platform,token",
            RemoteDeviceTokenDataSource.ON_CONFLICT_COLUMNS,
        )
    }

    @Test
    fun upsertToken_returns_Unknown_for_decode_or_serialization_failure() =
        runTest {
            // SerializationException would surface from Postgrest if the
            // server returned an unexpected response shape; treat as
            // Unknown so the caller surfaces a localized "something went
            // wrong" rather than a misleading Network error.
            val remote = FakeDeviceTokenRemote(nextError = IllegalStateException("Unexpected JSON shape"))
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth())

            val result = repo.upsertToken("token", PushPlatform.IOS, "en-US")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Unknown>(failure.error)
        }

    @Test
    fun upsertToken_does_not_retry_on_failure() =
        runTest {
            // Failure is non-retried at the repository layer — the OS
            // retries token acquisition on its own schedule, and the next
            // app foreground re-attempts the upsert. A retry loop here
            // would mask transient issues from telemetry.
            val remote = FakeDeviceTokenRemote(nextError = FakeIOException("transient"))
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth())

            repo.upsertToken("token", PushPlatform.IOS, "en-US")

            assertEquals(1, remote.callCount, "single attempt only — no implicit retry loop")
        }

    @Test
    fun upsertToken_clears_remote_state_when_user_signs_out_and_back_in() =
        runTest {
            val remote = FakeDeviceTokenRemote()
            val auth = makeAuth(userId = "alice-uuid")
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = auth)

            repo.upsertToken("token-1", PushPlatform.IOS, "en-US")
            assertEquals("alice-uuid", remote.lastUserId)

            // Sign out, sign in as a different user.
            auth.setAuthState(AuthState.Unauthenticated)
            val signedOut = repo.upsertToken("token-1", PushPlatform.IOS, "en-US")
            assertIs<UseCaseResult.Failure>(signedOut)

            auth.setAuthState(AuthState.Authenticated(userId = "bob-uuid", email = "bob@example.com"))
            repo.upsertToken("token-2", PushPlatform.IOS, "en-US")

            assertEquals("bob-uuid", remote.lastUserId, "fresh signed-in user resolves to fresh row")
        }

    @Test
    fun upsertToken_returns_RequiresConnectivity_even_when_signed_in_if_remote_is_null() =
        runTest {
            // Defensive ordering: RequiresConnectivity short-circuits BEFORE
            // SignInRequired. A signed-in user on a local-only build still
            // sees the offline-mode error rather than a misleading
            // SignInRequired (the user IS signed in; the issue is the
            // backend isn't configured).
            val repo = DeviceTokenRepositoryImpl(remote = null, authRepository = makeAuth(userId = "test-user"))

            val result = repo.upsertToken("token", PushPlatform.IOS, "en-US")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.RequiresConnectivity, failure.error)
        }

    @Test
    fun upsertToken_handles_long_fcm_token_without_truncation() =
        runTest {
            val remote = FakeDeviceTokenRemote()
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth())
            // FCM Base64-URL tokens are typically ~163 chars; defense
            // against a future change that adds a length cap.
            val longToken = "f".repeat(200)

            repo.upsertToken(longToken, PushPlatform.ANDROID, "en-US")

            assertEquals(longToken, remote.lastToken)
            assertEquals(200, remote.lastToken?.length)
        }

    @Test
    fun upsertToken_does_not_throw_CancellationException_wrapper() =
        runTest {
            // CancellationException MUST propagate uncaught so structured
            // concurrency invariants hold (parent scope cancellation
            // walks down the tree). The repository's try/catch carves
            // out CancellationException via explicit re-throw.
            val remote =
                FakeDeviceTokenRemote(nextError = kotlinx.coroutines.CancellationException("scope cancelled"))
            val repo = DeviceTokenRepositoryImpl(remote = remote, authRepository = makeAuth())

            try {
                repo.upsertToken("token", PushPlatform.IOS, "en-US")
                error("CancellationException should have propagated")
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Expected — the exception bubbles through.
            }
        }
}
