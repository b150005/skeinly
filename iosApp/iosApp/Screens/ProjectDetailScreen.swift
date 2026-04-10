import SwiftUI
import Shared

struct ProjectDetailScreen: View {
    let projectId: String
    @Binding var path: NavigationPath
    private let viewModel: ProjectDetailViewModel
    @StateObject private var stateObserver: ViewModelObserver<ProjectDetailState>
    @StateObject private var notesObserver: ViewModelObserver<NSArray>
    @State private var showError = false
    @State private var showEditSheet = false
    @State private var showAddNoteSheet = false
    @State private var showShareLink = false
    @State private var showDeleteNoteConfirmation = false
    @State private var noteToDelete: Shared.Progress?
    @State private var showCompleteConfirmation = false
    @State private var editTitle = ""
    @State private var editTotalRows = ""
    @State private var newNote = ""

    init(projectId: String, path: Binding<NavigationPath>) {
        self.projectId = projectId
        self._path = path
        let vm = ViewModelFactory.projectDetailViewModel(projectId: projectId)
        self.viewModel = vm
        let stateWrapper = KoinHelperKt.wrapStateFlow(flow: vm.state) as! FlowWrapper<ProjectDetailState>
        _stateObserver = StateObject(wrappedValue: ViewModelObserver(wrapper: stateWrapper))
        let notesWrapper = KoinHelperKt.wrapStateFlow(flow: vm.progressNotes) as! FlowWrapper<NSArray>
        _notesObserver = StateObject(wrappedValue: ViewModelObserver(wrapper: notesWrapper))
    }

    private var notes: [Shared.Progress] {
        notesObserver.state as? [Shared.Progress] ?? []
    }

    var body: some View {
        let state = stateObserver.state

        Group {
            if state.isLoading {
                ProgressView()
            } else if let project = state.project {
                projectContent(project)
            }
        }
        .navigationTitle(state.project?.title ?? "Project")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Button { viewModel.onEvent(event: ProjectDetailEventShareProject.shared) } label: {
                    Image(systemName: "square.and.arrow.up")
                }
                Button { prepareEdit(state.project) } label: {
                    Image(systemName: "pencil")
                }
            }
        }
        .sheet(isPresented: $showEditSheet) { editSheet(state.project) }
        .sheet(isPresented: $showAddNoteSheet) { addNoteSheet }
        .alert("Share Link", isPresented: $showShareLink) {
            Button("Copy") {
                if let link = state.shareLink {
                    UIPasteboard.general.string = "knitnote://share/\(link.shareToken)"
                }
                viewModel.onEvent(event: ProjectDetailEventDismissShareDialog.shared)
            }
            Button("Close", role: .cancel) {
                viewModel.onEvent(event: ProjectDetailEventDismissShareDialog.shared)
            }
        } message: {
            if let link = state.shareLink {
                Text("knitnote://share/\(link.shareToken)")
            }
        }
        .alert("Delete Note?", isPresented: $showDeleteNoteConfirmation) {
            Button("Delete", role: .destructive) {
                if let note = noteToDelete {
                    viewModel.onEvent(event: ProjectDetailEventDeleteNote(progressId: note.id))
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .onChange(of: state.error) { error in
            showError = error != nil
        }
        .onChange(of: state.shareLink) { link in
            showShareLink = link != nil
        }
        .alert("Error", isPresented: $showError) {
            Button("OK") { viewModel.onEvent(event: ProjectDetailEventClearError.shared) }
        } message: {
            Text(state.error ?? "")
        }
    }

    // MARK: - Project Content

    @ViewBuilder
    private func projectContent(_ project: Project) -> some View {
        List {
            // Counter section
            Section {
                counterSection(project)
            }

            // Status action
            Section {
                if project.status == .completed {
                    Button("Reopen Project") {
                        viewModel.onEvent(event: ProjectDetailEventReopenProject.shared)
                    }
                } else {
                    Button("Mark Complete") {
                        viewModel.onEvent(event: ProjectDetailEventCompleteProject.shared)
                    }
                }
            }

            // Notes section
            Section {
                HStack {
                    Text("Notes")
                        .font(.headline)
                    Spacer()
                    Button {
                        showAddNoteSheet = true
                    } label: {
                        Image(systemName: "plus.circle")
                    }
                }

                if notes.isEmpty {
                    Text("No notes yet")
                        .foregroundStyle(.secondary)
                        .font(.subheadline)
                } else {
                    ForEach(notes, id: \.id) { note in
                        NoteRow(note: note)
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    noteToDelete = note
                                    showDeleteNoteConfirmation = true
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                    }
                }
            }

            // Comments section
            Section {
                CommentSectionView(
                    targetType: .project,
                    targetId: projectId
                )
            }
        }
    }

    // MARK: - Counter Section

    @ViewBuilder
    private func counterSection(_ project: Project) -> some View {
        VStack(spacing: 16) {
            StatusBadge(status: project.status)

            Text("\(project.currentRow)")
                .font(.system(size: 72, weight: .bold, design: .rounded))
                .monospacedDigit()

            if let total = project.totalRows?.intValue {
                Text("of \(total) rows")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                ProgressView(value: Double(project.currentRow), total: Double(total))
            } else {
                Text("rows")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 32) {
                Button {
                    viewModel.onEvent(event: ProjectDetailEventDecrementRow.shared)
                } label: {
                    Image(systemName: "minus.circle.fill")
                        .font(.system(size: 48))
                }
                .disabled(project.currentRow <= 0 || project.status == .completed)

                Button {
                    viewModel.onEvent(event: ProjectDetailEventIncrementRow.shared)
                } label: {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: 64))
                }
                .disabled(project.status == .completed)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical)
    }

    // MARK: - Edit Sheet

    private func prepareEdit(_ project: Project?) {
        guard let project else { return }
        editTitle = project.title
        editTotalRows = project.totalRows.map { "\($0)" } ?? ""
        showEditSheet = true
    }

    @ViewBuilder
    private func editSheet(_ project: Project?) -> some View {
        NavigationStack {
            Form {
                TextField("Title", text: $editTitle)
                TextField("Total Rows (optional)", text: $editTotalRows)
                    .keyboardType(.numberPad)
            }
            .navigationTitle("Edit Project")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showEditSheet = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let totalRows = Int32(editTotalRows).map { Int($0) }
                        viewModel.onEvent(event: ProjectDetailEventEditProject(
                            title: editTitle,
                            totalRows: totalRows.map { KotlinInt(value: Int32($0)) }
                        ))
                        showEditSheet = false
                    }
                    .disabled(editTitle.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }

    // MARK: - Add Note Sheet

    private var addNoteSheet: some View {
        NavigationStack {
            Form {
                TextField("Note", text: $newNote, axis: .vertical)
                    .lineLimit(3...6)
            }
            .navigationTitle("Add Note")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        newNote = ""
                        showAddNoteSheet = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        viewModel.onEvent(event: ProjectDetailEventAddNote(note: newNote))
                        newNote = ""
                        showAddNoteSheet = false
                    }
                    .disabled(newNote.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Note Row

private struct NoteRow: View {
    let note: Shared.Progress

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(note.note)
                .font(.body)
            Text("Row \(note.rowNumber) \u{2022} \(formattedDate)")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var formattedDate: String {
        let epochSeconds = note.createdAt.epochSeconds
        let date = Date(timeIntervalSince1970: TimeInterval(epochSeconds))
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
