# Accessibility Label-Coverage Audit — VoiceOver + Dynamic Type + Color-Independence (2026-05-17)

> **Purpose**: per-screen, per-element accessibility **gap inventory** for the
> Phase-40 App Store Connect "アプリのアクセシビリティ 申告" (App Accessibility
> Nutrition Label). This is prerequisite **(c)** of the CLAUDE.md
> `### Phase 40 GA release prep` "App Store Connect → アプリのアクセシビリティ
> 申告" item — it answers *which assistive-tech features can be honestly
> declared end-to-end* and *what concretely blocks the rest*.
>
> **Relation to the baseline**: [audits/a11y-baseline-2026-05-12.md](a11y-baseline-2026-05-12.md)
> established the *aggregate code-level coverage* (file-count of
> `contentDescription` / `accessibilityLabel` annotations) for pre-alpha
> A23–A27. It explicitly deferred A23 (VoiceOver/TalkBack) and A24
> (Dynamic Type / contrast) as "per-screen walk-through / verification
> required". **This audit closes that deferral on the code side**: it is the
> per-screen, per-element drill the baseline said was still owed. A25 (Reduce
> Motion) and the ChartEditor WCAG 2.5.8 touch-target (M5) are **CLOSED** and
> out of scope here.
>
> **Audience**: an agent planning the a11y remediation slices, and the
> operator deciding which boxes to tick in App Store Connect.
>
> **Method**: two exhaustive static passes (every Compose screen under
> `shared/src/commonMain/.../ui/`, every SwiftUI screen + component under
> `iosApp/iosApp/`) — read in full, not sampled. Findings carry `file:line`
> and a severity calibrated to *ASC declaration impact*, not generic polish.

## Severity legend

| Severity | Meaning |
|---|---|
| **BLOCKER** | A declared feature would be **false** end-to-end. Cannot tick the box until fixed. |
| **HIGH** | Real barrier on a core surface; fix before declaring the related feature. |
| **MEDIUM** | Degraded but usable; declarable with reservations / fix in the same wave. |
| **LOW** | Polish; does not affect declaration. |
| **OK** | Verified clean (good posture) — recorded so the audit is a positive inventory too. |

The three ASC declarations this audit gates:
- **VoiceOver** (JA: VoiceOver / バリアフリー音声ガイド) — screen-reader perceivable + operable.
- **Larger Text / Dynamic Type** (JA: さらに大きな文字) — text honors the system size.
- **Differentiate Without Color** (JA: カラー以外で区別) — state never conveyed by color/shape alone.

(ダークインターフェイス + 視差効果を減らす are already declarable per CLAUDE.md
— Phase 18 Liquid Glass + A25 closed; not re-audited here.)

---

## 1. Declaration-level conclusion (the bottom line first)

| ASC declaration | Verdict | Why |
|---|---|---|
| **VoiceOver** | ❌ **CANNOT declare end-to-end** | The chart subsystem — the app's reason to exist — is opaque to VoiceOver/TalkBack on **both platforms**: ChartViewer progress canvas, ChartEditor authoring canvas, ChartComparison diff canvas have **zero** accessibility semantics. Plus HIGH icon-only unlabeled controls on core nav (ProjectList overflow, Discovery sort, ChartEditor undo/redo/overflow, ChartImageViewer close) and auth (MFA code fields). The non-chart surface is *close* to clean; the chart surface is fatal. |
| **Differentiate Without Color** | ❌ **CANNOT declare** | Two BLOCKERs, identical on both platforms: ChartComparison diff = traffic-light fill only (added=green/removed=red/modified=yellow, no text/shape/value), ChartViewer segment progress = fill/stroke color only (done/wip). Everywhere *else* is exemplary (filter chips use checkmarks, status badges render text) — the chart canvases are the sole, fatal exceptions. |
| **Larger Text (Dynamic Type)** | ⚠️ **Declarable WITH remediation** | **Positive structural finding: zero `.dynamicTypeSize(...)` clamps and zero `fontScale`/`LocalDensity` scale-disabling overrides anywhere** — nothing artificially caps the user's chosen size; stock Material 3 / SwiftUI text styles scale by default across the vast majority of screens. Defects are **localized and all MEDIUM (no BLOCKER)**: hardcoded fixed font literals on the row counter + +/− buttons (ProjectDetail / `DesignTokens`) and `maxLines=1`/`.lineLimit(1)` ellipsis on pattern & discovery descriptions. Honest declaration is defensible once those are remediated. |

**Net**: M5 (touch-target) + A25 (reduce motion) are done, but **VoiceOver and
Differentiate-Without-Color are blocked by the same root cause on both
platforms — the chart `Canvas` surfaces have no semantic representation at
all.** That is the highest-leverage remediation: one architectural pattern
(an accessible chart-grid representation: per-cell / per-row
`accessibilityElement` + `accessibilityValue` text for symbol & progress &
diff state) unblocks two declarations across three screens × two platforms.

> **R1a progress update (2026-05-18, ADR-025)**: the **Chart Viewer** third
> of R1 is shipped — B1/B2 CLOSED on both platforms via the shared
> `ChartAccessibility` per-row model + invisible overlay. The two
> declarations still **cannot** be made end-to-end: VoiceOver additionally
> needs B3 (Editor, R1b), B4 (Comparison, R1c), R2 (icon labels) and R3
> (headings); Differentiate-Without-Color additionally needs B4 (Comparison
> diff still traffic-light color, R1c). §5 progression table unchanged —
> R1a is necessary progress toward "After R1", not the full gate.

---

## 2. Cross-platform parity observations

The two codebases mirror each other, so most gaps are **symmetric** (fixing
one platform without the other re-opens the declaration gap):

| Gap | Compose | SwiftUI | Parity |
|---|---|---|---|
| Chart **viewer** progress canvas — no semantics, progress = color only | `chart/ChartViewerScreen.kt:495-612, 457-465` | `Screens/ChartViewerScreen.swift:319-420, 644-645` | ✅ **CLOSED (R1a) both** — shared `ChartAccessibility` model ⇒ identical per-row spoken text by construction (B1/B2) |
| Chart **editor** authoring canvas — no semantics | `chart/ChartEditorScreen.kt` `RectEditorCanvas`/`PolarEditorCanvas` | `Screens/ChartEditorScreen.swift:476-579 EditorCanvasView` | **Identical BLOCKER both** |
| Chart **comparison** diff canvas — no semantics, diff = color only | `chart/ChartComparisonScreen.kt:502-557, 335-337` | `Screens/ChartComparisonScreen.swift:321-468, 380-382` | **Identical BLOCKER both** |
| Symbol **palette** cells unlabeled | `chart/SymbolPaletteStrip.kt:159-173` (+ hardcoded-EN "Eraser" L133) | `Screens/ChartEditorScreen.swift:1114 PaletteSymbolCell` | **Identical HIGH both** |
| Icon-only **overflow / nav** controls unlabeled | (Compose overflow buttons mostly OK — announce "More options") | `ProjectListScreen.swift:168-171`, `ChartEditorScreen.swift:128-220`, `DiscoveryScreen.swift:182-198` `ellipsis.circle`/`arrow.up.arrow.down` no `.accessibilityLabel` | **iOS-worse** (SF Symbol auto-label is wrong; Compose has explicit strings) |
| ChartImage surfaces hardcoded **English** strings | `chartviewer/ChartImageGrid.kt:42,84,100,133` + `chartviewer/ChartImageViewer.kt:85,129` (1 title + 5 contentDescription, no `stringResource`) | `Components/ChartImageViewer.swift:61-66` close button no label | **Both** (Compose = i18n+label; iOS = label) |
| Row-counter / +/− **fixed font size** | `projectdetail/ProjectDetailScreen.kt:633 (96.sp), 686 (28.sp), 696 (36.sp)` in fixed `Modifier.size()` | `Core/DesignTokens.swift:14-16 (72/48/64)` consumed `ProjectDetailScreen.swift:307,333,343` | **Identical MEDIUM both** |
| MFA code field empty a11y label | (Compose MFA uses labeled fields — OK) | `MfaChallengeScreen.swift:33`, `MfaEnrollmentScreen.swift:122-125` empty `LocalizedStringKey("")` | **iOS-only HIGH** |
| No heading semantics on section headers | systemic — **no** `semantics{heading()}` anywhere | iOS `Section` headers auto-traited (framework) — OK; only ad-hoc `.font(.title2)` headers (OAuthProfileSetup, Paywall) miss `.isHeader` (LOW) | **Compose-worse (systemic HIGH); iOS mostly OK** |

**Implication for remediation sequencing**: the chart-canvas a11y pattern,
the icon-label sweep, and the ProjectDetail Dynamic-Type fix must each land
on **both** platforms in the same slice or the ASC declaration stays blocked.

---

## 3. Per-screen inventory

### 3.1 BLOCKERs (must fix before VoiceOver / Differentiate-Without-Color)

| # | Surface | Platform | `file:line` | Gap |
|---|---|---|---|---|
| B1 | **ChartViewer progress canvas** | Both | KT `ChartViewerScreen.kt:495-612` · SW `ChartViewerScreen.swift:319-420` | ✅ **CLOSED (R1a, 2026-05-18, ADR-025)** — invisible per-row semantic overlay (`RectRowAccessibilityOverlay` Compose / `RowAccessibilityCell` SwiftUI) over the rect Canvas; shared pure `ChartAccessibility.rowDescriptors` + `spokenLabel`. Each grid row is one VoiceOver/TalkBack element (position + run-length symbol summary + progress), traversal row-1-first. ~~Entire chart is one opaque `Canvas` with no semantics.~~ Polar overlay gated → Phase 35.2+. |
| B2 | **ChartViewer segment progress = color only** | Both | KT `:457-465` · SW `:644-645` | ✅ **CLOSED (R1a, 2026-05-18, ADR-025)** — row progress is now spoken text (`not started` / `%1$d of %2$d done` / `done`, localized en+ja), derived from the same cell set `MarkRowSegmentsDoneUseCase` touches. No longer color-only. ~~done = fill @20%; wip = 2dp stroke, no text equivalent.~~ |
| B3 | **ChartEditor authoring canvas** | Both | KT `ChartEditorScreen.kt` `RectEditorCanvas`/`PolarEditorCanvas` (`editorCanvas` testTag only) · SW `ChartEditorScreen.swift:476-579` | Tappable place/erase `Canvas` with zero a11y — authoring is impossible by VoiceOver/TalkBack. (Touch-target is M5-resolved; this is the orthogonal *perceive/operate* gap.) |
| B4 | **ChartComparison diff canvas** | Both | KT `ChartComparisonScreen.kt:502-557` (+ color map `:335-337`) · SW `ChartComparisonScreen.swift:321-468` (`:380-382,440-442`) | Per-cell diff identity is **100% traffic-light fill** (green/red/yellow) in a `Canvas` with no a11y element. `DiffSummaryRow` (aggregate counts) is partial mitigation — KT `:227-242` correctly exposes it via `semantics{contentDescription}` — but per-cell change is invisible to SR + color-blind. |

### 3.2 HIGH (fix before declaring the related feature)

| # | Surface | Platform | `file:line` | Gap |
|---|---|---|---|---|
| H1 | Symbol palette cells unlabeled | Both | KT `SymbolPaletteStrip.kt:159-173`; eraser hardcoded EN `:133` · SW `ChartEditorScreen.swift:1114 PaletteSymbolCell` | Each stitch symbol cell announces nothing meaningful (`def.enLabel`/`jaLabel` available but unused). Authoring/selection unusable by SR. |
| H2 | ChartImageGrid hardcoded English | Compose | `chartviewer/ChartImageGrid.kt:42,84,100,133` | 1 title (`"Chart Images"`) + 3 `contentDescription` (`"Chart thumbnail"`, `"Remove image"`, `"Add chart image"`) all literal English, no `stringResource` — VoiceOver/TalkBack announces English on ja-JP. |
| H3 | ChartImageViewer hardcoded English | Both | KT `ChartImageViewer.kt:85,129` (`"Chart image"`,`"Close"`) · SW `Components/ChartImageViewer.swift:61-66` (close `xmark.circle.fill` no label) | Sole dismiss affordance of the full-screen viewer is unlabeled / English. (KT error text already fixed per A33-adjacent trail; the 2 contentDescriptions were missed.) |
| H4 | Icon-only nav controls unlabeled | iOS | `ProjectListScreen.swift:168-171` (`ellipsis.circle` overflow — core nav), `ChartEditorScreen.swift:99-114` (undo/redo), `:128-220` (overflow), `DiscoveryScreen.swift:182-198` (sort `arrow.up.arrow.down`) | `.accessibilityIdentifier` present but **no `.accessibilityLabel`** → VoiceOver reads the English SF Symbol name. Strings (`action_more_options`/`action_sort`/`action_undo`/`action_redo`) already exist & are used elsewhere. |
| H5 | ProjectDetail add-image / thumbnails unlabeled | iOS | `ProjectDetailScreen.swift:528-530` (PhotosPicker `plus.circle`), `:549-569` (tappable thumbnails + contextMenu) | Only affordance to add a reference image, and the tappable/deletable thumbnails, have no `.accessibilityLabel`/`.accessibilityElement`/`.accessibilityAction`. |
| H6 | SegmentProgressSummary not a button | Compose | `projectdetail/ProjectDetailScreen.kt:822-856` | Tappable `Text` via `Modifier.clickable` with **no `role = Role.Button`** — the deep-link into chart segment progress is announced as static text, not actionable. |
| H7 | ProjectDetail row counter / +− fixed font | Both | KT `:633 (96.sp), 686 (28.sp), 696 (36.sp)` in fixed `Modifier.size()` · SW `DesignTokens.swift:14-16` → `ProjectDetailScreen.swift:307,333,343` (72/48/64 pt) | The single most-used screen (row counter loop) does not honor Larger Text and clips inside fixed-size buttons at accessibility sizes → blocks "Larger Text". |
| H8 | MFA code fields empty label | iOS | `MfaChallengeScreen.swift:33` (`LocalizedStringKey("")`), `MfaEnrollmentScreen.swift:122-125` (`TextField("",…)`) | Auth-critical 6-digit code fields announce only "text field" — no hint. |
| H9 | No heading semantics (systemic) | Compose | every screen — **no** `Modifier.semantics{heading()}` | Section headers (Settings, ProjectDetail, ChartEditor, onboarding titles, TopAppBar titles) announce as body text; no rotor/heading navigation on long screens. One systemic HIGH, not per-screen. (iOS uses `Section` → framework-traited; mostly OK.) |

### 3.3 MEDIUM (declarable with reservations / same-wave fix)

| # | Surface | Platform | `file:line` | Gap |
|---|---|---|---|---|
| M1 | Onboarding page-indicator dots color-only | Compose | `onboarding/OnboardingScreen.kt:320-347` | Bare `Box`+`background(color)` dots, no semantics; current page = primary-vs-outlineVariant color only. First-run surface. |
| M2 | Pattern/Discovery description ellipsis at large type | Both | KT `PatternLibraryScreen.kt:538,550` (`maxLines=1`), `DiscoveryScreen.kt:499,511` · SW `PatternLibraryScreen.swift:251`, `DiscoveryScreen.swift:409,417` (`.lineLimit(1/2)`) | Essential metadata hard-truncates at large Dynamic Type; no `.minimumScaleFactor`. (VoiceOver still reads full string — visual only.) |
| M3 | Recovery-code copy-tap affordance hidden | Both | KT `MfaEnrollmentScreen.kt:276-302` · SW `MfaEnrollmentScreen.swift:163-172` | Code is readable but the tap-to-copy interaction has no role/hint/action — undiscoverable by SR. |
| M4 | WipeData confirm-phrase field empty label | iOS | `WipeDataConfirmPhraseView.swift:135` (`TextField("",…)`) | Destructive-action gate announces bare "text field"; Section header is not a field label for VoiceOver. |
| M5 | ChartConflictResolution picked = style weight only | iOS | `ChartConflictResolutionScreen.swift:259` | Selected resolution signaled only by `.borderedProminent` vs `.bordered`; no `.accessibilityAddTraits(.isSelected)`/value. (Row text carries conflict identity — not a color-alone blocker, but selection state is weak for SR.) |
| M6 | Decorative icons not hidden (VoiceOver noise) | iOS | `LoginScreen.swift:126-128` (Google `g.circle.fill`), `OnboardingScreen.swift:128-130,174` (hero), `ActivityFeedScreen.swift:52-89` (type icon) | Decorative `Image`s lack `.accessibilityHidden(true)` → VoiceOver reads junk SF Symbol names before the real text. Login is the canonical sign-in surface (declaration-sensitive). |
| M7 | LinearProgressIndicator no value | Compose | `projectlist/ProjectListScreen.kt:660`, `projectdetail/ProjectDetailScreen.kt:660` | Announces a generic progress bar with no percent/value (adjacent "X of Y rows" text mitigates — borderline MEDIUM/LOW). |

### 3.4 LOW / OK highlights

- **Good a11y infrastructure (positive inventory)**: Compose `ui/components/LiveSnackbarHost.kt` wires `liveRegion = Polite` (every toast announced — WCAG 4.1.3); `ui/components/SelectedCheckmarkIcon.kt` is a deliberate non-color selected-state cue used across PatternLibrary/ProjectList/Discovery/SuggestionList filter chips. iOS `Core/AccessibilityAnnouncements.swift` is the parallel toast announcer, widely used; `SuggestionListScreen`/`SuggestionDetailScreen` are exemplary (status = neutral-gray capsule + **text**, never color).
- **Declaration-ready screens (verified clean, both platforms)**: Auth (ForgotPassword, MfaChallenge-on-Compose, OAuthProfileSetup), Settings, Profile, Suggestion list/detail, ChartConflictResolution (Compose), ChartHistory, ChartVariationPicker, Comments, all Moderation (Report/Block/BlockedUsers), Connections, FriendInvite, SharedWithMe, SharedContent, SymbolGallery, Paywall, PackManagement, BugReport, ForceUpdate, Maintenance, ActivityFeed (text carries type), EmptyStateView, all leaf dialogs. The non-chart surface is in good shape.
- **LOW**: in-button spinner replaces text label while submitting (no `contentDescription` mid-submit) — repeated across Login/ForgotPassword/Profile/Settings dialogs/Paywall (Compose); iOS `.font(.title3, design:.monospaced)` on the MFA secret correctly uses the *text-style* form so it **does** scale (recorded as the correct pattern); ad-hoc `.font(.title2)` headers missing `.isHeader` on OAuthProfileSetup/Paywall (iOS).
- **Honesty note (carried from the Compose pass)**: 4 small Settings-family leaf files (`DataExportScreen`, `OssLicensesScreen`, `WipeDataConfirmPhraseScreen`, `WipeDataExplanationDialog` on the Compose side) were enumerated but not opened in the Compose pass; their iOS counterparts **were** read and are clean, and the Settings-family pattern is consistent. Marked **assumed-OK, not exhaustively verified** — a remediation slice touching Settings should spot-confirm them.

---

## 4. User-impact-ranked remediation backlog

Ordered by *declaration leverage* (how many ASC boxes × platforms × core-task
weight a fix unblocks), per the project rule "always pick the best outcome".
**Remediation is deliberately NOT bundled into this audit** — each row below
is a follow-up slice.

| Rank | Slice | Unblocks | Scope (both platforms unless noted) |
|---|---|---|---|
| **R1** | **Accessible chart-grid representation** — per-row (rect grid row / polar ring) semantic model: shared `ChartAccessibility` model → invisible per-row overlay on the M5 coordinate space; row text = symbol-run summary + position + progress/diff state (no color); `accessibilityAction` mark-row-done (Viewer) / in-row adjustable cell cursor + place/erase (Editor); per-row change list (Comparison). **ADR DECIDED → [ADR-025](../docs/en/adr/025-chart-canvas-accessibility.md)** (semantic granularity: per-row, not per-cell/summary; agent-team deliberated). Sub-slices R1a Viewer → R1b Editor → R1c Comparison, rect-first (polar gated Phase 35.2+ like M5). | **VoiceOver** (B1,B3,B4) **+ Differentiate-Without-Color** (B2,B4) — 2 declarations, 3 screens, core feature | Largest. ADR-025 now gates it (no longer "needs an ADR"). KMP shared model mirrors `GridHitTest` ⇒ structural parity. |
| **R2** | **Icon-label + i18n sweep** — add `.accessibilityLabel`/localized `contentDescription` to every icon-only control; route `ChartImageGrid`/`ChartImageViewer` hardcoded English through `Res.string.*` (+ JA + iOS xcstrings); fix empty-string MFA / WipeData `TextField` labels. | **VoiceOver** (H1–H5,H8) | Medium, mechanical, low-risk. Mostly reuses existing strings (`action_more_options`/`action_sort`/`action_undo`/`action_redo`); ~3–4 new i18n keys for ChartImage + MFA field. |
| **R3** | **Heading semantics** — `Modifier.semantics{heading()}` on every section header / screen title (Compose, systemic); add `.accessibilityAddTraits(.isHeader)` to the ad-hoc iOS `.font(.title2)` headers. | **VoiceOver** (H9) — rotor navigation on long screens | Medium, broad but mechanical (Compose-heavy; iOS mostly framework-OK). |
| **R4** | **ProjectDetail Dynamic Type** — replace fixed `fontSize=96/28/36 sp` (KT) and `DesignTokens` 72/48/64 pt (SW) with type-style + `@ScaledMetric`/`.minimumScaleFactor`; let the counter/+− containers grow; relax `maxLines=1`/`.lineLimit(1)` on pattern/discovery descriptions (or add `.minimumScaleFactor`). | **Larger Text** (H7,M2) — flips it from "with reservations" to declarable | Small/medium, isolated to ProjectDetail + DesignTokens + 2 list screens. |
| **R5** | **State-not-color polish** — onboarding page-indicator dots (semantics + non-color current marker), recovery-code copy affordance role/hint, ChartConflictResolution `.isSelected` trait, decorative-icon `.accessibilityHidden(true)` (iOS Login/Onboarding/ActivityFeed), `LinearProgressIndicator` value. | **Differentiate-Without-Color** residual + VoiceOver polish (M1,M3,M5,M6,M7) | Small, broad; close out after R1 removes the chart blockers. |

**Sequencing note**: R1 is the gate for two declarations. Its design
decision (semantic granularity) is now **resolved by
[ADR-025](../docs/en/adr/025-chart-canvas-accessibility.md)** (per-row
unit + shared model + invisible overlay on the M5 coordinate space);
implementation is sub-slices R1a (Viewer) → R1b (Editor) → R1c
(Comparison), rect-first — **R1a is the natural next a11y *implementation*
slice**. R2/R3/R4 are independent and can proceed in parallel/any order.
R5 is the closeout.

## 5. What the operator can declare *today* vs after each slice

| Declaration | Today | After R2+R3 | After R1 | After R4 |
|---|---|---|---|---|
| ダークインターフェイス | ✅ (Phase 18) | ✅ | ✅ | ✅ |
| 視差効果を減らす (Reduce Motion) | ✅ (A25 closed) | ✅ | ✅ | ✅ |
| VoiceOver | ❌ | ❌ (chart still opaque) | ✅ (if R2/R3 also done) | ✅ |
| カラー以外で区別 | ❌ | ❌ | ✅ (R1 carries the non-color text) | ✅ |
| さらに大きな文字 (Dynamic Type) | ⚠️ reservations | ⚠️ | ⚠️ | ✅ |

Declaring only what passes is mandatory (Apple: "verified end-to-end for core
tasks"; false declarations are App-Review-rejectable, per the CLAUDE.md
Phase-40 entry). **Today's safe declarations: ダークインターフェイス +
視差効果を減らす only.**

---

## 6. Maintenance

- This audit is point-in-time (HEAD `00e6df0`, post-M5). A screen materially
  changing its a11y posture should update its row here in the same commit
  (mirror of the chart-editor spec maintenance rule).
- When a remediation slice (R1–R5) lands, mark the corresponding findings
  **CLOSED** here with the commit hash, and update §5 + the CLAUDE.md
  Phase-40 ASC prerequisite (c) status.
- Re-run the two static passes before the Phase-40 GA ASC submission to
  catch drift introduced by intervening feature work.

### Remediation log

- **2026-05-18 — R1a (ADR-025)**: B1 + B2 CLOSED on both platforms. Shared
  pure `ChartAccessibility` per-row model + `spokenLabel` (21 commonTest) +
  Compose `RectRowAccessibilityOverlay` + SwiftUI `RowAccessibilityCell`
  invisible overlays + 9 `a11y_chart_*` i18n keys (`verifyI18nKeys` parity).
  §1 verdicts unchanged (VoiceOver still needs B3/B4/R2/R3;
  Differentiate-Without-Color still needs B4) — see the §1 R1a progress
  note. B3 (Editor) → R1b, B4 (Comparison) → R1c remain open.
