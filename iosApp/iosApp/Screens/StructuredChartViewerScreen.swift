import SwiftUI
import Shared

/// Phase 31 SwiftUI counterpart of `ChartViewerScreen` (Compose Multiplatform).
/// Owns a Koin-resolved `ChartViewerViewModel` via `ScopedViewModel` so the
/// observed state survives parent re-inits (see `ScopedViewModel` for context).
struct StructuredChartViewerScreen: View {
    let patternId: String
    @StateObject private var holder: ScopedViewModel<ChartViewerViewModel, ChartViewerState>
    private let catalog: SymbolCatalog

    init(patternId: String) {
        self.patternId = patternId
        let vm = ViewModelFactory.chartViewerViewModel(patternId: patternId)
        let wrapper = KoinHelperKt.wrapChartViewerState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
        self.catalog = ViewModelFactory.symbolCatalog()
    }

    private var viewModel: ChartViewerViewModel { holder.viewModel }

    var body: some View {
        Group {
            if holder.state.isLoading {
                ProgressView()
            } else if let chart = holder.state.chart {
                content(chart: chart)
            } else {
                ContentUnavailableView(
                    "No structured chart available",
                    systemImage: "square.grid.3x3",
                    description: Text("This pattern does not have a chart yet.")
                )
            }
        }
        .navigationTitle("Chart")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func content(chart: StructuredChart) -> some View {
        VStack(spacing: 4) {
            if !chart.layers.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(chart.layers, id: \.id) { layer in
                            let visible = !holder.state.hiddenLayerIds.contains(layer.id)
                            Button {
                                viewModel.onEvent(
                                    event: ChartViewerEventToggleLayer(layerId: layer.id)
                                )
                            } label: {
                                Text(layer.name)
                                    .font(.caption)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(
                                        Capsule().fill(visible ? Color.accentColor.opacity(0.2) : Color.gray.opacity(0.1))
                                    )
                                    .foregroundStyle(visible ? Color.accentColor : .secondary)
                            }
                        }
                    }
                    .padding(.horizontal)
                }
            }

            ChartCanvasView(
                chart: chart,
                catalog: catalog,
                hiddenLayerIds: holder.state.hiddenLayerIds
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// Mutable cache for parsed SVG path commands keyed by symbol id. Held as an
/// `ObservableObject` so writes during `Canvas` drawing do not trigger SwiftUI
/// state-graph invalidations. SwiftUI's `Canvas` always invokes its drawing
/// closure on the main thread, so this is safe to use from there without an
/// explicit `@MainActor` annotation (avoids actor-isolation mismatch with the
/// `Canvas` closure's `nonisolated` signature).
private final class PathCommandCache: ObservableObject {
    private var entries: [String: [PathCommand]] = [:]

    func get(id: String, parser: () -> [PathCommand]) -> [PathCommand] {
        if let cached = entries[id] { return cached }
        let parsed = parser()
        entries[id] = parsed
        return parsed
    }
}

/// Pure-SwiftUI canvas that draws a `StructuredChart` using `Canvas`. Pinch and
/// drag gestures provide zoom/pan; the underlying chart geometry is recomputed
/// on every redraw so the picture stays sharp across scale changes.
private struct ChartCanvasView: View {
    let chart: StructuredChart
    let catalog: SymbolCatalog
    let hiddenLayerIds: Set<String>

    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    /// Cache parsed SVG path commands per symbol id. Reference-typed so writes
    /// from inside `Canvas { ... }` do not mark the SwiftUI state graph dirty.
    /// Symbol path data is immutable per id, so entries never need invalidation.
    @StateObject private var pathCache = PathCommandCache()

    private let minScale: CGFloat = 0.5
    private let maxScale: CGFloat = 8.0

    var body: some View {
        Canvas { context, size in
            guard let rect = chart.extents as? ChartExtentsRect else { return }
            if rect.maxX < rect.minX || rect.maxY < rect.minY { return }
            draw(into: &context, size: size, rect: rect)
        }
        .scaleEffect(scale)
        .offset(offset)
        .gesture(
            SimultaneousGesture(
                MagnificationGesture()
                    .onChanged { value in
                        let proposed = lastScale * value
                        scale = min(max(proposed, minScale), maxScale)
                    }
                    .onEnded { _ in
                        lastScale = scale
                    },
                DragGesture()
                    .onChanged { value in
                        offset = CGSize(
                            width: lastOffset.width + value.translation.width,
                            height: lastOffset.height + value.translation.height
                        )
                    }
                    .onEnded { _ in
                        lastOffset = offset
                    }
            )
        )
        .background(Color(.systemBackground))
        .clipped()
    }

    private func draw(into context: inout GraphicsContext, size: CGSize, rect: ChartExtentsRect) {
        let gridWidth = Int(rect.maxX - rect.minX + 1)
        let gridHeight = Int(rect.maxY - rect.minY + 1)
        let cellSize = max(
            1,
            min(size.width / CGFloat(gridWidth), size.height / CGFloat(gridHeight))
        )
        let drawW = cellSize * CGFloat(gridWidth)
        let drawH = cellSize * CGFloat(gridHeight)
        let originX = (size.width - drawW) / 2
        let originY = (size.height - drawH) / 2

        // Grid background
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

        let strokeWidth = max(1, cellSize * 0.06)
        let symbolColor = GraphicsContext.Shading.color(.primary)
        let unknownBg = GraphicsContext.Shading.color(.red.opacity(0.2))

        for layer in chart.layers {
            if !layer.visible || hiddenLayerIds.contains(layer.id) { continue }
            for cell in layer.cells {
                let bounds = cellRect(
                    cell: cell,
                    rect: rect,
                    gridHeight: gridHeight,
                    cellSize: cellSize,
                    originX: originX,
                    originY: originY
                )

                guard let def = catalog.get(id: cell.symbolId) else {
                    drawUnknown(into: &context, bounds: bounds, fill: unknownBg)
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
                drawParameterSlots(
                    into: &context,
                    def: def,
                    cell: cell,
                    bounds: bounds,
                    cellSize: cellSize
                )
            }
        }
    }

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

    private func drawUnknown(
        into context: inout GraphicsContext,
        bounds: CGRect,
        fill: GraphicsContext.Shading
    ) {
        context.fill(Path(bounds), with: fill)
        let glyphSize = max(8, bounds.height * 0.5)
        let text = Text("?").font(.system(size: glyphSize)).foregroundColor(.red)
        context.draw(text, at: CGPoint(x: bounds.midX, y: bounds.midY), anchor: .center)
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
        context.stroke(
            path,
            with: color,
            style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round)
        )
    }

    private func drawParameterSlots(
        into context: inout GraphicsContext,
        def: SymbolDefinition,
        cell: ChartCell,
        bounds: CGRect,
        cellSize: CGFloat
    ) {
        guard !def.parameterSlots.isEmpty else { return }
        let cellBounds = CellBounds(
            left: Double(bounds.minX),
            top: Double(bounds.minY),
            right: Double(bounds.maxX),
            bottom: Double(bounds.maxY)
        )
        let fontSize = max(8, cellSize * 0.35)
        for slot in def.parameterSlots {
            let value: String
            if let provided = cell.symbolParameters[slot.key] {
                value = provided
            } else if let fallback = slot.defaultValue {
                value = fallback
            } else {
                continue
            }
            let anchor = SymbolRenderTransform.shared.mapPoint(
                unitX: slot.x,
                unitY: slot.y,
                bounds: cellBounds,
                rotation: Int32(cell.rotation)
            )
            let text = Text(value).font(.system(size: fontSize)).foregroundColor(.accentColor)
            context.draw(text, at: CGPoint(x: anchor.x, y: anchor.y), anchor: .center)
        }
    }
}
