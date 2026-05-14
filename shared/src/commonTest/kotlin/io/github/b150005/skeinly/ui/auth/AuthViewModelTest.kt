package io.github.b150005.skeinly.ui.auth

import app.cash.turbine.test
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.OAuthProviderKind
import io.github.b150005.skeinly.domain.model.OAuthSignInOutcome
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.domain.usecase.ObserveAuthStateUseCase
import io.github.b150005.skeinly.domain.usecase.SignInUseCase
import io.github.b150005.skeinly.domain.usecase.SignUpUseCase
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

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var authRepo: FakeAuthRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        authRepo = FakeAuthRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private var fakeGoogleAcquisition: io.github.b150005.skeinly.auth.OAuthIdTokenResult =
        io.github.b150005.skeinly.auth.OAuthIdTokenResult.Success(
            idToken = "fake.google.idtoken",
            nonce = null,
        )

    private fun createViewModel(): AuthViewModel =
        AuthViewModel(
            observeAuthState = ObserveAuthStateUseCase(authRepo),
            signIn = SignInUseCase(authRepo),
            signUp = SignUpUseCase(authRepo),
            signInWithApple = { idToken, nonce -> authRepo.signInWithApple(idToken, nonce) },
            signInWithGoogle = { idToken, nonce -> authRepo.signInWithGoogle(idToken, nonce) },
            acquireGoogleIdToken = { fakeGoogleAcquisition },
        )

    @Test
    fun `initial state is unauthenticated`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(AuthState.Unauthenticated, state.authState)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `update email updates state`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.UpdateEmail("test@example.com"))

            viewModel.state.test {
                val state = awaitItem()
                assertEquals("test@example.com", state.email)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `update password updates state`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.UpdatePassword("secret123"))

            viewModel.state.test {
                val state = awaitItem()
                assertEquals("secret123", state.password)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `toggle mode switches between sign in and sign up`() =
        runTest {
            val viewModel = createViewModel()

            assertFalse(viewModel.state.value.isSignUp)

            viewModel.onEvent(AuthEvent.ToggleMode)

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.isSignUp)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `successful sign in clears submitting state`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.UpdateEmail("test@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNull(state.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `failed sign in shows error`() =
        runTest {
            authRepo.signInError = RuntimeException("Invalid credentials")
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.UpdateEmail("test@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNotNull(state.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `clear error resets error`() =
        runTest {
            authRepo.signInError = RuntimeException("Invalid credentials")
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.UpdateEmail("test@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)

            viewModel.onEvent(AuthEvent.ClearError)

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `successful sign up clears submitting state`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.ToggleMode)
            viewModel.onEvent(AuthEvent.UpdateEmail("new@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNull(state.error)
                assertNull(state.emailConfirmationSentTo)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `sign up with email-confirmation required surfaces emailConfirmationSentTo and clears password`() =
        runTest {
            // Locks the post-bug behavior surfaced 2026-05-13: Supabase
            // Dashboard had Confirm email enabled in production, so
            // signUpWith succeeded at HTTP but no session was created.
            // The ViewModel must transition to a "check your email" state
            // and clear the password field — NOT stay silent waiting for
            // an Authenticated transition that will never arrive.
            authRepo.signUpEmailConfirmationRequired = true
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.ToggleMode)
            viewModel.onEvent(AuthEvent.UpdateEmail("new@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNull(state.error)
                assertEquals("new@example.com", state.emailConfirmationSentTo)
                assertEquals("", state.password)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dismiss email confirmation returns to sign-in mode with cleared password`() =
        runTest {
            // After EmailConfirmationRequired surfaces the "check your
            // email" view, the user taps "ログイン画面に戻る" to return.
            // The ViewModel must reset emailConfirmationSentTo, flip
            // isSignUp back to false, and ensure no stale password
            // remains in form state.
            authRepo.signUpEmailConfirmationRequired = true
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.ToggleMode)
            viewModel.onEvent(AuthEvent.UpdateEmail("new@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)
            viewModel.onEvent(AuthEvent.DismissEmailConfirmation)

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.emailConfirmationSentTo)
                assertFalse(state.isSignUp)
                assertEquals("", state.password)
                // Email is intentionally retained so the user can sign-in
                // immediately after confirming via the link — not blanked.
                assertEquals("new@example.com", state.email)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `sign up with already-registered email surfaces same UI as new email to preserve Supabase obscurity`() =
        runTest {
            // Models the 2026-05-13 operator confusion: user repeatedly
            // tried to sign up with their own pre-existing
            // b150005@outlook.jp email. Supabase returned HTTP 200 OK
            // with empty identities each time, by design to prevent
            // email-enumeration attacks (OWASP A07 — Identification
            // and Authentication Failures).
            //
            // Critical security property: the UI for AlreadyRegistered
            // must be INDISTINGUISHABLE from the UI for
            // EmailConfirmationRequired. Specifically:
            //   - no UserAlreadyExists error alert (would leak that
            //     the email exists)
            //   - no auto-flip to sign-in mode (would also leak)
            //   - same emailConfirmationSentTo population so the same
            //     "check your email" view renders
            //
            // The legitimate owner discovers their existing-account
            // status via the "you may already have an account, try
            // signing in" hint surfaced on the confirmation screen —
            // observable only to someone with access to their inbox
            // (who would see no confirmation email arrive), not to a
            // network observer or screen-recorder attacker.
            authRepo.signUpEmailAlreadyRegistered = true
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.ToggleMode)
            viewModel.onEvent(AuthEvent.UpdateEmail("existing@example.com"))
            viewModel.onEvent(AuthEvent.UpdatePassword("password"))
            viewModel.onEvent(AuthEvent.Submit)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                // CRITICAL: no error surfaced — same as new-email branch
                assertNull(state.error)
                // CRITICAL: stays in sign-up mode — same as new-email
                // branch. Dismissing the confirmation view (via
                // AuthEvent.DismissEmailConfirmation) flips both cases
                // consistently to sign-in mode.
                assertTrue(state.isSignUp)
                // CRITICAL: same observable surface as new-email branch
                assertEquals("existing@example.com", state.emailConfirmationSentTo)
                // Password cleared (matches EmailConfirmationRequired)
                assertEquals("", state.password)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ----------------------------------------------------------------
    // Phase 26.1 (ADR-022 §6.1) — Apple Sign-In flows
    // ----------------------------------------------------------------

    @Test
    fun `apple id token success transitions to authenticated`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onEvent(
                AuthEvent.SignInWithAppleIdToken(
                    idToken = "fake.apple.idtoken",
                    nonce = "nonce-plaintext-xyz",
                ),
            )

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNull(state.error)
                assertNull(state.linkIdentityRequired)
                assertEquals("fake.apple.idtoken", authRepo.lastAppleIdToken)
                assertEquals("nonce-plaintext-xyz", authRepo.lastAppleNonce)
                // FakeAuthRepository flips authState to Authenticated.
                assertTrue(state.authState is AuthState.Authenticated)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `apple id token link required surfaces challenge`() =
        runTest {
            authRepo.signInWithAppleOutcome =
                OAuthSignInOutcome.LinkIdentityRequired(
                    email = "user@privaterelay.appleid.com",
                    provider = OAuthProviderKind.Apple,
                )
            val viewModel = createViewModel()

            viewModel.onEvent(
                AuthEvent.SignInWithAppleIdToken(
                    idToken = "fake.apple.idtoken",
                    nonce = "n",
                ),
            )

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNull(state.error)
                val challenge = state.linkIdentityRequired
                assertNotNull(challenge)
                assertEquals("user@privaterelay.appleid.com", challenge.email)
                assertEquals(OAuthProviderKind.Apple, challenge.provider)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `apple id token failure surfaces generic error`() =
        runTest {
            authRepo.signInWithAppleError = RuntimeException("nonce_mismatch")
            val viewModel = createViewModel()

            viewModel.onEvent(
                AuthEvent.SignInWithAppleIdToken(idToken = "tok", nonce = "n"),
            )

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNotNull(state.error)
                assertNull(state.linkIdentityRequired)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `apple id token submit guards reentry`() =
        runTest {
            // Gate the fake's signInWithApple suspend so the first
            // submit stays in-flight long enough for the second tap to
            // fire — exercising the re-entrant guard.
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val slowRepo =
                object {
                    var callCount = 0
                    var lastIdToken: String? = null

                    suspend fun signInWithApple(
                        idToken: String,
                        @Suppress("UNUSED_PARAMETER") nonce: String,
                    ): io.github.b150005.skeinly.domain.model.OAuthSignInOutcome {
                        callCount++
                        lastIdToken = idToken
                        gate.await()
                        return io.github.b150005.skeinly.domain.model.OAuthSignInOutcome.SessionCreated
                    }
                }
            val viewModel =
                AuthViewModel(
                    observeAuthState = ObserveAuthStateUseCase(authRepo),
                    signIn = SignInUseCase(authRepo),
                    signUp = SignUpUseCase(authRepo),
                    signInWithApple = { idToken, nonce -> slowRepo.signInWithApple(idToken, nonce) },
                    signInWithGoogle = { idToken, nonce -> authRepo.signInWithGoogle(idToken, nonce) },
                    acquireGoogleIdToken = { fakeGoogleAcquisition },
                )

            viewModel.onEvent(
                AuthEvent.SignInWithAppleIdToken(idToken = "first", nonce = "n"),
            )
            // Second tap while still submitting — guard must short-circuit.
            viewModel.onEvent(
                AuthEvent.SignInWithAppleIdToken(idToken = "second", nonce = "n"),
            )

            // Only "first" was forwarded; "second" was dropped by the
            // re-entrant guard on FormState.isSubmitting.
            assertEquals(1, slowRepo.callCount)
            assertEquals("first", slowRepo.lastIdToken)
            gate.complete(Unit)
        }

    // ----------------------------------------------------------------
    // Phase 26.2 (ADR-022 §6.2) — Google Sign-In flows
    // ----------------------------------------------------------------

    @Test
    fun `google sign-in success transitions to authenticated`() =
        runTest {
            fakeGoogleAcquisition =
                io.github.b150005.skeinly.auth.OAuthIdTokenResult.Success(
                    idToken = "fake.google.idtoken",
                    nonce = null,
                )
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.SignInWithGoogle)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNull(state.error)
                assertNull(state.linkIdentityRequired)
                assertTrue(state.authState is AuthState.Authenticated)
                assertEquals("fake.google.idtoken", authRepo.lastGoogleIdToken)
                assertNull(authRepo.lastGoogleNonce)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `google sign-in with nonce forwards nonce to repo`() =
        runTest {
            fakeGoogleAcquisition =
                io.github.b150005.skeinly.auth.OAuthIdTokenResult.Success(
                    idToken = "tok",
                    nonce = "nonce-abc",
                )
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.SignInWithGoogle)

            viewModel.state.test {
                awaitItem()
                assertEquals("nonce-abc", authRepo.lastGoogleNonce)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `google sign-in link required surfaces challenge`() =
        runTest {
            fakeGoogleAcquisition =
                io.github.b150005.skeinly.auth.OAuthIdTokenResult.Success(
                    idToken = "tok",
                    nonce = null,
                )
            authRepo.signInWithGoogleOutcome =
                OAuthSignInOutcome.LinkIdentityRequired(
                    email = "user@gmail.com",
                    provider = OAuthProviderKind.Google,
                )
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.SignInWithGoogle)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                val challenge = state.linkIdentityRequired
                assertNotNull(challenge)
                assertEquals("user@gmail.com", challenge.email)
                assertEquals(OAuthProviderKind.Google, challenge.provider)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `google sign-in user cancelled clears submitting silently`() =
        runTest {
            fakeGoogleAcquisition = io.github.b150005.skeinly.auth.OAuthIdTokenResult.UserCancelled
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.SignInWithGoogle)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                // CRITICAL: no banner. User-cancelled cancel is silent
                // per ADR-022 §6.2 — matches the Apple cancel UX where
                // SignInWithAppleButton swallows the gesture.
                assertNull(state.error)
                assertNull(state.linkIdentityRequired)
                // CRITICAL: no repo call happened.
                assertNull(authRepo.lastGoogleIdToken)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `google sign-in acquisition failure surfaces generic error`() =
        runTest {
            fakeGoogleAcquisition =
                io.github.b150005.skeinly.auth.OAuthIdTokenResult.Failure(
                    message = "No Google account on device",
                )
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.SignInWithGoogle)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNotNull(state.error)
                // No repo call.
                assertNull(authRepo.lastGoogleIdToken)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `google sign-in repo failure surfaces generic error`() =
        runTest {
            fakeGoogleAcquisition =
                io.github.b150005.skeinly.auth.OAuthIdTokenResult.Success(
                    idToken = "tok",
                    nonce = null,
                )
            authRepo.signInWithGoogleError = RuntimeException("nonce_mismatch")
            val viewModel = createViewModel()

            viewModel.onEvent(AuthEvent.SignInWithGoogle)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNotNull(state.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `google sign-in throws acquired by handler maps to generic error`() =
        runTest {
            val viewModel =
                AuthViewModel(
                    observeAuthState = ObserveAuthStateUseCase(authRepo),
                    signIn = SignInUseCase(authRepo),
                    signUp = SignUpUseCase(authRepo),
                    signInWithApple = { idToken, nonce -> authRepo.signInWithApple(idToken, nonce) },
                    signInWithGoogle = { idToken, nonce -> authRepo.signInWithGoogle(idToken, nonce) },
                    acquireGoogleIdToken = { throw RuntimeException("Play Services missing") },
                )

            viewModel.onEvent(AuthEvent.SignInWithGoogle)

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNotNull(state.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `google sign-in guards reentry`() =
        runTest {
            // Same pattern as the Apple re-entrancy test — gate the
            // acquisition with CompletableDeferred so first call stays
            // in-flight while the second tap fires.
            val gate = kotlinx.coroutines.CompletableDeferred<io.github.b150005.skeinly.auth.OAuthIdTokenResult>()
            var callCount = 0
            val viewModel =
                AuthViewModel(
                    observeAuthState = ObserveAuthStateUseCase(authRepo),
                    signIn = SignInUseCase(authRepo),
                    signUp = SignUpUseCase(authRepo),
                    signInWithApple = { idToken, nonce -> authRepo.signInWithApple(idToken, nonce) },
                    signInWithGoogle = { idToken, nonce -> authRepo.signInWithGoogle(idToken, nonce) },
                    acquireGoogleIdToken = {
                        callCount++
                        gate.await()
                    },
                )

            viewModel.onEvent(AuthEvent.SignInWithGoogle)
            viewModel.onEvent(AuthEvent.SignInWithGoogle)

            assertEquals(1, callCount)
            gate.complete(io.github.b150005.skeinly.auth.OAuthIdTokenResult.UserCancelled)
        }

    // ----------------------------------------------------------------
    // Phase 26.3 (ADR-022 §6.2) — iOS Google Sign-In via direct
    // SignInWithGoogleIdToken event (bypassing acquisition seam)
    // ----------------------------------------------------------------

    @Test
    fun `google id token success transitions to authenticated`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onEvent(
                AuthEvent.SignInWithGoogleIdToken(
                    idToken = "fake.google.idtoken.ios",
                    nonce = null,
                ),
            )

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNull(state.error)
                assertNull(state.linkIdentityRequired)
                assertEquals("fake.google.idtoken.ios", authRepo.lastGoogleIdToken)
                assertNull(authRepo.lastGoogleNonce)
                assertTrue(state.authState is AuthState.Authenticated)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `google id token with explicit nonce forwards nonce to repo`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onEvent(
                AuthEvent.SignInWithGoogleIdToken(
                    idToken = "tok",
                    nonce = "nonce-from-ios-bridge",
                ),
            )

            viewModel.state.test {
                awaitItem()
                assertEquals("nonce-from-ios-bridge", authRepo.lastGoogleNonce)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `google id token link required surfaces challenge`() =
        runTest {
            authRepo.signInWithGoogleOutcome =
                OAuthSignInOutcome.LinkIdentityRequired(
                    email = "ios-user@gmail.com",
                    provider = OAuthProviderKind.Google,
                )
            val viewModel = createViewModel()

            viewModel.onEvent(
                AuthEvent.SignInWithGoogleIdToken(idToken = "tok", nonce = null),
            )

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                val challenge = state.linkIdentityRequired
                assertNotNull(challenge)
                assertEquals("ios-user@gmail.com", challenge.email)
                assertEquals(OAuthProviderKind.Google, challenge.provider)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `google id token repo failure surfaces generic error`() =
        runTest {
            authRepo.signInWithGoogleError = RuntimeException("nonce_mismatch")
            val viewModel = createViewModel()

            viewModel.onEvent(
                AuthEvent.SignInWithGoogleIdToken(idToken = "tok", nonce = null),
            )

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNotNull(state.error)
                assertNull(state.linkIdentityRequired)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `google id token submit guards reentry`() =
        runTest {
            // Same gate pattern as Apple IdToken re-entrancy test — the
            // iOS bridge path can fire `signInWithGoogleIdToken` twice
            // if the user double-taps the Continue-with-Google button
            // before the first round trip completes. Re-entrant guard
            // on FormState.isSubmitting must short-circuit the second.
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val slowRepo =
                object {
                    var callCount = 0
                    var lastIdToken: String? = null

                    suspend fun signInWithGoogle(
                        idToken: String,
                        @Suppress("UNUSED_PARAMETER") nonce: String?,
                    ): OAuthSignInOutcome {
                        callCount++
                        lastIdToken = idToken
                        gate.await()
                        return OAuthSignInOutcome.SessionCreated
                    }
                }
            val viewModel =
                AuthViewModel(
                    observeAuthState = ObserveAuthStateUseCase(authRepo),
                    signIn = SignInUseCase(authRepo),
                    signUp = SignUpUseCase(authRepo),
                    signInWithApple = { idToken, nonce -> authRepo.signInWithApple(idToken, nonce) },
                    signInWithGoogle = { idToken, nonce -> slowRepo.signInWithGoogle(idToken, nonce) },
                    acquireGoogleIdToken = { fakeGoogleAcquisition },
                )

            viewModel.onEvent(
                AuthEvent.SignInWithGoogleIdToken(idToken = "first", nonce = null),
            )
            viewModel.onEvent(
                AuthEvent.SignInWithGoogleIdToken(idToken = "second", nonce = null),
            )

            assertEquals(1, slowRepo.callCount)
            assertEquals("first", slowRepo.lastIdToken)
            gate.complete(Unit)
        }

    @Test
    fun `google id token bypasses acquisition seam`() =
        runTest {
            // CRITICAL: the iOS direct-bridge path MUST NOT call
            // `acquireGoogleIdToken()` — that seam returns Failure on
            // iOS and would surface a spurious error. Phase 26.3 fix:
            // `handleGoogleIdToken` calls `forwardGoogleIdToken`
            // directly, never the acquisition lambda.
            var acquisitionCalled = false
            val viewModel =
                AuthViewModel(
                    observeAuthState = ObserveAuthStateUseCase(authRepo),
                    signIn = SignInUseCase(authRepo),
                    signUp = SignUpUseCase(authRepo),
                    signInWithApple = { idToken, nonce -> authRepo.signInWithApple(idToken, nonce) },
                    signInWithGoogle = { idToken, nonce -> authRepo.signInWithGoogle(idToken, nonce) },
                    acquireGoogleIdToken = {
                        acquisitionCalled = true
                        io.github.b150005.skeinly.auth.OAuthIdTokenResult
                            .Failure("should not be called")
                    },
                )

            viewModel.onEvent(
                AuthEvent.SignInWithGoogleIdToken(idToken = "tok", nonce = null),
            )

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSubmitting)
                assertNull(state.error)
                assertEquals("tok", authRepo.lastGoogleIdToken)
                cancelAndIgnoreRemainingEvents()
            }
            assertFalse(acquisitionCalled, "acquireGoogleIdToken must not be called on iOS direct-bridge path")
        }

    @Test
    fun `dismiss link identity prompt clears challenge`() =
        runTest {
            authRepo.signInWithAppleOutcome =
                OAuthSignInOutcome.LinkIdentityRequired(
                    email = "u@example.com",
                    provider = OAuthProviderKind.Apple,
                )
            val viewModel = createViewModel()
            viewModel.onEvent(AuthEvent.SignInWithAppleIdToken("tok", "n"))

            // Wait for the challenge to surface before dismissing.
            viewModel.state.test {
                var current = awaitItem()
                while (current.linkIdentityRequired == null) current = awaitItem()
                assertNotNull(current.linkIdentityRequired)

                viewModel.onEvent(AuthEvent.DismissLinkIdentityPrompt)
                current = awaitItem()
                assertNull(current.linkIdentityRequired)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
