import SwiftUI
import PhotosUI
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
    @State private var showDeleteChartImageConfirmation = false
    @State private var chartImageToDelete: String?
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var showChartViewer = false
    @State private var chartViewerIndex: Int = 0
    @State private var editTitle = ""
    @State private var editTotalRows = ""
    @State private var newNote = ""

    init(projectId: String, path: Binding<NavigationPath>) {
        self.projectId = projectId
        self._path = path
        let vm = ViewModelFactory.projectDetailViewModel(projectId: projectId)
        self.viewModel = vm
        let stateWrapper = KoinHelperKt.wrapProjectDetailState(flow: vm.state)
        _stateObserver = StateObject(wrappedValue: ViewModelObserver(wrapper: stateWrapper))
        let notesWrapper = KoinHelperKt.wrapProgressNotesState(flow: vm.progressNotes)
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
        .alert("Remove Chart Image?", isPresented: $showDeleteChartImageConfirmation) {
            Button("Remove", role: .destructive) {
                if let path = chartImageToDelete {
                    viewModel.onEvent(event: ProjectDetailEventDeleteChartImage(imagePath: path))
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .fullScreenCover(isPresented: $showChartViewer) {
            let urls = stateObserver.state.chartImageSignedUrls as? [String] ?? []
            if chartViewerIndex < urls.count {
                ChartImageViewer(
                    imageUrl: urls[chartViewerIndex],
                    onDismiss: { showChartViewer = false }
                )
            }
        }
        .onChange(of: selectedPhotoItem) { item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    let compressed = compressImageToJpeg(data: data, maxSize: 2 * 1024 * 1024)
                    let fileName = "chart_\(Int(Date().timeIntervalSince1970)).jpg"
                    let kotlinData = dataToKotlinByteArray(compressed)
                    viewModel.onEvent(event: ProjectDetailEventUploadChartImage(data: kotlinData, fileName: fileName))
                }
                selectedPhotoItem = nil
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

            // Chart images section
            Section {
                chartImagesSection(state)
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
                .accessibilityIdentifier("rowCounter")

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
                .accessibilityIdentifier("decrementButton")

                Button {
                    viewModel.onEvent(event: ProjectDetailEventIncrementRow.shared)
                } label: {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: 64))
                }
                .disabled(project.status == .completed)
                .accessibilityIdentifier("incrementButton")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical)
    }

    // MARK: - Chart Images Section

    @ViewBuilder
    private func chartImagesSection(_ state: ProjectDetailState) -> some View {
        let signedUrls = state.chartImageSignedUrls as? [String] ?? []
        let storagePaths = state.chartImagePaths as? [String] ?? []

        HStack {
            Text("Chart Images")
                .font(.headline)
            Spacer()
            PhotosPicker(selection: $selectedPhotoItem, matching: .images) {
                Image(systemName: "plus.circle")
            }
        }

        if state.isUploadingImage {
            HStack {
                ProgressView()
                    .padding(.trailing, 8)
                Text("Uploading...")
                    .foregroundStyle(.secondary)
            }
        }

        if signedUrls.isEmpty && !state.isUploadingImage {
            Text("No chart images yet")
                .foregroundStyle(.secondary)
                .font(.subheadline)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(Array(signedUrls.enumerated()), id: \.offset) { index, url in
                        AsyncImage(url: URL(string: url)) { phase in
                            switch phase {
                            case .success(let image):
                                image
                                    .resizable()
                                    .scaledToFill()
                            default:
                                Rectangle()
                                    .fill(Color.gray.opacity(0.2))
                                    .overlay {
                                        ProgressView()
                                    }
                            }
                        }
                        .frame(width: 100, height: 100)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .onTapGesture {
                            chartViewerIndex = index
                            showChartViewer = true
                        }
                        .contextMenu {
                            if index < storagePaths.count {
                                Button(role: .destructive) {
                                    chartImageToDelete = storagePaths[index]
                                    showDeleteChartImageConfirmation = true
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                            }
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        }
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

// MARK: - Image Helpers

private func compressImageToJpeg(data: Data, maxSize: Int) -> Data {
    guard let image = UIImage(data: data) else { return data }
    var quality: CGFloat = 0.9
    while quality >= 0.1 {
        if let compressed = image.jpegData(compressionQuality: quality),
           compressed.count <= maxSize {
            return compressed
        }
        quality -= 0.1
    }
    return image.jpegData(compressionQuality: 0.1) ?? data
}

private func dataToKotlinByteArray(_ data: Data) -> KotlinByteArray {
    // Convert via NSData → Kotlin ByteArray using the KMP helper which uses memcpy
    let nsData = data as NSData
    return KoinHelperKt.nsDataToByteArray(nsData)
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
