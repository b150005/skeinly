import SwiftUI
import Shared

struct DiscoveryScreen: View {
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<DiscoveryViewModel, DiscoveryState>
    @State private var showError = false
    @State private var searchText = ""
    @State private var forkedCloseable: Closeable?
    /// Phase 36.5+: transient post-fork feedback. Mirrors the Compose
    /// Snackbar surfaced via `launch { showSnackbar(...) }` in
    /// DiscoveryScreen.kt — the message visibility window is 2s on the
    /// Discovery screen before `path = NavigationPath()` unmounts the view
    /// and pushes ProjectDetail, since SwiftUI overlays are scoped to the
    /// host view's lifetime (unlike StructuredChartViewerScreen's
    /// `prOpenedToast` which survives because the source stays in the
    /// navigation stack — Discovery resets the stack instead of pushing).
    @State private var forkResultToast: String?
    /// Holds the unstructured `Task` spawned from the Kotlin Flow callback
    /// in `observeForkedProjectId` so `.onDisappear` can cancel it. Without
    /// this, the task would keep running through its 2s sleep + path
    /// mutations even after the view unmounts — latent hazard if the
    /// navigation stack composition ever changes.
    @State private var forkTask: Task<Void, Never>?

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
            .onChange(of: holder.state.error != nil) { _, hasError in
            showError = hasError
        }
            // `.alert` is in the "needs explicit LocalizedStringKey wrap" column of
            // the SwiftUI literal-promotion table (see docs/en/i18n-convention.md) —
            // overload resolution on a bare literal can silently select the
            // String-typed overload and skip localization.
            .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
                Button("action_ok") { viewModel.onEvent(event: DiscoveryEventClearError.shared) }
            } message: {
                Text(holder.state.error?.localizedString ?? "")
            }
            .navigationTitle(LocalizedStringKey("title_discover_patterns"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { sortToolbarItem }
            .overlay { forkingOverlay }
            .overlay(alignment: .bottom) { forkResultToastOverlay }
            .task { observeForkedProjectId() }
            .onDisappear {
                forkedCloseable?.close()
                forkedCloseable = nil
                forkTask?.cancel()
                forkTask = nil
            }
    }

    @ViewBuilder
    private var contentView: some View {
        let state = holder.state
        // Phase 36.4: include chartsOnlyFilter so the matching-filter empty
        // state surfaces when "Charts only" filters out every pattern; mirrors
        // the Compose behavior in DiscoveryScreen.kt.
        let hasActiveFilter =
            !state.searchQuery.isEmpty
            || state.difficultyFilter != nil
            || state.chartsOnlyFilter

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
                chartsOnlyFilter: state.chartsOnlyFilter,
                onDifficultyFilterChange: { difficulty in
                    viewModel.onEvent(event: DiscoveryEventUpdateDifficultyFilter(difficulty: difficulty))
                },
                onToggleChartsOnly: {
                    viewModel.onEvent(event: DiscoveryEventToggleChartsOnly.shared)
                }
            )

            ForEach(state.patterns, id: \.id) { pattern in
                DiscoveryPatternRow(
                    pattern: pattern,
                    hasChart: state.patternsWithCharts.contains(pattern.id),
                    onChartTap: {
                        path.append(Route.chartViewer(patternId: pattern.id, projectId: nil))
                    }
                )
                    .swipeActions(edge: .leading, allowsFullSwipe: true) {
                        Button {
                            viewModel.onEvent(event: DiscoveryEventForkPattern(patternId: pattern.id))
                        } label: {
                            Label("action_fork", systemImage: "doc.on.doc")
                        }
                        // Brand accent (#7B61FF) — Sprint A unified the app's primary
                        // action color via Assets.xcassets/AccentColor; previously the
                        // swipe button was hard-coded to system blue.
                        .tint(.accentColor)
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

    /// Phase 36.5+: transient toast for post-fork feedback. Pattern mirrors
    /// `prOpenedToast` / `switchedToast` in StructuredChartViewerScreen.swift
    /// (Capsule + thinMaterial + callout font + 24pt bottom inset), but the
    /// visibility window is governed by an explicit pre-navigation delay in
    /// `observeForkedProjectId` rather than a `.task` auto-dismiss timer
    /// because Discovery resets the navigation path (which unmounts this
    /// view) instead of pushing on top of it.
    @ViewBuilder
    private var forkResultToastOverlay: some View {
        if let message = forkResultToast {
            Text(verbatim: message)
                .font(.callout)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.thinMaterial, in: Capsule())
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
                .transition(.opacity)
                // Surface the message text to accessibility/automation trees
                // (XCUITest, Maestro) that statically inspect the hierarchy
                // even though the 2s visibility window is too short for
                // VoiceOver to reliably announce in normal use.
                .accessibilityLabel(Text(verbatim: message))
                .accessibilityIdentifier("forkResultToast")
        }
    }

    private func observeForkedProjectId() {
        // `.task { }` re-fires on every view re-appearance. Close any prior
        // subscription before replacing it so we do not leak one Closeable per
        // background/foreground cycle.
        forkedCloseable?.close()
        forkedCloseable = nil
        let wrapper = KoinHelperKt.wrapDiscoveryForkedProjectFlow(flow: viewModel.forkedProject)
        forkedCloseable = wrapper.collect { result in
            // Phase 36.5+ closes the Phase 36.3 ADR-012 §7 deferral.
            // Compose surfaces both success and failure paths via the
            // host-launched (non-awaited) Snackbar; iOS now mirrors that
            // by setting a 2s-visible bottom toast BEFORE resetting the
            // navigation path. The chart-clone failure copy explicitly
            // names the recovery action ("try re-forking from project
            // detail") consistent with `message_forked_chart_failed`.
            //
            // The unstructured `Task` is captured into `forkTask` so
            // `.onDisappear` can cancel it — without that, the 2s sleep +
            // path mutations would keep running after the view unmounts.
            // `try await` (no `?`) allows `CancellationError` to short-
            // circuit the function, leaving `path` untouched on cancel.
            forkTask?.cancel()
            forkTask = Task { @MainActor in
                guard let forkResult = result as? DiscoveryForkResult else { return }
                let key = forkResult.chartCloneFailed
                    ? "message_forked_chart_failed"
                    : "message_forked_successfully"
                forkResultToast = NSLocalizedString(key, comment: "")
                do {
                    try await Task.sleep(nanoseconds: 2_000_000_000)
                } catch {
                    // Cancelled by `.onDisappear` — leave navigation alone.
                    return
                }
                forkResultToast = nil
                path = NavigationPath()
                path.append(Route.projectDetail(projectId: forkResult.projectId))
            }
        }
    }
}

// MARK: - Filter Section

private struct DiscoveryFilterSection: View {
    let difficultyFilter: Difficulty?
    let chartsOnlyFilter: Bool
    let onDifficultyFilterChange: (Difficulty?) -> Void
    let onToggleChartsOnly: () -> Void

    var body: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    // Phase 36.4 (ADR-012 §4): "Charts only" filter chip leads
                    // the row so it is visible without horizontal scrolling.
                    DiscoveryFilterChip(
                        labelKey: "label_filter_charts_only",
                        identifier: "chartsOnlyChip",
                        isSelected: chartsOnlyFilter,
                        action: onToggleChartsOnly
                    )
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
    let hasChart: Bool
    let onChartTap: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            // Phase 36.4.1b (ADR-012 §5): live mini-render of the structured
            // chart via `ChartThumbnailView` (Components/ChartThumbnailView.swift).
            // Closes the Phase 36.4 deferral — iOS now renders the same
            // grid + symbol-glyphs thumbnail as the Compose `ChartThumbnail`,
            // sharing rendering primitives via Components/ChartRenderingKit.swift.
            if hasChart {
                ChartThumbnailView(patternId: pattern.id, onTap: onChartTap)
            }

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
