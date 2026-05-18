# ADR-025: 編み図キャンバスのアクセシビリティ — 行単位のセマンティック表現

> 英語版が正本: [docs/en/adr/025-chart-canvas-accessibility.md](../../en/adr/025-chart-canvas-accessibility.md)

## ステータス

承認済み（2026-05-17）。[a11y ラベルカバレッジ監査](../../../audits/a11y-label-coverage-2026-05-17.md)（§4）の **R1** 改善スライスをゲートする。R1 はアクセシビリティ修正の中で最大レバレッジ — **2 つ**の App Store Connect 申告（**VoiceOver** + **カラー以外で区別**）を、**3 画面**（編み図ビューア / 編み図エディタ / 編み図比較）× **両プラットフォーム**で同時にアンブロックする。本 ADR は*セマンティックモデル*を確定する。実装はサブスライス R1a/R1b/R1c として着地（§サブスライス計画）。極座標グリッドのアクセシビリティは M5 と同じ境界（rect 優先）で Phase 35.2+ にゲートするため、本 ADR の規範スコープは rect グリッドだが、モデルは再設計なしで極座標へ拡張可能に定義する。

本 ADR は監査の他の改善行（R2 アイコンラベル + ChartImage i18n 一掃、R3 見出しセマンティクス、R4 ProjectDetail Dynamic Type、R5 色非依存ポリッシュ）は扱わない — それらは機械的で意思決定記録不要。コントラスト比測定（監査前提 (d)、未着手）も対象外。

## 背景

2026-05-17 のアクセシビリティ・ラベルカバレッジ監査は、Skeinly の非編み図サーフェスは概ねアクセシブル（良好な `LiveSnackbarHost` / `AccessibilityAnnouncements` 基盤、フィルタチップは非色の選択キュー、ステータスバッジはテキスト描画）だが、**編み図サブシステム — アプリの存在理由そのもの — が両プラットフォームで VoiceOver/TalkBack に完全に不透明**であることを発見した：

- **B1 — 編み図ビューア**（`shared/.../ui/chart/ChartViewerScreen.kt:495-612` / `iosApp/.../Screens/ChartViewerScreen.swift:319-420`）: 編み図全体が 1 つのタップ/変形可能 `Canvas` で `Modifier.semantics` / `.accessibilityElement` が**皆無**。スクリーンリーダー利用者には、コアの閲覧/進捗追跡タスクがラベルも操作性もない矩形に見える。
- **B2 — ビューア進捗が色のみ**（`ChartViewerScreen.kt:457-465` / `.swift:644-645`）: 完了 = 20% 塗り、進行中 = 2dp/アクセント線。テキスト/`accessibilityValue` 等価物なし。
- **B3 — 編み図エディタ**の作図 `Canvas`（`ChartEditorScreen.kt` `RectEditorCanvas` / `.swift:476-579` `EditorCanvasView`）: タップで配置/消去するが a11y ゼロ — スクリーンリーダーで作図不可能。（タップ標的サイズは M5 で解決済み。これは直交する*知覚/操作*のギャップ。）
- **B4 — 編み図比較**の差分 `Canvas`（`ChartComparisonScreen.kt:502-557` / `.swift:321-468`）: セル単位の差分の正体が 100% 信号機色塗り（追加=緑/削除=赤/変更=黄）でテキスト/形状なし。集約 `DiffSummaryRow` は部分的緩和だがセル単位の変更は SR + 色覚多様性に不可視。

監査の申告判定: VoiceOver と カラー以外で区別 はこれらが直るまで**申告不可**。両者は両プラットフォームで**単一の根本原因**（編み図 `Canvas` にセマンティック表現が皆無）を共有する。監査は*どうやるか*を ADR に明示的に委ねた — 中心的問い「**2 次元・最大 256×256 のグリッドを、線形なスクリーンリーダーに対し、知覚可能かつ操作可能になるどの粒度で公開するか?**」が機械的修正ではなく本物の設計判断だから。

既存アーキテクチャから持ち込まれる制約：

- **M5 / ADR-007 系譜**（chart-editor spec Invariant 8）: rect キャンバスは 2 軸スクロールコンテナ内の**単一コンテンツ座標空間**にレイアウトされ、描画と `GridHitTest.hitTest` がその空間を**逆変換なし**で共有する。アクセシビリティ層はこの不変条件を尊重しなければならない — 変換や第 2 の座標空間を再導入できない。
- **共有ジオメトリ先例**: `GridHitTest` / `PolarCellLayout` / `WcagTargetSize` は両プラットフォームが呼ぶ純粋共有（`domain/chart/`）の単一真実源。監査 §2 のパリティ表は全編み図ギャップが対称であることを示す — プラットフォーム別の手書き a11y モデルはドリフトし申告ギャップを再オープンする。
- **グリッド規模**: rect `MAX_GRID_DIMENSION = 256`（`ResizeChartDialog`）。最大 256×256 = 65,536 のセル単位セマンティックノード数は使えるスクリーンリーダーサーフェスにならない。
- **ドメイン真実**（knitter アドバイザ）: 編み図は**段ごと**（往復編み）/ **段（周）ごと**（輪編み）に編まれ追跡される。進捗は段/周単位。ビューアは既に段単位の*段完了マーク*を公開している。段内では編み手は**記号のラン**（「表 8、裏 2、×4」）を読み、個々のセルを読まない。

## エージェントチーム検討

### (a) セマンティック粒度 — 行単位を主単位とする（決定）

検討した選択肢：

- **セル単位 `accessibilityElement`** — 最大精度。各セルがフォーカス可能ノード「R 段、C 列、<記号>、<状態>」。**却下**: 最大サイズグリッドで 65,536 スワイプ停止は明白に使用不能。クラフトの編み方とも不一致。
- **グリッド全体サマリーのみ** — キャンバスが「16×16 編み図、40% 完了」とアナウンス。**却下**: 知覚可能だが**操作不可** — エディタは標的に記号を配置できず、ビューアは*特定*段をマークできず、比較は変更箇所を特定できない。
- **行単位要素 + 精度が必要な箇所のみ行内セルカーソル**（採用） — 各グリッド行（極座標: 各リング）が 1 つのアクセシビリティ要素で、その読み上げテキストが 位置 + 記号ランサマリー + 進捗/差分状態 を運ぶ。**調整可能セルカーソル**は**エディタのみ**（セル精度の配置が本質的な箇所）に追加。≤256 停止、視覚/クラフトのスキャン単位に一致、操作可能。

**決定 (a)**: **グリッド行**（rect）/ **リング**（極座標）を主アクセシビリティ単位とする。セルレベルアクセスはエディタサーフェス上の*行内調整可能カーソル*としてのみ存在し、6.5 万の独立ノードにはしない。

### (b) 共有モデル — 新規 `domain/chart/ChartAccessibility.kt`（決定）

プラットフォーム別の手書き文字列ビルダーはドリフトする（監査 §2: 全編み図ギャップは対称、乖離は申告を再オープン）。`GridHitTest` 先例に倣い、行記述子ロジックは**純粋共有 Kotlin**。

**決定 (b)**: `shared/src/commonMain/.../domain/chart/ChartAccessibility.kt` を追加 — `ChartExtents` + `List<ChartLayer>`（+ ビューア用の任意のセル単位進捗状態、+ 比較用の任意の `ChartComparison` 差分）から順序付き `List<RowAccessibilityDescriptor>` を生成する純粋関数。各記述子は: 1 始まりの行/リングインデックスと総数、ラン長**記号ランサマリー**（既存カタログ経由のローカライズ済み記号名 — キー化、英語リテラル*ではない*）、**状態**フィールド（進捗または差分、enum + カウントで表現、色トークンでは*ない*）を運ぶ。両プラットフォームが同一記述子を消費。記号名ローカライズはカタログを再利用。R2（パレット i18n）が未ローカライズの記号は記述子が記号 id にフォールバック（機能的、ブロックしない — (f) 依存参照）。

### (c) サーフェス別セマンティクス（決定）

| サーフェス | 行要素ラベル（読み上げ） | アクション / 調整可能性 | 修正 |
|---|---|---|---|
| **ビューア** | 「R 段／全 N 段 — <記号ランサマリー> — <完了 / 進行中 M 中 X / 未着手>」 | `accessibilityAction` **段を完了にする**（既存の段単位操作にマップ）; キャンバス全体サマリーをコンテナラベルに（「16×16、40% 完了」） | B1, **B2**（進捗が色でなく読み上げテキストに） |
| **エディタ** | 「R 段／全 N 段 — <記号ランサマリー>」 | **調整可能**セルカーソル: 増減でフォーカス段内の列を移動し「C 列 — <記号 or 空>」をアナウンス; `accessibilityAction` **<選択中パレット記号>を配置** / **消去** | B3（セル単位ノードなしで作図操作可能） |
| **比較** | 「R 段／全 N 段 — <K 件の変更: C 列に<記号>追加、C2 列の<記号>削除、…>」 | なし（読み取り専用）; 集約 `DiffSummaryRow` は既出（維持） | B4（セル単位差分が信号機色でなく読み上げテキストに） |

**決定 (c)**: 全状態（進捗、差分、記号同一性）を行要素内の**読み上げテキスト**で伝える。これが VoiceOver *と* カラー以外で区別 を同時に満たすもの — ブロックされた 2 申告はこの単一機構を共有する。

### (d) 描画統合 — M5 座標空間上の不可視行単位セマンティックオーバーレイ（決定）

視覚 `Canvas` は不変（高性能レンダラ）。a11y は **行単位リージョンの不可視オーバーレイ**として重ね、M5 が確立した**同一**の `centeredLayout` 原点 + `effectiveCell` 計算（rect）/ `PolarCellLayout`（極座標）で配置する。M5 のスクロール修飾子は*レイアウト*修飾子なので、オーバーレイはキャンバスと自動的にスクロールし行リージョンは描画グリッドに 1:1 整合 — **逆変換なし、第 2 座標空間なし**（M5 / chart-editor Invariant 8 保持）。

- **Compose**: 同一スクロールコンテンツノード内で `Canvas` の上に行単位 `Box` の `Column`/`Box`、各 `Modifier.semantics { … }`（+ エディタカーソル用 `progressBarRangeInfo` / カスタムアクション）; 視覚 `Canvas` には `Modifier.clearAndSetSemantics {}` で二重アナウンス防止。
- **SwiftUI**: キャンバスコンテナに `.accessibilityElement(children: .contain)` + 不可視 `ForEach(rows)` の `.accessibilityElement()` リージョンを重ねる（+ エディタカーソル用 `.accessibilityAdjustableAction`）; `Canvas` 自体は `.accessibilityHidden(true)`。

**決定 (d)**: 既存 M5 コンテンツ座標空間に整合した不可視セマンティックオーバーレイ。視覚キャンバスは hidden/`clearAndSetSemantics` とし、セマンティクスはオーバーレイのみから出す。

**実装時の精緻化（R1a, 2026-05-18 — 機構のみ、決定は不変）**: R1a で、チャート `Canvas` が `testTag("segmentOverlay")`（Compose）/ `.accessibilityIdentifier("segmentOverlay")`（iOS）という、`e2e/flows/{android,ios}/P1_per_segment_progress.yaml` がアサートする load-bearing ランドマークを持つことが判明。Compose Canvas へ一律 `clearAndSetSemantics {}` を適用すると testTag が剥がれ Maestro フローが壊れる。決定（セマンティクスは行単位オーバーレイのみ／キャンバスはアナウンス要素を出さない）は維持しつつ、機構を次のとおりとする:

- **Compose**: Canvas は testTag を保持し `clearAndSetSemantics` しない。`contentDescription`／`role`／`onClick` を持たない testTag のみのノード（Canvas は生の `detectTapGestures` ポインタ入力＝クリック*セマンティクス*ではない）は TalkBack フォーカス対象外であり、構造上すでに二重アナウンスされない。ビューアの座標空間は `computeViewerLayout`（ビューアは M5 のエディタ `centeredLayout`/`effectiveCell` に移行していない＝M5 はエディタ専用）。オーバーレイはその前方レイアウト計算でベーススケールに配置し、ビューアの `graphicsLayer`/`transformable` レンダー変換の内側には置かない（Invariant 8 の「逆変換なし・単一座標空間」の精神に整合。スクリーンリーダー利用者はピンチしないためベースレイアウトが SR 関連空間）。
- **iOS**: `.accessibilityHidden(true)` は**内側 Canvas のみ**に適用し、`segmentOverlay` accessibilityIdentifier を持つ `ChartCanvasView` 合成体には適用しない。これにより Maestro ランドマークを保持しつつ描画ラスタは VoiceOver 要素を出さない。

`requires-supabase` タグにより `P1_per_segment_progress` は CI と `make e2e-android`/`-ios` から除外される。ランドマーク保持は CI ゲート対象ではなく、文書化フローの正当性義務である。

### (e) 極座標 — 同一モデル、リングインデックス; Phase 35.2+ ゲート（決定）

極座標の作図/閲覧自体が Phase 35.2+（M5 も極座標ズームを同様にゲート）。`ChartAccessibility` は `ChartExtents.Polar`（リング = 行、`stitchesPerRing[r]` = 行長、`PolarCellLayout` でジオメトリ）を受けるよう定義し極座標オープン時に再設計不要だが、**R1 の規範的出荷スコープは rect**。極座標オーバーレイ経路は `extents is Polar` でゲートし未実装のまま残す（M5 の極座標延期と並行）— 極座標 UX に先行して未テストの極座標 a11y サーフェスを出荷しない。

### (f) シーケンス + R2 クロス依存（決定）

ユーザー価値順: **R1a ビューア（知覚 + 追跡）→ R1b エディタ（作図）→ R1c 比較（差分）**。エディタの*選択中パレット記号*アナウンスは **R2**（パレットアイコンラベル + i18n 一掃）が導入するローカライズ済み記号名を理想的には使うが、R1b は **R2 をブロックしない** — R2 がカタログをローカライズするまで記号 id / `enLabel` にフォールバックし、その後自動的に改善する。依存を記録、直列化しない。

### (g) テスト戦略（決定）

`ChartAccessibility` は純粋 → 網羅的 `commonTest`（行インデックス、ラン長サマリー境界、進捗/差分状態マッピング、極座標リングインデックス、空/退化 extents、1 段目/N 段目のオフバイワン）。オーバーレイ整合 + アクション配線は既存 `P1_chart_editor.yaml`（グリーン維持必須 — オーバーレイは加算的・非視覚）+ ハーネスが a11y ツリークエリをサポートする箇所での新規プラットフォーム別 UI アサーションで検証。パリティは共有モデルで担保 — 同一記述子 ⇒ 構造的に同一読み上げテキスト。

## サブスライス計画

### R1a — 共有モデル + 編み図ビューア（rect）

- 新規 `shared/.../domain/chart/ChartAccessibility.kt`: `rowDescriptors(extents: ChartExtents.Rect, layers, progress?) : List<RowAccessibilityDescriptor>`; `RowAccessibilityDescriptor`（index, total, symbolRunSummaryKeys, state）。純粋; Compose/SwiftUI import なし。
- 網羅的 `ChartAccessibilityTest`（commonTest）。
- Compose `ChartViewerScreen` + SwiftUI `ChartViewerScreen`: M5 座標空間上の不可視行単位オーバーレイ; キャンバス `clearAndSetSemantics` / `.accessibilityHidden(true)`; 行 `accessibilityAction` → 既存の段完了マーク。
- i18n: 行ラベル書式 + 状態文字列（`a11y_chart_row_*`）× en/ja CMP + iOS xcstrings; `verifyI18nKeys` パリティ。（記号ラン名は既存カタログキー再利用; 行/状態フレーミングの新キーのみ。）
- 監査 B1/B2 → 監査内で **CLOSED (R1a)** マーク; 監査 §5 + CLAUDE.md Phase-40 ASC 前提 (c) 進行を更新。

### R1b — 編み図エディタ（rect）

- エディタオーバーレイ + 行内調整可能セルカーソル; `accessibilityAction` カーソル位置に配置/消去（既存 `ChartEditorEvent.PlaceCell` をルート）。
- カーソルアナウンスは R2 まで記号 id フォールバック; カーソル状態機械のテスト（列 0 / 列 N-1 でクランプ、レイヤ可視性相互作用、VM に既存のパラメトリック記号 pending-input ブロック）。
- 監査 B3 → CLOSED (R1b)。

### R1c — 編み図比較（rect）

- 比較オーバーレイ: 共有差分から行単位変更リスト読み上げテキスト; 集約 `DiffSummaryRow` 維持。
- 監査 B4 → CLOSED (R1c); 監査 §5 → R2（アイコンラベル）+ R3（見出し）も着地後に VoiceOver + カラー以外で区別 が申告可能へ（監査 §5 進行表参照 — R1 単独は VoiceOver に必要だが十分でない; カラー以外で区別 には**十分**）。

## 検討した代替案

- **セル単位セマンティックノード（却下）** — 最悪 65,536 ノード; 使用不能; クラフト非整合。§(a)。
- **グリッド全体サマリーのみ（却下）** — 知覚可能だが操作不可; エディタと「*この*段をマーク」ビューアタスクで失敗。§(a)。
- **プラットフォーム別手書き a11y 文字列（却下）** — 監査 §2 パリティ要件に対し確実にドリフト; `GridHitTest` 単一真実源先例に違反。§(b)。
- **キャンバスから分離した独立線形「セルインスペクタ」コントロール（却下）** — ユーザーが学ぶべき並行ナビゲーションモデルを追加; 行単位オーバーレイは既に見えている空間モデルと既存の段単位操作を再利用。§(a)/(d)。
- **キャンバス描画出力を変換して a11y ツリーを描画（却下）** — 座標変換を再導入; M5 / chart-editor Invariant 8（単一座標空間、逆変換なし）に違反。§(d)。
- **R1 で極座標 a11y 出荷（却下/延期）** — 極座標 UX 自体が Phase 35.2+; 極座標 UX に先行して未テストの極座標 a11y サーフェスを出荷するのは M5 が極座標ズームをゲートして避けたアンチパターンそのもの。§(e)。

## 影響

- **正**: 単一共有モデル + 加算的不可視オーバーレイが、視覚レンダラを変えず M5 座標空間不変条件にも違反せず、3 画面 × 2 プラットフォームで 2 つの ASC 申告をアンブロック。行単位はクラフト真実かつ有界（≤256 ノード）。進捗/差分が読み上げテキストになり、色のみブロッカーを構造的に解消。パリティは構築上保証（同一共有記述子）。
- **負 / コスト**: 両プラットフォームに触れる 3 サブスライス + 新共有モデル + i18n + 各々の `make ci-local` フルゲート（文書化済み Xcode-26 ホスト注意点: `IOS_SIM_DEST=iPhone 17`、ios-test teardown + e2e-ios は A20/A33 先例通り CI ゲート）。エディタ調整可能カーソルが最も複雑（状態機械 + アクションルーティング）で記号命名の R2 クロス依存を持つ。
- **フォローアップ**: VoiceOver は R1 **かつ** R2（アイコンラベル）**かつ** R3（見出し）の後に申告可能 — R1 は VoiceOver に必要だが十分でない（カラー以外で区別 には**十分**）。監査 §5 進行表が申告タイミングの真実源。

## 改訂履歴

- 2026-05-17 — 承認。a11y ラベルカバレッジ監査 R1 から起草。エージェントチーム検討（knitter / ui-ux-designer / architect / implementer）をインラインで記録。コードはまだなし; 本 ADR が R1a/R1b/R1c をゲートする。
- 2026-05-18 — **R1a 出荷**（ビューア＋共有モデル）。新規 pure `shared/.../domain/chart/ChartAccessibility.kt`（`rowDescriptors` ＋ `RowAccessibilityDescriptor`/`SymbolRun`/`RowProgress` ＋ `A11yStrings` ＋ `spokenLabel`）＋ 21 件の網羅 `commonTest`; Compose `RectRowAccessibilityOverlay` ＋ SwiftUI `RowAccessibilityCell` 不可視行単位オーバーレイ; 9 件の `a11y_chart_*` i18n キー（en/ja CMP ＋ xcstrings、`verifyI18nKeys` パリティ）。実装時の精緻化（決定は不変）: progress は `progressAt` ラムダで受け渡し（`Map<SegmentKey,…>` ではない＝Kotlin/Native の Swift-`Hashable` 落とし穴回避）; キャンバス抑制機構は §(d) 実装時の精緻化のとおり（`segmentOverlay` Maestro ランドマーク保持）。監査 B1/B2 → CLOSED (R1a)。B3（エディタ）→ R1b、B4（比較）→ R1c は未了。
- 2026-05-18 — **R1b 出荷**（編み図エディタ＋行内調整可能セルカーソル）。`ChartAccessibility` を拡張: `CellAccessibilityDescriptor`、`CellA11yStrings`、`cellDescriptor(...)`（カーソルを `[minX..maxX] × [minY..maxY]` にクランプ、最上位可視シンボルは `rowRuns`/`topmostLayerAt` と整合、`hiddenLayerIds` ＋ `visible` 述語が行オーバーレイと完全一致）、`spokenCellLabel(...)`、`placeOrEraseActionLabel(...)` ＋ 新規 14 件 `commonTest`（列 1 / 列 N / 段 1 / 段 N のクランプ、カーソル位置の最上位可視シンボル、不可視 + UI 非表示レイヤ除外、空白セル、退化 extents、リゾルバ有無の整形、アクションラベル両経路）— `ChartAccessibilityTest` 合計 **35 件 GREEN**。Compose `RectEditorAccessibilityOverlay`（行毎 `Box` に `progressBarRangeInfo` ＋ `setProgress`（カーソル調整）＋ `stateDescription`（セル読み上げ）＋ `customActions`（配置 / 消去）、カーソル状態は extents-keyed `mutableStateMapOf<Int,Int>`）＋ SwiftUI `ChartEditorAccessibilityOverlay`（`.accessibilityAdjustableAction` ＋ `.accessibilityValue` ＋ `.accessibilityAction(named:)`、`@State [Int32:Int32]` カーソルは `.id(rect)` でリセット）。視覚キャンバスは `testTag("editorCanvas")`（Compose）＋ `.accessibilityHidden(true)`（iOS — 内側 Canvas のみ、§(d) 実装時の精緻化に準拠）を維持; オーバーレイには pointer 修飾子なしのため、視覚ユーザーへのタップ＋スクロールはキャンバスに通過する。実装時の精緻化（決定は不変）: (i) 配置/消去アクションは **1 つ**で、ラベルが「&lt;シンボル&gt;を配置」/「消去」を切り替える — どちらも同じ `PlaceCell(x, y)` VM イベントに意図的にルートする（VM は既に `selectedSymbolId == null` を消去にマップしているため、読み上げアクションがタッチアフォーダンスを忠実に反映）; (ii) エディタにはプロジェクト/進捗コンテキストがない ⇒ R1b は `rowDescriptors(... progressAt = null)` を再利用し、行読み上げラベルから状態セクションが構造的に省略される（§c の「進捗なし」エディタ行と整合）; (iii) R1b の新 i18n キー 4 件（`a11y_editor_cell_with_symbol`、`a11y_editor_cell_blank`、`a11y_editor_action_place`、`a11y_editor_action_erase`）は `docs/en/phase/tasks/R1b.i18n.tsv` 経由でオーケストレータがコンソリデーション時に共有ファイルへスプライス（並行ワークツリー i18n-fragment プロトコル）; 両プラットフォームはデバイスロケールで切替えるソース内 en/ja バイリンガルフォールバックを使用 — R1a の `enLabel`/`jaLabel` シンボルフォールバックと同一パターン。監査 B3 → CLOSED (R1b)。B4（比較）→ R1c は未了。
