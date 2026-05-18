package io.github.b150005.skeinly.ui.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_unblock
import io.github.b150005.skeinly.generated.resources.body_blocked_users_empty
import io.github.b150005.skeinly.generated.resources.state_blocked_user_unknown
import io.github.b150005.skeinly.generated.resources.title_blocked_users
import io.github.b150005.skeinly.ui.components.LiveSnackbarHost
import io.github.b150005.skeinly.ui.components.localized
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Phase 39 (ADR-021 §D4) — Settings → Privacy → Blocked Users.
 *
 * Lists the caller's blocked users (display name resolved server-side;
 * a deleted-account block falls back to the shared "Unknown user"
 * label). Each row has an Unblock affordance that removes the
 * `user_blocks` row — the migration-032 RLS amendments then stop
 * filtering that user's content. A successful unblock removes the row
 * in place + flashes a confirmation snackbar; an error surfaces via
 * the same [LiveSnackbarHost] (the VM maps it to a localized
 * [io.github.b150005.skeinly.domain.usecase.ErrorMessage]).
 *
 * Auto-loads on VM init (no explicit Load tap). Mirrors the
 * Scaffold / TopAppBar / LazyColumn / empty-state shape of
 * [io.github.b150005.skeinly.ui.connections.ConnectionsScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedUsersScreen(
    onBack: () -> Unit,
    viewModel: BlockedUsersViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val unknownUser = stringResource(Res.string.state_blocked_user_unknown)

    // Snackbar on inline error (VM-mapped ErrorMessage).
    state.error?.let { err ->
        val text = err.localized()
        LaunchedEffect(err) {
            snackbarHostState.showSnackbar(text)
            viewModel.onEvent(BlockedUsersEvent.ClearError)
        }
    }

    Scaffold(
        modifier = Modifier.testTag("blockedUsersScreen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_blocked_users),
                        modifier = Modifier.semantics { heading() },
                    )
                },
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
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            when {
                state.isLoading && state.users.isEmpty() ->
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).testTag("blockedUsersLoading"),
                    )
                state.isEmpty ->
                    Text(
                        text = stringResource(Res.string.body_blocked_users_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(32.dp)
                                .testTag("blockedUsersEmpty"),
                    )
                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        items(state.users, key = { it.userId }) { user ->
                            val name = user.displayName.ifBlank { unknownUser }
                            ListItem(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("blockedUserRow_${user.userId}"),
                                headlineContent = { Text(name) },
                                trailingContent = {
                                    if (state.isUnblocking(user.userId)) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        TextButton(
                                            onClick = {
                                                viewModel.onEvent(BlockedUsersEvent.Unblock(user.userId))
                                            },
                                            modifier = Modifier.testTag("unblockButton_${user.userId}"),
                                        ) {
                                            Text(stringResource(Res.string.action_unblock))
                                        }
                                    }
                                },
                            )
                        }
                    }
            }
        }
    }
}
