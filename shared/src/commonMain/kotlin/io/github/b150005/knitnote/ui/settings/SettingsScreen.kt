package io.github.b150005.knitnote.ui.settings

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
import androidx.compose.material3.SnackbarHost
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
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_change_email
import io.github.b150005.knitnote.generated.resources.action_change_password
import io.github.b150005.knitnote.generated.resources.action_delete
import io.github.b150005.knitnote.generated.resources.action_delete_account
import io.github.b150005.knitnote.generated.resources.action_privacy_policy
import io.github.b150005.knitnote.generated.resources.action_save
import io.github.b150005.knitnote.generated.resources.action_sign_out
import io.github.b150005.knitnote.generated.resources.action_terms_of_service
import io.github.b150005.knitnote.generated.resources.body_analytics_explanation
import io.github.b150005.knitnote.generated.resources.body_delete_account_warning
import io.github.b150005.knitnote.generated.resources.dialog_change_email_title
import io.github.b150005.knitnote.generated.resources.dialog_change_password_title
import io.github.b150005.knitnote.generated.resources.dialog_delete_account_body
import io.github.b150005.knitnote.generated.resources.dialog_delete_account_title
import io.github.b150005.knitnote.generated.resources.label_about_section
import io.github.b150005.knitnote.generated.resources.label_account_section
import io.github.b150005.knitnote.generated.resources.label_allow_analytics
import io.github.b150005.knitnote.generated.resources.label_danger_zone
import io.github.b150005.knitnote.generated.resources.label_new_email
import io.github.b150005.knitnote.generated.resources.label_new_password
import io.github.b150005.knitnote.generated.resources.label_privacy_section
import io.github.b150005.knitnote.generated.resources.message_email_change_pending
import io.github.b150005.knitnote.generated.resources.message_password_changed
import io.github.b150005.knitnote.generated.resources.state_deleting_account
import io.github.b150005.knitnote.generated.resources.title_settings
import io.github.b150005.knitnote.ui.components.localized
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private const val URL_PRIVACY_POLICY = "https://b150005.github.io/knit-note/privacy-policy/"
private const val URL_TERMS_OF_SERVICE = "https://b150005.github.io/knit-note/terms-of-service/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAccountDeleted: () -> Unit,
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(24.dp))

                // Privacy section — Phase F2 analytics opt-in. Default OFF;
                // PostHog SDK init (KnitNoteApplication / iOSApp) is gated on
                // this flag. The whole row is clickable so tapping anywhere
                // (not just the Switch thumb) flips the value.
                Text(
                    text = stringResource(Res.string.label_privacy_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                ListItem(
                    headlineContent = { Text(stringResource(Res.string.label_allow_analytics)) },
                    trailingContent = {
                        // `onCheckedChange = null` so the Switch is purely visual —
                        // the row's `clickable` is the single dispatch site. With
                        // both wired, a tap on the Switch thumb fires onCheckedChange
                        // (with the new value) AND the row's clickable (toggling the
                        // already-flipped value), cancelling the user's intent.
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
                    text = stringResource(Res.string.body_analytics_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

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
