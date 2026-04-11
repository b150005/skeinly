import SwiftUI
import Shared

/// Navigation routes for the app.
enum Route: Hashable {
    case projectDetail(projectId: String)
    case profile
    case activityFeed
    case sharedWithMe
    case sharedContent(token: String?, shareId: String?)

    // Hashable conformance for sharedContent with optionals
    func hash(into hasher: inout Hasher) {
        switch self {
        case .projectDetail(let id):
            hasher.combine("projectDetail")
            hasher.combine(id)
        case .profile:
            hasher.combine("profile")
        case .activityFeed:
            hasher.combine("activityFeed")
        case .sharedWithMe:
            hasher.combine("sharedWithMe")
        case .sharedContent(let token, let shareId):
            hasher.combine("sharedContent")
            hasher.combine(token)
            hasher.combine(shareId)
        }
    }
}

/// Root view that handles auth-gated navigation.
struct AppRootView: View {
    let authViewModel: AuthViewModel
    @StateObject private var authObserver: ViewModelObserver<AuthUiState>
    @State private var path = NavigationPath()
    @State private var pendingDeepLinkToken: String?

    init() {
        let vm = ViewModelFactory.authViewModel()
        self.authViewModel = vm
        let wrapper = KoinHelperKt.wrapStateFlow(flow: vm.state) as! FlowWrapper<AuthUiState>
        _authObserver = StateObject(wrappedValue: ViewModelObserver(wrapper: wrapper))
    }

    var body: some View {
        let authState = authObserver.state.authState
        let isConfigured = SupabaseConfig.shared.isConfigured

        Group {
            if !isConfigured {
                // Local-only mode: skip auth
                NavigationStack(path: $path) {
                    ProjectListScreen(path: $path)
                        .navigationDestination(for: Route.self) { route in
                            destinationView(for: route)
                        }
                }
            } else if authState is AuthStateAuthenticated {
                NavigationStack(path: $path) {
                    ProjectListScreen(path: $path)
                        .navigationDestination(for: Route.self) { route in
                            destinationView(for: route)
                        }
                }
                .onAppear {
                    if let token = pendingDeepLinkToken {
                        pendingDeepLinkToken = nil
                        path.append(Route.sharedContent(token: token, shareId: nil))
                    }
                }
            } else if authState is AuthStateLoading {
                ProgressView("Loading...")
            } else {
                LoginScreen(viewModel: authViewModel)
            }
        }
        .onOpenURL { url in
            handleDeepLink(url: url)
        }
    }

    @ViewBuilder
    private func destinationView(for route: Route) -> some View {
        switch route {
        case .projectDetail(let projectId):
            ProjectDetailScreen(projectId: projectId, path: $path)
        case .profile:
            ProfileScreen()
        case .activityFeed:
            ActivityFeedScreen()
        case .sharedWithMe:
            SharedWithMeScreen(path: $path)
        case .sharedContent(let token, let shareId):
            SharedContentScreen(token: token, shareId: shareId, path: $path)
        }
    }

    func handleDeepLink(url: URL) {
        guard url.scheme == "knitnote",
              url.host == "share",
              let token = url.pathComponents.dropFirst().first,
              isValidShareToken(token) else {
            return
        }

        let authState = authObserver.state.authState
        if authState is AuthStateAuthenticated {
            path.append(Route.sharedContent(token: token, shareId: nil))
        } else {
            pendingDeepLinkToken = token
        }
    }

}
