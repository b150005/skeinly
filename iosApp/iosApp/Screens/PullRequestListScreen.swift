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
    @State private var showError = false

    private var viewModel: PullRequestListViewModel { holder.viewModel }

    init(defaultFilter: PullRequestFilter, path: Binding<NavigationPath>) {
        self.defaultFilter = defaultFilter
        self._path = path
        let vm = ViewModelFactory.pullRequestListViewModel(defaultFilter: defaultFilter)
        let wrapper = KoinHelperKt.wrapPullRequestListState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        contentView
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("pullRequestListScreen")
            .navigationTitle(LocalizedStringKey("title_pull_requests"))
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
                    LocalizedStringKey("state_no_pull_requests"),
                    systemImage: "tray",
                    description: Text(LocalizedStringKey("state_no_pull_requests_body"))
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
                title: LocalizedStringKey("label_filter_incoming"),
                identifier: "incomingFilterChip",
                isSelected: current == .incoming,
                onTap: {
                    viewModel.onEvent(
                        event: PullRequestListEventSelectFilter(filter: .incoming)
                    )
                }
            )
            FilterChipButton(
                title: LocalizedStringKey("label_filter_outgoing"),
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
            Text(title)
                .font(.subheadline)
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
        return String(format: NSLocalizedString("label_pr_authored_by", comment: ""), resolved)
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
        case .open: return "label_pr_status_open"
        case .merged: return "label_pr_status_merged"
        case .closed: return "label_pr_status_closed"
        // `default` forced by Kotlin enum→ObjC bridging. Returns the raw
        // case name (locale-neutral, non-empty) so a future PullRequestStatus
        // addition without a matching Swift switch update surfaces visibly
        // at runtime rather than silently mislabeled as "Open" — same idiom
        // as Phase 33.1.13 SharedContentScreen.swift Difficulty default.
        default: return status.name
        }
    }
}
