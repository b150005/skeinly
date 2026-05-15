import Shared
import SwiftUI

/// Phase 39 (ADR-021 §D4) — SwiftUI mirror of the Compose
/// `BlockUserConfirmDialog`. A destructive confirmation presented as a
/// `.confirmationDialog`-style sheet. Mounts a fresh
/// `BlockUserViewModel` keyed on `blockedUserId`.
///
/// On success the VM emits `BlockUserNavEventBlocked`; forwarded as
/// `onBlocked` so the presenter dismisses + flashes "User blocked".
/// Failure stays inline (retryable without re-confirming).
struct BlockUserConfirmView: View {
    let blockedUserId: String
    let onBlocked: () -> Void
    let onDismiss: () -> Void
    /// Resolved display name; when non-nil the title reads
    /// "Block <name>?" (ADR-021 §D4), else the generic title.
    let blockedDisplayName: String?

    @StateObject private var holder: ScopedViewModel<BlockUserViewModel, BlockUserState>
    @State private var navCloseable: Closeable?

    private var viewModel: BlockUserViewModel { holder.viewModel }

    private var titleText: String {
        if let name = blockedDisplayName, !name.isEmpty {
            return String(
                format: NSLocalizedString("title_block_user_confirm_named", comment: ""),
                name
            )
        }
        return NSLocalizedString("title_block_user_confirm", comment: "")
    }

    init(
        blockedUserId: String,
        onBlocked: @escaping () -> Void,
        onDismiss: @escaping () -> Void,
        blockedDisplayName: String? = nil
    ) {
        self.blockedUserId = blockedUserId
        self.onBlocked = onBlocked
        self.onDismiss = onDismiss
        self.blockedDisplayName = blockedDisplayName
        let vm = ViewModelFactory.blockUserViewModel(blockedUserId: blockedUserId)
        let wrapper = KoinHelperKt.wrapBlockUserState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("body_block_user_confirm")
                    .font(.body)
                if let err = state.error {
                    Text(err.localizedString)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .accessibilityIdentifier("blockUserError")
                }
                Spacer()
            }
            .padding()
            .navigationTitle(titleText)
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("blockUserSheet")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action_cancel") { onDismiss() }
                        .disabled(state.isBlocking)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if state.isBlocking {
                        ProgressView()
                    } else {
                        Button("action_block_confirm", role: .destructive) {
                            viewModel.onEvent(event: BlockUserEventConfirm.shared)
                        }
                        .accessibilityIdentifier("blockUserConfirmButton")
                    }
                }
            }
            .task {
                navCloseable?.close()
                let flow = KoinHelperKt.wrapBlockUserNavEvents(flow: viewModel.navEvents)
                navCloseable = flow.collect { event in
                    Task { @MainActor in
                        if event is BlockUserNavEventBlocked {
                            onBlocked()
                        }
                    }
                }
            }
            .onDisappear {
                navCloseable?.close()
                navCloseable = nil
            }
        }
    }
}
