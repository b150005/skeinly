import SwiftUI
import PhotosUI
import Shared

struct ProjectDetailScreen: View {
    let projectId: String
    @Binding var path: NavigationPath
    @StateObject private var holder: ProjectDetailHolder
    @State private var showError = false
    @State private var showEditSheet = false
    @State private var showAddNoteSheet = false
    @State private var showShareLink = false
    @State private var showDeleteNoteConfirmation = false
    @State private var showResetProgressConfirmation = false
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
        _holder = StateObject(wrappedValue: ProjectDetailHolder(projectId: projectId))
    }

    private var viewModel: ProjectDetailViewModel { holder.viewModel }
    private var notes: [Shared.Progress] { holder.notes }

    var body: some View {
        let state = holder.state

        Group {
            if state.isLoading {
                ProgressView()
            } else if let project = state.project {
                projectContent(project, state: state)
            }
        }
        // `.navigationTitle` takes a `LocalizedStringKey`-inferred `String` when the
        // argument is a bare literal, but when the argument is a ternary of `String?`
        // like `project.title ?? "Project"`, overload resolution picks the
        // plain-String path and skips localization. Wrap the fallback explicitly
        // and drive navigationTitle through a computed binding so both branches
        // localize correctly (literal-promotion table rule from Phase 33.1.6).
        .navigationTitle(navigationTitleText(for: state))
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
        .confirmationDialog(
            LocalizedStringKey("dialog_reset_progress_title"),
            isPresented: $showResetProgressConfirmation,
            titleVisibility: .visible
        ) {
            Button(LocalizedStringKey("action_reset_progress"), role: .destructive) {
                viewModel.onEvent(event: ProjectDetailEventResetProgress.shared)
            }
            .accessibilityIdentifier("resetProgressConfirmButton")
            Button(LocalizedStringKey("action_cancel"), role: .cancel) {}
        } message: {
            Text(LocalizedStringKey("dialog_reset_progress_body"))
        }
        .onChange(of: state.error) { _, newError in
            showError = newError != nil
        }
        .onChange(of: state.shareLink) { _, newLink in
            showShareLink = newLink != nil
        }
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button(LocalizedStringKey("action_ok")) { viewModel.onEvent(event: ProjectDetailEventClearError.shared) }
        } message: {
            Text(state.error ?? "")
        }
    }

    // Returns the navigation title as a localizable Text. `project.title` is
    // user-authored free text (not localized); the fallback is i18n'd.
    private func navigationTitleText(for state: ProjectDetailState) -> Text {
        if let title = state.project?.title {
            return Text(verbatim: title)
        }
        return Text(LocalizedStringKey("title_project"))
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private func toolbarItems(_ state: ProjectDetailState) -> some ToolbarContent {
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            Button { viewModel.onEvent(event: ProjectDetailEventShareProject.shared) } label: {
                Image(systemName: "square.and.arrow.up")
            }
            .accessibilityLabel(Text(LocalizedStringKey("action_share_link")))
            Button { prepareEdit(state.project) } label: {
                Image(systemName: "pencil")
            }
            .accessibilityLabel(Text(LocalizedStringKey("action_edit_project")))
            .accessibilityIdentifier("editProjectButton")
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

                // Phase 34 US-7: per-segment progress summary. Shows only when a
                // structured chart is linked and has placed cells on visible
                // layers. Tapping deep-links to ChartViewer for the pattern.
                if let done = state.segmentsDone?.intValue,
                   let total = state.segmentsTotal?.intValue {
                    segmentProgressSummary(
                        done: done,
                        total: total,
                        onTap: {
                            path.append(Route.chartViewer(patternId: project.patternId, projectId: projectId))
                        }
                    )
                }
            }

            // Pattern info section
            if let pattern = state.pattern {
                Section(LocalizedStringKey("label_pattern_info_section")) {
                    patternInfoSection(
                        pattern,
                        hasStructuredChart: state.hasStructuredChart,
                        parentPattern: state.parentPattern,
                        parentPatternAuthor: state.parentPatternAuthor,
                        onChartViewerTap: {
                            path.append(Route.chartViewer(patternId: pattern.id, projectId: projectId))
                        },
                        onChartEditorTap: {
                            path.append(Route.chartEditor(patternId: pattern.id))
                        },
                        // Phase 36.5 (ADR-012 §6): tap on the "Forked from" row
                        // routes to the source pattern's read-only chart viewer.
                        // `projectId: nil` because the user is browsing someone
                        // else's pattern — segment overlay belongs to the current
                        // project, not the source.
                        onParentPatternTap: { parentPatternId in
                            path.append(Route.chartViewer(patternId: parentPatternId, projectId: nil))
                        },
                        // Phase 37.2 (ADR-013 §6).
                        onChartHistoryTap: {
                            path.append(Route.chartHistory(patternId: pattern.id))
                        },
                        // Phase 38.2 (ADR-014 §6) — default filter is Outgoing
                        // when the project's pattern is itself a fork (the
                        // user authored PRs targeting the upstream); Incoming
                        // when the pattern IS the upstream.
                        onSuggestionsTap: {
                            let filter: PullRequestFilter =
                                pattern.parentPatternId != nil ? .outgoing : .incoming
                            path.append(Route.pullRequestList(defaultFilter: filter))
                        }
                    )
                }
            }

            // Status action
            Section {
                if project.status == .completed {
                    Button(LocalizedStringKey("action_reopen_project")) {
                        viewModel.onEvent(event: ProjectDetailEventReopenProject.shared)
                    }
                    .accessibilityIdentifier("reopenProjectButton")
                } else {
                    Button(LocalizedStringKey("action_mark_complete")) {
                        viewModel.onEvent(event: ProjectDetailEventCompleteProject.shared)
                    }
                    .accessibilityIdentifier("markCompleteButton")
                }

                // Phase 34 US-4: Reset segment progress. Enabled only when the
                // project has a linked structured chart AND at least one segment
                // row exists — matches PRD AC-4.1.
                if state.hasStructuredChart && state.hasSegmentProgress {
                    Button(role: .destructive) {
                        showResetProgressConfirmation = true
                    } label: {
                        Label(
                            LocalizedStringKey("action_reset_progress"),
                            systemImage: "arrow.counterclockwise"
                        )
                    }
                    .accessibilityIdentifier("resetProgressButton")
                }
            }

            // Chart images section
            Section {
                chartImagesSection(state)
            }

            // Notes section
            Section {
                HStack {
                    Text(LocalizedStringKey("label_notes_section"))
                        .font(.headline)
                    Spacer()
                    Button {
                        showAddNoteSheet = true
                    } label: {
                        Image(systemName: "plus.circle")
                    }
                    .accessibilityLabel(Text(LocalizedStringKey("action_add_note")))
                }

                if notes.isEmpty {
                    Text(LocalizedStringKey("state_no_notes"))
                        .foregroundStyle(.secondary)
                        .font(.subheadline)
                        .accessibilityIdentifier("noNotesLabel")
                } else {
                    ForEach(notes, id: \.id) { note in
                        NoteRow(
                            note: note,
                            photoSignedUrl: holder.state.photoSignedUrls[note.id] as? String
                        )
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    noteToDelete = note
                                    showDeleteNoteConfirmation = true
                                } label: {
                                    Label(LocalizedStringKey("action_delete"), systemImage: "trash")
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
                Text(String(format: NSLocalizedString("label_of_rows", comment: ""), total))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("rowTotalLabel")

                ProgressView(value: Double(project.currentRow), total: Double(total))
            } else {
                Text(LocalizedStringKey("label_rows_only"))
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
                .accessibilityLabel(Text(LocalizedStringKey("action_decrement_row")))

                Button {
                    viewModel.onEvent(event: ProjectDetailEventIncrementRow.shared)
                } label: {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: DesignTokens.incrementButtonSize))
                }
                .disabled(project.status == .completed)
                .accessibilityIdentifier("incrementButton")
                .accessibilityLabel(Text(LocalizedStringKey("action_increment_row")))
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical)
    }

    // MARK: - Segment Progress Summary (Phase 34 US-7)

    @ViewBuilder
    private func segmentProgressSummary(
        done: Int,
        total: Int,
        onTap: @escaping () -> Void
    ) -> some View {
        // Caller guarantees total > 0 (null-gated in ProjectDetailState), so
        // integer percent math is safe. Floor-division matches the "X%" reading.
        let percent = (done * 100) / total
        // %1$d / %2$d / %3$d — pass as Int32 per Phase 33.1.12 convention
        // (xcstrings `%d` specifier matches Int32 unambiguously on all Apple
        // architectures, including hypothetical 32-bit).
        Button(action: onTap) {
            Text(String(
                format: NSLocalizedString("label_segments_completed", comment: ""),
                Int32(done), Int32(total), Int32(percent)
            ))
            .font(.subheadline)
            .foregroundStyle(Color.accentColor)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("segmentProgressSummary")
    }

    // MARK: - Pattern Info Section

    @ViewBuilder
    private func patternInfoSection(
        _ pattern: Pattern,
        hasStructuredChart: Bool,
        parentPattern: Pattern?,
        parentPatternAuthor: User?,
        onChartViewerTap: @escaping () -> Void,
        onChartEditorTap: @escaping () -> Void,
        onParentPatternTap: @escaping (String) -> Void,
        onChartHistoryTap: @escaping () -> Void,
        onSuggestionsTap: @escaping () -> Void
    ) -> some View {
        LabeledContent(LocalizedStringKey("label_title"), value: pattern.title)

        // Phase 36.5 (ADR-012 §6) "Forked from" attribution row. Three render
        // states keyed off `pattern.parentPatternId` + `parentPattern`:
        //   1. parentPatternId == nil → not forked, render nothing.
        //   2. parentPatternId != nil && parentPattern == nil → source
        //      deleted/private/lookup-failed, render plain `state_forked_from_deleted`.
        //   3. parentPattern != nil → render parametric `label_forked_from`.
        //      Tappable only when source is still PUBLIC.
        if pattern.parentPatternId != nil {
            forkedFromRow(
                parentPattern: parentPattern,
                parentPatternAuthor: parentPatternAuthor,
                onTap: onParentPatternTap
            )
        }

        if hasStructuredChart {
            Button(action: onChartViewerTap) {
                Label(LocalizedStringKey("action_view_structured_chart"), systemImage: "square.grid.3x3")
                    .font(.caption)
            }
            .accessibilityIdentifier("openChartViewerLink")

            // Phase 37.2 (ADR-013 §6) "History" sibling link. Surfaces only
            // when a structured chart exists so there is at least one revision
            // to render — avoids a dead-end empty-state for metadata-only patterns.
            Button(action: onChartHistoryTap) {
                Label(LocalizedStringKey("title_chart_history"), systemImage: "clock.arrow.circlepath")
                    .font(.caption)
            }
            .accessibilityIdentifier("openChartHistoryLink")
        }

        Button(action: onChartEditorTap) {
            Label(
                hasStructuredChart
                    ? LocalizedStringKey("action_edit_structured_chart")
                    : LocalizedStringKey("action_create_structured_chart"),
                systemImage: "square.and.pencil"
            )
            .font(.caption)
        }
        .accessibilityIdentifier("openChartEditorLink")

        // Phase 38.2 (ADR-014 §6) — read-only PR list entry point.
        // Surfaces unconditionally; the outer screen passes the right default
        // filter via `pattern.parentPatternId` so upstream owners default to
        // Incoming and forkers default to Outgoing.
        Button(action: onSuggestionsTap) {
            Label(LocalizedStringKey("title_pull_requests"), systemImage: "bubble.left.and.bubble.right")
                .font(.caption)
        }
        .accessibilityIdentifier("openPullRequestsLink")

        if let difficulty = pattern.difficulty {
            LabeledContent(LocalizedStringKey("label_difficulty")) {
                DifficultyBadge(difficulty: difficulty)
            }
        }

        if let gauge = pattern.gauge, !gauge.isEmpty {
            LabeledContent(LocalizedStringKey("label_gauge"), value: gauge)
        }

        if let yarnInfo = pattern.yarnInfo, !yarnInfo.isEmpty {
            LabeledContent(LocalizedStringKey("label_yarn"), value: yarnInfo)
        }

        if let needleSize = pattern.needleSize, !needleSize.isEmpty {
            LabeledContent(LocalizedStringKey("label_needle_size"), value: needleSize)
        }
    }

    // MARK: - Forked-from Row (Phase 36.5)

    @ViewBuilder
    private func forkedFromRow(
        parentPattern: Pattern?,
        parentPatternAuthor: User?,
        onTap: @escaping (String) -> Void
    ) -> some View {
        if let source = parentPattern {
            // Use NSLocalizedString + String(format:) so both substitutions go
            // through the parametric `label_forked_from` translation. Same
            // pattern as `label_activity_*_by` parametric strings (Phase 33.1.7).
            // `Text(verbatim:)` because the resolved string contains user-
            // authored title + author name and must not be re-resolved as a
            // LocalizedStringKey.
            let authorName = parentPatternAuthor?.displayName
                ?? NSLocalizedString("label_someone", comment: "")
            let attribution = String(
                format: NSLocalizedString("label_forked_from", comment: ""),
                source.title,
                authorName
            )
            // `Shared.Visibility.PUBLIC` bridges to `.public_` in Swift because
            // `public` is a Swift reserved word. Same trailing-underscore mapping
            // as `.private_` / `VisibilityOption.toVisibility()` in PatternEditScreen.
            if source.visibility == .public_ {
                Button(action: { onTap(source.id) }) {
                    Text(verbatim: attribution)
                        .font(.caption)
                        .foregroundStyle(Color.accentColor)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("forkedFromLink")
            } else {
                Text(verbatim: attribution)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("forkedFromLabel")
            }
        } else {
            Text(LocalizedStringKey("state_forked_from_deleted"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("forkedFromDeletedLabel")
        }
    }

    // MARK: - Chart Images Section

    @ViewBuilder
    private func chartImagesSection(_ state: ProjectDetailState) -> some View {
        let signedUrls = state.chartImageSignedUrls
        let storagePaths = state.chartImagePaths

        HStack {
            Text(LocalizedStringKey("label_chart_images_section"))
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
                Text(LocalizedStringKey("state_uploading_image"))
                    .foregroundStyle(.secondary)
            }
        }

        if signedUrls.isEmpty && !state.isUploadingImage {
            Text(LocalizedStringKey("state_no_chart_images"))
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
                                    Label(LocalizedStringKey("action_remove"), systemImage: "trash")
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
                TextField(LocalizedStringKey("label_title"), text: $editTitle)
                    .accessibilityIdentifier("projectNameInput")
                TextField(LocalizedStringKey("label_total_rows_optional"), text: $editTotalRows)
                    .keyboardType(.numberPad)
                    .accessibilityIdentifier("totalRowsInput")
            }
            .navigationTitle(LocalizedStringKey("dialog_edit_project_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(LocalizedStringKey("action_cancel")) { showEditSheet = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(LocalizedStringKey("action_save")) {
                        let totalRows = Int32(editTotalRows).map { Int($0) }
                        viewModel.onEvent(event: ProjectDetailEventEditProject(
                            title: editTitle,
                            totalRows: totalRows.map { KotlinInt(value: Int32($0)) }
                        ))
                        showEditSheet = false
                    }
                    .accessibilityIdentifier("saveButton")
                    .disabled(editTitle.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            // Landmark mirrors Kotlin `editProjectDialog` (Phase 33.4) — iOS
            // `NavigationStack` nav title is not accessibilityIdentifier-
            // addressable, so a container landmark is the only locale-
            // independent route.
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("editProjectDialog")
        }
        .presentationDetents([.medium])
    }

    // MARK: - Add Note Sheet

    @State private var notePhotoItem: PhotosPickerItem?
    @State private var notePhotoData: Data?

    private var addNoteSheet: some View {
        NavigationStack {
            Form {
                TextField(LocalizedStringKey("label_note"), text: $newNote, axis: .vertical)
                    .lineLimit(3...6)

                Section {
                    if let photoData = notePhotoData, let uiImage = UIImage(data: photoData) {
                        HStack {
                            Image(uiImage: uiImage)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 60, height: 60)
                                .clipShape(RoundedRectangle(cornerRadius: 8))

                            Text(LocalizedStringKey("label_photo_attached"))
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
                            .accessibilityLabel(Text(LocalizedStringKey("action_remove_photo")))
                        }
                    } else {
                        PhotosPicker(selection: $notePhotoItem, matching: .images) {
                            Label(LocalizedStringKey("action_add_photo"), systemImage: "photo.on.rectangle.angled")
                        }
                    }
                }
            }
            .navigationTitle(LocalizedStringKey("dialog_add_note_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(LocalizedStringKey("action_cancel")) {
                        newNote = ""
                        notePhotoItem = nil
                        notePhotoData = nil
                        showAddNoteSheet = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(LocalizedStringKey("action_add")) {
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
                Text(
                    String(
                        format: NSLocalizedString("label_note_row_timestamp", comment: ""),
                        note.rowNumber,
                        formattedDate
                    )
                )
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
        // `.alert(_:isPresented:actions:message:)` takes a `LocalizedStringKey`
        // overload, but bare `String` literals also resolve via `StringProtocol`.
        // Wrap explicitly per the 33.1.6 literal-promotion table to guarantee
        // localization.
        self.alert(LocalizedStringKey("dialog_share_link_title"), isPresented: isPresented) {
            // Platform-idiom divergence (documented per the 33.1.9 action_fork /
            // action_fork_pattern precedent): iOS uses `action_copy` ("Copy") on
            // the compact .alert button because the alert presents the URL as the
            // message body; Compose ShareLinkDialog.kt uses `action_copy_link`
            // ("Copy Link") because the URL is embedded in an OutlinedTextField
            // above the button, so the verb needs to be clearer about what is
            // being copied. Both keys exist in all 5 i18n sources.
            Button(LocalizedStringKey("action_copy")) {
                if let link = state.shareLink, let token = link.shareToken {
                    UIPasteboard.general.string = "knitnote://share/\(token)"
                }
                viewModel.onEvent(event: ProjectDetailEventDismissShareDialog.shared)
            }
            Button(LocalizedStringKey("action_close"), role: .cancel) {
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
        self.alert(LocalizedStringKey("dialog_remove_chart_image_title"), isPresented: isPresented) {
            Button(LocalizedStringKey("action_remove"), role: .destructive) {
                if let path = chartImageToDelete {
                    viewModel.onEvent(event: ProjectDetailEventDeleteChartImage(imagePath: path))
                }
            }
            Button(LocalizedStringKey("action_cancel"), role: .cancel) {}
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
        self.alert(LocalizedStringKey("dialog_delete_note_title"), isPresented: isPresented) {
            Button(LocalizedStringKey("action_delete"), role: .destructive) {
                if let note = noteToDelete {
                    viewModel.onEvent(event: ProjectDetailEventDeleteNote(progressId: note.id))
                }
            }
            Button(LocalizedStringKey("action_cancel"), role: .cancel) {}
        }
    }
}
