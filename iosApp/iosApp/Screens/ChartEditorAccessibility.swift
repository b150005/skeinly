import Shared
import SwiftUI

/// One invisible row element in the chart-editor accessibility overlay
/// (ADR-025 R1b). Renders a clear, correctly-sized hit-rect carrying the
/// shared row spoken label + an **adjustable in-row cell cursor** + a
/// single named **place/erase** custom action. Carries NO gesture /
/// pointer modifier so touch + scroll still pass through to the Canvas
/// underneath for sighted users — mirrors the R1a viewer overlay rule.
/// `.offset` (not `.position`) keeps the per-row accessibility frame
/// correct rather than expanding to fill the parent.
private struct EditorRowAccessibilityCell: View {
    let rowLabel: String
    let cellValue: String
    let actionName: String
    let cursorX: Int32
    let cursorMin: Int32
    let cursorMax: Int32
    let width: CGFloat
    let height: CGFloat
    let topY: CGFloat
    let sortPriority: Double
    let onIncrement: () -> Void
    let onDecrement: () -> Void
    let onPlaceOrErase: () -> Void

    var body: some View {
        Color.clear
            .frame(width: width, height: height, alignment: .topLeading)
            .offset(x: 0, y: topY)
            .accessibilityElement()
            .accessibilityLabel(Text(rowLabel))
            .accessibilityValue(Text(cellValue))
            .accessibilitySortPriority(sortPriority)
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment:
                    if cursorX < cursorMax { onIncrement() }
                case .decrement:
                    if cursorX > cursorMin { onDecrement() }
                @unknown default:
                    break
                }
            }
            .accessibilityAction(named: Text(actionName)) { onPlaceOrErase() }
    }
}

/// ADR-025 R1b — invisible per-row accessibility overlay for the rect
/// chart editor. Each grid row is one VoiceOver element labeled with the
/// shared row spoken text (position + run-length symbol summary; no
/// progress section — the editor has no project context). The element is
/// **adjustable**: `.accessibilityAdjustableAction` moves an in-row cell
/// cursor across columns and `accessibilityValue` announces the cell at
/// the cursor (built via the shared pure `ChartAccessibility.spokenCellLabel`
/// so Compose + SwiftUI speak identically by construction).
///
/// A single named custom action invokes the existing
/// `ChartEditorEvent.PlaceCell(cursorX, cursorY)` VM route — the label
/// flips between **Place &lt;symbol&gt;** (palette selected) and
/// **Erase** (no selection). Both intentionally map to the same VM event
/// because the VM already routes `selectedSymbolId == nil` to an immediate
/// erase, exactly mirroring the touch affordance.
///
/// Positioned with the SAME forward `centeredLayout` math the visual
/// Canvas draws with (`cellGeometry(...)` below mirrors the editor's
/// `EditorCanvasView.centeredLayout`) — single coordinate space, no
/// inverse transform (M5 / chart-editor Invariant 8 spirit).
///
/// Per-row cursor state lives in `@State` keyed by chart-y. Default
/// (per row first read) = `rect.minX` = col 1. The caller is responsible
/// for resetting the overlay's identity (via `.id(...)` on the parent)
/// when extents change so stale cursors do not survive a resize.
struct ChartEditorAccessibilityOverlay: View {
    let extents: ChartExtents
    let layers: [ChartLayer]
    let catalog: SymbolCatalog
    let selectedSymbolId: String?
    let size: CGSize
    let onPlaceCell: (Int, Int) -> Void

    @State private var cursorByRow: [Int32: Int32] = [:]

    var body: some View {
        if let rect = extents as? ChartExtentsRect,
           rect.maxX >= rect.minX, rect.maxY >= rect.minY,
           let geometry = cellGeometry(rect: rect) {
            let rowStrings = Self.makeRowA11yStrings()
            let cellStrings = Self.makeCellA11yStrings()
            let descriptors = ChartAccessibility.shared.rowDescriptors(
                extents: rect,
                layers: layers,
                hiddenLayerIds: [],
                progressAt: nil
            )
            // Y3-iOS — consolidated locale resolution through
            // `SymbolDefinition.localizedLabel(locale:)`
            // (Bridging/SymbolDefinition+Localized.swift) mirroring the Y3
            // Kotlin half.
            let locale = Locale.current.language.languageCode?.identifier ?? "en"
            let symbolNameResolver: (String) -> String = { id in
                if let def = catalog.get(id: id) {
                    return def.localizedLabel(locale: locale)
                }
                return id
            }
            let placeOrEraseLabel = ChartAccessibility.shared.placeOrEraseActionLabel(
                strings: cellStrings,
                selectedSymbolId: selectedSymbolId,
                symbolName: { id in symbolNameResolver(id) }
            )

            ZStack(alignment: .topLeading) {
                ForEach(descriptors, id: \.chartY) { descriptor in
                    let chartY = descriptor.chartY
                    let cursorX = cursorByRow[chartY] ?? rect.minX
                    let cellDescriptor = ChartAccessibility.shared.cellDescriptor(
                        extents: rect,
                        layers: layers,
                        hiddenLayerIds: [],
                        cursorX: cursorX,
                        cursorY: chartY
                    )
                    let rowLabel = ChartAccessibility.shared.spokenLabel(
                        descriptor: descriptor,
                        strings: rowStrings,
                        symbolName: { id in symbolNameResolver(id) }
                    )
                    let cellValue: String = {
                        guard let cd = cellDescriptor else { return "" }
                        return ChartAccessibility.shared.spokenCellLabel(
                            descriptor: cd,
                            strings: cellStrings,
                            symbolName: { id in symbolNameResolver(id) }
                        )
                    }()
                    let topY = geometry.originY
                        + CGFloat(geometry.gridHeight - Int(descriptor.rowNumber)) * geometry.cellSize

                    EditorRowAccessibilityCell(
                        rowLabel: rowLabel,
                        cellValue: cellValue,
                        actionName: placeOrEraseLabel,
                        cursorX: cursorX,
                        cursorMin: rect.minX,
                        cursorMax: rect.maxX,
                        width: size.width,
                        height: geometry.cellSize,
                        topY: topY,
                        // Row 1 is visually at the bottom; raise its
                        // VoiceOver sort priority so traversal is
                        // row-1-first (knitting work order). Matches the
                        // Compose `traversalIndex` policy + R1a viewer.
                        sortPriority: Double(geometry.gridHeight - Int(descriptor.rowNumber)),
                        onIncrement: {
                            let current = cursorByRow[chartY] ?? rect.minX
                            cursorByRow[chartY] = min(current + 1, rect.maxX)
                        },
                        onDecrement: {
                            let current = cursorByRow[chartY] ?? rect.minX
                            cursorByRow[chartY] = max(current - 1, rect.minX)
                        },
                        onPlaceOrErase: {
                            onPlaceCell(Int(cursorX), Int(chartY))
                        }
                    )
                }
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
        }
    }

    /// Forward draw layout — mirrors the editor's
    /// `EditorCanvasView.centeredLayout` (Kotlin `centeredLayout`) so the
    /// overlay rows align 1:1 with the rendered grid. Returns `nil` on a
    /// degenerate canvas size. This is the un-zoomed M5 content layout
    /// space, NOT the `.scaleEffect` render transform — screen-reader
    /// users do not pinch, so no inverse transform exists (Invariant 8
    /// spirit). The editor has no row-label gutter (rect editor `Canvas`
    /// draws straight grid cells), so the math is purely
    /// `min(W/gridW, H/gridH)` floored at 1.
    private func cellGeometry(
        rect: ChartExtentsRect
    ) -> (gridHeight: Int, cellSize: CGFloat, originY: CGFloat)? {
        guard size.width > 0, size.height > 0 else { return nil }
        let gridWidth = Int(rect.maxX - rect.minX + 1)
        let gridHeight = Int(rect.maxY - rect.minY + 1)
        let cellSize = max(
            1,
            min(size.width / CGFloat(gridWidth), size.height / CGFloat(gridHeight))
        )
        let drawH = cellSize * CGFloat(gridHeight)
        return (gridHeight, cellSize, (size.height - drawH) / 2)
    }

    /// R1a row keys — already present in the 3 shared i18n files.
    /// `NSLocalizedString` is bundle-cached after first read.
    private static func makeRowA11yStrings() -> ChartAccessibility.A11yStrings {
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

    /// R1b cell + action keys, resource-driven (X3 closed R1b Follow-up
    /// #1). The four keys (`a11y_editor_cell_with_symbol` /
    /// `a11y_editor_cell_blank` / `a11y_editor_action_place` /
    /// `a11y_editor_action_erase`) were splice-shipped by R1b and are now
    /// read through `NSLocalizedString` (bundle-cached after first read).
    /// The format placeholders intentionally use the SHARED Kotlin
    /// formatter's `%1$d`/`%5$s` syntax (NOT `xcstrings`-style
    /// `%lld`/`%@`) because `ChartAccessibility.spokenCellLabel` does the
    /// substitution at call-time; the xcstrings entries were splice-stored
    /// with the Kotlin-shape format strings for parity.
    private static func makeCellA11yStrings() -> ChartAccessibility.CellA11yStrings {
        ChartAccessibility.CellA11yStrings(
            cellSymbolFormat: NSLocalizedString("a11y_editor_cell_with_symbol", comment: ""),
            cellBlank: NSLocalizedString("a11y_editor_cell_blank", comment: ""),
            actionPlaceFormat: NSLocalizedString("a11y_editor_action_place", comment: ""),
            actionErase: NSLocalizedString("a11y_editor_action_erase", comment: "")
        )
    }
}
