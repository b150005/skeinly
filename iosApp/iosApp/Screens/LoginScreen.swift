import AuthenticationServices
import SwiftUI
import Shared

struct LoginScreen: View {
    @StateObject private var holder: ScopedViewModel<AuthViewModel, AuthUiState>
    @State private var showError = false
    @State private var showForgotPassword = false
    /// Phase 26.1 — surfaced via `state.linkIdentityRequired` after the
    /// repository returns `OAuthSignInOutcome.LinkIdentityRequired`.
    @State private var showLinkIdentityPrompt = false

    init(viewModel: AuthViewModel) {
        let wrapper = KoinHelperKt.wrapAuthState(flow: viewModel.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: viewModel, wrapper: wrapper))
    }

    // Phase 26.1 — current build's Apple Sign-In button style. `.signIn`
    // for both sign-in and sign-up modes; Apple's HIG treats this as a
    // single capability rather than two distinct affordances.
    private var appleButtonStyle: SignInWithAppleButton.Style {
        // Default to dark style — sits well on both light and dark
        // app surfaces and matches Apple HIG guidance "use the style
        // that contrasts with your sign-in surface".
        return .black
    }

    var body: some View {
        let state = holder.state
        let viewModel = holder.viewModel

        // When Supabase has Confirm-email enabled on the dashboard,
        // signUp succeeds at HTTP but no session is created — the
        // ViewModel surfaces that via emailConfirmationSentTo. Show a
        // dedicated "check your email" view in that case; otherwise
        // the standard sign-in / sign-up form.
        if let confirmationEmail = state.emailConfirmationSentTo {
            EmailConfirmationSentView(
                email: confirmationEmail,
                onReturnToSignIn: {
                    viewModel.onEvent(event: AuthEventDismissEmailConfirmation.shared)
                }
            )
        } else {
            signInForm(state: state, viewModel: viewModel)
        }
    }

    @ViewBuilder
    private func signInForm(state: AuthUiState, viewModel: AuthViewModel) -> some View {
        VStack(spacing: 24) {
            Spacer()

            // App title
            VStack(spacing: 8) {
                // app_name is locale-identical ("Skeinly" in both en and ja).
                Text("app_name")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                Text(LocalizedStringKey(state.isSignUp ? "title_create_account" : "title_sign_in"))
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            // Phase 26.1 (ADR-022 §6.1) — Apple Sign-In primary CTA.
            // Placed ABOVE the email/password form per Apple HIG §"Sign
            // in with Apple": the OAuth path SHOULD be visually
            // prominent when both are offered. The email/password
            // surface stays below as a secondary path.
            SignInWithAppleButton(
                .signIn,
                onRequest: { request in
                    request.requestedScopes = [.fullName, .email]
                    let nonces = AppleSignInBridge.shared.beginRequest()
                    request.nonce = nonces.sha256Digest
                },
                onCompletion: { result in
                    AppleSignInBridge.shared.handleCompletion(result) { idToken, nonce in
                        KoinHelperKt.signInWithAppleIdToken(idToken: idToken, nonce: nonce)
                    }
                }
            )
            .signInWithAppleButtonStyle(appleButtonStyle)
            .frame(height: 48)
            .padding(.horizontal)
            .disabled(state.isSubmitting)
            .accessibilityIdentifier("signInWithAppleButton")

            // "or sign in with email" divider — visually separates the
            // primary Apple Sign-In path from the email/password
            // secondary fallback below.
            HStack(spacing: 8) {
                Rectangle()
                    .fill(Color.secondary.opacity(0.3))
                    .frame(height: 1)
                Text(LocalizedStringKey("label_or_sign_in_with_email"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .layoutPriority(1)
                Rectangle()
                    .fill(Color.secondary.opacity(0.3))
                    .frame(height: 1)
            }
            .padding(.horizontal)

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

            // Forgot password — only in sign-in mode (not sign-up).
            if !state.isSignUp {
                Button {
                    showForgotPassword = true
                } label: {
                    Text(LocalizedStringKey("action_forgot_password"))
                        .font(.footnote)
                }
                .accessibilityIdentifier("forgotPasswordButton")
            }

            Spacer()
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("loginScreen")
        .onChange(of: state.error != nil) { _, hasError in
            showError = hasError
        }
        .onChange(of: state.linkIdentityRequired != nil) { _, hasChallenge in
            showLinkIdentityPrompt = hasChallenge
        }
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button("action_ok") {
                viewModel.onEvent(event: AuthEventClearError.shared)
            }
        } message: {
            Text(state.error?.localizedString ?? "")
        }
        .alert(
            LocalizedStringKey("title_email_confirmation_sent"),
            isPresented: $showLinkIdentityPrompt
        ) {
            Button("action_ok") {
                viewModel.onEvent(event: AuthEventDismissLinkIdentityPrompt.shared)
            }
        } message: {
            // The challenge carries the email Supabase reported as
            // already-registered. Display it in the prompt body so the
            // user can identify which credentials to retry with.
            let challenge = state.linkIdentityRequired
            let email = challenge?.email ?? ""
            Text(
                String(
                    format: NSLocalizedString("body_email_already_used_oauth_link_prompt", comment: ""),
                    email
                )
            )
        }
        .sheet(isPresented: $showForgotPassword) {
            NavigationStack {
                ForgotPasswordScreen(onDone: { showForgotPassword = false })
            }
        }
    }
}

/// "Check your email" view shown after a sign-up call returns
/// `SignUpOutcome.EmailConfirmationRequired` (Supabase dashboard has
/// Confirm-email enabled, so signUp succeeded at HTTP but no session
/// was issued until the user confirms via the link).
private struct EmailConfirmationSentView: View {
    let email: String
    let onReturnToSignIn: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Text(LocalizedStringKey("title_email_confirmation_sent"))
                .font(.title2)
                .fontWeight(.semibold)

            VStack(spacing: 12) {
                Text(String(format: NSLocalizedString("body_email_confirmation_sent", comment: ""), email))
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Text(LocalizedStringKey("body_email_confirmation_check_spam"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Text(LocalizedStringKey("body_email_confirmation_check_existing_account"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal)

            Button {
                onReturnToSignIn()
            } label: {
                Text(LocalizedStringKey("action_return_to_sign_in"))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .padding(.horizontal)
            .accessibilityIdentifier("returnToSignInButton")

            Spacer()
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("emailConfirmationSentScreen")
    }
}
