# ADR-015: Phase 39 Beta Tester Per-Screen Bug Reporting (F2)

## Status

Proposed

## Context

Phase 39 (Closed Beta) is launching to 5–10 testers via TestFlight Internal
+ Play Internal Testing per the Phase 39 beta rubric (`docs/en/phase/phase-39-beta-rubric.md`).
The `.github/ISSUE_TEMPLATE/beta-bug.yml` bilingual template was shipped in
Phase 39.0 prep (commit `23c1ee1`). Testers can already report bugs *manually*
by opening GitHub, selecting the template, and filling in the form.

The gap that F2 closes: **manual reporting is high-friction and lossy**.
Testers have to context-switch to their browser, recall which screen they
were on, recall what they tapped right before the bug, take + attach a
screenshot, fill in OS/device meta by hand, and do all of this *after* the
bug has interrupted their workflow. The signal-to-noise ratio of the resulting
issues will be poor: critical reproduction details (last N taps, exact screen)
are lost, ja-JP testers may not bother filling EN-language template fields,
and the ones who do will produce inconsistent metadata.

F2 brings the reporting flow **inside the app**: a tester encounters a bug,
triggers an in-app reporter (gesture-based), reviews a pre-filled bug report
that already includes the screen path, last N actions, OS/device meta, and
locale, optionally edits the freeform description, and submits — landing in
the same `.github/ISSUE_TEMPLATE/beta-bug.yml` flow with the structured
metadata pre-populated.

**Forces at play:**

1. **Discoverability vs intrusiveness.** A persistent floating CTA on every
   screen maximizes discoverability but adds a third global UI affordance
   alongside FAB and AppBar overflow (Phase 39.0.2 Sprint A just consolidated
   to those two). Gesture triggers (shake / 3-finger-long-press) are
   discoverable only via documentation but produce no visual noise.

2. **Telemetry richness vs privacy.** Knitters write personal notes on
   projects (recipient names, pattern attribution to family members), upload
   progress photos, and search by free-text. Anything visual / textual is
   sensitive PII. PostHog session replay reconstructs full pixel data;
   custom event capture lets us whitelist exactly what's transmitted.

3. **Submission ease vs secret management.** Direct GitHub API submission
   from the app requires either a per-user PAT (testers without GitHub PAT
   know-how blocked) or a shared service PAT shipped in the app binary
   (rotation nightmare, MITM extraction risk). URL prefill via
   `https://github.com/b150005/skeinly/issues/new?template=...&title=...&body=...`
   is GitHub-native, requires zero secrets, but context-switches to browser.

4. **Privacy posture by jurisdiction.** Phase 39 includes ja-JP testers.
   GDPR (EU users in scope of TestFlight Internal) and Japan's Act on the
   Protection of Personal Information (改正個人情報保護法) both require
   explicit opt-in for data collection that includes any identifying
   information. PostHog SDK initialization, even without session replay,
   establishes a network connection to a third party — opt-in required.

5. **Production posture.** Phase 39 is a beta. v1.0 production users
   (Phase 40) have a different audience profile and a different privacy
   posture. Shipping bug reporting in production binaries leaks data flow
   to non-tester users who never agreed to it.

**Constraints carried forward:**

- **Phase 25 `multiplatform-settings`:** the `hasSeenOnboarding` slot lives
  here today; F2 reuses the same KMP key-value persistence by extending
  the existing `AnalyticsPreferences` store (see Existing infrastructure
  below).
- **Phase 39.0 `.github/ISSUE_TEMPLATE/beta-bug.yml`:** the URL prefill
  target. The template has freeform `description` field plus structured
  metadata sections that F2 will populate via Markdown-formatted body.
- **`docs/public/privacy-policy/index.html` (EN) +
  `docs/public/ja/privacy-policy/index.html` (JA):** existing GitHub
  Pages-hosted privacy policy at
  `https://b150005.github.io/skeinly/privacy-policy/` (EN) and
  `https://b150005.github.io/skeinly/ja/privacy-policy/` (JA). F2 amends
  both with the PostHog data flow disclosure.
- **No PAT in any committed file.** Codebase has zero secrets shipped to
  date (Supabase URL/anon-key are public values; no service tokens).
  F2 must not break this invariant.

**Existing infrastructure that F2 builds atop (already in `shared/src/commonMain/`):**

- **`AnalyticsPreferences`** (`data/preferences/AnalyticsPreferences.kt`):
  consent-state holder. Boolean `analyticsOptIn: StateFlow<Boolean>` +
  `setAnalyticsOptIn(value)`. Backed by `multiplatform-settings` key
  `"analytics_opt_in"` with default `false` per the Phase 27a no-tracking
  stance. Already registered as `single<AnalyticsPreferences>` in
  `PreferencesModule`. F2 reuses this verbatim — no new consent
  repository, no schema migration, no new key.
- **`AnalyticsTracker`** (`data/analytics/AnalyticsTracker.kt`): event
  emission interface with `events: SharedFlow<AnalyticsEvent>` +
  `track(event: AnalyticsEvent)`. Application layer (Android
  `SkeinlyApplication`, iOS `iOSApp.swift`) collects the SharedFlow and
  forwards each event to the platform-native PostHog SDK. The
  `track()` call is gated on `analyticsPrefs.analyticsOptIn.value` —
  silent no-op when off. F2 reuses this verbatim and adds new
  `AnalyticsEvent` variants for screen-view + click-action capture.
- **`AnalyticsEvent`** sealed-interface hierarchy (per the comment in
  `AnalyticsTracker.kt`): typed event variants. F2 adds a
  `ScreenViewed(screen: String)` variant + a `ClickAction(name: String, screen: String)` variant
  to the existing catalog. The compile-time exhaustiveness of the sealed
  interface enforces metadata discipline (no free-form String maps).

## Agent team deliberation

Convened once for the full ADR. Five interacting decisions: reporting UX,
operation log capture, issue submission, privacy, build flavor.

### Voices

- **product-manager:** Beta is 5–10 testers, technical-leaning per the
  Phase 39 rubric. They've used TestFlight before and know the shake-to-
  report idiom from there. The actual reporter UX should be invisible
  until invoked — testers came to test the app, not to interact with
  reporting infrastructure. A permanent floating CTA wastes their visual
  attention budget. Settings entry is the right fallback for the
  non-discoverability of gestures, but the gesture is the primary path.
  v1.0 production has zero need for an in-app reporter — public users use
  email or community channels — so this is beta-only.

- **ui-ux-designer:** Phase 39.0.2 Sprint A made FAB the primary CTA and
  collapsed AppBar to overflow. A floating CTA would directly contradict
  that consolidation. Each platform should follow its native bug-report
  idiom: iOS = shake (TestFlight + Instabug + standard since iOS 6),
  Android = 3-finger-long-press (Bugsnag + Sentry standard, also more
  deliberate than shake). The Settings entry text should read "Send
  Feedback" not "Report Bug" — feedback covers both bugs and feature
  requests, broader scope. Onboarding card on beta first-launch is the
  one-time discoverability mechanism — adds it as a 4th onboarding page
  shown only when `BuildFlags.isBeta`.

- **knitter:** Knitters frequently knit-and-phone — one hand holds
  needles, the other holds the phone. Shake gestures fire accidentally
  when stitches are dense and the phone gets jostled. 3-finger-long-press
  requires the user to put down their needles, deliberate. Shake on iOS
  is acceptable because iOS users have TestFlight conditioning; if a
  knitter accidentally shakes during knitting and the reporter opens, the
  preview screen has a Cancel button. Knitters care about privacy: the
  consent dialog text must be plain Japanese / plain English, name what
  is and isn't collected explicitly. "We send the screen names and your
  taps, but never your patterns or notes or photos."

- **architect:** PostHog has KMP support: `posthog-android` (Maven
  Central, BSD licensed) for Android, `PostHogIos` (SwiftPM) for iOS.
  Free tier is 1M events/month — Phase 39 with 10 testers, 100 events/
  tester/day, 30-day window = 30K events, well within the free tier.
  No paid tier features needed (we deliberately skip session replay).
  The codebase already has `AnalyticsTracker` (SharedFlow → Application-
  layer-collected → PostHog SDK forward) plus `AnalyticsPreferences`
  (boolean opt-in gate, default false) wired through `PreferencesModule`.
  F2 reuses both: extend `AnalyticsEvent` with new typed variants
  (`ScreenViewed`, `ClickAction`), add a sibling `EventRingBuffer`
  collector that taps the same SharedFlow to retain the last N events
  for bug-report attachment. No `TelemetryGate` interface — the
  `track(event)` call site already handles the consent gate. The
  `BuildFlags.isBeta` `expect/actual` flag (NEW) follows the
  `SystemBackHandler` Phase 32.4 precedent.

- **implementer:** Android shake-detection is a 3-finger touch dispatched
  to the root `Activity.dispatchTouchEvent` — `MotionEvent.getPointerCount() == 3`
  + 500ms long-press timer at the WindowDecor view level. iOS shake is
  `UIViewController.motionEnded(.motionShake)` — SwiftUI does not expose
  `UIResponder` motion callbacks directly. The cleanest bridge is
  subclassing `UIHostingController` at the AppDelegate / SceneDelegate
  level (the controller that already hosts the SwiftUI view hierarchy);
  `motionEnded` overrides catch the shake at the root and forward via
  an injected callback. The `UIViewControllerRepresentable` alternative
  (wrap a UIKit controller inside SwiftUI) does not work here because
  motion events propagate to the *root* responder, not to children;
  representable-wrapped controllers nested inside SwiftUI never receive
  the event. URL prefill is platform-trivial: Android
  `Intent(ACTION_VIEW, Uri.parse(url))`, iOS via `URLComponents` +
  `UIApplication.shared.open`. GitHub URL body length practical limit is
  ~8KB; 10-event ring buffer (~1KB) + device meta + freeform description
  fits. Build flag: Android `BuildConfig.IS_BETA` set via
  `buildConfigField` driven by version suffix (`*-beta*` → true); iOS
  `IsBetaBuild` Boolean key in Info.plist driven by xcconfig
  `IS_BETA = YES` on the beta scheme. Swift code reads the Info.plist
  key directly via `Bundle.main.object(forInfoDictionaryKey:)` — no
  Kotlin-object bridge needed at the iOS Swift call sites.

- **security-reviewer:** Three concerns. (1) The PostHog SDK opens a
  network connection at init time — this MUST be gated on consent =
  opted_in, not just on `BuildFlags.isBeta`. SDK presence in the binary
  alone is fine; SDK *initialization* is what triggers the data flow.
  (2) Custom event metadata MUST be whitelisted at compile time — never
  pass user-authored strings (note text, pattern names, search queries,
  comment bodies) into `posthog.capture` calls. The event metadata schema
  is enums + booleans + ints only. (3) URL prefill of GitHub Issues is
  HTTPS-only by URL scheme; the body content is visible in the user's
  browser history but not transmitted to any server other than GitHub.
  Acceptable. PAT-in-app is firmly rejected — secret rotation on a
  shipped binary requires app store updates, MITM extraction is trivial
  on jailbroken/rooted devices.

- **knitter (privacy concern):** When a knitter reports a bug they're
  often confused or frustrated. The consent flow at the moment-of-report
  is the wrong moment to read a privacy policy. Consent must be
  established at first launch *before* the gesture is even discoverable
  — that way, by the time the user wants to report, the data flow is
  already established (or refused). Don't conflate "I want to report
  this bug" with "I want to opt into telemetry."

### Decision points resolved by the team

1. **Reporting UX** → gesture-primary (iOS shake / Android 3-finger-long-press) +
   Settings "Send Feedback" entry as fallback + beta-onboarding card for
   discoverability. No floating CTA. (product-manager + ui-ux-designer +
   knitter, strong agreement.)

2. **Operation log capture** → PostHog free tier, custom events, no
   session replay. Reuse existing `AnalyticsTracker` SharedFlow +
   `AnalyticsPreferences` opt-in gate verbatim — extend `AnalyticsEvent`
   sealed-interface with `ScreenViewed` / `ClickAction` variants. Add KMP
   shared `EventRingBuffer` (FIFO N=10) as a sibling collector reading the
   same SharedFlow to retain last-N events for bug-report attachment.
   Per-platform nav listeners emit `ScreenViewed`; per-screen explicit
   click instrumentation on ~15 key actions emits `ClickAction`.
   Compile-time discipline: no free-form String maps, only typed sealed
   variants. (architect + security-reviewer + implementer, strong; knitter
   agrees on privacy grounds.)

3. **Issue submission** → URL prefill via `Intent(ACTION_VIEW)` (Android) /
   `URLComponents.queryItems` + `UIApplication.shared.open` (iOS) targeting
   `https://github.com/b150005/skeinly/issues/new?template=beta-bug.yml&title=…&body=…`.
   `URLComponents.queryItems` is preferred over manual percent-encoding
   because it correctly encodes `&` and other separators that
   `.urlQueryAllowed` does not. No PAT, no Edge Function. (security-
   reviewer, strong; product-manager accepts the browser context-switch
   as worth the zero-secrets posture.)

4. **Privacy** → reuse the existing `AnalyticsPreferences` opt-in store
   (`analyticsOptIn: StateFlow<Boolean>` backed by
   `multiplatform-settings` key `"analytics_opt_in"`, default false) as
   the consent state. Add a 4th onboarding page on beta builds that
   asks "Help improve Skeinly?" with Yes/No buttons writing through
   `AnalyticsPreferences.setAnalyticsOptIn()`. Settings toggle exposes
   the same `analyticsOptIn` for revoke / re-grant later. PostHog SDK
   init at the Application layer gated on
   `AnalyticsPreferences.analyticsOptIn.value && BuildFlags.isBeta`.
   Privacy policy update enumerating PostHog data fields. Plain-language
   explanation in the onboarding page. No new key, no migration.
   (security-reviewer + knitter, strong; ui-ux-designer agrees on UX
   ordering.)

5. **Build flavor** → beta-only. `BuildFlags.isBeta` `expect/actual` flag.
   Production v1.0 binaries: SDK linked but never initialized, gesture
   detection never registered, Settings "Beta" section never rendered,
   onboarding 4th page never shown. (product-manager + security-reviewer,
   strong.)

## Decision

### 1. Reporting UX

#### iOS — shake gesture

`UIViewController.motionEnded(_:with:)` with `event.subtype == .motionShake`
intercepts. SwiftUI does not expose `UIResponder` motion callbacks
directly. The bridge: subclass `UIHostingController` at the
SceneDelegate / AppDelegate level (the controller that already hosts the
SwiftUI view hierarchy) and override `motionEnded`. Motion events
propagate to the *root* responder — a `UIViewControllerRepresentable`
wrap nested *inside* SwiftUI never receives the event, so root-level
subclassing is the only correct shape.

```swift
final class ShakeDetectingController<Content: View>: UIHostingController<Content> {
    var onShake: (() -> Void)?

    override func motionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        super.motionEnded(motion, with: event)
        if motion == .motionShake { onShake?() }
    }
}
```

Instantiation moves from default `UIHostingController(rootView: AppRootView())`
to `ShakeDetectingController(rootView: AppRootView())` in `SceneDelegate.scene(_:willConnectTo:options:)`.
Set `onShake` to invoke the bug-report flow.

Gating: read `Bundle.main.object(forInfoDictionaryKey: "IsBetaBuild") as? Bool == true`
+ `KoinHelper.shared.analyticsPreferences().analyticsOptInValue` (the
existing `AnalyticsPreferences.analyticsOptIn` `StateFlow<Boolean>` is
exposed to Swift via a small accessor on `KoinHelper`). When either is
false, the `onShake` closure is a no-op — the shake is observed but no
data flow happens (we do not even log "user shook with consent off"
because the log itself would be telemetry).

#### Android — 3-finger long press

Override `Activity.dispatchTouchEvent(MotionEvent)` at the root `MainActivity`.
Detection logic:

```kotlin
private var threeFingerStartTime: Long = 0L

override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    if (BuildConfig.IS_BETA && analyticsPrefs.analyticsOptIn.value) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 3) threeFingerStartTime = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (ev.pointerCount <= 2) threeFingerStartTime = 0L
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount == 3 &&
                    threeFingerStartTime > 0 &&
                    SystemClock.uptimeMillis() - threeFingerStartTime >= 500L
                ) {
                    threeFingerStartTime = 0L
                    onBugReportRequested()
                    return true // consume
                }
            }
        }
    }
    return super.dispatchTouchEvent(ev)
}
```

`analyticsPrefs` is `AnalyticsPreferences` injected via Koin in
`MainActivity`. `analyticsOptIn` is the existing `StateFlow<Boolean>`
backed by `multiplatform-settings` key `"analytics_opt_in"`.

500ms threshold is the same as Compose's `combinedClickable` long-press default
(matches user muscle memory from the Phase 39.0.2 Sprint B M6 swipe-context-menu
pattern).

#### Settings entry

`SettingsScreen` (Compose + SwiftUI) gains a new "Beta" section, rendered
only when `BuildFlags.isBeta`. The section contains:

- "Send Feedback" entry — taps invoke the same `onBugReportRequested` handler
  as the gestures. testTag `sendFeedbackButton`.
- "Diagnostic Data Sharing" toggle — see Decision §4.

Both entries hidden entirely (not just disabled) on production builds.

#### Onboarding card

`OnboardingScreen` already supports 3 pages. On beta builds, a 4th page is
inserted between the existing "Build Your Pattern Library" page and the
"Get Started" CTA, explaining the gesture:

- Title: "Found a bug? Let us know"
- Body: "Shake your iPhone (or use 3 fingers + long press on Android) to
  report any issue you find. You can also use Settings → Beta → Send Feedback."
- Icon: `Icons.Default.BugReport` (Compose) / `ladybug` SF Symbol (iOS)

Card shown only on beta builds, only on first launch (the same
`hasSeenOnboarding` slot from Phase 25 gates this — no separate
`hasSeenBetaOnboarding` key needed because production users never see
the onboarding 4th page anyway).

### 2. Operation log capture: extend `AnalyticsTracker` + add `EventRingBuffer`

#### Dependencies

Android: `posthog-android` via Maven Central, version pinned in
`gradle/libs.versions.toml`. License: BSD-3.

iOS: `PostHogIos` via SwiftPM, declared in `iosApp/project.yml` per
the established Phase 32+ pattern. License: BSD-3.

Both SDKs are linked in beta and production builds (binary size cost ~500KB
on Android / ~1MB on iOS), but **only initialized** when
`BuildFlags.isBeta && AnalyticsPreferences.analyticsOptIn.value`.
Production builds never initialize the SDK, never open the network
connection. The Application-layer `events` collector (existing
`SkeinlyApplication` on Android, `iOSApp.swift` on iOS — already
collecting `AnalyticsTracker.events`) is the place that reads the gate
and decides whether to call `PostHog.capture(...)` for each emission.

#### `AnalyticsEvent` extension (sealed-interface variants)

The existing `AnalyticsEvent` sealed-interface hierarchy already enforces
compile-time event type discipline:

```kotlin
sealed interface AnalyticsEvent {
    val name: String
    val properties: Map<String, Any>?
    // ... 11 existing variants from Phase F.3 / F.5+ ...
}
```

Per the documented cardinality contract in `AnalyticsEvent.kt`,
constructors must NOT accept free-form `String` properties at the wire
boundary — values must be discrete enums, booleans, or small integers.
F2 adds two new variants and two companion enums:

```kotlin
// shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/analytics/AnalyticsEvent.kt
// (additions to the existing sealed interface)

/** Phase 39.3: closed set of screen identifiers for telemetry.
 *  39.3 enumerates the full set; values match existing screen testTags. */
enum class Screen {
    ProjectList, ProjectDetail, PatternLibrary, PatternEdit,
    Discovery, Settings, ChartViewer, ChartEditor,
    /* ... full set in Phase 39.3 implementation ... */
}

/** Phase 39.3: closed set of click-action identifiers for telemetry. */
enum class ClickActionId {
    CreateProject, IncrementRow, MarkComplete, Fork,
    SwitchBranch, SaveChart, UndoChart,
    /* ... full set in Phase 39.3 implementation ... */
}

/** Phase 39.3: screen navigation event. Captured by Compose
 *  NavController listener (Android) and SwiftUI ViewModifier (iOS). */
data class ScreenViewed(val screen: Screen) : AnalyticsEvent {
    override val name: String get() = "screen_viewed"
    override val properties: Map<String, Any> get() = mapOf("screen" to screen.name)
}

/** Phase 39.3: user click / tap on a key affordance. */
data class ClickAction(val action: ClickActionId, val screen: Screen) : AnalyticsEvent {
    override val name: String get() = "click_action"
    override val properties: Map<String, Any> get() = mapOf(
        "action" to action.name,
        "screen" to screen.name,
    )
}
```

Both variants follow the existing `override val name` /
`override val properties` contract verbatim. The sealed-interface design
forces a `when` exhaustiveness check at every `track(event)` site, and
the enum-typed constructor params block accidental capture of
user-authored strings or domain UUIDs at compile time. No separate
whitelist constants object is needed — the type system enforces the
discipline.

#### KMP shared `EventRingBuffer`

A new sibling collector reads the same `AnalyticsTracker.events`
SharedFlow and retains the last N events for bug-report attachment:

```kotlin
// shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/analytics/EventRingBuffer.kt
class EventRingBuffer(
    private val tracker: AnalyticsTracker,
    private val capacity: Int = 10,
) {
    private val events = ArrayDeque<TimestampedEvent>(capacity)
    private val mutex = Mutex()

    /** Start collecting from [tracker.events] in [scope]. Cancel the
     *  scope (or its parent) to stop. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            tracker.events.collect { event ->
                mutex.withLock {
                    if (events.size >= capacity) events.removeFirst()
                    events.addLast(TimestampedEvent(event, Clock.System.now()))
                }
            }
        }
    }

    suspend fun snapshot(): List<TimestampedEvent> = mutex.withLock {
        events.toList()
    }

    suspend fun clear() = mutex.withLock { events.clear() }
}

data class TimestampedEvent(val event: AnalyticsEvent, val capturedAt: Instant)
```

Critical invariant: **the ring buffer reads from the same
`AnalyticsTracker.events` SharedFlow** that `AnalyticsTrackerImpl.track`
emits to *only when* `analyticsOptIn == true`. So the ring buffer is
implicitly gated by the same opt-in: when consent is off, `track()` is
a silent no-op, no event reaches the SharedFlow, and the ring buffer
stays empty. When consent is revoked mid-session, future events stop
flowing; existing ring-buffer contents are cleared explicitly via
`EventRingBuffer.clear()` in the same code path that calls
`AnalyticsPreferences.setAnalyticsOptIn(false)` (so the bug-report flow
never includes pre-revoke events for a now-opted-out user).

Registered as `single<EventRingBuffer> { EventRingBuffer(get()) }` in
`PreferencesModule`. `start(scope)` is invoked from the Application-layer
process lifecycle (alongside the existing `events` SharedFlow collector).

#### Nav listener wiring (Android)

Compose `NavController` listener registered in `MainActivity`. The
`MainActivity` class needs to implement `KoinComponent` (Koin-Android
idiom) for `by inject()` delegation; if it doesn't already, the 39.3
implementer adds the conformance, or alternatively switches to
`get<AnalyticsTracker>()` via `GlobalContext.get()` at the call site:

```kotlin
// MainActivity (must implement org.koin.core.component.KoinComponent for by inject())
private val tracker: AnalyticsTracker by inject()
private val ringBuffer: EventRingBuffer by inject()

// init block: nav listener emits ScreenViewed via the existing tracker.
// Ring buffer reads the same SharedFlow asynchronously — no plumbing
// at this call site.
navController.addOnDestinationChangedListener { _, destination, _ ->
    val routeName = destination.route?.substringBefore('/') ?: return@addOnDestinationChangedListener
    val screen = Screen.entries.firstOrNull { it.name == routeName } ?: return@addOnDestinationChangedListener
    tracker.track(AnalyticsEvent.ScreenViewed(screen))
}
```

`tracker.track` is silent no-op when consent is off (existing
`AnalyticsTrackerImpl` checks `analyticsPrefs.analyticsOptIn.value`
before emitting). Routes that don't map to a known `Screen` enum entry
are silently dropped — keeps the closed-set discipline at the call
site.

#### Nav listener wiring (iOS)

SwiftUI ViewModifier installed at `AppRootView` and applied per top-level
screen. The `Screen` Kotlin enum bridges to Swift as a typed enum
(reachable via the Shared framework as `Screen.projectList`,
`Screen.projectDetail`, etc.):

```swift
// iosApp/iosApp/Telemetry/TrackScreen.swift
struct TrackScreen: ViewModifier {
    let screen: Screen
    func body(content: Content) -> some View {
        content.onAppear {
            KoinHelper.shared.analyticsTracker().track(
                event: AnalyticsEventScreenViewed(screen: screen)
            )
        }
    }
}

extension View {
    func trackScreen(_ screen: Screen) -> some View { modifier(TrackScreen(screen: screen)) }
}
```

Top-level screens add `.trackScreen(.projectList)`,
`.trackScreen(.projectDetail)`, etc. The enum-typed argument matches the
Kotlin cardinality contract: a string-typo at the call site won't even
compile.

#### Click action wiring

Per-screen explicit instrumentation on key actions only — not every
IconButton. The instrumentation surface in Phase 39.3 covers:

- ProjectList: createProject (FAB), open project (row tap), delete (swipe + context menu)
- ProjectDetail: increment row, mark complete, reopen, edit
- PatternLibrary: create pattern, delete
- Discovery: fork
- ChartViewer: switch branch, open PR, view history
- ChartEditor: save, undo, redo, palette select

That's ~15 actions across 6 screens. Not every button — the goal is
"reproduction context," not full interaction trace. Each call site
constructs a typed `AnalyticsEvent.ClickAction(action, screen)` and
passes it to `tracker.track(event)`.

### 3. Issue submission: URL prefill

#### `BugSubmissionLauncher` `expect/actual`

```kotlin
// commonMain
interface BugSubmissionLauncher {
    suspend fun launch(title: String, body: String)
}
```

Android actual:

```kotlin
class BugSubmissionLauncherAndroid(private val context: Context) : BugSubmissionLauncher {
    override suspend fun launch(title: String, body: String) {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedBody = URLEncoder.encode(body, "UTF-8")
        val url = "https://github.com/b150005/skeinly/issues/new?template=beta-bug.yml&title=$encodedTitle&body=$encodedBody"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
```

iOS actual:

```swift
final class BugSubmissionLauncherIOS: BugSubmissionLauncher {
    func launch(title: String, body: String) async {
        var components = URLComponents(string: "https://github.com/b150005/skeinly/issues/new")!
        components.queryItems = [
            URLQueryItem(name: "template", value: "beta-bug.yml"),
            URLQueryItem(name: "title", value: title),
            URLQueryItem(name: "body", value: body),
        ]
        guard let url = components.url else { return }
        await MainActor.run { UIApplication.shared.open(url) }
    }
}
```

`URLComponents.queryItems` correctly percent-encodes `&`, `=`, `?`, and
other URL-reserved characters that `String.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)`
does NOT encode (the `.urlQueryAllowed` character set permits `&` and
`=` to support pre-formed query strings — wrong tool for individual
component encoding). A freeform description containing `&` written via
manual percent-encoding would silently truncate the body parameter at
the GitHub-side parser. This gotcha is the reason `URLComponents` is
the only correct shape here.

#### Body format

The `.github/ISSUE_TEMPLATE/beta-bug.yml` template has structured fields.
URL prefill via `?body=` populates a Markdown-formatted single body block;
the template's individual fields are NOT directly addressable by URL.
Body format:

```markdown
## Description
{{user-supplied freeform description}}

## Reproduction context
- Screen: {{currentScreen}}
- App version: {{appVersion}} ({{buildNumber}})
- Platform: {{platform}} {{osVersion}}
- Device: {{deviceModel}}
- Locale: {{locale}}
- Timestamp: {{ISO8601 timestamp}}

## Recent actions (last 10)
| # | Type | Screen | Action | Time |
|---|------|--------|--------|------|
| 1 | screen_view | ProjectList | — | 12:34:56 |
| 2 | click | ProjectList | create_project (fab) | 12:34:58 |
| ... |

## Telemetry session ID
{{posthog distinct_id, for cross-referencing PostHog dashboard if needed}}
```

The user can edit any part of this body before submitting (see
`BugReportPreviewScreen` in §5).

#### URL length budget

GitHub's URL length limit is ~8KB (varies by browser / proxy). Body
budget: ~6.5KB after subtracting URL chrome and title. Ring buffer at
N=10 events × ~120 bytes (rendered as Markdown table row) = ~1.2KB. Device
meta = ~300 bytes. Freeform description capped at 4000 chars in the
preview screen UI. Headroom: ~1KB. Comfortable.

### 4. Privacy: reuse `AnalyticsPreferences` + onboarding consent page + Settings toggle + privacy policy update

#### Consent state — reuse existing

The codebase already has `AnalyticsPreferences` (Phase F2 / F.3 work-
in-progress, registered in `PreferencesModule`):

```kotlin
// shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/preferences/AnalyticsPreferences.kt
interface AnalyticsPreferences {
    val analyticsOptIn: StateFlow<Boolean>  // backed by "analytics_opt_in", default false
    fun setAnalyticsOptIn(value: Boolean)
}
```

F2 reuses this verbatim. **No new consent repository, no new key, no
3-state enum, no migration.** The boolean default-false matches Phase
27a's "no tracking by default" stance and the GDPR / 改正個人情報保護法
opt-in default. The "has the consent dialog been shown yet?" question
is answered structurally by the onboarding completion gate
(`hasSeenOnboarding` from Phase 25): a beta user who has not seen
onboarding has never been asked; once they finish onboarding (which on
beta builds includes the consent page — see below), their decision is
persisted and the question is closed for that install.

#### Beta onboarding 4th page (consent capture)

Rather than a separate post-onboarding modal dialog, the consent question
lives *inside* the onboarding flow as a 4th page (only inserted when
`BuildFlags.isBeta`). This composes naturally with the existing
`hasSeenOnboarding` state machine — no separate "has consent dialog
been shown" key needed.

The page contains:

- Header: "Help improve Skeinly?"
- Body: "Beta versions send anonymous app navigation events and crash
  data to help us fix bugs faster. Never includes your patterns, notes,
  or photos. You can change this anytime in Settings → Beta."
- Inline "Read full privacy policy" link (locale-aware, see below)
- Two buttons (sized equal, side-by-side): "No, thanks" (left, default
  focus per GDPR) and "Yes, share" (right, accent-tinted)

Tapping either button:
1. Calls `analyticsPreferences.setAnalyticsOptIn(yesTapped)`
2. Advances to the next onboarding page (the existing "Get Started" CTA)

Since the underlying preference is boolean, "No, thanks" sets the same
`false` that's already the default — but the user's explicit choice is
recorded by the act of finishing onboarding (which then writes
`hasSeenOnboarding = true`). A user who quits the app mid-onboarding
re-enters at the consent page on next launch, since the existing
onboarding-resume logic already handles "stuck mid-flow" cases.

testTags: `onboardingConsentPage`, `consentYesButton`, `consentNoButton`,
`consentPolicyLink`.

The "Read full privacy policy" link opens external browser to:

- EN locale: `https://b150005.github.io/skeinly/privacy-policy/`
- JA locale: `https://b150005.github.io/skeinly/ja/privacy-policy/`

(Locale resolved via the Compose `Locale.current` / SwiftUI
`Locale.current.identifier` at link tap time.)

#### Settings toggle

Settings → Beta → "Diagnostic Data Sharing" toggle. Reads/writes the
same `analyticsOptIn` `StateFlow`. Toggle off:

1. `analyticsPreferences.setAnalyticsOptIn(false)` (the existing
   `AnalyticsTracker.track` becomes a silent no-op for future events)
2. `EventRingBuffer.clear()` (so any subsequent bug-report flow does
   not surface pre-revoke events to a now-opted-out user)
3. Application-layer collector (existing `SkeinlyApplication` /
   `iOSApp.swift`) detects `analyticsOptIn = false` via the StateFlow
   and calls `PostHog.shutdown()` / `PostHogSDK.shared.close()`

Toggle on (re-grant):

1. `analyticsPreferences.setAnalyticsOptIn(true)`
2. Application-layer collector calls
   `PostHog.setup(apiKey, config)` / `PostHogSDK.shared.setup(...)`
3. Future `tracker.track(event)` calls flow through to PostHog and the
   ring buffer

testTag: `diagnosticDataSharingToggle`.

#### Privacy policy update

`docs/public/privacy-policy/index.html` (EN) and
`docs/public/ja/privacy-policy/index.html` (JA) gain a new section:

```html
<h2>Diagnostic Data (Beta builds only)</h2>
<p>If you have opted in via Settings → Beta → Diagnostic Data Sharing,
beta versions of Skeinly send the following data to PostHog
(<a href="https://posthog.com/privacy">privacy policy</a>):</p>
<ul>
  <li>App version and build number</li>
  <li>Operating system version</li>
  <li>Device model</li>
  <li>Locale (e.g. en-US, ja-JP)</li>
  <li>Anonymous device identifier (random UUID generated by the PostHog
      SDK, not linked to your account or any other identifier)</li>
  <li>Screen names you visit (e.g. "ProjectList", "Settings")</li>
  <li>Categorical action names (e.g. "create_project", "fork")</li>
</ul>
<p>We <strong>never</strong> send:</p>
<ul>
  <li>Pattern names, project titles, or note text</li>
  <li>Search queries, comment bodies, or any free-text you enter</li>
  <li>Photos or images</li>
  <li>Email addresses or display names</li>
</ul>
<p>The anonymous device identifier may appear in bug reports you submit
through the in-app reporter (the auto-generated GitHub Issue body
includes it for cross-referencing with the PostHog dashboard). It is
generated by the PostHog SDK and does not link to your account, email,
or any persistent identifier outside the app's local storage. You can
clear it by uninstalling and reinstalling the app.</p>
<p>You can revoke consent at any time in Settings → Beta. Revocation
takes effect immediately and clears all locally cached events.</p>
<p>Production releases of Skeinly do not include this functionality at all.</p>
```

JA mirror, same structure with localized headings and copy.

The Phase 39.0 prep already deployed `docs/public/privacy-policy/` to
GitHub Pages. Phase 39.2 of this ADR ships the update on the same
`pages.yml` workflow — automatic deployment on merge to main.

### 5. Build flavor: beta-only

#### `BuildFlags.isBeta` `expect/actual`

```kotlin
// commonMain
object BuildFlags {
    expect val isBeta: Boolean
}
```

Android actual:

```kotlin
// androidMain (or in androidApp module)
actual object BuildFlags {
    actual val isBeta: Boolean = BuildConfig.IS_BETA
}
```

`androidApp/build.gradle.kts` derives `IS_BETA` from version suffix:

```kotlin
android {
    defaultConfig {
        val isBeta = (project.findProperty("appVersion") as? String)?.contains("-beta") ?: false
        buildConfigField("Boolean", "IS_BETA", isBeta.toString())
    }
}
```

Versions matching `*-beta*` → IS_BETA=true. v1.0.0 → IS_BETA=false. Driven
entirely by `version.properties`; no manual flag flipping.

iOS actual:

```kotlin
// iosMain
actual object BuildFlags {
    actual val isBeta: Boolean
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey("IsBetaBuild") as? Boolean ?: false
}
```

`iosApp/project.yml` adds an xcconfig macro:

```yaml
configs:
  Debug-Beta:
    IS_BETA: 'YES'
  Release-Beta:
    IS_BETA: 'YES'
  Release:
    IS_BETA: 'NO'
```

And `iosApp/iosApp/Info.plist` adds:

```xml
<key>IsBetaBuild</key>
<string>$(IS_BETA)</string>
```

Plist booleans via xcconfig string-to-bool: Xcode resolves `YES` → boolean
true at build time when the plist key is read as `objectForInfoDictionaryKey`.

#### Swift-side access

Swift code reads the Info.plist key directly without going through the
Kotlin `expect object`:

```swift
// iosApp/iosApp/Telemetry/BuildFlags.swift
enum BuildFlags {
    static var isBeta: Bool {
        Bundle.main.object(forInfoDictionaryKey: "IsBetaBuild") as? Bool == true
    }
}
```

The Kotlin `actual object BuildFlags` exists for use from Kotlin/Native
iOS source sets (e.g. shared `commonMain` code that branches on
`BuildFlags.isBeta`). At Swift call sites — `SceneDelegate`, the shake
controller, ViewModifier installation — using
`Bundle.main.object(forInfoDictionaryKey:)` is more direct than
bridging through a Kotlin object accessor and avoids any KMP/Swift
interop surface that would have to be revisited if the Shared
framework's symbol-export discipline changes.

#### What gets gated

Every F2-introduced surface checks `BuildFlags.isBeta` before:

- Registering shake / 3-finger detection (the `dispatchTouchEvent`
  override early-returns if `!BuildConfig.IS_BETA`; the iOS shake
  controller's `onShake` closure early-returns on `BuildFlags.isBeta == false`)
- Rendering Settings → Beta section (entire section conditionally composed)
- Inserting the 4th onboarding consent page (only added to the pages
  list when isBeta)
- Initializing PostHog SDK (Application-layer init call wrapped in
  `if (BuildFlags.isBeta && analyticsPrefs.analyticsOptIn.value)`)

Production v1.0 binaries: bit-identical to current except for the SDK
binary present in the linker output (~500KB Android / ~1MB iOS). The SDK
is never initialized. No data flow.

### 6. Sub-slice plan

| Slice | Scope | Test delta | Migrations | i18n keys |
|---|---|---|---|---|
| **39.1 (this ADR)** | F2 design (no code) | 0 | 0 | 0 |
| **39.2** | `BuildFlags.isBeta` `expect/actual` (`commonMain` + `androidMain` reading `BuildConfig.IS_BETA` + `iosMain` reading `IsBetaBuild` Info.plist key) + xcconfig wiring, privacy policy update (`docs/public/privacy-policy/index.html` EN + `docs/public/ja/privacy-policy/index.html` JA) enumerating PostHog data fields and the never-collected list including the `distinct_id`-in-issue-body disclosure | +3–5 | 0 | 0 |
| **39.3** | `posthog-android` ≥3.x + `PostHogIos` SwiftPM ≥3.x deps (snippets in this ADR verified against the v3 setup-API shape — `PostHog.setup(config)` / `PostHog.shutdown()`; pin a specific minor in `libs.versions.toml`), `AnalyticsEvent.ScreenViewed` + `ClickAction` sealed-interface variants + companion `Screen` / `ClickActionId` enums, KMP `EventRingBuffer` (FIFO N=10) with `Mutex` consuming `AnalyticsTracker.events` SharedFlow, NavController listener (Android) + `.trackScreen` ViewModifier (iOS) emitting `ScreenViewed`, per-screen click instrumentation on ~15 key actions emitting `ClickAction`, Application-layer PostHog init gated on `BuildFlags.isBeta && analyticsOptIn.value` (existing `SkeinlyApplication` + `iOSApp.swift` collectors gain the gate), new `KoinHelper.analyticsTracker()` + `KoinHelper.analyticsPreferences()` accessors for the iOS bridge per the established Phase 32+ KMP-bridge pattern (the latter consumed by the 39.5 iOS shake gate) | +12–18 | 0 | 0 |
| **39.4** | Beta 4th onboarding consent page (Compose + SwiftUI) writing through existing `AnalyticsPreferences.setAnalyticsOptIn`, Settings "Beta" section (gated on `BuildFlags.isBeta`) with "Send Feedback" entry + "Diagnostic Data Sharing" toggle bound to `analyticsOptIn` `StateFlow`, i18n 8–10 keys (EN + JA shared composeResources + iOS xcstrings), `EventRingBuffer.clear()` wired into the toggle-off path | +6–10 | 0 | 8–10 |
| **39.5** | Android 3-finger-long-press detector at `MainActivity.dispatchTouchEvent` gated on `BuildConfig.IS_BETA && analyticsOptIn`, iOS shake bridge (`ShakeDetectingController : UIHostingController` subclass overriding `motionEnded`) installed in `SceneDelegate`, `BugReportPreviewScreen` (review + edit submission body composable + SwiftUI mirror), `BugSubmissionLauncher` `expect/actual` for URL prefill via `Intent(ACTION_VIEW)` (Android) / `URLComponents.queryItems` + `UIApplication.shared.open` (iOS), Markdown body formatting helper in `commonMain` | +10–15 | 0 | 6–8 |

Total test delta: +33–51 (current 1211 → 1244–1262)
Total i18n keys delta: +14–18

Each sub-slice updates `CLAUDE.md`'s "Completed" section in the same
commit that ships it. Slices 39.2 / 39.3 / 39.4 / 39.5 form a linear
dependency chain: 39.2 lands the build flag + privacy policy update
(no behavioral change yet — the existing `AnalyticsPreferences` /
`AnalyticsTracker` infrastructure already in `PreferencesModule` is
sufficient as the gate interface); 39.3 wires PostHog SDK init at the
Application layer and adds the `EventRingBuffer` collector + new
`AnalyticsEvent` variants; 39.4 surfaces the consent and Settings UI;
39.5 adds the gesture trigger + submission flow. Slipping 39.5 leaves
bug reporting unreachable (testers report manually as before — no
regression from current state). Slipping 39.4 leaves the existing
default-false `analyticsOptIn` permanently false (PostHog init never
fires; ring buffer always empty; no data flow). Slipping 39.3 leaves
the build flag and updated privacy policy without any new behavior.
Each slice is independently shippable without breaking earlier slices.

### 7. Explicitly NOT in F2 MVP

- **In-app screenshot capture** — testers attach screenshots via GitHub's
  drag-and-drop on the issue page after URL prefill lands. KMP screenshot
  capture has known parity issues (Compose `captureToImage` vs SwiftUI
  `ImageRenderer`) and the round-trip through GitHub upload would force
  multipart form handling we don't have today.
- **Crash auto-capture** — Sentry / Firebase Crashlytics are separate
  Phase 40+ concerns. F2 is for *observed* bugs; unhandled exceptions
  surface as separate signal (and most testers will manually report after
  a crash anyway).
- **Network log capture** — last N HTTP requests/responses. Privacy footgun
  (request bodies often contain user data); skipped.
- **State snapshot at bug time** — current ViewModel state dumped into
  the issue body. Same privacy concern (state often references user-
  authored content); skipped. The ring buffer of last-10-actions gives
  reproduction context without serializing full state.
- **In-app feedback for v1.0 production** — Phase 40+ if it ever ships.
- **Audio/video bug recording** — way out of scope.
- **GitHub OAuth in-app** — would let testers submit without browser
  context-switch but adds OAuth flow complexity + token storage; rejected
  in favor of URL prefill.
- **Server-side issue aggregation / dashboard** — testers' issues land
  in the standard repo Issues tab; PostHog dashboard covers the
  analytical view. No additional dashboarding.
- **Triage automation** — bug template's frontmatter labels handle this
  via the existing GitHub Issues label automation; nothing F2-specific.
- **Reporter rate-limiting** — beta is 10 testers; abuse risk is zero.
- **Custom Detekt rule for `TelemetryKeys.*` enforcement** — code-review
  + a single test covers Phase 39.3 needs; static analysis is post-MVP
  if the surface grows.

## Consequences

### Positive

- Beta tester bug reporting is structured, low-friction, privacy-respecting.
  The signal-to-noise ratio of Phase 39 issues will be higher than the
  manual baseline — every report carries reproduction context (last-10
  actions + screen + device meta).
- Zero secrets in the binary. URL prefill via GitHub's native template
  flow inherits GitHub's HTTPS + auth posture.
- $0 telemetry cost. PostHog free tier covers Phase 39 with headroom
  (Phase 39 = ~30K events/month; free tier = 1M).
- GDPR + 改正個人情報保護法 compliant via opt-in default Off + revocable
  Settings toggle + plain-language disclosure.
- Production v1.0 binaries are bit-identical to current state (modulo
  ~500KB–1MB SDK linker output). No privacy posture change for non-beta
  users.
- Reuses Phase 39.0 `.github/ISSUE_TEMPLATE/beta-bug.yml`. The
  template + the in-app flow are the same submission path.
- KMP shared `EventRingBuffer` + the existing `AnalyticsTracker`
  SharedFlow contract can extend to v1.0 production (Phase 40+) with a
  different downstream collector (Sentry Crashlytics etc.) without
  rewriting the call sites. The instrumentation surface is
  forward-compat: every `tracker.track(AnalyticsEvent.X)` call site is
  unchanged across the boundary; only the Application-layer collector
  decides where the events go.

### Negative

- PostHog SDK adds ~500KB to Android beta APK / ~1MB to iOS beta IPA.
  Production binaries also carry the linked SDK (uninitialized) for
  build-process simplicity — same size cost. Acceptable on modern
  devices but visible on app store size metadata.
- Consent dialog adds friction to beta first-launch. Testers must
  consciously opt in or skip. Some testers will skip and then report
  bugs manually — the auto-attached reproduction context is missing
  from those reports. Acceptable: gesture still works to launch the
  preview screen with an empty action history.
- Gesture-based trigger has lower discoverability than a persistent
  CTA. Mitigated by: (a) Settings entry as fallback, (b) beta 4th
  onboarding card, (c) the Phase 39 beta rubric documenting the
  gesture in the tester onboarding email.
- Custom event capture is coarser than session replay. Bugs that
  manifest in deeply-nested state (e.g. "after 7 specific edits, the
  ChartEditor undo stack ends up in a wrong state") may not be
  reproducible from the last-10-actions ring buffer alone. Mitigated by
  the freeform description field in the preview screen — testers can
  describe the bug textually beyond what the buffer captures.
- URL prefill context-switches to the browser. Testers leave the app
  to submit; some may forget to come back. Acceptable: the issue lands
  on GitHub regardless.
- 3-finger gesture on Android conflicts with the system 3-finger
  screenshot on some OEMs (Xiaomi, Huawei, OnePlus). On those devices
  the system intercepts before the app sees the touch event. Tester
  documentation will note "if 3-finger doesn't work, use Settings →
  Beta → Send Feedback." Falling back to a 4-finger gesture would just
  shift the conflict; documenting the Settings fallback is the right
  trade-off.
- Shake gesture on iOS conflicts with the system "Undo" prompt
  (`UIApplication.applicationSupportsShakeToEdit`). We do NOT disable
  this — instead, the shake invokes the bug reporter; if a user has
  edited a text field and then shakes, both events fire (the system
  Undo prompt + the bug reporter). Both are dismissable. Tester
  documentation will note "if undo prompt appears with the bug reporter,
  cancel the undo and proceed."

### Neutral

- `multiplatform-settings` Phase 25 dependency unchanged in API. F2
  adds **no new keys** — the existing `analytics_opt_in` boolean from
  `AnalyticsPreferences` carries the consent state; Phase 25's
  `hasSeenOnboarding` remains the source of truth for onboarding flow
  completion (which now includes the consent capture page on beta
  builds).
- `.github/ISSUE_TEMPLATE/beta-bug.yml` Phase 39.0 template unchanged
  — F2 populates a Markdown-formatted single body block via URL prefill.
- Phase 39 beta rubric (`docs/en/phase/phase-39-beta-rubric.md`) gains a
  brief reference to F2 in the "How to report bugs" section but is
  otherwise unchanged.
- `docs/public/privacy-policy/index.html` (EN) +
  `docs/public/ja/privacy-policy/index.html` (JA), both Phase 39.0
  artifacts, gain the diagnostic data section. The deployment workflow
  (`pages.yml`) is unchanged.
- iOS deployment target stays at 17.2 (per Phase 39.0 prep). PostHogIos
  supports iOS 13+. Android minSdk stays at 26.

## Considered alternatives

| Alternative | Pros | Cons | Why not chosen |
|---|---|---|---|
| Persistent floating CTA on every screen | Maximum discoverability | Adds third global affordance alongside FAB + AppBar overflow; visual noise; constant accidental-tap risk; contradicts Phase 39.0.2 Sprint A consolidation | Settings entry + gesture covers the use case; gesture has zero visual cost |
| Shake on Android instead of 3-finger-long-press | Cross-platform parity | Knitters often jostle phone while knitting → false positives; 3-finger more deliberate | Each platform's native idiom is preferable to forced parity |
| 3-finger-long-press on iOS instead of shake | Cross-platform parity | iOS users have shake-to-report conditioning from TestFlight + Instabug; adopting Android idiom would feel foreign | Each platform's native idiom |
| 4-finger long-press on Android (avoid OEM 3-finger screenshot conflict) | No OEM conflict | 4-finger gesture is unergonomic on most phones; awkward to perform | Document the Settings fallback for OEM-conflict cases instead |
| PostHog session replay | Full visual reconstruction | Paid tier ($0.005/recording); pixel data risks PII; masking config is brittle (one mistake leaks notes/photos) | Custom events are sufficient for ≤10 testers and structurally safer |
| Sentry instead of PostHog | Better unhandled-exception capture | $26/month minimum for our usage; primary need is action analytics, not just exceptions | PostHog free tier covers analytics + we don't need crash auto-capture in F2 |
| Firebase Crashlytics | Free, well-known | Requires Google Mobile Services dep; Phase 1 deliberately avoided GMS for AOSP-friendliness; would force schema review with Google | PostHog is platform-neutral and BSD-licensed |
| Self-hosted telemetry (Supabase POST + custom dashboard) | No third-party | Engineering overhead for ingestion + dashboard + retention; PostHog free tier solves all of this without build-it-yourself tax | Build-vs-buy falls heavily on buy |
| GitHub API direct submission with PAT in app | One-tap submit, no browser context-switch | PAT in binary = persistent secret, rotation requires app store update, MITM extraction trivial on jailbroken devices | Friction-vs-security tradeoff falls on URL prefill |
| GitHub OAuth in-app | Submit without browser context-switch + no shipped secret | Adds full OAuth flow complexity + token storage + refresh logic; testers must authorize an OAuth app per device | URL prefill simpler; testers re-auth in browser is an acceptable cost |
| Supabase Edge Function with server-side PAT | Server-side secret, simpler client | Adds infrastructure (Edge Function deployed to Supabase); rate-limit complexity; network-dependent submission | URL prefill needs zero infrastructure |
| Implicit consent (auto opt-in for beta) | Higher data volume | GDPR / 改正個人情報保護法 non-compliant; testers may be in EU; legally risky | Explicit opt-in required by law |
| Always-on (no beta-only build flavor) | Simpler | Privacy posture change for v1.0 production users; would require extending privacy policy to non-beta; expands attack surface to public install base | Beta-only matches Phase 39 scope and v1.0 production has separate Phase 40+ feedback design |
| Crash auto-capture in F2 (PostHog Crash Reports) | Catches bugs testers don't manually report | Adds significant scope; PostHog crash reports require additional SDK config + symbolication setup; F2 is for *observed* bugs | Crash auto-capture is Phase 40+ |
| In-app screenshot capture and embed in URL | Visual context | KMP screenshot capture has parity issues (Compose `captureToImage` vs SwiftUI `ImageRenderer`); base64-encoded screenshots break the 8KB URL limit; multipart form upload to GitHub adds API complexity | Testers attach via GitHub's drag-drop after URL prefill |
| Network log capture (last N HTTP requests) | Captures backend interaction context | Privacy footgun — request bodies often contain user-authored content; whitelisting bodies is harder than whitelisting categorical event names | Out of scope; gate is "categorical events only" |
| Custom Detekt rule for `TelemetryKeys.*` enforcement | Compile-time guarantee against accidental PII capture | Adds Detekt setup + custom rule maintenance; surface is small (~15 call sites in 39.3) | Code-review + single test covers Phase 39.3; revisit if surface grows |
| Settings entry only (no gesture) | Simplest | Tester must navigate 2-3 levels deep to file every report — high friction at the moment-of-bug | Gesture-primary + Settings fallback is the right balance |
| Gesture only (no Settings entry) | Cleaner | If tester forgets the gesture (no onboarding card sighting), they have no other entry point | Settings entry as fallback is essential |
| Markdown-render the freeform description in the preview screen | Tester sees rendered output | KMP markdown rendering parity (Compose vs SwiftUI) is a known bug surface; same render-divergence concern as ADR-014 §6 PR comments | Plain text in preview, GitHub renders Markdown after submission |
| Bilingual (EN+JA) freeform description | ja-JP testers feel less friction | Adds locale-aware UI; ja-JP testers can write Japanese into the freeform field already (no UI lock to English); template body rendering accepts UTF-8 | No special handling needed; UTF-8 just works |

## References

- ADR-005: Account deletion (`delete_own_account` SECURITY DEFINER pattern
  — not used here, but the cross-table atomic-write precedent is relevant
  for future Phase 40+ feedback aggregation if it ever ships server-side)
- ADR-014: Phase 38 PR workflow (URL-prefill-vs-API submission tradeoff
  resolved similarly: thin server, rich client, no secrets in binary)
- Phase 39.0 prep (commit `23c1ee1`): `version.properties` 1.0.0-beta1 +
  `.github/ISSUE_TEMPLATE/beta-bug.yml` + `docs/en/phase/phase-39-beta-rubric.md`
  — F2 builds atop these
- Phase 25 (`multiplatform-settings`): KMP key-value persistence pattern,
  reused via the existing `analytics_opt_in` key from `AnalyticsPreferences`
- Phase 32.4 (`SystemBackHandler` `expect/actual`): the precedent for
  platform-bridging `expect/actual` patterns; `BuildFlags.isBeta` follows
  the same shape
- Phase 39.0.2 Sprint A: AppBar overflow consolidation; F2 deliberately
  preserves this by NOT introducing a third global affordance
- PostHog Android SDK: <https://github.com/PostHog/posthog-android> (BSD-3)
- PostHog iOS SDK: <https://github.com/PostHog/posthog-ios> (BSD-3)
- `docs/public/privacy-policy/` (EN at
  `https://b150005.github.io/skeinly/privacy-policy/`) +
  `docs/public/ja/privacy-policy/` (JA at
  `https://b150005.github.io/skeinly/ja/privacy-policy/`): existing
  GitHub Pages-hosted privacy policy; F2 ships a diagnostic-data
  section update on both
