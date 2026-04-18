import SwiftUI
import Shared

/// Phase 30.1 SwiftUI counterpart of the Compose `SymbolGalleryScreen`. Browses
/// the bundled `SymbolCatalog` and renders each glyph via the shared path
/// transform + `SvgPathParser`. Owned by `ScopedViewModel` so the Koin-resolved
/// view model survives SwiftUI view re-inits (see ADR-007 / Phase 27.1).
struct SymbolGalleryScreen: View {
    @StateObject private var holder: ScopedViewModel<SymbolGalleryViewModel, SymbolGalleryState>
    @StateObject private var pathCache = SymbolGalleryPathCache()

    init() {
        let vm = ViewModelFactory.symbolGalleryViewModel()
        let wrapper = KoinHelperKt.wrapSymbolGalleryState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    private var viewModel: SymbolGalleryViewModel { holder.viewModel }
    private var state: SymbolGalleryState { holder.state }

    private let columns = [GridItem(.adaptive(minimum: 160), spacing: 12)]

    var body: some View {
        VStack(spacing: 4) {
            CategoryFilterRow(
                active: state.activeCategoryFilter,
                onTap: { category in
                    if state.activeCategoryFilter == category {
                        viewModel.onEvent(event: SymbolGalleryEventClearCategoryFilter.shared)
                    } else {
                        viewModel.onEvent(
                            event: SymbolGalleryEventFilterByCategory(category: category)
                        )
                    }
                }
            )

            ScrollView {
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(state.symbols, id: \.id) { def in
                        SymbolCardView(definition: def, pathCache: pathCache)
                    }
                }
                .padding(12)
            }
            .accessibilityIdentifier("symbolGalleryGrid")
        }
        .navigationTitle("Symbol Dictionary")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Text("\(state.symbols.count) / \(state.totalCount)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct CategoryFilterRow: View {
    let active: SymbolCategory?
    let onTap: (SymbolCategory) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(SymbolCategory.entries, id: \.name) { category in
                    let isActive = active == category
                    Button {
                        onTap(category)
                    } label: {
                        Text("\(category.jaLabel) / \(category.enLabel)")
                            .font(.caption)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(
                                Capsule()
                                    .fill(
                                        isActive
                                            ? Color.accentColor.opacity(0.2)
                                            : Color.gray.opacity(0.1)
                                    )
                            )
                            .foregroundStyle(isActive ? Color.accentColor : .secondary)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
        }
    }
}

private struct SymbolCardView: View {
    let definition: SymbolDefinition
    @ObservedObject var pathCache: SymbolGalleryPathCache

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Canvas { context, size in
                let padding: CGFloat = 16
                let w = size.width - padding * 2
                let h = size.height - padding * 2
                let side = min(w, h)
                let left = (size.width - side) / 2
                let top = (size.height - side) / 2
                drawSymbol(
                    into: &context,
                    definition: definition,
                    bounds: CGRect(x: left, y: top, width: side, height: side)
                )
            }
            .frame(height: 96)
            .frame(maxWidth: .infinity)
            .background(Color.gray.opacity(0.1))

            Text(definition.jaLabel)
                .font(.subheadline)
                .fontWeight(.medium)
            Text(definition.enLabel)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(definition.id)
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
            if let ja = definition.jaDescription, !ja.isEmpty {
                Text(ja).font(.caption2)
            }
            if let en = definition.enDescription, !en.isEmpty {
                Text(en).font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityIdentifier("symbolCard-\(definition.id)")
    }

    private func drawSymbol(
        into context: inout GraphicsContext,
        definition: SymbolDefinition,
        bounds: CGRect
    ) {
        let side = bounds.width
        let lineWidth = max(1.5, side * 0.05)
        let cellBounds = CellBounds(
            left: Double(bounds.minX),
            top: Double(bounds.minY),
            right: Double(bounds.maxX),
            bottom: Double(bounds.maxY)
        )
        let commands = pathCache.get(id: definition.id) {
            SvgPathParser.shared.parse(pathData: definition.pathData)
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
        context.stroke(
            path,
            with: .color(.primary),
            style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round)
        )
    }
}

/// Cache of parsed SVG path commands keyed by symbol id. Reference type so
/// writes during `Canvas` drawing don't dirty the SwiftUI state graph.
private final class SymbolGalleryPathCache: ObservableObject {
    private var entries: [String: [PathCommand]] = [:]

    func get(id: String, parser: () -> [PathCommand]) -> [PathCommand] {
        if let cached = entries[id] { return cached }
        let parsed = parser()
        entries[id] = parsed
        return parsed
    }
}
