# ベンダーアカウント設定 — Phase A0 手順書

> 英語原典: [docs/en/vendor-setup.md](../en/vendor-setup.md)

ベンダーアカウントを設定し、[release-secrets.md](release-secrets.md) で登録対象となる成果物（証明書、鍵、プロファイル、JSON ファイル）を取得するための逐次手順です。alpha1 リリース前準備の Phase A0 で、`v1.0.0-alpha1` タグ push 前に完了している必要があります。

このドキュメントは **Apple 側の設定** (Apple Developer Portal + App Store Connect + Universal Links) に絞っています。理由は、Provisioning Profile 生成後に Capability を追加すると profile 再生成のサイクルが必要になり、これがクリティカルパスだからです。その他のベンダー（Google Play、Firebase、Sentry、PostHog）の取得手順は [release-secrets.md](release-secrets.md) の各 secret セクション内 OBTAIN ステップに記述しています（既存パターン）。

## Skeinly 定数

ベンダーポータルのフォーム入力に使う値です。プロンプトされたら以下を入力してください。

| 項目 | 値 |
|---|---|
| Bundle ID (iOS) | `io.github.b150005.skeinly` |
| Application ID (Android) | `io.github.b150005.skeinly` |
| アプリ名 | Skeinly |
| デフォルト言語 | English (U.S.) |
| Apple Developer Team ID | (10文字の ID。enrollment 後に確認) |
| プライバシーポリシー URL | `https://b150005.github.io/skeinly/privacy-policy/` |
| サポート URL | `https://github.com/b150005/skeinly/issues` |

## 前提条件

- **Apple Developer Program 登録** — $99/年、<https://developer.apple.com/programs/>。Phase A0 全体の前提
- **Xcode 26+ 環境の Mac** — Keychain Access と `.p12` エクスポート用。[README 前提条件](../../README.md#前提条件)参照
- **`gh` CLI 認証済み** — `gh auth login`

## Phase A0a — Apple Developer Portal

### A0a-1: App ID 作成

1. <https://developer.apple.com/account> → **Certificates, Identifiers & Profiles** → **Identifiers** → **+**
2. **App IDs** → Continue → **App** → Continue
3. Description: `Skeinly`
4. Bundle ID: **Explicit** → `io.github.b150005.skeinly`
5. **Capabilities — 4つすべて有効化**:
   - **Sign In with Apple** — *Configure* クリック → **Enable as a primary App ID** 選択 → Save
   - **Push Notifications** — チェックボックスのみ。設定は APNs key 生成時 (Phase A0a-2) で実施
   - **Associated Domains** — チェックボックスのみ。ドメイン値はアプリレベルの entitlements ファイルで設定（App ID 上ではない）
   - **In-App Purchase** — チェックボックスのみ
6. Continue → Register

その他の Capability (HealthKit、CloudKit、Game Center 等) は **alpha1 では不要**。後から追加すると Provisioning Profile 再生成が必要になるので、機能が実際に必要になってから有効化してください。

### A0a-2: APNs Auth Key (`.p8`) 生成

1. 同じ Certificates, Identifiers & Profiles → **Keys** → **+**
2. Name: `skeinly APNs`
3. Enable: **Apple Push Notifications service (APNs)**
4. Continue → Register
5. **`.p8` ファイルを即座にダウンロード**。一度限りのダウンロード — Apple 側は生成後に秘密鍵を破棄します。ダウンロードファイル名は `AuthKey_<KEY_ID>.p8`
6. **10文字の Key ID** を控える（Keys 一覧に表示）
7. `.p8` ファイルと Key ID をパスワードマネージャに保存

同じ APNs key で **Production と TestFlight 両方**の push を Skeinly の Supabase Edge Function から送信できます — 環境別の鍵は不要です。

**登録**:
- Base64 エンコード → [release-secrets.md](release-secrets.md#supabase-edge-function-secrets) の通り `APPLE_APNS_KEY_BASE64` を Supabase Edge Function secret として登録
- `APPLE_APNS_KEY_ID` Supabase Edge Function secret として登録 (10文字)

### A0a-3: Apple Distribution Certificate

[release-secrets.md §1](release-secrets.md#1-apple_distribution_cert_base64) 参照。alpha1 固有の変更はなし — 既存の Distribution 証明書は、App ID で Capability を有効化した後は4 capability 全てをカバーします。

### A0a-4: Provisioning Profile

[release-secrets.md §3](release-secrets.md#3-apple_provisioning_profile_base64) 参照。

**順序が重要**: Profile は A0a-1 の **後** に生成（4 capability すべてが profile に焼き込まれるため）。Capability 追加前に生成された profile はそれを含みません。再生成して `APPLE_PROVISIONING_PROFILE_BASE64` を再登録する必要があります。

### A0a-5: App Store Connect API Key

[release-secrets.md §5–7](release-secrets.md#5-app_store_connect_api_key_base64) 参照。

同じ key を以下の用途に使います:
- fastlane 経由の TestFlight アップロード (CI / GitHub Secrets コンテキスト)
- App Store Server API による IAP レシート検証 (Supabase Edge Function コンテキスト)

key を **両方**のコンテキストに登録: GitHub Secret `APP_STORE_CONNECT_API_KEY_BASE64` と Supabase Edge Function secret `APP_STORE_CONNECT_API_KEY` (key ファイルは1つ、登録場所が2箇所)。

## Phase A0b — App Store Connect (アプリ + IAP)

### A0b-1: アプリ作成

1. <https://appstoreconnect.apple.com> → **My Apps** → **+** → **New App**
2. Platform: **iOS** (チェックボックス)
3. Name: `Skeinly`
4. Primary Language: **English (U.S.)**
5. Bundle ID: `io.github.b150005.skeinly` を選択 (Phase A0a-1 で作成済みのもの)
6. SKU: `skeinly-001`
7. User Access: **Full Access**
8. Create

### A0b-2: Subscription Group

1. アプリ詳細 → **Monetization** → **Subscriptions** → **Create Subscription Group**
2. Reference Name: `Skeinly Pro`
3. Localizations:
   - English (U.S.): Display Name `Skeinly Pro`
   - Japanese: Display Name `Skeinly Pro` (ブランド名は全 locale で同一)

### A0b-3: IAP Products — Pro グループ内に2つの subscription

| 項目 | Monthly | Yearly |
|---|---|---|
| Product ID | `skeinly.pro.monthly` | `skeinly.pro.yearly` |
| Reference Name | Monthly Pro | Yearly Pro |
| 価格 (USD) | $3.99 | $24.99 |
| 価格 (JPY) | ¥600 | ¥3,800 |
| Subscription Duration | 1ヶ月 | 1年 |
| Free Trial | 7日 (Introductory Offer → Free → 1 week) | 7日 |
| Localized Display Name (EN) | Monthly Pro | Yearly Pro |
| Localized Display Name (JA) | 月額 Pro | 年額 Pro |
| 説明 (EN) | Unlimited projects, structured chart editing, share send, pull request creation. Renews monthly. | Unlimited projects, structured chart editing, share send, pull request creation. Renews yearly (about 48% off vs monthly). |
| 説明 (JA) | プロジェクト無制限、構造化チャート編集、共有送信、PR 作成。毎月自動更新。 | プロジェクト無制限、構造化チャート編集、共有送信、PR 作成。年額更新（月額より約 48% お得）。 |
| Family Sharing | Off (alpha) | Off (alpha) |
| App Review Information | Sandbox tester credentials を申請時に App Review に提供 | Sandbox tester credentials を申請時に App Review に提供 |

両方の product 作成後、Pro グループに格納され、StoreKit 2 SDK の `Product.products(for:)` 呼び出しで両方が surface します。

### A0b-4: IAP テスト用 Sandbox Tester

1. App Store Connect → **Users and Access** → **Sandbox Testers** → **+**
2. メールアドレスを別にした sandbox tester を最低2人作成。alpha 開発中の購入 + 復元 + 解約 + 自動更新フローのテストに使います
3. 認証情報をパスワードマネージャに記録 — alpha テスター本人は sandbox tester を使えません。彼らは `subscriptions.platform = 'alpha-grant'` sentinel 経由で本物の Pro grant を受けます

### A0b-5: プライバシー宣言

1. アプリ詳細 → **App Privacy**
2. Privacy Policy URL: `https://b150005.github.io/skeinly/privacy-policy/`
3. **収集データ種別** — alpha1 のスコープに合わせて以下を宣言 (Sentry + PostHog + Feedback と整合):
   - **Identifiers**: User ID (Supabase auth UUID), Device ID (PostHog distinct_id, opt-in)
   - **User Content**: Other User Content (knitting project data, patterns, comments)
   - **Diagnostics**: Crash Data (Sentry), Performance Data (Sentry)
   - **Usage Data**: Product Interaction (PostHog, opt-in)
4. 各データ種別への回答:
   - Linked to user? → Yes (User ID は紐付き)
   - Used for tracking? → **No** (広告目的のサードパーティ共有なし)

Phase 27a プライバシーポリシーは既にこれらに言及済み — alpha1 アップデート時に整合確認すること。

## Phase A0c — Universal Links (AASA)

### A0c-1: AASA ホスティング戦略の決定

Apple は AASA ファイルを `https://<domain>/.well-known/apple-app-site-association` (またはルート `https://<domain>/apple-app-site-association`) に HTTPS で、**`Content-Type: application/json`** で、**リダイレクトなし**で配信する必要があります。アプリの `applinks:<domain>` entitlement は同じ `<domain>` を指します。

**Skeinly のホスティング状況**: GitHub Pages は本プロジェクトを `https://b150005.github.io/skeinly/` (ユーザー `b150005` 配下のプロジェクトページ) で配信しています。AASA は関連ドメインの apex に必要 — `https://b150005.github.io/.well-known/apple-app-site-association`。この apex はユーザー `b150005` の **GitHub user site** に対応し、別リポジトリ `b150005/b150005.github.io` に存在します。プロジェクトリポジトリ `b150005/skeinly` ではこの apex で AASA を配信できません。

3つの選択肢、推奨順:

**選択肢 A — `b150005/b150005.github.io` user site を作成 (無料、alpha 推奨)**
- ユーザー `b150005` 配下に新規 public リポジトリ `b150005.github.io`
- リポジトリルートに `.well-known/apple-app-site-association` と `.well-known/assetlinks.json` を配置
- GitHub Pages は `main` ブランチルートから自動デプロイ
- 初回デプロイ後すぐに `https://b150005.github.io/.well-known/apple-app-site-association` で解決
- アプリの entitlements に `applinks:b150005.github.io` を追加。path matching を `/skeinly/share/*` 等に制限することで、user site が他のパスを誤ってアプリに routing するのを防ぐ

**選択肢 B — カスタムドメイン (有料、v1.0 推奨)**
- `skeinly.app` 等を購入 (Cloudflare Registrar at-cost で約 $10/年)
- GitHub Pages CNAME → カスタムドメイン
- AASA は `https://skeinly.app/.well-known/apple-app-site-association`
- production リリース時のブランディングがクリーン

**選択肢 C — Universal Links を v1.0 に延期 (alpha でドメイン投資なし)**
- alpha 中は `skeinly://share/<token>` URI scheme のみ
- SMS / メール経由で送信された share URL がアプリを自動起動しないことを許容
- App ID の Universal Links Capability は v1.0 まで無効のまま (その時点で Provisioning Profile 再生成 — 一回だけのコスト)

**alpha1 推奨**: 選択肢 A。コストはゼロ、ホスティングは `b150005` の他プロジェクトと統一、後から選択肢 B への移行は CNAME 変更のみ。

### A0c-2: AASA ファイル内容

ホスティング先決定後、`<host>/.well-known/apple-app-site-association` に以下を配置:

```json
{
  "applinks": {
    "details": [
      {
        "appIDs": ["TEAMID.io.github.b150005.skeinly"],
        "components": [
          { "/": "/skeinly/share/*", "comment": "Direct share invite deep link" },
          { "/": "/skeinly/pull-request/*", "comment": "PR open/comment/merge deep link" },
          { "/": "/skeinly/shared-content/*", "comment": "Shared pattern/project deep link" }
        ]
      }
    ]
  }
}
```

`TEAMID` は 10 文字の Apple Developer Team ID に置換。選択肢 B の場合は `/skeinly/...` プレフィックスを削除（カスタムドメインなのでパス無し）。

### A0c-3: assetlinks.json (Android App Links 対応)

`<host>/.well-known/assetlinks.json` に以下を配置:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "io.github.b150005.skeinly",
    "sha256_cert_fingerprints": [
      "<upload-keystore.jks の SHA-256 fingerprint>"
    ]
  }
}]
```

SHA-256 fingerprint は `keytool -list -v -keystore upload-keystore.jks` で取得 ([release-secrets.md §8](release-secrets.md#8-keystore_base64) の keystore 生成手順参照)。

### A0c-4: デプロイ後の検証

```bash
# AASA: 200 応答、JSON、リダイレクトなし、Content-Type: application/json 必須
curl -I https://b150005.github.io/.well-known/apple-app-site-association

# assetlinks.json も同条件
curl -I https://b150005.github.io/.well-known/assetlinks.json

# Apple 公式 validator (代替)
# https://search.developer.apple.com/appsearch-validation-tool
```

## Phase A0d — RevenueCat セットアップ

RevenueCat はクロスプラットフォーム IAP / サブスク管理レイヤ。Apple StoreKit と Google Play Billing を抽象化し、プラットフォームごとに Public SDK Key 1 個を発行 — クライアントは `Purchases.configure()` にこれを渡すだけで済む。**前提**: A0a-5 (App Store Connect API Key) + A0b-3 (App Store Connect で IAP product 作成済) + Google Play Console で本アプリを公開し Service Account API access を有効化済 (release-secrets EF-4 `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` 同 JSON を使う)。

### A0d-1: RevenueCat Project 作成

1. [RevenueCat Dashboard](https://app.revenuecat.com) にサインイン。
2. **+ New Project** → Project Name: `Skeinly` → Create。
3. **Project Settings** → **General** → Project ID を確認。

### A0d-2: iOS App 連携 (App Store Connect)

1. 新 Project 内: **Project Settings** → **Apps** → **+ New** → **App Store**。
2. App name: `Skeinly iOS`。
3. Bundle ID: `io.github.b150005.skeinly`。
4. **App Store Connect API Key**: A0a-5 で生成した `.p8` をアップロード + Key ID + Issuer ID を入力 (RevenueCat はこれをキャッシュして product メタデータ取得 + サブスク状態変化の観測に使う)。
5. **App-specific Shared Secret**: API Key 提供時は不要 (legacy 仕組み)。
6. Save → RevenueCat が product 一覧を取得するまで待機 (通常 1 分未満)。
7. **API Keys** タブ → **Public iOS SDK Key** (`appl_` 始まり) をコピー → [release-secrets §19](release-secrets.md#19-revenuecat_api_key_ios) の手順で `REVENUECAT_API_KEY_IOS` として登録。

### A0d-3: Android App 連携 (Google Play)

1. 同 Project: **Project Settings** → **Apps** → **+ New** → **Play Store**。
2. App name: `Skeinly Android`。
3. Package name: `io.github.b150005.skeinly`。
4. **Service Account Credentials**: [release-secrets EF-4](release-secrets.md#ef-4-google_play_service_account_json) で `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` に使った同じ JSON ファイルをアップロード (single source of truth — RevenueCat は product + receipt validation のために Play Developer API read access が必要)。
5. Save → RevenueCat が Play Console product 一覧を取得するまで待機。
6. **API Keys** タブ → **Public Android SDK Key** (`goog_` 始まり) をコピー → [release-secrets §20](release-secrets.md#20-revenuecat_api_key_android) の手順で `REVENUECAT_API_KEY_ANDROID` として登録。

### A0d-4: Entitlement + Offering の作成

Entitlement は user に付与する*能力* (例: `pro`)。Offering はペイウォールが提示する*product バンドル*。両者とも RevenueCat 側の概念で、user が実際に購入した IAP product を RevenueCat が解決し対応 Entitlement を自動付与する。

1. **Product Catalog** → **Entitlements** → **+ New Entitlement**:
   - Identifier: `pro`
   - Display Name: `Skeinly Pro`
   - Attached Products (product import 後): `skeinly.pro.monthly` (iOS + Android variant、Play Console product ID が一致する場合) + `skeinly.pro.yearly` 両方。

2. **Product Catalog** → **Offerings** → **+ New Offering**:
   - Identifier: `default`
   - Description: `Default paywall offering`
   - **+ Add Package** → identifier `$rc_monthly` → `skeinly.pro.monthly` (iOS + Android) 紐付け。
   - **+ Add Package** → identifier `$rc_annual` → `skeinly.pro.yearly` (iOS + Android) 紐付け。
   - この Offering を **Current** に設定。

クライアントは `Purchases.shared.getOfferings()` を呼び `current` offering の package を読んでペイウォールを描画する — クライアントコードに product ID をハードコードしない。

### A0d-5: Webhook 統合 (alpha+)

Webhook は RevenueCat からサブスク状態変化 (renewal / cancel / refund / billing issue) を Supabase Edge Function に push し、サーバーサイド Pro entitlement 状態をクライアント polling なしで同期する。

1. 強い shared secret 生成: `openssl rand -hex 32`。
2. **Integrations** → **Webhooks** → **+ New Webhook**:
   - Webhook URL: `https://<project-ref>.supabase.co/functions/v1/revenuecat-webhook` (Phase 41 — F1 で作成する Edge Function)。
   - Authorization header: 手順 1 の secret を貼り付け。
   - Environment filter: alpha 期間中は Sandbox + Production 両方 (デフォルト) のままで OK。
3. Save。
4. 同じ secret を [release-secrets EF-5](release-secrets.md#ef-5-revenuecat_webhook_secret) の手順で `REVENUECAT_WEBHOOK_SECRET` として登録。

Edge Function 自体は F1 (Pro サブスクロールアウト) と同時に作成する。それまではこのステップは保留可能 — RevenueCat はクライアント側 entitlement 状態を webhook なしでも正しく維持する。webhook はサーバーサイド reconciliation 用途。

### A0d-6: 統合テスト

1. A0b-4 で作成した Sandbox Tester (iOS) + Google Play Internal Testing トラックに登録した license tester アカウント (Android debug ビルド) を使う。
2. debug ビルドからテスト購入実行 (`Purchases.configure(apiKey: "appl_..." or "goog_...")` + `Purchases.purchase(package:)`)。
3. RevenueCat Dashboard → **Customers** → テスト user を検索 → `pro` entitlement が予期した有効期限で付与されていることを確認。

## Phase A0 検証チェックリスト

`v1.0.0-alpha1` タグ push 前に確認:

- [ ] Apple Developer App ID `io.github.b150005.skeinly` が 4 capability 有効で存在 (Sign In with Apple、Push Notifications、Associated Domains、In-App Purchase)
- [ ] APNs Auth Key `.p8` ダウンロード済 + パスワードマネージャ保存済
- [ ] APPLE_APNS_KEY_BASE64 + APPLE_APNS_KEY_ID を Supabase Edge Function secret として登録済
- [ ] Capability 追加後に Provisioning Profile を再生成済
- [ ] APPLE_PROVISIONING_PROFILE_BASE64 を GitHub Secret として(再)登録済
- [ ] App Store Connect で `Skeinly` アプリを bundle ID `io.github.b150005.skeinly` で作成済
- [ ] Subscription Group `Skeinly Pro` 作成済
- [ ] 2 つの IAP product 作成済: `skeinly.pro.monthly` ($3.99/¥600)、`skeinly.pro.yearly` ($24.99/¥3,800)、両方 7日 free trial
- [ ] Sandbox Tester を最低 2 人作成済
- [ ] App Privacy 宣言を Sentry + PostHog + Feedback データ種別で提出済
- [ ] AASA ホスティング決定 (選択肢 A / B / C) と AASA + assetlinks.json デプロイ済 (選択肢 A または B の場合)
- [ ] RevenueCat Project `Skeinly` 作成済 + iOS + Android Apps 連携済
- [ ] Entitlement `pro` + Offering `default` を monthly + yearly package 構成で設定済
- [ ] `REVENUECAT_API_KEY_IOS` + `REVENUECAT_API_KEY_ANDROID` を GitHub Secret として登録済
- [ ] Webhook 統合は F1 (Phase 41) まで保留 (それ以前にサーバーサイド entitlement reconciliation が必要な場合のみ先行設定)

## 関連リンク

- 各 secret の OBTAIN/VERIFY/REGISTER 手順: [release-secrets.md](release-secrets.md)
- ブランチ保護 + CI 要件: [repo-policy.md](repo-policy.md)
- プライバシーポリシー原典: [docs/public/privacy-policy/](../public/privacy-policy/)
- Phase 39 alpha rubric: [phase/phase-39-beta-rubric.md](phase/phase-39-beta-rubric.md)
