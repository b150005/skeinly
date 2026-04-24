import SwiftUI
import Shared

/// Phase 32 SwiftUI mirror of `ChartEditorScreen` (Compose Multiplatform).
/// Tap-to-place editor with undo/redo and save. Holds a Koin-resolved
/// `ChartEditorViewModel` via `ScopedViewModel` so state survives SwiftUI
/// view reinits (see `ScopedViewModel`).
struct StructuredChartEditorScreen: View {
    let patternId: String
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<ChartEditorViewModel, ChartEditorState>
    @State private var showDiscardConfirm = false
    @State private var showError = false
    @State private var savedCloseable: Closeable?
    // Decoupled from ViewModel state so the sheet's presented flag is driven by an
    // explicit handler, not by reading back from the reactive state flow (avoids a
    // transient race on confirm where the flag would spuriously fire CancelParameterInput).
    @State private var isParameterSheetPresented = false
    @State private var activeParameterPending: PendingParameterInput?
    @State private var showPolarPicker = false
    private let catalog: SymbolCatalog

    private var viewModel: ChartEditorViewModel { holder.viewModel }

    init(patternId: String, path: Binding<NavigationPath>) {
        self.patternId = patternId
        self._path = path
        let vm = ViewModelFactory.chartEditorViewModel(patternId: patternId)
        let wrapper = KoinHelperKt.wrapChartEditorState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
        self.catalog = ViewModelFactory.symbolCatalog()
    }

    var body: some View {
        let state = holder.state

        VStack(spacing: 0) {
            if state.isLoading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                EditorCanvasView(
                    extents: state.draftExtents,
                    layers: state.draftLayers,
                    catalog: catalog,
                    onCellTap: { x, y in
                        viewModel.onEvent(
                            event: ChartEditorEventPlaceCell(x: Int32(x), y: Int32(y))
                        )
                    }
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                Divider()

                SymbolPaletteView(
                    selectedCategory: state.selectedCategory,
                    availableCategories: availableCategories,
                    symbols: state.paletteSymbols,
                    selectedSymbolId: state.selectedSymbolId,
                    onCategorySelected: { category in
                        viewModel.onEvent(
                            event: ChartEditorEventSelectCategory(category: category)
                        )
                    },
                    onSymbolSelected: { id in
                        viewModel.onEvent(
                            event: ChartEditorEventSelectSymbol(symbolId: id)
                        )
                    }
                )
                .frame(height: 110)
            }
        }
        .navigationTitle("Edit chart")
        .navigationBarTitleDisplayMode(.inline)
        .interactiveDismissDisabled(state.hasUnsavedChanges)
        // Hide the system-generated back button so NavigationStack cannot pop
        // without routing through attemptBack() (which raises the discard
        // dialog on unsaved edits). Edge-swipe back is a known limitation
        // (see CLAUDE.md > CI Known Limitations).
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    attemptBack(state: state)
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left")
                        Text("Back")
                    }
                }
                .accessibilityIdentifier("backButton")
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    viewModel.onEvent(event: ChartEditorEventUndo.shared)
                } label: { Image(systemName: "arrow.uturn.backward") }
                .accessibilityIdentifier("editorUndoButton")
                .disabled(!state.canUndo)

                Button {
                    viewModel.onEvent(event: ChartEditorEventRedo.shared)
                } label: { Image(systemName: "arrow.uturn.forward") }
                .accessibilityIdentifier("editorRedoButton")
                .disabled(!state.canRedo)

                Button("Save") {
                    viewModel.onEvent(event: ChartEditorEventSave.shared)
                }
                .accessibilityIdentifier("editorSaveButton")
                .disabled(!state.hasUnsavedChanges || state.isSaving)

                Menu {
                    Section("Craft") {
                        Picker("Craft", selection: Binding(
                            get: { state.draftCraftType },
                            set: { viewModel.onEvent(event: ChartEditorEventSelectCraft(craftType: $0)) }
                        )) {
                            Text("Knit").tag(CraftType.knit)
                            Text("Crochet").tag(CraftType.crochet)
                        }
                    }
                    Section("Reading") {
                        Picker("Reading", selection: Binding(
                            get: { state.draftReadingConvention },
                            set: { viewModel.onEvent(event: ChartEditorEventSelectReading(readingConvention: $0)) }
                        )) {
                            Text("Knit flat (RS →, WS ←)").tag(ReadingConvention.knitFlat)
                            Text("Crochet flat (L→R)").tag(ReadingConvention.crochetFlat)
                            Text("Round (center out)").tag(ReadingConvention.round)
                        }
                    }
                    // Phase 35.2a: extents picker only on new charts.
                    // ViewModel rejects SetExtents when original != nil as a second line.
                    if state.original == nil {
                        Section(LocalizedStringKey("label_extents")) {
                            Button {
                                viewModel.onEvent(
                                    event: ChartEditorEventSetExtents(
                                        extents: ChartExtentsRect(minX: 0, maxX: 7, minY: 0, maxY: 7)
                                    )
                                )
                            } label: {
                                if state.draftExtents is ChartExtentsRect {
                                    Label(LocalizedStringKey("label_extents_flat"), systemImage: "checkmark")
                                } else {
                                    Text(LocalizedStringKey("label_extents_flat"))
                                }
                            }
                            .accessibilityIdentifier("extentsOption_FLAT")

                            Button {
                                showPolarPicker = true
                            } label: {
                                if state.draftExtents is ChartExtentsPolar {
                                    Label(LocalizedStringKey("label_extents_polar"), systemImage: "checkmark")
                                } else {
                                    Text(LocalizedStringKey("label_extents_polar"))
                                }
                            }
                            .accessibilityIdentifier("extentsOption_POLAR")
                        }
                    }
                    // Phase 35.2b: polar-only symmetry ops (ADR-011 §3). Fixed
                    // fold set + reflection axis at stitch 0 for v1; variable-axis
                    // picker is Phase 35.2c scope.
                    if state.draftExtents is ChartExtentsPolar {
                        Section(LocalizedStringKey("label_symmetry_section")) {
                            ForEach([2, 3, 4, 6, 8], id: \.self) { fold in
                                Button {
                                    viewModel.onEvent(
                                        event: ChartEditorEventApplyRotationalSymmetry(fold: Int32(fold))
                                    )
                                } label: {
                                    Text(String(
                                        format: NSLocalizedString("action_symmetry_fold", comment: ""),
                                        fold
                                    ))
                                }
                                .accessibilityIdentifier("symmetryFold_\(fold)")
                            }
                            Button {
                                viewModel.onEvent(
                                    event: ChartEditorEventApplyReflection(axisStitch: 0)
                                )
                            } label: {
                                Text(LocalizedStringKey("action_symmetry_reflect"))
                            }
                            .accessibilityIdentifier("symmetryReflect")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityIdentifier("editorOverflowButton")
            }
        }
        .task {
            let savedFlow = KoinHelperKt.wrapChartEditorSavedFlow(flow: viewModel.saved)
            savedCloseable = savedFlow.collect { _ in
                Task { @MainActor in
                    if !path.isEmpty {
                        path.removeLast()
                    }
                }
            }
        }
        .onDisappear {
            savedCloseable?.close()
            savedCloseable = nil
        }
        .onChange(of: state.errorMessage) { _, newValue in
            showError = newValue != nil
        }
        .alert("Error", isPresented: $showError) {
            Button("OK") { viewModel.onEvent(event: ChartEditorEventClearError.shared) }
        } message: {
            Text(state.errorMessage ?? "")
        }
        .confirmationDialog(
            "Unsaved changes",
            isPresented: $showDiscardConfirm,
            titleVisibility: .visible
        ) {
            Button("Discard", role: .destructive) {
                showDiscardConfirm = false
                if !path.isEmpty { path.removeLast() }
            }
            Button("Keep editing", role: .cancel) {
                showDiscardConfirm = false
            }
        } message: {
            Text("You have unsaved changes. Discard them?")
        }
        .onChange(of: state.pendingParameterInput) { _, newPending in
            if let pending = newPending {
                activeParameterPending = pending
                isParameterSheetPresented = true
            } else {
                // ViewModel cleared the pending (confirm or cancel handled).
                isParameterSheetPresented = false
                activeParameterPending = nil
            }
        }
        .sheet(isPresented: $showPolarPicker) {
            PolarExtentsSheet(
                onConfirm: { polar in
                    showPolarPicker = false
                    viewModel.onEvent(event: ChartEditorEventSetExtents(extents: polar))
                },
                onCancel: { showPolarPicker = false }
            )
            .presentationDetents([.medium])
        }
        .sheet(isPresented: $isParameterSheetPresented, onDismiss: {
            // User-initiated swipe-down dismiss — if VM still has a pending, treat as cancel.
            if holder.state.pendingParameterInput != nil {
                viewModel.onEvent(event: ChartEditorEventCancelParameterInput.shared)
            }
            activeParameterPending = nil
        }) {
            if let pending = activeParameterPending {
                ParameterInputSheet(
                    pending: pending,
                    onConfirm: { values in
                        viewModel.onEvent(
                            event: ChartEditorEventConfirmParameterInput(values: values)
                        )
                    },
                    onCancel: {
                        viewModel.onEvent(event: ChartEditorEventCancelParameterInput.shared)
                    }
                )
                // Force a fresh view instance per-pending so .onAppear re-seeds drafts
                // when a new cell is tapped while the sheet is already dismissed/re-opening.
                .id("\(pending.symbolId)@\(pending.x),\(pending.y)")
                .presentationDetents([.medium])
            }
        }
    }

    private func attemptBack(state: ChartEditorState) {
        if state.hasUnsavedChanges {
            showDiscardConfirm = true
        } else if !path.isEmpty {
            path.removeLast()
        }
    }

    private var availableCategories: [SymbolCategory] {
        SymbolCategory.entries.filter { category in
            !catalog.listByCategory(category: category).isEmpty
        }
    }
}

// MARK: - Canvas

private struct EditorCanvasView: View {
    let extents: ChartExtents
    let layers: [ChartLayer]
    let catalog: SymbolCatalog
    let onCellTap: (Int, Int) -> Void

    @State private var canvasSize: CGSize = .zero

    var body: some View {
        GeometryReader { proxy in
            Canvas { context, size in
                switch extents {
                case let rect as ChartExtentsRect:
                    if rect.maxX < rect.minX || rect.maxY < rect.minY { return }
                    draw(into: &context, size: size, rect: rect)
                case let polar as ChartExtentsPolar:
                    if polar.rings <= 0 || polar.stitchesPerRing.isEmpty { return }
                    drawPolar(into: &context, size: size, polar: polar)
                default:
                    break
                }
            }
            .background(Color(.systemBackground))
            .contentShape(Rectangle())
            .onTapGesture { location in
                switch extents {
                case let rect as ChartExtentsRect:
                    let layout = Self.layout(for: proxy.size, rect: rect)
                    if let cell = GridHitTest.shared.hitTest(
                        screenX: Double(location.x),
                        screenY: Double(location.y),
                        extents: rect,
                        cellSize: Double(layout.cellSize),
                        originX: Double(layout.originX),
                        originY: Double(layout.originY)
                    ) {
                        onCellTap(Int(cell.x), Int(cell.y))
                    }
                case let polar as ChartExtentsPolar:
                    let layout = Self.polarLayout(for: proxy.size, polar: polar)
                    if let hit = Self.hitTestPolar(location: location, polar: polar, layout: layout) {
                        // Polar convention (ADR-011): cell.x = stitch, cell.y = ring.
                        onCellTap(hit.stitch, hit.ring)
                    }
                default:
                    break
                }
            }
        }
    }

    /// Inlined polar layout (mirrors `shared/ui/chart/PolarDrawing.kt:polarLayoutFor`).
    /// Kept as a Swift struct — we avoid bridging `PolarCellLayout.Layout` through
    /// the Shared framework to keep the boundary narrow. Same pattern as
    /// `StructuredChartViewerScreen.swift:PolarLayout`.
    private struct PolarLayout {
        let cx: Double
        let cy: Double
        let innerRadius: Double
        let ringThickness: Double
    }

    private static func polarLayout(for size: CGSize, polar: ChartExtentsPolar) -> PolarLayout {
        let cx = Double(size.width / 2)
        let cy = Double(size.height / 2)
        let maxRadius = Double(min(size.width, size.height)) * 0.47
        let innerRadius = maxRadius * 0.15
        let rings = max(1, Int(polar.rings))
        let ringThickness = (maxRadius - innerRadius) / Double(rings)
        return PolarLayout(cx: cx, cy: cy, innerRadius: innerRadius, ringThickness: ringThickness)
    }

    /// Inlined polar hit-test (mirrors `GridHitTest.hitTestPolar` in Kotlin).
    /// Returns nil for inner-hole / outer-of-grid / degenerate cases.
    private static func hitTestPolar(
        location: CGPoint,
        polar: ChartExtentsPolar,
        layout: PolarLayout
    ) -> (stitch: Int, ring: Int)? {
        let ringsCount = Int(polar.rings)
        if ringsCount <= 0 { return nil }
        if layout.ringThickness <= 0 || layout.innerRadius < 0 { return nil }
        let perRing = polar.stitchesPerRing.map { Int(truncating: $0) }
        if perRing.count < ringsCount { return nil }

        let dx = Double(location.x) - layout.cx
        let dy = Double(location.y) - layout.cy
        let radius = (dx * dx + dy * dy).squareRoot()
        if radius < layout.innerRadius { return nil }
        let outerRadius = layout.innerRadius + Double(ringsCount) * layout.ringThickness
        if radius >= outerRadius { return nil }

        let ringRaw = Int(floor((radius - layout.innerRadius) / layout.ringThickness))
        let ring = min(max(ringRaw, 0), ringsCount - 1)
        let stitchesInRing = perRing[ring]
        if stitchesInRing <= 0 { return nil }

        let twoPi = 2.0 * Double.pi
        let rawTheta = atan2(dy, dx) + Double.pi / 2
        let theta = (rawTheta.truncatingRemainder(dividingBy: twoPi) + twoPi)
            .truncatingRemainder(dividingBy: twoPi)
        let sweep = twoPi / Double(stitchesInRing)
        let stitchRaw = Int(floor(theta / sweep))
        let stitch = min(max(stitchRaw, 0), stitchesInRing - 1)
        return (stitch: stitch, ring: ring)
    }

    private func drawPolar(into context: inout GraphicsContext, size: CGSize, polar: ChartExtentsPolar) {
        let layout = Self.polarLayout(for: size, polar: polar)
        let gridShading = GraphicsContext.Shading.color(.gray.opacity(0.3))
        let ringsCount = Int(polar.rings)

        // Concentric ring boundaries.
        for i in 0...ringsCount {
            let r = layout.innerRadius + Double(i) * layout.ringThickness
            var path = Path()
            path.addArc(
                center: CGPoint(x: layout.cx, y: layout.cy),
                radius: CGFloat(r),
                startAngle: .zero,
                endAngle: .degrees(360),
                clockwise: false
            )
            context.stroke(path, with: gridShading, lineWidth: 1)
        }

        // Outer-ring radial spokes.
        let outerStitches = Int(polar.stitchesPerRing.last?.intValue ?? 0)
        if outerStitches > 0 {
            let innerR = layout.innerRadius
            let outerR = layout.innerRadius + Double(ringsCount) * layout.ringThickness
            let sweep = 2.0 * Double.pi / Double(outerStitches)
            for s in 0..<outerStitches {
                // 12-o'clock-CW convention → screen cartesian: subtract π/2.
                let screenAngle = Double(s) * sweep - Double.pi / 2.0
                let dx = cos(screenAngle)
                let dy = sin(screenAngle)
                var path = Path()
                path.move(to: CGPoint(x: layout.cx + innerR * dx, y: layout.cy + innerR * dy))
                path.addLine(to: CGPoint(x: layout.cx + outerR * dx, y: layout.cy + outerR * dy))
                context.stroke(path, with: gridShading, lineWidth: 1)
            }
        }

        // Glyphs per cell — inscribed square + radial rotation per ADR-011 §2.
        let symbolShading = GraphicsContext.Shading.color(.primary)
        // Defensive: guards against a polar chart round-tripped through storage
        // with a stitchesPerRing list shorter than rings. hitTestPolar already
        // has this guard; drawPolar needs the mirror so indexed access can't trap.
        let perRing = polar.stitchesPerRing.map { Int(truncating: $0) }
        if perRing.count < ringsCount { return }
        for layer in layers where layer.visible {
            for cell in layer.cells {
                let ring = Int(cell.y)
                let stitch = Int(cell.x)
                if ring < 0 || ring >= ringsCount { continue }
                let stitchesInRing = perRing[ring]
                if stitch < 0 || stitch >= stitchesInRing { continue }
                guard let def = catalog.get(id: cell.symbolId) else { continue }

                let sweep = 2.0 * Double.pi / Double(stitchesInRing)
                let rCenter = layout.innerRadius + (Double(ring) + 0.5) * layout.ringThickness
                // cellCenter mirror: theta = stitch*sweep + sweep/2 (12-o'clock-CW).
                let thetaCenter = Double(stitch) * sweep + sweep / 2.0
                // Screen cartesian: subtract π/2.
                let screenAngle = thetaCenter - Double.pi / 2.0
                let px = layout.cx + rCenter * cos(screenAngle)
                let py = layout.cy + rCenter * sin(screenAngle)
                let chord = 2.0 * rCenter * sin(sweep / 2.0)
                let side = max(1.0, min(layout.ringThickness, chord))
                let half = side / 2.0
                let bounds = CGRect(x: px - half, y: py - half, width: side, height: side)

                var subcontext = context
                subcontext.translateBy(x: CGFloat(px), y: CGFloat(py))
                subcontext.rotate(by: .radians(thetaCenter))
                subcontext.translateBy(x: -CGFloat(px), y: -CGFloat(py))
                drawSymbolPath(
                    into: &subcontext,
                    def: def,
                    bounds: bounds,
                    rotation: Int(cell.rotation),
                    color: symbolShading,
                    lineWidth: max(1, CGFloat(side) * 0.06)
                )
            }
        }
    }

    private struct Layout {
        let cellSize: CGFloat
        let originX: CGFloat
        let originY: CGFloat
    }

    private static func layout(for size: CGSize, rect: ChartExtentsRect) -> Layout {
        let gridWidth = Int(rect.maxX - rect.minX + 1)
        let gridHeight = Int(rect.maxY - rect.minY + 1)
        let cell = max(1, min(size.width / CGFloat(gridWidth), size.height / CGFloat(gridHeight)))
        let drawW = cell * CGFloat(gridWidth)
        let drawH = cell * CGFloat(gridHeight)
        let originX = (size.width - drawW) / 2
        let originY = (size.height - drawH) / 2
        return Layout(cellSize: cell, originX: originX, originY: originY)
    }

    private func draw(into context: inout GraphicsContext, size: CGSize, rect: ChartExtentsRect) {
        let layout = Self.layout(for: size, rect: rect)
        let gridWidth = Int(rect.maxX - rect.minX + 1)
        let gridHeight = Int(rect.maxY - rect.minY + 1)

        let gridColor = GraphicsContext.Shading.color(.gray.opacity(0.3))
        for gx in 0...gridWidth {
            let x = layout.originX + CGFloat(gx) * layout.cellSize
            var path = Path()
            path.move(to: CGPoint(x: x, y: layout.originY))
            path.addLine(to: CGPoint(x: x, y: layout.originY + CGFloat(gridHeight) * layout.cellSize))
            context.stroke(path, with: gridColor, lineWidth: 1)
        }
        for gy in 0...gridHeight {
            let y = layout.originY + CGFloat(gy) * layout.cellSize
            var path = Path()
            path.move(to: CGPoint(x: layout.originX, y: y))
            path.addLine(to: CGPoint(x: layout.originX + CGFloat(gridWidth) * layout.cellSize, y: y))
            context.stroke(path, with: gridColor, lineWidth: 1)
        }

        let strokeWidth = max(1, layout.cellSize * 0.06)
        let symbolColor = GraphicsContext.Shading.color(.primary)

        for layer in layers {
            if !layer.visible { continue }
            for cell in layer.cells {
                let bounds = Self.cellRect(
                    cell: cell,
                    rect: rect,
                    gridHeight: gridHeight,
                    cellSize: layout.cellSize,
                    originX: layout.originX,
                    originY: layout.originY
                )
                guard let def = catalog.get(id: cell.symbolId) else { continue }
                drawSymbolPath(
                    into: &context,
                    def: def,
                    bounds: bounds,
                    rotation: Int(cell.rotation),
                    color: symbolColor,
                    lineWidth: strokeWidth
                )
            }
        }
    }

    private static func cellRect(
        cell: ChartCell,
        rect: ChartExtentsRect,
        gridHeight: Int,
        cellSize: CGFloat,
        originX: CGFloat,
        originY: CGFloat
    ) -> CGRect {
        let gx = Int(cell.x - rect.minX)
        let gy = Int(cell.y - rect.minY)
        let left = originX + CGFloat(gx) * cellSize
        let bottom = originY + CGFloat(gridHeight - gy) * cellSize
        let top = bottom - CGFloat(cell.height) * cellSize
        let right = left + CGFloat(cell.width) * cellSize
        return CGRect(x: left, y: top, width: right - left, height: bottom - top)
    }

    private func drawSymbolPath(
        into context: inout GraphicsContext,
        def: SymbolDefinition,
        bounds: CGRect,
        rotation: Int,
        color: GraphicsContext.Shading,
        lineWidth: CGFloat
    ) {
        let cellBounds = CellBounds(
            left: Double(bounds.minX),
            top: Double(bounds.minY),
            right: Double(bounds.maxX),
            bottom: Double(bounds.maxY)
        )
        let commands = SvgPathParser.shared.parse(pathData: def.pathData)
        var path = Path()
        for raw in commands {
            let mapped = SymbolRenderTransform.shared.mapCommand(
                command: raw,
                bounds: cellBounds,
                rotation: Int32(rotation)
            )
            switch mapped {
            case let move as PathCommandMoveTo:
                path.move(to: CGPoint(x: move.x, y: move.y))
            case let line as PathCommandLineTo:
                path.addLine(to: CGPoint(x: line.x, y: line.y))
            case let curve as PathCommandCurveTo:
                path.addCurve(
                    to: CGPoint(x: curve.x, y: curve.y),
                    control1: CGPoint(x: curve.c1x, y: curve.c1y),
                    control2: CGPoint(x: curve.c2x, y: curve.c2y)
                )
            case let quad as PathCommandQuadTo:
                path.addQuadCurve(
                    to: CGPoint(x: quad.x, y: quad.y),
                    control: CGPoint(x: quad.c1x, y: quad.c1y)
                )
            case is PathCommandClosePath:
                path.closeSubpath()
            default:
                break
            }
        }
        if def.fill {
            context.fill(path, with: color)
        } else {
            context.stroke(
                path,
                with: color,
                style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round)
            )
        }
    }
}

// MARK: - Palette

/// Shared per-symbol SVG-parse cache so the palette does not re-parse every glyph
/// on each SwiftUI redraw. Reference-typed so writes from `Canvas`'s draw closure
/// do not invalidate the SwiftUI state graph.
private final class PalettePathCache: ObservableObject {
    private var entries: [String: [PathCommand]] = [:]

    func get(id: String, parser: () -> [PathCommand]) -> [PathCommand] {
        if let cached = entries[id] { return cached }
        let parsed = parser()
        entries[id] = parsed
        return parsed
    }
}

private struct SymbolPaletteView: View {
    let selectedCategory: SymbolCategory
    let availableCategories: [SymbolCategory]
    let symbols: [SymbolDefinition]
    let selectedSymbolId: String?
    let onCategorySelected: (SymbolCategory) -> Void
    let onSymbolSelected: (String?) -> Void
    @StateObject private var pathCache = PalettePathCache()

    var body: some View {
        VStack(spacing: 4) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(availableCategories, id: \.name) { category in
                        Button {
                            onCategorySelected(category)
                        } label: {
                            Text(category.enLabel)
                                .font(.caption)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(
                                    Capsule().fill(
                                        selectedCategory == category
                                            ? Color.accentColor.opacity(0.25)
                                            : Color.gray.opacity(0.1)
                                    )
                                )
                        }
                    }
                }
                .padding(.horizontal, 8)
            }
            .frame(height: 32)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    PaletteEraserCell(selected: selectedSymbolId == nil) {
                        onSymbolSelected(nil)
                    }
                    ForEach(symbols, id: \.id) { def in
                        PaletteSymbolCell(
                            def: def,
                            selected: def.id == selectedSymbolId,
                            pathCache: pathCache
                        ) {
                            onSymbolSelected(def.id)
                        }
                    }
                }
                .padding(.horizontal, 8)
            }
        }
        .accessibilityIdentifier("symbolPalette")
    }
}

private struct PaletteEraserCell: View {
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "eraser")
                .font(.title3)
                .foregroundStyle(.secondary)
                .frame(width: 56, height: 56)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color(.secondarySystemBackground)))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(selected ? Color.accentColor : Color.gray.opacity(0.3),
                                lineWidth: selected ? 2 : 1)
                )
        }
        .accessibilityIdentifier("paletteEraser")
    }
}

private struct PaletteSymbolCell: View {
    let def: SymbolDefinition
    let selected: Bool
    let pathCache: PalettePathCache
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Canvas { context, size in
                let bounds = CGRect(x: 8, y: 8, width: size.width - 16, height: size.height - 16)
                let cellBounds = CellBounds(
                    left: Double(bounds.minX),
                    top: Double(bounds.minY),
                    right: Double(bounds.maxX),
                    bottom: Double(bounds.maxY)
                )
                let commands = pathCache.get(id: def.id) {
                    SvgPathParser.shared.parse(pathData: def.pathData)
                }
                var path = Path()
                for raw in commands {
                    let mapped = SymbolRenderTransform.shared.mapCommand(
                        command: raw,
                        bounds: cellBounds,
                        rotation: 0
                    )
                    switch mapped {
                    case let move as PathCommandMoveTo:
                        path.move(to: CGPoint(x: move.x, y: move.y))
                    case let line as PathCommandLineTo:
                        path.addLine(to: CGPoint(x: line.x, y: line.y))
                    case let curve as PathCommandCurveTo:
                        path.addCurve(
                            to: CGPoint(x: curve.x, y: curve.y),
                            control1: CGPoint(x: curve.c1x, y: curve.c1y),
                            control2: CGPoint(x: curve.c2x, y: curve.c2y)
                        )
                    case let quad as PathCommandQuadTo:
                        path.addQuadCurve(
                            to: CGPoint(x: quad.x, y: quad.y),
                            control: CGPoint(x: quad.c1x, y: quad.c1y)
                        )
                    case is PathCommandClosePath:
                        path.closeSubpath()
                    default:
                        break
                    }
                }
                let shading = GraphicsContext.Shading.color(.primary)
                if def.fill {
                    context.fill(path, with: shading)
                } else {
                    context.stroke(
                        path,
                        with: shading,
                        style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
                    )
                }
            }
            .frame(width: 56, height: 56)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color(.systemBackground)))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(selected ? Color.accentColor : Color.gray.opacity(0.3),
                            lineWidth: selected ? 2 : 1)
            )
        }
        .accessibilityIdentifier("paletteSymbol_\(def.id)")
    }
}

// MARK: - Parametric symbol input

/// Inline input sheet shown when placing or re-editing a parametric cell (ADR-009 §7).
/// Mirrors the Compose `ParameterInputDialog`.
private struct ParameterInputSheet: View {
    let pending: PendingParameterInput
    let onConfirm: ([String: String]) -> Void
    let onCancel: () -> Void

    @State private var drafts: [String: String] = [:]

    var body: some View {
        NavigationStack {
            Form {
                ForEach(pending.slots, id: \.key) { slot in
                    TextField(
                        slot.enLabel,
                        text: Binding(
                            get: { drafts[slot.key] ?? "" },
                            set: { drafts[slot.key] = $0 }
                        )
                    )
                    .accessibilityIdentifier("parameterInput_\(slot.key)")
                }
            }
            .navigationTitle(pending.isEditingExisting ? "Edit parameter" : "Enter parameter")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onCancel() }
                        .accessibilityIdentifier("parameterCancelButton")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("OK") { onConfirm(drafts) }
                        .accessibilityIdentifier("parameterConfirmButton")
                }
            }
        }
        .onAppear {
            // Seed with current values (re-edit) or defaults (new placement).
            var initial: [String: String] = [:]
            for slot in pending.slots {
                if let v = pending.currentValues[slot.key] {
                    initial[slot.key] = v
                } else if let d = slot.defaultValue {
                    initial[slot.key] = d
                } else {
                    initial[slot.key] = ""
                }
            }
            drafts = initial
        }
    }
}

// MARK: - Polar extents picker (Phase 35.2a)

/// New-chart-only polar extents input. Mirrors Compose `PolarExtentsDialog`.
/// Requires rings ≥ 1 and a comma-separated list of `rings` positive integers.
private struct PolarExtentsSheet: View {
    let onConfirm: (ChartExtentsPolar) -> Void
    let onCancel: () -> Void

    @State private var ringsText: String = "3"
    @State private var stitchesText: String = "8,16,24"

    private var parsed: ChartExtentsPolar? {
        guard let rings = Int(ringsText.trimmingCharacters(in: .whitespaces)), rings >= 1 else {
            return nil
        }
        let perRing: [Int] = stitchesText
            .split(separator: ",")
            .compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
        guard perRing.count == rings, perRing.allSatisfy({ $0 >= 1 }) else { return nil }
        return ChartExtentsPolar(
            rings: Int32(rings),
            stitchesPerRing: perRing.map { NSNumber(value: Int32($0)) }
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField(LocalizedStringKey("label_polar_rings"), text: $ringsText)
                    .keyboardType(.numberPad)
                    .accessibilityIdentifier("polarRingsInput")
                TextField(LocalizedStringKey("label_polar_stitches_per_ring"), text: $stitchesText)
                    .accessibilityIdentifier("polarStitchesInput")
            }
            .navigationTitle(LocalizedStringKey("dialog_polar_extents_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(LocalizedStringKey("action_cancel")) { onCancel() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(LocalizedStringKey("action_ok")) {
                        if let p = parsed { onConfirm(p) }
                    }
                    .disabled(parsed == nil)
                    .accessibilityIdentifier("polarExtentsConfirmButton")
                }
            }
        }
    }
}
