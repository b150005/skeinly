package io.github.b150005.skeinly.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_continue_with_apple
import io.github.b150005.skeinly.generated.resources.action_continue_with_google
import io.github.b150005.skeinly.generated.resources.action_dismiss_link_identity
import io.github.b150005.skeinly.generated.resources.action_forgot_password
import io.github.b150005.skeinly.generated.resources.action_link_identity
import io.github.b150005.skeinly.generated.resources.action_return_to_sign_in
import io.github.b150005.skeinly.generated.resources.action_sign_in
import io.github.b150005.skeinly.generated.resources.action_sign_up
import io.github.b150005.skeinly.generated.resources.action_toggle_to_sign_in
import io.github.b150005.skeinly.generated.resources.action_toggle_to_sign_up
import io.github.b150005.skeinly.generated.resources.app_name
import io.github.b150005.skeinly.generated.resources.body_email_already_used_link_prompt
import io.github.b150005.skeinly.generated.resources.body_email_confirmation_check_existing_account
import io.github.b150005.skeinly.generated.resources.body_email_confirmation_check_spam
import io.github.b150005.skeinly.generated.resources.body_email_confirmation_sent
import io.github.b150005.skeinly.generated.resources.label_email
import io.github.b150005.skeinly.generated.resources.label_password
import io.github.b150005.skeinly.generated.resources.state_linking_identity
import io.github.b150005.skeinly.generated.resources.title_create_account
import io.github.b150005.skeinly.generated.resources.title_email_confirmation_sent
import io.github.b150005.skeinly.generated.resources.title_link_identity
import io.github.b150005.skeinly.generated.resources.title_sign_in
import io.github.b150005.skeinly.ui.components.LiveSnackbarHost
import io.github.b150005.skeinly.ui.components.localized
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LoginScreen(
    onForgotPassword: () -> Unit = {},
    viewModel: AuthViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(AuthEvent.ClearError)
        }
    }

    Scaffold(
        snackbarHost = { LiveSnackbarHost(snackbarHostState) },
    ) { padding ->
        val confirmationEmail = state.emailConfirmationSentTo
        if (confirmationEmail != null) {
            EmailConfirmationSentView(
                email = confirmationEmail,
                onReturnToSignIn = { viewModel.onEvent(AuthEvent.DismissEmailConfirmation) },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 32.dp),
            )
            return@Scaffold
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp)
                    .testTag("loginScreen"),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text =
                    stringResource(
                        if (state.isSignUp) Res.string.title_create_account else Res.string.title_sign_in,
                    ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = state.email,
                onValueChange = { viewModel.onEvent(AuthEvent.UpdateEmail(it)) },
                label = { Text(stringResource(Res.string.label_email)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("emailField"),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.password,
                onValueChange = { viewModel.onEvent(AuthEvent.UpdatePassword(it)) },
                label = { Text(stringResource(Res.string.label_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("passwordField"),
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.onEvent(AuthEvent.Submit) },
                enabled = !state.isSubmitting && state.email.isNotBlank() && state.password.isNotBlank(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("submitButton"),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        stringResource(
                            if (state.isSignUp) Res.string.action_sign_up else Res.string.action_sign_in,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = { viewModel.onEvent(AuthEvent.ToggleMode) },
            ) {
                Text(
                    stringResource(
                        if (state.isSignUp) Res.string.action_toggle_to_sign_in else Res.string.action_toggle_to_sign_up,
                    ),
                )
            }

            // Forgot password link — only shown in sign-in mode (not sign-up).
            if (!state.isSignUp) {
                TextButton(
                    onClick = onForgotPassword,
                    modifier = Modifier.testTag("forgotPasswordButton"),
                ) {
                    Text(stringResource(Res.string.action_forgot_password))
                }
            }

            Spacer(Modifier.height(24.dp))

            // Phase 26.2 (ADR-022 §6.2) — Google Sign-In CTA. Enabled
            // when not currently submitting; on tap fires
            // `OAuthClient.acquireGoogleIdToken()` → `signInWithGoogle()`.
            OutlinedButton(
                onClick = { viewModel.onEvent(AuthEvent.SignInWithGoogle) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("signInWithGoogleButton"),
                enabled = !state.isSubmitting,
            ) {
                Text(stringResource(Res.string.action_continue_with_google))
            }

            Spacer(Modifier.height(8.dp))

            // Phase 26.x (ADR-022 §6.1) — Apple-on-Android Sign-In CTA
            // via Supabase web-OAuth + Custom Tabs. On tap, supabase-kt
            // launches a Chrome Custom Tab to Supabase's hosted auth
            // endpoint, which then routes through Apple's OAuth flow.
            // On completion Supabase redirects to `skeinly://auth-callback`,
            // `MainActivity.handleDeeplinks(intent)` resolves the
            // session, and `observeAuthState()` emits Authenticated.
            //
            // iOS Compose path never renders this screen — iOS uses
            // SwiftUI's `LoginScreen.swift` with the native
            // SignInWithAppleButton from Phase 26.1. The button
            // appears on Android only (the Compose tree backing
            // `:androidApp:`).
            OutlinedButton(
                onClick = { viewModel.onEvent(AuthEvent.SignInWithAppleViaWebOAuth) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("signInWithAppleButton"),
                enabled = !state.isSubmitting,
            ) {
                Text(stringResource(Res.string.action_continue_with_apple))
            }
        }

        // Phase 26.4 (ADR-022 §6.3) — link-identity resolution
        // AlertDialog. Surfaces when `state.linkIdentityRequired` is
        // non-null (the user attempted OAuth sign-in with an email
        // that already exists under a different auth method). User
        // types their existing password to merge the OAuth identity
        // into the existing account via supabase-kt's
        // `linkIdentityWithIdToken`.
        //
        // Email is the `challenge.email` value Supabase reported —
        // shown as part of the body copy so the user can identify
        // which credentials to retry with. The user types only the
        // password (no email field) to remove the typo risk on the
        // already-Supabase-known email.
        val challenge = state.linkIdentityRequired
        if (challenge != null) {
            // Password state lives at the call-site (NOT in
            // ViewModel.form.password) because the form's password
            // field is the regular sign-up/sign-in password; the
            // link-identity password is a distinct scratch buffer
            // for the duration of the dialog. Cleared automatically
            // when the dialog dismisses (state.linkIdentityRequired
            // flips back to null + the dialog leaves composition).
            //
            // Key on `pendingIdToken` (NOT `email`) so a same-email
            // re-challenge (e.g. user dismisses Apple → tries Google
            // with same email) gets a fresh password buffer. Each
            // OAuth invocation issues a unique JWT, so the token is
            // a safer freshness signal than the email value.
            var linkPassword by rememberSaveable(challenge.pendingIdToken) {
                mutableStateOf("")
            }
            AlertDialog(
                onDismissRequest = {
                    if (!state.isSubmitting) {
                        viewModel.onEvent(AuthEvent.DismissLinkIdentityPrompt)
                    }
                },
                title = {
                    Text(stringResource(Res.string.title_link_identity))
                },
                text = {
                    Column {
                        Text(
                            text =
                                stringResource(
                                    Res.string.body_email_already_used_link_prompt,
                                    challenge.email,
                                ),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = linkPassword,
                            onValueChange = { linkPassword = it },
                            label = { Text(stringResource(Res.string.label_password)) },
                            singleLine = true,
                            enabled = !state.isSubmitting,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("linkIdentityPasswordField"),
                        )
                    }
                },
                confirmButton = {
                    val linkingLabel = stringResource(Res.string.state_linking_identity)
                    Button(
                        onClick = {
                            viewModel.onEvent(AuthEvent.SubmitLinkIdentity(linkPassword))
                        },
                        enabled = !state.isSubmitting && linkPassword.isNotBlank(),
                        modifier =
                            Modifier
                                .testTag("linkIdentitySubmitButton")
                                .then(
                                    if (state.isSubmitting) {
                                        Modifier.semantics { contentDescription = linkingLabel }
                                    } else {
                                        Modifier
                                    },
                                ),
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(Res.string.action_link_identity))
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            viewModel.onEvent(AuthEvent.DismissLinkIdentityPrompt)
                        },
                        enabled = !state.isSubmitting,
                        modifier = Modifier.testTag("linkIdentityCancelButton"),
                    ) {
                        Text(stringResource(Res.string.action_dismiss_link_identity))
                    }
                },
            )
        }
    }
}

@Composable
private fun EmailConfirmationSentView(
    email: String,
    onReturnToSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.testTag("emailConfirmationSentScreen"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.title_email_confirmation_sent),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(Res.string.body_email_confirmation_sent, email),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(Res.string.body_email_confirmation_check_spam),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(Res.string.body_email_confirmation_check_existing_account),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onReturnToSignIn,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("returnToSignInButton"),
        ) {
            Text(stringResource(Res.string.action_return_to_sign_in))
        }
    }
}
