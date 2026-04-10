import SwiftUI
import Shared

struct ProfileScreen: View {
    private let viewModel: ProfileViewModel
    @StateObject private var observer: ViewModelObserver<ProfileState>
    @State private var showError = false

    init() {
        let vm = ViewModelFactory.profileViewModel()
        self.viewModel = vm
        let wrapper = KoinHelperKt.wrapStateFlow(flow: vm.state) as! FlowWrapper<ProfileState>
        _observer = StateObject(wrappedValue: ViewModelObserver(wrapper: wrapper))
    }

    var body: some View {
        let state = observer.state

        Group {
            if state.isLoading {
                ProgressView()
            } else if state.isEditing {
                editView(state)
            } else {
                displayView(state)
            }
        }
        .navigationTitle("Profile")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !state.isEditing && !state.isLoading {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        viewModel.onEvent(event: ProfileEventStartEditing.shared)
                    } label: {
                        Text("Edit")
                    }
                }
            }
        }
        .onChange(of: state.error) { error in
            showError = error != nil
        }
        .alert("Error", isPresented: $showError) {
            Button("OK") { viewModel.onEvent(event: ProfileEventClearError.shared) }
        } message: {
            Text(state.error ?? "")
        }
    }

    // MARK: - Display View

    @ViewBuilder
    private func displayView(_ state: ProfileState) -> some View {
        VStack(spacing: 16) {
            Spacer().frame(height: 20)

            Image(systemName: "person.circle.fill")
                .font(.system(size: 80))
                .foregroundStyle(.secondary)

            if let user = state.user {
                Text(user.displayName)
                    .font(.title2)
                    .fontWeight(.semibold)

                if let bio = user.bio, !bio.isEmpty {
                    Text(bio)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }
            } else {
                Text("No profile yet")
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
    }

    // MARK: - Edit View

    @ViewBuilder
    private func editView(_ state: ProfileState) -> some View {
        Form {
            Section {
                HStack {
                    Spacer()
                    Image(systemName: "person.circle.fill")
                        .font(.system(size: 60))
                        .foregroundStyle(.secondary)
                    Spacer()
                }
            }
            .listRowBackground(Color.clear)

            Section("Display Name") {
                TextField("Display Name", text: Binding(
                    get: { state.editDisplayName },
                    set: { viewModel.onEvent(event: ProfileEventUpdateDisplayName(value: $0)) }
                ))
            }

            Section("Bio") {
                TextField("Bio", text: Binding(
                    get: { state.editBio },
                    set: { viewModel.onEvent(event: ProfileEventUpdateBio(value: $0)) }
                ), axis: .vertical)
                .lineLimit(3...6)
            }

            Section {
                HStack {
                    Button("Cancel", role: .cancel) {
                        viewModel.onEvent(event: ProfileEventCancelEditing.shared)
                    }
                    Spacer()
                    Button {
                        viewModel.onEvent(event: ProfileEventSaveProfile.shared)
                    } label: {
                        if state.isSaving {
                            ProgressView()
                        } else {
                            Text("Save")
                        }
                    }
                    .disabled(state.isSaving)
                }
            }
        }
    }
}
