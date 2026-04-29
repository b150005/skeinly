package io.github.b150005.knitnote.ui.comments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.Comment
import io.github.b150005.knitnote.domain.model.CommentTargetType
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_delete
import io.github.b150005.knitnote.generated.resources.action_delete_comment
import io.github.b150005.knitnote.generated.resources.action_send_comment
import io.github.b150005.knitnote.generated.resources.dialog_delete_comment_body
import io.github.b150005.knitnote.generated.resources.dialog_delete_comment_title
import io.github.b150005.knitnote.generated.resources.hint_add_comment
import io.github.b150005.knitnote.generated.resources.label_comments_section
import io.github.b150005.knitnote.generated.resources.label_someone
import io.github.b150005.knitnote.generated.resources.state_no_comments
import io.github.b150005.knitnote.ui.util.formatShort
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CommentSection(
    targetType: CommentTargetType,
    targetId: String,
    currentUserId: String?,
    onError: (ErrorMessage) -> Unit,
    viewModel: CommentSectionViewModel =
        koinViewModel(
            key = "$targetType:$targetId",
        ) { parametersOf(targetType, targetId) },
) {
    val state by viewModel.state.collectAsState()
    var commentToDelete by remember { mutableStateOf<String?>(null) }

    commentToDelete?.let { commentId ->
        AlertDialog(
            onDismissRequest = { commentToDelete = null },
            title = { Text(stringResource(Res.string.dialog_delete_comment_title)) },
            text = { Text(stringResource(Res.string.dialog_delete_comment_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onEvent(CommentSectionEvent.DeleteComment(commentId))
                    commentToDelete = null
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { commentToDelete = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            onError(it)
            viewModel.onEvent(CommentSectionEvent.ClearError)
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("commentSection"),
    ) {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.label_comments_section),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
        }

        // Comment input
        if (currentUserId != null) {
            CommentInput(
                isSending = state.isSending,
                onSend = { body ->
                    viewModel.onEvent(CommentSectionEvent.PostComment(body))
                },
            )
        }

        // Comments list
        if (state.comments.isEmpty() && !state.isLoading) {
            Text(
                text = stringResource(Res.string.state_no_comments),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.comments.forEach { comment ->
                CommentItem(
                    comment = comment,
                    author = state.authors[comment.authorId],
                    isOwn = comment.authorId == currentUserId,
                    onDelete = { commentToDelete = comment.id },
                )
            }
        }
    }
}

@Composable
private fun CommentInput(
    isSending: Boolean,
    onSend: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(Res.string.hint_add_comment)) },
            modifier = Modifier.weight(1f),
            singleLine = false,
            maxLines = 3,
            enabled = !isSending,
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (isSending) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = text.isNotBlank(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(Res.string.action_send_comment),
                )
            }
        }
    }
}

@Composable
private fun CommentItem(
    comment: Comment,
    author: User?,
    isOwn: Boolean,
    onDelete: () -> Unit,
) {
    val timestamp = remember(comment.createdAt) { comment.createdAt.formatShort() }
    // Null-author fallback reuses `label_someone` from 33.1.7 ActivityFeed —
    // same "user-record lookup failed" semantic, unified across platforms.
    val authorName = author?.displayName ?: stringResource(Res.string.label_someone)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = authorName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isOwn) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.action_delete_comment),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Text(
            text = comment.body,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
