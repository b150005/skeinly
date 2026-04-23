import SwiftUI
import Shared

struct SharedWithMeScreen: View {
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<SharedWithMeViewModel, SharedWithMeState>
    @State private var showError = false

    init(path: Binding<NavigationPath>) {
        self._path = path
        let vm = ViewModelFactory.sharedWithMeViewModel()
        let wrapper = KoinHelperKt.wrapSharedWithMeState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state
        let viewModel = holder.viewModel

        // Root landmark — ZStack (not bare Group) so .accessibilityElement / .accessibilityIdentifier
        // attach to a concrete layout node rather than propagating to each state-branch child,
        // per the 33.1.6 HIGH rule.
        ZStack {
            if state.isLoading && state.shares.isEmpty {
                ProgressView()
            } else if state.shares.isEmpty {
                ContentUnavailableView(
                    LocalizedStringKey("state_no_shares"),
                    systemImage: "shared.with.you",
                    description: Text(LocalizedStringKey("state_no_shares_body"))
                )
            } else {
                List(state.shares, id: \.id) { share in
                    ShareRow(
                        share: share,
                        patternTitle: state.patternTitles[share.patternId]
                            ?? NSLocalizedString("label_unknown_pattern", comment: ""),
                        sharerName: state.sharers[share.fromUserId]?.displayName
                            ?? NSLocalizedString("label_someone", comment: ""),
                        onAccept: {
                            viewModel.onEvent(event: SharedWithMeEventAcceptShare(shareId: share.id))
                        },
                        onDecline: {
                            viewModel.onEvent(event: SharedWithMeEventDeclineShare(shareId: share.id))
                        }
                    )
                    .contentShape(Rectangle())
                    .onTapGesture {
                        path.append(Route.sharedContent(token: nil, shareId: share.id))
                    }
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sharedWithMeScreen")
        .navigationTitle(LocalizedStringKey("title_shared_with_me"))
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: state.error) { _, newError in
            showError = newError != nil
        }
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button(LocalizedStringKey("action_ok")) {
                viewModel.onEvent(event: SharedWithMeEventClearError.shared)
            }
        } message: {
            // `state.error` is a raw ViewModel message — ViewModel-error-message
            // localization is tracked in the Tech Debt Backlog.
            Text(verbatim: state.error ?? "")
        }
    }
}

private struct ShareRow: View {
    let share: Share
    let patternTitle: String
    let sharerName: String
    let onAccept: () -> Void
    let onDecline: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Pattern title is user-authored content (or the localized `label_unknown_pattern`
            // fallback, already resolved by the caller) — use verbatim so SwiftUI's
            // LocalizedStringKey overload doesn't key-resolve the user's text.
            Text(verbatim: patternTitle)
                .font(.headline)

            HStack {
                // "From {name}" and "Shared on {date}" match the Android renderer —
                // both platforms route through the same parametric keys so translators
                // see the full sentence context and the "From"/"Shared on" prefixes
                // aren't silently dropped on iOS.
                Text(verbatim: fromText)
                Text(verbatim: "\u{2022}")
                Text(verbatim: sharedOnText)
                Text(verbatim: "\u{2022}")
                Text(permissionLabelKey)
                if share.status == .pending {
                    Text(verbatim: "\u{2022}")
                    Text(LocalizedStringKey("label_share_status_pending"))
                        .foregroundStyle(.orange)
                } else if share.status == .declined {
                    Text(verbatim: "\u{2022}")
                    Text(LocalizedStringKey("label_share_status_declined"))
                        .foregroundStyle(.red)
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            if share.status == .pending {
                HStack(spacing: 12) {
                    Button(LocalizedStringKey("action_accept"), action: onAccept)
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                    Button(LocalizedStringKey("action_decline"), action: onDecline)
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                }
            }
        }
        .padding(.vertical, DesignTokens.listRowPaddingV)
    }

    private var permissionLabelKey: LocalizedStringKey {
        share.permission == .fork
            ? LocalizedStringKey("label_permission_fork")
            : LocalizedStringKey("label_permission_view")
    }

    private var fromText: String {
        String(format: NSLocalizedString("label_from_user", comment: ""), sharerName)
    }

    private var sharedOnText: String {
        String(format: NSLocalizedString("label_shared_on", comment: ""), formattedDate)
    }

    private var formattedDate: String {
        let date = Date(timeIntervalSince1970: TimeInterval(share.sharedAt.epochSeconds))
        return date.formatted(date: .abbreviated, time: .omitted)
    }
}
