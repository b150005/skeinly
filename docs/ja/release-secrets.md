# リリースシークレット — セットアップガイド

> English source: [docs/en/release-secrets.md](../en/release-secrets.md)

このドキュメントは、リリースパイプラインおよび Supabase Edge Function が消費する全シークレットの取得・検証・登録手順を段階的にまとめたガイドです。**GitHub Secrets 19 個**を 6 カテゴリ + **Supabase Edge Function ランタイムシークレット 4 個**を 7 番目のカテゴリで扱います:

**GitHub Secrets**（`gh secret set` で登録）:
- **iOS コード署名**（4 個）— Distribution 証明書 + provisioning profile + Team ID
- **App Store Connect API**（3 個）— `.p8` API キー + Key ID + Issuer ID
- **Android 署名**（4 個）— キーストア + パスワード + alias
- **Supabase ランタイム**（2 個）— バックエンド URL + anon キー
- **Android FCM クライアント**（1 個）— Push クライアント SDK 用 `google-services.json`
- **クラッシュ + エラー報告**（3 個）— Sentry DSN（iOS + Android）+ Auth Token
- **分析**（2 個）— PostHog プロジェクト API キー（prod + dev）

**Supabase Edge Function Secrets**（`supabase secrets set` で登録）:
- **iOS Push**（2 個）— APNs `.p8` + Key ID
- **Android Push**（1 個）— FCM HTTP v1 用 Firebase Service Account JSON
- **Android IAP**（1 個）— レシート検証用 Google Play Service Account JSON
- （App Store Connect API キーは GitHub Secrets から再利用 — 同じ `.p8` ファイルを 2 つのコンテキストで登録）

リリースワークフロー（[`.github/workflows/release.yml`](../../.github/workflows/release.yml)）は GitHub Secrets を `${{ secrets.* }}` として読みます。値の欠如や誤りはサイレントに失敗するもの（ビルドは成功するがアップロードされない）と、明示的に失敗するもの（署名失敗）があります。Supabase Edge Function は自身のシークレットを `Deno.env.get(...)` で読みます。本ガイドは登録**前**に値の妥当性を確認するための検証手順を含みます。

## 目次

- [前提ツール](#前提ツール)
- [GitHub にシークレットを登録する方法](#github-にシークレットを登録する方法)
- [iOS コード署名（4 シークレット）](#ios-コード署名4-シークレット)
- [App Store Connect API（3 シークレット）](#app-store-connect-api3-シークレット)
- [Android 署名（4 シークレット）](#android-署名4-シークレット)
- [Supabase ランタイム（2 シークレット）](#supabase-ランタイム2-シークレット)
- [Android FCM クライアント（1 シークレット）](#android-fcm-クライアント1-シークレット)
- [クラッシュ + エラー報告 — Sentry（3 シークレット）](#クラッシュ--エラー報告--sentry3-シークレット)
- [分析 — PostHog（2 シークレット）](#分析--posthog2-シークレット)
- [Supabase Edge Function Secrets（4 シークレット）](#supabase-edge-function-secrets4-シークレット)
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

シークレット関連コマンドはリポジトリルート（`cd knit-note`）で実行する前提です。

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

## iOS コード署名（4 シークレット）

これら 4 つの暗号資産により、CI が Apple Developer チームとして iOS アプリに署名できるようになります。App Store Connect API 認証とは独立しています（後述）。

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

### 3. `APPLE_PROVISIONING_PROFILE_BASE64`

**内容**: App Store プロビジョニングプロファイル `.mobileprovision` を base64 エンコードしたもの。

**取得手順:**

1. [Apple Developer](https://developer.apple.com/account) にサインイン → **Certificates, Identifiers & Profiles** → **Profiles**。
2. 以下のいずれか:
   - 既存のプロファイルが `io.github.b150005.knitnote` の App Store 配布用にあれば、それをクリック → **Download**。
   - なければ `+` → Distribution の **App Store** → bundle ID `io.github.b150005.knitnote` を選択 → 手順 1 の Apple Distribution cert を選択 → 名前（例: `Knit Note App Store`）→ **Generate** → **Download**。
3. base64 エンコード:

   ```bash
   base64 -i Knit_Note_App_Store.mobileprovision -o profile.base64
   ```

**検証:**

```bash
# CMS エンベロープをデコード — 出力はプロファイルメタデータの XML plist。
security cms -D -i Knit_Note_App_Store.mobileprovision | head -40
```

確認項目:
- `<key>Name</key>` の値がプロファイル名と一致すること
- `<key>application-identifier</key>` が `io.github.b150005.knitnote` で終わること
- `<key>ExpirationDate</key>` が未来の日付であること（通常 1 年後）
- `<key>TeamIdentifier</key>` が次のシークレットの Team ID と一致すること

**登録:**

```bash
gh secret set APPLE_PROVISIONING_PROFILE_BASE64 < profile.base64
```

**ローテーション**: プロファイルは作成から 1 年で失効します。11 ヶ月時点でカレンダーリマインダーを設定してください。更新手順は手順 2 と同一です。

### 4. `APPLE_TEAM_ID`

**内容**: Apple Developer の 10 文字英数字 Team ID。

**取得手順:**

- [Apple Developer](https://developer.apple.com/account) → **Membership** → **Team ID** フィールド。
- もしくはダウンロードした `.mobileprovision` から抽出:

   ```bash
   security cms -D -i Knit_Note_App_Store.mobileprovision \
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

### 5. `APP_STORE_CONNECT_API_KEY_BASE64`

**内容**: `.p8` 秘密鍵ファイル（PKCS#8 形式）を base64 エンコードしたもの。

**取得手順:**

1. [App Store Connect](https://appstoreconnect.apple.com) にサインイン。
2. **Users and Access** → **Integrations** タブ → **Team Keys**。
3. **Generate API Key** をクリック（既存キーがある場合は `+`）。
4. 設定:
   - **Name**: 例えば `knit-note CI`。
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

### 6. `APP_STORE_CONNECT_API_KEY_ID`

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

### 7. `APP_STORE_CONNECT_ISSUER_ID`

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

### 8. `KEYSTORE_BASE64`

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

### 9. `KEYSTORE_PASSWORD`

**内容**: `keytool -genkey` 中に設定した「キーストアパスワード」。

**取得手順**: 自分で設定したもの。忘れた場合キーストアは復旧不可能です — 上記バックアップ参照。

**登録:**

```bash
gh secret set KEYSTORE_PASSWORD
# 貼り付け → Ctrl+D
```

### 10. `KEY_ALIAS`

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

### 11. `KEY_PASSWORD`

**内容**: `keytool -genkey` 中に設定した「キーパスワード」（キーストアパスワードとは別）。CI 構成簡略化のため `KEYSTORE_PASSWORD` と同じ値にすることが多い。

**登録:**

```bash
gh secret set KEY_PASSWORD
# 貼り付け → Ctrl+D
```

## Supabase ランタイム（2 シークレット）

これらのシークレットはビルド時に Android APK と iOS IPA に焼き込まれ、アプリが Supabase バックエンドに接続できるようにします。署名シークレットでは**ありません** — ランタイム設定です。

### 12. `SUPABASE_URL`

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

### 13. `SUPABASE_ANON_KEY`

**内容**: 「anon」公開 API キー。クライアントアプリへの埋め込みを想定（RLS ポリシーがデータベース層で不正アクセスを防ぐ）。

**取得手順:**

- 同じ Project Settings ページ → **API Keys** セクション → **anon public** キーをコピー（`eyJ` で始まる長い JWT 形式の文字列）。

**検証**: `eyJ` で始まる（Base64-URL エンコードされた JWT）。中央セグメントをデコード（[jwt.io](https://jwt.io) 等）すると `"role": "anon"` と `"ref": "<project-ref>"` が表示されるはず。

**登録:**

```bash
gh secret set SUPABASE_ANON_KEY
# 長い JWT を貼り付け → Ctrl+D
```

**`service_role` キーをクライアントビルド用 GitHub Secret に登録しないでください**。service-role キーは RLS をバイパスするため、APK に同梱されると重大な漏洩となります。

## Android FCM クライアント（1 シークレット）

このシークレットは Android アプリ用 Firebase プロジェクトのクライアント側設定です。ビルド時に読み込まれて APK に焼き込まれ、FCM SDK が Firebase に登録できるようになります。値はパッケージ名 + 署名証明書 SHA-1 で Firebase 側で制限されているので漏洩時の影響範囲は小さいですが、整理目的と環境別差し替え対応のため git ignored で管理します。

### 14. `FIREBASE_GOOGLE_SERVICES_JSON_BASE64`

**WHAT**: Firebase Console から Android アプリ用にダウンロードした `google-services.json` の Base64 エンコード版。

**OBTAIN:**

1. [Firebase Console](https://console.firebase.google.com) にサインイン
2. Knit Note 用 Firebase プロジェクトがなければ: **Add project** → 名前 `knit-note` → **Disable Google Analytics** (PostHog を使うため) → Create
3. プロジェクト内: **Project Overview** → **Add app** → Android アイコン
4. Android パッケージ名: `io.github.b150005.knitnote`
5. App nickname: `Knit Note Android`
6. 署名証明書 SHA-1: `keytool -list -v -keystore upload-keystore.jks` を実行して **SHA-1** fingerprint をコピー（リリース keystore — debug ビルドは別の自動生成 keystore を使うので alpha ではここの登録は不要。FCM debug テストが必要になったら debug SHA-1 を後追加）
7. Continue → Continue → **`google-services.json` をダウンロード** → Continue → SDK セットアップ手順はスキップ（Gradle で別途配線）
8. Base64 エンコード:

   ```bash
   base64 -i google-services.json -o google-services.base64
   ```

**VERIFY:**

```bash
# "project_info" と "client" キーを持つ JSON オブジェクトが表示されるはず
cat google-services.json | python3 -m json.tool | head -10
```

確認:
- `project_info.project_id` が Firebase プロジェクト名 (`knit-note`) と一致
- `client[0].client_info.android_client_info.package_name` が `io.github.b150005.knitnote`
- `client[0].api_key[0].current_key` が長い英数字文字列（パッケージ + SHA-1 で制限された Android API キー）

**REGISTER:**

```bash
gh secret set FIREBASE_GOOGLE_SERVICES_JSON_BASE64 < google-services.base64
```

Android Gradle ビルドはこれをビルド時に `androidApp/google-services.json` (git ignored) にデコードします。

**ROTATE**: パッケージ名変更（Phase 28 で確定済み）または署名 SHA-1 変更（upload keystore 紛失を意味する — 復旧不可、[§8 Backup](#8-keystore_base64) 参照）の場合のみ Firebase Console から再ダウンロード。日常的なローテーションは不要。

## クラッシュ + エラー報告 — Sentry（3 シークレット）

これらのシークレットは iOS + Android で Sentry SDK を配線し、CI がリリースビルド後に debug symbols (iOS は dSYM、Android は mapping ファイル) を Sentry にアップロードしてスタックトレースを自動シンボル化できるようにします。

### 15. `SENTRY_DSN_IOS`

**WHAT**: iOS Sentry プロジェクトの Data Source Name (DSN)。Sentry プロジェクト + 認証を識別する URL 形式の文字列。

**OBTAIN:**

1. [Sentry](https://sentry.io) にサインイン。Knit Note 用組織がなければ作成
2. **Projects** → **Create Project** → Platform: **Apple iOS** → Project Name: `knit-note-ios` → Create
3. 作成後: **Settings** → **Projects** → `knit-note-ios` → **Client Keys (DSN)** → DSN をコピー
4. 形式: `https://<32-char-public-key>@<org>.ingest.sentry.io/<project-id>`

**VERIFY**: URL が HTTPS、`@` 含む、`.ingest.sentry.io` 含む、数値プロジェクト ID で終わる。

**REGISTER:**

```bash
gh secret set SENTRY_DSN_IOS
# DSN を貼り付け、Ctrl+D
```

**ROTATE**: Sentry → Settings → Projects → `knit-note-ios` → Client Keys → 古い key を revoke + 新規作成。GitHub Secret を更新。

### 16. `SENTRY_DSN_ANDROID`

**WHAT**: Android Sentry プロジェクトの DSN。`SENTRY_DSN_IOS` と同じ形式だが iOS と Android のクラッシュをダッシュボード上で独立してフィルタリングできるよう別プロジェクトを使う。

**OBTAIN**: #15 の手順を繰り返すが、Platform: **Android** を選び、プロジェクト名を `knit-note-android` に。

**REGISTER:**

```bash
gh secret set SENTRY_DSN_ANDROID
# DSN を貼り付け、Ctrl+D
```

### 17. `SENTRY_AUTH_TOKEN`

**WHAT**: 各リリースビルド後に CI が Sentry に dSYM (iOS) / mapping ファイル (Android) をアップロードするための user auth token。スタックトレースの自動シンボル化に必要。

**OBTAIN:**

1. Sentry → 左上のアバタークリック → **User Settings** → **Auth Tokens**
2. **Create New Token**
3. Name: `knit-note CI`
4. Scopes (最小):
   - `project:releases` — release 作成 + アーティファクトアップロード
   - `org:read` — org/projects リスト (sentry-cli が slug からプロジェクトを解決するのに必要)
5. Create → token を即座にコピー (一度限り表示)

**VERIFY:**

```bash
# Token の形: 64-char hex; sentry-cli で接続検証可能
brew install getsentry/tools/sentry-cli  # 未インストールなら
sentry-cli --auth-token <token> info
# 組織名 + プロジェクト一覧が表示されるはず
```

**REGISTER:**

```bash
gh secret set SENTRY_AUTH_TOKEN
# token を貼り付け、Ctrl+D
```

**ROTATE**: User Settings → Auth Tokens → revoke + 再作成。GitHub Secret を更新。

## 分析 — PostHog（2 シークレット）

これらのシークレットは iOS + Android で PostHog SDK を配線します。Sentry とは違い、両プラットフォームで **同じプロジェクトキー**を使いますが、**production / development** PostHog プロジェクトは分けて、開発時のイベントが prod データセットを汚染しないようにします。両方のキーは `auto_capture: false` 設定 + Settings の opt-in トグル（「使用状況の収集を許可」、デフォルト OFF）でゲートされます — Phase 27a プライバシーポリシーに従う。

### 18. `POSTHOG_PROJECT_API_KEY_PROD`

**WHAT**: production PostHog プロジェクトの Project API Key。release ビルドに焼き込まれる。形式: `phc_<43-char-base62>`。

**OBTAIN:**

1. [PostHog](https://eu.posthog.com) にサインイン (GDPR データレジデンシーのため **EU クラウド**を使う)
2. 組織がなければ `knit-note` という名前で作成
3. **Settings** → **Projects** → **Create Project** → Name: `knitnote-prod`
4. プロジェクト内: **Settings** → **Project** → **General** → **Project API Key** → 値をコピー (`phc_` で始まる)

**VERIFY**: `phc_` 始まり、合計 47 文字 (`phc_` + 43 文字 base62)、大文字小文字区別。

**REGISTER:**

```bash
gh secret set POSTHOG_PROJECT_API_KEY_PROD
# 貼り付け、Ctrl+D
```

**ROTATE**: PostHog → Settings → Project → Project API Key リセット。GitHub Secret を更新。古いイベントはプロジェクトに紐付いたまま、新規取り込みのみシフト。

### 19. `POSTHOG_PROJECT_API_KEY_DEV`

**WHAT**: development PostHog プロジェクトの Project API Key。debug ビルドに焼き込まれることで、開発者の churn が prod データセットに混入しない。

**OBTAIN**: #18 を繰り返すが、`knitnote-dev` という**別プロジェクト**を作り、そのプロジェクトの API キーをコピー。

**REGISTER:**

```bash
gh secret set POSTHOG_PROJECT_API_KEY_DEV
# 貼り付け、Ctrl+D
```

## Supabase Edge Function Secrets（4 シークレット）

これらのシークレットは Supabase Edge Function (`notify-on-write` で Push 送信、`verify-receipt` で IAP レシート検証) が消費します。**GitHub Secrets ではありません** — Supabase CLI で Supabase プロジェクトに登録します:

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

(Bundle ID `io.github.b150005.knitnote` は Edge Function ソースに直接埋め込まれており、シークレットには登録しません)

### EF-3. `FIREBASE_SERVICE_ACCOUNT_JSON`

**WHAT**: FCM HTTP v1 API 経由で Push を送信するための Service Account JSON。Edge Function はこれを使って FCM 用の短命 OAuth 2.0 access token を発行します。

**OBTAIN:**

1. [Firebase Console](https://console.firebase.google.com) → プロジェクト (`knit-note`) → **Project Settings** → **Service Accounts**
2. **Firebase Admin SDK** タブ → **Generate new private key** → 確認 → JSON ダウンロード
3. デフォルト付与ロール **Firebase Admin SDK Administrator Service Agent** に Cloud Messaging が含まれている — 追加 IAM 設定不要

**VERIFY**: JSON が `"type": "service_account"` を含む、`project_id` が Firebase プロジェクトと一致、`client_email` が `@<project-id>.iam.gserviceaccount.com` で終わる。

**REGISTER:**

```bash
supabase secrets set FIREBASE_SERVICE_ACCOUNT_JSON="$(cat firebase-admin-sdk.json)"
```

**ROTATE**: Firebase Console → Project Settings → Service Accounts → Manage all service accounts → SA クリック → Keys タブ → 古い key を revoke + 新規 JSON 追加。

### EF-4. `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`

**WHAT**: Google Play Developer API 用の Service Account JSON。Android IAP レシートのサーバー側検証および subscription 状態変更（更新、解約、返金）の観測に使います。

**OBTAIN:**

1. [Google Play Console](https://play.google.com/console) → **Setup** → **API access**
2. リンク済みでなければ: **Choose a project to link** → Firebase と同じ Google Cloud プロジェクトを使用 (または別プロジェクトでも OK。再利用すると billing が単純化)
3. **Service accounts** セクション → **Create new service account** → Google Cloud Console が開く
4. Service Account Name: `knit-note-play-publisher`
5. Cloud Console での role 付与はスキップ (Play Console 側 UI で別途付与)
6. Done → Cloud Console に戻り SA クリック → **Keys** → **Add Key** → **Create new key** → JSON → ダウンロード
7. Play Console **API access** に戻り、新しい SA をクリック → **Grant access**
8. 権限: 最低 **View financial data**、**Manage orders**、**Manage store presence** (レシート検証をカバー)
9. Apply

**VERIFY**: JSON が `"type": "service_account"`。Play Console **API access** ページの **Service accounts** にこの SA がリストされ、付与した権限に緑のチェックマーク。

**REGISTER:**

```bash
supabase secrets set GOOGLE_PLAY_SERVICE_ACCOUNT_JSON="$(cat play-developer-api.json)"
```

**ROTATE**: Cloud Console → IAM → Service Accounts → SA クリック → Keys → 古い key を revoke + 新規 JSON 作成。Supabase Edge Function secret を更新。

### 再利用: App Store Connect API キー

Edge Function `verify-receipt` (iOS 分岐) は App Store Server API を呼び出すために、GitHub Secrets §5–§7 で既に登録した同じ App Store Connect API キーが必要です。Supabase 側にも登録 (single source of truth、登録場所が 2 箇所):

```bash
# GitHub Secret APP_STORE_CONNECT_API_KEY_BASE64 と同じ .p8 ファイル、ただし base64 ではなく生
supabase secrets set APP_STORE_CONNECT_API_KEY="$(cat AuthKey_XYZ1234567.p8)"
supabase secrets set APP_STORE_CONNECT_KEY_ID=XYZ1234567
supabase secrets set APP_STORE_CONNECT_ISSUER_ID=69a6de70-03db-47e3-e053-5b8c7c11a4d1
```

[§5 ROTATE](#5-app_store_connect_api_key_base64) に従って GitHub Secret をローテートする際、Supabase Edge Function secret も新キーで再登録してください。

## 一括検証

19 個の GitHub Secrets 全てを登録した後、`gh` で確認:

```bash
gh secret list
```

期待される出力（名前と最終更新日時。値は決して表示されない）:

```
APPLE_DISTRIBUTION_CERT_BASE64        Updated YYYY-MM-DD
APPLE_DISTRIBUTION_CERT_PASSWORD      Updated YYYY-MM-DD
APPLE_PROVISIONING_PROFILE_BASE64     Updated YYYY-MM-DD
APPLE_TEAM_ID                         Updated YYYY-MM-DD
APP_STORE_CONNECT_API_KEY_BASE64      Updated YYYY-MM-DD
APP_STORE_CONNECT_API_KEY_ID          Updated YYYY-MM-DD
APP_STORE_CONNECT_ISSUER_ID           Updated YYYY-MM-DD
FIREBASE_GOOGLE_SERVICES_JSON_BASE64  Updated YYYY-MM-DD
KEY_ALIAS                             Updated YYYY-MM-DD
KEY_PASSWORD                          Updated YYYY-MM-DD
KEYSTORE_BASE64                       Updated YYYY-MM-DD
KEYSTORE_PASSWORD                     Updated YYYY-MM-DD
POSTHOG_PROJECT_API_KEY_DEV           Updated YYYY-MM-DD
POSTHOG_PROJECT_API_KEY_PROD          Updated YYYY-MM-DD
SENTRY_AUTH_TOKEN                     Updated YYYY-MM-DD
SENTRY_DSN_ANDROID                    Updated YYYY-MM-DD
SENTRY_DSN_IOS                        Updated YYYY-MM-DD
SUPABASE_ANON_KEY                     Updated YYYY-MM-DD
SUPABASE_URL                          Updated YYYY-MM-DD
```

19 エントリ。欠けているものや古いタイムスタンプのものは要確認。

Supabase Edge Function secrets (`supabase secrets set` で登録):

```bash
supabase secrets list
```

期待される出力（8 エントリ — Edge Function 固有 4 個 + App Store Connect API から再利用 4 個）:

```
APP_STORE_CONNECT_API_KEY
APP_STORE_CONNECT_ISSUER_ID
APP_STORE_CONNECT_KEY_ID
APPLE_APNS_KEY_ID
APPLE_APNS_KEY_P8
APPLE_TEAM_ID
FIREBASE_SERVICE_ACCOUNT_JSON
GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
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
| `APPLE_PROVISIONING_PROFILE_BASE64` | Apple Developer → Profiles → 再生成（同じ cert） | Apple により年次強制 |
| `APPLE_TEAM_ID` | チーム変更なしには変更不可 | 該当なし |
| `APP_STORE_CONNECT_API_KEY_*` | App Store Connect → Team Keys → revoke + 新規生成（Supabase Edge Function secret も再登録） | 12 ヶ月ごと推奨 |
| `KEYSTORE_*` | **ローテーション不可**。キーストア紛失は Play Store 更新を破壊。Google Play の「App Signing by Google Play」key reset は最終手段としてのみ。 | 通常状況下では never |
| `SUPABASE_*` | Supabase Dashboard → Project Settings → API Keys → anon キーをリセット | 漏洩疑い時 |
| `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` | パッケージ名または署名 SHA-1 変更時のみ Firebase Console から再ダウンロード | 実質的に never |
| `SENTRY_DSN_*` | Sentry → Settings → Project → Client Keys → revoke + 新規作成 | 漏洩疑い時 |
| `SENTRY_AUTH_TOKEN` | User Settings → Auth Tokens → revoke + 再作成 | 年次またはインシデント時 |
| `POSTHOG_PROJECT_API_KEY_*` | PostHog → Settings → Project → Project API Key リセット | 漏洩疑い時 |
| Edge Function `APPLE_APNS_KEY_*` | Apple Developer → Keys → revoke + 新規生成 + Supabase secret 再登録 | 年次またはインシデント時 |
| Edge Function `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase Console → Service Accounts → revoke + 新キー | 年次またはインシデント時 |
| Edge Function `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Cloud Console → IAM → Service Accounts → revoke + 新キー | 年次またはインシデント時 |

ローテーション後、影響したシークレットそれぞれに `gh secret set` を再実行してください。次の CI 実行で新しい値が自動的に反映されます。

## セキュリティ注意事項

- **デコード済みファイル**（`.p12`、`.p8`、`.mobileprovision`、`.jks`）**を絶対にリポジトリにコミットしないでください**。パスワード同様に扱ってください。
- **シークレット値を AI アシスタントのチャット**、スクリーンショット、画面録画**に貼り付けないでください**。base64 エンコードは**暗号化ではありません** — 文字列を見た者は誰でもデコードできます。
- **登録前に検証**: 各セクションは `検証` 手順を含みます。検証をスキップすると、タイポが本番に landed しタグ push 時に初めて表面化します。
- **`.p8` と `.jks` は再ダウンロード不可**です — 生成直後に暗号化ストレージ（パスワードマネージャー、暗号化ディスクイメージ）にバックアップしてください。
- **GitHub Secrets は保管時暗号化**されますが、`secrets.*` アクセスを持つ任意のワークフローから可視化されます。`permissions:` と `if:` ガードで機密シークレットを読むワークフロー数を制限してください。
- **iOS 7 シークレットは CI に貴方の Apple Developer アカウントとして TestFlight にアップロードする権限を与えます**。漏洩は攻撃者に TestFlight テスター向け悪意のあるビルド送信を許します。漏洩疑い時は最初に `APP_STORE_CONNECT_API_KEY_*` を revoke してください — 影響範囲が最も広いです。
