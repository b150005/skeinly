package com.knitnote.data.repository

import com.knitnote.domain.model.AuthState
import com.knitnote.domain.repository.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class AuthRepositoryImpl(
    private val supabaseClient: SupabaseClient?,
) : AuthRepository {
    override fun observeAuthState(): Flow<AuthState> {
        val client = supabaseClient ?: return flowOf(AuthState.Unauthenticated)

        return client.auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Authenticated ->
                    AuthState.Authenticated(
                        userId = status.session.user?.id ?: "",
                        email = status.session.user?.email,
                    )
                is SessionStatus.NotAuthenticated -> AuthState.Unauthenticated
                is SessionStatus.Initializing -> AuthState.Loading
                is SessionStatus.RefreshFailure ->
                    AuthState.Error(
                        status.cause.toString(),
                    )
            }
        }
    }

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ) {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
    ) {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun signOut() {
        supabaseClient?.auth?.signOut()
    }

    override fun getCurrentUserId(): String? = supabaseClient?.auth?.currentUserOrNull()?.id
}
