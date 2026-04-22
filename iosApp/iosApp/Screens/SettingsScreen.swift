import SwiftUI
import Shared

struct SettingsScreen: View {
    @StateObject private var holder: ScopedViewModel<SettingsViewModel, SettingsState>
    @State private var showDeleteConfirmation = false
    @State private var showError = false

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
        .onChange(of: state.error) { _, newError in
            showError = newError != nil
        }
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button("action_ok") { viewModel.onEvent(event: SettingsEventClearError.shared) }
        } message: {
            // state.error is rendered raw — ViewModel error-message
            // localization is deferred per Tech Debt Backlog.
            Text(state.error ?? "")
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
            }

            // Danger zone section
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
