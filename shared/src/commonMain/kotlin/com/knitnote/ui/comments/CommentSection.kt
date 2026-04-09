package com.knitnote.ui.comments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.knitnote.domain.model.Comment
import com.knitnote.domain.model.CommentTargetType
import com.knitnote.domain.model.User
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CommentSection(
    targetType: CommentTargetType,
    targetId: String,
    currentUserId: String?,
    onError: (String) -> Unit,
    viewModel: CommentSectionViewModel = koinViewModel(
        key = "$targetType:$targetId",
    ) { parametersOf(targetType, targetId) },
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.error) {
        state.error?.let {
            onError(it)
            viewModel.onEvent(CommentSectionEvent.ClearError)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Comments",
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
                text = "No comments yet",
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
                    onDelete = {
                        viewModel.onEvent(CommentSectionEvent.DeleteComment(comment.id))
                    },
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Add a comment...") },
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
                Icon(Icons.Default.Send, contentDescription = "Send comment")
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
    val timestamp = remember(comment.createdAt) {
        val dt = comment.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())
        val month = dt.monthNumber.toString().padStart(2, '0')
        val day = dt.dayOfMonth.toString().padStart(2, '0')
        val hour = dt.hour.toString().padStart(2, '0')
        val minute = dt.minute.toString().padStart(2, '0')
        "$month/$day $hour:$minute"
    }

    Column(
        modifier = Modifier
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
                    text = author?.displayName ?: "Unknown",
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
                        contentDescription = "Delete comment",
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
