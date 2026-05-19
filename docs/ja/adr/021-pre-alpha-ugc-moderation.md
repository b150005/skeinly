# ADR-021: Pre-Alpha UGC モデレーション (Report + Block + Filter)

> 英語原文: [docs/en/adr/021-pre-alpha-ugc-moderation.md](../../en/adr/021-pre-alpha-ugc-moderation.md)
>
> 状態: Accepted (2026-05-12)。実装は段階分け; 基盤は pre-alpha、フル UI は Phase 40 GA 前。

## 概要

Apple App Store Review Guideline 1.2 (UGC Safety) + Google Play UGC ポリシーはアプリで UGC を表示する場合、**全 4 要件**を実装する必要がある:

1. **フィルター / モデレーション** — サーバー側または運用者介在による不適切コンテンツ除去
2. **報告 (Report) 機能** — 各 UGC 要素に viewer から 1 タップ以下で到達可能な Report 動線
3. **ユーザーブロック機能** — viewer が特定ユーザーのコンテンツを非表示にでき、ブロック済ユーザーは blocker に対して新規 Suggestion / コメントを送れない
4. **公開連絡先** — オペレーターメールアドレスがアプリ内 + ストアリスティングメタデータ両方から見える

Skeinly が UGC を surface する場所:

| Surface | UGC 要素 | 可視性 |
|---|---|---|
| Discovery (パターンを探す) | `visibility = public` パターンの名前・説明・編み図サムネイル | 認証済全ユーザー |
| Suggestion detail | Suggestion 本体 + コメント + サブコメント | パターンオーナー + Suggestion 投稿者 + watcher |
| パターン・プロジェクトコメント | `comments` テーブル (Phase 32+) | パターン / プロジェクトオーナー + 共有先 (migration 030 で締め直し) |

要件 4 は完了済 (`skeinly.app@gmail.com` が Privacy Policy / ToS DMCA / Help / Settings / Web account-deletion ページに掲載済、A34 closed)。

要件 1, 2, 3 は未実装。Pre-alpha checklist 項目 **A1** + **A5** がこのギャップを指摘。ADR-021 は段階的解決を設計する:

- **Pre-alpha 基盤**: データモデル + 最小限の報告メカニズム (Edge Function、ADR-020 W5b の `submit-bug-report` パターン再利用) + 最小限のブロックメカニズム (RLS フィルターのみ、`0.1.0` アルファ時点ではクライアント UI なし)。App Store / Play 申請時に「Skeinly に UGC モデレーション機能あり」と説明可能。
- **Phase 40 GA 前フルスイープ**: Report ボタン + Block User エントリの実 UI、Blocked Users 一覧、フルモデレーター運用ワークフロー、最も明らかな悪用テキストへの自動キーワードフィルター。

## 主要な判断

### D1 — データモデル (基盤、pre-alpha)

新規 Supabase テーブル 2 つ:

**`public.ugc_reports`** — 報告本体。`reporter_id` + `target_type` (pattern / comment / suggestion / suggestion_comment) + `target_id` + `reason` (1–2000 文字) + `reason_category` (spam / harassment / sexual / violence / hate / ip / other) + `state` (open / triaging / resolved_remove / resolved_keep / dismissed) + `operator_notes` + `resolved_at` + timestamps。RLS: authenticated INSERT (`reporter_id = auth.uid()` チェック) + SELECT 自分の報告のみ + UPDATE/DELETE 不可 (運用者は Dashboard service-role 経由)。

**`public.user_blocks`** — ユーザーブロック関係。複合主キー `(blocker_id, blocked_id)` + `CHECK (blocker_id != blocked_id)`。RLS: authenticated INSERT/SELECT/DELETE 自分の行のみ。

両テーブルは 1 つのマイグレーション `031_ugc_moderation.sql` で配備。

### D2 — RLS レベルフィルター (基盤、pre-alpha)

Discovery `patterns` SELECT ポリシーに `user_blocks` NOT-EXISTS 句追加 — blocker が見える結果から blocked ユーザーのコンテンツを除外:

```sql
-- 既存 patterns SELECT ポリシーへの概念的追記:
-- AND NOT EXISTS (SELECT 1 FROM public.user_blocks ub
--                 WHERE ub.blocker_id = auth.uid()
--                   AND ub.blocked_id = patterns.owner_id)
```

同じ NOT-EXISTS 句を `comments` / `suggestions` / `suggestion_comments` SELECT ポリシーにも適用。

これがサーバー側保証: ブロック済ユーザーのコンテンツは blocker のデバイスに**到達しない**。クライアント UI (D4) で追加フィルター不要 — 行が返ってこないだけ。

### D3 — 報告送信 (基盤、pre-alpha)

報告は ADR-020 W5b のバグ報告と同じ Edge Function + Skeinly Feedback GitHub App パイプラインを使い、ただし 2 つの宛先に書き込む:

1. `public.ugc_reports` INSERT (state tracking の canonical authority record)
2. `b150005/skeinly` リポジトリに `ugc-report` ラベル付き GitHub Issue POST (運用者のトリアージサーフェス — 既存のバグ報告 inbox 動線を流用)

新規 Edge Function `submit-ugc-report` は `submit-bug-report` と別:

- 認証済呼び出しのみ (`verify_jwt = true`、`submit-bug-report` の `verify_jwt = false` と異なる; UGC 報告は reporter identity が必要)
- レート制限: `auth.uid()` ごとに 1 時間 10 報告 (`submit-bug-report` と同じ in-memory `Map` パターン)
- 同じ GitHub App 認証 (ADR-020 §D2)
- Issue 本文テンプレ: reporter user_id (メールアドレスではない — 運用者は Dashboard SQL Editor で必要に応じて解決)、target_type + target_id、reason_category、redacted reason text (先頭 500 文字 + 長さ)、report_id (`ugc_reports` への FK)

### D4 — クライアント UI (pre-alpha 基盤 vs Phase 40 GA 前フル)

#### Pre-alpha 基盤 (本 ADR 直後 Wave E)

クライアント UI を完全に**先送り**。アルファ期は **運用者が tester に代わって** 報告 + ブロック実施: テスターが skeinly.app@gmail.com にメール → 運用者が Dashboard SQL Editor で手動 INSERT。

**先送り理由**: クローズドアルファ tester pool (5–10 人、選別済、全員 trusted) では「敵対的 UGC が self-service 報告を待つ前に surface する」というスレットモデルが**事実上ゼロ**。運用者が tester より早く email 経由でフィールド可能。基盤 (D1 テーブル + D2 RLS + D3 Edge Function) が live なので、GA 時の UI スイープは pure UI 追加で、後付けスキーマ作業なし。

これは「UGC モデレーション不在」ではない。データモデル + RLS フィルター + Edge Function 運用パイプラインは全て live。Apple Review への説明: 「Skeinly のクローズドアルファは operator-mediated; in-app Report ボタンは GA で出荷」。

#### Phase 40 GA 前フルスイープ (CLAUDE.md polish list で schedule 済)

UI 追加 3 つ:

1. **Discovery パターンカードの Report ボタン** — オーバーフローメニュー (⋮) → 「このパターンを報告」 → カテゴリピッカー + 2000 文字理由テキスト + 送信モーダル。`target_type='pattern'` + `target_id=<pattern.id>` で `submit-ugc-report` 呼び出し
2. **Suggestion / コメントスレッドの Report ボタン** — 同じモーダル、`target_type='comment' | 'suggestion' | 'suggestion_comment'`
3. **ユーザープロフィール + Suggestion 投稿者 chip の Block User エントリ** — 確認ダイアログ「<display_name> をブロックしますか?」 → `user_blocks` INSERT。Unblock は Settings → プライバシー → ブロックしたユーザー一覧から逆アクション可能

クライアント i18n: 新規 8–10 key × en/ja × CMP/iOS。

### D5 — 運用者トリアージワークフロー (基盤、pre-alpha)

UGC 報告 Issue が `b150005/skeinly` に `ugc-report` ラベル付きで着地したら:

1. 運用者が GitHub Issue を開き、target_type + target_id + reason_category を読む
2. Dashboard SQL Editor で target_id を実コンテンツに解決
3. Apple SLA 不適切コンテンツ除去: **24 時間**。運用者は documented runbook (D6) でこの SLA をコミット
4. 解決アクション 3 択:
   - **コンテンツ除去**: パターンなら `visibility = 'private'` UPDATE、コメントなら DELETE。`ugc_reports.state = 'resolved_remove'` UPDATE
   - **コンテンツ維持**: 報告が false-positive または受容可能。`state = 'resolved_keep'`、operator_notes に理由
   - **却下**: 報告自体が abusive (例: 嫌がらせ目的の false report)。`state = 'dismissed'`。reporter の UID を operator_notes に track — 複数 `dismissed` 報告は reporter 側の問題を示唆
5. GitHub Issue を `ugc_reports.id` 参照付き 1 行 summary で close

週 ~10 報告超過したら専用モデレーター UI 構築または契約モデレーター onboarding を再検討。

> **Evolution note (2026-05-19)**: Phase 39 closed-beta 運用知見の反映は ADR-027 で行う。ADR-027 採択後は本セクションの手動 dual-sync は fallback path に降格し、デフォルトのトリアージワークフローは GitHub Issue close → webhook 駆動になる。24 時間 SLA コミットメントは変更なし; 上記段落の volume 閾値 (モデレータープール scale-out) は ADR-027 の自動化と直交する判断。詳細は [ADR-027](./027-ugc-triage-automation.md) 参照。

### D6 — Runbook

Pre-alpha 基盤スライス出荷物:
- マイグレーション 031 (D1) — `ugc_reports` + `user_blocks` テーブル + RLS
- マイグレーション 032 (D2) — patterns / comments / suggestions / suggestion_comments SELECT ポリシーに `user_blocks` NOT-EXISTS 句追加
- Edge Function `submit-ugc-report` (D3) — Deno + Skeinly Feedback GitHub App 認証
- 新規 runbook `docs/en/ops/ugc-moderation-sop.md` (D5 ワークフロー + 24 時間 SLA + resolve/keep/dismiss 判断マトリックス + Phase 40 GA UI フック)

Phase 40 GA スイープ出荷物:
- Report モーダル Composable + SwiftUI mirror
- Block User UI
- Settings → Privacy → Blocked Users 一覧
- i18n key (× 4: en, ja, CMP, iOS xcstrings)
- 12+ commonTest ケース

## 代替案検討

### A1 — 既存 `submit-bug-report` Edge Function 流用

却下。`verify_jwt = false` (バグ報告) と `verify_jwt = true` (UGC 報告 — reporter identity 必要) を関数レベルで混在させると Supabase の関数レベル `verify_jwt` がバイナリで切り替え不可。+ GitHub Issue ラベル混在で運用ミス risk 増加。別関数のほうが App Review reviewer + 将来運用者にとってドキュメントサーフェスとして明確。

### A2 — Pre-alpha 時点で in-app Report ボタン実装

却下 (GA に先送り)。クローズドアルファ tester pool では self-service 報告不要 (D4 §先送り理由参照)。これは**コスト理由の先送りではない** (user policy で禁止)。**スコープ判断**: 「Skeinly に UGC モデレーション」というアルファ検証目標は基盤 + 運用者パイプラインで満たされる。Tester-facing self-service は UX 改善であってコンプライアンスギャップではない。

### A3 — Pre-alpha 時点で自動キーワードフィルター

却下 (GA に先送り)。キーワードブロッキングは 50 年の false positive 履歴 (Scunthorpe problem)。クローズドアルファ向けは過剰設計。運用者介在トリアージのほうがどの自動フィルターより accurate。GA で報告 volume が justify する場合に再考。

### A4 — モデレーション外部委託 (Sift / Spectrum Labs 等)

却下。コスト (月額 USD 99–500 最低)、データ residency 複雑性、DPA 追加。クローズドアルファ規模では single-operator triage で十分。

## コンプライアンス対応

### Apple App Store Guideline 1.2

| 要件 | 基盤 (pre-alpha) | GA |
|---|---|---|
| フィルター / モデレーション | ✅ Edge Function + Dashboard SQL 経由運用者介在 | ✅ + アルファフィードバック駆動の自動キーワードフィルター |
| 報告メカニズム | ✅ Edge Function + GitHub Issue triage (運用者が email 経由で tester 代行) | ✅ 全 UGC 面に In-app Report ボタン |
| ユーザーブロック | ✅ データモデル + RLS フィルター live; 運用者が tester 代行 INSERT | ✅ In-app Block User UI + Blocked Users 一覧 |
| 公開連絡先 | ✅ Privacy / ToS / Help / Settings / Web account-deletion / app footer に掲載済 | ✅ 変更なし |

### Google Play UGC ポリシー

同じマッピング。Google Play ポリシーはメカニクス的に Apple のサブセット; 両者とも同じ基盤で満たす。

### App Store Connect Review Notes 提出用

```
Skeinly surfaces user-generated content via the Discovery feed (public
knitting patterns), Suggestion threads (collaboration), and Comments on
patterns and projects. UGC moderation is implemented in three layers:
(1) server-side row-level security filters out content from users a
viewer has blocked; (2) reports route through a Supabase Edge Function
+ GitHub App that creates internal triage Issues; (3) the operator
commits to a 24-hour SLA for objectionable content removal per Apple
Guideline 1.2. The current closed-alpha release ships the data-model +
filter + reporting foundation; in-app Report and Block User UI
surfaces ship at v1.0 GA. The contact email skeinly.app@gmail.com is
published in the Privacy Policy, ToS, in-app Settings, and on the
public web pages.
```

## スコープ外

- **モデレーターダッシュボード** (Supabase Studio 以上のもの) — 報告 volume が justify する場合のみ構築
- **コンテンツ除去に対する appeal フロー** — クローズドアルファでは運用者が email reply で個別対応。最初の appeal 発生まで codified flow は不要
- **クロスインスタンス shadow ban / IP ブロック** — single user_id ベース block でアルファ規模十分
- **Trust-and-safety ML 分類器** — 自動キーワードフィルターと同様、先送り

## 実装段階

| Slice | スコープ | Wave |
|---|---|---|
| 021.1 — データスパイン | マイグレーション 031 (D1) + 032 (D2 RLS 修正) | Wave E (次セッション) |
| 021.2 — Edge Function | `submit-ugc-report` + テスト + README + EF-8 secret (`SKEINLY_BUGREPORT_*` 再利用 or 別 GitHub App) | Wave E |
| 021.3 — 運用者 SOP runbook | `docs/en/ops/ugc-moderation-sop.md` (+ JA mirror) | Wave E |
| 021.4 — Phase 40 GA 前クライアント UI | Report Composable + SwiftUI mirror + Block User UI + Blocked Users 一覧 + i18n + テスト | Pre-Phase-40 polish |

## クロスリファレンス

- [ADR-005](005-account-deletion.md) — 隣接権利 (削除) フロー
- [ADR-014](014-phase-38-pull-request-workflow.md) — Suggestion データモデル
- [ADR-020](020-phase-39-w5-bug-report-proxy.md) — submit-ugc-report が mirror するパターン
- [docs/en/ops/pre-alpha-checklist.md §1.1 A1 + A5](../../en/ops/pre-alpha-checklist.md) — closure 記録
- [Apple Guideline 1.2](https://developer.apple.com/app-store/review/guidelines/#user-generated-content)
- [Google Play UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937)

## 改訂履歴

| 日付 | 変更 | 著者 |
|---|---|---|
| 2026-05-12 | 初版 ADR — pre-alpha A1+A5 設計 closure; 実装は Wave E + Phase 40 GA 前に段階分け | b150005 |
| 2026-05-19 | §D5 に evolution-note cross-link 追加。ADR-027 (Proposed) が §D5 を fallback path に降格する GitHub Issue close webhook トリアージ自動化を導入。§D5 自体の設計は無変更。 | b150005 |
