# IAP セットアップ — Google Play Console (Skeinly Pro)

> Source of truth (English): [docs/en/ops/iap-setup-play-console.md](../../en/ops/iap-setup-play-console.md)

## ゴール

Skeinly Pro 自動更新サブスクリプションを Google 側で end-to-end 設定:

- **Subscription Product:** `skeinly_pro`
- **Monthly Base Plan:** `pro-monthly` — 月額 $3.99
- **Yearly Base Plan:** `pro-yearly` — 年額 $24.99
- 各 base plan に **7 日間無料トライアル** オファー
- **Real-time Developer Notifications (RTDN)** を Google Cloud Pub/Sub 経由で RevenueCat にルート
- **ライセンステスター** 登録でクローズドベータテスターが実課金なしで購入できるように

この runbook 完了後、次セッションで [RevenueCat 側](../adr/016-phase-41-revenuecat-subscription.md) (RevenueCat MCP 経由のプロダクト ↔ パッケージ紐付け) へ。

iOS 側: [iap-setup-app-store-connect.md](iap-setup-app-store-connect.md)。

## 前提条件

- Skeinly アプリに Google Play Console の Admin 権限保持。
- Skeinly アプリレコードが Play Console に存在 (パッケージ名 `io.github.b150005.skeinly`) かつ **少なくとも 1 トラック** (Internal Testing で十分) にリリース済み。**ライセンステスター購入は未リリース draft アプリでは動作しません。**
- Play Console サービスアカウントのクレデンシャルがある GCP プロジェクトの Google Cloud Console アクセス (通常は Play Console 初期セットアップ時に作成)。
- RevenueCat プロジェクトに Android アプリが登録済み。

## 確定済みの重要な決定事項

| 決定事項 | 値 | 根拠 |
|---|---|---|
| 価格 | 月額 $3.99 + 年額 $24.99 | ADR-016 §50 |
| 無料トライアル期間 | 7 日 | ADR-016 §50 |
| 構造 | 1 Subscription Product × 2 Base Plans | モダンな PBL 7+ idiom。無料トライアル対象資格は subscription product スコープで強制される = ユーザ 1 名につき両 base plan 通算 1 回 → トライアルコスト保護。RevenueCat プロダクト識別子 `subscription_id:base_plan_id` (コロン区切り) は 2023 年 2 月以降のフォーマット。 |
| Subscription Product ID | `skeinly_pro` | 将来安全のためアプリ接頭辞付き。subscription product ID はアンダースコア許容。 |
| Base Plan IDs | `pro-monthly` + `pro-yearly` | **ハイフンのみ — アンダースコアは base plan ID では不可。** これはスタイル指針ではなく Google Play API の制約。 |
| 後方互換性 | 月額 base plan を「backwards compatible」マーク | RevenueCat docs より旧 SDK バージョンは「Use for deprecated billing methods」マーク済みの base plan のみ参照可能。月額をマークしてフォールバックパスを残す。 |
| 価格設定基準リージョン | USD | Play Console が有効国全てに自動換算、国別上書きも可能。 |
| ライセンステスター用トラック | Internal Testing | ライセンステスターは公開済み任意トラックから購入可能。Internal が最低摩擦。 |

### ID 有効文字リファレンス

| フィールド | 許容文字 | 例 |
|---|---|---|
| Subscription Product ID | `a-z`, `0-9`, `_`, `.` | `skeinly_pro` ✅ |
| Base Plan ID | `a-z`, `0-9`, `-` のみ | `pro-monthly` ✅ — `pro_monthly` ❌ Play Console が reject |
| Offer ID | base plan と同じ | `pro-monthly-trial` ✅ |

ソース: [Google Publisher API — Subscriptions reference](https://developers.google.com/android-publisher/api-ref/rest/v3/monetization.subscriptions)。

## 操作順序

1. Subscription Product `skeinly_pro` 作成 (base plan はまだ追加しない)
2. 月額 base plan `pro-monthly` 追加 + 価格設定 + backwards compatible マーク + アクティブ化
3. 年額 base plan `pro-yearly` 追加 + 価格設定 + アクティブ化
4. 月額 base plan に 7 日間無料トライアルオファー作成 + アクティブ化
5. 年額 base plan に 7 日間無料トライアルオファー作成 + アクティブ化
6. ストア掲載に Japanese (ja-JP) 翻訳追加
7. アプリを Internal Testing トラックに公開 (未公開の場合)
8. ライセンステスターの Google アカウント追加
9. GCP で Pub/Sub トピック作成 + Google の RTDN サービスアカウントに IAM Publisher 権限付与
10. Play Console で Pub/Sub トピックを指して RTDN 設定
11. RevenueCat ダッシュボードでテスト通知受信確認

ステップ 9–11 は若干の順序変更可能: RevenueCat の「Connect to Google」フローがトピック ID を生成してくれる場合、ステップ 11a (RevenueCat でトピック ID 生成) をステップ 9 の前に。

---

## ステップ 1 — Subscription Product 作成

Play Console → Skeinly アプリ → サイドバー **Monetize with Play → Products → Subscriptions** → **Create subscription**。

| フィールド | 値 |
|---|---|
| Product ID | `skeinly_pro` |
| Name | `Skeinly Pro` (最大 55 文字、サブスクリプションメールと Google Play サブスクリプションセンターでユーザに表示) |

**Create** クリック。

### 永久フィールド

Product ID は変更も再利用も不可。タイポした場合、プロダクトをアーカイブして別 ID で新規作成必須。アーカイブ済み ID の再利用も block されます。入力前に計画を。

### Subscription benefits 追加

作成後、**Edit subscription details** クリック。**最大 4 行のベネフィット** を追加 (各 40 文字以内):

- `Unlimited chart creation`
- `Advanced pattern analysis`
- `Priority support`
- (任意の 4 行目)

**ベネフィットテキストに「無料トライアル」や具体的な価格を書かないこと** — Play ポリシー違反。トライアルと価格はユーザのサブスクリプション UI に別途表示されます。

正典: [Play Console Help — Create and manage subscriptions](https://support.google.com/googleplay/android-developer/answer/140504)。

## ステップ 2 — 月額 Base Plan 追加

サブスクリプション詳細ページから **Add base plan** クリック。

| フィールド | 値 |
|---|---|
| Base Plan ID | `pro-monthly` (ハイフンのみ — アンダースコアは reject) |
| Type | **Auto-renewing** |
| Billing period | Monthly |
| Grace period | 3 日 (Play デフォルト、より良い retention のため 7 日も検討) |
| Account hold | 30 日 (デフォルト) |
| Resubscribe | 有効 |

### 2a. 国の availability

**Manage country/region availability** クリック → **United States** + **Japan** + その他ターゲット市場をチェック。任意で **New countries/regions** をチェックして将来 Google 対応する市場を自動追加。**Apply** → **Save**。

### 2b. 価格設定

**Update prices** クリック:
1. 先ほど有効化した全ての国を選択。
2. 基本価格を入力: **`3.99`** USD。
3. Play Console が各選択国の現地通貨に現在の為替レートで自動換算、現地の「price charming」(端数の丸め方) を適用。
4. 日本 (JPY) への自動換算価格を確認。典型は ¥600–¥700 帯。特定の好みがあれば鉛筆アイコンで上書き。
5. **Apply** → **Save**。

### 2c. Backwards compatible マーク (RevenueCat 重要)

base plan のオーバーフローメニュー (**⋯**) を開く → **Use for deprecated billing methods** (「Mark as backwards compatible」と表示される場合あり) を選択。

これにより月額 base plan が旧 Play Billing Library バージョンのクライアントから見えるようになります。RevenueCat docs に明記: *"Google Play Console で 'backwards compatible' とマークされた base plan のみ旧 SDK バージョンで available"*。

**月額 base plan のみマーク**、年額はマークしない。1 つの subscription product につき 1 base plan しかマークできません。

### 2d. アクティブ化

**Activate** クリック → ダイアログで確認。ステータスが **Active** に変化。

### 重要な制約

base plan が **アクティブ化されてかつ購入が発生** すると、親 subscription product は削除不可になります (アーカイブのみ可能)。Plan ID も永久。

## ステップ 3 — 年額 Base Plan 追加

ステップ 2 と同手順、値だけ差し替え:

| フィールド | 値 |
|---|---|
| Base Plan ID | `pro-yearly` |
| Type | Auto-renewing |
| Billing period | **Yearly** |
| Grace period | 7 日 (年額推奨 — 高額コミットメントには長めの grace 適切) |
| Account hold | 30 日 |
| Resubscribe | 有効 |
| Backwards compatible | **マークしない** |

基本価格を **`24.99`** USD で設定、Play Console が自動換算。日本は典型的に ¥3,600–¥4,000 帯。

**Activate** クリック。

## ステップ 4 — 無料トライアルオファー (月額)

無料トライアルは base plan に埋め込まれず、各 base plan に紐付く **独立した Offer オブジェクト**。各 base plan に 1 オファー作成。

サブスクリプション詳細ページ → **Base plans and offers** セクション → `pro-monthly` 行で **Add offer** クリック。

| フィールド | 値 |
|---|---|
| Associated base plan | `pro-monthly` (事前選択済み) |
| Offer ID | `pro-monthly-trial` (ハイフンのみ) |
| Eligibility | **New customer acquisition** → **"The user has never had entitlement to this subscription"** |
| Country availability | base plan と同じセット |

### トライアルフェーズ追加

**Add phase** クリック → **Free trial** 選択 → Duration: **7 days**。

Free Trial フェーズには価格フィールドが表示されない — ゼロコストが定義上自明。

**Save** → **Activate** クリック。

### 対象資格スコープ注

- **"The user has never had entitlement to this subscription"** = subscription product 全体でユーザ 1 名につき 1 回適用 (月額・年額両 base plan で eligibility カウント共有)。これが望ましい挙動 — トライアルコスト保護。
- 代替: 「the user has never had any subscription in this app」は過去に別の Skeinly サブスクリプションを持っていたユーザのトライアルもブロック。我々のケースではない。
- **対象資格は Google Play が自動施行。** バックエンドチェック不要。
- Google Play はトライアル開始前に有効な支払い方法の登録を要求。

正典: [Play Console Help — Understanding subscriptions](https://support.google.com/googleplay/android-developer/answer/12154973)。

## ステップ 5 — 無料トライアルオファー (年額)

`pro-yearly` 行に対してステップ 4 を繰り返し。

| フィールド | 値 |
|---|---|
| Offer ID | `pro-yearly-trial` |
| Associated base plan | `pro-yearly` |
| Eligibility | New customer acquisition → "The user has never had entitlement to this subscription" |
| Phase | Free trial, 7 日 |

**Save** → **Activate**。

## ステップ 6 — 日本語ローカライズ

Play Console → **Grow users → Translations → Manage translations → Select languages → Japanese (Japan) (ja-JP) → Apply**。

ローカライズ対象:

| フィールド | English (en-US) | Japanese (ja-JP) |
|---|---|---|
| Subscription Name | `Skeinly Pro` | `Skeinly Pro` (固有名詞、ラテン文字維持) |
| Benefit 1 | `Unlimited chart creation` | `無制限のチャート作成` |
| Benefit 2 | `Advanced pattern analysis` | `高度なパターン分析` |
| Benefit 3 | `Priority support` | `優先サポート` |

日本語は **Google の無料機械翻訳セットには含まれない** — 手動入力。technical-writer が GA 準備時に JA copy をレビュー。

正典: [Play Console Help — Translate and localize your app](https://support.google.com/googleplay/android-developer/answer/9844778)。

## ステップ 7 — Internal Testing トラックに公開

Skeinly がトラックにリリースされていない場合、Internal Testing にビルドしてアップロード。ライセンステスター購入はアプリが少なくとも 1 トラックに公開されている必要があります — 未リリース draft はライセンステスター含め購入を受け付けません。

このステップに IAP 固有の UI 作業はなし。標準リリースアップロードフロー ([release.md](release.md) 参照)。

## ステップ 8 — ライセンステスター登録

Play Console → サイドバー **Setup → License testing**。

**Gmail accounts with testing access** 配下に全クローズドベータテスターの Google アカウントメールアドレスを 1 行ずつ入力 (最大 2,000 アドレス)。自分の publisher アカウントは自動で含まれます。

**Save changes** クリック。

### Sandbox 加速更新時間

ライセンステスターがサブスクリプションを購入すると、更新は加速スケジュールで実行 (購入あたり最大 6 更新):

| 本番期間 | 加速テスト期間 |
|---|---|
| 無料トライアル | 3 分 |
| 1 ヶ月 | 5 分 |
| 1 年 | 30 分 |
| Grace period | 5 分 |

これは Play が自動施行 — オペレータ操作不要。更新 → RevenueCat webhook → `subscriptions` テーブル行更新のパスを月単位ではなく分単位で検証できます。

### 任意: Play Billing Lab

[Play Billing Lab](https://play.google.com/store/apps/details?id=com.google.android.apps.play.billingtestcompanion) はテストデバイスにインストールするコンパニオンアプリ:
- Play 国の切替 (実 JP Google アカウントなしで JPY 価格テスト)
- 同じテストアカウントでのトライアル対象資格リセット
- オンデマンドでサブスクリプション状態遷移を発火 (grace period, account hold)

設定は 2 時間で失効。

正典: [Android Developers — Test in-app billing](https://developer.android.com/google/play/billing/test)。

## ステップ 9 — RTDN 用 Pub/Sub トピック

### 9a. RevenueCat 経由でトピック生成 (推奨)

RevenueCat Dashboard → Skeinly Android アプリ設定 → サービスクレデンシャルセクション → **Connect to Google** クリック。

RevenueCat が Pub/Sub トピック ID を `projects/<your_gcp_project_id>/topics/<auto_name>` 形式で生成。これをクリップボードにコピー。

RevenueCat の Play Console サービスクレデンシャルセットアップから 36 時間未満の場合、Google クレデンシャルのウォームアップを待つ必要あり — そうでないと Connect フローが信頼性低下。

### 9b. または GCP でトピックを手動作成

手動セットアップを好む場合の代替パス。[Google Cloud Console → Pub/Sub → Topics → Create topic](https://console.cloud.google.com/cloudpubsub/topicList)。

- Topic ID: 例 `play-billing-notifications`
- フルリソース名は: `projects/<your_gcp_project_id>/topics/play-billing-notifications`

### 9c. Pub/Sub API 未有効化なら有効化

Play サービスアカウントをホストする GCP プロジェクトで https://console.cloud.google.com/flows/enableapi?apiid=pubsub にアクセス → **Enable** クリック。

### 9d. IAM Publisher 権限付与

GCP Console → Pub/Sub → Topics → トピック名クリック → **Permissions** タブ → **Add Principal**。

| フィールド | 値 |
|---|---|
| New principals | `google-play-developer-notifications@system.gserviceaccount.com` |
| Role | **Pub/Sub Publisher** (`roles/pubsub.publisher`) |

**Save** クリック。

**落とし穴 — Domain Restricted Sharing:** GCP 組織が Domain Restricted Sharing 組織ポリシーを強制している場合、外部 `@system.gserviceaccount.com` プリンシパルの追加が block される可能性あり。必要に応じて組織ポリシーで `system.gserviceaccount.com` への例外を追加。症状: Add Principal フローが「principal does not match domain restriction」または類似メッセージを返す。

正典: [Android Developers — Get ready for Play Billing Library](https://developer.android.com/google/play/billing/getting-ready) (RTDN configuration セクション)。

## ステップ 10 — Play Console で RTDN 設定

Play Console → Skeinly アプリ → サイドバー **Monetize with Play → Monetization setup** → **Real-time developer notifications** までスクロール。

| フィールド | 値 |
|---|---|
| Enable real-time notifications | ✅ チェック |
| Topic name | ステップ 9 のフルトピック名をペースト: `projects/<gcp_project_id>/topics/<topic_name>` |
| Notification content | **Subscriptions, voided purchases, and all one-time products** (最大カバレッジ) |

**Send Test Message** クリックで接続を検証。

### テストメッセージ失敗時

よくある原因:
- トピック名のタイポ。GCP Console の文字列と完全一致を確認。
- IAM publisher 権限が伝播していない。5–10 分待って再試行。
- GCP プロジェクトで Pub/Sub API が有効化されていない。

テスト合格後 **Save changes** クリック。

## ステップ 11 — RevenueCat で検証

RevenueCat Dashboard → Skeinly Android アプリ設定 → テスト通知到達を示す **Last received** タイムスタンプを探す。

任意で RevenueCat の Purchase Tracking 設定で **Track new purchases from server-to-server notifications** を有効化 — クライアント SDK が取りこぼした購入を RevenueCat がキャッチできるように (ネットワーク問題、フロー途中のアンインストール/再インストール)。

正典: [RevenueCat — Google Server Notifications](https://www.revenuecat.com/docs/google-server-notifications)。

## RevenueCat プロダクト紐付け前の検証

Play Console で確認:

- [ ] Subscription Product `skeinly_pro` が存在。
- [ ] Base plan `pro-monthly` が存在、ステータス **Active**、**Use for deprecated billing methods** マーク済み。
- [ ] Base plan `pro-yearly` が存在、ステータス **Active**。
- [ ] 無料トライアルオファー `pro-monthly-trial` が存在、ステータス **Active**、7 日フェーズ、「Never had entitlement to this subscription」eligibility。
- [ ] 無料トライアルオファー `pro-yearly-trial` が存在、ステータス **Active**、同じ shape。
- [ ] Japanese (ja-JP) 翻訳が benefits に追加済み。
- [ ] アプリが Internal Testing (またはそれ以上) に公開済み。
- [ ] ライセンステスター 1 名以上登録 (US 1 名 + JP 1 名 推奨)。
- [ ] Pub/Sub トピック作成済み、`google-play-developer-notifications@system.gserviceaccount.com` に Publisher IAM 付与済み。
- [ ] Monetization setup で RTDN 設定済み、テストメッセージが成功を返す。
- [ ] RevenueCat ダッシュボードに「Last received」タイムスタンプ表示。

## よくある落とし穴

| # | 落とし穴 | 詳細 |
|---|---|---|
| 1 | Subscription Product ID は永久かつ再利用不可 | アーカイブ済み ID も再利用不可。慎重に入力。 |
| 2 | Base Plan ID はハイフン、Product ID はアンダースコア | `skeinly_pro` (product) vs `pro-monthly` (base plan)。混同 = Play Console reject。 |
| 3 | 1 base plan のみ「backwards compatible」可 | 月額をマーク。旧 SDK はマーク済みのみ参照。 |
| 4 | Benefits テキストに「無料トライアル」や具体的価格を書けない | Play ポリシー。トライアルと価格はペイウォール copy + StoreKit ダイアログ経由のみで表示。 |
| 5 | ライセンステスターはトラックに公開済みアプリが必要 | Internal Testing 最低。Draft アプリは購入を受け付けない。 |
| 6 | 無料トライアル対象資格のスコープ | 「Has never had entitlement to this subscription」= ユーザ 1 名につき両 base plan 通算 1 回。保護されている。 |
| 7 | GCP クレデンシャルは約 36 時間のウォームアップが必要 | RevenueCat の Play Console サービスクレデンシャルを Pub/Sub Connect ステップの十分前にセットアップ。 |
| 8 | Domain Restricted Sharing 組織ポリシーが IAM 付与を block する可能性 | 付与失敗時は `system.gserviceaccount.com` 例外を追加。 |
| 9 | 2023 年 2 月以降の RevenueCat 識別子フォーマット | `subscription_id:base_plan_id` コロン区切り。我々の場合: `skeinly_pro:pro-monthly` + `skeinly_pro:pro-yearly`。 |
| 10 | Play Console「charming」による JPY 丸め | 自動換算された円価格を確認、Play の丸めが意図と異なる場合は上書き。 |
| 11 | PBL バージョンは RevenueCat の責務、我々の責務ではない | RevenueCat KMP SDK を使用、PBL バージョン (現在 `purchases-android` 17.55.x 内蔵の 7.x) は我々のために管理されている。 |

## Phase 39 全体パイプライン内の位置

1. **App Store Connect セットアップ** ([iap-setup-app-store-connect.md](iap-setup-app-store-connect.md)) → 両プロダクト「Ready to Submit」
2. **Play Console セットアップ** (この runbook) → 両 base plan Active
3. **RevenueCat プロダクト登録** (次セッション) — Claude が RevenueCat MCP を使って:
   - iOS プロダクト `io.github.b150005.skeinly.pro.monthly` + `.yearly` をインポート
   - Android プロダクト `skeinly_pro:pro-monthly` + `skeinly_pro:pro-yearly` をインポート
   - 4 つ全てを既存の `$rc_monthly` / `$rc_annual` パッケージに紐付け
   - Skeinly Pro エンタイトルメント `entlaaca26b181` が 4 つ全てをカバーしていることを確認
4. **End-to-end smoke test** — ライセンステスター signed in 状態の Play Internal Testing ビルドでペイウォール開いて購入、エンタイトルメント付与と `revenuecat-webhook` 経由の `subscriptions` 行書き込みを確認。
