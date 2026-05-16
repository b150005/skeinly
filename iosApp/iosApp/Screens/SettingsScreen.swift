import SwiftUI
import Shared

struct SettingsScreen: View {
    @StateObject private var holder: ScopedViewModel<SettingsViewModel, SettingsState>
    // Phase 24.2c (ADR-017 §3.6) — push notification consent ViewModel.
    // Drives the Beta → Notifications row + the in-app pre-permission
    // explainer alert. Co-mounted with the SettingsViewModel so refresh
    // happens once at screen entry alongside the other Settings state.
    @StateObject private var notificationHolder: ScopedViewModel<
        NotificationPermissionViewModel, NotificationPermissionState
    >
    /// Phase 26.6 (ADR-022 §6.5) — biometric settings state for the
    /// Settings → Security row's trailing label. Same singleton VM as
    /// the BiometricSettingsScreen consumes (Koin returns the same
    /// process-scope instance), so toggling on the screen reactively
    /// updates the row's label without a manual refresh.
    @StateObject private var biometricHolder: ScopedViewModel<
        BiometricSettingsViewModel, BiometricSettingsState
    >
    @State private var showDeleteConfirmation = false
    @State private var showError = false
    @State private var newPassword = ""
    @State private var newEmail = ""
    @State private var toastMessage: String?
    @State private var toastCloseable: Closeable?
    /// Pre-alpha A25 — Reduce Motion. The toast assignment is currently
    /// un-animated (no `withAnimation`/`.animation` transaction → the
    /// declared transition is inert), so there is no motion to gate
    /// today. The transition is still made RM-aware defensively so a
    /// future `withAnimation` wrap cannot reintroduce an un-gated slide.
    ///
    /// COUPLED-EDIT: the `.transition` ternary gates the transition
    /// *shape*, not the animation *transaction*. If you later animate the
    /// toast, wrap the `toastMessage` assignment in
    /// `withMotion(reduceMotion, …)` — NOT a bare `withAnimation` — or
    /// the opacity fade still animates under Reduce Motion. The ternary
    /// alone is insufficient.
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Phase 26.5 (ADR-022 §6.4) — disable-2FA confirmation alert.
    @State private var showDisableMfaConfirmation = false

    // Phase 39.4 (ADR-015 §6) — invoked when the user taps "Send
    // Feedback". Phase 39.5 will plumb this to BugReportPreviewScreen
    // + GitHub Issue prefill; today the binding from the call site is a
    // no-op so the row renders correctly under the beta-gated section
    // without surfacing a half-built UX.
    let onSendFeedback: () -> Void
    /// Phase 41.3b (ADR-016 §5.1) — Pro section entry. Always-on, NOT
    /// gated on `BuildFlags.isBeta` because F1 monetization is a
    /// production feature.
    let onSubscribeToProClick: () -> Void
    /// Phase 41.4 (ADR-016 §5.2) — Manage Symbol Packs entry. Always-on.
    let onManagePacksClick: () -> Void
    /// Phase 26.5 (ADR-022 §6.4) — Security → Enable 2FA entry.
    let onEnableMfaClick: () -> Void
    /// Phase 26.6 (ADR-022 §6.5) — invoked when the user taps "Biometric
    /// authentication" in the Security section. AppRouter wires this to
    /// `path.append(.biometricSettings)`.
    let onBiometricSettingsClick: () -> Void
    /// Phase 27.2 (ADR-023 §UX) — invoked when the user taps "Delete
    /// all my data" in the Danger Zone section. AppRouter wires this
    /// to `path.append(.wipeDataConfirmPhrase)`.
    let onWipeDataClick: () -> Void
    /// Phase 25.3 (ADR-024 §(e)) — invoked when the user taps
    /// "Connections" in the new Privacy section. AppRouter wires this
    /// to `path.append(.connections)`.
    let onConnectionsClick: () -> Void
    /// Phase 39 (ADR-021 §D4) — invoked when the user taps "Blocked
    /// users" in the Privacy section. AppRouter wires this to
    /// `path.append(.blockedUsers)`.
    let onBlockedUsersClick: () -> Void

    private var viewModel: SettingsViewModel { holder.viewModel }
    private var notificationViewModel: NotificationPermissionViewModel { notificationHolder.viewModel }

    /// Phase 24.2c — bound to the alert presentation. Tapping outside
    /// the alert (impossible on iOS — alerts are modal) or selecting a
    /// button dismisses; the binding setter routes through
    /// `UserDismissedExplainer` only when the alert is closing without
    /// either button having fired (defensive — alert buttons always run
    /// their action closures synchronously before isPresented flips).
    private var notificationExplainerBinding: Binding<Bool> {
        Binding(
            get: { notificationHolder.state.isExplainerVisible },
            set: { newValue in
                if !newValue && notificationHolder.state.isExplainerVisible {
                    notificationViewModel.onEvent(
                        event: NotificationPermissionEventUserDismissedExplainer.shared
                    )
                }
            }
        )
    }

    /// Phase 24.2c — Settings row trailing label. iOS surfaces the same
    /// 3-state mapping as Compose: GRANTED → Enabled, DENIED /
    /// NOT_DETERMINED → Disabled. The `default:` arm prevents future
    /// status additions from silently misrendering.
    private var notificationStatusLabelKey: LocalizedStringKey {
        switch notificationHolder.state.osStatus {
        case .granted: return "state_notifications_enabled"
        case .denied, .notDetermined: return "state_notifications_disabled"
        default: return "state_notifications_disabled"
        }
    }

    init(
        onSendFeedback: @escaping () -> Void = {},
        onSubscribeToProClick: @escaping () -> Void = {},
        onManagePacksClick: @escaping () -> Void = {},
        onEnableMfaClick: @escaping () -> Void = {},
        onBiometricSettingsClick: @escaping () -> Void = {},
        onWipeDataClick: @escaping () -> Void = {},
        onConnectionsClick: @escaping () -> Void = {},
        onBlockedUsersClick: @escaping () -> Void = {}
    ) {
        self.onSendFeedback = onSendFeedback
        self.onSubscribeToProClick = onSubscribeToProClick
        self.onManagePacksClick = onManagePacksClick
        self.onEnableMfaClick = onEnableMfaClick
        self.onBiometricSettingsClick = onBiometricSettingsClick
        self.onWipeDataClick = onWipeDataClick
        self.onConnectionsClick = onConnectionsClick
        self.onBlockedUsersClick = onBlockedUsersClick
        let vm = ViewModelFactory.settingsViewModel()
        let wrapper = KoinHelperKt.wrapSettingsState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
        let nvm = KoinHelperKt.getNotificationPermissionViewModel()
        let nWrapper = KoinHelperKt.wrapNotificationPermissionState(flow: nvm.state)
        _notificationHolder = StateObject(
            wrappedValue: ScopedViewModel(viewModel: nvm, wrapper: nWrapper)
        )
        let bvm = ViewModelFactory.biometricSettingsViewModel()
        let bWrapper = KoinHelperKt.wrapBiometricSettingsState(flow: bvm.state)
        _biometricHolder = StateObject(
            wrappedValue: ScopedViewModel(viewModel: bvm, wrapper: bWrapper)
        )
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
        // Phase 24.2c (ADR-017 §3.6) — pre-permission explainer alert.
        // SwiftUI alert mirrors the Compose AlertDialog. Native OS prompt
        // fires only after the user taps Enable; Not now records a global
        // dismiss in NotificationPermissionPrompter so the alert never
        // re-surfaces.
        .alert(
            LocalizedStringKey("title_notifications_explainer"),
            isPresented: notificationExplainerBinding
        ) {
            Button("action_enable_notifications") {
                notificationViewModel.onEvent(
                    event: NotificationPermissionEventUserAcceptedExplainer.shared
                )
            }
            .disabled(notificationHolder.state.isRequestingPermission)
            Button("action_not_now_notifications", role: .cancel) {
                notificationViewModel.onEvent(
                    event: NotificationPermissionEventUserDismissedExplainer.shared
                )
            }
            .disabled(notificationHolder.state.isRequestingPermission)
        } message: {
            Text("body_notifications_explainer")
        }
        // Phase 26.5 (ADR-022 §6.4) — disable-2FA confirmation alert.
        // Confirm fires DisableMfaConfirmed; cancel dismisses with no
        // ViewModel event (the row's tap is the only entry point).
        .alert(
            LocalizedStringKey("title_mfa_disable_confirm"),
            isPresented: $showDisableMfaConfirmation
        ) {
            Button("action_disable_mfa", role: .destructive) {
                viewModel.onEvent(event: SettingsEventDisableMfaConfirmed.shared)
            }
            .accessibilityIdentifier("disableMfaConfirmButton")
            Button("action_cancel", role: .cancel) { }
        } message: {
            Text("body_mfa_disable_warning")
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
                    // A25: cross-dissolve is Apple's recommended Reduce-
                    // Motion substitution for a slide; full slide only when
                    // the user has not requested reduced motion.
                    .transition(
                        reduceMotion
                            ? .opacity
                            : .move(edge: .bottom).combined(with: .opacity)
                    )
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
                    // A25: assignment is intentionally un-animated. To
                    // animate, use `withMotion(reduceMotion) { … }` — NOT
                    // a bare `withAnimation` (see the `reduceMotion`
                    // property's COUPLED-EDIT note).
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

                    // Phase 26.6 (ADR-022 §6.6) — primary identity
                    // discriminator + linked-accounts list (when the
                    // user has merged multiple providers via Phase 26.4
                    // linkIdentity).
                    if let primary = state.linkedIdentities.first as? LinkedIdentity {
                        HStack {
                            Text("label_signed_in_via")
                            Spacer()
                            Text(providerLabelKey(for: primary))
                                .foregroundStyle(.secondary)
                        }
                        .accessibilityIdentifier("settingsSignedInVia")
                        let extras = state.linkedIdentities.compactMap { $0 as? LinkedIdentity }.dropFirst()
                        if !extras.isEmpty {
                            DisclosureGroup(LocalizedStringKey("label_linked_identities")) {
                                ForEach(Array(extras.enumerated()), id: \.offset) { _, extra in
                                    Text(providerLabelKey(for: extra))
                                        .accessibilityIdentifier("settingsLinkedIdentity")
                                }
                            }
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

                    // Pre-alpha A30 — Manage subscription deep link.
                    // Recommended by Apple HIG; mirrors the Compose Settings
                    // row for symmetry across platforms. Opens
                    // https://apps.apple.com/account/subscriptions via the
                    // shared `SubscriptionManagementLauncher` Kotlin
                    // platform actual. Shown to all signed-in users
                    // regardless of Pro subscription state (non-subscribers
                    // see an empty subscriptions list, which is informative).
                    Button {
                        KoinHelperKt.openSubscriptionManagement()
                    } label: {
                        VStack(alignment: .leading) {
                            Label("action_manage_subscription", systemImage: "creditcard")
                            Text("body_manage_subscription_helper")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    .accessibilityIdentifier("manageSubscriptionButton")
                }
            }

            // Phase 26.5 (ADR-022 §6.4) — Security section. Houses the
            // 2FA enable/disable row. Gated on `isSignedIn` because all
            // security controls require an active session.
            if state.isSignedIn {
                Section("label_security_section") {
                    // Kotlin sealed-interface data-class case `Enrolled` bridges
                    // to Swift as `MfaEnrollmentStatusEnrolled` (concatenated
                    // class name) — the dotted `.Enrolled` form is unavailable
                    // because `MfaEnrollmentStatus` surfaces as a Swift protocol.
                    let mfaEnrolled = state.mfaStatus is MfaEnrollmentStatusEnrolled
                    Button {
                        if mfaEnrolled {
                            showDisableMfaConfirmation = true
                        } else {
                            onEnableMfaClick()
                        }
                    } label: {
                        HStack {
                            Text(LocalizedStringKey("label_two_factor_auth"))
                            Spacer()
                            Text(LocalizedStringKey(
                                mfaEnrolled
                                    ? "state_mfa_enabled"
                                    : "state_mfa_disabled"
                            ))
                            .foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier("twoFactorAuthRow")

                    // Phase 26.6 (ADR-022 §6.5) — biometric entry routes to
                    // a pushed BiometricSettingsScreen with the toggle +
                    // threshold picker. Trailing state label reads from
                    // the same VM singleton that drives the screen so a
                    // toggle change reflects here reactively.
                    Button {
                        onBiometricSettingsClick()
                    } label: {
                        HStack {
                            Text(LocalizedStringKey("title_biometric_settings"))
                            Spacer()
                            Text(LocalizedStringKey(
                                biometricHolder.state.enabled
                                    ? "state_biometric_enabled"
                                    : "state_biometric_disabled"
                            ))
                            .foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier("biometricSettingsRow")
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

                // Pre-alpha A35 — Help & FAQ page link.
                Link(destination: URL(string: "https://b150005.github.io/skeinly/help/")!) {
                    Label("action_help_faq", systemImage: "questionmark.circle")
                }
                .accessibilityIdentifier("helpFaqButton")

                // Pre-alpha A33 — Open Source Licenses page link.
                // Required attribution for Apache-2.0 / MIT licensed
                // dependencies (Kotlin, Compose Multiplatform, Ktor,
                // Coil, etc.). Manual list for alpha; pre-Phase-40 GA
                // upgrade to AboutLibraries Gradle plugin scheduled.
                Link(destination: URL(string: "https://b150005.github.io/skeinly/licenses/")!) {
                    Label("action_open_source_licenses", systemImage: "doc.append")
                }
                .accessibilityIdentifier("openSourceLicensesButton")

                // Pre-alpha A34 — Contact Support row. Opens mailto:
                // composer via the shared `SupportContactLauncher`
                // Kotlin actual; the URL is built in commonMain with
                // diagnostic context pre-filled (app version / OS /
                // device / locale).
                Button {
                    KoinHelperKt.openSupportEmail()
                } label: {
                    VStack(alignment: .leading) {
                        Label("action_contact_support", systemImage: "envelope.badge")
                        Text("body_contact_support_helper")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .accessibilityIdentifier("contactSupportButton")
            }

            // Phase 41.3b (ADR-016 §5.1) — Skeinly Pro section. Always-on,
            // NOT gated on BuildFlags.isBeta unlike the Beta section below.
            Section("label_paywall_section") {
                Button {
                    viewModel.onEvent(event: SettingsEventSubscribeToProTapped.shared)
                    onSubscribeToProClick()
                } label: {
                    Label("label_subscribe_to_pro", systemImage: "star.fill")
                }
                .accessibilityIdentifier("subscribeToProButton")

                // Phase 41.4 (ADR-016 §5.2) — Manage Symbol Packs entry.
                // Always-on, NOT beta-gated.
                Button {
                    onManagePacksClick()
                } label: {
                    Label("label_pack_management", systemImage: "square.and.arrow.down.on.square")
                }
                .accessibilityIdentifier("managePacksButton")
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

                    // Phase 24.2c (ADR-017 §3.6) — Notifications row.
                    // Trailing label reflects current OS permission state.
                    // Tap routes to OS settings via OsSettingsLauncher
                    // (24.2c stub today, 24.2d wires real openURL).
                    Button {
                        notificationViewModel.onEvent(
                            event: NotificationPermissionEventOpenOsSettingsRequested()
                        )
                    } label: {
                        HStack {
                            Text("label_notifications_settings_row")
                            Spacer()
                            Text(notificationStatusLabelKey)
                                .foregroundColor(.secondary)
                        }
                    }
                    .accessibilityIdentifier("notificationsSettingsRow")

                    Button {
                        onSendFeedback()
                    } label: {
                        Label("action_send_feedback", systemImage: "exclamationmark.bubble")
                    }
                    .accessibilityIdentifier("sendFeedbackButton")
                } header: {
                    Text("label_beta_section")
                } footer: {
                    // Section footer surfaces three paragraphs inline.
                    VStack(alignment: .leading, spacing: 4) {
                        Text("body_diagnostic_data_explanation")
                        Text("body_notifications_setting_explanation")
                        Text("body_send_feedback_explanation")
                    }
                }
            }

            // Phase 25.3 (ADR-024 §(e)) — Privacy section. Sits ABOVE
            // Danger Zone so the destructiveness gradient reads
            // top-to-bottom: Connections (non-destructive) → Wipe
            // (content-destructive) → Delete Account (identity-
            // destructive). Auth-only because the friend graph is
            // auth-scoped.
            if state.isSignedIn {
                Section {
                    Button {
                        onConnectionsClick()
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("title_connections")
                                .foregroundStyle(.primary)
                            Text("body_connections_settings_row")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier("connectionsSettingsRow")

                    // Phase 39 (ADR-021 §D4) — Blocked Users row. Sits
                    // directly below Connections (both privacy
                    // graph-management entries; non-destructive).
                    Button {
                        onBlockedUsersClick()
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("title_blocked_users")
                                .foregroundStyle(.primary)
                            Text("body_blocked_users_settings_row")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier("blockedUsersSettingsRow")
                } header: {
                    Text("label_privacy_section")
                }
            }

            // B3 (Phase 39.1): Danger zone is auth-only — same gating
            // as the Account section above.
            if state.isSignedIn {
                Section {
                    // Phase 27.2 (ADR-023 §UX) — "Delete all my data" row.
                    // Sits ABOVE "Delete Account" — less-destructive first
                    // per Material + HIG. Routes to the wipe confirmation
                    // flow which prompts twice (preservation-matrix sheet +
                    // phrase typing) before firing the RPC.
                    Button {
                        onWipeDataClick()
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("title_wipe_data_settings_row")
                                .foregroundStyle(.primary)
                            Text("body_wipe_data_settings_row")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier("wipeDataRow")

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

    /// Phase 26.6 (ADR-022 §6.6) — closed-set mapping from Kotlin's
    /// [LinkedIdentity] to the localized copy key. Apple relay
    /// surfaces a distinct disclosure copy so users with Hide-My-Email
    /// understand the address shown elsewhere is a private relay.
    private func providerLabelKey(for identity: LinkedIdentity) -> LocalizedStringKey {
        switch identity.provider {
        case .apple:
            return identity.isAppleRelay
                ? LocalizedStringKey("state_signed_in_via_apple_relay")
                : LocalizedStringKey("state_signed_in_via_apple")
        case .google:
            return LocalizedStringKey("state_signed_in_via_google")
        case .email:
            return LocalizedStringKey("state_signed_in_via_email")
        default:
            return LocalizedStringKey("state_signed_in_via_email")
        }
    }
}
