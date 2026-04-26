package io.github.b150005.knitnote.ui.pullrequest

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
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestStatus
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.label_filter_incoming
import io.github.b150005.knitnote.generated.resources.label_filter_outgoing
import io.github.b150005.knitnote.generated.resources.label_pr_authored_by
import io.github.b150005.knitnote.generated.resources.label_pr_status_closed
import io.github.b150005.knitnote.generated.resources.label_pr_status_merged
import io.github.b150005.knitnote.generated.resources.label_pr_status_open
import io.github.b150005.knitnote.generated.resources.label_someone
import io.github.b150005.knitnote.generated.resources.state_no_pull_requests
import io.github.b150005.knitnote.generated.resources.state_no_pull_requests_body
import io.github.b150005.knitnote.generated.resources.title_pull_requests
import io.github.b150005.knitnote.ui.util.formatFull
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Phase 38.2 (ADR-014 §6 §8) — read-only pull-request list.
 *
 * Top bar with back nav + chip row toggling between Incoming / Outgoing.
 * Tap a row → no-op for 38.2; Phase 38.3 routes to `PullRequestDetailScreen`.
 *
 * Rows are grouped by status: OPEN at top, MERGED next, CLOSED last. Within a
 * group the repository's `created_at DESC` ordering is preserved (newest-first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRequestListScreen(
    defaultFilter: PullRequestFilter,
    onBack: () -> Unit,
    onPullRequestClick: (String) -> Unit = {},
    viewModel: PullRequestListViewModel = koinViewModel { parametersOf(defaultFilter) },
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(PullRequestListEvent.ClearError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_pull_requests)) },
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
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("pullRequestListScreen"),
        ) {
            FilterChipRow(
                current = state.filter,
                onSelect = { viewModel.onEvent(PullRequestListEvent.SelectFilter(it)) },
            )
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                    state.pullRequests.isEmpty() ->
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(Res.string.state_no_pull_requests),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(Res.string.state_no_pull_requests_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                    else -> {
                        // Group by status — OPEN first, then MERGED, then CLOSED.
                        // `groupBy` preserves original list order within each group,
                        // which carries the repository's `created_at DESC` semantics.
                        val grouped = state.pullRequests.groupBy { it.status }
                        val ordered =
                            STATUS_DISPLAY_ORDER.flatMap { status ->
                                grouped[status].orEmpty()
                            }
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(ordered, key = { it.id }) { pr ->
                                PullRequestRow(
                                    pullRequest = pr,
                                    authorName = pr.authorId?.let { state.users[it]?.displayName },
                                    onClick = { onPullRequestClick(pr.id) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

private val STATUS_DISPLAY_ORDER =
    listOf(
        PullRequestStatus.OPEN,
        PullRequestStatus.MERGED,
        PullRequestStatus.CLOSED,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    current: PullRequestFilter,
    onSelect: (PullRequestFilter) -> Unit,
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
                selected = current == PullRequestFilter.INCOMING,
                onClick = { onSelect(PullRequestFilter.INCOMING) },
                label = { Text(stringResource(Res.string.label_filter_incoming)) },
                modifier = Modifier.testTag("incomingFilterChip"),
            )
            FilterChip(
                selected = current == PullRequestFilter.OUTGOING,
                onClick = { onSelect(PullRequestFilter.OUTGOING) },
                label = { Text(stringResource(Res.string.label_filter_outgoing)) },
                modifier = Modifier.testTag("outgoingFilterChip"),
            )
        }
    }
}

@Composable
private fun PullRequestRow(
    pullRequest: PullRequest,
    authorName: String?,
    onClick: () -> Unit,
) {
    // Fall back to `label_someone` when:
    //  - the user-record lookup missed (e.g. cold-launch with empty UserRepository
    //    cache, account deleted via ON DELETE SET NULL), OR
    //  - the PR row's authorId is itself null (Postgres ON DELETE SET NULL on
    //    profiles cascade — see PullRequest.authorId KDoc).
    val resolvedName = authorName ?: stringResource(Res.string.label_someone)
    val authorLine = stringResource(Res.string.label_pr_authored_by, resolvedName)
    val timestamp = pullRequest.createdAt.formatFull()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .testTag("prRow_${pullRequest.id}")
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pullRequest.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .weight(1f, fill = true)
                        .testTag("prTitleLabel_${pullRequest.id}"),
            )
            StatusChip(status = pullRequest.status, prId = pullRequest.id)
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
    status: PullRequestStatus,
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
 * Maps the closed [PullRequestStatus] enum to its `label_pr_status_*` resource.
 * Exhaustive `when` per the project's sealed-type discipline.
 */
private val PullRequestStatus.labelKey: StringResource
    get() =
        when (this) {
            PullRequestStatus.OPEN -> Res.string.label_pr_status_open
            PullRequestStatus.MERGED -> Res.string.label_pr_status_merged
            PullRequestStatus.CLOSED -> Res.string.label_pr_status_closed
        }
