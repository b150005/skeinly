# ADR-015: Phase 39 ベータテスター画面別バグ報告（F2）

> Source: [English version](../../en/adr/015-phase-39-beta-bug-reporting.md)

## ステータス

Proposed

## 概要

Phase 39（クローズドベータ）はテスター 5〜10 名を TestFlight Internal +
Play Internal Testing で受け入れる。Phase 39.0 prep（commit `23c1ee1`）で
バイリンガルの `.github/ISSUE_TEMPLATE/beta-bug.yml` テンプレートは出荷済み
だが、テスターが手動で GitHub を開いて報告する現状はフリクションが高く、
再現コンテキスト（直前の操作、画面遷移、端末メタ）が落ちやすい。
ja-JP テスターは EN テンプレートのフィールドを埋めない可能性も高い。

F2 は報告フローをアプリ内に閉じる：テスターがバグに遭遇 → ジェスチャーで
インアプリレポーターを起動 → スクリーンパス・直近 N アクション・
OS/端末メタ・ロケールが事前充填されたバグ報告のプレビューを確認 →
任意でフリーフォーム説明を編集して送信 → 同じ
`.github/ISSUE_TEMPLATE/beta-bug.yml` テンプレートに事前充填済みメタデータ
付きで着地する。

検討した主な力学：

1. **発見性 vs 視覚ノイズ**：常時表示のフローティング CTA は発見性が高いが、
   Phase 39.0.2 Sprint A で FAB + AppBar overflow に集約した直後にもう
   1 つのグローバル UI 要素を増やすことになる。ジェスチャー（shake /
   3-finger-long-press）はドキュメントでしか発見できないが視覚ノイズはゼロ。
2. **テレメトリの豊かさ vs プライバシー**：編み手はパターンや個人ノートに
   固有名（家族の名前等）を書く・進行写真をアップする・自由文で検索する。
   視覚的・テキスト的なものはセンシティブ。PostHog session replay は
   ピクセルデータを再構成するためマスキングミスで PII が漏れる。
   カスタムイベントなら送信内容をホワイトリスト化できる。
3. **送信のしやすさ vs シークレット管理**：GitHub API 直叩きは PAT を
   アプリ内に同梱する必要があり、ローテーション不能・MITM 抽出リスク。
   URL prefill (`?title=...&body=...`) は GitHub ネイティブで秘密ゼロ。
4. **法域別プライバシー姿勢**：Phase 39 は ja-JP テスターを含む。
   GDPR / 改正個人情報保護法のいずれも、識別情報を含むデータ収集には
   明示的 opt-in を要求する。PostHog SDK 初期化はサードパーティへの
   ネットワーク接続を確立するため、opt-in 必須。
5. **本番ポスチャー**：Phase 39 はベータ。v1.0 production（Phase 40）の
   利用者層・プライバシー姿勢は別物。本番バイナリにバグ報告機能を載せる
   = テスターでない利用者にデータフローが漏出する。

## 主な決定事項（詳細は EN 版を参照）

1. **報告 UX**: ジェスチャー優先（iOS=shake / Android=3-finger-long-press）
   + Settings「Send Feedback」エントリをフォールバック + ベータ初回起動の
   オンボーディングカードで発見性を担保。フローティング CTA なし。
   Android shake は編み中の誤発動リスクが高いため 3-finger-long-press。
   iOS は TestFlight 経験から shake が編み手にとっても自然。
2. **操作ログ収集**: PostHog 無料枠 + カスタムイベント方式。
   session replay は採用しない（プライバシー footgun かつ ≤10 名規模で
   過剰）。新規型は最小限：既存の `AnalyticsTracker`（SharedFlow ベース、
   Application 層でコレクト → PostHog SDK へ転送、`AnalyticsPreferences.analyticsOptIn`
   ゲート済み）と `AnalyticsPreferences`（`PreferencesModule` 登録済み）
   をそのまま再利用。`AnalyticsEvent` sealed-interface に `ScreenViewed`
   と `ClickAction` の 2 バリアントを追加し、別途 KMP shared
   `EventRingBuffer`（FIFO N=10、`Mutex`）が同じ SharedFlow を購読して
   バグ報告添付用に直近 N 件を保持。`tracker.track()` 呼び出し側で
   既に opt-in ゲートが効くので、独自 `TelemetryGate` は作らない。
   sealed-interface の網羅性で型安全、ユーザー入力文字列は型として
   渡せない。
3. **Issue 提出**: URL prefill 経由
   (`https://github.com/b150005/skeinly/issues/new?template=beta-bug.yml&title=...&body=...`)。
   Android `Intent(ACTION_VIEW)` / iOS は `URLComponents.queryItems` +
   `UIApplication.shared.open`（`addingPercentEncoding(.urlQueryAllowed)`
   は `&` をエンコードしないので、フリーフォーム説明に `&` が含まれると
   body パラメータが silently truncate される footgun。`URLComponents`
   なら自動で正しくエンコードされる）。PAT も Edge Function も使わない、
   シークレット同梱ゼロ。本文は Markdown フォーマットの単一ブロックとして
   生成し、再現コンテキストセクション（画面・OS・端末・ロケール・
   タイムスタンプ）+ 直近 10 アクションのテーブル + フリーフォーム説明を
   含む。GitHub URL の 8KB 制限内に収まるよう設計。
4. **プライバシー**: 既存の `AnalyticsPreferences`
   （`analyticsOptIn: StateFlow<Boolean>`、`multiplatform-settings` キー
   `"analytics_opt_in"`、デフォルト false）をそのまま同意状態として再利用。
   **新キーなし、新リポジトリなし、3 状態 enum なし、マイグレーションなし**。
   「同意ダイアログを表示済みか?」という問いは、オンボーディング完了
   フラグ（Phase 25 の `hasSeenOnboarding`）で構造的に答える：ベータ
   ビルドのオンボーディングに 4 ページ目として同意ページを差し込み、
   "Yes, share" / "No, thanks" のいずれかタップで
   `analyticsPreferences.setAnalyticsOptIn()` を呼んでから次ページへ進む。
   Settings → Beta → 「Diagnostic Data Sharing」トグルで後から撤回 / 再付与可。
   PostHog SDK 初期化は Application 層で
   `BuildFlags.isBeta && analyticsOptIn.value` ゲート。
   `docs/public/privacy-policy/index.html` (EN at
   `https://b150005.github.io/skeinly/privacy-policy/`) +
   `docs/public/ja/privacy-policy/index.html` (JA at
   `https://b150005.github.io/skeinly/ja/privacy-policy/`) に PostHog
   データフィールドの列挙 + 「収集しないもの」リスト + バグ報告 issue
   body に含まれる PostHog `distinct_id`（匿名 UUID、口座リンクなし）の
   開示を追記。
5. **ビルドフレーバー**: beta-only。`BuildFlags.isBeta` `expect/actual`
   で gate。Android は `version.properties` の `-beta` サフィックスから
   `BuildConfig.IS_BETA` を導出、iOS は `iosApp/project.yml` の xcconfig
   `IS_BETA` を Info.plist `IsBetaBuild` キーで露出。Swift コードは
   `Bundle.main.object(forInfoDictionaryKey: "IsBetaBuild") as? Bool == true`
   で直接読み取る（Kotlin object 経由不要、KMP/Swift interop 表面を増やさない）。
   本番 v1.0 バイナリは SDK をリンクするが初期化はせず（ジェスチャー
   検知も Settings → Beta セクションも同意ページも無効）、現行のバイナリと
   実質ビット同等。

## サブスライス計画

| Slice | スコープ | テスト目標 | マイグレーション | i18n キー |
|---|---|---|---|---|
| **39.1** | この ADR（コードなし） | 0 | 0 | 0 |
| **39.2** | `BuildFlags.isBeta` `expect/actual`（`commonMain` + `androidMain` で `BuildConfig.IS_BETA` + `iosMain` で `IsBetaBuild` Info.plist 読み出し）+ xcconfig 配線、privacy policy 更新（`docs/public/privacy-policy/index.html` EN + `docs/public/ja/privacy-policy/index.html` JA、PostHog 収集データの列挙 + 不収集リスト + 匿名 distinct_id 開示） | +3–5 | 0 | 0 |
| **39.3** | `posthog-android` ≥3.x + `PostHogIos` SwiftPM ≥3.x 依存（本 ADR のスニペットは v3 系 setup-API 形状で検証済み、`libs.versions.toml` で具体的な minor をピン留め）、`AnalyticsEvent.ScreenViewed` + `ClickAction` バリアント + コンパニオン `Screen` / `ClickActionId` enum 追加、`AnalyticsTracker.events` SharedFlow を購読する KMP `EventRingBuffer`（FIFO N=10、Mutex）、NavController listener (Android) + `.trackScreen` ViewModifier (iOS) で `ScreenViewed` 発火、〜15 ヶ所の主要アクション計装で `ClickAction` 発火、Application 層 PostHog init を `BuildFlags.isBeta && analyticsOptIn.value` でガード（既存の `SkeinlyApplication` + `iOSApp.swift` コレクタにゲートを追加）、Phase 32+ KMP ブリッジパターンに従い `KoinHelper.analyticsTracker()` + `KoinHelper.analyticsPreferences()` アクセサを iOS 用に追加（後者は 39.5 の iOS shake ゲートで消費） | +12–18 | 0 | 0 |
| **39.4** | ベータ第 4 オンボーディング同意ページ（Compose + SwiftUI）が既存 `AnalyticsPreferences.setAnalyticsOptIn` を呼ぶ、Settings「Beta」セクション（`BuildFlags.isBeta` ゲート）に「Send Feedback」エントリ + `analyticsOptIn` `StateFlow` バインディングの「Diagnostic Data Sharing」トグル、i18n 8〜10 キー、トグル off 時に `EventRingBuffer.clear()` 配線 | +6–10 | 0 | 8–10 |
| **39.5** | Android 3-finger-long-press の `MainActivity.dispatchTouchEvent` 検出（`BuildConfig.IS_BETA && analyticsOptIn` ゲート）、iOS shake ブリッジ（`ShakeDetectingController : UIHostingController` サブクラスで `motionEnded` オーバーライド、`SceneDelegate` で注入）、`BugReportPreviewScreen`（送信本文のレビュー + 編集）、`BugSubmissionLauncher` `expect/actual`（Android `Intent(ACTION_VIEW)` / iOS `URLComponents.queryItems` + `UIApplication.shared.open`）、Markdown 本文整形ヘルパー（commonMain） | +10–15 | 0 | 6–8 |

合計テスト delta: +33〜51（現行 1211 → 1244〜1262）
合計 i18n キー delta: +14〜18

各サブスライスは `CLAUDE.md` の "Completed" セクションを同じコミットで
更新する。39.2 → 39.3 → 39.4 → 39.5 は線形依存：39.2 はビルドフラグ +
プライバシーポリシー更新（既存の `AnalyticsPreferences` /
`AnalyticsTracker` がゲートインターフェイスとして既に十分なので、振る舞い
変化なし）、39.3 が PostHog SDK Application 層 init + `EventRingBuffer`
コレクタ + 新 `AnalyticsEvent` バリアントを追加、39.4 で同意 UI と Settings
を露出、39.5 でジェスチャー + 提出フローを露出する。39.5 をスリップさせると
バグ報告は不可達のまま（テスターは現状通り手動報告、退行なし）。39.4 を
スリップさせると既存のデフォルト false のままで PostHog init は発火せず、
リングバッファも空のまま（データフローなし）。39.3 をスリップさせると
ビルドフラグとプライバシーポリシー更新が出荷されるが新しい挙動はなし。
各スライスは前段を壊さず独立に出荷可能。

## 明示的に F2 MVP の対象外

- インアプリスクリーンショット撮影（KMP 実装パリティ問題 + URL 8KB
  制限超過 + GitHub multipart upload の複雑性）
- クラッシュ自動捕捉（Sentry / Firebase Crashlytics は別 Phase 40+）
- ネットワークログ捕捉（リクエストボディは PII を含む可能性が高い）
- バグ発生時の状態スナップショット（同様のプライバシー懸念）
- v1.0 production 向けインアプリフィードバック（Phase 40+）
- 音声 / 動画によるバグ記録
- インアプリ GitHub OAuth フロー（OAuth 複雑性 + トークンストレージ）
- サーバ側の Issue 集約 / ダッシュボード（PostHog ダッシュボードで分析、
  GitHub Issues で報告管理で十分）
- レポーターのレート制限（テスター 10 名規模で乱用リスクなし）
- `TelemetryKeys.*` 強制のためのカスタム Detekt ルール（コードレビュー
  + 単体テストで Phase 39.3 のニーズはカバー、計装が増えたら再検討）
- Markdown 整形プレビュー（Compose vs SwiftUI のレンダリング差分が
  懸念、GitHub 側で Markdown 解釈されるためプレビューは plain text）

## 関連 ADR

- ADR-005: アカウント削除（`delete_own_account` SECURITY DEFINER の先例
  — F2 では使わないが、Phase 40+ で server-side フィードバック集約が
  必要になった場合の参照点）
- ADR-014: Phase 38 PR ワークフロー（URL prefill vs API 直叩きの
  トレードオフを同様に解決：薄いサーバ・リッチなクライアント・
  バイナリ内シークレットゼロ）
- Phase 39.0 prep（commit `23c1ee1`）：`version.properties` 1.0.0-beta1
  + `.github/ISSUE_TEMPLATE/beta-bug.yml` + `docs/en/phase/phase-39-beta-rubric.md`
  — F2 はこれらの上に構築
- Phase 25（`multiplatform-settings`）：KMP key-value 永続化パターン、
  既存の `AnalyticsPreferences` の `analytics_opt_in` キーを再利用
- Phase 32.4（`SystemBackHandler` `expect/actual`）：プラットフォーム
  ブリッジの `expect/actual` パターンの先例、`BuildFlags.isBeta` も
  同形
- Phase 39.0.2 Sprint A: AppBar overflow 集約。F2 はこの集約を
  あえて維持するために第 3 のグローバル UI 要素を導入しない
- PostHog Android SDK: <https://github.com/PostHog/posthog-android>（BSD-3）
- PostHog iOS SDK: <https://github.com/PostHog/posthog-ios>（BSD-3）
- `docs/public/privacy-policy/`（EN at
  `https://b150005.github.io/skeinly/privacy-policy/`）+
  `docs/public/ja/privacy-policy/`（JA at
  `https://b150005.github.io/skeinly/ja/privacy-policy/`）：GitHub
  Pages ホストの既存プライバシーポリシー、F2 で両方に診断データ
  セクションを追記

詳細な意思決定プロセス、エージェントチーム議論、却下した代替案は
[英語版](../../en/adr/015-phase-39-beta-bug-reporting.md) を参照。
