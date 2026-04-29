package io.github.b150005.knitnote.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.chart.GridHitTest
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CraftType
import io.github.b150005.knitnote.domain.model.ReadingConvention
import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_add_layer
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_delete
import io.github.b150005.knitnote.generated.resources.action_delete_layer
import io.github.b150005.knitnote.generated.resources.action_discard
import io.github.b150005.knitnote.generated.resources.action_drag_to_reorder
import io.github.b150005.knitnote.generated.resources.action_keep_editing
import io.github.b150005.knitnote.generated.resources.action_layers
import io.github.b150005.knitnote.generated.resources.action_more_options
import io.github.b150005.knitnote.generated.resources.action_ok
import io.github.b150005.knitnote.generated.resources.action_redo
import io.github.b150005.knitnote.generated.resources.action_rename_layer
import io.github.b150005.knitnote.generated.resources.action_resize_chart
import io.github.b150005.knitnote.generated.resources.action_save
import io.github.b150005.knitnote.generated.resources.action_symmetry_fold
import io.github.b150005.knitnote.generated.resources.action_symmetry_reflect
import io.github.b150005.knitnote.generated.resources.action_toggle_layer_lock
import io.github.b150005.knitnote.generated.resources.action_toggle_layer_visibility
import io.github.b150005.knitnote.generated.resources.action_undo
import io.github.b150005.knitnote.generated.resources.banner_pick_reflection_axis
import io.github.b150005.knitnote.generated.resources.dialog_delete_layer_body
import io.github.b150005.knitnote.generated.resources.dialog_delete_layer_title
import io.github.b150005.knitnote.generated.resources.dialog_parameter_edit_title
import io.github.b150005.knitnote.generated.resources.dialog_parameter_enter_title
import io.github.b150005.knitnote.generated.resources.dialog_polar_extents_title
import io.github.b150005.knitnote.generated.resources.dialog_resize_chart_title
import io.github.b150005.knitnote.generated.resources.dialog_unsaved_changes_body
import io.github.b150005.knitnote.generated.resources.dialog_unsaved_changes_title
import io.github.b150005.knitnote.generated.resources.label_craft
import io.github.b150005.knitnote.generated.resources.label_craft_crochet
import io.github.b150005.knitnote.generated.resources.label_craft_knit
import io.github.b150005.knitnote.generated.resources.label_extents
import io.github.b150005.knitnote.generated.resources.label_extents_flat
import io.github.b150005.knitnote.generated.resources.label_extents_polar
import io.github.b150005.knitnote.generated.resources.label_grid_height
import io.github.b150005.knitnote.generated.resources.label_grid_width
import io.github.b150005.knitnote.generated.resources.label_layer_default_name
import io.github.b150005.knitnote.generated.resources.label_layers_section
import io.github.b150005.knitnote.generated.resources.label_polar_rings
import io.github.b150005.knitnote.generated.resources.label_polar_stitches_per_ring
import io.github.b150005.knitnote.generated.resources.label_polar_stitches_uniform
import io.github.b150005.knitnote.generated.resources.label_reading
import io.github.b150005.knitnote.generated.resources.label_reading_crochet_flat
import io.github.b150005.knitnote.generated.resources.label_reading_knit_flat
import io.github.b150005.knitnote.generated.resources.label_reading_round
import io.github.b150005.knitnote.generated.resources.label_resize_trim_count
import io.github.b150005.knitnote.generated.resources.label_symmetry_section
import io.github.b150005.knitnote.generated.resources.state_empty_chart
import io.github.b150005.knitnote.generated.resources.state_no_layers
import io.github.b150005.knitnote.generated.resources.title_edit_chart
import io.github.b150005.knitnote.ui.components.localized
import io.github.b150005.knitnote.ui.platform.SystemBackHandler
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    // Phase 35.3 (ADR-011 §6): grid-size picker. Available on both new and
    // existing charts within the same coordinate system.
    var showResizeDialog by remember { mutableStateOf(false) }
    // Phase 35.2f-ui: right-side modal layer-list drawer. `gesturesEnabled = false`
    // because swipe-from-right would conflict with Android predictive-back —
    // toolbar tap is the only open path. Closed by tapping the scrim or via
    // the SystemBackHandler when the drawer is open.
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    // Pending delete-confirm context: layer id whose row triggered "Delete"
    // and has at least one cell. Null means no confirmation pending. Empty
    // layers delete silently per ADR-011 §5 addendum decision 4.
    var pendingDeleteLayerId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.saved.collect { onBack() }
    }

    val attemptBack: () -> Unit = {
        when {
            // Drawer-open back closes the drawer rather than popping the screen.
            drawerState.isOpen -> drawerScope.launch { drawerState.close() }
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
    // unsaved edits OR an axis pick is in flight OR the drawer is open. The drawer
    // condition is intentional: with `gesturesEnabled = false` the user can only
    // dismiss via scrim tap, so system-back must close it explicitly.
    SystemBackHandler(
        enabled = state.hasUnsavedChanges || state.isPickingReflectionAxis || drawerState.isOpen,
    ) {
        when {
            drawerState.isOpen -> drawerScope.launch { drawerState.close() }
            state.isPickingReflectionAxis ->
                viewModel.onEvent(ChartEditorEvent.CancelPickReflectionAxis)
            else -> showDiscardDialog = true
        }
    }

    // Phase 35.2f-ui: locale-resolved next-layer name. Recomputed on every
    // composition so the localized string follows the current draft layer set.
    val nextAutoLayerName =
        stringResource(Res.string.label_layer_default_name, nextLayerNumber(state.draftLayers))

    // Phase 35.2f-ui: anchor the ModalNavigationDrawer to the right edge by
    // flipping LayoutDirection at the wrapper level (CMP's
    // ModalNavigationDrawer ships left-edge-only). The drawer-content and
    // body slots re-apply the original direction so their internal layout
    // (text alignment, swipe-dismiss, etc.) matches the parent.
    // Future RTL-locale support: when the parent is already RTL, this flip
    // currently produces a left-anchored drawer — a custom (non-flipped)
    // implementation will be needed at that point. Tracked as tech-debt.
    val parentLayoutDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides parentLayoutDirection) {
                    ModalDrawerSheet(
                        modifier =
                            Modifier
                                .testTag("layerDrawer")
                                .fillMaxHeight()
                                .width(320.dp),
                    ) {
                        LayerDrawerContent(
                            layers = state.draftLayers,
                            selectedLayerId = state.selectedLayerId,
                            nextAutoLayerName = nextAutoLayerName,
                            onSelectLayer = { id ->
                                viewModel.onEvent(ChartEditorEvent.SelectLayer(id))
                            },
                            onAddLayer = { name ->
                                viewModel.onEvent(ChartEditorEvent.AddLayer(name = name))
                            },
                            onRenameLayer = { id, newName ->
                                viewModel.onEvent(ChartEditorEvent.RenameLayer(id, newName))
                            },
                            onToggleVisibility = { id ->
                                viewModel.onEvent(ChartEditorEvent.ToggleLayerVisibility(id))
                            },
                            onToggleLock = { id ->
                                viewModel.onEvent(ChartEditorEvent.ToggleLayerLock(id))
                            },
                            onReorder = { from, to ->
                                viewModel.onEvent(ChartEditorEvent.ReorderLayer(from, to))
                            },
                            onRequestDelete = { layer ->
                                // Empty-layer deletes go through silently per
                                // ADR-011 §5 addendum decision 4.
                                if (layer.cells.isEmpty()) {
                                    viewModel.onEvent(ChartEditorEvent.RemoveLayer(layer.id))
                                } else {
                                    pendingDeleteLayerId = layer.id
                                }
                            },
                        )
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides parentLayoutDirection) {
                EditorBody(
                    state = state,
                    catalog = catalog,
                    attemptBack = attemptBack,
                    showOverflowMenu = showOverflowMenu,
                    onShowOverflowMenu = { showOverflowMenu = it },
                    onUndo = { viewModel.onEvent(ChartEditorEvent.Undo) },
                    onRedo = { viewModel.onEvent(ChartEditorEvent.Redo) },
                    onSave = { viewModel.onEvent(ChartEditorEvent.Save) },
                    onOpenLayers = { drawerScope.launch { drawerState.open() } },
                    onCraftSelected = { viewModel.onEvent(ChartEditorEvent.SelectCraft(it)) },
                    onReadingSelected = { viewModel.onEvent(ChartEditorEvent.SelectReading(it)) },
                    onFlatSelected = {
                        viewModel.onEvent(
                            ChartEditorEvent.SetExtents(
                                ChartExtents.Rect(minX = 0, maxX = 7, minY = 0, maxY = 7),
                            ),
                        )
                    },
                    onPolarSelected = { showPolarPicker = true },
                    onResize = { showResizeDialog = true },
                    onRotationalSymmetry = { fold ->
                        viewModel.onEvent(ChartEditorEvent.ApplyRotationalSymmetry(fold))
                    },
                    onReflection = { viewModel.onEvent(ChartEditorEvent.StartPickReflectionAxis) },
                    onCancelPickReflectionAxis = {
                        viewModel.onEvent(ChartEditorEvent.CancelPickReflectionAxis)
                    },
                    onPlaceCell = { x, y ->
                        viewModel.onEvent(ChartEditorEvent.PlaceCell(x = x, y = y))
                    },
                    onSelectCategory = { viewModel.onEvent(ChartEditorEvent.SelectCategory(it)) },
                    onSelectSymbol = { viewModel.onEvent(ChartEditorEvent.SelectSymbol(it)) },
                )
            }
        }
    }

    if (pendingDeleteLayerId != null) {
        val targetId = pendingDeleteLayerId
        val target = state.draftLayers.firstOrNull { it.id == targetId }
        if (target == null) {
            // Race: the layer disappeared (undo, etc.) — drop the dialog.
            pendingDeleteLayerId = null
        } else {
            AlertDialog(
                onDismissRequest = { pendingDeleteLayerId = null },
                title = { Text(stringResource(Res.string.dialog_delete_layer_title)) },
                text = {
                    Text(stringResource(Res.string.dialog_delete_layer_body, target.cells.size))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.onEvent(ChartEditorEvent.RemoveLayer(target.id))
                            pendingDeleteLayerId = null
                        },
                        modifier = Modifier.testTag("deleteLayerConfirmButton"),
                    ) { Text(stringResource(Res.string.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteLayerId = null }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
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

    if (showResizeDialog) {
        ResizeChartDialog(
            currentExtents = state.draftExtents,
            currentLayers = state.draftLayers,
            onConfirm = { newExtents ->
                showResizeDialog = false
                viewModel.onEvent(ChartEditorEvent.ResizeChart(newExtents))
            },
            onCancel = { showResizeDialog = false },
        )
    }
}

/**
 * Phase 35.2f-ui: editor body that previously lived inline inside
 * [ChartEditorScreen]. Extracted so the [ModalNavigationDrawer] wrapping the
 * screen can re-use the same Scaffold + canvas + palette tree without
 * duplicating it. All state is hoisted as parameters so the composable stays
 * locale-agnostic and event-routing is the caller's responsibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorBody(
    state: ChartEditorState,
    catalog: SymbolCatalog,
    attemptBack: () -> Unit,
    showOverflowMenu: Boolean,
    onShowOverflowMenu: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onOpenLayers: () -> Unit,
    onCraftSelected: (CraftType) -> Unit,
    onReadingSelected: (ReadingConvention) -> Unit,
    onFlatSelected: () -> Unit,
    onPolarSelected: () -> Unit,
    onResize: () -> Unit,
    onRotationalSymmetry: (Int) -> Unit,
    onReflection: () -> Unit,
    onCancelPickReflectionAxis: () -> Unit,
    onPlaceCell: (Int, Int) -> Unit,
    onSelectCategory: (SymbolCategory) -> Unit,
    onSelectSymbol: (String?) -> Unit,
) {
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
                        onClick = onUndo,
                        enabled = state.canUndo,
                        modifier = Modifier.testTag("editorUndoButton"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(Res.string.action_undo),
                        )
                    }
                    IconButton(
                        onClick = onRedo,
                        enabled = state.canRedo,
                        modifier = Modifier.testTag("editorRedoButton"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = stringResource(Res.string.action_redo),
                        )
                    }
                    IconButton(
                        onClick = onSave,
                        enabled = state.hasUnsavedChanges && !state.isSaving,
                        modifier = Modifier.testTag("editorSaveButton"),
                    ) {
                        Icon(
                            Icons.Filled.Save,
                            contentDescription = stringResource(Res.string.action_save),
                        )
                    }
                    // Phase 35.2f-ui: layers drawer trigger.
                    IconButton(
                        onClick = onOpenLayers,
                        modifier = Modifier.testTag("layersButton"),
                    ) {
                        Icon(
                            Icons.Filled.Layers,
                            contentDescription = stringResource(Res.string.action_layers),
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { onShowOverflowMenu(true) },
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
                            onDismiss = { onShowOverflowMenu(false) },
                            onCraftSelected = onCraftSelected,
                            onReadingSelected = onReadingSelected,
                            onFlatSelected = onFlatSelected,
                            onPolarSelected = onPolarSelected,
                            onResize = onResize,
                            onRotationalSymmetry = onRotationalSymmetry,
                            onReflection = onReflection,
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
                        text = state.errorMessage.localized(),
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
                            ReflectionAxisPickBanner(onCancel = onCancelPickReflectionAxis)
                        }
                        EditorCanvas(
                            extents = state.draftExtents,
                            layers = state.draftLayers,
                            catalog = catalog,
                            isPickingReflectionAxis = state.isPickingReflectionAxis,
                            onCellTap = onPlaceCell,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        SymbolPaletteStrip(
                            selectedCategory = state.selectedCategory,
                            availableCategories = availableCategories,
                            symbols = state.paletteSymbols,
                            selectedSymbolId = state.selectedSymbolId,
                            onCategorySelected = onSelectCategory,
                            onSymbolSelected = onSelectSymbol,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Right-side modal layer-list drawer content per ADR-011 §5 addendum
 * decision 4. Renders the layer list with per-row drag handle, visibility
 * eye, lock padlock, name (inline rename via [OutlinedTextField] swapped on
 * tap), and overflow delete; an [Add] FAB anchors the bottom of the panel.
 *
 * Reorder is long-press-and-drag on the drag handle (PRD §3) — a
 * compositional version that does not require a third-party reorderable
 * library. Tap-to-select on the row body uses the regular tap gesture; the
 * drag handle's gesture detector consumes long-press separately so reorder
 * does not conflict with selection.
 */
@Composable
private fun LayerDrawerContent(
    layers: List<ChartLayer>,
    selectedLayerId: String?,
    nextAutoLayerName: String,
    onSelectLayer: (String) -> Unit,
    onAddLayer: (String) -> Unit,
    onRenameLayer: (String, String) -> Unit,
    onToggleVisibility: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onRequestDelete: (ChartLayer) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(Res.string.label_layers_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (layers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.state_no_layers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Track which row is currently being renamed (id of the layer
            // whose name field is in edit mode). null = no row is in edit
            // mode. Hoisted at the column level so tapping a different row
            // commits any pending name and shifts edit focus naturally.
            var editingLayerId by remember { mutableStateOf<String?>(null) }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(items = layers, key = { _, layer -> layer.id }) { index, layer ->
                    LayerRow(
                        // Re-keying on (id, index) below ensures a fresh
                        // pointerInput coroutine after each successful reorder
                        // commit so dragAccumulator resets and the next
                        // delta resolves against the new index.
                        rowKey = "${layer.id}@$index",
                        layer = layer,
                        isSelected = layer.id == selectedLayerId,
                        isEditing = layer.id == editingLayerId,
                        onSelect = {
                            // Tapping a non-editing row selects it and exits any
                            // open rename. Selecting the already-editing row is
                            // a no-op (handled by the SelectLayer guard).
                            if (editingLayerId != null && editingLayerId != layer.id) {
                                editingLayerId = null
                            }
                            onSelectLayer(layer.id)
                        },
                        onStartRename = { editingLayerId = layer.id },
                        onRename = { newName ->
                            editingLayerId = null
                            onRenameLayer(layer.id, newName)
                        },
                        onCancelRename = { editingLayerId = null },
                        onToggleVisibility = { onToggleVisibility(layer.id) },
                        onToggleLock = { onToggleLock(layer.id) },
                        onDelete = { onRequestDelete(layer) },
                        onDragMoveBy = { rowsDelta ->
                            // The drag handle's gesture detector translates
                            // vertical-drag pixels into row-shift deltas (see
                            // [LayerRow.DragHandle]); we clamp here so the
                            // ViewModel never sees an out-of-range index.
                            val target = (index + rowsDelta).coerceIn(0, layers.size - 1)
                            if (target != index) onReorder(index, target)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FloatingActionButton(
            onClick = { onAddLayer(nextAutoLayerName) },
            modifier =
                Modifier
                    .testTag("addLayerFab"),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(Res.string.action_add_layer),
            )
        }
    }
}

@Composable
private fun LayerRow(
    rowKey: String,
    layer: ChartLayer,
    isSelected: Boolean,
    isEditing: Boolean,
    onSelect: () -> Unit,
    onStartRename: () -> Unit,
    onRename: (String) -> Unit,
    onCancelRename: () -> Unit,
    onToggleVisibility: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit,
    onDragMoveBy: (Int) -> Unit,
) {
    val rowHeight = 52.dp
    val backgroundColor =
        if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            ListItemDefaults.containerColor
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .background(backgroundColor)
                .testTag("layerRow_${layer.id}")
                .pointerInput(rowKey) {
                    detectTapGestures(onTap = { onSelect() })
                },
    ) {
        // Drag handle: long-press + vertical drag to reorder. Accumulator
        // converts pixel deltas into row-index deltas using rowHeight. Keyed
        // on rowKey so a successful reorder restarts the gesture coroutine
        // with a fresh accumulator and the new captured index.
        var dragAccumulator by remember(rowKey) { mutableStateOf(0f) }
        val rowHeightPxState = with(LocalDensity.current) { rowHeight.toPx() }
        Box(
            modifier =
                Modifier
                    .testTag("layerDragHandle_${layer.id}")
                    .size(36.dp)
                    .pointerInput(rowKey) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragAccumulator = 0f },
                            onDragEnd = { dragAccumulator = 0f },
                            onDragCancel = { dragAccumulator = 0f },
                            onDrag = { _, dragAmount ->
                                dragAccumulator += dragAmount.y
                                val rows = (dragAccumulator / rowHeightPxState).roundToInt()
                                if (rows != 0) {
                                    onDragMoveBy(rows)
                                    dragAccumulator -= rows * rowHeightPxState
                                }
                            },
                        )
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = stringResource(Res.string.action_drag_to_reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Visibility eye.
        IconButton(
            onClick = onToggleVisibility,
            modifier = Modifier.testTag("layerVisibilityButton_${layer.id}"),
        ) {
            Icon(
                imageVector = if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = stringResource(Res.string.action_toggle_layer_visibility),
            )
        }

        // Lock padlock.
        IconButton(
            onClick = onToggleLock,
            modifier = Modifier.testTag("layerLockButton_${layer.id}"),
        ) {
            Icon(
                imageVector = if (layer.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = stringResource(Res.string.action_toggle_layer_lock),
            )
        }

        // Layer name — inline rename via OutlinedTextField swap.
        if (isEditing) {
            var draft by remember(layer.id) { mutableStateOf(layer.name) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .testTag("layerNameField_${layer.id}"),
            )
            IconButton(
                onClick = { onRename(draft) },
                modifier = Modifier.testTag("layerNameConfirmButton_${layer.id}"),
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(Res.string.action_rename_layer),
                )
            }
        } else {
            Text(
                text = layer.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .pointerInput(layer.id) {
                            detectTapGestures(
                                onTap = { onSelect() },
                                onLongPress = { onStartRename() },
                            )
                        },
            )
        }

        // Overflow delete IconButton — direct affordance per addendum
        // decision 4 ("overflow menu (delete)"). Material 3 menu indirection
        // is overkill for a single-action overflow; we expose Delete directly.
        IconButton(
            onClick = onDelete,
            modifier = Modifier.testTag("layerDeleteButton_${layer.id}"),
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(Res.string.action_delete_layer),
            )
        }
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

/**
 * Phase 35.3 (ADR-011 §6): grid-size picker dialog. Stays within the current
 * coordinate system — rect-vs-polar variant is selected from [currentExtents],
 * not user input. Polar resize uses a single uniform stitch count applied to
 * every ring (per-ring list editing is deferred per §6 MVP).
 *
 * Validation is local (W/H/rings/stitches each in `[1, MAX_DIM]`); on confirm
 * the ViewModel re-validates the same-system invariant defensively.
 *
 * The destructive trim warning is displayed below the inputs whenever the
 * computed new extents would drop ≥ 1 cell from [currentLayers]; the warning
 * uses [MaterialTheme.colorScheme.error] to match the layer-delete confirm
 * pattern from Phase 35.2f.
 */
@Composable
private fun ResizeChartDialog(
    currentExtents: ChartExtents,
    currentLayers: List<ChartLayer>,
    onConfirm: (ChartExtents) -> Unit,
    onCancel: () -> Unit,
) {
    when (currentExtents) {
        is ChartExtents.Rect -> {
            val initialWidth = currentExtents.maxX - currentExtents.minX + 1
            val initialHeight = currentExtents.maxY - currentExtents.minY + 1
            // Key on currentExtents so a future flow that leaves the dialog
            // open across an extents change (e.g. async Realtime sync of an
            // existing chart) reseeds the inputs rather than displaying stale
            // initial values. Matches the precedent in [ParameterInputDialog].
            var widthText by remember(currentExtents) { mutableStateOf(initialWidth.toString()) }
            var heightText by remember(currentExtents) { mutableStateOf(initialHeight.toString()) }

            val parsed =
                remember(widthText, heightText) {
                    val w = widthText.trim().toIntOrNull() ?: return@remember null
                    val h = heightText.trim().toIntOrNull() ?: return@remember null
                    if (w !in 1..MAX_GRID_DIMENSION || h !in 1..MAX_GRID_DIMENSION) {
                        return@remember null
                    }
                    ChartExtents.Rect(minX = 0, maxX = w - 1, minY = 0, maxY = h - 1)
                }
            val trimCount =
                remember(parsed, currentLayers) {
                    parsed?.let { trimRemovalCount(currentLayers, it) } ?: 0
                }

            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text(stringResource(Res.string.dialog_resize_chart_title)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = widthText,
                            onValueChange = { widthText = it },
                            label = { Text(stringResource(Res.string.label_grid_width)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("resizeWidthInput"),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = heightText,
                            onValueChange = { heightText = it },
                            label = { Text(stringResource(Res.string.label_grid_height)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("resizeHeightInput"),
                        )
                        if (trimCount > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(Res.string.label_resize_trim_count, trimCount),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("resizeTrimWarning"),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { parsed?.let(onConfirm) },
                        enabled = parsed != null,
                        modifier = Modifier.testTag("resizeChartConfirmButton"),
                    ) { Text(stringResource(Res.string.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        }
        is ChartExtents.Polar -> {
            val initialRings = currentExtents.rings
            // Seed the uniform-stitch input with ring 0's count when present.
            // A non-uniform polar chart will collapse to uniform on resize per
            // §6 MVP — the user can see (and edit) this default before confirm.
            val initialStitches = currentExtents.stitchesPerRing.firstOrNull() ?: 8
            var ringsText by remember(currentExtents) { mutableStateOf(initialRings.toString()) }
            var stitchesText by remember(currentExtents) { mutableStateOf(initialStitches.toString()) }

            val parsed =
                remember(ringsText, stitchesText) {
                    val r = ringsText.trim().toIntOrNull() ?: return@remember null
                    val s = stitchesText.trim().toIntOrNull() ?: return@remember null
                    if (r !in 1..MAX_GRID_DIMENSION || s !in 1..MAX_GRID_DIMENSION) {
                        return@remember null
                    }
                    ChartExtents.Polar(rings = r, stitchesPerRing = List(r) { s })
                }
            val trimCount =
                remember(parsed, currentLayers) {
                    parsed?.let { trimRemovalCount(currentLayers, it) } ?: 0
                }

            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text(stringResource(Res.string.dialog_resize_chart_title)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = ringsText,
                            onValueChange = { ringsText = it },
                            label = { Text(stringResource(Res.string.label_polar_rings)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("resizeRingsInput"),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = stitchesText,
                            onValueChange = { stitchesText = it },
                            label = {
                                Text(stringResource(Res.string.label_polar_stitches_uniform))
                            },
                            singleLine = true,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("resizeStitchesUniformInput"),
                        )
                        if (trimCount > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(Res.string.label_resize_trim_count, trimCount),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("resizeTrimWarning"),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { parsed?.let(onConfirm) },
                        enabled = parsed != null,
                        modifier = Modifier.testTag("resizeChartConfirmButton"),
                    ) { Text(stringResource(Res.string.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        }
    }
}

/** ADR-011 §6 hard cap on per-axis grid dimension. */
private const val MAX_GRID_DIMENSION: Int = 256

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
    onResize: () -> Unit,
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
        // Phase 35.3 (ADR-011 §6): the Grid section is always rendered; the
        // rect↔polar coordinate-system toggle stays conditional on a new
        // chart (cell indices are not portable across systems), but the
        // "Resize chart" entry always shows because resize keeps the
        // current coordinate system.
        HorizontalDivider()
        MetadataMenuHeader(stringResource(Res.string.label_extents))
        if (canChangeExtents) {
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
        DropdownMenuItem(
            text = { Text("  ${stringResource(Res.string.action_resize_chart)}") },
            onClick = {
                onResize()
                onDismiss()
            },
            modifier = Modifier.testTag("resizeChartMenuItem"),
        )
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
