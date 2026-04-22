import SwiftUI
import Shared

struct DiscoveryScreen: View {
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<DiscoveryViewModel, DiscoveryState>
    @State private var showError = false
    @State private var searchText = ""
    @State private var forkedCloseable: Closeable?

    private var viewModel: DiscoveryViewModel { holder.viewModel }

    init(path: Binding<NavigationPath>) {
        self._path = path
        let vm = ViewModelFactory.discoveryViewModel()
        let wrapper = KoinHelperKt.wrapDiscoveryState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        contentView
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("discoveryScreen")
            .searchable(text: $searchText, prompt: Text("hint_search_public_patterns"))
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
            // `.alert` is in the "needs explicit LocalizedStringKey wrap" column of
            // the SwiftUI literal-promotion table (see docs/en/i18n-convention.md) —
            // overload resolution on a bare literal can silently select the
            // String-typed overload and skip localization.
            .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
                Button("action_ok") { viewModel.onEvent(event: DiscoveryEventClearError.shared) }
            } message: {
                Text(holder.state.error ?? "")
            }
            .navigationTitle(LocalizedStringKey("title_discover_patterns"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { sortToolbarItem }
            .overlay { forkingOverlay }
            .task { observeForkedProjectId() }
            .onDisappear {
                forkedCloseable?.close()
                forkedCloseable = nil
            }
    }

    @ViewBuilder
    private var contentView: some View {
        let state = holder.state
        let hasActiveFilter = !state.searchQuery.isEmpty || state.difficultyFilter != nil

        if state.isLoading && state.patterns.isEmpty {
            ProgressView()
        } else if state.patterns.isEmpty && hasActiveFilter {
            ContentUnavailableView(
                "state_no_matching_patterns",
                systemImage: "magnifyingglass",
                description: Text("state_no_matching_patterns_body")
            )
        } else if state.patterns.isEmpty {
            ContentUnavailableView(
                "state_no_public_patterns",
                systemImage: "globe",
                description: Text("state_no_public_patterns_body")
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
                            Label("action_fork", systemImage: "doc.on.doc")
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
                    Label("label_sort_recent", systemImage: holder.state.sortOrder == .recent ? "checkmark" : "")
                }
                Button {
                    viewModel.onEvent(event: DiscoveryEventUpdateSortOrder(order: .alphabetical))
                } label: {
                    Label("label_sort_alphabetical_detail", systemImage: holder.state.sortOrder == .alphabetical ? "checkmark" : "")
                }
            } label: {
                Image(systemName: "arrow.up.arrow.down")
            }
        }
    }

    @ViewBuilder
    private var forkingOverlay: some View {
        if holder.state.forkingPatternId != nil {
            ProgressView("state_forking_pattern")
                .padding()
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private func observeForkedProjectId() {
        // `.task { }` re-fires on every view re-appearance. Close any prior
        // subscription before replacing it so we do not leak one Closeable per
        // background/foreground cycle.
        forkedCloseable?.close()
        forkedCloseable = nil
        let wrapper = KoinHelperKt.wrapDiscoveryForkedProjectIdFlow(flow: viewModel.forkedProjectId)
        forkedCloseable = wrapper.collect { projectId in
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
                    DiscoveryFilterChip(
                        labelKey: "label_difficulty_all",
                        identifier: "difficultyAllChip",
                        isSelected: difficultyFilter == nil
                    ) {
                        onDifficultyFilterChange(nil)
                    }
                    DiscoveryFilterChip(
                        labelKey: "label_difficulty_beginner",
                        identifier: "difficultyBeginnerChip",
                        isSelected: difficultyFilter == .beginner
                    ) {
                        onDifficultyFilterChange(difficultyFilter == .beginner ? nil : .beginner)
                    }
                    DiscoveryFilterChip(
                        labelKey: "label_difficulty_intermediate",
                        identifier: "difficultyIntermediateChip",
                        isSelected: difficultyFilter == .intermediate
                    ) {
                        onDifficultyFilterChange(difficultyFilter == .intermediate ? nil : .intermediate)
                    }
                    DiscoveryFilterChip(
                        labelKey: "label_difficulty_advanced",
                        identifier: "difficultyAdvancedChip",
                        isSelected: difficultyFilter == .advanced
                    ) {
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
    let labelKey: LocalizedStringKey
    let identifier: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(labelKey)
                .font(.subheadline)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(isSelected ? Color.accentColor : Color.gray.opacity(0.15))
                .foregroundStyle(isSelected ? .white : .primary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
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
            pattern.gauge.map { String(format: NSLocalizedString("label_gauge_value", comment: ""), $0) },
            pattern.yarnInfo.map { String(format: NSLocalizedString("label_yarn_value", comment: ""), $0) },
            pattern.needleSize.map { String(format: NSLocalizedString("label_needle_value", comment: ""), $0) },
        ]
        .compactMap { $0 }
        .joined(separator: " \u{2022} ")
    }
}

private struct DiscoveryDifficultyBadge: View {
    let difficulty: Difficulty

    var body: some View {
        Text(labelKey)
            .font(.caption2)
            .foregroundStyle(color)
    }

    private var labelKey: LocalizedStringKey {
        switch difficulty {
        case .beginner: "label_difficulty_beginner"
        case .intermediate: "label_difficulty_intermediate"
        case .advanced: "label_difficulty_advanced"
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
