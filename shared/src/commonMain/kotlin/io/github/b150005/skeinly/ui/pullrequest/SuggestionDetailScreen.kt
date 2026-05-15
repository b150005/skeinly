package io.github.b150005.skeinly.ui.pullrequest

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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionComment
import io.github.b150005.skeinly.domain.model.SuggestionStatus
import io.github.b150005.skeinly.domain.model.UgcTargetType
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_apply_suggestion
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_block_user
import io.github.b150005.skeinly.generated.resources.action_cancel
import io.github.b150005.skeinly.generated.resources.action_close_suggestion
import io.github.b150005.skeinly.generated.resources.action_more_options
import io.github.b150005.skeinly.generated.resources.action_post_comment
import io.github.b150005.skeinly.generated.resources.action_report_content
import io.github.b150005.skeinly.generated.resources.dialog_apply_suggestion_body
import io.github.b150005.skeinly.generated.resources.dialog_apply_suggestion_title
import io.github.b150005.skeinly.generated.resources.dialog_close_suggestion_body
import io.github.b150005.skeinly.generated.resources.dialog_close_suggestion_title
import io.github.b150005.skeinly.generated.resources.hint_add_comment_to_suggestion
import io.github.b150005.skeinly.generated.resources.label_someone
import io.github.b150005.skeinly.generated.resources.label_suggestion_authored_by
import io.github.b150005.skeinly.generated.resources.label_suggestion_changes_preview
import io.github.b150005.skeinly.generated.resources.label_suggestion_comments
import io.github.b150005.skeinly.generated.resources.label_suggestion_description
import io.github.b150005.skeinly.generated.resources.label_suggestion_status_applied
import io.github.b150005.skeinly.generated.resources.label_suggestion_status_closed
import io.github.b150005.skeinly.generated.resources.label_suggestion_status_open
import io.github.b150005.skeinly.generated.resources.message_suggestion_applied_successfully
import io.github.b150005.skeinly.generated.resources.message_suggestion_closed_successfully
import io.github.b150005.skeinly.generated.resources.state_suggestion_not_found
import io.github.b150005.skeinly.generated.resources.title_suggestion_detail
import io.github.b150005.skeinly.notifications.NotificationPromptTrigger
import io.github.b150005.skeinly.ui.components.LiveSnackbarHost
import io.github.b150005.skeinly.ui.components.localized
import io.github.b150005.skeinly.ui.moderation.BlockUserConfirmDialog
import io.github.b150005.skeinly.ui.moderation.ReportContentDialog
import io.github.b150005.skeinly.ui.notifications.NotificationPermissionEvent
import io.github.b150005.skeinly.ui.notifications.NotificationPermissionExplainerDialog
import io.github.b150005.skeinly.ui.notifications.NotificationPermissionViewModel
import io.github.b150005.skeinly.ui.util.formatFull
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
 *  - Diff preview tap-out card linking to the existing Phase 37.3 ChartComparisonScreen
 *    (between commonAncestorRevisionId and sourceTipRevisionId). Inline canvas
 *    rendering is deferred — extracting `DualCanvasPanel` from ChartComparisonScreen
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
fun SuggestionDetailScreen(
    prId: String,
    onBack: () -> Unit,
    onOpenDiff: (baseRevisionId: String, targetRevisionId: String) -> Unit = { _, _ -> },
    onResolveConflicts: (prId: String) -> Unit = { _ -> },
    viewModel: SuggestionDetailViewModel = koinViewModel { parametersOf(prId) },
    // Phase 24.2c-3 (ADR-017 §3.6) — drives the in-app pre-permission
    // explainer dispatched on first PR detail open + first comment post.
    notificationViewModel: NotificationPermissionViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val notificationState by notificationViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Phase 39 (ADR-021 §D4) — UGC overflow: Report this suggestion +
    // Block the author. Apple Guideline 1.2 requires both reachable on
    // any UGC surface; Suggestion threads carry author-written bodies.
    var overflowExpanded by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }
    var blockOpen by remember { mutableStateOf(false) }
    val closedMessage = stringResource(Res.string.message_suggestion_closed_successfully)
    val mergedMessage = stringResource(Res.string.message_suggestion_applied_successfully)

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(SuggestionDetailEvent.ClearError)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                SuggestionDetailNavEvent.PrClosed -> snackbarHostState.showSnackbar(closedMessage)
                is SuggestionDetailNavEvent.PrMerged -> snackbarHostState.showSnackbar(mergedMessage)
                is SuggestionDetailNavEvent.NavigateToConflictResolution ->
                    onResolveConflicts(event.prId)
                SuggestionDetailNavEvent.CommentPosted ->
                    notificationViewModel.onEvent(
                        NotificationPermissionEvent.TriggerEncountered(
                            NotificationPromptTrigger.PR_COMMENT_POSTED,
                        ),
                    )
            }
        }
    }

    // Phase 24.2c-3 (ADR-017 §3.6) — first PR detail open is the second
    // collaboration moment trigger after the Incoming list. Keyed on
    // `state.suggestion != null` so the dispatch fires after `loadInitial`
    // resolves rather than during the loading-spinner phase. The prompter's
    // global "asked" bit makes a hypothetical refire idempotent.
    LaunchedEffect(state.suggestion != null) {
        if (state.suggestion != null) {
            notificationViewModel.onEvent(
                NotificationPermissionEvent.TriggerEncountered(
                    NotificationPromptTrigger.PR_DETAIL_OPENED,
                ),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_suggestion_detail),
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
                actions = {
                    // Phase 39 (ADR-021 §D4) — only meaningful once the
                    // suggestion has loaded (need its id for Report +
                    // author id for Block).
                    val loaded = state.suggestion
                    if (loaded != null) {
                        IconButton(
                            onClick = { overflowExpanded = true },
                            modifier = Modifier.testTag("suggestionOverflowButton"),
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                // a11y: announce the menu affordance, not
                                // the first item (parallels DiscoveryScreen).
                                contentDescription = stringResource(Res.string.action_more_options),
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.action_report_content)) },
                                onClick = {
                                    overflowExpanded = false
                                    reportOpen = true
                                },
                                modifier = Modifier.testTag("reportSuggestionMenuItem"),
                            )
                            if (loaded.authorId != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.action_block_user)) },
                                    onClick = {
                                        overflowExpanded = false
                                        blockOpen = true
                                    },
                                    modifier = Modifier.testTag("blockAuthorMenuItem"),
                                )
                            }
                        }
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
                    .testTag("suggestionDetailScreen"),
        ) {
            when {
                state.isLoading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                state.suggestion == null ->
                    Text(
                        text = stringResource(Res.string.state_suggestion_not_found),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                else ->
                    DetailContent(
                        pr = state.suggestion!!,
                        comments = state.comments,
                        users = state.users,
                        commentDraft = state.commentDraft,
                        isSendingComment = state.isSendingComment,
                        canApply = state.canApply,
                        canClose = state.canClose,
                        onCommentDraftChanged = {
                            viewModel.onEvent(SuggestionDetailEvent.CommentDraftChanged(it))
                        },
                        onPostComment = { viewModel.onEvent(SuggestionDetailEvent.PostComment) },
                        onCloseClick = { viewModel.onEvent(SuggestionDetailEvent.RequestClose) },
                        onMergeClick = { viewModel.onEvent(SuggestionDetailEvent.RequestMerge) },
                        onOpenDiff = onOpenDiff,
                    )
            }
        }
    }

    if (state.pendingCloseConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(SuggestionDetailEvent.DismissCloseConfirmation) },
            title = { Text(stringResource(Res.string.dialog_close_suggestion_title)) },
            text = { Text(stringResource(Res.string.dialog_close_suggestion_body)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onEvent(SuggestionDetailEvent.ConfirmClose) },
                    modifier = Modifier.testTag("confirmClosePrButton"),
                ) { Text(stringResource(Res.string.action_close_suggestion)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onEvent(SuggestionDetailEvent.DismissCloseConfirmation) },
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
            onDismissRequest = { viewModel.onEvent(SuggestionDetailEvent.DismissMergeConfirmation) },
            title = { Text(stringResource(Res.string.dialog_apply_suggestion_title)) },
            text = { Text(stringResource(Res.string.dialog_apply_suggestion_body)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onEvent(SuggestionDetailEvent.ConfirmMerge) },
                    enabled = !state.isMerging,
                    modifier = Modifier.testTag("confirmMergePrButton"),
                ) { Text(stringResource(Res.string.action_apply_suggestion)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onEvent(SuggestionDetailEvent.DismissMergeConfirmation) },
                ) { Text(stringResource(Res.string.action_cancel)) }
            },
            modifier = Modifier.testTag("mergePrDialog"),
        )
    }

    // Phase 24.2c-3 (ADR-017 §3.6) — pre-permission explainer dialog,
    // mounted as a sibling to the Scaffold so it floats above. Visibility
    // is driven by the notification VM, which decides whether to show the
    // dialog based on (a) OS permission status (NOT_DETERMINED only) and
    // (b) prompter state (have we asked before?).
    NotificationPermissionExplainerDialog(
        isVisible = notificationState.isExplainerVisible,
        isRequestingPermission = notificationState.isRequestingPermission,
        onAccept = {
            notificationViewModel.onEvent(NotificationPermissionEvent.UserAcceptedExplainer)
        },
        onDismiss = {
            notificationViewModel.onEvent(NotificationPermissionEvent.UserDismissedExplainer)
        },
    )

    // Phase 39 (ADR-021 §D4) — UGC report / block dialogs. Hosted at
    // the composable root (AlertDialog overlays regardless of tree
    // position); gated on the loaded suggestion so target ids are
    // non-null.
    val loadedSuggestion = state.suggestion
    if (reportOpen && loadedSuggestion != null) {
        ReportContentDialog(
            targetType = UgcTargetType.Suggestion,
            targetId = loadedSuggestion.id,
            onSubmitted = { reportOpen = false },
            onDismiss = { reportOpen = false },
        )
    }
    val authorId = loadedSuggestion?.authorId
    if (blockOpen && authorId != null) {
        BlockUserConfirmDialog(
            blockedUserId = authorId,
            onBlocked = { blockOpen = false },
            onDismiss = { blockOpen = false },
            // Resolve the author's display name for the "Block <name>?"
            // title; null (unresolved / deleted account) falls back to
            // the generic title inside the dialog.
            blockedDisplayName = state.users[authorId]?.displayName,
        )
    }
}

@Composable
private fun DetailContent(
    pr: Suggestion,
    comments: List<SuggestionComment>,
    users: Map<String, io.github.b150005.skeinly.domain.model.User>,
    commentDraft: String,
    isSendingComment: Boolean,
    canApply: Boolean,
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
                    text = stringResource(Res.string.label_suggestion_comments),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(comments, key = { it.id }) { comment ->
                CommentRow(comment, users)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        if (pr.status == SuggestionStatus.OPEN) {
            CommentComposeBox(
                draft = commentDraft,
                isSending = isSendingComment,
                onChange = onCommentDraftChanged,
                onPost = onPostComment,
            )
        }

        if (canApply || canClose) {
            ActionBar(
                canApply = canApply,
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
    pr: Suggestion,
    users: Map<String, io.github.b150005.skeinly.domain.model.User>,
) {
    val authorName = pr.authorId?.let { users[it]?.displayName }
    val resolvedName = authorName ?: stringResource(Res.string.label_someone)
    val authorLine = stringResource(Res.string.label_suggestion_authored_by, resolvedName)

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
private fun DescriptionCard(pr: Suggestion) {
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
                text = stringResource(Res.string.label_suggestion_description),
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
    pr: Suggestion,
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
                text = stringResource(Res.string.label_suggestion_changes_preview),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onOpenDiff(pr.commonAncestorRevisionId, pr.sourceTipRevisionId) },
                modifier = Modifier.testTag("openDiffButton"),
            ) {
                Text(stringResource(Res.string.label_suggestion_changes_preview))
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: SuggestionComment,
    users: Map<String, io.github.b150005.skeinly.domain.model.User>,
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
                label = { Text(stringResource(Res.string.hint_add_comment_to_suggestion)) },
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
    canApply: Boolean,
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
            ) { Text(stringResource(Res.string.action_close_suggestion)) }
        }
        if (canApply) {
            // Phase 38.4: merge active. Tap → confirmation dialog → ConfirmMerge
            // → conflict detection → either direct RPC (auto-clean) or push
            // ChartConflictResolutionScreen (interactive resolution).
            Button(
                onClick = onMerge,
                modifier = Modifier.testTag("mergeButton"),
            ) { Text(stringResource(Res.string.action_apply_suggestion)) }
        }
    }
}

private val SuggestionStatus.labelKey: StringResource
    get() =
        when (this) {
            SuggestionStatus.OPEN -> Res.string.label_suggestion_status_open
            SuggestionStatus.APPLIED -> Res.string.label_suggestion_status_applied
            SuggestionStatus.CLOSED -> Res.string.label_suggestion_status_closed
        }
