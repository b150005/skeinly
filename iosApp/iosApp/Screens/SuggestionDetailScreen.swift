import SwiftUI
import Shared

/// SwiftUI mirror of the shared Compose `SuggestionDetailScreen` (Phase 38.3,
/// ADR-014 §6 §8). Owns a Koin-resolved `SuggestionDetailViewModel` via
/// `ScopedViewModel` so the observed state survives parent re-inits.
///
/// **Inline diff preview deferred** — same scope cut as the Compose mirror:
/// the `prDiffPreview` section ships as a "View proposed changes" link that
/// routes to the existing Phase 37.3 ChartComparisonScreen between the snapshot
/// `commonAncestorRevisionId` and `sourceTipRevisionId`. Extracting the diff
/// canvas into a thumbnail-sized component is a meaningful refactor,
/// deferred to a follow-up slice.
struct SuggestionDetailScreen: View {
    let prId: String
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<SuggestionDetailViewModel, SuggestionDetailState>
    /// Phase 24.2c-3 (ADR-017 §3.6) — drives the in-app pre-permission
    /// explainer alert dispatched on first PR detail open + first comment
    /// post on this PR.
    @StateObject private var notificationHolder: ScopedViewModel<
        NotificationPermissionViewModel, NotificationPermissionState
    >
    @State private var showError = false
    @State private var navEventsCloseable: Closeable?
    @State private var pendingClosedToast: Bool = false
    @State private var pendingMergedToast: Bool = false

    private var viewModel: SuggestionDetailViewModel { holder.viewModel }
    private var notificationViewModel: NotificationPermissionViewModel { notificationHolder.viewModel }

    /// Phase 24.2c-3 — bound to the alert presentation; same shape as the
    /// Settings → Notifications binding.
    private var notificationExplainerBinding: Binding<Bool> {
        Binding(
            get: { notificationHolder.state.isExplainerVisible },
            set: { newValue in
                if !newValue && notificationHolder.state.isExplainerVisible {
                    notificationViewModel.onEvent(
                        event: NotificationPermissionEventUserDismissedExplainer.shared
                    )
                }
            }
        )
    }

    init(prId: String, path: Binding<NavigationPath>) {
        self.prId = prId
        self._path = path
        let vm = ViewModelFactory.suggestionDetailViewModel(prId: prId)
        let wrapper = KoinHelperKt.wrapSuggestionDetailState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
        let nvm = KoinHelperKt.getNotificationPermissionViewModel()
        let nWrapper = KoinHelperKt.wrapNotificationPermissionState(flow: nvm.state)
        _notificationHolder = StateObject(
            wrappedValue: ScopedViewModel(viewModel: nvm, wrapper: nWrapper)
        )
    }

    var body: some View {
        contentView
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("pullRequestDetailScreen")
            .navigationTitle(LocalizedStringKey("title_suggestion_detail"))
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: holder.state.error != nil) { _, hasError in
            showError = hasError
        }
            .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
                Button("action_ok") {
                    viewModel.onEvent(event: SuggestionDetailEventClearError.shared)
                }
            } message: {
                Text(holder.state.error?.localizedString ?? "")
            }
            .overlay(alignment: .bottom) {
                if pendingClosedToast {
                    Text(LocalizedStringKey("message_suggestion_closed_successfully"))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.regularMaterial)
                        .clipShape(Capsule())
                        .padding(.bottom, 32)
                        .transition(.opacity)
                } else if pendingMergedToast {
                    Text(LocalizedStringKey("message_suggestion_applied_successfully"))
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
                let wrapper = KoinHelperKt.wrapSuggestionDetailNavEvents(flow: viewModel.navEvents)
                navEventsCloseable = wrapper.collect { event in
                    Task { @MainActor in
                        switch event {
                        case is SuggestionDetailNavEventPrClosed:
                            withAnimation { pendingClosedToast = true }
                            announceToVoiceOver(messageKey: "message_suggestion_closed_successfully")
                            try? await Task.sleep(nanoseconds: 2_000_000_000)
                            withAnimation { pendingClosedToast = false }
                        case is SuggestionDetailNavEventPrMerged:
                            withAnimation { pendingMergedToast = true }
                            announceToVoiceOver(messageKey: "message_suggestion_applied_successfully")
                            try? await Task.sleep(nanoseconds: 2_000_000_000)
                            withAnimation { pendingMergedToast = false }
                        case let nav as SuggestionDetailNavEventNavigateToConflictResolution:
                            path.append(Route.chartConflictResolution(prId: nav.prId))
                        case is SuggestionDetailNavEventCommentPosted:
                            // Phase 24.2c-3 — dispatch the third
                            // collaboration-moment trigger.
                            notificationViewModel.onEvent(
                                event: NotificationPermissionEventTriggerEncountered(
                                    trigger: .prCommentPosted
                                )
                            )
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
                LocalizedStringKey("dialog_close_suggestion_title"),
                isPresented: closeDialogBinding,
                titleVisibility: .visible
            ) {
                Button(LocalizedStringKey("action_close_suggestion"), role: .destructive) {
                    viewModel.onEvent(event: SuggestionDetailEventConfirmClose.shared)
                }
                Button(LocalizedStringKey("action_cancel"), role: .cancel) {
                    viewModel.onEvent(event: SuggestionDetailEventDismissCloseConfirmation.shared)
                }
            } message: {
                Text(LocalizedStringKey("dialog_close_suggestion_body"))
            }
            .confirmationDialog(
                LocalizedStringKey("dialog_apply_suggestion_title"),
                isPresented: mergeDialogBinding,
                titleVisibility: .visible
            ) {
                // Phase 38.4: merge confirm dispatches ConfirmMerge → 3-way
                // conflict detection → either direct RPC merge (auto-clean)
                // or push ChartConflictResolutionScreen via the navEvent
                // collector above.
                Button(LocalizedStringKey("action_apply_suggestion")) {
                    viewModel.onEvent(event: SuggestionDetailEventConfirmMerge.shared)
                }
                .disabled(holder.state.isMerging)
                Button(LocalizedStringKey("action_cancel"), role: .cancel) {
                    viewModel.onEvent(event: SuggestionDetailEventDismissMergeConfirmation.shared)
                }
            } message: {
                Text(LocalizedStringKey("dialog_apply_suggestion_body"))
            }
            // Phase 24.2c-3 (ADR-017 §3.6) — second collaboration-moment
            // trigger: first time PR detail finishes loading. Keyed on the
            // null→non-null transition so the dispatch fires after
            // loadInitial resolves (not during the loading-spinner phase).
            .onChange(of: holder.state.suggestion != nil) { _, hasPr in
                if hasPr {
                    notificationViewModel.onEvent(
                        event: NotificationPermissionEventTriggerEncountered(
                            trigger: .prDetailOpened
                        )
                    )
                }
            }
            // Phase 24.2c-3 — pre-permission explainer alert. Same shape
            // as the Settings → Notifications binding.
            .alert(
                LocalizedStringKey("title_notifications_explainer"),
                isPresented: notificationExplainerBinding
            ) {
                Button("action_enable_notifications") {
                    notificationViewModel.onEvent(
                        event: NotificationPermissionEventUserAcceptedExplainer.shared
                    )
                }
                .disabled(notificationHolder.state.isRequestingPermission)
                Button("action_not_now_notifications", role: .cancel) {
                    notificationViewModel.onEvent(
                        event: NotificationPermissionEventUserDismissedExplainer.shared
                    )
                }
                .disabled(notificationHolder.state.isRequestingPermission)
            } message: {
                Text("body_notifications_explainer")
            }
    }

    private var closeDialogBinding: Binding<Bool> {
        Binding(
            get: { holder.state.pendingCloseConfirmation },
            set: { isPresented in
                if !isPresented && holder.state.pendingCloseConfirmation {
                    viewModel.onEvent(event: SuggestionDetailEventDismissCloseConfirmation.shared)
                }
            }
        )
    }

    private var mergeDialogBinding: Binding<Bool> {
        Binding(
            get: { holder.state.pendingMergeConfirmation },
            set: { isPresented in
                if !isPresented && holder.state.pendingMergeConfirmation {
                    viewModel.onEvent(event: SuggestionDetailEventDismissMergeConfirmation.shared)
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
        } else if let pr = state.suggestion {
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
                LocalizedStringKey("state_suggestion_not_found"),
                systemImage: "questionmark.circle"
            )
        }
    }

    @ViewBuilder
    private func headerSection(pr: Suggestion, state: SuggestionDetailState) -> some View {
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
    private func descriptionSection(pr: Suggestion) -> some View {
        if let description = pr.description_, !description.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(LocalizedStringKey("label_suggestion_description"))
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
    private func diffPreviewSection(pr: Suggestion) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(LocalizedStringKey("label_suggestion_changes_preview"))
                .font(.caption)
                .foregroundStyle(.secondary)
            Button {
                path.append(
                    Route.chartComparison(
                        baseRevisionId: pr.commonAncestorRevisionId,
                        targetRevisionId: pr.sourceTipRevisionId
                    )
                )
            } label: {
                Text(LocalizedStringKey("label_suggestion_changes_preview"))
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
    private func commentsSection(pr: Suggestion, state: SuggestionDetailState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(LocalizedStringKey("label_suggestion_comments"))
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
    private func commentRow(comment: SuggestionComment, state: SuggestionDetailState) -> some View {
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
    private func commentComposeBox(state: SuggestionDetailState) -> some View {
        HStack(alignment: .center, spacing: 8) {
            TextField(
                LocalizedStringKey("hint_add_comment_to_suggestion"),
                text: Binding(
                    get: { state.commentDraft },
                    set: { newValue in
                        viewModel.onEvent(
                            event: SuggestionDetailEventCommentDraftChanged(draft: newValue)
                        )
                    }
                ),
                axis: .vertical
            )
            .lineLimit(1...4)
            .textFieldStyle(.roundedBorder)
            .disabled(state.isSendingComment)
            .accessibilityLabel(LocalizedStringKey("hint_add_comment_to_suggestion"))
            .accessibilityIdentifier("commentInputField")

            Button(LocalizedStringKey("action_post_comment")) {
                viewModel.onEvent(event: SuggestionDetailEventPostComment.shared)
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
                Button(LocalizedStringKey("action_close_suggestion"), role: .destructive) {
                    viewModel.onEvent(event: SuggestionDetailEventRequestClose.shared)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("closeButton")
            }
            if canMerge {
                Button(LocalizedStringKey("action_apply_suggestion")) {
                    viewModel.onEvent(event: SuggestionDetailEventRequestMerge.shared)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("mergeButton")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.thinMaterial)
    }

    private func authorLine(pr: Suggestion, state: SuggestionDetailState) -> String {
        let resolved = pr.authorId.flatMap { state.users[$0] }?.displayName
            ?? NSLocalizedString("label_someone", comment: "")
        return String(format: NSLocalizedString("label_suggestion_authored_by", comment: ""), resolved)
    }

    private func formattedTimestamp(_ epochSeconds: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochSeconds))
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    @ViewBuilder
    private func statusChip(_ status: SuggestionStatus) -> some View {
        Text(LocalizedStringKey(statusKey(status)))
            .font(.caption2)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Capsule().fill(Color.gray.opacity(0.15)))
            .accessibilityIdentifier("prStatusChip")
    }

    private func statusKey(_ status: SuggestionStatus) -> String {
        switch status {
        case .open: return "label_suggestion_status_open"
        case .applied: return "label_suggestion_status_applied"
        case .closed: return "label_suggestion_status_closed"
        // `default` forced by Kotlin enum→ObjC bridging — same idiom as
        // SuggestionListScreen.swift.
        default: return status.name
        }
    }
}
