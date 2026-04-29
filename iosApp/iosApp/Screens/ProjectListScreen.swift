import SwiftUI
import Shared

struct ProjectListScreen: View {
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<ProjectListViewModel, ProjectListState>
    @State private var showCreateSheet = false
    @State private var showError = false
    @State private var showDeleteConfirmation = false
    @State private var projectToDelete: Project?
    @State private var newTitle = ""
    @State private var newTotalRows = ""
    @State private var selectedPatternId: String?
    @State private var searchText = ""

    private var viewModel: ProjectListViewModel { holder.viewModel }

    init(path: Binding<NavigationPath>) {
        self._path = path
        let vm = ViewModelFactory.projectListViewModel()
        let wrapper = KoinHelperKt.wrapProjectListState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state
        let hasActiveFilter = !state.searchQuery.isEmpty || state.statusFilter != nil

        Group {
            if state.isLoading && state.projects.isEmpty {
                ProgressView()
            } else if state.projects.isEmpty && hasActiveFilter {
                // `emptyStateNoMatch` is a defensive landmark — no current
                // Maestro/XCUITest flow asserts on the filter-empty state yet,
                // but keeping the id matches the Kotlin side and unblocks a
                // future search-filter E2E without a follow-up PR.
                ContentUnavailableView(
                    LocalizedStringKey("state_no_matching_projects"),
                    systemImage: "magnifyingglass",
                    description: Text(LocalizedStringKey("state_no_matching_projects_body"))
                )
                .accessibilityIdentifier("emptyStateNoMatch")
            } else if state.projects.isEmpty {
                // `ContentUnavailableView` with a content closure renders its
                // Label as two child accessibility elements; placing the id on
                // an inner view makes cross-query lookup unreliable. Wrap in a
                // ZStack-backed landmark so Maestro (`assertVisible: id`) and
                // XCUITest (`otherElements[id]`) both resolve to a single
                // container — same pattern as 33.1.6's ProfileScreen.swift.
                ZStack {
                    ContentUnavailableView {
                        Label(LocalizedStringKey("state_no_projects"), systemImage: "folder")
                    } description: {
                        Text(LocalizedStringKey("state_no_projects_body"))
                    } actions: {
                        Button(LocalizedStringKey("action_create_project")) {
                            showCreateSheet = true
                        }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("createProjectEmptyCta")
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("emptyStateLabel")
            } else {
                List {
                    StatusFilterSection(
                        statusFilter: state.statusFilter,
                        onStatusFilterChange: { status in
                            viewModel.onEvent(event: ProjectListEventUpdateStatusFilter(status: status))
                        }
                    )

                    ForEach(state.projects, id: \.id) { project in
                        ProjectRow(project: project)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                path.append(Route.projectDetail(projectId: project.id))
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    projectToDelete = project
                                    showDeleteConfirmation = true
                                } label: {
                                    Label(LocalizedStringKey("action_delete"), systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
        .searchable(
            text: $searchText,
            prompt: Text(LocalizedStringKey("hint_search_projects"))
        )
        .onChange(of: searchText) { _, newValue in
            viewModel.onEvent(event: ProjectListEventUpdateSearchQuery(query: newValue))
        }
        .onAppear {
            searchText = holder.state.searchQuery
        }
        // `app_name` is locale-identical ("Knit Note" in both en and ja) per the
        // i18n-convention doc. The bare-literal nav-title is intentional —
        // LocalizedStringKey would resolve to the same string and any future
        // rename ships through the xcstrings source anyway.
        .navigationTitle(LocalizedStringKey("app_name"))
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showCreateSheet = true
                } label: {
                    Image(systemName: "plus")
                }
                // Aligned with Kotlin `createProjectFab` testTag — previously the
                // toolbar trigger and the sheet-confirm button both had identifier
                // "createProjectButton", which collided when the sheet was present.
                .accessibilityIdentifier("createProjectFab")
                .accessibilityLabel(LocalizedStringKey("action_new_project"))
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    sortMenu(currentOrder: state.sortOrder)

                    Divider()

                    Button { path.append(Route.discovery) } label: {
                        Label(LocalizedStringKey("action_discover_patterns"), systemImage: "globe")
                    }
                    .accessibilityIdentifier("discoverPatternsButton")
                    Button { path.append(Route.patternLibrary) } label: {
                        Label(LocalizedStringKey("action_pattern_library"), systemImage: "heart.text.square")
                    }
                    .accessibilityIdentifier("patternLibraryButton")
                    Button { path.append(Route.symbolGallery) } label: {
                        Label(LocalizedStringKey("action_symbol_dictionary"), systemImage: "square.grid.3x3")
                    }
                    .accessibilityIdentifier("symbolGalleryButton")
                    Button { path.append(Route.profile) } label: {
                        Label(LocalizedStringKey("action_profile"), systemImage: "person.circle")
                    }
                    .accessibilityIdentifier("profileButton")
                    Button { path.append(Route.activityFeed) } label: {
                        Label(LocalizedStringKey("action_activity_feed"), systemImage: "bell")
                    }
                    .accessibilityIdentifier("activityFeedButton")
                    Button {
                        path.append(Route.pullRequestList(defaultFilter: .incoming))
                    } label: {
                        Label(LocalizedStringKey("action_pull_requests"), systemImage: "bubble.left.and.bubble.right")
                    }
                    .accessibilityIdentifier("pullRequestsButton")
                    Button { path.append(Route.sharedWithMe) } label: {
                        Label(LocalizedStringKey("action_shared_with_me"), systemImage: "shared.with.you")
                    }
                    .accessibilityIdentifier("sharedWithMeButton")
                    Divider()
                    Button { path.append(Route.settings) } label: {
                        Label(LocalizedStringKey("action_settings"), systemImage: "gearshape")
                    }
                    .accessibilityIdentifier("settingsButton")
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityIdentifier("moreMenu")
            }
        }
        .sheet(isPresented: $showCreateSheet) {
            createProjectSheet
        }
        .alert(
            LocalizedStringKey("dialog_delete_project_title"),
            isPresented: $showDeleteConfirmation,
            presenting: projectToDelete
        ) { project in
            Button(LocalizedStringKey("action_delete"), role: .destructive) {
                viewModel.onEvent(event: ProjectListEventDeleteProject(id: project.id))
            }
            Button(LocalizedStringKey("action_cancel"), role: .cancel) {}
        } message: { project in
            // `dialog_delete_project_body` is parametric ("%1$@" / "%1$s" in
            // Android) — resolve via `String(format:)` so the project title
            // substitutes in. Android uses `stringResource(res, name)`.
            Text(
                String(
                    format: NSLocalizedString("dialog_delete_project_body", comment: ""),
                    project.title
                )
            )
        }
        .onChange(of: state.error != nil) { _, hasError in
            showError = hasError
        }
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button(LocalizedStringKey("action_ok")) { viewModel.onEvent(event: ProjectListEventClearError.shared) }
        } message: {
            // ViewModel error strings are still raw — typed error-channel
            // localization is tracked in the Tech Debt Backlog.
            Text(state.error?.localizedString ?? "")
        }
    }

    @ViewBuilder
    private func sortMenu(currentOrder: Shared.SortOrder) -> some View {
        Menu {
            Button {
                viewModel.onEvent(event: ProjectListEventUpdateSortOrder(order: .recent))
            } label: {
                Label(
                    LocalizedStringKey("label_sort_recent"),
                    systemImage: currentOrder == .recent ? "checkmark" : ""
                )
            }
            Button {
                viewModel.onEvent(event: ProjectListEventUpdateSortOrder(order: .alphabetical))
            } label: {
                Label(
                    LocalizedStringKey("label_sort_alphabetical_detail"),
                    systemImage: currentOrder == .alphabetical ? "checkmark" : ""
                )
            }
            Button {
                viewModel.onEvent(event: ProjectListEventUpdateSortOrder(order: .progress))
            } label: {
                // Use the `_detail` variant to match the adjacent
                // `label_sort_alphabetical_detail` menu entry — the short form
                // `label_sort_progress` is reserved for the chip render that
                // only Android uses today.
                Label(
                    LocalizedStringKey("label_sort_progress_detail"),
                    systemImage: currentOrder == .progress ? "checkmark" : ""
                )
            }
        } label: {
            Label(LocalizedStringKey("action_sort"), systemImage: "arrow.up.arrow.down")
        }
    }

    private var createProjectSheet: some View {
        NavigationStack {
            Form {
                // `.accessibilityIdentifier` gives XCUITests + Maestro a locale-independent
                // selector — TextField's bare-literal `titleKey` is still auto-localized
                // via LocalizedStringKey per the SwiftUI literal-promotion table in
                // docs/{en,ja}/i18n-convention.md.
                TextField(LocalizedStringKey("label_title"), text: $newTitle)
                    .accessibilityIdentifier("projectNameInput")
                TextField(LocalizedStringKey("label_total_rows_optional"), text: $newTotalRows)
                    .keyboardType(.numberPad)
                    .accessibilityIdentifier("totalRowsInput")

                if !holder.state.patternsForCreate.isEmpty {
                    Picker(LocalizedStringKey("label_pattern_optional"), selection: $selectedPatternId) {
                        Text(LocalizedStringKey("label_none")).tag(nil as String?)
                        ForEach(holder.state.patternsForCreate, id: \.id) { pattern in
                            Text(verbatim: pattern.title).tag(pattern.id as String?)
                        }
                    }
                }
            }
            .navigationTitle(LocalizedStringKey("dialog_new_project_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(LocalizedStringKey("action_cancel")) {
                        resetCreateForm()
                        showCreateSheet = false
                    }
                    .accessibilityIdentifier("cancelButton")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(LocalizedStringKey("action_create")) {
                        let totalRows = Int32(newTotalRows).map { KotlinInt(value: $0) }
                        viewModel.onEvent(event: ProjectListEventCreateProject(
                            title: newTitle,
                            totalRows: totalRows,
                            patternId: selectedPatternId
                        ))
                        resetCreateForm()
                        showCreateSheet = false
                    }
                    .accessibilityIdentifier("createProjectButton")
                    .disabled(newTitle.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            // Landmark for Maestro / XCUITest — SwiftUI `NavigationStack` nav
            // title is not an `accessibilityIdentifier`-addressable element, so
            // a container landmark is the only locale-independent route. Mirrors
            // the Kotlin `newProjectDialog` landmark added in Phase 33.4.
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("newProjectDialog")
        }
        .presentationDetents([.medium])
    }

    private func resetCreateForm() {
        newTitle = ""
        newTotalRows = ""
        selectedPatternId = nil
    }
}

// MARK: - Status Filter Section

private struct StatusFilterSection: View {
    let statusFilter: ProjectStatus?
    let onStatusFilterChange: (ProjectStatus?) -> Void

    var body: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    StatusFilterChip(
                        titleKey: LocalizedStringKey("label_status_all"),
                        isSelected: statusFilter == nil,
                        action: { onStatusFilterChange(nil) }
                    )
                    .accessibilityIdentifier("filterAllChip")
                    StatusFilterChip(
                        titleKey: LocalizedStringKey("label_status_in_progress"),
                        isSelected: statusFilter == .inProgress,
                        action: { onStatusFilterChange(statusFilter == .inProgress ? nil : .inProgress) }
                    )
                    .accessibilityIdentifier("filterInProgressChip")
                    StatusFilterChip(
                        titleKey: LocalizedStringKey("label_status_not_started"),
                        isSelected: statusFilter == .notStarted,
                        action: { onStatusFilterChange(statusFilter == .notStarted ? nil : .notStarted) }
                    )
                    .accessibilityIdentifier("filterNotStartedChip")
                    StatusFilterChip(
                        titleKey: LocalizedStringKey("label_status_completed"),
                        isSelected: statusFilter == .completed,
                        action: { onStatusFilterChange(statusFilter == .completed ? nil : .completed) }
                    )
                    .accessibilityIdentifier("filterCompletedChip")
                }
                .padding(.vertical, DesignTokens.listRowPaddingV)
            }
        }
        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
    }
}

private struct StatusFilterChip: View {
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

// MARK: - Project Row

private struct ProjectRow: View {
    let project: Project

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(verbatim: project.title)
                    .font(.headline)
                Spacer()
                StatusBadge(status: project.status)
            }

            HStack {
                if let total = project.totalRows?.intValue {
                    Text(
                        String(
                            format: NSLocalizedString("label_rows_of_total", comment: ""),
                            project.currentRow,
                            total
                        )
                    )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("projectRowCount")
                } else {
                    Text(
                        String(
                            format: NSLocalizedString("label_rows_current", comment: ""),
                            project.currentRow
                        )
                    )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("projectRowCount")
                }
            }

            if let total = project.totalRows?.intValue, total > 0 {
                ProgressView(value: Double(project.currentRow), total: Double(total))
                    .tint(progressTint)
            }
        }
        .padding(.vertical, DesignTokens.listRowPaddingV)
    }

    private var progressTint: Color {
        switch project.status {
        case .completed: return .green
        case .inProgress: return .accentColor
        default: return .secondary
        }
    }
}

// MARK: - Status Badge

struct StatusBadge: View {
    let status: ProjectStatus

    var body: some View {
        statusText
            .font(.caption2)
            .fontWeight(.medium)
            .padding(.horizontal, DesignTokens.badgePaddingH)
            .padding(.vertical, DesignTokens.badgePaddingV)
            .background(statusColor.opacity(DesignTokens.highlightOpacity))
            .foregroundStyle(statusColor)
            .clipShape(Capsule())
            .accessibilityIdentifier("projectStatusBadge")
    }

    @ViewBuilder
    private var statusText: some View {
        switch status {
        case .notStarted: Text(LocalizedStringKey("label_status_not_started"))
        case .inProgress: Text(LocalizedStringKey("label_status_in_progress"))
        case .completed: Text(LocalizedStringKey("label_status_completed"))
        // `default` is forced by the Kotlin-enum→ObjC bridging — Kotlin
        // `ProjectStatus` is a closed enum but Swift sees it as open. Per the
        // Phase 33.1.13 precedent, return a locale-neutral non-empty fallback
        // rather than an empty string so an unhandled case surfaces visibly.
        default: Text(verbatim: "\(status)")
        }
    }

    private var statusColor: Color {
        switch status {
        case .notStarted: return .secondary
        case .inProgress: return .accentColor
        case .completed: return .green
        default: return .secondary
        }
    }
}
