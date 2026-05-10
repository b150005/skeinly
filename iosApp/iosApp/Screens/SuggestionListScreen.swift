import SwiftUI
import Shared

/// SwiftUI mirror of the shared Compose `PullRequestListScreen` (Phase 38.2,
/// ADR-014 §6 §8). Owns a Koin-resolved `PullRequestListViewModel` via
/// `ScopedViewModel` so the observed state survives parent re-inits.
///
/// Read-only — tap on a row is a no-op for 38.2; Phase 38.3 routes to
/// `PullRequestDetailScreen`.
struct PullRequestListScreen: View {
    let defaultFilter: PullRequestFilter
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<PullRequestListViewModel, PullRequestListState>
    /// Phase 24.2c-3 (ADR-017 §3.6) — drives the in-app pre-permission
    /// explainer alert when the Incoming filter has at least one PR.
    @StateObject private var notificationHolder: ScopedViewModel<
        NotificationPermissionViewModel, NotificationPermissionState
    >
    @State private var showError = false

    private var viewModel: PullRequestListViewModel { holder.viewModel }
    private var notificationViewModel: NotificationPermissionViewModel { notificationHolder.viewModel }

    /// Phase 24.2c-3 — bound to the alert presentation. Mirrors the
    /// Settings → Notifications binding exactly: dismiss-without-button
    /// route emits `UserDismissedExplainer` (defensive — alert button
    /// closures fire synchronously before isPresented flips, so this path
    /// is only reachable on iOS swipe-to-dismiss style interactions).
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

    init(defaultFilter: PullRequestFilter, path: Binding<NavigationPath>) {
        self.defaultFilter = defaultFilter
        self._path = path
        let vm = ViewModelFactory.pullRequestListViewModel(defaultFilter: defaultFilter)
        let wrapper = KoinHelperKt.wrapPullRequestListState(flow: vm.state)
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
            .accessibilityIdentifier("pullRequestListScreen")
            .navigationTitle(LocalizedStringKey("title_suggestions"))
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: holder.state.error != nil) { _, hasError in
            showError = hasError
        }
            .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
                Button("action_ok") {
                    viewModel.onEvent(event: PullRequestListEventClearError.shared)
                }
            } message: {
                Text(holder.state.error?.localizedString ?? "")
            }
            // Phase 24.2c-3 (ADR-017 §3.6) — Incoming + non-empty PRs is
            // the canonical first-collaboration-moment trigger. The
            // shouldDispatch derivation is observed via `.onChange` so
            // SelectFilter / data refresh both refire the dispatch (the
            // prompter's global "asked" bit makes it idempotent).
            .onChange(of: shouldDispatchIncomingTrigger) { _, dispatch in
                if dispatch {
                    notificationViewModel.onEvent(
                        event: NotificationPermissionEventTriggerEncountered(
                            trigger: .prListIncomingWithPrs
                        )
                    )
                }
            }
            .task {
                if shouldDispatchIncomingTrigger {
                    notificationViewModel.onEvent(
                        event: NotificationPermissionEventTriggerEncountered(
                            trigger: .prListIncomingWithPrs
                        )
                    )
                }
            }
            // Phase 24.2c-3 — pre-permission explainer alert. Same shape
            // as Settings → Notifications: native OS prompt fires only
            // after the user taps Enable; Not now records a global dismiss
            // so the alert never re-surfaces.
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

    /// Phase 24.2c-3 — derived flag: Incoming filter AND list is non-empty.
    /// Computed inline so SwiftUI's diff layer compares a Bool, not the
    /// full state object, when deciding whether to refire the trigger.
    private var shouldDispatchIncomingTrigger: Bool {
        holder.state.filter == .incoming && !holder.state.pullRequests.isEmpty
    }

    @ViewBuilder
    private var contentView: some View {
        let state = holder.state
        VStack(spacing: 0) {
            filterChipRow(current: state.filter)

            if state.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if state.pullRequests.isEmpty {
                ContentUnavailableView(
                    LocalizedStringKey("state_no_suggestions"),
                    systemImage: "tray",
                    description: Text(LocalizedStringKey("state_no_suggestions_body"))
                )
            } else {
                // Group by status — OPEN first, then MERGED, then CLOSED.
                // Within each group the repository's `created_at DESC`
                // ordering carries through the grouping pass.
                let ordered = orderedByStatus(state.pullRequests)
                List(ordered, id: \.id) { pr in
                    PullRequestRow(
                        pullRequest: pr,
                        authorName: pr.authorId.flatMap { state.users[$0] }?.displayName
                    )
                    .contentShape(Rectangle())
                    .onTapGesture {
                        path.append(Route.pullRequestDetail(prId: pr.id))
                    }
                }
                .listStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private func filterChipRow(current: PullRequestFilter) -> some View {
        HStack(spacing: 8) {
            FilterChipButton(
                title: LocalizedStringKey("label_filter_received"),
                identifier: "incomingFilterChip",
                isSelected: current == .incoming,
                onTap: {
                    viewModel.onEvent(
                        event: PullRequestListEventSelectFilter(filter: .incoming)
                    )
                }
            )
            FilterChipButton(
                title: LocalizedStringKey("label_filter_sent"),
                identifier: "outgoingFilterChip",
                isSelected: current == .outgoing,
                onTap: {
                    viewModel.onEvent(
                        event: PullRequestListEventSelectFilter(filter: .outgoing)
                    )
                }
            )
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func orderedByStatus(_ prs: [PullRequest]) -> [PullRequest] {
        let order: [PullRequestStatus] = [.open, .merged, .closed]
        let grouped = Dictionary(grouping: prs, by: { $0.status })
        return order.flatMap { grouped[$0] ?? [] }
    }
}

private struct FilterChipButton: View {
    let title: LocalizedStringKey
    let identifier: String
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 4) {
                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.caption)
                }
                Text(title)
                    .font(.subheadline)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(
                Capsule()
                    .fill(isSelected ? Color.accentColor.opacity(0.2) : Color.clear)
            )
            .overlay(
                Capsule().stroke(isSelected ? Color.accentColor : Color.gray.opacity(0.4))
            )
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
    }
}

private struct PullRequestRow: View {
    let pullRequest: PullRequest
    let authorName: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: pullRequest.title)
                    .font(.body)
                    .accessibilityIdentifier("prTitleLabel_\(pullRequest.id)")
                Spacer()
                PullRequestStatusBadge(status: pullRequest.status, prId: pullRequest.id)
            }
            Text(authorLine)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(formattedDate)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("prRow_\(pullRequest.id)")
    }

    private var authorLine: String {
        // Falls back to label_someone when:
        //  - the user-record lookup missed (cold-launch / account deleted), OR
        //  - the PR row's authorId is itself null.
        let resolved = authorName ?? NSLocalizedString("label_someone", comment: "")
        return String(format: NSLocalizedString("label_suggestion_authored_by", comment: ""), resolved)
    }

    private var formattedDate: String {
        let date = Date(timeIntervalSince1970: TimeInterval(pullRequest.createdAt.epochSeconds))
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}

private struct PullRequestStatusBadge: View {
    let status: PullRequestStatus
    let prId: String

    var body: some View {
        Text(LocalizedStringKey(statusKey))
            .font(.caption2)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(
                Capsule().fill(Color.gray.opacity(0.15))
            )
            .accessibilityIdentifier("prStatusChip_\(prId)")
    }

    private var statusKey: String {
        switch status {
        case .open: return "label_suggestion_status_open"
        case .merged: return "label_suggestion_status_applied"
        case .closed: return "label_suggestion_status_closed"
        // `default` forced by Kotlin enum→ObjC bridging. Returns the raw
        // case name (locale-neutral, non-empty) so a future PullRequestStatus
        // addition without a matching Swift switch update surfaces visibly
        // at runtime rather than silently mislabeled as "Open" — same idiom
        // as Phase 33.1.13 SharedContentScreen.swift Difficulty default.
        default: return status.name
        }
    }
}
