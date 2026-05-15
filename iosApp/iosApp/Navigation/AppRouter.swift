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
    case chartComparison(baseRevisionId: String?, targetRevisionId: String)
    case symbolGallery
    case pullRequestList(defaultFilter: SuggestionFilter)
    case pullRequestDetail(prId: String)
    case chartConflictResolution(prId: String)
    case bugReportPreview
    /// Phase 26.5 (ADR-022 §6.4) — TOTP enrollment entry. Reached from
    /// Settings → Security → "Enable two-factor authentication".
    case mfaEnrollment
    /// Phase 26.5 (ADR-022 §6.4) — TOTP challenge gate. Surfaced as a
    /// root-level screen when AuthState.MfaChallengeRequired is observed
    /// (NOT pushed onto the path because the user cannot back out of
    /// the AAL2 gate via the back stack; only verify-success or
    /// sign-out exits).
    case mfaChallenge
    /// Phase 26.6 (ADR-022 §6.5) — biometric authentication settings.
    /// Reached from Settings → Security → "Biometric authentication".
    case biometricSettings
    /// Phase 27.2 (ADR-023 §UX) — data-wipe confirmation flow.
    /// Reached from Settings → Danger Zone → "Delete all my data".
    /// Mounts the WipeData ViewModel with the locale-active required
    /// phrase resolved via `NSLocalizedString("phrase_wipe_data_confirm", ...)`
    /// at view-init time; mid-flow locale change is unsupported.
    case wipeDataConfirmPhrase
    /// Phase 25.3 (ADR-024 §(e)) — Settings → Privacy → Connections.
    /// Three-tab Friends / Pending / Invite surface for managing the
    /// mutual-friendship graph + invite generation.
    case connections
    /// Phase 39 (ADR-021 §D4) — Settings → Privacy → Blocked Users.
    /// Lists the caller's blocked users with a per-row Unblock action.
    case blockedUsers
    /// Phase 25.4 (ADR-024 §Phase 25.4) — friend-invite redemption.
    /// `token` non-nil ⇒ Token mode (Universal Link tap, auto-redeem);
    /// nil ⇒ Code mode (reached from Connections → "Add by code").
    case friendInviteConfirm(token: String?)
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
        case .chartComparison(let baseRevisionId, let targetRevisionId):
            hasher.combine("chartComparison")
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
        case .mfaEnrollment:
            hasher.combine("mfaEnrollment")
        case .mfaChallenge:
            hasher.combine("mfaChallenge")
        case .biometricSettings:
            hasher.combine("biometricSettings")
        case .wipeDataConfirmPhrase:
            hasher.combine("wipeDataConfirmPhrase")
        case .connections:
            hasher.combine("connections")
        case .blockedUsers:
            hasher.combine("blockedUsers")
        case .friendInviteConfirm(let token):
            hasher.combine("friendInviteConfirm")
            hasher.combine(token)
        }
    }
}

/// Root view that handles onboarding and auth-gated navigation.
struct AppRootView: View {
    @StateObject private var authHolder: ScopedViewModel<AuthViewModel, AuthUiState>
    @State private var path = NavigationPath()
    /// Phase 39 (W3 / 2026-05-11) — typed deep-link Route, parsed at
    /// receive time via `parseExternalRoute(url:)` (Swift mirror of the
    /// commonMain helper in `NavGraph.kt`). Stashed here when the deep
    /// link arrives before auth completes; replayed in the
    /// `AuthStateAuthenticated` branch's `.onAppear`. Renamed from the
    /// pre-W3 `pendingDeepLinkToken: String?` (which carried only a
    /// share-token UUID extracted from the legacy `skeinly://share/<token>`
    /// custom scheme — that scheme was deleted in this slice, no Tech
    /// Debt fallback per pre-v1 breaking changes accepted policy).
    @State private var pendingDeepLinkRoute: Route?
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

    /// Phase 26.6 (ADR-022 §6.6) — captured at the first Authenticated
    /// transition (in the `.onChange(authState)` below). Non-nil ⇒ the
    /// router replaces the ProjectList stack with `OAuthProfileSetupScreen`
    /// until the gate fires `onCompleted`, which sets this back to nil
    /// and falls through to the normal stack.
    @State private var pendingOAuthSetupMetadata: PendingOAuthSetup?

    @ViewBuilder
    private var authenticatedContent: some View {
        if let gateMetadata = pendingOAuthSetupMetadata {
            OAuthProfileSetupScreen(
                displayName: gateMetadata.displayName,
                pictureUrl: gateMetadata.pictureUrl,
                onCompleted: {
                    pendingOAuthSetupMetadata = nil
                }
            )
        } else {
            NavigationStack(path: $path) {
                ProjectListScreen(path: $path)
                    .trackScreen(.projectlist)
                    .navigationDestination(for: Route.self) { route in
                        destinationView(for: route)
                    }
            }
            .onAppear {
                if let route = pendingDeepLinkRoute {
                    pendingDeepLinkRoute = nil
                    path.append(route)
                }
            }
        }
    }

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
                // Phase 26.6 (ADR-022 §6.6) — post-OAuth profile setup
                // gate. Shown ONCE per install on the first Authenticated
                // transition when (a) the gate preference hasn't been
                // marked complete AND (b) the OAuth provider supplied a
                // displayName seed (Apple `full_name` on first sign-in
                // or Google `name`). Email/password sign-ups have no
                // user_metadata seed → metadata.displayName is null →
                // the gate falls through to ProjectList silently.
                authenticatedContent
            } else if authState is AuthStateMfaChallengeRequired {
                // Phase 26.5 (ADR-022 §6.4) — AAL1 session pending TOTP.
                // Full-screen gate; no NavigationStack push because the
                // user cannot back out via the gesture stack. Verify
                // success elevates the session AAL → mfa.statusFlow re-
                // emits → observeAuthState produces plain Authenticated
                // → this branch yields to the projectlist branch above.
                //
                // No .trackScreen(...) modifier — the Screen enum doesn't
                // carry a `mfaChallenge` case yet; analytics for this
                // pre-AAL2 surface lands in a follow-up.
                MfaChallengeScreen()
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
        // Phase 26.6 (ADR-022 §6.6) — re-evaluate the OAuth profile
        // setup gate every time the AuthState transitions. The gate
        // fires asynchronously because it queries AuthRepository for
        // user_metadata; we capture the result into
        // `pendingOAuthSetupMetadata` which the body branch above
        // reads to decide whether to surface the setup screen.
        .onChange(of: authHolder.state.authState is AuthStateAuthenticated) { _, isAuthed in
            // Phase 26.6 (ADR-022 §6.6) — re-evaluate the OAuth profile
            // setup gate when the AuthState transitions to/from
            // Authenticated. We pivot on the Bool discriminator (rather
            // than the Kotlin sealed-class instance) because
            // `AuthState` does not bridge as `Equatable` from
            // Kotlin/Native + .onChange requires Equatable.
            guard isAuthed else {
                pendingOAuthSetupMetadata = nil
                return
            }
            // Already completed on a prior install / sign-in → never
            // re-prompt. Synchronous read of the SharedPreferences-
            // backed gate flag.
            if KoinHelperKt.isOAuthProfileSetupGateCompleted() {
                pendingOAuthSetupMetadata = nil
                return
            }
            Task { @MainActor in
                let metadata = try? await KoinHelperKt.fetchOAuthOnboardingMetadata()
                guard
                    let displayName = metadata?.displayName,
                    !displayName.trimmingCharacters(in: .whitespaces).isEmpty
                else {
                    pendingOAuthSetupMetadata = nil
                    return
                }
                pendingOAuthSetupMetadata = PendingOAuthSetup(
                    displayName: displayName,
                    pictureUrl: metadata?.pictureUrl
                )
            }
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
                },
                // Phase 26.5 (ADR-022 §6.4) — Settings → Security →
                // Enable 2FA routes to the TOTP enrollment screen.
                onEnableMfaClick: {
                    path.append(Route.mfaEnrollment)
                },
                // Phase 26.6 (ADR-022 §6.5) — Settings → Security →
                // Biometric authentication routes to the biometric
                // settings screen.
                onBiometricSettingsClick: {
                    path.append(Route.biometricSettings)
                },
                // Phase 27.2 (ADR-023 §UX) — Settings → Danger Zone →
                // "Delete all my data" routes to the confirmation flow.
                onWipeDataClick: {
                    path.append(Route.wipeDataConfirmPhrase)
                },
                // Phase 25.3 (ADR-024 §(e)) — Settings → Privacy →
                // "Connections" routes to the 3-tab management screen.
                onConnectionsClick: {
                    path.append(Route.connections)
                },
                // Phase 39 (ADR-021 §D4) — Settings → Privacy →
                // "Blocked users" routes to the blocked-users list.
                onBlockedUsersClick: {
                    path.append(Route.blockedUsers)
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
            ChartViewerScreen(patternId: patternId, projectId: projectId, path: $path)
                .trackScreen(.chartviewer)
                .skeinlyBackButton(path: $path)
        case .chartEditor(let patternId):
            // Owns its own back button with discard-guard semantics + the
            // same `accessibilityIdentifier("backButton")` — see
            // `ChartEditorScreen.swift`. Do NOT add
            // `.skeinlyBackButton` here; it would duplicate the toolbar item.
            ChartEditorScreen(patternId: patternId, path: $path)
                .trackScreen(.charteditor)
        case .chartHistory(let patternId):
            ChartHistoryScreen(patternId: patternId, path: $path)
                .trackScreen(.charthistory)
                .skeinlyBackButton(path: $path)
        case .chartComparison(let baseRevisionId, let targetRevisionId):
            ChartComparisonScreen(baseRevisionId: baseRevisionId, targetRevisionId: targetRevisionId)
                .trackScreen(.chartcomparison)
                .skeinlyBackButton(path: $path)
        case .symbolGallery:
            SymbolGalleryScreen()
                .trackScreen(.symbolgallery)
                .skeinlyBackButton(path: $path)
        case .pullRequestList(let defaultFilter):
            SuggestionListScreen(defaultFilter: defaultFilter, path: $path)
                .trackScreen(.suggestionlist)
                .skeinlyBackButton(path: $path)
        case .pullRequestDetail(let prId):
            SuggestionDetailScreen(prId: prId, path: $path)
                .trackScreen(.suggestiondetail)
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
        case .mfaEnrollment:
            // Phase 26.5 (ADR-022 §6.4) — TOTP enrollment flow. Pushed
            // from Settings → Security; the screen completes itself by
            // popping back to Settings on recovery-code dismissal.
            MfaEnrollmentScreen(onCompleted: { path.removeLast() })
                .skeinlyBackButton(path: $path)
        case .mfaChallenge:
            // The MfaChallenge case is rendered as a root-level branch
            // in AppRootView, NOT through navigation push. This case
            // exists in the Route enum for type completeness but is
            // unreachable via `path.append(.mfaChallenge)`.
            MfaChallengeScreen()
        case .biometricSettings:
            // Phase 26.6 (ADR-022 §6.5) — biometric re-auth + threshold
            // picker. Pushed from Settings → Security → "Biometric
            // authentication" entry.
            BiometricSettingsScreen()
                .skeinlyBackButton(path: $path)
        case .wipeDataConfirmPhrase:
            // Phase 27.2 (ADR-023 §UX) — data-wipe confirmation flow.
            // The screen self-manages its dual phases (preservation
            // sheet → phrase typing). On Cancel: pop directly to
            // Settings. On WipeCompleted: pop the wipe route AND any
            // intermediate route off the stack, landing the user
            // implicitly on Pattern Library / Project List (root). The
            // post-wipe banner is signaled inside the view via
            // `KoinHelperKt.notifyWipeCompleted()` so the
            // PatternLibrary VM (still alive in the back stack)
            // surfaces the success banner.
            WipeDataConfirmPhraseView(
                onCancel: { path.removeLast() },
                onWipeCompleted: {
                    // Pop everything back to the root tab so the user
                    // lands on Project List / Pattern Library where
                    // the banner is rendered.
                    path = NavigationPath()
                }
            )
                .skeinlyBackButton(path: $path)
        case .connections:
            // Phase 25.3 (ADR-024 §(e)) — Settings → Privacy →
            // Connections. The view's `init` resolves the Koin
            // ConnectionsViewModel which kicks off the initial
            // refresh against listFriends / listPending / listInvites
            // + caller-id resolution. Standard back-button pop
            // routes back to Settings.
            ConnectionsView(
                // Phase 25.4 — "Add by code" routes to the redemption
                // screen in code mode (nil token).
                onAddByCode: {
                    path.append(Route.friendInviteConfirm(token: nil))
                }
            )
                .skeinlyBackButton(path: $path)
        case .blockedUsers:
            // Phase 39 (ADR-021 §D4) — Settings → Privacy → Blocked
            // Users. The view's `init` resolves the Koin
            // BlockedUsersViewModel which auto-loads the caller's
            // block list. Standard back-button pop routes to Settings.
            BlockedUsersListView()
                .skeinlyBackButton(path: $path)
        case .friendInviteConfirm(let token):
            // Phase 25.4 (ADR-024 §Phase 25.4) — friend-invite
            // redemption. Token mode auto-redeems on init; code mode
            // renders the entry form. On "Done" pop back to Connections
            // (Friends tab shows the new friend); a cold deep-link
            // launch resets the path so the user lands coherently.
            FriendInviteConfirmView(
                token: token,
                onDone: {
                    path = NavigationPath()
                    path.append(Route.connections)
                },
                onBack: { path.removeLast() }
            )
                .skeinlyBackButton(path: $path)
        }
    }

    /// Phase 39 (W3 / 2026-05-11) — handle a Universal Link arriving via
    /// `.onOpenURL`. Parses the URL via [parseExternalRoute] and routes
    /// to the typed [Route] case. Authenticated path appends to the nav
    /// path immediately; unauthenticated path stashes the route in
    /// `pendingDeepLinkRoute` for the post-login `.onAppear` replay.
    ///
    /// Phase 26.3 (ADR-022 §6.2) — give the GoogleSignInBridge first
    /// crack at the URL. The GIDSignIn OAuth flow returns to the app
    /// via a custom URL scheme (the reverse-client-ID registered in
    /// `Info.plist`'s `CFBundleURLTypes`); SwiftUI delivers it through
    /// the same `.onOpenURL` hook. `GIDSignIn.sharedInstance.handle(_:)`
    /// returns `true` if it recognized + consumed the URL — in that
    /// case we short-circuit so `parseExternalRoute` (which only
    /// recognizes `https://b150005.github.io/skeinly/...`) doesn't run
    /// against a Google callback and silently drop it.
    func handleDeepLink(url: URL) {
        if GoogleSignInBridge.shared.handle(url: url) {
            return
        }
        guard let route = parseExternalRoute(url: url) else { return }
        let authState = authHolder.state.authState
        if authState is AuthStateAuthenticated {
            path.append(route)
        } else {
            pendingDeepLinkRoute = route
        }
    }

    /// Swift mirror of the Kotlin commonMain `parseExternalRoute` (see
    /// `shared/src/commonMain/.../NavGraph.kt`). Two implementations
    /// because Compose Navigation routes (`SharedContent`, `SuggestionDetail`)
    /// are Kotlin-side types that don't bridge to Swift, and SwiftUI
    /// uses its own [Route] enum. The format contract is identical:
    /// any URL recognized here is also recognized by the Kotlin helper
    /// for the equivalent Compose route, and vice versa.
    ///
    /// Recognized URL family (alpha scope):
    ///   https://b150005.github.io/skeinly/patterns/shared/<token>
    ///     → .sharedContent(token: <token>, shareId: nil)
    ///   https://b150005.github.io/skeinly/pull-requests/<prId>
    ///     → .pullRequestDetail(prId: <prId>)
    ///
    /// Returns nil for any URL outside the family, with empty
    /// identifier segment, or with an invalid share-token shape (UUID
    /// v4 only — guards against hand-crafted URLs reaching the
    /// SharedContent fetch with garbage tokens).
    func parseExternalRoute(url: URL) -> Route? {
        guard url.scheme == "https" else { return nil }
        guard url.host == "b150005.github.io" else { return nil }
        // pathComponents includes a leading "/" entry; drop it. Then we
        // expect "skeinly" as the first real segment, and the
        // resource/identifier segments after.
        let segments = url.pathComponents.filter { $0 != "/" }
        guard segments.first == "skeinly" else { return nil }
        let rest = Array(segments.dropFirst())

        // /skeinly/patterns/shared/<token>
        if rest.count == 3, rest[0] == "patterns", rest[1] == "shared" {
            let token = rest[2]
            guard !token.isEmpty, isValidShareToken(token) else { return nil }
            return .sharedContent(token: token, shareId: nil)
        }

        // /skeinly/pull-requests/<prId>
        if rest.count == 2, rest[0] == "pull-requests" {
            let prId = rest[1]
            guard !prId.isEmpty else { return nil }
            return .pullRequestDetail(prId: prId)
        }

        // /skeinly/friend/<token> — Phase 25.4 (ADR-024 §Phase 25.4).
        // Token is a 32-byte URL-safe random (migration 035), NOT a
        // UUID, so no shape regex applies. Validation is minimal here
        // (non-empty + length cap ≤512 cheap DoS guard); real
        // existence / expiry / consumed / self-redeem checks are
        // delegated to the redeem_friend_invite_token RPC. Mirrors the
        // Kotlin commonMain parseExternalRoute friend arm + its
        // MAX_FRIEND_TOKEN_LENGTH constant.
        if rest.count == 2, rest[0] == "friend" {
            let token = rest[1]
            guard !token.isEmpty, token.count <= 512 else { return nil }
            return .friendInviteConfirm(token: token)
        }

        return nil
    }

}

/// Phase 26.6 (ADR-022 §6.6) — captured payload for the OAuth profile
/// setup gate decision. The display name is non-empty by gate
/// invariant (the surfacing rule already filtered the empty/null case
/// before constructing this); pictureUrl is optional because Apple
/// does not expose an avatar URL.
struct PendingOAuthSetup: Equatable {
    let displayName: String
    let pictureUrl: String?
}
