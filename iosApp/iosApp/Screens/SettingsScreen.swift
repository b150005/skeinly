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

        Group {
            if state.isLoading {
                ProgressView()
            } else {
                settingsContent(state)
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(
            "Delete Account?",
            isPresented: $showDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("Delete Account", role: .destructive) {
                viewModel.onEvent(event: SettingsEventDeleteAccountConfirmed.shared)
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will permanently delete your account and all associated data. This action cannot be undone.")
        }
        .onChange(of: state.error) { _, newError in
            showError = newError != nil
        }
        .alert("Error", isPresented: $showError) {
            Button("OK") { viewModel.onEvent(event: SettingsEventClearError.shared) }
        } message: {
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
            Section("Account") {
                if let email = state.email {
                    HStack {
                        Text("Email")
                        Spacer()
                        Text(email)
                            .foregroundStyle(.secondary)
                    }
                }

                Button {
                    viewModel.onEvent(event: SettingsEventSignOut.shared)
                } label: {
                    Label("Sign Out", systemImage: "rectangle.portrait.and.arrow.right")
                }
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
                            Text("Deleting Account...")
                        }
                    } else {
                        Label("Delete Account", systemImage: "trash")
                    }
                }
                .disabled(state.isDeletingAccount)
            } header: {
                Text("Danger Zone")
            } footer: {
                Text("Deleting your account will permanently remove all your projects, progress, and shared content. This action cannot be undone.")
            }
        }
    }
}
