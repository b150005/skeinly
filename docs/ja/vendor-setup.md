# ベンダーアカウント設定 — Phase A0 (alpha 公開前)

> 英語原典: [docs/en/vendor-setup.md](../en/vendor-setup.md)

最初の alpha (Phase 39) タグ push 前のベンダーポータル 1 回きりセットアップの single source of truth。Apple Developer Portal + App Store Connect + Google Play Console + Universal Links + RevenueCat をカバー。secret ごとの OBTAIN/REGISTER 手順は [release-secrets.md](release-secrets.md) を参照。繰り返し運用タスク (リリース、ベータテスター招待、障害対応) は [ops/](ops/) を参照。

ベンダーポータルでクリックしながら使う **チェックリスト** として使うこと。各決定の "なぜ" は [ADR-016](adr/016-phase-41-revenuecat-subscription.md) / [ADR-017](adr/017-phase-24-push-notifications.md) / [pre-alpha-checklist.md](ops/pre-alpha-checklist.md) にあり、ここでは link out のみ。

## Skeinly 定数

すべてのベンダーフォームで再利用される値。

| フィールド | 値 |
|---|---|
| Bundle ID (iOS) | `io.github.b150005.skeinly` |
| Application ID (Android) | `io.github.b150005.skeinly` |
| Subscription Product ID (iOS — monthly) | `io.github.b150005.skeinly.pro.monthly` |
| Subscription Product ID (iOS — yearly) | `io.github.b150005.skeinly.pro.yearly` |
| Subscription Product ID (Android) | `io.github.b150005.skeinly.pro` (base plan `monthly` + `yearly`) |
| 無料トライアル期間 | 7 日間 |
| 価格 | $3.99 USD / 月 + $24.99 USD / 年 |
| RevenueCat entitlement | `entlaaca26b181` (Skeinly Pro) |
| RevenueCat packages | `$rc_monthly` + `$rc_annual` |
| アプリ名 | `Skeinly` |
| デフォルト言語 | English (U.S.) |
| Apple Developer Team ID | (10 文字; enrollment 後に確定) |
| プライバシー ポリシー URL | `https://b150005.github.io/skeinly/privacy-policy/` |
| アカウント削除 URL | `https://b150005.github.io/skeinly/account-deletion/` |
| サポートメール | `skeinly.app@gmail.com` |
| サポート URL | `https://github.com/b150005/skeinly/issues` |

## 前提条件

- **Apple Developer Program 登録** — $99/年、<https://developer.apple.com/programs/>
- **Google Play Console publisher account** — $25 一回限り、<https://play.google.com/console/signup>
- **Xcode 26+ の Mac** — Keychain Access + `.p12` export 用。[README Prerequisites](../../README.md#prerequisites) 参照
- **`gh` CLI 認証済** — `gh auth login`
- **RevenueCat account** — 無料、<https://app.revenuecat.com> ($2.5K MRR 超で paid Mid-Market)

## Phase 概要

順番に実行。`A0a` / `A0b` / `A0c` / `A0d` 内の step も順序依存 — 前 step の成果物に基づいて構築。

| Phase | 内容 | 依存先 |
|---|---|---|
| **A0a** | Apple Developer Portal: App ID / APNs key / 証明書 / Profile / ASC API key | Apple 登録 |
| **A0b** | App Store Connect: アプリ作成 / IAP / 無料トライアル / ASSN V2 / sandbox tester / App Privacy | A0a |
| **A0c** | Google Play Console — IAP: subscription product / base plan / 無料トライアル offer / license tester / Pub/Sub + RTDN | Play Console 登録 + アプリアップロード |
| **A0d** | Google Play Console — アプリのコンテンツ + ストア掲載情報 + Internal Testing | A0c |
| **A0e** | Universal Links / App Links (AASA + assetlinks.json) | A0a + A0c |
| **A0f** | RevenueCat: プロジェクト / アプリ連携 / product import / entitlement + offering binding / webhook | A0b + A0c |

---

## Phase A0a — Apple Developer Portal

### A0a-1: App ID 作成

- [ ] <https://developer.apple.com/account> → **Certificates, Identifiers & Profiles** → **Identifiers** → **+** → **App IDs** → **App**
- [ ] Description: `Skeinly`、Bundle ID: **Explicit** → `io.github.b150005.skeinly`
- [ ] Capability 4 つを有効化: **Sign In with Apple** (Configure → Enable as a primary App ID)、**Push Notifications**、**Associated Domains**、**In-App Purchase**
- [ ] Continue → Register

Capability の後付けは Provisioning Profile 再生成を強制。alpha で本当に必要なものだけ enable。

### A0a-2: APNs Auth Key (`.p8`) 生成

- [ ] **Keys** → **+** → Name `skeinly APNs` → **Apple Push Notifications service (APNs)** を有効化 → Continue → Register
- [ ] **`.p8` ファイルを即ダウンロード** (1 回限り; ファイル名 `AuthKey_<KEY_ID>.p8`)
- [ ] 10 文字の **Key ID** をメモ
- [ ] 両方をパスワードマネージャに保存
- [ ] base64 encode + `APPLE_APNS_KEY_BASE64` を Edge Function secret として登録 ([release-secrets.md](release-secrets.md#supabase-edge-function-secrets) 参照)
- [ ] `APPLE_APNS_KEY_ID` Edge Function secret 登録 (10 文字)

同じ key が production + TestFlight 両方で動作。

### A0a-3: Apple Distribution Certificate

[release-secrets.md §1](release-secrets.md#1-apple_distribution_cert_base64) 参照。既存の証明書が 4 capability をカバー済。

### A0a-4: Provisioning Profile

CI が ASC API key (A0a-5) を用いて `sigh` 経由で runtime fetch。GitHub Secret に profile バイトは保存しない。

**順序が重要**: A0a-1 の **後** に profile を再生成して 4 capability すべてを焼き込む。capability 追加前の profile は不完全。

### A0a-5: App Store Connect API Key

[release-secrets.md §4–6](release-secrets.md#4-app_store_connect_api_key_base64) 参照。`APP_STORE_CONNECT_API_KEY_BASE64` を GitHub Secret として登録。fastlane の TestFlight upload にも同じ key を使う。

---

## Phase A0b — App Store Connect (App + IAP + Sandbox)

### A0b-1: アプリ作成

- [ ] <https://appstoreconnect.apple.com> → **My Apps** → **+** → **New App**
- [ ] Platform: iOS、Name `Skeinly`、Primary Language **English (U.S.)**、Bundle ID `io.github.b150005.skeinly`、SKU `skeinly-001`、User Access **Full Access** → Create

### A0b-2: Subscription Group

- [ ] App → **Monetization → Subscriptions** → **+** → Reference Name `Skeinly Pro` → Create
- [ ] **App Store Localizations**: EN (U.S.) + Japanese (Japan) を追加、両方 Subscription Group Display Name = `Skeinly Pro`

### A0b-3: 月額プロダクト作成

- [ ] `Skeinly Pro` group 内 → **Create**
- [ ] Reference Name `Skeinly Pro Monthly`、Product ID **`io.github.b150005.skeinly.pro.monthly`** ⚠️ 永続
- [ ] Subscription Duration **1 Month** → Save
- [ ] Subscription Prices → US **$3.99** → JP 自動換算 (¥600 帯) を確認、デフォルト accept → Confirm
- [ ] Availability: 全領域
- [ ] App Store Localizations — EN (U.S.):
  - Display Name `Skeinly Pro Monthly` (30 文字上限)
  - Description `Unlock all Pro features. Auto-renews monthly.` (locale ごとに 55 文字上限)
- [ ] App Store Localizations — Japanese:
  - Display Name `Skeinly Pro 月額プラン`
  - Description `Skeinly Pro の全機能を解放。毎月自動更新。`

### A0b-4: 年額プロダクト作成

- [ ] 同じ手順で: Reference `Skeinly Pro Yearly`、Product ID **`io.github.b150005.skeinly.pro.yearly`**、Duration **1 Year**、US **$24.99** (JP 自動換算 ¥3,600–¥4,000)
- [ ] EN Display `Skeinly Pro Yearly`、EN Description `Unlock all Pro features. Auto-renews yearly. Save 40%+.` (55 文字ぴったり)
- [ ] JA Display `Skeinly Pro 年額プラン`、JA Description `Skeinly Pro の全機能を解放。毎年自動更新、40% お得。`

### A0b-5: Subscription levels

- [ ] Group → **Edit Order** → **両プロダクトを Level 1** に → Save

同 level = 次回更新時に crossgrade。両プロダクトとも同じ RC entitlement を付与。

### A0b-6: 7 日間無料トライアル — 月額

- [ ] 月額プロダクト詳細 → **Subscription Prices** → **View all Subscription pricing** → **Set up Introductory Offer**
- [ ] Countries: 全領域 (availability に一致)、Start: today、**End: 空欄** (永続)、Offer Type: **Free Trial**、Duration: **1 Week** (7 日)、Price: $0 自動 → Confirm

⚠️ Introductory offer は作成後編集不可 — 削除 + 再作成のみ。

Apple は「サブスクリプショングループ内 1 ユーザー 1 回」を自動施行 — 両プロダクトで eligibility カウント共有。オペレータ操作不要。

### A0b-7: 7 日間無料トライアル — 年額

- [ ] A0b-6 と同じ手順を `Skeinly Pro Yearly` に対して実行

### A0b-8: App Store Server Notifications V2

- [ ] RevenueCat Dashboard → Apps & providers → Skeinly iOS → **Apple Server to Server notification settings** → URL コピー
- [ ] ASC → App → **General → App Information → App Store Server Notifications**:
  - Production Server URL: RC URL をペースト → Save
  - Sandbox Server URL: **同じ** URL をペースト → Save
- [ ] Notification Version picker が出れば **Version 2** を選択。出なければ V2 が自動適用される (Apple は doc 未更新のまま default-to-V2 UI 変更を出荷)
- [ ] 検証: RC Dashboard → **Send test event** → 200 OK + "Last received" timestamp 更新

### A0b-9: Sandbox tester

- [ ] ASC ホーム → **Users and Access** → **Sandbox** タブ → **+** → 最低 2 名作成 (US 1 名 + JP 1 名推奨)
- [ ] **Gmail プラスサブアドレッシング** を本物の inbox に使う (例: `skeinly.app+sandbox-us-1@gmail.com`) — Apple 自身の help page でも同パターン使用
- [ ] First Name = cohort (`Core` / `Beta`)、Last Name = `Tester-<locale>-<n>` (作成後編集不可)
- [ ] デバイス側: 設定 → [Apple ID] → **Media & Purchases** → サインアウト (iCloud top-level は **しない**)、StoreKit prompt でサンドボックス認証情報でサインイン

### A0b-10: App Privacy 宣言

- [ ] App 詳細 → **App Privacy** → Privacy Policy URL `https://b150005.github.io/skeinly/privacy-policy/`
- [ ] 「データ収集」セクションの **編集** をクリック → 14 カテゴリのチェックボックスダイアログが開く
- [ ] **以下 11 個ちょうどをチェック** (Skeinly のデータフローとの突き合わせは [pre-alpha-checklist.md §35.1 A6](ops/pre-alpha-checklist.md#a6-data-safety-form) を参照):

| Apple カテゴリ | サブオプション (チェックする) | Skeinly での該当 |
|---|---|---|
| **連絡先情報** | **名前** | Supabase profile の `display_name` — freeform、ユーザーが本名を入れる可能性あり |
| 連絡先情報 | **メールアドレス** | Supabase Auth の email (Apple の注記「ハッシュ化されたものを含む」も該当) |
| **ユーザコンテンツ** | **写真またはビデオ** | プロジェクト進捗写真 (Supabase Storage に保管) |
| ユーザコンテンツ | **カスタマーサポート** | 不具合報告内容 → `submit-bug-report` Edge Function 経由で GitHub Issue |
| ユーザコンテンツ | **その他のユーザコンテンツ** | チャートデータ、パターン、コメント、提案 |
| **ID** | **ユーザID** | Supabase UUID。Apple 定義の「スクリーン名、ハンドル、アカウントID」に `display_name` も該当 |
| ID | **デバイスID** | APNs デバイストークン + PostHog `distinct_id` (opt-in) |
| **購入** | **購入** | RevenueCat webhook 経由のサブスク状態 (`subscriptions` テーブル — Pro entitlement, product, expires_at) |
| **使用状況データ** | **製品の操作** | PostHog のページビュー / タップ / スクロール (同意画面 opt-in) |
| **診断** | **クラッシュデータ** | Sentry crash log (opt-in) |
| 診断 | **パフォーマンスデータ** | Sentry performance (opt-in; Apple 表記「起動時間、ハング率、エネルギー使用量」に一致) |

- 上記以外のサブオプションは全てチェックしない。判断が割れやすい 2 点だけ補足:
  - **財務情報 > 支払い情報** — Apple フォーム内注記より、StoreKit / RevenueCat 経由は「デベロッパは支払い情報にアクセスできない」ため申告対象外。
  - **検索履歴** — パターン検索クエリは transient リクエストパラメータでオフデバイス永続化しない。

- [ ] **保存** → ダッシュボードに各データタイプの黄色 ⚠️「X を設定」ボタンが並ぶ。それぞれをクリックして 6 択の用途 follow-up モーダル (「該当するものをすべて選択」) を下表通りに埋める。

**データタイプごとの用途選択** — 各モーダルを開き、下記のみチェック、保存:

| モーダルタイトル (Apple 表記) | チェック (これだけ) |
|---|---|
| 名前 | アプリの機能 |
| メールアドレス | アプリの機能 |
| 写真またはビデオ | アプリの機能 |
| カスタマーサポート | アプリの機能 |
| その他のユーザコンテンツ | アプリの機能 |
| ユーザID | アプリの機能 |
| デバイスID | **アプリの機能 + アナリティクス** |
| 購入履歴 | アプリの機能 |
| 製品の操作 | **アナリティクス** |
| クラッシュデータ | アプリの機能 |
| パフォーマンスデータ | アプリの機能 |

- [ ] 各モーダルで「次へ」を押すと Apple が以下を聞く:
  - **Linked to user?** → 全 11 項目 **Yes**
  - **Used for tracking?** → 全 11 項目 **No**

判断の根拠 (各 1 行):

- クラッシュデータ / パフォーマンスデータ は **アナリティクス ではない** — Apple の「アプリの機能」定義に「クラッシュの最小化」「拡張性とパフォーマンス」が明文化されている。アナリティクスはユーザ行動評価で、アプリ挙動 diagnostics は別。
- ユーザID が「アプリの機能」のみなのは `PostHog.identify(supabaseUid)` が意図的に未配線 (CLAUDE.md「strict anonymity stance」) のため。
- デバイスID は両方該当: APNs token = push routing (アプリの機能)、PostHog `distinct_id` = device-level analytics linkage (アナリティクス)。
- メール / 名前 は「デベロッパの広告またはマーケティング」非該当 — transactional auth メールは Apple 定義の marketing に含まれない。

### A0b-11: RevenueCat に product import (ダッシュボード手動 step)

- [ ] RC Dashboard → **Project Settings → Apps & providers** → Skeinly iOS → **Products** → **+ New** の右隣にある **Import** ボタンをクリック
- [ ] `io.github.b150005.skeinly.pro.monthly` + `io.github.b150005.skeinly.pro.yearly` 両方の import を確認

ASC ↔ RC OAuth が wired でも必須。MCP binding (A0f-4) は次の step。

### A0b-12: ASC 検証

- [ ] Subscription Group `Skeinly Pro` (EN + JA localization)
- [ ] 両プロダクト存在、duration + 価格 + EN/JA localization 正常
- [ ] 両プロダクト Level 1
- [ ] 両方に 7 日間無料トライアル
- [ ] Production + Sandbox Server URL 設定済 (V2)
- [ ] sandbox tester ≥ 1 (US + JP 推奨)
- [ ] RC dashboard で import 後に両プロダクト visible

「メタデータが不足」バッジは App Review Screenshot + 1024×1024 promotional image を追加するまで残るが、Phase 39 alpha では sandbox 購入は機能する。Phase 40 GA 提出前に対応。

### A0b — Phase 40 GA でのみ対応する ASC サーフェス (Phase 39 alpha ではスキップ)

以下の ASC サーフェスは **App Store 公開商品ページにしか表示されない**。Phase 39 alpha は TestFlight Internal 配信のみで公開商品ページが存在しないため、alpha 段階の表示価値はゼロ。スキップが正解で、Phase 40 GA 提出前に audit して対応する。

- **App 情報 → アプリのアクセシビリティ** — VoiceOver / 音声コントロール / さらに大きな文字 / ダークインターフェイス / カラー以外で区別 / 十分なコントラスト / 視差効果を減らす / キャプション / バリアフリー音声ガイド など、支援技術によるコアタスク完遂を申告。Apple コンプライアンス: **エンドツーエンドで動作確認済の機能のみ申告可**。虚偽申告は App Review reject 対象。Skeinly の a11y audit は途中 (CLAUDE.md Tech Debt: A25 Reduce Motion iOS SwiftUI sweep + M5 ChartEditor zoom WCAG 2.5.8 両方 pre-Phase-40-GA)。audit 完了後に申告。
- **App プライバシー → プライバシーニュートリションラベル — App Review Information screenshots** — IAP レビュー時のみ必要 (上記 IAP 「メタデータが不足」バッジ)。
- **成長とマーケティング** 群 (アプリ内イベント / カスタムプロダクトページ / プロダクトページの最適化 / プロモーションコード / Game Center) + **フィーチャー → ノミネート** — App Store **公開商品ページ** のマーケティング surface。公開商品ページが存在する場合のみレンダリングされ、Phase 39 TestFlight Internal では存在しない。**これらは RevenueCat の管理範囲ではない** — ASC はインストール前のマーケティング (App Store 商品ページ内容)、RevenueCat はインストール後のマネタイズ (アプリ内 paywall + サブスクリプションオファリング) を担当し、両者は重ならない。特に ASC の「プロモーションコード」はアプリや IAP 商品を **無料で配るコード** を発行する機能で、RC の「Promotional Offers」は既存 subscriber に対する **割引・無料延長**。完全に別の仕組みで、両方を Phase 40 以降併用可能。Game Center は N/A (Skeinly はゲームではない)。

### A0b よくある落とし穴

- Product ID は永続。保存前に三重確認。
- Duration は App Review 通過後変更不可。
- Introductory offer は編集不可 — 削除 + 再作成のみ。
- Description 文字数上限は locale ごとに 55 (2026-05-13 operator 検証; Apple doc では依然として 45)。Display Name は 30 上限。
- Sandbox tester の email は real Apple ID として使われたことがあってはダメ。Plus-subaddressing で 1 inbox に集約可 (Gmail / iCloud / Fastmail / ProtonMail は `+` サポート; Outlook は NG)。
- 新プロダクトが sandbox に見えるまで ~1 時間の伝播遅延。

---

## Phase A0c — Google Play Console (App + IAP + License tester + RTDN)

### A0c-1: アプリレコード作成

- [ ] <https://play.google.com/console> → **すべてのアプリ** → **アプリを作成**
- [ ] アプリ名 `Skeinly`、デフォルトの言語 **English – en-US**、アプリかゲームか **アプリ**、無料・有料 **無料**
- [ ] 宣言に同意 → 作成

### A0c-2: Subscription Product 作成

- [ ] アプリ → **Play で収益化 → プロダクト → 定期購入** → **定期購入の作成**
- [ ] Product ID **`io.github.b150005.skeinly.pro`** ⚠️ 永続 + 再使用不可
- [ ] 名前 `Skeinly Pro` (≤55 文字、ユーザー可視)
- [ ] 作成 → **定期購入の詳細を編集** → 特典 (≤4 件、各 ≤40 文字) を追加:
  - `無制限のチャート作成`
  - `高度なパターン分析`
  - `優先サポート`
- [ ] 説明 (社内専用、≤200 文字): `Skeinly Pro auto-renewable subscription. Monthly ($3.99) + yearly ($24.99) base plans, 7-day free trial. RevenueCat entitlement entlaaca26b181 via $rc_monthly / $rc_annual packages.`

⚠️ 特典テキストに「無料トライアル」や具体的価格を含めない — Play ポリシー違反。

### A0c-3: 月額 base plan

- [ ] 定期購入詳細 → **基本プランを追加**
- [ ] Base Plan ID **`monthly`** (単語、`a-z 0-9 -` のみ、≤63 文字)、Type **自動更新**、請求期間 **1 か月**
- [ ] 据置期間 3 日 (継続率上げるなら 7 日も可)、Account hold 30 日
- [ ] ユーザーの基本プランと特典の変更: **次回の請求日に請求** (次回更新時まで billing 変更を defer)
- [ ] 再加入: 有効、タグ: 空、Backwards compatible: **マークする** (これのみ)
- [ ] **国/地域の公開設定の管理** → US + JP + 全ターゲット市場 + **新しい国/地域** (将来の Google 対応市場を自動追加) → 保存
- [ ] **価格の更新** → 基本価格 **`3.99`** USD → JP 自動換算 (¥600 帯) を確認 → 保存
- [ ] **アクティブ化**: `monthly` ID テキスト (またはテーブル右端の `›` 矢印 — 横スクロール必要な場合あり) をクリック → 編集ページが開く → ページ最下部までスクロール → **「有効にする」** をクリック。3 点リーダー ⋮ メニュー経由ではない。

### A0c-4: 年額 base plan

- [ ] 同じ手順で: Base Plan ID **`yearly`**、請求期間 **1 年**、据置期間 7 日 (年額推奨)
- [ ] 次回の請求日に請求、再加入有効、タグ空、Backwards compatible **マークしない**
- [ ] 基本価格 **`24.99`** USD (JP ¥3,600–¥4,000)
- [ ] 同じ編集ページ最下部のボタンでアクティブ化

### A0c-5: 無料トライアル offer — 月額

- [ ] 定期購入詳細 → **基本プランと特典** セクション → 右上の **「特典を追加」リンク** → ダイアログで `monthly` を選択 → **「特典を追加」ボタン** → フォームが開く
- [ ] 特典 ID **`monthly-trial`** ⚠️ 永続、`a-z 0-9 -` のみ、≤63 文字
- [ ] 基本プランと公開設定: pre-selected `monthly`、提供地域 174/174 inherit
- [ ] **提供の条件**: **新規ユーザーの獲得**
- [ ] **資格** (サブフォーム): **この定期購入を利用したことがない** (デフォルト; subscription-product スコープ、将来複数 product 化に備えて future-proof)
- [ ] タグ: 空
- [ ] **段階** セクションまでスクロール → **「段階を追加」** → **無料トライアル** → Duration **7 日**
- [ ] 保存 → offer 編集ページ最下部の **アクティブ化** をクリック

### A0c-6: 無料トライアル offer — 年額

- [ ] 同手順を `yearly` base plan に対して: 特典 ID `yearly-trial`、同 eligibility + 資格 + 7 日 phase
- [ ] 保存 → アクティブ化

### A0c-7: 日本語ローカライズ

- [ ] **ユーザーを増やす → 翻訳 → 翻訳を管理 → 言語を選択 → 日本語 (日本) (ja-JP) → 適用**
- [ ] ja-JP 文字列を入力: 定期購入名 `Skeinly Pro`、特典 1 `無制限のチャート作成`、特典 2 `高度なパターン分析`、特典 3 `優先サポート`

Google 無料機械翻訳は日本語を含まない — 手動入力。

### A0c-8: Internal Testing トラックに公開

- [ ] `release.yml` CI flow で build + アップロード ([release.md](ops/release.md))。`gradle-play-publisher` で `DRAFT` 状態にアップロード。
- [ ] License tester 購入の前提: アプリが少なくとも 1 つの track に公開されていること (Internal Testing 最低限)。draft 状態は購入を拒否する。

⚠️ 初回 Bundle アップロードには Phase A0d (アプリのコンテンツ + ストア掲載情報) の完了が必須。A0d 完了後に「テスター宛にロールアウト開始」をクリック。

### A0c-9: License testers

- [ ] Play Console → **設定 → ライセンスのテスト**
- [ ] Gmail アカウントを追加 (1 行 1 件、最大 2,000)
- [ ] 変更を保存

加速テスト更新時間 (RC tester ごと): 無料トライアル 3 分、月額 5 分、年額 30 分、grace 5 分。

### A0c-10: RTDN 用 Pub/Sub topic

- [ ] 推奨経路: RC Dashboard → Skeinly Android → service credentials → **Connect to Google** (RC が topic ID 自動生成)
- [ ] または手動: [GCP Console → Pub/Sub → トピック → トピックを作成](https://console.cloud.google.com/cloudpubsub/topicList) → ID `play-billing-notifications`
- [ ] Pub/Sub API を有効化: <https://console.cloud.google.com/flows/enableapi?apiid=pubsub>
- [ ] topic → 権限タブ → **プリンシパルを追加**:
  - 新しいプリンシパル: `google-play-developer-notifications@system.gserviceaccount.com`
  - ロール: **Pub/Sub パブリッシャー** (`roles/pubsub.publisher`)
  - 保存

⚠️ GCP organization が Domain Restricted Sharing を強制している場合は `system.gserviceaccount.com` 例外を追加。

### A0c-11: Play Console で RTDN 設定

- [ ] アプリ → **Play で収益化 → 収益化の設定 → リアルタイム デベロッパー通知**
- [ ] 有効化: ✅、トピック名: A0c-10 の `projects/<gcp_project>/topics/<topic_name>`
- [ ] 通知内容: **定期購入、無効になった購入、すべての 1 回限りの商品**
- [ ] **テスト メッセージを送信** → 成功を確認
- [ ] 変更を保存

### A0c-12: RC で RTDN 検証

- [ ] RC Dashboard → Skeinly Android → **Last received** timestamp が更新されることを確認
- [ ] (任意) **Track new purchases from server-to-server notifications** を有効化

### A0c-13: RevenueCat に product import

- [ ] RC Dashboard → **Project Settings → Apps & providers** → Skeinly Android → **Products** → **Import** ボタン
- [ ] `io.github.b150005.skeinly.pro:monthly` + `io.github.b150005.skeinly.pro:yearly` の import を確認 (post-Feb-2023 Play products の colon-separated RC 識別子)

### A0c-14: Play Console IAP 検証

- [ ] Subscription Product `io.github.b150005.skeinly.pro` 存在
- [ ] Base plan `monthly` 有効、backwards-compatible マーク済
- [ ] Base plan `yearly` 有効
- [ ] Offer `monthly-trial` 有効、7 日 phase
- [ ] Offer `yearly-trial` 有効、7 日 phase
- [ ] ja-JP 翻訳追加済
- [ ] アプリが Internal Testing に公開済
- [ ] License tester ≥ 1 (US + JP 推奨)
- [ ] Pub/Sub topic + IAM Publisher grant
- [ ] RTDN 設定済 + テストメッセージ成功
- [ ] Import 後、RC dashboard に Android 両プロダクト visible

### A0c よくある落とし穴

- Subscription Product ID + Base Plan ID の文字集合が異なる (product は `_` `.` も可、base plan は `a-z 0-9 -` のみ)。単語 base plan で issue 回避済。
- Backwards-compatible マークは 1 base plan のみ可。monthly を選択。
- License tester はアプリが track に公開されている必要あり (draft は拒否)。
- RC product 識別子は Android で `subscription_id:base_plan_id` の colon separator (iOS は `.`)。
- RC の Play Console service credentials は Pub/Sub Connect 機能が動くまで ~36 時間の warmup 必要。

---

## Phase A0d — Google Play Console (アプリのコンテンツ + ストア掲載情報 + Internal Testing)

**初回 App Bundle アップロード** に必須。設定は左メニュー **アプリのコンテンツ** 配下に集約。

### A0d-1: プライバシー ポリシー

- [ ] アプリのコンテンツ → **プライバシー ポリシー** → URL `https://b150005.github.io/skeinly/privacy-policy/` → 保存

保存前に別タブで URL が 200 を返すことを確認 (GitHub Pages deploy 遅延 ~1–5 分)。

### A0d-2: アプリのアクセス権 (レビュアー用 demo アカウント)

- [ ] アプリのコンテンツ → **アプリのアクセス権** → **すべての機能または一部の機能のアクセスが制限されている** を選択 (Skeinly はサインアップ必須)
- [ ] **手順を追加** → モーダルが開く

Skeinly は Supabase email+password (ユーザー名なし)。レビュアーアカウントは [`beta-testing.md`](ops/beta-testing.md) の慣習に従い Gmail プラスサブアドレッシングを使用:

| Platform | Email | 用途 |
|---|---|---|
| iOS (ASC App Review Information) | `skeinly.app+review-ios@gmail.com` | Apple 審査員専用 |
| **Android (本 step)** | `skeinly.app+review-android@gmail.com` | Google 審査員専用 |

プラットフォームごとに分離することで、レビューウィンドウ重複時の demo state 汚染を回避 + Supabase Auth audit trail で識別容易。

| フィールド | 文字数制限 | 値 |
|---|---|---|
| 手順の名前 | 60 | `Skeinly Reviewer Access (Android)` (33 文字) |
| ユーザー名、メールアドレス、電話番号 | 100 | `skeinly.app+review-android@gmail.com` |
| パスワード | 100 | 16+ 文字の強パスワードを 1Password で生成 |
| アプリへのアクセスに必要なその他の情報 | 500 | 下記 478 文字英語サンプル |
| アプリへのアクセスに必要な情報は他にない (チェックボックス) | — | **チェックしない** |

Play の helper text + 右サイドガイダンスは英語必須。サンプル (478 文字、英語のみ):

```
Sign in with the credentials above (Supabase email+password auth, no 2FA). Demo data is pre-seeded: 3 patterns (rectangular / polar / variation), 1 in-progress project with row counter and photos, 1 active Suggestion in Discovery. The account is in Pro state, so Settings > Upgrade is reachable without a purchase. IAP runs in Play Billing sandbox via license tester registration. Delete the account via Settings > Account or https://b150005.github.io/skeinly/account-deletion/.
```

500 文字超過した場合: (a) 括弧書きパターン種類を削る → (b) Account deletion 行を削る、の順で短縮。

⚠️ **Demo アカウント前提条件** (Supabase 側、保存前に完了):

- [ ] Supabase Auth Dashboard で `skeinly.app+review-ios@gmail.com` user 作成 (手動で Confirm 済状態に切替)
- [ ] `skeinly.app+review-android@gmail.com` も同様に作成
- [ ] 両アカウントに idempotent seed 投入 (3 patterns / 1 project / 1 Suggestion / Pro 状態)
- [ ] RevenueCat で `grant-customer-entitlement` 経由で両アカウントに Skeinly Pro entitlement 直接付与 (paywall 強制を回避)

手順登録後、「**提供した認証情報を Android がパフォーマンスやアプリの互換性のテストに使用することを許可する**」チェックボックスは **有効化のまま** (デフォルト ON):

- これは Google の **Pre-Launch Report** インフラに demo 認証情報でログインしてアプリを実機ラボ (各種 OEM / 画面サイズ / API レベル) で自動テストする許可を与える。結果は App Bundle アップロード数十分後に テストとリリース → Pre-launch reports に表示される。
- 個人開発者では Samsung / Pixel / 低 RAM / 折りたたみのマトリクスを揃えられない — Google のラボがリリース毎に無料で実行してくれる費用対効果は非常に高い。
- 信頼モデルは変化なし: 認証情報は App Review 時に Google 人間レビュアーと既に共有済 — これは Google 自動テストインフラを consumer に加えるだけ。
- 運用注意: demo アカウントに定期的に bot トラフィックが流入する。上記の idempotent seed が正しい形 — Pre-Launch 実行間で「fresh state」を仮定しない seed にすること。Sentry / PostHog に bot 由来イベントが乗る — メトリクスノイズが気になる場合は `app_user_id` でフィルタ可能。

### A0d-3: 広告

- [ ] アプリのコンテンツ → **広告** → **いいえ、アプリに広告は含まれていません** (Skeinly は広告なし; 収益化は IAP のみ)

### A0d-4: コンテンツのレーティング (IARC)

アプリのコンテンツ → **コンテンツのレーティング** → ステップ 1 / 3 **カテゴリ** ページ:

- [ ] メールアドレス: **`skeinly.app+rating@gmail.com`** (コンテンツレーティング専用 plus-alias — フォーム helper に「このメールアドレスはレーティング機関や IARC と共有されることがあります」とある通り、IARC + 各国レーティング機関 (ESRB / PEGI / USK / CERO / ClassInd / ACB 等) すべてを受ける lane)
- [ ] カテゴリ: **その他のすべてのアプリの種類** — Skeinly のコアはプロジェクト + パターン管理 (個人作業)、UGC / Discovery / コメント / 提案は副次的。**ゲーム** ではない、**ソーシャルまたはコミュニケーション** でもない (コミュニケーションは Skeinly の主目的ではない、Facebook / Twitter / Skype とは異質)。さらに「その他」を選ぶと質問票が UGC / デジタル購入中心の **短い path** にルーティングされる — ゲームを選ぶと暴力 / ギャンブル / 性的描写などの不要な分岐を引き込む。
- [ ] ☑ **International Age Rating Coalition (IARC) の利用規約に同意します** にチェック
- [ ] **次へ** → ステップ 2 アンケート (IARC 質問票本体)

**想定結果**: PEGI 12 / USK 12+ / IARC 12+ / ACB 12+ / ESRB Teen (13+) / Russia RARS 14+。**Everyone / 4+ にはならない**。UGC + ユーザー間 interaction を持つアプリ (Instagram / Twitter / Reddit / Pinterest 等) の標準的なレーティング帯。PEGI / USK / ESRB は moderation 品質に関わらず「UGC を持つアプリは自動 12+」というポリシー (UGC は本質的に rating-relevant content を含み得るという原則)。Skeinly の collaboration はコア (Phase 36-38) なのでこれは不可避かつ accept 可能。Internal Testing track はレーティング非表示; A0d-5 Target Audience 18+ ≥ IARC 12+ で申告整合; Apple の age rating (別 questionnaire) は UGC を自動 12+ ドライバーにしないので 4+ or 9+ に落ち着く可能性が高い。

ステップ 2 は 5 セクションの質問票 — ゲート (はい/いいえ) があるのは最初のセクションのみで、残りはサブ質問がインラインで直接表示される。下表の matrix で回答。

| セクション | トップレベル ゲート? | Skeinly 方針 |
|---|---|---|
| **ダウンロード済みアプリ** | あり (はい / いいえ がトップに) | **いいえ** で回答 — バンドルは JIS 編み図シンボル 70 個 + UI 文字列 + アプリアイコンのみで、いずれも rating-relevant content ではない。**はい** にすると 10+ のサブ質問 (暴力 / 血液 / 流血 / 恐怖 / 性的 / ギャンブル / 言葉 / 規制物質 / 下品なユーモア etc.) が展開され、すべて いいえ で答えても結果は同じ — 短く安全な path として **いいえ** を選択。 |
| **ユーザー コンテンツの共有** | なし (サブ質問がインライン表示) | 下表の 8 サブ質問の matrix で回答。 |
| **オンライン コンテンツ** | なし (サブ質問がインライン表示) | サブ質問は下記で網羅。Skeinly のシンボルパック (Phase 41 dynamic インフラ: `symbol_packs` + `symbol_pack_locales` Supabase テーブル + RPC 配信) は server-delivered の curated コンテンツ — UGC でも bundled でもないのでこのセクションに該当。編み記号のみで暴力 / 性 / 言葉 / 薬物 / ギャンブル等の rating-relevant content なし、Skeinly team が著作するため fully moderated。 |
| **年齢制限が適用される製品または活動の宣伝または販売** | なし (サブ質問がインライン表示) | 広告なし、アルコール / タバコ / 武器 / 宝くじ等の販売・宣伝なし。IAP サブスクリプション自体は年齢制限対象外。サブ質問はすべて いいえ。 |
| **その他** | なし (サブ質問がインライン表示) | 5 つのサブ質問がインライン表示 — 下表の matrix で回答。 |

**ユーザー コンテンツの共有 サブ質問** (ゲートが「はい」の時に展開):

| サブ質問 | 回答 | 理由 |
|---|---|---|
| 音声通信 / SMS / 画像オーディオ共有で交流・コンテンツ交換? | **はい** | [Google policy guidance (answer/11070862)](https://support.google.com/googleplay/android-developer/answer/11070862) の文言は JA UI より広く、「UGC を交換できるなら はい — **コメント、写真共有、その他あらゆる UGC 交換** を含む」と明示。音声 / SMS / messaging-style に限らない。Skeinly のパターンへのコメント + Discovery 画像共有 + 提案 (Suggestion) はすべて該当 |
| UGC が **主要な** コンテンツソース? | **はい** | Skeinly の非 UGC 同梱コンテンツは 70 JIS シンボルのみで、これは primitives (アルファベット相当) であって "content" ではない。ユーザーが実質的に見る content はほぼ全て UGC (パターン / チャート / コメント / 提案)。製品ビジョンの中核は collaboration: Phase 36 Discovery+Fork / Phase 37 Collaboration Core / Phase 38 Pull Request workflow。Wave E UGC moderation foundation 投資 (ADR-021) も UGC-primary shape を裏付ける — `いいえ` だと moderation 規模と申告の不整合。逆説テスト: 共有 UGC を取り除くと Discovery + 提案 + コメントが消え、collaborative platform としての差別化価値が消失 |
| ヌード公開を許可? | **いいえ** | 機能として存在せず、Terms of Service 禁止、UGC moderation で削除 |
| 露骨な暴力表現の公開を許可? | **いいえ** | 同上 |
| ユーザー / UGC をブロックする機能? | **はい** | Wave E foundation (ADR-021) — `user_blocks` テーブル + RLS NOT-EXISTS filter で server-side block 実装済。ユーザー向け UI は Phase 40 GA 前リリース (ADR-021 §D4)。代替は **いいえ** + GA 提出時に質問票 re-take だが、レーティング結果 (Everyone) は両 path 同一なので **はい** を推奨 |
| ユーザー / UGC を報告する機能? | **はい** | 同 — `submit-ugc-report` Edge Function + GitHub Issue mirror + 24h オペレータトリアージ SLA ([`ugc-moderation-sop.md`](ops/ugc-moderation-sop.md)) |
| チャットモデレート? | **いいえ** | Skeinly にチャット機能なし。コメント / 提案は async forum-style で real-time chat ではない |
| 対話を招待友人のみに制限可? | **いいえ** | friend-only mode / private circles なし。Discovery / コメント / 提案はすべて公開、private 機能なし |

**その他 サブ質問** (5 つがインライン表示):

| サブ質問 | 回答 | 理由 |
|---|---|---|
| ユーザーの詳細な現在地情報を他ユーザーと共有? | **いいえ** | Skeinly は位置情報を収集していない (A0d-6 Data safety で宣言済) |
| ユーザーはアプリを通じてデジタル商品を購入できる? | **はい** | IAP Pro subscription (StoreKit / Play Billing 経由) |
| 現金報酬 / ギフトカード / play-to-earn / 換金可能暗号通貨 / 譲渡可能デジタル資産 (NFT) の発行? | **いいえ** | いずれも該当機能なし |
| ウェブブラウザまたは検索エンジン? | **いいえ** | Skeinly は craft プロジェクト管理アプリでブラウザではない |
| 主にニュースまたは教育商品? | **いいえ** | コアはプロジェクト管理 + collaboration。Discovery でパターンから技法を学べるのは副次的、curriculum 型の教育商品 / ニュース商品ではない |

ステップ 2 送信後、IARC が各地域別レーティングを自動算出。**2026-05-14 検証結果**: PEGI 12 / USK 12+ / IARC 12+ / ACB 12+ / ESRB Teen / RARS 14+。ドライバーは UGC + Users-Interact descriptor + IAP 申告であり、Skeinly のコンテンツ自体は craft-safe (rating-relevant content なし)。collaboration を持つアプリの標準帯なので accept してそのまま進める。

### A0d-5: ターゲット ユーザー

- [ ] アプリのコンテンツ → **ターゲット ユーザー** — 6 つの年齢層が表示されるが、A0d-4 IARC 結果に ESRB Teen (13+) が含まれるため、**Play Console が 13 歳未満の 3 つ (5歳以下 / 6〜8歳 / 9〜12歳) を選択不能** に gate する。選べるのは 13〜15歳 / 16〜17歳 / 18歳以上 の 3 つのみ。
- [ ] **選択可能な 3 つすべてをチェック** (13〜15歳 + 16〜17歳 + 18歳以上)。理由: (1) ESRB 13+ で under-13 が構造的に排除されるため DFF (Designed for Families) policy は既に回避済; (2) teen は Skeinly の正当なユーザー層 (祖母から教わる、学校クラブ等) — 18+ のみに制限すると実ユーザーを排除し Play Store discoverability も下がる; (3) [pre-alpha-checklist V7](ops/pre-alpha-checklist.md) は "Adults only" / "Teens and adults" 両方を policy-compliant として明示。
- [ ] 子供向けの魅力: **いいえ** (teen をターゲットに含めるが、under-13 向けではない)
- [ ] (尋ねられたら) 子供がアプリを利用する可能性: **私のアプリは子供向けではありません**

より厳格な declaration を求める場合は **18歳以上のみ** チェックでも policy 上 OK。トレードオフ: policy surface 最小だが正当な teen 層を排除 + Play Store algorithmic reach 低下。3-band 選択がデフォルト推奨。

### A0d-6: データ セーフティ

アプリのコンテンツ → **データ セーフティ**。フォームは 5 ページの wizard:
1. **概要** — landing ページ、開始 / 編集 をクリック
2. **データの収集とセキュリティ** — トップレベルの収集 + セキュリティ + アカウント / 削除関連の設問
3. **データの種類** — 9 種データの per-type 申告 (下表の matrix)
4. **データの使用と処理** — per-type の用途 + 共有
5. **プレビュー** — 確認 + 公開

#### ページ 2: データの収集とセキュリティ

| 設問 | 回答 |
|---|---|
| アプリは対象になる種類のユーザーデータを収集または共有しますか? | **はい** |
| アプリで収集するユーザーデータはすべて、転送時に暗号化されますか? | **はい** (HTTPS / TLS — Supabase / RevenueCat / GitHub / APNs / FCM すべて) |
| アプリが対応しているアカウントの作成方法 (該当をすべて) | **Phase 26 (OAuth Sign-In) の実装状態に依存** — 下表参照 |
| ユーザーによるアカウントの作成をアプリで許可していない | **チェックしない** (sign-up は可能) |
| アカウント削除用 URL | `https://b150005.github.io/skeinly/account-deletion/` |
| アカウントの削除を必要とすることなく、一部またはすべてのデータの削除をリクエストする方法をユーザーに提供していますか? (任意) | **Phase 27 (Data Wipe) の実装状態に依存**。Pre-Phase-27 = **いいえ** (account-level deletion via `delete_own_account` RPC + 個別 CRUD のみ、bulk-deletion request mechanism なし)。Post-Phase-27 = **はい** + データ削除 URL **`https://b150005.github.io/skeinly/data-deletion/`** (in-app Settings → プライバシー → 「すべてのデータを削除」の web mirror、`wipe_own_data` RPC で auth + subscriptions は保持しつつ UGC をクリア)。Phase 27 は alpha-launch HARD-GATE (上記 Planned セクション参照)。URL slug `data-deletion` は agent-team deliberation 2026-05-14 で plain-language + 既存 `account-deletion` ページとの並列性を理由に決定 |

**アカウント作成方法の選択 (Phase 26 の状態別)**:

| Phase 26 の状態 | チェックする項目 |
|---|---|
| **Pre-Phase-26** (現状 — email/password のみ) | **「ユーザー名とパスワード」のみ** |
| **Post-Phase-26 26.1-26.4** (Apple Sign-In + Google Sign-In + account-merge shipped) | **「ユーザー名とパスワード」** + **「OAuth」** |
| **Post-Phase-26 26.5+** (MFA TOTP shipped) | 上記 + **「ユーザー名、パスワード、その他の認証」** |

Phase 26 は alpha-launch HARD-GATE (上記 Planned セクション参照) で、OAuth + MFA + 生体認証 を 1 wave で ship する設計。提出時、ビルドに実際 shipped されている内容に合わせて本セクションの選択を更新する。Phase 26 完了後の最終選択は **「ユーザー名とパスワード」 + 「OAuth」 + 「ユーザー名、パスワード、その他の認証」** の 3 つチェック。

「**その他のバッジ**」セクション (任意):
- **独自のセキュリティ審査**: チェックしない (3rd-party security audit 未実施)
- **UPI での支払い確認済み**: チェックしない (Skeinly はインド NPCI 認定対象のファイナンスアプリではない)

#### ページ 3: データの種類 — Play カテゴリ別ウォークスルー

Play は 14 の折りたたみカテゴリに分類。各カテゴリを展開し、下記項目のみチェック、他は外す。出典マッピングは [pre-alpha-checklist.md §35.1 A6](ops/pre-alpha-checklist.md#a6-data-safety-form) を参照。

**位置情報** (0/2): チェックなし。Skeinly は位置情報を収集しない。

**個人情報** (3/9 チェック):
- ☑ **名前** — Skeinly `display_name` (Supabase profile)。ユーザーが本名を入れる可能性があるため安全側で Name 申告。
- ☑ **メールアドレス** — Supabase Auth email。
- ☑ **ユーザー ID** — Supabase UUID。
- チェック外す: 住所 / 電話番号 / 人種、民族 / 政治信条、宗教 / 性的指向 / その他の情報 (生年月日 etc.) — いずれも収集なし。

**財務情報** (1/4 チェック):
- ☑ **購入履歴** — RevenueCat サブスクリプション状態 (Pro のみ)。
- チェック外す: 支払い情報 (Play / StoreKit が処理し Skeinly に届かない — ASC の対応設問と同様); 信用度 / 与信; その他の財務情報。

**健康とフィットネス** (0/2): チェックなし。Skeinly は健康・フィットネスデータを収集しない。

**メッセージ** (1/3 チェック):
- ☑ **その他のアプリ内メッセージ** — ユーザー間のテキスト交換 (パターンのコメント、提案 (Suggestion) + そのコメントスレッド) + user-to-developer テキスト送信 (Settings → Send Feedback の bug report 内容) をまとめてカバー。Play の tooltip 「その他の種類のメッセージ（インスタント メッセージ**など**）」の「など」を broader な「forum-style テキスト交換も含む」と読む解釈に基づく (real-time IM 限定ではない)。
- チェック外す: メール (Skeinly はユーザーの email 内容を扱わない); SMS または MMS (SMS なし)。

**写真と動画** (現在 1/2 チェック、Phase 28 完了後は 2/2):
- ☑ **写真** — Supabase Storage 上のプロジェクト進捗写真。
- ☐ 動画 — **Phase 28 (Video Upload Pro Feature) ship 後にチェック追加** + Data Safety を再提出。

**音声ファイル** (0/3): チェックなし。音声録音 / 音楽ファイル / その他オーディオなし。

**ファイル、ドキュメント** (1/1 チェック):
- ☑ **ファイル、ドキュメント** — チャートデータ / パターンデータエクスポート (UGC)。

**カレンダー** (0/1): チェックなし。カレンダーイベント連携なし。

**コンタクト** (0/1): チェックなし。Skeinly はデバイスの連絡先リストにアクセスしない。Phase 25 friend-only mode は in-app friend connection を追加するが、これは Play の意図する「contacts」(デバイスアドレス帳 / 連絡先由来のソーシャル グラフ) には該当しない。

**アプリのアクティビティ** (1/5 チェック):
- ☑ **アプリのインタラクション数** — PostHog の page view / tap / scroll (同意画面 opt-in)。
- チェック外す: アプリ内の検索履歴 (transient で永続化しない); インストール済みのアプリ (他アプリ調査機能なし); その他のユーザー作成コンテンツ (Skeinly UGC はチャート → ファイル、ドキュメント、コメント → メッセージ で申告済、残りなし); その他の操作 (PostHog の取得はインタラクション数で、その他の操作ではない)。

**ウェブ閲覧** (0/1): チェックなし。ブラウジング履歴取得なし。

**アプリの情報、パフォーマンス** (2/3 チェック):
- ☑ **クラッシュログ** — Sentry crash logs (opt-in)。
- ☑ **診断情報** — Sentry performance traces (opt-in)。
- チェック外す: その他のアプリのパフォーマンス データ。

**デバイスまたはその他の ID** (1/1 チェック):
- ☑ **デバイスまたはその他の ID** — FCM/APNs push token + PostHog distinct_id。

予想チェック合計: **14 カテゴリ全体で 11 件** (Phase 28 完了後は 動画 追加で 12 件)。

#### ページ 4: データの使用と処理

- データ共有: **いいえ** (Sentry / PostHog / RevenueCat / GitHub は Play の定義で service provider 扱い、sharing ではない)
- セキュリティ プラクティス: 送信時暗号化 **はい**、ユーザー削除要求可能 **はい** (in-app + web)、独立検証 **いいえ**、Families Policy **いいえ**

### A0d-7: 行政 / 金融 / 健康

- [ ] **行政アプリ**: いいえ
- [ ] **金融取引機能**: いいえ (IAP サブスクリプションは Play 定義の「金融サービス」非該当)
- [ ] **健康**: いいえ

### A0d-8: アプリのカテゴリと連絡先情報

- [ ] ダッシュボード → **アプリのカテゴリを選択し、連絡先情報を提供する** (またはストア掲載情報の概要 → ストアの設定)
- [ ] アプリ or ゲーム: **アプリ**
- [ ] カテゴリ: **ライフスタイル** ([store-listing.md](store-listing.md) 参照)
- [ ] タグ: Play 提示リストから選択 — `Knitting`、`Hobby`、`Craft`、`Pattern` (5 つまで; 自由入力ではない)
- [ ] メール: `skeinly.app@gmail.com` (公開される)
- [ ] ウェブサイト: `https://b150005.github.io/skeinly/`
- [ ] 電話: 空
- [ ] 外部マーケティング: **いいえ**

### A0d-9: ストアの掲載情報

- [ ] ストアでの表示 → **メインのストアの掲載情報**
- [ ] アプリ名 `Skeinly` (30 文字)
- [ ] 簡単な説明 (80 文字 EN/JA — [store-listing.md](store-listing.md))
- [ ] 詳細な説明 (4000 文字 EN/JA — [store-listing.md](store-listing.md))
- [ ] グラフィック アセット:
  - アプリ アイコン 512×512 (`androidApp/src/main/ic_launcher-playstore.png` に配置済)
  - **フィーチャー グラフィック 1024×500** ⚠️ 未作成 — Internal Testing rollout でも必須
  - 電話 screenshot ≥ 2 枚 (EN + JA 推奨) ⚠️ 未作成
  - 7"/10" タブレット任意

### A0d-10: Internal Testing トラック設定

- [ ] **テストとリリース → テスト → 内部テスト** → **新しいリリースを作成**
- [ ] CI が `release.yml` + `gradle-play-publisher` で `releaseStatus = DRAFT` アップロード (CLAUDE.md Tech Debt エントリ)
- [ ] リリースノート EN + JA 両方必須 ([release.md](ops/release.md))
- [ ] **テスター** タブ → Google アカウントの email を追加 (Internal track は ≤100) — 「アプリのテスター ライセンス」セクションの opt-in リンクを共有
- [ ] License tester (Settings → ライセンスのテスト の別リスト — 同じ email を両方に登録)
- [ ] **保存 → 公開を確認** で DRAFT 停止 (safety: `releaseStatus = DRAFT` で auto-rollout 防止)
- [ ] A0d 全て green になってから **テスター宛にロールアウト開始** を手動クリック

### A0d-11: 公開可否チェックリスト (Internal Testing 配信前)

- [ ] A0d-1: プライバシー ポリシー URL 登録済 + 200 OK
- [ ] A0d-2: アプリのアクセス権 demo 認証情報登録 + 両 demo アカウント存在 + seed 済 + Pro entitlement grant 済
- [ ] A0d-3: 広告 = なし
- [ ] A0d-4: コンテンツのレーティング = Everyone
- [ ] A0d-5: ターゲット ユーザー = Adults only (子供年齢層なし)
- [ ] A0d-6: データ セーフティ 全カテゴリ申告 + Account Deletion URL 登録
- [ ] A0d-7: 行政 / 金融 / 健康 = いいえ
- [ ] A0d-8: カテゴリ ライフスタイル + 連絡先 email + ウェブサイト
- [ ] A0d-9: 掲載情報文 EN + JA + アイコン + フィーチャー グラフィック + 電話 screenshot ≥ 2 枚
- [ ] A0d-10: Internal Testing track + テスター + License tester + リリースノート EN + JA

### A0d よくある落とし穴

1. 保存せずタブ切替 — 各セクションで Save 必要。
2. プライバシー URL 404 / 503 — GitHub Pages deploy 待ち (~1–5 分)。
3. サインアップありで「データ収集なし」と申告 — 偽申告。最低限 email + display name は収集している。
4. 子供年齢層チェック — DFF flag は解除が遅い。最初から Adults only。
5. License tester ≠ Internal tester — Pro IAP テストには両リストに登録必要。
6. Internal Testing でも feature graphic 必須 — track の visibility に関わらず掲載情報完成度を要求。

---

## Phase A0e — Universal Links (AASA) + Android App Links (assetlinks.json)

### A0e-1: ホスティング戦略の決定

Apple は AASA を `https://<domain>/.well-known/apple-app-site-association` で HTTPS, `Content-Type: application/json`, リダイレクトなし、で要求。Skeinly の GitHub Pages は `https://b150005.github.io/skeinly/` (project page) だが、AASA は apex `https://b150005.github.io/.well-known/...` (別 repo の user site) に置く必要がある。

| Option | コスト | 用途 |
|---|---|---|
| **A — `b150005/b150005.github.io` user site 作成** | $0 | alpha 推奨 |
| **B — カスタムドメイン (`skeinly.app` 等)** | ~$10/年 | v1.0 推奨 |
| **C — v1.0 まで延期、URI scheme のみ** | $0 | Universal Links 待てる場合 |

**alpha 推奨**: Option A。後で B への移行は CNAME 変更のみ。

### A0e-2: AASA ファイル内容

```json
{
  "applinks": {
    "apps": [],
    "details": [
      {
        "appIDs": ["<TEAMID>.io.github.b150005.skeinly"],
        "components": [
          { "/": "/skeinly/share/*" }
        ]
      }
    ]
  }
}
```

- [ ] `<TEAMID>` を 10 文字 Apple Developer Team ID に置換
- [ ] `https://b150005.github.io/.well-known/apple-app-site-association` (拡張子 `.json` なし) にデプロイ
- [ ] iOS app entitlements に `applinks:b150005.github.io` を追加

### A0e-3: assetlinks.json (Android App Links)

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "io.github.b150005.skeinly",
    "sha256_cert_fingerprints": ["<APP_SIGNING_SHA256>"]
  }
}]
```

- [ ] Play Console → 設定 → アプリの完全性 → App signing key SHA-256 から `<APP_SIGNING_SHA256>` を取得
- [ ] `https://b150005.github.io/.well-known/assetlinks.json` にデプロイ

### A0e-4: デプロイ後の検証

```bash
curl -I https://b150005.github.io/.well-known/apple-app-site-association
# Expect: HTTP/2 200, content-type: application/json
curl https://b150005.github.io/.well-known/apple-app-site-association | jq .
# Expect: valid JSON parse

# Apple 自身のバリデータ (alternative)
# https://search.developer.apple.com/appsearch-validation-tool
```

---

## Phase A0f — RevenueCat

RC はクロスプラットフォーム IAP / サブスクリプション orchestration レイヤ。Skeinly のプロダクトは A0b-11 + A0c-13 の import 経由で流入; 本 phase は entitlement + offering + webhook を配線。

### A0f-1: RevenueCat Project 作成

- [ ] RC Dashboard → **+ New Project** → Name `Skeinly` → Create

### A0f-2: iOS App 連携

- [ ] **Project Settings → Apps → + New → App Store**
- [ ] App name `Skeinly iOS`、Bundle ID `io.github.b150005.skeinly`
- [ ] A0a-5 の `.p8` をアップロード + Key ID + Issuer ID
- [ ] Save → RC が product list を fetch するまで 1 分未満
- [ ] **API Keys** タブ → **Public iOS SDK Key** (`appl_...`) をコピー → `REVENUECAT_API_KEY_IOS` を [release-secrets §18](release-secrets.md#18-revenuecat_api_key_ios) で登録

### A0f-3: Android App 連携

- [ ] **Project Settings → Apps → + New → Play Store**
- [ ] App name `Skeinly Android`、Package `io.github.b150005.skeinly`
- [ ] `revenuecat@<project-ref>.iam.gserviceaccount.com` SA JSON をアップロード (この SA は Play Console → ユーザーと権限 で「財務データの表示」+「注文と定期購入の管理」を付与済)
- [ ] Save → RC が product list を fetch するまで待機
- [ ] **API Keys** タブ → **Public Android SDK Key** (`goog_...`) をコピー → `REVENUECAT_API_KEY_ANDROID` を [release-secrets §19](release-secrets.md#19-revenuecat_api_key_android) で登録

### A0f-4: Entitlement + Offering binding

これは A0b-11 + A0c-13 ダッシュボード import 完了後に **次セッションで RevenueCat MCP 経由で実行**:

- [ ] `list-products` — 4 product 可視性を確認 (iOS monthly + yearly + Android `:monthly` + `:yearly`)
- [ ] `list-offerings` → `list-packages` — `default` offering 上の既存 `$rc_monthly` / `$rc_annual` を確認
- [ ] `attach-products-to-package` × 4: iOS+Android monthly → `$rc_monthly`、iOS+Android yearly → `$rc_annual`
- [ ] `list-entitlements` + `attach-products-to-entitlement` — `entlaaca26b181` が 4 product すべてを含むことを確認

クライアントは `Purchases.shared.getOfferings()` で `current` offering の package を読むだけ — クライアントコードに hardcoded product ID なし。

### A0f-5: Webhook 統合

- [ ] 共有 secret 生成: `openssl rand -hex 32`
- [ ] RC → **Integrations → Webhooks → + New Webhook**:
  - URL: `https://<project-ref>.supabase.co/functions/v1/revenuecat-webhook`
  - Authorization header: secret をペースト
  - 環境: デフォルト (Sandbox + Production)
- [ ] Save → secret を `REVENUECAT_WEBHOOK_SECRET` として [release-secrets EF-4](release-secrets.md#ef-4-revenuecat_webhook_secret) で登録
- [ ] RC dashboard から test event をトリガ → Edge Function ログで 200 受信を確認

### A0f-6: エンドツーエンド smoke test

- [ ] iOS: TestFlight ビルドを実機に、Sandbox tester (US 1 + JP 1) でサインイン、paywall を開く、月額をタップ → StoreKit Sandbox ダイアログ → 購入完了 → entitlement grant + `subscriptions` 行を Supabase MCP `execute_sql` で確認
- [ ] Android: Play Internal Testing ビルド、license tester Google アカウントでサインイン、paywall を開く、月額をタップ → Play Billing ダイアログ (テスト表記あり) → 購入完了 → 同じ `subscriptions` 行 write を確認

---

## Phase A0 — 検証チェックリスト (最初の alpha タグ前)

### Apple 側
- [ ] App ID `io.github.b150005.skeinly` + 4 capability
- [ ] APNs `.p8` ダウンロード済、EF secret 登録済
- [ ] capability 追加後に Provisioning Profile 再生成済
- [ ] ASC アプリ + Subscription Group `Skeinly Pro` + EN/JA localization
- [ ] 2 IAP product 正しい ID + EN/JA localization + 各々に 7 日間無料トライアル
- [ ] 両 product Level 1
- [ ] ASSN V2 webhook 設定 (Production + Sandbox) → RC test event 成功
- [ ] Sandbox tester ≥ 2 (US 1 + JP 1 推奨)
- [ ] App Privacy 宣言提出済
- [ ] RC dashboard に product import 済

### Google 側
- [ ] Play Console アプリ `Skeinly` を Internal Testing に公開
- [ ] Subscription Product `io.github.b150005.skeinly.pro` + 両 base plan アクティブ + 両無料トライアル offer アクティブ
- [ ] ja-JP 翻訳追加済
- [ ] License tester ≥ 1 (US + JP 推奨)
- [ ] Pub/Sub + IAM Publisher + RTDN test message 成功
- [ ] RC dashboard に product import 済
- [ ] アプリのコンテンツ全 green: プライバシー / アクセス権 / 広告 / レーティング / ターゲット / データ セーフティ / 行政 / 金融 / 健康 / カテゴリ / ストア掲載情報
- [ ] Supabase に demo アカウント作成 + seed 済 + Pro entitlement grant 済

### クロスベンダー
- [ ] AASA + assetlinks.json デプロイ済 (or Option C を明示的に accept)
- [ ] RC Project + iOS + Android アプリ連携済
- [ ] `REVENUECAT_API_KEY_IOS` + `REVENUECAT_API_KEY_ANDROID` GitHub Secret 登録済
- [ ] Entitlement `entlaaca26b181` を MCP 経由で 4 product 全てに bind 済
- [ ] `default` offering に `$rc_monthly` + `$rc_annual` package
- [ ] `REVENUECAT_WEBHOOK_SECRET` EF secret 登録 + test webhook 200
- [ ] エンドツーエンド smoke test を両プラットフォームで pass

## クロスリファレンス

- secret ごとの OBTAIN / VERIFY / REGISTER 手順: [release-secrets.md](release-secrets.md)
- ADR-016 (RevenueCat 決定、価格、entitlement): [adr/016-phase-41-revenuecat-subscription.md](adr/016-phase-41-revenuecat-subscription.md)
- ADR-017 (Push 通知): [adr/017-phase-24-push-notifications.md](adr/017-phase-24-push-notifications.md)
- ADR-021 (UGC moderation foundation): [adr/021-pre-alpha-ugc-moderation.md](adr/021-pre-alpha-ugc-moderation.md)
- コンプライアンス監査 (policy ごとの検証): [ops/pre-alpha-checklist.md](ops/pre-alpha-checklist.md)
- ブランチ保護 + CI: [ops/repo-policy.md](ops/repo-policy.md)
- リリース flow (タグ push → CI → store upload): [ops/release.md](ops/release.md)
- クローズドベータテスター招待運用: [ops/beta-testing.md](ops/beta-testing.md)
- プライバシー ポリシーソース: [docs/public/privacy-policy/](../public/privacy-policy/)
- アカウント削除ページソース: [docs/public/account-deletion/](../public/account-deletion/)
- ストア掲載情報コピー (EN + JA): [store-listing.md](store-listing.md)

## Revision history

| 日付 | 内容 |
|---|---|
| 2026-05-13 | `ops/iap-setup-app-store-connect.md`、`ops/iap-setup-play-console.md`、`ops/play-console-app-setup.md` を本 file に統合 ([ops/README.md](ops/README.md) の「1 回きりセットアップ → vendor-setup.md」ルールに準拠)。Phase 番号を再構成 (Apple A0a+A0b、Google A0c+A0d、Universal Links A0e、RevenueCat A0f)。散文をチェックリスト形式に圧縮。 |
| Earlier | Apple-side scoped の初版 Phase A0 手順。 |
