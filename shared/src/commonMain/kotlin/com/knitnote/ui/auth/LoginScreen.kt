package com.knitnote.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LoginScreen(
    viewModel: AuthViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(AuthEvent.ClearError)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Knit Note",
                style = MaterialTheme.typography.headlineLarge,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (state.isSignUp) "Create Account" else "Sign In",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = state.email,
                onValueChange = { viewModel.onEvent(AuthEvent.UpdateEmail(it)) },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.password,
                onValueChange = { viewModel.onEvent(AuthEvent.UpdatePassword(it)) },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.onEvent(AuthEvent.Submit) },
                enabled = !state.isSubmitting && state.email.isNotBlank() && state.password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(if (state.isSignUp) "Sign Up" else "Sign In")
                }
            }

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = { viewModel.onEvent(AuthEvent.ToggleMode) },
            ) {
                Text(
                    if (state.isSignUp) "Already have an account? Sign In"
                    else "Don't have an account? Sign Up",
                )
            }

            Spacer(Modifier.height(24.dp))

            // Social login buttons (coming soon)
            OutlinedButton(
                onClick = { },
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
            ) {
                Text("Continue with Google (Coming soon)")
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { },
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
            ) {
                Text("Continue with Apple (Coming soon)")
            }
        }
    }
}
