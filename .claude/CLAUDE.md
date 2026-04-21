# Knit Note — KMP Knitting App

## About This Project

**Knit Note** is a Kotlin Multiplatform (KMP) mobile app for knitters. It helps users manage knitting patterns (charts), track progress on projects, and share patterns/progress with others.

- **Platforms**: Android (Jetpack Compose), iOS (SwiftUI), macOS (SwiftUI, stretch goal)
- **Architecture**: KMP shared logic + platform-native UI
- **Data Strategy**: Local-first with cloud sync (Firebase or Supabase — to be decided via ADR)
- **Language**: Kotlin (shared), Kotlin (Android UI), Swift (iOS/macOS UI)

Created from the [ECC Base Template](https://github.com/b150005/ecc-base-template).

## Project Structure

```
knit-note/
├── shared/                    # KMP shared module
│   ├── src/commonMain/        # Shared business logic, models, repositories
│   ├── src/commonTest/        # Shared tests
│   ├── src/androidMain/       # Android-specific implementations
│   └── src/iosMain/           # iOS-specific implementations (Kotlin)
├── androidApp/                # Android application (Jetpack Compose UI)
├── iosApp/                    # iOS/macOS application (SwiftUI, Xcode project)
├── docs/
│   ├── en/                    # English documentation (source of truth)
│   └── ja/                    # Japanese translations
└── .claude/                   # Agent team and Claude Code configuration
```

## Architecture Principles

- **Clean Architecture** with clear separation: UI → ViewModel → UseCase → Repository → DataSource
- **KMP shared module** contains: domain models, use cases, repository interfaces, and common utilities
- **Platform-native UI**: Android uses Jetpack Compose, iOS/macOS uses SwiftUI + UIKit (via `UIViewControllerRepresentable`/`UIViewRepresentable` where SwiftUI alone is insufficient)
- **Repository pattern** for data access abstraction (local DB + remote sync)
- **Immutable data structures**: use Kotlin `data class` with `copy()`, Swift structs
- **Dependency injection**: Koin for shared/Android, native DI patterns for iOS
- **Unidirectional data flow**: State flows down, events flow up

## Key Technology Stack

| Layer | Technology |
|-------|-----------|
| Shared Logic | Kotlin Multiplatform |
| Android UI | Jetpack Compose + Material 3 |
| iOS/macOS UI | SwiftUI + UIKit (interop as needed) |
| Local Database | SQLDelight (multiplatform) |
| Networking | Ktor Client (multiplatform) |
| Serialization | kotlinx.serialization |
| Async | Kotlin Coroutines + Flow (shared), Swift async/await (iOS) |
| DI | Koin Multiplatform |
| Image Loading | Coil (Android), platform-native (iOS) |
| Build System | Gradle (KMP), Xcode via XcodeGen (iOS) |

## Domain Model (Core Concepts)

- **Pattern**: A knitting chart/pattern with metadata (name, difficulty, gauge, yarn info, chart image/data)
- **Project**: An instance of working on a pattern (start date, current row/round, status)
- **Progress**: Row-by-row or section-based progress tracking with timestamps
- **Share**: Exported pattern or progress snapshot for sharing with others

## Agent Team

This project uses an agent team for structured development. The **orchestrator** agent coordinates the following specialists:

| Agent | Role |
|-------|------|
| orchestrator | Analyzes issues, creates plans, delegates to specialists |
| product-manager | Product planning, PRD, user stories, acceptance criteria |
| market-analyst | Market research, competitor analysis |
| monetization-strategist | Business model, pricing, revenue strategy |
| ui-ux-designer | UI/UX design, accessibility, usability review |
| docs-researcher | Documentation research, API verification, freshness-safe search |
| architect | System architecture, technology decisions |
| implementer | Code implementation following architecture and TDD |
| code-reviewer | Code quality and standards review |
| test-runner | Test execution, coverage reporting |
| linter | Static analysis, code style enforcement |
| security-reviewer | Vulnerability detection, OWASP Top 10 |
| performance-engineer | Profiling, bottleneck identification, optimization |
| devops-engineer | CI/CD, deployment strategy, release management |
| technical-writer | Documentation, changelog, bilingual docs |
| knitter | Domain advisor on knitting symbols, bilingual labels, and craft conventions (first-pass reviewer; escalates conflicts to user) |

### Ecosystem Detection

Agents detect this project as **Kotlin Multiplatform** by finding:
- `build.gradle.kts` with KMP plugin configuration
- `shared/` module with `commonMain`, `androidMain`, `iosMain` source sets
- `androidApp/` with Jetpack Compose dependencies
- `iosApp/` with Xcode project and SwiftUI

## Development Workflow

1. **Issue Analysis**: Feed issues to the orchestrator via GitHub MCP or copy-paste
2. **Product Planning**: The product-manager creates PRD, user stories, and acceptance criteria
3. **Research & Reuse**: Search GitHub, package registries, and docs before writing new code
4. **Architecture**: The architect designs the solution; significant decisions are recorded as ADRs in `docs/en/adr/`
5. **Implementation**: The implementer writes code following TDD (RED -> GREEN -> IMPROVE)
   - Shared logic in `shared/src/commonMain/`
   - Android UI in `androidApp/`
   - iOS UI in `iosApp/`
6. **Quality Gate**: The code-reviewer, linter, security-reviewer, and performance-engineer validate the implementation
   - After task completion, request a code review from the code-reviewer agent
   - Fix ALL reported issues (CRITICAL, HIGH, MEDIUM, and LOW)
   - Re-run the code review after fixes
   - Repeat the review-fix cycle until no issues remain (APPROVED)
   - Only then proceed to commit
7. **Documentation**: The technical-writer updates docs and changelog
8. **Release**: The devops-engineer manages deployment and release
9. **Commit, Push & CI Verification**: When the code review is APPROVED and all tests pass:
   - **Pre-commit CI check**: If any CI workflow file (`.github/workflows/`) was modified, reproduce its build/test commands locally before committing to catch task name mismatches, missing dependencies, or configuration errors early
   - **Pre-commit CI warning check**: After pushing, review CI logs for warnings (not just failures). Warnings such as deprecated actions, missing configuration fields, or fallback behaviors indicate latent issues that should be fixed proactively
   - Commit using conventional commits format (feat, fix, refactor, docs, test, chore, perf, ci)
   - Push to `origin/main`
   - Monitor CI results (both failures AND warnings); fix issues within the same session
   - These steps are performed autonomously — no user confirmation needed
10. **Next Step Planning**: At each task boundary (phase completion, milestone), convene the agent team (architect, product-manager, implementer, etc.) to discuss and agree on the next priority. Output the result as Next Session Instructions for handoff.

## CI Known Limitations

- **CodeQL java-kotlin**: CodeQL does not support Kotlin 2.3.20 yet (upstream limit). The `security.yml` job uses `continue-on-error: true` so it does not block CI. The overlay-base database warning (`build-mode is set to "manual" instead of "none"`) is benign — CodeQL falls back to full (non-incremental) analysis. Both issues resolve when CodeQL adds Kotlin 2.3.20 support.
- **Maestro + iOS 26**: Maestro 2.4.0 cannot reliably tap SwiftUI `Button` elements inside `List` sections on iOS 26 — the first tap works but subsequent taps are silently dropped. Also, `inputText` into `.searchable` does not update the SwiftUI `@State` binding. Affected iOS E2E flows are tagged `skip-ios26` and excluded from `run-ios.sh`. Both issues are upstream Maestro/XCTest compatibility bugs that resolve when Maestro ships iOS 26 support.
- **Maestro + SwiftUI Button on headless CI**: Maestro `tapOn` (both text and accessibility ID) does not reliably register SwiftUI `Button` taps on headless CI simulators (macOS GitHub Actions runner). The iOS onboarding helper bypasses this by relaunching the app with `-has_seen_onboarding true` launch argument, which NSUserDefaults picks up from the argument domain.
- **E2E local-only mode**: CI builds use empty Supabase credentials (local-only mode). Flows that require a live Supabase backend (e.g., Discovery browse) are tagged `requires-supabase` and excluded from CI. Run these flows locally against a Supabase-connected build.
- **iOS NavigationStack edge-swipe**: iOS 16+ `NavigationStack` does not expose a public API to disable the left-edge swipe-to-pop gesture while keeping the rest of the bar interactive. `.interactiveDismissDisabled` applies only to modally presented sheets, not pushed stack entries. Screens with discard-guard semantics (e.g. the structured chart editor) hide and replace the default back button so tapping it routes through a confirmation dialog, but the edge-swipe still pops silently. Disabling the gesture requires dropping to UIKit (`navigationController.interactivePopGestureRecognizer.delegate = self`), which is out of scope for the shared-module architecture. Users on iOS who care about in-flight edits should use the top-bar back button.

## Testing Requirements

- Minimum 80% test coverage for shared module
- **Unit tests** (commonTest): Domain models, use cases, repository logic
- **Android tests**: Compose UI tests, ViewModel tests
- **iOS tests**: XCTest for SwiftUI views and ViewModels
- **Integration tests**: SQLDelight database operations, Ktor API calls
- **E2E tests**: Critical user flows (create pattern, track progress, share)

### Testing Tools

| Platform | Framework |
|----------|-----------|
| Shared (commonTest) | kotlin.test |
| Android | JUnit 5, Compose UI Testing, Turbine (Flow testing) |
| iOS | XCTest, Swift Testing |
| E2E | Maestro (YAML flows, `e2e/` directory) |

## Code Quality Standards

- Functions: < 50 lines
- Files: 200-400 lines typical, 800 max
- Validate all inputs at system boundaries
- Handle errors explicitly at every level
- Never hardcode secrets; use environment variables or BuildConfig/Info.plist
- Use `ktlint` for Kotlin formatting
- Use `SwiftFormat` / `SwiftLint` for Swift formatting
- Gradle: use version catalogs (`libs.versions.toml`)

## Documentation Convention

- Technology reference docs: `docs/en/` (English, source of truth)
- Japanese translations: `docs/ja/` (maintained translations)
- Claude reads `docs/en/` only to minimize context window usage
- Japanese files include a header linking to the English source
- ADRs in `docs/en/adr/` and `docs/ja/adr/`

## Session Handoff Protocol

Each session MUST end with a **Session Task Prompt** block output as a fenced code block in the conversation (never written to any file). This block is the prompt the user will paste back at the start of the next session to instruct you on what to do — so write it in the imperative second-person voice ("Your task this session is X", "Run Y", "Open Z"), NOT as a third-person prediction of what a future session will do. The reader of this block IS the session doing the work.

The handoff is a short tactical prompt for resuming work — NOT a comprehensive status dump. Persistent facts live in this CLAUDE.md; the handoff only carries what the resuming session needs to act immediately.

### What goes in the handoff — keep terse (target ≤40 lines)
1. **Heading**: start with a clear task-prompt header, e.g. `# Your task this session` or `## Task for this session` — NOT `## Next Session Instructions` (that phrasing makes the reader think the task belongs to someone else).
2. **Current state**: branch + latest commit hash + test count + CI status (one line each)
3. **Your task**: one-line imperative statement of the phase / option to execute ("Implement Phase 30.4 glyph bundle", not "Phase 30.4 glyph bundle is next")
4. **First actions**: 3–5 concrete commands or edits to run immediately (e.g. `gh run list ...` sanity check, path to the first file to open, the test to write first) — phrased as direct instructions
5. **Any pending execution decision blocking completion** (merge conflict, flaky test awaiting investigation, etc.)

### What does NOT go in the handoff — put in CLAUDE.md instead
- **Roadmap progress** → `## Development Roadmap > Completed`. Move each finished phase here in the same commit that ships it. Include test delta, key design points, scope cuts.
- **Scope decisions for the completed phase** → keep in the phase's Completed bullet (what was cut, why, when to revisit).
- **Tech debt and follow-ups** → `## Tech Debt Backlog`. One line per item, grouped by theme.
- **Architecture invariants, ADR references** → `## Architecture Principles` or the relevant ADR under `docs/en/adr/`.
- **Gotchas / local-CI divergence** → `## CI Known Limitations`.

If the handoff is growing past ~40 lines it means you are duplicating CLAUDE.md — move the content there and link to it by phase name only.

**Ordering**: emit the handoff as the very last message of the session, after every execution decision (push, CI verify, etc.) is resolved. Never emit it mid-session — state can change afterwards and the handoff goes stale.

**Voice check**: before emitting, re-read the block as if you were opening a fresh session and someone handed it to you. If any sentence reads as "the next session will do X" rather than "do X", rewrite it. The user copy-pastes this block verbatim as the first message of the resuming session — it must read as a direct instruction to that session, not a description of it.

## Development Roadmap

### Completed
- **Phase 1**: Row Counter — Project CRUD, increment/decrement, status tracking (62 tests)
- **Phase 1.5**: Progress Notes — row-level memos via ProgressRepository (79 tests)
- **Phase 2a**: Technical Debt — `updated_at` column on ProjectEntity (ADR-003 prerequisite)
- **Phase 2b**: Result Pattern — Unify UseCase error handling with `UseCaseResult<T>` (92 tests)
- **Phase 2c**: Project Edit — Update title/totalRows from detail screen (103 tests)
- **Phase 3a**: UX Polish — ViewModel stateIn unification, error channel consolidation, date formatting, Progress.note non-null (103 tests)
- **Phase 3b-pre**: Android UX Finish — Manual complete/reopen status transitions, swipe-to-delete for projects and notes (118 tests)

- **Phase 4a**: iOS App Shell — iosApp/ Xcode project, ComposeUIViewController host, Koin iOS init, simulator verified (118 tests)
- **Phase 3b**: Supabase Foundation MVP — supabase-kt 3.5.0 integration, Auth (Email/Social), Local/Remote DataSource refactor, ConnectivityMonitor, coordinator repositories, SQL migration, ADR-004 (130 tests)
- **Phase 3b+**: Supabase Sync — PendingSync queue with coalescing/backoff, offline write support (local-first + syncOrEnqueue), RealtimeSyncManager (auth-aware 3-table subscriptions), SyncExecutor, ConnectivityMonitor (Android/iOS), currentRow conflict guard (ADR-003)
- **Phase 4b**: Sharing — Share entity, ShareRepository (remote-only with inline Realtime), fork, deep links
- **Phase 5a**: Comments — CommentRepository with Realtime subscriptions, CommentSectionViewModel
- **Phase 5b**: Activity Feed — ActivityRepository with Realtime, ActivityFeedViewModel
- **Phase 5c**: User Profiles — UserRepository, OfflineUserRepository fallback, Profile screen, GetCurrentUser/UpdateProfile UseCases
- **Phase 5d**: UX Polish — Channel-based one-shot events, delete confirmations, DateTimeFormat KMP compat, sqlite-driver platform fix, nullable UserRepository elimination

- **Phase 6**: iOS SwiftUI — Native SwiftUI screens (7 screens + CommentSection), FlowWrapper StateFlow bridge, KoinHelper ViewModel accessors, NavigationStack routing, deep linking, default hierarchy template fix, ContentView.swift Compose bridge removed

- **Phase 7a**: Android Compose UI Tests — 19 instrumented tests (ProjectList, ProjectDetail, Navigation, Profile screens)
- **Phase 7b**: iOS XCTest — 19 tests (UI + unit), ApplicationScope singleton, Koin startup diagnostics, resetDatabase error handling, UITest reliability improvements

- **Phase 7.5**: Technical Debt Cleanup — Dependency updates (AGP 9.1.0, Koin 4.2.1, Navigation 2.9.7), ProGuard/R8 rules, network security config, iOS safe casting, code quality improvements, SQLDelight indices

- **Phase 8**: Dispatcher Injection — Inject IO dispatcher via Koin, typed UseCaseError mapping
- **Phase 9**: CI/CD Foundation — ktlint, Kover coverage reporting, signing config, release workflow
- **Phase 10**: Chart Image Support — Chart image upload, storage, and zoom viewer
- **Phase 11**: Test Coverage 80% — Kover exclusions, 428 tests, sanitizeFileName security hardening
- **Phase 12**: CI/CD Hardening — koverVerify enforcement in CI, iOS test failure propagation fix, Swift CodeQL runner fix
- **Phase 13**: Realtime Testability — RealtimeChannelProvider/ChannelHandle/ChangeFilter abstraction, DataSourceOperations extraction, 34 new tests (462 shared total)
- **Phase 14**: Offline-First Hardening — Progress owner_id denormalization + Realtime filter, idempotent upsert, SyncLogger, auto-reconnect with backoff/jitter/ConnectivityMonitor, security review fixes (469 shared tests)
- **Phase 15**: Tech Debt Cleanup — RemoteSyncOperations insert→upsert rename, DefaultSyncLogger expect/actual (Android Log.d, iOS NSLog), Progress.ownerId fix with AuthRepository injection, SQLDelight default alignment (471 shared tests)
- **Phase 15.5**: AGP 9.x KMP Plugin Migration — `com.android.library` → `com.android.kotlin.multiplatform.library`, BuildConfig → generated SupabaseCredentials, `androidUnitTest` → `androidHostTest`, dependency updates (ktlint 14.2.0, kotlinx-serialization 1.11.0, Coil 3.4.0, Kover 0.9.8) (471 shared tests)

- **Phase 16**: Search/Filter + builtInKotlin — ViewModel-level search (title substring), status filter (All/Not Started/In Progress/Completed), sort (Recent/Alphabetical/Progress), Material 3 SearchBar + FilterChip (Android), SwiftUI .searchable + Picker (iOS), androidApp builtInKotlin migration (removed org.jetbrains.kotlin.android plugin, android.builtInKotlin=true) (491 shared tests)

- **Phase 17**: Settings + App Store Readiness — Settings screen (account management, sign-out, delete account), delete_own_account PostgreSQL RPC (SECURITY DEFINER, ADR-005), PrivacyInfo.xcprivacy, adaptive icon scaffold (Android), AppIcon.appiconset scaffold (iOS), UILaunchScreen config (502 shared tests)

- **Phase 18**: Store Submission + UI Polish — CloseRealtimeChannelsUseCase extraction (SignOut/DeleteAccount duplication removal, partial-failure resilience), local.xcconfig for DEVELOPMENT_TEAM (git-ignored), iOS DesignTokens.swift (spacing/typography/opacity tokens), screen token adoption (ProjectList, ProjectDetail, Profile, SharedWithMe), Compose deprecated API investigation (rememberSwipeToDismissBoxState still experimental in CMP 1.10.3, no change needed) (508 shared tests)

- **Phase 19a**: Supabase Production Deploy — All 8 migrations applied (001-007 schema + 008 Realtime publication fix), `supabase init` + `supabase link`, Email Auth verified, `delete_own_account` RPC (SECURITY DEFINER) confirmed, `chart-images` private Storage bucket + 5 RLS policies, 6-table Realtime publication (projects/progress/patterns/shares/comments/activities), 32-check verification script, config.toml committed (508 shared tests)

- **Phase 19b**: App Icon Artwork — iOS 1024x1024 PNG, Android adaptive icon PNGs (all densities), Play Store 512x512, yarn ball motif with purple (#7B61FF) brand color (508 shared tests)

- **Phase 19c**: CI Signing + TestFlight — iOS code signing pipeline in release.yml (temporary keychain, manual signing, ExportOptions.plist generation, xcodebuild archive/exportArchive, TestFlight upload via xcrun altool with App Store Connect API key), conditional execution (graceful degradation when secrets absent), version numbering (MARKETING_VERSION from tag, CURRENT_PROJECT_VERSION from run_number), IPA artifact on GitHub Release, ADR-006 (508 shared tests)

- **Phase 20a**: Maestro E2E Setup + P0 Android Flows — `e2e/` directory, 3 P0 Maestro flows (app launch, create project, row counter), testTag additions to Compose UI (incrementButton, decrementButton, createProjectFab, projectNameInput, totalRowsInput, createProjectButton), local run script, clearState-based test isolation (508 shared tests)

- **Phase 20b**: Maestro P1+P2 Android Flows — `testTagsAsResourceId = true` on root Box (MainActivity), local-only build via empty SUPABASE env vars, text-based dialog selectors (CMP AlertDialog window isolation workaround), 3 new flows (edit project, search/filter, navigation), run-android.sh updated for full suite, SwipeToDismissBox Maestro incompatibility documented (508 shared tests, 6 E2E flows)

- **Phase 20c**: Maestro iOS Flows — 6 iOS Maestro flows mirroring Android (P0×3 + P1×2 + P2×1), 5/6 passing (P1_search_filter skipped due to Maestro + iOS 26 bug), `accessibilityLabel` on increment/decrement/edit buttons, `run-ios.sh` script (xcodebuild + simctl + Maestro), Maestro SwiftUI List Button tap bug documented and worked around (508 shared tests, 6+6 E2E flows)

- **Phase 20d**: E2E CI Integration — Separate `e2e.yml` workflow (main push + v* tags), Android E2E via `reactivecircus/android-emulator-runner` (API 34, KVM, Maestro), iOS E2E via `macos-latest` (simulator boot, xcpretty, Maestro with `--exclude-tags skip-ios26`), failure screenshots as artifacts (508 shared tests, 6+6 E2E flows)

- **Phase 22**: Pattern Management — Full CRUD for Pattern entity with gauge/yarnInfo/needleSize metadata, Supabase migration 009, PatternLibrary screen (search/filter/sort), PatternEdit screen (7-field form), CreateProject pattern linking, ProjectDetail pattern info section, iOS SwiftUI mirrors, 4 new UseCases + 2 ViewModels, 33 new tests (541 shared tests)

- **Phase 22.1 + 23**: Tech Debt Cleanup + Progress Photos — PatternEditViewModel isSaved→Channel one-shot event, ActivityType.CREATED enum + CreatePatternUseCase fix, iOS PatternLibraryScreen searchText sync, RemoteStorageDataSource bucket parameterization (chart-images + progress-photos), Supabase migration 010 (progress-photos bucket + RLS + CREATED activity type), UploadProgressPhotoUseCase/DeleteProgressPhotoUseCase, AddProgressNoteUseCase photoUrl support, ProjectDetailViewModel photo upload/display/delete integration, Android AddNoteDialog photo picker + NoteItem thumbnail + ChartImageViewer reuse, iOS addNoteSheet PhotosPicker + NoteRow thumbnail + ChartImageViewer fullScreenCover, 12 new tests (553 shared tests)

- **Phase 23.1**: Review Item Fixes — StorageOperations.upload `patternId`→`subFolder` generic rename, sanitizeFileName double-extension normalization (`.jpg.html`→`.jpg`), isValidJpeg EOI marker (0xFF 0xD9) validation + min size 6, DeleteNote orphan blob logging (PII-safe), loadProgressPhotoUrls signed URL path-change caching (556 shared tests)

- **Phase 25**: Onboarding — First-run onboarding carousel (3 pages: Track Projects, Count Stitches, Pattern Library), `multiplatform-settings` for `hasSeenOnboarding` persistence (SharedPreferences/NSUserDefaults), reusable `EmptyStateView` composable with icon + CTA button, Compose `HorizontalPager` (Android) + SwiftUI `TabView` (iOS), navigation integration (onboarding before auth, local-only compatible), iOS `ContentUnavailableView` with actions closure, `PreferencesModule` Koin DI, 2 new + 10 updated Maestro E2E flows with shared helper, code review APPROVED (572 shared tests)

- **Phase 26**: Public Pattern Discovery — Browse/search/fork public patterns, PublicPatternDataSource interface, GetPublicPatternsUseCase (server-side ilike search), ForkPublicPatternUseCase (deep-copy without Share record), DiscoveryViewModel (debounced search, client-side filter/sort, fork with Channel navigation), DiscoveryScreen (Compose PullToRefreshBox + SwiftUI .refreshable), Supabase migration 011 (visibility index), ProjectList Discover button (both platforms), 2 Maestro E2E flows, 19 new tests (591 shared tests)

- **Phase 27a**: v1 Store Submission Prep (Session 1) — Version bump to 1.0.0 (version.properties + project.yml), privacy policy (EN/JA, GDPR section, no-tracking disclosure), store listing metadata (descriptions, keywords, screenshot strategy, feature graphic spec), Maestro screenshot capture flows (Android 7-screen + iOS 6-screen), feature graphic HTML template (591 shared tests)

- **Phase 27b**: v1 Store Submission Prep (Session 2) — 5 missing ProGuard navigation routes (Onboarding, Settings, PatternLibrary, PatternEdit, Discovery), GitHub Pages privacy policy deployment (EN/JA static HTML, pages.yml workflow), feature graphic real app icon (emoji→512px PNG), GitHub Pages enabled via API (591 shared tests)

- **Phase 27.1**: iOS ViewModel Lifecycle Fix — ScopedViewModel/ProjectDetailHolder holders pin Koin-resolved ViewModels across SwiftUI View re-inits, fixing a real-device onboarding bug where Next/Skip button taps dispatched events to orphan ViewModels while observers stayed bound to the original state flow. Migrated all 12 iOS screens + AppRootView to the holder pattern; added 2 XCTest regression cases and a Maestro `nextButton` flow. Android E2E stabilized against Pixel Launcher ANR via emulator RAM bump (4096 MB), VM heap (1024 MB), CPU pinning, cold-boot settle and dialog-dismiss loops. ADR-007 records the pivot away from v1.0 store submission. (591 shared + 21 iOS UI tests)

- **Phase 28**: Bundle ID Rename — `com.knitnote.*` → `io.github.b150005.knitnote` across Kotlin packages (322 files, 9 source-root directories moved), SQLDelight package, iOS bundle ID + bundleIdPrefix + CFBundleURLName, Android applicationId + namespace, ProGuard rules, Kover exclusions, generated SupabaseCredentials package, Maestro E2E flow appIds (Android + iOS), store-listing + e2e docs. `knitnote://` URL scheme, `knitnote.db` filename, "Knit Note" display name, and `KnitNote*` class names preserved. (591 shared tests × 2 platforms + 19 iOS UI tests)

- **Phase 31**: Structured Chart Viewer — `SymbolRenderTransform` pure helper (commonMain) maps unit-square (y-down, 0..1) symbol coordinates to a screen-space cell rect with axis-aligned rotation around the cell center (consumed by both renderers). Compose `ChartViewerScreen` (Scaffold + Canvas + grid background + FilterChip layer toggles + `Modifier.transformable` pan/zoom + parsed-path memoization + parameter-slot text + `?` placeholder for unknown symbol ids). SwiftUI `StructuredChartViewerScreen` mirror via shared bridge (`ScopedViewModel` pattern, magnification + drag gestures, `PathCommandCache` `ObservableObject`). `ChartViewerViewModel` observes by patternId with error surfacing. Wired through Koin (`SymbolCatalog` single, ViewModel factory), NavGraph (`ChartViewer(patternId)`), and ProjectDetail "View structured chart" entry on both platforms. +15 commonTest (SymbolRenderTransform 12 + ChartViewerViewModel 3 then later 4 with error path).

- **Phase 30.1**: Knitter review + Symbol Dictionary — `SymbolGalleryScreen` on both platforms (Compose `LazyVerticalGrid` / SwiftUI `LazyVGrid` over `SymbolCatalog.all()`, each card shows the rendered glyph plus JA + EN labels and id always visible per design direction until Phase 33 i18n). `SymbolGalleryViewModel` (category filter-ready, search-ready state shape). ProjectList entry point added (Android top-bar `GridView` IconButton, iOS overflow menu entry). Knitter written review committed to `docs/en/symbol-review/phase-30.1.md` (+ `docs/ja/`); ADR-008 addendum records next-category decision (`jis.crochet.*`) and geometry follow-up plan. +4 commonTest.

- **Phase 30.1-fix**: `KnitSymbols.kt` geometry corrections per knitter advisory — purl bar narrowed, cable under-strokes broken, SSK/k2tog/p2tog/k3tog redrawn as stem+slash, `std.cyc.kfb` namespace addition.

- **Phase 30.2 (+ fix)**: `jis.crochet.*` catalog first pass — ~25 glyphs (ch / sc / hdc / dc / tr / sl-st / cluster variants) per JIS L 0201 Table 2 + CYC. Follow-up fix added `SymbolDefinition.fill` field for glyphs that JIS / publisher convention draws filled (sl-st, bobble); stroke-vs-fill is now per-symbol.

- **Phase 30.3**: crochet catalog extension — `jis.crochet.{reverse-sc, puff, hdc-cluster-3}`.

- **Phase 30.4**: Knitter-priority glyph bundle — 7 new `jis.crochet.*` glyphs (`hdc-cluster-5` widthUnits=2 mirror of `dc-cluster-5`; `dc-crossed-2` widthUnits=2 aran-style X with per-stem top-bar+slash; `bullion` two-coil spring silhouette, JIS-silent; `solomon-knot` two-arc open loop, JIS-silent; `picot-4`/`picot-5` w=1 with progressive loop size; `picot-6` w=2 arch per ADR-009 §8 clause). `picot-3` gains bare `"picot"` alias per ADR-009 §4 (shortest family member carries the stem). `hdc-cluster-3` outer stems widened 0.25/0.75 → 0.22/0.78 per Phase 30.3 deferred polish (no-slash hdc-cluster stems read as one thick line on 24dp low-DPI Android without the gap). Existing `all entries declare a JIS reference to Table 2` test amended to exempt `solomon-knot` + `bullion` (intentionally null `jisReference`; JIS L 0201 Table 2 does not enumerate either). Knitter advisory caught and fixed pre-impl: M1 (solomon-knot closed-pill → open-loop form), m1 (bullion 3 coils → 2 to clear Phase 30.2 §3.5 density threshold at 24dp), m2 (dc-crossed-2 stem-endpoint comment so future readers don't "correct" top-bars back to pre-cross column positions), m3 (picot-6 loop x∈[0.1, 0.9] → x∈[0.05, 0.95] so widthUnits=2 stretch truly spans ~2 cells). Catalog total 35 crochet glyphs (~95% JP commercial coverage). Code review: APPROVED after 1 LOW fix (bullion `"bullion"` alias added for cross-convention lookup symmetry with `reverse-sc`'s `"rsc"` alias). +8 commonTest (776 total).

- **Phase 33**: i18n skeleton — 21 seed keys synced across `values/strings.xml` + `values-ja/strings.xml` + `iosApp/.../Localizable.xcstrings`. `iosApp/project.yml` CFBundleDevelopmentRegion + CFBundleLocalizations. `scripts/verify-i18n-keys.sh` drift-check wired into CI `shared-checks`. `docs/{en,ja}/i18n-convention.md` documents role-prefixed key naming. Consumer wiring from shared Compose screens is a follow-up (see Tech Debt Backlog).

- **Phase 33.1**: i18n consumer wiring (plumbing + chart editor reference) — adds the `compose.components.resources` plugin to `shared/build.gradle.kts` with explicit `packageOfResClass = "io.github.b150005.knitnote.generated.resources"` so downstream imports are stable across any future `shared` rename. New source-of-truth pair `shared/src/commonMain/composeResources/values{,-ja}/strings.xml` holds the 33-key superset (21 existing + 12 editor-specific: `title_edit_chart`, `action_more_options`, `state_empty_chart`, `dialog_parameter_edit_title`, `dialog_parameter_enter_title`, `label_craft`, `label_reading`, `label_craft_{knit,crochet}`, `label_reading_{knit_flat,crochet_flat,round}`). `ChartEditorScreen.kt` now resolves every user-visible literal via `stringResource(Res.string.*)` — including craft/reading dropdown labels via private `craftLabelKey` / `readingLabelKey` helpers returning `StringResource`. `slot.enLabel` in `ParameterInputDialog` intentionally NOT migrated (SymbolCatalog labels are deferred per Phase 33 non-goals — ADR-008). `verify-i18n-keys.sh` extended to enforce 5-source key parity (androidApp en/ja + shared en/ja + iOS xcstrings) pivoting on androidApp en as canonical anchor — transitive coverage confirmed via chained diffs. Kover exclusion added for `generated.resources.*` (plugin-generated) and `ChartEditorScreenKt*` (previously missing from the Compose-UI exclusion list — filled the gap while adjacent). `i18n-convention.md` (EN + JA) updated to describe the dual Android-side source split (`R.string.*` for native, `Res.string.*` for shared Compose) and document the 5-file add/remove protocol. No new shared tests — CMP-generated `Res` is infrastructure, not behavior. Code review: APPROVED (0 CRITICAL/HIGH, 2 MEDIUM fixed pre-commit — Kover exclusion + script label ambiguity). 776 shared tests (unchanged). **Scope cut**: only `ChartEditorScreen.kt` migrated as the reference surface — other shared screens (ChartViewerScreen, SymbolGalleryScreen, ProjectListScreen, PatternLibraryScreen, etc.) still hardcode English and need the same treatment as follow-up work.

- **Phase 32.1**: StructuredChart schema v2 — `CraftType { KNIT, CROCHET }` + `ReadingConvention { KNIT_FLAT, CROCHET_FLAT, ROUND }` enums added to the document envelope. Schema bump 1→2 is backward compatible (v1 rows read with defaults, promoted to v2 on next save). `computeContentHash` unchanged (metadata lives outside drawing identity). `UpdateStructuredChartUseCase` has a `metadataUnchanged` guard + auto-promote. No DDL change. ADR-008 Phase 32.1 addendum. +15 commonTest (720 total).

- **Phase 32**: Chart Editor MVP — tap-to-place authoring with undo/redo and save. Shared: `ChartEditorViewModel` (one-shot load via `GetStructuredChartByPatternId` — intentionally not observing so external Realtime does not stomp mid-edit; conflict resolution is Phase 37 scope), `EditHistory` (50-entry `ArrayDeque` snapshot ring buffer with overflow eviction + redo-clear on record), `GridHitTest` (screen→grid with y-flip), `ChartEditorScreen` (Scaffold + Canvas + palette), `SymbolPaletteStrip` (category chips + eraser + symbol cells), `cellScreenRect` extracted from viewer to shared `SymbolDrawing.kt`. iOS: `StructuredChartEditorScreen.swift` via `ScopedViewModel`, `PalettePathCache` ObservableObject, Closeable cancelled in `onDisappear`. Koin + NavGraph + ProjectDetail entries on both platforms. **Scope cuts (MVP)**: `CraftType.KNIT` / `ReadingConvention.KNIT_FLAT` hardcoded (picker = Phase 32.2), 8×8 default grid (picker = Phase 35), flat rect only (polar = Phase 35), Maestro E2E deferred (requires linked-pattern prerequisite — see Tech Debt Backlog), i18n hardcoded English. **Known gotcha that burnt CI**: Kotlin/Native rejects backticked test-fun names containing `(`, `)`, `,` — JVM tolerates. 3 tests renamed in fix commit (6b65df4). +30 commonTest (750 total). Code review: 2 HIGH + 4 MEDIUM + 3 LOW fixes landed before merge (iOS Closeable leak, `save()` stale snapshot, `!!` on errorMessage, vacuous test assertion, eraser icon semantics, pointerInput over-keying, iOS palette SVG parse cache, `availableCategories` memoization).

- **Phase 32.3**: Parametric symbol input per ADR-009 §7 — Phase 32 MVP wrote `symbolParameters = emptyMap()` for every placement, so `ch-space` (the only parametric glyph today) stuck rendering the literal `"n"` placeholder. 32.3 forks `placeCell`: eraser and non-parametric selections keep the immediate-commit path; parametric selections open an inline input dialog and defer commit to `ConfirmParameterInput`. Re-edit reopens the same dialog pre-populated when the user taps a parametric cell with the matching symbol selected. `PendingParameterInput` holds (`symbolId`, `x`, `y`, `slots`, `currentValues`, `isEditingExisting`) and every mutating path — `placeCell`, `undo`, `redo` — short-circuits while it is set, so nothing can rewrite `draftLayers` between dialog-open and confirm. `save()` is async but untouched by parametric state and gated by `hasUnsavedChanges`, so it cannot interleave either. Overwrite of a parametric cell with a non-parametric selection intentionally wipes `symbolParameters` — ADR-009 §5 round-trip preservation is scoped to storage, not editor mutation; called out in-code. Compose: `AlertDialog` + `OutlinedTextField` per slot, drafts keyed by `(x, y, symbolId)`. SwiftUI: `NavigationStack` + `Form` sheet driven by a local `@State var isParameterSheetPresented: Bool` (not a reactive binding — avoids a transient confirm-path race where reading back from the state flow in a `Binding.set` closure could spuriously fire `CancelParameterInput`); `.id("\(symbolId)@\(x),\(y)")` forces a fresh view per-pending so `.onAppear` reseeds cleanly. +8 commonTest (768 total). Code review: 2 HIGH + 4 MEDIUM + 3 LOW — fixed before commit (undo/redo pending-guards, sheet presented-flag decoupling, `.id()` robustness, test 33 catalog pre-condition, ktlint import hygiene, ADR §5 comment, concurrency-invariant comment block).

- **Phase 32.4**: System-back discard dialog — Phase 32 only intercepted the top-bar back button, so Android hardware / predictive-back and iOS NavigationStack back both silently popped the editor and dropped unsaved edits. New `expect/actual` helper `ui/platform/SystemBackHandler` wires `androidx.activity.compose.BackHandler` on Android and stays a no-op on iOS (shared Compose isn't rendered on iOS for the editor — SwiftUI is). `ChartEditorScreen.kt` gates the intercept on `state.hasUnsavedChanges`, so the normal clean-state pop behavior is preserved. iOS `StructuredChartEditorScreen.swift` now sets `.navigationBarBackButtonHidden(true)` + a custom `ToolbarItem(.topBarLeading)` back button that routes through a new local `attemptBack(state:)` helper (mirrors the Compose `attemptBack` closure). `androidx.activity.compose` dep added to shared `androidMain`. Kover exclusion for the new `ui.platform.*` package (Compose plumbing, JVM-untestable; logic is in `ChartEditorViewModel` tests). No new commonTest — the discard-dialog trigger is `state.hasUnsavedChanges` which is already covered. Known iOS limitation: edge-swipe-from-left still pops without the dialog (NavigationStack doesn't expose a swipe-disable API without dropping to UIKit); documented in CI Known Limitations. 768 shared tests (unchanged).

- **Phase 32.2**: Chart Editor craft/reading picker + iOS Closeable leak audit — surfaces `CraftType` + `ReadingConvention` in the editor so CROCHET authoring writes correct metadata instead of the Phase 32 KNIT default. `ChartEditorState` gains `draftCraftType` / `draftReadingConvention`; `SelectCraft` / `SelectReading` events update draft via atomic `_state.update { if (== val) return@update it; it.copy(...) }` guard. Metadata edits deliberately skip `EditHistory` (drawing-only) but still mark `hasUnsavedChanges` on existing charts. On a new chart, metadata alone does not mark dirty — cells are still required to save (21b/21c symmetric tests). `CreateStructuredChartUseCase` now accepts `craftType` + `readingConvention` params (defaults KNIT / KNIT_FLAT). Compose: TopBar overflow `MoreVert` → `DropdownMenu` with non-interactive `Text` section headers (no disabled-button a11y semantics), U+2713 check prefix. SwiftUI: `ellipsis.circle` `Menu` with nested `Picker`s. **Bundled iOS Closeable leak audit** (same anti-pattern fixed in Phase 32 `StructuredChartEditorScreen`): `PatternEditScreen`, `SharedContentScreen`, `DiscoveryScreen` now use `@State Closeable` + `close()` in `.onDisappear`; Discovery / SharedContent additionally guard against `.task { }` re-entry by closing prior subscription before reassigning. `iosApp/Info.plist` regenerated by xcodegen to align with Phase 33 i18n (`CFBundleDevelopmentRegion=en`, `CFBundleLocalizations=[en,ja]`). Code review: APPROVED after 2 HIGH + 2 MEDIUM fixes (TOCTOU guard moved inside `_state.update`, 21c test added, `.task { }` re-entry leak defense, DropdownMenu header a11y). +10 commonTest (760 total). **Gotcha**: `./gradlew ktlintCheck` must run locally before push — JVM toolchain tolerated prior multiline layout; CI ktlint required `computeUnsavedChanges(...)` to start on a new line (fix commit 739fc82).

### Deferred (superseded by ADR-007)
- **Phase 27c**: v1 Store Submission (Final) — staged but not executed. Will re-open only after the structured chart vision (Phase 29–40) reaches beta readiness.

### Planned — Structured Chart Authoring (per ADR-007)
- **Phase 34**: Per-Segment Progress — stitch/section granularity for todo/wip/done, progress visualization overlays on the chart viewer. Needs an ADR (segment table vs. layer extension).
- **Phase 35**: Chart Editor (Advanced) — symmetry copy, layer ops, snap grid, polar-coordinate (round) mode, grid size picker.
- **Phase 36**: Chart Discovery + Fork — extend Discovery to structured charts, upgrade fork to a commit-rooted copy with author attribution.
- **Phase 37**: Collaboration Core — commit history, branch, diff view (MVP). Minimal Git semantics; no CRDT in v1.
- **Phase 38**: Pull Request — comment + approval workflow, merge strategies, conflict visualization.
- **Phase 39**: Closed Beta — TestFlight internal + Play Internal Testing distribution to invited users; data migration compat freezes here.
- **Phase 40**: v1.0 Public Release — re-open the Phase 27c submission work with the finalized product name and bundle ID.

### Post-v1.0
- **Phase 24**: Push Notifications — FCM/APNs, Supabase Edge Functions
- **Phase 21**: macOS Target — based on user demand after v1.0

## Tech Debt Backlog

Items deferred across phases. When reopening one, cut a dedicated PR — do not bundle into unrelated feature work unless the commit message calls it out explicitly.

### Chart Editor (Phase 32 follow-ups)
- **i18n sweep across remaining shared screens**: Phase 33.1 landed the `compose.components.resources` plumbing and migrated `ChartEditorScreen.kt` as the reference. The following shared Compose screens still hardcode English and need the same `stringResource(Res.string.*)` treatment: `ChartViewerScreen`, `SymbolGalleryScreen`, `ProjectListScreen`, `PatternLibraryScreen`, `PatternEditScreen`, `ProjectDetailScreen`, `OnboardingScreen`, `SettingsScreen`, `DiscoveryScreen`, `SharedContentScreen`, `SharedWithMeScreen`, `ActivityFeedScreen`, `LoginScreen`, `ProfileScreen`, `CommentSection` (16 total). Each migration adds its own domain keys to all 5 i18n sources and should be cut as its own PR.
- **ViewModel error-message localization**: `errorMessage: String?` fields on ViewModels are displayed raw to the user — if a ViewModel populates them from a Kotlin exception, Japanese users see English. Need a typed error channel (`UseCaseError` → `StringResource`) rather than free-form strings. Deferred per Phase 33 non-goals; reopen after the shared-screen i18n sweep completes.
- **Maestro E2E for chart editor**: deferred because the "Edit structured chart" entry is gated on `state.pattern != null` (Project → Pattern linkage). Flow needs a pattern-create pre-step (~60 LOC). Reopening depends on E2E value vs. the 17-case ViewModel test coverage.

### KMP / Compose recurring gotchas
- **Kotlin/Native test-name restriction**: backticked function names containing `(`, `)`, `,` fail `:shared:compileTestKotlinIosSimulatorArm64` / `IosX64` but pass `:shared:testAndroidHostTest` (JVM). Hit Phase 32 CI once (commit 6b65df4 is the fix). Pre-push verification should include the iOS simulator compile target; consider adding to `docs/en/` or a pre-push hook.
- **ktlint multiline formatting**: multiline expressions (e.g. `hasUnsavedChanges = computeUnsavedChanges(...)` with wrapped args) must start the call on a new line. JVM builds tolerate the inline form; CI `ktlintCommonMainSourceSetCheck` rejects it. Pre-push verification should include `./gradlew ktlintCheck` (or at minimum run `ktlintFormat` before committing). Hit Phase 32.2 CI once (commit 739fc82 is the fix).
- **`BackHandler` not in commonMain**: `androidx.activity.compose.BackHandler` cannot be used directly from shared Compose screens. Resolved in Phase 32.4 by adding `ui/platform/SystemBackHandler.kt` (`expect/actual`). Use `SystemBackHandler(enabled, onBack)` from any shared screen that needs to intercept Android system-back. iOS actual is a no-op (iOS uses SwiftUI screens, not shared Compose).

### Deprecated-API cleanup (low priority)
- `rememberSwipeToDismissBoxState` — 3 screens (PatternLibrary, ProjectDetail, ProjectList). No non-experimental CMP 1.10.3 replacement yet.
- `MenuAnchorType` typealias in CreateProjectDialog — rename to `ExposedDropdownMenuAnchorType`.
- `AuthRepositoryImpl.RefreshFailureCause` — migrate to `AuthEvent.RefreshFailure(cause)` per supabase-kt API change.

### Structured chart cosmetic polish
- `jis.crochet.reverse-sc` chevron form — awaiting real-user feedback before changing.

### Future opportunistic crochet catalog additions (post-30.4)
- Foundation stitches (`fsc`, `fdc`) — US-dominant convention; defer until a JP-pattern user reports needing them.
- Extended sc (`exsc`) — CYC-standard, JIS silent.
- Spike stitches (long-loop variants) — colorwork crochet.
- `turning-ch-N` family — ADR-009 §9 open question (parametric vs. discrete family); reopens when a concrete non-dc-height turning chain shows up in a user pattern.
