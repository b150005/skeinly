package io.github.b150005.skeinly.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_friend_invite_done
import io.github.b150005.skeinly.generated.resources.action_friend_invite_redeem
import io.github.b150005.skeinly.generated.resources.action_retry
import io.github.b150005.skeinly.generated.resources.body_friend_invite_code_helper
import io.github.b150005.skeinly.generated.resources.label_friend_invite_code_field
import io.github.b150005.skeinly.generated.resources.state_connections_unknown_user
import io.github.b150005.skeinly.generated.resources.state_friend_invite_success
import io.github.b150005.skeinly.generated.resources.state_friend_invite_token_failed
import io.github.b150005.skeinly.generated.resources.title_friend_invite_confirm
import io.github.b150005.skeinly.ui.components.LiveSnackbarHost
import io.github.b150005.skeinly.ui.components.localized
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Phase 25.4 (ADR-024 §Phase 25.4) — friend-invite redemption screen.
 *
 * One screen, two modes (selected by [token] nullability, threaded
 * into [FriendInviteConfirmViewModel] via Koin parametric injection):
 *
 * - **Token mode** ([token] != null): reached via a Universal Link /
 *   App Link tap. The VM auto-redeems on init; this screen renders
 *   only a progress spinner → success / error. No code-entry form
 *   (the user already expressed intent by tapping the link).
 * - **Code mode** ([token] == null): reached from Connections →
 *   "Add by code". Renders a code-entry `OutlinedTextField` + submit
 *   button gated on [FriendInviteConfirmState.submitEnabled].
 *
 * On success both modes converge to the same confirmation copy
 * ("You and X are now friends") with a single "Done" CTA that pops
 * back to Connections (per the Phase 25.4 agent-team decision — the
 * ADR's "View profile" CTA was scope-cut because no arbitrary-user
 * profile-view screen exists; deferred to post-beta backlog).
 *
 * Errors surface via the snackbar (generic message — invite-specific
 * error i18n is part of the project-wide ViewModel error-i18n Tech
 * Debt); the user can retry without losing their typed code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendInviteConfirmScreen(
    token: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: FriendInviteConfirmViewModel =
        koinViewModel(
            // Key by token so a fresh deep-link tap (different token)
            // re-creates the VM instead of reusing a prior instance
            // that already consumed its token. Null-token (code mode)
            // collapses to a single stable key.
            key = "friend-invite-${token ?: "code"}",
            parameters = { parametersOf(token) },
        ),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(FriendInviteConfirmEvent.ClearError)
        }
    }

    Scaffold(
        modifier = Modifier.testTag("friendInviteConfirmScreen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_friend_invite_confirm),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("friendInviteBackButton"),
                    ) {
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
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
        ) {
            val success = state.success
            if (success != null) {
                SuccessContent(
                    inviterDisplayName = success.inviterDisplayName,
                    onDone = onDone,
                )
            } else {
                // Exhaustive on mode (no `else`) so a future third mode
                // fails to compile here rather than silently falling
                // through to the code-entry form.
                when (state.mode) {
                    FriendInviteConfirmMode.Token ->
                        // Token mode auto-redeems on init (no form).
                        // While the RPC is in flight → spinner. If it
                        // FAILED (not redeeming, no success yet) → an
                        // explicit retry surface so a transient network
                        // failure on a deep-link tap isn't a dead end
                        // (the only prior escape was the back button,
                        // which exits the flow entirely).
                        if (state.isRedeeming) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.testTag("friendInviteTokenSpinner"),
                                )
                            }
                        } else {
                            TokenFailedContent(
                                onRetry = {
                                    viewModel.onEvent(FriendInviteConfirmEvent.Redeem)
                                },
                            )
                        }
                    FriendInviteConfirmMode.Code ->
                        CodeEntryContent(
                            state = state,
                            onCodeChange = {
                                viewModel.onEvent(FriendInviteConfirmEvent.UpdateCode(it))
                            },
                            onSubmit = {
                                viewModel.onEvent(FriendInviteConfirmEvent.Redeem)
                            },
                        )
                }
            }
        }
    }
}

@Composable
private fun CodeEntryContent(
    state: FriendInviteConfirmState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.body_friend_invite_code_helper),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.codeInput,
            onValueChange = onCodeChange,
            label = { Text(stringResource(Res.string.label_friend_invite_code_field)) },
            singleLine = true,
            enabled = !state.isRedeeming,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("friendInviteCodeField"),
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onSubmit,
            enabled = state.submitEnabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("friendInviteRedeemButton"),
        ) {
            if (state.isRedeeming) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text(stringResource(Res.string.action_friend_invite_redeem))
            }
        }
    }
}

@Composable
private fun TokenFailedContent(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.state_friend_invite_token_failed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("friendInviteTokenFailedText"),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("friendInviteRetryButton"),
        ) {
            Text(stringResource(Res.string.action_retry))
        }
    }
}

@Composable
private fun SuccessContent(
    inviterDisplayName: String?,
    onDone: () -> Unit,
) {
    val name =
        inviterDisplayName
            ?: stringResource(Res.string.state_connections_unknown_user)
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.state_friend_invite_success, name),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .testTag("friendInviteSuccessText")
                    .semantics { heading() },
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onDone,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("friendInviteDoneButton"),
        ) {
            Text(stringResource(Res.string.action_friend_invite_done))
        }
    }
}
