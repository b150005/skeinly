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
    case chartViewer(patternId: String, projectId: String?)
    case chartEditor(patternId: String)
    case chartHistory(patternId: String)
    case chartDiff(baseRevisionId: String?, targetRevisionId: String)
    case symbolGallery
    case pullRequestList(defaultFilter: PullRequestFilter)
    case pullRequestDetail(prId: String)
    case chartConflictResolution(prId: String)

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
        case .chartViewer(let patternId, let projectId):
            hasher.combine("chartViewer")
            hasher.combine(patternId)
            hasher.combine(projectId)
        case .chartEditor(let patternId):
            hasher.combine("chartEditor")
            hasher.combine(patternId)
        case .chartHistory(let patternId):
            hasher.combine("chartHistory")
            hasher.combine(patternId)
        case .chartDiff(let baseRevisionId, let targetRevisionId):
            hasher.combine("chartDiff")
            hasher.combine(baseRevisionId)
            hasher.combine(targetRevisionId)
        case .symbolGallery:
            hasher.combine("symbolGallery")
        case .pullRequestList(let defaultFilter):
            hasher.combine("pullRequestList")
            hasher.combine(defaultFilter)
        case .pullRequestDetail(let prId):
            hasher.combine("pullRequestDetail")
            hasher.combine(prId)
        case .chartConflictResolution(let prId):
            hasher.combine("chartConflictResolution")
            hasher.combine(prId)
        }
    }
}

/// Root view that handles onboarding and auth-gated navigation.
struct AppRootView: View {
    @StateObject private var authHolder: ScopedViewModel<AuthViewModel, AuthUiState>
    @State private var path = NavigationPath()
    @State private var pendingDeepLinkToken: String?
    @State private var hasSeenOnboarding: Bool
    // Tests inject `-local_only_mode true` via NSUserDefaults launch
    // arguments (mirroring `-has_seen_onboarding`) so ProjectListScreen
    // renders without forcing a real Supabase login. Without this gate,
    // any build that wires up real SUPABASE_URL — including ci.yml's
    // XCUITest job — would route the test simulator to LoginScreen and
    // every `emptyStateLabel` / `createProjectFab` assertion would fail.
    // Captured once at init time because launch args are immutable for
    // the lifetime of the process; reading from `body` would re-query
    // UserDefaults on every render and would not participate in
    // SwiftUI's state-tracking either way.
    @State private var localOnlyMode: Bool

    init() {
        let vm = ViewModelFactory.authViewModel()
        let wrapper = KoinHelperKt.wrapAuthState(flow: vm.state)
        _authHolder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
        _hasSeenOnboarding = State(initialValue: KoinHelperKt.isOnboardingCompleted())
        _localOnlyMode = State(initialValue: UserDefaults.standard.bool(forKey: "local_only_mode"))
    }

    var body: some View {
        let authState = authHolder.state.authState
        let isConfigured = SupabaseConfig.shared.isConfigured && !localOnlyMode

        // Onboarding is a UX gate only. Auth enforcement via authState cannot be bypassed
        // by clearing the onboarding preference.
        Group {
            if !hasSeenOnboarding {
                // Phase 39.3 (ADR-015 §6) — pre-router screens declare
                // their own `.trackScreen()` since they don't pass through
                // `destinationView(for:)`.
                OnboardingScreen {
                    hasSeenOnboarding = true
                }
                .trackScreen(.onboarding)
            } else if !isConfigured {
                // Local-only mode: skip auth
                NavigationStack(path: $path) {
                    ProjectListScreen(path: $path)
                        .trackScreen(.projectlist)
                        .navigationDestination(for: Route.self) { route in
                            destinationView(for: route)
                        }
                }
            } else if authState is AuthStateAuthenticated {
                NavigationStack(path: $path) {
                    ProjectListScreen(path: $path)
                        .trackScreen(.projectlist)
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
                    .trackScreen(.login)
            }
        }
        .onOpenURL { url in
            handleDeepLink(url: url)
        }
    }

    @ViewBuilder
    private func destinationView(for route: Route) -> some View {
        // Phase 39.3 (ADR-015 §6) — every navigation destination registers
        // a `ScreenViewed` event via the shared `Screen` enum. Centralizing
        // the modifier in the router beats per-screen edits because it's a
        // single anchor — adding a new route is one diff (Route case +
        // destination view + .trackScreen() suffix), not three.
        // Kotlin/Native ObjC bridging lowercases PascalCase enum entries
        // entirely (no camelCase split): `Screen.ProjectDetail` (Kotlin)
        // surfaces as `Screen.projectdetail` (Swift). Same convention as
        // multi-word `ClickActionId.SelectPaletteSymbol` →
        // `.selectpalettesymbol`. Single-word entries stay tidy
        // (`Screen.discovery`); multi-word ones look unusual but are
        // mechanical from the bridge — annotating each entry with
        // `@ObjCName` would let us override but balloons the Kotlin enum
        // declaration.
        switch route {
        case .projectDetail(let projectId):
            ProjectDetailScreen(projectId: projectId, path: $path)
                .trackScreen(.projectdetail)
        case .profile:
            ProfileScreen()
                .trackScreen(.profile)
        case .settings:
            SettingsScreen()
                .trackScreen(.settings)
        case .activityFeed:
            ActivityFeedScreen()
                .trackScreen(.activityfeed)
        case .sharedWithMe:
            SharedWithMeScreen(path: $path)
                .trackScreen(.sharedwithme)
        case .sharedContent(let token, let shareId):
            SharedContentScreen(token: token, shareId: shareId, path: $path)
                .trackScreen(.sharedcontent)
        case .discovery:
            DiscoveryScreen(path: $path)
                .trackScreen(.discovery)
        case .patternLibrary:
            PatternLibraryScreen(path: $path)
                .trackScreen(.patternlibrary)
        case .patternEdit(let patternId):
            PatternEditScreen(patternId: patternId, path: $path)
                .trackScreen(.patternedit)
        case .chartViewer(let patternId, let projectId):
            StructuredChartViewerScreen(patternId: patternId, projectId: projectId, path: $path)
                .trackScreen(.chartviewer)
        case .chartEditor(let patternId):
            StructuredChartEditorScreen(patternId: patternId, path: $path)
                .trackScreen(.charteditor)
        case .chartHistory(let patternId):
            ChartHistoryScreen(patternId: patternId, path: $path)
                .trackScreen(.charthistory)
        case .chartDiff(let baseRevisionId, let targetRevisionId):
            ChartDiffScreen(baseRevisionId: baseRevisionId, targetRevisionId: targetRevisionId)
                .trackScreen(.chartdiff)
        case .symbolGallery:
            SymbolGalleryScreen()
                .trackScreen(.symbolgallery)
        case .pullRequestList(let defaultFilter):
            PullRequestListScreen(defaultFilter: defaultFilter, path: $path)
                .trackScreen(.pullrequestlist)
        case .pullRequestDetail(let prId):
            PullRequestDetailScreen(prId: prId, path: $path)
                .trackScreen(.pullrequestdetail)
        case .chartConflictResolution(let prId):
            ChartConflictResolutionScreen(prId: prId, path: $path)
                .trackScreen(.chartconflictresolution)
        }
    }

    func handleDeepLink(url: URL) {
        guard url.scheme == "skeinly",
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
