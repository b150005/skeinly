# ADR-023: Phase 27 — データ消去（アカウント保持）

> 英語版が正本: [docs/en/adr/023-phase-27-data-wipe.md](../../en/adr/023-phase-27-data-wipe.md)

## ステータス

承認済み（2026-05-14）。Phase 27 は alpha ローンチの HARD-GATE — 4 つの実装サブスライス（27.1〜27.4）すべてが TestFlight / Play Internal Testing への招待発行前に出荷される必要がある。

## 背景

Play Console「データセーフティ」フォーム（vendor-setup A0d-6）の任意サブ質問：

> アカウントの削除を必要とすることなく、一部またはすべてのデータの削除をリクエストする方法をユーザーに提供していますか？

Skeinly の現在の回答は **いいえ**。現状で出荷している破壊的プリミティブは 2 つ：

1. **アカウント完全削除** — `delete_own_account()` SECURITY DEFINER RPC（ADR-005、migration 007）が `auth.users` 行を削除、下流はすべて CASCADE で消滅。サインイン identity が完全に失われる。
2. **項目単位の CRUD 削除** — 各テーブルに認証済みユーザーの DELETE policy あり。1 パターン / 1 プロジェクト / 1 コメント単位で手動削除。一括コンテンツリセットは UX レベルで実現不可。

どちらも「データセーフティ」フォームが問うているプライバシー制御セマンティクスに合致しない。Phase 27 は第 3 のプリミティブを導入する — **アカウントを保持したままの一括コンテンツ削除** — これにより「データセーフティ」回答が **はい** にフリップする。

コンプライアンス上の動機を超えて、「データを全て削除するがアカウントは保持」は確立されたプライバシー制御カテゴリ。成熟した消費者向けアプリ（GitHub、Google、Discord）はすべて、アカウントクローズと区別された形で提供している。ユーザー信頼のレベルでは意味のある区別 — 家族にアプリを共有したのでやり直したい、ベータに使い捨てコンテンツで参加したのでクリーンにしたい、パターン命名を最初からやり直したい、といったケースで Pro 権利・サインイン identity・表示名を失わずに白紙キャンバスを得たい場合がある。

## 第一の決定 — 新 ADR（ADR-005 への追記ではなく）

本ドキュメントは **ADR-023**、新規 ADR。ADR-005 は変更しない。

理由（agent team 協議、2026-05-14）：

- **architect**: データ消去はアカウント削除と構造的に別物 — 別 RPC 名、別保持マトリクス、別 web フォールバックスラッグ、別 UX 確認コピー。両方を ADR-005 に押し込むと CASCADE 駆動のアカウント purge の物語が濁る。
- **product-manager**: 将来の読み手は「データ消去」「データ削除」で検索する — 専用 ADR アドレスの方が埋もれた Amendment ブロックより発見性が高い。
- **technical-writer**: ADR-005 は 35 行で意図的に狭い。wipe 仕様を追記すると 3 倍に膨らみ、文書のスコープが中盤で変質する。におう。
- **implementer**: ADR-023 は SECURITY DEFINER パターンで ADR-005 を相互参照。前例の再導出は不要。2 つの ADR がきれいに並ぶ。

**結論**: ADR-023 として新規 ADR。ADR-005 は据え置き。

## エージェントチーム協議

### product-manager — UX 明確性

ユーザーが「データ消去」と「アカウント削除」を確信を持って選べる必要がある。両者は混同されがち。Settings → プライバシーで隣接 2 行を提示：

- **「すべてのデータを削除」** — アカウント・サインイン・Pro 状態を保持、コンテンツをクリア。
- **「アカウントを削除」** — サインインを含めて全て破壊。

各行のタップで、保持されるもの・削除されるものを網羅列挙するモーダルが開く。コピーは GitHub の「すべてのリポジトリを削除」vs「アカウントを削除」モデルを参考 — 明示的列挙、曖昧な「データをリセットします」ではなく。Phase 39 でテスター信号が「やはり混同される」を示せば、モーダル冒頭に比較表を追加する。消去完了後はパターンライブラリ（空状態）に着地。サインアウト画面は出ない — それはアカウント削除パスの挙動。

**結論**: 明確に区別された 2 つの Settings 行。モーダルコピーは保持マトリクスをユーザー向け言語で列挙。消去後はパターンライブラリ（空状態）に着地。

### architect — RPC 形・トランザクション・FK 順

消去 RPC は：

1. 単一トランザクション内で実行 — 部分消去は構造的に不可能（すべて消えるか、何も消えないか）。
2. リトライ下で冪等 — RPC が短時間に 2 回呼ばれた場合（ネットワークリトライ、ダブルタップ）、2 回目は既に空の状態を観測してエラーなく成功を返す。
3. 子孫優先で DELETE — 子テーブル先、親テーブル後。Postgres のトランザクション内 FK 解決はほとんどの FK が `ON DELETE CASCADE` なので自動だが、監査読みやすさのため明示 DELETE を発行 + 非 CASCADE FK の落とし穴回避。
4. 末尾に `activities` への audit 行 1 件 INSERT（決定 (e) 参照） — 他のすべての `activities` 行を同じトランザクションで消した直後でも「ユーザーが 2026-05-14T12:34:56Z に消去」の audit トレイルが残る。

並行呼び出しハザード: RPC 実行中に 2 回目呼び出しが部分状態を見る可能性。`auth.users WHERE id = auth.uid()` への行ロック（`SELECT ... FOR UPDATE`）を RPC body 冒頭で取得し、同じユーザーからの並行呼び出しを直列化。2 回目呼び出しは 1 回目のコミットまでブロック、その後空状態に対して実行（コンテンツテーブルでは冪等 no-op、新 audit 行のみ INSERT）。

**結論**: 単一トランザクションの SECURITY DEFINER `public.wipe_own_data()`。body 冒頭で `auth.users WHERE id = auth.uid()` に行ロック。子孫優先で DELETE。末尾に audit 行 INSERT。戻り値 `void`。

### security-reviewer — 認証態勢・web フォールバック CSRF・冪等性

**アプリ内パス**は認証済み Supabase セッション下で動作。RPC は `auth.uid()` のみを信頼、ユーザー制御パラメータは SQL に届かない。`delete_own_account` 前例と合致。

**再認証**: パスワード再入力 / MFA チャレンジ / 生体認証を `wipe_own_data` 発火前に要求すべきか。立場：

- **消去は破壊的だがクラウド側復元コストはゼロのみ可能（我々は復元を運用していない）**。ローカル SQLDelight キャッシュは RPC 呼び出し後も残るが、これは「偶然」 — 次回 sync-down で空サーバ状態がローカルキャッシュに伝播し、ローカルも空になる。
- **アカウント削除と同じ破壊的サーフェス** — アカウント削除は現状再認証を要求しない（ADR-005 パスは認証済みクライアントからの直接 RPC）。ここでセキュリティ床を下げてはいないが、強い床を主張もできない。
- **MFA ゲート（Phase 26）**: MFA 登録（Phase 26.5）が配線され次第、ユーザーが TOTP factor を登録していれば消去パスにも MFA チャレンジステップを含めるべき。Phase 26 着地までは、確認ステップはテキストタイピングのみ（アカウント削除 UX に合わせる）。
- **生体ゲート（Phase 26.6）**: 同様。`LocalAuthentication` / `BiometricPrompt` が「センシティブアクション」用に配線され次第、`wipe_own_data` も対象リストに乗る（アカウント削除・MFA 解除・将来のマーケットプレイス支払い手段変更と並ぶ）。

Phase 27 はフレーズタイピング確認のみで出荷。Phase 26 が `wipe_own_data` と `delete_own_account` の両方に MFA + 生体ゲートを 1 つのスライスで後付け。

**Web フォールバック CSRF**: `/skeinly/data-deletion/` ページ（Phase 27.3）は既存の `/skeinly/account-deletion/` ページアーキテクチャをミラー — web フォームが Supabase Auth で認証（email/password または Phase 26 後の OAuth）、Edge Function `wipe-my-data-web`（`verify_jwt = true`）が呼ばれて service-role-with-impersonated-user パターンで `wipe_own_data` RPC を起動。Supabase Auth セッション cookie は SameSite で CSRF 保護、Edge Function は加えて POST の `Authorization: Bearer` ヘッダにアクセストークンを要求（既存 account-deletion-web パターンに合致）。新規攻撃サーフェスなし。

**結論**: Phase 27 では新規再認証要件なし（アカウント削除床と同等）。Phase 26 で両方の破壊的 RPC に MFA + 生体ゲートを 1 パスで後付け。Web フォールバックは account-deletion-web アーキテクチャ再利用（`verify_jwt = true` Edge Function + Bearer token POST）。

### ui-ux-designer — 2 段階確認・消去後空状態 UX・コピー

**Step 1 — 説明モーダル**: Settings 行タップで開く。レイアウトはアカウント削除モーダルの 2 列「保持 | 削除」表に合わせる（具体的なユーザー向け用語、テーブル名ではない）。下部 CTA: 「続行」（赤・破壊的）。キャンセル: 「データを保持」（左上）。

**Step 2 — フレーズタイピング確認**: 固定フレーズ `データを削除`（JA）/ `delete my data`（EN）をテキストフィールドに入力。マッチングは case-insensitive + trim。「すべてのデータを削除」送信ボタンはフレーズ一致まで disabled。表示名タイピングをフレーズ機構に使わない理由: MFA 有効アカウントでは表示名がユーザー名と同じことがあり、「意図的アクション」チェックが弱まる。固定フレーズが明確。

**消去後 UX**: RPC 成功で、パターンライブラリ（下部タブナビのルートエントリ）に遷移。空状態にはデフォルト「最初のパターンを作成」CTA。dismiss 可能バナーが「データを削除しました。アカウントは引き続きご利用いただけます。」と表示 — 8 秒で自動 dismiss またはタップで。

**コピー明確化**: 説明モーダル「保持」列の名称: サインイン identity、表示名、Pro メンバーシップ（該当時）、アバター、ブロック済みユーザーリスト（Phase 27 で持ち越し — 保持マトリクス参照）。「削除」列の名称: パターン、プロジェクト、編み図、コメント、提案、写真、プッシュ通知設定。テーブル名は使わず、既存 Settings コピーに合致するユーザー向けラベルを使用。

**結論**: 2 段階モーダル — ユーザー向け言語で保持 vs 削除を網羅列挙、固定フレーズ確認タイピング。消去後はパターンライブラリへ自動 dismiss バナー付きでルーティング。

### implementer — RPC body + Edge Function パターン

RPC body は `auth.uid()` にスコープした DELETE 文の上から下へのリスト。`ON DELETE CASCADE` で行が消える場合でも、明示 DELETE を発行する理由は (a) migration ファイルレベルでの監査明確性 — 読み手が CASCADE 連鎖を頭の中でトレースしなくて良い、(b) 将来の migration が下流 CASCADE を SET NULL に変える場合の防御一貫性。

Web フォールバック Edge Function `wipe-my-data-web` は `delete-my-account-web`（既存の account-deletion ページ backing Edge Function、`supabase/functions/delete-my-account-web/`）をミラー。POST の Bearer JWT でユーザー認証、ユーザーセッション下で `wipe_own_data()` RPC を呼び出す（service-role bypass ではなく — RLS で同じ `auth.uid()` セマンティクスを強制したい）、成功で 200 / トークン欠落・不正で 401 / RPC エラーで 500 を返す。

**結論**: 単一トランザクション内でテーブルごとに明示 DELETE。Web Edge Function は `delete-my-account-web` アーキテクチャをミラー。RPC は `void` を返す。クライアントはエラー不在を成功として扱う。

---

## 決定事項

### (a) 保持マトリクス

`public.*` テーブル完全インベントリ（`supabase/migrations/001`–`032` で検証済み）。すべての本番テーブルに対し、消去 vs 保持の明示的選択。

**消去** = `DELETE FROM <table> WHERE <user-scope column> = auth.uid()` を `wipe_own_data` トランザクション内で発行。

**保持** = 行は触らない。

**Cascade** = 直接 DELETE せず、消去された親からの FK cascade で除去。

**匿名化** = 行は残るが identity-hint 列をリセット。

| テーブル | アクション | 所有者列 | 理由 |
|---|---|---|---|
| `auth.users` | **保持** | `id` (== `auth.uid()`) | 定義上保持 — wipe-not-delete の区別がこの機能の本質。 |
| `public.profiles` | **保持**（display_name リセットは任意、注記参照） | `id` | サインイン display_name + avatar_url + bio を保持。表示名・アバターは消去後も保持されるユーザー identity の一部（「Pro ユーザーは消去後も PR スレッドで認識されたい」と合致）。 |
| `public.patterns` | **消去** | `owner_id` | コアコンテンツ。CASCADE で `chart_documents` / `chart_versions` / `chart_variations` / `progress`（`projects` 経由）/ `project_segments`（`projects` 経由）/ `shares` / `comments` / `suggestions` / `suggestion_comments` を除去。 |
| `public.projects` | **消去** | `owner_id` | コアコンテンツ。CASCADE で `progress` と `project_segments` を除去。 |
| `public.progress` | **Cascade** | (`projects.id` 経由) | `projects` からの FK は `ON DELETE CASCADE`。`projects` 消去で除去。 |
| `public.project_segments` | **Cascade** | `owner_id`（直接も） | `projects.id` からの FK は CASCADE。直接 `owner_id` も存在 — RPC は明示 DELETE を先に発行。 |
| `public.chart_documents` | **Cascade** | `owner_id`（直接も） | `patterns` からの FK で cascade。直接 `owner_id` も存在 — RPC は明示 DELETE を先に発行。 |
| `public.chart_versions`（旧 `chart_revisions`） | **消去** | `owner_id` | append-only な user authored revision 履歴。ユーザーがクリーンスレートを要求 — 履歴も消える。`author_id` は `ON DELETE SET NULL` で、他人のパターン（Phase 38 merge パス）に書いた revision は匿名化されるが除去はされない。 |
| `public.chart_variations`（旧 `chart_branches`） | **消去** | `owner_id` | ユーザーが authored したブランチ。patterns 経由でも cascade — 直接 DELETE が監査明確パス。 |
| `public.shares` | **消去** | `from_user_id` | ユーザーが authored したアウトゴーイング share。インカミング share（`to_user_id = auth.uid()`）も消去 — ユーザーは share 関係から対称に「離脱」。 |
| `public.comments` | **消去** | `author_id` | ユーザーが authored したコメント全て、場所を問わず。他人のパターンに残したコメントも消去。 |
| `public.activities` | **消去 + audit 行 1 件 INSERT** | `user_id` | 過去の activity 行はすべて消える。RPC の末尾文が `type = 'data_wiped'` の新 enum 値で行 1 件を INSERT（決定 (e) 参照）。 |
| `public.suggestions`（旧 `pull_requests`） | **消去（source 側）/ 匿名化（target 側 FK SET NULL 経由）** | `author_id`（SET NULL FK） | `source_pattern_id.owner_id = auth.uid()` 行を消去。他人のパターンに対してユーザーが OPEN した提案: `author_id` が SET NULL なので row-source レベルでの消去で自然に匿名化。明示 RPC 挙動: ユーザーが source AND/OR target を所有する提案を削除、第三者の提案は残し `author_id` のみクリア。 |
| `public.suggestion_comments`（旧 `pull_request_comments`） | **消去** | `author_id`（SET NULL FK） | 提案スレッドへの user authored コメント。`comments` と同じパターン。 |
| `public.subscriptions` | **保持** | `user_id` | ユーザーは Pro に課金済み。消去は CONTENT データに関するもの。subscription state は取引情報。消去をキャンセルとして扱うのはユーザーを驚かせる（キャンセルは依頼していない）+ 消去後に paywall サーフェスを強制することになり矛盾。Pro 権利は持続。 |
| `public.device_tokens` | **消去** | `user_id` | プッシュ通知ルーティング。フレッシュスタートセマンティクス — プッシュ登録は次回起動時に `PushTokenRegistrar`（ADR-017 §3.5、Phase 24.2e）で再生成。消去 + 次回フォアグラウンド時の自動再登録 = 将来のプッシュ機能を失わないクリーン状態。 |
| `public.feedback` | **匿名化（FK SET NULL）** | `user_id`（SET NULL FK） | Phase F3 フィードバックログは append-only で運用者向け。`user_id` は設計上 `ON DELETE SET NULL`（migration 018、ADR-021 §D1 前例）。消去せず匿名化: 運用者の履歴は保持、ユーザー identity は切断。RPC は feedback への DELETE を発行しない、代わりに `UPDATE feedback SET user_id = NULL WHERE user_id = auth.uid()` を発行。 |
| `public.ugc_reports` | **FK 経由で匿名化** | `reporter_id`（CASCADE FK！） | Migration 031 では `reporter_id` を `ON DELETE CASCADE` として配線（アカウント削除セマンティクスを前提とした reporter テーブルだから）。消去では運用者の audit トレイルを保持しつつ reporter を匿名化したい — feedback と同じ理由。`UPDATE ugc_reports SET reporter_id = ...` は NOT NULL 制約に違反する。**決定**: Phase 27.1 の migration 033 で `ugc_reports.reporter_id` を nullable + `ON DELETE SET NULL` に変更、運用者の調査スレッドを消去で保持。同じ migration が wipe-RPC パスに明示 UPDATE を担う。 |
| `public.user_blocks` | **消去（両方の脚）** | `blocker_id` AND `blocked_id` | Blocker 側: ユーザーが自分のコンテンツ + 関係状態を purge している、ブロックリストも消える。Blocked 側: 他ユーザーがこの消去中ユーザーをブロックしていた行は除去されない（他人の選択は保持）。RPC は `DELETE FROM user_blocks WHERE blocker_id = auth.uid()` のみ発行。 |
| `public.friend_connections` | **消去（非対称、ADR-024 §(g.1)）** | `requester_id` / `user_a` / `user_b` | Phase 25.1 で追加。消去挙動は migration 037（Phase 25.1 フォローアップ）に先送りされていた。ADR-024 §(g.1)「outbound 消去・inbound 保持」に従う: **outbound**（`requester_id = auth.uid()`、state 不問）→ `DELETE`; **inbound accepted**（参加者かつ requester でない、`state='accepted'`）→ `UPDATE state='blocked', accepted_at=NULL`（元の requester に「接続が切断された」シグナルが clean に伝わる）; **inbound pending**（参加者かつ requester でない、`state='pending'`）→ `DELETE`; **inbound blocked** → 不介入（既に terminal）。意図的な非対称性: 消去中ユーザー自身が開始した接続はシグナルなしで消える、他者が開始した接続には「切断」マーカーが残る。 |
| `public.friend_invites` | **消去（outbound のみ）** | `inviter_id` | Phase 25.1 で追加、migration 037。`DELETE FROM friend_invites WHERE inviter_id = auth.uid()` — ユーザー自身が作成した招待は outbound アーティファクト（`user_blocks` / `shares` の outbound posture をミラー）。`consumed_by = auth.uid()` の行（消去中ユーザーが redeem した、他者が作成した招待）は**不介入**: `friend_invites_consumed_pair` CHECK が `consumed_at`/`consumed_by` を結合するため `consumed_by` 単独 NULL 化は不正、両方 NULL 化は単回使用招待を再 redeem 可能に復活させる（セキュリティ後退）。招待は inviter の private アーティファクト（RLS で読み取りは inviter にスコープ）なので、`user_blocks` の inbound 保持の論拠と一致。 |
| `public.symbol_packs` | **保持** | （所有者列なし） | グローバルカタログ。ユーザー所有ではない。 |
| `public.symbol_pack_locales` | **保持** | （所有者列なし） | グローバルカタログ。ユーザー所有ではない。 |
| `public.user_symbol_pack_state` | **消去** | `user_id` | per-user「pack X の version V を DL 済み」ミラー。フレッシュスタート: 次回 paywall 操作時に再 DL。ローカルキャッシュポインタの Pro 無料テスター pack は消えるが、グローバル `symbol_packs` 行自体は残る（上で保持）。 |
| `public.app_config` | **保持** | （所有者列なし） | グローバル maintenance-mode + feature-flag config。ユーザー所有ではない。 |

ストレージバケット：

| バケット | アクション | 理由 |
|---|---|---|
| `chart_images`（private） | **best-effort 消去** | `{owner_id}/` プレフィックス下を list → 一括削除。ストレージ呼び出し失敗は RPC を中断しない（孤立ファイルは認証なしではアクセス不可 — ADR-005 §Consequences と同じ態勢）。 |
| `avatars`（public） | **保持** | アバターは identity、identity は保持。 |
| `symbol-packs`（private） | **保持** | グローバルカタログペイロード。ユーザー所有ではない。 |

**audit `activities` 行**: 上記すべての DELETE/UPDATE 完了後、RPC が INSERT:

```sql
INSERT INTO public.activities (user_id, type, target_type, target_id, metadata)
VALUES (auth.uid(), 'data_wiped', 'project', auth.uid(), NULL);
```

（`target_type` = 'project' は既存列の NOT NULL チェックを満たすプレースホルダ; `target_id = auth.uid()` は自己参照規約。新 `data_wiped` enum 値がセマンティクスを担う; 下流 UI はこの type の target 参照を非表示にする。代替案 — `activities.target_type` 制約を NULL 許容に緩める — はすべての下流コンシューマを移行する必要があるため却下。）

### (b) RPC 形

```sql
CREATE OR REPLACE FUNCTION public.wipe_own_data()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_uid UUID := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'wipe_own_data requires an authenticated session';
    END IF;

    -- 同じユーザーからの並行呼び出しを直列化。
    PERFORM 1 FROM auth.users WHERE id = v_uid FOR UPDATE;

    -- 子孫優先 DELETE 順。各文は CASCADE 安全だが、明示 DELETE は監査明確性のため。
    DELETE FROM public.suggestion_comments WHERE author_id = v_uid;
    DELETE FROM public.suggestions WHERE source_pattern_id IN (
        SELECT id FROM public.patterns WHERE owner_id = v_uid
    ) OR target_pattern_id IN (
        SELECT id FROM public.patterns WHERE owner_id = v_uid
    );
    DELETE FROM public.comments WHERE author_id = v_uid;
    DELETE FROM public.shares WHERE from_user_id = v_uid OR to_user_id = v_uid;
    DELETE FROM public.project_segments WHERE owner_id = v_uid;
    DELETE FROM public.progress WHERE project_id IN (
        SELECT id FROM public.projects WHERE owner_id = v_uid
    );
    DELETE FROM public.projects WHERE owner_id = v_uid;
    DELETE FROM public.chart_versions WHERE owner_id = v_uid;
    DELETE FROM public.chart_variations WHERE owner_id = v_uid;
    DELETE FROM public.chart_documents WHERE owner_id = v_uid;
    DELETE FROM public.patterns WHERE owner_id = v_uid;
    DELETE FROM public.activities WHERE user_id = v_uid;
    DELETE FROM public.device_tokens WHERE user_id = v_uid;
    DELETE FROM public.user_symbol_pack_state WHERE user_id = v_uid;
    DELETE FROM public.user_blocks WHERE blocker_id = v_uid;

    -- 行は保持し匿名化する。
    UPDATE public.feedback SET user_id = NULL WHERE user_id = v_uid;
    UPDATE public.ugc_reports SET reporter_id = NULL WHERE reporter_id = v_uid;

    -- 末尾の audit 行。
    INSERT INTO public.activities (user_id, type, target_type, target_id, metadata)
    VALUES (v_uid, 'data_wiped', 'project', v_uid, NULL);
END;
$$;

REVOKE ALL ON FUNCTION public.wipe_own_data() FROM public;
GRANT EXECUTE ON FUNCTION public.wipe_own_data() TO authenticated;
```

`SECURITY DEFINER` + `search_path = ''` は `delete_own_account` と `upsert_subscription_from_webhook` の確立された規約に合致。`LANGUAGE plpgsql`（vs `sql`）の理由: `BEGIN ... END` ブロック、`v_uid` 変数、`PERFORM ... FOR UPDATE` 行ロックが必要。

ステートメントリスト全体は単一 implicit transaction（RPC body）で実行。いずれかのステートメントが失敗すれば body 全体がロールバック — 部分消去は構造的に不可能。

### (c) UI ゲーティング

**Settings エントリ**: 既存「プライバシー」セクション下。行ラベル: 「すべてのデータを削除」/ "Delete all my data"。「アカウントを削除」/ "Delete account" に隣接、その**上**に配置（破壊度が低い方を先に列挙する Material + HIG 規約）。

**Step 1 — 保持マトリクスモーダル**: Compose `AlertDialog`（小型端末では `Modifier.fillMaxWidth(0.95f)` でフルスクリーン）+ SwiftUI `.sheet` パリティ。2 列「保持 | 削除」のユーザー向け用語列挙。下部 CTA: 「続行」（赤・破壊的）+ 「データを保持」（セカンダリ）。

**Step 2 — フレーズタイピング**: ナビスタックに push される専用画面。単一テキストフィールド、ヘルパーテキスト「確認のため『データを削除』と入力してください」、送信ボタンは trim + lowercase 入力 == 固定フレーズで初めて有効化。EN フレーズ: `delete my data`。2 言語マッチング: モーダル開時のアクティブロケールがどちらのフレーズを要求するかを選択、フロー途中のロケール変更はサポートしない（closed-beta スコープ）。

**送信ハンドラ**: ViewModel `wipeDataInternal` が `WipeDataRepository.wipe()` → Supabase `postgrest.rpc("wipe_own_data")` を呼ぶ。成功で `WipeDataNavEvent.WipeCompleted` を emit。エラーで `WipeDataNavEvent.WipeFailed(throwable.message)` を emit + snack/alert 表示。

**消去後ナビゲーション**: nav controller がルートまで pop、パターンライブラリ（ルートタブ）に deep-link。`PatternLibraryViewModel` のバナー状態が `wipeBannerVisible = true` に 8 秒間フリップ。

### (d) Pro 権利のエッジケース

`public.subscriptions` は保持列。

検討した対抗論: 他のコンテンツと一緒に subscription 行も消すのが「より徹底」で、ユーザーの「フレッシュスタート」メンタルモデルに合致。却下理由：

1. ユーザーはキャンセルを依頼していない。キャンセルは RevenueCat 管理 paywall パスでの独自破壊的アクション。
2. 消去でキャンセルすると消去後に paywall サーフェスを強制 — 「あなたはあなたのまま、ただコンテンツがない」フィードバックと矛盾。
3. RevenueCat はユーザーが App Store / Play サブスク管理でキャンセルするまでプラットフォーム側で課金を続ける。協調プラットフォーム側キャンセルなしでサーバ側 `subscriptions` 行を破壊すると「課金されているが Pro なし」ミスマッチが発生。

Pro 機能（Pro symbol packs、将来の Pro-only Discovery フィルタ等）は消去後も機能する。

**結論**: `subscriptions` 行は保持。モーダルコピー「保持」列に「Pro メンバーシップ（該当時）」として明記。

### (e) audit ログ規律 — 新 `activities.type` enum 値

既存の `public.activities.type` CHECK 制約（migration 004）：

```sql
CHECK (type IN ('shared', 'commented', 'forked', 'completed', 'started'))
```

Phase 27.1 migration が `'data_wiped'` を追加：

```sql
ALTER TABLE public.activities DROP CONSTRAINT activities_type_check;
ALTER TABLE public.activities ADD CONSTRAINT activities_type_check
    CHECK (type IN ('shared', 'commented', 'forked', 'completed', 'started', 'data_wiped'));
```

audit 行の `target_type = 'project'` と `target_id = auth.uid()` は NOT NULL を満たすセンチネル — `activities` のコンシューマ（Phase 36.5 Activity Feed）は `type = 'data_wiped'` 時に `target_*` 列を無視、「<timestamp> にすべてのデータを削除しました」の自己完結エントリとしてレンダリング。

制約拡張 migration は前方互換（既存行も有効のまま）。逆戻しには制約フリップ + 任意の `data_wiped` 行の DELETE の両方が必要 — migration のコメントブロックで明記。

## サブスライス計画

各サブスライスは独立して出荷可能。完全 wave が alpha テスター招待をゲート。

### Phase 27.1 — RPC + migration + commonTest

**Migration 033**（`033_wipe_own_data.sql`）:

1. `activities.type` CHECK を拡張して `'data_wiped'` を含める。
2. `ugc_reports.reporter_id` を nullable + `ON DELETE SET NULL` に変更（保持マトリクスの決定; 現状は NOT NULL + CASCADE）。
3. `public.wipe_own_data()` SECURITY DEFINER 関数を作成（決定 (b) の body）。
4. `REVOKE ALL ... FROM public; GRANT EXECUTE ... TO authenticated`。

**新 `WipeDataRepository`** （`shared/src/commonMain/.../data/wipe/`）:

- `interface WipeDataRepository { suspend fun wipe(): Result<Unit> }`
- `WipeDataRepositoryImpl(supabaseClient)` が `postgrest.rpc("wipe_own_data")` を呼ぶ、`PostgrestException` を `Result.failure` にマップ。

**commonTest（+15 ケース）**:

- `WipeDataRepositoryImplTest`（9）: オフライン → `NetworkUnavailable` 失敗; 未認証 → `NotAuthenticated` 失敗; ハッピーパス → 成功; 一過性 PostgrestException → マップ済みメッセージ失敗; キャンセル伝播; ダブルコール冪等性（`wipe()` を 2 回呼んで両方成功）; 2 並行コルーチンでの並行呼び出し; ロケール独立（RPC body はロケールフリー）。
- `WipeDataViewModelTest`（6）: 初期状態、フレーズタイピングマッチで submit が有効化、submit が repository を発火、成功で `WipeCompleted` nav イベント emit、失敗で `WipeFailed` をメッセージ付きで emit、冪等性ガード（submit ダブルタップで 1 回のみ発火）。

Deno test なし（27.1 で Edge Function なし）。

### Phase 27.2 — Compose + SwiftUI + ViewModel + i18n

**`WipeDataViewModel`**（commonMain）: ステートマシン — `ModalVisible` → `PhraseEntryVisible` → `Submitting` → `Done` / `Error`。リポジトリの `wipe` 呼び出しを lambda-seam DI で（Phase 24.2c-1 / Phase 39.5 `BugReportPreviewViewModel` 前例に合致）。

**Compose**:

- `SettingsScreen.kt` のプライバシーセクションに「すべてのデータを削除」行を追加。
- `WipeDataExplanationDialog.kt` — 保持マトリクスを 2 列レイアウトで、「続行」+「データを保持」CTA。
- `WipeDataConfirmPhraseScreen.kt` — フレーズタイピング画面、送信ボタンゲート。

**SwiftUI** ミラー:

- `SettingsScreen.swift` 行追加。
- `WipeDataExplanationSheet.swift` `.sheet` プレゼンテーション。
- `WipeDataConfirmPhraseView.swift`。

Koin: `RepositoryModule` に `WipeDataRepository`、`ViewModelModule` に `WipeDataViewModel`。

**i18n（+13 keys × 4 surfaces = 52 strings）**: en + ja × CMP + iOS xcstrings。完全リストは英語版 ADR 参照。JA 表現は逐語翻訳より自然な日本語を優先（`保持されるもの` / `削除されるもの`、`データを削除`）。

### Phase 27.3 — Web フォールバックページ + Edge Function

**Web ページ**: `docs/public/data-deletion/index.html`（EN）+ `docs/public/ja/data-deletion/index.html`（JA ミラー）。`docs/public/account-deletion/` のアーキテクチャミラー。レイアウト:

1. ヘッダー: 「Skeinly のデータをすべて削除」/ "Delete all your Skeinly data"。
2. 区別ボックス: `/skeinly/account-deletion/` への相互リンク、明示的に「アカウント自体を削除したい場合はこちらを使用」。
3. 平易な言葉での保持マトリクス。
4. サインインフォーム（Supabase Auth email + password; Phase 26 後の OAuth）。
5. フレーズタイピング確認（アプリ内 Step 2 と一致）。
6. 送信 → `wipe-my-data-web` Edge Function に `Authorization: Bearer <access_token>` で POST。
7. 成功メッセージ + App Store / Play リスティングへの再インストール用相互リンク。

スラッグ **`data-deletion`** を `data-wipe` / `clear-data` / `delete-data` / `wipe-data` より選んだ理由:

- 平易な命名 — 「deletion」は技術非対称ユーザーにとって「wipe」より認識しやすい。
- `account-deletion` とのパラレリズム（同じ動詞語幹、異なる修飾子）。
- 検索エンジン発見性 — ユーザーがアプリにアクセスできないときデスクトップブラウザで打つ「delete my data」クエリ。

**Edge Function** `supabase/functions/wipe-my-data-web/`（`verify_jwt = true`）:

- `index.ts` — POST ハンドラが Bearer トークンを検証、ユーザーセッション下で authed Supabase クライアントを作成、`wipe_own_data()` RPC を呼ぶ、200 / 401 / 500 を返す。
- `delete-my-account-web` アーキテクチャを正確にミラー。新規サードパーティ依存なし。Deno ~80 LOC + tests ~50 LOC。
- ~6 Deno tests: Bearer 欠落 → 401; 不正 Bearer → 401; ハッピーパス → 200; RPC エラー → 500（envelope `{ ok: false, message }`）; ダブルコール冪等性 → 200 / 200; non-POST → 405。

### Phase 27.4 — プライバシーポリシー + smoke test

**プライバシーポリシー `<h3>` サブセクション**（`docs/public/privacy-policy/index.html` EN + JA ミラー）。既存「アカウント削除」サブセクションに隣接配置。コンテンツ:

- 本製品における「データ削除（アカウント保持）」の意味。
- 平易な言葉での保持マトリクス（アプリ内モーダルコピーのミラー）。
- 影響を受けるストレージ媒体（Supabase Postgres、Supabase Storage `chart_images` バケット — best-effort）。
- audit トレイル: 運用者監査のため `activities` 行 1 件保持、この行はユーザーには非表示。
- 復元性: ゼロ。RPC コミット後データは消失。
- 呼び出し方法: アプリ内 Settings → プライバシー →「すべてのデータを削除」、または `/skeinly/data-deletion/` web ページ。

**Smoke test**（staging Supabase project に対する closed-beta）:

1. コンテンツ付きテストアカウントでサインイン（≥ 3 patterns + 2 projects + 5 comments + `assign-customer-offering` 経由の Pro grant）。
2. Settings → すべてのデータを削除 → 続行 → フレーズ入力 → 送信。
3. 検証: Pattern Library 空; バナー 8 秒表示; Settings に Pro チップ残存; 次回フォアグラウンドでプッシュ通知到着（= `device_tokens` クリーン再登録）; ユーザーメニューの表示名変わらず。
4. 再サインインサイクル: ログアウト、再ログイン、状態が step 3 と一致を確認。
5. Web フォールバックパリティ: 同アカウントで `/skeinly/data-deletion/` 経由ログイン、同一最終状態を確認。
6. ダブルタブ冪等性: 2 デバイスで Settings を開き両方で送信 — 2 回目は成功（冪等）、エラートースト無し。

## 検討した代替案

英語版 ADR §「Alternatives considered」参照。要約: soft-delete with restore window（RLS 複雑化で却下）、opt-in per-category UI（all-or-nothing で十分のため却下）、Pro 権利非保持（decision (d) で却下）、Beta-only ゲート（Data Safety 開示要件で却下）。

## プライバシーへの影響

- **消去は復元不能**。`wipe_own_data` コミット後にクラウド側復元は存在しない。ローカル SQLDelight キャッシュは次回 sync まで残るが、その時点で空サーバ状態に収束。アプリ内モーダルコピー + プライバシーポリシーで明記。
- **Sentry イベント履歴（Phase 39.2 F1）** — Sentry は `user.id` キーで crash イベントを保持。Phase 27 は Sentry の user-purge API を呼ばない。Sentry の保持ポリシー（free tier で 90 日）が最終的削除を処理。ユーザーが Sentry purge を望む場合は既存のアカウント削除パスが正しいプリミティブ。
- **PostHog イベント履歴（Phase 39.3 F2）** — Sentry と同態勢。`distinct_id` 紐付けの PostHog Person Profile は消去で明示的に purge されない。ユーザーリサーチで必要性が surface すれば Phase 27+ で `posthog.deletePersonProfile` を呼ぶことが可能。
- **Storage `chart_images` バケット** — best-effort 消去（保持マトリクス参照）。削除失敗時は孤立ファイルが認証なしではアクセス不可（ADR-005 と同じ態勢）。closed beta では許容可能。

## セキュリティへの影響

- **認証**: Phase 27 は `delete_own_account` と同等で出荷 — 認証済みセッションが唯一のゲート。再認証 + MFA + 生体は Phase 26 で後付け。
- **CSRF（web パス）**: Supabase Auth の SameSite cookie + Edge Function の `Authorization: Bearer` POST 要件で処理。新規サーフェスなし。
- **リトライ下の冪等性**: `auth.users WHERE id = auth.uid()` の行ロックで並行呼び出しを直列化。ネットワークリトライ・ダブルタップ・twin-tab で安全。
- **ユーザー制御パラメータなし**: RPC body は `auth.uid()` のみ使用。インジェクションサーフェスなし。
- **運用者側**: service-role admin がユーザーの代理で `wipe_own_data` を発行することは構造的に不可能 — RPC はパラメータからではなく JWT から `auth.uid()` を読む。impersonation パスは Supabase Dashboard「Impersonate user」のみで監査可能。

## i18n + commonTest サマリ

- **i18n**: 13 keys × 4 surfaces（en CMP `string_resources.xml`、ja CMP `string_resources.xml`、en iOS `Localizable.xcstrings`、ja iOS `Localizable.xcstrings`）= 52 strings。`verifyI18nKeys` パリティゲートが漏れを catch。
- **commonTest**: +15 ケース（9 repository + 6 ViewModel）。Phase 27 では iOS XCUITest 追加なし（新画面は commonTest + 手動 smoke でカバー; 退行リスクが surface すれば Phase 40 GA 前に XCUITest を追加）。
- **Deno tests**: `wipe-my-data-web` 用 +6。

## 改訂履歴

| 日付 | 変更 | 著者 |
|---|---|---|
| 2026-05-14 | 初期切り出し。データセーフティ A0d-6 サブ質問により Phase 27 を alpha ローンチ HARD-GATE に昇格。 | architect（agent team） |
| 2026-05-15 | 保持マトリクスに `friend_connections` + `friend_invites` 行を追記（Phase 25.1 フレンドグラフ）。消去セマンティクスは ADR-024 §(g.1) に従い migration 037（`wipe_own_data()` `CREATE OR REPLACE`）で適用。migration 035 からインラインで繰り越されていた Tech Debt をクローズ。 | architect（agent team） |

## 相互参照

- ADR-005 — `delete_own_account` RPC によるアカウント削除（兄弟プリミティブ）。
- ADR-016 — Pro subscription（`subscriptions` 保持理由）。
- ADR-017 — Push notifications（`device_tokens` 消去 + 再登録理由）。
- ADR-021 — UGC moderation（`ugc_reports`、`user_blocks` 保持マトリクスエントリ）。
- ADR-024 §(g.1) — フレンドグラフ消去セマンティクス（`friend_connections` / `friend_invites`）; migration 037 で適用。
- CLAUDE.md `### Planned — Phase 27 Data Wipe` — サブスライスインデックス + HARD-GATE 位置づけ。
- `docs/en/ops/data-export-sop.md` — 運用者向け export SOP（A20 Option A）; Phase 27 は削除側の対応するユーザー向けプライバシープリミティブ。
