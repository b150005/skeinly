import SwiftUI
import Shared

/// SwiftUI mirror of the shared Compose `ChartDiffScreen` (Phase 37.3, ADR-013 §5 §6).
///
/// Renders two side-by-side `Canvas` views of the base + target charts with
/// SYNCHRONIZED pan + zoom (a single `MagnificationGesture` + `DragGesture`
/// writes to shared `@State` consumed by both panes via `.scaleEffect` +
/// `.offset`). Cell highlights paint UNDER the symbol glyph using a
/// traffic-light palette (green/yellow/red) deliberately distinct from the
/// per-segment overlay palette, per ADR-013 Considered alternatives row 8.
///
/// Initial commit: when `baseRevisionId == nil`, the base pane shows
/// `label_initial_commit` placeholder text instead of an empty canvas.
struct ChartDiffScreen: View {
    let baseRevisionId: String?
    let targetRevisionId: String
    @StateObject private var holder: ScopedViewModel<ChartDiffViewModel, ChartDiffState>
    @State private var showError = false
    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    private let catalog: SymbolCatalog

    private var viewModel: ChartDiffViewModel { holder.viewModel }

    init(baseRevisionId: String?, targetRevisionId: String) {
        self.baseRevisionId = baseRevisionId
        self.targetRevisionId = targetRevisionId
        let vm = ViewModelFactory.chartDiffViewModel(
            baseRevisionId: baseRevisionId,
            targetRevisionId: targetRevisionId
        )
        let wrapper = KoinHelperKt.wrapChartDiffState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
        self.catalog = ViewModelFactory.symbolCatalog()
    }

    var body: some View {
        contentView
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("chartDiffScreen")
            .navigationTitle(LocalizedStringKey("title_chart_diff"))
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: holder.state.error != nil) { _, hasError in
            showError = hasError
        }
            .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
                Button("action_ok") {
                    viewModel.onEvent(event: ChartDiffEventClearError.shared)
                }
            } message: {
                Text(holder.state.error?.localizedString ?? "")
            }
    }

    @ViewBuilder
    private var contentView: some View {
        let state = holder.state
        if state.isLoading {
            ProgressView()
        } else if let diff = state.diff {
            DiffContent(
                diff: diff,
                catalog: catalog,
                scale: $scale,
                lastScale: $lastScale,
                offset: $offset,
                lastOffset: $lastOffset
            )
        } else {
            // Error path — the alert covers the message; render a quiet empty
            // placeholder rather than a competing inline error UI.
            EmptyView()
        }
    }
}

// MARK: - Diff content

private struct DiffContent: View {
    let diff: ChartDiff
    let catalog: SymbolCatalog
    @Binding var scale: CGFloat
    @Binding var lastScale: CGFloat
    @Binding var offset: CGSize
    @Binding var lastOffset: CGSize

    private var classification: DiffClassification { classifyCells(diff: diff) }

    var body: some View {
        VStack(spacing: 8) {
            DiffSummaryRow(diff: diff)
            if !diff.layerChanges.isEmpty {
                LayerChangesBanner(changes: diff.layerChanges)
            }
            if diff.hasNoChanges {
                Text(LocalizedStringKey("state_no_changes"))
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .padding()
                Spacer()
            } else {
                DualCanvasPanel(
                    diff: diff,
                    catalog: catalog,
                    classification: classification,
                    scale: $scale,
                    lastScale: $lastScale,
                    offset: $offset,
                    lastOffset: $lastOffset
                )
            }
        }
    }
}

private struct DiffSummaryRow: View {
    let diff: ChartDiff

    var body: some View {
        HStack {
            Text(
                String(
                    format: NSLocalizedString("label_diff_summary", comment: ""),
                    Int32(diff.addedCellCount),
                    Int32(diff.modifiedCellCount),
                    Int32(diff.removedCellCount)
                )
            )
            .font(.caption)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Capsule().fill(Color.gray.opacity(0.15)))
            .accessibilityIdentifier("diffSummaryChip")
            Spacer()
        }
        .padding(.horizontal)
    }
}

private struct LayerChangesBanner: View {
    let changes: [LayerChange]

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(changes, id: \.layerId) { change in
                Text(verbatim: describeLayerChange(change))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal)
        .accessibilityIdentifier("layerChangesBanner")
    }

    private func describeLayerChange(_ change: LayerChange) -> String {
        switch change {
        case is LayerChangeAdded:
            return NSLocalizedString("label_layer_added", comment: "")
        case is LayerChangeRemoved:
            return NSLocalizedString("label_layer_removed", comment: "")
        case let prop as LayerChangePropertyChanged:
            if prop.before.name != prop.after.name {
                return String(
                    format: NSLocalizedString("label_layer_renamed", comment: ""),
                    prop.before.name,
                    prop.after.name
                )
            } else if prop.before.visible && !prop.after.visible {
                return NSLocalizedString("label_layer_hidden", comment: "")
            } else if !prop.before.visible && prop.after.visible {
                return NSLocalizedString("label_layer_shown", comment: "")
            } else if !prop.before.locked && prop.after.locked {
                return NSLocalizedString("label_layer_locked", comment: "")
            } else if prop.before.locked && !prop.after.locked {
                return NSLocalizedString("label_layer_unlocked", comment: "")
            } else {
                return ""
            }
        default:
            return ""
        }
    }
}

// MARK: - Dual canvas

private struct DualCanvasPanel: View {
    let diff: ChartDiff
    let catalog: SymbolCatalog
    let classification: DiffClassification
    @Binding var scale: CGFloat
    @Binding var lastScale: CGFloat
    @Binding var offset: CGSize
    @Binding var lastOffset: CGSize

    // SwiftUI re-evaluates this struct on every gesture frame. Per-pane
    // `@StateObject` caches survive those re-evaluations; a stored `let`
    // property on the child `DiffCanvas` would not, and would re-parse all
    // symbol SVG paths on every pan / zoom tick. Two separate caches because
    // base + target may reference different symbol sets.
    @StateObject private var basePathCache = PathCommandCache()
    @StateObject private var targetPathCache = PathCommandCache()

    var body: some View {
        let synchronizedGesture = MagnificationGesture()
            .onChanged { value in
                scale = max(0.5, min(8.0, lastScale * value))
            }
            .onEnded { _ in lastScale = scale }
            .simultaneously(
                with: DragGesture()
                    .onChanged { value in
                        offset = CGSize(
                            width: lastOffset.width + value.translation.width,
                            height: lastOffset.height + value.translation.height
                        )
                    }
                    .onEnded { _ in lastOffset = offset }
            )

        HStack(spacing: 4) {
            // Base pane (left) — initial commit shows placeholder text instead.
            ZStack {
                if let baseChart = diff.base {
                    DiffCanvas(
                        chart: baseChart,
                        catalog: catalog,
                        side: .base,
                        classification: classification,
                        pathCache: basePathCache
                    )
                    .scaleEffect(scale)
                    .offset(offset)
                } else {
                    Text(LocalizedStringKey("label_initial_commit"))
                        .font(.body)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("baseChartCanvas")

            DiffCanvas(
                chart: diff.target,
                catalog: catalog,
                side: .target,
                classification: classification,
                pathCache: targetPathCache
            )
            .scaleEffect(scale)
            .offset(offset)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("targetChartCanvas")
        }
        .padding(4)
        .gesture(synchronizedGesture)
    }
}

private enum DiffSide { case base, target }

// MARK: - Diff classification (mirrors Kotlin ChartDiffScreen.classifyCells)

private enum CellHighlight { case added, modified, removed }

private struct CellKey: Hashable {
    let layerId: String
    let x: Int32
    let y: Int32
}

private struct DiffClassification {
    let baseHighlights: [CellKey: CellHighlight]
    let targetHighlights: [CellKey: CellHighlight]
}

private func classifyCells(diff: ChartDiff) -> DiffClassification {
    var base: [CellKey: CellHighlight] = [:]
    var target: [CellKey: CellHighlight] = [:]

    for change in diff.cellChanges {
        switch change {
        case let added as CellChangeAdded:
            target[CellKey(layerId: added.layerId, x: added.cell.x, y: added.cell.y)] = .added
        case let removed as CellChangeRemoved:
            base[CellKey(layerId: removed.layerId, x: removed.cell.x, y: removed.cell.y)] = .removed
        case let modified as CellChangeModified:
            base[CellKey(layerId: modified.layerId, x: modified.before.x, y: modified.before.y)] = .modified
            target[CellKey(layerId: modified.layerId, x: modified.after.x, y: modified.after.y)] = .modified
        default:
            break
        }
    }
    for change in diff.layerChanges {
        switch change {
        case let added as LayerChangeAdded:
            for cell in added.layer.cells {
                target[CellKey(layerId: added.layerId, x: cell.x, y: cell.y)] = .added
            }
        case let removed as LayerChangeRemoved:
            for cell in removed.layer.cells {
                base[CellKey(layerId: removed.layerId, x: cell.x, y: cell.y)] = .removed
            }
        default:
            break
        }
    }
    return DiffClassification(baseHighlights: base, targetHighlights: target)
}

// MARK: - Diff canvas

private struct DiffCanvas: View {
    let chart: StructuredChart
    let catalog: SymbolCatalog
    let side: DiffSide
    let classification: DiffClassification
    // Owned by the parent `DualCanvasPanel` via `@StateObject` so the cache
    // survives view re-evaluation during pan / zoom (a stored property on this
    // struct would re-create on every gesture frame and re-parse all paths).
    let pathCache: PathCommandCache

    var body: some View {
        Canvas { context, size in
            let highlights = side == .base ? classification.baseHighlights : classification.targetHighlights
            switch chart.extents {
            case let rect as ChartExtentsRect:
                drawRect(into: &context, size: size, rect: rect, highlights: highlights)
            case let polar as ChartExtentsPolar:
                drawPolar(into: &context, size: size, polar: polar, highlights: highlights)
            default:
                break
            }
        }
    }

    // Mirror of `ChartDiffScreen.kt drawRectDiff`.
    private func drawRect(
        into context: inout GraphicsContext,
        size: CGSize,
        rect: ChartExtentsRect,
        highlights: [CellKey: CellHighlight]
    ) {
        let gridWidth = Int(rect.maxX - rect.minX + 1)
        let gridHeight = Int(rect.maxY - rect.minY + 1)
        guard gridWidth > 0 && gridHeight > 0 else { return }
        let cellSize = max(1, min(size.width / CGFloat(gridWidth), size.height / CGFloat(gridHeight)))
        let drawW = cellSize * CGFloat(gridWidth)
        let drawH = cellSize * CGFloat(gridHeight)
        let originX = (size.width - drawW) / 2
        let originY = (size.height - drawH) / 2

        let gridColor = GraphicsContext.Shading.color(.gray.opacity(0.25))
        for gx in 0...gridWidth {
            let x = originX + CGFloat(gx) * cellSize
            var path = Path()
            path.move(to: CGPoint(x: x, y: originY))
            path.addLine(to: CGPoint(x: x, y: originY + drawH))
            context.stroke(path, with: gridColor, lineWidth: 1)
        }
        for gy in 0...gridHeight {
            let y = originY + CGFloat(gy) * cellSize
            var path = Path()
            path.move(to: CGPoint(x: originX, y: y))
            path.addLine(to: CGPoint(x: originX + drawW, y: y))
            context.stroke(path, with: gridColor, lineWidth: 1)
        }

        let symbolColor = GraphicsContext.Shading.color(.primary)
        let unknownBg = GraphicsContext.Shading.color(.red.opacity(0.2))
        let strokeWidth = max(1, cellSize * 0.06)
        let addedColor = GraphicsContext.Shading.color(Color(red: 0.2, green: 0.7, blue: 0.3).opacity(0.4))
        let modifiedColor = GraphicsContext.Shading.color(Color(red: 0.95, green: 0.78, blue: 0.1).opacity(0.4))
        let removedColor = GraphicsContext.Shading.color(Color(red: 0.85, green: 0.2, blue: 0.2).opacity(0.4))

        for layer in chart.layers {
            for cell in layer.cells {
                let bounds = cellRect(
                    cell: cell,
                    rect: rect,
                    gridHeight: gridHeight,
                    cellSize: cellSize,
                    originX: originX,
                    originY: originY
                )
                let key = CellKey(layerId: layer.id, x: cell.x, y: cell.y)
                if let highlight = highlights[key] {
                    let color: GraphicsContext.Shading
                    switch highlight {
                    case .added: color = addedColor
                    case .modified: color = modifiedColor
                    case .removed: color = removedColor
                    }
                    context.fill(Path(bounds), with: color)
                }

                guard let def = catalog.get(id: cell.symbolId) else {
                    context.fill(Path(bounds), with: unknownBg)
                    continue
                }
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

    private func drawPolar(
        into context: inout GraphicsContext,
        size: CGSize,
        polar: ChartExtentsPolar,
        highlights: [CellKey: CellHighlight]
    ) {
        let ringsCount = Int(polar.rings)
        let stitchesPerRing = polar.stitchesPerRing.map { Int(truncating: $0) }
        guard ringsCount > 0, stitchesPerRing.count >= ringsCount else { return }
        let layout = polarLayout(polar: polar, canvasSize: size, ringsCount: ringsCount)
        let gridColor = GraphicsContext.Shading.color(.gray.opacity(0.3))

        for i in 0...ringsCount {
            let r = layout.innerRadius + CGFloat(i) * layout.ringThickness
            let rectShape = CGRect(x: layout.cx - r, y: layout.cy - r, width: r * 2, height: r * 2)
            context.stroke(Path(ellipseIn: rectShape), with: gridColor, lineWidth: 1)
        }

        let addedColor = GraphicsContext.Shading.color(Color(red: 0.2, green: 0.7, blue: 0.3).opacity(0.4))
        let modifiedColor = GraphicsContext.Shading.color(Color(red: 0.95, green: 0.78, blue: 0.1).opacity(0.4))
        let removedColor = GraphicsContext.Shading.color(Color(red: 0.85, green: 0.2, blue: 0.2).opacity(0.4))

        for layer in chart.layers {
            for cell in layer.cells {
                let ring = Int(cell.y)
                let stitch = Int(cell.x)
                if ring < 0 || ring >= ringsCount { continue }
                if ring >= stitchesPerRing.count { continue }
                let stitchesInRing = stitchesPerRing[ring]
                if stitch < 0 || stitch >= stitchesInRing { continue }
                guard let highlight = highlights[CellKey(layerId: layer.id, x: cell.x, y: cell.y)] else { continue }
                let color: GraphicsContext.Shading
                switch highlight {
                case .added: color = addedColor
                case .modified: color = modifiedColor
                case .removed: color = removedColor
                }
                let path = polarWedgePath(
                    stitch: stitch,
                    ring: ring,
                    stitchesInRing: stitchesInRing,
                    layout: layout
                )
                context.fill(path, with: color)
            }
        }
    }

    // MARK: - Helpers (mirror StructuredChartViewerScreen primitives, inlined
    // here to avoid a refactor of the gestural ChartCanvasView struct in 37.3.
    // Refactor to a shared helper if a third consumer surfaces — not needed
    // for 37.3 alone.)

    private func cellRect(
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

    private struct PolarLayout {
        let cx: CGFloat
        let cy: CGFloat
        let innerRadius: CGFloat
        let ringThickness: CGFloat
    }

    private func polarLayout(polar: ChartExtentsPolar, canvasSize: CGSize, ringsCount: Int) -> PolarLayout {
        let maxRadius = min(canvasSize.width, canvasSize.height) * 0.47
        let innerRadius = maxRadius * 0.15
        let rings = max(1, ringsCount)
        let ringThickness = (maxRadius - innerRadius) / CGFloat(rings)
        return PolarLayout(
            cx: canvasSize.width / 2,
            cy: canvasSize.height / 2,
            innerRadius: innerRadius,
            ringThickness: ringThickness
        )
    }

    private func polarWedgePath(
        stitch: Int,
        ring: Int,
        stitchesInRing: Int,
        layout: PolarLayout
    ) -> Path {
        let sweep = 2.0 * Double.pi / Double(stitchesInRing)
        let startTheta = Double(stitch) * sweep
        let endTheta = startTheta + sweep
        let startAngleScreen = startTheta - Double.pi / 2
        let endAngleScreen = endTheta - Double.pi / 2
        let innerR = layout.innerRadius + CGFloat(ring) * layout.ringThickness
        let outerR = layout.innerRadius + CGFloat(ring + 1) * layout.ringThickness
        let center = CGPoint(x: layout.cx, y: layout.cy)
        var path = Path()
        path.addArc(
            center: center,
            radius: outerR,
            startAngle: .radians(startAngleScreen),
            endAngle: .radians(endAngleScreen),
            clockwise: false
        )
        path.addArc(
            center: center,
            radius: innerR,
            startAngle: .radians(endAngleScreen),
            endAngle: .radians(startAngleScreen),
            clockwise: true
        )
        path.closeSubpath()
        return path
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
        let commands = pathCache.get(id: def.id) {
            SvgPathParser.shared.parse(pathData: def.pathData)
        }
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

/// Tiny SVG-path parse cache, parallel to the Compose `parsedPathCache` map.
/// Conforms to `ObservableObject` so the owning view can hold it via
/// `@StateObject` — that keeps the cache instance alive across the many view
/// re-evaluations triggered by pan / zoom gestures. A struct stored property
/// would NOT survive those re-evaluations and the cache would be useless
/// (every gesture frame would re-parse all symbol SVG paths).
private final class PathCommandCache: ObservableObject {
    private var storage: [String: [PathCommand]] = [:]

    func get(id: String, compute: () -> [PathCommand]) -> [PathCommand] {
        if let cached = storage[id] { return cached }
        let result = compute()
        storage[id] = result
        return result
    }
}
