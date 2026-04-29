import SwiftUI
import Shared

struct PatternEditScreen: View {
    let patternId: String?
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<PatternEditViewModel, PatternEditState>
    @State private var showError = false
    @State private var saveCloseable: Closeable?

    private var viewModel: PatternEditViewModel { holder.viewModel }

    init(patternId: String?, path: Binding<NavigationPath>) {
        self.patternId = patternId
        self._path = path
        let vm = ViewModelFactory.patternEditViewModel(patternId: patternId)
        let wrapper = KoinHelperKt.wrapPatternEditState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state

        Group {
            if state.isLoading {
                ProgressView()
            } else {
                formContent(state)
            }
        }
        // `.navigationTitle` is conditional — the SwiftUI literal-promotion table in
        // docs/{en,ja}/i18n-convention.md requires an explicit LocalizedStringKey wrap
        // to prevent overload resolution from silently dropping to the String overload.
        .navigationTitle(
            patternId != nil
                ? LocalizedStringKey("title_edit_pattern")
                : LocalizedStringKey("title_new_pattern")
        )
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(LocalizedStringKey("action_save")) {
                    viewModel.onEvent(event: PatternEditEventSave.shared)
                }
                .disabled(state.title.trimmingCharacters(in: .whitespaces).isEmpty || state.isSaving)
            }
        }
        .task {
            let saveFlow = KoinHelperKt.wrapPatternEditSaveSuccess(flow: viewModel.saveSuccess)
            saveCloseable = saveFlow.collect { _ in
                Task { @MainActor in
                    path.removeLast()
                }
            }
        }
        .onDisappear {
            saveCloseable?.close()
            saveCloseable = nil
        }
        .onChange(of: state.error != nil) { _, hasError in
            showError = hasError
        }
        // `.alert` title sits in the "needs explicit wrap" column of the literal-
        // promotion table — bare String would skip localization.
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button(LocalizedStringKey("action_ok")) { viewModel.onEvent(event: PatternEditEventClearError.shared) }
        } message: {
            Text(state.error?.localizedString ?? "")
        }
    }

    @ViewBuilder
    private func formContent(_ state: PatternEditState) -> some View {
        Form {
            Section(LocalizedStringKey("label_pattern_title")) {
                TextField(LocalizedStringKey("label_pattern_title"), text: Binding(
                    get: { state.title },
                    set: { viewModel.onEvent(event: PatternEditEventUpdateTitle(title: $0)) }
                ))
            }

            Section(LocalizedStringKey("label_description_optional")) {
                TextEditor(text: Binding(
                    get: { state.description_ },
                    set: { viewModel.onEvent(event: PatternEditEventUpdateDescription(description: $0)) }
                ))
                .frame(minHeight: 80)
            }

            Section(LocalizedStringKey("label_details")) {
                difficultyPicker(state)

                TextField(LocalizedStringKey("label_gauge"), text: Binding(
                    get: { state.gauge },
                    set: { viewModel.onEvent(event: PatternEditEventUpdateGauge(gauge: $0)) }
                ), prompt: Text(LocalizedStringKey("hint_gauge_example")))

                TextField(LocalizedStringKey("label_yarn_info"), text: Binding(
                    get: { state.yarnInfo },
                    set: { viewModel.onEvent(event: PatternEditEventUpdateYarnInfo(yarnInfo: $0)) }
                ), prompt: Text(LocalizedStringKey("hint_yarn_info_example")))

                TextField(LocalizedStringKey("label_needle_size"), text: Binding(
                    get: { state.needleSize },
                    set: { viewModel.onEvent(event: PatternEditEventUpdateNeedleSize(needleSize: $0)) }
                ), prompt: Text(LocalizedStringKey("hint_needle_size_example")))
            }

            Section(LocalizedStringKey("label_visibility")) {
                visibilityPicker(state)
            }
        }
    }

    @ViewBuilder
    private func difficultyPicker(_ state: PatternEditState) -> some View {
        Picker(LocalizedStringKey("label_difficulty"), selection: Binding(
            get: { DifficultyOption.from(state.difficulty) },
            set: { viewModel.onEvent(event: PatternEditEventUpdateDifficulty(difficulty: $0.toDifficulty())) }
        )) {
            ForEach(DifficultyOption.allCases, id: \.self) { option in
                Text(option.labelKey).tag(option)
            }
        }
    }

    @ViewBuilder
    private func visibilityPicker(_ state: PatternEditState) -> some View {
        Picker(LocalizedStringKey("label_visibility"), selection: Binding(
            get: { VisibilityOption.from(state.visibility) },
            set: { viewModel.onEvent(event: PatternEditEventUpdateVisibility(visibility: $0.toVisibility())) }
        )) {
            ForEach(VisibilityOption.allCases, id: \.self) { option in
                Text(option.labelKey).tag(option)
            }
        }
    }
}

// MARK: - Difficulty Option (Swift-friendly wrapper)

private enum DifficultyOption: CaseIterable {
    case none
    case beginner
    case intermediate
    case advanced

    // LocalizedStringKey so Text(option.labelKey) resolves via Localizable.xcstrings.
    // A bare String here would skip localization.
    var labelKey: LocalizedStringKey {
        switch self {
        case .none: return LocalizedStringKey("label_difficulty_none")
        case .beginner: return LocalizedStringKey("label_difficulty_beginner")
        case .intermediate: return LocalizedStringKey("label_difficulty_intermediate")
        case .advanced: return LocalizedStringKey("label_difficulty_advanced")
        }
    }

    func toDifficulty() -> Difficulty? {
        switch self {
        case .none: return nil
        case .beginner: return .beginner
        case .intermediate: return .intermediate
        case .advanced: return .advanced
        }
    }

    static func from(_ difficulty: Difficulty?) -> DifficultyOption {
        guard let difficulty else { return .none }
        switch difficulty {
        case .beginner: return .beginner
        case .intermediate: return .intermediate
        case .advanced: return .advanced
        default: return .none
        }
    }
}

// MARK: - Visibility Option (Swift-friendly wrapper)

private enum VisibilityOption: CaseIterable {
    case privateVisibility
    case sharedVisibility
    case publicVisibility

    var labelKey: LocalizedStringKey {
        switch self {
        case .privateVisibility: return LocalizedStringKey("label_visibility_private")
        case .sharedVisibility: return LocalizedStringKey("label_visibility_shared")
        case .publicVisibility: return LocalizedStringKey("label_visibility_public")
        }
    }

    func toVisibility() -> Shared.Visibility {
        switch self {
        case .privateVisibility: return .private_
        case .sharedVisibility: return .shared
        case .publicVisibility: return .public_
        }
    }

    static func from(_ visibility: Shared.Visibility) -> VisibilityOption {
        switch visibility {
        case .private_: return .privateVisibility
        case .shared: return .sharedVisibility
        case .public_: return .publicVisibility
        default: return .privateVisibility
        }
    }
}
