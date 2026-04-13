import SwiftUI
import Shared

struct PatternLibraryScreen: View {
    @Binding var path: NavigationPath
    private let viewModel: PatternLibraryViewModel
    @StateObject private var observer: ViewModelObserver<PatternLibraryState>
    @State private var showDeleteConfirmation = false
    @State private var patternToDelete: String?
    @State private var showError = false
    @State private var searchText = ""

    init(path: Binding<NavigationPath>) {
        self._path = path
        let vm = ViewModelFactory.patternLibraryViewModel()
        self.viewModel = vm
        let wrapper = KoinHelperKt.wrapPatternLibraryState(flow: vm.state)
        _observer = StateObject(wrappedValue: ViewModelObserver(wrapper: wrapper))
    }

    var body: some View {
        let state = observer.state
        let hasActiveFilter = !state.searchQuery.isEmpty || state.difficultyFilter != nil

        Group {
            if state.isLoading && state.patterns.isEmpty {
                ProgressView()
            } else if state.patterns.isEmpty && hasActiveFilter {
                ContentUnavailableView(
                    "No Matching Patterns",
                    systemImage: "magnifyingglass",
                    description: Text("Try adjusting your search or filters.")
                )
            } else if state.patterns.isEmpty {
                ContentUnavailableView(
                    "No Patterns Yet",
                    systemImage: "heart.text.square",
                    description: Text("Tap + to create your first pattern.")
                )
            } else {
                List {
                    DifficultyFilterSection(
                        difficultyFilter: state.difficultyFilter,
                        onDifficultyFilterChange: { difficulty in
                            viewModel.onEvent(event: PatternLibraryEventUpdateDifficultyFilter(difficulty: difficulty))
                        }
                    )

                    ForEach(state.patterns, id: \.id) { pattern in
                        PatternRow(pattern: pattern)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                path.append(Route.patternEdit(patternId: pattern.id))
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    patternToDelete = pattern.id
                                    showDeleteConfirmation = true
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
        .searchable(text: $searchText, prompt: "Search patterns...")
        .onChange(of: searchText) { _, newValue in
            viewModel.onEvent(event: PatternLibraryEventUpdateSearchQuery(query: newValue))
        }
        .onAppear {
            searchText = observer.state.searchQuery
        }
        .onChange(of: observer.state.searchQuery) { _, newQuery in
            if searchText != newQuery {
                searchText = newQuery
            }
        }
        .navigationTitle("Pattern Library")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    path.append(Route.patternEdit(patternId: nil))
                } label: {
                    Image(systemName: "plus")
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                sortMenu(currentOrder: state.sortOrder)
            }
        }
        .alert("Delete Pattern?", isPresented: $showDeleteConfirmation) {
            Button("Delete", role: .destructive) {
                if let id = patternToDelete {
                    viewModel.onEvent(event: PatternLibraryEventDeletePattern(id: id))
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This action cannot be undone.")
        }
        .onChange(of: state.error) { _, newError in
            showError = newError != nil
        }
        .alert("Error", isPresented: $showError) {
            Button("OK") { viewModel.onEvent(event: PatternLibraryEventClearError.shared) }
        } message: {
            Text(state.error ?? "")
        }
    }

    @ViewBuilder
    private func sortMenu(currentOrder: Shared.SortOrder) -> some View {
        Menu {
            Button {
                viewModel.onEvent(event: PatternLibraryEventUpdateSortOrder(order: .recent))
            } label: {
                Label("Recent", systemImage: currentOrder == .recent ? "checkmark" : "")
            }
            Button {
                viewModel.onEvent(event: PatternLibraryEventUpdateSortOrder(order: .alphabetical))
            } label: {
                Label("Alphabetical", systemImage: currentOrder == .alphabetical ? "checkmark" : "")
            }
        } label: {
            Image(systemName: "arrow.up.arrow.down")
        }
    }
}

// MARK: - Difficulty Filter Section

private struct DifficultyFilterSection: View {
    let difficultyFilter: Difficulty?
    let onDifficultyFilterChange: (Difficulty?) -> Void

    var body: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    DifficultyFilterChip(
                        title: "All",
                        isSelected: difficultyFilter == nil,
                        action: { onDifficultyFilterChange(nil) }
                    )
                    DifficultyFilterChip(
                        title: "Beginner",
                        isSelected: difficultyFilter == .beginner,
                        action: { onDifficultyFilterChange(difficultyFilter == .beginner ? nil : .beginner) }
                    )
                    DifficultyFilterChip(
                        title: "Intermediate",
                        isSelected: difficultyFilter == .intermediate,
                        action: { onDifficultyFilterChange(difficultyFilter == .intermediate ? nil : .intermediate) }
                    )
                    DifficultyFilterChip(
                        title: "Advanced",
                        isSelected: difficultyFilter == .advanced,
                        action: { onDifficultyFilterChange(difficultyFilter == .advanced ? nil : .advanced) }
                    )
                }
                .padding(.vertical, DesignTokens.listRowPaddingV)
            }
        }
        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
    }
}

private struct DifficultyFilterChip: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .fontWeight(isSelected ? .semibold : .regular)
                .padding(.horizontal, DesignTokens.chipPaddingH)
                .padding(.vertical, DesignTokens.chipPaddingV)
                .background(isSelected ? Color.accentColor.opacity(DesignTokens.highlightOpacity) : Color(.systemGray6))
                .foregroundStyle(isSelected ? Color.accentColor : .primary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Pattern Row

private struct PatternRow: View {
    let pattern: Pattern

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(pattern.title)
                    .font(.headline)
                Spacer()
                if let difficulty = pattern.difficulty {
                    DifficultyBadge(difficulty: difficulty)
                }
            }

            if let description = pattern.description_, !description.isEmpty {
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, DesignTokens.listRowPaddingV)
    }
}

// MARK: - Difficulty Badge

struct DifficultyBadge: View {
    let difficulty: Difficulty

    var body: some View {
        Text(difficultyText)
            .font(.caption2)
            .fontWeight(.medium)
            .padding(.horizontal, DesignTokens.badgePaddingH)
            .padding(.vertical, DesignTokens.badgePaddingV)
            .background(difficultyColor.opacity(DesignTokens.highlightOpacity))
            .foregroundStyle(difficultyColor)
            .clipShape(Capsule())
    }

    private var difficultyText: String {
        switch difficulty {
        case .beginner: return "Beginner"
        case .intermediate: return "Intermediate"
        case .advanced: return "Advanced"
        default: return "Unknown"
        }
    }

    private var difficultyColor: Color {
        switch difficulty {
        case .beginner: return .green
        case .intermediate: return .orange
        case .advanced: return .red
        default: return .secondary
        }
    }
}
