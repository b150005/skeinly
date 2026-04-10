import SwiftUI
import Shared

struct ActivityFeedScreen: View {
    private let viewModel: ActivityFeedViewModel
    @StateObject private var observer: ViewModelObserver<ActivityFeedState>

    init() {
        let vm = ViewModelFactory.activityFeedViewModel()
        self.viewModel = vm
        let wrapper = KoinHelperKt.wrapStateFlow(flow: vm.state) as! FlowWrapper<ActivityFeedState>
        _observer = StateObject(wrappedValue: ViewModelObserver(wrapper: wrapper))
    }

    var body: some View {
        let state = observer.state

        Group {
            if state.isLoading && state.activities.isEmpty {
                ProgressView()
            } else if state.activities.isEmpty {
                ContentUnavailableView(
                    "No Activity Yet",
                    systemImage: "bell.slash",
                    description: Text("Your activity will appear here.")
                )
            } else {
                List(state.activities, id: \.id) { activity in
                    ActivityRow(
                        activity: activity,
                        userName: userName(for: activity, in: state)
                    )
                }
            }
        }
        .navigationTitle("Activity Feed")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func userName(for activity: Activity, in state: ActivityFeedState) -> String {
        let user = state.users[activity.userId]
        return user?.displayName ?? "Someone"
    }
}

private struct ActivityRow: View {
    let activity: Activity
    let userName: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: iconName)
                .foregroundStyle(iconColor)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text("\(userName) \(verbText)")
                    .font(.subheadline)
                Text(formattedDate)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    private var iconName: String {
        switch activity.type {
        case .started: return "plus.circle"
        case .completed: return "checkmark.circle.fill"
        case .shared: return "square.and.arrow.up"
        case .commented: return "text.bubble"
        case .forked: return "arrow.triangle.branch"
        default: return "circle"
        }
    }

    private var iconColor: Color {
        switch activity.type {
        case .started: return .blue
        case .completed: return .green
        case .shared: return .orange
        case .commented: return .purple
        case .forked: return .teal
        default: return .secondary
        }
    }

    private var verbText: String {
        switch activity.type {
        case .started: return "started a new project"
        case .completed: return "completed a project"
        case .shared: return "shared a pattern"
        case .commented: return "commented on a project"
        case .forked: return "forked a pattern"
        default: return "performed an action"
        }
    }

    private var formattedDate: String {
        let date = Date(timeIntervalSince1970: TimeInterval(activity.createdAt.epochSeconds))
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
