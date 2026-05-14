package io.github.b150005.skeinly.data.repository

import app.cash.turbine.test
import io.github.b150005.skeinly.domain.model.AuthState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
