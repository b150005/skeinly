# Skeinly — KMP Knitting App

## About This Project

**Skeinly** is a Kotlin Multiplatform (KMP) mobile app for knitters. It helps users manage knitting patterns (charts), track progress on projects, and share patterns/progress with others.

- **Platforms**: Android (Jetpack Compose), iOS (SwiftUI), macOS (SwiftUI, stretch goal)
- **Architecture**: KMP shared logic + platform-native UI
- **Data Strategy**: Local-first with cloud sync (Firebase or Supabase — to be decided via ADR)
- **Language**: Kotlin (shared), Kotlin (Android UI), Swift (iOS/macOS UI)

Created from the [ECC Base Template](https://github.com/b150005/ecc-base-template).

## Project Structure

```
skeinly/
├── shared/                    # KMP shared module (commonMain / commonTest / androidMain / iosMain)
├── androidApp/                # Android application (Jetpack Compose UI)
├── iosApp/                    # iOS/macOS application (SwiftUI, Xcode project)
├── docs/
│   ├── en/                    # English documentation (source of truth)
│   └── ja/                    # Japanese translations (reference docs only — see Documentation Convention)
└── .claude/                   # Agent team and Claude Code configuration
```

## Documentation map

Each doc has a deliberate lane. Match the question to the lane before reading or writing:

| Lane | Purpose | Where |
|---|---|---|
| **WHAT IS** | Current shape of the system / a feature | [docs/en/architecture.md](../docs/en/architecture.md) (system) + [docs/en/spec/](../docs/en/spec/) (per-feature) |
| **WHY** | Design rationale + alternatives considered | [docs/en/adr/](../docs/en/adr/) |
| **WHAT TO DO** | Step-by-step operator runbooks | [docs/en/ops/](../docs/en/ops/) |
| **WHAT NEXT** | Forward backlog + standing tech debt | [docs/en/phase/roadmap.md](../docs/en/phase/roadmap.md) + [docs/en/phase/tech-debt.md](../docs/en/phase/tech-debt.md) |
| **HISTORICAL** | How we got here over time | [docs/en/phase/](../docs/en/phase/) (completed-archive / ci-known-limitations / tasks) + ADR revision histories |

When implementation drifts from an ADR, update the spec (or system architecture); only update the ADR if the *decision* changed (not just the implementation). The ADR `Revision history` block is the trail for decision-level changes.

Quick entry points: joining/returning → `docs/en/architecture.md`; extending a feature → `docs/en/spec/<feature>.md`; ops task → `docs/en/ops/README.md` then the matching runbook; decisions → `docs/en/adr/`; parallel scheduling → `## Parallel-Worktree Workflow Protocol` (forward backlog / debt / closed history live in the orchestrator-exclusive EN-only `docs/en/phase/` lane; workers use `docs/en/phase/tasks/<id>.md`).

## Architecture Principles

- **Clean Architecture** with clear separation: UI → ViewModel → UseCase → Repository → DataSource
- **KMP shared module** contains: domain models, use cases, repository interfaces, and common utilities
- **Platform-native UI**: Android = Jetpack Compose; iOS/macOS = SwiftUI + UIKit (via `UIViewControllerRepresentable`/`UIViewRepresentable` where SwiftUI alone is insufficient)
- **Repository pattern** for data access abstraction (local DB + remote sync)
- **Immutable data structures**: Kotlin `data class` with `copy()`, Swift structs
- **Dependency injection**: Koin for shared/Android, native DI patterns for iOS
- **Unidirectional data flow**: state flows down, events flow up

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

## Development Environment

### Required versions

| Component | Minimum | Source of truth |
|---|---|---|
| macOS | 26.0 (Tahoe) | [README.md](../README.md) Prerequisites |
| Xcode | 26.0 | [README.md](../README.md) Prerequisites; verified by `make verify-xcode` |
| JDK | 17 | Temurin recommended |
| Android SDK | API 36 | platform-tools (`adb`) required |
| iOS deployment target | 17.0 | [iosApp/project.yml](../iosApp/project.yml) `deploymentTarget.iOS` |

**Why Xcode 26 / macOS 26 / iOS 26 SDK?** Apple App Store Connect submissions since **2026-04-28** require apps built with Xcode 26+ using the iOS 26 SDK or later. CI runners (`macos-latest` on GitHub-hosted) ship with Xcode 26+ since the enforcement date; local builds must match. Full requirement + references: [docs/en/ops/repo-policy.md](../docs/en/ops/repo-policy.md#apple-app-store-sdk-requirements).

**Build SDK vs deployment target**: build with the iOS 26 SDK (latest), runtime deployment target stays iOS 17 — independent. iOS 26-only APIs gated with `if #available(iOS 26.0, *)` (Swift) / `Build.VERSION.SDK_INT` (Android-side equivalents). The iOS 26 SDK applies Liquid Glass styling to native controls by default; Phase 18 adopted Liquid Glass tokens, so no opt-out is required.

**Verification before release**: `make release-ipa-local` invokes `make verify-xcode` first to fail fast if Xcode < 26. Apple's upload-time enforcement rejects the artifact downstream if skipped.

## Domain Model (Core Concepts)

- **Pattern**: A knitting pattern with metadata (name, difficulty, gauge, yarn info, chart/reference images)
- **Chart** (model: `Chart`): The symbol-grid editor surface — cells × layers × symbols. JA UI: 「編み図」.
- **Project**: An instance of working on a pattern (start date, current row/round, status)
- **Progress**: Row-by-row or section-based progress tracking with timestamps
- **Share**: Exported pattern or progress snapshot for sharing with others
- **Variation** (model: `ChartVariation`, table: `chart_variations`): A named parallel design forked off a base chart. JA UI: 「アレンジ」.
- **Version** (model: `ChartVersion`, table: `chart_versions`): An append-only saved snapshot of a chart. JA UI: 「バージョン」.
- **Suggestion** (model: `Suggestion`, table: `suggestions`): A proposed change to another user's chart. JA UI: 「提案」.

### Vocabulary mapping (Git mental model → user-facing language)

The collaboration model is a Git-shaped DAG internally, but user-facing language is knitter-friendly per the 2026-05-10 terminology audit (`audits/terminology-audit-2026-05-10.md`). Prefer the knitter-friendly term in user-visible copy + ADR text; Git mental models are fine in code comments + ADR technical sections.

| Git concept | Skeinly user-facing (EN) | Skeinly user-facing (JA) | Internal class / table |
|---|---|---|---|
| fork (verb/noun) | Save a copy / Save to my library | コピーを保存 / マイライブラリに追加 | `SaveSharedPatternToLibraryUseCase` (no table; uses `Pattern.parentPatternId`) |
| branch | Variation | アレンジ | `ChartVariation` / `chart_variations` |
| commit / revision | Version | バージョン | `ChartVersion` / `chart_versions` |
| merge | Apply changes | 変更を反映 | `ApplySuggestionUseCase` + RPC `apply_suggestion` |
| pull request | Suggestion | 提案 | `Suggestion` / `suggestions` |
| diff | Comparison | 比較 | `ChartComparison` |
| upstream | original | 元の (e.g. 元のパターン) | (concept; no class) |

See [README.md](../README.md) `## Vocabulary mapping (developer reference)` for the same table for human contributors.

## Agent Team

The **orchestrator** agent (analyzes issues, plans, delegates) coordinates these specialists: **product-manager** (PRD / user stories / acceptance criteria), **market-analyst** (market + competitor research), **monetization-strategist** (pricing / revenue), **ui-ux-designer** (UI/UX, accessibility, usability), **docs-researcher** (API verification, freshness-safe search), **architect** (system architecture, tech decisions), **implementer** (code, architecture + TDD), **code-reviewer** (quality/standards), **test-runner** (test execution, coverage), **linter** (static analysis, style), **security-reviewer** (OWASP Top 10), **performance-engineer** (profiling, bottlenecks), **devops-engineer** (CI/CD, deploy, release), **technical-writer** (docs, changelog, bilingual), **knitter** (domain advisor on knitting symbols / bilingual labels / craft conventions — first-pass reviewer, escalates conflicts to user).

**Ecosystem Detection** — agents detect this as **Kotlin Multiplatform** via: `build.gradle.kts` with KMP plugin; `shared/` with `commonMain`/`androidMain`/`iosMain`; `androidApp/` with Compose deps; `iosApp/` with Xcode + SwiftUI.

## Development Workflow

> Branch protection / PR gates / bypass: [docs/en/ops/repo-policy.md](../docs/en/ops/repo-policy.md) (JA mirror under `docs/ja/ops/`). Active ruleset `main-strict` (id `15581036`); Owner has Admin-role direct-push bypass, everyone else PRs.
> Build/test/lint/release are Makefile targets (`make help`). Pre-push invariant chain: `make ci-local`. Local IPA: `make release-ipa-local`.

0. **Failure Triage Discipline**: when a build/test/E2E/runtime failure is unexpected and not reproducible from your isolated change scope (revert → still fails; fresh `main` checkout → still fails), suspect an upstream library/SDK/tool, not Skeinly code. **Before classifying it as a CI Known Limitation or applying any workaround, search the upstream issue tracker — mandatory, not optional.** Full DETECT → RECORD → TRACK workflow + entry format + workaround-expiry: [.claude/docs/process/upstream-issue-triage.md](docs/process/upstream-issue-triage.md).
1. **Issue Analysis** → orchestrator (GitHub MCP or paste).
2. **Product Planning** → product-manager: PRD, user stories, acceptance criteria.
3. **Research & Reuse** → search GitHub, package registries, docs before writing new code.
4. **Architecture** → architect; significant decisions = ADRs in `docs/en/adr/`.
5. **Implementation** → implementer, TDD (RED→GREEN→IMPROVE). Shared in `shared/src/commonMain/`, Android in `androidApp/`, iOS in `iosApp/`.
6. **Quality Gate** → code-reviewer + linter + security-reviewer + performance-engineer. Fix ALL findings (CRITICAL→LOW), re-review, repeat until APPROVED, only then commit.
7. **Documentation** → technical-writer. If the change touches a feature with a spec under [docs/spec/](docs/spec/), update the spec in the **same commit** (specs = *what is*; *why* → ADR).
8. **Release** → devops-engineer.
8.5 **Final pre-push re-verification (after ALL edits)**: any edit after the invariant chain passed — code-review fix-ups, ktlintFormat, single-line import reorder, default-arg, KDoc — invalidates the prior run. Re-run the FULL `make ci-local` from project root before `git commit`, however trivial the last edit looked. **`make ci-local` is the single canonical pre-push entry point** (session-only `verify-all`/`verify-ios` aliases were folded back in 2026-05-02 after they shipped a runtime gap for three CI failures).
   `make ci-local` layered chain (~30–45 min; needs a booted Android emulator + iOS Simulator): `:shared:ktlintCheck` → `:androidApp:ktlintCheck` → `:shared:compileTestKotlinIosSimulatorArm64` → `:shared:testAndroidHostTest` → `:shared:koverVerify` → `verifyI18nKeys` → `make ios-build` → `make ios-test` → `make e2e-android` → `make e2e-ios`. It reproduces every CI check — single source of truth for "safe to push".
   Ground rules: (a) a one-line `Edit` is not safe — single-line import reorders have gone red in CI; (b) compile-pass ≠ lint-pass (ktlint/compiler rules are orthogonal); (c) `xcodebuild build` (app target) ≠ test-target build (`iosAppTests` excludes `Core/Bridging/`, doesn't link `Shared.framework`); (d) compile-pass ≠ test-pass (XCUITest assertion + Maestro flow regressions surface only at execution — `make ci-local` runs both; never substitute individual targets for the full chain). Motivating incidents: [completed-archive.md](../docs/en/phase/completed-archive.md) / [ci-known-limitations.md](../docs/en/phase/ci-known-limitations.md).
9. **Commit, Push & CI Verification** (when review APPROVED + tests pass):
   - If a `.github/workflows/` file changed, reproduce its build/test commands locally before committing; after push, scan CI logs for warnings (deprecated actions / missing config / fallbacks), not just failures.
   - Conventional commits (feat/fix/refactor/docs/test/chore/perf/ci); push to `origin/main`.
   - **CI-wait policy (since 2026-04-22)**: do NOT block waiting for CI/Security/E2E to go green — push and proceed. Pre-push invariants catch regressions locally; a red prior commit is surfaced at the next session's handoff restate. CI-file reproduction + post-push warning scan still apply on `.github/workflows/` commits.
   - **Do NOT propose `/schedule` agents to verify CI.** The next-session handoff is the canonical CI-verification mechanism (resuming session runs `gh run list` for each unverified SHA, fixes red, proceeds). (User-confirmed 2026-04-30.)
   - Performed autonomously — no user confirmation.
10. **Next Step Planning**: at each task boundary convene the agent team, agree the next priority, emit it as the handoff.
    - **Default to agent-team deliberation for ALL project decisions** (path / scope / prioritization / tradeoff). Never present a single-voice default when multiple reasonable paths exist. Concise roleplayed voices in one response are fine for path choices; spawn real sub-agents only for deep investigation. End every deliberation with an explicit **Decision:** line.
    - **Ignore implementation cost in path choices**: AI does the implementation, so effort/time is not scarce. Never cite hours / "fits this session" / "quick win" / "low-cost" as a reason. Compare on user value, strategic leverage, risk, info-gathering value. Name larger scope ("requires X screens / touches Y subsystems") without a time estimate. **Always pick the best outcome, not the cheapest path.**

## CI Known Limitations

> **Format discipline** (per [.claude/docs/process/upstream-issue-triage.md](docs/process/upstream-issue-triage.md)): every entry MUST link its upstream issue, carry a `last-checked` date, name any applied workaround, and set a `Re-check by` date (default monthly). Workarounds are temporary — when upstream closes, the workaround commit removes itself.

Full per-incident triage trails (dated, monthly-re-checked, mutable) live in [docs/en/phase/ci-known-limitations.md](../docs/en/phase/ci-known-limitations.md) — relocated 2026-05-18 so this file stays stable-reference-only and parallel worktrees never conflict on a re-check-date edit. Currently-active limitations (full detail + workaround + re-check date in that file):

- **Maestro 2.5.x iOS driver setup broken on Xcode 26.x** — host `make e2e-ios` only; CI gates the same suite. Upstream [Maestro#3218](https://github.com/mobile-dev-inc/Maestro/issues/3218) cluster.
- **Maestro + SwiftUI Button on iOS 26 / headless CI** — flows tagged `skip-ios26`; XCUITest covers natively.
- **E2E local-only mode** — `requires-supabase` flows excluded from CI; run locally against a Supabase build.
- **iOS NavigationStack edge-swipe** — no public disable API; UIKit-drop out of shared-Compose scope.
- **CMP resources on AGP 9.x KMP library plugin** — worked around in `androidApp/build.gradle.kts` (`CopyComposeResourcesForAndroid`).
- **Transient macOS-runner flakes** — XCUITest `testDeepLink_invalidURL`, Maestro `P0_create_project`, swift-CodeQL `macos-26` `actool` (last definitively-transient confirmation 2026-05-17): document-and-tolerate; escalation triggers in the trail file.
- **swift-CodeQL `macos-latest` vs pinned `macos-26` required-check drift** — latent governance, operator decision (carried in handoff pending threads).
- **CodeQL java-kotlin** — ✅ resolved (`--no-build-cache` in `security.yml`; required check on `main-strict`).

## Testing Requirements

- Minimum 80% test coverage for shared module
- **Unit** (commonTest): domain models, use cases, repository logic
- **Android**: Compose UI tests, ViewModel tests
- **iOS**: XCTest for SwiftUI views and ViewModels
- **Integration**: SQLDelight DB operations, Ktor API calls
- **E2E**: critical user flows (create pattern, track progress, share)

| Platform | Framework |
|----------|-----------|
| Shared (commonTest) | kotlin.test |
| Android | JUnit 5, Compose UI Testing, Turbine |
| iOS | XCTest, Swift Testing |
| E2E | Maestro (YAML flows, `e2e/`) |

## Code Quality Standards

- Functions < 50 lines; files 200–400 lines typical, 800 max
- Validate all inputs at system boundaries; handle errors explicitly at every level
- Never hardcode secrets; use environment variables or BuildConfig/Info.plist
- `ktlint` for Kotlin formatting; `SwiftFormat` / `SwiftLint` for Swift
- Gradle: use version catalogs (`libs.versions.toml`)

## Output Quality Standard

**There is no token-consumption ceiling** — the user has explicitly removed output budget as a constraint. Optimize relentlessly for **precision and quality of deliverables** over brevity:

- **Investigate to the bottom of the question.** Read every relevant file before responding to a code-review finding; dispatch a `code-explorer` agent rather than guess at current shape; exhaustively enumerate finding categories when user-visible UI is at stake.
- **Never skip agent-team deliberation to save tokens** — multi-voice catches scope-cut and security holes a single-voice answer drops.
- **Never trade test coverage for brevity** — ship N tests for N units of behavior, not 4 "to keep the diff small".
- **Don't pre-collapse documentation** — a 1000+-line ADR that captures every decision beats a 200-line one that hand-waves the tricky parts.
- **Code-review iterations are not a cost** — land all findings (CRITICAL→LOW) before commit (Workflow step 6); two passes is the floor when any non-trivial finding surfaces.
- **Prefer doing too much over too little** — when in doubt whether a check/test/doc clarification is necessary, do it.
- **Never trust a single WebFetch summary on identifier-precision claims** (runner labels, SDK versions, API paths, env-var spellings — WebFetch is model-summarized and can hallucinate exact strings). Before reflecting any such identifier into code/config/docs, invoke the **verification-layer (research domain)** skill (`.claude/skills/verification-layer/SKILL.md`; full protocol in `.claude/skills/verification-layer/research/protocol.md`). Anti-example: the 2026-05-09 `macos-26-arm64` mis-commit (canonical label is `macos-26`) cost ~2h CI + a forced fix-forward.

Does NOT override: minimal-diff in build-error-resolver mode; the agent-team-deliberation requirement; the CI-wait policy. DOES override: any tendency to truncate analysis, skip clarifying questions, or collapse multi-voice into one fast answer because output volume feels large.

## Documentation Convention

- Technology reference docs: `docs/en/` (English, source of truth); Claude reads `docs/en/` only to minimize context
- Japanese translations: `docs/ja/` (maintained); JA files header-link the English source; ADRs in `docs/en/adr/` + `docs/ja/adr/`
- **`docs/en/phase/**` (completed-archive / roadmap / tech-debt / ci-known-limitations / tasks) is the EN-only agent progress ledger** — no `docs/ja/phase/` mirror (deliberate; matches the pre-existing completed-archive precedent). User-facing reference docs (architecture / adr / ops / spec) keep their JA mirrors.

## Session Handoff Protocol

Each session MUST end with a **Session Task Prompt** fenced block in the conversation (never written to a file). It is the prompt the user pastes back next session — write it imperative second-person ("Your task this session is X", "Run Y"), NOT a third-person prediction. The reader IS the session doing the work. It is a short tactical resume prompt, NOT a status dump — persistent facts live in this CLAUDE.md / the phase lane.

### What goes in the handoff (≤40 lines)
1. Task-prompt header (`# Your task this session`, not "Next Session Instructions").
2. Current state: branch + latest commit + test count + CI status (one line each).
3. Your task: one-line imperative of the phase/option to execute.
4. First actions: 3–5 concrete commands/edits to run immediately.
5. Any pending execution decision blocking completion.

### What does NOT go in the handoff
- Roadmap progress → [roadmap.md](../docs/en/phase/roadmap.md); closed waves → [completed-archive.md](../docs/en/phase/completed-archive.md) (orchestrator folds at consolidation).
- Tech debt / follow-ups → [tech-debt.md](../docs/en/phase/tech-debt.md).
- Architecture invariants / ADR refs → `## Architecture Principles` or `docs/en/adr/`.
- CI gotchas → `## CI Known Limitations` + [ci-known-limitations.md](../docs/en/phase/ci-known-limitations.md).

**Ordering**: emit as the very last message, after every execution decision (push, CI verify) is resolved — never mid-session. **Voice check**: re-read as if handed it fresh; if any line reads "the next session will do X" rather than "do X", rewrite. **Parallel handoff**: when work was dispatched across worktrees, follow `## Parallel-Worktree Workflow Protocol` → `### Parallel handoff` (emit N worker prompts + 1 orchestrator-resume block instead of a single handoff).

## Specifications

Feature-organized "current shape" docs. Loaded into agent context when extending a feature; cheaper than re-discovering via grep. Specs describe *what is*; ADRs answer *why*.

| Spec | Covers | Key ADRs |
|---|---|---|
| [docs/spec/chart-editor.md](docs/spec/chart-editor.md) | Chart authoring (palette + canvas + history + save). Phase 32.x / 35.1a–d / metadata picker | ADR-007, 008, 009, 011, 013 |
| [docs/spec/suggestion-flow.md](docs/spec/suggestion-flow.md) | Suggestion open / list / detail / comment / close / apply / conflict resolution. Phase 38.x | ADR-012, 013, 014 |
| [docs/spec/collaboration-history.md](docs/spec/collaboration-history.md) | `chart_versions` append-only spine, version history / variants / comparison / restore. Phase 37.x | ADR-007, 013 |

Specs intentionally do NOT exist for: `i18n` (script-verified parity is the source of truth), `auth` (small surface), `realtime-sync` / `discovery-fork` / `segment-progress` / `symbol-catalog` (captured by their ADRs).

## Development Roadmap

### Archive policy

When a wave fully closes (no more sub-slices — signaled by a "closes Phase N entirely" remark + no follow-up for ≥1 session), the **orchestrator** promotes its entries to [docs/en/phase/completed-archive.md](../docs/en/phase/completed-archive.md) and replaces them with a one-line index pointer. Same at major-release commits (v1.0 / Phase 40). Cadence: a dedicated `docs(process)`/release commit at wave-close — whichever comes first; mechanical cut + verbatim append. Tech Debt items marked **CLOSED** in [docs/en/phase/tech-debt.md](../docs/en/phase/tech-debt.md) follow the same policy. The forward backlog and standing-debt list are orchestrator-exclusive — workers never edit them (see `## Parallel-Worktree Workflow Protocol`).

### Phase-lane pointers (mutable progress relocated 2026-05-18)

Mutable progress moved out of this file (so parallel worktrees never merge-conflict); all under the EN-only orchestrator-exclusive `docs/en/phase/` ledger:

- **Forward backlog** → [roadmap.md](../docs/en/phase/roadmap.md) (Deferred, Planned Structured Chart Authoring, Residual inventory post-alpha/beta/release, Pre-Phase-40 polish, Accessibility R-series R1b/R1c/R2–R5 + REJECTED guard, Phase 28, Post-v1.0, crochet catalog).
- **Standing Tech Debt + CLOSED trails** → [tech-debt.md](../docs/en/phase/tech-debt.md) (incl. Phase 40 GA release prep + the ASC accessibility 申告 R-series progression = the R-series timing source of truth).
- **Closed waves / historical** → [completed-archive.md](../docs/en/phase/completed-archive.md) (Phase 1–37.x, 38.x, 39.x, 41.x, 24/25/26/27, W5). **CI Known Limitations full trails** → [ci-known-limitations.md](../docs/en/phase/ci-known-limitations.md).
- **Live per-task progress** → [tasks/](../docs/en/phase/tasks/) (one file per parallel task, single-owner; [`_TEMPLATE.md`](../docs/en/phase/tasks/_TEMPLATE.md) = schema).

## Parallel-Worktree Workflow Protocol

Multiple `git worktree` sessions run in parallel. The historical conflict driver — CLAUDE.md's mutable progress sections — is relocated to the orchestrator-exclusive phase lane above. This protocol keeps parallel work conflict-free. Prior art reused: single-writer sovereignty (`multi-*`), worktree-isolation + worker-never-resolves-cross-task-conflict (`claude-devfleet`), structured Task Result contract (`orchestrate`/`devfleet`), "independent tasks only" → write-set disjointness (`dmux-workflows`), DAG serialization for hot-file lanes (`devfleet`).

### Two-layer roles

- **Orchestrator** (serial, single session). Selects parallelizable tasks; runs the write-set disjointness analysis; emits per-worktree worker prompts; consolidates Task Results; runs post-merge integration verification. **The ONLY session allowed to edit `.claude/CLAUDE.md`, `roadmap.md`, `tech-debt.md`, `completed-archive.md`, `ci-known-limitations.md`.** Splices i18n fragments + runs `verifyI18nKeys` at consolidation. Owns the consolidation commit (always last, serial).
- **Worker** (one scoped task per worktree). May edit ONLY: its own `docs/en/phase/tasks/<task-id>.md` (+ `<task-id>.i18n.tsv`), its code, its tests, and ADRs/specs strictly inside its declared scope. **NEVER edits CLAUDE.md / roadmap / tech-debt / completed-archive / ci-known-limitations.** Ends by emitting the structured `TASK RESULT` block (schema in [tasks/_TEMPLATE.md](../docs/en/phase/tasks/_TEMPLATE.md)).

### Per-task ownership-progress model

Each task = `docs/en/phase/tasks/<task-id>.md`, created/owned/written by exactly one worktree, never read/written by another (→ zero conflict). Required schema + lifecycle (`PLANNED → IN_PROGRESS → BLOCKED → READY_FOR_CONSOLIDATION → CLOSED`) in [tasks/_TEMPLATE.md](../docs/en/phase/tasks/_TEMPLATE.md): task id, scope, declared write-set, ADR/spec refs, test delta, status, result summary, follow-ups. Worker advances to ≤ READY_FOR_CONSOLIDATION; only the orchestrator sets CLOSED, folds `Result summary` into `completed-archive.md`/`tech-debt.md`, and `git rm`s the per-task file in the consolidation commit.

### Write-set disjointness selection

The orchestrator enumerates each candidate task's expected file write-set (its per-task `Declared write-set`) and dispatches a batch **iff member write-sets are pairwise disjoint**, OR the only overlap is the 3 i18n files handled via the i18n-fragment rule. Any **hot file** touched by >1 task in a batch forces serialization (DAG `depends_on`, not parallel).

**Hot-file inventory** (touch by >1 task ⇒ serialize): `.claude/CLAUDE.md`; the 3 i18n files (`shared/src/commonMain/composeResources/values/strings.xml`, `…/values-ja/strings.xml`, `iosApp/iosApp/Localizable.xcstrings`); `shared/src/commonMain/kotlin/**/domain/chart/ChartAccessibility.kt`; `version.properties` + `iosApp/project.yml`; the *same* ADR file's revision-history block; root `build.gradle.kts` + `Makefile` (verify-task / xcstrings allow-list wiring); any Koin module (`**/di/*Module*.kt`, `PlatformModule.{android,ios}.kt`); navigation route enums (`**/NavGraph*.kt`, `AppRouter*.swift`, `Screen.kt`); `libs.versions.toml`; `supabase/config.toml` + `supabase/functions/_shared/**`; `.github/workflows/**`; the *same* `docs/spec/*.md`. `roadmap.md`/`tech-debt.md`/`completed-archive.md`/`ci-known-limitations.md` are orchestrator-exclusive ⇒ structurally never in a worker write-set.

**Worked example**: R1b and R1c BOTH extend `ChartAccessibility.kt` (hot file) ⇒ NOT parallelizable together — serialize R1b → R1c. R4 (ProjectDetail Dynamic Type) is write-set-disjoint from R3/R5 and from R1 ⇒ parallelizable. R2 (icon-label + ChartImage i18n sweep) touches the 3 i18n files only ⇒ parallelizable with a non-i18n task iff it ships an i18n fragment.

### i18n-fragment + orchestrator-merge rule

Workers never edit the 3 shared i18n files. A worker needing keys writes a sibling `docs/en/phase/tasks/<task-id>.i18n.tsv` (cols: `key⇥en⇥ja⇥xcstrings_value`) and references the final key names in code. The orchestrator splices all fragments into the 3 files at consolidation and runs `verifyI18nKeys` **before** the consolidation commit (Stop-Loss gate). Distinct-key tasks are i18n-parallelizable; a same-key collision across two fragments ⇒ the orchestrator serializes those two (no silent auto-rename — surface to operator).

### Direct-push reconciliation

Workers push to `origin/main` with `git pull --rebase` immediately before `git push`. Disjoint write-sets ⇒ trivial replay. **If the rebase raises ANY conflict the worker runs `git rebase --abort`, keeps its commits on its worktree branch, sets `Status: BLOCKED` + `Blocked-reason: rebase-conflict` + the exact conflicting paths in its Task Result, and STOPS — it never resolves a cross-task conflict.** The orchestrator owns reconciliation (re-scope / serialize / cherry-pick). Each worker still runs the full `make ci-local` on its own slice before attempting push.

### Parallel handoff (extends `## Session Handoff Protocol`)

When dispatching parallel work the orchestrator emits, as the last message, **N worker prompts as separate fenced blocks + 1 orchestrator-resume fenced block**. Per-worktree worker prompt skeleton:
```
# Worker task: <task-id> — <title>   (日本語で会話してください, work-without-stopping)
Fresh git worktree off origin/main. local.properties = only `sdk.dir=/Users/b150005/Library/Android/sdk`.
1. cp docs/en/phase/tasks/_TEMPLATE.md docs/en/phase/tasks/<task-id>.md; fill Scope + Declared write-set (stay inside: <declared globs>).
2. Implement <one-line scope>. TDD. ADR/spec refs: <...>. NEW i18n keys → <task-id>.i18n.tsv ONLY (never the 3 shared i18n files).
3. NEVER edit CLAUDE.md / roadmap.md / tech-debt.md / completed-archive.md / ci-known-limitations.md or any hot file outside your declared set.
4. Full `make ci-local` green on your slice → `git pull --rebase` → push origin/main. Rebase conflict ⇒ `git rebase --abort`, Status: BLOCKED, STOP.
5. Emit the `TASK RESULT` block (schema in tasks/_TEMPLATE.md) as the last message.
```
Orchestrator-resume prompt skeleton:
```
# Orchestrator resume (serial, single)   (日本語で会話してください)
1. gh run list -L 8 --branch=main — verify each pushed worker SHA green; fix-forward any red FIRST.
2. Collect each worker's TASK RESULT + tasks/<id>.md; verify `git diff --name-only` stayed inside each Declared write-set.
3. Splice all <id>.i18n.tsv fragments → 3 i18n files; run verifyI18nKeys + :shared:ktlintCheck.
4. Fold CLOSED Result summaries into completed-archive.md / tech-debt.md; move surfaced follow-ups into roadmap.md/tech-debt.md; `git rm` consolidated per-task files. Single `docs(process)` consolidation commit.
5. Re-run write-set disjointness over the remaining backlog; emit the next batch (worker prompts + this resume block).
```
