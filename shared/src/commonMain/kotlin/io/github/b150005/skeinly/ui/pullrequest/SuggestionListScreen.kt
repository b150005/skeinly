package io.github.b150005.skeinly.ui.pullrequest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionStatus
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.label_filter_received
import io.github.b150005.skeinly.generated.resources.label_filter_sent
import io.github.b150005.skeinly.generated.resources.label_someone
import io.github.b150005.skeinly.generated.resources.label_suggestion_authored_by
import io.github.b150005.skeinly.generated.resources.label_suggestion_status_applied
import io.github.b150005.skeinly.generated.resources.label_suggestion_status_closed
import io.github.b150005.skeinly.generated.resources.label_suggestion_status_open
import io.github.b150005.skeinly.generated.resources.state_no_suggestions
import io.github.b150005.skeinly.generated.resources.state_no_suggestions_body
import io.github.b150005.skeinly.generated.resources.title_suggestions
import io.github.b150005.skeinly.notifications.NotificationPromptTrigger
import io.github.b150005.skeinly.ui.components.LiveSnackbarHost
import io.github.b150005.skeinly.ui.components.localized
import io.github.b150005.skeinly.ui.components.selectedCheckmarkIcon
import io.github.b150005.skeinly.ui.notifications.NotificationPermissionEvent
import io.github.b150005.skeinly.ui.notifications.NotificationPermissionExplainerDialog
import io.github.b150005.skeinly.ui.notifications.NotificationPermissionViewModel
import io.github.b150005.skeinly.ui.util.formatFull
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Phase 38.2 (ADR-014 §6 §8) — read-only pull-request list.
 *
 * Top bar with back nav + chip row toggling between Incoming / Outgoing.
 * Tap a row → no-op for 38.2; Phase 38.3 routes to `SuggestionDetailScreen`.
 *
 * Rows are grouped by status: OPEN at top, MERGED next, CLOSED last. Within a
 * group the repository's `created_at DESC` ordering is preserved (newest-first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionListScreen(
    defaultFilter: SuggestionFilter,
    onBack: () -> Unit,
    onSuggestionClick: (String) -> Unit = {},
    viewModel: SuggestionListViewModel = koinViewModel { parametersOf(defaultFilter) },
    // Phase 24.2c-3 (ADR-017 §3.6) — drives the in-app pre-permission
    // explainer dispatched on first encounter of a non-empty Incoming list.
    // Scoped via `koinViewModel()` so it shares lifecycle with this screen
    // (re-reads OS status on each entry; explainer-shown state persists in
    // NotificationPermissionPrompter).
    notificationViewModel: NotificationPermissionViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val notificationState by notificationViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(SuggestionListEvent.ClearError)
        }
    }

    // Phase 24.2c-3 (ADR-017 §3.6) — Incoming filter + non-empty PR list
    // is the canonical "first collaboration moment" trigger. The keyed
    // LaunchedEffect refires when either piece changes; the trigger
    // dispatch is idempotent (the prompter records "asked" globally on
    // first response) so a hypothetical refire after a SelectFilter →
    // back-to-Incoming round trip stays safe.
    LaunchedEffect(state.filter, state.suggestions.isNotEmpty()) {
        if (state.filter == SuggestionFilter.INCOMING && state.suggestions.isNotEmpty()) {
            notificationViewModel.onEvent(
                NotificationPermissionEvent.TriggerEncountered(
                    NotificationPromptTrigger.PR_LIST_INCOMING_WITH_PRS,
                ),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_suggestions),
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
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("suggestionListScreen"),
        ) {
            FilterChipRow(
                current = state.filter,
                onSelect = { viewModel.onEvent(SuggestionListEvent.SelectFilter(it)) },
            )
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                    state.suggestions.isEmpty() ->
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(Res.string.state_no_suggestions),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(Res.string.state_no_suggestions_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                    else -> {
                        // Group by status — OPEN first, then MERGED, then CLOSED.
                        // `groupBy` preserves original list order within each group,
                        // which carries the repository's `created_at DESC` semantics.
                        val grouped = state.suggestions.groupBy { it.status }
                        val ordered =
                            STATUS_DISPLAY_ORDER.flatMap { status ->
                                grouped[status].orEmpty()
                            }
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(ordered, key = { it.id }) { pr ->
                                SuggestionRow(
                                    suggestion = pr,
                                    authorName = pr.authorId?.let { state.users[it]?.displayName },
                                    onClick = { onSuggestionClick(pr.id) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    // Phase 24.2c-3 (ADR-017 §3.6) — pre-permission explainer dialog.
    // Mounted at the Scaffold sibling level so the AlertDialog floats
    // above the screen content. Visibility binds to the notification VM
    // state.
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
}

private val STATUS_DISPLAY_ORDER =
    listOf(
        SuggestionStatus.OPEN,
        SuggestionStatus.APPLIED,
        SuggestionStatus.CLOSED,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    current: SuggestionFilter,
    onSelect: (SuggestionFilter) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = current == SuggestionFilter.INCOMING,
                onClick = { onSelect(SuggestionFilter.INCOMING) },
                label = { Text(stringResource(Res.string.label_filter_received)) },
                leadingIcon = selectedCheckmarkIcon(current == SuggestionFilter.INCOMING),
                modifier = Modifier.testTag("incomingFilterChip"),
            )
            FilterChip(
                selected = current == SuggestionFilter.OUTGOING,
                onClick = { onSelect(SuggestionFilter.OUTGOING) },
                label = { Text(stringResource(Res.string.label_filter_sent)) },
                leadingIcon = selectedCheckmarkIcon(current == SuggestionFilter.OUTGOING),
                modifier = Modifier.testTag("outgoingFilterChip"),
            )
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: Suggestion,
    authorName: String?,
    onClick: () -> Unit,
) {
    // Fall back to `label_someone` when:
    //  - the user-record lookup missed (e.g. cold-launch with empty UserRepository
    //    cache, account deleted via ON DELETE SET NULL), OR
    //  - the PR row's authorId is itself null (Postgres ON DELETE SET NULL on
    //    profiles cascade — see Suggestion.authorId KDoc).
    val resolvedName = authorName ?: stringResource(Res.string.label_someone)
    val authorLine = stringResource(Res.string.label_suggestion_authored_by, resolvedName)
    val timestamp = suggestion.createdAt.formatFull()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .testTag("prRow_${suggestion.id}")
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .weight(1f, fill = true)
                        .testTag("prTitleLabel_${suggestion.id}"),
            )
            StatusChip(status = suggestion.status, prId = suggestion.id)
        }
        Text(
            text = authorLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = timestamp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusChip(
    status: SuggestionStatus,
    prId: String,
) {
    AssistChip(
        onClick = {},
        label = { Text(stringResource(status.labelKey)) },
        enabled = false,
        colors =
            AssistChipDefaults.assistChipColors(
                disabledLabelColor = MaterialTheme.colorScheme.onSurface,
            ),
        modifier = Modifier.testTag("prStatusChip_$prId"),
    )
}

/**
 * Maps the closed [SuggestionStatus] enum to its `label_pr_status_*` resource.
 * Exhaustive `when` per the project's sealed-type discipline.
 */
private val SuggestionStatus.labelKey: StringResource
    get() =
        when (this) {
            SuggestionStatus.OPEN -> Res.string.label_suggestion_status_open
            SuggestionStatus.APPLIED -> Res.string.label_suggestion_status_applied
            SuggestionStatus.CLOSED -> Res.string.label_suggestion_status_closed
        }
