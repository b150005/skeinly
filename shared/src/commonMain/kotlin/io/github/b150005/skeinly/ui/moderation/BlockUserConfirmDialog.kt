package io.github.b150005.skeinly.ui.moderation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_block_confirm
import io.github.b150005.skeinly.generated.resources.action_cancel
import io.github.b150005.skeinly.generated.resources.body_block_user_confirm
import io.github.b150005.skeinly.generated.resources.title_block_user_confirm
import io.github.b150005.skeinly.generated.resources.title_block_user_confirm_named
import io.github.b150005.skeinly.ui.components.localized
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Phase 39 (ADR-021 §D4) — the "Block this user?" destructive
 * confirmation. Reachable from the Suggestion author chip (and a
 * future user-profile surface).
 *
 * Mounts a fresh [BlockUserViewModel] keyed on [blockedUserId] via
 * Koin `parametersOf`. On success the VM emits
 * [BlockUserNavEvent.Blocked]; forwarded as [onBlocked] so the caller
 * dismisses + flashes "User blocked". Failure stays inline so the user
 * can retry a transient failure without re-confirming.
 *
 * [onDismiss] fires on Cancel / scrim tap with no block applied.
 */
@Composable
fun BlockUserConfirmDialog(
    blockedUserId: String,
    onBlocked: () -> Unit,
    onDismiss: () -> Unit,
    /** Resolved display name of the user being blocked. When non-null
     *  the title reads "Block <name>?" (ADR-021 §D4); null falls back
     *  to the generic "Block this user?" (caller could not resolve the
     *  name, e.g. a deleted-account author). */
    blockedDisplayName: String? = null,
    viewModel: BlockUserViewModel =
        koinViewModel(
            key = "blockUser-$blockedUserId",
            parameters = { parametersOf(blockedUserId) },
        ),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                BlockUserNavEvent.Blocked -> onBlocked()
            }
        }
    }

    AlertDialog(
        modifier = Modifier.testTag("blockUserDialog"),
        onDismissRequest = { if (!state.isBlocking) onDismiss() },
        title = {
            Text(
                if (blockedDisplayName.isNullOrBlank()) {
                    stringResource(Res.string.title_block_user_confirm)
                } else {
                    stringResource(Res.string.title_block_user_confirm_named, blockedDisplayName)
                },
            )
        },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    text = stringResource(Res.string.body_block_user_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.error?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = err.localized(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("blockUserError"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.onEvent(BlockUserEvent.Confirm) },
                enabled = !state.isBlocking,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                modifier = Modifier.testTag("blockUserConfirmButton"),
            ) {
                if (state.isBlocking) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).width(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(Res.string.action_block_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isBlocking,
                modifier = Modifier.testTag("blockUserCancelButton"),
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
