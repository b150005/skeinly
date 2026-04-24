import SwiftUI
import Shared

/// SwiftUI counterpart of `ChartViewerScreen`. Owns a Koin-resolved
/// `ChartViewerViewModel` via `ScopedViewModel` so the observed state survives
/// parent re-inits. Phase 34 adds per-segment progress overlay + tap/long-press
/// gestures when `projectId` is non-null.
struct StructuredChartViewerScreen: View {
    let patternId: String
    let projectId: String?
    @StateObject private var holder: ScopedViewModel<ChartViewerViewModel, ChartViewerState>
    private let catalog: SymbolCatalog

    init(patternId: String, projectId: String?) {
        self.patternId = patternId
        self.projectId = projectId
        let vm = ViewModelFactory.chartViewerViewModel(patternId: patternId, projectId: projectId)
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
                    LocalizedStringKey("state_no_structured_chart"),
                    systemImage: "square.grid.3x3"
                )
            }
        }
        .navigationTitle(LocalizedStringKey("title_chart_viewer"))
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
                                Text(verbatim: layer.name)
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
                hiddenLayerIds: holder.state.hiddenLayerIds,
                segmentLookup: { layerId, x, y in
                    viewModel.segmentStateAt(layerId: layerId, x: Int32(x), y: Int32(y))
                },
                segmentsVersion: holder.state.segments.count,
                onTap: { layerId, x, y in
                    viewModel.onEvent(event: ChartViewerEventTapCell(layerId: layerId, x: Int32(x), y: Int32(y)))
                },
                onLongPress: { layerId, x, y in
                    let generator = UIImpactFeedbackGenerator(style: .medium)
                    generator.impactOccurred()
                    viewModel.onEvent(event: ChartViewerEventLongPressCell(layerId: layerId, x: Int32(x), y: Int32(y)))
                },
                onMarkRowDone: { row in
                    let generator = UIImpactFeedbackGenerator(style: .medium)
                    generator.impactOccurred()
                    viewModel.onEvent(event: ChartViewerEventMarkRowDone(row: Int32(row)))
                }
            )
            .accessibilityIdentifier("segmentOverlay")
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// Mutable cache for parsed SVG path commands keyed by symbol id.
private final class PathCommandCache: ObservableObject {
    private var entries: [String: [PathCommand]] = [:]

    func get(id: String, parser: () -> [PathCommand]) -> [PathCommand] {
        if let cached = entries[id] { return cached }
        let parsed = parser()
        entries[id] = parsed
        return parsed
    }
}

/// Canvas with overlay + tap/long-press support. Canvas geometry is recomputed
/// on every draw so hit-test math stays in lockstep. The gesture layer sits in
/// parallel via `.simultaneousGesture` so pinch-to-zoom keeps working.
private struct ChartCanvasView: View {
    let chart: StructuredChart
    let catalog: SymbolCatalog
    let hiddenLayerIds: Set<String>
    /// Reads segment state by (layerId, x, y) — routes through the Kotlin
    /// helper to avoid bridging a Kotlin `Map<SegmentKey, SegmentState>`
    /// through Swift `Hashable`.
    let segmentLookup: (String, Int, Int) -> SegmentState?
    /// Sentinel value that changes whenever the segment map changes — forces
    /// SwiftUI to re-evaluate the Canvas closure when the lookup result flips.
    let segmentsVersion: Int
    let onTap: (String, Int, Int) -> Void
    let onLongPress: (String, Int, Int) -> Void
    let onMarkRowDone: (Int) -> Void

    /// Reserved left-gutter width for rect row-number labels. Mirrors Kotlin
    /// `RECT_ROW_LABEL_GUTTER_PX` in ChartViewerScreen.kt — update in lock-step.
    private let rectRowLabelGutter: CGFloat = 28

    /// Half-width of the polar ring-label hit rectangle around the 12 o'clock
    /// diameter. Mirrors Kotlin `POLAR_RING_LABEL_HALF_W_PX`.
    private let polarRingLabelHalfW: CGFloat = 16

    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    @State private var canvasSize: CGSize = .zero
    /// Flag set briefly when a long-press fires so the subsequent `onTapGesture`
    /// callback can short-circuit. Without this, SwiftUI fires both gesture
    /// recognizers on long-press release and the tap handler immediately
    /// cycles the now-done cell back to todo.
    @State private var longPressActive = false
    @StateObject private var pathCache = PathCommandCache()

    private let minScale: CGFloat = 0.5
    private let maxScale: CGFloat = 8.0

    var body: some View {
        GeometryReader { proxy in
            Canvas { context, size in
                if let rect = chart.extents as? ChartExtentsRect {
                    if rect.maxX < rect.minX || rect.maxY < rect.minY { return }
                    draw(into: &context, size: size, rect: rect)
                } else if let polar = chart.extents as? ChartExtentsPolar {
                    let rings = Int(polar.rings)
                    let perRing = polar.stitchesPerRing.map { Int(truncating: $0) }
                    if rings <= 0 || perRing.count < rings || perRing.contains(where: { $0 <= 0 }) {
                        return
                    }
                    drawPolar(into: &context, size: size, polar: polar, ringsCount: rings, stitchesPerRing: perRing)
                }
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
                        .onEnded { _ in lastScale = scale },
                    DragGesture()
                        .onChanged { value in
                            offset = CGSize(
                                width: lastOffset.width + value.translation.width,
                                height: lastOffset.height + value.translation.height
                            )
                        }
                        .onEnded { _ in lastOffset = offset }
                )
            )
            .onAppear { canvasSize = proxy.size }
            .onChange(of: proxy.size) { _, newSize in canvasSize = newSize }
            .background(Color(.systemBackground))
            .clipped()
            // Phase 34 gestures — coexist with pinch/drag via .simultaneousGesture.
            // Phase 35.1d wired polar through `resolveAnyHit` so both rect and polar
            // taps dispatch to the ViewModel.
            .contentShape(Rectangle())
            .simultaneousGesture(
                LongPressGesture(minimumDuration: 0.5)
                    .sequenced(before: DragGesture(minimumDistance: 0))
                    .onEnded { value in
                        if case .second(true, let drag) = value, let d = drag {
                            longPressActive = true
                            handleLongPress(at: d.startLocation)
                            // Defer the clear so the subsequent onTapGesture that
                            // SwiftUI fires on finger-lift is suppressed.
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                                longPressActive = false
                            }
                        }
                    }
            )
            .onTapGesture { location in
                guard !longPressActive else { return }
                handleTap(at: location)
            }
        }
    }

    /// Converts a gesture-space tap location into the Canvas's unscaled/unpanned
    /// coordinate space. `.scaleEffect` + `.offset` are render-only transforms;
    /// the gesture recognizer delivers coordinates in the view's layout space.
    /// Without inverting the transform, hit-tests at any zoom ≠ 1.0 or non-zero
    /// pan land on the wrong cell.
    private func toCanvasSpace(_ location: CGPoint) -> (point: CGPoint, size: CGSize) {
        let safeScale = scale == 0 ? 1 : scale
        let adjusted = CGPoint(
            x: (location.x - offset.width) / safeScale,
            y: (location.y - offset.height) / safeScale,
        )
        let adjustedSize = CGSize(
            width: canvasSize.width / safeScale,
            height: canvasSize.height / safeScale,
        )
        return (adjusted, adjustedSize)
    }

    private func handleTap(at location: CGPoint) {
        let (p, s) = toCanvasSpace(location)
        guard let hit = resolveAnyHit(location: p, size: s) else { return }
        onTap(hit.layerId, hit.x, hit.y)
    }

    private func handleLongPress(at location: CGPoint) {
        let (p, s) = toCanvasSpace(location)
        // Phase 35.2d: label hits take priority over cell hits so long-press on
        // the row/ring number dispatches MarkRowDone.
        if let row = resolveAnyLabelHit(location: p, size: s) {
            onMarkRowDone(row)
            return
        }
        guard let hit = resolveAnyHit(location: p, size: s) else { return }
        onLongPress(hit.layerId, hit.x, hit.y)
    }

    /// Dispatches to the rect row-label or polar ring-label hit-test. Returns
    /// the chart y-coordinate (rect) or ring index (polar) — both map to
    /// `ChartCell.y` without reinterpretation per ADR-010 §4.
    private func resolveAnyLabelHit(location: CGPoint, size: CGSize) -> Int? {
        if let rect = chart.extents as? ChartExtentsRect {
            return resolveRowLabelHit(location: location, size: size, rect: rect)
        } else if let polar = chart.extents as? ChartExtentsPolar {
            return resolvePolarRingLabelHit(location: location, size: size, polar: polar)
        }
        return nil
    }

    /// Hit-test the reserved left gutter for rect row-number labels.
    /// Returns the chart y-coordinate (row, offset by `rect.minY`) or nil.
    private func resolveRowLabelHit(location: CGPoint, size: CGSize, rect: ChartExtentsRect) -> Int? {
        let gridWidth = Int(rect.maxX - rect.minX + 1)
        let gridHeight = Int(rect.maxY - rect.minY + 1)
        let availableW = max(1, size.width - rectRowLabelGutter)
        let cellSize = max(
            1,
            min(availableW / CGFloat(gridWidth), size.height / CGFloat(gridHeight))
        )
        let drawW = cellSize * CGFloat(gridWidth)
        let drawH = cellSize * CGFloat(gridHeight)
        let originX = rectRowLabelGutter + (availableW - drawW) / 2
        let originY = (size.height - drawH) / 2
        if location.x < 0 || location.x >= originX { return nil }
        if location.y < originY { return nil }
        if location.y >= originY + drawH { return nil }
        let rowFromTop = Int(floor((location.y - originY) / cellSize))
        let gy = gridHeight - 1 - rowFromTop
        if gy < 0 || gy >= gridHeight { return nil }
        return Int(rect.minY) + gy
    }

    /// Hit-test the polar ring-label column along the 12 o'clock diameter.
    /// Returns the ring index (0-based, matches `ChartCell.y` storage) or nil.
    private func resolvePolarRingLabelHit(location: CGPoint, size: CGSize, polar: ChartExtentsPolar) -> Int? {
        let ringsCount = Int(polar.rings)
        if ringsCount <= 0 { return nil }
        let layout = polarLayout(for: polar, canvasSize: size, ringsCount: ringsCount)
        if location.x < layout.cx - polarRingLabelHalfW { return nil }
        if location.x >= layout.cx + polarRingLabelHalfW { return nil }
        if location.y >= layout.cy { return nil }
        let dy = Double(layout.cy - location.y)
        let innerR = Double(layout.innerRadius)
        let ringThickness = Double(layout.ringThickness)
        let outerR = innerR + Double(ringsCount) * ringThickness
        if dy < innerR || dy >= outerR { return nil }
        let ring = Int(floor((dy - innerR) / ringThickness))
        return min(max(ring, 0), ringsCount - 1)
    }

    /// Dispatches to the rect or polar hit-test depending on the chart extents.
    /// Matches the Kotlin `resolveHit` / `resolvePolarHit` split.
    private func resolveAnyHit(location: CGPoint, size: CGSize) -> HitResult? {
        if let rect = chart.extents as? ChartExtentsRect {
            return resolveHit(location: location, size: size, rect: rect)
        } else if let polar = chart.extents as? ChartExtentsPolar {
            return resolvePolarHit(location: location, size: size, polar: polar)
        }
        return nil
    }

    private struct HitResult { let layerId: String; let x: Int; let y: Int }

    private func resolveHit(location: CGPoint, size: CGSize, rect: ChartExtentsRect) -> HitResult? {
        let gridWidth = Int(rect.maxX - rect.minX + 1)
        let gridHeight = Int(rect.maxY - rect.minY + 1)
        // Reserve the left gutter for row labels (Phase 35.2d). Mirrors the
        // Kotlin `computeViewerLayout` math to keep hit-test and draw in lockstep.
        let availableW = max(1, size.width - rectRowLabelGutter)
        let cellSize = max(
            1,
            min(availableW / CGFloat(gridWidth), size.height / CGFloat(gridHeight))
        )
        let drawW = cellSize * CGFloat(gridWidth)
        let drawH = cellSize * CGFloat(gridHeight)
        let originX = rectRowLabelGutter + (availableW - drawW) / 2
        let originY = (size.height - drawH) / 2

        // GridHitTest.hitTest semantics — mirror Kotlin for consistency.
        let localX = location.x - originX
        let localY = location.y - originY
        if localX < 0 || localY < 0 { return nil }
        if localX >= cellSize * CGFloat(gridWidth) { return nil }
        if localY >= cellSize * CGFloat(gridHeight) { return nil }
        let gx = Int(floor(localX / cellSize))
        let rowFromTop = Int(floor(localY / cellSize))
        let gy = gridHeight - 1 - rowFromTop
        let cellX = Int(rect.minX) + gx
        let cellY = Int(rect.minY) + gy

        // Top-most visible layer that has a drawn cell at (cellX, cellY) wins.
        for layer in chart.layers.reversed() {
            if !layer.visible || hiddenLayerIds.contains(layer.id) { continue }
            if layer.cells.contains(where: { Int($0.x) == cellX && Int($0.y) == cellY }) {
                return HitResult(layerId: layer.id, x: cellX, y: cellY)
            }
        }
        return nil
    }

    /// Polar analog of `resolveHit`. Mirrors Kotlin `GridHitTest.hitTestPolar`:
    /// 12-o'clock-CW-positive convention, inner-hole tap returns nil, outer
    /// boundary exclusive. `cell.x = stitch`, `cell.y = ring` — same storage
    /// convention the Phase 35.1b/c overlay + glyph passes use.
    private func resolvePolarHit(location: CGPoint, size: CGSize, polar: ChartExtentsPolar) -> HitResult? {
        let ringsCount = Int(polar.rings)
        if ringsCount <= 0 { return nil }
        let perRing = polar.stitchesPerRing.map { Int(truncating: $0) }
        if perRing.count < ringsCount || perRing.contains(where: { $0 <= 0 }) { return nil }

        let layout = polarLayout(for: polar, canvasSize: size, ringsCount: ringsCount)
        if layout.ringThickness <= 0 || layout.innerRadius < 0 { return nil }

        let dx = Double(location.x - layout.cx)
        let dy = Double(location.y - layout.cy)
        let radius = (dx * dx + dy * dy).squareRoot()
        if radius < Double(layout.innerRadius) { return nil }
        let outerRadius = Double(layout.innerRadius) + Double(ringsCount) * Double(layout.ringThickness)
        if radius >= outerRadius { return nil }

        let ringRaw = Int(floor((radius - Double(layout.innerRadius)) / Double(layout.ringThickness)))
        let ring = min(max(ringRaw, 0), ringsCount - 1) // defensive against fp boundary rounding
        let stitchesInRing = perRing[ring]
        if stitchesInRing <= 0 { return nil }

        // Screen atan2 runs CW from +x (3 o'clock); adding π/2 shifts origin to
        // 12 o'clock, mod 2π normalizes.
        let twoPi = 2.0 * Double.pi
        let rawTheta = atan2(dy, dx) + Double.pi / 2
        // Mirror Kotlin's `((rawTheta % 2π) + 2π) % 2π` for positive normalization.
        let theta = (rawTheta.truncatingRemainder(dividingBy: twoPi) + twoPi)
            .truncatingRemainder(dividingBy: twoPi)

        let sweep = twoPi / Double(stitchesInRing)
        let stitchRaw = Int(floor(theta / sweep))
        let stitch = min(max(stitchRaw, 0), stitchesInRing - 1)

        for layer in chart.layers.reversed() {
            if !layer.visible || hiddenLayerIds.contains(layer.id) { continue }
            if layer.cells.contains(where: { Int($0.x) == stitch && Int($0.y) == ring }) {
                return HitResult(layerId: layer.id, x: stitch, y: ring)
            }
        }
        return nil
    }

    private func draw(into context: inout GraphicsContext, size: CGSize, rect: ChartExtentsRect) {
        let gridWidth = Int(rect.maxX - rect.minX + 1)
        let gridHeight = Int(rect.maxY - rect.minY + 1)
        // Reserve the left gutter (Phase 35.2d). Shared with resolveHit so the
        // tap coords and drawn cells agree.
        let availableW = max(1, size.width - rectRowLabelGutter)
        let cellSize = max(
            1,
            min(availableW / CGFloat(gridWidth), size.height / CGFloat(gridHeight))
        )
        let drawW = cellSize * CGFloat(gridWidth)
        let drawH = cellSize * CGFloat(gridHeight)
        let originX = rectRowLabelGutter + (availableW - drawW) / 2
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
        // Per PRD AC-1.1 — done → filled 20% primary (SwiftUI has no true onSurface);
        // wip → 2pt outline in accent.
        let segmentDoneShading = GraphicsContext.Shading.color(.primary.opacity(0.2))
        let segmentWipShading = GraphicsContext.Shading.color(.accentColor)

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

                // Paint overlay under the glyph (AC-1.2). The rect draw path is
                // only reached for rect extents; polar routes through `drawPolar`
                // which has its own segment overlay. No `!isPolar` guard needed here.
                if let s = segmentLookup(layer.id, Int(cell.x), Int(cell.y)) {
                    if s == .done {
                        context.fill(Path(bounds), with: segmentDoneShading)
                    } else if s == .wip {
                        context.stroke(Path(bounds), with: segmentWipShading, lineWidth: 2)
                    }
                }
                _ = segmentsVersion // capture to drive re-evaluation when map mutates

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

        // Phase 35.2d: paint row-number labels in the left gutter. Labels are
        // 1-indexed upward from the bottom row per chart y-up convention
        // (docs/en/chart-coordinates.md). Locale-independent digits — no i18n.
        let labelFontPx = max(8, cellSize * 0.35)
        let gutterCenterX = originX - rectRowLabelGutter / 2
        for gy in 0..<gridHeight {
            let rowCenterY = originY + CGFloat(gridHeight - gy) * cellSize - cellSize / 2
            let label = Text(verbatim: "\(gy + 1)")
                .font(.system(size: labelFontPx))
                .foregroundColor(.secondary)
            context.draw(label, at: CGPoint(x: gutterCenterX, y: rowCenterY), anchor: .center)
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
            let text = Text(verbatim: value).font(.system(size: fontSize)).foregroundColor(.accentColor)
            context.draw(text, at: CGPoint(x: anchor.x, y: anchor.y), anchor: .center)
        }
    }

    // MARK: - Polar rendering (ADR-011 §2)

    /// Screen-space layout for a polar chart, parallel to the Kotlin
    /// `PolarCellLayout.Layout` — kept inlined in Swift to avoid bridging the
    /// `PolarCellLayout` object + its `Layout`/`Wedge` data classes through
    /// the Shared framework.
    private struct PolarLayout {
        let cx: CGFloat
        let cy: CGFloat
        let innerRadius: CGFloat
        let ringThickness: CGFloat
    }

    private func polarLayout(for polar: ChartExtentsPolar, canvasSize: CGSize, ringsCount: Int) -> PolarLayout {
        // 0.47 ≈ 94% of the half-extent — leaves a visible margin on the canvas edge.
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

    /// Build an annular-wedge `Path` for the (stitch, ring) wedge in our
    /// 12-o'clock-CW-positive convention.
    /// Outer arc traces CW on screen from startAngle to endAngle, inner arc
    /// traces CCW back — produces a closed annular region.
    private func polarWedgePath(
        stitch: Int,
        ring: Int,
        stitchesInRing: Int,
        layout: PolarLayout
    ) -> Path {
        let sweep = 2.0 * Double.pi / Double(stitchesInRing)
        let startTheta = Double(stitch) * sweep
        let endTheta = startTheta + sweep
        // Shift from 12-o'clock origin to SwiftUI's 3-o'clock origin.
        let startAngleScreen = startTheta - Double.pi / 2
        let endAngleScreen = endTheta - Double.pi / 2
        let innerR = layout.innerRadius + CGFloat(ring) * layout.ringThickness
        let outerR = layout.innerRadius + CGFloat(ring + 1) * layout.ringThickness
        let center = CGPoint(x: layout.cx, y: layout.cy)
        var path = Path()
        // SwiftUI Path.addArc clockwise parameter is defined in the math (y-up)
        // coordinate system. Under SwiftUI's y-down screen coords, clockwise:false
        // renders visually clockwise — matching our CW-positive convention.
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

    private func drawPolar(
        into context: inout GraphicsContext,
        size: CGSize,
        polar: ChartExtentsPolar,
        ringsCount: Int,
        stitchesPerRing: [Int]
    ) {
        let layout = polarLayout(for: polar, canvasSize: size, ringsCount: ringsCount)
        let gridColor = GraphicsContext.Shading.color(.gray.opacity(0.3))
        let segmentDoneShading = GraphicsContext.Shading.color(.primary.opacity(0.2))
        let segmentWipShading = GraphicsContext.Shading.color(.accentColor)

        // Ring boundaries (includes innermost + outermost).
        for i in 0...ringsCount {
            let r = layout.innerRadius + CGFloat(i) * layout.ringThickness
            let rect = CGRect(
                x: layout.cx - r,
                y: layout.cy - r,
                width: r * 2,
                height: r * 2
            )
            context.stroke(Path(ellipseIn: rect), with: gridColor, lineWidth: 1)
        }

        // Radial spokes — outermost ring stitch count, per-ring spokes is a
        // Phase 35.x polish item per ADR-011 §2.
        if let outerStitches = stitchesPerRing.last, outerStitches > 0 {
            let innerR = layout.innerRadius
            let outerR = layout.innerRadius + CGFloat(ringsCount) * layout.ringThickness
            let sweep = 2.0 * Double.pi / Double(outerStitches)
            for s in 0..<outerStitches {
                // 12-o'clock-CW convention → screen cartesian: subtract π/2.
                let screenAngle = Double(s) * sweep - Double.pi / 2
                let dx = cos(screenAngle)
                let dy = sin(screenAngle)
                var path = Path()
                path.move(to: CGPoint(
                    x: layout.cx + innerR * CGFloat(dx),
                    y: layout.cy + innerR * CGFloat(dy)
                ))
                path.addLine(to: CGPoint(
                    x: layout.cx + outerR * CGFloat(dx),
                    y: layout.cy + outerR * CGFloat(dy)
                ))
                context.stroke(path, with: gridColor, lineWidth: 1)
            }
        }

        // Segment overlay — paint done/wip wedges. Out-of-range cells silently
        // skip, matching the rect renderer's defensive clipping.
        for layer in chart.layers {
            if !layer.visible || hiddenLayerIds.contains(layer.id) { continue }
            for cell in layer.cells {
                let ring = Int(cell.y)
                let stitch = Int(cell.x)
                if ring < 0 || ring >= ringsCount { continue }
                if ring >= stitchesPerRing.count { continue }
                let stitchesInRing = stitchesPerRing[ring]
                if stitch < 0 || stitch >= stitchesInRing { continue }
                guard let state = segmentLookup(layer.id, stitch, ring) else { continue }
                let path = polarWedgePath(
                    stitch: stitch,
                    ring: ring,
                    stitchesInRing: stitchesInRing,
                    layout: layout
                )
                if state == .done {
                    context.fill(path, with: segmentDoneShading)
                } else if state == .wip {
                    context.stroke(path, with: segmentWipShading, lineWidth: 2)
                }
            }
        }
        _ = segmentsVersion // force re-evaluation on segment map mutation

        // Glyph pass — paints on top of the overlay per ADR-011 §2 (matches rect
        // AC-1.2 "overlay under glyph"). Each cell is the largest axis-aligned
        // square that fits inside the wedge at its mid-radius, then rotated CW
        // by the wedge's angular center so the glyph's local "up" points
        // radially outward. `cell.rotation` (author's discrete 0/90/180/270)
        // composes on top via `SymbolRenderTransform.mapCommand`.
        let symbolColor = GraphicsContext.Shading.color(.primary)
        let unknownBg = GraphicsContext.Shading.color(.red.opacity(0.2))
        for layer in chart.layers {
            if !layer.visible || hiddenLayerIds.contains(layer.id) { continue }
            for cell in layer.cells {
                let ring = Int(cell.y)
                let stitch = Int(cell.x)
                if ring < 0 || ring >= ringsCount { continue }
                if ring >= stitchesPerRing.count { continue }
                let stitchesInRing = stitchesPerRing[ring]
                if stitch < 0 || stitch >= stitchesInRing { continue }

                let sweep = 2.0 * Double.pi / Double(stitchesInRing)
                let rCenter = layout.innerRadius + (CGFloat(ring) + 0.5) * layout.ringThickness
                let chord = 2.0 * Double(rCenter) * sin(sweep / 2.0)
                let side = max(1.0, min(Double(layout.ringThickness), chord))
                let half = CGFloat(side) / 2

                // Center of the wedge at r_center — mirrors Kotlin
                // PolarCellLayout.cellCenter (12-o'clock-CW, screen-angle = θ − π/2).
                // `thetaCenter` mirrors `PolarCellLayout.cellRadialUpRotation` — update in
                // lock-step when that formula changes (e.g. Phase 35.x widthUnits > 1).
                let thetaCenter = Double(stitch) * sweep + sweep / 2
                let screenAngle = thetaCenter - Double.pi / 2
                let px = layout.cx + rCenter * CGFloat(cos(screenAngle))
                let py = layout.cy + rCenter * CGFloat(sin(screenAngle))
                let bounds = CGRect(x: px - half, y: py - half, width: half * 2, height: half * 2)
                let strokeWidth = max(1, CGFloat(side) * 0.06)

                // Rotate a subcontext around the cell center so the glyph's local
                // "up" aligns with the radial direction. Positive radians rotate
                // CW on SwiftUI's y-down screen — matches our 12-o'clock-CW convention.
                var subcontext = context
                subcontext.translateBy(x: px, y: py)
                subcontext.rotate(by: .radians(thetaCenter))
                subcontext.translateBy(x: -px, y: -py)

                guard let def = catalog.get(id: cell.symbolId) else {
                    drawUnknown(into: &subcontext, bounds: bounds, fill: unknownBg)
                    continue
                }
                drawSymbolPath(
                    into: &subcontext,
                    def: def,
                    bounds: bounds,
                    rotation: Int(cell.rotation),
                    color: symbolColor,
                    lineWidth: strokeWidth
                )
            }
        }

        // Phase 35.2d: paint ring-number labels along the 12 o'clock diameter
        // at each ring's mid-radius. 1-indexed display; ring 0 renders as "1".
        let fontPx = max(10, layout.ringThickness * 0.4)
        for ring in 0..<ringsCount {
            let rCenter = layout.innerRadius + (CGFloat(ring) + 0.5) * layout.ringThickness
            let py = layout.cy - rCenter
            let label = Text(verbatim: "\(ring + 1)")
                .font(.system(size: fontPx))
                .foregroundColor(.secondary)
            context.draw(label, at: CGPoint(x: layout.cx, y: py), anchor: .center)
        }
    }
}
