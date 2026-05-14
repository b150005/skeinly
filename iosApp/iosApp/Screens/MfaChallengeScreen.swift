import SwiftUI
import Shared

/// Phase 26.5 (ADR-022 §6.4) — SwiftUI mirror of the Compose
/// MfaChallengeScreen. Surfaced by AppRootView when
/// AuthState.MfaChallengeRequired is observed. Verify-success elevates
/// the session AAL → observeAuthState re-emits Authenticated → the
/// AppRootView body falls through to ProjectListScreen automatically.
struct MfaChallengeScreen: View {
    @StateObject private var holder: ScopedViewModel<MfaChallengeViewModel, MfaChallengeUiState>

    private var viewModel: MfaChallengeViewModel { holder.viewModel }

    init() {
        let vm = ViewModelFactory.mfaChallengeViewModel()
        let wrapper = KoinHelperKt.wrapMfaChallengeState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state
        let isRecoveryPhase = state.phase == MfaChallengePhase.enterrecoverycode

        ScrollView {
            VStack(spacing: 16) {
                Text(LocalizedStringKey("body_mfa_challenge_prompt"))
                    .multilineTextAlignment(.center)
                    .padding(.top, 32)

                TextField(
                    isRecoveryPhase
                        ? LocalizedStringKey("label_mfa_recovery_code_input")
                        : LocalizedStringKey(""),
                    text: Binding(
                        get: { state.codeInput },
                        set: { viewModel.onEvent(event: MfaChallengeEventUpdateCode(code: $0)) }
                    )
                )
                .textFieldStyle(.roundedBorder)
                .keyboardType(isRecoveryPhase ? .asciiCapable : .numberPad)
                .autocapitalization(.allCharacters)
                .disabled(state.isSubmitting || state.error is MfaChallengeErrorLocked)
                .accessibilityIdentifier("mfaChallengeCodeInput")

                if let errorMessage = errorText(state.error) {
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Button {
                    let event: MfaChallengeEvent = isRecoveryPhase
                        ? MfaChallengeEventSubmitRecoveryCode.shared
                        : MfaChallengeEventSubmitCode.shared
                    viewModel.onEvent(event: event)
                } label: {
                    HStack {
                        if state.isSubmitting {
                            ProgressView()
                                .tint(.white)
                        }
                        Text(LocalizedStringKey("action_mfa_verify"))
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(
                    state.isSubmitting ||
                    state.error is MfaChallengeErrorLocked ||
                    state.codeInput.isEmpty
                )
                .accessibilityIdentifier("mfaChallengeVerifyButton")

                Button {
                    let event: MfaChallengeEvent = isRecoveryPhase
                        ? MfaChallengeEventSwitchToTotp.shared
                        : MfaChallengeEventSwitchToRecoveryCode.shared
                    viewModel.onEvent(event: event)
                } label: {
                    Text(LocalizedStringKey(
                        isRecoveryPhase
                            ? "action_mfa_back_to_totp"
                            : "action_mfa_use_recovery_code"
                    ))
                }
                .accessibilityIdentifier("mfaChallengeToggleRecoveryButton")
            }
            .padding(.horizontal, 24)
        }
        .navigationTitle(LocalizedStringKey("title_mfa_challenge"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("mfaChallengeScreen")
    }

    private func errorText(_ error: MfaChallengeError?) -> LocalizedStringKey? {
        // Kotlin sealed-interface data-object cases bridge to Swift as
        // distinct classes (`MfaChallengeErrorXxx`). The dotted
        // `.InvalidCode` member-access form is unavailable because the
        // bridged interface surfaces as a Swift protocol.
        if error is MfaChallengeErrorInvalidCode {
            return "error_mfa_invalid_code"
        }
        if error is MfaChallengeErrorInvalidRecoveryCode || error is MfaChallengeErrorGeneric {
            return "error_mfa_invalid_recovery_code"
        }
        if error is MfaChallengeErrorLocked {
            return "error_mfa_locked"
        }
        return nil
    }
}
