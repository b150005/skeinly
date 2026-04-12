> Source: [English version](../../en/adr/006-ci-signing-strategy.md)

# ADR-006: CI/CD コード署名・配布戦略

## ステータス

承認済み

## コンテキスト

Knit Note は Android と iOS の両プラットフォームをターゲットとしている。リリースワークフロー (`release.yml`) は GitHub Secrets に base64 エンコードされたキーストアを使用して署名済み Android APK を生成済みだが、iOS パイプラインは共有 KMP フレームワークのビルドのみで、iOS アプリのアーカイブ・署名・配布は行っていない。

TestFlight（および最終的に App Store）に到達するには、CI パイプラインが以下を行う必要がある：

1. Apple Distribution 証明書とプロビジョニングプロファイルを CI ランナーにインポート
2. `xcodebuild archive` で iOS アプリをアーカイブ
3. `xcodebuild -exportArchive` で署名済み IPA をエクスポート
4. IPA を App Store Connect にアップロードし TestFlight 配布

主な制約：

- プロジェクトは Fastlane を使用せず **GitHub Actions** を直接使用
- iOS プロジェクトは **XcodeGen** により `iosApp/project.yml` から生成
- 共有 KMP フレームワークは xcodebuild アーカイブ前に Gradle でビルドが必要
- Apple Developer Program 登録と有効な証明書が前提条件
- 署名シークレット未設定時にも**正常にデグレード**すべき

## 決定

### 署名方式

**ネイティブ xcodebuild + 手動コード署名**を使用。Fastlane やサードパーティ署名ツールは不使用。

- **証明書**: Apple Distribution `.p12` を `IOS_DISTRIBUTION_CERT_BASE64` GitHub Secret として保存
- **プロビジョニングプロファイル**: App Store 配布用 `.mobileprovision` を `IOS_PROVISIONING_PROFILE_BASE64` として保存
- **一時キーチェーン**: CI 実行ごとに `$RUNNER_TEMP/app-signing.keychain-db` にランダムパスワードで作成。`if: always()` クリーンアップステップで破棄
- **ExportOptions.plist**: CI 実行時に生成（リポジトリにコミットしない）。リポジトリを特定の Apple Developer アカウントに依存させないため。プロビジョニングプロファイル名はインストール済み `.mobileprovision` から実行時に抽出

### TestFlight アップロード

**App Store Connect API キー**認証パラメータ（`-authenticationKeyPath`、`-authenticationKeyID`、`-authenticationKeyIssuerID`）を使用した `xcodebuild -exportArchive` を使用。ExportOptions.plist の `destination` を `upload` に設定すると、xcodebuild が IPA エクスポートと App Store Connect アップロードを単一ステップで処理する — 別途アップロードツール不要。

これにより、Xcode 14 以降非推奨となった `xcrun altool --upload-app` の使用を回避する。

### 条件付き実行

すべての iOS 署名・アップロードステップはシークレットの存在チェックでゲート：

- **署名シークレット** (`IOS_DISTRIBUTION_CERT_BASE64`, `IOS_DISTRIBUTION_CERT_PASSWORD`, `IOS_PROVISIONING_PROFILE_BASE64`, `APPLE_TEAM_ID`): アーカイブと IPA エクスポートに必要
- **TestFlight シークレット** (`APP_STORE_CONNECT_API_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID`, `APP_STORE_CONNECT_API_KEY_BASE64`): アップロードのみに必要

シークレット未設定時は共有フレームワークアーティファクトのビルドのみ実行し、警告アノテーションを出力 — 失敗にはならない。

### バージョン番号付け

- `MARKETING_VERSION` (CFBundleShortVersionString): git タグから導出（例: `v0.2.0` → `0.2.0`）
- `CURRENT_PROJECT_VERSION` (CFBundleVersion): `github.run_number` を設定し、TestFlight アップロードごとにユニークな自動インクリメントビルド番号を保証

### 必要な GitHub Secrets

| シークレット | 用途 |
|-------------|------|
| `IOS_DISTRIBUTION_CERT_BASE64` | Base64 エンコードされた .p12 配布証明書 |
| `IOS_DISTRIBUTION_CERT_PASSWORD` | .p12 ファイルのパスワード |
| `IOS_PROVISIONING_PROFILE_BASE64` | Base64 エンコードされた .mobileprovision |
| `APPLE_TEAM_ID` | Apple Developer Team ID（10文字） |
| `APP_STORE_CONNECT_API_KEY_ID` | App Store Connect API キー ID |
| `APP_STORE_CONNECT_ISSUER_ID` | App Store Connect 発行者 ID |
| `APP_STORE_CONNECT_API_KEY_BASE64` | Base64 エンコードされた .p8 API キー |

### Android 署名（変更なし）

Android 署名は Phase 9 で確立済みで変更なし：

- `KEYSTORE_BASE64`: base64 エンコードされた `.jks` キーストア、`$RUNNER_TEMP/keystore.jks` にデコード
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`: 署名クレデンシャル
- シークレット未設定時はデバッグ署名にフォールバック

## 結果

### ポジティブ

- 追加ビルドツール依存なし（Fastlane 不要、Ruby ランタイム不要）
- シークレットがディスクに永続化されない — 一時キーチェーンとファイルは毎回クリーンアップ
- 正常なデグレード: Apple Developer アカウントを持たないコントリビューターもリリースをトリガー可能（Android のみ出力）
- 再現性: 同じタグは常に同じバージョンメタデータを生成
- 署名済み IPA は Android APK と共に GitHub Release に添付

### ネガティブ

- Fastlane ベースのアプローチと比較してワークフロー YAML が冗長
- ExportOptions.plist 生成がプロビジョニングプロファイルのイントロスペクションに依存 — バンドル ID 変更時に脆弱
- `xcodebuild -exportArchive` の `-authenticationKeyPath` は API キーの一時的なディスクへのデコードが必要（各実行後にクリーンアップ）
- プロビジョニングプロファイルは年次で期限切れとなり手動更新が必要

### ニュートラル

- iOS 配布の前提条件として Apple Developer Program 登録（$99/年）が必要
- TestFlight ビルドは外部テストにはApp Store Connect レビューが必要（内部テストは即時）

## 検討した代替案

| 代替案 | 長所 | 短所 | 不採用の理由 |
|--------|------|------|-------------|
| Fastlane (match + pilot) | 豊富なエコシステム、match がプロファイルを自動管理、pilot が TestFlight を処理 | Ruby 依存の追加、追加ツールの保守、学習コスト | 単一アプリプロジェクトには不要な複雑性 |
| Xcode Cloud | ネイティブ Apple CI、自動署名 | ベンダーロックイン、カスタマイズ制限、GitHub Actions と分離 | プロジェクトは既に GitHub Actions を使用; CI 分割は複雑性を増加 |
| App Store Connect REST API（直接） | 将来性、完全な制御 | コード量増加（マルチパートアップロード、JWT 認証）、デバッグ困難 | `xcodebuild -exportArchive` の認証キーパラメータがシンプルで Apple 公式サポート |
| コミット済み ExportOptions.plist | シンプルなワークフロー、ランタイム plist 生成不要 | リポジトリを特定 Apple アカウントに結合、チーム ID の漏洩 | 生成方式がよりポータブル |
