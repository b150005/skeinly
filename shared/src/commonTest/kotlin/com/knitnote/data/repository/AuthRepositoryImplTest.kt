package com.knitnote.data.repository

import app.cash.turbine.test
import com.knitnote.domain.model.AuthState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AuthRepositoryImplTest {
    @Test
    fun `observeAuthState returns Unauthenticated when client is null`() = runTest {
        val repo = AuthRepositoryImpl(supabaseClient = null)
        repo.observeAuthState().test {
            assertEquals(AuthState.Unauthenticated, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `signInWithEmail throws when client is null`() = runTest {
        val repo = AuthRepositoryImpl(supabaseClient = null)
        assertFailsWith<IllegalStateException> {
            repo.signInWithEmail("test@example.com", "password")
        }
    }

    @Test
    fun `signUpWithEmail throws when client is null`() = runTest {
        val repo = AuthRepositoryImpl(supabaseClient = null)
        assertFailsWith<IllegalStateException> {
            repo.signUpWithEmail("test@example.com", "password")
        }
    }

    @Test
    fun `signOut does nothing when client is null`() = runTest {
        val repo = AuthRepositoryImpl(supabaseClient = null)
        // Should not throw
        repo.signOut()
    }

    @Test
    fun `getCurrentUserId returns null when client is null`() {
        val repo = AuthRepositoryImpl(supabaseClient = null)
        assertNull(repo.getCurrentUserId())
    }
}
