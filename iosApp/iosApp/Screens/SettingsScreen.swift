import SwiftUI
import Shared

struct SettingsScreen: View {
    @StateObject private var holder: ScopedViewModel<SettingsViewModel, SettingsState>
    @State private var showDeleteConfirmation = false
    @State private var showError = false
    @State private var newPassword = ""
    @State private var newEmail = ""
    @State private var toastMessage: String?
    @State private var toastCloseable: Closeable?

    // Phase 39.4 (ADR-015 §6) — invoked when the user taps "Send
    // Feedback". Phase 39.5 will plumb this to BugReportPreviewScreen
    // + GitHub Issue prefill; today the binding from the call site is a
    // no-op so the row renders correctly under the beta-gated section
    // without surfacing a half-built UX.
    let onSendFeedback: () -> Void

    private var viewModel: SettingsViewModel { holder.viewModel }

    init(onSendFeedback: @escaping () -> Void = {}) {
        self.onSendFeedback = onSendFeedback
        let vm = ViewModelFactory.settingsViewModel()
        let wrapper = KoinHelperKt.wrapSettingsState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state

        // ZStack wrap (not Group) so the accessibility element attaches to a
        // concrete layout node — matches the Profile 33.1.6 HIGH fix, where a
        // bare Group is layout-transparent and propagates modifiers to each
        // branch independently, breaking the landmark query.
        ZStack {
            if state.isLoading {
                ProgressView()
            } else {
                settingsContent(state)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("settingsScreen")
        .navigationTitle(LocalizedStringKey("title_settings"))
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(
            LocalizedStringKey("dialog_delete_account_title"),
            isPresented: $showDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("action_delete_account", role: .destructive) {
                viewModel.onEvent(event: SettingsEventDeleteAccountConfirmed.shared)
            }
            Button("action_cancel", role: .cancel) {}
        } message: {
            // Wrap explicitly: `.confirmationDialog` is in the overload-
            // ambiguous "needs wrap" column of the SwiftUI literal-promotion
            // table (convention doc) — the message closure inherits that
            // caution since the whole modifier group can resolve via String
            // overloads in some Swift toolchains.
            Text(LocalizedStringKey("dialog_delete_account_body"))
        }
        .onChange(of: state.error != nil) { _, hasError in
            showError = hasError
        }
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button("action_ok") { viewModel.onEvent(event: SettingsEventClearError.shared) }
        } message: {            Text(state.error?.localizedString ?? "")
        }
        .sheet(isPresented: Binding(
            get: { state.pendingChangePasswordDialog },
            set: { if !$0 { viewModel.onEvent(event: SettingsEventDismissChangePassword.shared) } }
        )) {
            changePasswordSheet(state: state)
        }
        .sheet(isPresented: Binding(
            get: { state.pendingChangeEmailDialog },
            set: { if !$0 { viewModel.onEvent(event: SettingsEventDismissChangeEmail.shared) } }
        )) {
            changeEmailSheet(state: state)
        }
        .overlay(alignment: .bottom) {
            if let toastMessage {
                Text(toastMessage)
                    .padding()
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
                    .padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .task {
            toastCloseable?.close()
            let flow = KoinHelperKt.wrapSettingsToastEvents(flow: holder.viewModel.toastEvents)
            toastCloseable = flow.collect { event in
                Task { @MainActor in
                    let key: String =
                        if event is SettingsToastEventPasswordChanged {
                            "message_password_changed"
                        } else if event is SettingsToastEventEmailChangePending {
                            "message_email_change_pending"
                        } else {
                            ""
                        }
                    let resolved = NSLocalizedString(key, comment: "")
                    toastMessage = resolved
                    announceToVoiceOver(message: resolved)
                    try? await Task.sleep(nanoseconds: 2_500_000_000)
                    toastMessage = nil
                }
            }
        }
        .onDisappear {
            toastCloseable?.close()
            toastCloseable = nil
        }
        // Account deletion triggers signOut internally, which sets AuthState
        // to Unauthenticated. AppRootView observes this and navigates to login.
    }

    // MARK: - Settings Content

    @ViewBuilder
    private func settingsContent(_ state: SettingsState) -> some View {
        List {
            // B3 (Phase 39.1): Account section is gated on `state.isSignedIn`.
            // Apple Reviewers may reject UIs that imply features the user
            // can't access (e.g. showing "Sign Out" / "Delete Account"
            // while never signed in).
            if state.isSignedIn {
                // Account section
                Section("label_account_section") {
                    if let email = state.email {
                        HStack {
                            Text("label_email")
                            Spacer()
                            Text(email)
                                .foregroundStyle(.secondary)
                        }
                    }

                    Button {
                        viewModel.onEvent(event: SettingsEventSignOut.shared)
                    } label: {
                        Label("action_sign_out", systemImage: "rectangle.portrait.and.arrow.right")
                    }
                    .accessibilityIdentifier("signOutButton")

                    // Phase B3: change password / change email
                    Button {
                        newPassword = ""
                        viewModel.onEvent(event: SettingsEventRequestChangePassword.shared)
                    } label: {
                        Label("action_change_password", systemImage: "key.fill")
                    }
                    .accessibilityIdentifier("changePasswordButton")

                    Button {
                        newEmail = ""
                        viewModel.onEvent(event: SettingsEventRequestChangeEmail.shared)
                    } label: {
                        Label("action_change_email", systemImage: "envelope")
                    }
                    .accessibilityIdentifier("changeEmailButton")
                }
            }

            // About / Legal section — Phase E2
            Section("label_about_section") {
                Link(destination: URL(string: "https://b150005.github.io/skeinly/privacy-policy/")!) {
                    Label("action_privacy_policy", systemImage: "lock.shield")
                }
                .accessibilityIdentifier("privacyPolicyButton")

                Link(destination: URL(string: "https://b150005.github.io/skeinly/terms-of-service/")!) {
                    Label("action_terms_of_service", systemImage: "doc.text")
                }
                .accessibilityIdentifier("termsOfServiceButton")
            }

            // Beta section — Phase 39.4 (ADR-015 §6). Holds diagnostic
            // data sharing + Send Feedback. Gated on `BuildFlags.isBeta`
            // so production binaries surface neither — Phase 27a
            // no-tracking stance for v1.0+. The pre-39.4 "Privacy"
            // section header / `label_allow_analytics` copy was renamed
            // because what we collect on beta is diagnostic data, not
            // "usage analytics" in general.
            if BuildFlags.isBeta {
                Section {
                    Toggle(isOn: Binding(
                        get: { state.analyticsOptIn },
                        set: { newValue in
                            viewModel.onEvent(
                                event: SettingsEventSetAnalyticsOptIn(value: newValue)
                            )
                        }
                    )) {
                        Text("label_diagnostic_data_sharing")
                    }
                    .accessibilityIdentifier("analyticsOptInSwitch")

                    Button {
                        onSendFeedback()
                    } label: {
                        Label("action_send_feedback", systemImage: "exclamationmark.bubble")
                    }
                    .accessibilityIdentifier("sendFeedbackButton")
                } header: {
                    Text("label_beta_section")
                } footer: {
                    // Two paragraphs joined; Section footer surfaces them
                    // inline as a single block. Mirrors the Compose
                    // sibling-Texts under the toggle.
                    VStack(alignment: .leading, spacing: 4) {
                        Text("body_diagnostic_data_explanation")
                        Text("body_send_feedback_explanation")
                    }
                }
            }

            // B3 (Phase 39.1): Danger zone is auth-only — same gating
            // as the Account section above.
            if state.isSignedIn {
                Section {
                    Button(role: .destructive) {
                        showDeleteConfirmation = true
                    } label: {
                        if state.isDeletingAccount {
                            HStack {
                                ProgressView()
                                    .padding(.trailing, 8)
                                Text("state_deleting_account")
                            }
                        } else {
                            Label("action_delete_account", systemImage: "trash")
                        }
                    }
                    .disabled(state.isDeletingAccount)
                    .accessibilityIdentifier("deleteAccountButton")
                } header: {
                    Text("label_danger_zone")
                } footer: {
                    Text("body_delete_account_warning")
                }
            }
        }
    }

    @ViewBuilder
    private func changePasswordSheet(state: SettingsState) -> some View {
        NavigationStack {
            Form {
                Section {
                    SecureField(
                        LocalizedStringKey("label_new_password"),
                        text: $newPassword
                    )
                    .textContentType(.newPassword)
                    .accessibilityIdentifier("newPasswordField")
                }
            }
            .navigationTitle(LocalizedStringKey("dialog_change_password_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(LocalizedStringKey("action_cancel")) {
                        viewModel.onEvent(event: SettingsEventDismissChangePassword.shared)
                    }
                    .disabled(state.isChangingPassword)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(LocalizedStringKey("action_save")) {
                        viewModel.onEvent(
                            event: SettingsEventConfirmChangePassword(newPassword: newPassword)
                        )
                    }
                    .disabled(state.isChangingPassword || newPassword.isEmpty)
                    .accessibilityIdentifier("confirmChangePasswordButton")
                }
            }
        }
    }

    @ViewBuilder
    private func changeEmailSheet(state: SettingsState) -> some View {
        NavigationStack {
            Form {
                Section {
                    TextField(
                        LocalizedStringKey("label_new_email"),
                        text: $newEmail
                    )
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .autocapitalization(.none)
                    .accessibilityIdentifier("newEmailField")
                }
            }
            .navigationTitle(LocalizedStringKey("dialog_change_email_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(LocalizedStringKey("action_cancel")) {
                        viewModel.onEvent(event: SettingsEventDismissChangeEmail.shared)
                    }
                    .disabled(state.isChangingEmail)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(LocalizedStringKey("action_save")) {
                        viewModel.onEvent(
                            event: SettingsEventConfirmChangeEmail(newEmail: newEmail)
                        )
                    }
                    .disabled(state.isChangingEmail || newEmail.isEmpty)
                    .accessibilityIdentifier("confirmChangeEmailButton")
                }
            }
        }
    }
}
