# ADR-020: Phase 39 W5 — GitHub App によるバグ報告プロキシ

> 英語原文: [docs/en/adr/020-phase-39-w5-bug-report-proxy.md](../../en/adr/020-phase-39-w5-bug-report-proxy.md)
>
> 状態: Accepted (2026-05-11), 2026-05-12 統合整理 (末尾の §改訂履歴 参照)。

## 概要

Phase 39.5 (ADR-015 §3) は **クライアント側 URL プリフィル** 方式でベータバグ報告を実装した: `BugSubmissionLauncher` が `https://github.com/b150005/skeinly/issues/new?template=beta-bug.yml&title=…&body=…` をシステムブラウザで開き、テスター自身が GitHub Issue フォームから「Submit new issue」を押す。

Phase 39 W5 はこれを **サーバーサイド GitHub App プロキシ** に置換する。Supabase Edge Function `submit-bug-report` をホストとし、`Issues: Read & write` 権限のみを持つ GitHub App として `b150005/skeinly` リポジトリに Issue を作成する。

**スコープは beta 限定ではない**。GitHub App / Edge Function / secrets / Issue ラベルは Phase 40 GA 以降も同じ実体のまま一般ユーザーが使い続ける (バグ報告・要望・一般フィードバック)。Phase 39 クローズドベータ期間中は **アプリ内エントリポイントのみ** が `BuildFlags.isBeta` でゲートされる。Phase 40 GA で少なくとも Settings → 「フィードバックを送信」エントリは全ユーザーに開放される (シェイク / 3 本指長押しジェスチャはパワーユーザー向け affordance として beta 限定に残る可能性あり)。

そのため、ベンダー側成果物 (GitHub App 名、Edge Function、Issue ラベル、アプリ内 title prefix) には **"Beta" を含む文言を一切含めない**。Phase 39 を完了したテスターも Phase 40 GA で同じ「Skeinly Feedback」チャネルを継続利用し、GA 後ユーザーの報告も Issues 上で陳腐化した `[Beta]` prefix や `beta-bug` ラベルを伴わない。

## 動機

URL プリフィルの 4 つの摩擦点:
1. **GitHub アカウント必須**: TestFlight/Play Internal テスターが GitHub ユーザーとは限らない
2. **ブラウザコンテキスト切替**: アプリ離脱 → GitHub.com → 「Submit」と 2 画面分の操作
3. **URL ~8KB 制限**: 本文予算 ~6.5KB は現状ギリギリ; 将来の拡張 (≥20 events / 構造化ログ等) が天井に当たる
4. **PostHog `distinct_id` の可視性**: 現状本文に含まれて public Issue に出る (Phase 39.2 でプライバシーポリシー開示済だが、transit metadata に留めれば改善できる)

W5 は **QOL + プライバシーの改善** であり、Phase 39 クローズドベータのブロッカーではない。実装失敗時は Phase 39.5 (URL プリフィル) にロールバック可能。

## 設計判断 (agent team 協議結果)

### Q1: URL プリフィルパスをどう扱うか

**(a) 完全置換** を採用。`BugSubmissionLauncher` の `expect/actual` 全削除。理由: 10 テスター規模で 2 系統並行は保守二重化、半端な状態を作る危険、URL プリフィルが壊れた状態の Phase 39.5 と機能的に等価。

### Q2: Issue 可視性 — public か private か

**(a) Public Issue (`b150005/skeinly`)** を採用。理由:
- 別 private repo を立てると triage 分散; UX コスト > プライバシー改善
- PostHog `distinct_id` は SDK 生成の匿名 UUID; アカウント・永続 ID への紐付けなし; Phase 39.2 プライバシーポリシーで開示済
- description PII 混入リスクは Issue 可視性とは独立; テンプレートで「個人情報を書かないでください」明示で対応
- オープンソース化時の透明性で public 優位
- Phase 38 Suggestion / Pull Request の public-by-default 文化と一致

### Q3: レート制限の実装

**(a) Edge Function インメモリ Map** を採用。理由: クローズドベータ ≤10 名でインスタンス再起動許容可、永続化テーブルは overkill、Phase 40 GA で再評価。

### Q4: Edge Function 呼び出しの認証モデル

**(c) Unauthenticated client (`verify_jwt = false`); 実際の認証は GitHub API 呼び出し側の downstream で行う** を採用。

検討した選択肢:
- **(a) `verify_jwt = true` + Supabase ユーザー session JWT を `Authorization` に乗せる**。**Rejected**。バグ報告は未認証ユーザーでも動作する必要がある — 例えば sign-in flow 自体の不具合を報告したい場合、Supabase Auth に signed-in していない。Supabase user JWT に gate を掛けると正当なユースケースをブロックしてしまう。
- **(b) `verify_jwt = true` + publishable key を `Authorization` に乗せる**。**Rejected**。2025-11-01 の Supabase API key 移行以降、プロジェクトの publishable key は `sb_publishable_*` 形式で **JWT ではない**。`verify_jwt = true` の edge layer は非 JWT 値を `Authorization` で受けると `UNAUTHORIZED_INVALID_JWT_FORMAT` HTTP 401 で reject する (function 到達前)。`apikey` ヘッダ ([Supabase docs](https://supabase.com/docs/guides/functions/auth) 推奨チャネル) に移しても、`apikey` はプロジェクト識別子であって JWT check の代替ではないため `verify_jwt = true` 下では `UNAUTHORIZED_NO_AUTH_HEADER` で reject される。
- **(c) `verify_jwt = false` (採用)**。Supabase doc が明示する **未認証クライアント呼び出し** パターン + 既存 repo 慣例 (`notify-on-write` と `revenuecat-webhook` 両者とも `verify_jwt = false` で、custom Bearer shared-secret を function 内で読んで認証している) と一致する。実際の認証は downstream の GitHub API 呼び出しで GitHub App 3 点 secret により行われる — これが function の意味ある認証境界である。publishable key は public なため、Supabase 層で gate を掛けても performative にしかならない。

クライアントは引き続き `apikey: <publishable_key>` を送る (defensive + 将来 user session JWT を `Authorization: Bearer` で重ねる `supabase-js` 標準パターンとの forward-compat)。Edge Function は `apikey` に gate を掛けない; `apikey` (fallback で `Authorization`) の tail のみを `computeSourceHash` の seed として読み、caller ごとに rate-limit window を分離する用途に使う。

Abuse 防御の再確認: §2 の通り、in-memory rate limit (5 reports / hour per source-hash, `x-real-ip` + auth-tail から計算) が実際の防御層。クローズドベータ ≤10 testers + Phase 40 GA 規模 (≤O(10K) installs) でも、Edge Function instance が最大 N×5 requests/hour (N = unique source IP 数) を処理する規模は Supabase 関数呼び出し予算内に余裕で収まる。

## アーキテクチャ

```
App build ──POST──▶ Edge Function submit-bug-report ──▶ GitHub API
  apikey: <pub>     (verify_jwt = false)
                    1. apikey → rate-limit source-hash の seed (gate ではない)
                    2. レート制限チェック (5回/時)
                    3. 長さ・形状検証
                    4. App PEM で JWT 署名
                    5. installation access token 交換
                    6. POST /repos/.../issues
                    7. {number, html_url} を返却
```

## Edge Function 仕様

`supabase/config.toml` 登録:
```toml
[functions.submit-bug-report]
verify_jwt = false
```

Q4 参照。`notify-on-write` と `revenuecat-webhook` と同じ未認証クライアント呼び出しパターン。実際の認証は downstream の GitHub API 呼び出しで GitHub App 3 点 secret により行われる。

### リクエスト
```http
POST /functions/v1/submit-bug-report
apikey: <SUPABASE_PUBLISHABLE_KEY>
Content-Type: application/json

{
  "title": "tap Save crashes on iOS 26.4",
  "body": "## Description\n…",
  "labels": ["feedback"]
}
```

`apikey` はプロジェクトの `sb_publishable_*` キー。Edge Function は値を検証せず (Supabase 層 auth は無効化)、tail を rate-limit hash の seed として読むのみ。`labels` は省略可 (デフォルト `["feedback"]`)。

### レスポンス (成功)
```json
{ "ok": true, "issue_number": 123, "html_url": "https://github.com/..." }
```

### レスポンス (失敗)
```json
{ "ok": false, "code": "RATE_LIMITED" | "VALIDATION_FAILED" | "GITHUB_AUTH_FAILED" | "GITHUB_API_FAILED" | "CONFIG_MISSING", "message": "..." }
```

`code` は閉列挙でクライアント側ローカライズ可能、`message` はログ用英語生文字列。

### 入力検証
| Field | 制約 |
|---|---|
| `title` | 1 ≤ length ≤ 256, 改行禁止 |
| `body` | 1 ≤ length ≤ 65,536 (GitHub Issue 本文上限) |
| `labels` | optional, ≤ 5 件, 各 ≤ 50 文字 |

### GitHub App 認証フロー
1. **JWT 署名 (RS256)**: `iat = now - 60`, `exp = now + 540`, `iss = APP_ID`
2. **Installation access token 取得**: `POST /app/installations/<ID>/access_tokens` with `Authorization: Bearer <JWT>`
3. **Issue 作成**: `POST /repos/b150005/skeinly/issues` with `Authorization: Bearer <installation_token>`

JWT は ~9 分キャッシュ、installation token は 1 時間有効で 5 分マージンでキャッシュ。

### レート制限
インスタンス内 `Map<requestSourceHash, timestamps[]>`、1 時間スライディング窓、5 回/時超過で 200 OK + `{ok: false, code: "RATE_LIMITED"}` を返す。`requestSourceHash` は `x-real-ip` + `apikey` (fallback で `Authorization`) tail の SHA-256。

## KMP クライアント配線

### `BugSubmissionLauncher` 削除
`expect/actual` を全削除。HTTP POST は commonMain で Ktor だけで完結するため `expect/actual` 不要。

### 新規 `BugReportProxyClient` (commonMain)
```kotlin
class BugReportProxyClient(httpClient: HttpClient, config: SupabaseConfig) {
    suspend fun submit(title: String, body: String): Result<SubmitOutcome>
    // header に apikey: <SupabaseConfig.publishableKey>
    // Authorization: Bearer は送らない (verify_jwt = false なため; ADR-020 Q4 参照)
}

data class SubmitOutcome(val issueNumber: Int, val htmlUrl: String)

sealed class BugReportProxyException(message: String) : Exception(message) {
    object Offline / RateLimited / ValidationFailed / ConfigMissing
    class Server(message: String) / Unknown(message: String)
}
```

クライアントは publishable key を `apikey` ヘッダで送る (Q4 参照)。`Authorization: Bearer` は送らない — Edge Function は `verify_jwt = false` で動作するため caller-supplied JWT は無視される。将来 signed-in user attribution が必要になった時は `Authorization: Bearer <user_jwt>` を併用する形で `apikey` を触らずに拡張可能。

### ViewModel signature 変更
`submit: (title, body) -> Unit` → `submit: suspend (title, body) -> Result<SubmitOutcome>`

State 拡張:
```kotlin
data class BugReportPreviewState(
    /* 既存フィールド */,
    val submitResult: SubmitResultState? = null,
)

sealed interface SubmitResultState {
    data class Success(val issueNumber: Int, val htmlUrl: String) : SubmitResultState
    data class Error(val code: BugReportProxyException) : SubmitResultState
}
```

タイトルは description そのものを送る (`[Beta]` prefix なし — Phase 40 GA で同じ surface を再利用)。description が空のとき `Bug report` をデフォルトとして使う。

成功時はトースト「Bug report submitted: #123」表示 + 短時間後にプレビュー画面を閉じる。失敗時はインラインエラーバナーで再試行可能にする。

## サブスライス分割

W5 は 2 段階で着地させる。Phase 39.5 の URL プリフィル経路は W5a を通じて生きたままで、W5b で初めて削除される。各コミット時点でバグ報告フローは常に動作する (half-cutover 状態にならない)。

### W5a (Edge Function 着地)
- `supabase/functions/submit-bug-report/` 一式 (index.ts / github_app.ts / _fakes.ts / tests / README / deno.json)
- `supabase/config.toml` の `[functions.submit-bug-report]` 登録 (`verify_jwt = false`)
- ADR-020 (en + ja)
- `release-secrets.md` (en + ja) の EF-7 GitHub App 三点 (App ID / Installation ID / Private Key PEM)
- CLAUDE.md W5a entry

W5a 完了時のユーザー側作業: GitHub App 作成、`feedback` ラベル作成、secret 登録、Edge Function deploy、curl スモークテスト。クライアントは引き続き URL プリフィル経路を使う (ユーザー可視の挙動変化なし)。

### W5b (KMP クライアント切替)
- `BugReportProxyClient` (commonMain) 新規
- `BugSubmissionLauncher` (commonMain expect + actuals) 削除
- `BugReportPreviewViewModel` の submit を suspend 化、state に `submitResult` 追加
- `BugReportPreviewScreen.kt` (Compose) + `BugReportPreviewScreen.swift` の banner / トースト追加
- Koin wiring 更新 (`ViewModelModule.kt` / `RepositoryModule.kt` / `PlatformModule.{android,ios}.kt`)
- `docs/public/privacy-policy/index.html` + JA mirror の文言を「URL プリフィル」から「サーバーサイドプロキシ」に書き換え
- `BugReportPreviewViewModelTest` 書き直し、新 `BugReportProxyClientTest` (Ktor MockEngine)
- CLAUDE.md W5b entry

W5b 完了時のユーザー側作業: TestFlight + Play Internal 実機ビルドでスモークテスト (ブラウザを開かずに Issue が作成されることを確認)。

## ユーザー側手順 (autonomous 不可)

1. https://github.com/settings/apps/new で「Skeinly Feedback」作成
   - Description: `Server-side proxy that creates GitHub Issues from Skeinly users' in-app feedback (bug reports, feature requests, general feedback).`
2. Repository permissions → **Issues: Read & write** のみ
3. Installation scope → **Only on this account**
4. Webhook → 「Active」チェック外す
5. 保存 → **App ID** をメモ
6. **Private key (.pem)** を生成 + ダウンロード
7. `b150005/skeinly` のみに install → URL から **Installation ID** をメモ
8. `b150005/skeinly` で `feedback` Issue ラベルを作成 (Issues → Labels → New)。Edge Function のデフォルトラベルなので、ラベル不在だと GitHub が 422 `VALIDATION_FAILED` を返す
9. Supabase secrets 登録:
   - `SKEINLY_BUGREPORT_APP_ID`
   - `SKEINLY_BUGREPORT_INSTALLATION_ID`
   - `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM` (PEM 全文)
10. `git pull && supabase functions deploy submit-bug-report` (autonomous でも可)
11. 実機 Beta build からスモークテスト

## W5 で意図的に含めないもの

- 添付ファイル (スクリーンショット、ログ) — `/uploads/` multipart 別途必要
- Issue assignment / reviewer 設定
- Comment スレッド (アプリ側からは作成のみ)
- マルチリポ送信先 (`b150005/skeinly` 固定)
- テスター GitHub ID 認証 (プロキシが GitHub App として作成)
- レート制限の永続ストレージ
- アプリからの Issue 更新・クローズ
- Phase 40 GA でのアプリ内エントリ開放 — W5b では `BuildFlags.isBeta` ゲートを維持。Tech Debt Backlog `Bug-report Settings entry GA opening` で追跡

## 影響

### Positive
- アプリ内完結で 2 画面摩擦解消
- GitHub アカウント不要 — 非開発者テスター招待が解禁
- URL 長制限解除 — 将来の診断本文拡張に耐性
- `distinct_id` はトランジット経路にとどまり、GitHub Issue 本文には含まれない
- 単一 PR で着地 — 半端な中間状態を避ける
- Edge Function パターン標準化 (`notify-on-write`, `revenuecat-webhook`, `submit-bug-report` — 3 つとも `verify_jwt = false` で、実際の認証は downstream API 呼び出しで行う)
- scope-broad なブランディング (「Skeinly Feedback」) で Phase 40 GA への移行で vendor-artifact 変更なし

### Negative
- デプロイがクリティカルパスに。Edge Function 停止 = バグ報告停止。緩和: Supabase function logs 監視、長期障害時は GitHub Web UI からの直接報告が引き続き可能
- GitHub App ベンダー面追加 — 年次の private key ローテーション推奨
- KMP リファクタ (`BugSubmissionLauncher` 削除 + 呼び出し側変更) — 軽微

### Neutral
- ~250 LOC Edge Function + ~60 LOC KMP client + ~80 LOC tests = ~400 LOC 純増
- 新 Edge Function 1 件、テーブル/migration 増加なし
- プライバシーポリシー差分は記述変更のみ (新規データ収集なし)

## 代替案

### URL プリフィル維持
Q1 で却下。非開発者テスター招待時の摩擦は実存、URL 上限は Phase 39+ ペイロード成長で当たる。

### クライアント側 GitHub OAuth
ADR-015 §7 で却下。OAuth flow + token storage 追加、非 GitHub ユーザー問題は解決しない。

### `b150005/skeinly-bug-reports` private repo 分離
Q2 で却下。triage 分散コスト > プライバシー改善 (既存ポリシー開示済)。

### 永続化レート制限テーブル
Q3 で却下。10 テスター規模で overkill、Phase 40 GA で再評価。

### `verify_jwt = true` + Supabase user session JWT
Q4 で却下。バグ報告は未認証ユーザーでも動作する必要があり、sign-in flow 自体が壊れた場合の報告経路を塞いでしまう。

### `verify_jwt = true` + publishable key を `Authorization` に乗せる
Q4 で却下。2025-11-01 の Supabase API key 移行で publishable key は `sb_publishable_*` (JWT ではない) になり、`Authorization` に乗せると edge layer で reject される。`apikey` ヘッダに移しても `verify_jwt = true` 下では caller 認証として認められない。

### 「Skeinly Beta Bug Reporter」名 + `[Beta]` title prefix + `beta-bug` ラベル
デプロイ前レビューで却下。GitHub App / Edge Function / Issue ラベルは Phase 40 GA で一般ユーザーに継承される実体なので、beta-only のブランディングは GA 移行時に rename + secret 再作成の churn を強いるだけ。

## 改訂履歴

- 2026-05-11 — 初版 draft + acceptance。当初命名は「Skeinly Beta Bug Reporter」+ `beta-bug` ラベル + `[Beta]` title prefix; client auth は `Authorization: Bearer <anon_jwt>` + `verify_jwt = true` の提案
- 2026-05-12 — デプロイ前レビュー: ベンダー側成果物を「Skeinly Feedback」+ `feedback` ラベル + prefix なしに rename (beta-only ではないスコープ)
- 2026-05-12 — スモークテスト修正: 2025-11-01 Supabase `sb_publishable_*` 移行に合わせて client auth ヘッダを `apikey: <publishable_key>` に変更
- 2026-05-12 — スモークテスト修正: `verify_jwt = false` 採用; 実際の認証は downstream の GitHub API 呼び出し側で行う。Q4 を新規追加し、§1 / §2 / §3 を未認証クライアントパターンに合わせて更新
- 2026-05-12 — ADR 統合整理: 3 つの amendment セクションを本文に統合; 本改訂履歴ブロックを以後の正式な変更履歴とする

## 参照

- ADR-015 (Phase 39 F2 ベータバグ報告): W5 が置換する URL プリフィル形態
- ADR-017 (Phase 24 プッシュ通知): Edge Function third-party-credential custody パターン
- ADR-018 (Phase 24.3 プッシュ送信パス): JWT-based GitHub-style API 認証パターン
- GitHub Apps documentation: https://docs.github.com/en/apps/creating-github-apps
- Supabase Edge Functions auth doc: https://supabase.com/docs/guides/functions/auth
- `notify-on-write` (Phase 24.1 / commit 1ed59e2): コード形態 + `verify_jwt = false` 先行事例
- `revenuecat-webhook` (Phase 39.0.1 / commit 2752a30): Deno テスト形態 + `verify_jwt = false` 先行事例
