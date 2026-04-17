import SwiftUI
import Shared

/// Navigation routes for the app.
enum Route: Hashable {
    case projectDetail(projectId: String)
    case profile
    case settings
    case activityFeed
    case sharedWithMe
    case sharedContent(token: String?, shareId: String?)
    case discovery
    case patternLibrary
    case patternEdit(patternId: String?)
    case chartViewer(patternId: String)
    case symbolGallery

    // Hashable conformance for sharedContent with optionals
    func hash(into hasher: inout Hasher) {
        switch self {
        case .projectDetail(let id):
            hasher.combine("projectDetail")
            hasher.combine(id)
        case .profile:
            hasher.combine("profile")
        case .settings:
            hasher.combine("settings")
        case .activityFeed:
            hasher.combine("activityFeed")
        case .sharedWithMe:
            hasher.combine("sharedWithMe")
        case .sharedContent(let token, let shareId):
            hasher.combine("sharedContent")
            hasher.combine(token)
            hasher.combine(shareId)
        case .discovery:
            hasher.combine("discovery")
        case .patternLibrary:
            hasher.combine("patternLibrary")
        case .patternEdit(let patternId):
            hasher.combine("patternEdit")
            hasher.combine(patternId)
        case .chartViewer(let patternId):
            hasher.combine("chartViewer")
            hasher.combine(patternId)
        case .symbolGallery:
            hasher.combine("symbolGallery")
        }
    }
}

/// Root view that handles onboarding and auth-gated navigation.
struct AppRootView: View {
    @StateObject private var authHolder: ScopedViewModel<AuthViewModel, AuthUiState>
    @State private var path = NavigationPath()
    @State private var pendingDeepLinkToken: String?
    @State private var hasSeenOnboarding: Bool

    init() {
        let vm = ViewModelFactory.authViewModel()
        let wrapper = KoinHelperKt.wrapAuthState(flow: vm.state)
        _authHolder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
        _hasSeenOnboarding = State(initialValue: KoinHelperKt.isOnboardingCompleted())
    }

    var body: some View {
        let authState = authHolder.state.authState
        let isConfigured = SupabaseConfig.shared.isConfigured

        // Onboarding is a UX gate only. Auth enforcement via authState cannot be bypassed
        // by clearing the onboarding preference.
        Group {
            if !hasSeenOnboarding {
                OnboardingScreen {
                    hasSeenOnboarding = true
                }
            } else if !isConfigured {
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
                LoginScreen(viewModel: authHolder.viewModel)
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
        case .settings:
            SettingsScreen()
        case .activityFeed:
            ActivityFeedScreen()
        case .sharedWithMe:
            SharedWithMeScreen(path: $path)
        case .sharedContent(let token, let shareId):
            SharedContentScreen(token: token, shareId: shareId, path: $path)
        case .discovery:
            DiscoveryScreen(path: $path)
        case .patternLibrary:
            PatternLibraryScreen(path: $path)
        case .patternEdit(let patternId):
            PatternEditScreen(patternId: patternId, path: $path)
        case .chartViewer(let patternId):
            StructuredChartViewerScreen(patternId: patternId)
        case .symbolGallery:
            SymbolGalleryScreen()
        }
    }

    func handleDeepLink(url: URL) {
        guard url.scheme == "knitnote",
              url.host == "share",
              let token = url.pathComponents.dropFirst().first,
              isValidShareToken(token) else {
            return
        }

        let authState = authHolder.state.authState
        if authState is AuthStateAuthenticated {
            path.append(Route.sharedContent(token: token, shareId: nil))
        } else {
            pendingDeepLinkToken = token
        }
    }

}
