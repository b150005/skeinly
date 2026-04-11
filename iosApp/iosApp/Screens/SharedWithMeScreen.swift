import SwiftUI
import Shared

struct SharedWithMeScreen: View {
    @Binding var path: NavigationPath
    private let viewModel: SharedWithMeViewModel
    @StateObject private var observer: ViewModelObserver<SharedWithMeState>
    @State private var showError = false

    init(path: Binding<NavigationPath>) {
        self._path = path
        let vm = ViewModelFactory.sharedWithMeViewModel()
        self.viewModel = vm
        let wrapper = KoinHelperKt.wrapSharedWithMeState(flow: vm.state)
        _observer = StateObject(wrappedValue: ViewModelObserver(wrapper: wrapper))
    }

    var body: some View {
        let state = observer.state

        Group {
            if state.isLoading && state.shares.isEmpty {
                ProgressView()
            } else if state.shares.isEmpty {
                ContentUnavailableView(
                    "No Shared Projects",
                    systemImage: "shared.with.you",
                    description: Text("Projects shared with you will appear here.")
                )
            } else {
                List(state.shares, id: \.id) { share in
                    ShareRow(
                        share: share,
                        patternTitle: state.patternTitles[share.patternId] ?? "Untitled",
                        sharerName: state.sharers[share.fromUserId]?.displayName ?? "Unknown",
                        onAccept: {
                            viewModel.onEvent(event: SharedWithMeEventAcceptShare(shareId: share.id))
                        },
                        onDecline: {
                            viewModel.onEvent(event: SharedWithMeEventDeclineShare(shareId: share.id))
                        }
                    )
                    .contentShape(Rectangle())
                    .onTapGesture {
                        path.append(Route.sharedContent(token: nil, shareId: share.id))
                    }
                }
            }
        }
        .navigationTitle("Shared With Me")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: state.error) { _, newError in
            showError = newError != nil
        }
        .alert("Error", isPresented: $showError) {
            Button("OK") { viewModel.onEvent(event: SharedWithMeEventClearError.shared) }
        } message: {
            Text(state.error ?? "")
        }
    }
}

private struct ShareRow: View {
    let share: Share
    let patternTitle: String
    let sharerName: String
    let onAccept: () -> Void
    let onDecline: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(patternTitle)
                .font(.headline)

            HStack {
                Text(sharerName)
                Text("\u{2022}")
                Text(formattedDate)
                Text("\u{2022}")
                Text(share.permission == .fork ? "Fork" : "View")
                if share.status == .pending {
                    Text("\u{2022}")
                    Text("Pending")
                        .foregroundStyle(.orange)
                } else if share.status == .declined {
                    Text("\u{2022}")
                    Text("Declined")
                        .foregroundStyle(.red)
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            if share.status == .pending {
                HStack(spacing: 12) {
                    Button("Accept", action: onAccept)
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                    Button("Decline", action: onDecline)
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var formattedDate: String {
        let date = Date(timeIntervalSince1970: TimeInterval(share.sharedAt.epochSeconds))
        return date.formatted(date: .abbreviated, time: .omitted)
    }
}
