package io.github.b150005.skeinly.ui.chart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.domain.model.ChartBranch
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_cancel
import io.github.b150005.skeinly.generated.resources.action_create_branch
import io.github.b150005.skeinly.generated.resources.action_switch
import io.github.b150005.skeinly.generated.resources.dialog_create_branch_title
import io.github.b150005.skeinly.generated.resources.label_branch_name
import io.github.b150005.skeinly.generated.resources.label_current_branch
import io.github.b150005.skeinly.generated.resources.message_switched_to_branch
import io.github.b150005.skeinly.generated.resources.state_no_branches
import io.github.b150005.skeinly.generated.resources.title_branch_picker
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Phase 37.4 (ADR-013 §7) — branch picker over `ChartViewerScreen`.
 *
 * Sheet body: live branch list + "New branch" CTA at the bottom. The row
 * matching the chart's current tip carries a `label_current_branch` chip;
 * non-current rows expose a `Switch` action button.
 *
 * On a successful switch the sheet closes (via `BranchSwitchedEvent`) and the
 * caller receives [onBranchSwitched] with the switched-to branch name so it
 * can surface a Snackbar or similar transient confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartBranchPickerSheet(
    patternId: String,
    onDismiss: () -> Unit,
    onBranchSwitched: (branchName: String) -> Unit,
    viewModel: ChartBranchPickerViewModel = koinViewModel { parametersOf(patternId) },
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.branchSwitched.collect { event ->
            onBranchSwitched(event.branchName)
            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("branchPickerSheet"),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.title_branch_picker),
                style = MaterialTheme.typography.titleLarge,
            )

            if (state.branches.isEmpty() && !state.isLoading) {
                Text(
                    text = stringResource(Res.string.state_no_branches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.branches.forEach { branch ->
                    BranchRow(
                        branch = branch,
                        isCurrent = branch.tipRevisionId == state.currentRevisionId,
                        onSwitch = { viewModel.onEvent(ChartBranchPickerEvent.SwitchBranch(branch.branchName)) },
                    )
                    HorizontalDivider()
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClick = { showCreateDialog = true },
                        ).testTag("createBranchCta")
                        .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(text = stringResource(Res.string.action_create_branch))
            }
        }
    }

    if (showCreateDialog) {
        CreateBranchDialog(
            onConfirm = { name ->
                viewModel.onEvent(ChartBranchPickerEvent.CreateBranch(name))
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

@Composable
private fun BranchRow(
    branch: ChartBranch,
    isCurrent: Boolean,
    onSwitch: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("branchRow_${branch.branchName}")
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = branch.branchName,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (isCurrent) {
            AssistChip(
                onClick = {},
                label = { Text(stringResource(Res.string.label_current_branch)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.height(AssistChipDefaults.IconSize),
                    )
                },
                modifier = Modifier.testTag("currentBranchChip_${branch.branchName}"),
            )
        } else {
            TextButton(
                onClick = onSwitch,
                modifier = Modifier.testTag("switchBranchButton_${branch.branchName}"),
            ) {
                Text(stringResource(Res.string.action_switch))
            }
        }
    }
}

@Composable
private fun CreateBranchDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("createBranchDialog"),
        title = { Text(stringResource(Res.string.dialog_create_branch_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.label_branch_name)) },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("branchNameInput"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("confirmCreateBranchButton"),
            ) {
                Text(stringResource(Res.string.action_create_branch))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

/**
 * Helper for callers that want the localized "Switched to X" string for a
 * Snackbar host. SwiftUI mirror inlines `String(format:)` with the same key.
 */
@Composable
fun rememberSwitchedSnackbar(): (String) -> String {
    val template = stringResource(Res.string.message_switched_to_branch)
    return remember(template) {
        { branchName: String -> template.replace("%1\$s", branchName) }
    }
}
