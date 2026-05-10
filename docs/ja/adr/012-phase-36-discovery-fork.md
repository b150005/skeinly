# ADR-012: Phase 36 チャート探索 + フォーク（構造化チャート拡張）

> Source: [English version](../../en/adr/012-phase-36-discovery-fork.md)

## ステータス

Proposed

## 概要

Phase 36 は Discovery 画面を構造化チャート対応に拡張し、フォークを
コミットルート型のチャートコピーへ昇格する。Phase 26 のフォークは
Pattern 行のディープコピーのみで、`chart_document` を複製しない。
公開 Pattern に手書きの構造化チャートが付いていても、フォークすると
空の Project + Pattern が出来上がり、フォーク元の編集成果が消える。

主な決定事項（詳細は EN 版を参照）:

1. **コミットラインeージのチェーン化**: フォーク先の
   `chart_document.parent_revision_id` には元の `revision_id` が入る。
   Phase 37 のコラボ作業は当初から populated な祖先グラフから始まる。
2. **Pattern 帰属**: `patterns.parent_pattern_id UUID NULL` 列を追加
   （ON DELETE SET NULL）。マイグレーション 014。
3. **チャートクローンはベストエフォート**: クローン失敗時も
   Pattern + Project はそのまま、エラーを表示してフォーク先は
   「構造化チャート無し」状態で残す。
4. **Discovery フィルタ**: 既存のフィルタチップ列に「チャートのみ」を
   追加。新規画面は作らない。
5. **帰属 UI**: Project 詳細に「<タイトル>（作者: <表示名>）から
   フォーク」行を表示。元 Pattern が公開のままならディープリンク、
   非公開・削除済みならテキストのみ。
6. **チャートプレビューサムネ**: PatternCard 上に約 64dp/64pt で
   `ChartCanvas` をライブレンダリング。Phase 36.x でキャッシュ化を
   検討（パフォーマンス計測次第）。

## 関連 ADR

- ADR-007: チャートオーサリングへのピボット（Phase 36 を Discovery +
  フォーク拡張として位置づけ）
- ADR-008: 構造化チャートデータモデル（§7 `content_hash` 不変条件、
  `revision_id` 系譜の足場）
- ADR-010: セグメント単位進捗（segments は project スコープ、
  フォーク時にコピーしない）
- ADR-011: Phase 35 高度エディタ（クローズ済み、Phase 36 はここから）

詳細な意思決定プロセス、エージェントチーム議論、却下した代替案は
[英語版](../../en/adr/012-phase-36-discovery-fork.md) を参照。

---

## Amendment — 2026-05-10 (用語監査、v0.1.0 直前)

`audits/terminology-audit-2026-05-10.md` の決定に基づく用語ピボット。
詳細は EN 版 ADR の Amendment ブロックを参照。

**主要なリネーム** (本セッション 2026-05-10 適用):

| 旧 | 新 |
|---|---|
| Structured Chart / 構造化チャート | Chart / 編み図 |
| Fork / フォーク | Save a copy / コピーを保存 |
| Branch / ブランチ | Variation / アレンジ |
| Revision・Commit / リビジョン・コミット | Version / バージョン |
| Pull request / プルリクエスト | Suggestion / 提案 |
| Merge / マージ | Apply changes / 変更を反映 |
| Diff / 差分 | Comparison / 比較 |
| Discovery (EN) | Browse Patterns (EN); JA は既に「パターンを探す」で OK |

**Supabase migrations 026 + 027** で `chart_revisions` → `chart_versions`、
`chart_branches` → `chart_variations`、`pull_requests` → `suggestions`、
`pull_request_comments` → `suggestion_comments`、status enum value
`'merged'` → `'applied'`、`merge_pull_request` RPC → `apply_suggestion` を
適用済 (prod 反映済 2026-05-10)。

**検証ベース**: docs-researcher (T2) Round 1 + scoped Round 2 が
Craft Yarn Council, 日本ヴォーグ社 (tezukuritown.com), Brooklyn
Tweed, Stephen West, amu app 等の primary source で各リネームを
裏付けた。research-critic agent が Round 1 を独立 tool family
(WebFetch + GitHub) で再検証し 6/9 PASS を確認。

事前 v1 破壊的変更ポリシー (CLAUDE.md `### Planned — Phase 39`
HARD-GATE) により内部表名・status enum 値の変更を許容。
