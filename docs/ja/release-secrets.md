# リリースシークレット — セットアップガイド

> English source: [docs/en/release-secrets.md](../en/release-secrets.md)

このドキュメントは、リリースパイプラインおよび Supabase Edge Function が消費する全シークレットの取得・検証・登録手順を段階的にまとめたガイドです。**GitHub Secrets 22 種**（うち Firebase 2 種は `production` / `development` Environment 別に 2 回登録、`GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64` は `production` Environment のみ登録のため**合計 24 登録**）を 8 カテゴリ + **Supabase Edge Function ランタイムシークレット 5 個**を 9 番目のカテゴリで扱います:

**GitHub Secrets**（`gh secret set` で登録、Repository scope と Environment scope を使い分け）:
- **iOS コード署名**（4 個、Repo）— Distribution 証明書 + provisioning profile + Team ID
- **App Store Connect API**（3 個、Repo）— `.p8` API キー + Key ID + Issuer ID
- **Android 署名**（4 個、Repo）— キーストア + パスワード + alias
- **Supabase ランタイム**（2 個、Repo）— バックエンド URL + Publishable key（`sb_publishable_...`）
- **Firebase クライアント**（2 個 × 2 環境 = 4 登録、**Environment**）— `google-services.json`（Android）+ `GoogleService-Info.plist`（iOS）を `Skeinly` (prod) と `Skeinly-Dev` (dev) で分離
- **クラッシュ + エラー報告**（3 個、Repo）— Sentry DSN（iOS + Android）+ Organization Auth Token
- **分析**（1 個、Repo）— PostHog プロジェクト API キー（無料枠 1 Project 上限）
- **サブスクリプション**（2 個、Repo）— RevenueCat Public iOS / Android SDK Key
- **Android リリース公開**（1 個 × 1 環境 = 1 登録、**Environment** `production`）— `gradle-play-publisher` から Google Play Internal track へ AAB をアップロードするための Service Account JSON

**Supabase Edge Function Secrets**（`supabase secrets set` で登録）:
- **iOS Push**（2 個）— APNs `.p8` + Key ID
- **Android Push**（1 個）— FCM HTTP v1 用 Firebase Service Account JSON（`firebase-adminsdk-fbsvc@...` SA）
- **Android IAP 検証**（1 個）— Play Developer API レシート検証用 Service Account JSON（`revenuecat@...` SA）
- **RevenueCat Webhook**（1 個）— Webhook 署名検証用 Authorization 値
- （App Store Connect API キーは GitHub Secrets から再利用 — 同じ `.p8` ファイルを 2 つのコンテキストで登録）

> **SA 命名規則**: `GOOGLE_PLAY_<ROLE>_SA_JSON[_BASE64]` パターンで役割を名前に焼き込む。`PUBLISHER` = リリースアップロード（書き込み系、Skeinly アプリ単位の権限）/ `IAP_VALIDATOR` = IAP レシート検証（読み取り系 + 注文管理、開発者アカウント単位の権限）。Service Account の権限セットが直交するため漏洩時の blast radius が真に分離される（PoLP）。

リリースワークフロー（[`.github/workflows/release.yml`](../../.github/workflows/release.yml)）は GitHub Secrets を `${{ secrets.* }}` として読みます。値の欠如や誤りはサイレントに失敗するもの（ビルドは成功するがアップロードされない）と、明示的に失敗するもの（署名失敗）があります。Supabase Edge Function は自身のシークレットを `Deno.env.get(...)` で読みます。本ガイドは登録**前**に値の妥当性を確認するための検証手順を含みます。

## 目次

- [前提ツール](#前提ツール)
- [GitHub にシークレットを登録する方法](#github-にシークレットを登録する方法)
- [iOS コード署名（3 シークレット）](#ios-コード署名3-シークレット)
- [App Store Connect API（3 シークレット）](#app-store-connect-api3-シークレット)
- [Android 署名（4 シークレット）](#android-署名4-シークレット)
- [Supabase ランタイム（2 シークレット）](#supabase-ランタイム2-シークレット)
- [Firebase クライアント設定（2 シークレット × 2 環境 = 4 登録）](#firebase-クライアント設定2-シークレット--2-環境--4-登録)
- [クラッシュ + エラー報告 — Sentry（3 シークレット）](#クラッシュ--エラー報告--sentry3-シークレット)
- [分析 — PostHog（1 シークレット）](#分析--posthog1-シークレット)
- [サブスクリプション — RevenueCat（2 シークレット）](#サブスクリプション--revenuecat2-シークレット)
- [Android リリース公開（1 シークレット × 1 環境 = 1 登録）](#android-リリース公開1-シークレット--1-環境--1-登録)
- [Supabase Edge Function Secrets（8 シークレット）](#supabase-edge-function-secrets8-シークレット)
- [一括検証](#一括検証)
- [ローテーションと失効](#ローテーションと失効)
- [セキュリティ注意事項](#セキュリティ注意事項)

## 前提ツール

| ツール | 役割 | インストール |
|---|---|---|
| `gh`（GitHub CLI） | クリップボードに値を晒さずシークレットを登録 | `brew install gh` 後 `gh auth login` |
| `base64` | バイナリファイルをテキストとしてエンコード | macOS / Linux 標準搭載 |
| `openssl` | `.p12` と `.p8` の内容検証 | macOS 標準搭載 |
| `security`（macOS） | `.mobileprovision` の内容検証 | 標準搭載 |
| `keytool`（JDK） | Android `.jks` キーストアの内容検証 | JDK 17 以上に同梱 |

開始前に `gh` 認証を済ませてください:

```bash
gh auth login
gh auth status   # 「Logged in to github.com as <あなた>」が表示されるはず
```

シークレット関連コマンドはリポジトリルート（`cd skeinly`）で実行する前提です。

## GitHub にシークレットを登録する方法

2 つの方法があります。シェル履歴やクリップボードに値が残らないため `gh secret set` を推奨します。

**方法 A — ファイル経由の `gh secret set`:**

```bash
gh secret set APPLE_DISTRIBUTION_CERT_BASE64 < distribution.p12.base64
# 登録確認（名前と最終更新日時のみ表示。値は決して表示されない）
gh secret list
```

**方法 B — 対話モードの `gh secret set`:**

```bash
gh secret set APPLE_TEAM_ID
# 値を貼り付け → Ctrl+D で終了
```

**方法 C — GitHub UI:**

リポジトリ → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**。値を貼り付けて **Add secret** をクリック。

バイナリ blob（長い base64 文字列）には方法 A、短い文字列値（Team ID、Key ID、Issuer ID）には方法 B、`gh` が使えない場合のフォールバックとして方法 C を推奨します。

シークレットを削除するには:

```bash
gh secret delete <SECRET_NAME>
```

## iOS コード署名（3 シークレット）

これら 3 つの暗号資産により、CI が Apple Developer チームとして iOS アプリに署名できるようになります。App Store Connect API 認証とは独立しています（後述）。

### 1. `APPLE_DISTRIBUTION_CERT_BASE64`

**内容**: **Apple Distribution** 証明書とその秘密鍵を含む `.p12` ファイルを base64 エンコードしたもの。

**取得手順:**

1. **Keychain Access**（macOS）を開く。
2. 左サイドバーを **default keychains** → **login** → **My Certificates** に切り替える。
3. `Apple Distribution: Your Name (TEAMID)` のような名前の証明書を探す。
   - 存在しない場合: Xcode → **Settings** → **Accounts** → Apple ID を選択 → **Manage Certificates** → `+` → **Apple Distribution** をクリック。新しい cert が Keychain Access に表示されます。
4. cert を右クリック → **Export "Apple Distribution: …"** → `distribution.p12` として保存。
5. プロンプトで**パスワードを設定**（任意の文字列、例えば `openssl rand -hex 16` の出力）。このパスワードを保存してください — `APPLE_DISTRIBUTION_CERT_PASSWORD` として使います。
6. base64 エンコード:

   ```bash
   base64 -i distribution.p12 -o distribution.p12.base64
   ```

**検証**（登録前）:

```bash
# .p12 を検査 — issuer が「Apple Distribution: Your Name」であるはず。
# プロンプトで手順 5 のパスワードを入力。
openssl pkcs12 -in distribution.p12 -info -nokeys -legacy
```

以下のような行が表示されるはず:

```
issuer=/CN=Apple Worldwide Developer Relations Certification Authority/...
subject=/UID=.../CN=Apple Distribution: Your Name (TEAMID)/...
```

**登録:**

```bash
gh secret set APPLE_DISTRIBUTION_CERT_BASE64 < distribution.p12.base64
```

**ローテーション**: Apple Developer → **Certificates, Identifiers & Profiles** → **Certificates** → 既存を revoke して手順 3 から再実行。

### 2. `APPLE_DISTRIBUTION_CERT_PASSWORD`

**内容**: 手順 5 で `.p12` エクスポート時に設定したパスワード。

**取得手順**: 自分で設定したもの。未設定または忘れた場合は強力なものを生成:

```bash
openssl rand -hex 16
```

その文字列を `.p12` エクスポート時に使ってください。忘れた場合は cert を再エクスポートする必要があります。

**検証**: 上記の `openssl pkcs12 -info` コマンドがパスワードを受け付けることが検証となります。

**登録:**

```bash
gh secret set APPLE_DISTRIBUTION_CERT_PASSWORD
# パスワードを貼り付け → Ctrl+D
```

### 3. `APPLE_TEAM_ID`

**内容**: Apple Developer の 10 文字英数字 Team ID。

**取得手順:**

- [Apple Developer](https://developer.apple.com/account) → **Membership** → **Team ID** フィールド。
- もしくはダウンロードした `.mobileprovision` から抽出:

   ```bash
   security cms -D -i Skeinly_App_Store.mobileprovision \
     | grep -A1 'TeamIdentifier' | tail -1 \
     | sed -E 's/.*<string>([^<]+)<\/string>.*/\1/'
   ```

**検証**: 10 文字、A–Z と 0–9 のみ。短い・小文字・記号があるものは誤りです。

**登録:**

```bash
gh secret set APPLE_TEAM_ID
# 貼り付け → Ctrl+D
```

## App Store Connect API（3 シークレット）

これら 3 つの認証情報により、CI が App Store Connect REST API 経由で TestFlight にビルドをアップロードできるようになります。コード署名とは独立しています — 署名が成功してもこれらが欠けていればアップロードのみが失敗します。

### 4. `APP_STORE_CONNECT_API_KEY_BASE64`

**内容**: `.p8` 秘密鍵ファイル（PKCS#8 形式）を base64 エンコードしたもの。

**取得手順:**

1. [App Store Connect](https://appstoreconnect.apple.com) にサインイン。
2. **Users and Access** → **Integrations** タブ → **Team Keys**。
3. **Generate API Key** をクリック（既存キーがある場合は `+`）。
4. 設定:
   - **Name**: 例えば `skeinly CI`。
   - **Access**: TestFlight アップロードには最低 **App Manager**。**Developer** では不足。**Admin** は動作しますが CI には過剰権限です。
5. **Generate** をクリック。
6. **`.p8` ファイルを直ちにダウンロード**。これが唯一のチャンスです — Apple は生成後に秘密鍵を破棄します。ダウンロードファイル名は `AuthKey_<KEY_ID>.p8`。
7. base64 エンコード:

   ```bash
   base64 -i AuthKey_XYZ1234567.p8 -o p8.base64
   ```

**検証:**

```bash
# 「-----BEGIN PRIVATE KEY-----」が出るはず
head -1 AuthKey_XYZ1234567.p8

# または base64 エンコード後 — 同じ最初の行にラウンドトリップで戻るはず
base64 -d p8.base64 | head -1
```

**登録:**

```bash
gh secret set APP_STORE_CONNECT_API_KEY_BASE64 < p8.base64
```

**安全なバックアップ**: `AuthKey_XYZ1234567.p8` をパスワードマネージャー（1Password 等）に直ちに移動。GitHub Secret を誤って削除した場合、同じキーを再生成することはできません — 新しいキーを生成（既存を revoke）する必要があります。

**ローテーション**: App Store Connect → Users and Access → Integrations → Team Keys → 既存キーを revoke して手順 3–7 を繰り返す。`APP_STORE_CONNECT_API_KEY_BASE64` と `APP_STORE_CONNECT_API_KEY_ID` の両方の GitHub Secret を更新。

### 5. `APP_STORE_CONNECT_API_KEY_ID`

**内容**: ダウンロードした `.p8` に紐づく 10 文字の Key ID。

**取得手順:**

- 同じページ（Team Keys）の **Key ID** 列にキー名の隣に表示。
- `.p8` のファイル名にも埋め込まれている: `AuthKey_<KEY_ID>.p8`。

**検証**: 10 文字、英数字。`.p8` のファイル名のサフィックスと一致するはず。

**登録:**

```bash
gh secret set APP_STORE_CONNECT_API_KEY_ID
# 10 文字の ID を貼り付け → Ctrl+D
```

### 6. `APP_STORE_CONNECT_ISSUER_ID`

**内容**: App Store Connect アカウント組織を識別する UUID。

**取得手順:**

- 同じ Team Keys ページ。**Issuer ID** はアクティブキーテーブルの上に表示されています。
- 形式: ハイフン区切りの `8-4-4-4-12` 桁の 16 進数（UUID v4 形式）。

**検証**: `69a6de70-03db-47e3-e053-5b8c7c11a4d1` のような形式。ハイフン込みで合計 36 文字。

**登録:**

```bash
gh secret set APP_STORE_CONNECT_ISSUER_ID
# UUID を貼り付け → Ctrl+D
```

## Android 署名（4 シークレット）

これら 4 つの認証情報で Android Release APK に署名し、Google Play が受領できるようにします。署名キーストアは **Play 上のアプリの生涯ローテーション不可** です — 紛失すると別の app ID で新規アプリとして公開し直す必要があります。慎重に扱ってください。

### 7. `KEYSTORE_BASE64`

**内容**: Android 署名キーストア（`.jks` ファイル）を base64 エンコードしたもの。

**取得手順 — 初回作成:**

```bash
keytool -genkey -v \
  -keystore upload-keystore.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias upload
```

以下が順次プロンプト:
- **キーストアパスワード** — `KEYSTORE_PASSWORD` として登録
- **識別名（DN）** — 名前/姓、組織単位、組織、市区町村、都道府県、国コード（妥当な値で OK。これらのメタデータは署名済み APK に埋め込まれ、APK を検査する誰でも閲覧可能）
- **キーパスワード** — キーストアパスワードとは別（簡略化のため同じ値に設定することが多い）。`KEY_PASSWORD` として登録

**取得手順 — 既存ファイル使用:**

既存の `.jks` ファイルを探します。よくある場所:
- `~/.android/upload-keystore.jks`
- チームのパスワードマネージャー / 共有秘密管理庫

**エンコード:**

```bash
base64 -i upload-keystore.jks -o keystore.base64
```

**検証:**

```bash
# キーストア内の全 alias を一覧 — `upload`（または選んだ alias）が含まれるはず
keytool -list -v -keystore upload-keystore.jks
# プロンプトでキーストアパスワードを入力。
```

以下のエントリが表示されるはず:
- `Alias name: upload`
- `Valid from: ... until: ...`（10000 日 = 約 27 年）
- `SHA-256: AB:CD:EF:...` フィンガープリント

**登録:**

```bash
gh secret set KEYSTORE_BASE64 < keystore.base64
```

**安全なバックアップ**: `.p8` と同じルール — 直ちにパスワードマネージャーへ移動してください。**アップロードキーストアを失った場合は復旧不可能**です。Google Play は support 経由で「key reset」を提供しますが、数週間かかるアプリ中断作業です。最低 2 つの暗号化バックアップ（パスワードマネージャー + オフライン暗号化 USB 等）を保持してください。

### 8. `KEYSTORE_PASSWORD`

**内容**: `keytool -genkey` 中に設定した「キーストアパスワード」。

**取得手順**: 自分で設定したもの。忘れた場合キーストアは復旧不可能です — 上記バックアップ参照。

**登録:**

```bash
gh secret set KEYSTORE_PASSWORD
# 貼り付け → Ctrl+D
```

### 9. `KEY_ALIAS`

**内容**: `keytool -genkey` の `-alias` に渡した alias（上記例ではデフォルトで `upload`）。

**検証:**

```bash
keytool -list -keystore upload-keystore.jks
# 「Alias name」列に有効な alias が表示。
```

**登録:**

```bash
gh secret set KEY_ALIAS
# alias を貼り付け → Ctrl+D
```

### 10. `KEY_PASSWORD`

**内容**: `keytool -genkey` 中に設定した「キーパスワード」（キーストアパスワードとは別）。CI 構成簡略化のため `KEYSTORE_PASSWORD` と同じ値にすることが多い。

**登録:**

```bash
gh secret set KEY_PASSWORD
# 貼り付け → Ctrl+D
```

## Supabase ランタイム（2 シークレット）

これらのシークレットはビルド時に Android APK と iOS IPA に焼き込まれ、アプリが Supabase バックエンドに接続できるようにします。署名シークレットでは**ありません** — ランタイム設定です。

### 11. `SUPABASE_URL`

**内容**: Supabase プロジェクトの公開 URL。

**取得手順:**

- [Supabase Dashboard](https://supabase.com/dashboard) → プロジェクトを選択 → **Project Settings** → **Data API** → **Project URL**。
- 形式: `https://<project-ref>.supabase.co`。

**検証**: `https://` で始まり `.supabase.co` で終わる。20 文字の project ref を含む。

**登録:**

```bash
gh secret set SUPABASE_URL
# 貼り付け → Ctrl+D
```

### 12. `SUPABASE_PUBLISHABLE_KEY`

> **2025-11-01 完全移行**: legacy `anon` JWT (`eyJ...`) は廃止。Skeinly では `SUPABASE_ANON_KEY` を完全に廃止し **`SUPABASE_PUBLISHABLE_KEY`** に統一しました（コード側 `expect/actual val publishableKey`、Info.plist key、CI 全ワークフロー、Gradle 環境変数すべて）。
>
> 廃止スケジュール（[Supabase Discussion #29260](https://github.com/orgs/supabase/discussions/29260)）:
> - 2025-11-01: 新規/復元プロジェクトで legacy anon キー発行停止
> - 2026 後半: legacy キー完全削除

**内容**: 「Publishable」公開 API キー。クライアントアプリへの埋め込みを想定（RLS ポリシーがデータベース層で不正アクセスを防ぐ）。形式は不透明トークン (`sb_publishable_<base62>`)。**JWT ではない** — レガシー anon key とは値の形式自体が異なる。

**取得手順:**

1. [Supabase Dashboard](https://supabase.com/dashboard) → プロジェクト (`Skeinly`) → **Project Settings** → **API Keys**
2. **Publishable key** タブ → 既存キーをコピー、または **Generate new key** で新規発行（`sb_publishable_` で始まる）

**検証**: `sb_publishable_` で始まる。レガシー JWT (`eyJ...`) ではない。Supabase docs に明記:
> "Safe to expose online: web page, mobile or desktop app, GitHub Actions, CLIs, source code"

**登録:**

```bash
gh secret set SUPABASE_PUBLISHABLE_KEY
# sb_publishable_... を貼り付け → Ctrl+D
```

**legacy `SUPABASE_ANON_KEY` の削除（移行後）:**

```bash
gh secret delete SUPABASE_ANON_KEY
gh secret list | grep -i SUPABASE  # SUPABASE_URL + SUPABASE_PUBLISHABLE_KEY のみ残ることを確認
```

**`Secret key` (`sb_secret_...`、旧 `service_role` の後継) をクライアントビルド用 GitHub Secret に登録しないでください**。Secret key は RLS をバイパスするため、APK に同梱されると重大な漏洩となります。

**RLS セマンティクス**: Publishable key と legacy anon key は同じ Postgres ロール (`anon` / `authenticated`) にマッピングされるため、RLS ポリシーの書き換えは不要です。

参照: [Supabase API Keys](https://supabase.com/docs/guides/api/api-keys)

## Firebase クライアント設定（2 シークレット × 2 環境 = 4 登録）

これらのシークレットは iOS + Android アプリ用 Firebase クライアント設定です。ビルド時に読み込まれてバイナリに焼き込まれ、Crashlytics / FCM / Analytics / Performance / Remote Config の各 SDK が Firebase に登録できるようになります。値はパッケージ名 + 署名証明書 SHA-1（Android）または Bundle ID（iOS）で Firebase 側で制限されているので漏洩時の影響範囲は小さいですが、整理目的と環境別差し替え対応のため git ignored で管理します。

### Firebase プロジェクト構成（公式推奨: 環境ごとに別プロジェクト）

Firebase 公式は **環境ごとに別 Firebase プロジェクト**を強く推奨:
> "Firebase recommends using a separate Firebase project for each environment in your development workflow."

Skeinly の構成:

| プロジェクト名 | プラン | 用途 | 設定ファイル |
|---|---|---|---|
| **`Skeinly`** | **Blaze** + 予算アラート $5/月 | production（本番リリース） | `google-services.json` + `GoogleService-Info.plist` (各 1 個) |
| **`Skeinly-Dev`** | **Spark** (無料) | development（debug ビルド + ローカル + CI） | `google-services.json` + `GoogleService-Info.plist` (各 1 個) |

**合計 4 ファイル**を base64 化し、**GitHub Environments** で `production` / `development` ごとに同名で登録します（サフィックス不要）。

### GitHub Environments セットアップ（前提）

```bash
gh api -X PUT "repos/{owner}/{repo}/environments/development" -f wait_timer=0
gh api -X PUT "repos/{owner}/{repo}/environments/production" \
  -f deployment_branch_policy[protected_branches]=true \
  -f deployment_branch_policy[custom_branch_policies]=false
# production は main ブランチのみデプロイ可、wait_timer 0
```

または GitHub UI: Settings → Environments → New environment → `production` には Deployment branch policy で `main` のみ許可。

### 13. `FIREBASE_GOOGLE_SERVICES_JSON_BASE64`（Android）

**WHAT**: Firebase Console から Android アプリ用にダウンロードした `google-services.json` の Base64 エンコード版。**`production` / `development` Environment 別**に 2 回登録（同一名）。

**OBTAIN（各環境ごとに繰り返し）:**

1. [Firebase Console](https://console.firebase.google.com) にサインイン
2. プロジェクト選択（`Skeinly` または `Skeinly-Dev`。未作成なら: **Add project** → **Disable Google Analytics**（PostHog を使うため）→ Create）
3. プロジェクト内: **Project Overview** → **Add app** → Android アイコン
4. Android パッケージ名:
   - `Skeinly` → `io.github.b150005.skeinly`
   - `Skeinly-Dev` → `io.github.b150005.skeinly.dev`（Application ID suffix `.dev` をビルドバリアントで切替）
5. App nickname: `Skeinly Android` / `Skeinly Dev Android`
6. 署名証明書 SHA-1:
   - `Skeinly`（prod）: リリース keystore（`keytool -list -v -keystore upload-keystore.jks`）の SHA-1
   - `Skeinly-Dev`: debug keystore (`~/.android/debug.keystore`、デフォルトパスワード `android`) の SHA-1
7. Continue → Continue → **`google-services.json` をダウンロード** → Continue → SDK セットアップ手順はスキップ（Gradle で別途配線）
8. Base64 エンコード:

   ```bash
   base64 -i google-services.json -o google-services.base64
   ```

**VERIFY:**

```bash
cat google-services.json | python3 -m json.tool | head -10
```

確認:
- `project_info.project_id` が Firebase プロジェクト名 (`skeinly` / `skeinly-dev`) と一致
- `client[0].client_info.android_client_info.package_name` が登録したパッケージ名と一致
- `client[0].api_key[0].current_key` が長い英数字文字列（パッケージ + SHA-1 で制限）

**REGISTER（Environment 別、同一 Secret 名）:**

```bash
# production Environment へ Skeinly プロジェクトの値を登録
gh secret set FIREBASE_GOOGLE_SERVICES_JSON_BASE64 \
  --env production < google-services-prod.base64

# development Environment へ Skeinly-Dev プロジェクトの値を登録
gh secret set FIREBASE_GOOGLE_SERVICES_JSON_BASE64 \
  --env development < google-services-dev.base64
```

Workflow 側は `environment: production` / `environment: development` を Job レベルで宣言するだけで自動的に正しい値が解決される。

**ローカル配置** (デコード後): Gradle plugin (`com.google.gms.google-services`、Phase 24.2e で適用) は build type に応じて variant 固有の `google-services.json` を選択:

```text
androidApp/src/release/google-services.json   ← Skeinly project (Blaze, prod)
                                                package: io.github.b150005.skeinly
androidApp/src/debug/google-services.json     ← Skeinly-Dev project (Spark)
                                                package: io.github.b150005.skeinly.dev
                                                (.dev suffix は androidApp/
                                                build.gradle.kts debug
                                                buildType の applicationIdSuffix
                                                による)
```

デコードコマンド:

```bash
# Production (release builds)
gh secret list --env production    # FIREBASE_GOOGLE_SERVICES_JSON_BASE64 が登録済か確認
# secret アクセス可能なマシンで (gh auth 後の自分の laptop):
gh secret get FIREBASE_GOOGLE_SERVICES_JSON_BASE64 --env production \
  | base64 -d > androidApp/src/release/google-services.json

# Development (debug builds — ローカル開発 + Maestro E2E + ci.yml debug-build ジョブ)
gh secret get FIREBASE_GOOGLE_SERVICES_JSON_BASE64 --env development \
  | base64 -d > androidApp/src/debug/google-services.json
```

> **dev/prod 分離の理由**: dev push 配信を prod analytics から隔離、署名 SHA-1 (debug keystore vs release keystore) を分離、`.dev` `applicationIdSuffix` により debug + release ビルドを同一 Android 端末で共存可能にする。`Skeinly-Dev` Firebase project (Spark プラン) は意図的に **Play Console には別アプリ登録しない** — `gradle-play-publisher` は release variant しか publish しないため、dev ビルドは `adb install` (ローカル) + emulator install (CI Maestro) で配信、Play Internal track は経由しない。

> **CI 自動デコード** (Phase 24.2e): `ci.yml` debug-build ジョブ + `e2e.yml` は `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` を `development` 環境からデコードして `androidApp/src/debug/google-services.json` に配置。`release.yml` は同名 secret を `production` 環境からデコードして `androidApp/src/release/google-services.json` に配置。両ファイルは git ignored (project root `.gitignore` の Firebase ブロック参照)。

**ROTATE**: パッケージ名変更または署名 SHA-1 変更時のみ Firebase Console から再ダウンロード。日常的なローテーションは不要。

### 13b. `FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64`（iOS）

**WHAT**: Firebase Console から iOS アプリ用にダウンロードした `GoogleService-Info.plist` の Base64 エンコード版。**`production` / `development` Environment 別**に 2 回登録（同一名）。

**OBTAIN（各環境ごとに繰り返し）:**

1. Firebase Console → 該当プロジェクト → **Project Overview** → **Add app** → iOS アイコン
2. Bundle ID:
   - `Skeinly` → `io.github.b150005.skeinly`
   - `Skeinly-Dev` → `io.github.b150005.skeinly.dev`（Xcode の Debug Configuration で切替）
3. App nickname: `Skeinly iOS` / `Skeinly Dev iOS`
4. App Store ID: 空欄でも OK（後で App Store Connect 登録後に追加）
5. Register App → **`GoogleService-Info.plist` をダウンロード** → Continue → SDK セットアップ手順はスキップ
6. Base64 エンコード:

   ```bash
   base64 -i GoogleService-Info.plist -o google-service-info.base64
   ```

**VERIFY:**

```bash
plutil -p GoogleService-Info.plist | head -10
# BUNDLE_ID, PROJECT_ID, GOOGLE_APP_ID 等が表示されるはず
```

確認:
- `BUNDLE_ID` が登録した値と一致
- `PROJECT_ID` が `skeinly` / `skeinly-dev` と一致
- `GOOGLE_APP_ID` が `1:<sender-id>:ios:<hash>` 形式

**REGISTER（Environment 別、同一 Secret 名）:**

```bash
gh secret set FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64 \
  --env production < google-service-info-prod.base64

gh secret set FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64 \
  --env development < google-service-info-dev.base64
```

iOS リリースワークフローはこれをビルド時に `iosApp/iosApp/GoogleService-Info.plist` (git ignored) にデコードします。

**ROTATE**: Bundle ID 変更時のみ Firebase Console から再ダウンロード。

## クラッシュ + エラー報告 — Sentry（3 シークレット）

これらのシークレットは iOS + Android で Sentry SDK を配線し、CI がリリースビルド後に debug symbols (iOS は dSYM、Android は mapping ファイル) を Sentry にアップロードしてスタックトレースを自動シンボル化できるようにします。

### 14. `SENTRY_DSN_IOS`

**WHAT**: iOS Sentry プロジェクトの Data Source Name (DSN)。Sentry プロジェクト + 認証を識別する URL 形式の文字列。

**OBTAIN:**

1. [Sentry](https://sentry.io) にサインイン。Skeinly 用組織がなければ作成
2. **Projects** → **Create Project** → Platform: **Apple iOS** → Project Name: `skeinly-ios` → Create
3. 作成後: **Settings** → **Projects** → `skeinly-ios` → **Client Keys (DSN)** → DSN をコピー
4. 形式: `https://<32-char-public-key>@<org>.ingest.sentry.io/<project-id>`

**VERIFY**: URL が HTTPS、`@` 含む、`.ingest.sentry.io` 含む、数値プロジェクト ID で終わる。

**REGISTER:**

```bash
gh secret set SENTRY_DSN_IOS
# DSN を貼り付け、Ctrl+D
```

**ROTATE**: Sentry → Settings → Projects → `skeinly-ios` → Client Keys → 古い key を revoke + 新規作成。GitHub Secret を更新。

### 15. `SENTRY_DSN_ANDROID`

**WHAT**: Android Sentry プロジェクトの DSN。`SENTRY_DSN_IOS` と同じ形式だが iOS と Android のクラッシュをダッシュボード上で独立してフィルタリングできるよう別プロジェクトを使う。

**OBTAIN**: #15 の手順を繰り返すが、Platform: **Android** を選び、プロジェクト名を `skeinly-android` に。

**REGISTER:**

```bash
gh secret set SENTRY_DSN_ANDROID
# DSN を貼り付け、Ctrl+D
```

### 16. `SENTRY_AUTH_TOKEN`

**WHAT**: 各リリースビルド後に CI が Sentry に dSYM (iOS) / mapping ファイル (Android) をアップロードするための **Organization Auth Token**。User Auth Token ではない — ユーザーが組織から削除されると失効する運用リスクを避けるため。Sentry 公式は CI 用途に Organization Auth Token を明示的に推奨:

> "To upload source maps you have to configure an Organization Token."
>
> "Organization tokens are designed to be used in CI environments and with Sentry CLI."

**OBTAIN:**

1. Sentry → 左サイドバー下部の **Settings** → **Organization Settings** → **Auth Tokens**
   - ⚠️ **User Settings → Auth Tokens は使用しない**
2. **Create New Organization Token**
3. Name: `skeinly CI`
4. Scopes: Organization Auth Token は CI 用途に必要なスコープ（`project:releases`, `project:write`, `org:read`）が**自動付与**される — User Token のように手動選択不要
5. Create → token を即座にコピー (一度限り表示)

**Token 種別比較:**

| 種別 | CI 用途 | 推奨度 | 失効リスク |
|---|---|---|---|
| **Organization Auth Token** | ✅ | **推奨** | 組織存続中は有効 |
| User Auth Token (旧 Personal Token) | ⚠️ | 非推奨 | 作成ユーザーが Org 離脱で即失効 |
| Internal Integration token | △ | 高度な統合のみ | n/a |

**環境別プロジェクトとの関係**: `skeinly-ios` + `skeinly-android` の 2 プロジェクト構成でも `SENTRY_AUTH_TOKEN` は **1 個で OK**。Org Token は Org 内全プロジェクトに横断的に作用する。`SENTRY_PROJECT` / `SENTRY_ORG` は CI Job ごとに `env:` で切り替え。

**VERIFY:**

```bash
# Token 形式: sntrys_<base64>... (Org Token)
brew install getsentry/tools/sentry-cli  # 未インストールなら
sentry-cli --auth-token <token> info
# 組織名 + プロジェクト一覧が表示されるはず
```

**REGISTER:**

```bash
gh secret set SENTRY_AUTH_TOKEN
# token を貼り付け、Ctrl+D
```

**ROTATE**: Organization Settings → Auth Tokens → revoke + 再作成。GitHub Secret を更新。

**消費先**: `iosApp/fastlane/Fastfile` の `upload_dsym_to_sentry` helper (TestFlight upload 後に `beta` lane から呼ばれる)。このトークンを未登録のままだと Sentry App Hang / Crash frame が `<redacted>` のままになり、bottom syscall より上の triage ができない (CLAUDE.md `### Pre-alpha (RC) bug bash` の retrospective を参照)。

参照: [Sentry Auth Tokens](https://docs.sentry.io/account/auth-tokens/)

### 16.5. `SENTRY_ORG` (GitHub **Variable** であり Secret ではない)

**WHAT**: Sentry の organization slug — `sentry-cli` / `fastlane-plugin-sentry` が dSYM upload を正しい org に scope するために使用。半公開識別子: Sentry incident dashboard URL に登場し、[README.md](../../README.md) + [docs/ja/architecture.md](architecture.md) の vendor table に既に記載済み。秘匿性は不要のため GitHub **Variable** (Repo Settings → Variables、Secrets ではない) に登録 — 運用衛生目的のみ。

**OBTAIN**: Sentry → Settings → Organization Settings → **Organization Slug** フィールド (ページ上部)。

**REGISTER:**

```bash
gh variable set SENTRY_ORG --body "<slug>"
```

**消費先**: `iosApp/fastlane/Fastfile` の `upload_dsym_to_sentry` helper (`.github/workflows/release.yml` で `${{ vars.SENTRY_ORG }}` 経由で渡される)。

**ROTATE**: 不要 — org slug は Sentry account rename 時のみ変更。rename 時は GitHub Variable + 旧 slug を参照している docs を更新。

## 分析 — PostHog（1 シークレット）

このシークレットは iOS + Android で PostHog SDK を配線します。**PostHog 無料枠は 1 Project が上限**のため、prod / dev で別 Project を作る選択は有料 PAYG プラン契約後にのみ可能（PAYG で 6 Project まで）。無料枠の現状運用:

- **1 Project (`Skeinly`)** に iOS + Android 両プラットフォームから接続
- プラットフォーム識別は SDK 自動付与の `$os` super property（カスタム `platform` super property も追加可）
- **DEBUG ビルドでは `posthog.init` をスキップ** することで dev イベントの prod 汚染を防止
- `auto_capture: false` 設定 + Settings の opt-in トグル（「使用状況の収集を許可」、デフォルト OFF）でゲート — Phase 27a プライバシーポリシーに従う

PostHog 公式ドキュメントもプラットフォーム別 Project 分割を**非推奨**と明示:
> "PostHog strongly recommends keeping your apps and marketing website on the same production project"

### 17. `POSTHOG_PROJECT_API_KEY`

**WHAT**: PostHog プロジェクトの Project API Key。release ビルドに焼き込まれる。形式: `phc_<43-char-base62>`。

**重要事実**: PostHog Project API Key は **「write-only key」** で、SDK 経由で配信されたバイナリに埋め込まれることが前提。公式 docs:
> "Safer than other API key types for client-side use"

つまり伝統的な意味での「秘密」ではなく、漏洩時の影響範囲は小さい。GitHub Secret に置く目的は**運用衛生**（rotation の容易さ、grep 可能性、誤コミット防止）であり、機密保護ではない。

**OBTAIN:**

1. [PostHog](https://us.posthog.com) または [EU クラウド](https://eu.posthog.com)（GDPR 重視なら EU）にサインイン
2. 組織がなければ `skeinly` という名前で作成
3. **Settings** → **Projects** → **Create Project** → Name: `Skeinly`
4. プロジェクト内: **Settings** → **Project** → **General** → **Project API Key** → 値をコピー (`phc_` で始まる)

**VERIFY**: `phc_` 始まり、合計 47 文字 (`phc_` + 43 文字 base62)、大文字小文字区別。

**REGISTER:**

```bash
gh secret set POSTHOG_PROJECT_API_KEY
# 貼り付け、Ctrl+D
```

**(将来) PAYG 契約後の env 分離**: 有料化してから dev / prod を分けたくなった際は以下のいずれか:
- **(A) 推奨**: `Skeinly-Dev` PostHog Project を新規作成 + GitHub Repository **Environments** (`development` / `production`) を活用 → 同名の `POSTHOG_PROJECT_API_KEY` を Environment ごとに登録（サフィックス不要）
- (B) PostHog Environments 機能（Project 内のサブスコープ）を有効化

**ROTATE**: PostHog → Settings → Project → Project API Key リセット。GitHub Secret を更新。古いイベントはプロジェクトに紐付いたまま、新規取り込みのみシフト。

### `POSTHOG_HOST`（Repository Variable、シークレットではない）

**WHAT**: PostHog バックエンドのホスト URL。`https://us.i.posthog.com`（US クラウド）または `https://eu.i.posthog.com`（EU クラウド、GDPR 重視）等。**機密ではない公開 URL** なので Secret ではなく **Repository Variable** として登録する（`vars.POSTHOG_HOST` で workflow から参照）。

**REGISTER（GitHub UI 推奨）**: リポジトリ → Settings → Secrets and variables → Actions → **Variables** タブ → New repository variable → Name: `POSTHOG_HOST` / Value: `https://us.i.posthog.com`（または希望のリージョン URL）。

**または `gh` 経由**:

```bash
gh variable set POSTHOG_HOST --body "https://us.i.posthog.com"
gh variable list  # POSTHOG_HOST 行が出ることを確認
```

**注意**: 未登録だと `vars.POSTHOG_HOST` は空文字に解決され、PostHog SDK 初期化が `init failed: invalid host` で失敗する可能性あり。CI 失敗を避けるため必ず登録する。

参照: [PostHog: Multi-environment tutorial](https://posthog.com/tutorials/multiple-environments) / [PostHog Pricing](https://posthog.com/pricing)

## サブスクリプション — RevenueCat（2 シークレット）

これらのシークレットは iOS + Android で RevenueCat SDK を配線します。**Public SDK Key** はクライアント公開可（バイナリ埋め込み前提、`Purchases.configure()` に渡す）。`Secret Key (sk_...)` はサーバー専用で**絶対にクライアントに埋め込まない**。RevenueCat 公式は Project ごとに iOS / Android の **Public SDK Key を別々**に発行（Project は 1 個 = `Skeinly` で iOS + Android 両方の App Configuration を管理）。

### 18. `REVENUECAT_API_KEY_IOS`

**WHAT**: iOS App の Public SDK Key。release ビルドの iOS バイナリに焼き込まれる。

**OBTAIN:**

1. [RevenueCat Dashboard](https://app.revenuecat.com) にサインイン → Project (`Skeinly`) 選択
2. **Project Settings** → **Apps** → **iOS** App をクリック（Bundle ID `io.github.b150005.skeinly` で登録済みのもの）
3. **API Keys** タブ → **Public iOS SDK Key** をコピー（`appl_` で始まる）

**VERIFY**: `appl_` 始まり。Secret Key (`sk_...`) ではないことを確認。

**REGISTER:**

```bash
gh secret set REVENUECAT_API_KEY_IOS
# appl_... を貼り付け、Ctrl+D
```

**ROTATE**: RevenueCat → Project Settings → Apps → iOS → API Keys → 既存キーを revoke + 新規発行。GitHub Secret 更新後の最初のリリースから新キーが有効。

### 19. `REVENUECAT_API_KEY_ANDROID`

**WHAT**: Android App の Public SDK Key。release ビルドの Android AAB に焼き込まれる。

**OBTAIN**: #19 と同手順だが **Android** App を選択し、Public Android SDK Key をコピー（`goog_` で始まる）。

**VERIFY**: `goog_` 始まり。

**REGISTER:**

```bash
gh secret set REVENUECAT_API_KEY_ANDROID
# goog_... を貼り付け、Ctrl+D
```

**Webhook Secret について**: RevenueCat の Webhook 署名検証用 secret は **Edge Function 専用**（クライアントには不要）。本ドキュメント末尾の Edge Function Secrets セクション [EF-4 `REVENUECAT_WEBHOOK_SECRET`](#ef-4-revenuecat_webhook_secret) を参照。

**RevenueCat Apple/Google IAP 連携**（Public SDK Key 発行の前提となる App Store Connect API Key・Google Play Service Account の RevenueCat への登録手順）は [vendor-setup.md](vendor-setup.md) の RevenueCat セクションを参照。

参照: [RevenueCat API Keys & Authentication](https://www.revenuecat.com/docs/projects/authentication)

## Android リリース公開（1 シークレット × 1 環境 = 1 登録）

`gradle-play-publisher` plugin が CI から Google Play Internal track へ AAB をアップロードするための Service Account JSON。**`production` Environment のみに登録**（debug ビルドは Internal track アップロードしないため `development` Environment は不要）。

### 20. `GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64`

**WHAT**: Google Play Developer API へリリース track への書き込みアクセスを持つ Service Account JSON の Base64 エンコード値。`gradle-play-publisher` plugin（または fastlane `supply` 互換ツール）が AAB アップロード時に使用。

**SA**: `google-play-publisher@<project-ref>.iam.gserviceaccount.com`（IAP 検証用 `revenuecat@...` とは**別 SA** — 権限セットが直交するため PoLP 分離）。

**OBTAIN:**

1. [Google Cloud Console](https://console.cloud.google.com) → IAM と管理 → **サービス アカウント**
2. `google-play-publisher@<project-ref>.iam.gserviceaccount.com` SA が存在することを確認（vendor-setup.md `A0c-3 RevenueCat` の前段で `Skeinly` プロジェクトに作成済みの想定）
3. SA をクリック → **キー** タブ → **キーを追加** → **新しいキーを作成** → JSON → ダウンロード
4. ローカルで Base64 エンコード:

   ```bash
   base64 -i ~/path/to/google-play-publisher-xxxx.json | pbcopy
   ```

**Play Console での権限付与**（一度限り）:

5. [Play Console](https://play.google.com/console) → **ユーザーと権限** → **新しいユーザーを招待**
6. メールアドレス: `google-play-publisher@<project-ref>.iam.gserviceaccount.com`
7. 有効期限: なし
8. **アプリの権限** タブで Skeinly アプリを選択 → 以下のみ ✅:
   - **テスト版トラックとしてのアプリのリリース**（Release to testing tracks）
   - **テスト版トラックの管理、テスターリストの編集**（Manage testing tracks and edit tester lists）
   - 自動: アプリ情報の閲覧（読み取り専用）、アプリの品質情報の閲覧（読み取り専用）
9. **アカウントの権限** タブは何もチェックしない
10. ユーザーを招待

> **絶対に ✅ にしない権限**: 製品版としてのリリース（Release apps to production）、売上データ、注文管理、ストアでの表示の管理、レビューへの返信、ポリシー、ディープリンク、管理者（すべての権限）。CI が誤って Production track に rollout する事故を**構造的に防止**するため、リリース系は「テスト版」のみに限定する。
>
> **アプリ単位 vs アカウント単位**: `google-play-publisher` は **アプリ単位**（Skeinly のみ）が正解。CI Publisher は Skeinly 以外をアップロードしないことが構造的に保証されるため。

**VERIFY**: Play Console **ユーザーと権限** ページに `google-play-publisher@...` がリストされ、Skeinly アプリ単位で 2 つの権限に緑のチェックマーク。

**REGISTER:**

```bash
gh secret set GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64 \
  --env production \
  --body "$(base64 -i ~/path/to/google-play-publisher-xxxx.json)"
```

> `--env production` で **Environment scope** に登録する点に注意。Repository scope や `development` Environment には登録しない。

**CONSUMED BY**: `.github/workflows/release.yml` の `build-android` ジョブ（タグ push 時のみ実行）。ジョブが `${{ secrets.GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64 }}` を base64 デコードして `ANDROID_PUBLISHER_CREDENTIALS` 環境変数に export（gradle-play-publisher 4.x README の CI ガイダンス通り）、`./gradlew :androidApp:publishBundle` で AAB をアップロード。`androidApp/build.gradle.kts` の `play { }` ブロックで `track = "internal"` + `releaseStatus = DRAFT` を固定 — AAB は Play Console 上で Internal track の Draft release として着地し、ユーザーが手動で「テスターに配信」をクリックして配信を開始する構造的安全装置（タグ push が誤ってテスターに自動 rollout する事故を防止）。タグ push 前のローカル検証: `make release-aab-local` で AAB ビルドのみ（アップロードなし）。

**ROTATE**: Cloud Console → IAM → Service Accounts → `google-play-publisher@...` クリック → Keys → 古い key を revoke + 新規 JSON 作成 → 上記 REGISTER 手順を再実行。Play Console 権限は SA 識別子（email）が変わらない限り再設定不要。

## Supabase Edge Function Secrets（8 シークレット）

これらのシークレットは Supabase Edge Function が消費します (`notify-on-write` でコラボ push — Phase 24.1 が shell を配線、Phase 24.3 が実 APNs / FCM 送信; `revenuecat-webhook` で IAP webhook 受信 — Phase 39 prep, 2026-05-08; `submit-bug-report` でクローズドベータのフィードバック受信 — Phase 39 W5a)。**GitHub Secrets ではありません** — Supabase CLI で Supabase プロジェクトに登録します:

```bash
supabase login                                # 一度限り
supabase link --project-ref <your-project-ref>
supabase secrets list                         # 現在登録済みのシークレットを確認
```

Edge Function は実行時に `Deno.env.get("APPLE_APNS_KEY_P8")` 等で読みます。

### EF-1. `APPLE_APNS_KEY_P8`

**WHAT**: [vendor-setup.md A0a-2](vendor-setup.md#a0a-2-apns-auth-key-p8-生成) で生成した APNs `.p8` Auth Key ファイルの**生テキスト**内容。Base64 エンコードしません — Supabase secrets は raw 複数行 PEM 本体を受け付けます。

**OBTAIN**: [vendor-setup.md A0a-2](vendor-setup.md#a0a-2-apns-auth-key-p8-生成) 参照。ダウンロードファイル `AuthKey_<KEY_ID>.p8` を生のまま登録。

**REGISTER:**

```bash
supabase secrets set APPLE_APNS_KEY_P8="$(cat AuthKey_XYZ1234567.p8)"
supabase secrets list | grep APPLE_APNS
```

### EF-2. `APPLE_APNS_KEY_ID`

**WHAT**: Apple Developer Keys ページの 10 文字 Key ID。`.p8` ファイル名末尾と同じ値。

**REGISTER:**

```bash
supabase secrets set APPLE_APNS_KEY_ID=XYZ1234567
```

Edge Function はさらに `APPLE_TEAM_ID` (GitHub Secret と同じ 10 文字値) も必要です。APNs 用 JWT トークン生成に使います:

```bash
supabase secrets set APPLE_TEAM_ID=ABCDE12345
```

(Bundle ID `io.github.b150005.skeinly` は Edge Function ソースに直接埋め込まれており、シークレットには登録しません)

### EF-3. `FIREBASE_SERVICE_ACCOUNT_JSON`

**WHAT**: FCM HTTP v1 API 経由で Push を送信するための Service Account JSON。Edge Function はこれを使って FCM 用の短命 OAuth 2.0 access token を発行します。

**OBTAIN:**

1. [Firebase Console](https://console.firebase.google.com) → プロジェクト (`skeinly`) → **Project Settings** → **Service Accounts**
2. **Firebase Admin SDK** タブ → **Generate new private key** → 確認 → JSON ダウンロード
3. デフォルト付与ロール **Firebase Admin SDK Administrator Service Agent** に Cloud Messaging が含まれている — 追加 IAM 設定不要

**VERIFY**: JSON が `"type": "service_account"` を含む、`project_id` が Firebase プロジェクトと一致、`client_email` が `@<project-id>.iam.gserviceaccount.com` で終わる。

**REGISTER:**

```bash
supabase secrets set FIREBASE_SERVICE_ACCOUNT_JSON="$(cat firebase-admin-sdk.json)"
```

**ROTATE**: Firebase Console → Project Settings → Service Accounts → Manage all service accounts → SA クリック → Keys タブ → 古い key を revoke + 新規 JSON 追加。

### EF-4. `REVENUECAT_WEBHOOK_SECRET`

**WHAT**: RevenueCat → 自前 Edge Function `revenuecat-webhook` への Webhook 配信を**サーバー側で署名検証**するための共有秘密値。RevenueCat ダッシュボードの **Webhooks → Authorization header** に設定した値そのもの。Edge Function 側で `Authorization: Bearer <値>` ヘッダを定数時間比較で照合する。

**消費箇所**: [`supabase/functions/revenuecat-webhook/index.ts`](../../supabase/functions/revenuecat-webhook/index.ts) — Phase 39 closed beta launch 時に有効化。

**OBTAIN + Webhook URL 設定（一連の手順）:**

1. 強い乱数を生成（**自分で決める** — RevenueCat 側はこちらが指定した値を保管・送信するだけ）:

   ```bash
   openssl rand -hex 32
   # 例: 7f3a9c2d8e1b5a4f6c9d2e7b8a1f3c5d9e2b4a6c8f1d3e5a7b9c2d4e6f8a1b3c
   ```

2. **Supabase Edge Function secret に登録**（Webhook URL 登録より**先**に行う — RevenueCat の test event がデプロイ時点で届いても 401 でリジェクトされるよう）:

   ```bash
   supabase secrets set REVENUECAT_WEBHOOK_SECRET="<手順1で生成した値>"
   supabase secrets list | grep REVENUECAT
   ```

3. **Edge Function をデプロイ**:

   ```bash
   supabase functions deploy revenuecat-webhook
   ```

   > **JWT 検証 OFF が必須**: `supabase/config.toml` に `[functions.revenuecat-webhook] verify_jwt = false` が設定されているので、`supabase functions deploy` がこの flag を読み込めば自動で OFF になる。古い CLI で flag が読まれない場合は明示的に渡す:
   >
   > ```bash
   > supabase functions deploy revenuecat-webhook --no-verify-jwt
   > ```
   >
   > 検証 OFF を忘れると Supabase API gateway が webhook リクエストを受け取った時点で 401 `UNAUTHORIZED_INVALID_JWT_FORMAT` で reject し、私たちの function コードまで到達しない。

4. デプロイ URL を確認（例: `https://<project-ref>.supabase.co/functions/v1/revenuecat-webhook`）。Supabase Dashboard → Edge Functions → revenuecat-webhook → Details で確認可能。

5. [RevenueCat Dashboard](https://app.revenuecat.com) → Project (`Skeinly`) → **Integrations** → **Webhooks** → **Add Webhook**:
   - **Webhook URL**: 手順 4 の URL を貼り付け
   - **Authorization header value**: **`Bearer <手順1で生成した値>`** の形式で貼り付け（リテラル `Bearer` + 半角スペース 1 つ + hex 値）。RevenueCat はこの値を verbatim で `Authorization` ヘッダに乗せて送信する。私たちの function は `Bearer ` プレフィックスを strip して残りを Supabase secret と定数時間比較するので、**Bearer プレフィックス無しで hex 値だけ登録すると 401 で reject される**。
   - **Environment filter**: **空のまま**（Sandbox + Production 両方を流す。closed beta 中は sandbox 購入が主、Phase 40 GA 後に production も追加流入。Edge Function 側で `event.environment` を `subscriptions.environment` カラムに書き込むので、後段の分析クエリで `WHERE environment = 'production'` 等で絞り込める）

6. **Save**

7. RevenueCat Dashboard の同 Webhook 行で **"Send test event"** ボタンをクリック → ✅ 緑チェックマーク（HTTP 200 + body `{"status":"ok","note":"test_event_acknowledged"}`）が表示されれば end-to-end 成功。

**VERIFY**: 64 文字の hex 文字列。RevenueCat 側 Authorization header と Supabase secret の値が完全一致。`Send test event` 緑チェック。

**curl smoke test**（手順 6 完了後、ダッシュボードボタンを使わない場合）:

```bash
WEBHOOK_URL="https://<project-ref>.supabase.co/functions/v1/revenuecat-webhook"
SECRET="<手順1で生成した値>"

curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "Authorization: Bearer $SECRET" \
  -H "Content-Type: application/json" \
  -d '{"api_version":"1.0","event":{"id":"smoke-test-1","type":"TEST","event_timestamp_ms":1700000000000}}'
```

期待値: `HTTP 200` + body `{"status":"ok","note":"test_event_acknowledged"}`。

不正な認証ヘッダで 401 を返すかも検証:

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST "$WEBHOOK_URL" \
  -H "Authorization: Bearer wrong-secret" \
  -H "Content-Type: application/json" \
  -d '{"event":{"id":"x","type":"TEST","event_timestamp_ms":1700000000000}}'
```

期待値: `HTTP 401` + body `{"error":"unauthorized"}`。

**ROTATE**: RevenueCat ダッシュボードの Webhook 編集 → Authorization header を新しい値に更新 → Supabase secret を再登録。RevenueCat は Webhook 失敗時に最大 5 回まで自動リトライするため、ローテーション中の取りこぼし耐性は確保されている。

> **依存リソース**: 本 secret は Edge Function 単体だけでなく、(a) Edge Function 自体のデプロイ、(b) [migration 023 `upsert_subscription_from_webhook` RPC](../../supabase/migrations/023_revenuecat_webhook_helper.sql)、(c) KMP 側の [`RevenueCatAuthBridge.kt`](../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/subscription/RevenueCatAuthBridge.kt) (`Purchases.logIn(userId)` を呼び webhook の `event.app_user_id` を Skeinly UUID にする) と組み合わさって完全機能する。Phase 39 closed beta sandbox tester 設定の全体手順は [docs/ja/ops/beta-testing.md](ops/beta-testing.md) を参照。

参照: [RevenueCat Webhooks](https://www.revenuecat.com/docs/integrations/webhooks)

### EF-5. `SKEINLY_DATABASE_WEBHOOK_SECRET`

**WHAT**: Supabase Database Webhook が `notify-on-write` Edge Function に配信する HTTP リクエストの認証用共有秘密値 (Phase 24.1, ADR-017)。Maintainer が `openssl rand -hex 32` で生成し、Supabase Edge Function secret として AND Dashboard 上の各 Database Webhook の `Authorization: Bearer <value>` HTTP ヘッダ値として **両方** に登録。Edge Function は受信した webhook の Authorization ヘッダ値を定数時間文字列比較で検証。

> **認証方式**: [Supabase 公式 Database Webhooks doc](https://supabase.com/docs/guides/database/webhooks) によると、Database Webhook は payload を自動署名しません — Dashboard UI が露出する設定項目は Method / URL / Timeout / HTTP Headers / HTTP Parameters のみで、signing-secret 欄も `x-supabase-webhook-signature` ヘッダもありません。Authorization Bearer ヘッダが唯一サポートされた認証境界です。`revenuecat-webhook` (EF-4) と同じパターン。

> **命名上の注意**: `SKEINLY_` プレフィックスは load-bearing。[Supabase Edge Function limits doc](https://supabase.com/docs/guides/functions/limits#secrets) によると `SUPABASE_` で始まる env-var 名はプラットフォーム予約のため `supabase secrets set` が拒否する。Phase 24.1 では当初 `SUPABASE_DATABASE_WEBHOOK_SECRET` として設計したが、deploy 前に登録エラー回避のため `SKEINLY_DATABASE_WEBHOOK_SECRET` に rename。

**消費箇所**: [`supabase/functions/notify-on-write/index.ts`](../../supabase/functions/notify-on-write/index.ts) — webhook 配信ごとに `Authorization: Bearer <value>` ヘッダを検証。3 つの Database Webhook がこの Edge Function を fire する (詳細は [`docs/en/ops/webhooks.md`](../en/ops/webhooks.md))。

**OBTAIN**: ローカルで `openssl rand -hex 32` 生成 (任意の暗号論的乱数 32 byte hex 文字列で可、Supabase 側に最小長制約はないが、プロジェクト内の他の webhook secret ローテーション規律と揃えて 256-bit に固定)。

**REGISTER (Supabase Edge Function 側)**:

```bash
SKEINLY_DATABASE_WEBHOOK_SECRET="$(openssl rand -hex 32)"
echo "$SKEINLY_DATABASE_WEBHOOK_SECRET"   # この値を控える — Dashboard 設定に使う
supabase secrets set SKEINLY_DATABASE_WEBHOOK_SECRET="$SKEINLY_DATABASE_WEBHOOK_SECRET"
supabase secrets list | grep SKEINLY_DATABASE_WEBHOOK_SECRET
```

**REGISTER (Database Webhook 側)**: Dashboard → Database → Webhooks → 3 つの webhook それぞれで Type に `Supabase Edge Functions` を選択 (NOT `HTTP Request`) → function dropdown から `notify-on-write` を選択 → HTTP Headers セクションで **自動入力された `Authorization` ヘッダ値を上書き** (自動入力値はプロジェクトの anon key — public でアプリに埋め込み済み)、`Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET の値>` に置き換え。anon key を残すと、アプリの利用者なら誰でも Edge Function URL に直接 webhook payload を spoof 投稿できてしまう。手順詳細 + 根拠は [`docs/en/ops/webhooks.md`](../en/ops/webhooks.md) を参照。

**ROTATE**: `openssl rand -hex 32` で新しい値を生成、Supabase secret を再登録、Dashboard 上の 3 つの Database Webhook 全部の `Authorization` HTTP ヘッダ値を更新。Webhook システムは認証失敗で auto-retry しない (Edge Function が 401 を返し Supabase は失敗を記録するが re-deliver しない) ため、ローテーション中は静かなタイミングで実施するか、瞬間的な push 取りこぼしを許容する。

> **依存リソース**: 本 secret は Edge Function 単体だけでなく、(a) deploy された Edge Function 自体、(b) [`docs/en/ops/webhooks.md`](../en/ops/webhooks.md) に従って設定した 3 つの Database Webhook、(c) Phase 24.2 の `PushTokenRegistrar` で populate される `device_tokens` テーブル — の組み合わせで初めて完全機能する。Phase 24.1 が (a) + (b)、Phase 24.2 が client side、Phase 24.3 が APNs / FCM 実送信、Phase 24.4–24.6 が event matrix 拡張 + deep link routing + privacy policy 更新。

### EF-6. `SKEINLY_BUGREPORT_APP_ID` / `SKEINLY_BUGREPORT_INSTALLATION_ID` / `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM`

**WHAT**: Phase 39 W5 (ADR-020) の `submit-bug-report` Edge Function が "Skeinly Feedback" GitHub App として認証して `b150005/skeinly` リポジトリに Issue を作成するために使う 3 点 secret。Phase 39.5 のクライアント側 URL プリフィル方式を置換する。

- `SKEINLY_BUGREPORT_APP_ID` — 数値 App ID。App 作成後、設定ページ上部に表示される。
- `SKEINLY_BUGREPORT_INSTALLATION_ID` — 数値 Installation ID。Install 後のブラウザ URL `github.com/settings/installations/<id>` に出る。
- `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM` — App の "Private keys" セクションから downloadした `.pem` の全文。`-----BEGIN ... PRIVATE KEY-----` / `-----END ... PRIVATE KEY-----` 行を含めてそのまま登録。PKCS#1 (`BEGIN RSA PRIVATE KEY`) と PKCS#8 (`BEGIN PRIVATE KEY`) どちらも Edge Function 側で受け付ける。

> **命名**: `SKEINLY_` プレフィックスは load-bearing (EF-5 / EF-3 と同じ理由)。

**CONSUMED BY**: [`supabase/functions/submit-bug-report/index.ts`](../../supabase/functions/submit-bug-report/index.ts) (env vars 読込) + [`supabase/functions/submit-bug-report/github_app.ts`](../../supabase/functions/submit-bug-report/github_app.ts) (RS256 JWT 署名 + installation token 交換)。

**OBTAIN**:

1. https://github.com/settings/apps/new を開く
2. **GitHub App name**: `Skeinly Feedback`
3. **Homepage URL**: `https://b150005.github.io/skeinly/`
4. **Webhook → Active**: チェックを外す
5. **Repository permissions → Issues**: Read & write のみ、他は全部 No access
6. **Where can this GitHub App be installed?**: Only on this account
7. **Create GitHub App** を押す。再読込後の上部に表示される **App ID** を控える。
8. **Private keys** セクション → **Generate a private key** → `.pem` ファイルがダウンロードされる
9. 左サイドバーの **Install App** → 自分のアカウント横の **Install** → **Only select repositories** → `b150005/skeinly` をチェック → 確定
10. インストール後のブラウザ URL `github.com/settings/installations/<INSTALLATION_ID>` から **Installation ID** を控える

**REGISTER**:

```bash
supabase secrets set SKEINLY_BUGREPORT_APP_ID=<手順7の App ID>
supabase secrets set SKEINLY_BUGREPORT_INSTALLATION_ID=<手順10の Installation ID>
supabase secrets set SKEINLY_BUGREPORT_PRIVATE_KEY_PEM="$(cat /path/to/downloaded.pem)"
supabase secrets list | grep SKEINLY_BUGREPORT
```

**DEPLOY**:

```bash
git checkout main && git pull
supabase functions deploy submit-bug-report
```

**SMOKE TEST** (deploy 後):

```bash
ANON=<Supabase project の anon key>
curl -i \
  -X POST "https://<project>.supabase.co/functions/v1/submit-bug-report" \
  -H "apikey: ${ANON}" \
  -H "Content-Type: application/json" \
  -d '{"title":"[Beta] smoke test","body":"This is a smoke test."}'
```

HTTP 200 + `{"ok":true,"issue_number":<n>,"html_url":"..."}` を期待。返ってきた URL で Issue 存在を確認後、手動でクローズ。

**ROTATE**: App 設定ページ → Generate new private key → `.pem` ダウンロード → `supabase secrets set SKEINLY_BUGREPORT_PRIVATE_KEY_PEM="$(cat new.pem)"` → `supabase functions deploy submit-bug-report` → App 設定ページで旧キーを revoke。App ID と Installation ID は不変。

> **依存リソース**: Phase 39 W5a が Edge Function を着地させる (本エントリ)。Phase 39.5 のクライアント側 URL プリフィルが引き続き active な送信経路で、Phase 39 W5b が `BugSubmissionLauncher` (expect/actual) を削除して新規 `BugReportProxyClient` 経由に切り替える。W5b が landing するまで本 Edge Function は deploy 済みだが client builds から使われない状態 — W5b commit が deploy timing 調整なしで client-side を切り替えられるよう deploy したまま維持する。

参考: [GitHub Apps documentation](https://docs.github.com/en/apps/creating-github-apps)

## Apple Sign-In（Phase 26.1 — Supabase Dashboard プロバイダー設定）

> **スロット種別**: Supabase Dashboard 設定（GitHub Secret でも Supabase Edge Function Secret でもない）。下記の値は **Supabase Dashboard → Authentication → Providers → Apple** で設定する。アプリバイナリには Apple Sign-In の credential を一切含まない — Supabase Auth が下記の値で server-side ID-token 検証を実行する。

Phase 26.1 で iOS Apple Sign-In を出荷（SwiftUI `SignInWithAppleButton` + Supabase Auth `signInWith(IDToken) { provider = Apple }`）。Supabase の server-side JWT 検証を有効化するため、Apple 側の 3 つの artifact を Supabase に登録する:

| スロット名（ドキュメント上のキー） | 内容 | 取得元 | ローテーション頻度 |
|---|---|---|---|
| `APPLE_SIGNIN_KEY_P8` | Supabase が Apple トークン endpoint に送信する client_secret JWT の署名に使う ECDSA P-256 秘密鍵（`.p8` ファイル）。Apple Developer "Sign in with Apple" Key 登録の寿命（通常 1 年以上）の間、プロジェクトごとに同じ `.p8` を使う。 | Apple Developer Portal → Keys → "Sign in with Apple" Key | 失効 / 紛失時のみ新規生成。下記 JWT のローテーションをまたいで存続。 |
| `APPLE_SIGNIN_KEY_ID` | `.p8` ダウンロード時に表示される 10 文字の Key ID。JWT ヘッダの `kid` claim になる。 | Apple Developer Portal → Keys → key record | 安定。`.p8` をローテーションする時のみ変わる。 |
| `APPLE_SIGNIN_SERVICES_ID` | App ID 配下に設定する reverse-DNS Services ID（例: `io.github.b150005.skeinly.signin`）。client_secret JWT の `aud` フィールドになる。 | Apple Developer Portal → Identifiers → Services IDs | 安定。 |

**client_secret JWT — 6 ヶ月の強制ローテーション**: Supabase は client_secret JWT に署名し（上記の値そのものではなく、**JWT 本体**）、Dashboard の "Secret Key" フィールドに保管する。Apple の仕様で JWT 寿命は **6 ヶ月** が上限。オペレーターは 6 ヶ月ごとに JWT を再生成して dashboard に貼り直さないと、Apple トークン endpoint で `invalid_client` を返してサインインが失敗する。再生成手順は [ローテーションと失効](#ローテーションと失効) セクション参照。

### 初回セットアップ手順

1. **Apple Developer Portal — App ID で Sign in with Apple capability を有効化**:
   - Identifiers → App IDs → `io.github.b150005.skeinly` → Capabilities → "Sign In with Apple" にチェック → Save → Distribution Provisioning Profile を再生成。
2. **Services ID を作成**（Supabase server-side 検証用、iOS アプリの Bundle ID とは別物）:
   - Identifiers → Services IDs → `+` → Description「Skeinly Sign In with Apple」→ Identifier `io.github.b150005.skeinly.signin`（または `APPLE_SIGNIN_SERVICES_ID` になる値）。
   - "Sign In with Apple" にチェック → Configure → primary App ID = `io.github.b150005.skeinly`、Return URLs = `https://<your-project-ref>.supabase.co/auth/v1/callback`（Supabase プロジェクト URL + `/auth/v1/callback`）。
3. **"Sign in with Apple" Key を作成**:
   - Keys → `+` → Key Name「Skeinly Sign In with Apple Key」→ "Sign In with Apple" にチェック → Configure → primary App ID = `io.github.b150005.skeinly` → Save → **`.p8` ファイルをダウンロード**（一度しかダウンロードできない — 安全に保管）。
   - キー詳細ページに表示される **Key ID**（10 文字）をメモ。
4. **Supabase 用 client_secret JWT を生成**（6 ヶ月で expire する artifact）:
   - [Supabase docs のブラウザ内 JWT generator](https://supabase.com/docs/guides/auth/social-login/auth-apple#generate-the-client_secret) を開く（Web Crypto 使用、ブラウザ外には出ない）。
   - 貼り付ける値: Team ID（`APPLE_TEAM_ID` から）、Key ID（`APPLE_SIGNIN_KEY_ID`）、Services ID（`APPLE_SIGNIN_SERVICES_ID`）、`.p8` ファイル内容。
   - 生成された JWT をコピー。
5. **Supabase Dashboard → Authentication → Providers → Apple**:
   - Enable をトグル。
   - Client IDs（カンマ区切り）: `io.github.b150005.skeinly,io.github.b150005.skeinly.signin`（iOS アプリの Bundle ID、Web/Supabase server-side 検証用の Services ID）。
   - Secret Key (for OAuth): step 4 の JWT を貼り付け。
   - Save。
6. **iOS の entitlement を追加**（`iosApp/iosApp/iosApp.entitlements` にコミット済み）:
   - `com.apple.developer.applesignin = ["Default"]`。

### ローテーション手順（6 ヶ月ごと）

```bash
# 1. Supabase の JWT generator を開く
open "https://supabase.com/docs/guides/auth/social-login/auth-apple#generate-the-client_secret"

# 2. Team ID + Key ID + Services ID + .p8 内容を貼り付け → 新しい JWT をコピー

# 3. Supabase Dashboard → Authentication → Providers → Apple → Secret Key (for OAuth) を新しい JWT に差し替え → Save

# 4. TestFlight ビルドから smoke test:
#    - サインアウト
#    - "Apple でサインイン" をタップ
#    - Authenticated 分岐で session が着地することを確認
```

直前の JWT 生成から **5 ヶ月 25 日後** にカレンダーリマインダーを設定し、expire 前に新しい JWT を用意する。`.p8` 自体はローテーションしない — 署名する JWT のみ。

期限切れの失敗モード: `signInWithApple` が server-side エラーを返し、Supabase ログには Apple トークン endpoint から `invalid_client` が記録される。ユーザーには LoginScreen に generic エラーバナーが表示される。

## Google Sign-In（Phase 26.2 — Supabase Dashboard プロバイダー設定 + iOS GitHub Secret）

> **スロット種別**: 混在。Google OAuth Client ID は **Supabase Dashboard 設定**（Google プロバイダーに貼り付け） — GitHub Secret ではなく、クライアント側バンドルも持たない。iOS `GoogleService-Info.plist`（Phase 26.3+ で `GIDSignIn` 経由の iOS Google sign-in 用）は `production` 環境の **GitHub Secret**。

### Supabase Dashboard スロット（Google プロバイダー）

Phase 26.2 で Android Google Sign-In を出荷（`androidx.credentials.CredentialManager` + Supabase Auth `signInWith(IDToken) { provider = Google }`）。3 つの Google 側 OAuth Client ID を登録:

| スロット名 | 内容 | 取得元 | 利用箇所 |
|---|---|---|---|
| `GOOGLE_OAUTH_WEB_CLIENT_ID` | OAuth 2.0 Web アプリケーション Client ID — Google ID token の `aud` (audience) になる。Supabase はこの値に対してトークンを検証。 | Google Cloud Console → APIs & Services → Credentials → Web application | Supabase Dashboard (Authentication → Providers → Google → Client IDs); `google-services.json` の `oauth_client[type=3]` にも書き込まれ、`R.string.default_web_client_id` として読み出される |
| `GOOGLE_OAUTH_WEB_CLIENT_SECRET` | Web Client ID とペアの secret。 | 同じ Web app credential の「Client secret」フィールド | Supabase Dashboard（同じ Google provider 設定） |
| `GOOGLE_OAUTH_IOS_CLIENT_ID` | OAuth 2.0 iOS アプリケーション Client ID — `GoogleService-Info.plist` の `CLIENT_ID` フィールドから参照。`GIDSignIn` SDK が iOS で使う（Phase 26.3+）。 | Google Cloud Console → APIs & Services → Credentials → iOS application | iOS アプリが `GoogleService-Info.plist` 経由で参照 |
| `GOOGLE_OAUTH_ANDROID_CLIENT_ID` | OAuth 2.0 Android アプリケーション Client ID — パッケージ名 + SHA-1 フィンガープリントの一致で暗黙バインド。Credential Manager は直接消費しない（Android sign-in flow は server_client_id として Web Client ID のみ使う）。Android Client は package + SHA-1 の所有権アサーション用。 | Google Cloud Console → APIs & Services → Credentials → Android application | 暗黙（package + SHA-1 一致） |

**ローテーション頻度**: Client ID は OAuth Client 登録の寿命の間安定。ローテーション = Cloud Console で新 Client 作成 + 新 ID/Secret を Supabase Dashboard に貼り直し + `default_web_client_id` を使う package 向けに `google-services.json` を再ダウンロード。カレンダー時間ローテーションではない — credential 漏洩または運用移行時のみ。

### 初回セットアップ手順

1. **Google Cloud Console** → Firebase 紐付け済みプロジェクト（Skeinly Blaze）を選択。
2. APIs & Services → Credentials → OAuth 2.0 Client ID を 3 回作成:
   - **Web application** → `GOOGLE_OAUTH_WEB_CLIENT_ID` + `GOOGLE_OAUTH_WEB_CLIENT_SECRET` を記録。Authorized redirect URI: `https://<supabase-project-ref>.supabase.co/auth/v1/callback`。
   - **iOS application** → Bundle ID `io.github.b150005.skeinly` → `GOOGLE_OAUTH_IOS_CLIENT_ID` を記録。Reverse Client ID（iOS URL Scheme として使う）は自動派生。
   - **Android application** → Package name `io.github.b150005.skeinly` → アップロード署名キーストアの SHA-1 + Play App Signing フィンガープリント（Play Console 初回アップロード後に追加 — CLAUDE.md Phase 40 GA prep「Play App Signing SHA-1 登録」参照）。
3. **`google-services.json` を再ダウンロード** — Firebase Console → Project Settings → General → Skeinly Android app → ギアアイコンクリック → `google-services.json` をダウンロード。新ファイルに OAuth Client エントリが含まれる。Base64 エンコードし直して `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` GitHub Secret（`production` env）を再登録 — CI リリースビルドが OAuth-augmented 設定を取得するため。
4. **Supabase Dashboard → Authentication → Providers → Google**:
   - Enable をトグル。
   - Authorized Client IDs（改行区切り）: `GOOGLE_OAUTH_WEB_CLIENT_ID` + `GOOGLE_OAUTH_IOS_CLIENT_ID`（Phase 26.3+ で iOS Client ID を追加）。
   - Client ID (for OAuth): `GOOGLE_OAUTH_WEB_CLIENT_ID`。
   - Client Secret (for OAuth): `GOOGLE_OAUTH_WEB_CLIENT_SECRET`。
   - Save。
5. **Android アプリ**: 追加作業なし。`R.string.default_web_client_id` は Gradle build 時に `google-services.json` から自動生成される。shared `OAuthClient.android.kt` は `context.resources.getIdentifier("default_web_client_id", ...)` で読み出す。
6. **iOS アプリ（Phase 26.3+）**: `GoogleService-Info.plist` を `iosApp/iosApp/GoogleService-Info.plist` に配置（gitignored）。次のサブセクション参照。

### `IOS_GOOGLE_SERVICES_PLIST_BASE64`（GitHub Secret — `production` env）

> **Phase 26.2 ステータス**: Phase 26.3 iOS Google Sign-In の forward-compat スロットとしてここに記載。Phase 26.2 の iOS `OAuthClient` actual は Failure stub のため、iOS アプリは現状この plist を消費しない。GitHub Secret + CI デコードステップを今のうちに配線しておき、26.3（`GIDSignIn` import を追加）が別途 secret 登録ラウンドなしで出荷できるようにする。

`FIREBASE_GOOGLE_SERVICES_JSON_BASE64` の iOS 対応版。plist は iOS OAuth Client ID + reverse Client ID URL scheme + project number を含む。オペレーター側セットアップ:

1. Firebase Console → Project Settings → General → Skeinly iOS app から `GoogleService-Info.plist` をダウンロード。
2. ローカルに `iosApp/iosApp/GoogleService-Info.plist` として配置（gitignored）。
3. `production` Environment の GitHub Secret として登録:
   ```bash
   base64 -i iosApp/iosApp/GoogleService-Info.plist | pbcopy
   gh secret set IOS_GOOGLE_SERVICES_PLIST_BASE64 --env production --body "$(pbpaste)"
   ```
4. CI リリースワークフローの iOS build step が `xcodebuild` 起動前に secret を plist パスにデコード。デコードステップは secret 存在時のみ条件実行 — half-configured release は gracefully degrade（iOS Google Sign-In は Failure を返す; Apple Sign-In + email/password は影響なし）。

**ローテーション**: Cloud Console iOS Client 再作成時のみ。同じ手順: 再ダウンロード → 再エンコード → secret 再設定。

## 一括検証

GitHub Secrets を登録した後、`gh` で確認:

```bash
# Repository scope のシークレットを一覧
gh secret list

# Environment scope のシークレットを一覧
gh secret list --env production
gh secret list --env development
```

期待される **Repository scope** 出力(18 エントリ — 全環境共通の値):

```
APPLE_DISTRIBUTION_CERT_BASE64        Updated YYYY-MM-DD
APPLE_DISTRIBUTION_CERT_PASSWORD      Updated YYYY-MM-DD
APPLE_TEAM_ID                         Updated YYYY-MM-DD
APP_STORE_CONNECT_API_KEY_BASE64      Updated YYYY-MM-DD
APP_STORE_CONNECT_API_KEY_ID          Updated YYYY-MM-DD
APP_STORE_CONNECT_ISSUER_ID           Updated YYYY-MM-DD
KEY_ALIAS                             Updated YYYY-MM-DD
KEY_PASSWORD                          Updated YYYY-MM-DD
KEYSTORE_BASE64                       Updated YYYY-MM-DD
KEYSTORE_PASSWORD                     Updated YYYY-MM-DD
POSTHOG_PROJECT_API_KEY               Updated YYYY-MM-DD
REVENUECAT_API_KEY_ANDROID            Updated YYYY-MM-DD
REVENUECAT_API_KEY_IOS                Updated YYYY-MM-DD
SENTRY_AUTH_TOKEN                     Updated YYYY-MM-DD
SENTRY_DSN_ANDROID                    Updated YYYY-MM-DD
SENTRY_DSN_IOS                        Updated YYYY-MM-DD
SUPABASE_PUBLISHABLE_KEY              Updated YYYY-MM-DD
SUPABASE_URL                          Updated YYYY-MM-DD
```

期待される **`production` Environment scope** 出力（3 エントリ — Firebase prod 2 + Android リリース公開 1）:

```
FIREBASE_GOOGLE_SERVICES_JSON_BASE64        Updated YYYY-MM-DD
FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64   Updated YYYY-MM-DD
GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64        Updated YYYY-MM-DD
```

期待される **`development` Environment scope** 出力（2 エントリ — Firebase dev プロジェクト由来）:

```
FIREBASE_GOOGLE_SERVICES_JSON_BASE64        Updated YYYY-MM-DD
FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64   Updated YYYY-MM-DD
```

合計: Repository 18 + Environment scope (production: 3 + development: 2) = **23 登録**。欠けているものや古いタイムスタンプのものは要確認。

> **legacy 名のクリーンアップ**: 旧構成から移行する場合は以下を `gh secret delete` で削除:
> - `SUPABASE_ANON_KEY`（→ `SUPABASE_PUBLISHABLE_KEY` に完全移行済み）
> - `POSTHOG_PROJECT_API_KEY_PROD` / `POSTHOG_PROJECT_API_KEY_DEV`（→ `POSTHOG_PROJECT_API_KEY` に統合）
> - 旧 Repository scope の `FIREBASE_GOOGLE_SERVICES_JSON_BASE64`（→ Environment scope に移動）

Supabase Edge Function secrets (`supabase secrets set` で登録):

```bash
supabase secrets list
```

期待される出力（9 エントリ）:

```
APPLE_APNS_KEY_ID                           # notify-on-write 用 (Phase 24.3 で実送信)
APPLE_APNS_KEY_P8                           # notify-on-write 用 (Phase 24.3 で実送信)
APPLE_TEAM_ID                               # notify-on-write 用 (Phase 24.3 で実送信)
FIREBASE_SERVICE_ACCOUNT_JSON               # notify-on-write 用 (Phase 24.3 で実送信)
REVENUECAT_WEBHOOK_SECRET                   # revenuecat-webhook 用 (Phase 39 prep)
SKEINLY_BUGREPORT_APP_ID                    # submit-bug-report 用 (Phase 39 W5a)
SKEINLY_BUGREPORT_INSTALLATION_ID           # submit-bug-report 用 (Phase 39 W5a)
SKEINLY_BUGREPORT_PRIVATE_KEY_PEM           # submit-bug-report 用 (Phase 39 W5a)
SKEINLY_DATABASE_WEBHOOK_SECRET             # notify-on-write Database Webhook 署名 (Phase 24.1)
```

iOS パイプラインの最初のエンドツーエンド検証はタグ push 時にのみ行われます — iOS リリースジョブは通常の push ではなくタグトリガーでゲートされています。最初のアルファ/ベータタグ push 前にできること:

- `make release-ipa-local` をローカル実行して iOS 署名チェーンを検証（CI シークレットではなくローカル Mac の keychain を使うが、cert + profile が有効であることは証明できる）。
- Android パイプラインは、`main` ブランチへの任意のコミット push で CI が走り、通常の `assembleDebug` パスでキーストアを exercise（厳密にはリリースパスではないが近い）。

## ローテーションと失効

ローテーションが必要な状況:
- シークレットアクセス権を持つチームメンバーの離脱
- オリジナルの `.p12` / `.p8` を含む laptop の紛失/盗難
- 自動化キーの漏洩疑い（公開ログに誤って echo されたなど）
- 年次衛生（API キー・プロファイルは 12 ヶ月ごと推奨）

具体的に:

| シークレット | ローテーション手順 | 頻度 |
|---|---|---|
| `APPLE_DISTRIBUTION_CERT_BASE64` + パスワード | Apple Developer → Certificates → revoke + 新規作成 + `.p12` 再エクスポート | 年次またはインシデント時 |
| `APPLE_TEAM_ID` | チーム変更なしには変更不可 | 該当なし |
| `APP_STORE_CONNECT_API_KEY_*` | App Store Connect → Team Keys → revoke + 新規生成 | 12 ヶ月ごと推奨 |
| `KEYSTORE_*` | **ローテーション不可**。キーストア紛失は Play Store 更新を破壊。Google Play の「App Signing by Google Play」key reset は最終手段としてのみ。 | 通常状況下では never |
| `SUPABASE_PUBLISHABLE_KEY` | Supabase Dashboard → Project Settings → API Keys → Publishable key を Generate new key | 漏洩疑い時 |
| `FIREBASE_GOOGLE_SERVICES_JSON_BASE64`（Environment scope） | パッケージ名または署名 SHA-1 変更時のみ Firebase Console から再ダウンロード（環境ごと） | 実質的に never |
| `FIREBASE_GOOGLE_SERVICE_INFO_PLIST_BASE64`（Environment scope） | Bundle ID 変更時のみ Firebase Console から再ダウンロード（環境ごと） | 実質的に never |
| `SENTRY_DSN_*` | Sentry → Settings → Project → Client Keys → revoke + 新規作成 | 漏洩疑い時 |
| `SENTRY_AUTH_TOKEN` | **Organization Settings** → Auth Tokens → revoke + 再作成 | 年次またはインシデント時 |
| `POSTHOG_PROJECT_API_KEY` | PostHog → Settings → Project → Project API Key リセット | 漏洩疑い時 |
| `REVENUECAT_API_KEY_*` | RevenueCat → Project Settings → Apps → Public SDK Key revoke + 新規発行 | 漏洩疑い時 |
| Edge Function `APPLE_APNS_KEY_*` | Apple Developer → Keys → revoke + 新規生成 + Supabase secret 再登録 | 年次またはインシデント時 |
| Edge Function `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase Console → Service Accounts → revoke + 新キー | 年次またはインシデント時 |
| Environment `GOOGLE_PLAY_PUBLISHER_SA_JSON_BASE64` | Cloud Console → IAM → Service Accounts → `google-play-publisher@...` → revoke + 新キー → `gh secret set --env production` で再登録 | 年次またはインシデント時 |
| Edge Function `REVENUECAT_WEBHOOK_SECRET` | `openssl rand -hex 32` で新規生成 → RevenueCat Webhook の Authorization header 更新 → Supabase secret 再登録 | 年次またはインシデント時 |
| Edge Function `SKEINLY_DATABASE_WEBHOOK_SECRET` | `openssl rand -hex 32` で新規生成 → Supabase secret 再登録 + Dashboard 上の 3 つの Database Webhook の `Authorization` HTTP ヘッダ値を全部更新 | 年次またはインシデント時 |
| Edge Function `SKEINLY_BUGREPORT_PRIVATE_KEY_PEM` | GitHub App 設定ページ → Generate new private key → `.pem` ダウンロード → `supabase secrets set SKEINLY_BUGREPORT_PRIVATE_KEY_PEM="$(cat new.pem)"` → `supabase functions deploy submit-bug-report` → App 設定ページで旧キー revoke。`SKEINLY_BUGREPORT_APP_ID` + `SKEINLY_BUGREPORT_INSTALLATION_ID` は不変。 | 年次またはインシデント時 |

ローテーション後、影響したシークレットそれぞれに `gh secret set` を再実行してください。次の CI 実行で新しい値が自動的に反映されます。

## セキュリティ注意事項

- **デコード済みファイル**（`.p12`、`.p8`、`.mobileprovision`、`.jks`）**を絶対にリポジトリにコミットしないでください**。パスワード同様に扱ってください。
- **シークレット値を AI アシスタントのチャット**、スクリーンショット、画面録画**に貼り付けないでください**。base64 エンコードは**暗号化ではありません** — 文字列を見た者は誰でもデコードできます。
- **登録前に検証**: 各セクションは `検証` 手順を含みます。検証をスキップすると、タイポが本番に landed しタグ push 時に初めて表面化します。
- **`.p8` と `.jks` は再ダウンロード不可**です — 生成直後に暗号化ストレージ（パスワードマネージャー、暗号化ディスクイメージ）にバックアップしてください。
- **GitHub Secrets は保管時暗号化**されますが、`secrets.*` アクセスを持つ任意のワークフローから可視化されます。`permissions:` と `if:` ガードで機密シークレットを読むワークフロー数を制限してください。
- **iOS 7 シークレットは CI に貴方の Apple Developer アカウントとして TestFlight にアップロードする権限を与えます**。漏洩は攻撃者に TestFlight テスター向け悪意のあるビルド送信を許します。漏洩疑い時は最初に `APP_STORE_CONNECT_API_KEY_*` を revoke してください — 影響範囲が最も広いです。
