import SwiftUI
import Shared

/// Phase 26.5 (ADR-022 §6.4) — SwiftUI mirror of the Compose
/// MfaEnrollmentScreen. Three sub-phases driven by
/// `MfaEnrollmentUiState.phase`:
///   - PairingQr — QR seed + manual entry secret + "I've added it" CTA
///   - ConfirmCode — 6-digit TOTP input + Verify
///   - RecoveryCodeDisplay — single-use recovery code shown once
///
/// QR code rendering is deferred for alpha; users enter the manual
/// secret into their authenticator app. Production rendering needs an
/// iOS-side QR library (CoreImage `CIFilter.qrCodeGenerator`) — landed
/// as a follow-up Tech Debt item.
struct MfaEnrollmentScreen: View {
    let onCompleted: () -> Void
    @StateObject private var holder: ScopedViewModel<MfaEnrollmentViewModel, MfaEnrollmentUiState>

    private var viewModel: MfaEnrollmentViewModel { holder.viewModel }

    init(onCompleted: @escaping () -> Void) {
        self.onCompleted = onCompleted
        let vm = ViewModelFactory.mfaEnrollmentViewModel()
        let wrapper = KoinHelperKt.wrapMfaEnrollmentState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state

        ScrollView {
            VStack(spacing: 24) {
                if state.enrollment == nil && state.isSubmitting {
                    ProgressView()
                        .padding(.top, 48)
                } else if state.enrollment == nil {
                    Text(LocalizedStringKey("error_mfa_enroll_failed"))
                        .foregroundStyle(.red)
                        .padding()
                } else {
                    let phaseValue = state.phase
                    if phaseValue == MfaEnrollmentPhase.pairingqr {
                        pairingContent(state: state)
                    } else if phaseValue == MfaEnrollmentPhase.confirmcode {
                        confirmContent(state: state)
                    } else {
                        recoveryCodeContent(state: state)
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
        .navigationTitle(titleKey(for: state.phase))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            viewModel.onEvent(event: MfaEnrollmentEventStart.shared)
        }
        .onChange(of: state.completed) { completed in
            if completed {
                onCompleted()
            }
        }
        .accessibilityIdentifier("mfaEnrollmentScreen")
    }

    private func titleKey(for phase: MfaEnrollmentPhase) -> LocalizedStringKey {
        if phase == MfaEnrollmentPhase.pairingqr {
            return "title_mfa_enroll"
        } else if phase == MfaEnrollmentPhase.confirmcode {
            return "title_mfa_confirm_code"
        } else {
            return "title_mfa_recovery_code"
        }
    }

    @ViewBuilder
    private func pairingContent(state: MfaEnrollmentUiState) -> some View {
        guard let enrollment = state.enrollment else {
            return AnyView(EmptyView())
        }
        return AnyView(
            VStack(spacing: 16) {
                Text(LocalizedStringKey("body_mfa_enroll_scan_qr"))
                    .multilineTextAlignment(.center)

                VStack(alignment: .leading, spacing: 8) {
                    Text(LocalizedStringKey("label_mfa_manual_secret"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(enrollment.secret)
                        .font(.system(.title3, design: .monospaced))
                        .accessibilityIdentifier("mfaManualSecret")
                    Text(enrollment.otpAuthUri)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("mfaOtpAuthUri")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
                .background(Color(.secondarySystemBackground))
                .cornerRadius(12)

                Button {
                    viewModel.onEvent(event: MfaEnrollmentEventAdvanceToConfirm.shared)
                } label: {
                    Text(LocalizedStringKey("action_mfa_advance_to_confirm"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("mfaAdvanceToConfirmButton")
            }
        )
    }

    @ViewBuilder
    private func confirmContent(state: MfaEnrollmentUiState) -> some View {
        VStack(spacing: 16) {
            Text(LocalizedStringKey("body_mfa_confirm_code"))
                .multilineTextAlignment(.center)

            TextField("", text: Binding(
                get: { state.codeInput },
                set: { viewModel.onEvent(event: MfaEnrollmentEventUpdateCode(code: $0)) }
            ))
            .textFieldStyle(.roundedBorder)
            .keyboardType(.numberPad)
            .disabled(state.isSubmitting)
            .accessibilityIdentifier("mfaCodeInput")

            if isInvalidCodeError(state.error) {
                Text(LocalizedStringKey("error_mfa_invalid_code"))
                    .font(.caption)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            Button {
                viewModel.onEvent(event: MfaEnrollmentEventSubmitCode.shared)
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
            .disabled(state.isSubmitting || state.codeInput.count != 6)
            .accessibilityIdentifier("mfaVerifyButton")
        }
    }

    @ViewBuilder
    private func recoveryCodeContent(state: MfaEnrollmentUiState) -> some View {
        let code = state.enrollment?.recoveryCode ?? ""
        VStack(spacing: 16) {
            Text(LocalizedStringKey("body_mfa_recovery_code_warning"))
                .multilineTextAlignment(.center)

            Text(code)
                .font(.system(.title2, design: .monospaced))
                .frame(maxWidth: .infinity)
                .padding(24)
                .background(Color.accentColor.opacity(0.15))
                .cornerRadius(12)
                .onTapGesture {
                    UIPasteboard.general.string = code
                }
                .accessibilityIdentifier("mfaRecoveryCodeBox")

            Button {
                viewModel.onEvent(event: MfaEnrollmentEventDismissRecoveryCode.shared)
            } label: {
                Text(LocalizedStringKey("action_mfa_recovery_code_saved"))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("mfaRecoveryCodeSavedButton")
        }
    }

    private func isInvalidCodeError(_ error: MfaEnrollmentError?) -> Bool {
        // Kotlin sealed-interface data-object cases bridge to Swift as
        // distinct classes (`MfaEnrollmentErrorInvalidCode`); the
        // dotted `.InvalidCode` member-access form is unavailable
        // because the bridged interface surfaces as a Swift protocol.
        return error is MfaEnrollmentErrorInvalidCode
    }
}
