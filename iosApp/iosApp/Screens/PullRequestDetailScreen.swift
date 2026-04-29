import SwiftUI
import Shared

/// SwiftUI mirror of the shared Compose `PullRequestDetailScreen` (Phase 38.3,
/// ADR-014 §6 §8). Owns a Koin-resolved `PullRequestDetailViewModel` via
/// `ScopedViewModel` so the observed state survives parent re-inits.
///
/// **Inline diff preview deferred** — same scope cut as the Compose mirror:
/// the `prDiffPreview` section ships as a "View proposed changes" link that
/// routes to the existing Phase 37.3 ChartDiffScreen between the snapshot
/// `commonAncestorRevisionId` and `sourceTipRevisionId`. Extracting the diff
/// canvas into a thumbnail-sized component is a meaningful refactor,
/// deferred to a follow-up slice.
struct PullRequestDetailScreen: View {
    let prId: String
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<PullRequestDetailViewModel, PullRequestDetailState>
    @State private var showError = false
    @State private var navEventsCloseable: Closeable?
    @State private var pendingClosedToast: Bool = false
    @State private var pendingMergedToast: Bool = false

    private var viewModel: PullRequestDetailViewModel { holder.viewModel }

    init(prId: String, path: Binding<NavigationPath>) {
        self.prId = prId
        self._path = path
        let vm = ViewModelFactory.pullRequestDetailViewModel(prId: prId)
        let wrapper = KoinHelperKt.wrapPullRequestDetailState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        contentView
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("pullRequestDetailScreen")
            .navigationTitle(LocalizedStringKey("title_pull_request_detail"))
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: holder.state.error != nil) { _, hasError in
            showError = hasError
        }
            .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
                Button("action_ok") {
                    viewModel.onEvent(event: PullRequestDetailEventClearError.shared)
                }
            } message: {
                Text(holder.state.error?.localizedString ?? "")
            }
            .overlay(alignment: .bottom) {
                if pendingClosedToast {
                    Text(LocalizedStringKey("message_pr_closed_successfully"))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.regularMaterial)
                        .clipShape(Capsule())
                        .padding(.bottom, 32)
                        .transition(.opacity)
                } else if pendingMergedToast {
                    Text(LocalizedStringKey("message_pr_merged_successfully"))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.regularMaterial)
                        .clipShape(Capsule())
                        .padding(.bottom, 32)
                        .transition(.opacity)
                }
            }
            .task {
                navEventsCloseable?.close()
                let wrapper = KoinHelperKt.wrapPullRequestDetailNavEvents(flow: viewModel.navEvents)
                navEventsCloseable = wrapper.collect { event in
                    Task { @MainActor in
                        switch event {
                        case is PullRequestDetailNavEventPrClosed:
                            withAnimation { pendingClosedToast = true }
                            try? await Task.sleep(nanoseconds: 2_000_000_000)
                            withAnimation { pendingClosedToast = false }
                        case is PullRequestDetailNavEventPrMerged:
                            withAnimation { pendingMergedToast = true }
                            try? await Task.sleep(nanoseconds: 2_000_000_000)
                            withAnimation { pendingMergedToast = false }
                        case let nav as PullRequestDetailNavEventNavigateToConflictResolution:
                            path.append(Route.chartConflictResolution(prId: nav.prId))
                        default:
                            break
                        }
                    }
                }
            }
            .onDisappear {
                navEventsCloseable?.close()
                navEventsCloseable = nil
            }
            .confirmationDialog(
                LocalizedStringKey("dialog_close_pr_title"),
                isPresented: closeDialogBinding,
                titleVisibility: .visible
            ) {
                Button(LocalizedStringKey("action_close_pr"), role: .destructive) {
                    viewModel.onEvent(event: PullRequestDetailEventConfirmClose.shared)
                }
                Button(LocalizedStringKey("action_cancel"), role: .cancel) {
                    viewModel.onEvent(event: PullRequestDetailEventDismissCloseConfirmation.shared)
                }
            } message: {
                Text(LocalizedStringKey("dialog_close_pr_body"))
            }
            .confirmationDialog(
                LocalizedStringKey("dialog_merge_pr_title"),
                isPresented: mergeDialogBinding,
                titleVisibility: .visible
            ) {
                // Phase 38.4: merge confirm dispatches ConfirmMerge → 3-way
                // conflict detection → either direct RPC merge (auto-clean)
                // or push ChartConflictResolutionScreen via the navEvent
                // collector above.
                Button(LocalizedStringKey("action_merge_pr")) {
                    viewModel.onEvent(event: PullRequestDetailEventConfirmMerge.shared)
                }
                .disabled(holder.state.isMerging)
                Button(LocalizedStringKey("action_cancel"), role: .cancel) {
                    viewModel.onEvent(event: PullRequestDetailEventDismissMergeConfirmation.shared)
                }
            } message: {
                Text(LocalizedStringKey("dialog_merge_pr_body"))
            }
    }

    private var closeDialogBinding: Binding<Bool> {
        Binding(
            get: { holder.state.pendingCloseConfirmation },
            set: { isPresented in
                if !isPresented && holder.state.pendingCloseConfirmation {
                    viewModel.onEvent(event: PullRequestDetailEventDismissCloseConfirmation.shared)
                }
            }
        )
    }

    private var mergeDialogBinding: Binding<Bool> {
        Binding(
            get: { holder.state.pendingMergeConfirmation },
            set: { isPresented in
                if !isPresented && holder.state.pendingMergeConfirmation {
                    viewModel.onEvent(event: PullRequestDetailEventDismissMergeConfirmation.shared)
                }
            }
        )
    }

    @ViewBuilder
    private var contentView: some View {
        let state = holder.state
        if state.isLoading {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let pr = state.pullRequest {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        headerSection(pr: pr, state: state)
                        descriptionSection(pr: pr)
                        diffPreviewSection(pr: pr)
                        commentsSection(pr: pr, state: state)
                    }
                    .padding(.bottom, 16)
                }

                if pr.status == .open {
                    commentComposeBox(state: state)
                }

                if state.canMerge || state.canClose {
                    actionBar(canMerge: state.canMerge, canClose: state.canClose)
                }
            }
        } else {
            ContentUnavailableView(
                LocalizedStringKey("state_pr_not_found"),
                systemImage: "questionmark.circle"
            )
        }
    }

    @ViewBuilder
    private func headerSection(pr: PullRequest, state: PullRequestDetailState) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: pr.title)
                    .font(.title3)
                    .accessibilityIdentifier("prTitleLabel")
                Spacer()
                statusChip(pr.status)
            }
            Text(authorLine(pr: pr, state: state))
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(verbatim: formattedTimestamp(pr.createdAt.epochSeconds))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
    }

    @ViewBuilder
    private func descriptionSection(pr: PullRequest) -> some View {
        if let description = pr.description_, !description.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(LocalizedStringKey("label_pr_description"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(verbatim: description)
                    .font(.body)
                    .accessibilityIdentifier("prDescriptionLabel")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Color(.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16)
        }
    }

    @ViewBuilder
    private func diffPreviewSection(pr: PullRequest) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(LocalizedStringKey("label_pr_diff_preview"))
                .font(.caption)
                .foregroundStyle(.secondary)
            Button {
                path.append(
                    Route.chartDiff(
                        baseRevisionId: pr.commonAncestorRevisionId,
                        targetRevisionId: pr.sourceTipRevisionId
                    )
                )
            } label: {
                Text(LocalizedStringKey("label_pr_diff_preview"))
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("openDiffButton")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("prDiffPreview")
    }

    @ViewBuilder
    private func commentsSection(pr: PullRequest, state: PullRequestDetailState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(LocalizedStringKey("label_pr_comments"))
                .font(.headline)
                .padding(.horizontal, 16)
            ForEach(state.comments, id: \.id) { comment in
                commentRow(comment: comment, state: state)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("prCommentsList")
    }

    @ViewBuilder
    private func commentRow(comment: PullRequestComment, state: PullRequestDetailState) -> some View {
        let authorName = comment.authorId.flatMap { state.users[$0] }?.displayName
            ?? NSLocalizedString("label_someone", comment: "")
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(verbatim: authorName)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                Spacer()
                Text(verbatim: formattedTimestamp(comment.createdAt.epochSeconds))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(verbatim: comment.body)
                .font(.body)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16)
        .accessibilityIdentifier("commentRow_\(comment.id)")
    }

    @ViewBuilder
    private func commentComposeBox(state: PullRequestDetailState) -> some View {
        HStack(alignment: .center, spacing: 8) {
            TextField(
                LocalizedStringKey("hint_add_comment_to_pr"),
                text: Binding(
                    get: { state.commentDraft },
                    set: { newValue in
                        viewModel.onEvent(
                            event: PullRequestDetailEventCommentDraftChanged(draft: newValue)
                        )
                    }
                ),
                axis: .vertical
            )
            .lineLimit(1...4)
            .textFieldStyle(.roundedBorder)
            .disabled(state.isSendingComment)
            .accessibilityIdentifier("commentInputField")

            Button(LocalizedStringKey("action_post_comment")) {
                viewModel.onEvent(event: PullRequestDetailEventPostComment.shared)
            }
            .buttonStyle(.borderedProminent)
            .disabled(state.isSendingComment || state.commentDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .accessibilityIdentifier("postCommentButton")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.thinMaterial)
    }

    @ViewBuilder
    private func actionBar(canMerge: Bool, canClose: Bool) -> some View {
        HStack(spacing: 8) {
            Spacer()
            if canClose {
                Button(LocalizedStringKey("action_close_pr"), role: .destructive) {
                    viewModel.onEvent(event: PullRequestDetailEventRequestClose.shared)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("closeButton")
            }
            if canMerge {
                Button(LocalizedStringKey("action_merge_pr")) {
                    viewModel.onEvent(event: PullRequestDetailEventRequestMerge.shared)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("mergeButton")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.thinMaterial)
    }

    private func authorLine(pr: PullRequest, state: PullRequestDetailState) -> String {
        let resolved = pr.authorId.flatMap { state.users[$0] }?.displayName
            ?? NSLocalizedString("label_someone", comment: "")
        return String(format: NSLocalizedString("label_pr_authored_by", comment: ""), resolved)
    }

    private func formattedTimestamp(_ epochSeconds: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochSeconds))
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    @ViewBuilder
    private func statusChip(_ status: PullRequestStatus) -> some View {
        Text(LocalizedStringKey(statusKey(status)))
            .font(.caption2)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Capsule().fill(Color.gray.opacity(0.15)))
            .accessibilityIdentifier("prStatusChip")
    }

    private func statusKey(_ status: PullRequestStatus) -> String {
        switch status {
        case .open: return "label_pr_status_open"
        case .merged: return "label_pr_status_merged"
        case .closed: return "label_pr_status_closed"
        // `default` forced by Kotlin enum→ObjC bridging — same idiom as
        // PullRequestListScreen.swift.
        default: return status.name
        }
    }
}
