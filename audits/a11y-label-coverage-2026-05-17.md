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
>
> **R1b + R4 progress update (2026-05-18, ADR-025 / audit §4 R4)**: the
> **Chart Editor** second-third of R1 + the ProjectDetail Dynamic Type slice
> are shipped. **B3 CLOSED** on both platforms via the same `ChartAccessibility`
> per-row model (extended with `CellAccessibilityDescriptor` + `spokenCellLabel`
> + `placeOrEraseActionLabel`) + invisible per-row overlay on the Editor rect
> Canvas + in-row adjustable cell cursor (TalkBack swipe-up/down /
> `.accessibilityAdjustableAction` SwiftUI) + named place/erase custom action
> routing `ChartEditorEvent.PlaceCell(cursorX, cursorY)` — closes audit §3.1
> B3. **H7 CLOSED** via `displayLarge`/`headlineMedium`/`displaySmall` Compose
> typography + `@ScaledMetric` SwiftUI (`DesignTokens.*` read through
> `UIFontMetrics`) — closes audit §3.3 H7. After R1b + R4, declaration state:
> VoiceOver still needs B4 (R1c) + R2 + R3; Differentiate-Without-Color still
> needs B4 (R1c); **さらに大きな文字 (Dynamic Type) declarable today** —
> the only Dynamic Type override site is closed (M2 PatternList/Discovery
> `.lineLimit(1)` ellipsis is visual-only at large sizes, VoiceOver still
> reads full string ⇒ does not block the declaration; deferred to its own
> slice per R4 worker scope cut). §5 progression table unchanged — `After R1`
> remains gating on R1c.
>
> **R3 progress update (2026-05-19, audit §3.2 H9 + §3.4 LOW)**: the
> systemic heading-semantics sweep is shipped (commit `57a96ab`).
> **H9 CLOSED** — `Modifier.semantics { heading() }` applied to every
> TopAppBar title (29 screens; `ProjectListScreen.kt` correctly skipped per
> Sprint A audit's intentional empty `title = { }`) + 10 hero-typography
> `Text` sites styled `displayLarge`/`headlineLarge`/`headlineMedium`/
> `headlineSmall`/`titleLarge`/`displaySmall` that are screen titles or
> section headers. 35 `*Screen*.kt` files touched, two semantics imports
> per file, annotation-only (zero behavior/layout/focus-order change).
> Data-text styled as hero typography correctly skipped (ProjectDetail row
> counter `displayLarge` + +/- glyphs, MfaEnrollment recovery-code value
> `titleLarge`, Connections invite-code value `headlineSmall`).
> **§3.4 LOW iOS `.title2` headers CLOSED** on `OAuthProfileSetupScreen.swift`
> + `PaywallScreen.swift` via `.accessibilityAddTraits(.isHeader)`
> (mirrors the existing `LoginScreen.swift:353` `LinkIdentityForm`
> precedent; the third `EmailConfirmationSentView` `.title2` site in the
> same file is R3 Follow-up #2, deliberately out of declared scope per
> the worker prompt). After R3 every R1–R4 closure prerequisite for the
> three ASC accessibility declarations is met — **VoiceOver flips ✅**.
> §1 declaration matrix becomes **5/6 ✅**: ダークインターフェイス +
> 視差効果を減らす + さらに大きな文字 + カラー以外で区別 + VoiceOver are
> declarable end-to-end; キャプション (closed-caption video) +
> バリアフリー音声ガイド (audio description) remain N/A for a knitting app.
> R5 (state-not-color polish) is the sole remaining residual; it does
> **not** gate any ASC declaration.
>
> **R1c + R2 progress update (2026-05-18, ADR-025 + audit §4 R2)**: the
> **Chart Comparison** final third of R1 + the icon-label + i18n sweep are
> shipped. **B4 CLOSED** on both platforms (commit `8ca758d`) via the same
> `ChartAccessibility` per-row model (extended with `DiffChangeKind` /
> `RowDiffChange` / `RowDiffDescriptor` / `DiffA11yStrings` +
> `spokenDiffLabel`) + Compose `RectComparisonAccessibilityOverlay` + new
> SwiftUI `ChartComparisonAccessibility.swift`; 4 `a11y_diff_*` i18n keys;
> 47 ChartAccessibilityTest green (+12 new). Rect only — polar gated Phase
> 35.2+ identical to R1a/R1b. **H1, H2, H3, H4, H5, H8 + M4 CLOSED**
> (commit `5be0034`) — mechanical icon-label / hardcoded-EN-to-i18n /
> empty-TextField-label sweep across 3 Compose files (`SymbolPaletteStrip.kt`,
> `ChartImageGrid.kt`, `ChartImageViewer.kt`) + 8 SwiftUI screens
> (ProjectList / Discovery / ChartEditor / ProjectDetail chartImagesSection /
> ChartImageViewer / MfaChallenge / MfaEnrollment / WipeDataConfirmPhrase);
> 10 new i18n keys + 7 reused keys; +1 XCUITest file
> (`IconButtonAccessibilityTests`). After R1c + R2, declaration state:
> **Differentiate-Without-Color (カラー以外で区別) declarable today** (B2 +
> B4 both closed end-to-end on both platforms via shared
> `ChartAccessibility` text); VoiceOver still gated on R3 (H9 heading
> semantics); さらに大きな文字 already declarable post-R4. **Today's safe
> declarations after this consolidation: ダークインターフェイス +
> 視差効果を減らす + さらに大きな文字 + カラー以外で区別** (4/6 — only
> VoiceOver remains gating; キャプション / バリアフリー音声ガイド N/A for
> a knitting app). §5 progression table: R3 is the sole remaining gate for
> VoiceOver. R5 is closeout polish only.

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
| B3 | **ChartEditor authoring canvas** | Both | KT `ChartEditorScreen.kt:RectEditorCanvas/PolarEditorCanvas` · SW `ChartEditorScreen.swift` + new `ChartEditorAccessibility.swift` | ✅ **CLOSED (R1b, 2026-05-18, ADR-025)** — invisible per-row semantic overlay over the rect Canvas + in-row adjustable cell cursor (TalkBack swipe-up/down / SwiftUI `.accessibilityAdjustableAction`) + named place/erase custom action routing `ChartEditorEvent.PlaceCell(cursorX, cursorY)`; 4 `a11y_editor_*` i18n keys. Shared `ChartAccessibility` extended (+`CellAccessibilityDescriptor` / +`spokenCellLabel` / +`placeOrEraseActionLabel`, 14 new commonTest). ~~Tappable place/erase Canvas with zero a11y.~~ Polar gated → Phase 35.2+. |
| B4 | **ChartComparison diff canvas** | Both | KT `ChartComparisonScreen.kt:RectComparisonAccessibilityOverlay` · SW `ChartComparisonAccessibility.swift` | ✅ **CLOSED (R1c, 2026-05-18, ADR-025, commit `8ca758d`)** — per-row invisible semantic overlay over the rect Canvas (target pane only — `diff.base` may be null at first-commit; `diff.target` is "current state"); `LayerChange.PropertyChanged` deliberately not enumerated per-cell (existing `LayerChangesBanner` surfaces it); cells outside target.extents silent-drop on rare shrunken-target case (Tech Debt — LOW). Shared `ChartAccessibility` extended (+`DiffChangeKind` / +`RowDiffChange` / +`RowDiffDescriptor` / +`DiffA11yStrings` / +`spokenDiffLabel`, 47 commonTest total = R1a 21 + R1b 14 + R1c 12). 4 `a11y_diff_*` i18n keys. ~~Per-cell diff identity is 100% traffic-light fill … no a11y element.~~ Polar gated → Phase 35.2+. |

### 3.2 HIGH (fix before declaring the related feature)

| # | Surface | Platform | `file:line` | Gap |
|---|---|---|---|---|
| H1 | Symbol palette cells unlabeled | Both | KT `SymbolPaletteStrip.kt` (cell-level `Modifier.semantics`) · SW `ChartEditorScreen.swift PaletteSymbolCell` | ✅ **CLOSED (R2, 2026-05-18, commit `5be0034`)** — Compose `PaletteSymbolCell` + `LockedPaletteSymbolCell` wire `Modifier.semantics { contentDescription = "Symbol: <name>" + role + selected }` resolving locale via `koinInject<DeviceContextProvider>()` (matches `ChartViewerScreen.kt:712`); `EraserCell` routes through `Res.string.a11y_action_eraser_tool`. SwiftUI `paletteSymbolAccessibilityLabel(for:)` helper (locale via `Locale.current.language.languageCode == "ja"`) + `.accessibilityAddTraits(.isSelected)` on the entitled cell. New `a11y_label_palette_cell` / `a11y_action_eraser_tool` keys. ~~Each stitch symbol cell announces nothing meaningful.~~ |
| H2 | ChartImageGrid hardcoded English | Compose | `chartviewer/ChartImageGrid.kt` (`stringResource(Res.string.*)`) | ✅ **CLOSED (R2, 2026-05-18, commit `5be0034`)** — 4 hardcoded EN strings (title + 3 `contentDescription`) routed through `Res.string.{title_chart_images, a11y_chart_image_thumbnail, a11y_action_remove_image, a11y_action_add_chart_image}`. ~~All literal English, no `stringResource`.~~ |
| H3 | ChartImageViewer hardcoded English | Both | KT `ChartImageViewer.kt` (`stringResource`) · SW `Components/ChartImageViewer.swift` (`.accessibilityLabel`) | ✅ **CLOSED (R2, 2026-05-18, commit `5be0034`)** — Compose 2 hardcoded EN `contentDescription` routed through `Res.string.{a11y_chart_image_fullscreen, a11y_action_close_viewer}`. SwiftUI close `xmark.circle.fill` gets `.accessibilityLabel("a11y_action_close_viewer")`. ~~Sole dismiss affordance unlabeled / English.~~ |
| H4 | Icon-only nav controls unlabeled | iOS | `ProjectListScreen.swift` (overflow) · `ChartEditorScreen.swift` (undo/redo/overflow) · `DiscoveryScreen.swift` (sort) | ✅ **CLOSED (R2, 2026-05-18, commit `5be0034`)** — `.accessibilityLabel("action_more_options" / "action_sort" / "action_undo" / "action_redo")` added (reused existing keys, no new strings). ~~`.accessibilityIdentifier` present but no `.accessibilityLabel`.~~ +1 XCUITest file `IconButtonAccessibilityTests` locks in `.label` non-empty + non-SF-Symbol-name. |
| H5 | ProjectDetail add-image / thumbnails unlabeled | iOS | `ProjectDetailScreen.swift` `chartImagesSection` | ✅ **CLOSED (R2, 2026-05-18, commit `5be0034`)** — `chartImagesSection` ONLY (R4's `counterSection` untouched). PhotosPicker gets `.accessibilityLabel("a11y_action_add_chart_image")`; each thumbnail consolidated to `.accessibilityElement(children: .ignore)` + `.accessibilityLabel("a11y_chart_image_thumbnail")` + `.accessibilityAddTraits(.isButton)` + `.accessibilityAction(named: "action_remove")` — destructive `contextMenu` now surfaces as a named SR action (rotor-discoverable). ~~No `.accessibilityLabel`/`.accessibilityElement`/`.accessibilityAction`.~~ Compose-side `ChartImageThumbnail` opt-in polish remains optional (Tech Debt). |
| H6 | SegmentProgressSummary not a button | Compose | `projectdetail/ProjectDetailScreen.kt:822-856` | Tappable `Text` via `Modifier.clickable` with **no `role = Role.Button`** — the deep-link into chart segment progress is announced as static text, not actionable. |
| H7 | ProjectDetail row counter / +− fixed font | Both | KT `ProjectDetailScreen.kt:CounterSection` · SW `ProjectDetailScreen.swift:counterSection` | ✅ **CLOSED (R4, 2026-05-18)** — Compose: `displayLarge` (rowCounter) + `headlineMedium`/`displaySmall` (-/+) Material 3 typography + `Modifier.defaultMinSize(…)` containers (was `96.sp` / `28.sp` / `36.sp` fixed inside `Modifier.size`); SwiftUI: 3× `@ScaledMetric` reading `DesignTokens.*` through `UIFontMetrics` (relative to `.largeTitle`/`.title`). +2 androidInstrumentedTest at fontScale=2f confirm no clipping + reachable buttons. ~~Single most-used screen does not honor Larger Text.~~ No app-wide Dynamic Type cap applied — Follow-up: candidate ADR slot for cap policy decision pre-Phase-40 GA (R4 worker surfaced via Tech Debt). |
| H8 | MFA code fields empty label | iOS | `MfaChallengeScreen.swift`, `MfaEnrollmentScreen.swift` (`TextField("label_mfa_code_input",…)`) | ✅ **CLOSED (R2, 2026-05-18, commit `5be0034`)** — empty-string `TextField` labels replaced with `"label_mfa_code_input"` (one new shared key, two call sites). ~~Auth-critical 6-digit code fields announce only "text field" — no hint.~~ |
| H9 | No heading semantics (systemic) | Compose | every screen | ✅ **CLOSED (R3, 2026-05-19, commit `57a96ab`)** — `Modifier.semantics { heading() }` applied to every TopAppBar title (29 of 30 inventoried `*Screen*.kt` files; `ProjectListScreen.kt` correctly skipped because Sprint A audit intentionally set `title = { }` empty so the brand wordmark could be collapsed into the overflow menu — no Text composable to annotate) + 10 hero-typography `Text` sites styled with `displayLarge`/`headlineLarge`/`headlineMedium`/`headlineSmall`/`titleLarge`/`displaySmall` that are screen titles or section headers (LoginScreen app-name + email-confirmation, ForceUpdate + Maintenance hero, OAuthProfileSetup hero, Onboarding per-page + diagnostic-consent, PaywallScreen Compose hero, ProfileScreen displayName, SuggestionDetail pr.title, SharedContent pattern.title, FriendInviteConfirm success message). Two semantics imports added per file (`androidx.compose.ui.semantics.{heading,semantics}`). Annotation-only with no behavior/layout/focus-order/rendering effect. Data-text styled as hero typography correctly skipped (ProjectDetail row counter `displayLarge` + +/- glyphs, MfaEnrollment recovery-code value `titleLarge`, Connections invite-code value `headlineSmall`). Rotor/heading navigation now works on long screens (Settings, ChartEditor, ProjectDetail, Onboarding). 35 `*Screen*.kt` files touched. ~~Section headers announce as body text...~~ |

### 3.3 MEDIUM (declarable with reservations / same-wave fix)

| # | Surface | Platform | `file:line` | Gap |
|---|---|---|---|---|
| M1 | Onboarding page-indicator dots color-only | Compose | `onboarding/OnboardingScreen.kt:320-347` | ✅ **CLOSED (R5, 2026-05-19, commit `ebe101d`)** — `border(1.5.dp, onSurface, CircleShape)` outline ring on the active dot (non-color identifier) + parent `Row` `semantics { contentDescription = stringResource(a11y_state_page_indicator_x_of_y); liveRegion = Polite }` so the rotor announces page transitions. ~~Bare `Box`+`background(color)` dots, no semantics.~~ |
| M2 | Pattern/Discovery description ellipsis at large type | Both | KT `PatternLibraryScreen.kt:538,550` (`maxLines=1`), `DiscoveryScreen.kt:499,511` · SW `PatternLibraryScreen.swift:251`, `DiscoveryScreen.swift:409,417` (`.lineLimit(1/2)`) | Essential metadata hard-truncates at large Dynamic Type; no `.minimumScaleFactor`. (VoiceOver still reads full string — visual only.) |
| M3 | Recovery-code copy-tap affordance hidden | Both | KT `MfaEnrollmentScreen.kt:276-302` · SW `MfaEnrollmentScreen.swift:163-172` | ✅ **CLOSED (R5, 2026-05-19, commit `ebe101d`)** — Compose `clickable(onClickLabel = stringResource(a11y_action_copy_recovery_code), role = Role.Button)` + post-copy polite `liveRegion` Text via `state_recovery_code_copied`. iOS parity via SwiftUI `.accessibilityAddTraits(.isButton) + .accessibilityHint + .accessibilityValue` (worker scope, same SHA). ~~No role/hint/action — undiscoverable by SR.~~ |
| M4 | WipeData confirm-phrase field empty label | iOS | `WipeDataConfirmPhraseView.swift` (`TextField("label_wipe_confirm_phrase",…)`) | ✅ **CLOSED (R2, 2026-05-18, commit `5be0034`)** — empty-string `TextField` replaced with `"label_wipe_confirm_phrase"`. ~~Destructive-action gate announces bare "text field".~~ |
| M5 | ChartConflictResolution picked = style weight only | iOS | `ChartConflictResolutionScreen.swift:259` | ✅ **CLOSED (R5, 2026-05-19, commit `ebe101d`)** — `.accessibilityAddTraits(isSelected ? .isSelected : [])` on `pickerButton`; VoiceOver announces the OS-translated "Selected" trait on the picked row. ~~Selected resolution signaled only by `.borderedProminent` vs `.bordered`.~~ |
| M6 | Decorative icons not hidden (VoiceOver noise) | iOS | `LoginScreen.swift:126-128` (Google `g.circle.fill`), `OnboardingScreen.swift:128-130,174` (hero), `ActivityFeedScreen.swift:52-89` (type icon) | ✅ **CLOSED (R5, 2026-05-19, commit `ebe101d`)** — `.accessibilityHidden(true)` applied to all 3 sites; adjacent Text carries the semantic load. ~~SF Symbol names leaking before the real text.~~ |
| M7 | LinearProgressIndicator no value | Compose | `projectlist/ProjectListScreen.kt:660`, `projectdetail/ProjectDetailScreen.kt:660` | ✅ **CLOSED (R5, 2026-05-19, commit `ebe101d`)** — `Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(currentRow, 0f..totalRows); contentDescription = stringResource(a11y_progress_rows_completed_x_of_y, …) }` on both bars; SR now announces "X of Y rows completed" with bound value. ~~Announces a generic progress bar with no percent/value.~~ |

### 3.4 LOW / OK highlights

- **Good a11y infrastructure (positive inventory)**: Compose `ui/components/LiveSnackbarHost.kt` wires `liveRegion = Polite` (every toast announced — WCAG 4.1.3); `ui/components/SelectedCheckmarkIcon.kt` is a deliberate non-color selected-state cue used across PatternLibrary/ProjectList/Discovery/SuggestionList filter chips. iOS `Core/AccessibilityAnnouncements.swift` is the parallel toast announcer, widely used; `SuggestionListScreen`/`SuggestionDetailScreen` are exemplary (status = neutral-gray capsule + **text**, never color).
- **Declaration-ready screens (verified clean, both platforms)**: Auth (ForgotPassword, MfaChallenge-on-Compose, OAuthProfileSetup), Settings, Profile, Suggestion list/detail, ChartConflictResolution (Compose), ChartHistory, ChartVariationPicker, Comments, all Moderation (Report/Block/BlockedUsers), Connections, FriendInvite, SharedWithMe, SharedContent, SymbolGallery, Paywall, PackManagement, BugReport, ForceUpdate, Maintenance, ActivityFeed (text carries type), EmptyStateView, all leaf dialogs. The non-chart surface is in good shape.
- **LOW**: in-button spinner replaces text label while submitting (no `contentDescription` mid-submit) — repeated across Login/ForgotPassword/Profile/Settings dialogs/Paywall (Compose); iOS `.font(.title3, design:.monospaced)` on the MFA secret correctly uses the *text-style* form so it **does** scale (recorded as the correct pattern); ad-hoc `.font(.title2)` headers ✅ **CLOSED (R3, 2026-05-19, commit `57a96ab`)** on `OAuthProfileSetupScreen.swift` + `PaywallScreen.swift` via `.accessibilityAddTraits(.isHeader)` (matches `LoginScreen.swift:353` `LinkIdentityForm` precedent). `LoginScreen.swift` `EmailConfirmationSentView` `.font(.title2)` parity polish recorded as R3 Follow-up #2 — ✅ **CLOSED (R5, 2026-05-19, commit `ebe101d`)** via `.accessibilityAddTraits(.isHeader)` on L289-292; all 3 iOS `.title2` heading sites (LinkIdentityForm + OAuthProfileSetup + Paywall + EmailConfirmationSentView) now carry the trait.
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

## 5. What the operator can declare *today*

(Table simplified 2026-05-19 — R-series wave fully closed; no future column.
R1a/R1b/R1c/R2/R3/R4/R5 all shipped. R5 was non-gating closeout polish.)

| Declaration | Today |
|---|---|
| ダークインターフェイス | ✅ (Phase 18) |
| 視差効果を減らす (Reduce Motion) | ✅ (A25 closed) |
| カラー以外で区別 | ✅ (R1a B2 + R1c B4 + R5 M5/R3#4) |
| さらに大きな文字 (Dynamic Type) | ✅ (R4 + R5 R4#4 icon-scale) |
| VoiceOver | ✅ (B1–B4 via R1a/R1b/R1c + H1–H5 + H8 via R2 + H9 via R3 + M1/M3/M5/M6/M7 + R3#2 via R5) |
| キャプション / バリアフリー音声ガイド | N/A (knitting app, no media) |

Declaring only what passes is mandatory (Apple: "verified end-to-end for core
tasks"; false declarations are App-Review-rejectable, per the CLAUDE.md
Phase-40 entry). **Today's safe declarations: 5/6 ✅** — ダークインターフェイス
+ 視差効果を減らす + カラー以外で区別 + さらに大きな文字 + VoiceOver. The
remaining 1/6 (キャプション / バリアフリー音声ガイド bundled) is N/A. **R-series
wave closed 2026-05-19** — see [completed-archive.md `## R-series wave closure
— Accessibility (2026-05-18/-19)`](../docs/en/phase/completed-archive.md#r-series-wave-closure--accessibility-2026-05-18-19).

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
- **2026-05-18 — R1b (ADR-025) + R4** (audit §4 R4): B3 + H7 CLOSED on both
  platforms. **R1b** (commit `b119558`): shared `ChartAccessibility`
  extended (+`CellAccessibilityDescriptor` / +`spokenCellLabel` /
  +`placeOrEraseActionLabel`, 35 commonTest total = R1a 21 + R1b 14) +
  Compose `ChartEditorScreen.kt` in-row adjustable cell cursor overlay +
  new SwiftUI `ChartEditorAccessibility.swift` + 4 `a11y_editor_*` i18n
  keys (`verifyI18nKeys` 629 → 633 parity). Single named custom action
  flips between `Place <symbol>` / `Erase`. Rect-only (polar Phase 35.2+).
  **R4** (commit `5e22baa`): Compose `displayLarge`/`headlineMedium`/
  `displaySmall` typography (was `96.sp` / `28.sp` / `36.sp`) + SwiftUI
  `@ScaledMetric` (relative to `.largeTitle`/`.title`), containers grow
  via `Modifier.defaultMinSize(…)` (Compose) / @ScaledMetric (SwiftUI);
  +2 androidInstrumentedTest at fontScale=2f. §1 verdicts: **さらに大きな
  文字 (Dynamic Type) declarable today** (M2 visual-only residual); VoiceOver
  still needs B4 (R1c)+R2+R3; Differentiate-Without-Color still needs B4
  (R1c). B4 (Comparison) → R1c remains open. R4 surfaced Tech Debt:
  Dynamic Type cap policy ADR slot, DesignTokens.swift re-definition,
  PatternList/Discovery `.lineLimit(1)` (M2) clamp, ProjectDetail
  18dp status-badge icons (R5 candidate).
- **2026-05-19 — R3** (audit §3.2 H9 + §3.4 LOW): **H9 + iOS `.title2`
  CLOSED** on both platforms (commit `57a96ab`). Compose:
  `Modifier.semantics { heading() }` applied to every TopAppBar title (29
  screens — `ProjectListScreen.kt` correctly skipped, empty `title` per
  Sprint A audit) + 10 hero-typography `Text` sites that are screen titles
  or section headers (`displayLarge`/`headlineLarge`/`headlineMedium`/
  `headlineSmall`/`titleLarge`/`displaySmall`). 35 `*Screen*.kt` files
  touched; two new semantics imports per file. Data-text styled as hero
  typography correctly skipped (ProjectDetail row counter + +/- glyphs,
  MfaEnrollment recovery-code value, Connections invite-code value). iOS:
  `.accessibilityAddTraits(.isHeader)` on `OAuthProfileSetupScreen.swift`
  + `PaywallScreen.swift` (matches `LoginScreen.swift:353` `LinkIdentityForm`
  precedent). Annotation-only — zero behavior/layout/focus-order change;
  `make ci-local` 9/9 green. **§1 declaration matrix becomes 5/6 ✅** —
  VoiceOver flips ✅ (B1–B4 + H1–H5 + H8 + H9 all closed). **Today's safe
  declarations: ダークインターフェイス + 視差効果を減らす + さらに大きな文字 +
  カラー以外で区別 + VoiceOver**. キャプション / バリアフリー音声ガイド N/A
  for a knitting app. §5 progression table updated. R3 surfaced Tech Debt:
  (viii) `LoginScreen.swift` `EmailConfirmationSentView` `.font(.title2)`
  parity polish (third title2 site in the same file; out of R3 declared
  scope per the worker prompt — `OAuthProfileSetup` + `Paywall` only);
  (ix) Compose `titleMedium` section-header second-tier inventory
  (`SettingsScreen` ships 7 such headers + `ChartEditor` / `ProjectDetail`
  similar — worker-prompt baseline scoped to `headline*` / `titleLarge` /
  `displaySmall` / `TopAppBar` only; deliberate scope cut, NOT gating);
  (x) `PaywallScreen.kt` `PackageRow` `.titleMedium` `selected`
  state-description polish (R5-adjacent polish surface). R5 is the sole
  remaining R-series slice; it closes M1/M3/M5/M6/M7 state-not-color polish
  and does **not** gate any ASC declaration. **R-series wave closure**:
  promotes the entire R1a/R1b/R1c/R2/R3/R4/R5 trail from `tech-debt.md`
  into `completed-archive.md` when R5 lands (Archive Policy: wave-close).
- **2026-05-18 — R1c (ADR-025) + R2** (audit §4 R2): **B4 + H1/H2/H3/H4/H5/H8 +
  M4 CLOSED** on both platforms.
  - **R1c** (commit `8ca758d`): shared `ChartAccessibility` extended
    (+`DiffChangeKind` / +`RowDiffChange` / +`RowDiffDescriptor` /
    +`DiffA11yStrings` / +`spokenDiffLabel`, 47 commonTest total =
    R1a 21 + R1b 14 + R1c 12) + Compose `RectComparisonAccessibilityOverlay`
    + new SwiftUI `ChartComparisonAccessibility.swift` + 4 `a11y_diff_*`
    i18n keys (`verifyI18nKeys` 633 → 637 parity). Rect-only (polar
    Phase 35.2+).
  - **R2** (commit `5be0034`): Compose `SymbolPaletteStrip.kt` cell-level
    `Modifier.semantics` + locale-resolved `a11y_label_palette_cell` /
    `a11y_action_eraser_tool`; `ChartImageGrid.kt` 4 strings via
    `stringResource`; `ChartImageViewer.kt` 2 strings via `stringResource`.
    SwiftUI 8 screens amended with `.accessibilityLabel()` (reused
    `action_more_options` / `action_sort` / `action_undo` / `action_redo`;
    new `a11y_action_close_viewer` / `a11y_action_add_chart_image` /
    `a11y_chart_image_thumbnail`); MFA + WipeData TextFields wired to
    `label_mfa_code_input` + `label_wipe_confirm_phrase`. 10 new i18n keys
    (`verifyI18nKeys` 637 → 647 parity). +1 XCUITest file
    (`IconButtonAccessibilityTests`).
  - §1 verdicts: **Differentiate-Without-Color (カラー以外で区別)
    declarable today** (B2 + B4 closed end-to-end); VoiceOver still needs
    R3 (H9). さらに大きな文字 already declarable post-R4. **Today's safe
    declarations after this consolidation: ダークインターフェイス +
    視差効果を減らす + さらに大きな文字 + カラー以外で区別** (4/6).
    §5 progression table: R3 remains sole gate for VoiceOver.
  - R1c surfaced Tech Debt: (i) target extents silent-drop on shrink edges
    (LOW; cells outside target.extents drop from SR readout while
    remaining visible on base pane — union-extents model is a future
    consideration). R2 surfaced Tech Debt: (ii) iOS XCUITest transient-
    flake cluster names (`testCreateSheet_cancelDismisses`,
    `testProjectWithProgress_displaysCorrectly` + R1c observation names —
    added to `ci-known-limitations.md` transient bucket inventory);
    (iii) Compose `ChartImageThumbnail` opt-in `Role.Button` + selected
    polish (H5 was iOS-only; Compose precedent now uses identical
    `a11y_chart_image_thumbnail` key — small polish, not gating);
    (iv) i18n-fragment worker-protocol gap — R2 referenced
    `Res.string.<not-yet-canonical>` without an in-source fallback
    (unlike R1b's `Locale.current.language` bilingual fallback), causing
    the splice to be a mechanical compile-fix prerequisite. Recorded as a
    tech-debt process item.
- **2026-05-19 — R5** (audit §3.3 M1+M3+M5+M6+M7 + §3.4 LOW R3 Follow-up #2
  + tech-debt (viii)+(x) + R4 Follow-up #4): **M1 / M3 / M5 / M6 / M7 +
  iOS EmailConfirmationSentView `.title2` + PaywallScreen PackageRow selected
  + ProjectDetail icon-scale CLOSED** on both platforms (commit `ebe101d`).
  - **M1** Compose `OnboardingScreen.kt` page indicator: non-color outline
    ring (`border(1.5.dp, onSurface, CircleShape)`) on the active dot +
    parent `Row` `semantics { contentDescription = stringResource(
    a11y_state_page_indicator_x_of_y, currentPage + 1, pageCount);
    liveRegion = Polite }` so the rotor announces page transitions.
  - **M3** Compose `MfaEnrollmentScreen.kt` recovery-code copy-tap:
    `clickable(onClickLabel = stringResource(a11y_action_copy_recovery_code),
    role = Role.Button)` + post-copy polite `liveRegion` Text via
    `state_recovery_code_copied`. iOS parity in the same SHA.
  - **M5** iOS `ChartConflictResolutionScreen.swift`:
    `.accessibilityAddTraits(isSelected ? .isSelected : [])` on
    `pickerButton`; OS-translated "Selected" trait.
  - **M6** iOS decorative-icon `.accessibilityHidden(true)` at 3 sites
    (LoginScreen Google `g.circle.fill` L126 / OnboardingScreen hero
    L128 + DiagnosticConsentPageView L174 / ActivityFeedScreen type
    icon L52). Adjacent Text carries the semantic load in every case.
  - **M7** Compose `LinearProgressIndicator` (ProjectListScreen ProjectCard
    L660-663 + ProjectDetail CounterSection L670-684):
    `Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(
    currentRow.toFloat(), 0f..totalRows.toFloat()); contentDescription =
    stringResource(a11y_progress_rows_completed_x_of_y, currentRow,
    totalRows) }`. Mirrors `ChartEditorScreen.kt` R3 precedent.
  - **R3 Follow-up #2** iOS `LoginScreen.swift` `EmailConfirmationSentView`
    L289-292: `.accessibilityAddTraits(.isHeader)`. All 3 iOS `.title2`
    heading sites (`LinkIdentityForm` + `OAuthProfileSetup` + `Paywall` +
    `EmailConfirmationSentView`) now carry the trait.
  - **R3 Follow-up #4 / tech-debt (x)** Compose `PaywallScreen.kt`
    `PackageRow` L342-405: `clickable(role = Role.RadioButton, onClick =
    onSelect).semantics { this.selected = selected; stateDescription = if
    (selected) stringResource(state_selected) else stringResource(
    state_not_selected) }`. Pattern source: `BiometricSettingsScreen.kt`
    R1c `Role.RadioButton` precedent.
  - **R4 Follow-up #4** Compose `ProjectDetailScreen.kt` fixed-dp
    button-content icons scale via `with(LocalDensity.current) { N.sp.toDp()
    }`: `StatusToggleButton` L896/L907 (Refresh + CheckCircle 18.dp) +
    `ResetProgressButton` L933 (Refresh 18.dp) + `AddNoteDialog`
    photo-attached row L982 (Check 16.dp) + add-photo TextButton L1009 (Add
    16.dp). Out-of-scope: L997 IconButton-inner Close 16.dp (touch-target
    redesign) + L1021 CircularProgressIndicator (spinner, not Icon).
  - i18n: 6 new keys (`a11y_state_page_indicator_x_of_y` /
    `a11y_action_copy_recovery_code` / `state_recovery_code_copied` /
    `a11y_progress_rows_completed_x_of_y` / `state_selected` /
    `state_not_selected`) — `verifyI18nKeys` 647 → 653 parity. Worker
    shipped R1b-style `if (isJa)` inline fallback; orchestrator splice
    cleaned both fallback blocks + per-function `deviceContext = koinInject()`
    parameter at consolidation (replace-and-clean precedent, R1b model).
  - Tests: +0 / +0 / +0 (annotation-only + inline ternary string assembly;
    no new logic). Verified end-to-end via `make ci-local` 9/9 green
    (worker) + i18n splice re-verified at consolidation (`verifyI18nKeys`
    + `:shared:ktlintCheck`).
  - **§1 declaration matrix unchanged: 5/6 ✅** — R5 was non-gating closeout
    polish. R-series goal (Phase-40 ASC prerequisite (c)) met end-to-end.
  - **R-series wave closure**: trail R1a/R1b/R1c/R2/R3/R4/R5 promoted to
    `completed-archive.md ## R-series wave closure — Accessibility
    (2026-05-18/-19)` per Archive Policy wave-close cadence at this
    consolidation. Standing residual polish (R1b Follow-up #1 if not
    closed by R2 splice / R1c (v) silent-drop union-extents / R2 (vi)
    ChartImageThumbnail polish / R2 (vii) i18n-fragment process tech debt
    / R3 Follow-up #3 = (ix) Compose `titleMedium` 2nd-tier inventory /
    R4 Follow-up #3 = M2 PatternList/Discovery `.lineLimit(1)` clamp)
    remains in `tech-debt.md` as non-gating bullets; none gates any ASC
    declaration.
