import SwiftUI
import Shared

struct ActivityFeedScreen: View {
    @StateObject private var holder: ScopedViewModel<ActivityFeedViewModel, ActivityFeedState>

    init() {
        let vm = ViewModelFactory.activityFeedViewModel()
        let wrapper = KoinHelperKt.wrapActivityFeedState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state

        ZStack {
            if state.isLoading && state.activities.isEmpty {
                ProgressView()
            } else if state.activities.isEmpty {
                ContentUnavailableView(
                    "state_no_activity",
                    systemImage: "bell.slash",
                    description: Text("body_no_activity")
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
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("activityFeedScreen")
        .navigationTitle(LocalizedStringKey("title_activity_feed"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func userName(for activity: Activity, in state: ActivityFeedState) -> String {
        let user = state.users[activity.userId]
        return user?.displayName ?? String(localized: "label_someone")
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
                Text(headline)
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
        case .created: return "plus.circle.fill"
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
        case .created: return .indigo
        default: return .secondary
        }
    }

    private var headline: String {
        let key: String
        switch activity.type {
        case .started: key = "label_activity_started_by"
        case .completed: key = "label_activity_completed_by"
        case .shared: key = "label_activity_shared_by"
        case .commented: key = "label_activity_commented_by"
        case .forked: key = "label_activity_forked_by"
        case .created: key = "label_activity_created_by"
        // Kotlin `ActivityType` is bridged as a non-frozen enum, so Swift
        // requires a default branch. Fall through to the "started" copy;
        // unknown types should not reach users because serialization
        // rejects values outside the enum.
        default: key = "label_activity_started_by"
        }
        let format = NSLocalizedString(key, comment: "")
        return String(format: format, userName)
    }

    private var formattedDate: String {
        let date = Date(timeIntervalSince1970: TimeInterval(activity.createdAt.epochSeconds))
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
