import SwiftUI
import Shared

struct SharedContentScreen: View {
    let token: String?
    let shareId: String?
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<SharedContentViewModel, SharedContentState>
    @State private var showError = false
    @State private var forkedCloseable: Closeable?

    private var viewModel: SharedContentViewModel { holder.viewModel }

    init(token: String?, shareId: String?, path: Binding<NavigationPath>) {
        self.token = token
        self.shareId = shareId
        self._path = path
        let vm = ViewModelFactory.sharedContentViewModel(token: token, shareId: shareId)
        let wrapper = KoinHelperKt.wrapSharedContentState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state

        // A bare state-branch `Group` is layout-transparent and propagates
        // accessibility modifiers to each child branch independently, breaking
        // the `sharedContentScreen` landmark query for XCUITest (per the 33.1.6
        // HIGH convention). Wrap in ZStack so the accessibilityElement attaches
        // to a single concrete container.
        ZStack {
            if state.isLoading {
                ProgressView()
            } else if let pattern = state.pattern {
                patternContent(pattern: pattern, state: state)
            } else if let error = state.error {
                ContentUnavailableView(
                    LocalizedStringKey("state_could_not_load"),
                    systemImage: "exclamationmark.triangle",
                    description: Text(error.localizedString)
                )
            } else {
                Text(LocalizedStringKey("state_pattern_not_found"))
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sharedContentScreen")
        .navigationTitle(LocalizedStringKey("title_shared_pattern"))
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: state.error != nil) { _, hasError in
            showError = hasError
        }
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button(LocalizedStringKey("action_ok")) {
                viewModel.onEvent(event: SharedContentEventClearError.shared)
            }
        } message: {
            Text(state.error?.localizedString ?? "")
        }
        .task {
            // `.task { }` re-fires on every view re-appearance. Close any prior
            // subscription before replacing to avoid leaking one Closeable per
            // background/foreground cycle.
            forkedCloseable?.close()
            forkedCloseable = nil
            let forkedFlow = KoinHelperKt.wrapForkedProjectIdFlow(flow: viewModel.forkedProjectId)
            forkedCloseable = forkedFlow.collect { projectId in
                Task { @MainActor in
                    path = NavigationPath()
                    path.append(Route.projectDetail(projectId: projectId as String))
                }
            }
        }
        .onDisappear {
            forkedCloseable?.close()
            forkedCloseable = nil
        }
    }

    @ViewBuilder
    private func patternContent(pattern: Pattern, state: SharedContentState) -> some View {
        List {
            Section {
                Text(pattern.title)
                    .font(.title2)
                    .fontWeight(.bold)

                if let description = pattern.description_, !description.isEmpty {
                    Text(description)
                        .font(.body)
                        .foregroundStyle(.secondary)
                }
            }

            Section(LocalizedStringKey("label_details")) {
                if let difficulty = pattern.difficulty {
                    DetailRow(
                        label: LocalizedStringKey("label_difficulty"),
                        value: difficultyText(difficulty)
                    )
                }
                if let gauge = pattern.gauge, !gauge.isEmpty {
                    DetailRow(label: LocalizedStringKey("label_gauge"), value: gauge)
                }
                if let yarnInfo = pattern.yarnInfo, !yarnInfo.isEmpty {
                    DetailRow(label: LocalizedStringKey("label_yarn"), value: yarnInfo)
                }
                if let needleSize = pattern.needleSize, !needleSize.isEmpty {
                    DetailRow(label: LocalizedStringKey("label_needle_size"), value: needleSize)
                }
                if state.projectCount > 0 {
                    DetailRow(
                        label: LocalizedStringKey("label_projects"),
                        value: "\(state.projectCount)"
                    )
                }
            }

            Section {
                if state.share?.permission == .fork {
                    Button {
                        viewModel.onEvent(event: SharedContentEventFork.shared)
                    } label: {
                        HStack {
                            if state.isForkInProgress {
                                ProgressView()
                            }
                            Text(LocalizedStringKey("action_fork_to_projects"))
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .accessibilityIdentifier("forkButton")
                    .buttonStyle(.borderedProminent)
                    .disabled(state.isForkInProgress)
                } else {
                    Text(LocalizedStringKey("state_view_only_share"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func difficultyText(_ difficulty: Difficulty) -> String {
        // `Difficulty` is a Kotlin enum bridged to Swift as an NSEnum-like type,
        // so Swift requires a `default` branch even though the 3 cases are
        // exhaustive today. Fall back to the raw enum case name (locale-neutral,
        // non-empty, self-documenting at runtime) so a future Kotlin enum
        // addition without a matching Swift switch update surfaces visibly
        // rather than rendering as a blank cell.
        switch difficulty {
        case .beginner: return NSLocalizedString("label_difficulty_beginner", comment: "")
        case .intermediate: return NSLocalizedString("label_difficulty_intermediate", comment: "")
        case .advanced: return NSLocalizedString("label_difficulty_advanced", comment: "")
        default: return difficulty.name
        }
    }
}

private struct DetailRow: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
        }
    }
}
