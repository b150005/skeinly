package io.github.b150005.knitnote.ui.chart

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.ChartRevision
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_restore_revision
import io.github.b150005.knitnote.generated.resources.dialog_restore_revision_body
import io.github.b150005.knitnote.generated.resources.dialog_restore_revision_title
import io.github.b150005.knitnote.generated.resources.label_auto_save
import io.github.b150005.knitnote.generated.resources.label_initial_commit
import io.github.b150005.knitnote.generated.resources.state_no_chart_history
import io.github.b150005.knitnote.generated.resources.state_no_chart_history_body
import io.github.b150005.knitnote.generated.resources.title_chart_history
import io.github.b150005.knitnote.ui.components.localized
import io.github.b150005.knitnote.ui.util.formatFull
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Phase 37.2 (ADR-013 §6) — newest-first commit history for a chart.
 *
 * `onRevisionClick(baseRevisionId, targetRevisionId)` routes to `ChartDiffScreen`.
 * The ViewModel resolves `baseRevisionId` from the tapped row's
 * `parentRevisionId` so this callback is forwarded straight through with no
 * lookup at the screen layer; iOS mirror reuses the identical contract.
 *
 * Long-press → "Restore as new commit" is reserved for Phase 37.4 per ADR-013
 * §6 ("the sub-slice that ships history list (37.2) leaves long-press unwired").
 * The corresponding i18n key `action_restore_revision` ships in 37.2 for
 * forward-compat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartHistoryScreen(
    patternId: String,
    onBack: () -> Unit,
    onRevisionClick: (baseRevisionId: String?, targetRevisionId: String) -> Unit = { _, _ -> },
    viewModel: ChartHistoryViewModel = koinViewModel { parametersOf(patternId) },
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.revisionTaps.collect { target ->
            onRevisionClick(target.baseRevisionId, target.targetRevisionId)
        }
    }

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(ChartHistoryEvent.ClearError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_chart_history)) },
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
                    .testTag("chartHistoryScreen"),
        ) {
            when {
                state.isLoading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                state.revisions.isEmpty() ->
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(Res.string.state_no_chart_history),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(Res.string.state_no_chart_history_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.revisions, key = { it.revisionId }) { revision ->
                            RevisionRow(
                                revision = revision,
                                onClick = { viewModel.onEvent(ChartHistoryEvent.TapRevision(revision.revisionId)) },
                                onLongClick = {
                                    viewModel.onEvent(ChartHistoryEvent.LongPressRevision(revision.revisionId))
                                },
                            )
                            HorizontalDivider()
                        }
                    }
            }
        }
    }

    state.pendingRestoreRevision?.let {
        RestoreRevisionDialog(
            onConfirm = { viewModel.onEvent(ChartHistoryEvent.ConfirmRestore) },
            onDismiss = { viewModel.onEvent(ChartHistoryEvent.DismissRestore) },
        )
    }
}

@Composable
private fun RestoreRevisionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("restoreRevisionDialog"),
        title = { Text(stringResource(Res.string.dialog_restore_revision_title)) },
        text = { Text(stringResource(Res.string.dialog_restore_revision_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirmRestoreRevisionButton"),
            ) {
                Text(stringResource(Res.string.action_restore_revision))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RevisionRow(
    revision: ChartRevision,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val authorMessage = revision.commitMessage?.takeIf { it.isNotBlank() }
    val isInitialCommit = revision.parentRevisionId == null
    val headline =
        authorMessage
            ?: stringResource(
                if (isInitialCommit) Res.string.label_initial_commit else Res.string.label_auto_save,
            )
    val timestamp = revision.createdAt.formatFull()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).testTag("revisionRow_${revision.revisionId}")
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("commitMessageLabel_${revision.revisionId}"),
        )
        Text(
            text = timestamp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("revisionTimestampLabel_${revision.revisionId}"),
        )
    }
}
