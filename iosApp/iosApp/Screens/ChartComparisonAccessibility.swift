import Shared
import SwiftUI

/// One invisible row element in the chart-comparison accessibility overlay
/// (ADR-025 R1c). Renders a clear, correctly-sized hit-rect carrying the
/// shared row spoken label (position + change list). Carries NO gesture /
/// pointer modifier so touch + pinch still pass through to the Canvas
/// underneath for sighted users — mirrors the R1a viewer + R1b editor
/// overlay rule. `.offset` (not `.position`) keeps the per-row
/// accessibility frame correct rather than expanding to fill the parent.
/// Read-only: no `.accessibilityAction` (the Comparison surface is itself
/// read-only per ADR-025 §c).
private struct ComparisonRowAccessibilityCell: View {
    let rowLabel: String
    let width: CGFloat
    let height: CGFloat
    let topY: CGFloat
    let sortPriority: Double

    var body: some View {
        Color.clear
            .frame(width: width, height: height, alignment: .topLeading)
            .offset(x: 0, y: topY)
            .accessibilityElement()
            .accessibilityLabel(Text(rowLabel))
            .accessibilitySortPriority(sortPriority)
    }
}

/// ADR-025 R1c — invisible per-row accessibility overlay for the rect
/// Chart Comparison surface. Each row that has ≥1 change is one VoiceOver
/// element labeled with the shared row-diff spoken text (position + change
/// list: `"Row R of N — col C added <sym>, col C2 removed <sym>, …"`),
/// built by the shared pure `ChartAccessibility.spokenDiffLabel` so
/// Compose + SwiftUI speak identically by construction (ADR-025 §g).
///
/// Read-only: no `.accessibilityAction` (the Comparison surface itself is
/// read-only per ADR-025 §c "Comparison" row, "none (read-only); aggregate
/// `DiffSummaryRow` already exposed (keep)").
///
/// Positioned with the SAME forward `computeDiffLayout` math the visual
/// Canvas draws with — single coordinate space, no inverse transform
/// (M5 / chart-editor Invariant 8 spirit). The overlay sits OUTSIDE the
/// canvas's `.scaleEffect` / `.offset` transform — screen-reader users do
/// not pinch, and the base layout is the SR-relevant space.
///
/// Aligned with the TARGET pane only (not duplicated on the base pane).
/// Rationale: `target` is always non-null per `ChartComparison` (`base`
/// can be `nil` on initial commit) and represents "what's at this
/// position now" — the spoken change list narrates a unified diff
/// anchored in the post-change state. Removed cells whose chartY falls
/// outside the shrunken target's row range are surfaced visually on the
/// base pane only and silently dropped from the spoken description (see
/// `ChartAccessibility.rowDiffDescriptors` docs); polar charts are gated
/// to Phase 35.2+ per ADR-025 §e and the overlay early-returns on
/// `ChartExtentsPolar`.
struct ChartComparisonAccessibilityOverlay: View {
    let diff: ChartComparison
    let catalog: SymbolCatalog
    let size: CGSize

    var body: some View {
        if let rect = diff.target.extents as? ChartExtentsRect,
           rect.maxX >= rect.minX, rect.maxY >= rect.minY,
           let geometry = cellGeometry(rect: rect) {
            let isJa = Locale.current.language.languageCode?.identifier == "ja"
            let strings = Self.makeDiffA11yStrings(isJa: isJa)
            let symbolNameResolver: (String) -> String = { id in
                if let def = catalog.get(id: id) {
                    return isJa ? def.jaLabel : def.enLabel
                }
                return id
            }
            let descriptors = ChartAccessibility.shared.rowDiffDescriptors(
                targetExtents: rect,
                cellChanges: diff.cellChanges,
                layerChanges: diff.layerChanges
            )

            ZStack(alignment: .topLeading) {
                ForEach(descriptors, id: \.chartY) { descriptor in
                    let topY = geometry.originY
                        + CGFloat(geometry.gridHeight - Int(descriptor.rowNumber)) * geometry.cellSize
                    let label = ChartAccessibility.shared.spokenDiffLabel(
                        descriptor: descriptor,
                        strings: strings,
                        symbolName: { id in symbolNameResolver(id) }
                    )
                    ComparisonRowAccessibilityCell(
                        rowLabel: label,
                        width: size.width,
                        height: geometry.cellSize,
                        topY: topY,
                        // Row 1 is visually at the bottom; raise its
                        // VoiceOver sort priority so traversal is
                        // row-1-first (knitting work order). Matches the
                        // Compose `traversalIndex` policy + R1a/R1b.
                        sortPriority: Double(geometry.gridHeight - Int(descriptor.rowNumber))
                    )
                }
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
        }
    }

    /// Forward draw layout — mirrors `computeDiffLayout` in the Kotlin
    /// `ChartComparisonScreen.kt` so the overlay rows align 1:1 with the
    /// rendered target-pane grid. Returns `nil` on a degenerate canvas size.
    /// This is the un-zoomed base content layout space, NOT the
    /// `.scaleEffect` render transform — screen-reader users do not pinch,
    /// so no inverse transform exists (Invariant 8 spirit).
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

    /// R1c — bilingual fallback strings for the per-row Comparison spoken
    /// text. `rowPositionFormat` / `sectionSeparator` / `blankCellsName`
    /// reuse the R1a keys already shipped in the iOS `Localizable.xcstrings`
    /// (`a11y_chart_row_position` / `a11y_chart_section_separator` /
    /// `a11y_chart_blank_cells`). The change-format trio + change-separator
    /// are R1c-new keys carried in `docs/en/phase/tasks/R1c.i18n.tsv` for
    /// the orchestrator to splice into the shared `Localizable.xcstrings`
    /// at consolidation; until then this method returns hardcoded en/ja
    /// literals matching the TSV's `en` / `ja` columns. Format
    /// placeholders use the SHARED Kotlin formatter's `%1$d`/`%2$s` syntax
    /// (NOT xcstrings `%lld`/`%@`) because `ChartAccessibility.spokenDiffLabel`
    /// does the substitution. Pattern mirrors the R1b
    /// `makeCellA11yStrings(isJa:)` helper.
    private static func makeDiffA11yStrings(isJa: Bool) -> ChartAccessibility.DiffA11yStrings {
        if isJa {
            return ChartAccessibility.DiffA11yStrings(
                rowPositionFormat: NSLocalizedString("a11y_chart_row_position", comment: ""),
                changeSeparator: "、",
                changeAddedFormat: "%1$d列目に%2$sを追加",
                changeRemovedFormat: "%1$d列目の%2$sを削除",
                changeModifiedFormat: "%1$d列目を%2$sに変更",
                sectionSeparator: NSLocalizedString("a11y_chart_section_separator", comment: ""),
                blankCellsName: NSLocalizedString("a11y_chart_blank_cells", comment: "")
            )
        } else {
            return ChartAccessibility.DiffA11yStrings(
                rowPositionFormat: NSLocalizedString("a11y_chart_row_position", comment: ""),
                changeSeparator: ", ",
                changeAddedFormat: "col %1$d added %2$s",
                changeRemovedFormat: "col %1$d removed %2$s",
                changeModifiedFormat: "col %1$d modified to %2$s",
                sectionSeparator: NSLocalizedString("a11y_chart_section_separator", comment: ""),
                blankCellsName: NSLocalizedString("a11y_chart_blank_cells", comment: "")
            )
        }
    }
}
