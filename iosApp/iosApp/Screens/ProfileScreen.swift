import SwiftUI
import PhotosUI
import Shared

struct ProfileScreen: View {
    @StateObject private var holder: ScopedViewModel<ProfileViewModel, ProfileState>
    @State private var showError = false
    @State private var selectedPhoto: PhotosPickerItem?

    private var viewModel: ProfileViewModel { holder.viewModel }

    init() {
        let vm = ViewModelFactory.profileViewModel()
        let wrapper = KoinHelperKt.wrapProfileState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state

        // ZStack owns a real layout node so `.accessibilityElement` +
        // `.accessibilityIdentifier` attach to one concrete container.
        // Applying them to a bare `Group` would propagate to each child
        // branch independently and break the `profileScreen` landmark
        // query used by Maestro flows and XCUITests.
        ZStack {
            if state.isLoading {
                ProgressView()
            } else if state.isEditing {
                editView(state)
            } else {
                displayView(state)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("profileScreen")
        .navigationTitle(LocalizedStringKey("title_profile"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !state.isEditing && !state.isLoading {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        viewModel.onEvent(event: ProfileEventStartEditing.shared)
                    } label: {
                        Text("action_edit")
                    }
                    .accessibilityIdentifier("editButton")
                }
            }
        }
        .onChange(of: state.error != nil) { _, hasError in
            showError = hasError
        }
        // `.alert(_:isPresented:...)` has overloads for `String` and
        // `LocalizedStringKey`; overload resolution on a bare literal can
        // select the `String` form, which does not localize. Wrap
        // explicitly per the LoginScreen precedent.
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button("action_ok") { viewModel.onEvent(event: ProfileEventClearError.shared) }
        } message: {
            Text(state.error?.localizedString ?? "")
        }
    }

    // MARK: - Display View

    @ViewBuilder
    private func displayView(_ state: ProfileState) -> some View {
        VStack(spacing: 16) {
            Spacer().frame(height: 20)

            avatarView(state)

            // Phase C — change-avatar entry
            PhotosPicker(
                selection: $selectedPhoto,
                matching: .images,
                photoLibrary: .shared()
            ) {
                Text(LocalizedStringKey("action_change_avatar"))
                    .font(.footnote)
            }
            .accessibilityIdentifier("changeAvatarButton")
            .disabled(state.isUploadingAvatar)
            .onChange(of: selectedPhoto) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        let bytes = KotlinByteArray(size: Int32(data.count))
                        for (i, byte) in data.enumerated() {
                            bytes.set(index: Int32(i), value: Int8(bitPattern: byte))
                        }
                        viewModel.onEvent(
                            event: ProfileEventUploadAvatar(
                                imageData: bytes,
                                fileName: "avatar.jpg"
                            )
                        )
                    }
                    selectedPhoto = nil
                }
            }

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
                // `user == nil` post-load means the load failed — the
                // ViewModel doesn't distinguish "no profile created" from
                // a failure branch, so reuse the Kotlin-branch key.
                Text("state_profile_load_failed")
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
    }

    @ViewBuilder
    private func avatarView(_ state: ProfileState) -> some View {
        ZStack {
            if let url = state.user?.avatarUrl, let urlObj = URL(string: url) {
                AsyncImage(url: urlObj) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                    default:
                        Image(systemName: "person.circle.fill")
                            .font(.system(size: DesignTokens.avatarSizeLarge))
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(width: DesignTokens.avatarSizeLarge, height: DesignTokens.avatarSizeLarge)
                .clipShape(Circle())
                .accessibilityLabel(LocalizedStringKey("label_avatar"))
            } else {
                Image(systemName: "person.circle.fill")
                    .font(.system(size: DesignTokens.avatarSizeLarge))
                    .foregroundStyle(.secondary)
                    .accessibilityLabel(LocalizedStringKey("label_avatar"))
            }

            if state.isUploadingAvatar {
                ProgressView()
                    .scaleEffect(1.5)
            }
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
                        .font(.system(size: DesignTokens.avatarSizeSmall))
                        .foregroundStyle(.secondary)
                        .accessibilityLabel(LocalizedStringKey("label_avatar"))
                    Spacer()
                }
            }
            .listRowBackground(Color.clear)

            Section(LocalizedStringKey("label_display_name")) {
                TextField(LocalizedStringKey("label_display_name"), text: Binding(
                    get: { state.editDisplayName },
                    set: { viewModel.onEvent(event: ProfileEventUpdateDisplayName(value: $0)) }
                ))
                .accessibilityIdentifier("displayNameField")
            }

            Section(LocalizedStringKey("label_bio")) {
                TextField(LocalizedStringKey("label_bio"), text: Binding(
                    get: { state.editBio },
                    set: { viewModel.onEvent(event: ProfileEventUpdateBio(value: $0)) }
                ), axis: .vertical)
                .lineLimit(3...6)
                .accessibilityIdentifier("bioField")
            }

            Section {
                HStack {
                    Button("action_cancel", role: .cancel) {
                        viewModel.onEvent(event: ProfileEventCancelEditing.shared)
                    }
                    .accessibilityIdentifier("cancelButton")
                    Spacer()
                    Button {
                        viewModel.onEvent(event: ProfileEventSaveProfile.shared)
                    } label: {
                        if state.isSaving {
                            ProgressView()
                        } else {
                            Text("action_save")
                        }
                    }
                    .disabled(state.isSaving)
                    .accessibilityIdentifier("saveButton")
                }
            }
        }
    }
}
