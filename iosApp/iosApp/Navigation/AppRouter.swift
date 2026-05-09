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
    case bugReportPreview
    /// Phase 41.3b (ADR-016 §5.1) — paywall route. Uses
    /// `PaywallTrigger.wireValue` for the Hashable representation so the
    /// case stays Codable / Hashable without forcing PaywallTrigger to
    /// adopt SwiftUI conformances (it is bridged from a Kotlin enum).
    case paywall(trigger: PaywallTrigger)
    /// Phase 41.4 (ADR-016 §5.2 §6 §41.4) — pack management screen
    /// reachable from Settings → "Manage Symbol Packs". Always-on, NOT
    /// beta-gated.
    case packManagement

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
        case .bugReportPreview:
            hasher.combine("bugReportPreview")
        case .paywall(let trigger):
            hasher.combine("paywall")
            // PaywallTrigger bridges from Kotlin as an ObjC singleton enum
            // — `wireValue` is its stable string identifier and gives a
            // deterministic hash component (NSObject identity is stable
            // for singletons but its hash combines unstably with strings).
            hasher.combine(trigger.wireValue)
        case .packManagement:
            hasher.combine("packManagement")
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
        // Phase 39.5 (ADR-015 §1) — shake gesture opens the bug-report
        // preview. Gated on:
        //  - `BuildFlags.isBeta` (production binaries are bit-identical
        //    minus the SDK; the gesture surface is hidden entirely).
        //  - `KoinHelperKt.analyticsOptInValue()` synchronous read of the
        //    AnalyticsPreferences StateFlow (the EventRingBuffer is also
        //    privacy-gated upstream so the report would have an empty
        //    trail anyway, but gating the trigger itself matches the
        //    Android `MainActivity.dispatchTouchEvent` shape and keeps
        //    the behavior contracts symmetric across platforms).
        // No top-of-path dedup: NavigationPath does not expose a typed
        // `peek`, and a duplicate preview stack is harmless — Cancel
        // pops one, the prior view is still underneath.
        .onShake {
            guard BuildFlags.isBeta else { return }
            guard KoinHelperKt.analyticsOptInValue() else { return }
            path.append(Route.bugReportPreview)
        }
        // Phase 24.5 (ADR-017 §3.8) — push-tap deep link. AppDelegate's
        // `UNUserNotificationCenterDelegate` posts `.openPushRoute` with
        // a `route: String` in userInfo when the user taps a push (warm
        // start) OR when the cold-start launchOptions carry a remote
        // notification (re-posted from `application(_:didFinishLaunchingWithOptions:)`).
        // Either way, we parse the route here and append the matching
        // typed `Route` to the navigation path.
        .onReceive(NotificationCenter.default.publisher(for: .openPushRoute)) { notification in
            guard
                let route = notification.userInfo?[openPushRouteUserInfoKey] as? String,
                let target = parsePushRoute(route)
            else { return }
            path.append(target)
        }
    }

    /// Parse a host-relative push-route string into a typed `Route`
    /// case. The Phase 24 wave only emits `pull-request/<prId>`; future
    /// event sources extend the prefix table.
    ///
    /// Returns `nil` for unknown / malformed routes so the consumer at
    /// the call site silently drops a hostile or stale push without
    /// any user-visible navigation glitch. (Hostile = a Phase 24+ push
    /// arriving on an older client that doesn't recognize the route
    /// shape; stale = the OS delivered a cached push after the route
    /// scheme changed.)
    private func parsePushRoute(_ raw: String) -> Route? {
        let pullRequestPrefix = "pull-request/"
        if raw.hasPrefix(pullRequestPrefix) {
            let prId = String(raw.dropFirst(pullRequestPrefix.count))
            guard !prId.isEmpty else { return nil }
            return .pullRequestDetail(prId: prId)
        }
        return nil
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
        // Phase E2E hardening (2026-05-06): every NavigationStack destination
        // gets `.skeinlyBackButton(path: $path)` so Maestro / XCUITest can
        // locate the back button by `accessibilityIdentifier("backButton")`
        // — independent of the simulator locale (which renders the native
        // SwiftUI back button label as `戻る` on ja-JP and `Back` on en-US).
        // The `chartEditor` case is the sole exception: it ships its own
        // toolbar item with a discard-guard wrapper but DOES use the same
        // `accessibilityIdentifier("backButton")` so the contract is
        // uniform from the test side. See `Components/SkeinlyBackButton.swift`.
        switch route {
        case .projectDetail(let projectId):
            ProjectDetailScreen(projectId: projectId, path: $path)
                .trackScreen(.projectdetail)
                .skeinlyBackButton(path: $path)
        case .profile:
            ProfileScreen()
                .trackScreen(.profile)
                .skeinlyBackButton(path: $path)
        case .settings:
            SettingsScreen(
                onSendFeedback: { path.append(Route.bugReportPreview) },
                // Phase 41.3b (ADR-016 §5.1) — Settings → "Subscribe to
                // Pro" routes to the paywall. Always-on entry, NOT
                // beta-gated.
                onSubscribeToProClick: {
                    path.append(Route.paywall(trigger: PaywallTrigger.settings))
                },
                // Phase 41.4 (ADR-016 §5.2) — Settings → "Manage Symbol
                // Packs". Always-on, NOT beta-gated.
                onManagePacksClick: {
                    path.append(Route.packManagement)
                }
            )
                .trackScreen(.settings)
                .skeinlyBackButton(path: $path)
        case .activityFeed:
            ActivityFeedScreen()
                .trackScreen(.activityfeed)
                .skeinlyBackButton(path: $path)
        case .sharedWithMe:
            SharedWithMeScreen(path: $path)
                .trackScreen(.sharedwithme)
                .skeinlyBackButton(path: $path)
        case .sharedContent(let token, let shareId):
            SharedContentScreen(token: token, shareId: shareId, path: $path)
                .trackScreen(.sharedcontent)
                .skeinlyBackButton(path: $path)
        case .discovery:
            DiscoveryScreen(path: $path)
                .trackScreen(.discovery)
                .skeinlyBackButton(path: $path)
        case .patternLibrary:
            PatternLibraryScreen(path: $path)
                .trackScreen(.patternlibrary)
                .skeinlyBackButton(path: $path)
        case .patternEdit(let patternId):
            PatternEditScreen(patternId: patternId, path: $path)
                .trackScreen(.patternedit)
                .skeinlyBackButton(path: $path)
        case .chartViewer(let patternId, let projectId):
            StructuredChartViewerScreen(patternId: patternId, projectId: projectId, path: $path)
                .trackScreen(.chartviewer)
                .skeinlyBackButton(path: $path)
        case .chartEditor(let patternId):
            // Owns its own back button with discard-guard semantics + the
            // same `accessibilityIdentifier("backButton")` — see
            // `StructuredChartEditorScreen.swift`. Do NOT add
            // `.skeinlyBackButton` here; it would duplicate the toolbar item.
            StructuredChartEditorScreen(patternId: patternId, path: $path)
                .trackScreen(.charteditor)
        case .chartHistory(let patternId):
            ChartHistoryScreen(patternId: patternId, path: $path)
                .trackScreen(.charthistory)
                .skeinlyBackButton(path: $path)
        case .chartDiff(let baseRevisionId, let targetRevisionId):
            ChartDiffScreen(baseRevisionId: baseRevisionId, targetRevisionId: targetRevisionId)
                .trackScreen(.chartdiff)
                .skeinlyBackButton(path: $path)
        case .symbolGallery:
            SymbolGalleryScreen()
                .trackScreen(.symbolgallery)
                .skeinlyBackButton(path: $path)
        case .pullRequestList(let defaultFilter):
            PullRequestListScreen(defaultFilter: defaultFilter, path: $path)
                .trackScreen(.pullrequestlist)
                .skeinlyBackButton(path: $path)
        case .pullRequestDetail(let prId):
            PullRequestDetailScreen(prId: prId, path: $path)
                .trackScreen(.pullrequestdetail)
                .skeinlyBackButton(path: $path)
        case .chartConflictResolution(let prId):
            ChartConflictResolutionScreen(prId: prId, path: $path)
                .trackScreen(.chartconflictresolution)
                .skeinlyBackButton(path: $path)
        case .bugReportPreview:
            // Excluded from `.skeinlyBackButton` because the screen ships
            // its own primary dismissal — a body-level Cancel button calling
            // `onCancel`. Adding `.skeinlyBackButton` would duplicate that
            // affordance with a top-bar "Back" button (different label,
            // same effect), violating "one obvious dismiss per screen". The
            // SwiftUI-default back button stays — its label is the parent's
            // localized title, but Maestro flows do not interact with this
            // screen so locale-coupling does not surface.
            BugReportPreviewScreen(onCancel: { path.removeLast() })
                .trackScreen(.bugreportpreview)
        case .paywall(let trigger):
            // Phase 41.3b (ADR-016 §5.1) — paywall sheet rendered as a
            // pushed NavigationStack destination. Excluded from
            // `.skeinlyBackButton` because the screen ships its own
            // top-bar "Close" button (the canonical paywall dismissal
            // affordance). The trackScreen modifier registers the
            // ScreenViewed analytics event.
            PaywallScreen(
                trigger: trigger,
                onDismiss: { path.removeLast() }
            )
                .trackScreen(.paywall)
        case .packManagement:
            // Phase 41.4 (ADR-016 §5.2) — pack management screen.
            PackManagementScreen(path: $path)
                .skeinlyBackButton(path: $path)
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
