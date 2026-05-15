# ADR-024: Phase 25 — フレンド限定 / プライベートサークルモード

> 英語版が正本: [docs/en/adr/024-phase-25-friend-only-private-circle.md](../../en/adr/024-phase-25-friend-only-private-circle.md)

## ステータス

承認済み（2026-05-15）。Phase 25 は Phase 39 alpha ローンチ HARD-GATE をブロックせず、並行して進行する。ADR-024（本ドキュメント）が実装サブスライス 25.1〜25.5 をゲートし、25.x が TestFlight + Play Internal テスター招待発行前に完了した場合は alpha がフレンド限定モード込みでローンチする。それ以前にテスター招待が出る場合は alpha は公開のみ共有でローンチし、25.x はベータ運用期間中のビルドアップデートとしてロールインする。

## 背景

IARC Step-2 アンケート（vendor-setup A0d-4 Q8）に以下のサブ質問がある：

> 「アプリでの対話は招待された友人のみに制限できますか?」

Skeinly の現在の回答は **いいえ**。`patterns` の visibility enum は既に `{private, shared, public}`（migration 002、2026 年初頭）だが、`shared` は「リンクトークンで共有」を意味し — 共有 URL を持つ誰でも閲覧可能 — 「キュレーションされたフレンドグループと共有」とは異なる。データ層にフレンドグラフのプリミティブが存在しないため、Q8 を正直に **はい** と答えることはできない。

オペレータの 2026-05-14 のスタンス（CLAUDE.md Phase 25 計画エントリより）：

> "プライバシーコントロールはコア製品能力として『最初から』属するべきで、ローンチ後のレトロフィットではない。Phase 24 プッシュ通知が Phase 39 alpha 準備との並行作業の前例。"

Phase 25 は Skeinly のプライバシー姿勢の第 3 次元を導入する：

1. **アカウントレベル**のプライバシー — Phase 26（OAuth + MFA + biometric）出荷済。
2. **コンテンツレベル**の削除 — Phase 27（データ消去 + アカウント削除）出荷済。
3. **可視性スコープ** — Phase 25（本 ADR）。既存 visibility enum に第 4 の値 `friends` を追加 + `friend_connections` グラフ。

IARC コンプライアンスを超えて、「ニッティング・アロング・グループとは共有するが Discovery 公開フィードには出さない」は確立された社交的ニッティングのワークフロー。ニッターは仕上げる前のパターンを信頼サークルにフィードバック / 励まし目的で共有し、洗練後に広い視聴者に公開する。比較対象の成熟アプリ（Ravelry の「friends」階層、Discord の friends-only サーバー、Twitter の鍵アカウント）はすべてこの粒度を提供している。Skeinly の現在の `shared`（リンクトークン）は不完全な代替 — スクリーンショット / 転送で漏洩する — フレンドグラフメンバーシップこそが正しいアクセス制御プリミティブ。

近隣 ADR との関係：

- **ADR-005**（`delete_own_account` RPC によるアカウント削除）— Phase 25 は `auth.users` と CASCADE 削除される `friend_connections` 行を追加（前例通り ON DELETE CASCADE）。
- **ADR-021**（UGC モデレーション — `user_blocks` テーブル）— Phase 25 の RLS visibility-aware アームは migration 032 の既存 `user_blocks NOT EXISTS` アームと結合する。
- **ADR-023**（Phase 27 データ消去）— Phase 25 はデータ消去時に `friend_connections` 行がどうなるかを決定する必要がある（決定 (g) 参照）。
- **ADR-019**（Phase 39 Universal Link インフラ）— Phase 25.4 招待フローは既存 `/skeinly/...` Universal Link / App Link 名前空間に新パス `/skeinly/friend/<invite_token>` を拡張する。

## Agent team 協議

### (a) 接続モデル — mutual-confirmation vs follower-graph

主流の 2 設計：

- **Mutual-confirmation（相互確認）** — A が B にリクエスト送信；B が承認；行の状態が `pending → accepted` に遷移；両側が対称的にフレンドとして互いを認識。どちらかが切断；行の状態が `accepted → blocked` に遷移（または (g) の決定次第で行削除）。
- **Follower-graph（フォロワーグラフ）** — A が一方的に B をフォロー；B は承認不要；B は任意で A をフォローバック；関係は方向性（A→B と B→A は独立した行）。

各エージェントの声：

- **knitter**: 「ニッティングサークルは相互。Twitter で『フォロー』する相手と、WIP を共有したい相手は同じセットではない。ユースケースに合致する関係動詞は『互いを知っている』 — 相互。」
- **product-manager**: 「Twitter のフォローグラフは放送メディア・セマンティクスのため機能する。Skeinly はワークスペース・メディア・セマンティクス — WIP パターンは親密。follower-graph の非対称性は『間違った相手と共有してしまった』という表面を生み、相互ならこれを回避できる。」
- **architect**: 「Mutual は sorted-pair 不変条件（`user_a < user_b`）の composite-PK 行にきれいにマップする。Follower-graph は 2 行または方向性カラムが必要。RLS クエリ `is_friend(A, B)` は sorted-pair 不変条件があれば単純 — 1 EXISTS チェック、決定論的キー順。」
- **security-reviewer**: 「Mutual は『A が B をブロックしたが B はまだ A のフレンド限定コンテンツをキャッシュ経由で見える』という非対称ブロック表面を排除。Mutual ではどちら側のブロックも双方向にリンクを切断する。」

**決定**: **Mutual-confirmation**。`friend_connections` テーブル、`(user_a, user_b)` の composite PK、アプリケーションコードが INSERT/SELECT 前にペアをソート（`LEAST(uid1, uid2), GREATEST(uid1, uid2)`）。状態 enum `{pending, accepted, blocked}`。片側からの切断は行削除ではなく `accepted → blocked` 遷移（リクエスター側が次回クエリで「この接続は切られた」という明確なシグナルを得るため）。

### (b) Visibility カラムセマンティクス — 既存 enum の拡張

`patterns.visibility` は既に `{private, shared, public}` の値で存在（migration 002）。現在のセマンティックマッピング：

- `private` = オーナーのみ
- `shared` = 有効な共有トークン URL を持つ誰でも（`shares` テーブル経由で管理）
- `public` = Discovery フィードで誰でも閲覧可能

Phase 25 は 4 番目の値を導入：**`friends`** = オーナー + 承認済みフレンドグラフメンバー。

4 つの値は意図的な段階を形成：

| 値 | オーナー | フレンド | トークン保持者 | 公開 |
|---|---|---|---|---|
| `private` | ✓ | ✗ | ✗ | ✗ |
| `friends` | ✓ | ✓ | ✗ | ✗ |
| `shared` | ✓ | ✗ | ✓ | ✗ |
| `public` | ✓ | ✓ | ✓ | ✓ |

`friends` と `shared` は **直交** であり、連鎖関係ではないことに注意 — パターンはトークンなしのフレンド限定にも、フレンドグラフアクセスなしのトークン共有にもなれる。2 つは異なる信頼モデルを表す。

`chart_versions`、`comments`、`suggestions` は独自の `visibility` カラムを持たない — 親パターンから可視性を継承。それらのテーブルの RLS ポリシーは `patterns.visibility` を JOIN で参照する。データモデルをスリムに保ち、ドリフトを防止（例：`public` パターンの `friends` コメントは表現したくない混乱した状態）。

各エージェントの声：

- **architect**: 「enum を拡張、並列システムを導入しない。4 値 enum は信頼スコープのクリーンな全順序。`friends` と `shared` の直交性はバグでなく機能 — ニッターは明示的にニッティング・アロング・グループ AND 毛糸屋で会った見知らぬ人への一回限りのトークンの両方でパターンを共有したいかもしれない。」
- **product-manager**: 「子テーブルに visibility を追加しない。公開パターン上の『friends-only』コメントは UX 混乱 — ユーザーは公開スレッドに入力したのに非表示になることを期待していなかった。親から継承するのが予測可能なモデル。」
- **security-reviewer**: 「親パターンへの JOIN 経由の RLS はセキュリティ的に正しい読み取りパス。親の visibility が source of truth；コメント / suggestion は委譲するだけ。ドリフト表面なし。」

**決定**: `patterns.visibility` enum に `friends` を拡張。`chart_versions` / `comments` / `suggestions` には `visibility` カラムを追加しない — `patterns.visibility` への JOIN で継承。CLAUDE.md Phase 25.1 の記述「`patterns` / `chart_versions` / `comments` / `suggestions` 上の visibility カラム」は過剰指定 — patterns のみカラムが必要。

### (c) デフォルト visibility

オペレータの 2026-05-14 フラグ（CLAUDE.md）：

> 「default `public` to keep Discovery non-ghost-town」

現在の `patterns.visibility DEFAULT 'private'`（migration 002）は、Discovery 前の時代に明示的な共有リンクで opt-in 共有していた時代には正しかった。Discovery（Phase 36）がコアエンゲージメント表面となり、Phase 25 が `friends` を意味のある中間として追加する中、デフォルトを再アンカーする必要がある。

各エージェントの声：

- **product-manager**: 「デフォルト `public` はオペレータの目標と一致 — Discovery が新コンテンツで自動的に populated される。しかしユーザーが期待していないプライバシー姿勢のダウングレード。摩擦計算：新ユーザーがパターンを作成、Save をタップ、可視性ピッカーを見ない（public がデフォルトのため）、後で最初の編み目スケッチが Discovery 上にあることに気付く。これは回避可能な『あっ』モーメント。」
- **ui-ux-designer**: 「パターンコンポーザー（PatternEditScreen）の作成時点で可視性ピッカーを surface する。ピッカーの INITIAL 状態を public にデフォルト設定するが、パターンを保存する行為を明示的な可視性決定にする。これによりユーザーは opt-in していないプライバシー姿勢を強制されることなく、可視性選択を所有する。」
- **knitter**: 「編み目をプロトタイピング中は private にしたい。共有用パターンを最終化するときは public にしたい。モード切り替えは『シェアの準備完了』時点で起きる、『スケッチ開始』時点ではない。摩擦モデルは：最初の保存ではデフォルト private、ユーザーが Discovery またはフレンド経由で明示的に共有するときに可視性アップグレードのプロンプト。」
- **architect**: 「2 つのデフォルトを選ぶ：(1) visibility を指定しない新規 INSERT 向けの DB レベル `DEFAULT` — バックフィル、RPC パス、admin ツール挿入で重要；(2) PatternEditScreen 可視性ピッカーの UI レベル初期状態。両者は異なってよい。」

Agent team がハイブリッドに収束：

- **DB レベルデフォルト**: `'private'` を維持（カラムデフォルトへの migration 変更なし）。バックフィル安全性 — 既存パターンは可視性を保持。可視性を指定しない新規 RPC パスは最も安全なセマンティクスを得る。
- **UI レベル初期状態** PatternEditScreen 可視性ピッカー: NEW パターン作成時（patternId が null のとき） = **`public`**。これはオペレータの「ゴーストタウン回避」意図と一致。EDIT 時はピッカーが現在の値をプリフィル。
- **Discovery 公開ゲート**: 別途、Phase 25.5 が Discovery フィルタートグル（「friends-only パターンを表示」）を surface。デフォルト動作：公開のみフィルター。ユーザーが明示的に friends-only パターンを表示することに opt-in。

**決定**: DB レベルデフォルトは `'private'` のまま。NEW パターンの UI レベルピッカー初期状態は `'public'`。ピッカーは PatternEditScreen で必ず表示されなければならない — サイレントデフォルトなし — ユーザーが可視性選択を所有する。コメントと suggestion は親から可視性を継承（コンポーザーにピッカーは不要）。

### (d) 招待メカニズム — アプリ内コード + Universal/App Link

2 つのメカニズムが必要：

1. **アプリ内共有可能招待コード** — A が「Settings → Privacy → Connections → Invite a friend」から 6-8 文字のコードをコピーし、任意の帯域外チャネル（iMessage、LINE、対面）で送信。B が Skeinly を開き、「コードでフレンドを追加」をタップ、コードをペースト、Accept をタップ → フレンドリクエストが A の pending リストに着地。

2. **Universal Link / App Link 経由のディープリンク** — A が同じ Connections 画面から OS シェアシート経由で Share をタップ。OS シェアシートが `https://b150005.github.io/skeinly/friend/<invite_token>` を発信。B が iMessage/LINE/Mail でリンクをタップ → 既存 Phase 39 Universal Link インフラ経由でデバイスが Skeinly を開く → アプリが「A からのフレンドリクエストを承認しますか?」画面にルーティング → Accept タップで相互フレンドに遷移。

各エージェントの声：

- **knitter**: 「私のニッティング・アロング・グループの半分は iOS の iMessage、半分は Android の LINE。リンクパスは両方で機能。コードパスは『編み物の夜のミートアップで友達に声でコードを伝えた』時のフォールバック。」
- **product-manager**: 「両方。コードは最も摩擦の低い discovery パス；リンクは最も摩擦の低い completion パス。冗長ではない — 異なる招待コンテキストをターゲット。」
- **architect**: 「Universal Link インフラは既存（ADR-019、Phase 39）。新パス `/skeinly/friend/<token>` は既存ルートテーブルに新プラットフォームレベル設定なしで slot-in。サーバー側で、トークンは `friend_invites` 行（`friend_connections` とは別）に解決される — `created_by`、`token`、`expires_at`、`consumed_at` 列。」
- **security-reviewer**: 「トークンセキュリティ: 32 バイト URL セーフランダム、single-use（`consumed_at IS NULL` チェック + RPC 内アトミック set）、14 日有効期限。期限切れ後の再利用はトークン列挙経由の inviter discovery を防ぐため一般的な『この招待は無効です』メッセージを返す。コードは 8 文字英数字（人間可読性のため O/0/I/l を除外） — 最初の 8 コードに対する衝突確率約 1/33^8 = 1.4 兆分の 1、`gen_random_bytes(6)` + base32 エンコーディングで生成、14 日有効期限。」

**決定**: **両方** 出荷。コードはカジュアル / 帯域外招待向け；Universal Link はチャネル内タップ向け。新 `friend_invites` テーブル（`friend_connections` とは別）が背後にあり、`(id, inviter_id, token, code, expires_at, consumed_at, consumed_by)` 列を持つ。トークン形式：32 バイト URL セーフランダム；コード形式：O/0/I/l を除く 8 文字 base32。両方とも作成から 14 日後に期限切れ。2 つの RPC：`redeem_friend_invite_code(p_code TEXT)` と `redeem_friend_invite_token(p_token TEXT)` — 両方とも内部で同じ `friend_connections` INSERT に正規化。

### (e) フレンドリスト UX — Settings → Privacy → Connections

CLAUDE.md Phase 25.3 計画通り：pending リクエストの受信ボックス + 承認済み接続リスト + 検索・招待エントリポイントを持つ Settings → Privacy → Connections 画面。

各エージェントの声：

- **ui-ux-designer**: 「Connections 画面のタブ：[Friends] [Pending] [Invite]。Friends タブ = 承認済み接続のリスト、切断アクション付き（長押しまたはトレイリングアイコン）。Pending タブ = 受信リクエスト（Accept/Reject アクション） + 送信済み招待（Cancel アクション）。Invite タブ = コード生成カード + 『システムシェアシート経由で共有』ボタン（Universal Link 向け）。」
- **product-manager**: 「これを Pattern Library や任意のコンポーザー画面に surface しない。フレンド管理はメタアクション、コンテンツアクションではない。Settings → Privacy が正しいアーキテクチャ的ホーム。」
- **architect**: 「既存 Settings sectioning を再利用。Privacy セクションは現在 Delete-all-my-data（Phase 27.2、Delete Account の上）を housing。Connections は Delete-all-my-data の BELOW に配置 — 破壊性が増す順にプライバシーコントロールアクション：接続管理（非破壊的） → データ消去（コンテンツ破壊的） → アカウント削除（identity 破壊的）。」

**決定**: Settings → Privacy → Connections の新画面、専用スタックルート `ConnectionsScreen` としてナビゲート。3 タブレイアウト：Friends / Pending / Invite。Settings 行エントリ「Connections」は「Delete all my data」の BELOW だが「Delete Account」の ABOVE — 視覚的順序が破壊性勾配と一致。

### (f) Discovery 統合 — 公開のみフィルターデフォルト

Discovery フィード（Phase 36）は現在すべての `public` パターンを surface する。`friends` 追加で、Discovery の動作の質問：

1. **常に両方を表示** — `public` AND `friends` パターンを視覚的区別付き（例：friends-only カードに「Friends」バッジ）。
2. **公開のみフィルターデフォルト** — opt-in トグルで「friends-only パターンを表示」を公開フィードの隣に。
3. **2 つの別タブ**: [Discover]（公開）と [Friends]（friends-only フィード）。

各エージェントの声：

- **knitter**: 「明確な分離が欲しい。公開 Discovery は見知らぬ人の新パターンを見つけるため。Friends-only は私のサークルの WIP をフォローするため。混ぜると混乱する — カジュアルブラウズしたいとき、同じスクロールに親密な WIP は欲しくない。」
- **ui-ux-designer**: 「2 タブが最もクリーン。しかし Discovery のトップバー複雑性が増す。トグルが中間 — friends-only を含める opt-in 付きの単一フィード。opt-in 状態はユーザーごとに永続化。」
- **product-manager**: 「オプション 2 で開始 — 公開のみフィルターデフォルト + トグル。ベータシグナルでトグルが混乱と判明、または friends-only フィードが専用キュレーションを必要とするほど成長したら、2 タブに移行。」

**決定**: **オプション 2**。Discovery クエリが visibility フィルターを取得（`WHERE visibility = 'public' OR (visibility = 'friends' AND is_friend(auth.uid(), owner_id))`）。Discovery TopAppBar がトグル「friends-only パターンを表示」を追加、ローカル `Settings` バック preferences でユーザーごとに永続化（サーバー往復不要；preference は純粋にクエリパラメータ）。デフォルト OFF（公開のみフィルター）。クエリパスはどちらの場合も単一 SELECT；WHERE 句のみ変更。

### (g) 横断的決定（CLAUDE.md の 6 への追加）

#### (g.1) friend_connections 消去セマンティクス（ADR-023 との交点）

ユーザーが「すべてのデータを削除」を呼び出したとき、`friend_connections` 行はどうなる？

各エージェントの声：

- **product-manager**: 「『すべてのデータを削除』の全体ポイントはコンテンツリセット、関係リセットではない。フレンドは『コンテンツ』ではない — identity 隣接。保持。」
- **security-reviewer**: 「反論 — フレンドリストはユーザー生成データであり、プライバシー漏洩表面（リストはユーザーが知っている人を明らかにする）。ユーザーの消去意図は『クリーンスレート』 — それをフレンド接続を削除することと解釈するのも合理的。」

**決定**: **送信は消去、受信は保持**。消去ユーザーが `requester_id`（彼らが発起）の `friend_connections` 行は DELETE。ユーザーが受信者の行（state `accepted`）は state を `blocked` に遷移、相手側がクリーンに「この接続が切られた」を見る。受信 `pending` リクエストは DELETE（受け入れられたことがないので通知不要）。これは消去を「社交的にやり直す」と扱い、「皆が私を忘れる」とは扱わない。ADR-023 §preservation matrix amendment に文書化。

**実装ステータス（2026-05-15）**: ✅ **適用済み** — migration 037（`037_phase_25_1_wipe_friend_graph.sql`、prod 適用名 `phase_25_1_wipe_friend_graph`）。Phase 25.1 で先送りされた挙動（migration 035 §note: `wipe_own_data()` は消去ユーザーの `friend_connections` / `friend_invites` 行を残置）をクローズ。migration 037 は `public.wipe_own_data()` を `CREATE OR REPLACE` し、outbound `DELETE FROM friend_connections WHERE requester_id = v_uid` / inbound-pending `DELETE` / inbound-accepted `UPDATE state='blocked', accepted_at=NULL`（`friend_connections_accepted_at_matches_state` CHECK が遷移時の `accepted_at = NULL` を強制）/ inbound-blocked 不介入（terminal）を追加。`friend_invites` 拡張: `DELETE FROM friend_invites WHERE inviter_id = v_uid`（outbound のみ）— `consumed_by = v_uid` 行は `friend_invites_consumed_pair` CHECK が `consumed_at`/`consumed_by` を結合するため保持（単独 NULL は不正、両 NULL は単回使用招待を再 redeem 可能に復活）。RPC 名不変（`wipe_own_data`）のため Kotlin クライアント編集なし; Postgrest バインディング定数は `WipeDataRepositoryImplTest` で pin。

#### (g.2) フレンドのフレンド可視性

フレンドのフレンドは friends-only パターンを見られる？

**決定**: **いいえ**。RLS アームは `is_friend(auth.uid(), owner_id)` を直接チェック — 第一級接続のみアクセス付与。フレンドのフレンドアクセスは意図的なスコープカット（cf. Facebook の友達の友達への予期しない露出によるプライバシーインシデント履歴）。

#### (g.3) ブロックされた側のフレンド状態への可視性

A が B を（`user_blocks` 経由で）ブロックし、A と B が以前フレンドだった場合、フレンド接続は自動切断される？

**決定**: **はい、アトミックに**。`block_user(p_blocked_id UUID)` RPC（追加された場合 — 現在 `user_blocks` は RLS 経由の INSERT）は同じトランザクション内で一致する `friend_connections` 行を state `blocked` に遷移。その RPC が追加されるまで、アプリケーションレイヤーが呼び出し場所でこれを処理。ADR-021 Wave E `user_blocks NOT EXISTS` RLS アームが既にブロックされたユーザーを SELECT クエリからフィルタしているため、フレンド状態行は瞬間的に `accepted` のままだがコンテンツは不可視 — Wave-E 時代の簡略化として eventual consistency は許容。

## サブスライス計画

各サブスライスは独立して出荷可能。フルウェーブは Phase 39 alpha ローンチと並行追跡可能（25.x がテスター招待発行前に閉じない場合、alpha は公開のみ共有で出荷可）。

### Phase 25.1 — データ spine + RLS 更新 + Repository

**Migration 035**（`035_phase_25_1_friend_graph.sql`）：

1. CREATE TABLE `public.friend_connections`（`user_a UUID`、`user_b UUID`、`state TEXT CHECK IN ('pending', 'accepted', 'blocked')`、`requester_id UUID`、`created_at`、`accepted_at`、composite PK + sorted-pair CHECK）。
2. ALTER TABLE `public.patterns` ALTER COLUMN `visibility` — カラムレベル CHECK 制約を拡張し `'friends'` を含める。カラムデフォルトは変更なし。
3. CREATE FUNCTION `public.is_friend(p_user_a UUID, p_user_b UUID) RETURNS BOOLEAN` LANGUAGE SQL STABLE。
4. CREATE TABLE `public.friend_invites`（`id UUID PK`、`inviter_id UUID`、`token TEXT`、`code TEXT`、`expires_at`、`consumed_at`、`consumed_by`）。
5. CREATE FUNCTION `redeem_friend_invite_code` + `redeem_friend_invite_token` SECURITY DEFINER。
6. `patterns` SELECT ポリシーの RLS 更新 — 既存 `user_blocks NOT EXISTS` アーム（Wave E）と新しい visibility-aware アームを結合。
7. `chart_versions` / `comments` / `suggestions` / `suggestion_comments` SELECT ポリシーの RLS 更新 — `patterns.visibility` への JOIN + 同じ `user_blocks` + `is_friend` チェック。
8. is_friend ルックアップ用に `friend_connections(user_a)` + `friend_connections(user_b)` のインデックス。

**新 `FriendRepository`**（commonMain）+ `FriendRemoteOperations` Supabase ポート、`DeviceTokenRepository`（Phase 24.2e）前例をミラー：

- `interface FriendRepository { suspend fun listFriends(): Result<List<Friend>>; suspend fun listPending(): Result<List<PendingRequest>>; suspend fun sendRequest(recipientId): Result<Unit>; suspend fun acceptRequest(connectionId): Result<Unit>; suspend fun rejectRequest(connectionId): Result<Unit>; suspend fun disconnect(connectionId): Result<Unit>; suspend fun createInvite(): Result<FriendInvite>; suspend fun redeemInvite(codeOrToken): Result<Unit>; }`
- `FriendRepositoryImpl(remote, authRepository)`、ネットワーク往復 BEFORE に `RequiresConnectivity` + `SignInRequired` short-circuit（WipeDataRepositoryImpl をミラー）。

**commonTest（+25-30 cases）**：

- `FriendRepositoryImplTest`（10）: offline / unauthenticated short-circuits / 各メソッドのハッピーパス / IOException → Network / 任意 → Unknown / cancellation。
- `IsFriendRpcTest`（5）: mutual accepted = true / pending = false / blocked = false / 存在しない = false / 直接 SQL EXECUTE 経由で sorted-pair 不変条件を検証。
- `FriendInviteRedemptionTest`（8）: valid code happy / expired code → InviteExpired / consumed token → InviteAlreadyUsed / inviter blocked recipient → InviteBlocked / self-redemption → SelfInviteRejected / case-insensitive code matching / token URL parsing / code+token equivalence。
- `FriendConnectionsWipeTest`（4）: 送信行 DELETE / 受信行が blocked に遷移 / 受信 pending 行 DELETE / 既存 user_blocks 行で idempotent。

### Phase 25.2 — Per-UGC visibility ピッカー UI

PatternEditScreen が 4-state visibility ピッカー（Private / Friends / Shared / Public）を取得、Compose では `SegmentedButtonRow`、SwiftUI では `Picker`。NEW パターンの UI レベル初期状態（patternId == null）= `public`；EDIT 時は現在の値でプリフィル。~6 i18n キー × en/ja × CMP/iOS。

コメントと suggestion コンポーザーはピッカーを追加しない（決定 (b) 通り — 親パターンから継承）。

スコープ見積もり：~150 LOC Compose + ~120 LOC SwiftUI + ~50 LOC ViewModel 状態追加 + 可視性ピッカー状態の 8-12 commonTest cases。

### Phase 25.3 — 接続管理 UI

Settings → Privacy → Connections 画面、3 タブレイアウト（Friends / Pending / Invite）。Pending タブは受信（Accept/Reject）と送信（Cancel）の両リクエストを表示。Invite タブは新しいコードを生成 + クリップボードにコピー / Universal Link 用に OS シェアシートを起動。

ViewModel + 状態 + ~10-12 i18n キー。Settings の新行「Connections」が「Delete all my data」と「Delete Account」の間。

スコープ見積もり：~300 LOC Compose + ~250 LOC SwiftUI + ~150 LOC ViewModel + 15-20 commonTest cases。

### Phase 25.4 — 招待フロー（Universal Link + コードリディーム）

ADR-019 Phase 39 Universal Link インフラを `/skeinly/friend/<invite_token>` パスで拡張。招待者がリンクをタップ：

1. OS が Phase 39 Universal Link / App Link 経由で Skeinly アプリにリンクをルート。
2. NavGraph がパスを解析 → トークンを Serializable パラメータとして `FriendInviteConfirmScreen` にルート。
3. 画面が `FriendRepository.redeemInvite()` 経由で `redeem_friend_invite_token(p_token)` を呼ぶ。
4. RPC が検証：トークン存在、期限切れでない、消費されていない、inviter != caller。成功時：`friend_connections` 行を書き込み（state `accepted` — 直接トークンタップ = 暗黙的相互承認、A と B 両方がリンク交換に参加したため第二側確認不要）。
5. UI は「あなたと <inviter 表示名> はフレンドになりました」と「プロフィールを表示」CTA を表示。

コードリディームパスはトークンをミラーするが、アプリ内「コードでフレンドを追加」画面からルートする。

スコープ見積もり：~80 LOC NavGraph + AppRouter Universal Link 解析 + ~150 LOC FriendInviteConfirmScreen（Compose + SwiftUI）+ 8-12 commonTest cases。

### Phase 25.5 — Discovery フィルター + プライバシーポリシー + smoke test

Discovery クエリが visibility フィルターを取得（デフォルト `public` のみ；opt-in トグル「friends-only パターンを表示」）。DiscoveryScreen TopAppBar が `FilterChip` または `Switch` としてトグルを追加（UX 決定は実装に委ねる — Material 3 コンポーネント両方適合；Discovery が既存フィルターで使用しているものを採用）。トグル状態はローカル `Settings` バック preferences でユーザーごとに永続化（サーバー往復不要）。

プライバシーポリシー `<h3>` サブセクションを `friend_connections` データ + visibility セマンティクスに追加。EN + JA ミラー。内容：収集されるデータ（`(user_a, user_b, state, requester_id, timestamps)` のみ — プロフィールデータなし）、データ消去セマンティクス（(g.1) 通り：送信接続 + ユーザー自身の招待は削除、受信 accepted 接続は blocked マーカーに切断、受信 pending リクエストは削除 — 一律保持ではない）、アカウント削除カスケード（`ON DELETE CASCADE` で全参加者行が除去）、GDPR 権利列挙拡張（「Settings → Privacy → Connections からフレンド接続を管理」）。

End-to-end smoke test（closed-beta、staging Supabase プロジェクトに対して）：

1. 2 つのテスターアカウント A と B。
2. A が friends-only パターンを作成。A がそれを見ることを検証；B（まだフレンドでない）は見えないことを検証。
3. A が招待コードを生成；帯域外で B に送信。
4. B が Skeinly を開き、Connections → Add friend by code 経由でコードをリディーム。A の pending リストが更新されることを検証；A が承認。両リストが双方向に更新されることを検証。
5. B が A の friends-only パターンを見える。Discovery トグル ON で表示されること；OFF で非表示されることを検証。
6. A が Connections → Friends → 長押し → Disconnect 経由で切断。B から A の friends-only パターンへのビューが消えることを検証（次回クエリでの RLS 再評価）。
7. コードパスの代わりに Universal Link パスで繰り返し。同一の終了状態を検証。

IARC Q8 回答が次の Play Console 提出で **はい** にフリップ（Phase 40 GA の可能性）。

スコープ見積もり：~60 LOC Discovery フィルター + ~40 LOC トグル永続化 + ~50 LOC プライバシーポリシー HTML × 2 + smoke test runbook。

## 検討された代替案

### Follower-graph モデル（却下）

相互確認なしの Twitter スタイル非対称フォロー。(a) 協議通り却下 — Skeinly の WIP 共有セマンティクスは broadcast ではなく親密、対称モデルがマップしやすい。非対称は「間違った相手と共有してしまった」 footgun を生み、対称モデルがこれを回避する。

### Per-child visibility（chart_versions / comments / suggestions が独自 visibility を持つ）（却下）

(b) 通り — 親パターン visibility への JOIN が一貫したセマンティクス。Per-child visibility は「混乱した状態」表面を生む（例：`public` パターン上の `friends` コメント）。さらに、ユーザー可視メリットなしでスキーマ表面が 3 倍。

### 3 階層 visibility {private, friends, public}（却下）

enum をシンプルにするため既存 `shared` 値を削除。却下 — `shared`（リンクトークン共有）は `friends`（グラフメンバーシップ）とは異なる信頼モデル、既存トークンベース共有フロー（Phase 36 share-link インフラ）が既に `shared` 値に対してデプロイ済み。`shared` を別名にリネームすると downtime リスクのあるデータ migration が必要。4 値 enum が正しい抽象化。

### Friend-of-friend visibility（却下）

(g.2) 通り — Facebook スタイルの推移的フレンドアクセスはプライバシーインシデントを生む文書化された歴史を持つ（ユーザーは自分の disclosure のリーチを過小評価する）。Skeinly のユーザー信頼姿勢は第一級のみ保持。

### Discovery: バッジ付きで常に両方表示（却下）

(f) 通り — 調査したニッターは「見知らぬ人から新しいパターンを browsing」モードと「私のサークルをフォロー」モードの間の明確な分離を一貫して報告。混ぜると全スクロールでコンテキストスイッチコストが生じる。

### Cancel-invite vs delete-invite-row（却下）

招待者が送信 pending リクエストをキャンセルしたとき、行を DELETE する？それとも `cancelled` 状態に遷移する？検討後却下 — キャンセルは transient action；招待者は過去の失敗招待の監査トレイルを必要としない。DELETE がデータモデルをスリムに保つ。監査ニーズが後に surface したら、その時に state 値を追加。

## オープン質問（ADR 後、設計時）

1. **フレンドグラフサイズ制限？** フレンドグラフ・アズ・ブロードキャストチャネル乱用を防ぐためフレンド数にソフトキャップを設けるべき？延期；ベータシグナルでラチェットパターンを示せば再訪。
2. **フレンドリクエスト通知メカニズム**: 受信リクエストに対してプッシュ通知？それともアプリ内バッジのみ？おそらく ADR-017（Phase 24 プッシュ）統合が必要。Phase 25.3 実装に延期；摩擦の低いデフォルト（バッジ、プッシュなし）を採用。
3. **クロスプラットフォーム handle**: 現在フレンドは skeinly アカウント単位。将来の Marketplace / 共有インベントリ機能は「フォロー author」セマンティクスをフレンドシップと別に必要とするかも。延期；Phase 25 の相互フレンドプリミティブを混乱させずに関係を追加可能。
4. **Visibility ダウングレード UX**: A のパターンが `friends` で A が B をフレンドから削除した場合、B のパターンキャッシュビューは次の同期まで残る。alpha では許容；Phase 25.5+ smoke testing で明示的キャッシュ無効化を検討。

## 改訂履歴

- 2026-05-15: 初版（本ドキュメント）。Agent team 協議経て承認；CLAUDE.md Phase 25 計画エントリの設計質問リストを置き換え。
