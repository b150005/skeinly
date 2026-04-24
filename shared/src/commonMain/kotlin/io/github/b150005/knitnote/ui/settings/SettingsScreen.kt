package io.github.b150005.knitnote.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_delete
import io.github.b150005.knitnote.generated.resources.action_delete_account
import io.github.b150005.knitnote.generated.resources.action_sign_out
import io.github.b150005.knitnote.generated.resources.body_delete_account_warning
import io.github.b150005.knitnote.generated.resources.dialog_delete_account_body
import io.github.b150005.knitnote.generated.resources.dialog_delete_account_title
import io.github.b150005.knitnote.generated.resources.label_account_section
import io.github.b150005.knitnote.generated.resources.label_danger_zone
import io.github.b150005.knitnote.generated.resources.state_deleting_account
import io.github.b150005.knitnote.generated.resources.title_settings
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

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
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(SettingsEvent.ClearError)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.accountDeleted.collect {
            onAccountDeleted()
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
