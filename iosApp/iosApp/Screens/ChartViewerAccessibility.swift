import Shared
import SwiftUI

/// One invisible row element in the chart-viewer accessibility overlay
/// (ADR-025 R1a). A clear, correctly-sized hit-rect carrying the shared
/// spoken label, a VoiceOver sort priority (row-1-first work order), and an
/// optional named mark-row-done action. Carries NO gesture / pointer
/// modifier so touch + pinch still pass through to the Canvas underneath
/// for sighted users. `.offset` (not `.position`) keeps the per-row
/// accessibility frame correct rather than expanding to fill the parent.
private struct RowAccessibilityCell: View {
    let label: String
    let width: CGFloat
    let height: CGFloat
    let topY: CGFloat
    let sortPriority: Double
    let actionName: String?
    let onMarkRowDone: () -> Void

    var body: some View {
        let base = Color.clear
            .frame(width: width, height: height, alignment: .topLeading)
            .offset(x: 0, y: topY)
            .accessibilityElement()
            .accessibilityLabel(Text(label))
            .accessibilitySortPriority(sortPriority)
        if let actionName {
            base.accessibilityAction(named: Text(actionName)) { onMarkRowDone() }
        } else {
            base
        }
    }
}

/// ADR-025 R1a — invisible per-row accessibility overlay for the rect chart
/// viewer. Each grid row is one VoiceOver element whose label is the shared
/// `ChartAccessibility.spokenLabel` text (position + run-length symbol
/// summary + progress, no color), so Compose and SwiftUI speak identically
/// by construction. A named action maps to the existing row-level
/// mark-row-done op, attached only in a project context (`progress != nil`).
/// Positioned with the SAME forward draw layout (originY / cellSize from
/// `size`) — not the `.scaleEffect` render transform — so there is no
/// inverse transform (M5 / chart-editor Invariant 8 spirit). Rect only; the
/// polar overlay is gated and deferred to Phase 35.2+ in lockstep with M5
/// polar zoom.
///
/// Lives in its own file (not an `extension ChartCanvasView`) because
/// `ChartCanvasView` is a `private struct` — a same-module extension in
/// another file cannot reach its members. Inputs are passed explicitly;
/// `rectRowLabelGutter` is threaded from the single source of truth on
/// `ChartCanvasView` rather than re-declared here.
struct ChartRowAccessibilityOverlay: View {
    let chart: Chart
    let catalog: SymbolCatalog
    let hiddenLayerIds: Set<String>
    /// Mirrors `ChartCanvasView.segmentLookup` — routes through the Kotlin
    /// ViewModel helper to avoid bridging `Map<SegmentKey, SegmentState>`.
    let segmentLookup: (String, Int, Int) -> SegmentState?
    let hasProgressContext: Bool
    let rectRowLabelGutter: CGFloat
    let size: CGSize
    let onMarkRowDone: (Int) -> Void

    var body: some View {
        if let rect = chart.extents as? ChartExtentsRect,
           rect.maxX >= rect.minX, rect.maxY >= rect.minY,
           let geometry = cellGeometry(rect: rect) {
            let strings = Self.makeA11yStrings()
            let markRowDoneLabel = NSLocalizedString("a11y_chart_action_mark_row_done", comment: "")
            let progressAt: ((String, KotlinInt, KotlinInt) -> SegmentState?)? =
                hasProgressContext
                    ? { layerId, x, y in segmentLookup(layerId, x.intValue, y.intValue) }
                    : nil
            let descriptors = ChartAccessibility.shared.rowDescriptors(
                extents: rect,
                layers: chart.layers,
                hiddenLayerIds: hiddenLayerIds,
                progressAt: progressAt
            )
            // Y3-iOS — consolidated locale resolution through
            // `SymbolDefinition.localizedLabel(locale:)`
            // (Bridging/SymbolDefinition+Localized.swift) mirroring the Y3
            // Kotlin half. Hoisted out of the per-row ForEach body so it is
            // evaluated once per overlay layout rather than once per row.
            let locale = Locale.current.language.languageCode?.identifier ?? "en"
            ZStack(alignment: .topLeading) {
                ForEach(descriptors, id: \.chartY) { descriptor in
                    let topY = geometry.originY
                        + CGFloat(geometry.gridHeight - Int(descriptor.rowNumber)) * geometry.cellSize
                    RowAccessibilityCell(
                        label: ChartAccessibility.shared.spokenLabel(
                            descriptor: descriptor,
                            strings: strings,
                            symbolName: { id in
                                if let def = catalog.get(id: id) {
                                    return def.localizedLabel(locale: locale)
                                }
                                return id
                            }
                        ),
                        width: size.width,
                        height: geometry.cellSize,
                        topY: topY,
                        // Row 1 is visually at the bottom; raise its VoiceOver
                        // sort priority so traversal is row-1-first (work
                        // order), matching the spoken row numbers + Compose
                        // `traversalIndex`.
                        sortPriority: Double(geometry.gridHeight - Int(descriptor.rowNumber)),
                        // Exposed uniformly on every row in a project context
                        // (incl. already-`Done` rows) — mirrors the touch
                        // long-press affordance and the op is idempotent.
                        actionName: descriptor.progress != nil ? markRowDoneLabel : nil,
                        onMarkRowDone: { onMarkRowDone(Int(descriptor.chartY)) }
                    )
                }
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
        }
    }

    /// Forward draw layout — mirrors the Kotlin `computeViewerLayout` / the
    /// swift `draw(into:size:rect:)` math so the overlay rows align 1:1 with
    /// the rendered grid. Returns `nil` on a degenerate canvas size. This is
    /// the un-zoomed layout space (NOT the `.scaleEffect` render transform);
    /// screen-reader users do not pinch, so no inverse transform exists
    /// (Invariant 8 spirit).
    private func cellGeometry(
        rect: ChartExtentsRect
    ) -> (gridHeight: Int, cellSize: CGFloat, originY: CGFloat)? {
        guard size.width > 0, size.height > 0 else { return nil }
        let gridWidth = Int(rect.maxX - rect.minX + 1)
        let gridHeight = Int(rect.maxY - rect.minY + 1)
        let availableW = max(1, size.width - rectRowLabelGutter)
        let cellSize = max(
            1,
            min(availableW / CGFloat(gridWidth), size.height / CGFloat(gridHeight))
        )
        let drawH = cellSize * CGFloat(gridHeight)
        return (gridHeight, cellSize, (size.height - drawH) / 2)
    }

    /// Raw localized templates (no args) — the shared `spokenLabel` does the
    /// `%1$d`/`%1$s` substitution so the join is identical to Compose by
    /// construction. `NSLocalizedString` is bundle-cached after first read.
    private static func makeA11yStrings() -> ChartAccessibility.A11yStrings {
        ChartAccessibility.A11yStrings(
            rowPositionFormat: NSLocalizedString("a11y_chart_row_position", comment: ""),
            symbolRunFormat: NSLocalizedString("a11y_chart_symbol_run", comment: ""),
            blankCellsName: NSLocalizedString("a11y_chart_blank_cells", comment: ""),
            runSeparator: NSLocalizedString("a11y_chart_run_separator", comment: ""),
            sectionSeparator: NSLocalizedString("a11y_chart_section_separator", comment: ""),
            progressNotStarted: NSLocalizedString("a11y_chart_progress_not_started", comment: ""),
            progressDone: NSLocalizedString("a11y_chart_progress_done", comment: ""),
            progressInProgressFormat: NSLocalizedString(
                "a11y_chart_progress_in_progress", comment: ""
            )
        )
    }
}
