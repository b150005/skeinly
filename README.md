# Skeinly

A Kotlin Multiplatform knitting app for managing patterns, tracking progress, and sharing with others.

[English](#english) | [日本語](#japanese)

---

<a id="english"></a>

## English

### Features

- **Pattern Management** — Store, organize, and browse knitting charts and patterns
- **Progress Tracking** — Track row-by-row or section-based progress on your knitting projects
- **Sharing** — Share patterns and progress snapshots with other knitters

### Platforms

| Platform | UI Framework | Status |
|----------|-------------|--------|
| Android | Jetpack Compose + Material 3 | Active |
| iOS | SwiftUI | Active |
| macOS | SwiftUI | Stretch goal |

### Architecture

- **Kotlin Multiplatform (KMP)** shared module for business logic, domain models, and data access
- **Platform-native UI**: Jetpack Compose (Android) and SwiftUI (iOS/macOS)
- **Local-first** data storage with SQLDelight, with cloud sync via Supabase
- **Clean Architecture**: UI → ViewModel → UseCase → Repository → DataSource

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Shared Logic | Kotlin Multiplatform |
| Android UI | Jetpack Compose, Material 3 |
| iOS/macOS UI | SwiftUI |
| Local DB | SQLDelight |
| Networking | Ktor Client |
| Serialization | kotlinx.serialization |
| Async | Kotlin Coroutines + Flow / Swift async-await |
| DI | Koin Multiplatform |
| Backend / Auth | Supabase (Postgres, Auth, Realtime, Storage, Edge Functions) |
| Crash + Errors | Sentry (iOS + Android, separate projects) |
| Analytics | PostHog |
| Push (Android) | Firebase Cloud Messaging via Edge Function (HTTP v1) |
| Push (iOS) | APNs `.p8` via Edge Function |
| Subscriptions | RevenueCat (Apple App Store + Google Play) |
| iOS Release | fastlane |
| E2E | Maestro |

### Vendor Environments

| Vendor | Project layout | Plan | Environment split mechanism |
|---|---|---|---|
| Supabase | 1 project (`Skeinly`) + local CLI for dev | Free | `supabase start` (Docker) for dev, hosted for prod |
| Firebase | 2 projects: `Skeinly` (prod) + `Skeinly-Dev` (dev) | Blaze (prod) / Spark (dev) | Project-level isolation; GitHub Environment secrets |
| Sentry | 2 projects per platform: `skeinly-ios` + `skeinly-android` | Free | `environment` tag (development / production) per build config |
| PostHog | 1 project (`Skeinly`), iOS + Android share | Free (1-project cap) | `$os` super property + `posthog.init` skipped in DEBUG |
| RevenueCat | 1 project (`Skeinly`) with iOS + Android Apps | Free (<$2.5K MTR) | Auto sandbox/production detection by Apple/Google receipt |
| Google Play | 1 production app + Internal/Closed/Open testing tracks | $25 one-time reg fee | Tracks under one application ID |
| Apple ASC | 1 app + TestFlight (Internal + External testers) | $99/year | TestFlight as the dev/staging surface |

The full step-by-step setup procedure is in [`docs/ja/vendor-setup.md`](docs/ja/vendor-setup.md) (Japanese-primary). Secret registration steps are in [`docs/ja/release-secrets.md`](docs/ja/release-secrets.md).

### Development

#### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| macOS | 26.0+ (Tahoe) | iOS builds require macOS; Xcode 26.4+ requires macOS Tahoe 26.2+ |
| JDK | 17+ | Temurin recommended; set via `JAVA_HOME` |
| Xcode | 26.0+ | iOS 26 SDK; required for App Store Connect submissions since 2026-04-28 (see [Repo Policy](./docs/en/repo-policy.md#apple-app-store-sdk-requirements)) |
| Ruby | 3.3+ | Drives fastlane for iOS releases (`brew install ruby`) |
| Bundler | 2.x+ | `gem install bundler` if not present |
| Android SDK | API 36+ | platform-tools (`adb`) required for installs |
| xcodegen | latest | `brew install xcodegen` (regenerates `iosApp.xcodeproj`) |
| maestro | latest | `brew install maestro` (E2E flows only) |

#### Initial setup

```bash
git clone https://github.com/b150005/skeinly.git
cd skeinly
make setup            # installs fastlane gems via Bundler
```

Optional but recommended for contributors:

```bash
brew install xcodegen maestro
```

If you intend to run the app against a live Supabase backend, copy `iosApp/local.xcconfig.example` to `iosApp/local.xcconfig` and add your `SUPABASE_URL` / `SUPABASE_PUBLISHABLE_KEY` to a root-level `local.properties` file (both are git-ignored).

#### Common tasks (Makefile)

All build, test, lint, and release tasks are exposed as Makefile targets. Run `make help` for the full list with descriptions.

Most-used targets:

| Target | Action |
|---|---|
| `make shared-test` | Run shared module tests (commonTest + androidHostTest + iosSimulatorArm64Test) |
| `make android-build` | Build Android debug APK |
| `make android-install` | Install debug APK on connected device/emulator |
| `make ios-build` | Build iOS app for the simulator |
| `make ios-test` | Run iOS XCUITest (override sim via `IOS_SIM_DEST='platform=...'`) |
| `make e2e-android` | Run Android Maestro flows (requires running emulator) |
| `make e2e-ios` | Run iOS Maestro flows (requires running simulator) |
| `make lint` | ktlint check |
| `make format` | ktlint auto-fix |
| `make coverage` | Generate Kover coverage report and verify 80% threshold |
| `make i18n-verify` | Validate i18n key parity across 5 sources |
| `make ci-local` | Reproduce the CI pre-push invariant chain locally |
| `make release-ipa-local` | Build a Release IPA via fastlane (no upload) |
| `make clean` | Remove Gradle + Xcode build artifacts |

Recipes are thin wrappers over Gradle / xcodebuild / fastlane / Maestro — invoke those tools directly if preferred. The underlying command of every target is visible in [`Makefile`](./Makefile).

#### Pre-push invariants

Reproduce the CI's required checks locally before pushing:

```bash
make ci-local
```

Runs in order:
- `ktlintCheck` — formatting and style
- `compileTestKotlinIosSimulatorArm64` — iOS-target compile sanity
- `testAndroidHostTest` — JVM-target test suite
- `koverVerify` — 80% coverage threshold on the shared module
- `verifyI18nKeys` — 5-source i18n key parity (androidApp `values/` + `values-ja/` + shared `composeResources/values/` + `composeResources/values-ja/` + iOS `Localizable.xcstrings`)

#### Release pipeline (iOS)

iOS releases are driven by fastlane ([`iosApp/fastlane/Fastfile`](./iosApp/fastlane/Fastfile)).

Local verification (build a signed IPA without uploading):

```bash
make release-ipa-local
```

Production release (CI, on tag push):

```bash
git tag v1.0.0-alpha1
git push origin v1.0.0-alpha1
```

Tag push triggers [`.github/workflows/release.yml`](./.github/workflows/release.yml), which runs `bundle exec fastlane beta`. The lane:
1. Sets up a temporary CI keychain via `setup_ci`
2. Imports the `.p12` cert and `.mobileprovision` profile from GitHub Secrets
3. Runs `build_app` (= `xcodebuild archive` + `xcodebuild -exportArchive`)
4. Runs `upload_to_testflight` to push the IPA to App Store Connect

Required GitHub Secrets for the iOS release pipeline (7 of 19 total):

| Secret | Source |
|---|---|
| `APPLE_DISTRIBUTION_CERT_BASE64` | Apple Distribution `.p12` (base64-encoded) |
| `APPLE_DISTRIBUTION_CERT_PASSWORD` | `.p12` export password |
| `APPLE_PROVISIONING_PROFILE_BASE64` | App Store provisioning profile (base64-encoded) |
| `APPLE_TEAM_ID` | 10-char Team ID from Apple Developer |
| `APP_STORE_CONNECT_API_KEY_BASE64` | `.p8` private key (base64-encoded) |
| `APP_STORE_CONNECT_API_KEY_ID` | 10-char Key ID |
| `APP_STORE_CONNECT_ISSUER_ID` | UUID Issuer ID |

Code-signing strategy is **manual** (not `match`). See ADR-007 for rationale.

The full set covers 19 GitHub Secrets across 7 categories (iOS code signing + ASC API + Android signing + Android FCM client + Sentry + PostHog + Supabase) plus 4 Supabase Edge Function runtime secrets for Push and IAP server-side. For step-by-step instructions on **obtaining, verifying, and registering** every secret (`gh secret set` / `supabase secrets set` commands, verification with `openssl` / `security` / `keytool`, rotation procedures), see [docs/en/release-secrets.md](./docs/en/release-secrets.md). For the Apple-side vendor setup procedure (App ID + Capabilities + APNs key + App Store Connect IAP + Universal Links), see [docs/en/vendor-setup.md](./docs/en/vendor-setup.md).

#### Release pipeline (Android)

Android releases produce a signed APK as a GitHub Actions artifact. Upload to Google Play Console → Internal Testing is **manual** (no auto-upload via API in v1).

Required signing secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Plus `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` for live-backend builds. See [docs/en/release-secrets.md](./docs/en/release-secrets.md) for full setup and verification.

`VERSION_CODE` in [`version.properties`](./version.properties) MUST be incremented before each Play Console upload (Play rejects duplicate codes).

#### Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Daemon stopped: JVM GC thrashing` | Gradle daemon out of memory | `export GRADLE_OPTS="-Xmx6g"` then retry |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (Android) | Local debug keystore mismatch with prior install | `adb uninstall io.github.b150005.skeinly` then re-install |
| `No provisioning profile found` (fastlane local) | Cert/profile not installed in macOS keychain | Install via Xcode → Settings → Accounts, or run on CI |
| Maestro tap doesn't fire on iOS sim | Known SwiftUI `Button` bug on iOS 26 | Affected flows are tagged `skip-ios26` and excluded from `make e2e-ios` |
| Coverage below 80% | New code without tests | `make coverage` produces a per-class report under `shared/build/reports/kover/` |
| `xcodegen: command not found` | Missing iOS toolchain | `brew install xcodegen` |

### Documentation

| Document | Description |
|----------|-------------|
| [Repo Policy](./docs/en/repo-policy.md) | Branch protection, ruleset, security posture, Apple SDK requirements |
| [Vendor Setup](./docs/en/vendor-setup.md) | Apple Developer / App Store Connect / Universal Links Phase A0 procedure |
| [Release Secrets Setup](./docs/en/release-secrets.md) | Step-by-step guide for 19 GitHub Secrets + 4 Supabase Edge Function secrets |
| [i18n Convention](./docs/en/i18n-convention.md) | Key-naming rules across the 5 i18n sources |
| [ADR Index](./docs/en/adr/) | Architecture Decision Records |
| [Phase 39 Beta Rubric](./docs/en/phase/phase-39-beta-rubric.md) | Tester acceptance criteria |

### License

[MIT](LICENSE)

---

<a id="japanese"></a>

## 日本語

### 機能

- **編み図管理** — 編み図やパターンの保存、整理、閲覧
- **進捗記録** — 段ごと・セクションごとの進捗トラッキング
- **共有** — 編み図や進捗のスナップショットを他の編み物愛好者と共有

### 対応プラットフォーム

| プラットフォーム | UI フレームワーク | 状態 |
|----------------|-----------------|------|
| Android | Jetpack Compose + Material 3 | 開発中 |
| iOS | SwiftUI | 開発中 |
| macOS | SwiftUI | 将来目標 |

### アーキテクチャ

- **Kotlin Multiplatform (KMP)** 共有モジュールでビジネスロジック、ドメインモデル、データアクセスを共有
- **プラットフォームネイティブ UI**: Jetpack Compose (Android)、SwiftUI (iOS/macOS)
- **ローカルファースト** のデータ保存 (SQLDelight) + Supabase によるクラウド同期
- **クリーンアーキテクチャ**: UI → ViewModel → UseCase → Repository → DataSource

### 技術スタック

| 層 | 技術 |
|-------|-----------|
| 共有ロジック | Kotlin Multiplatform |
| Android UI | Jetpack Compose、Material 3 |
| iOS/macOS UI | SwiftUI |
| ローカル DB | SQLDelight |
| ネットワーク | Ktor Client |
| シリアライズ | kotlinx.serialization |
| 非同期 | Kotlin Coroutines + Flow / Swift async-await |
| DI | Koin Multiplatform |
| バックエンド / 認証 | Supabase（Postgres、Auth、Realtime、Storage、Edge Functions） |
| クラッシュ + エラー | Sentry（iOS + Android、別プロジェクト） |
| 分析 | PostHog |
| Push（Android） | Firebase Cloud Messaging（Edge Function 経由 HTTP v1） |
| Push（iOS） | APNs `.p8`（Edge Function 経由） |
| サブスクリプション | RevenueCat（Apple App Store + Google Play） |
| iOS リリース | fastlane |
| E2E | Maestro |

### ベンダー環境構成

| ベンダー | プロジェクト構成 | プラン | 環境分離方式 |
|---|---|---|---|
| Supabase | 1 プロジェクト（`Skeinly`）+ ローカル CLI | 無料 | dev は `supabase start`（Docker）、prod はホスト |
| Firebase | 2 プロジェクト: `Skeinly`（prod）+ `Skeinly-Dev`（dev） | Blaze（prod）/ Spark（dev） | プロジェクト単位で完全分離、GitHub Environment secrets |
| Sentry | プラットフォーム別 2 プロジェクト: `skeinly-ios` + `skeinly-android` | 無料 | `environment` タグ（development / production）をビルドコンフィグで切替 |
| PostHog | 1 プロジェクト（`Skeinly`）、iOS + Android 共有 | 無料（1 プロジェクト上限） | `$os` super property + DEBUG ビルドで `posthog.init` スキップ |
| RevenueCat | 1 プロジェクト（`Skeinly`）に iOS + Android アプリ | 無料（MTR < $2.5K） | Apple/Google レシートから sandbox/production を自動判定 |
| Google Play | 1 本番アプリ + Internal/Closed/Open テストトラック | $25 一括登録 | 同一 application ID 配下のトラックで分離 |
| Apple ASC | 1 アプリ + TestFlight（Internal/External テスター） | $99/年 | TestFlight が dev/staging サーフェス |

詳細な手順は [`docs/ja/vendor-setup.md`](docs/ja/vendor-setup.md) と [`docs/ja/release-secrets.md`](docs/ja/release-secrets.md) を参照してください。

### 開発

#### 前提条件

| ツール | バージョン | 備考 |
|---|---|---|
| macOS | 26.0 以上 (Tahoe) | iOS ビルドには macOS が必要。Xcode 26.4+ は macOS Tahoe 26.2+ を要求 |
| JDK | 17 以上 | Temurin 推奨。`JAVA_HOME` を設定 |
| Xcode | 26.0 以上 | iOS 26 SDK。2026-04-28 以降 App Store Connect への申請に必須（[Repo Policy](./docs/ja/repo-policy.md#apple-app-store-sdk-要件)参照） |
| Ruby | 3.3 以上 | iOS リリースの fastlane 駆動用（`brew install ruby`） |
| Bundler | 2.x 以上 | 未インストールなら `gem install bundler` |
| Android SDK | API 36 以上 | platform-tools (`adb`) がインストール時に必要 |
| xcodegen | 最新 | `brew install xcodegen`（`iosApp.xcodeproj` を再生成） |
| maestro | 最新 | `brew install maestro`（E2E フロー専用） |

#### 初回セットアップ

```bash
git clone https://github.com/b150005/skeinly.git
cd skeinly
make setup            # Bundler 経由で fastlane gem をインストール
```

コントリビューター向けの推奨追加インストール:

```bash
brew install xcodegen maestro
```

ライブの Supabase バックエンドに接続して動作確認する場合は、`iosApp/local.xcconfig.example` を `iosApp/local.xcconfig` にコピーし、ルートの `local.properties` ファイルに `SUPABASE_URL` / `SUPABASE_PUBLISHABLE_KEY` を追加してください（両方とも git 管理外）。

#### 主なタスク (Makefile)

すべてのビルド、テスト、Lint、リリース作業は Makefile ターゲットとして公開されています。`make help` で全ターゲットを説明付きで一覧表示できます。

よく使うターゲット:

| ターゲット | 動作 |
|---|---|
| `make shared-test` | shared モジュールのテスト実行（commonTest + androidHostTest + iosSimulatorArm64Test） |
| `make android-build` | Android デバッグ APK をビルド |
| `make android-install` | デバッグ APK を接続中の端末/エミュレータにインストール |
| `make ios-build` | iOS アプリをシミュレータ向けにビルド |
| `make ios-test` | iOS XCUITest を実行（シミュレータ指定は `IOS_SIM_DEST='platform=...'`） |
| `make e2e-android` | Android Maestro フロー実行（エミュレータ起動済みであること） |
| `make e2e-ios` | iOS Maestro フロー実行（シミュレータ起動済みであること） |
| `make lint` | ktlint チェック |
| `make format` | ktlint 自動修正 |
| `make coverage` | Kover カバレッジレポート生成 + 80% 閾値検証 |
| `make i18n-verify` | 5 ソースの i18n キー parity 検証 |
| `make ci-local` | CI の pre-push 不変条件をローカルで再現 |
| `make release-ipa-local` | fastlane で Release IPA をローカルビルド（アップロードなし） |
| `make clean` | Gradle と Xcode のビルド成果物を削除 |

各レシピは Gradle / xcodebuild / fastlane / Maestro の薄いラッパーです。実コマンドを直接叩きたい場合は [`Makefile`](./Makefile) を参照してください。

#### Pre-push 不変条件

push 前に CI の必須チェックをローカルで再現:

```bash
make ci-local
```

順番に実行:
- `ktlintCheck` — フォーマットとスタイル
- `compileTestKotlinIosSimulatorArm64` — iOS ターゲットのコンパイル健全性
- `testAndroidHostTest` — JVM ターゲットのテストスイート
- `koverVerify` — shared モジュールの 80% カバレッジ閾値
- `verifyI18nKeys` — 5 ソース（androidApp `values/` + `values-ja/` + shared `composeResources/values/` + `composeResources/values-ja/` + iOS `Localizable.xcstrings`）の i18n キー parity

#### リリースパイプライン (iOS)

iOS リリースは fastlane ([`iosApp/fastlane/Fastfile`](./iosApp/fastlane/Fastfile)) で駆動します。

ローカル検証（署名済み IPA をアップロードなしでビルド）:

```bash
make release-ipa-local
```

本番リリース（CI、タグ push トリガー）:

```bash
git tag v1.0.0-alpha1
git push origin v1.0.0-alpha1
```

タグ push で [`.github/workflows/release.yml`](./.github/workflows/release.yml) がトリガーされ、`bundle exec fastlane beta` が走ります。lane の処理:
1. `setup_ci` で一時 CI keychain を作成
2. GitHub Secrets から `.p12` 証明書と `.mobileprovision` プロファイルをインポート
3. `build_app`（= `xcodebuild archive` + `xcodebuild -exportArchive`）を実行
4. `upload_to_testflight` で IPA を App Store Connect にアップロード

iOS リリースパイプライン用 GitHub Secrets（19 個中の 7 個）:

| シークレット | 取得元 |
|---|---|
| `APPLE_DISTRIBUTION_CERT_BASE64` | Apple Distribution `.p12`（base64 エンコード） |
| `APPLE_DISTRIBUTION_CERT_PASSWORD` | `.p12` エクスポート時のパスワード |
| `APPLE_PROVISIONING_PROFILE_BASE64` | App Store プロビジョニングプロファイル（base64 エンコード） |
| `APPLE_TEAM_ID` | Apple Developer の 10 文字 Team ID |
| `APP_STORE_CONNECT_API_KEY_BASE64` | `.p8` 秘密鍵（base64 エンコード） |
| `APP_STORE_CONNECT_API_KEY_ID` | 10 文字の Key ID |
| `APP_STORE_CONNECT_ISSUER_ID` | UUID Issuer ID |

コード署名方針は **manual**（`match` 不採用）。理由は ADR-007 を参照。

全体は GitHub Secrets 19 個を 7 カテゴリ（iOS コード署名 + ASC API + Android 署名 + Android FCM クライアント + Sentry + PostHog + Supabase）+ Push / IAP サーバー側用 Supabase Edge Function ランタイムシークレット 4 個でカバーします。各シークレットの**取得・検証・登録手順**（`gh secret set` / `supabase secrets set` コマンド、`openssl` / `security` / `keytool` での検証、ローテーション手順）は [docs/ja/release-secrets.md](./docs/ja/release-secrets.md) を参照。Apple 側ベンダーセットアップ手順（App ID + Capabilities + APNs key + App Store Connect IAP + Universal Links）は [docs/ja/vendor-setup.md](./docs/ja/vendor-setup.md) を参照。

#### リリースパイプライン (Android)

Android リリースは署名済み APK を GitHub Actions のアーティファクトとして出力します。Google Play Console の Internal Testing へのアップロードは **手動**（v1 では API 経由の自動化なし）。

必要な署名シークレット: `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。ライブバックエンドビルドには `SUPABASE_URL` と `SUPABASE_PUBLISHABLE_KEY` も必要。詳細なセットアップと検証手順は [docs/ja/release-secrets.md](./docs/ja/release-secrets.md) を参照。

[`version.properties`](./version.properties) の `VERSION_CODE` は Play Console アップロードのたびに必ず増加させてください（Play は重複コードを拒否します）。

#### トラブルシュート

| 症状 | 原因 | 対処 |
|---|---|---|
| `Daemon stopped: JVM GC thrashing` | Gradle daemon のメモリ不足 | `export GRADLE_OPTS="-Xmx6g"` してから再実行 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（Android） | 既存インストールとローカル debug keystore の不一致 | `adb uninstall io.github.b150005.skeinly` してから再インストール |
| `No provisioning profile found`（fastlane ローカル） | macOS keychain に証明書/プロファイルが未インストール | Xcode → Settings → Accounts でインストール、または CI で実行 |
| Maestro のタップが iOS Sim で発火しない | iOS 26 の SwiftUI `Button` 既知バグ | 該当フローは `skip-ios26` タグ付きで `make e2e-ios` から除外済み |
| カバレッジが 80% 未満 | テストなしのコード追加 | `make coverage` がクラスごとのレポートを `shared/build/reports/kover/` に出力 |
| `xcodegen: command not found` | iOS ツールチェイン未インストール | `brew install xcodegen` |

### ドキュメント

| ドキュメント | 説明 |
|-------------|------|
| [リポジトリポリシー](./docs/ja/repo-policy.md) | ブランチ保護、ルールセット、セキュリティ姿勢、Apple SDK 要件 |
| [ベンダーセットアップ](./docs/ja/vendor-setup.md) | Apple Developer / App Store Connect / Universal Links Phase A0 手順 |
| [リリースシークレットセットアップ](./docs/ja/release-secrets.md) | 19 GitHub Secrets + 4 Supabase Edge Function secrets の段階的取得・検証・登録手順 |
| [i18n 規約](./docs/ja/i18n-convention.md) | 5 つの i18n ソース間のキー命名規則 |
| [ADR 索引](./docs/ja/adr/) | アーキテクチャ決定レコード |

### ライセンス

[MIT](LICENSE)
