package io.github.b150005.skeinly.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.config.BuildFlags
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_cancel
import io.github.b150005.skeinly.generated.resources.action_change_email
import io.github.b150005.skeinly.generated.resources.action_change_password
import io.github.b150005.skeinly.generated.resources.action_delete
import io.github.b150005.skeinly.generated.resources.action_delete_account
import io.github.b150005.skeinly.generated.resources.action_privacy_policy
import io.github.b150005.skeinly.generated.resources.action_save
import io.github.b150005.skeinly.generated.resources.action_send_feedback
import io.github.b150005.skeinly.generated.resources.action_sign_out
import io.github.b150005.skeinly.generated.resources.action_terms_of_service
import io.github.b150005.skeinly.generated.resources.body_delete_account_warning
import io.github.b150005.skeinly.generated.resources.body_diagnostic_data_explanation
import io.github.b150005.skeinly.generated.resources.body_send_feedback_explanation
import io.github.b150005.skeinly.generated.resources.dialog_change_email_title
import io.github.b150005.skeinly.generated.resources.dialog_change_password_title
import io.github.b150005.skeinly.generated.resources.dialog_delete_account_body
import io.github.b150005.skeinly.generated.resources.dialog_delete_account_title
import io.github.b150005.skeinly.generated.resources.label_about_section
import io.github.b150005.skeinly.generated.resources.label_account_section
import io.github.b150005.skeinly.generated.resources.label_beta_section
import io.github.b150005.skeinly.generated.resources.label_danger_zone
import io.github.b150005.skeinly.generated.resources.label_diagnostic_data_sharing
import io.github.b150005.skeinly.generated.resources.label_new_email
import io.github.b150005.skeinly.generated.resources.label_new_password
import io.github.b150005.skeinly.generated.resources.message_email_change_pending
import io.github.b150005.skeinly.generated.resources.message_password_changed
import io.github.b150005.skeinly.generated.resources.state_deleting_account
import io.github.b150005.skeinly.generated.resources.title_settings
import io.github.b150005.skeinly.ui.components.LiveSnackbarHost
import io.github.b150005.skeinly.ui.components.localized
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private const val URL_PRIVACY_POLICY = "https://b150005.github.io/skeinly/privacy-policy/"
private const val URL_TERMS_OF_SERVICE = "https://b150005.github.io/skeinly/terms-of-service/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAccountDeleted: () -> Unit,
    // Phase 39.4 (ADR-015 §6) — invoked when the user taps "Send Feedback".
    // Phase 39.5 will wire this to the BugReportPreviewScreen + GitHub
    // Issue prefill flow; today the binding from NavGraph is a no-op so
    // the row renders correctly under the beta-gated section without
    // surfacing a half-built UX.
    onSendFeedback: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // state.error is still rendered raw here — ViewModel error-message
    // localization is deferred per Tech Debt Backlog.
    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(SettingsEvent.ClearError)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.accountDeleted.collect {
            onAccountDeleted()
        }
    }

    val passwordChangedMessage = stringResource(Res.string.message_password_changed)
    val emailChangePendingMessage = stringResource(Res.string.message_email_change_pending)
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { event ->
            val message =
                when (event) {
                    SettingsToastEvent.PasswordChanged -> passwordChangedMessage
                    SettingsToastEvent.EmailChangePending -> emailChangePendingMessage
                }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.testTag("settingsScreen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("backButton")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { LiveSnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
            ) {
                // B3 (Phase 39.1): Account section is gated on
                // `state.isSignedIn`. Apple Reviewers may reject UIs that
                // imply features the user can't access (e.g. showing
                // "Sign Out" / "Delete Account" while never signed in).
                if (state.isSignedIn) {
                    // Account section
                    Text(
                        text = stringResource(Res.string.label_account_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )

                    state.email?.let { email ->
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    Button(
                        onClick = { viewModel.onEvent(SettingsEvent.SignOut) },
                        modifier = Modifier.fillMaxWidth().testTag("signOutButton"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(stringResource(Res.string.action_sign_out))
                    }

                    Spacer(Modifier.height(8.dp))

                    // Phase B3: Change password / change email
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.action_change_password)) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    viewModel.onEvent(SettingsEvent.RequestChangePassword)
                                }.testTag("changePasswordButton"),
                    )

                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.action_change_email)) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    viewModel.onEvent(SettingsEvent.RequestChangeEmail)
                                }.testTag("changeEmailButton"),
                    )

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(24.dp))
                }

                // About / Legal section — Phase E2
                Text(
                    text = stringResource(Res.string.label_about_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                val uriHandler = LocalUriHandler.current

                ListItem(
                    headlineContent = { Text(stringResource(Res.string.action_privacy_policy)) },
                    leadingContent = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                uriHandler.openUri(URL_PRIVACY_POLICY)
                            }.testTag("privacyPolicyButton"),
                )

                ListItem(
                    headlineContent = { Text(stringResource(Res.string.action_terms_of_service)) },
                    leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                uriHandler.openUri(URL_TERMS_OF_SERVICE)
                            }.testTag("termsOfServiceButton"),
                )

                // Beta section — Phase 39.4 (ADR-015 §6). Holds diagnostic
                // data sharing toggle + Send Feedback. Gated on
                // [BuildFlags.isBeta] so production binaries surface
                // neither — Phase 27a no-tracking stance for v1.0+. The
                // pre-39.4 "Privacy" header / `label_allow_analytics`
                // copy was renamed because what we collect on beta is
                // diagnostic data, not "usage analytics" in general.
                if (BuildFlags.isBeta) {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = stringResource(Res.string.label_beta_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.label_diagnostic_data_sharing))
                        },
                        trailingContent = {
                            // `onCheckedChange = null` so the Switch is purely
                            // visual — the row's `clickable` is the single
                            // dispatch site. With both wired, a tap on the
                            // Switch thumb fires onCheckedChange (with the
                            // new value) AND the row's clickable (toggling
                            // the already-flipped value), cancelling intent.
                            Switch(
                                checked = state.analyticsOptIn,
                                onCheckedChange = null,
                                modifier = Modifier.testTag("analyticsOptInSwitch"),
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Switch) {
                                    viewModel.onEvent(
                                        SettingsEvent.SetAnalyticsOptIn(!state.analyticsOptIn),
                                    )
                                },
                    )
                    Text(
                        text = stringResource(Res.string.body_diagnostic_data_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    Spacer(Modifier.height(8.dp))

                    // Phase 39.5 wires this to the BugReportPreviewScreen
                    // + GitHub Issue prefill launcher. Today the callback
                    // is a no-op (default `onSendFeedback = {}` at the
                    // screen signature), so the entry renders correctly
                    // and a tester can discover the feature without an
                    // observable broken UX behind the row.
                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.action_send_feedback))
                        },
                        leadingContent = {
                            Icon(Icons.Filled.Feedback, contentDescription = null)
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onSendFeedback() }
                                .testTag("sendFeedbackButton"),
                    )
                    Text(
                        text = stringResource(Res.string.body_send_feedback_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // B3 (Phase 39.1): Danger zone is auth-only — same gating
                // as the Account section above.
                if (state.isSignedIn) {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(24.dp))

                    // Danger zone
                    Text(
                        text = stringResource(Res.string.label_danger_zone),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(Res.string.body_delete_account_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    Button(
                        onClick = { showDeleteConfirmation = true },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        enabled = !state.isDeletingAccount,
                        modifier = Modifier.fillMaxWidth().testTag("deleteAccountButton"),
                    ) {
                        if (state.isDeletingAccount) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.onError,
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(
                            if (state.isDeletingAccount) {
                                stringResource(Res.string.state_deleting_account)
                            } else {
                                stringResource(Res.string.action_delete_account)
                            },
                        )
                    }
                }
            }
        }
    }

    if (state.pendingChangePasswordDialog) {
        ChangePasswordDialog(
            isSubmitting = state.isChangingPassword,
            onConfirm = { newPassword ->
                viewModel.onEvent(SettingsEvent.ConfirmChangePassword(newPassword))
            },
            onDismiss = { viewModel.onEvent(SettingsEvent.DismissChangePassword) },
        )
    }

    if (state.pendingChangeEmailDialog) {
        ChangeEmailDialog(
            isSubmitting = state.isChangingEmail,
            onConfirm = { newEmail ->
                viewModel.onEvent(SettingsEvent.ConfirmChangeEmail(newEmail))
            },
            onDismiss = { viewModel.onEvent(SettingsEvent.DismissChangeEmail) },
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(Res.string.dialog_delete_account_title)) },
            text = { Text(stringResource(Res.string.dialog_delete_account_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.onEvent(SettingsEvent.DeleteAccountConfirmed)
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text(stringResource(Res.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ChangePasswordDialog(
    isSubmitting: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newPassword by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("changePasswordDialog"),
        title = { Text(stringResource(Res.string.dialog_change_password_title)) },
        text = {
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text(stringResource(Res.string.label_new_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth().testTag("newPasswordField"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newPassword) },
                enabled = !isSubmitting && newPassword.isNotBlank(),
                modifier = Modifier.testTag("confirmChangePasswordButton"),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                } else {
                    Text(stringResource(Res.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ChangeEmailDialog(
    isSubmitting: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newEmail by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("changeEmailDialog"),
        title = { Text(stringResource(Res.string.dialog_change_email_title)) },
        text = {
            OutlinedTextField(
                value = newEmail,
                onValueChange = { newEmail = it },
                label = { Text(stringResource(Res.string.label_new_email)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    ),
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth().testTag("newEmailField"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newEmail) },
                enabled = !isSubmitting && newEmail.isNotBlank(),
                modifier = Modifier.testTag("confirmChangeEmailButton"),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                } else {
                    Text(stringResource(Res.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
