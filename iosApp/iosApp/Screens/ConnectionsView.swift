import SwiftUI
import Foundation
import Shared

/// Phase 25.3 (ADR-024 §(e)) — SwiftUI mirror of the Compose
/// `ConnectionsScreen`. Hosts the 3-tab Friends / Pending / Invite
/// surface for managing the mutual-friendship graph + invite generation.
///
/// Tab discrimination uses a SwiftUI `Picker(.segmented)` rather than
/// `TabView` so the screen sits inside the existing `NavigationStack`
/// without nested-tab-bar artifacts. Tab content is heterogeneous
/// (List vs Form vs Card) so HorizontalPager is intentionally avoided
/// — same call as the Compose side which uses a `when (state.activeTab)`
/// switch instead of `HorizontalPager`.
///
/// Per-row spinners replace the trailing button while the action is
/// in flight (Accept / Reject / Disconnect / Cancel-outbound) so
/// concurrent taps on different rows each get their own affordance.
///
/// The disconnect confirmation surfaces via SwiftUI `.confirmationDialog`
/// (rather than `.alert`) so the destructive role styles correctly on
/// iOS 17+ and the dismissal is consistent with platform idioms.
///
/// **Phase 25.4 sequencing**: this view exposes existing invites
/// (code-only display) and the "Create invite" button. Universal-Link
/// redemption + system share-sheet land in Phase 25.4. Testers can
/// manually relay codes for end-to-end smoke testing.
struct ConnectionsView: View {
    @StateObject private var holder: ScopedViewModel<ConnectionsViewModel, ConnectionsState>
    @State private var pendingDisconnect: PendingDisconnect?

    private var viewModel: ConnectionsViewModel { holder.viewModel }

    init() {
        let vm = ViewModelFactory.connectionsViewModel()
        let wrapper = KoinHelperKt.wrapConnectionsState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state

        VStack(spacing: 0) {
            Picker("", selection: tabBinding(state: state)) {
                Text(LocalizedStringKey("tab_connections_friends"))
                    .tag(ConnectionsTab.friends)
                Text(LocalizedStringKey("tab_connections_pending"))
                    .tag(ConnectionsTab.pending)
                Text(LocalizedStringKey("tab_connections_invite"))
                    .tag(ConnectionsTab.invite)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .accessibilityIdentifier("connectionsTabPicker")

            ZStack {
                if state.isLoading {
                    ProgressView()
                        .accessibilityIdentifier("connectionsLoading")
                } else {
                    switch state.activeTab {
                    case ConnectionsTab.friends:
                        FriendsTabContent(state: state) { id, name in
                            pendingDisconnect = PendingDisconnect(otherUserId: id, displayName: name)
                        }
                    case ConnectionsTab.pending:
                        PendingTabContent(state: state) { event in
                            viewModel.onEvent(event: event)
                        }
                    case ConnectionsTab.invite:
                        InviteTabContent(state: state) {
                            viewModel.onEvent(event: ConnectionsEventCreateInvite.shared)
                        }
                    default:
                        // Defensive — Kotlin sealed-enum exhaustion does
                        // not compile-time enforce on Swift `switch` over
                        // a bridged enum; default keeps us safe against
                        // a future fourth tab being added on the Kotlin
                        // side without a matching Swift arm.
                        ProgressView()
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .navigationTitle(LocalizedStringKey("title_connections"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("connectionsScreen")
        .alert(
            LocalizedStringKey("title_error"),
            isPresented: errorPresented(state: state),
            actions: {
                Button {
                    viewModel.onEvent(event: ConnectionsEventClearError.shared)
                } label: {
                    Text(LocalizedStringKey("action_ok"))
                }
            },
            message: {
                Text(LocalizedStringKey("error_generic"))
            }
        )
        .confirmationDialog(
            LocalizedStringKey("dialog_connections_disconnect_title"),
            isPresented: disconnectPresented(),
            titleVisibility: .visible,
            presenting: pendingDisconnect
        ) { pending in
            Button(role: .destructive) {
                viewModel.onEvent(
                    event: ConnectionsEventDisconnect(otherUserId: pending.otherUserId)
                )
                pendingDisconnect = nil
            } label: {
                Text(LocalizedStringKey("action_connections_disconnect"))
            }
            Button(role: .cancel) {
                pendingDisconnect = nil
            } label: {
                Text(LocalizedStringKey("action_cancel"))
            }
        } message: { pending in
            Text(
                String(
                    format: NSLocalizedString(
                        "dialog_connections_disconnect_body",
                        comment: "Disconnect confirmation body"
                    ),
                    pending.displayName
                )
            )
        }
    }

    private func tabBinding(state: ConnectionsState) -> Binding<ConnectionsTab> {
        Binding(
            get: { state.activeTab },
            set: { newValue in
                viewModel.onEvent(event: ConnectionsEventSelectTab(tab: newValue))
            }
        )
    }

    private func errorPresented(state: ConnectionsState) -> Binding<Bool> {
        Binding(
            get: { state.error != nil },
            set: { presenting in
                if !presenting && state.error != nil {
                    viewModel.onEvent(event: ConnectionsEventClearError.shared)
                }
            }
        )
    }

    private func disconnectPresented() -> Binding<Bool> {
        Binding(
            get: { pendingDisconnect != nil },
            set: { presenting in
                if !presenting {
                    pendingDisconnect = nil
                }
            }
        )
    }
}

private struct PendingDisconnect: Identifiable {
    let otherUserId: String
    let displayName: String
    var id: String { otherUserId }
}

private struct FriendsTabContent: View {
    let state: ConnectionsState
    let onDisconnect: (String, String) -> Void

    var body: some View {
        if state.callerId == nil || state.friends.isEmpty {
            EmptyStateView(
                key: "state_connections_no_friends",
                identifier: "connectionsFriendsEmpty"
            )
        } else {
            List(state.friends, id: \.self.compositeKey) { connection in
                let otherUserId = connection.otherUserId(callerId: state.callerId ?? "")
                let displayName = displayNameOf(state: state, otherUserId: otherUserId)
                HStack {
                    Text(displayName)
                    Spacer()
                    if state.isActionInFlight(otherUserId: otherUserId) {
                        ProgressView()
                    } else {
                        Button {
                            onDisconnect(otherUserId, displayName)
                        } label: {
                            Text(LocalizedStringKey("action_connections_disconnect"))
                                .foregroundStyle(.red)
                        }
                        .accessibilityIdentifier("connectionsDisconnectButton_\(otherUserId)")
                    }
                }
                .accessibilityIdentifier("connectionsFriendRow_\(otherUserId)")
            }
            .accessibilityIdentifier("connectionsFriendsList")
        }
    }
}

private struct PendingTabContent: View {
    let state: ConnectionsState
    /// Single dispatcher closure so the parent's `viewModel.onEvent`
    /// captures aren't stored at construction time (mirrors the
    /// FriendsTabContent's onDisconnect closure shape).
    let dispatch: (ConnectionsEvent) -> Void

    var body: some View {
        if state.callerId == nil || state.pending.isEmpty {
            EmptyStateView(
                key: "state_connections_no_pending",
                identifier: "connectionsPendingEmpty"
            )
        } else {
            List(state.pending, id: \.self.compositeKey) { connection in
                let callerId = state.callerId ?? ""
                let otherUserId = connection.otherUserId(callerId: callerId)
                let displayName = displayNameOf(state: state, otherUserId: otherUserId)
                let inbound = state.isInbound(connection: connection)
                let labelKey = inbound
                    ? "state_connections_request_from"
                    : "state_connections_request_to"
                HStack {
                    Text(
                        String(
                            format: NSLocalizedString(labelKey, comment: ""),
                            displayName
                        )
                    )
                    Spacer()
                    if state.isActionInFlight(otherUserId: otherUserId) {
                        ProgressView()
                    } else if inbound {
                        Button {
                            dispatch(ConnectionsEventAcceptRequest(otherUserId: otherUserId))
                        } label: {
                            Text(LocalizedStringKey("action_connections_accept"))
                        }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("connectionsAcceptButton_\(otherUserId)")
                        Button {
                            dispatch(ConnectionsEventRejectRequest(otherUserId: otherUserId))
                        } label: {
                            Text(LocalizedStringKey("action_connections_reject"))
                                .foregroundStyle(.red)
                        }
                        .accessibilityIdentifier("connectionsRejectButton_\(otherUserId)")
                    } else {
                        Button {
                            dispatch(ConnectionsEventCancelOutboundRequest(otherUserId: otherUserId))
                        } label: {
                            Text(LocalizedStringKey("action_cancel"))
                        }
                        .accessibilityIdentifier("connectionsCancelOutboundButton_\(otherUserId)")
                    }
                }
                .accessibilityIdentifier("connectionsPendingRow_\(otherUserId)")
            }
            .accessibilityIdentifier("connectionsPendingList")
        }
    }
}

private struct InviteTabContent: View {
    let state: ConnectionsState
    let onCreateInvite: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(LocalizedStringKey("body_connections_invite_explanation"))
                .font(.body)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 16)
                .padding(.top, 16)

            Button {
                onCreateInvite()
            } label: {
                HStack {
                    Spacer()
                    if state.isCreatingInvite {
                        ProgressView()
                    } else {
                        Text(LocalizedStringKey("action_connections_create_invite"))
                    }
                    Spacer()
                }
                .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .disabled(state.isCreatingInvite)
            .padding(.horizontal, 16)
            .accessibilityIdentifier("connectionsCreateInviteButton")

            if state.invites.isEmpty {
                EmptyStateView(
                    key: "state_connections_no_invite",
                    identifier: "connectionsInviteEmpty"
                )
            } else {
                List(state.invites, id: \.self.id) { invite in
                    InviteRow(invite: invite)
                }
                .listStyle(.insetGrouped)
                .accessibilityIdentifier("connectionsInviteList")
            }

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct InviteRow: View {
    let invite: FriendInvite

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(invite.code)
                .font(.title2.monospaced())
                .accessibilityIdentifier("connectionsInviteCode_\(invite.id)")
            Text(
                String(
                    format: NSLocalizedString(
                        "state_connections_invite_expires_in_days",
                        comment: ""
                    ),
                    daysRemaining(invite: invite)
                )
            )
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .accessibilityIdentifier("connectionsInviteCard_\(invite.id)")
    }
}

private struct EmptyStateView: View {
    let key: String
    let identifier: String

    var body: some View {
        VStack {
            Spacer()
            Text(LocalizedStringKey(key))
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
                .accessibilityIdentifier(identifier)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}

/// Returns the cached display name OR the localized "Unknown user"
/// fallback. Keyed off the inline state map so a row that has no cached
/// resolution (cold start, deleted profile) renders predictably without
/// throwing.
private func displayNameOf(state: ConnectionsState, otherUserId: String) -> String {
    if let name = state.displayNames[otherUserId] {
        return name
    }
    return NSLocalizedString("state_connections_unknown_user", comment: "")
}

/// Days until the invite expires, floored to 0 for already-expired
/// invites (defensive — UI should still render those as "0 days"
/// rather than negative). Uses Foundation `Date` directly rather than
/// the Kotlin `Clock.System` bridge — keeps the Swift-side helper free
/// of Kotlin/Native surface and matches the timestamp comparison done
/// against the bridged `FriendInvite.expiresAt: Kotlinx_datetimeInstant`
/// (which exposes `epochSeconds`).
private func daysRemaining(invite: FriendInvite) -> Int {
    let nowSeconds = Int64(Date().timeIntervalSince1970)
    let remaining = invite.expiresAt.epochSeconds - nowSeconds
    return max(0, Int(remaining / 86_400))
}

/// Composite key for List `id:` keypath so accepted-state edges with
/// the same composite-PK pair don't collide across the friends/pending
/// tabs (they shouldn't anyway since edge state is unique per pair, but
/// defensive against a same-key SwiftUI diff fail).
extension FriendConnection {
    fileprivate var compositeKey: String {
        "\(userA)|\(userB)"
    }
}
