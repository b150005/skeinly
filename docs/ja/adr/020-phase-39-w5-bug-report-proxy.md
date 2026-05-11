# ADR-020: Phase 39 W5 — GitHub App によるバグ報告プロキシ

> 英語原文: [docs/en/adr/020-phase-39-w5-bug-report-proxy.md](../../en/adr/020-phase-39-w5-bug-report-proxy.md)
>
> 状態: Accepted (2026-05-11)。**2026-05-12 amendment** あり (下記参照)。

## 改訂 (2026-05-12) — Beta 限定から一般ユーザー対象へスコープ拡大

当初 ADR は GitHub App + Edge Function を「Skeinly Beta Bug Reporter」として Phase 39 クローズドベータ専用の人工物として位置付けていた。デプロイ前レビューで、**GitHub App + Edge Function は Phase 40 GA 後も同じ実体のまま一般ユーザーが使い続ける**ことが明確化された。アプリ内エントリポイント (Settings → ベータ → 「フィードバックを送信」、シェイク / 3 本指長押しジェスチャ) のみが Phase 39 クローズドベータ運用のために `BuildFlags.isBeta` でゲートされている。Phase 40 GA で少なくとも Settings エントリは全ユーザーに開放される。

そのため、ベンダー側成果物 (GitHub App、Edge Function、secrets、Issue ラベル、アプリ内 title prefix) には **"Beta" を含む文言を一切含めない**。Phase 39 を完了したテスターも Phase 40 GA で同じ「Skeinly Feedback」チャネルを継続利用し、GA 後ユーザーの報告も Issues 上で陳腐化した "[Beta]" prefix や `beta-bug` ラベルを伴わない。

主な変更点:

| 面 | 当初 | 改訂後 |
|---|---|---|
| GitHub App name | `Skeinly Beta Bug Reporter` | **`Skeinly Feedback`** |
| App description | `Server-side proxy that creates Issues from Skeinly beta tester in-app bug reports.` | **`Server-side proxy that creates GitHub Issues from Skeinly users' in-app feedback (bug reports, feature requests, general feedback).`** |
| デフォルト Issue ラベル | `beta-bug` | **`feedback`** (scope-broad; triage 時に `bug` / `feature-request` 等を補助で付与) |
| ViewModel title prefix | `[Beta] $description` (空時は `[Beta] Bug report`) | **prefix なし; `$description` をそのまま、空時 → `Bug report`** |
| Edge Function コードコメント | "Beta Bug Reporter" を参照 | "Skeinly Feedback" を参照 |
| Secrets EF-7 環境変数名 | `SKEINLY_BUGREPORT_APP_ID` / `..._INSTALLATION_ID` / `..._PRIVATE_KEY_PEM` | **変更なし** (env-var 名は内部用途、rename はエンドユーザーに利点なく deploy + secret 再作成サイクルを強いるだけ) |
| アプリ内エントリの BuildFlags.isBeta ゲート | Phase 39 クローズドベータ限定 | **本コミットでは変更なし**; Phase 40 GA で Settings → 「フィードバックを送信」を全ユーザーに開放 (Tech Debt Backlog `Bug-report Settings entry GA opening` で管理) |

ユーザー向け文言は W5b の i18n sweep の段階で既に scope-neutral に書き換え済 (「Skeinly チームに送ります」とのみ書いてあり、「ベータチームに送ります」とは書いていない)。本 amendment で追加の i18n 変更は不要。

### 第二改訂 (2026-05-12 PM) — `apikey` header (`Authorization: Bearer` から変更)

デプロイ後の curl スモークテストが HTTP 401 `UNAUTHORIZED_INVALID_JWT_FORMAT` で失敗。原因: project が Supabase の新形式 `sb_publishable_*` API key へ移行済 (2025-11-01 切り替え、JWT 形式 `anon` キーは deprecated。CLAUDE.md `The legacy anon JWT key is deprecated by Supabase` を参照)。`verify_jwt = true` の Edge Function は `Authorization` header の値を JWT として検証するが、`sb_publishable_*` は JWT ではないため [Supabase Edge Functions auth doc](https://supabase.com/docs/guides/functions/auth) が明記する通り `apikey` でなく `Authorization` に乗せると edge layer で reject される。

Supabase 推奨の client-invoked Edge Function 認証パターンは **`apikey` header に publishable key を入れる** こと (`supabase-js` SDK が自動的にこれを行う)。

具体的な修正:

| 面 | 当初 | 改訂後 |
|---|---|---|
| `BugReportProxyClient.kt` request header | `Authorization: Bearer $supabasePublishableKey` | **`apikey: $supabasePublishableKey`** |
| Edge Function `computeSourceHash` rate-limit seed | `Authorization` header tail を読む | **`apikey` tail を優先、`Authorization` を fallback** (defense; 旧経路を壊さない) |
| Smoke test curl (README, release-secrets.md) | `-H "Authorization: Bearer ${ANON}"` | **`-H "apikey: ${ANON}"`** |
| Edge Function `index.ts` header コメント | "Client auth: Supabase anon JWT" | **"Client auth: Supabase publishable key in the `apikey` header"** + 上記理由 |
| `BugReportProxyClientTest` assertion | `request.headers[HttpHeaders.Authorization] == "Bearer ..."` | **`request.headers["apikey"] == "..."`** |

`verify_jwt = true` は `supabase/config.toml` で維持 — Supabase edge は `apikey` header の publishable key を認識して通常通り request を受け入れる。新たな deploy-config 変更不要、コードマージ後に通常の `supabase functions deploy submit-bug-report` で OK。

テスト: KMP `BugReportProxyClientTest` 14 件 + Deno `index.test.ts` 17 件を新 header convention に合わせて更新 (Deno 側は `buildRequest` + 2 inline-request の body 3 箇所を `apikey` に書き換え; KMP 側は 1 assertion 更新)。総 commonTest 数 1536 件 / Deno 32 件、いずれも増減なし。

将来互換: signed-in user の session JWT を per-user attribution 用に同時に送りたくなった場合は、`apikey` に publishable key + `Authorization: Bearer <user_jwt>` を併用する `supabase-js` の標準パターンに pivot する。クライアント側書き直し不要で対応可能。

§ユーザー側手順の GitHub App 名は `Skeinly Feedback` (旧 `Skeinly Beta Bug Reporter`) + 上記 description に置き換える。

ADR 本文の残りの節 (§Q1-Q3、§1-§6、§影響、§代替案) は原文のまま、name 文字列 2 箇所と title-prefix scope のみ本 amendment で読み替える。今後の読者は本 Amendment セクションの命名・ラベルを authoritative として扱う。

## 概要

Phase 39.5 (ADR-015 §3) は **クライアント側 URL プリフィル** 方式でベータバグ報告を実装した: `BugSubmissionLauncher` が `https://github.com/b150005/skeinly/issues/new?template=beta-bug.yml&title=…&body=…` をシステムブラウザで開き、テスター自身が GitHub Issue フォームから「Submit new issue」を押す。

Phase 39 W5 はこれを **サーバーサイド GitHub App プロキシ** に置換する。Supabase Edge Function `submit-bug-report` をホストとし、`Issues: Read & write` 権限のみを持つ GitHub App として `b150005/skeinly` リポジトリに Issue を作成する。

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

## アーキテクチャ

```
Beta build ──POST──▶ Edge Function submit-bug-report ──▶ GitHub API
                     1. Bearer <anon> 認証
                     2. レート制限チェック (5回/時)
                     3. 長さ・形状検証
                     4. App PEM で JWT 署名
                     5. installation access token 交換
                     6. POST /repos/.../issues
                     7. {number, html_url} を返却
```

## Edge Function 仕様

### リクエスト
```http
POST /functions/v1/submit-bug-report
apikey: <SUPABASE_PUBLISHABLE_KEY>
Content-Type: application/json

{
  "title": "[Beta] tap Save crashes on iOS 26.4",
  "body": "## Description\n…",
  "labels": ["feedback"]
}
```

### レスポンス (成功)
```json
{ "ok": true, "issue_number": 123, "html_url": "https://github.com/..." }
```

### レスポンス (失敗)
```json
{ "ok": false, "code": "RATE_LIMITED" | "VALIDATION_FAILED" | "GITHUB_AUTH_FAILED" | "GITHUB_API_FAILED" | "CONFIG_MISSING", "message": "..." }
```

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
インスタンス内 `Map<requestSourceHash, timestamps[]>`、1 時間スライディング窓、5 回/時超過で 200 OK + `{ok: false, code: "RATE_LIMITED"}` を返す。

## KMP クライアント配線

### `BugSubmissionLauncher` 削除
`expect/actual` を全削除。HTTP POST は commonMain で Ktor だけで完結するため `expect/actual` 不要。

### 新規 `BugReportProxyClient` (commonMain)
```kotlin
class BugReportProxyClient(httpClient: HttpClient, config: SupabaseConfig) {
    suspend fun submit(title: String, body: String): Result<SubmitOutcome>
}

data class SubmitOutcome(val issueNumber: Int, val htmlUrl: String)

sealed class BugReportProxyException(message: String) : Exception(message) {
    object Offline / RateLimited / ValidationFailed / ConfigMissing
    class Server(message: String) / Unknown(message: String)
}
```

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

成功時はトースト「Bug report submitted: #123」表示 + 短時間後にプレビュー画面を閉じる。失敗時はインラインエラーバナーで再試行可能にする。

## サブスライス分割

W5 は 2 段階で着地させる。Phase 39.5 の URL プリフィル経路は W5a を通じて生きたままで、W5b で初めて削除される。各コミット時点でバグ報告フローは常に動作する (half-cutover 状態にならない)。

### W5a (Edge Function 着地 — 本コミット)
- `supabase/functions/submit-bug-report/` 一式 (index.ts / github_app.ts / _fakes.ts / tests / README / deno.json)
- `supabase/config.toml` の `[functions.submit-bug-report]` 登録
- ADR-020 (en + ja)
- `release-secrets.md` (en + ja) の EF-7 GitHub App 三点 (App ID / Installation ID / Private Key PEM)
- CLAUDE.md W5a entry

W5a 完了時のユーザー側作業: GitHub App 作成、secret 登録、Edge Function deploy、curl スモークテスト。クライアントは引き続き URL プリフィル経路を使う (ユーザー可視の挙動変化なし)。

### W5b (KMP クライアント切替 — 次セッション)
- `BugReportProxyClient` (commonMain) 新規
- `BugSubmissionLauncher` (commonMain expect + actuals) 削除
- `BugReportPreviewViewModel` の submit を suspend 化、state に `submitResult` 追加
- `BugReportPreviewScreen.kt` (Compose) + `BugReportPreviewScreen.swift` の banner / トースト追加
- Koin wiring 更新
- `docs/public/privacy-policy/index.html` + JA mirror の文言を「URL プリフィル」から「サーバーサイドプロキシ」に書き換え
- `BugReportPreviewViewModelTest` 書き直し、新 `BugReportProxyClientTest` (Ktor MockEngine)
- CLAUDE.md W5b entry

W5b 完了時のユーザー側作業: TestFlight + Play Internal 実機ビルドでスモークテスト (ブラウザを開かずに Issue が作成されることを確認)。

## ユーザー側手順 (autonomous 不可)

1. https://github.com/settings/apps/new で「Skeinly Feedback」作成
2. Repository permissions → **Issues: Read & write** のみ
3. Installation scope → **Only on this account**
4. Webhook → 「Active」チェック外す
5. 保存 → **App ID** をメモ
6. **Private key (.pem)** を生成 + ダウンロード
7. `b150005/skeinly` のみに install → URL から **Installation ID** をメモ
8. Supabase secrets 登録:
   - `SKEINLY_BUGREPORT_APP_ID`
   - `SKEINLY_BUGREPORT_INSTALLATION_ID`
   - `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM` (PEM 全文)
9. `git pull && supabase functions deploy submit-bug-report` (autonomous でも可)
10. 実機 Beta build からスモークテスト

## W5 で意図的に含めないもの

- 添付ファイル (スクリーンショット、ログ) — `/uploads/` multipart 別途必要
- Issue assignment / reviewer 設定
- Comment スレッド (アプリ側からは作成のみ)
- マルチリポ送信先 (`b150005/skeinly` 固定)
- テスター GitHub ID 認証 (プロキシが GitHub App として作成)
- レート制限の永続ストレージ
- アプリからの Issue 更新・クローズ

## 影響

### Positive
- アプリ内完結で 2 画面摩擦解消
- GitHub アカウント不要 — 非開発者テスター招待が解禁
- URL 長制限解除 — 将来の診断本文拡張に耐性
- `distinct_id` はトランジット経路にとどまり、GitHub Issue 本文には含まれない
- 単一 PR で着地 — 半端な中間状態を避ける
- Edge Function パターン標準化 (notify-on-write, revenuecat-webhook, submit-bug-report)

### Negative
- デプロイがクリティカルパスに。Edge Function 停止 = バグ報告停止。緩和: Supabase function logs 監視、長期障害時は GitHub Web UI からの直接報告が引き続き可能
- GitHub App ベンダー面追加 — 年次の private key ローテーション推奨
- KMP リファクタ (`BugSubmissionLauncher` 削除 + 呼び出し側変更) — 軽微

### Neutral
- ~250 LOC Edge Function + ~60 LOC KMP client + ~80 LOC tests = ~400 LOC 純増
- 新 Edge Function 1 件、テーブル/migration 増加なし
- プライバシーポリシー差分は記述変更のみ (新規データ収集なし)

## 参照

- ADR-015 (Phase 39 F2 ベータバグ報告): W5 が置換する URL プリフィル形態
- ADR-017 (Phase 24 プッシュ通知): Edge Function third-party-credential custody パターン
- ADR-018 (Phase 24.3 プッシュ送信パス): JWT-based GitHub-style API 認証パターン
- GitHub Apps documentation: https://docs.github.com/en/apps/creating-github-apps
- `notify-on-write` (Phase 24.1 / commit 1ed59e2): コード形態の先行事例
- `revenuecat-webhook` (Phase 39.0.1 / commit 2752a30): Deno テスト形態の先行事例
