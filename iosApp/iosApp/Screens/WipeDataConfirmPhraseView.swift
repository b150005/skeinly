import SwiftUI
import Shared

/// Phase 27.2 (ADR-023 §UX) — SwiftUI mirror of the Compose
/// `WipeDataConfirmPhraseScreen`. Hosts the data-wipe flow as a single
/// pushed `NavigationStack` destination with two visual phases driven
/// by `WipeDataState.step`:
///
/// - `Modal` → presents `WipeDataExplanationSheet` as a `.sheet` over
///   a blank backdrop. Sheet dismissal routes to `onCancel` (pop nav).
/// - `PhraseEntry` → renders an inline `Form` with the phrase text
///   field and red destructive submit button.
///
/// The required phrase is captured once at view-init from the active
/// locale via `NSLocalizedString("phrase_wipe_data_confirm", ...)` and
/// passed to `ViewModelFactory.wipeDataViewModel(requiredPhrase:)` so
/// the VM's phrase-matching gate stays consistent with the helper text
/// rendered above the field. Mid-flow locale change is not supported
/// (matches the Compose side; ADR §UX).
///
/// On successful submit the screen calls
/// `KoinHelperKt.notifyWipeCompleted()` so the singleton
/// `WipeCompletionNotifier` fires — which the PatternLibrary VM
/// observes to flip its 8 s banner state — then invokes `onWipeCompleted`
/// to navigate the user back to Pattern Library.
struct WipeDataConfirmPhraseView: View {
    let onCancel: () -> Void
    let onWipeCompleted: () -> Void
    @StateObject private var holder: ScopedViewModel<WipeDataViewModel, WipeDataState>
    @State private var navCloseable: Closeable?
    /// Captured at view-init via `NSLocalizedString` so the same string
    /// is used both for the VM's phrase-match gate AND for any UI that
    /// needs to show the phrase verbatim. Locked in for the view's
    /// lifetime — locale switches mid-flow do not re-resolve.
    private let requiredPhrase: String

    private var viewModel: WipeDataViewModel { holder.viewModel }

    init(
        onCancel: @escaping () -> Void,
        onWipeCompleted: @escaping () -> Void
    ) {
        self.onCancel = onCancel
        self.onWipeCompleted = onWipeCompleted
        let phrase = NSLocalizedString(
            "phrase_wipe_data_confirm",
            comment: "Phrase the user must type verbatim to confirm data wipe"
        )
        self.requiredPhrase = phrase
        let vm = ViewModelFactory.wipeDataViewModel(requiredPhrase: phrase)
        let wrapper = KoinHelperKt.wrapWipeDataState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state

        ZStack {
            // The backdrop differs by step: Modal renders an empty
            // ZStack underneath the sheet; PhraseEntry renders the
            // form inline.
            if state.step == WipeDataStep.modal {
                Color(.systemBackground)
                    .ignoresSafeArea()
            } else {
                phraseEntryContent(state: state)
            }
        }
        .navigationTitle(LocalizedStringKey("title_wipe_data_confirm_phrase"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("wipeDataConfirmPhraseScreen")
        .sheet(
            isPresented: Binding(
                get: { state.step == WipeDataStep.modal },
                set: { presenting in
                    // SwiftUI flips this to false on swipe-down /
                    // background tap. Translate that into the same
                    // "Keep my data" signal — bail the flow entirely.
                    if !presenting && state.step == WipeDataStep.modal {
                        onCancel()
                    }
                }
            )
        ) {
            WipeDataExplanationSheet(
                onContinue: {
                    viewModel.onEvent(event: WipeDataEventContinue.shared)
                },
                onKeep: onCancel
            )
        }
        .alert(
            LocalizedStringKey("title_error"),
            isPresented: errorPresented(state: state),
            actions: {
                Button {
                    viewModel.onEvent(event: WipeDataEventClearError.shared)
                } label: {
                    Text(LocalizedStringKey("action_ok"))
                }
            },
            message: {
                Text(localizedErrorBody(state: state))
            }
        )
        .task {
            // Per Phase 32.2 / 36.5 iOS Closeable leak audit pattern,
            // close any prior subscription before re-subscribing on
            // .task re-fire.
            navCloseable?.close()
            let flow = KoinHelperKt.wrapWipeDataNavEvents(flow: viewModel.navEvents)
            navCloseable = flow.collect { event in
                Task { @MainActor in
                    if event is WipeDataNavEventWipeCompleted {
                        // Fire the PatternLibrary banner notifier first
                        // — once the path pops, the PatternLibrary VM
                        // re-attaches its state collector and picks up
                        // the visible=true flip.
                        try? await KoinHelperKt.notifyWipeCompleted()
                        onWipeCompleted()
                    }
                }
            }
        }
        .onDisappear {
            navCloseable?.close()
            navCloseable = nil
        }
    }

    @ViewBuilder
    private func phraseEntryContent(state: WipeDataState) -> some View {
        Form {
            Section {
                TextField("", text: phraseBinding(state: state))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled(true)
                    .disabled(state.isSubmitting)
                    .accessibilityIdentifier("wipeDataPhraseField")
            } header: {
                Text("body_wipe_data_confirm_phrase_helper")
                    .textCase(nil)
                    .font(.body)
                    .foregroundStyle(.primary)
            }

            Section {
                Button(role: .destructive) {
                    viewModel.onEvent(event: WipeDataEventSubmit.shared)
                } label: {
                    HStack {
                        Spacer()
                        if state.isSubmitting {
                            ProgressView()
                                .tint(Color.white)
                        } else {
                            Text("action_wipe_data_submit")
                                .fontWeight(.semibold)
                                .foregroundStyle(Color.white)
                        }
                        Spacer()
                    }
                }
                .listRowBackground(
                    state.submitEnabled(requiredPhrase: requiredPhrase)
                        ? Color.red
                        : Color.red.opacity(0.4)
                )
                .disabled(!state.submitEnabled(requiredPhrase: requiredPhrase))
                .accessibilityIdentifier("wipeDataSubmitButton")
            }
        }
    }

    private func phraseBinding(state: WipeDataState) -> Binding<String> {
        Binding(
            get: { state.phraseInput },
            set: { newValue in
                viewModel.onEvent(event: WipeDataEventUpdatePhrase(value: newValue))
            }
        )
    }

    private func errorPresented(state: WipeDataState) -> Binding<Bool> {
        Binding(
            get: { state.error != nil },
            set: { presenting in
                if !presenting && state.error != nil {
                    viewModel.onEvent(event: WipeDataEventClearError.shared)
                }
            }
        )
    }

    /// Phase 26.5/26.6 precedent: ErrorMessage is a sealed Kotlin type
    /// without a built-in Swift bridge for its localized form. Surface
    /// a generic fallback for alpha — the per-arm Tech Debt fix tracks
    /// the same "ViewModel error i18n complete sweep" item documented
    /// in the Tech Debt Backlog.
    private func localizedErrorBody(state: WipeDataState) -> LocalizedStringKey {
        guard state.error != nil else { return LocalizedStringKey("") }
        return LocalizedStringKey("error_generic")
    }
}

/// Phase 27.2 (ADR-023 §UX) — preservation-matrix sheet shown as Step
/// 1 of the wipe flow. Surfaced via `.sheet` from
/// `WipeDataConfirmPhraseView`; provides "Continue" (destructive) and
/// "Keep my data" (cancel) CTAs.
struct WipeDataExplanationSheet: View {
    let onContinue: () -> Void
    let onKeep: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    matrixSection(
                        titleKey: "subtitle_wipe_data_explanation_preserved",
                        bodyKey: "body_wipe_data_preserved_list",
                        identifierSuffix: "Preserved"
                    )
                    matrixSection(
                        titleKey: "subtitle_wipe_data_explanation_deleted",
                        bodyKey: "body_wipe_data_deleted_list",
                        identifierSuffix: "Deleted"
                    )
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
            }
            .navigationTitle(LocalizedStringKey("title_wipe_data_explanation"))
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("wipeDataExplanationSheet")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        onKeep()
                    } label: {
                        Text("action_wipe_data_keep")
                    }
                    .accessibilityIdentifier("wipeDataKeepButton")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(role: .destructive) {
                        onContinue()
                    } label: {
                        Text("action_wipe_data_continue")
                    }
                    .accessibilityIdentifier("wipeDataContinueButton")
                }
            }
        }
    }

    @ViewBuilder
    private func matrixSection(
        titleKey: LocalizedStringKey,
        bodyKey: LocalizedStringKey,
        identifierSuffix: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(titleKey)
                .font(.headline)
                .foregroundStyle(.primary)
            Text(bodyKey)
                .font(.body)
                .foregroundStyle(.secondary)
        }
        .accessibilityIdentifier("wipeDataMatrixSection\(identifierSuffix)")
    }
}
