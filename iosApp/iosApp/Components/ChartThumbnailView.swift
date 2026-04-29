import SwiftUI
import Shared

/// Phase 36.4.1b (ADR-012 §5): live mini-render of a structured chart as a
/// tappable thumbnail. iOS mirror of the Compose `ChartThumbnail` composable.
///
/// Reuses the rendering primitives in `ChartRenderingKit.swift` (extracted
/// in 36.4.1a) — the third consumer surfacing here was the trigger that
/// closed the inline-helper duplication. Renders grid + symbol glyphs only:
/// no segment overlay, no row/ring labels, no parameter slot text.
///
/// Tap routes through `onTap` to the read-only chart viewer; consumers
/// (currently `DiscoveryScreen`) decide what `Route` to push.
///
/// The cached-PNG thumbnail column on `chart_documents` is permanently
/// scoped to "if telemetry shows perf regression" per ADR-012 §8 — live
/// render is correct (always matches current chart state) and Discovery's
/// `List` keeps the fetch demand-driven (off-screen rows do not fetch).
struct ChartThumbnailView: View {
    let patternId: String
    let onTap: () -> Void
    var size: CGFloat = 64

    @State private var chart: StructuredChart?
    @StateObject private var pathCache = PathCommandCache()
    private let catalog: SymbolCatalog = ViewModelFactory.symbolCatalog()
    private let repository: StructuredChartRepository = ViewModelFactory.structuredChartRepository()

    var body: some View {
        Button(action: onTap) {
            ZStack {
                // Placeholder background — visible during initial fetch and
                // for charts whose extents are degenerate.
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color.gray.opacity(0.12))
                    .frame(width: size, height: size)
                if let chart = chart {
                    Canvas { context, canvasSize in
                        switch chart.extents {
                        case let rect as ChartExtentsRect:
                            if rect.maxX < rect.minX || rect.maxY < rect.minY { return }
                            drawRectThumbnail(into: &context, size: canvasSize, chart: chart, rect: rect)
                        case let polar as ChartExtentsPolar:
                            let rings = Int(polar.rings)
                            let perRing = polar.stitchesPerRing.map { Int(truncating: $0) }
                            if rings <= 0 || perRing.count < rings || perRing.contains(where: { $0 <= 0 }) {
                                return
                            }
                            drawPolarThumbnail(
                                into: &context,
                                size: canvasSize,
                                chart: chart,
                                polar: polar,
                                ringsCount: rings,
                                stitchesPerRing: perRing
                            )
                        default:
                            break
                        }
                    }
                    .frame(width: size, height: size)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(LocalizedStringKey("action_view_chart_thumbnail"))
        .accessibilityIdentifier("chartThumbnail_\(patternId)")
        .task(id: patternId) {
            // Refetch on patternId change. `.task(id:)` cancels any in-flight
            // fetch before re-issuing — the suspend `getByPatternId` is
            // cancellation-aware via Kotlin/Native's auto-generated async
            // bridge. Errors fall back to the placeholder background.
            do {
                chart = try await repository.getByPatternId(patternId: patternId)
            } catch is CancellationError {
                // View left composition mid-fetch (LazyList scroll). Coroutine
                // cleanup propagates through the bridge — no further action.
            } catch {
                // The Discovery list-fetch already named which patterns have
                // charts via `state.patternsWithCharts`; a per-row fetch
                // failure here is transient — fall back to the placeholder.
                chart = nil
            }
        }
    }

    /// Stripped-down rect renderer matching Compose `drawRectThumbnail`:
    /// full canvas (no left gutter), no row labels, no segment overlay, no
    /// parameter slot text.
    @MainActor
    private func drawRectThumbnail(
        into context: inout GraphicsContext,
        size: CGSize,
        chart: StructuredChart,
        rect: ChartExtentsRect
    ) {
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

        let gridColor = GraphicsContext.Shading.color(.gray.opacity(0.4))
        drawRectGrid(
            into: &context,
            gridWidth: gridWidth,
            gridHeight: gridHeight,
            cellSize: cellSize,
            originX: originX,
            originY: originY,
            color: gridColor
        )

        let symbolColor = GraphicsContext.Shading.color(.primary)
        let unknownBg = GraphicsContext.Shading.color(.red.opacity(0.2))
        let strokeWidth = max(1, cellSize * 0.06)
        for layer in chart.layers {
            if !layer.visible { continue }
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
                    context.fill(Path(bounds), with: unknownBg)
                    continue
                }
                drawSymbolPath(
                    into: &context,
                    def: def,
                    bounds: bounds,
                    rotation: Int(cell.rotation),
                    color: symbolColor,
                    lineWidth: strokeWidth,
                    cache: pathCache
                )
            }
        }
    }

    /// Polar thumbnail renderer mirroring the Compose `drawPolarCells` +
    /// `drawPolarGrid` flow at fixed scale. Reuses the same per-cell
    /// inscribed-square geometry as the full-screen polar viewer (Phase
    /// 35.1c) — keeps the visual identity consistent across consumers.
    @MainActor
    private func drawPolarThumbnail(
        into context: inout GraphicsContext,
        size: CGSize,
        chart: StructuredChart,
        polar: ChartExtentsPolar,
        ringsCount: Int,
        stitchesPerRing: [Int]
    ) {
        let layout = polarLayout(canvasSize: size, ringsCount: ringsCount)
        let gridColor = GraphicsContext.Shading.color(.gray.opacity(0.4))

        // Concentric ring boundaries.
        for i in 0...ringsCount {
            let r = layout.innerRadius + CGFloat(i) * layout.ringThickness
            let rectShape = CGRect(
                x: layout.cx - r,
                y: layout.cy - r,
                width: r * 2,
                height: r * 2
            )
            context.stroke(Path(ellipseIn: rectShape), with: gridColor, lineWidth: 1)
        }

        let symbolColor = GraphicsContext.Shading.color(.primary)
        let unknownBg = GraphicsContext.Shading.color(.red.opacity(0.2))
        for layer in chart.layers {
            if !layer.visible { continue }
            for cell in layer.cells {
                let ring = Int(cell.y)
                let stitch = Int(cell.x)
                if ring < 0 || ring >= ringsCount { continue }
                if ring >= stitchesPerRing.count { continue }
                let stitchesInRing = stitchesPerRing[ring]
                if stitch < 0 || stitch >= stitchesInRing { continue }

                let side = polarCellInscribedSide(ring: ring, stitchesInRing: stitchesInRing, layout: layout)
                let half = CGFloat(side) / 2
                let center = polarCellCenter(stitch: stitch, ring: ring, stitchesInRing: stitchesInRing, layout: layout)
                let rotation = polarCellRotation(stitch: stitch, stitchesInRing: stitchesInRing)
                let bounds = CGRect(x: center.x - half, y: center.y - half, width: half * 2, height: half * 2)
                let strokeWidth = max(1, CGFloat(side) * 0.06)

                var subcontext = context
                subcontext.translateBy(x: center.x, y: center.y)
                subcontext.rotate(by: .radians(rotation))
                subcontext.translateBy(x: -center.x, y: -center.y)

                guard let def = catalog.get(id: cell.symbolId) else {
                    drawUnknownGlyph(into: &subcontext, bounds: bounds, fill: unknownBg)
                    continue
                }
                drawSymbolPath(
                    into: &subcontext,
                    def: def,
                    bounds: bounds,
                    rotation: Int(cell.rotation),
                    color: symbolColor,
                    lineWidth: strokeWidth,
                    cache: pathCache
                )
            }
        }
    }
}
