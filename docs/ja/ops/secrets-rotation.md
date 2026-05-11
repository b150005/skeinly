# Runbook — Secret ローテーション

> 英語原典: [docs/en/ops/secrets-rotation.md](../../en/ops/secrets-rotation.md)
>
> **目的**: secret 別のローテーション手順。漏洩疑い、アクセス保持メンバーの離脱、年次定期に使用。
>
> **対象**: secret ローテーショントリガーに対応する運用者。
>
> **registry との対応**: 本 runbook は *手順* のみ。secret *レジストリ* (用途、初回登録、検証) は [release-secrets.md](../../en/release-secrets.md) (英語)。ローテーションは registration の再実行と論理的に同じなのでペア構造。

## いつローテートするか

- secret アクセス保持メンバーの離脱
- オリジナルクレデンシャル (`.p12`, `.p8`, `.jks`, JSON key) を含むラップトップの紛失・盗難
- automation key 漏洩疑い (例: public ログに誤って echo)
- 年次衛生 (API key + signing profile は 12 ヶ月推奨)
- ベンダーセキュリティアドバイザリ指示時

## ローテーションチートシート

| Secret | 手順 | 頻度 |
|---|---|---|
| `APPLE_DISTRIBUTION_CERT_BASE64` + password | Apple Developer → Certificates → revoke + 新規 + `.p12` re-export → 両方再登録 | 年次 or インシデント時 |
| `APPLE_PROVISIONING_PROFILE_BASE64` | DEPRECATED 2026-05-11。sigh が CI 毎回 Portal から fetch。Portal Web UI での再 Generate は capability 変更 or 年次失効時のみ | GitHub 側は N/A |
| `APPLE_TEAM_ID` | 移籍しない限り変更不可 | 永久不要 |
| `APP_STORE_CONNECT_API_KEY_*` (3 secrets) | ASC → Team Keys → revoke + 新規生成 + 3 個 (base64 / id / issuer) 全部再登録 | 年次 |
| `KEYSTORE_*` (4 secrets) | **ローテートしない**。keystore 紛失は Play Store 更新を永久に壊す。Google Play "App Signing by Google Play" key reset は最終手段の recovery であって routine ローテーションではない | 永久不要 |
| `SUPABASE_PUBLISHABLE_KEY` | Supabase Dashboard → Project Settings → API Keys → 新 Publishable key 生成 → 再登録 + アプリ re-build | 漏洩疑い時 |
| `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` (Environment ごと) | パッケージ or 署名 SHA-1 変更時のみ Firebase Console から再 download | 実質永久不要 |
| `FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64` (Environment ごと) | Bundle ID 変更時のみ Firebase Console から再 download | 実質永久不要 |
| `SENTRY_DSN_*` | Sentry → Settings → Project → Client Keys → revoke + 新規 → 再登録 | 漏洩疑い時 |
| `SENTRY_AUTH_TOKEN` | Sentry → **Organization Settings** → Auth Tokens → revoke + 再生成 (Organization Token、User Token ではない) | 年次 |
| `POSTHOG_PROJECT_API_KEY` | PostHog → Settings → Project → Project API Key を reset | 漏洩疑い時 |
| `REVENUECAT_API_KEY_*` | RevenueCat → Project Settings → Apps → Public SDK Key → revoke + 新規発行 | 漏洩疑い時 |
| `GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64` (Environment scoped) | Cloud Console → IAM → Service Accounts → `google-play-publisher@...` → revoke + 新 key → `gh secret set --env production` で再登録 | 年次 |
| `APPLE_APNS_KEY_P8` / `APPLE_APNS_KEY_ID` (Edge Function) | Apple Developer → Keys → revoke + 新規生成 + `supabase secrets set` で両方登録 + `supabase functions deploy notify-on-write` | 年次 |
| `FIREBASE_SERVICE_ACCOUNT_JSON` (Edge Function) | Firebase Console → Service Accounts → revoke + 新 key + `supabase secrets set` + `supabase functions deploy notify-on-write` | 年次 |
| `REVENUECAT_WEBHOOK_SECRET` (Edge Function) | `openssl rand -hex 32` で新値 → RevenueCat Webhook の Authorization header を update → `supabase secrets set` + re-deploy | 年次 |
| `SKEINLY_DATABASE_WEBHOOK_SECRET` (Edge Function) | `openssl rand -hex 32` で新値 → `supabase secrets set` + re-deploy + 3 個の Database Webhook 全部の Authorization HTTP header を Supabase Dashboard で update | 年次 |
| `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM` (Edge Function) | GitHub App settings → 新 private key 生成 → `.pem` download → `supabase secrets set` + `supabase functions deploy submit-bug-report` → App settings page で旧 key を revoke | 年次 |
| `SKEINLY_BUGREPORT_APP_ID` / `SKEINLY_BUGREPORT_INSTALLATION_ID` | App について immutable。ローテートしない | 永久不要 |

## 詳細手順

具体的なコマンドサンプルは英語版 [secrets-rotation.md](../../en/ops/secrets-rotation.md) の "Detailed procedures" 節を参照。代表例:

### iOS distribution certificate

```bash
# 1. Apple Developer Portal で revoke + 新 cert 発行 + .p12 export
# 2. Keychain Access に import + .p12 export (export password を設定)
cd iosApp && bundle exec fastlane sigh --force

# 3. GitHub Secrets 2 個を再登録
base64 -i <new>.p12 | pbcopy
gh secret set APPLE_DISTRIBUTION_CERT_BASE64
echo "<export password>" | pbcopy
gh secret set APPLE_DISTRIBUTION_CERT_PASSWORD

# 4. ローカルビルドで確認
make release-ipa-local
```

### Supabase Edge Function secrets

`supabase secrets set` が Edge Function secret 7 個の唯一の登録経路:

```bash
# 例: APNs key ローテーション
supabase secrets set APPLE_APNS_KEY_P8="$(cat new.p8)"
supabase secrets set APPLE_APNS_KEY_ID="ABC1234567"
supabase functions deploy notify-on-write
# smoke test (supabase/functions/notify-on-write/README.md 参照)
```

> bug-report と database-webhook secret の `SKEINLY_` プレフィックスは load-bearing。Supabase は `SUPABASE_*` をプラットフォーム injected env var 用に予約しており `supabase secrets set` がそのプレフィックス名を reject する。グループ化のために `SUPABASE_BUGREPORT_*` に rename しない。

### Database Webhook Bearer secret

secret が 2 箇所にあって一致必須なので tricky:

```bash
# 1. 新値生成
NEW_SECRET=$(openssl rand -hex 32)

# 2. Supabase Edge Function 側に登録
supabase secrets set SKEINLY_DATABASE_WEBHOOK_SECRET="${NEW_SECRET}"

# 3. consumer function を deploy
supabase functions deploy notify-on-write

# 4. 3 個の webhook 全部の Authorization HTTP header を Dashboard で update
#    Supabase Dashboard → Database → Webhooks → 各行 click → edit
#    HTTP Headers → Authorization Bearer 値を置換
#    (suggestions INSERT, suggestions UPDATE, suggestion_comments INSERT)

# 5. 1 つ PR 系イベントを起動して smoke test
#    function ログが 401 unauthorized でなく成功を出すことを確認
```

step 2 と 4 が一致しないと function が全 delivery を 401 rejection — 症状は「誰にも push が来ない、常時」。[incident-playbook.md → Push 通知が来ない](incident-playbook.md#symptom-push-notification-not-landing) 参照。

### GitHub App private key (Skeinly Feedback)

```bash
# 1. GitHub → Settings → Developer settings → GitHub Apps → Skeinly Feedback
#    → Private keys → "Generate a private key" — .pem download
#    旧 key はまだ revoke しない (新 key 検証後)

# 2. 登録
supabase secrets set SKEINLY_BUGREPORT_PRIVATE_KEY_PEM="$(cat new.pem)"

# 3. deploy
supabase functions deploy submit-bug-report

# 4. smoke test — beta build からバグ報告して Issue 着地確認

# 5. 検証後、GitHub App settings page で旧 key を revoke
```

`SKEINLY_BUGREPORT_APP_ID` と `SKEINLY_BUGREPORT_INSTALLATION_ID` は App について immutable。ローテーション不要。

## ローテーション後の検証

毎回:

1. **consumer surface を起動** して新 secret が動くことを確認:
   - iOS / Android cert: `make release-ipa-local` / `make release-aab-local` ローカル、または release タグ push
   - ASC API key: release タグ push (iOS `build-ios` ジョブが exercise)
   - Play Publisher SA: 同上
   - Supabase Edge Function secret: `supabase/functions/<name>/README.md` の smoke test
   - Database Webhook secret: PR 系 DB write を起動、`notify-on-write` ログで成功確認
   - RevenueCat webhook secret: RevenueCat Dashboard → Webhooks → "Send test event" → 200 期待

2. **旧クレデンシャルが動かないことを確認** (revocation の proof):
   - Apple cert: 旧 `.p12` は `security cms -D` で ASC validation fail
   - APNs `.p8`: Apple Developer → Keys の旧 key が "Revoked"
   - Bearer 系 (RevenueCat / Database Webhook): 旧 secret 値で test、HTTP 401 期待

3. **ローテーション日を private operations log に記録** (運用ログを維持しているなら)。カレンダーリマインダーの方が記憶より honor しやすい。

## セキュリティインシデント対応

secret 漏洩確定時 (public ログで発見、誤って public ブランチに push 等):

1. **先に revoke、後で再登録**。短時間ダウンが許容されても漏洩クレデンシャルアクセスを先に切る。revocation は上記ローテーション手順 1 と同じパス
2. **影響範囲 audit**:
   - iOS distribution cert 漏洩 → ASC で unauthorized TestFlight アップロードを漏洩窓内で確認
   - APNs `.p8` 漏洩 → APNs delivery ログ確認 (Apple は直接露出しないので worst-case 想定)
   - service-role / GitHub App PEM 漏洩 → Postgres / Issue 作成ログで unauthorized write 確認
3. **インシデント記録** を CLAUDE.md → "Tech Debt Backlog" → "Security incidents" (なければ作成) に日付 + scope + remediation 付きで

## 横断参照

- [docs/en/release-secrets.md](../../en/release-secrets.md) — 完全な secret レジストリ + 初回登録手順 + bulk verification
- [incident-playbook.md](incident-playbook.md) — 症状別障害一覧 (うち複数がローテーション必要な secret に traceable)
- [.claude/CLAUDE.md](../../../.claude/CLAUDE.md) → "Tech Debt Backlog" — 遅延したローテーション作業を追跡
