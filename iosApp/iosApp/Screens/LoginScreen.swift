import SwiftUI
import Shared

struct LoginScreen: View {
    let viewModel: AuthViewModel
    @StateObject private var observer: ViewModelObserver<AuthUiState>
    @State private var showError = false

    init(viewModel: AuthViewModel) {
        self.viewModel = viewModel
        let wrapper = KoinHelperKt.wrapStateFlow(flow: viewModel.state) as! FlowWrapper<AuthUiState>
        _observer = StateObject(wrappedValue: ViewModelObserver(wrapper: wrapper))
    }

    var body: some View {
        let state = observer.state

        VStack(spacing: 24) {
            Spacer()

            // App title
            VStack(spacing: 8) {
                Text("Knit Note")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                Text(state.isSignUp ? "Create Account" : "Sign In")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            // Form fields
            VStack(spacing: 16) {
                TextField("Email", text: Binding(
                    get: { state.email },
                    set: { viewModel.onEvent(event: AuthEventUpdateEmail(email: $0)) }
                ))
                .textContentType(.emailAddress)
                .keyboardType(.emailAddress)
                .autocapitalization(.none)
                .textFieldStyle(.roundedBorder)

                SecureField("Password", text: Binding(
                    get: { state.password },
                    set: { viewModel.onEvent(event: AuthEventUpdatePassword(password: $0)) }
                ))
                .textContentType(state.isSignUp ? .newPassword : .password)
                .textFieldStyle(.roundedBorder)
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
                    Text(state.isSignUp ? "Sign Up" : "Sign In")
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(state.isSubmitting || state.email.isEmpty || state.password.isEmpty)
            .padding(.horizontal)

            // Toggle mode
            Button {
                viewModel.onEvent(event: AuthEventToggleMode.shared)
            } label: {
                Text(state.isSignUp ? "Already have an account? Sign In" : "Don't have an account? Sign Up")
                    .font(.footnote)
            }

            Spacer()
        }
        .onChange(of: state.error) { error in
            showError = error != nil
        }
        .alert("Error", isPresented: $showError) {
            Button("OK") {
                viewModel.onEvent(event: AuthEventClearError.shared)
            }
        } message: {
            Text(state.error ?? "")
        }
    }
}
