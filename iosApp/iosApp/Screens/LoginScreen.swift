import SwiftUI
import Shared

struct LoginScreen: View {
    @StateObject private var holder: ScopedViewModel<AuthViewModel, AuthUiState>
    @State private var showError = false

    init(viewModel: AuthViewModel) {
        let wrapper = KoinHelperKt.wrapAuthState(flow: viewModel.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: viewModel, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state
        let viewModel = holder.viewModel

        VStack(spacing: 24) {
            Spacer()

            // App title
            VStack(spacing: 8) {
                // app_name is locale-identical ("Knit Note" in both en and ja).
                Text("app_name")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                Text(LocalizedStringKey(state.isSignUp ? "title_create_account" : "title_sign_in"))
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            // Form fields
            VStack(spacing: 16) {
                TextField(LocalizedStringKey("label_email"), text: Binding(
                    get: { state.email },
                    set: { viewModel.onEvent(event: AuthEventUpdateEmail(email: $0)) }
                ))
                .textContentType(.emailAddress)
                .keyboardType(.emailAddress)
                .autocapitalization(.none)
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier("emailField")

                SecureField(LocalizedStringKey("label_password"), text: Binding(
                    get: { state.password },
                    set: { viewModel.onEvent(event: AuthEventUpdatePassword(password: $0)) }
                ))
                .textContentType(state.isSignUp ? .newPassword : .password)
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier("passwordField")
            }
            .padding(.horizontal)

            // Submit button
            Button {
                viewModel.onEvent(event: AuthEventSubmit.shared)
            } label: {
                if state.isSubmitting {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                } else {
                    Text(LocalizedStringKey(state.isSignUp ? "action_sign_up" : "action_sign_in"))
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(state.isSubmitting || state.email.isEmpty || state.password.isEmpty)
            .padding(.horizontal)
            .accessibilityIdentifier("submitButton")

            // Toggle mode
            Button {
                viewModel.onEvent(event: AuthEventToggleMode.shared)
            } label: {
                Text(LocalizedStringKey(state.isSignUp ? "action_toggle_to_sign_in" : "action_toggle_to_sign_up"))
                    .font(.footnote)
            }

            Spacer()
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("loginScreen")
        .onChange(of: state.error) { _, newError in
            showError = newError != nil
        }
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button("action_ok") {
                viewModel.onEvent(event: AuthEventClearError.shared)
            }
        } message: {
            Text(state.error ?? "")
        }
    }
}
