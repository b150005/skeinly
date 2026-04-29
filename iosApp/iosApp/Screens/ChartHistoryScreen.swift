import SwiftUI
import Shared

/// SwiftUI mirror of the shared Compose `ChartHistoryScreen` (Phase 37.2,
/// ADR-013 §6). Owns a Koin-resolved `ChartHistoryViewModel` via
/// `ScopedViewModel` so the observed state survives parent re-inits.
///
/// Tap a revision row → ViewModel emits a `RevisionTapTarget` carrying
/// `(targetRevisionId, baseRevisionId)`. The Phase 37.3 path appends a
/// `Route.chartDiff` to the navigation stack (initial-commit case routes
/// `baseRevisionId = nil`).
struct ChartHistoryScreen: View {
    let patternId: String
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<ChartHistoryViewModel, ChartHistoryState>
    @State private var revisionTapCloseable: Closeable?
    @State private var showError = false

    private var viewModel: ChartHistoryViewModel { holder.viewModel }

    init(patternId: String, path: Binding<NavigationPath>) {
        self.patternId = patternId
        self._path = path
        let vm = ViewModelFactory.chartHistoryViewModel(patternId: patternId)
        let wrapper = KoinHelperKt.wrapChartHistoryState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        contentView
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("chartHistoryScreen")
            .navigationTitle(LocalizedStringKey("title_chart_history"))
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: holder.state.error != nil) { _, hasError in
            showError = hasError
        }
            .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
                Button("action_ok") {
                    viewModel.onEvent(event: ChartHistoryEventClearError.shared)
                }
            } message: {
                Text(holder.state.error?.localizedString ?? "")
            }
            .task { observeRevisionTaps() }
            .onDisappear {
                revisionTapCloseable?.close()
                revisionTapCloseable = nil
            }
    }

    @ViewBuilder
    private var contentView: some View {
        let state = holder.state
        if state.isLoading {
            ProgressView()
        } else if state.revisions.isEmpty {
            ContentUnavailableView(
                LocalizedStringKey("state_no_chart_history"),
                systemImage: "clock.arrow.circlepath",
                description: Text(LocalizedStringKey("state_no_chart_history_body"))
            )
        } else {
            List(state.revisions, id: \.revisionId) { revision in
                RevisionRow(revision: revision)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        viewModel.onEvent(
                            event: ChartHistoryEventTapRevision(revisionId: revision.revisionId)
                        )
                    }
                    .onLongPressGesture {
                        viewModel.onEvent(
                            event: ChartHistoryEventLongPressRevision(revisionId: revision.revisionId)
                        )
                    }
            }
            .confirmationDialog(
                LocalizedStringKey("dialog_restore_revision_title"),
                isPresented: Binding(
                    get: { state.pendingRestoreRevision != nil },
                    set: { newValue in
                        if !newValue {
                            viewModel.onEvent(event: ChartHistoryEventDismissRestore.shared)
                        }
                    }
                ),
                titleVisibility: .visible
            ) {
                Button(LocalizedStringKey("action_restore_revision")) {
                    viewModel.onEvent(event: ChartHistoryEventConfirmRestore.shared)
                }
                .accessibilityIdentifier("confirmRestoreRevisionButton")
                Button(LocalizedStringKey("action_cancel"), role: .cancel) {
                    viewModel.onEvent(event: ChartHistoryEventDismissRestore.shared)
                }
            } message: {
                Text(LocalizedStringKey("dialog_restore_revision_body"))
            }
        }
    }

    private func observeRevisionTaps() {
        // `.task { }` re-fires on every view re-appearance. Close any prior
        // subscription before replacing it so we do not leak one Closeable per
        // background/foreground cycle (same idiom as DiscoveryScreen.swift).
        revisionTapCloseable?.close()
        revisionTapCloseable = nil
        let wrapper = KoinHelperKt.wrapChartHistoryRevisionTaps(flow: viewModel.revisionTaps)
        revisionTapCloseable = wrapper.collect { target in
            Task { @MainActor in
                path.append(
                    Route.chartDiff(
                        baseRevisionId: target.baseRevisionId,
                        targetRevisionId: target.targetRevisionId
                    )
                )
            }
        }
    }
}

private struct RevisionRow: View {
    let revision: ChartRevision

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(headline)
                .font(.body)
                .accessibilityIdentifier("commitMessageLabel_\(revision.revisionId)")
            Text(formattedDate)
                .font(.caption)
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("revisionTimestampLabel_\(revision.revisionId)")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("revisionRow_\(revision.revisionId)")
    }

    private var headline: String {
        if let message = revision.commitMessage, !message.isEmpty {
            return message
        }
        let key = revision.parentRevisionId == nil ? "label_initial_commit" : "label_auto_save"
        return NSLocalizedString(key, comment: "")
    }

    private var formattedDate: String {
        let date = Date(timeIntervalSince1970: TimeInterval(revision.createdAt.epochSeconds))
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
