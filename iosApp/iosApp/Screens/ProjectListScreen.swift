import SwiftUI
import Shared

struct ProjectListScreen: View {
    @Binding var path: NavigationPath
    private let viewModel: ProjectListViewModel
    @StateObject private var observer: ViewModelObserver<ProjectListState>
    @State private var showCreateSheet = false
    @State private var showError = false
    @State private var showDeleteConfirmation = false
    @State private var projectToDelete: Project?
    @State private var newTitle = ""
    @State private var newTotalRows = ""
    @State private var searchText = ""

    init(path: Binding<NavigationPath>) {
        self._path = path
        let vm = ViewModelFactory.projectListViewModel()
        self.viewModel = vm
        let wrapper = KoinHelperKt.wrapProjectListState(flow: vm.state)
        _observer = StateObject(wrappedValue: ViewModelObserver(wrapper: wrapper))
    }

    var body: some View {
        let state = observer.state
        let hasActiveFilter = !state.searchQuery.isEmpty || state.statusFilter != nil

        Group {
            if state.isLoading && state.projects.isEmpty {
                ProgressView()
            } else if state.projects.isEmpty && hasActiveFilter {
                ContentUnavailableView(
                    "No Matching Projects",
                    systemImage: "magnifyingglass",
                    description: Text("Try adjusting your search or filters.")
                )
            } else if state.projects.isEmpty {
                ContentUnavailableView(
                    "No Projects Yet",
                    systemImage: "square.stack.3d.up.slash",
                    description: Text("Tap + to create your first knitting project.")
                )
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
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
        .searchable(text: $searchText, prompt: "Search projects...")
        .onChange(of: searchText) { _, newValue in
            viewModel.onEvent(event: ProjectListEventUpdateSearchQuery(query: newValue))
        }
        .onAppear {
            searchText = observer.state.searchQuery
        }
        .navigationTitle("Knit Note")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showCreateSheet = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityIdentifier("createProjectButton")
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    sortMenu(currentOrder: state.sortOrder)

                    Divider()

                    Button { path.append(Route.profile) } label: {
                        Label("Profile", systemImage: "person.circle")
                    }
                    Button { path.append(Route.activityFeed) } label: {
                        Label("Activity", systemImage: "bell")
                    }
                    Button { path.append(Route.sharedWithMe) } label: {
                        Label("Shared With Me", systemImage: "shared.with.you")
                    }
                    Divider()
                    Button(role: .destructive) {
                        viewModel.onEvent(event: ProjectListEventSignOut.shared)
                    } label: {
                        Label("Sign Out", systemImage: "rectangle.portrait.and.arrow.right")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityIdentifier("moreMenu")
            }
        }
        .sheet(isPresented: $showCreateSheet) {
            createProjectSheet
        }
        .alert("Delete Project?", isPresented: $showDeleteConfirmation) {
            Button("Delete", role: .destructive) {
                if let project = projectToDelete {
                    viewModel.onEvent(event: ProjectListEventDeleteProject(id: project.id))
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
            Button("OK") { viewModel.onEvent(event: ProjectListEventClearError.shared) }
        } message: {
            Text(state.error ?? "")
        }
    }

    @ViewBuilder
    private func sortMenu(currentOrder: SortOrder) -> some View {
        Menu {
            Button {
                viewModel.onEvent(event: ProjectListEventUpdateSortOrder(order: .recent))
            } label: {
                Label("Recent", systemImage: currentOrder == .recent ? "checkmark" : "")
            }
            Button {
                viewModel.onEvent(event: ProjectListEventUpdateSortOrder(order: .alphabetical))
            } label: {
                Label("Alphabetical", systemImage: currentOrder == .alphabetical ? "checkmark" : "")
            }
            Button {
                viewModel.onEvent(event: ProjectListEventUpdateSortOrder(order: .progress))
            } label: {
                Label("Progress", systemImage: currentOrder == .progress ? "checkmark" : "")
            }
        } label: {
            Label("Sort by", systemImage: "arrow.up.arrow.down")
        }
    }

    private var createProjectSheet: some View {
        NavigationStack {
            Form {
                TextField("Project Title", text: $newTitle)
                TextField("Total Rows (optional)", text: $newTotalRows)
                    .keyboardType(.numberPad)
            }
            .navigationTitle("New Project")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        resetCreateForm()
                        showCreateSheet = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        let totalRows = Int32(newTotalRows).map { KotlinInt(value: $0) }
                        viewModel.onEvent(event: ProjectListEventCreateProject(
                            title: newTitle,
                            totalRows: totalRows
                        ))
                        resetCreateForm()
                        showCreateSheet = false
                    }
                    .disabled(newTitle.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }

    private func resetCreateForm() {
        newTitle = ""
        newTotalRows = ""
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
                        title: "All",
                        isSelected: statusFilter == nil,
                        action: { onStatusFilterChange(nil) }
                    )
                    StatusFilterChip(
                        title: "In Progress",
                        isSelected: statusFilter == .inProgress,
                        action: { onStatusFilterChange(statusFilter == .inProgress ? nil : .inProgress) }
                    )
                    StatusFilterChip(
                        title: "Not Started",
                        isSelected: statusFilter == .notStarted,
                        action: { onStatusFilterChange(statusFilter == .notStarted ? nil : .notStarted) }
                    )
                    StatusFilterChip(
                        title: "Completed",
                        isSelected: statusFilter == .completed,
                        action: { onStatusFilterChange(statusFilter == .completed ? nil : .completed) }
                    )
                }
                .padding(.vertical, 4)
            }
        }
        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
    }
}

private struct StatusFilterChip: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .fontWeight(isSelected ? .semibold : .regular)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(isSelected ? Color.accentColor.opacity(0.15) : Color(.systemGray6))
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
                Text(project.title)
                    .font(.headline)
                Spacer()
                StatusBadge(status: project.status)
            }

            HStack {
                if let total = project.totalRows?.intValue {
                    Text("\(project.currentRow) / \(total) rows")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    Text("\(project.currentRow) rows")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            if let total = project.totalRows?.intValue, total > 0 {
                ProgressView(value: Double(project.currentRow), total: Double(total))
                    .tint(progressTint)
            }
        }
        .padding(.vertical, 4)
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
        Text(statusText)
            .font(.caption2)
            .fontWeight(.medium)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(statusColor.opacity(0.15))
            .foregroundStyle(statusColor)
            .clipShape(Capsule())
    }

    private var statusText: String {
        switch status {
        case .notStarted: return "Not Started"
        case .inProgress: return "In Progress"
        case .completed: return "Completed"
        default: return "Unknown"
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
