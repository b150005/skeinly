import SwiftUI
import Shared

/// Phase B3 — request a password reset email. Presented as a sheet from
/// LoginScreen because both states are unauthenticated; users dismiss the
/// sheet and try logging in again after receiving the email.
struct ForgotPasswordScreen: View {
    let onDone: () -> Void
    @StateObject private var holder: ScopedViewModel<ForgotPasswordViewModel, ForgotPasswordState>
    @State private var showError = false

    private var viewModel: ForgotPasswordViewModel { holder.viewModel }

    init(onDone: @escaping () -> Void) {
        self.onDone = onDone
        let vm = ViewModelFactory.forgotPasswordViewModel()
        let wrapper = KoinHelperKt.wrapForgotPasswordState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state

        VStack(spacing: 24) {
            Spacer()

            if state.didSubmit {
                Text(LocalizedStringKey("message_reset_email_sent"))
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)

                Button {
                    onDone()
                } label: {
                    Text(LocalizedStringKey("action_back"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .padding(.horizontal)
                .accessibilityIdentifier("backToLoginButton")
            } else {
                Text(LocalizedStringKey("label_email_for_reset"))
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)

                TextField(LocalizedStringKey("label_email"), text: Binding(
                    get: { state.email },
                    set: { viewModel.onEvent(event: ForgotPasswordEventUpdateEmail(email: $0)) }
                ))
                .textContentType(.emailAddress)
                .keyboardType(.emailAddress)
                .autocapitalization(.none)
                .textFieldStyle(.roundedBorder)
                .padding(.horizontal)
                .accessibilityIdentifier("emailField")

                Button {
                    viewModel.onEvent(event: ForgotPasswordEventSubmit.shared)
                } label: {
                    if state.isSubmitting {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                    } else {
                        Text(LocalizedStringKey("action_send_reset_link"))
                            .frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(state.isSubmitting || state.email.isEmpty)
                .padding(.horizontal)
                .accessibilityIdentifier("sendResetLinkButton")
            }

            Spacer()
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("forgotPasswordScreen")
        .navigationTitle(LocalizedStringKey("title_forgot_password"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(LocalizedStringKey("action_cancel")) {
                    onDone()
                }
            }
        }
        .onChange(of: state.error != nil) { _, hasError in
            showError = hasError
        }
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button("action_ok") {
                viewModel.onEvent(event: ForgotPasswordEventClearError.shared)
            }
        } message: {
            Text(state.error?.localizedString ?? "")
        }
    }
}
