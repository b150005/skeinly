# アーキテクチャ — 現状

> 英語原典: [docs/en/architecture.md](../en/architecture.md)
>
> **目的**: Skeinly が `main` で今どうなっているかをシステムレベルで俯瞰する地図。新規参加時、または長期離脱からの復帰時に最初に読む。機能レベルの現状は [spec/](../en/spec/) を、設計判断の理由は [adr/](../en/adr/) を辿る。

## 一段落で

Skeinly は KMP の編み物アプリ (Android Compose + iOS SwiftUI)、共通ビジネスロジックは `shared/` に集約。バックエンドは Supabase (Postgres + Auth + Realtime + Storage + Edge Functions)。サブスクは RevenueCat 経由、push は APNs (iOS) + FCM (Android) を Supabase Edge Function (`notify-on-write`) でファンアウトし、Database Webhook で発火。シンボルパレットは Pro 課金ゲート付きの Edge Function で動的配信できるので、アプリアップデートなしで新シンボルを追加可能。リリースはタグ駆動 (`v0.X.Y` push) で CI が TestFlight + Play Internal Testing に並列アップロード。

## モジュール構成

```
skeinly/
├── shared/                    KMP 共通モジュール — ドメインロジック / ViewModel / データアクセス
│   ├── src/commonMain/        プラットフォーム共通の Kotlin
│   │   ├── kotlin/
│   │   │   ├── data/          リポジトリ・remote/local データソース・sync manager
│   │   │   ├── domain/        モデル・リポジトリ interface・use case・service
│   │   │   ├── ui/            Compose Multiplatform 画面 + ViewModel
│   │   │   └── di/            Koin モジュール
│   │   ├── composeResources/  ローカライズ文字列 (en + ja)、drawables
│   │   └── sqldelight/        ローカル DB スキーマ (.sq)
│   ├── src/commonTest/        共通テスト (kotlin.test + Turbine)
│   ├── src/androidMain/       Android 固有 actual (Context 系)
│   ├── src/androidHostTest/   JVM 側 shared モジュールテスト
│   └── src/iosMain/           iOS 固有 actual (Foundation / UIKit interop)
├── androidApp/                Android ホスト (MainActivity / push service / Koin 初期化)
├── iosApp/                    Xcode プロジェクト — SwiftUI 画面・KMP bridge・AppDelegate
├── supabase/
│   ├── migrations/            prod に適用されたバージョン管理付き SQL マイグレーション
│   └── functions/             Deno Edge Function (4 個 deploy 済)
├── docs/
│   ├── en/                    ソース・オブ・トゥルース
│   │   ├── architecture.md    システムレベル現状 (本ドキュメントの英語版)
│   │   ├── spec/              機能レベル現状
│   │   ├── ops/               運用 runbook
│   │   ├── adr/               Architecture Decision Records (理由)
│   │   └── phase/             履歴
│   └── ja/                    翻訳
├── e2e/                       Maestro フロー (Android + iOS)
└── .claude/                   Agent 指示・Agent 定義・内部 spec
```

## アーキテクチャ層

```
┌───────────────────────────────────────────────────────────────┐
│  UI                                                            │
│    Compose Multiplatform (Android プライマリ)                  │
│    SwiftUI (iOS, KoinHelper 経由で ViewModel を共有)            │
├───────────────────────────────────────────────────────────────┤
│  ViewModel                                                     │
│    単一 state object (StateFlow), event sink, suspend          │
├───────────────────────────────────────────────────────────────┤
│  UseCase / Domain service                                      │
│    Pure business logic, Compose / SwiftUI import なし           │
├───────────────────────────────────────────────────────────────┤
│  Repository (interface: domain/, impl: data/)                  │
│    local + remote DataSource を統合、キャッシュ                  │
├───────────────────────────────────────────────────────────────┤
│  DataSource                                                    │
│    Local: SQLDelight / multiplatform-settings                   │
│    Remote: supabase-kt (Postgrest/Functions/Auth/Storage)       │
│            Ktor HTTP client (素の URL fetch 用)                 │
└───────────────────────────────────────────────────────────────┘
```

依存方向は厳密に下向き。UI → ViewModel → UseCase → Repository (interface) → DataSource。Repository interface は `domain/`、impl は `data/`。ViewModel は repository の境界を超えない。

## ベンダー surface

| ベンダー | プロジェクト構成 | 流れるもの |
|---|---|---|
| **Supabase** (1 project) | `Skeinly` prod + ローカル CLI for dev | Postgres テーブル / RLS / Realtime / Auth (email + OAuth) / Storage bucket `symbol-packs` / Edge Functions 4 個 |
| **Firebase** (2 projects) | `Skeinly` Blaze (prod) + `Skeinly-Dev` Spark (dev) | Android push FCM。environment ごとの `google-services.json` で分離 |
| **Apple Push** | Apple Developer Push capability | iOS push 用 APNs `.p8` (Edge Function 側) |
| **RevenueCat** (1 project) | `Skeinly` iOS + Android Apps | サブスク receipt。webhook → `revenuecat-webhook` Edge Function が `subscriptions` を書く |
| **Sentry** | `skeinly-ios` + `skeinly-android` (プラットフォーム別) | クラッシュ + 非致命例外。ベータ限定 |
| **PostHog** (1 project) | iOS + Android 共有 | プロダクト分析。オプトイン限定 |
| **GitHub** | `b150005/skeinly` (public repo) + `Skeinly Feedback` GitHub App | ソース・CI・リリース自動化・`submit-bug-report` Edge Function 経由で Issue 作成 |
| **Google Play** | 1 app + Internal / Closed / Open テストトラック | Android 配信。CI から Internal Testing トラックに Draft で上げる |
| **Apple ASC** | 1 app + TestFlight Internal + External | iOS 配信。fastlane 経由で CI が上げる |

## Supabase Postgres surface

ドメイン別テーブル一覧。全テーブル RLS 有効。マイグレーション本体: [supabase/migrations/](../../supabase/migrations/)。

| ドメイン | テーブル |
|---|---|
| **Auth / アカウント** | `auth.users` (Supabase 管理) + `delete_own_account()` RPC (ADR-005) |
| **編み図 / Chart** | `patterns` / `charts` / `chart_variations` (旧 `chart_branches`) / `chart_versions` (旧 `chart_revisions`) |
| **プロジェクト / 進捗** | `projects` / `project_segments` / `progress` / `chart_segments` |
| **コラボ** | `suggestions` (旧 `pull_requests`) / `suggestion_comments` (旧 `pull_request_comments`) |
| **サブスク** | `subscriptions` (RevenueCat 同期) + `upsert_subscription_from_webhook()` RPC + `is_pro(uid)` ヘルパー |
| **シンボルパック** | `symbol_packs` (catalog) / `symbol_pack_locales` / `user_symbol_pack_state` |
| **Push** | `device_tokens` |
| **共有** | `shares` (read-only snapshot) |
| **Discovery** | `patterns` 上のインデックス / view |

スキーマ履歴の全体ビューは `supabase/migrations/` (番号順)。コード内 Git 系語彙と UI 上の編み物向け語彙のマッピングは [README.md "Vocabulary mapping"](../../README.md#vocabulary-mapping-developer-reference) を参照。

## Supabase Edge Functions

4 個 deploy 済 (Deno + supabase-js + djwt + WebCrypto):

| Function | トリガー | Auth 姿勢 | 役割 |
|---|---|---|---|
| `notify-on-write` | `suggestions` / `suggestion_comments` 上の Database Webhook | カスタム Bearer secret (`SKEINLY_DATABASE_WEBHOOK_SECRET`)、`verify_jwt = false` | PR 系コラボイベントを APNs (iOS) + FCM (Android) にファンアウト。`device_tokens` から recipient を取り、APNs ES256 JWT + FCM SA→OAuth を発行して送信、無効 token は DELETE |
| `revenuecat-webhook` | RevenueCat webhook | カスタム Bearer secret (`REVENUECAT_WEBHOOK_SECRET`)、`verify_jwt = false` | 11 種の RevenueCat event タイプを `subscriptions.status` 遷移に map。`last_verified_at` ガードで out-of-order 安全 |
| `request-pack-download` | モバイルアプリから呼ぶ | ユーザー session JWT を `Authorization`、`verify_jwt = true` | private な `symbol-packs` Storage bucket に対して Pro entitlement check 後 5 分 TTL の signed URL を発行 |
| `submit-bug-report` | モバイルアプリから呼ぶ (Settings → フィードバック送信 + beta ビルドのジェスチャ) | `apikey: <publishable_key>` のみ、`verify_jwt = false` | Skeinly Feedback GitHub App として `b150005/skeinly` に Issue 作成。実認証は downstream の GitHub。Per-source rate-limit (5/h) |

機能ごとの詳細は `supabase/functions/<name>/README.md` (コード補足のクイックリファレンス) と該当の機能 spec / 運用 runbook に記載。

## ビルド / リリースパイプライン

タグ駆動。単一エントリポイント:

```bash
make release-tag-validate          # 前提チェック: branch=main / 作業 tree clean / tag 未使用
CONFIRM=yes make release-tag-publish   # v$VERSION_NAME でタグ → push
```

タグ push で `release.yml` が起動、3 ジョブ並列:
- **build-android**: Gradle で署名済 AAB → `:androidApp:publishBundle` が Play Internal Testing トラックに Draft で上げる (Draft は load-bearing — 手動 rollout するまでテスターに見えない)。APK も Actions artifact に。
- **build-ios**: fastlane `beta` lane が署名 + archive + export → `upload_to_testflight` で ASC へ。Build number = `github.run_number`。
- **create-release**: 両バイナリを添付した draft GitHub Release を作成。

Pre-push invariants: `make ci-local` が CI のチェック全てをローカルで再現 (ktlint + KMP tests + iOS xcodebuild + iOS XCUITest + Maestro Android + Maestro iOS)。30–45 分。「ローカル green ⇒ CI green」を構造的に保証。

詳細手順: [docs/en/ops/release.md](../en/ops/release.md)。secret 一覧: [docs/en/release-secrets.md](../en/release-secrets.md)。ローテーション手順: [docs/en/ops/secrets-rotation.md](../en/ops/secrets-rotation.md)。

## ドキュメント surface

| Lane | 目的 | 場所 |
|---|---|---|
| **WHAT IS** (現在形) | システム/機能が今どうなっているか | 本ファイル + [spec/](../en/spec/) |
| **WHY** (理由) | なぜそう設計したか / 検討した代替案 | [adr/](../en/adr/) |
| **WHAT TO DO** (手順) | 運用手順 | [ops/](../en/ops/) |
| **HISTORICAL** (履歴) | どう辿り着いたか | [phase/](../en/phase/) + ADR の Revision history |

実装が ADR から drift したら spec を更新する。ADR を更新するのは **decision が変わった時だけ** (実装が変わっただけなら spec / アーキテクチャ doc 更新)。ADR の `Revision history` ブロックが decision レベルの変更履歴。

## タスク別エントリポイント

| タスク | 読む順 |
|---|---|
| 編図エディタを拡張 | [spec/chart-editor.md](../../.claude/docs/spec/chart-editor.md) → 該当 ADR |
| 新しいシンボルパックを追加 | [spec/symbol-pack-delivery.md](../en/spec/symbol-pack-delivery.md) → [ops/content-publishing.md](../en/ops/content-publishing.md) |
| 新コラボ event を追加 | [spec/suggestion-flow.md](../../.claude/docs/spec/suggestion-flow.md) → ADR-014 |
| リリースを切る | [ops/release.md](../en/ops/release.md) |
| 障害対応 | [ops/incident-playbook.md](../en/ops/incident-playbook.md) |
| ベータテスター追加 | [ops/beta-testing.md](../en/ops/beta-testing.md) |
| 漏洩した secret をローテーション | [ops/secrets-rotation.md](../en/ops/secrets-rotation.md) |

## 横断的に重要な ADR

(該当トピックが出てきた時に読めば OK。コールドスタート時の必読ではない。)

- ADR-001 — Supabase を選んだ理由
- ADR-002 + ADR-004 — データモデル + RLS の哲学
- ADR-005 — アカウント削除カスケード
- ADR-007 — structured chart pivot (基礎)
- ADR-008 + ADR-009 — チャートデータモデル + シンボル意味論
- ADR-013 + ADR-014 — コラボ設計
- ADR-016 — Pro サブスク + シンボルパック配信
- ADR-017 + ADR-018 — push 設計
- ADR-020 — バグ報告プロキシ
