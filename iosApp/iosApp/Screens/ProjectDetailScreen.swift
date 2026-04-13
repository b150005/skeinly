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
                projectContent(project, state: state)
            }
        }
        .navigationTitle(state.project?.title ?? "Project")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarItems(state) }
        .sheet(isPresented: $showEditSheet) { editSheet(state.project) }
        .sheet(isPresented: $showAddNoteSheet) { addNoteSheet }
        .shareLinkAlert(state: state, isPresented: $showShareLink, viewModel: viewModel)
        .deleteChartImageAlert(isPresented: $showDeleteChartImageConfirmation, chartImageToDelete: chartImageToDelete, viewModel: viewModel)
        .chartViewerCover(isPresented: $showChartViewer, urls: state.chartImageSignedUrls, index: chartViewerIndex)
        .onChange(of: selectedPhotoItem) { _, newItem in
            handlePhotoSelection(newItem)
        }
        .deleteNoteAlert(isPresented: $showDeleteNoteConfirmation, noteToDelete: noteToDelete, viewModel: viewModel)
        .onChange(of: state.error) { _, newError in
            showError = newError != nil
        }
        .onChange(of: state.shareLink) { _, newLink in
            showShareLink = newLink != nil
        }
        .alert("Error", isPresented: $showError) {
            Button("OK") { viewModel.onEvent(event: ProjectDetailEventClearError.shared) }
        } message: {
            Text(state.error ?? "")
        }
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private func toolbarItems(_ state: ProjectDetailState) -> some ToolbarContent {
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            Button { viewModel.onEvent(event: ProjectDetailEventShareProject.shared) } label: {
                Image(systemName: "square.and.arrow.up")
            }
            Button { prepareEdit(state.project) } label: {
                Image(systemName: "pencil")
            }
            .accessibilityLabel("Edit project")
        }
    }

    // MARK: - Photo Handling

    private func handlePhotoSelection(_ item: PhotosPickerItem?) {
        guard let item else { return }
        Task {
            if let data = try? await item.loadTransferable(type: Data.self) {
                let compressed = compressImageToJpeg(data: data, maxSize: 2 * 1024 * 1024)
                let timestamp = Int(Date().timeIntervalSince1970)
                let fileName = "chart_\(timestamp).jpg"
                let kotlinData = dataToKotlinByteArray(compressed)
                viewModel.onEvent(event: ProjectDetailEventUploadChartImage(data: kotlinData, fileName: fileName))
            }
            selectedPhotoItem = nil
        }
    }

    // MARK: - Project Content

    @ViewBuilder
    private func projectContent(_ project: Project, state: ProjectDetailState) -> some View {
        List {
            // Counter section
            Section {
                counterSection(project)
            }

            // Pattern info section
            if let pattern = state.pattern {
                Section("Pattern Info") {
                    patternInfoSection(pattern)
                }
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
                        NoteRow(
                            note: note,
                            photoSignedUrl: stateObserver.state.photoSignedUrls[note.id] as? String
                        )
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
                .font(.system(size: DesignTokens.counterFontSize, weight: .bold, design: .rounded))
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
                        .font(.system(size: DesignTokens.decrementButtonSize))
                }
                .disabled(project.currentRow <= 0 || project.status == .completed)
                .accessibilityIdentifier("decrementButton")
                .accessibilityLabel("Decrement row")

                Button {
                    viewModel.onEvent(event: ProjectDetailEventIncrementRow.shared)
                } label: {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: DesignTokens.incrementButtonSize))
                }
                .disabled(project.status == .completed)
                .accessibilityIdentifier("incrementButton")
                .accessibilityLabel("Increment row")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical)
    }

    // MARK: - Pattern Info Section

    @ViewBuilder
    private func patternInfoSection(_ pattern: Pattern) -> some View {
        LabeledContent("Title", value: pattern.title)

        if let difficulty = pattern.difficulty {
            LabeledContent("Difficulty") {
                DifficultyBadge(difficulty: difficulty)
            }
        }

        if let gauge = pattern.gauge, !gauge.isEmpty {
            LabeledContent("Gauge", value: gauge)
        }

        if let yarnInfo = pattern.yarnInfo, !yarnInfo.isEmpty {
            LabeledContent("Yarn", value: yarnInfo)
        }

        if let needleSize = pattern.needleSize, !needleSize.isEmpty {
            LabeledContent("Needle Size", value: needleSize)
        }
    }

    // MARK: - Chart Images Section

    @ViewBuilder
    private func chartImagesSection(_ state: ProjectDetailState) -> some View {
        let signedUrls = state.chartImageSignedUrls
        let storagePaths = state.chartImagePaths

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

    @State private var notePhotoItem: PhotosPickerItem?
    @State private var notePhotoData: Data?

    private var addNoteSheet: some View {
        NavigationStack {
            Form {
                TextField("Note", text: $newNote, axis: .vertical)
                    .lineLimit(3...6)

                Section {
                    if let photoData = notePhotoData, let uiImage = UIImage(data: photoData) {
                        HStack {
                            Image(uiImage: uiImage)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 60, height: 60)
                                .clipShape(RoundedRectangle(cornerRadius: 8))

                            Text("Photo attached")
                                .font(.caption)
                                .foregroundStyle(.secondary)

                            Spacer()

                            Button(role: .destructive) {
                                notePhotoItem = nil
                                notePhotoData = nil
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    } else {
                        PhotosPicker(selection: $notePhotoItem, matching: .images) {
                            Label("Add Photo", systemImage: "photo.on.rectangle.angled")
                        }
                    }
                }
            }
            .navigationTitle("Add Note")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        newNote = ""
                        notePhotoItem = nil
                        notePhotoData = nil
                        showAddNoteSheet = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        if let photoData = notePhotoData {
                            let compressed = compressImageToJpeg(data: photoData, maxSize: 2 * 1024 * 1024)
                            let kotlinData = dataToKotlinByteArray(compressed)
                            let fileName = "progress_\(Int(Date().timeIntervalSince1970)).jpg"
                            viewModel.onEvent(
                                event: ProjectDetailEventAddNoteWithPhoto(
                                    note: newNote,
                                    photoData: kotlinData,
                                    photoFileName: fileName
                                )
                            )
                        } else {
                            viewModel.onEvent(event: ProjectDetailEventAddNote(note: newNote))
                        }
                        newNote = ""
                        notePhotoItem = nil
                        notePhotoData = nil
                        showAddNoteSheet = false
                    }
                    .disabled(newNote.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onChange(of: notePhotoItem) { _, newItem in
                Task {
                    if let data = try? await newItem?.loadTransferable(type: Data.self) {
                        notePhotoData = data
                    }
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
    return KoinHelperKt.nsDataToByteArray(data: data)
}

// MARK: - Note Row

private struct NoteRow: View {
    let note: Shared.Progress
    var photoSignedUrl: String? = nil
    @State private var showPhotoViewer = false

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(note.note)
                    .font(.body)
                Text("Row \(note.rowNumber) \u{2022} \(formattedDate)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            if let urlString = photoSignedUrl, let url = URL(string: urlString) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFill()
                    case .failure:
                        Image(systemName: "photo")
                            .foregroundStyle(.secondary)
                    default:
                        ProgressView()
                    }
                }
                .frame(width: 48, height: 48)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .onTapGesture { showPhotoViewer = true }
                .fullScreenCover(isPresented: $showPhotoViewer) {
                    ChartImageViewer(imageUrl: urlString, onDismiss: { showPhotoViewer = false })
                }
            }
        }
    }

    private var formattedDate: String {
        let epochSeconds = note.createdAt.epochSeconds
        let date = Date(timeIntervalSince1970: TimeInterval(epochSeconds))
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}

// MARK: - View Modifier Helpers (break up type-check complexity)

private extension View {
    func shareLinkAlert(
        state: ProjectDetailState,
        isPresented: Binding<Bool>,
        viewModel: ProjectDetailViewModel
    ) -> some View {
        self.alert("Share Link", isPresented: isPresented) {
            Button("Copy") {
                if let link = state.shareLink, let token = link.shareToken {
                    UIPasteboard.general.string = "knitnote://share/\(token)"
                }
                viewModel.onEvent(event: ProjectDetailEventDismissShareDialog.shared)
            }
            Button("Close", role: .cancel) {
                viewModel.onEvent(event: ProjectDetailEventDismissShareDialog.shared)
            }
        } message: {
            if let link = state.shareLink, let token = link.shareToken {
                Text(verbatim: "knitnote://share/\(token)")
            }
        }
    }

    func deleteChartImageAlert(
        isPresented: Binding<Bool>,
        chartImageToDelete: String?,
        viewModel: ProjectDetailViewModel
    ) -> some View {
        self.alert("Remove Chart Image?", isPresented: isPresented) {
            Button("Remove", role: .destructive) {
                if let path = chartImageToDelete {
                    viewModel.onEvent(event: ProjectDetailEventDeleteChartImage(imagePath: path))
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    func chartViewerCover(
        isPresented: Binding<Bool>,
        urls: [String],
        index: Int
    ) -> some View {
        self.fullScreenCover(isPresented: isPresented) {
            if index < urls.count {
                ChartImageViewer(
                    imageUrl: urls[index],
                    onDismiss: { isPresented.wrappedValue = false }
                )
            }
        }
    }

    func deleteNoteAlert(
        isPresented: Binding<Bool>,
        noteToDelete: Shared.Progress?,
        viewModel: ProjectDetailViewModel
    ) -> some View {
        self.alert("Delete Note?", isPresented: isPresented) {
            Button("Delete", role: .destructive) {
                if let note = noteToDelete {
                    viewModel.onEvent(event: ProjectDetailEventDeleteNote(progressId: note.id))
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }
}
