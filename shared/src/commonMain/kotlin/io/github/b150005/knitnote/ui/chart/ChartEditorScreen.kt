package io.github.b150005.knitnote.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.chart.GridHitTest
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.CraftType
import io.github.b150005.knitnote.domain.model.ReadingConvention
import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_discard
import io.github.b150005.knitnote.generated.resources.action_keep_editing
import io.github.b150005.knitnote.generated.resources.action_more_options
import io.github.b150005.knitnote.generated.resources.action_ok
import io.github.b150005.knitnote.generated.resources.action_redo
import io.github.b150005.knitnote.generated.resources.action_save
import io.github.b150005.knitnote.generated.resources.action_symmetry_fold
import io.github.b150005.knitnote.generated.resources.action_symmetry_reflect
import io.github.b150005.knitnote.generated.resources.action_undo
import io.github.b150005.knitnote.generated.resources.banner_pick_reflection_axis
import io.github.b150005.knitnote.generated.resources.dialog_parameter_edit_title
import io.github.b150005.knitnote.generated.resources.dialog_parameter_enter_title
import io.github.b150005.knitnote.generated.resources.dialog_polar_extents_title
import io.github.b150005.knitnote.generated.resources.dialog_unsaved_changes_body
import io.github.b150005.knitnote.generated.resources.dialog_unsaved_changes_title
import io.github.b150005.knitnote.generated.resources.label_craft
import io.github.b150005.knitnote.generated.resources.label_craft_crochet
import io.github.b150005.knitnote.generated.resources.label_craft_knit
import io.github.b150005.knitnote.generated.resources.label_extents
import io.github.b150005.knitnote.generated.resources.label_extents_flat
import io.github.b150005.knitnote.generated.resources.label_extents_polar
import io.github.b150005.knitnote.generated.resources.label_polar_rings
import io.github.b150005.knitnote.generated.resources.label_polar_stitches_per_ring
import io.github.b150005.knitnote.generated.resources.label_reading
import io.github.b150005.knitnote.generated.resources.label_reading_crochet_flat
import io.github.b150005.knitnote.generated.resources.label_reading_knit_flat
import io.github.b150005.knitnote.generated.resources.label_reading_round
import io.github.b150005.knitnote.generated.resources.label_symmetry_section
import io.github.b150005.knitnote.generated.resources.state_empty_chart
import io.github.b150005.knitnote.generated.resources.title_edit_chart
import io.github.b150005.knitnote.ui.platform.SystemBackHandler
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartEditorScreen(
    patternId: String,
    onBack: () -> Unit,
    viewModel: ChartEditorViewModel = koinViewModel { parametersOf(patternId) },
    catalog: SymbolCatalog = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    // Phase 35.2a: polar extents picker dialog visibility. Opening it only makes
    // sense on a new chart — the menu entry is hidden when state.original != null
    // and the ViewModel rejects SetExtents on existing charts as a second line.
    var showPolarPicker by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.saved.collect { onBack() }
    }

    val attemptBack: () -> Unit = {
        when {
            // Phase 35.2c: back while axis-picking cancels the pick instead of
            // popping the screen — the banner-cancel button also dispatches this
            // but users reflexively reach for the system back gesture first.
            state.isPickingReflectionAxis ->
                viewModel.onEvent(ChartEditorEvent.CancelPickReflectionAxis)
            state.hasUnsavedChanges -> showDiscardDialog = true
            else -> onBack()
        }
    }

    // Intercept system-back (hardware / predictive-back gesture) while there are
    // unsaved edits OR an axis pick is in flight; otherwise let the navigator pop.
    SystemBackHandler(enabled = state.hasUnsavedChanges || state.isPickingReflectionAxis) {
        if (state.isPickingReflectionAxis) {
            viewModel.onEvent(ChartEditorEvent.CancelPickReflectionAxis)
        } else {
            showDiscardDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_edit_chart)) },
                navigationIcon = {
                    IconButton(onClick = attemptBack, modifier = Modifier.testTag("backButton")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.onEvent(ChartEditorEvent.Undo) },
                        enabled = state.canUndo,
                        modifier = Modifier.testTag("editorUndoButton"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(Res.string.action_undo),
                        )
                    }
                    IconButton(
                        onClick = { viewModel.onEvent(ChartEditorEvent.Redo) },
                        enabled = state.canRedo,
                        modifier = Modifier.testTag("editorRedoButton"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = stringResource(Res.string.action_redo),
                        )
                    }
                    IconButton(
                        onClick = { viewModel.onEvent(ChartEditorEvent.Save) },
                        enabled = state.hasUnsavedChanges && !state.isSaving,
                        modifier = Modifier.testTag("editorSaveButton"),
                    ) {
                        Icon(
                            Icons.Filled.Save,
                            contentDescription = stringResource(Res.string.action_save),
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.testTag("editorOverflowButton"),
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(Res.string.action_more_options),
                            )
                        }
                        ChartMetadataMenu(
                            expanded = showOverflowMenu,
                            craftType = state.draftCraftType,
                            readingConvention = state.draftReadingConvention,
                            currentExtents = state.draftExtents,
                            // Extents section only actionable on a new chart —
                            // ViewModel rejects SetExtents on existing charts.
                            canChangeExtents = state.original == null,
                            // Phase 35.2b: symmetry section visible only on polar charts.
                            showSymmetrySection = state.draftExtents is ChartExtents.Polar,
                            onDismiss = { showOverflowMenu = false },
                            onCraftSelected = {
                                viewModel.onEvent(ChartEditorEvent.SelectCraft(it))
                            },
                            onReadingSelected = {
                                viewModel.onEvent(ChartEditorEvent.SelectReading(it))
                            },
                            onFlatSelected = {
                                viewModel.onEvent(
                                    ChartEditorEvent.SetExtents(
                                        ChartExtents.Rect(minX = 0, maxX = 7, minY = 0, maxY = 7),
                                    ),
                                )
                            },
                            onPolarSelected = {
                                showPolarPicker = true
                            },
                            onRotationalSymmetry = { fold ->
                                viewModel.onEvent(ChartEditorEvent.ApplyRotationalSymmetry(fold))
                            },
                            onReflection = {
                                viewModel.onEvent(ChartEditorEvent.StartPickReflectionAxis)
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surface),
        ) {
            when {
                state.isLoading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                state.errorMessage != null ->
                    Text(
                        text = state.errorMessage.orEmpty(),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.error,
                    )

                else -> {
                    val availableCategories =
                        remember(catalog) {
                            SymbolCategory.entries.filter { catalog.listByCategory(it).isNotEmpty() }
                        }
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (state.isPickingReflectionAxis) {
                            ReflectionAxisPickBanner(
                                onCancel = {
                                    viewModel.onEvent(ChartEditorEvent.CancelPickReflectionAxis)
                                },
                            )
                        }
                        EditorCanvas(
                            extents = state.draftExtents,
                            layers = state.draftLayers,
                            catalog = catalog,
                            isPickingReflectionAxis = state.isPickingReflectionAxis,
                            onCellTap = { x, y ->
                                viewModel.onEvent(ChartEditorEvent.PlaceCell(x = x, y = y))
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        SymbolPaletteStrip(
                            selectedCategory = state.selectedCategory,
                            availableCategories = availableCategories,
                            symbols = state.paletteSymbols,
                            selectedSymbolId = state.selectedSymbolId,
                            onCategorySelected = {
                                viewModel.onEvent(ChartEditorEvent.SelectCategory(it))
                            },
                            onSymbolSelected = {
                                viewModel.onEvent(ChartEditorEvent.SelectSymbol(it))
                            },
                        )
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(Res.string.dialog_unsaved_changes_title)) },
            text = { Text(stringResource(Res.string.dialog_unsaved_changes_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onBack()
                    },
                    modifier = Modifier.testTag("discardChangesButton"),
                ) { Text(stringResource(Res.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(Res.string.action_keep_editing))
                }
            },
        )
    }

    state.pendingParameterInput?.let { pending ->
        ParameterInputDialog(
            pending = pending,
            onConfirm = { values ->
                viewModel.onEvent(ChartEditorEvent.ConfirmParameterInput(values))
            },
            onCancel = { viewModel.onEvent(ChartEditorEvent.CancelParameterInput) },
        )
    }

    if (showPolarPicker) {
        PolarExtentsDialog(
            onConfirm = { polar ->
                showPolarPicker = false
                viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            },
            onCancel = { showPolarPicker = false },
        )
    }
}

@Composable
private fun ParameterInputDialog(
    pending: PendingParameterInput,
    onConfirm: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    // Local draft — keyed by (x, y, symbolId) so re-opening the dialog for a
    // different cell starts fresh instead of inheriting the previous draft.
    val drafts =
        remember(pending.x, pending.y, pending.symbolId) {
            val initial = mutableMapOf<String, String>()
            pending.slots.forEach { slot ->
                initial[slot.key] =
                    pending.currentValues[slot.key] ?: slot.defaultValue ?: ""
            }
            mutableStateMapOf<String, String>().apply { putAll(initial) }
        }

    val titleKey: StringResource =
        if (pending.isEditingExisting) {
            Res.string.dialog_parameter_edit_title
        } else {
            Res.string.dialog_parameter_enter_title
        }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(titleKey)) },
        text = {
            Column {
                pending.slots.forEach { slot ->
                    OutlinedTextField(
                        value = drafts[slot.key].orEmpty(),
                        onValueChange = { drafts[slot.key] = it },
                        label = { Text(slot.enLabel) },
                        singleLine = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("parameterInput_${slot.key}"),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(drafts.toMap()) },
                modifier = Modifier.testTag("parameterConfirmButton"),
            ) { Text(stringResource(Res.string.action_ok)) }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("parameterCancelButton"),
            ) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

@Composable
private fun EditorCanvas(
    extents: ChartExtents,
    layers: List<io.github.b150005.knitnote.domain.model.ChartLayer>,
    catalog: SymbolCatalog,
    isPickingReflectionAxis: Boolean,
    onCellTap: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (extents) {
        is ChartExtents.Rect -> RectEditorCanvas(extents, layers, catalog, onCellTap, modifier)
        is ChartExtents.Polar ->
            PolarEditorCanvas(
                extents = extents,
                layers = layers,
                catalog = catalog,
                isPickingReflectionAxis = isPickingReflectionAxis,
                onCellTap = onCellTap,
                modifier = modifier,
            )
    }
}

@Composable
private fun RectEditorCanvas(
    extents: ChartExtents.Rect,
    layers: List<io.github.b150005.knitnote.domain.model.ChartLayer>,
    catalog: SymbolCatalog,
    onCellTap: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (extents.maxX < extents.minX || extents.maxY < extents.minY) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.state_empty_chart),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val symbolColor = MaterialTheme.colorScheme.onSurface
    val parsedPathCache =
        remember {
            mutableMapOf<String, List<io.github.b150005.knitnote.domain.symbol.PathCommand>>()
        }

    Canvas(
        modifier =
            modifier
                .testTag("editorCanvas")
                // Rekey only on extents — layers changing on every edit would
                // needlessly re-register the gesture detector. The hit-test uses
                // extents, cellSize, and origin — all derived from extents + Canvas
                // size, not from layers.
                .pointerInput(extents) {
                    detectTapGestures { offset ->
                        val layout = computeLayout(size.width.toFloat(), size.height.toFloat(), extents)
                        val hit =
                            GridHitTest.hitTest(
                                screenX = offset.x.toDouble(),
                                screenY = offset.y.toDouble(),
                                extents = extents,
                                cellSize = layout.cellSize.toDouble(),
                                originX = layout.originX.toDouble(),
                                originY = layout.originY.toDouble(),
                            )
                        hit?.let { onCellTap(it.x, it.y) }
                    }
                },
    ) {
        val layout = computeLayout(size.width, size.height, extents)
        val gridWidth = extents.maxX - extents.minX + 1
        val gridHeight = extents.maxY - extents.minY + 1

        for (gx in 0..gridWidth) {
            val x = layout.originX + gx * layout.cellSize
            drawLine(
                color = gridColor,
                start = Offset(x, layout.originY),
                end = Offset(x, layout.originY + gridHeight * layout.cellSize),
                strokeWidth = 1f,
            )
        }
        for (gy in 0..gridHeight) {
            val y = layout.originY + gy * layout.cellSize
            drawLine(
                color = gridColor,
                start = Offset(layout.originX, y),
                end = Offset(layout.originX + gridWidth * layout.cellSize, y),
                strokeWidth = 1f,
            )
        }

        layers.forEach { layer ->
            if (!layer.visible) return@forEach
            layer.cells.forEach { cell ->
                val bounds =
                    cellScreenRect(
                        cell = cell,
                        rect = extents,
                        gridHeight = gridHeight,
                        cellSize = layout.cellSize,
                        originX = layout.originX,
                        originY = layout.originY,
                    )
                val def = catalog.get(cell.symbolId) ?: return@forEach
                drawSymbolPath(
                    def = def,
                    bounds = bounds,
                    rotation = cell.rotation,
                    color = symbolColor,
                    strokeWidthPx = max(1f, layout.cellSize * 0.06f),
                    parsedPathCache = parsedPathCache,
                )
            }
        }
    }
}

private data class CanvasLayout(
    val cellSize: Float,
    val originX: Float,
    val originY: Float,
)

private fun computeLayout(
    widthPx: Float,
    heightPx: Float,
    extents: ChartExtents.Rect,
): CanvasLayout {
    val gridWidth = (extents.maxX - extents.minX + 1)
    val gridHeight = (extents.maxY - extents.minY + 1)
    val cellSize = min(widthPx / gridWidth.toFloat(), heightPx / gridHeight.toFloat()).coerceAtLeast(1f)
    val drawW = cellSize * gridWidth
    val drawH = cellSize * gridHeight
    val originX = (widthPx - drawW) / 2f
    val originY = (heightPx - drawH) / 2f
    return CanvasLayout(cellSize, originX, originY)
}

@Composable
private fun PolarEditorCanvas(
    extents: ChartExtents.Polar,
    layers: List<io.github.b150005.knitnote.domain.model.ChartLayer>,
    catalog: SymbolCatalog,
    isPickingReflectionAxis: Boolean,
    onCellTap: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (extents.rings <= 0 || extents.stitchesPerRing.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.state_empty_chart),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val symbolColor = MaterialTheme.colorScheme.onSurface
    val unknownBg = MaterialTheme.colorScheme.errorContainer
    val unknownFg = MaterialTheme.colorScheme.onErrorContainer
    // Phase 35.2c: accent ring highlight when axis-picking mode is active. Paired
    // with the ReflectionAxisPickBanner above the canvas — the banner carries the
    // text explanation, the ring just adds a visual "anywhere on this wheel is a
    // valid target" hint without intruding on the chart itself.
    val axisPickHintColor = MaterialTheme.colorScheme.primary
    val textMeasurer =
        androidx.compose.ui.text
            .rememberTextMeasurer()
    val parsedPathCache =
        remember {
            mutableMapOf<String, List<io.github.b150005.knitnote.domain.symbol.PathCommand>>()
        }

    // Minimal viewer-style chart for the renderers' drawPolarCells signature.
    // drawPolarCells only reads `chart.layers` (for the iteration + visible /
    // hiddenLayerIds filter) — the rest of StructuredChart is unused. We feed
    // stub values for id/patternId/ownerId/revision/hash and DISTANT_PAST
    // timestamps so the editor does not need the full chart construction path
    // during tap-to-place authoring. If a future drawPolarCells refactor starts
    // reading any other StructuredChart field (coordinateSystem, revisionId,
    // contentHash, createdAt, updatedAt, ...), update this stub accordingly.
    val draftChart =
        remember(layers, extents) {
            io.github.b150005.knitnote.domain.model
                .StructuredChart(
                    id = "editor-draft",
                    patternId = "",
                    ownerId = "",
                    schemaVersion = io.github.b150005.knitnote.domain.model.StructuredChart.CURRENT_SCHEMA_VERSION,
                    storageVariant = io.github.b150005.knitnote.domain.model.StorageVariant.INLINE,
                    coordinateSystem = io.github.b150005.knitnote.domain.model.CoordinateSystem.POLAR_ROUND,
                    extents = extents,
                    layers = layers,
                    revisionId = "",
                    parentRevisionId = null,
                    contentHash = "",
                    createdAt = kotlin.time.Instant.DISTANT_PAST,
                    updatedAt = kotlin.time.Instant.DISTANT_PAST,
                )
        }

    Canvas(
        modifier =
            modifier
                .testTag("editorCanvas")
                .pointerInput(extents) {
                    detectTapGestures { offset ->
                        val layout = polarLayoutFor(size.width.toFloat(), size.height.toFloat(), extents)
                        val hit =
                            GridHitTest.hitTestPolar(
                                screenX = offset.x.toDouble(),
                                screenY = offset.y.toDouble(),
                                extents = extents,
                                layout = layout,
                            )
                        // Polar cell convention (ADR-011): cell.x = stitch, cell.y = ring.
                        hit?.let { onCellTap(it.x, it.y) }
                    }
                },
    ) {
        val layout = polarLayoutFor(size.width, size.height, extents)
        drawPolarGrid(extents, layout, gridColor)
        drawPolarCells(
            polar = extents,
            chart = draftChart,
            hiddenLayerIds = emptySet(),
            catalog = catalog,
            layout = layout,
            textMeasurer = textMeasurer,
            parsedPathCache = parsedPathCache,
            symbolColor = symbolColor,
            unknownBg = unknownBg,
            unknownFg = unknownFg,
        )
        if (isPickingReflectionAxis) {
            val outerRadius = (layout.innerRadius + extents.rings * layout.ringThickness).toFloat()
            drawCircle(
                color = axisPickHintColor,
                radius = outerRadius,
                center = Offset(layout.cx.toFloat(), layout.cy.toFloat()),
                style = Stroke(width = 3f),
            )
        }
    }
}

@Composable
private fun PolarExtentsDialog(
    onConfirm: (ChartExtents.Polar) -> Unit,
    onCancel: () -> Unit,
) {
    // Defaults: a common amigurumi/hat-crown starter. Users can edit freely —
    // validation only requires rings ≥ 1 and matching count of per-ring stitches.
    var ringsText by remember { mutableStateOf("3") }
    var stitchesText by remember { mutableStateOf("8,16,24") }

    val parsed =
        remember(ringsText, stitchesText) {
            val rings = ringsText.trim().toIntOrNull()
            val perRing =
                stitchesText
                    .split(',')
                    .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toIntOrNull() }
            if (rings == null || rings < 1) return@remember null
            if (perRing.size != rings) return@remember null
            if (perRing.any { it < 1 }) return@remember null
            ChartExtents.Polar(rings = rings, stitchesPerRing = perRing)
        }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.dialog_polar_extents_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = ringsText,
                    onValueChange = { ringsText = it },
                    label = { Text(stringResource(Res.string.label_polar_rings)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("polarRingsInput"),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = stitchesText,
                    onValueChange = { stitchesText = it },
                    label = { Text(stringResource(Res.string.label_polar_stitches_per_ring)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("polarStitchesInput"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null,
                modifier = Modifier.testTag("polarExtentsConfirmButton"),
            ) { Text(stringResource(Res.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ChartMetadataMenu(
    expanded: Boolean,
    craftType: CraftType,
    readingConvention: ReadingConvention,
    currentExtents: ChartExtents,
    canChangeExtents: Boolean,
    showSymmetrySection: Boolean,
    onDismiss: () -> Unit,
    onCraftSelected: (CraftType) -> Unit,
    onReadingSelected: (ReadingConvention) -> Unit,
    onFlatSelected: () -> Unit,
    onPolarSelected: () -> Unit,
    onRotationalSymmetry: (Int) -> Unit,
    onReflection: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MetadataMenuHeader(stringResource(Res.string.label_craft))
        CraftType.entries.forEach { craft ->
            val label = stringResource(craftLabelKey(craft))
            DropdownMenuItem(
                text = {
                    val prefix = if (craft == craftType) "\u2713 " else "  "
                    Text("$prefix$label")
                },
                onClick = {
                    onCraftSelected(craft)
                    onDismiss()
                },
                modifier = Modifier.testTag("craftOption_${craft.name}"),
            )
        }
        HorizontalDivider()
        MetadataMenuHeader(stringResource(Res.string.label_reading))
        ReadingConvention.entries.forEach { reading ->
            val label = stringResource(readingLabelKey(reading))
            DropdownMenuItem(
                text = {
                    val prefix = if (reading == readingConvention) "\u2713 " else "  "
                    Text("$prefix$label")
                },
                onClick = {
                    onReadingSelected(reading)
                    onDismiss()
                },
                modifier = Modifier.testTag("readingOption_${reading.name}"),
            )
        }
        if (canChangeExtents) {
            HorizontalDivider()
            MetadataMenuHeader(stringResource(Res.string.label_extents))
            val isFlat = currentExtents is ChartExtents.Rect
            DropdownMenuItem(
                text = {
                    val prefix = if (isFlat) "\u2713 " else "  "
                    Text("$prefix${stringResource(Res.string.label_extents_flat)}")
                },
                onClick = {
                    onFlatSelected()
                    onDismiss()
                },
                modifier = Modifier.testTag("extentsOption_FLAT"),
            )
            DropdownMenuItem(
                text = {
                    val prefix = if (!isFlat) "\u2713 " else "  "
                    Text("$prefix${stringResource(Res.string.label_extents_polar)}")
                },
                onClick = {
                    onPolarSelected()
                    onDismiss()
                },
                modifier = Modifier.testTag("extentsOption_POLAR"),
            )
        }
        if (showSymmetrySection) {
            HorizontalDivider()
            MetadataMenuHeader(stringResource(Res.string.label_symmetry_section))
            // Phase 35.2b: v1 offers a fixed fold set (×2, ×3, ×4, ×6, ×8) and a
            // fixed reflection axis at stitch 0 (12 o'clock). Variable-axis picker
            // is Phase 35.2c scope per ADR-011 §3.
            listOf(2, 3, 4, 6, 8).forEach { fold ->
                DropdownMenuItem(
                    text = {
                        Text("  ${stringResource(Res.string.action_symmetry_fold, fold)}")
                    },
                    onClick = {
                        onRotationalSymmetry(fold)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("symmetryFold_$fold"),
                )
            }
            DropdownMenuItem(
                text = {
                    Text("  ${stringResource(Res.string.action_symmetry_reflect)}")
                },
                onClick = {
                    onReflection()
                    onDismiss()
                },
                modifier = Modifier.testTag("symmetryReflect"),
            )
        }
    }
}

@Composable
private fun ReflectionAxisPickBanner(onCancel: () -> Unit) {
    androidx.compose.foundation.layout
        .Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("axisPickBanner"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.banner_pick_reflection_axis),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("axisPickCancelButton"),
            ) { Text(stringResource(Res.string.action_cancel)) }
        }
}

@Composable
private fun MetadataMenuHeader(label: String) {
    // Non-interactive section header — avoids the "disabled button" semantics
    // that screen readers announce for `DropdownMenuItem(enabled = false)`.
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private fun craftLabelKey(craft: CraftType): StringResource =
    when (craft) {
        CraftType.KNIT -> Res.string.label_craft_knit
        CraftType.CROCHET -> Res.string.label_craft_crochet
    }

private fun readingLabelKey(reading: ReadingConvention): StringResource =
    when (reading) {
        ReadingConvention.KNIT_FLAT -> Res.string.label_reading_knit_flat
        ReadingConvention.CROCHET_FLAT -> Res.string.label_reading_crochet_flat
        ReadingConvention.ROUND -> Res.string.label_reading_round
    }
