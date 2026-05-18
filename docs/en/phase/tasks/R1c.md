# R1c — Chart Comparison accessibility (per-row change list)

## Status

`READY_FOR_CONSOLIDATION`

## Scope

ADR-025 R1 の最終サブスライス。Chart Comparison 画面に不可視 per-row a11y overlay を追加し、各「変更があった行」を VoiceOver / TalkBack が `"Row R of N — col C added <sym>, col C2 removed <sym>, …"` として読み上げる。100% トラフィックライト塗り (audit §3.1 B4 ブロッカー) を spoken text による補完で解消する。Read-only (アクション無し)。Rect のみ — Polar は ADR-025 §e ゲートに従い Phase 35.2+ へ deferred。共有 pure model (`ChartAccessibility.rowDiffDescriptors` / `spokenDiffLabel` / `RowDiffDescriptor` / `RowDiffChange` / `DiffA11yStrings` / `DiffChangeKind`) を追加し、Compose `RectComparisonAccessibilityOverlay` (private composable in `ChartComparisonScreen.kt`) + SwiftUI `ChartComparisonAccessibilityOverlay` (new file `ChartComparisonAccessibility.swift`) が同一テキストを by construction で発話。

**Out of scope** (R1c では触らない): Polar 対応、`DiffSummaryRow` / `LayerChangesBanner` の再設計 (既存 a11y はそのまま継続)、R2 (symbol palette i18n)、R3 (heading semantics)、base ペインへの overlay 追加 (target ペインのみ — base は `null` になりうる + target が「現在の状態」で row index 基底)、ChartComparisonViewModel / `ChartComparisonAlgorithm`、Supabase 側、`pull-request-flow.md` への波及。

## Declared write-set

- `shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/chart/ChartAccessibility.kt`
- `shared/src/commonTest/kotlin/io/github/b150005/skeinly/domain/chart/ChartAccessibilityTest.kt`
- `shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/chart/ChartComparisonScreen.kt`
- `iosApp/iosApp/Screens/ChartComparisonScreen.swift`
- `iosApp/iosApp/Screens/ChartComparisonAccessibility.swift`  <!-- NEW -->
- `docs/en/adr/025-chart-canvas-accessibility.md`  <!-- revision history append のみ — hot file -->
- `docs/ja/adr/025-chart-canvas-accessibility.md`  <!-- 同上 (JA mirror) -->
- `docs/en/phase/tasks/R1c.md`  <!-- this file -->
- `docs/en/phase/tasks/R1c.i18n.tsv`  <!-- 4 new keys -->

Hot-file disjointness: `ls docs/en/phase/tasks/` で R1c.* 以外のタスクファイル無し (`_TEMPLATE.md` + `README.md` のみ) を session 開始時に確認。ADR-025 / `ChartAccessibility.kt` を触る sibling worktree は無く、安全に append 可能。**write-set 違反なし** — `git diff --name-only` の全パスが declared 内 (.claude/docs/spec/collaboration-history.md は触らないので declared から外した)。

## ADR / Spec refs

- **ADR-025** — `docs/en/adr/025-chart-canvas-accessibility.md` (§a granularity, §c per-surface = Comparison, §d single coordinate space, §e rect-only, §g pure shared formatter)。Revision history に R1c エントリを append (§a–§g は不変、mechanism only)。
- **Spec**: `.claude/docs/spec/collaboration-history.md` には accessibility 専用セクションが無いため R1c でも更新しない (R1a/R1b も同様に spec を触っていない; ADR-025 が R1 全体の単一 source of truth)。

## Test delta

Final: **+12 `commonTest`** in `ChartAccessibilityTest.kt` (R1c block at file end)。累計 35 (R1a 21 + R1b 14) → **+12 = 47 ChartAccessibilityTest green** ✓ (`./gradlew :shared:testAndroidHostTest --tests ChartAccessibilityTest`)。XCUITest 新規追加なし; e2e Maestro 追加なし。

新規テストケース:
1. `rowDiffDescriptors maps CellChange Added Removed Modified by chartY`
2. `rowDiffDescriptors uses the AFTER symbol for Modified cells`
3. `rowDiffDescriptors expands LayerChange Added into per-row ADDED entries`
4. `rowDiffDescriptors expands LayerChange Removed into per-row REMOVED entries`
5. `rowDiffDescriptors ignores LayerChange PropertyChanged at the cell level`
6. `rowDiffDescriptors sorts changes within a row by colNumber ascending`
7. `rowDiffDescriptors omits rows with zero changes from the output`
8. `rowDiffDescriptors uses 1-based row and col numbers with minX minY offsets`
9. `rowDiffDescriptors drops changes whose y or x is outside target extents`
10. `rowDiffDescriptors returns empty for degenerate target extents`
11. `spokenDiffLabel composes position and change list with separators`
12. `spokenDiffLabel uses blankCellsName for null symbol and honors resolver fallback`

## Result summary

**配送内容** — ADR-025 R1c (audit §3.1 B4 closer) を出荷:

**共有 a11y モデル拡張** (`ChartAccessibility.kt`、+199 行 with KDoc):
- `enum class DiffChangeKind { ADDED, REMOVED, MODIFIED }`
- `data class RowDiffChange(colNumber, kind, symbolId)` — 1-based col、Modified は AFTER シンボル
- `data class RowDiffDescriptor(rowNumber, rowCount, chartY, changes)` — 非空 changes (空行は出力しない)
- `data class DiffA11yStrings` — 7 ローカライズ済みテンプレート (3 件は R1a `a11y_chart_*` 再利用、4 件は R1c 新規)
- `fun rowDiffDescriptors(targetExtents, cellChanges, layerChanges): List<RowDiffDescriptor>` — `CellChange.Added`/`Removed`/`Modified` + `LayerChange.Added`/`Removed` のセルを `chartY` ごとの change list にまとめる; `LayerChange.PropertyChanged` は cell 単位では無視 (`ChartComparisonScreen.kt:455` 既存ルール `LayerChangesBanner` と整合); `colNumber` 昇順ソート; target extents 外のセルは silent drop
- `fun spokenDiffLabel(descriptor, strings, symbolName): String` — Compose + SwiftUI からの byte-identical 整形 (`%1$d`/`%2$s` manual replace、R1a/R1b と同一)

**Compose 統合** (`ChartComparisonScreen.kt`、+176 行):
- private composable `RectComparisonAccessibilityOverlay` を追加
- `DualCanvasPanel` の target ペイン Box を `BoxWithConstraints` にラップし、`isTraversalGroup = true` でトラバーサルグループ宣言
- per-row invisible `Box` を forward `computeDiffLayout` 計算でベーススケールに配置 (graphicsLayer の scale/translate transform は通さない — ADR-025 §d / Invariant 8 / R1a-R1b 同一パターン)
- `semantics { contentDescription = spoken; traversalIndex = rowNumber.toFloat() }` で行 1 先頭 SR トラバーサル
- private helper `rememberDiffA11yStrings(isJa)` — R1a の `a11y_chart_*` 3 件を再利用しつつ、R1c 新規 4 件は en/ja in-source bilingual fallback (R1b と同一パターン)

**SwiftUI 統合**:
- 新規 `iosApp/iosApp/Screens/ChartComparisonAccessibility.swift` (+167 行)、R1b の `ChartEditorAccessibility.swift` を範に取る
- `struct ChartComparisonAccessibilityOverlay` — `Color.clear` 不可視行 + `.accessibilityElement()` + `.accessibilityLabel(spoken)` + `.accessibilitySortPriority(Double(gridHeight - rowNumber))` で行 1 先頭 VoiceOver 順
- `ChartComparisonScreen.swift` `DualCanvasPanel` の target ペインを `GeometryReader { geometry in ZStack { DiffCanvas(...).accessibilityHidden(true); ChartComparisonAccessibilityOverlay(...) } }` でラップ
- `.accessibilityHidden(true)` は **inner DiffCanvas のみ**に適用し `targetChartCanvas` accessibilityIdentifier を持つ GeometryReader ラッパーには適用しない (§(d) Implementation refinement — Maestro ランドマーク `targetChartCanvas` を保持)

**i18n フラグメント** (`docs/en/phase/tasks/R1c.i18n.tsv`、4 件) — オーケストレータが consolidation で 3 共有 i18n ファイルへ splice:
- `a11y_diff_change_added`: `col %1$d added %2$s` / `%1$d列目に%2$sを追加`
- `a11y_diff_change_removed`: `col %1$d removed %2$s` / `%1$d列目の%2$sを削除`
- `a11y_diff_change_modified`: `col %1$d modified to %2$s` / `%1$d列目を%2$sに変更`
- `a11y_diff_change_separator`: `, ` / `、`

**主要な実装判断**:
- (i) overlay は TARGET ペインのみに整合 (`diff.base` は初回コミットで `null`; `target` は常に非 null かつ「現在の状態」を表す)
- (ii) `LayerChange.PropertyChanged` はセル単位で列挙しない (既存 `LayerChangesBanner` が surface; レンダラ `classifyCells` と整合)
- (iii) target extents 外のセル (rare な縮小ターゲットケース) は読み上げから silent drop — base ペインに視覚的に残る、統合行インデックスで追跡すると SR ユーザーに範囲外の幻の行番号を押し付けることになる (監査再訪時の Follow-up として記録)
- (iv) Modified change は AFTER symbol を採用 (`ChartComparison.kt:18-19` "knitters care about what's at this position now")
- (v) 変更が 1 件以上ある行のみ descriptor 出力 (R1a は全行を出すが、Comparison は ADR-025 §c "K changes" に整合 + SR 利用者が無変更行を 65k 回スワイプしない UX)

**検証 (`make ci-local`)** — 全 9 ステップ green を確認:
1. ✓ `:shared:ktlintCheck` + `:androidApp:ktlintCheck` (ktlintFormat 1 回適用後、auto-fixed)
2. ✓ `:shared:compileTestKotlinIosSimulatorArm64` (Kotlin/Native コンパイル — `toSortedMap()` JVM-only 問題を `keys.sorted()` で回避)
3. ✓ `:shared:testAndroidHostTest` — **47 ChartAccessibilityTest GREEN** (12 新規 + 35 既存)
4. ✓ `:shared:koverVerify` (カバレッジ閾値クリア)
5. ✓ `verifyI18nKeys` (3 共有 i18n ファイルに触れない → parity 維持)
6. ✓ `make ios-build` (Xcode 26 / iOS 26 SDK / iPhone 17 sim、BUILD SUCCEEDED)
7. ✓ `make ios-test` — **19/19 tests, 0 failures** (`IOS_SIM_DEST='platform=iOS Simulator,name=iPhone 17'` override で実行)
8. ✓ `make e2e-android` — **9/9 Maestro flows passed** (P0/P1/P2 全件、P1 Chart Editor 含む)
9. ✓ `make e2e-ios` — **5/5 Maestro flows passed**

**Pushed**: TBD (commit + push 後に SHA を記入)

**Audit B4 → CLOSED (R1c)**。Differentiate-Without-Color (カラー以外で区別) は本サブスライスで申告可能化; VoiceOver は引き続き R2 (icon labels) + R3 (headings) も必要 (audit §5 progression table — R1 は VoiceOver に必要だが十分でない)。

## Follow-ups

新規 Tech Debt / roadmap 項目 (オーケストレータが consolidation で `tech-debt.md` / `roadmap.md` へ反映):

- **[Tech Debt — LOW]** R1c の `rowDiffDescriptors` は target extents 外のセル変更 (縮小ターゲット rare ケース) を silent drop している。具体的には: base が y=0..3 で target が y=0..1 に縮小された場合、y=2/y=3 の Removed セルは BASE ペインに視覚的に残るが SR 読み上げには含まれない。現状の影響は極めて軽微 (chart 縮小はまれな操作 + base ペインに視覚提示は残る) だが、将来 a11y audit が再訪する場合は union-extents モデル (`min(base.minY, target.minY) .. max(base.maxY, target.maxY)`) への移行を検討する余地あり。
- **[Tech Debt — LOW]** R1c の ios-test 実行中に複数の transient flake が観測された (`NavigationFlowUITests.testDeepLink_validToken_localOnlyMode_staysOnProjectList`、`testNavigateToProfile_andBack`、`testNavigateToActivityFeed_andBack`、`OnboardingUITests.testTappingNextAdvancesThroughAllPagesAndCompletes`、`ProfileUITests.testDefaultState_displaysProfileScreen`、`IconButtonAccessibilityTests.testProjectListOverflowButton_hasAccessibilityLabel`、`ProjectDetailUITests.testDisplaysProjectDetails`/`testIncrementRow_updatesCount`、`ProjectListUITests.testCreateProject_appearsInList`/`testPlusButton_opensCreateSheet`)。**異なる回で異なるテストがランダム失敗** + 単独再実行で全て green + 最終フル ios-test ランは 19/19 GREEN — Chart 関連は 1 件も無く、R1c 起因ではない (revert/stash で確認済)。CLAUDE.md の "Transient macOS-runner flakes" 既知カテゴリ (currently lists `testDeepLink_invalidURL`) と完全に同一パターン。`ci-known-limitations.md` の transient flake entry にこれらのテスト名を追記する余地あり (現エントリは 1 テストのみ列挙)。

## Task Result (orchestrator-consumed handoff block)

(末尾の最終 message として emit — push 完了後に SHA を埋める)
