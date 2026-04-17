package io.github.b150005.knitnote.domain.model

sealed interface AuthState {
    data object Loading : AuthState

    data class Authenticated(
        val userId: String,
        val email: String?,
    ) : AuthState

    data object Unauthenticated : AuthState

    data class Error(
        val message: String,
    ) : AuthState
}
