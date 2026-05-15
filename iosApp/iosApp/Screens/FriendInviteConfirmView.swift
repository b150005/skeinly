import SwiftUI
import Shared

/// Phase 25.4 (ADR-024 §Phase 25.4) — SwiftUI mirror of the Compose
/// `FriendInviteConfirmScreen`. One screen, two modes (selected by the
/// `token` nullability, threaded into `FriendInviteConfirmViewModel`
/// via Koin parametric injection):
///
/// - **Token mode** (`token != nil`): reached via a Universal Link tap
///   (AppRouter `parseExternalRoute` → `.friendInviteConfirm(token:)`).
///   The VM auto-redeems on init; this view shows only a spinner →
///   success / error. No code-entry form.
/// - **Code mode** (`token == nil`): reached from Connections →
///   "Add by code". Renders a code `TextField` + submit button gated
///   on `state.submitEnabled`.
///
/// On success both modes converge to the same confirmation copy with a
/// single "Done" CTA that pops back to Connections (Phase 25.4
/// agent-team decision — the ADR's "View profile" CTA was scope-cut
/// because no arbitrary-user profile-view screen exists; deferred to
/// post-beta backlog).
struct FriendInviteConfirmView: View {
    let token: String?
    let onDone: () -> Void
    let onBack: () -> Void
    @StateObject private var holder: ScopedViewModel<FriendInviteConfirmViewModel, FriendInviteConfirmState>

    private var viewModel: FriendInviteConfirmViewModel { holder.viewModel }

    init(
        token: String?,
        onDone: @escaping () -> Void,
        onBack: @escaping () -> Void
    ) {
        self.token = token
        self.onDone = onDone
        self.onBack = onBack
        let vm = ViewModelFactory.friendInviteConfirmViewModel(token: token)
        let wrapper = KoinHelperKt.wrapFriendInviteConfirmState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state

        ZStack {
            if let success = state.success {
                successContent(success: success)
            } else if state.mode == FriendInviteConfirmMode.token {
                // Token mode auto-redeems on init. Spinner while in
                // flight; explicit retry surface if it FAILED so a
                // transient network failure on a deep-link tap isn't a
                // dead end (mirrors the Compose TokenFailedContent —
                // Phase 25.4 code-review HIGH fix).
                if state.isRedeeming {
                    ProgressView()
                        .accessibilityIdentifier("friendInviteTokenSpinner")
                } else {
                    tokenFailedContent()
                }
            } else {
                codeEntryContent(state: state)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
        .navigationTitle(LocalizedStringKey("title_friend_invite_confirm"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("friendInviteConfirmScreen")
        .alert(
            LocalizedStringKey("title_error"),
            isPresented: errorPresented(state: state),
            actions: {
                Button {
                    viewModel.onEvent(event: FriendInviteConfirmEventClearError.shared)
                } label: {
                    Text(LocalizedStringKey("action_ok"))
                }
            },
            message: {
                Text(LocalizedStringKey("error_generic"))
            }
        )
    }

    @ViewBuilder
    private func codeEntryContent(state: FriendInviteConfirmState) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(LocalizedStringKey("body_friend_invite_code_helper"))
                .font(.body)
                .foregroundStyle(.secondary)

            TextField(
                LocalizedStringKey("label_friend_invite_code_field"),
                text: codeBinding(state: state)
            )
            .textFieldStyle(.roundedBorder)
            .textInputAutocapitalization(.characters)
            .autocorrectionDisabled(true)
            .disabled(state.isRedeeming)
            .accessibilityIdentifier("friendInviteCodeField")

            Button {
                viewModel.onEvent(event: FriendInviteConfirmEventRedeem.shared)
            } label: {
                HStack {
                    Spacer()
                    if state.isRedeeming {
                        ProgressView()
                    } else {
                        Text(LocalizedStringKey("action_friend_invite_redeem"))
                    }
                    Spacer()
                }
                .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!state.submitEnabled)
            .accessibilityIdentifier("friendInviteRedeemButton")

            Spacer()
        }
    }

    @ViewBuilder
    private func tokenFailedContent() -> some View {
        VStack(spacing: 24) {
            Text(LocalizedStringKey("state_friend_invite_token_failed"))
                .font(.body)
                .multilineTextAlignment(.center)
                .accessibilityIdentifier("friendInviteTokenFailedText")

            Button {
                viewModel.onEvent(event: FriendInviteConfirmEventRedeem.shared)
            } label: {
                HStack {
                    Spacer()
                    Text(LocalizedStringKey("action_retry"))
                    Spacer()
                }
                .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("friendInviteRetryButton")
        }
    }

    @ViewBuilder
    private func successContent(success: FriendInviteSuccess) -> some View {
        let name = success.inviterDisplayName
            ?? NSLocalizedString("state_connections_unknown_user", comment: "")
        VStack(spacing: 24) {
            Text(
                String(
                    format: NSLocalizedString("state_friend_invite_success", comment: ""),
                    name
                )
            )
            .font(.title2)
            .multilineTextAlignment(.center)
            .accessibilityIdentifier("friendInviteSuccessText")

            Button {
                onDone()
            } label: {
                HStack {
                    Spacer()
                    Text(LocalizedStringKey("action_friend_invite_done"))
                    Spacer()
                }
                .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("friendInviteDoneButton")
        }
    }

    private func codeBinding(state: FriendInviteConfirmState) -> Binding<String> {
        Binding(
            get: { state.codeInput },
            set: { newValue in
                viewModel.onEvent(
                    event: FriendInviteConfirmEventUpdateCode(value: newValue)
                )
            }
        )
    }

    private func errorPresented(state: FriendInviteConfirmState) -> Binding<Bool> {
        Binding(
            get: { state.error != nil },
            set: { presenting in
                if !presenting && state.error != nil {
                    viewModel.onEvent(event: FriendInviteConfirmEventClearError.shared)
                }
            }
        )
    }
}
