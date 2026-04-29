package io.github.b150005.knitnote.ui.pullrequest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestComment
import io.github.b150005.knitnote.domain.model.PullRequestStatus
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_close_pr
import io.github.b150005.knitnote.generated.resources.action_merge_pr
import io.github.b150005.knitnote.generated.resources.action_post_comment
import io.github.b150005.knitnote.generated.resources.dialog_close_pr_body
import io.github.b150005.knitnote.generated.resources.dialog_close_pr_title
import io.github.b150005.knitnote.generated.resources.dialog_merge_pr_body
import io.github.b150005.knitnote.generated.resources.dialog_merge_pr_title
import io.github.b150005.knitnote.generated.resources.hint_add_comment_to_pr
import io.github.b150005.knitnote.generated.resources.label_pr_authored_by
import io.github.b150005.knitnote.generated.resources.label_pr_comments
import io.github.b150005.knitnote.generated.resources.label_pr_description
import io.github.b150005.knitnote.generated.resources.label_pr_diff_preview
import io.github.b150005.knitnote.generated.resources.label_pr_status_closed
import io.github.b150005.knitnote.generated.resources.label_pr_status_merged
import io.github.b150005.knitnote.generated.resources.label_pr_status_open
import io.github.b150005.knitnote.generated.resources.label_someone
import io.github.b150005.knitnote.generated.resources.message_pr_closed_successfully
import io.github.b150005.knitnote.generated.resources.message_pr_merged_successfully
import io.github.b150005.knitnote.generated.resources.state_pr_not_found
import io.github.b150005.knitnote.generated.resources.title_pull_request_detail
import io.github.b150005.knitnote.ui.components.localized
import io.github.b150005.knitnote.ui.util.formatFull
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Phase 38.3 (ADR-014 §6 §8) — single PR detail surface.
 *
 * Layout:
 *  - TopAppBar with back + title + status chip
 *  - Description card (plain text, multi-line)
 *  - Diff preview tap-out card linking to the existing Phase 37.3 ChartDiffScreen
 *    (between commonAncestorRevisionId and sourceTipRevisionId). Inline canvas
 *    rendering is deferred — extracting `DualCanvasPanel` from ChartDiffScreen
 *    into a smaller reusable thumbnail-sized component is a meaningful refactor
 *    of the gestural Compose Canvas, same scope-cut precedent as Phase 36.4's
 *    iOS Discovery thumbnail (live render shipped on Compose, deferred on iOS).
 *    The `prDiffPreview` testTag is reserved for the future inline-render
 *    surface.
 *  - Comments section (chronological, compose box at bottom)
 *  - Action bar (target owner sees Merge[disabled] + Close; source author sees Close)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRequestDetailScreen(
    prId: String,
    onBack: () -> Unit,
    onOpenDiff: (baseRevisionId: String, targetRevisionId: String) -> Unit = { _, _ -> },
    onResolveConflicts: (prId: String) -> Unit = { _ -> },
    viewModel: PullRequestDetailViewModel = koinViewModel { parametersOf(prId) },
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val closedMessage = stringResource(Res.string.message_pr_closed_successfully)
    val mergedMessage = stringResource(Res.string.message_pr_merged_successfully)

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(PullRequestDetailEvent.ClearError)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                PullRequestDetailNavEvent.PrClosed -> snackbarHostState.showSnackbar(closedMessage)
                is PullRequestDetailNavEvent.PrMerged -> snackbarHostState.showSnackbar(mergedMessage)
                is PullRequestDetailNavEvent.NavigateToConflictResolution ->
                    onResolveConflicts(event.prId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_pull_request_detail),
                        modifier = Modifier.testTag("prDetailTitle"),
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("pullRequestDetailScreen"),
        ) {
            when {
                state.isLoading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                state.pullRequest == null ->
                    Text(
                        text = stringResource(Res.string.state_pr_not_found),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                else ->
                    DetailContent(
                        pr = state.pullRequest!!,
                        comments = state.comments,
                        users = state.users,
                        commentDraft = state.commentDraft,
                        isSendingComment = state.isSendingComment,
                        canMerge = state.canMerge,
                        canClose = state.canClose,
                        onCommentDraftChanged = {
                            viewModel.onEvent(PullRequestDetailEvent.CommentDraftChanged(it))
                        },
                        onPostComment = { viewModel.onEvent(PullRequestDetailEvent.PostComment) },
                        onCloseClick = { viewModel.onEvent(PullRequestDetailEvent.RequestClose) },
                        onMergeClick = { viewModel.onEvent(PullRequestDetailEvent.RequestMerge) },
                        onOpenDiff = onOpenDiff,
                    )
            }
        }
    }

    if (state.pendingCloseConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(PullRequestDetailEvent.DismissCloseConfirmation) },
            title = { Text(stringResource(Res.string.dialog_close_pr_title)) },
            text = { Text(stringResource(Res.string.dialog_close_pr_body)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onEvent(PullRequestDetailEvent.ConfirmClose) },
                    modifier = Modifier.testTag("confirmClosePrButton"),
                ) { Text(stringResource(Res.string.action_close_pr)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onEvent(PullRequestDetailEvent.DismissCloseConfirmation) },
                ) { Text(stringResource(Res.string.action_cancel)) }
            },
            modifier = Modifier.testTag("closePrDialog"),
        )
    }

    if (state.pendingMergeConfirmation) {
        // Phase 38.4 (ADR-014 §5 §6): merge confirm is now active — the
        // button routes through ConfirmMerge, which triggers
        // ConflictDetector.detect(); auto-clean merges call the RPC
        // directly, conflicts navigate to ChartConflictResolutionScreen via
        // the navEvent collector above.
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(PullRequestDetailEvent.DismissMergeConfirmation) },
            title = { Text(stringResource(Res.string.dialog_merge_pr_title)) },
            text = { Text(stringResource(Res.string.dialog_merge_pr_body)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onEvent(PullRequestDetailEvent.ConfirmMerge) },
                    enabled = !state.isMerging,
                    modifier = Modifier.testTag("confirmMergePrButton"),
                ) { Text(stringResource(Res.string.action_merge_pr)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onEvent(PullRequestDetailEvent.DismissMergeConfirmation) },
                ) { Text(stringResource(Res.string.action_cancel)) }
            },
            modifier = Modifier.testTag("mergePrDialog"),
        )
    }
}

@Composable
private fun DetailContent(
    pr: PullRequest,
    comments: List<PullRequestComment>,
    users: Map<String, io.github.b150005.knitnote.domain.model.User>,
    commentDraft: String,
    isSendingComment: Boolean,
    canMerge: Boolean,
    canClose: Boolean,
    onCommentDraftChanged: (String) -> Unit,
    onPostComment: () -> Unit,
    onCloseClick: () -> Unit,
    onMergeClick: () -> Unit,
    onOpenDiff: (baseRevisionId: String, targetRevisionId: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = true),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { TitleAndStatus(pr, users) }
            item { DescriptionCard(pr) }
            item { DiffPreviewCard(pr, onOpenDiff) }
            item {
                Text(
                    text = stringResource(Res.string.label_pr_comments),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(comments, key = { it.id }) { comment ->
                CommentRow(comment, users)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        if (pr.status == PullRequestStatus.OPEN) {
            CommentComposeBox(
                draft = commentDraft,
                isSending = isSendingComment,
                onChange = onCommentDraftChanged,
                onPost = onPostComment,
            )
        }

        if (canMerge || canClose) {
            ActionBar(
                canMerge = canMerge,
                canClose = canClose,
                onMerge = onMergeClick,
                onClose = onCloseClick,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitleAndStatus(
    pr: PullRequest,
    users: Map<String, io.github.b150005.knitnote.domain.model.User>,
) {
    val authorName = pr.authorId?.let { users[it]?.displayName }
    val resolvedName = authorName ?: stringResource(Res.string.label_someone)
    val authorLine = stringResource(Res.string.label_pr_authored_by, resolvedName)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pr.title,
                style = MaterialTheme.typography.titleLarge,
                modifier =
                    Modifier
                        .weight(1f, fill = true)
                        .testTag("prTitleLabel"),
            )
            AssistChip(
                onClick = {},
                label = { Text(stringResource(pr.status.labelKey)) },
                enabled = false,
                colors =
                    AssistChipDefaults.assistChipColors(
                        disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                modifier = Modifier.testTag("prStatusChip"),
            )
        }
        Text(
            text = authorLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = pr.createdAt.formatFull(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DescriptionCard(pr: PullRequest) {
    val description = pr.description?.takeIf { it.isNotBlank() } ?: return
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.label_pr_description),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("prDescriptionLabel"),
            )
        }
    }
}

@Composable
private fun DiffPreviewCard(
    pr: PullRequest,
    onOpenDiff: (baseRevisionId: String, targetRevisionId: String) -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("prDiffPreview"),
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.label_pr_diff_preview),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onOpenDiff(pr.commonAncestorRevisionId, pr.sourceTipRevisionId) },
                modifier = Modifier.testTag("openDiffButton"),
            ) {
                Text(stringResource(Res.string.label_pr_diff_preview))
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: PullRequestComment,
    users: Map<String, io.github.b150005.knitnote.domain.model.User>,
) {
    val authorName = comment.authorId?.let { users[it]?.displayName }
    val resolvedName = authorName ?: stringResource(Res.string.label_someone)

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("commentRow_${comment.id}"),
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = resolvedName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = true),
                )
                Text(
                    text = comment.createdAt.formatFull(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = comment.body,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CommentComposeBox(
    draft: String,
    isSending: Boolean,
    onChange: (String) -> Unit,
    onPost: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onChange,
                placeholder = { Text(stringResource(Res.string.hint_add_comment_to_pr)) },
                modifier =
                    Modifier
                        .weight(1f, fill = true)
                        .testTag("commentInputField"),
                enabled = !isSending,
                maxLines = 4,
            )
            Button(
                onClick = onPost,
                enabled = !isSending && draft.isNotBlank(),
                modifier = Modifier.testTag("postCommentButton"),
            ) {
                Text(stringResource(Res.string.action_post_comment))
            }
        }
    }
}

@Composable
private fun ActionBar(
    canMerge: Boolean,
    canClose: Boolean,
    onMerge: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.End),
    ) {
        if (canClose) {
            TextButton(
                onClick = onClose,
                modifier = Modifier.testTag("closeButton"),
            ) { Text(stringResource(Res.string.action_close_pr)) }
        }
        if (canMerge) {
            // Phase 38.4: merge active. Tap → confirmation dialog → ConfirmMerge
            // → conflict detection → either direct RPC (auto-clean) or push
            // ChartConflictResolutionScreen (interactive resolution).
            Button(
                onClick = onMerge,
                modifier = Modifier.testTag("mergeButton"),
            ) { Text(stringResource(Res.string.action_merge_pr)) }
        }
    }
}

private val PullRequestStatus.labelKey: StringResource
    get() =
        when (this) {
            PullRequestStatus.OPEN -> Res.string.label_pr_status_open
            PullRequestStatus.MERGED -> Res.string.label_pr_status_merged
            PullRequestStatus.CLOSED -> Res.string.label_pr_status_closed
        }
