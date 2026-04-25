# ADR-013: Phase 37 コラボレーションコア（コミット履歴・ブランチ・差分ビュー）

> Source: [English version](../../en/adr/013-phase-37-collaboration-core.md)

## ステータス

Proposed

## 概要

ADR-007 で v1.0 の差別化要因として「チャートに対する Git ライクな
コラボレーション」を約束した。Phase 29–36 で構造化チャートの
土台（ADR-008/009/010/011/012）を構築し、`chart_documents` には
最初から `revision_id` / `parent_revision_id` / `content_hash` 列を
予約してきた。Phase 36 でフォークが初めて非 NULL の
`parent_revision_id` を書き込み、Phase 37 はその系譜を初めて
読み出す側になる。

ただし `chart_documents` は今 `pattern_id UUID NOT NULL UNIQUE` で、
1 パターン 1 行しか持たない。`update` は行を上書きするので過去の
リビジョンは消える。コミット履歴を提供するためには、`chart_documents`
を append-only に作り変えるか、別テーブルで履歴を持つかを決める
必要がある。

主な決定事項（詳細は EN 版を参照）:

1. **append-only な `chart_revisions` テーブルを新設**。`chart_documents`
   は今のまま（UNIQUE on `pattern_id`）残し、tip ポインタとして使う。
   既存のあらゆる読み取り経路は無変更で動き続ける。
2. **Tip ポインタは `chart_documents.revision_id`**。書き込みパスは
   「① `chart_revisions` に INSERT → ② `chart_documents` を UPDATE」
   の 2 ステップ。原子性は保証せず、PendingSync の coalesce 機構に
   任せる（Phase 38+ で必要になればサーバ RPC を検討）。
3. **ブランチテーブル `chart_branches` も予約**。`(pattern_id, branch_name)
   → tip_revision_id` のマッピング。マイグレーションは 37.1 で同梱、
   UI は 37.4 まで保留。MVP では "main" ブランチのみが自動生成される。
4. **コミットメッセージは省略可能**。`chart_revisions.commit_message TEXT`
   nullable。自動保存は NULL、明示的な保存時のみ単一行入力フィールドを
   表示。
5. **差分アルゴリズムはセル単位**。`(layer.id, x, y)` の 3 つ組をキーに
   added / modified / removed を分類。レイヤープロパティ変更
   （表示・名前・ロック）は別系統で追跡。極座標チャートも同じ
   アルゴリズムで動く（`(stitch, ring)` をキーにする）。
6. **UX は専用画面 `ChartHistoryScreen` + `ChartDiffScreen`**。
   ChartViewer のタブにはしない。差分ビューは横並びの `ChartCanvas`
   レンダリングで、変更セルを色分けハイライト。パン/ズームは両ペインで
   同期。
7. **Phase 37 はサブスライス分割**（37.0 ADR のみ・コードなし → 37.1
   スキーマ＋データ層 → 37.2 履歴一覧 UI → 37.3 差分ビュー → 37.4
   ブランチ＋復元）。Phase 36 で 5 つのコードスライスを分けて回した
   実績に倣う形だが、Phase 37 では 37.0 をドキュメント単独スライスと
   位置づけてコードと切り離す（コードは 37.1〜37.4 の 4 つ）。

## 明示的に MVP の対象外

- プルリクエストワークフロー（Phase 38）
- 三方向マージ・コンフリクト解決 UI（Phase 38）
- フォーク先での「上流から取り込む」操作（Phase 38）
- cherry-pick / rebase / squash / amend（永続的に対象外）
- 過去のコミットメッセージの編集
- ブランチ保護ルール / 必須レビュアー（永続的に対象外）
- 差分の作者表示（blame、Phase 38+）
- フォークチェーンの完全祖先表示 UI（ADR-012 §8 で保留済み）
- リビジョン append + tip update のサーバ RPC 原子化（テレメトリで
  必要性が見えたら再検討）
- CRDT による同時編集（v1 以降の検討、ADR-007）

## 関連 ADR

- ADR-007: チャートオーサリングへのピボット（Phase 37 をコラボ・コアとして位置づけ）
- ADR-008: 構造化チャートデータモデル（`revision_id` 系譜の足場、§7 `content_hash` 不変条件）
- ADR-010: セグメント単位進捗（segments は project スコープ、コミット履歴とは独立）
- ADR-011: Phase 35 高度エディタ（クローズ済み、Phase 37 はその出力を読む）
- ADR-012: Phase 36 Discovery + フォーク（`parentRevisionId` 最初の書き手、Phase 37 は最初の読み手）

詳細な意思決定プロセス、エージェントチーム議論、却下した代替案は
[英語版](../../en/adr/013-phase-37-collaboration-core.md) を参照。
