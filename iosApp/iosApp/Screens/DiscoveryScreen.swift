import SwiftUI
import Shared

struct DiscoveryScreen: View {
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<DiscoveryViewModel, DiscoveryState>
    @State private var showError = false
    @State private var searchText = ""

    private var viewModel: DiscoveryViewModel { holder.viewModel }

    init(path: Binding<NavigationPath>) {
        self._path = path
        let vm = ViewModelFactory.discoveryViewModel()
        let wrapper = KoinHelperKt.wrapDiscoveryState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        contentView
            .searchable(text: $searchText, prompt: "Search public patterns...")
            .onChange(of: searchText) { _, newValue in
                viewModel.onEvent(event: DiscoveryEventUpdateSearchQuery(query: newValue))
            }
            .onAppear { searchText = holder.state.searchQuery }
            .onChange(of: holder.state.searchQuery) { _, newQuery in
                if searchText != newQuery { searchText = newQuery }
            }
            .onChange(of: holder.state.error) { _, newError in
                showError = newError != nil
            }
            .alert("Error", isPresented: $showError) {
                Button("OK") { viewModel.onEvent(event: DiscoveryEventClearError.shared) }
            } message: {
                Text(holder.state.error ?? "")
            }
            .navigationTitle("Discover Patterns")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { sortToolbarItem }
            .overlay { forkingOverlay }
            .task { observeForkedProjectId() }
    }

    @ViewBuilder
    private var contentView: some View {
        let state = holder.state
        let hasActiveFilter = !state.searchQuery.isEmpty || state.difficultyFilter != nil

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
                "No Public Patterns Yet",
                systemImage: "globe",
                description: Text("Public patterns from other knitters will appear here.")
            )
        } else {
            patternList(state: state)
        }
    }

    @ViewBuilder
    private func patternList(state: DiscoveryState) -> some View {
        List {
            DiscoveryFilterSection(
                difficultyFilter: state.difficultyFilter,
                onDifficultyFilterChange: { difficulty in
                    viewModel.onEvent(event: DiscoveryEventUpdateDifficultyFilter(difficulty: difficulty))
                }
            )

            ForEach(state.patterns, id: \.id) { pattern in
                DiscoveryPatternRow(pattern: pattern)
                    .swipeActions(edge: .leading, allowsFullSwipe: true) {
                        Button {
                            viewModel.onEvent(event: DiscoveryEventForkPattern(patternId: pattern.id))
                        } label: {
                            Label("Fork", systemImage: "doc.on.doc")
                        }
                        .tint(.blue)
                    }
            }
        }
        .refreshable {
            viewModel.onEvent(event: DiscoveryEventRefresh.shared)
        }
    }

    private var sortToolbarItem: some ToolbarContent {
        ToolbarItem(placement: .primaryAction) {
            Menu {
                Button {
                    viewModel.onEvent(event: DiscoveryEventUpdateSortOrder(order: .recent))
                } label: {
                    Label("Recent", systemImage: holder.state.sortOrder == .recent ? "checkmark" : "")
                }
                Button {
                    viewModel.onEvent(event: DiscoveryEventUpdateSortOrder(order: .alphabetical))
                } label: {
                    Label("Alphabetical", systemImage: holder.state.sortOrder == .alphabetical ? "checkmark" : "")
                }
            } label: {
                Image(systemName: "arrow.up.arrow.down")
            }
        }
    }

    @ViewBuilder
    private var forkingOverlay: some View {
        if holder.state.forkingPatternId != nil {
            ProgressView("Forking pattern...")
                .padding()
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private func observeForkedProjectId() {
        let wrapper = KoinHelperKt.wrapDiscoveryForkedProjectIdFlow(flow: viewModel.forkedProjectId)
        let _ = wrapper.collect { projectId in
            Task { @MainActor in
                guard let id = projectId as? String else { return }
                path = NavigationPath()
                path.append(Route.projectDetail(projectId: id))
            }
        }
    }
}

// MARK: - Filter Section

private struct DiscoveryFilterSection: View {
    let difficultyFilter: Difficulty?
    let onDifficultyFilterChange: (Difficulty?) -> Void

    var body: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    DiscoveryFilterChip(label: "All", isSelected: difficultyFilter == nil) {
                        onDifficultyFilterChange(nil)
                    }
                    DiscoveryFilterChip(label: "Beginner", isSelected: difficultyFilter == .beginner) {
                        onDifficultyFilterChange(difficultyFilter == .beginner ? nil : .beginner)
                    }
                    DiscoveryFilterChip(label: "Intermediate", isSelected: difficultyFilter == .intermediate) {
                        onDifficultyFilterChange(difficultyFilter == .intermediate ? nil : .intermediate)
                    }
                    DiscoveryFilterChip(label: "Advanced", isSelected: difficultyFilter == .advanced) {
                        onDifficultyFilterChange(difficultyFilter == .advanced ? nil : .advanced)
                    }
                }
                .padding(.vertical, 4)
            }
        }
        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
    }
}

private struct DiscoveryFilterChip: View {
    let label: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(isSelected ? Color.accentColor : Color.gray.opacity(0.15))
                .foregroundStyle(isSelected ? .white : .primary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Pattern Row

private struct DiscoveryPatternRow: View {
    let pattern: Pattern

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(pattern.title)
                    .font(.headline)
                Spacer()
                if let difficulty = pattern.difficulty {
                    DiscoveryDifficultyBadge(difficulty: difficulty)
                }
            }

            if let description = pattern.description_, !description.isEmpty {
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            let details = buildDetails()
            if !details.isEmpty {
                Text(details)
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 4)
    }

    private func buildDetails() -> String {
        [
            pattern.gauge.map { "Gauge: \($0)" },
            pattern.yarnInfo.map { "Yarn: \($0)" },
            pattern.needleSize.map { "Needle: \($0)" },
        ]
        .compactMap { $0 }
        .joined(separator: " \u{2022} ")
    }
}

private struct DiscoveryDifficultyBadge: View {
    let difficulty: Difficulty

    var body: some View {
        Text(label)
            .font(.caption2)
            .foregroundStyle(color)
    }

    private var label: String {
        switch difficulty {
        case .beginner: "Beginner"
        case .intermediate: "Intermediate"
        case .advanced: "Advanced"
        default: ""
        }
    }

    private var color: Color {
        switch difficulty {
        case .beginner: .green
        case .intermediate: .blue
        case .advanced: .red
        default: .primary
        }
    }
}
