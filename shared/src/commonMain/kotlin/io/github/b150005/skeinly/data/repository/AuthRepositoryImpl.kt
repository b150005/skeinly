package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.event.AuthEvent
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

class AuthRepositoryImpl(
    private val supabaseClient: SupabaseClient?,
) : AuthRepository {
    // TODO: re-evaluate @SupabaseExperimental before v1.0 — auth.events is the
    // library-recommended migration target for SessionStatus.RefreshFailure.cause
    // but carries an API-stability caveat until the annotation is removed upstream.
    @OptIn(SupabaseExperimental::class)
    override fun observeAuthState(): Flow<AuthState> {
        val client = supabaseClient ?: return flowOf(AuthState.Unauthenticated)
        val auth = client.auth

        // supabase-kt 3.x deprecated `SessionStatus.RefreshFailure.cause` and routes
        // failure-cause diagnostics through a separate `auth.events` SharedFlow.
        // Combine the two so `AuthState.Error(message)` still carries the last-known
        // cause when SessionStatus.RefreshFailure fires.
        val refreshCauseFlow: Flow<RefreshFailureCause?> =
            auth.events
                .filterIsInstance<AuthEvent.RefreshFailure>()
                .map<AuthEvent.RefreshFailure, RefreshFailureCause?> { it.cause }
                .onStart { emit(null) }

        return combine(auth.sessionStatus, refreshCauseFlow) { status, latestCause ->
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
                        latestCause?.toString() ?: "Session refresh failed",
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

    override suspend fun sendPasswordResetEmail(email: String) {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        client.auth.resetPasswordForEmail(email)
    }

    override suspend fun updatePassword(newPassword: String) {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        client.auth.updateUser { password = newPassword }
    }

    override suspend fun updateEmail(newEmail: String) {
        val client =
            supabaseClient
                ?: throw IllegalStateException("Supabase is not configured")

        client.auth.updateUser { email = newEmail }
    }
}
