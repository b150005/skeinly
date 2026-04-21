# Phase 30.4 — Knitter 助言レビュー：機会的グリフバンドル

> **ソース（英語版）**: [`docs/en/symbol-review/phase-30.4.md`](../../en/symbol-review/phase-30.4.md) — 原典はこちら。翻訳との差分がある場合は英語版を優先。

**日付:** 2026-04-21
**レビュアー:** Knitter エージェント（[`.claude/agents/knitter.md`](../../../.claude/agents/knitter.md) 参照）
**典拠:** [ADR-009 §8 — `picot-N` の取り扱い](../adr/009-parametric-symbols.md) — ピコットは離散ファミリとして出荷、パラメトリックにはしない。
**レビュー対象:** [`shared/src/commonMain/kotlin/io/github/b150005/knitnote/domain/symbol/catalog/CrochetSymbols.kt`](../../../shared/src/commonMain/kotlin/io/github/b150005/knitnote/domain/symbol/catalog/CrochetSymbols.kt) — 現行 28 グリフ、Phase 30.4 で 7 追加。
**参考資料:** JIS L 0201-1995 表 2（かぎ針編目）、CYC クロシェ表、日本ヴォーグ社／文化出版局／オンドリ社 国内出版社規約。

本文書は**実装前助言**。Phase 30.3 以降 [`phase-30.3.md §4`](./phase-30.3.md) で繰り延べた 7 グリフに対して正規ジオメトリを起案し、ADR-009 §8 に照らして picot-N ファミリ契約を確定させ、30.3 に持ち越された 1 件のジオメトリ微調整（`hdc-cluster-3` ステム間隔）も同時に反映する。

スコープは意図的に絞った：どのグリフも単独では Phase 32 エディタの利用性を阻害しない**機会的追加**だが、いま出荷することでアラン風クロシェ（交差長編み）、ショール／ストール（ラブノット）、ドイリー（バリオン）、中長編み玉編みの完全対称、および 3 目超のエッジング（picot-4/5/6）が使えるようになる。

## 1. 判定サマリ

| id | 判定 | 備考 |
|---|---|---|
| `jis.crochet.hdc-cluster-5` | **ship** | `dc-cluster-5` のミラー、斜線なし（hdc シグナルは `hdc-cluster-3` と同じ）。`widthUnits=2`。 |
| `jis.crochet.solomon-knot` | **ship** | 細長い開いたループ。バンドル内で唯一 JIS Table 2 に項目なし、出版社規約に依拠。 |
| `jis.crochet.dc-crossed-2` | **ship** | 2 本の長編みステムが X 字交差、各ステムに独自の上端バー＋斜線。`widthUnits=2`。 |
| `jis.crochet.bullion` | **ship** | スプリングコイル風の 2 コイル（実装前草案の 3 コイルから Knitter m1 で縮小、24dp 視認性を確保）。 |
| `jis.crochet.picot-4` | **ship** | `picot-3` よりやや大きめのループ。`widthUnits=1`。 |
| `jis.crochet.picot-5` | **ship** | ほぼセル全体のループ。`widthUnits=1`。 |
| `jis.crochet.picot-6` | **ship** | アーチ状ループ。ADR-009 §8 に基づき `widthUnits=2`（1 セルに収まらない）。 |
| `jis.crochet.hdc-cluster-3`（調整） | **polish** | 外ステムを `0.25/0.75` → `0.22/0.78`。[`phase-30.3.md §5`](./phase-30.3.md) で繰り延べた微修正。 |

ブロッカーなし。このフェーズで catalog は 35 グリフ（28＋7）。

## 2. グリフごとの正規レンダリング

（詳細なジオメトリ、理由付け、参考資料、ラベルは英語版 §2 を参照。以下は要点のみ。）

### 2.1 `jis.crochet.hdc-cluster-5`
- `dc-cluster-5` と同形（5 本のステム＋クローズド楕円のキャップ）から斜線を除去。`widthUnits = 2`。
- ラベル: JA「5目の中長編み玉編み」／ EN「5-hdc cluster」／ `cycName = "hdc-cl5"`／ aliases `["bob-hdc-5"]`。

### 2.2 `jis.crochet.solomon-knot`
- JIS は**未掲載**。ヴォーグ 毛糸だま／文化／Interweave いずれも**縦長の開いたループ**（閉じた枕形ではない、24dp で横向き `ch` に誤読される — Knitter M1）。
- ジオメトリ：2 本の縦弧カーブと上下の短い水平キャップ線。
- ラベル: JA「ラブノット」／ EN「Solomon's knot」／ `jisReference = null`／ aliases `["love knot", "true lover's knot"]`。

### 2.3 `jis.crochet.dc-crossed-2`
- 2 本の長編みステムが中央で X 字交差、各ステムに上端バー＋斜線。`widthUnits = 2`。
- **重要**（Knitter m2）：左ステムは `(0.1, 0.9) → (0.7, 0.1)`、右ステムは `(0.9, 0.9) → (0.3, 0.1)`。上端バーは**交差後の終端**（左ステム終端 = 右上、右ステム終端 = 左上）を覆う。未来の読み手が「元の列に戻そう」と誤修正しないよう、コードコメントで明示。
- ラベル: JA「2目交差の長編み」／ EN「Crossed 2-dc」／ `cycName = "cross-dc"`。

### 2.4 `jis.crochet.bullion`
- JIS 未掲載。JP 出版社（毛糸だま、文化）は**縦ステム＋コイル風の重ね描き**。24dp で潰れないよう 2 コイル採用（Knitter m1：3 コイルは Phase 30.2 §3.5 密度閾値を割る）。
- ジオメトリ：縦ステム＋交互の半円ループ 2 本。
- ラベル: JA「バリオン編み」／ EN「Bullion stitch」／ `jisReference = null`／ aliases `["roll stitch"]`。

### 2.5 `jis.crochet.picot-{4,5,6}` — 離散ファミリ
ADR-009 §8 に基づき**パラメトリックではない**：ジオメトリは N で変化する（3 目ピコットと 6 目ピコットは商用パターンでサイズが異なって読まれる）ので、各 N は第一級カタログエントリ。

- **`picot-4`**（w=1）：loop `x∈[0.25, 0.75]`、top y=0.2（`picot-3` よりやや大きい）。
- **`picot-5`**（w=1）：loop `x∈[0.2, 0.8]`、top y=0.1（ほぼセル全体）。
- **`picot-6`**（w=2）：loop `x∈[0.05, 0.95]`（Knitter m3：後段 stretch で真に 2 セル幅に）、stem 短縮。

全メンバー `parameterSlots = emptyList()`（ADR-009 §8）。`picot-3`（既存）は `aliases` に `"picot"` を追加し、辞書検索で裸語にもヒット（ADR-009 §4）。

### 2.6 `jis.crochet.hdc-cluster-3` — ステム間隔調整
外ステム `0.25/0.75` → `0.22/0.78`。楕円キャップ未変更（0.15→0.85 のまま、広がったステムも包含）。理由：斜線がないため低 DPI Android 24dp で 3 本の並行ステムが 1 本の太線に見える。6% 広げれば識別可、キャップ越境なし。

## 3. このバンドル後のカバレッジ

7 追加で `jis.crochet.*` のカバレッジ：
- 国内商業クロシェパターンの約 95%（30.3 後の約 90% → ）
- アラン風クロシェ（交差長編み） — 未対応だった
- 開き編みショール（ラブノット） — 未対応だった
- ボブルレース／ドイリー（バリオン＋hdc-cluster-5） — 部分対応のみだった
- エッジングの 6 目まで（`picot-3` のみ→最長 6 目まで）

**今後の機会的フェーズ向け未対応（ブロッカーなし）**：
- Foundation スティッチ（`fsc`, `fdc`）— 米国優勢の規約
- Extended sc（`exsc`）— CYC 標準、JIS 未掲載
- スパイクステッチ — カラーワーククロシェ
- turning-ch-N ファミリ — ADR-009 §9 が別判断（パラメトリック vs. 離散ファミリ）としてフラグ済み

## 4. ユーザ向けオープン質問（ブロッカーなし）

- [ ] **`solomon-knot` 補助マーカー**：開いたループ（今回出荷、Knitter M1 に基づく）vs. 小さな底部ドット（文化 2020+ バリアント）。実パターン報告に基づいて後から差し替え可能。
- [ ] **`dc-crossed-2` 上端バー位置の明示**：実装者はコードコメントで各ステム終端を明記する必要あり（Knitter m2）— 将来の読み手が交差前の列位置に「修正」しないよう。

## 5. Knitter レビュー反映（実装前）

実装前草案は Knitter エージェントによりレビューされ、コード着手前に以下の修正を反映：

- **M1（major）→ 修正済**：`solomon-knot` を閉じた枕形（回転した `ch` に誤読）から上下キャップつき 2 本縦弧カーブへ変更 — ヴォーグ／文化／Interweave の開いたループ形式に準拠。
- **m1（minor）→ 修正済**：`bullion` を 3 半円→2 半円に削減。y=0.2–0.85 帯に 3 コイル押し込むと Phase 30.2 §3.5 密度閾値を割る。
- **m2（minor）→ コードコメントで反映**：`dc-crossed-2` の上端バー座標は正しい（交差ステム終端を覆う）が、実装者はコメントでステム終端を明示、未来の読み手が交差前の列位置に「修正」しないよう。
- **m3（minor）→ 修正済**：`picot-6` loop を `x∈[0.1, 0.9]` → `x∈[0.05, 0.95]` に拡大し、widthUnits=2 stretch 後に真の約 2 セル幅ループに。`picot-5` との視覚差を確保。

`OK` 判定は不変：`hdc-cluster-5`、`hdc-cluster-3` 調整、`picot-4`、`picot-5`、バイリンガルラベル、ADR-009 §8 準拠。

## 6. チーム合意

キックオフ前のエージェントチーム合意：

- **Knitter（ドメイン）**: 7 グリフ全てを同フェーズで推奨、`picot-6 widthUnits=2` を ADR-009 §8 に照らして明示フラグ。
- **Architect**: ADR-009 §8 の離散ファミリ判定に従えば catalog が機械的に決まる（各 picot-N は独立エントリ、parameter-slot 曲芸不要）。
- **Implementer**: CLAUDE.md Tech Debt Backlog 規約に従い、30.3 で繰り延べた `hdc-cluster-3` ステム間隔調整を同 PR で明示的に commit メッセージに記載。
- **PM**: このフェーズではエディタパレット以外のユーザ可視面なし — カタログに乗れば自動的にパレットが拾う。

CLAUDE.md step 10 に従い記録。
