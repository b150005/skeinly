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

## 関連リンク

- 各 secret の OBTAIN/VERIFY/REGISTER 手順: [release-secrets.md](release-secrets.md)
- ブランチ保護 + CI 要件: [repo-policy.md](repo-policy.md)
- プライバシーポリシー原典: [docs/public/privacy-policy/](../public/privacy-policy/)
- Phase 39 alpha rubric: [phase/phase-39-beta-rubric.md](phase/phase-39-beta-rubric.md)
