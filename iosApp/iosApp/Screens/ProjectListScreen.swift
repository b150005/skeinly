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

    init(path: Binding<NavigationPath>) {
        self._path = path
        let vm = ViewModelFactory.projectListViewModel()
        self.viewModel = vm
        let wrapper = KoinHelperKt.wrapStateFlow(flow: vm.state) as! FlowWrapper<ProjectListState>
        _observer = StateObject(wrappedValue: ViewModelObserver(wrapper: wrapper))
    }

    var body: some View {
        let state = observer.state

        Group {
            if state.isLoading && state.projects.isEmpty {
                ProgressView()
            } else if state.projects.isEmpty {
                ContentUnavailableView(
                    "No Projects Yet",
                    systemImage: "square.stack.3d.up.slash",
                    description: Text("Tap + to create your first knitting project.")
                )
            } else {
                List {
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
        .navigationTitle("Knit Note")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showCreateSheet = true
                } label: {
                    Image(systemName: "plus")
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
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
        .onChange(of: state.error) { error in
            showError = error != nil
        }
        .alert("Error", isPresented: $showError) {
            Button("OK") { viewModel.onEvent(event: ProjectListEventClearError.shared) }
        } message: {
            Text(state.error ?? "")
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
