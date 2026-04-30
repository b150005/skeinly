package io.github.b150005.skeinly.domain.repository

import io.github.b150005.skeinly.domain.model.AuthState
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    fun observeAuthState(): Flow<AuthState>

    suspend fun signInWithEmail(
        email: String,
        password: String,
    )

    suspend fun signUpWithEmail(
        email: String,
        password: String,
    )

    suspend fun signOut()

    suspend fun deleteAccount()

    fun getCurrentUserId(): String?

    /**
     * Sends a password-reset email to the given address. The email contains
     * a Supabase-hosted reset link that lets the user set a new password
     * via Supabase's default reset page (no in-app deep link required for
     * alpha1; Universal Links / App Links land in Phase D).
     */
    suspend fun sendPasswordResetEmail(email: String)

    /**
     * Updates the currently-authenticated user's password. Requires an
     * active session — Supabase rejects with 401 if not signed in.
     */
    suspend fun updatePassword(newPassword: String)

    /**
     * Initiates an email change for the current user. Supabase sends a
     * verification email to [newEmail]; the change does not take effect
     * until the user clicks the verification link. The current session
     * remains valid throughout.
     */
    suspend fun updateEmail(newEmail: String)
}
