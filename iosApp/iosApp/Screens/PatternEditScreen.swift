import SwiftUI
import Shared

struct PatternEditScreen: View {
    let patternId: String?
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<PatternEditViewModel, PatternEditState>
    @State private var showError = false

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
        .navigationTitle(patternId != nil ? "Edit Pattern" : "New Pattern")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    viewModel.onEvent(event: PatternEditEventSave.shared)
                }
                .disabled(state.title.trimmingCharacters(in: .whitespaces).isEmpty || state.isSaving)
            }
        }
        .task {
            let saveFlow = KoinHelperKt.wrapPatternEditSaveSuccess(flow: viewModel.saveSuccess)
            let closeable = saveFlow.collect { _ in
                Task { @MainActor in
                    path.removeLast()
                }
            }
            _ = closeable
        }
        .onChange(of: state.error) { _, newError in
            showError = newError != nil
        }
        .alert("Error", isPresented: $showError) {
            Button("OK") { viewModel.onEvent(event: PatternEditEventClearError.shared) }
        } message: {
            Text(state.error ?? "")
        }
    }

    @ViewBuilder
    private func formContent(_ state: PatternEditState) -> some View {
        Form {
            Section("Title") {
                TextField("Pattern title", text: Binding(
                    get: { state.title },
                    set: { viewModel.onEvent(event: PatternEditEventUpdateTitle(title: $0)) }
                ))
            }

            Section("Description") {
                TextEditor(text: Binding(
                    get: { state.description_ },
                    set: { viewModel.onEvent(event: PatternEditEventUpdateDescription(description: $0)) }
                ))
                .frame(minHeight: 80)
            }

            Section("Details") {
                difficultyPicker(state)

                TextField("Gauge", text: Binding(
                    get: { state.gauge },
                    set: { viewModel.onEvent(event: PatternEditEventUpdateGauge(gauge: $0)) }
                ), prompt: Text("20 sts = 4 in"))

                TextField("Yarn Info", text: Binding(
                    get: { state.yarnInfo },
                    set: { viewModel.onEvent(event: PatternEditEventUpdateYarnInfo(yarnInfo: $0)) }
                ), prompt: Text("Worsted weight, 100% merino"))

                TextField("Needle Size", text: Binding(
                    get: { state.needleSize },
                    set: { viewModel.onEvent(event: PatternEditEventUpdateNeedleSize(needleSize: $0)) }
                ), prompt: Text("US 7 / 4.5mm"))
            }

            Section("Visibility") {
                visibilityPicker(state)
            }
        }
    }

    @ViewBuilder
    private func difficultyPicker(_ state: PatternEditState) -> some View {
        Picker("Difficulty", selection: Binding(
            get: { DifficultyOption.from(state.difficulty) },
            set: { viewModel.onEvent(event: PatternEditEventUpdateDifficulty(difficulty: $0.toDifficulty())) }
        )) {
            ForEach(DifficultyOption.allCases, id: \.self) { option in
                Text(option.label).tag(option)
            }
        }
    }

    @ViewBuilder
    private func visibilityPicker(_ state: PatternEditState) -> some View {
        Picker("Visibility", selection: Binding(
            get: { VisibilityOption.from(state.visibility) },
            set: { viewModel.onEvent(event: PatternEditEventUpdateVisibility(visibility: $0.toVisibility())) }
        )) {
            ForEach(VisibilityOption.allCases, id: \.self) { option in
                Text(option.label).tag(option)
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

    var label: String {
        switch self {
        case .none: return "None"
        case .beginner: return "Beginner"
        case .intermediate: return "Intermediate"
        case .advanced: return "Advanced"
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

    var label: String {
        switch self {
        case .privateVisibility: return "Private"
        case .sharedVisibility: return "Shared"
        case .publicVisibility: return "Public"
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
