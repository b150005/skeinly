import SwiftUI
import Shared

/// Phase 26.6 (ADR-022 §6.6) — SwiftUI mirror of the Compose
/// `OAuthProfileSetupScreen`. Mounted as a full-screen gate after the
/// first Authenticated transition for a user who has not completed the
/// gate AND whose OAuth provider supplied a `displayName` seed.
///
/// Two exit paths (Save / Skip) both fire
/// `OAuthProfileSetupNavEventCompleted`; the host (AppRouter) reads
/// the event and routes back to the main surface, marking the gate
/// preference complete server-side via the ViewModel's repository wiring.
struct OAuthProfileSetupScreen: View {
    @StateObject private var holder: ScopedViewModel<OAuthProfileSetupViewModel, OAuthProfileSetupState>
    @StateObject private var navObserver: EventFlowObserver<OAuthProfileSetupNavEvent>

    private let onCompleted: () -> Void
    private var viewModel: OAuthProfileSetupViewModel { holder.viewModel }

    init(displayName: String?, pictureUrl: String?, onCompleted: @escaping () -> Void) {
        let vm = ViewModelFactory.oauthProfileSetupViewModel(
            displayName: displayName,
            pictureUrl: pictureUrl
        )
        let stateWrapper = KoinHelperKt.wrapOAuthProfileSetupState(flow: vm.state)
        _holder = StateObject(
            wrappedValue: ScopedViewModel(viewModel: vm, wrapper: stateWrapper)
        )
        let navWrapper = KoinHelperKt.wrapOAuthProfileSetupNavEvents(flow: vm.navEvents)
        _navObserver = StateObject(wrappedValue: EventFlowObserver(wrapper: navWrapper))
        self.onCompleted = onCompleted
    }

    var body: some View {
        let state = holder.state

        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(LocalizedStringKey("title_oauth_profile_setup"))
                        .font(.title2)
                        .fontWeight(.semibold)
                    Text(LocalizedStringKey("body_oauth_profile_setup"))
                        .font(.body)
                        .foregroundStyle(.secondary)

                    TextField(
                        LocalizedStringKey("label_oauth_profile_display_name"),
                        text: Binding(
                            get: { state.displayName },
                            set: { newValue in
                                viewModel.onEvent(
                                    event: OAuthProfileSetupEventUpdateDisplayName(value: newValue)
                                )
                            }
                        )
                    )
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled(false)
                    .textFieldStyle(.roundedBorder)
                    .disabled(state.isSubmitting)
                    .accessibilityIdentifier("oauthProfileDisplayNameField")

                    if let pictureUrl = state.pictureUrl {
                        avatarTile(pictureUrl: pictureUrl, state: state)
                    }

                    Button {
                        viewModel.onEvent(event: OAuthProfileSetupEventSubmit())
                    } label: {
                        Group {
                            if state.isSubmitting {
                                ProgressView()
                            } else {
                                Text(LocalizedStringKey("action_save_profile_setup"))
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(
                        state.isSubmitting ||
                        state.displayName.trimmingCharacters(in: .whitespaces).isEmpty
                    )
                    .accessibilityIdentifier("oauthProfileSaveButton")

                    Button {
                        viewModel.onEvent(event: OAuthProfileSetupEventSkip())
                    } label: {
                        Text(LocalizedStringKey("action_skip_profile_setup"))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .disabled(state.isSubmitting)
                    .accessibilityIdentifier("oauthProfileSkipButton")
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 16)
            }
            .accessibilityIdentifier("oauthProfileSetupScreen")
        }
        .onChange(of: navObserver.latestEvent != nil) { _, hasEvent in
            guard hasEvent else { return }
            navObserver.consume()
            onCompleted()
        }
    }

    @ViewBuilder
    private func avatarTile(pictureUrl: String, state: OAuthProfileSetupState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(LocalizedStringKey("title_oauth_profile_avatar"))
                .font(.headline)

            if state.isImportingAvatar {
                HStack {
                    ProgressView()
                        .accessibilityIdentifier("oauthAvatarImportingSpinner")
                    Text(LocalizedStringKey("state_oauth_avatar_importing"))
                        .foregroundStyle(.secondary)
                }
            } else if state.avatarImported {
                HStack {
                    Text(LocalizedStringKey("state_oauth_avatar_imported"))
                        .foregroundStyle(.tint)
                    Spacer()
                    Button {
                        viewModel.onEvent(event: OAuthProfileSetupEventChooseDifferentAvatar())
                    } label: {
                        Text(LocalizedStringKey("action_choose_different_avatar"))
                    }
                    .accessibilityIdentifier("oauthAvatarChooseDifferentButton")
                }
            } else {
                Button {
                    viewModel.onEvent(event: OAuthProfileSetupEventUseOAuthAvatar())
                } label: {
                    Text(LocalizedStringKey("action_use_oauth_avatar"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.regular)
                .disabled(state.isSubmitting)
                .accessibilityIdentifier("oauthAvatarUseButton")
            }

            // Phase 26.7 (Tech Debt resolution) — surface a hint
            // pointing the user at Settings → Profile after they've
            // declined the OAuth picture. Photo picker integration on
            // the setup screen itself is deferred — Settings → Profile
            // already covers arbitrary uploads via the existing
            // ProfileScreen flow.
            if state.chooseDifferentHintVisible {
                Text(LocalizedStringKey("body_change_avatar_in_profile_hint"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("oauthAvatarChangeProfileHint")
            }
        }
    }
}
