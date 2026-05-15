import Shared
import SwiftUI

/// Phase 39 (ADR-021 §D4) — SwiftUI mirror of the Compose
/// `BlockedUsersScreen`. Settings → Privacy → Blocked Users. Lists the
/// caller's blocked users (display name resolved server-side; a
/// deleted-account block falls back to the shared "Unknown user"
/// label) with a per-row Unblock action. Auto-loads on VM `init`.
struct BlockedUsersListView: View {
    @StateObject private var holder: ScopedViewModel<BlockedUsersViewModel, BlockedUsersState>

    private var viewModel: BlockedUsersViewModel { holder.viewModel }

    init() {
        let vm = ViewModelFactory.blockedUsersViewModel()
        let wrapper = KoinHelperKt.wrapBlockedUsersState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state
        Group {
            if state.isLoading, state.users.isEmpty {
                ProgressView()
                    .accessibilityIdentifier("blockedUsersLoading")
            } else if state.isEmpty {
                ContentUnavailableView(
                    "body_blocked_users_empty",
                    systemImage: "person.slash"
                )
                .accessibilityIdentifier("blockedUsersEmpty")
            } else {
                List {
                    ForEach(state.users, id: \.userId) { user in
                        HStack {
                            Text(displayName(for: user))
                            Spacer()
                            if state.isUnblocking(userId: user.userId) {
                                ProgressView()
                            } else {
                                Button("action_unblock") {
                                    viewModel.onEvent(
                                        event: BlockedUsersEventUnblock(userId: user.userId)
                                    )
                                }
                                .accessibilityIdentifier("unblockButton_\(user.userId)")
                            }
                        }
                        .accessibilityIdentifier("blockedUserRow_\(user.userId)")
                    }
                }
            }
        }
        .navigationTitle(LocalizedStringKey("title_blocked_users"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("blockedUsersScreen")
        .alert(
            LocalizedStringKey("title_error"),
            isPresented: errorPresented(state: state),
            actions: {
                Button("action_ok") {
                    viewModel.onEvent(event: BlockedUsersEventClearError.shared)
                }
            },
            message: {
                Text(state.error?.localizedString ?? "")
            }
        )
    }

    private func displayName(for user: BlockedUser) -> String {
        user.displayName.isEmpty
            ? NSLocalizedString("state_blocked_user_unknown", comment: "")
            : user.displayName
    }

    private func errorPresented(state: BlockedUsersState) -> Binding<Bool> {
        Binding(
            get: { state.error != nil },
            set: { presenting in
                if !presenting {
                    viewModel.onEvent(event: BlockedUsersEventClearError.shared)
                }
            }
        )
    }
}
