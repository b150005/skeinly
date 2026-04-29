import SwiftUI
import Shared

/// Shared rendering primitives for SwiftUI Canvas-based chart drawing. Extracted
/// from `ChartCanvasView` (StructuredChartViewerScreen) and `DiffCanvas`
/// (ChartDiffScreen) which previously held identical inline copies; the third
/// consumer (Phase 36.4.1 `ChartThumbnailView`) triggered the extraction per
/// the in-code "if a third consumer surfaces" comment that ChartDiffScreen
/// landed in Phase 37.3.
///
/// All helpers are pure (no SwiftUI state, no GraphicsContext capture) except
/// `PathCommandCache` which carries an instance-scoped parse cache. Callers own
/// the cache via `@StateObject` so SwiftUI keeps it alive across Canvas
/// re-evaluations without leaking across views.
///
/// Parity contract: the geometry / cell-rect / polar wedge math here is the
/// load-bearing mirror of Kotlin's `SymbolDrawing.kt` + `PolarDrawing.kt` +
/// `domain/chart/PolarCellLayout.kt`. Update both sides in lock-step when the
/// underlying conventions (12-o'clock-CW polar, y-up cell coords) change.

/// Per-render parse cache for symbol path commands. SVG parsing via
/// `SvgPathParser.shared.parse` is non-trivial, and a chart Canvas re-evaluates
/// each frame during pinch/drag — the cache amortizes parsing across frames.
///
/// `@MainActor` matches the actual ownership pattern: every consumer holds the
/// cache via `@StateObject` on a SwiftUI View (which is main-actor isolated),
/// and `get(...)` is called from inside `Canvas { context, size in ... }`
/// closures that run on the main actor. The annotation makes Swift 6 strict
/// concurrency happy without forcing async dispatch on the hot render path.
@MainActor
final class PathCommandCache: ObservableObject {
    private var entries: [String: [PathCommand]] = [:]

    func get(id: String, parser: () -> [PathCommand]) -> [PathCommand] {
        if let cached = entries[id] { return cached }
        let parsed = parser()
        entries[id] = parsed
        return parsed
    }
}

/// Pixel rect for a single cell under rect-extents coordinates. Cell coords
/// are y-up (row 0 at the bottom) per `docs/en/chart-coordinates.md`; this
/// helper flips into SwiftUI's y-down screen coordinates.
func cellRect(
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

/// Screen-space layout for a polar chart, parallel to the Kotlin
/// `PolarCellLayout.Layout`. Kept as a Swift struct (not bridged from the
/// Shared framework) to avoid importing `PolarCellLayout` and its data
/// classes — bridging Kotlin `data class` through ObjC adds friction.
struct PolarLayout: Sendable {
    let cx: CGFloat
    let cy: CGFloat
    let innerRadius: CGFloat
    let ringThickness: CGFloat
}

/// Default polar layout: 0.47 ≈ 94% of the half-extent leaves a visible
/// margin; inner 15% reserved keeps inner rings readable when ring count is
/// small. Mirrors `polarLayoutFor` in Kotlin's `PolarDrawing.kt`.
func polarLayout(canvasSize: CGSize, ringsCount: Int) -> PolarLayout {
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

/// Screen-space center point of a (stitch, ring) cell at the wedge mid-radius.
/// Mirrors Kotlin `PolarCellLayout.cellCenter` — the inscribed-square geometry
/// in `drawPolarCells` reads from this. 12-o'clock-CW convention.
func polarCellCenter(
    stitch: Int,
    ring: Int,
    stitchesInRing: Int,
    layout: PolarLayout
) -> CGPoint {
    let sweep = 2.0 * Double.pi / Double(stitchesInRing)
    let rCenter = layout.innerRadius + (CGFloat(ring) + 0.5) * layout.ringThickness
    let thetaCenter = Double(stitch) * sweep + sweep / 2
    let screenAngle = thetaCenter - Double.pi / 2
    return CGPoint(
        x: layout.cx + rCenter * CGFloat(cos(screenAngle)),
        y: layout.cy + rCenter * CGFloat(sin(screenAngle))
    )
}

/// Radial-up rotation (in radians) for a polar cell, suitable for
/// `GraphicsContext.rotate(by: .radians(...))` so a cell's local "up"
/// points outward from center. Mirrors Kotlin
/// `PolarCellLayout.cellRadialUpRotation`. Update both sides in lock-step
/// when widthUnits > 1 polar cells land (Phase 35.x).
func polarCellRotation(stitch: Int, stitchesInRing: Int) -> Double {
    let sweep = 2.0 * Double.pi / Double(stitchesInRing)
    return Double(stitch) * sweep + sweep / 2
}

/// Inscribed-square side length for a polar cell — the largest axis-aligned
/// square that fits inside the wedge at its mid-radius. Returns at least 1.0
/// to keep degenerate inner rings renderable.
func polarCellInscribedSide(
    ring: Int,
    stitchesInRing: Int,
    layout: PolarLayout
) -> Double {
    let sweep = 2.0 * Double.pi / Double(stitchesInRing)
    let rCenter = layout.innerRadius + (CGFloat(ring) + 0.5) * layout.ringThickness
    let chord = 2.0 * Double(rCenter) * sin(sweep / 2.0)
    return max(1.0, min(Double(layout.ringThickness), chord))
}

/// Paints the rectangular grid (`gridWidth + 1` vertical + `gridHeight + 1`
/// horizontal lines) inside the bounds anchored at `(originX, originY)`.
/// Used by both the full chart viewer and the thumbnail consumers; the diff
/// consumer paints its own grid because of its inline-helper style.
@MainActor
func drawRectGrid(
    into context: inout GraphicsContext,
    gridWidth: Int,
    gridHeight: Int,
    cellSize: CGFloat,
    originX: CGFloat,
    originY: CGFloat,
    color: GraphicsContext.Shading,
    lineWidth: CGFloat = 1
) {
    let drawW = cellSize * CGFloat(gridWidth)
    let drawH = cellSize * CGFloat(gridHeight)
    for gx in 0...gridWidth {
        let x = originX + CGFloat(gx) * cellSize
        var path = Path()
        path.move(to: CGPoint(x: x, y: originY))
        path.addLine(to: CGPoint(x: x, y: originY + drawH))
        context.stroke(path, with: color, lineWidth: lineWidth)
    }
    for gy in 0...gridHeight {
        let y = originY + CGFloat(gy) * cellSize
        var path = Path()
        path.move(to: CGPoint(x: originX, y: y))
        path.addLine(to: CGPoint(x: originX + drawW, y: y))
        context.stroke(path, with: color, lineWidth: lineWidth)
    }
}

/// Annular-wedge `Path` for the (stitch, ring) wedge in our 12-o'clock-CW
/// convention. Outer arc traces CW from start to end angle, inner arc traces
/// CCW back — produces a closed annular region.
///
/// SwiftUI Path.addArc's `clockwise` parameter is defined under math (y-up)
/// coordinates. With y-down screen coords, `clockwise: false` renders
/// visually clockwise, which matches our CW-positive convention.
func polarWedgePath(
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

/// Draws a symbol's SVG path inside `bounds`, rotated by `rotation` degrees.
/// `def.fill` flips between fill and stroke render. Path parsing is amortized
/// via `cache` so re-entries for the same symbol id reuse the parsed commands.
///
/// `@MainActor` mirrors `PathCommandCache`'s isolation — both are owned by
/// SwiftUI views which are main-actor-isolated, so the annotation is a
/// no-op at runtime but unblocks Swift 6 strict concurrency cleanly.
@MainActor
func drawSymbolPath(
    into context: inout GraphicsContext,
    def: SymbolDefinition,
    bounds: CGRect,
    rotation: Int,
    color: GraphicsContext.Shading,
    lineWidth: CGFloat,
    cache: PathCommandCache
) {
    let cellBounds = CellBounds(
        left: Double(bounds.minX),
        top: Double(bounds.minY),
        right: Double(bounds.maxX),
        bottom: Double(bounds.maxY)
    )
    let commands = cache.get(id: def.id) {
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

/// Fallback render for cells whose `symbolId` has no entry in the catalog —
/// renders a filled background plus a "?" glyph. Used by all three consumers
/// when a chart references a symbol the current catalog version does not know.
func drawUnknownGlyph(
    into context: inout GraphicsContext,
    bounds: CGRect,
    fill: GraphicsContext.Shading
) {
    context.fill(Path(bounds), with: fill)
    let glyphSize = max(8, bounds.height * 0.5)
    let text = Text("?").font(.system(size: glyphSize)).foregroundColor(.red)
    context.draw(text, at: CGPoint(x: bounds.midX, y: bounds.midY), anchor: .center)
}
