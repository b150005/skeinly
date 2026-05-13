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

### Vocabulary mapping (developer reference)

The collaboration model is internally a Git-shaped DAG (commits, branches, pull requests, merges), but **user-facing language is knitter-friendly** per the 2026-05-10 terminology audit ([audits/terminology-audit-2026-05-10.md](audits/terminology-audit-2026-05-10.md)). When working on these features, expect this mental-model bridge:

| Git concept | App label (EN) | App label (JA) | Internal class / Supabase table |
|---|---|---|---|
| fork (verb/noun) | Save a copy | コピーを保存 | `SaveSharedPatternToLibraryUseCase`; `Pattern.parentPatternId` (no separate table) |
| branch | Variation | アレンジ | `ChartVariation` / `chart_variations` |
| commit / revision | Version | バージョン | `ChartVersion` / `chart_versions` |
| merge | Apply changes | 変更を反映 | `ApplySuggestionUseCase`; RPC `apply_suggestion` |
| pull request | Suggestion | 提案 | `Suggestion` / `suggestions` |
| diff | Comparison | 比較 | `ChartComparison` |
| upstream | original | 元の (e.g. 元のパターン) | (concept; no class) |

The `_revision_id` / `_branch_id` / `pull_request_id` etc. column names inside the renamed tables are intentionally retained as internal artifacts — only the table names + status enum value (`'merged'` → `'applied'`) were renamed at the schema level. ADR-013 + ADR-014 amendment blocks document the boundary between user-visible vs internal vocabulary.

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
| JDK | 25 (LTS) | Temurin recommended (`brew install --cask temurin`); set via `JAVA_HOME`. Bytecode targets stay at Java 17 for Android runtime compatibility — only the build-running JDK is upgraded. |
| Xcode | 26.0+ | iOS 26 SDK; required for App Store Connect submissions since 2026-04-28 (see [Repo Policy](./docs/en/ops/repo-policy.md#apple-app-store-sdk-requirements)) |
| Ruby | 3.3+ | Drives fastlane for iOS releases (`brew install ruby`) |
| Bundler | 2.x+ | `gem install bundler` if not present |
| Android SDK | API 36+ | platform-tools (`adb`) required for installs |
| xcodegen | latest | `brew install xcodegen` (regenerates `iosApp.xcodeproj`) |
| maestro | latest | `brew install maestro` (E2E flows only) |

> CI uses GitHub-hosted `macos-26` (Tahoe) runners with the same JDK 25 toolchain. `macos-latest` is intentionally NOT used because GitHub still aliases it to `macos-15` (Sequoia) as of 2026-05 — see the workflow file headers under [`.github/workflows/`](./.github/workflows/) for the full pinning rationale.

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

##### Local config files (gitignored)

Skeinly's build system reads two layers of configuration that must NEVER be committed: build credentials (Supabase URL, Sentry DSN, signing keystore, etc.) and developer-personal identifiers (Apple Developer Team ID). Each layer has a committed `*.example` template that the developer copies to a gitignored sibling and fills in. Without these files the app builds and runs, but every backend-dependent feature falls back to "local-only mode" (sign-in, Discovery, Profile, etc. show empty/error states).

The full set of files a contributor manages locally:

| Path | Required? | Template (committed) | Purpose | Created by |
|---|---|---|---|---|
| `local.properties` | **Yes** | [`local.properties.example`](./local.properties.example) | Android + KMP shared module — Supabase URL/key, Sentry DSN, PostHog, RevenueCat, Release signing | Hand-copy from `.example` |
| `iosApp/local.xcconfig` | **Yes** for iOS builds | [`iosApp/local.xcconfig.example`](./iosApp/local.xcconfig.example) | iOS Xcode build settings — Apple Developer Team ID, Supabase URL/key, Sentry DSN, PostHog, RevenueCat | Auto-copied by Makefile prereq `.ensure-local-xcconfig` on first `make ios-build` |
| `androidApp/src/debug/google-services.json` | Optional (FCM Push only) | none — download from Firebase Console | Firebase Cloud Messaging client config for the Skeinly-Dev (Spark) project, `io.github.b150005.skeinly.dev` package | Download from [Firebase Console](https://console.firebase.google.com/) → Skeinly-Dev project |
| `androidApp/src/release/google-services.json` | Optional (FCM Push only) | none — download from Firebase Console | Firebase Cloud Messaging client config for the Skeinly (Blaze) project, `io.github.b150005.skeinly` package | Download from Firebase Console → Skeinly project |
| Android keystore (`*.jks`, configurable via `KEYSTORE_FILE` in `local.properties`) | Optional (Release builds only) | none — generate locally or share via Bitwarden | Android Release APK/AAB signing keystore | Android Studio → Build → Generate Signed Bundle / APK → new keystore, OR `keytool -genkey -v -keystore <filename>.jks ...`. Path is configurable — default `keystore.jks` at repo root; override via `KEYSTORE_FILE=<path>` in `local.properties` |

CI reproduces this matrix from GitHub Environment Secrets — see [`docs/en/ops/release-secrets.md`](./docs/en/ops/release-secrets.md) for the server-side rotation procedure. The local hierarchy mirrors the CI hierarchy 1:1 so contributors can validate end-to-end paths (TestFlight upload, Play Internal Testing release) locally before tag push.

The minimum viable setup for sign-in + browsing Discovery: fill in `SUPABASE_URL` + `SUPABASE_PUBLISHABLE_KEY` in **both** `local.properties` (Android) and `iosApp/local.xcconfig` (iOS). Everything else can stay empty for local development.

##### iOS code signing — edit `iosApp/local.xcconfig`

First time you run `make ios-build` / `make ios-test` / `make release-ipa-local`, the Makefile prereq `.ensure-local-xcconfig` auto-copies `iosApp/local.xcconfig.example` to `iosApp/local.xcconfig` (the destination is git-ignored). Open the new file and replace `YOUR_TEAM_ID_HERE` with your 10-char Apple Developer Team ID. The value flows from `local.xcconfig` through the `configFiles:` declaration in `iosApp/project.yml` into the generated `.xcodeproj`, so Xcode UI immediately shows the Team selected on Signing & Capabilities and the "Team: None" red banner is gone.

Find your Team ID at Apple Developer portal → Membership.

The Team ID is a public identifier (it appears in App Store URLs and the AASA file), but per repo hygiene it is not committed — `iosApp/local.xcconfig` stays git-ignored and CI injects via the `APPLE_TEAM_ID` GitHub Secret on tag-driven release builds. The configuration is intentionally project-local (no shell env vars) so multiple Xcode projects on the same machine never collide on Team ID selection.

##### Android — edit `local.properties`

Copy [`local.properties.example`](./local.properties.example) (committed at the repository root) to `local.properties` and fill in the Supabase backend credentials at minimum:

```bash
cp local.properties.example local.properties
# Open local.properties and fill SUPABASE_URL + SUPABASE_PUBLISHABLE_KEY.
# Other keys (SENTRY_DSN_ANDROID, POSTHOG_API_KEY, REVENUECAT_API_KEY_ANDROID,
# KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD) are optional — leave them empty
# for local-only mode unless you specifically need the SDK.
```

The Gradle build readers (`shared/build.gradle.kts` + `androidApp/build.gradle.kts`) load `local.properties` at configuration time and generate `BuildConfig` constants / Kotlin codegen objects from the values; rebuild after editing for changes to take effect (`./gradlew :androidApp:installDebug` / `make android-install`).

FCM Push Notifications additionally require `androidApp/src/{debug,release}/google-services.json` (the Firebase Console JSON config). Without those files the FCM Gradle plugin auto-disables itself and the rest of the app works in local-only mode (no Push only); see the Local config files table above.

**Value escaping**: `local.properties` follows the Java Properties format. Unlike `iosApp/local.xcconfig` (where `//` is a line comment and `https://...` must be written `https:/$()/...`), Properties values can contain `://` verbatim — paste your `SUPABASE_URL` from the dashboard with no transformation. See the header of `local.properties.example` for the full escape-rule reference.

**Custom keystore filename**: the Android signing keystore path is configurable. By default Gradle looks for `keystore.jks` at the repo root, but the lookup is overridable via `KEYSTORE_FILE=<path>` in `local.properties` (relative paths resolve against the repo root; absolute paths are used verbatim). Use whichever filename matches your local convention — `keystore.jks`, `upload-keystore.jks` (Google Play default), `skeinly-release.jks`, etc. CI uses the same `KEYSTORE_FILE` env var per `release.yml`, so the resolution chain is uniform local ↔ CI.

**Android SDK path (`sdk.dir`)**: Android Studio auto-writes the developer-specific `sdk.dir=...` entry to `local.properties` the first time you open the project — most contributors don't need to do anything. CLI-only workflows (no Android Studio) should set the `ANDROID_HOME` shell env var instead. AGP's resolution chain is `sdk.dir` (local.properties) → `ANDROID_HOME` → `ANDROID_SDK_ROOT` (deprecated) → OS defaults. The value is intentionally NOT in `local.properties.example` because it's machine-specific (absolute path differs per developer + OS).

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
| `make ci-local` | **Comprehensive pre-push verification.** Runs ktlint + KMP unit tests + coverage + i18n parity + iOS build + iOS XCUITest + Android & iOS Maestro flows. ~30-45 min, requires booted Android emulator + iOS Simulator before invocation. |
| `make release-ipa-local` | Build a Release IPA via fastlane (no upload) — local signing-chain verification |
| `make release-aab-local` | Build a Release AAB locally (no Play upload) — local signing-chain verification |
| `make release-tag-validate` | Pre-flight check for tag push (branch=main, clean tree, tag does not exist). No side effects. |
| `make release-tag-publish` | Tag the current commit (`v$(VERSION_NAME)`) and push to trigger the Release workflow. Requires `CONFIRM=yes`. |
| `make clean` | Remove Gradle + Xcode build artifacts |

Recipes are thin wrappers over Gradle / xcodebuild / fastlane / Maestro — invoke those tools directly if preferred. The underlying command of every target is visible in [`Makefile`](./Makefile).

#### Pre-push invariants

`make ci-local` is the single canonical pre-push entry point. It reproduces every check CI runs — there is no longer any daylight between "local green" and "CI green":

```bash
make ci-local
```

Runs in order (fail-fast):

| # | Step | Catches |
|---|---|---|
| 1 | `ktlintCheck` (shared + androidApp) | Formatting and style |
| 2 | `compileTestKotlinIosSimulatorArm64` | iOS-target test compile sanity (Kotlin/Native test-name restrictions, commonTest type errors) |
| 3 | `testAndroidHostTest` | JVM unit tests on the shared module |
| 4 | `koverVerify` | 80% coverage threshold on the shared module |
| 5 | `verifyI18nKeys` | 5-source i18n key parity (androidApp `values/` + `values-ja/` + shared `composeResources/values/` + `composeResources/values-ja/` + iOS `Localizable.xcstrings`) |
| 6 | `make ios-build` | iOS app `xcodebuild build` (multi-arch simulator) |
| 7 | `make ios-test` | iOS XCUITest run (`xcodebuild test`) — catches runtime test assertion failures + `Core/Bridging/` regressions |
| 8 | `make e2e-android` | Android Maestro flows (P0 + P1 + P2, excluding `requires-supabase`) |
| 9 | `make e2e-ios` | iOS Maestro flows (excluding `skip-ios26` + `requires-supabase`) |

**Time cost: ~30-45 minutes.** **Prerequisites:** a running Android emulator (or connected device) and a booted iOS Simulator before invocation — steps 7–9 will fail fast with a clear error message if either is missing. For iterative dev work that doesn't need the full chain, invoke individual targets directly (`make lint`, `make shared-test`, `make ios-build`, `make ios-test`, etc.).

#### Release pipeline

Releases are tag-driven. Pushing a tag matching `v*` triggers [`.github/workflows/release.yml`](./.github/workflows/release.yml), which builds + signs both platforms in parallel and uploads to TestFlight + Play Console Internal Testing.

##### Local verification (recommended before first tag of a release)

```bash
make release-ipa-local   # signed IPA via fastlane, no upload
make release-aab-local   # signed AAB via Gradle, no upload
```

Both verify the signing chain locally without exposing the store-side surface to a half-configured release.

##### Triggering a release

The single canonical entry point:

```bash
make release-tag-validate          # pre-flight: branch=main, clean tree, tag does not exist
CONFIRM=yes make release-tag-publish   # tags v$(VERSION_NAME) on HEAD and pushes to origin
```

The `release-tag-publish` target derives the tag name from `version.properties` `VERSION_NAME`, so the only edit per release is bumping that file. The `CONFIRM=yes` env var is a defense against accidental tag pushes (a stray Tab-completion cannot trigger a production upload).

If you prefer raw git:

```bash
# bump version.properties first, commit, then:
git tag -a v0.1.0 -m "Release v0.1.0"
git push origin v0.1.0
```

`VERSION_CODE` in [`version.properties`](./version.properties) MUST be strictly greater than the most recent successful Play Console upload (Play rejects duplicate codes). The first beta upload uses `VERSION_CODE=1`; bump it for every subsequent release. iOS build numbers are sourced from `github.run_number` of the Release workflow run, so they are automatic.

##### What the workflow does

The workflow runs three jobs in parallel on tag push:

- **`build-android`** (Linux): Builds a signed AAB via `:androidApp:bundleRelease`, then `:androidApp:publishBundle` uploads it to Play Console Internal Testing track using [`gradle-play-publisher`](https://github.com/Triple-T/gradle-play-publisher) with `releaseStatus = DRAFT`. The Draft state is **load-bearing** — testers don't see the build until you manually click "Start rollout to Internal testing" in Play Console. The Service Account's Play Console permissions are scoped to "Release to testing tracks" only, so production rollout is structurally impossible. APK is also produced as a GitHub Actions artifact.
- **`build-ios`** (macOS 26): Runs `bundle exec fastlane beta` ([`iosApp/fastlane/Fastfile`](./iosApp/fastlane/Fastfile)) which sets up an ephemeral CI keychain, imports the distribution cert + provisioning profile from secrets, runs `build_app` (= `xcodebuild archive` + `-exportArchive`), then `upload_to_testflight` pushes the IPA to App Store Connect with `skip_waiting_for_build_processing: true` (Apple processing continues asynchronously after the workflow finishes).
- **`create-release`** (Linux): Creates a draft GitHub Release with the IPA + APK attached.

Code-signing strategy is **manual** (not `match`). See ADR-007 for rationale.

##### Post-upload manual steps

- **Play Console**: Open Internal Testing, find the Draft release, fill release notes, click "Save → Review release → Start rollout".
- **TestFlight**: Wait for Apple processing (typically 5–30 min). On the first build per app, App Store Connect prompts for export-compliance disclosure (Skeinly uses standard OS-provided HTTPS-only encryption — qualifies for the exemption). Then add the build to your Internal Testing group.

##### Required secrets

For step-by-step instructions on **obtaining, verifying, and registering** every secret (`gh secret set` / `supabase secrets set` commands, verification with `openssl` / `security` / `keytool`, rotation procedures), see [docs/en/release-secrets.md](./docs/en/release-secrets.md). For the Apple-side vendor setup procedure (App ID + Capabilities + APNs key + App Store Connect IAP + Universal Links), see [docs/en/vendor-setup.md](./docs/en/vendor-setup.md).

The high-level secret split:

- **iOS code signing + ASC API**: 7 secrets in repository scope (cert, profile, team id, ASC API key id/issuer/p8)
- **Android signing + Play Publisher**: 5 secrets (4 keystore + 1 SA JSON, the SA JSON in `production` Environment scope)
- **Firebase config (Android)**: 1 secret registered into both `development` (Skeinly-Dev) and `production` (Skeinly Blaze) Environments
- **Supabase runtime + observability + RevenueCat**: 7 secrets for Supabase URL/key, Sentry DSN/token, PostHog API key, RevenueCat keys
- **Supabase Edge Function runtime**: 5 secrets managed via `supabase secrets set` (APNs `.p8`/key id, Firebase SA, RevenueCat webhook secret, Database webhook secret) — separate from GitHub Secrets

The `release-tag-publish` Makefile target gracefully degrades when secrets are missing: each platform's upload step gates on the presence of its required secrets and emits a `::warning::` rather than failing, so a half-configured release still produces an artifact-only build.

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

Documentation is organized into 4 lanes — `WHAT IS` (current shape), `WHY` (rationale), `WHAT TO DO` (operator runbooks), `HISTORICAL` (phase log). Start here based on the task:

| Task | Entry point |
|---|---|
| Cold-start orientation | [Architecture](./docs/en/architecture.md) (system) + [Specs](./docs/en/spec/) (per-feature) |
| Operating the system | [Ops Runbooks](./docs/en/ops/README.md) — content publishing, release, incidents, secret rotation, beta testing |
| Tracking decisions | [ADR Index](./docs/en/adr/) |
| Doc map overview | [docs/en/index.md](./docs/en/index.md) |

Key reference docs:

| Document | Description |
|---|---|
| [Repo Policy](./docs/en/ops/repo-policy.md) | Branch protection, ruleset, Apple SDK requirements |
| [Vendor Setup](./docs/en/vendor-setup.md) | Apple Developer / ASC / Universal Links one-time setup |
| [Release Secrets](./docs/en/release-secrets.md) | All GitHub Secrets + Edge Function secrets — registry + first-time registration |
| [i18n Convention](./docs/en/i18n-convention.md) | Key-naming rules across the 5 i18n sources |
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

### 用語対応表 (開発者向け)

コラボレーションモデルの内部実装は Git 相当の DAG (commit / branch / pull request / merge) ですが、**UI に表示される用語は編み物者にとって自然な言葉に統一**されています (2026-05-10 の用語監査による。詳細: [audits/terminology-audit-2026-05-10.md](audits/terminology-audit-2026-05-10.md))。本プロジェクトのコードを読む際は以下の対応表をブリッジとしてください:

| Git 概念 | アプリ表示 (EN) | アプリ表示 (JA) | 内部クラス / Supabase テーブル |
|---|---|---|---|
| fork (verb/noun) | Save a copy | コピーを保存 | `SaveSharedPatternToLibraryUseCase`; `Pattern.parentPatternId` (専用テーブルなし) |
| branch | Variation | アレンジ | `ChartVariation` / `chart_variations` |
| commit / revision | Version | バージョン | `ChartVersion` / `chart_versions` |
| merge | Apply changes | 変更を反映 | `ApplySuggestionUseCase`; RPC `apply_suggestion` |
| pull request | Suggestion | 提案 | `Suggestion` / `suggestions` |
| diff | Comparison | 比較 | `ChartComparison` |
| upstream | original | 元の (例: 元のパターン) | (概念のみ; 専用クラスなし) |

リネームされたテーブル内の `_revision_id` / `_branch_id` / `pull_request_id` 等のカラム名は、内部アーティファクトとして意図的に旧名を保持しています — スキーマレベルでリネームしたのはテーブル名と status enum value (`'merged'` → `'applied'`) のみ。ADR-013 + ADR-014 の amendment ブロックに「user-visible 用語と内部用語の境界」を記載しています。

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
| JDK | 25 (LTS) | Temurin 推奨（`brew install --cask temurin`）。`JAVA_HOME` を設定。Bytecode ターゲットは Android runtime 互換のため Java 17 のまま — ビルド実行用 JDK のみ最新化。 |
| Xcode | 26.0 以上 | iOS 26 SDK。2026-04-28 以降 App Store Connect への申請に必須（[Repo Policy](./docs/ja/ops/repo-policy.md#apple-app-store-sdk-要件)参照） |
| Ruby | 3.3 以上 | iOS リリースの fastlane 駆動用（`brew install ruby`） |
| Bundler | 2.x 以上 | 未インストールなら `gem install bundler` |
| Android SDK | API 36 以上 | platform-tools (`adb`) がインストール時に必要 |
| xcodegen | 最新 | `brew install xcodegen`（`iosApp.xcodeproj` を再生成） |
| maestro | 最新 | `brew install maestro`（E2E フロー専用） |

> CI も同じく `macos-26` (Tahoe) ランナー + JDK 25 toolchain で実行されます。`macos-latest` を意図的に避けているのは、2026-05 時点で GitHub のエイリアスが依然 `macos-15` (Sequoia) を指しており、本リポジトリの dev 環境（macOS 26）と乖離が生じるためです。詳細は [`.github/workflows/`](./.github/workflows/) 各ファイルのヘッダコメントを参照。

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

##### ローカル設定ファイル（git 管理外）

Skeinly のビルドは 2 層の設定を読み込みますが、いずれもコミットしてはいけません: ビルド資格情報（Supabase URL、Sentry DSN、署名 keystore など）と開発者個別の識別子（Apple Developer Team ID）。各層には `*.example` のテンプレートがコミット済で、開発者がそれを git 管理外の同名ファイルへコピーして値を埋めます。これらのファイルが無くてもアプリ自体はビルド + 起動しますが、バックエンド依存機能はすべて「local-only モード」にフォールバックします（サインイン / Discovery / Profile 等が空 or エラー状態になる）。

コントリビューターがローカルで管理するファイル一覧:

| パス | 必須？ | テンプレート（コミット済） | 用途 | 作成方法 |
|---|---|---|---|---|
| `local.properties` | **必須** | [`local.properties.example`](./local.properties.example) | Android + KMP shared モジュール — Supabase URL/Key、Sentry DSN、PostHog、RevenueCat、Release 署名 | `.example` から手動コピー |
| `iosApp/local.xcconfig` | **iOS ビルド時必須** | [`iosApp/local.xcconfig.example`](./iosApp/local.xcconfig.example) | iOS Xcode ビルド設定 — Apple Developer Team ID、Supabase URL/Key、Sentry DSN、PostHog、RevenueCat | 初回 `make ios-build` 時に Makefile prereq `.ensure-local-xcconfig` が自動コピー |
| `androidApp/src/debug/google-services.json` | 任意（FCM Push 受信時のみ） | テンプレートなし — Firebase Console からダウンロード | Skeinly-Dev (Spark) プロジェクトの FCM クライアント設定、`io.github.b150005.skeinly.dev` パッケージ用 | [Firebase Console](https://console.firebase.google.com/) → Skeinly-Dev からダウンロード |
| `androidApp/src/release/google-services.json` | 任意（FCM Push 受信時のみ） | テンプレートなし — Firebase Console からダウンロード | Skeinly (Blaze) プロジェクトの FCM クライアント設定、`io.github.b150005.skeinly` パッケージ用 | Firebase Console → Skeinly からダウンロード |
| Android keystore (`*.jks`、`local.properties` の `KEYSTORE_FILE` で設定可能) | 任意（Release ビルド時のみ） | テンプレートなし — ローカル生成 or Bitwarden 経由共有 | Android Release APK/AAB 署名 keystore | Android Studio → Build → Generate Signed Bundle / APK → 新規 keystore 作成、もしくは `keytool -genkey -v -keystore <filename>.jks ...`。ファイル名は設定可能 — デフォルトは repo ルート `keystore.jks`、`local.properties` の `KEYSTORE_FILE=<path>` で上書き可 |

CI は GitHub Environment Secrets から同マトリクスを再現しています — サーバー側のローテーション手順は [`docs/en/ops/release-secrets.md`](./docs/en/ops/release-secrets.md) を参照。ローカルの階層は CI 階層と 1:1 で対応するため、コントリビューターはタグ push 前に TestFlight アップロードや Play Internal Testing リリースを end-to-end でローカル検証できます。

サインイン + Discovery 閲覧の最小構成は `local.properties` (Android) と `iosApp/local.xcconfig` (iOS) **両方** で `SUPABASE_URL` + `SUPABASE_PUBLISHABLE_KEY` を埋めるだけ。その他は空のまま local-only モードで開発可能です。

##### iOS コード署名 — `iosApp/local.xcconfig` を編集

初回 `make ios-build` / `make ios-test` / `make release-ipa-local` 実行時に、Makefile prereq `.ensure-local-xcconfig` が `iosApp/local.xcconfig.example` を `iosApp/local.xcconfig` に自動コピーします（コピー先は git-ignore 済）。生成されたファイルを開いて `YOUR_TEAM_ID_HERE` を 10 文字の Apple Developer Team ID に書き換えてください。値は `local.xcconfig` → `iosApp/project.yml` の `configFiles:` 宣言 → 生成された `.xcodeproj` の順に流れるので、Xcode UI の Signing & Capabilities で Team が即座に選択された状態になり "Team: None" の赤バナーは消えます。

Team ID は Apple Developer portal → Membership で確認できます。

Team ID 自体は public identifier（App Store URL や AASA ファイルに公開される）ですが、リポジトリ hygiene のためコミット対象外とし、`iosApp/local.xcconfig` は git-ignore、CI はタグ駆動の Release ビルドで `APPLE_TEAM_ID` GitHub Secret から注入する方針です。Team ID 設定はあえてプロジェクト内ローカルに閉じており（shell 環境変数は使わない）、同一マシン上で複数の Xcode プロジェクトが Team ID 選択で衝突することを避けています。

##### Android — `local.properties` を編集

リポジトリルートにコミット済の [`local.properties.example`](./local.properties.example) を `local.properties` にコピーし、最低限 Supabase バックエンドの認証情報を埋めてください:

```bash
cp local.properties.example local.properties
# local.properties を開いて SUPABASE_URL + SUPABASE_PUBLISHABLE_KEY を埋める。
# 他のキー (SENTRY_DSN_ANDROID, POSTHOG_API_KEY, REVENUECAT_API_KEY_ANDROID,
# KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD) は任意 — 該当 SDK が必要でない
# 限り空のままで local-only モードとして動作します。
```

Gradle 読み込み側 (`shared/build.gradle.kts` + `androidApp/build.gradle.kts`) は構成フェーズで `local.properties` をロードし、値から `BuildConfig` 定数 / Kotlin codegen オブジェクトを生成するため、編集後は再ビルドが必要です（`./gradlew :androidApp:installDebug` または `make android-install`）。

FCM Push Notification を受信する場合は別途 `androidApp/src/{debug,release}/google-services.json` (Firebase Console の JSON 設定) が必要です。当該ファイルが無ければ FCM Gradle plugin は自動的に無効化され、Push 以外の機能は local-only モードで通常通り動作します。詳細は上記「ローカル設定ファイル」表参照。

**値の escape ルール**: `local.properties` は Java Properties フォーマット準拠。`iosApp/local.xcconfig` (`//` が行コメント、`https://...` を `https:/$()/...` と書く必要あり) とは異なり、Properties の value 内 `://` はそのまま記述可 — ダッシュボードから取得した `SUPABASE_URL` をそのまま貼り付けて OK。フル escape ルールは `local.properties.example` のヘッダコメント参照。

**Keystore ファイル名のカスタマイズ**: Android 署名 keystore のパスは設定可能。デフォルトでは Gradle がリポジトリルートの `keystore.jks` を探しますが、`local.properties` に `KEYSTORE_FILE=<path>` を追加すれば上書きできます (相対パスはリポジトリルート基準、絶対パスはそのまま使用)。ローカル運用の慣習に合わせて `keystore.jks` / `upload-keystore.jks` (Google Play 標準) / `skeinly-release.jks` 等の任意のファイル名で OK。CI も `release.yml` で同じ `KEYSTORE_FILE` env var を使うため、ローカル ↔ CI で解決ロジックが統一されています。

**Android SDK パス (`sdk.dir`)**: Android Studio で本プロジェクトを最初に開いた時点で、開発者ごとの `sdk.dir=...` が `local.properties` に自動追記されます — ほとんどのコントリビューターは特別な操作不要。CLI のみで運用する場合 (Android Studio を開かない) は代わりに `ANDROID_HOME` 環境変数を設定してください。AGP の解決順序は `sdk.dir` (local.properties) → `ANDROID_HOME` → `ANDROID_SDK_ROOT` (deprecated) → OS デフォルトパス。値は machine-specific な絶対パスのため `local.properties.example` には意図的に含めていません (開発者・OS ごとに異なるため)。

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
| `make ci-local` | **包括的な pre-push 検証**。ktlint + KMP unit テスト + カバレッジ + i18n parity + iOS ビルド + iOS XCUITest + Android & iOS の Maestro フローを実行。所要時間 ~30-45 分。実行前に Android エミュレータと iOS Simulator が起動済みである必要がある。 |
| `make release-ipa-local` | fastlane で Release IPA をローカルビルド（アップロードなし）— ローカル署名チェーン検証用 |
| `make release-aab-local` | Release AAB をローカルビルド（アップロードなし）— ローカル署名チェーン検証用 |
| `make release-tag-validate` | Tag push の事前チェック（branch=main、clean tree、tag 未存在）。副作用なし。 |
| `make release-tag-publish` | 現在のコミットに `v$(VERSION_NAME)` で tag を打ち、push して Release workflow をトリガー。`CONFIRM=yes` 必須。 |
| `make clean` | Gradle と Xcode のビルド成果物を削除 |

各レシピは Gradle / xcodebuild / fastlane / Maestro の薄いラッパーです。実コマンドを直接叩きたい場合は [`Makefile`](./Makefile) を参照してください。

#### Pre-push 不変条件

`make ci-local` が pre-push 検証の唯一の正規エントリーポイントです。CI が実行するすべてのチェックを再現するので、「ローカル green」と「CI green」の間に隙間は存在しません:

```bash
make ci-local
```

順番に実行 (fail-fast):

| # | ステップ | 検出対象 |
|---|---|---|
| 1 | `ktlintCheck` (shared + androidApp) | フォーマットとスタイル |
| 2 | `compileTestKotlinIosSimulatorArm64` | iOS ターゲットのテストコンパイル健全性 (Kotlin/Native のテスト名制限、commonTest の型エラー) |
| 3 | `testAndroidHostTest` | shared モジュールの JVM unit テスト |
| 4 | `koverVerify` | shared モジュールの 80% カバレッジ閾値 |
| 5 | `verifyI18nKeys` | 5 ソース (androidApp `values/` + `values-ja/` + shared `composeResources/values/` + `composeResources/values-ja/` + iOS `Localizable.xcstrings`) の i18n キー parity |
| 6 | `make ios-build` | iOS アプリの `xcodebuild build` (マルチアーキテクチャシミュレータ) |
| 7 | `make ios-test` | iOS XCUITest 実行 (`xcodebuild test`) — テストアサーション失敗 + `Core/Bridging/` 回帰の検出 |
| 8 | `make e2e-android` | Android Maestro フロー (P0 + P1 + P2、`requires-supabase` 除外) |
| 9 | `make e2e-ios` | iOS Maestro フロー (`skip-ios26` + `requires-supabase` 除外) |

**所要時間: ~30-45 分。** **前提条件:** 実行前に Android エミュレータ (または接続端末) と iOS Simulator が起動済みであること — 手順 7〜9 は端末がない場合に明確なエラーメッセージで即座に失敗します。フル chain が不要な反復開発では、個別ターゲット (`make lint`, `make shared-test`, `make ios-build`, `make ios-test` 等) を直接呼び出してください。

#### リリースパイプライン

リリースは tag 駆動です。`v*` パターンの tag を push すると [`.github/workflows/release.yml`](./.github/workflows/release.yml) がトリガーされ、両プラットフォームを並列でビルド・署名し、TestFlight + Play Console Internal Testing にアップロードします。

##### ローカル検証（リリース前の初回には推奨）

```bash
make release-ipa-local   # fastlane で署名済み IPA、アップロードなし
make release-aab-local   # Gradle で署名済み AAB、アップロードなし
```

両方ともローカルで署名チェーンを検証し、ストア側を half-configured な状態にさらしません。

##### リリースのトリガー

唯一の正規エントリーポイント:

```bash
make release-tag-validate          # 事前チェック: branch=main, 未コミット無し, tag 未存在
CONFIRM=yes make release-tag-publish   # HEAD に v$(VERSION_NAME) で tag を打ち origin に push
```

`release-tag-publish` ターゲットは `version.properties` の `VERSION_NAME` から tag 名を自動導出するため、リリース毎の編集は version 更新の 1 箇所のみ。`CONFIRM=yes` は誤 tag push に対する防御で、Tab completion の事故では本番アップロードが起動しません。

raw git で行いたい場合:

```bash
# version.properties を bump して commit してから:
git tag -a v0.1.0 -m "Release v0.1.0"
git push origin v0.1.0
```

[`version.properties`](./version.properties) の `VERSION_CODE` は **直近の Play Console アップロード成功値より厳密に大きい** 必要があります（Play は重複 code を拒否）。初回 beta は `VERSION_CODE=1`、以降のリリース毎に増分。iOS build number は Release workflow run の `github.run_number` から自動採番されます。

##### Workflow が行う処理

Tag push で 3 ジョブが並列実行されます:

- **`build-android`** (Linux): `:androidApp:bundleRelease` で署名済み AAB をビルド、`:androidApp:publishBundle` が [`gradle-play-publisher`](https://github.com/Triple-T/gradle-play-publisher) を使って Play Console Internal Testing track に `releaseStatus = DRAFT` でアップロード。Draft 状態は **load-bearing**: ユーザーが Play Console で「テスター用ロールアウトを開始」を手動クリックするまでテスターには見えません。Service Account の Play Console 権限は「テスト版トラックへのリリース」のみに絞られており、Production への昇格は構造的に不可能。APK も GitHub Actions アーティファクトとして生成。
- **`build-ios`** (macOS 26): `bundle exec fastlane beta` ([`iosApp/fastlane/Fastfile`](./iosApp/fastlane/Fastfile)) を実行。一時 CI keychain を作成、distribution cert + provisioning profile を secrets からインポート、`build_app`（= `xcodebuild archive` + `-exportArchive`）→ `upload_to_testflight` で IPA を App Store Connect に push（`skip_waiting_for_build_processing: true` のため Apple processing は workflow 完了後も非同期で継続）。
- **`create-release`** (Linux): IPA + APK 添付の Draft GitHub Release を作成。

コード署名方針は **manual**（`match` 不採用）。理由は ADR-007 を参照。

##### アップロード後の手動操作

- **Play Console**: 内部テスト → Draft release を開く → リリースノート記入 → 「保存 → リリースをレビュー → ロールアウトを開始」をクリック。
- **TestFlight**: Apple の処理待ち（通常 5〜30 分）。アプリ初回ビルドのみ App Store Connect が export compliance（暗号化輸出規制）の質問を表示 — Skeinly は標準 OS 提供の HTTPS-only 暗号化のみ使用 → 例外条件に該当。回答後、ビルドを Internal Testing グループに追加。

##### 必要なシークレット

各シークレットの**取得・検証・登録手順**（`gh secret set` / `supabase secrets set` コマンド、`openssl` / `security` / `keytool` での検証、ローテーション手順）は [docs/ja/release-secrets.md](./docs/ja/release-secrets.md) を参照。Apple 側ベンダーセットアップ手順（App ID + Capabilities + APNs key + App Store Connect IAP + Universal Links）は [docs/ja/vendor-setup.md](./docs/ja/vendor-setup.md) を参照。

シークレット種別の概観:

- **iOS コード署名 + ASC API**: リポジトリスコープの 7 個（cert, profile, team id, ASC API key id/issuer/p8）
- **Android 署名 + Play Publisher**: 5 個（keystore 4 個 + SA JSON 1 個。SA JSON は `production` Environment スコープ）
- **Firebase 設定 (Android)**: 1 個を `development`（Skeinly-Dev）と `production`（Skeinly Blaze）両方の Environment に登録
- **Supabase ランタイム + 観測 + RevenueCat**: 7 個（Supabase URL/key, Sentry DSN/token, PostHog API key, RevenueCat keys）
- **Supabase Edge Function ランタイム**: `supabase secrets set` で管理する 5 個（APNs `.p8`/key id, Firebase SA, RevenueCat webhook secret, Database webhook secret）— GitHub Secrets とは別管理

`release-tag-publish` Makefile ターゲットはシークレット欠如時に graceful degrade します: 各プラットフォームのアップロード step が必要 secrets の存在をゲートし、欠けていれば `::warning::` を出してアーティファクトのみのビルドに収束します。

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

ドキュメントは 4 lane で構成 — `WHAT IS` (現状) / `WHY` (理由) / `WHAT TO DO` (運用 runbook) / `HISTORICAL` (フェーズログ)。タスク別エントリポイント:

| タスク | エントリポイント |
|---|---|
| コールドスタート | [アーキテクチャ](./docs/ja/architecture.md) (システム) + [Spec](./docs/ja/spec/) (機能別) |
| 運用 | [Ops Runbook](./docs/ja/ops/README.md) — コンテンツ公開、リリース、障害対応、シークレットローテーション、ベータテスト |
| 設計判断 | [ADR 索引](./docs/ja/adr/) |
| Doc 全体マップ | [docs/ja/index.md](./docs/ja/index.md) |

主要リファレンス:

| ドキュメント | 説明 |
|---|---|
| [リポジトリポリシー](./docs/ja/ops/repo-policy.md) | ブランチ保護、ルールセット、Apple SDK 要件 |
| [ベンダーセットアップ](./docs/ja/vendor-setup.md) | Apple Developer / App Store Connect / Universal Links 一発設定 |
| [リリースシークレット](./docs/ja/release-secrets.md) | 全 GitHub Secrets + Edge Function secret のレジストリ + 初回登録 |
| [i18n 規約](./docs/ja/i18n-convention.md) | 5 つの i18n ソース間のキー命名規則 |

### ライセンス

[MIT](LICENSE)
