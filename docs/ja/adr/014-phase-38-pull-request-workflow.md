# ADR-014: Phase 38 プルリクエストワークフロー（コメント・承認・マージ・コンフリクト解決）

> Source: [English version](../../en/adr/014-phase-38-pull-request-workflow.md)

## ステータス

Proposed

## 概要

Phase 37（ADR-013）でコラボレーションの読み取り側
（履歴・ブランチ・差分ビュー）が完成した。Phase 38 は書き込み側を閉じる
スライス：フォーク所有者が変更を上流の作者に提案し、作者が差分を
レビューしコメントしてマージする / クローズする一連のループを構築する。
マージ時、寄稿者の編集はターゲットブランチに **新しいコミット 1 件** として
着地し、その `chart_revisions.author_id` は寄稿者（= フォーク所有者）に、
`owner_id` はターゲット所有者になる。これは ADR-013 §1 で「Phase 38 のために」
予約しておいた `author_id` 列が初めて `owner_id` と異なる値を取るケースとなる。

Phase 38 が初めて扱う 3 つの構造的要素:

1. **複数作者コミット**。`chart_revisions` の INSERT RLS は今
   `WITH CHECK (owner_id = auth.uid() AND author_id = auth.uid())` で、
   ターゲット所有者が自分以外の `author_id` を持つ行を挿入することを
   禁じている。Phase 38 はこの制約を超える必要がある。
2. **クロスパターン系譜**。Phase 37 の系譜は同一パターン内のみだが、
   マージはソースパターンの描画ペイロードをターゲットパターンの新規
   コミットとして書き込む。`parent_revision_id` はターゲット内に
   留まる（チップに乗る）が、`author_id` はソース所有者を指す。
3. **3-way 差分**。コンフリクトは「共通祖先 / theirs / mine」の 3 リビジョン
   間でセル単位に diff-of-diffs を行うことで検出する。Phase 37 の差分
   アルゴリズムをそのまま流用できる形だが、UI は読み取り専用ではなく
   インタラクティブ（セルごとに side を選ぶ）になる。

主な決定事項（詳細は EN 版を参照）:

1. **PR テーブル `pull_requests` を新設**。列は `source_pattern_id /
   source_branch_id / source_tip_revision_id /
   target_pattern_id / target_branch_id /
   common_ancestor_revision_id / author_id / title / description /
   status（`open|merged|closed`）/ merged_revision_id / merged_at /
   closed_at / created_at / updated_at`。
   `common_ancestor_revision_id` は PR open 時にクライアント側で
   ソースの `parent_revision_id` チェーンをたどってターゲット履歴と
   交差する最初のリビジョンとして算出し、PR 行に NOT NULL で保存する。
   マージ RPC が再検証する。INSERT 用 RLS は `source_pattern_id` が
   `target_pattern_id` のフォークであること（`patterns.parent_pattern_id =
   target_pattern_id`）を強制する。コメントは別テーブル
   `pull_request_comments`（append-only）。
2. **マージはサーバ側 RPC `merge_pull_request` で原子化**。`SECURITY
   DEFINER` で実行し、PR open 状態 / 呼び出し元がターゲット所有者 /
   ソースチップ未変更 を検証してから、`chart_revisions` への INSERT
   + `chart_branches` のチップ更新 + `chart_documents` のチップ更新
   + PR の `status='merged'` 化を一括で実施する。`author_id !=
   auth.uid()` の行を生成できる唯一の書き手はこの関数のみ。
   ADR-013 で「テレメトリで分岐が見えたら再検討」と保留した RPC を
   Phase 38 で実際に必要になったため解禁する。先例は ADR-005 の
   `delete_own_account`。
3. **マージ戦略のデフォルトは squash**。Fast-forward は適用可能なときに
   自動検出するが UI 上の選択肢としては露出しない。Multi-parent
   merge-commit は `chart_revisions.parent_revision_id` が単一値なので
   v1 では却下（スキーマ再設計が必要になる）。編み手は multi-parent の
   履歴可視化から恩恵を受けない。
4. **コンフリクト解決は専用画面 `ChartConflictResolutionScreen`**。
   `ChartDiffScreen` は読み取り専用 2 ペインなので拡張ではなく分離。
   3 ペイン（祖先 / theirs / mine）+ セルごとのピッカー
   （Take Theirs / Keep Mine / Skip）。配色は Phase 37 の信号機
   パレット（追加 / 変更 / 削除）と区別するため第 4 色（Material
   `tertiary` / iOS `systemPurple` 50% アルファ）を使う。
5. **承認は単独所有者・1 ボタン**。「Required Approval」「N 名レビュアー」
   「ブランチ保護」は v1 では作らない。ターゲット所有者 = 唯一の承認者で、
   Merge ボタンが承認の表現、Close ボタンが拒否の表現。コメントは
   情報用途のみ（ゲートではない）。
6. **PR 一覧画面 `PullRequestListScreen(filter: Incoming|Outgoing)`** を
   1 つに統一。`incoming` はターゲットが自分のパターン、`outgoing` は
   author が自分。同じデータモデルを 2 視点で見せる。
7. **PR 通知用の Realtime チャンネル**は所有者ベースで 2 つ追加：
   `pull-requests-incoming-<ownerId>`（ターゲットフィルタ）と
   `pull-requests-outgoing-<ownerId>`（author フィルタ）。コメント用は
   PR 詳細画面表示中だけ動的に開閉する `pull-request-comments-<prId>`。
8. **タイトル / 説明 / コメントは v1 ではプレーンテキスト**。Markdown は
   render-target 差分（Compose `Text` vs SwiftUI `Text`）で共有
   サニタイザを持たないため避ける。タイトル 200 字 / 説明 2000 字 /
   コメント 5000 字のソフトリミットを UI で強制。

## サブスライス計画

| Slice | スコープ | テスト目標 | マイグレーション | i18n キー |
|---|---|---|---|---|
| **38.0** | この ADR（コードなし） | 0 | 0 | 0 |
| **38.1** | スキーマ + RPC + ドメイン + リポジトリ + 同期配線。UI なし | +25–35 | 016 | 0 |
| **38.2** | PR 一覧 UseCase + ViewModel + Screen。読み取り専用 | +10 | 0 | 9 |
| **38.3** | PR 詳細 + コメント + open/close UseCase + Screen。マージ未配線 | +20–25 | 0 | 14 |
| **38.4** | `ConflictDetector` + コンフリクト解決画面 + Merge UseCase + RPC 配線 | +20 | 0 | 9 |

各サブスライスは `CLAUDE.md` の "Completed" セクションを同じコミット
で更新する。38.4 は MVP からスリップしても 38.2/38.3 の読み取り専用
レビュー体験は成立する（マージは "Coming soon" でブロック）。38.3 を
スリップさせると一覧が情報不能になり、38.2 をスリップさせるとデータ
スパインが観測不能になる。

## 明示的に MVP の対象外

- フォーク先での「上流から取り込む」操作（Phase 38+）
- 必須承認ゲート / ブランチ保護
- 任意ターゲットの PR ルーティング（v1 はソースの `parentPatternId` 限定）
- マルチ作者 blame ビュー（Phase 38+ で `author_id` を使って構築）
- CRDT 同時編集（v1 以降）
- cherry-pick / rebase / squash-with-fixup / amend
- Multi-parent merge-commit 戦略
- 過去のコメントの編集 / 削除
- ドラフト PR ステート
- PR テンプレート / 保存済み説明
- コメントへのリアクション（絵文字 / +1）
- @-メンション + プッシュ通知
- Markdown / 整形コメント
- ソースチップが解決中にずれた場合の再解決の UX 改善（v1 は汎用
  エラーメッセージ）

## 関連 ADR

- ADR-001: バックエンドプラットフォーム（薄いサーバ、SECURITY DEFINER）
- ADR-005: アカウント削除（`delete_own_account` RPC、SECURITY DEFINER の先例）
- ADR-007: チャートオーサリングへのピボット（Phase 38 をコラボ・ループの完結として位置づけ）
- ADR-008: 構造化チャートデータモデル（`revision_id` がコミット識別子）
- ADR-012: Phase 36 Discovery + フォーク（`Pattern.parentPatternId` が PR ルーティングの上流ポインタ）
- ADR-013: Phase 37 コラボレーションコア（履歴・ブランチ・差分。Phase 38 はその上に乗る）

詳細な意思決定プロセス、エージェントチーム議論、却下した代替案は
[英語版](../../en/adr/014-phase-38-pull-request-workflow.md) を参照。
