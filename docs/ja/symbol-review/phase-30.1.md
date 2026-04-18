# Phase 30.1 — Knitter Advisory: `jis.knit.*` カタログ視覚レビュー

**日付:** 2026-04-18
**レビュアー:** Knitter agent（[`.claude/agents/knitter.md`](../../../.claude/agents/knitter.md) 参照）
**レビュー対象:** [`shared/src/commonMain/kotlin/io/github/b150005/knitnote/domain/symbol/catalog/KnitSymbols.kt`](../../../shared/src/commonMain/kotlin/io/github/b150005/knitnote/domain/symbol/catalog/KnitSymbols.kt) — 35 glyph、単位正方形 `viewBox 0 0 1 1`（y-down）。
**ビューア:** Phase 31 Compose `ChartViewerScreen` + SwiftUI `StructuredChartViewerScreen` + Phase 30.1 `SymbolGalleryScreen`（記号辞書 UI）。

英語版（Source of truth）: [docs/en/symbol-review/phase-30.1.md](../../en/symbol-review/phase-30.1.md)

本ドキュメントは ADR-007 / ロードマップ Phase 30.1 で予約された **書面レビュー checkpoint** を記録するもの。JIS L 0201-1995 + CYC + 主要 JP 出版社慣行に対する craft-correctness 所見を保存し、人間ユーザと Knitter の間で (a) 既存カタログの geometry 修正、(b) 次に出す catalog category を合意する。

## 1. glyph 別評価

頻度: **H** 高（大半のパターン）、**M** 中、**L** 低、**N** 特殊用途。
相互参照安定性: **Y** = JIS + CYC + 主要 JP 出版社（Vogue / 文化）一致、**N** = 既知の食い違い、**U** = 不明 / 規格沈黙。
Path 評価は JIS の記述に対するもののみ（スタイル好みではない）。

| id | jaLabel | 頻度 | X-ref | Path | 備考 |
|---|---|---|---|---|---|
| `jis.knit.k` | 表目 | H | Y | 妥当 | 中央縦線。OK |
| `jis.knit.p` | 裏目 | H | Y | **要修正** | 裏目記号は **中央に短い横線**（0.3–0.7 程度）が JIS / 各社標準。現在の 0.1→0.9 は伏せ目バーに見える |
| `jis.knit.yo` | 掛け目 | H | Y | 妥当 | 半径 0.35 の円。JIS / CYC と一致 |
| `jis.knit.sl-k` | すべり目（表） | M | U | 妥当 | JIS は `V` 指定。現在はローカル合成。§5 参照 |
| `jis.knit.sl-p` | すべり目（裏） | M | U | 妥当 | `sl-k` と同じ議論 |
| `jis.knit.float-front` | 浮き目（糸を手前） | L | N | 要検討 | JIS は `V` + 矢印/フックで糸位置表現 |
| `jis.knit.twist-r` | ねじり目（右） | M | Y | 妥当 | 上部に小さい斜線。OK |
| `jis.knit.twist-l` | ねじり目（左） | M | Y | 妥当 | `twist-r` の鏡像。OK |
| `jis.knit.twist-p-r` | 裏ねじり目（右） | L | U | 要修正 | 裏目バー問題を継承 |
| `jis.knit.k2tog-r` | 右上2目一度 | H | **N** | **要修正** | 方向性記号は「縦棒 + 左傾斜 1 本」であるべき。現在は対称な逆 V（中央減目の形） |
| `jis.knit.k2tog-l` | 左上2目一度 | H | **N** | **要修正** | `k2tog-r` の鏡像問題 — 視覚的に区別不可 |
| `jis.knit.p2tog-r` | 右上2目一度（裏） | M | U | 要修正 | 同じ方向性問題 + 裏目バー問題 |
| `jis.knit.p2tog-l` | 左上2目一度（裏） | M | U | 要修正 | 同上 |
| `jis.knit.k3tog-c` | 中上3目一度 | H | Y | 妥当 | 中央収束 3 本線。標準形 |
| `jis.knit.k3tog-r` | 右上3目一度 | M | N | 要検討 | 方向性 tick が短すぎる |
| `jis.knit.k3tog-l` | 左上3目一度 | M | N | 要検討 | `k3tog-r` の鏡像 |
| `jis.knit.m1-r` | 右増目 | H | Y | 妥当 | 縦棒 + 右小足。OK |
| `jis.knit.m1-l` | 左増目 | H | Y | 妥当 | 鏡像。OK |
| `jis.knit.kfb` | ねじり増し目 | H | **N** | 要検討 | **ラベル / 形状不一致**。JA `ねじり増し目` は JIS / Vogue では twisted-M1 であり `kfb` ではない |
| `jis.knit.cast-on` | 編出し増目 | H | Y | 妥当 | 縦棒 + 底辺バー + count slot。OK |
| `jis.knit.bind-off` | 伏せ目 | H | Y | 妥当 | 二重横線 + count slot。OK |
| `jis.knit.cable-1x1-r` | 1目交差 右上 | H | Y | **要修正** | 純粋な X — over/under の上下差が描き分けられておらず、右上 / 左上が視覚的に同一 |
| `jis.knit.cable-1x1-l` | 1目交差 左上 | H | Y | 要修正 | `cable-1x1-r` と同じ問題 |
| `jis.knit.cable-2x2-r` | 2目交差 右上 | H | Y | 要修正 | 同じ top-over-bottom 不明瞭問題 |
| `jis.knit.cable-2x2-l` | 2目交差 左上 | H | Y | 要修正 | 同上 |
| `jis.knit.cable-3x3-r` | 3目交差 右上 | M | Y | 要修正 | 同上 |
| `jis.knit.cable-3x3-l` | 3目交差 左上 | M | Y | 要修正 | 同上 |
| `jis.knit.cable-1x1-r-p` | 1目交差 右上（下が裏） | M | Y | 要修正 | 同じ X 不明瞭 + 裏目 tick |
| `jis.knit.cable-1x1-l-p` | 1目交差 左上（下が裏） | M | Y | 要修正 | 同上 |
| `jis.knit.bobble` | 玉編み | M | Y | 妥当 | レンズ形。標準 |
| `jis.knit.w-and-t` | 引き返し編み | M | N | 妥当 | 出版社可変 glyph。許容範囲 |
| `jis.knit.k-below` | 引き上げ編み（表） | L | U | 妥当 | OK |
| `jis.knit.p-below` | 引き上げ編み（裏） | L | U | 要修正 | 裏目バー問題を継承 |
| `jis.knit.psso` | かぶせ目 | L | N | 妥当 | 点線バーはローカル慣行。JIS はオーバーレイ弧を指定 |
| `jis.knit.no-stitch` | なし（目なし） | H | Y | 妥当 | 斜めバツ印。Vogue / Interweave 互換 |

サマリ: **35 glyph 中 ~19 が妥当、~16 が craft-correctness 懸念あり**。

## 2. Geometry 懸念 — follow-up バグ候補

スタイル好みではなく明らかな craft-wrong のみ:

1. **裏目バー幅**（`p`, `twist-p-r`, `p2tog-r`, `p2tog-l`, `p-below`）: 0.1→0.9 を ≈0.3→0.7 に狭める。JIS + Vogue + 文化出版局の慣行に合わせる。
2. **交差記号の over/under 描き分け不足**（`cable-*` 全 8 種）: カタログ全体で最もインパクトの大きい修正。現在は両対角線とも unbroken で、右上 / 左上が視覚的に区別不能。
3. **SSK / k2tog 方向性記号**（`k2tog-r/l`, `p2tog-r/l`, `k3tog-r/l`）: 現在は両方とも (0.5, 0.2) で合流する対称な逆 V（= 中央減目）。JIS は「縦棒 + 1 本の斜線」で方向を出す。`\|` = 右上（SSK）、`/|` = 左上（k2tog）。
4. **`kfb` ラベル / 形状不一致**: JA `ねじり増し目` は JIS / Vogue では twisted-M1。ID が誤りか JA ラベルが誤りかユーザ確認要。
5. **`k3tog-r/l` の方向性 tick が薄すぎる**: 24px 以下で不可視。太く / 長くするか、別形状に。

## 3. パラメータスロット audit

3 glyph が `parameterSlots` を宣言している:

| id | slot key | (x, y) | defaultValue | 評価 |
|---|---|---|---|---|
| `jis.knit.cast-on` | `count` | (0.5, 0.25) | `"n"` | 正しい — JIS は count を stem-and-base の上に印字 |
| `jis.knit.bind-off` | `count` | (0.5, 0.65) | `"n"` | 正しい — 二重バーの下 |
| `jis.knit.w-and-t` | `rowLabel` | (0.5, 0.40) | `null` | 正しい — パターン固有でデフォルトなしが正解 |

パラメータスロットのバグなし。**ギャップ:** 交差記号は商用 JP パターンでは目数表記（例: "2/2"）を持つことが多い。v1 ではブロッカーにならないが Phase 32 editor で検討。

## 4. 次 catalog category 推奨

1 (弱) 〜 5 (強) スコア:

| 要因 | CROCHET | AFGHAN | MACHINE |
|---|---|---|---|
| 商用パターン頻度 | 5 | 2 | 2 |
| JIS L 0201 + CYC カバレッジギャップ | 5 | 3 | 3 |
| ユーザセグメント解放 | 5 | 1 | 2 |
| 実装コスト（低い方が良い） | 3 | 2 | 4 |
| **合計** | **18** | **8** | **11** |

**推奨: CROCHET（かぎ針）**。JIS L 0201 Table 2 が既にかぎ針記号を規定しており、CYC にも完全な crochet set あり。JP 商用パターン volume は一度に解放できる最大のオーディエンス層。実装コストは中等度（~25–35 glyph、ほとんどが単一 path、パラメータなし）。

ロードマップに **Phase 30.2** として追加。

## 5. 人間ユーザへの open questions

Knitter は flag するが単独では解決しない項目。ユーザ回答は Phase 30.1-fix の
ハンドオフで確定しており、各項目の下に **User:** 行として併記する。

1. **裏目バー幅** — 中央短線に絞る（Knitter 推奨）か edge-to-edge を維持?
   - **User:** 短線に絞る。ただし中央ではなく **セル下部**。Phase 30.1-fix で `p` / `twist-p-r` / `p-below` の 3 glyph に適用。`sl-p` は今回のスコープ外のため触らない（後続で再検討）。
2. **交差 over/under** — JIS 風の under-stroke broken（Knitter 推奨）か v1 は単純な着色で済ませる?
   - **User:** under-stroke broken。Phase 30.1-fix で 8 cable glyph（`cable-1x1-r/l`・`cable-2x2-r/l`・`cable-3x3-r/l`・`cable-1x1-r/l-p`）すべてに適用、交差点を中心に ~20% gap を入れた。
3. **減目方向性記号** — SSK/k2tog/p2tog/k3tog 全部を「縦棒 + 斜線 1 本」に切り替える（Knitter 推奨）?
   - **User:** YES。縦棒は cell 全高、斜線は y=60–70% で縦棒を横切り、**交差点の下に縦棒 tail を残す**（これが `m1-r/l` 増し目記号との区別 core — M1 は stem + foot で tail なし）。`k3tog-r/l` は斜線 2 本平行、`p2tog-r/l` は top に短い purl 横バーを追加。Phase 30.1-fix で適用済み。
4. **`kfb` vs `ねじり増し目`** — ID を直すか JA ラベルを直すか? 2 通りの修正経路あり、ユーザ判断要
   - **User:** 両方誤り。(a) JIS は `kfb` 記号を標準化していないので `jis.knit.kfb` → `std.cyc.kfb` に移す。Phase 30.1-fix で `CycSymbols.kt` を新設、`std.cyc.*` 初エントリとして kfb を登録。(b) `ねじり増し目` は JA の knitter が増し目用途で使う場合に右ねじり目（`jis.knit.twist-r`）を指す慣用語なので、twist-r に **検索可能 alias** として登録。`SymbolDefinition` に `aliases: List<String>` field を追加。
5. **CYC 英語エイリアス** — `Right-leaning k2tog (SSK)` 形式を維持か、単に `SSK` に短縮?
   - **Open。** Phase 30.1-fix スコープ外。Phase 30.2 以降に持ち越し、現状の記述ラベルを維持。
6. **すべり目の記号慣行** — 現在の合成プリミティブを維持か、JIS / Vogue / CYC の `V` に切替?
   - **Open。** Phase 30.1-fix スコープ外。Phase 30.2 の crochet catalog もしくは後続の `jis.knit.*` geometry pass で再検討。
7. **psso 描画** — 点線バーを維持か、Vogue のオーバーヘッド弧を採用?
   - **Open。** Phase 30.1-fix スコープ外。(6) と同時期に再検討。
8. **次 category 確認** — Phase 30.2 = CROCHET で進めて良い?（本セッションで事前承認済み）
   - **User:** 承認。Phase 30.2 = `jis.crochet.*` カタログ。ADR-008 Phase 30.1 addendum に記録済み。

## 6. Follow-up 作業

- **Phase 30.1-fix**（新規）: §2 の 1–4 と 5 を resolve する geometry-only PR（`KnitSymbols.kt`）。スキーマ変更なし。§5 1–4 へのユーザ回答後にスコープ確定。
- **Phase 30.2**（新規）: Crochet カタログ — `jis.crochet.*` 名前空間、ch / sc / hdc / dc / tr / sl-st / cluster variants 等 ~25–35 glyph。Phase 30 と同じ path-data + `SymbolDefinition` パターン踏襲。
- **Phase 32 スコープ追加**: editor MVP で交差記号に `stitches-over` parameter slot を持たせるか評価。
