import SwiftUI
import Shared

struct PatternLibraryScreen: View {
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<PatternLibraryViewModel, PatternLibraryState>
    @State private var showDeleteConfirmation = false
    @State private var patternToDelete: String?
    @State private var showError = false
    @State private var searchText = ""

    private var viewModel: PatternLibraryViewModel { holder.viewModel }

    init(path: Binding<NavigationPath>) {
        self._path = path
        let vm = ViewModelFactory.patternLibraryViewModel()
        let wrapper = KoinHelperKt.wrapPatternLibraryState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state
        let hasActiveFilter = !state.searchQuery.isEmpty || state.difficultyFilter != nil
        let pendingDeleteName = state.patterns.first(where: { $0.id == patternToDelete })?.title ?? ""

        Group {
            if state.isLoading && state.patterns.isEmpty {
                ProgressView()
            } else if state.patterns.isEmpty && hasActiveFilter {
                ContentUnavailableView(
                    LocalizedStringKey("state_no_matching_patterns"),
                    systemImage: "magnifyingglass",
                    description: Text(LocalizedStringKey("state_no_matching_patterns_body"))
                )
            } else if state.patterns.isEmpty {
                ContentUnavailableView {
                    Label(LocalizedStringKey("state_no_patterns"), systemImage: "heart.text.square")
                } description: {
                    Text(LocalizedStringKey("state_no_patterns_body"))
                } actions: {
                    Button(LocalizedStringKey("action_create_pattern")) {
                        path.append(Route.patternEdit(patternId: nil))
                    }
                    .buttonStyle(.borderedProminent)
                }
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
                                    Label(LocalizedStringKey("action_delete"), systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
        .searchable(text: $searchText, prompt: Text(LocalizedStringKey("hint_search_patterns")))
        .onChange(of: searchText) { _, newValue in
            viewModel.onEvent(event: PatternLibraryEventUpdateSearchQuery(query: newValue))
        }
        .onAppear {
            searchText = holder.state.searchQuery
        }
        .onChange(of: holder.state.searchQuery) { _, newQuery in
            if searchText != newQuery {
                searchText = newQuery
            }
        }
        .navigationTitle(LocalizedStringKey("title_pattern_library"))
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    path.append(Route.patternEdit(patternId: nil))
                } label: {
                    Image(systemName: "plus")
                        .accessibilityLabel(Text(LocalizedStringKey("action_new_pattern")))
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                sortMenu(currentOrder: state.sortOrder)
            }
        }
        .alert(
            LocalizedStringKey("dialog_delete_pattern_title"),
            isPresented: $showDeleteConfirmation
        ) {
            Button(LocalizedStringKey("action_delete"), role: .destructive) {
                if let id = patternToDelete {
                    viewModel.onEvent(event: PatternLibraryEventDeletePattern(id: id))
                }
            }
            Button(LocalizedStringKey("action_cancel"), role: .cancel) {}
        } message: {
            // Parametric body — iOS `.alert` `message:` closure sits inside the
            // SwiftUI literal-promotion-table "needs explicit LocalizedStringKey"
            // column, so `Text(LocalizedStringKey(...))` can't accept format
            // args. Resolve through NSLocalizedString + String(format:) to match
            // the precedent established in Phase 33.1.7 for parametric activity
            // strings.
            Text(String(
                format: NSLocalizedString("dialog_delete_pattern_body", comment: ""),
                pendingDeleteName
            ))
        }
        .onChange(of: state.error) { _, newError in
            showError = newError != nil
        }
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button(LocalizedStringKey("action_ok")) {
                viewModel.onEvent(event: PatternLibraryEventClearError.shared)
            }
        } message: {
            // Raw ViewModel error string — localization of these messages is
            // tracked in the Tech Debt Backlog "ViewModel error-message
            // localization" item.
            Text(state.error ?? "")
        }
    }

    @ViewBuilder
    private func sortMenu(currentOrder: Shared.SortOrder) -> some View {
        Menu {
            Button {
                viewModel.onEvent(event: PatternLibraryEventUpdateSortOrder(order: .recent))
            } label: {
                Label(
                    LocalizedStringKey("label_sort_recent"),
                    systemImage: currentOrder == .recent ? "checkmark" : ""
                )
            }
            Button {
                viewModel.onEvent(event: PatternLibraryEventUpdateSortOrder(order: .alphabetical))
            } label: {
                Label(
                    LocalizedStringKey("label_sort_alphabetical_detail"),
                    systemImage: currentOrder == .alphabetical ? "checkmark" : ""
                )
            }
        } label: {
            Image(systemName: "arrow.up.arrow.down")
                .accessibilityLabel(Text(LocalizedStringKey("action_sort")))
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
                        titleKey: LocalizedStringKey("label_difficulty_all"),
                        isSelected: difficultyFilter == nil,
                        action: { onDifficultyFilterChange(nil) }
                    )
                    .accessibilityIdentifier("difficultyAllChip")
                    DifficultyFilterChip(
                        titleKey: LocalizedStringKey("label_difficulty_beginner"),
                        isSelected: difficultyFilter == .beginner,
                        action: { onDifficultyFilterChange(difficultyFilter == .beginner ? nil : .beginner) }
                    )
                    .accessibilityIdentifier("difficultyBeginnerChip")
                    DifficultyFilterChip(
                        titleKey: LocalizedStringKey("label_difficulty_intermediate"),
                        isSelected: difficultyFilter == .intermediate,
                        action: { onDifficultyFilterChange(difficultyFilter == .intermediate ? nil : .intermediate) }
                    )
                    .accessibilityIdentifier("difficultyIntermediateChip")
                    DifficultyFilterChip(
                        titleKey: LocalizedStringKey("label_difficulty_advanced"),
                        isSelected: difficultyFilter == .advanced,
                        action: { onDifficultyFilterChange(difficultyFilter == .advanced ? nil : .advanced) }
                    )
                    .accessibilityIdentifier("difficultyAdvancedChip")
                }
                .padding(.vertical, DesignTokens.listRowPaddingV)
            }
        }
        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
    }
}

private struct DifficultyFilterChip: View {
    let titleKey: LocalizedStringKey
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(titleKey)
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
        Text(difficultyLabelKey)
            .font(.caption2)
            .fontWeight(.medium)
            .padding(.horizontal, DesignTokens.badgePaddingH)
            .padding(.vertical, DesignTokens.badgePaddingV)
            .background(difficultyColor.opacity(DesignTokens.highlightOpacity))
            .foregroundStyle(difficultyColor)
            .clipShape(Capsule())
    }

    private var difficultyLabelKey: LocalizedStringKey {
        switch difficulty {
        case .beginner: return LocalizedStringKey("label_difficulty_beginner")
        case .intermediate: return LocalizedStringKey("label_difficulty_intermediate")
        case .advanced: return LocalizedStringKey("label_difficulty_advanced")
        default: return LocalizedStringKey("")
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
