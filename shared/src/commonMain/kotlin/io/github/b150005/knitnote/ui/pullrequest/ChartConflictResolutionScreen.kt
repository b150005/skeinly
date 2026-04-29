package io.github.b150005.knitnote.ui.pullrequest

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import io.github.b150005.knitnote.domain.chart.CellConflict
import io.github.b150005.knitnote.domain.chart.CellCoordinate
import io.github.b150005.knitnote.domain.chart.LayerConflict
import io.github.b150005.knitnote.domain.usecase.ConflictResolution
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_apply_and_merge
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_keep_mine
import io.github.b150005.knitnote.generated.resources.action_skip_conflict
import io.github.b150005.knitnote.generated.resources.action_take_theirs
import io.github.b150005.knitnote.generated.resources.label_conflict_cell
import io.github.b150005.knitnote.generated.resources.label_conflict_layer
import io.github.b150005.knitnote.generated.resources.label_conflict_summary
import io.github.b150005.knitnote.generated.resources.message_pr_merged_successfully
import io.github.b150005.knitnote.generated.resources.state_all_conflicts_resolved
import io.github.b150005.knitnote.generated.resources.title_resolve_conflicts
import io.github.b150005.knitnote.ui.components.localized
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Phase 38.4 (ADR-014 §6) — three-pane conflict resolver. Reached from
 * [PullRequestDetailScreen]'s merge button when [ConflictDetector.detect]
 * returns at least one conflict; auto-clean merges bypass this screen and
 * invoke `MergePullRequestUseCase` directly.
 *
 * Layout (phone-first stack — three-pane canvas is post-MVP polish):
 *  - TopAppBar with back + title + summary chip ("3 conflicts to resolve")
 *  - LazyColumn of conflict rows. Each row shows the layer + (x, y) header
 *    plus a 3-button picker (Take Theirs / Keep Mine / Skip). The picked
 *    option's button highlights via the existing tonal-button hierarchy.
 *  - Bottom-bar "Apply and merge" button — disabled until every conflict
 *    has a resolution.
 *
 * The three-pane canvas (ancestor pinned center, theirs/mine side-by-side)
 * named in ADR-014 §6 is intentionally deferred: the existing
 * `DualCanvasPanel` extracted from `ChartDiffScreen` is gestural and tightly
 * bound, refactoring it for a 3-pane layout is a meaningful follow-up. The
 * row-based picker UI alone closes the merge loop end-to-end; the canvas
 * preview is a polish item that can land independently against the
 * `chartConflictResolutionScreen` testTag without changing the data layer
 * (same scope-cut precedent as Phase 36.4 iOS Discovery thumbnail).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartConflictResolutionScreen(
    prId: String,
    onBack: () -> Unit,
    onMerged: () -> Unit,
    viewModel: ChartConflictResolutionViewModel = koinViewModel { parametersOf(prId) },
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val mergedMessage = stringResource(Res.string.message_pr_merged_successfully)

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(ChartConflictResolutionEvent.ClearError)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is ChartConflictResolutionNavEvent.MergeApplied -> {
                    snackbarHostState.showSnackbar(mergedMessage)
                    onMerged()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_resolve_conflicts),
                        modifier = Modifier.testTag("conflictResolutionTitle"),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("backButton"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
                actions = {
                    val report = state.report
                    if (report != null) {
                        val total = report.conflicts.size + report.layerConflicts.size
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text =
                                        if (total == 0) {
                                            stringResource(Res.string.state_all_conflicts_resolved)
                                        } else {
                                            stringResource(Res.string.label_conflict_summary, total)
                                        },
                                )
                            },
                            enabled = false,
                            colors =
                                AssistChipDefaults.assistChipColors(
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            modifier = Modifier.testTag("conflictSummaryChip"),
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
                    .testTag("chartConflictResolutionScreen"),
        ) {
            when {
                state.isLoading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                state.report == null ->
                    Text(
                        text = state.error?.localized() ?: "",
                        modifier = Modifier.align(Alignment.Center),
                    )

                else ->
                    ResolutionContent(
                        cellConflicts = state.report!!.conflicts,
                        layerConflicts = state.report!!.layerConflicts,
                        cellResolutions = state.cellResolutions,
                        layerResolutions = state.layerResolutions,
                        canApplyAndMerge = state.canApplyAndMerge,
                        isMerging = state.isMerging,
                        onPickCell = { coord, res ->
                            viewModel.onEvent(ChartConflictResolutionEvent.PickCell(coord, res))
                        },
                        onPickLayer = { layerId, res ->
                            viewModel.onEvent(ChartConflictResolutionEvent.PickLayer(layerId, res))
                        },
                        onApplyAndMerge = {
                            viewModel.onEvent(ChartConflictResolutionEvent.ApplyAndMerge)
                        },
                    )
            }
        }
    }
}

@Composable
private fun ResolutionContent(
    cellConflicts: List<CellConflict>,
    layerConflicts: List<LayerConflict>,
    cellResolutions: Map<CellCoordinate, ConflictResolution>,
    layerResolutions: Map<String, ConflictResolution>,
    canApplyAndMerge: Boolean,
    isMerging: Boolean,
    onPickCell: (CellCoordinate, ConflictResolution) -> Unit,
    onPickLayer: (String, ConflictResolution) -> Unit,
    onApplyAndMerge: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = true),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(vertical = 12.dp),
        ) {
            items(layerConflicts, key = { "layer-${it.layerId}" }) { conflict ->
                LayerConflictRow(
                    conflict = conflict,
                    pick = layerResolutions[conflict.layerId],
                    onPick = { res -> onPickLayer(conflict.layerId, res) },
                )
            }
            items(cellConflicts, key = { "cell-${it.layerId}-${it.x}-${it.y}" }) { conflict ->
                val coord = CellCoordinate(conflict.layerId, conflict.x, conflict.y)
                CellConflictRow(
                    conflict = conflict,
                    pick = cellResolutions[coord],
                    onPick = { res -> onPickCell(coord, res) },
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.End),
            ) {
                Button(
                    onClick = onApplyAndMerge,
                    enabled = canApplyAndMerge && !isMerging,
                    modifier = Modifier.testTag("applyAndMergeButton"),
                ) {
                    Text(stringResource(Res.string.action_apply_and_merge))
                }
            }
        }
    }
}

@Composable
private fun CellConflictRow(
    conflict: CellConflict,
    pick: ConflictResolution?,
    onPick: (ConflictResolution) -> Unit,
) {
    val testTagSuffix = "${conflict.layerId}_${conflict.x}_${conflict.y}"
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("conflictRow_$testTagSuffix"),
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.label_conflict_cell, conflict.x, conflict.y),
                style = MaterialTheme.typography.titleSmall,
            )
            ResolutionPickerRow(
                pick = pick,
                onPick = onPick,
                takeTheirsTag = "takeTheirsButton_$testTagSuffix",
                keepMineTag = "keepMineButton_$testTagSuffix",
                skipTag = "skipButton_$testTagSuffix",
            )
        }
    }
}

@Composable
private fun LayerConflictRow(
    conflict: LayerConflict,
    pick: ConflictResolution?,
    onPick: (ConflictResolution) -> Unit,
) {
    // Prefer a human-readable layer name over the layerId (UUID). At least one
    // of theirs / mine is non-null on a PropertyChanged conflict; fall back to
    // ancestor and finally the bare id only if all three are absent (defensive
    // — should be unreachable for the layer-conflict path).
    val layerName =
        conflict.theirs?.name
            ?: conflict.mine?.name
            ?: conflict.ancestor?.name
            ?: conflict.layerId
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("layerConflictRow_${conflict.layerId}"),
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${stringResource(Res.string.label_conflict_layer)}: $layerName",
                style = MaterialTheme.typography.titleSmall,
            )
            ResolutionPickerRow(
                pick = pick,
                onPick = onPick,
                takeTheirsTag = "takeTheirsLayerButton_${conflict.layerId}",
                keepMineTag = "keepMineLayerButton_${conflict.layerId}",
                skipTag = "skipLayerButton_${conflict.layerId}",
            )
        }
    }
}

@Composable
private fun ResolutionPickerRow(
    pick: ConflictResolution?,
    onPick: (ConflictResolution) -> Unit,
    takeTheirsTag: String,
    keepMineTag: String,
    skipTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PickerButton(
            isSelected = pick == ConflictResolution.TAKE_THEIRS,
            onClick = { onPick(ConflictResolution.TAKE_THEIRS) },
            label = stringResource(Res.string.action_take_theirs),
            modifier = Modifier.testTag(takeTheirsTag),
        )
        PickerButton(
            isSelected = pick == ConflictResolution.KEEP_MINE,
            onClick = { onPick(ConflictResolution.KEEP_MINE) },
            label = stringResource(Res.string.action_keep_mine),
            modifier = Modifier.testTag(keepMineTag),
        )
        PickerButton(
            isSelected = pick == ConflictResolution.SKIP,
            onClick = { onPick(ConflictResolution.SKIP) },
            label = stringResource(Res.string.action_skip_conflict),
            modifier = Modifier.testTag(skipTag),
        )
    }
}

@Composable
private fun PickerButton(
    isSelected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    if (isSelected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        TextButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}
