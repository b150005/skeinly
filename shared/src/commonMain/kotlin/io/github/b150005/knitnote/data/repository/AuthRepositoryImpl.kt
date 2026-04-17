package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
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

    override suspend fun deleteAccount() {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        client.postgrest.rpc("delete_own_account")
        // Best-effort local session cleanup — account is already deleted server-side
        try {
            client.auth.signOut()
        } catch (_: Exception) {
            // Ignore: the auth session will become invalid on next refresh anyway
        }
    }

    override fun getCurrentUserId(): String? = supabaseClient?.auth?.currentUserOrNull()?.id
}
