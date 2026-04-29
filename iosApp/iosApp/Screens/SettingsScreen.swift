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

    private var viewModel: SettingsViewModel { holder.viewModel }

    init() {
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
                    toastMessage = NSLocalizedString(key, comment: "")
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

            // About / Legal section — Phase E2
            Section("label_about_section") {
                Link(destination: URL(string: "https://b150005.github.io/knit-note/privacy-policy/")!) {
                    Label("action_privacy_policy", systemImage: "lock.shield")
                }
                .accessibilityIdentifier("privacyPolicyButton")

                Link(destination: URL(string: "https://b150005.github.io/knit-note/terms-of-service/")!) {
                    Label("action_terms_of_service", systemImage: "doc.text")
                }
                .accessibilityIdentifier("termsOfServiceButton")
            }

            // Privacy section — Phase F2 analytics opt-in. Default OFF; the
            // PostHog SDK init in iOSApp.swift is gated on this flag. Toggle
            // dispatches SetAnalyticsOptIn; the StateFlow flip is the single
            // source of truth, so SwiftUI re-renders from `state.analyticsOptIn`
            // rather than a local @State binding.
            Section {
                Toggle(isOn: Binding(
                    get: { state.analyticsOptIn },
                    set: { newValue in
                        viewModel.onEvent(
                            event: SettingsEventSetAnalyticsOptIn(value: newValue)
                        )
                    }
                )) {
                    Text("label_allow_analytics")
                }
                .accessibilityIdentifier("analyticsOptInSwitch")
            } header: {
                Text("label_privacy_section")
            } footer: {
                Text("body_analytics_explanation")
            }

            // Danger zone section
            // (continues below)
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
