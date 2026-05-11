# Architecture — Current Shape

> **Purpose**: system-level current-shape map of Skeinly as it stands in `main` today. Read this first when joining the project or returning after a long break. For feature-level shape, follow the [spec/](spec/) links from here. For *why* a design choice was made, follow the [ADR](adr/) references at the end.
>
> **Audience**: a developer or contributor who needs to understand "where things live" without reading the 500+ line CLAUDE.md or the 20+ ADRs cold.
>
> **Scope**: codebase shape, vendor surface, data flow at the system level. Each feature has its own current-shape doc under [spec/](spec/); operational procedures live under [ops/](ops/).

## One-paragraph summary

Skeinly is a KMP knitting app (Android Compose + iOS SwiftUI) with shared business logic in `shared/`. The backend is Supabase (Postgres + Auth + Realtime + Storage + Edge Functions). Subscriptions are routed through RevenueCat; push notifications through APNs (iOS) + FCM (Android) via a Supabase Edge Function fan-out triggered by Database Webhooks. Symbol palette content can be dynamically delivered (per-pack) from Supabase Storage through an entitlement-gated Edge Function so we can ship new symbols without an app update. Releases are tag-driven (push `v0.X.Y`) and CI uploads to TestFlight + Play Internal Testing in parallel.

## Module map

```
skeinly/
├── shared/                    KMP shared module — domain logic, ViewModels, data access
│   ├── src/commonMain/        Kotlin shared across platforms
│   │   ├── kotlin/
│   │   │   ├── data/          Repositories, remote / local data sources, sync managers
│   │   │   ├── domain/        Models, repositories (interfaces), use cases, services
│   │   │   ├── ui/            Compose Multiplatform screens + ViewModels
│   │   │   └── di/            Koin modules
│   │   ├── composeResources/  Localized strings (en + ja), drawables
│   │   └── sqldelight/        Local DB schema (.sq files)
│   ├── src/commonTest/        Shared tests (kotlin.test, Turbine)
│   ├── src/androidMain/       Android-specific actuals (Context-bound APIs)
│   ├── src/androidHostTest/   JVM-side tests of the shared module
│   └── src/iosMain/           iOS-specific actuals (Foundation / UIKit interop)
├── androidApp/                Android host (MainActivity, push service, Koin init)
├── iosApp/                    Xcode project — SwiftUI screens, KMP bridge, AppDelegate
├── supabase/
│   ├── migrations/            Versioned SQL migrations applied to prod Postgres
│   └── functions/             Deno Edge Functions (4 deployed)
├── docs/
│   ├── en/                    Source-of-truth documentation
│   │   ├── architecture.md    ← you are here
│   │   ├── spec/              Feature current-shape docs
│   │   ├── ops/               Operator runbooks
│   │   ├── adr/               Architecture Decision Records (rationale)
│   │   └── phase/             Historical phase log
│   └── ja/                    Maintained translations
├── e2e/                       Maestro flows (Android + iOS)
└── .claude/                   Agent instructions, agent definitions, internal specs
```

## Architecture layers

```
┌───────────────────────────────────────────────────────────────┐
│  UI                                                            │
│    Compose Multiplatform screens (Android primary host)        │
│    SwiftUI screens (iOS host; shares ViewModels via KoinHelper)│
├───────────────────────────────────────────────────────────────┤
│  ViewModel                                                     │
│    Single state object (StateFlow), event sink, suspend flows  │
├───────────────────────────────────────────────────────────────┤
│  UseCase / Domain service                                      │
│    Pure business logic, no Compose / SwiftUI imports           │
├───────────────────────────────────────────────────────────────┤
│  Repository (interface in domain/, impl in data/)              │
│    Coordinates local + remote data sources, caching            │
├───────────────────────────────────────────────────────────────┤
│  DataSource                                                    │
│    Local: SQLDelight, multiplatform-settings                   │
│    Remote: supabase-kt (Postgrest / Functions / Auth / Storage)│
│            Ktor HTTP clients (for raw URL fetches)             │
└───────────────────────────────────────────────────────────────┘
```

Dependency direction is strictly downward. UI → ViewModel → UseCase → Repository (interface) → DataSource. The repository interface lives in `domain/`; the impl in `data/`. ViewModels never reach past the repository boundary.

## Vendor surface

| Vendor | Project layout | What flows through |
|---|---|---|
| **Supabase** (1 project) | `Skeinly` prod + local CLI for dev | Postgres tables, RLS, Realtime, Auth (email + OAuth), Storage bucket `symbol-packs`, 4 Edge Functions |
| **Firebase** (2 projects) | `Skeinly` Blaze (prod) + `Skeinly-Dev` Spark (dev) | FCM for Android push; project isolation via `google-services.json` per environment |
| **Apple Push** | Apple Developer Push capability | APNs `.p8` for iOS push (Edge Function side); production-only `aps-environment` |
| **RevenueCat** (1 project) | `Skeinly` iOS + Android Apps | Subscription product receipts; webhook → `revenuecat-webhook` Edge Function writes `subscriptions` |
| **Sentry** | `skeinly-ios` + `skeinly-android` per-platform | Crash + non-fatal capture, beta gated |
| **PostHog** (1 project) | iOS + Android share | Product analytics, opt-in only |
| **GitHub** | `b150005/skeinly` public repo + `Skeinly Feedback` GitHub App | Source, CI, release automation, bug-report Issue creation via `submit-bug-report` Edge Function |
| **Google Play** | 1 app + Internal/Closed/Open testing tracks | Android distribution; CI uploads to Internal Testing as Draft |
| **Apple ASC** | 1 app + TestFlight Internal + External | iOS distribution; CI uploads via fastlane |

## Supabase Postgres surface

Tables, grouped by domain. All tables are RLS-enabled. Migrations live at [supabase/migrations/](../../supabase/migrations/).

| Domain | Tables |
|---|---|
| **Auth + Account** | `auth.users` (Supabase-managed); `delete_own_account()` RPC for account deletion (ADR-005) |
| **Pattern + Chart** | `patterns`, `charts`, `chart_variations` (formerly `chart_branches`), `chart_versions` (formerly `chart_revisions`) |
| **Project + Progress** | `projects`, `project_segments`, `progress`, `chart_segments` |
| **Collaboration** | `suggestions` (formerly `pull_requests`), `suggestion_comments` (formerly `pull_request_comments`) |
| **Subscription** | `subscriptions` (RevenueCat-mirrored); `upsert_subscription_from_webhook()` RPC; `is_pro(uid)` helper |
| **Symbol packs** | `symbol_packs` (catalog), `symbol_pack_locales`, `user_symbol_pack_state` |
| **Push** | `device_tokens` |
| **Sharing** | `shares` (read-only snapshots) |
| **Discovery** | indexes / views over `patterns` for the discovery surface |

The full schema-history view is `supabase/migrations/` (numerically ordered files). The terminology rename map between code-level Git vocabulary and user-facing knitter vocabulary is in [README.md "Vocabulary mapping"](../../README.md#vocabulary-mapping-developer-reference).

## Supabase Edge Functions

Four functions deployed (Deno + supabase-js + djwt + WebCrypto):

| Function | Trigger | Auth posture | What it does |
|---|---|---|---|
| `notify-on-write` | Database Webhook on `suggestions` / `suggestion_comments` | Custom Bearer secret (`SKEINLY_DATABASE_WEBHOOK_SECRET`); `verify_jwt = false` | Fans out PR-collaboration events to APNs (iOS) + FCM (Android). Reads `device_tokens` to find recipients, mints APNs ES256 JWT + FCM SA → OAuth, sends, deletes invalidated tokens. |
| `revenuecat-webhook` | RevenueCat webhook | Custom Bearer secret (`REVENUECAT_WEBHOOK_SECRET`); `verify_jwt = false` | Maps 11 RevenueCat event types to `subscriptions.status` transitions via `upsert_subscription_from_webhook` RPC. Out-of-order-safe via `last_verified_at` guard. |
| `request-pack-download` | Mobile app invokes | User session JWT in `Authorization`; `verify_jwt = true` | Mints 5-min signed URLs against private `symbol-packs` Storage bucket after Pro entitlement check. |
| `submit-bug-report` | Mobile app invokes (Settings → Send Feedback + gestures in beta builds) | `apikey: <publishable_key>` only; `verify_jwt = false` | Authenticates as the Skeinly Feedback GitHub App and creates an Issue on `b150005/skeinly`. Real auth is downstream at GitHub. Per-source rate-limit (5/h). |

Per-function details live in `supabase/functions/<name>/README.md` (slim code-side reference) and the feature spec or operator runbook that exercises the function.

## Data flow examples

### Authoring a chart cell

```
ChartEditorScreen.onCellTap
  → ChartEditorViewModel.onEvent(CellTapped(...))
  → state.update { ... }                  StateFlow emit
  → CompositeSymbolCatalog.get(symbolId)  synchronous, render hot path
     ├─ downloaded snapshot lookup
     ├─ tier === PRO → EntitlementResolver.isPro() gate
     └─ fallback: bundled compile-time catalog
```

### Opening a Suggestion from another user

```
PullRequestDetailScreen open
  → PullRequestDetailViewModel.loadInitial()
  → SuggestionRepository.observe(suggestionId)   Flow from SQLDelight + remote
     ├─ Supabase Postgrest SELECT (RLS-filtered)
     ├─ SQLDelight cache for offline reads
     └─ Supabase Realtime subscription for comment streaming
```

### Receiving a push notification (iOS, app in foreground)

```
APNs                                              Real network event
  → iOS UNUserNotificationCenter delegate (AppDelegate.swift)
  → NotificationCenter post .openPushRoute
  → AppRootView.onReceive(.openPushRoute)
  → parsePushRoute("pull-request/<id>") → Route.pullRequestDetail(prId)
  → NavigationStack push
```

### Symbol pack download (cold start, online)

```
App launch
  → applicationScope.launch { SymbolPackSyncManager.sync() }
  → RemoteSymbolPackDataSource.fetchManifest()      RLS open SELECT symbol_packs
  → LocalSymbolPackDataSource.replaceManifest(...)  SQLDelight diff
  → For each pack with version mismatch:
      → RemoteSymbolPackDataSource.requestDownload(packId)
         → supabase-kt functions.invoke("request-pack-download", { pack_id })
         → 200: { payload_url, ttl, version, size }
      → HttpClient.get(payload_url)                  signed URL, 5-min TTL
      → LocalSymbolPackDataSource.upsertPayload(...)
  → CompositeSymbolCatalog.refresh()                 rebuilds render snapshot
```

## Build + release pipeline

Tag-driven. The single entry point:

```bash
make release-tag-validate          # pre-flight: branch=main, clean tree, tag not used
CONFIRM=yes make release-tag-publish   # tag v$VERSION_NAME, push to origin
```

The `release.yml` workflow runs three parallel jobs on tag push:
- **build-android**: signed AAB via Gradle → `:androidApp:publishBundle` uploads to Play Internal Testing as Draft (DRAFT is load-bearing — testers don't see it until manual rollout). APK also produced as a GitHub Actions artifact.
- **build-ios**: fastlane `beta` lane signs + archives + exports → `upload_to_testflight` pushes to ASC. Build number = `github.run_number`.
- **create-release**: draft GitHub Release with both binaries attached.

Pre-push invariants: `make ci-local` reproduces every check CI runs (ktlint + KMP tests + iOS xcodebuild + iOS XCUITest + Maestro Android + Maestro iOS). ~30–45 min wall-clock; the structural guarantee that "local green ⇒ CI green".

Detailed release procedure: [docs/en/ops/release.md](ops/release.md).
Secrets registry: [docs/en/release-secrets.md](release-secrets.md).
Rotation runbook: [docs/en/ops/secrets-rotation.md](ops/secrets-rotation.md).

## Documentation surface

| Lane | Purpose | Where |
|---|---|---|
| **WHAT IS** (current shape) | What the system / a feature looks like right now | This file + [spec/](spec/) |
| **WHY** (rationale) | Why a design decision was made; alternatives considered | [adr/](adr/) |
| **WHAT TO DO** (procedures) | Step-by-step operational tasks | [ops/](ops/) |
| **HISTORICAL** (log) | How we got here over time | [phase/](phase/) + ADR revision histories |

When a feature's implementation drifts from its ADR, update the spec; only update the ADR if the *decision* changed (not just the implementation). The ADR's `Revision history` section is the trail for decision-level changes.

## Where to start by task

| Task | Read in order |
|---|---|
| Extend chart editor surface | [spec/chart-editor.md](spec/chart-editor.md) → relevant ADR |
| Add a new symbol pack | [spec/symbol-pack-delivery.md](spec/symbol-pack-delivery.md) → [ops/content-publishing.md](ops/content-publishing.md) |
| Add a new collaboration event type | [spec/suggestion-flow.md](spec/suggestion-flow.md) → ADR-014 |
| Roll a new release | [ops/release.md](ops/release.md) |
| Triage a production failure | [ops/incident-playbook.md](ops/incident-playbook.md) |
| Set up a new beta tester | [ops/beta-testing.md](ops/beta-testing.md) |
| Rotate a leaked secret | [ops/secrets-rotation.md](ops/secrets-rotation.md) |

## Key cross-cutting ADRs

(Read these when their topic surfaces; not required for cold start.)

- [ADR-001](adr/001-backend-platform.md) — why Supabase
- [ADR-002](adr/002-data-model-design.md) + [ADR-004](adr/004-supabase-schema-v1.md) — data model + RLS philosophy
- [ADR-005](adr/005-account-deletion.md) — account deletion cascade
- [ADR-007](adr/007-pivot-to-chart-authoring.md) — the structured chart pivot (foundational)
- [ADR-008](adr/008-structured-chart-data-model.md) + [ADR-009](adr/009-parametric-symbols.md) — chart data model + symbol semantics
- [ADR-013](adr/013-phase-37-collaboration-core.md) + [ADR-014](adr/014-phase-38-pull-request-workflow.md) — collaboration design
- [ADR-016](adr/016-phase-41-pro-subscription-dynamic-symbols.md) — Pro subscription + symbol pack delivery
- [ADR-017](adr/017-phase-24-push-notifications.md) + [ADR-018](adr/018-phase-24-3-push-send-paths.md) — push architecture
- [ADR-020](adr/020-phase-39-w5-bug-report-proxy.md) — bug-report proxy
