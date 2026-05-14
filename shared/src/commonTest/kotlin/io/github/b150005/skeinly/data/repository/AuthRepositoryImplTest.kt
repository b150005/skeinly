package io.github.b150005.skeinly.data.repository

import app.cash.turbine.test
import io.github.b150005.skeinly.domain.model.AuthState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRepositoryImplTest {
    @Test
    fun `observeAuthState returns Unauthenticated when client is null`() =
        runTest {
            val repo = AuthRepositoryImpl(supabaseClient = null)
            repo.observeAuthState().test {
                assertEquals(AuthState.Unauthenticated, awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `signInWithEmail throws when client is null`() =
        runTest {
            val repo = AuthRepositoryImpl(supabaseClient = null)
            assertFailsWith<IllegalStateException> {
                repo.signInWithEmail("test@example.com", "password")
            }
        }

    @Test
    fun `signUpWithEmail throws when client is null`() =
        runTest {
            val repo = AuthRepositoryImpl(supabaseClient = null)
            assertFailsWith<IllegalStateException> {
                repo.signUpWithEmail("test@example.com", "password")
            }
        }

    @Test
    fun `signOut does nothing when client is null`() =
        runTest {
            val repo = AuthRepositoryImpl(supabaseClient = null)
            // Should not throw
            repo.signOut()
        }

    @Test
    fun `getCurrentUserId returns null when client is null`() {
        val repo = AuthRepositoryImpl(supabaseClient = null)
        assertNull(repo.getCurrentUserId())
    }

    // ----------------------------------------------------------------
    // Phase 26.1 (ADR-022 §6.1) — Apple Sign-In short-circuit + JWT
    // payload extraction. Full happy-path integration with Supabase
    // is exercised at the AuthViewModel layer via FakeAuthRepository;
    // here we only lock the client-null + helper-parsing contracts.
    // ----------------------------------------------------------------

    @Test
    fun `signInWithApple throws when client is null`() =
        runTest {
            val repo = AuthRepositoryImpl(supabaseClient = null)
            assertFailsWith<IllegalStateException> {
                repo.signInWithApple(idToken = "tok", nonce = "n")
            }
        }

    // ----------------------------------------------------------------
    // Phase 26.2 (ADR-022 §6.2) — Google Sign-In short-circuit + the
    // shared `signInWithOAuthIdToken` private path. Full happy-path
    // integration with Supabase is exercised at the AuthViewModel
    // layer via FakeAuthRepository.
    // ----------------------------------------------------------------

    @Test
    fun `signInWithGoogle throws when client is null`() =
        runTest {
            val repo = AuthRepositoryImpl(supabaseClient = null)
            assertFailsWith<IllegalStateException> {
                repo.signInWithGoogle(idToken = "tok", nonce = null)
            }
        }

    // ----------------------------------------------------------------
    // Phase 26.4 (ADR-022 §6.3) — linkPendingIdentity short-circuit.
    // Full happy-path is exercised at AuthViewModel layer via the
    // FakeAuthRepository call-counter; here we lock the null-client
    // contract.
    // ----------------------------------------------------------------

    @Test
    fun `linkPendingIdentity throws when client is null`() =
        runTest {
            val repo = AuthRepositoryImpl(supabaseClient = null)
            assertFailsWith<IllegalStateException> {
                repo.linkPendingIdentity(
                    provider = io.github.b150005.skeinly.domain.model.OAuthProviderKind.Apple,
                    pendingIdToken = "tok",
                    nonce = "n",
                )
            }
        }

    @Test
    fun `signInWithGoogle nonce defaults to null when omitted`() =
        runTest {
            val repo = AuthRepositoryImpl(supabaseClient = null)
            // Default nonce parameter — the suspend call short-circuits
            // before consuming nonce because the client is null, but
            // the call site shape (no `nonce =` argument) is what
            // matters: Compose / SwiftUI surfaces want a 1-arg call
            // site for Google.
            assertFailsWith<IllegalStateException> {
                repo.signInWithGoogle(idToken = "tok")
            }
        }

    @Test
    fun `extractEmailFromIdToken handles google issued tokens`() {
        // Google ID tokens carry the same `email` JWT claim shape as
        // Apple — re-asserting the helper against a Google-shaped
        // payload locks in cross-provider robustness.
        val header = base64Url("""{"alg":"RS256","kid":"abc"}""")
        val payload =
            base64Url(
                """{"iss":"https://accounts.google.com","aud":"web-client.apps.googleusercontent.com","email":"user@gmail.com","email_verified":true}""",
            )
        val token = "$header.$payload.sig"
        assertEquals("user@gmail.com", extractEmailFromIdToken(token))
    }

    @Test
    fun `extractEmailFromIdToken returns null on google token without email scope`() {
        // Google can issue ID tokens without the email scope; the
        // helper returns null and the UI surfaces a generic prompt.
        val header = base64Url("""{"alg":"RS256","kid":"abc"}""")
        val payload = base64Url("""{"iss":"https://accounts.google.com","sub":"123","email_verified":true}""")
        val token = "$header.$payload.sig"
        assertNull(extractEmailFromIdToken(token))
    }

    @Test
    fun `oauth provider kind enum has exactly Apple and Google`() {
        // Compile-time guard: the enum has exactly two entries. If a
        // future provider lands (e.g. Microsoft, GitHub), this test
        // forces a deliberate update + a sweep of consumer sites.
        assertEquals(
            setOf(
                io.github.b150005.skeinly.domain.model.OAuthProviderKind.Apple,
                io.github.b150005.skeinly.domain.model.OAuthProviderKind.Google,
            ),
            io.github.b150005.skeinly.domain.model.OAuthProviderKind.entries
                .toSet(),
        )
    }

    @Test
    fun `oauth sign in outcome session created identity`() {
        // SessionCreated is a data object — identity preserved across
        // references. Locks the type shape so a future refactor
        // doesn't silently change it to a data class (which would
        // break the VM's `is OAuthSignInOutcome.SessionCreated`
        // smart-cast benchmark).
        val a: io.github.b150005.skeinly.domain.model.OAuthSignInOutcome =
            io.github.b150005.skeinly.domain.model.OAuthSignInOutcome.SessionCreated
        val b: io.github.b150005.skeinly.domain.model.OAuthSignInOutcome =
            io.github.b150005.skeinly.domain.model.OAuthSignInOutcome.SessionCreated
        assertTrue(a === b)
    }

    @Test
    fun `oauth sign in outcome link required carries provider verbatim`() {
        // Constructor preserves the provider field; the VM uses
        // outcome.provider to populate LinkIdentityChallenge so the
        // UI can render provider-specific copy.
        val outcome =
            io.github.b150005.skeinly.domain.model.OAuthSignInOutcome.LinkIdentityRequired(
                email = "user@gmail.com",
                provider = io.github.b150005.skeinly.domain.model.OAuthProviderKind.Google,
                pendingIdToken = "fake.google.idtoken",
                nonce = null,
            )
        assertEquals("user@gmail.com", outcome.email)
        assertEquals(
            io.github.b150005.skeinly.domain.model.OAuthProviderKind.Google,
            outcome.provider,
        )
        // Phase 26.4 — outcome carries the token + nonce for the
        // post-password linkIdentity resolution step.
        assertEquals("fake.google.idtoken", outcome.pendingIdToken)
        assertEquals(null, outcome.nonce)
    }

    @Test
    fun `extractEmailFromIdToken returns email claim`() {
        // header={"alg":"ES256"}, payload={"email":"hello@example.com"}
        // signature omitted — verification is Supabase-side.
        val header = base64Url("""{"alg":"ES256"}""")
        val payload = base64Url("""{"email":"hello@example.com"}""")
        val token = "$header.$payload.sig"
        assertEquals(
            "hello@example.com",
            extractEmailFromIdToken(token),
        )
    }

    @Test
    fun `extractEmailFromIdToken handles apple private relay`() {
        val header = base64Url("""{"alg":"ES256"}""")
        val payload =
            base64Url(
                """{"sub":"001234.abc","email":"abc123@privaterelay.appleid.com","is_private_email":"true"}""",
            )
        val token = "$header.$payload.sig"
        assertEquals(
            "abc123@privaterelay.appleid.com",
            extractEmailFromIdToken(token),
        )
    }

    @Test
    fun `extractEmailFromIdToken returns null for malformed jwt`() {
        assertNull(extractEmailFromIdToken("not-a-jwt"))
        assertNull(extractEmailFromIdToken(""))
        assertNull(extractEmailFromIdToken("only.two"))
    }

    @Test
    fun `extractEmailFromIdToken returns null when email claim absent`() {
        val header = base64Url("""{"alg":"ES256"}""")
        val payload = base64Url("""{"sub":"001234.abc","iss":"https://appleid.apple.com"}""")
        val token = "$header.$payload.sig"
        assertNull(extractEmailFromIdToken(token))
    }

    @Test
    fun `extractEmailFromIdToken returns null on blank email value`() {
        val header = base64Url("""{"alg":"ES256"}""")
        // Empty-string email is treated as "no usable claim" — surface
        // generic prompt copy rather than an empty address.
        val payload = base64Url("""{"sub":"001234.abc","email":""}""")
        val token = "$header.$payload.sig"
        assertNull(extractEmailFromIdToken(token))
    }

    @Test
    fun `extractEmailFromIdToken handles claim ordering robustly`() {
        val header = base64Url("""{"alg":"ES256"}""")
        // email claim appears after other quoted claims — make sure the
        // substring parse finds the right key/value pair.
        val payload =
            base64Url(
                """{"iss":"https://appleid.apple.com","aud":"io.github.b150005.skeinly","email":"second@example.com"}""",
            )
        val token = "$header.$payload.sig"
        assertEquals("second@example.com", extractEmailFromIdToken(token))
    }

    @Test
    fun `extractEmailFromIdToken survives base64url padding stripped`() {
        // Payload length yields ASCII 21 bytes → no `=` padding in Base64URL;
        // ensures the padding-restoration math doesn't break the un-padded case.
        val header = base64Url("""{"a":1}""")
        val payload = base64Url("""{"email":"a@b.co"}""")
        val token = "$header.$payload.sig"
        assertEquals("a@b.co", extractEmailFromIdToken(token))
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun base64Url(plain: String): String {
        val encoded =
            kotlin.io.encoding.Base64
                .encode(plain.encodeToByteArray())
        // Convert standard Base64 to URL-safe (drop padding, swap chars)
        return encoded
            .trimEnd('=')
            .replace('+', '-')
            .replace('/', '_')
    }
}
