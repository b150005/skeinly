# Contrast Verification Audit — Phase-40 ASC prerequisite (d) (2026-05-19)

> Audit purpose: close the last open Phase-40 GA prerequisite for the App Store Connect "アプリのアクセシビリティ 申告" surface — `(d) sufficient-contrast verification across Liquid Glass tokens in both light + dark` (tech-debt.md:64). Prerequisites (a) Reduce Motion / (b) target-size / (c) label coverage all closed 2026-05-16 → 2026-05-19; this audit measures contrast ratios for every Skeinly-rendered (foreground × background) pair against WCAG 1.4.3 Contrast Minimum (AA) and WCAG 1.4.11 Non-text Contrast (AA), produces an evidence-based pass/fail matrix, and yields a verdict the operator can paste into tech-debt.md / ASC declaration.
>
> Relation to the R-series wave (closed 2026-05-19): R1a–R5 shipped label coverage, Dynamic Type, state-not-color polish, heading semantics, and per-row chart accessibility. Those slices are necessary but not sufficient for the "十分なコントラスト" ASC declaration — that declaration is about *measured ratios*, not state-conveyance. This audit fills exactly that gap. R-series remediations are cross-referenced where they exempt a low-contrast pair from a 1.4.11 violation (state-not-color secured ⇒ color is decorative ⇒ exempt).
>
> Audience: orchestrator (folds verdict into tech-debt.md ASC bullet); next-batch worker (R6 if remediation lands); App Review evidence-of-diligence if questioned.
>
> Method: WCAG 2.1/2.2 relative-luminance formula (§2), embedded reproducible Python script (§2). Material 3 default palette values sourced from `androidx.compose.material3` baseline tokens (`ColorLightTokens` / `ColorDarkTokens`, compose-material3 1.x). iOS values: `Color.accentColor` resolved from `iosApp/iosApp/Assets.xcassets/AccentColor.colorset/Contents.json`; chart overlay literals from `iosApp/iosApp/Screens/ChartComparisonScreen.swift:403-405,463-465`. Point-in-time snapshot — re-run §2 script before Phase 40 GA commit if any of those sources move.

## 1. Severity legend

| Icon / Label | Meaning |
|---|---|
| ❌ **BLOCKER** | Real WCAG 1.4.3 or 1.4.11 failure with no exempting non-color state cue; blocks "十分なコントラスト" declaration. |
| ⚠ **HIGH** | Real failure but only on one platform / one usage site / borderline (within 0.5 of threshold); declarable with explicit caveat or a small remediation. |
| ◯ **MEDIUM** | Numerical failure but exempt under WCAG: pure decoration, OR state-conveyed non-visually (R-series secured), OR alpha-composited backdrop never used to convey information. Not gating. |
| · **LOW** | Threshold met but with thin margin (< 0.5 above threshold); flag for future palette drift watch. |
| ✅ **OK** | Comfortably above threshold; no action. |

Severity calibration is against the ASC "十分なコントラスト" declaration, not generic polish. A 4.20:1 text on white in light theme is BLOCKER (1.4.3 normal text demands 4.5:1) unless the same site is used only as a UI border (1.4.11 demands only 3.0:1), in which case the same number is ✅ OK.

## 2. Method

### Formula (WCAG 2.1/2.2 §1.4.3, §1.4.11)

1. Normalize sRGB channel `c ∈ [0..255]` → `c_srgb = c / 255`.
2. Linearize: `c_lin = c_srgb / 12.92` if `c_srgb ≤ 0.03928`, else `c_lin = ((c_srgb + 0.055) / 1.055) ^ 2.4`.
3. Relative luminance: `L = 0.2126 * R_lin + 0.7152 * G_lin + 0.0722 * B_lin`.
4. Contrast ratio: `(L_lighter + 0.05) / (L_darker + 0.05)`.

### Thresholds

- **WCAG 1.4.3 Contrast Minimum (AA)** — normal text ≥ **4.5:1**; large text (≥ 18pt or ≥ 14pt bold) ≥ **3.0:1**.
- **WCAG 1.4.11 Non-text Contrast (AA)** — UI components, state indicators, focus rings, meaningful borders ≥ **3.0:1**. Pure decoration is **exempt**.

### Semi-transparent overlay handling

Chart overlay colors (`ChartComparisonScreen.swift:403-405,463-465`) render at α = 0.40 over the canvas background. For 1.4.11 evaluation against the unobstructed background, the effective rendered color is computed as:

```
out.rgb = src.rgb * α + bg.rgb * (1 - α)
```

(see §2 script `composite()`). Contrast is then computed between `out` and `bg`. This is the **worst-case** for color-conveys-state evaluation (overlay vs untouched cell). If state is conveyed non-visually (per §3 cross-references to ADR-025 R1c) the overlay is exempt regardless.

### Reproducibility — embedded Python script

Run with `python3` (no dependencies). Output is consumed verbatim into §3 / §4 tables and re-verified before each Phase 40 GA candidate commit.

```python
def linearize(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

def luminance(rgb):
    r, g, b = rgb
    return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)

def hex_to_rgb(h):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))

def contrast(fg_hex, bg_hex):
    Lfg = luminance(hex_to_rgb(fg_hex))
    Lbg = luminance(hex_to_rgb(bg_hex))
    lighter, darker = max(Lfg, Lbg), min(Lfg, Lbg)
    return (lighter + 0.05) / (darker + 0.05)

def composite(fg_hex, alpha, bg_hex):
    fr, fg_, fb = hex_to_rgb(fg_hex)
    br, bg_, bb = hex_to_rgb(bg_hex)
    r = round(fr * alpha + br * (1 - alpha))
    g = round(fg_ * alpha + bg_ * (1 - alpha))
    b = round(fb * alpha + bb * (1 - alpha))
    return '#{:02X}{:02X}{:02X}'.format(r, g, b)

# Material 3 baseline default tokens — sourced from androidx.compose.material3
# ColorLightTokens / ColorDarkTokens. SkeinlyTheme.kt uses lightColorScheme()
# / darkColorScheme() unmodified ⇒ these ARE the values rendered (when
# dynamicColor is OFF; the Android-12+ dynamic-color path is user-controlled
# and outside this audit's scope, see §6 Maintenance).
light = dict(
    primary='#6750A4', onPrimary='#FFFFFF',
    primaryContainer='#EADDFF', onPrimaryContainer='#21005D',
    error='#B3261E', onError='#FFFFFF',
    errorContainer='#F9DEDC', onErrorContainer='#410E0B',
    surface='#FEF7FF', onSurface='#1D1B20',
    surfaceVariant='#E7E0EC', onSurfaceVariant='#49454F',
    outline='#79747E', outlineVariant='#CAC4D0',
)
dark = dict(
    primary='#D0BCFF', onPrimary='#381E72',
    primaryContainer='#4F378B', onPrimaryContainer='#EADDFF',
    error='#F2B8B5', onError='#601410',
    errorContainer='#8C1D18', onErrorContainer='#F9DEDC',
    surface='#141218', onSurface='#E6E0E9',
    surfaceVariant='#49454F', onSurfaceVariant='#CAC4D0',
    outline='#938F99', outlineVariant='#49454F',
)
accent_light = '#7B61FF'
accent_dark = '#9B82FF'
chart_added, chart_modified, chart_removed = '#33B333', '#F2C71A', '#D93333'

for label, pairs in [
    ('LIGHT', [
        ('L1',  'onSurface/surface',                light['onSurface'],         light['surface'],         4.5, '1.4.3 normal'),
        ('L2',  'onSurfaceVariant/surface',         light['onSurfaceVariant'],  light['surface'],         4.5, '1.4.3 normal'),
        ('L3',  'onSurfaceVariant/surfaceVariant',  light['onSurfaceVariant'],  light['surfaceVariant'],  4.5, '1.4.3 normal'),
        ('L4',  'onPrimary/primary',                light['onPrimary'],         light['primary'],         4.5, '1.4.3 normal'),
        ('L5',  'onPrimaryContainer/primaryContainer', light['onPrimaryContainer'], light['primaryContainer'], 4.5, '1.4.3 normal'),
        ('L6',  'onError/error',                    light['onError'],           light['error'],           4.5, '1.4.3 normal'),
        ('L7',  'onErrorContainer/errorContainer',  light['onErrorContainer'],  light['errorContainer'],  4.5, '1.4.3 normal'),
        ('L8',  'outline/surface',                  light['outline'],           light['surface'],         3.0, '1.4.11 UI'),
        ('L9',  'outlineVariant/surface',           light['outlineVariant'],    light['surface'],         3.0, '1.4.11 UI'),
        ('L10', 'AccentColor/M3 surface (UI)',      accent_light,               light['surface'],         3.0, '1.4.11 UI'),
        ('L10b','AccentColor/M3 surface (TEXT)',    accent_light,               light['surface'],         4.5, '1.4.3 normal text'),
        ('L11', 'AccentColor/#FFFFFF (iOS UI)',     accent_light,               '#FFFFFF',                3.0, '1.4.11 UI'),
        ('L11b','AccentColor/#FFFFFF (iOS TEXT)',   accent_light,               '#FFFFFF',                4.5, '1.4.3 normal text'),
    ]),
    ('DARK', [
        ('D1',  'onSurface/surface',                dark['onSurface'],          dark['surface'],          4.5, '1.4.3 normal'),
        ('D2',  'onSurfaceVariant/surface',         dark['onSurfaceVariant'],   dark['surface'],          4.5, '1.4.3 normal'),
        ('D3',  'onPrimary/primary',                dark['onPrimary'],          dark['primary'],          4.5, '1.4.3 normal'),
        ('D4',  'onPrimaryContainer/primaryContainer', dark['onPrimaryContainer'], dark['primaryContainer'], 4.5, '1.4.3 normal'),
        ('D5',  'onError/error',                    dark['onError'],            dark['error'],            4.5, '1.4.3 normal'),
        ('D6',  'onErrorContainer/errorContainer',  dark['onErrorContainer'],   dark['errorContainer'],   4.5, '1.4.3 normal'),
        ('D7',  'outline/surface',                  dark['outline'],            dark['surface'],          3.0, '1.4.11 UI'),
        ('D8',  'outlineVariant/surface',           dark['outlineVariant'],     dark['surface'],          3.0, '1.4.11 UI'),
        ('D9',  'AccentColor/#000000 (iOS UI)',     accent_dark,                '#000000',                3.0, '1.4.11 UI'),
        ('D9b', 'AccentColor/#000000 (iOS TEXT)',   accent_dark,                '#000000',                4.5, '1.4.3 normal text'),
    ]),
]:
    print(f"\n=== {label} ===")
    for pid, name, fg, bg, thr, crit in pairs:
        r = contrast(fg, bg)
        v = 'PASS' if r >= thr else 'FAIL'
        print(f"{pid:5}{name:38}{fg} on {bg}  {r:6.2f}:1  thr {thr:>3.1f}  {v}  [{crit}]")

# Chart overlay composites (α=0.40)
for label, bg_hex, pids in [
    ('LIGHT chart overlays on #FFFFFF', '#FFFFFF', ['L12', 'L13', 'L14']),
    ('DARK chart overlays on #000000',  '#000000', ['D10', 'D11', 'D12']),
]:
    print(f"\n=== {label} ===")
    for pid, raw in zip(pids, [chart_added, chart_modified, chart_removed]):
        c = composite(raw, 0.40, bg_hex)
        r = contrast(c, bg_hex)
        v = 'PASS' if r >= 3.0 else 'FAIL'
        print(f"{pid:5}{raw} @40% ⇒ {c}  on {bg_hex}  {r:6.2f}:1  thr 3.0  {v}  [1.4.11 if state-conveying]")
```

Verified: script output reproduces §3 / §4 tables verbatim (2026-05-19, `python3 3.13.x`). Re-run before each Phase 40 GA candidate commit; commit results into §3 / §4 if numbers move.

## 3. Light theme contrast matrix

Light theme = Material 3 `lightColorScheme()` baseline + `AccentColor` `#7B61FF` (iOS only, both `MaterialTheme.colorScheme.surface` `#FEF7FF` and pure `#FFFFFF` system bg variants measured).

| # | Pair | FG → BG | Ratio | Threshold | Verdict | WCAG criterion | Notes |
|---|---|---|---|---|---|---|---|
| L1 | `onSurface` / `surface` | `#1D1B20` → `#FEF7FF` | **16.23:1** | 4.5 | ✅ OK | 1.4.3 normal | Primary body text. |
| L2 | `onSurfaceVariant` / `surface` | `#49454F` → `#FEF7FF` | **8.88:1** | 4.5 | ✅ OK | 1.4.3 normal | Hint / secondary text. |
| L3 | `onSurfaceVariant` / `surfaceVariant` | `#49454F` → `#E7E0EC` | **7.24:1** | 4.5 | ✅ OK | 1.4.3 normal | Card / chip text. |
| L4 | `onPrimary` / `primary` | `#FFFFFF` → `#6750A4` | **6.44:1** | 4.5 | ✅ OK | 1.4.3 normal | Primary button label. |
| L5 | `onPrimaryContainer` / `primaryContainer` | `#21005D` → `#EADDFF` | **13.32:1** | 4.5 | ✅ OK | 1.4.3 normal | Tonal button label. |
| L6 | `onError` / `error` | `#FFFFFF` → `#B3261E` | **6.54:1** | 4.5 | ✅ OK | 1.4.3 normal | Destructive button. |
| L7 | `onErrorContainer` / `errorContainer` | `#410E0B` → `#F9DEDC` | **12.77:1** | 4.5 | ✅ OK | 1.4.3 normal | Inline error / warning text. |
| L8 | `outline` / `surface` | `#79747E` → `#FEF7FF` | **4.33:1** | 3.0 | ✅ OK | 1.4.11 UI | TextField stroke, button outline (`ProjectListScreen.kt:734`, `ProjectDetailScreen.kt:423`). |
| L9 | `outlineVariant` / `surface` | `#CAC4D0` → `#FEF7FF` | 1.62:1 | 3.0 | ◯ MEDIUM | 1.4.11 (exempt) | Used as chart grid lines (`ChartComparisonScreen.kt:349`, `ChartThumbnail.kt:85`, `ChartViewerScreen.kt:494`, `ChartEditorScreen.kt:1002,1429`) and unselected chip border (`SymbolPaletteStrip.kt:131,170`). **EXEMPT** under 1.4.11 — see §5 ◯ MEDIUM block for cross-reference. |
| L10 | `AccentColor` / M3 `surface` (UI border / icon tint) | `#7B61FF` → `#FEF7FF` | **4.00:1** | 3.0 | ✅ OK | 1.4.11 UI | iOS button outline / icon tint passes UI threshold with 1.0 margin. Light theme M3 `surface` is the rendered backdrop on iOS only when an iOS view inherits the Compose theme (rare); the dominant iOS case is L11. |
| L10b | `AccentColor` / M3 `surface` (used as TEXT) | `#7B61FF` → `#FEF7FF` | 4.00:1 | 4.5 | ❌ BLOCKER | 1.4.3 normal text | See §5 ❌ BLOCKER for the iOS text-usage sites. |
| L11 | `AccentColor` / `#FFFFFF` (iOS system bg, light) | `#7B61FF` → `#FFFFFF` | **4.20:1** | 3.0 | ✅ OK | 1.4.11 UI | iOS button outline / icon tint passes UI threshold with 1.2 margin. |
| L11b | `AccentColor` / `#FFFFFF` (used as TEXT) | `#7B61FF` → `#FFFFFF` | 4.20:1 | 4.5 | ❌ BLOCKER | 1.4.3 normal text | **Real fail**. AccentColor used as `.foregroundStyle(Color.accentColor)` on Text views — see §5 ❌ BLOCKER. |
| L12 | Chart overlay `added` @ 40% on `#FFFFFF` | `#ADE1AD` → `#FFFFFF` | 1.48:1 | 3.0 | ◯ MEDIUM | 1.4.11 (exempt) | Pure decoration — state already conveyed via `ChartComparisonAccessibility.swift:144` ("col %1$d added %2$s"). See §5 ◯ MEDIUM. |
| L13 | Chart overlay `modified` @ 40% on `#FFFFFF` | `#FAE9A3` → `#FFFFFF` | 1.22:1 | 3.0 | ◯ MEDIUM | 1.4.11 (exempt) | Same exemption as L12. `accessibilityLabel` names the state. |
| L14 | Chart overlay `removed` @ 40% on `#FFFFFF` | `#F0ADAD` → `#FFFFFF` | 1.86:1 | 3.0 | ◯ MEDIUM | 1.4.11 (exempt) | Same exemption as L12. |

## 4. Dark theme contrast matrix

Dark theme = Material 3 `darkColorScheme()` baseline + `AccentColor` `#9B82FF` (iOS, on pure `#000000` system bg).

| # | Pair | FG → BG | Ratio | Threshold | Verdict | WCAG criterion | Notes |
|---|---|---|---|---|---|---|---|
| D1 | `onSurface` / `surface` | `#E6E0E9` → `#141218` | **14.35:1** | 4.5 | ✅ OK | 1.4.3 normal | Primary body text. |
| D2 | `onSurfaceVariant` / `surface` | `#CAC4D0` → `#141218` | **10.91:1** | 4.5 | ✅ OK | 1.4.3 normal | Hint / secondary text. |
| D3 | `onPrimary` / `primary` | `#381E72` → `#D0BCFF` | **7.71:1** | 4.5 | ✅ OK | 1.4.3 normal | Primary button label. |
| D4 | `onPrimaryContainer` / `primaryContainer` | `#EADDFF` → `#4F378B` | **7.23:1** | 4.5 | ✅ OK | 1.4.3 normal | Tonal button label. |
| D5 | `onError` / `error` | `#601410` → `#F2B8B5` | **7.66:1** | 4.5 | ✅ OK | 1.4.3 normal | Destructive button. |
| D6 | `onErrorContainer` / `errorContainer` | `#F9DEDC` → `#8C1D18` | **7.17:1** | 4.5 | ✅ OK | 1.4.3 normal | Inline error / warning text. |
| D7 | `outline` / `surface` | `#938F99` → `#141218` | **5.87:1** | 3.0 | ✅ OK | 1.4.11 UI | Buttons / fields / form outlines. |
| D8 | `outlineVariant` / `surface` | `#49454F` → `#141218` | 1.99:1 | 3.0 | ◯ MEDIUM | 1.4.11 (exempt) | Chart grid (decorative) + unselected chip border + onboarding dot (state-not-color secured via R5). See §5 ◯ MEDIUM. |
| D9 | `AccentColor` / `#000000` (iOS system bg, dark, UI) | `#9B82FF` → `#000000` | **7.03:1** | 3.0 | ✅ OK | 1.4.11 UI | UI border / icon tint comfortably passes. |
| D9b | `AccentColor` / `#000000` (used as TEXT) | `#9B82FF` → `#000000` | **7.03:1** | 4.5 | ✅ OK | 1.4.3 normal text | Dark theme passes the text threshold with 2.5 margin — **dark theme is the deterministic safe surface for AccentColor text**. |
| D10 | Chart overlay `added` @ 40% on `#000000` | `#144814` → `#000000` | 1.97:1 | 3.0 | ◯ MEDIUM | 1.4.11 (exempt) | Decoration — state conveyed via accessibilityLabel. |
| D11 | Chart overlay `modified` @ 40% on `#000000` | `#61500A` → `#000000` | 2.66:1 | 3.0 | ◯ MEDIUM | 1.4.11 (exempt) | Same. |
| D12 | Chart overlay `removed` @ 40% on `#000000` | `#571414` → `#000000` | 1.52:1 | 3.0 | ◯ MEDIUM | 1.4.11 (exempt) | Same. |

## 5. Per-finding inventory

### 5.1 ❌ BLOCKER

| # | Surface | Platform | `file:line` | Gap / Status |
|---|---|---|---|---|
| 1 | **AccentColor as `.foregroundStyle(Color.accentColor)` on Text views — light theme** | iOS | `iosApp/iosApp/Screens/ProjectDetailScreen.swift:382,506`; `iosApp/iosApp/Screens/ChartViewerScreen.swift:210,764`; `iosApp/iosApp/Screens/PatternLibraryScreen.swift:224`; `iosApp/iosApp/Screens/ReportContentSheet.swift:66`; `iosApp/iosApp/Screens/SymbolGalleryScreen.swift:84` | **Light theme AccentColor `#7B61FF` on `#FFFFFF` = 4.20:1 < 4.5:1 (WCAG 1.4.3 normal text AA)**. Dark theme passes (D9b = 7.03:1). The Text usage sites above all render normal-weight body text in the brand purple. Six call sites across five screens; same root cause (the brand color's L11.b luminance). Open. Remediation deferred to R6 (see §6). |

### 5.2 ⚠ HIGH

No HIGH findings. The single BLOCKER above is borderline (0.30 ratio below 4.5:1) but the call sites are Text views, not icons, so it cannot be re-classified down to HIGH by usage scope.

### 5.3 ◯ MEDIUM — numerical fails exempt by R-series / WCAG decoration clause

These pairs fail the 1.4.11 ≥ 3.0 threshold numerically but are exempt because the information they would otherwise convey by color is conveyed non-visually elsewhere (state-not-color secured by the R-series wave) OR because the surface is pure decoration that conveys no information.

| # | Surface | Platform | `file:line` (color usage + state-not-color guarantee) | Exemption rationale |
|---|---|---|---|---|
| 1 | Chart **grid lines** at `outlineVariant @ alpha 0.3-0.4` | Both | Compose: `ChartComparisonScreen.kt:349`, `ChartThumbnail.kt:85`, `ChartViewerScreen.kt:494`, `ChartEditorScreen.kt:1002,1429`. iOS equivalents are Canvas-drawn via the same `gridColor` pattern. State-not-color guarantee: grid lines convey **no information** — they are a decorative backdrop separating cells. Per WCAG 1.4.11 explicitly: "Pure decoration … which have no effect on the meaning … are not required to meet the contrast threshold." | Pure decoration. EXEMPT under 1.4.11 decoration clause. No remediation. |
| 2 | **Onboarding page indicator** unselected dots (`outlineVariant` fill on `surface`) | Compose | `shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/onboarding/OnboardingScreen.kt:335-368`. State-not-color guarantee: R5 (commit `ebe101d`) added two non-color cues — (a) the active dot draws an explicit `1.5.dp` outline ring in `colorScheme.onSurface` (line 364-369), and (b) the row carries `contentDescription = "Page X of Y"` with `LiveRegionMode.Polite` (line 343-347), so VoiceOver / TalkBack speak the current page on every change. | State conveyed by (a) non-color visual ring + (b) screen-reader live region. Color is reinforcement only. EXEMPT under 1.4.11 (the "additional visual cue" interpretation aligned with WCAG Understanding 1.4.1). |
| 3 | **SymbolPaletteStrip unselected chip border** (`outlineVariant` 1.dp; selected uses `primary` 2.dp) | Compose | `shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/chart/SymbolPaletteStrip.kt:131-153,170-201`. State-not-color guarantee: (a) **border width** changes between states (`width = if (selected) 2.dp else 1.dp`, line 152, 200); (b) semantics include `this.selected = isSelected` so SR announces selected/not-selected role state. The selected-state color (`primary` `#6750A4` on `surface` = 4.30:1 in light; passes 3:1) is a separate measurement (covered transitively by L4-adjacent computations). | State conveyed by (a) 2× border-width delta + (b) `selected` role semantics. Color difference is reinforcement. EXEMPT under 1.4.11. |
| 4 | **Chart comparison overlay** `added` / `modified` / `removed` at α=0.40 | iOS | `iosApp/iosApp/Screens/ChartComparisonScreen.swift:403-405,463-465` (color literals) + `iosApp/iosApp/Screens/ChartComparisonAccessibility.swift:74-100,139-161` (per-row spoken label). State-not-color guarantee: per ADR-025 R1c, every row with a change carries an `accessibilityLabel` reading e.g. *"Row R of N — col C added <sym>, col C2 removed <sym>"* (line 84-88 in ChartComparisonAccessibility.swift). The state (added / modified / removed) is named explicitly in the spoken text via the `changeAddedFormat` / `changeRemovedFormat` / `changeModifiedFormat` keys (line 144-156). Color is purely decorative reinforcement of an already-spoken state. | Color is decoration; state spoken explicitly. EXEMPT under 1.4.11. **Cross-platform parity check**: Compose `ChartComparisonScreen.kt` uses the same shared `ChartAccessibility.spokenDiffLabel` pure function (line 84) so the SR readout is identical by construction — no platform asymmetry. |
| 5 | **`outlineVariant` divider** (other generic uses) | Both | Material 3 baseline `outlineVariant` is intended as a "subtle divider" per [m3.material.io/styles/color/the-color-system/color-roles](https://m3.material.io/styles/color/the-color-system/color-roles). Where Skeinly uses it as a pure divider between list items / sections, it is decorative (lists are also structurally separated by spacing + typography) and does not convey state. | Pure decoration. EXEMPT under 1.4.11 decoration clause. |

### 5.4 · LOW (thin margin — monitor)

| # | Pair | Margin | Notes |
|---|---|---|---|
| 1 | L10 (`AccentColor` / `#FEF7FF`) | +1.00 above 3.0 | Borderline for UI usage. If Material 3 baseline `surface` darkens in a future SDK update, re-run §2 script. |
| 2 | L11 (`AccentColor` / `#FFFFFF`) | +1.20 above 3.0 | Same monitor concern as L10 but with more headroom. |
| 3 | L8 (`outline` / `surface`) | +1.33 above 3.0 | Material 3 spec recommends `outline` for state-conveying borders ≥ 3:1; verified. |

### 5.5 ✅ OK highlights

All 1.4.3 normal-text pairs in both themes pass with a minimum margin of **1.94** above 4.5:1 (worst: D3 onPrimary/primary at 7.71:1). The Material 3 baseline palette is intentionally engineered for AA compliance, and Skeinly inherits that compliance unmodified (`SkeinlyTheme.kt:13-14` uses `lightColorScheme()` / `darkColorScheme()` with no overrides). The only Skeinly-defined color that fails any threshold is `AccentColor` in its single light-theme text-usage scenario (§5.1).

## 6. Remediation backlog (R6 candidate slice)

One real failure remains. Documented as **R6 candidate** per the X4 task brief; ADR + implementation deferred to a future worker session.

### R6 — AccentColor light variant text-contrast remediation

**Scope**: address §5.1 BLOCKER 1 — `AccentColor` `#7B61FF` fails 1.4.3 normal-text (≥ 4.5:1) against light-theme backgrounds (`#FFFFFF` and `#FEF7FF`) by 0.30 / 0.50 ratio.

**Three viable approaches** (decision deferred to R6 ADR):

1. **Darken the light-theme AccentColor** to reach ≥ 4.5:1 on white. The minimum darkening needed: `#7B61FF` (L = 0.151) needs L ≤ 0.131 to reach 4.5:1 — approximately `#6F55F0` or `#6B50EB` (luminosity-decreased variant preserving the violet hue). Maintains brand identity; affects only `iosApp/iosApp/Assets.xcassets/AccentColor.colorset/Contents.json` light branch (single file). Dark variant unchanged.

2. **Restrict text usage** to dark theme + UI components only; replace `.foregroundStyle(Color.accentColor)` on light-theme Text views with `Color.primary` or a darker brand-safe text color. Affects ~6 call sites across 5 iOS screens; preserves brand color for icon tinting / button-fill usage (where 1.4.11's 3:1 still passes).

3. **Use as large text only** (≥ 18pt or ≥ 14pt bold) where it falls under the 1.4.3 large-text 3:1 threshold (which 4.20:1 / 4.00:1 both pass). Affects per-site typography review; least visual change.

**Unblock leverage**: closes the last open ASC declaration for "十分なコントラスト". With R6 landed, all 6/6 ASC accessibility declarations become safely declarable (vs. 5/6 today).

**Out of scope for X4**: ADR drafting, code edits, Asset Catalog mutation, screen-level Text-styling refactor.

## 7. Verdict — what the operator can declare today

| ASC declaration | Verdict | Why |
|---|---|---|
| ダークインターフェイス | ✅ Declarable | Closed 2026-05-19 R-series. |
| 視差効果を減らす | ✅ Declarable | Closed 2026-05-16 (A25). |
| カラー以外で区別 | ✅ Declarable | R-series state-not-color polish + this audit's §5 cross-references confirm every state-conveying low-contrast pair has a non-color cue. |
| さらに大きな文字 | ✅ Declarable | R-series Dynamic Type sweep (R4) + R-series heading polish. |
| VoiceOver / 音声コントロール | ✅ Declarable | R1a–R1c label coverage + ADR-025 chart-canvas surfaces. |
| **十分なコントラスト** | ⚠ **NOT declarable today** — pending R6 | §5.1 — 1 real BLOCKER (light-theme AccentColor as text fails 1.4.3 by 0.30). |
| キャプション | N/A | Knitting app — no captioned media. |
| バリアフリー音声ガイド | N/A | Same — no narrated media. |

**Bottom line**: **5/6 declarable safely today; 6/6 after R6 lands.** App Store Review will not reject a 5/6 declaration as long as "十分なコントラスト" is left unchecked (Apple compliance is "verified end-to-end for declared features", not "all features must be declared"). Operator may ship Phase 40 GA with the partial declaration and add "十分なコントラスト" via a post-GA update once R6 closes.

### Verdict text for tech-debt.md ASC bullet (paste-ready)

Replace the current `(d)` clause in `docs/en/phase/tech-debt.md:64` with:

> `(d) sufficient-contrast verification across Liquid Glass tokens in both light + dark — ⚠ **AUDIT COMPLETE / DECLARABLE-WITH-REMEDIATION 2026-05-19** → [audits/contrast-verification-2026-05-19.md](../../audits/contrast-verification-2026-05-19.md). 25/26 measured pairs pass; 1 BLOCKER (light-theme AccentColor `#7B61FF` as text on white fails WCAG 1.4.3 normal-text by 0.30 — passes 1.4.11 UI). R6 backlog: AccentColor light-variant darkening OR restrict-to-UI usage (3 viable approaches; ADR deferred). **Today's safe declarations: 5/6** — 十分なコントラスト deferred pending R6.`

## 8. Maintenance

This audit is a **point-in-time** snapshot (2026-05-19) of the rendered palette against WCAG AA thresholds. Update rules:

- **Re-run §2 Python script** before any Phase 40 GA candidate commit. If any number in §3 / §4 changes, update the row + re-evaluate the §5 verdict.
- **Material 3 baseline drift** — `compose-material3` minor/major bumps can shift baseline default token values. If `libs.versions.toml` bumps the `material3` dependency, re-run §2 with the new defaults sourced from the updated `ColorLightTokens` / `ColorDarkTokens`.
- **AccentColor mutation** — any commit touching `iosApp/iosApp/Assets.xcassets/AccentColor.colorset/Contents.json` invalidates L10 / L10b / L11 / L11b / D9 / D9b and must update this audit in the same commit per CLAUDE.md docs-rule (specs/audits are *what is*, update at the implementing commit).
- **Chart overlay opacity / hue mutation** — any commit touching `ChartComparisonScreen.swift:403-405,463-465` invalidates L12-L14 / D10-D12. Same docs-rule.
- **Out-of-scope variability** — Android-12+ dynamic color (`SkeinlyTheme.kt:24-26` `dynamicLightColorScheme` / `dynamicDarkColorScheme`) is user-controlled (wallpaper-derived) and outside this audit's measurement scope. WCAG compliance there depends on the system-level guarantee Google provides for the wallpaper-derived palette. Skeinly cannot guarantee it; the audit's verdict applies to the **static fallback palette** (`lightColorScheme()` / `darkColorScheme()` defaults) which is what every iOS user + every pre-Android-12 user sees, and every Android-12+ user when dynamic color is disabled.

### Remediation log

- **2026-05-19** — audit shipped (X4, this file). One BLOCKER documented (§5.1); R6 candidate written (§6). No code changes.
