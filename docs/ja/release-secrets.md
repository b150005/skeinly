# リリースシークレット — セットアップガイド

> English source: [docs/en/release-secrets.md](../en/release-secrets.md)

このドキュメントは、リリースパイプラインが消費する全 GitHub Secret の取得・検証・登録手順を段階的にまとめたガイドです。3 カテゴリ計 13 個のシークレットを扱います:

- **iOS**（7 個）— コード署名 + App Store Connect API 認証
- **Android**（4 個）— キーストア署名
- **ランタイム**（2 個）— Supabase バックエンド認証情報

リリースワークフロー（[`.github/workflows/release.yml`](../../.github/workflows/release.yml)）はこれらを `${{ secrets.* }}` として読みます。値の欠如や誤りはサイレントに失敗するもの（ビルドは成功するがアップロードされない）と、明示的に失敗するもの（署名失敗）があります。本ガイドは登録**前**に値の妥当性を確認するための検証手順を含みます。

## 目次

- [前提ツール](#前提ツール)
- [GitHub にシークレットを登録する方法](#github-にシークレットを登録する方法)
- [iOS コード署名（4 シークレット）](#ios-コード署名4-シークレット)
- [App Store Connect API（3 シークレット）](#app-store-connect-api3-シークレット)
- [Android 署名（4 シークレット）](#android-署名4-シークレット)
- [Supabase ランタイム（2 シークレット）](#supabase-ランタイム2-シークレット)
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

## 一括検証

13 シークレット全てを登録した後、`gh` で確認:

```bash
gh secret list
```

期待される出力（名前と最終更新日時。値は決して表示されない）:

```
APPLE_TEAM_ID                      Updated YYYY-MM-DD
APP_STORE_CONNECT_API_KEY_BASE64   Updated YYYY-MM-DD
APP_STORE_CONNECT_API_KEY_ID       Updated YYYY-MM-DD
APP_STORE_CONNECT_ISSUER_ID        Updated YYYY-MM-DD
APPLE_DISTRIBUTION_CERT_BASE64       Updated YYYY-MM-DD
APPLE_DISTRIBUTION_CERT_PASSWORD     Updated YYYY-MM-DD
APPLE_PROVISIONING_PROFILE_BASE64    Updated YYYY-MM-DD
KEYSTORE_BASE64                    Updated YYYY-MM-DD
KEYSTORE_PASSWORD                  Updated YYYY-MM-DD
KEY_ALIAS                          Updated YYYY-MM-DD
KEY_PASSWORD                       Updated YYYY-MM-DD
SUPABASE_ANON_KEY                  Updated YYYY-MM-DD
SUPABASE_URL                       Updated YYYY-MM-DD
```

13 エントリ。欠けているものや古いタイムスタンプのものは要確認。

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
| `APP_STORE_CONNECT_API_KEY_*` | App Store Connect → Team Keys → revoke + 新規生成 | 12 ヶ月ごと推奨 |
| `KEYSTORE_*` | **ローテーション不可**。キーストア紛失は Play Store 更新を破壊。Google Play の「App Signing by Google Play」key reset は最終手段としてのみ。 | 通常状況下では never |
| `SUPABASE_*` | Supabase Dashboard → Project Settings → API Keys → anon キーをリセット | 漏洩疑い時 |

ローテーション後、影響したシークレットそれぞれに `gh secret set` を再実行してください。次の CI 実行で新しい値が自動的に反映されます。

## セキュリティ注意事項

- **デコード済みファイル**（`.p12`、`.p8`、`.mobileprovision`、`.jks`）**を絶対にリポジトリにコミットしないでください**。パスワード同様に扱ってください。
- **シークレット値を AI アシスタントのチャット**、スクリーンショット、画面録画**に貼り付けないでください**。base64 エンコードは**暗号化ではありません** — 文字列を見た者は誰でもデコードできます。
- **登録前に検証**: 各セクションは `検証` 手順を含みます。検証をスキップすると、タイポが本番に landed しタグ push 時に初めて表面化します。
- **`.p8` と `.jks` は再ダウンロード不可**です — 生成直後に暗号化ストレージ（パスワードマネージャー、暗号化ディスクイメージ）にバックアップしてください。
- **GitHub Secrets は保管時暗号化**されますが、`secrets.*` アクセスを持つ任意のワークフローから可視化されます。`permissions:` と `if:` ガードで機密シークレットを読むワークフロー数を制限してください。
- **iOS 7 シークレットは CI に貴方の Apple Developer アカウントとして TestFlight にアップロードする権限を与えます**。漏洩は攻撃者に TestFlight テスター向け悪意のあるビルド送信を許します。漏洩疑い時は最初に `APP_STORE_CONNECT_API_KEY_*` を revoke してください — 影響範囲が最も広いです。
