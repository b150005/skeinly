# Runbook — インシデントプレイブック

> 英語原典: [docs/en/ops/incident-playbook.md](../../en/ops/incident-playbook.md)
>
> **目的**: 症状別の本番障害トリアージ。各エントリは「まずどこを見るか」のクイックレシピ。深いデバッガ代替ではない。
>
> **対象**: ユーザー報告またはモニタリングアラートに対応する運用者・開発者。

## トリアージ順 (常に)

1. **surface を再現するか pin down**: iOS/Android どちらか、両方か。ベータ build のみか TestFlight 本番でも出るか。1 ユーザーか多数か
2. **まず関係ログを引く**。Edge Function ログ 1 行で答えがあることが多い
3. **secret + デプロイ状態を確認**。「昨日は動いてた」障害の多くは rotation 後 or deploy 後の不整合
4. **症状がノベルなら upstream issue tracker をチェック**。詳細は [.claude/docs/process/upstream-issue-triage.md](../../../.claude/docs/process/upstream-issue-triage.md)

## ログソース一覧

| ソース | 引き方 |
|---|---|
| Edge Function ログ (notify-on-write / revenuecat-webhook / request-pack-download / submit-bug-report) | `mcp__supabase__get_logs service=edge-function` |
| Postgres ログ | `mcp__supabase__get_logs service=postgres` |
| Auth ログ | `mcp__supabase__get_logs service=auth` |
| Storage ログ | `mcp__supabase__get_logs service=storage` |
| Sentry iOS | https://sentry.io/organizations/<org>/issues/?project=<skeinly-ios> |
| Sentry Android | https://sentry.io/organizations/<org>/issues/?project=<skeinly-android> |
| PostHog プロダクト分析 | https://app.posthog.com/project/<id>/events |
| Play Console プレローンチレポート | Play Console → Skeinly → Pre-launch report |
| App Store Connect クラッシュログ | ASC → TestFlight → Builds → Logs |
| RevenueCat イベント | RevenueCat dashboard → Events |
| GitHub Actions ワークフローログ | `gh run view <run-id> --log-failed` |

---

## 症状: シンボルパックダウンロードが失敗

ユーザーから Pro パック (or 新規 free パック) が編図に表示されない / sync がサイレント失敗。

### 一次確認 — サーバー側

```sql
-- catalog にパックが存在するか
SELECT id, tier, version, payload_path, payload_size, symbol_count
FROM public.symbol_packs WHERE id = '<pack_id>';
```

空なら → catalog 行が INSERT されていない or DELETE されている。[content-publishing.md](content-publishing.md) で公開フロー参照。

```
mcp__supabase__get_logs を service: edge-function で実行
```
直近 1h の request-pack-download function ログを見る。成功時は `pack_download_signed` event 行、失敗時は `error` フィールド。ステータスコードで分類: 403 (entitlement) / 404 (pack 不在) / 429 (rate-limit) / 500 (Storage sign 失敗)。

### 失敗モード → アクション

| Function が返した | 意味 | アクション |
|---|---|---|
| 401 `unauthorized` | Bearer JWT 欠落 or 無効 | ユーザーが auth されていない — クライアント側 auth state を確認 |
| 403 `pro_entitlement_required` | Pro パック + active subscription なし | `SELECT * FROM public.subscriptions WHERE user_id = '<uid>' AND status IN ('active','in_grace_period')` を確認。空なら webhook が来てない — 下の「RevenueCat webhook が来ない」参照 |
| 404 `pack_not_found` | catalog 行なし | seed migration を走らせる or 行を再 INSERT |
| 429 `rate_limited` | ユーザーが 10 req / 60s を超えた | <1 分で自己解決するはず。1 ユーザーが恒常的に出るならクライアント側 retry ループを疑う |
| 500 `internal_error` (Storage sign failed) | `payload_path` mismatch or bucket policy regression | `payload_path` が Storage オブジェクトパスと文字単位で一致するか確認 (大文字小文字含む) |
| 500 `edge_function_misconfigured` | `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` が runtime 欠落 | 起きないはず — Supabase が auto-inject。function を re-deploy |

### 一次確認 — クライアント側

function が 200 を返すのにシンボルが見えない場合はクライアント側 sync outcome を確認。SymbolPackSyncManager は per-pack `PackSyncOutcome` をログ — `VersionRegression` (サーバーが下げた) / `ParseError` (payload 破損) / `Failed` (SQLDelight 書き込み失敗) を探す。

完全な sync state machine: [spec/symbol-pack-delivery.md](../../en/spec/symbol-pack-delivery.md)。

---

## 症状: Push 通知が来ない

ユーザーが push (PR opened, comment, merged, closed) を期待するが届かない。

### Step 1: トリガーイベントは発火したか

```sql
-- PR open / merged / closed:
SELECT id, status, author_id, target_pattern_id, created_at, updated_at
FROM public.suggestions
WHERE id = '<suggestion_id>'
ORDER BY updated_at DESC;

-- コメント:
SELECT id, pull_request_id, author_id, created_at
FROM public.suggestion_comments
WHERE id = '<comment_id>';
```

行が存在すれば DB 書き込みは起きた。Database Webhook が発火しているはず。

### Step 2: notify-on-write は走ったか

`mcp__supabase__get_logs service=edge-function` で `notify_on_write_dispatched` event 行を確認。なければ:
- Database Webhook が未設定。[webhooks.md](webhooks.md) 参照
- webhook の Bearer secret がローテートされて function が `unauthorized` 拒否。[secrets-rotation.md](secrets-rotation.md) → `SKEINLY_DATABASE_WEBHOOK_SECRET`

### Step 3: 受信者のデバイス token は登録されているか

```sql
SELECT user_id, platform, locale, updated_at
FROM public.device_tokens
WHERE user_id = '<recipient_uid>';
```

空なら → ユーザーが push 登録してない。最頻原因: 権限プロンプトを拒否、またはプロンプトトリガーが起こってない (PR-list-with-incoming + PR-detail-opened + first-PR-comment-posted; [ADR-017 §3.6](../../en/adr/017-phase-24-push-notifications.md) 参照)。

### Step 4: APNs / FCM は成功を返したか

`notify-on-write` ログで `send_stats.success` (per-dispatch counter) vs `send_stats.delete_token` (token 無効 → DELETE) vs `send_stats.transient_error` (APNs/FCM 5xx — 次のイベントで再試行) を確認。

token 無効化のよくある原因:
- ユーザーが uninstall + reinstall (FCM 404 UNREGISTERED)
- iOS ユーザーが設定 → 一般 → 転送またはリセット (APNs 410 Unregistered)
- Bundle ID 変更で再登録漏れ (FCM 403 SENDER_ID_MISMATCH)

### Step 5: APNs / FCM クレデンシャルは有効か

function ログが per-event token outcomes でなく `config_error` を出している場合、function が APNs か FCM への認証に失敗している:
- APNs: `APPLE_APNS_KEY_P8`, `APPLE_APNS_KEY_ID`, `APPLE_TEAM_ID`。[secrets-rotation.md](secrets-rotation.md) でローテート
- FCM: `FIREBASE_SERVICE_ACCOUNT_JSON`。同様

---

## 症状: RevenueCat webhook が来ない

ユーザーが購入完了したが `subscriptions` 行が出ない。

`mcp__supabase__get_logs service=edge-function` で `revenuecat_webhook_*` event 行を確認。RevenueCat dashboard → Events で event id と webhook 配信が ack されたかを確認。

### 失敗モード

| 症状 | 原因 | アクション |
|---|---|---|
| RevenueCat が「Webhook delivery failed: 401」 | `REVENUECAT_WEBHOOK_SECRET` が Supabase + RevenueCat dashboard で不一致 | [secrets-rotation.md](secrets-rotation.md) でローテート、両側を update |
| RevenueCat 「Webhook delivery succeeded」だが `subscriptions` 空 | App user id マッピング | auth 後 `Purchases.logIn(skeinly_user_id)` を呼んでいるか確認。webhook の `app_user_id` フィールドが `auth.users(id)` の行と一致する必要 |
| `subscriptions` 行は存在するが古い status | 配信順序の入れ替わり | `last_verified_at` が monotonic か確認。`upsert_subscription_from_webhook` RPC がこれをガード — 新しい状態が勝つはず |

---

## 症状: バグ報告送信が 401 を返す

```bash
# function を直接 smoke test:
PUB=<sb_publishable_test_key>
curl -i -X POST "https://<project>.supabase.co/functions/v1/submit-bug-report" \
  -H "apikey: ${PUB}" \
  -H "Content-Type: application/json" \
  -d '{"title":"smoke test","body":"test"}'
```

期待: HTTP 200 + `{"ok":true,"issue_number":<n>,"html_url":"..."}`。

401 のよくある原因:
- `supabase/config.toml` の `verify_jwt = true` が誤って残っている。function は `verify_jwt = false` で走る必要 (ADR-020 §Q4)。修正して `supabase functions deploy submit-bug-report`
- クライアントが `apikey: <publishable>` でなく `Authorization: Bearer <publishable>` を送っている。canonical なヘッダ形式は [spec/symbol-pack-delivery.md](../../en/spec/symbol-pack-delivery.md) と ADR-020 の auth-model 判断参照

GitHub からの 422:
- `feedback` Issue ラベルが `b150005/skeinly` に未作成。Issues → Labels → New で作成
- GitHub App の installation がリポジトリから外れている。ADR-020 §6 のユーザー側手順で再 install

---

## 症状: 認証が失敗 / 「セッション切れ」ループ

`mcp__supabase__get_logs service=auth`

よくあるモード:

| 症状 | 原因 | アクション |
|---|---|---|
| 全 login が 400 で fail | `SUPABASE_PUBLISHABLE_KEY` ローテート済だがアプリが旧 key を ship | 新 key でアプリを re-build + re-deploy |
| 特定 OAuth provider のみ fail | Universal Links 設定後の OAuth callback URL drift | Supabase Auth → Providers の callback URL が Universal Link spec と一致するか確認 |
| コールド起動で「セッション切れ」無限ループ | refresh token 失効 + offline retry のハング | `EncryptedSharedPreferences` (Android) / Keychain (iOS) が読めるか確認。iOS は backup-restore 後に Keychain access 失敗があり得る |

---

## 症状: Release CI 失敗

run を引く:

```bash
gh run view <run-id> --log-failed
```

| 失敗ジョブ | よくある原因 |
|---|---|
| `build-android` `:androidApp:bundleRelease` | keystore secret 不整合 (`KEYSTORE_BASE64` + パスワード secret 3 個)。[secrets-rotation.md](secrets-rotation.md) → KEYSTORE |
| `build-android` `:androidApp:publishBundle` | Play Publisher SA 期限切れ or 権限取消。[secrets-rotation.md](secrets-rotation.md) → `GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64` |
| `build-ios` fastlane "No provisioning profile found" | cert + profile チェーン切れ。`.p12` を re-export して再登録 |
| `build-ios` "upload_to_testflight 401" | App Store Connect API key ローテート済。[secrets-rotation.md](secrets-rotation.md) → `APP_STORE_CONNECT_API_KEY_*` |
| `verifyIosBetaFlag` | `version.properties` major と `iosApp/project.yml` IS_BETA の YES/NO が乖離。どちらか修正 |
| `verifyI18nKeys` | 5 i18n ソースのいずれかでキー欠落。`make i18n-verify` をローカル実行で差分を見る |

---

## 症状: 障害がノベルで search すると他者も hit している

**upstream issue triage** 規律を適用:

1. upstream ライブラリの issue tracker を検索 (OSS は GitHub Issues、closed-source はベンダーサポートページ)
2. 同じ症状の open issue があれば known limitation として扱う — CLAUDE.md の `## CI Known Limitations` に upstream link + `Re-check by` 日付付きで記載
3. 該当 issue がなければ filed する (or 我々の wishlist of canonical reports をチェック) してから patch around

完全な規律: [.claude/docs/process/upstream-issue-triage.md](../../../.claude/docs/process/upstream-issue-triage.md)。upstream チェック前に workaround を当てない — 規律がスリップすると "Known Limitations" リストが死亡エントリで膨らむ。

---

## エスカレーション

一次トリアージが ~30 分で fix に収斂しない場合:

1. 失敗ユーザーの id、タイムスタンプ窓、プラットフォーム、関連ログスニペットを保存
2. `b150005/skeinly` に `incident` ラベルで GitHub Issue を起票 (ラベルなければ作成)
3. ユーザーデータ完全性が危うい場合 (例: subscription 課金が間違っている) は機能開発より優先

## 横断参照

- [spec/symbol-pack-delivery.md](../../en/spec/symbol-pack-delivery.md)
- [spec/suggestion-flow.md](../../../.claude/docs/spec/suggestion-flow.md)
- ADR-017 (push), ADR-018 (push send paths), ADR-016 (Pro / packs), ADR-020 (bug reports)
- [secrets-rotation.md](secrets-rotation.md)
- [webhooks.md](webhooks.md)
- [.claude/docs/process/upstream-issue-triage.md](../../../.claude/docs/process/upstream-issue-triage.md)
