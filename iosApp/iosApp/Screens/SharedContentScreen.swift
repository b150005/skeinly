import SwiftUI
import Shared

struct SharedContentScreen: View {
    let token: String?
    let shareId: String?
    @Binding var path: NavigationPath
    private let viewModel: SharedContentViewModel
    @StateObject private var observer: ViewModelObserver<SharedContentState>
    @State private var showError = false

    init(token: String?, shareId: String?, path: Binding<NavigationPath>) {
        self.token = token
        self.shareId = shareId
        self._path = path
        let vm = ViewModelFactory.sharedContentViewModel(token: token, shareId: shareId)
        self.viewModel = vm
        let wrapper = KoinHelperKt.wrapSharedContentState(flow: vm.state)
        _observer = StateObject(wrappedValue: ViewModelObserver(wrapper: wrapper))
    }

    var body: some View {
        let state = observer.state

        Group {
            if state.isLoading {
                ProgressView()
            } else if let pattern = state.pattern {
                patternContent(pattern: pattern, state: state)
            } else if let error = state.error {
                ContentUnavailableView(
                    "Could not load",
                    systemImage: "exclamationmark.triangle",
                    description: Text(error)
                )
            }
        }
        .navigationTitle("Shared Pattern")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: state.error) { _, newError in
            showError = newError != nil
        }
        .alert("Error", isPresented: $showError) {
            Button("OK") { viewModel.onEvent(event: SharedContentEventClearError.shared) }
        } message: {
            Text(state.error ?? "")
        }
        .onReceive(NotificationCenter.default.publisher(for: .init("forkedProject"))) { _ in
            // Handled via observer below
        }
        .task {
            // Observe forked project channel
            let forkedFlow = KoinHelperKt.wrapForkedProjectIdFlow(flow: viewModel.forkedProjectId)
            let closeable = forkedFlow.collect { projectId in
                Task { @MainActor in
                    // Pop to root and navigate to the forked project
                    path = NavigationPath()
                    path.append(Route.projectDetail(projectId: projectId as String))
                }
            }
            // Keep closeable alive for the task duration — will be cancelled on view disappear
            _ = closeable
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

            Section("Details") {
                if let difficulty = pattern.difficulty {
                    DetailRow(label: "Difficulty", value: difficultyText(difficulty))
                }
                if let gauge = pattern.gauge, !gauge.isEmpty {
                    DetailRow(label: "Gauge", value: gauge)
                }
                if let yarnInfo = pattern.yarnInfo, !yarnInfo.isEmpty {
                    DetailRow(label: "Yarn", value: yarnInfo)
                }
                if let needleSize = pattern.needleSize, !needleSize.isEmpty {
                    DetailRow(label: "Needle Size", value: needleSize)
                }
                if state.projectCount > 0 {
                    DetailRow(label: "Projects", value: "\(state.projectCount)")
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
                            Text("Fork to My Projects")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(state.isForkInProgress)
                } else {
                    Text("View only — forking is not permitted for this share")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func difficultyText(_ difficulty: Difficulty) -> String {
        switch difficulty {
        case .beginner: return "Beginner"
        case .intermediate: return "Intermediate"
        case .advanced: return "Advanced"
        default: return "Unknown"
        }
    }
}

private struct DetailRow: View {
    let label: String
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
