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

- [ ] **チェックしない** (明示的に非収集 — 過剰申告も違反になる):
  - 連絡先情報: 電話番号 / 所在地 / ユーザのその他の連絡先情報
  - **健康とフィットネス**: 健康 / フィットネス (該当なし)
  - **財務情報**: 支払い情報 (Apple のフォーム内注記より —「アプリが支払いサービスを利用している場合、支払い情報はアプリ外で入力され、あなた（デベロッパ）は支払い情報にアクセスできません。その場合、データは収集されないため、申告する必要はありません。」Skeinly は StoreKit / RevenueCat 使用 — 支払い情報はアプリの手の届かない場所に留まる) / クレジット情報 / その他の財務情報
  - **位置情報**: 詳細な位置情報 / おおよその場所 (Skeinly は位置情報非収集)
  - **機密情報** (人種、性的指向、宗教等)
  - **連絡先** (アドレス帳アクセスなし)
  - ユーザコンテンツ: メールまたはテキストメッセージ / オーディオデータ / ゲームプレイコンテンツ
  - **閲覧履歴**
  - **検索履歴** (パターン検索クエリは transient リクエストパラメータで永続化しない)
  - 使用状況データ: 広告データ (広告なし) / その他の使用状況データ
  - 診断: その他の診断データ
  - **周囲**: 環境スキャン (AR / scene scanning なし)
  - **身体**: 手 / 頭 (body tracking なし)
  - **その他のデータ**

- [ ] **保存** をクリック → 次画面でチェックした項目ごとに follow-up 質問
- [ ] **チェックした全項目** に対して:
  - Linked to user? **Yes** (User ID がグラフ全体の anchor)
  - Used for tracking? **No** (Skeinly はクロスアプリ広告目的の第三者共有なし; Sentry / PostHog / RevenueCat / GitHub への SDK 経由転送は Apple 定義の service provider 処理に該当)
  - 用途: 機能必須なものは **App Functionality**。Diagnostics + Usage Data + Device ID (PostHog opt-in 経路) には **Analytics** も追加

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

### A0d-3: 広告

- [ ] アプリのコンテンツ → **広告** → **いいえ、アプリに広告は含まれていません** (Skeinly は広告なし; 収益化は IAP のみ)

### A0d-4: コンテンツのレーティング (IARC)

- [ ] アプリのコンテンツ → **コンテンツのレーティング** → 連絡先 email + カテゴリ登録 → IARC 質問票開始

目標: **Everyone (全年齢)**。Skeinly 回答:

| カテゴリ | 回答 |
|---|---|
| 暴力、性的コンテンツ、不適切な言葉、恐怖/ホラー、薬物/アルコール/タバコ、ギャンブル | **いいえ** |
| ユーザー間のやりとり | **はい** (共有 / コメント / 提案 / アクティビティフィード) |
| ユーザー生成コンテンツ (UGC) | **はい** (パターン + コメント) |
| 位置情報の共有 | **いいえ** |
| 個人情報のユーザー間共有 | **いいえ** (display name のみ公開) |
| デジタル購入 | **はい** (IAP) |
| 報告 / ブロック機能 | **はい** (ADR-021 Wave E foundation — `submit-ugc-report` + `user_blocks` + 24h オペレータトリアージ) |

ユーザー向け Report/Block UI は Phase 40 GA 前にリリース (ADR-021 §D4); foundation で policy 要件は既に満たしている。

### A0d-5: ターゲット ユーザー

- [ ] アプリのコンテンツ → **ターゲット ユーザー** → **18 歳以上のみ (Adults only)** のみチェック — 子供年齢層は全外し
- [ ] 子供向けの魅力: **いいえ**
- [ ] (尋ねられたら) 子供がアプリを利用する可能性: **私のアプリは子供向けではありません**

⚠️ いずれかの子供年齢層を選ぶと **Designed for Families (DFF) policy** が発動: COPPA 準拠、child-directed ad 制限、behavioral advertising 禁止。Skeinly は Adults only で全回避。

### A0d-6: データ セーフティ

- [ ] アプリのコンテンツ → **データ セーフティ** — 9 種データを申告 ([pre-alpha-checklist.md §35.1 A6](ops/pre-alpha-checklist.md#a6-data-safety-form) 参照):

| データカテゴリ | 種類 | 必須? | 暗号化 | 削除可? |
|---|---|---|---|---|
| 個人情報 | メール | はい | はい | はい |
| 個人情報 | Display name | はい | はい | はい |
| 個人情報 | 不具合報告内容 | いいえ (ユーザー送信時のみ) | はい | サポート経由 |
| 金融情報 | 購入履歴 (RevenueCat) | いいえ (Pro 加入者のみ) | はい | はい |
| アプリのアクティビティ | PostHog イベント | いいえ (opt-in) | はい | 匿名化 |
| アプリの情報とパフォーマンス | Sentry crash log | いいえ (opt-in) | はい | 匿名化 |
| デバイス ID | FCM/APNs token | いいえ (push 許可後のみ) | はい | はい |
| デバイス ID | Supabase user UUID | はい | はい | はい |
| ファイルとドキュメント | UGC (チャート画像 / パターンデータ) | いいえ (Discovery 共有時のみ) | はい | はい |

- [ ] データ共有: **いいえ** (Sentry / PostHog / RevenueCat / GitHub は Play の定義で service provider 扱い、sharing ではない)
- [ ] セキュリティ プラクティス: 送信時暗号化 **はい**、ユーザー削除要求可能 **はい** (in-app + web)、独立検証 **いいえ**、Families Policy **いいえ**
- [ ] アカウントとデータの削除:
  - ウェブ URL: `https://b150005.github.io/skeinly/account-deletion/`
  - 削除対象: アカウント情報、パターン、プロジェクト、進捗、コメント、提案、デバイストークン、サブスクリプション状態、UGC レポート、フィードバック、avatar 画像。保持: 法的要件の最小ログのみ。
  - 一部削除可: **いいえ** (アカウント全削除のみ、`delete_own_account` atomic RPC)

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
