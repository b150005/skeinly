# IAP セットアップ — App Store Connect (Skeinly Pro)

> Source of truth (English): [docs/en/ops/iap-setup-app-store-connect.md](../../en/ops/iap-setup-app-store-connect.md)

## ゴール

Skeinly Pro 自動更新サブスクリプションを Apple 側で end-to-end 設定:

- **`io.github.b150005.skeinly.pro.monthly`** — 月額 $3.99
- **`io.github.b150005.skeinly.pro.yearly`** — 年額 $24.99
- 両方に **7 日間無料トライアル** 導入オファー
- **App Store Server Notifications V2** webhook を RevenueCat に接続
- **Sandbox テスター** を登録してクローズドベータテスターが実課金なしで購入できるように

この runbook 完了後、次セッションで [RevenueCat 側](../adr/016-phase-41-revenuecat-subscription.md) (RevenueCat MCP 経由のプロダクト ↔ パッケージ紐付け) へ。

Android 側: [iap-setup-play-console.md](iap-setup-play-console.md)。

## 前提条件

- App Store Connect で Account Holder / Admin / App Manager 権限保持。
- Skeinly アプリレコードが App Store Connect に存在 (Bundle ID `io.github.b150005.skeinly`)。
- RevenueCat プロジェクトに iOS アプリが **Apps & providers** 配下で登録済み。
- Apple Developer In-App Purchase 契約 + Paid Apps 契約が両方アクティブ (Agreements, Tax, and Banking でステータス「Active」、「Action Required」ではないこと)。両契約がアクティブでない場合、サブスクリプションを審査に提出できません。

## 確定済みの重要な決定事項

| 決定事項 | 値 | 根拠 |
|---|---|---|
| 価格 | 月額 $3.99 + 年額 $24.99 | ADR-016 §50 |
| 無料トライアル期間 | 7 日 | ADR-016 §50 |
| Subscription Group | 1 グループ、両プロダクト同レベル | 月額 ↔ 年額 のクロスグレードは次回更新日に有効化。両プロダクトとも同じ Pro アクセスを付与 (同一 RevenueCat エンタイトルメント `entlaaca26b181`) |
| 通知バージョン | **V2 (推奨)** | RevenueCat docs が自動価格変更検出のために V2 を明示推奨。V1 はまだ valid だが新規連携では V2 を選択 |
| 価格設定基準リージョン | **United States (USD)** | 他 174 ストアフロント分は Apple が自動換算、テリトリー単位の手動上書きも可能 |
| 年額プロダクトの課金形態 | **「1 年間前払い」** — 「12 か月契約の月額プラン」ではない | 後者は iOS 26.4+ / SDK 26.5+ 限定。Skeinly の deployment target は iOS 17.0 のため、26.4 ゲートのオプションを選ぶと大多数のユーザを年額プラン購入対象から除外することになる。「1 年間前払い」は業界標準形、既存「Save 40%+ vs monthly」マーケティングナラティブ ($24.99 一括 vs 12×$3.99=$47.88) と整合、knitter-craft の前払いサブスクリプション慣習に合致、Play Console の base-plan モデルとの parity 維持にも必要。2026-05-13 に Agent Team 協議で確定。 |

## 操作順序

1. Subscription Group 作成
2. Subscription Group のローカライズ (EN + JA) 追加
3. 月額プロダクト作成 (Reference Name, Product ID, Duration, Price, Localizations)
4. 年額プロダクト作成 (同上)
5. Subscription Group の順序編集 — 両プロダクトを同レベルに配置
6. 月額プロダクトに 7 日間無料トライアル導入オファー追加
7. 年額プロダクトに 7 日間無料トライアル導入オファー追加
8. App Store Server Notifications V2 URL 設定 (Production + Sandbox)
9. Users and Access → Sandbox で Sandbox テスター登録

ステップ 3–7 は相互に独立。ステップ 8–9 は全体から独立。

---

## ステップ 1 — Subscription Group 作成

App Store Connect → Skeinly アプリ → サイドバー **Monetization → Subscriptions** → Subscription Groups リスト先頭の **+**。

- **Reference Name:** `Skeinly Pro` (内部用、ユーザに非表示)

**Create** クリック。

正典: [Apple — Offer auto-renewable subscriptions](https://developer.apple.com/help/app-store-connect/manage-subscriptions/offer-auto-renewable-subscriptions/)。

## ステップ 2 — Subscription Group のローカライズ

新しい `Skeinly Pro` グループ内で、**App Store Localizations** までスクロール。グループ表示名は iOS の「サブスクリプションを管理」画面でプロダクトリストの上にヘッダーとして表示されます。

**English (U.S.)** 追加:
- Subscription Group Display Name: `Skeinly Pro`

**Japanese (Japan)** 追加:
- Subscription Group Display Name: `Skeinly Pro`

(両ロケールで「Skeinly Pro」は固有名詞として同一表記。GA 前に technical-writer がカタカナ表記の必要性を確認。)

## ステップ 3 — 月額プロダクト作成

グループ内で **Create** (または **+**)。

| フィールド | 値 | 注意 |
|---|---|---|
| Reference Name | `Skeinly Pro Monthly` | 内部用 |
| Product ID | `io.github.b150005.skeinly.pro.monthly` | **永久変更不可。保存前に 3 重チェック。** |

**Create** クリック後、プロダクト詳細ページで:

- **Subscription Duration:** 1 Month → **Save**
- **Subscription Prices:**
  - **Add Subscription Price** → **United States** → **$3.99** → **Next**
  - 他 174 ストアフロントへの自動生成価格を確認。日本 (JPY) は四捨五入された円価格 (典型的に ¥600 台) を Apple が提示。特に上書きする理由がなければ受け入れる — 日本は丸めた円が慣習。
  - **Confirm** クリック。
- **Availability:** 全テリトリー選択を確認 (現段階で Skeinly はグローバル配信)。

### 月額プロダクトのローカライズ

プロダクト詳細ページの **App Store Localizations** までスクロール。

**English (U.S.)** 追加:
- Display Name: `Skeinly Pro Monthly` (上限 30 字、19 字で収まる)
- Description: `Unlock all Pro features. Auto-renews monthly.` (45 字 — 上限は言語によらず 55 字)

**Japanese (Japan)** 追加:
- Display Name: `Skeinly Pro 月額プラン` (15 字)
- Description: `Skeinly Pro の全機能を解放。毎月自動更新。` (22 字)

**Save** クリック。

> **Description 55 字制限:** Apple の Description フィールドはロケールあたり 55 字のハード上限。EN でも JA でも同じ。55 字未満で余裕を持って収める — soft warning は出ず保存時に reject されます。

正典: [Apple — Manage pricing for auto-renewable subscriptions](https://developer.apple.com/help/app-store-connect/manage-subscriptions/manage-pricing-for-auto-renewable-subscriptions/)。

## ステップ 4 — 年額プロダクト作成

ステップ 3 と同手順、値だけ差し替え:

| フィールド | 値 |
|---|---|
| Reference Name | `Skeinly Pro Yearly` |
| Product ID | `io.github.b150005.skeinly.pro.yearly` |
| Subscription Duration | **1 Year** |
| Base Price (USD) | **$24.99** |
| EN Display Name | `Skeinly Pro Yearly` (18 字、上限 30 字) |
| EN Description | `Unlock all Pro features. Auto-renews yearly. Save 40%+.` (55 字 — 上限ちょうど) |
| JA Display Name | `Skeinly Pro 年額プラン` (15 字) |
| JA Description | `Skeinly Pro の全機能を解放。毎年自動更新、40% お得。` (30 字) |

日本 (JPY) は ¥3,600–¥4,000 帯を Apple が提示。特に理由がなければ受け入れる。

## ステップ 5 — Subscription Level 割り当て

`Skeinly Pro` グループ内で → プロダクトリスト先頭の **Edit Order**。

`Skeinly Pro Monthly` と `Skeinly Pro Yearly` の両方が表示されているはず。**両方を Level 1 (同レベル)** に配置。**Save** クリック。

**同レベルにする理由**: 月額と年額は同じ Pro アクセスを付与する (同じエンタイトルメント)。年額を上位レベルに置くとより多くのコンテンツがアンロックされる印象になり、Skeinly の設計とは異なります。同レベルのプロダクトは **クロスグレード** として扱われ、両者の切替は次回更新日に有効化され、日割り計算は Apple が処理。

## ステップ 6 — 7 日間無料トライアル導入オファー設定 (月額)

`Skeinly Pro Monthly` プロダクト詳細ページ → **Subscription Prices** セクション → **View all Subscription pricing** → **Set up Introductory Offer**。

| フィールド | 値 |
|---|---|
| Countries or regions | 全て選択 (プロダクトの提供地域に合わせる) |
| Start date | 当日 (または空欄) |
| End date | **空欄** — 期限なしの恒久オファー |
| Offer Type | **Free Trial** (「Pay as You Go」では NO — それは割引継続課金で無料ではない) |
| Duration | **1 Week** (= 7 日) — Apple の duration ピッカーは選択パネルに依存して「1 Week」または「1 or 2 Weeks」と表示。保存前に確認画面でちょうど 7 日になっていることを確認。 |
| Price | $0.00 / ¥0 (Free Trial タイプは自動) |

**Confirm** クリック。

### 重要な制約

**導入オファーは作成後に編集不可。** 期間やタイプを変更したい場合は **削除** して新規作成する必要があります。両方とも同じ「View all Subscription pricing」画面からアクセス可能。

### グループ単位の対象資格 (操作不要 — Apple が自動施行)

Apple が「お客様 1 名につき Subscription Group 1 つあたり導入オファー 1 回」を自動施行。月額プランで無料トライアルを利用したユーザは年額プランの無料トライアル **対象外** (逆も同じ)。これは望ましい挙動 — 二重トライアルコストを防御。オペレータ操作は不要。

正典: [Apple — Set up introductory offers for auto-renewable subscriptions](https://developer.apple.com/help/app-store-connect/manage-subscriptions/set-up-introductory-offers-for-auto-renewable-subscriptions/)。

## ステップ 7 — 7 日間無料トライアル導入オファー設定 (年額)

`Skeinly Pro Yearly` に対してステップ 6 を同じ値で繰り返し。

## ステップ 8 — App Store Server Notifications V2

### 8a. RevenueCat の webhook URL を取得

RevenueCat Dashboard → **Apps & providers** → Skeinly iOS アプリをクリック → **Apple Server to Server notification settings** までスクロール → **Apple Server Notification URL** 配下の URL をコピー。

URL はプロジェクト固有 (RevenueCat プロジェクトトークンが含まれる)。汎用 public テンプレートは存在しない — 必ずダッシュボードから読み取る。

### 8b. App Store Connect で URL 設定

App Store Connect → Skeinly アプリ → サイドバー **General → App Information** → **App Store Server Notifications** までスクロール。

**Production Server URL:**
- Production Server URL 配下の **Set Up URL** クリック。
- RevenueCat URL をペースト。
- Notification Version: **Version 2**。
- **Save** クリック。

**Sandbox Server URL:**
- Sandbox Server URL 配下の **Set Up URL** クリック。
- **同じ RevenueCat URL** をペースト。
- Notification Version: **Version 2**。
- **Save** クリック。

両 URL を明示的に設定することで (sandbox を production に自動ルーティングさせるのではなく)、App Store Connect 上で構成が可視化され自己記述的になります。

### 8c. 自動化オプション (任意)

RevenueCat の「Apple Server to Server notification settings」パネルには **Apply in App Store Connect** ボタンがあり、OAuth 経由で App Store Connect の両 URL を直接設定します。自動化を好む場合はこちら。上記手動手順も同等に valid で、自動化フローでトラブルがあった場合の bypass 経路として有用。

正典:
- [Apple — Enter server URLs for App Store Server Notifications](https://developer.apple.com/help/app-store-connect/configure-in-app-purchase-settings/enter-server-urls-for-app-store-server-notifications/)
- [RevenueCat — Apple App Store Server Notifications](https://www.revenuecat.com/docs/platform-resources/server-notifications/apple-server-notifications)

## ステップ 9 — Sandbox テスター登録

App Store Connect → **Users and Access** (App Store Connect ホームから、アプリ内ではない) → 上部ナビゲーションの **Sandbox** タブ。

**+** クリック (最初のテスターの場合は **Create Test Accounts**)。各テスターについて:

| フィールド | 注意 |
|---|---|
| First Name / Last Name | 任意値。作成後は編集不可。 |
| Email Address | **過去に実 Apple ID として** または App Store 購入で使用されたことが **絶対にない** こと。新しいアドレスを使用。プラスサブアドレス (`yourbase+jp@gmail.com`) で 1 つの受信箱から複数のテスターを backup 可能。作成後は編集不可。 |
| Password | あなたが設定。Apple の強パスワード要件を満たす必要あり。作成後は編集不可。 |
| App Store Country or Region | 初期ストアフロント。**このフィールドは後から編集可能** — US ↔ Japan ストアフロント切替に使える。 |

**Create** クリック。

### Sandbox テスターを実機で使用

iOS テストデバイス: 設定 → [Apple ID] → **メディアと購入** → **サインアウト**。最上部 Apple ID から **iCloud をサインアウトしないこと**。アプリが購入を開始したとき、StoreKit ダイアログで Apple ID 入力プロンプトが表示される — そこで sandbox 認証情報を入力。

正典: [Apple — Create a Sandbox Apple Account](https://developer.apple.com/help/app-store-connect/test-in-app-purchases/create-a-sandbox-apple-account/)。

## RevenueCat 側 handoff 前の検証

App Store Connect で確認:

- [ ] Subscription Group `Skeinly Pro` が EN + JA ローカライズ付きで存在。
- [ ] `io.github.b150005.skeinly.pro.monthly` が存在、ステータス「Ready to Submit」以上、1 Month 期間、基本価格 $3.99 USD、EN + JA ローカライズ。
- [ ] `io.github.b150005.skeinly.pro.yearly` が存在、ステータス「Ready to Submit」以上、1 Year 期間、基本価格 $24.99 USD、EN + JA ローカライズ。
- [ ] 両プロダクトがグループ順序で Level 1。
- [ ] 両プロダクトに 7 日間無料トライアル導入オファー設定済み。
- [ ] Production Server URL が Version 2 で設定済み。
- [ ] Sandbox Server URL が Version 2 で設定済み。
- [ ] Sandbox テスター 1 名以上登録 (クローズドベータでは US 1 名 + JP 1 名 推奨)。

メタデータ伝播に約 1 時間 — 新規作成プロダクトは StoreKit sandbox で即座には available にならない。

## よくある落とし穴

| # | 落とし穴 | 詳細 |
|---|---|---|
| 1 | Product ID は永久 | 作成後変更不可。3 重チェック。 |
| 2 | Subscription duration はアプリ審査後変更不可 | 提出前に正しく選択 (1 Month / 1 Year)。 |
| 3 | 導入オファーは編集不可 | 削除して再作成のみ。 |
| 4 | グループ単位の導入オファー対象資格 | お客様 1 名につきグループ 1 つで 1 回 — 両プロダクトでカウント共有。無料トライアルコスト保護。 |
| 5 | Notification Version は V2 で | V1 もまだ動作するが RevenueCat は V2 推奨。新規連携は常に V2。 |
| 6 | RevenueCat URL はプロジェクト固有 | 汎用テンプレートなし。毎回ダッシュボードからコピー。 |
| 7 | Sandbox テスターのメールアドレスは未使用必須 | プラスサブアドレスのトリックで複数の実受信箱不要。 |
| 8 | メタデータ伝播遅延 | プロダクト作成/編集後 sandbox で見えるまで 約 1 時間。 |
| 9 | Paid Apps 契約がアクティブである必要 | そうでなければサブスクリプションを審査に提出できない。 |
| 10 | Apple ID サインインフロー | iOS デバイスで「メディアと購入」をサインアウト — **iCloud ではない** — sandbox テスト前。 |
| 11 | Description 字数上限 | ASC UI は現状ロケールあたり **55 字** を施行 (2026-05-13 オペレータ実測確認)。Apple 公式 docs ([promoting-in-app-purchases](https://developer.apple.com/app-store/promoting-in-app-purchases/) + [help/app-store-connect/reference/in-app-purchase-information](https://developer.apple.com/help/app-store-connect/reference/in-app-purchase-information/)) は依然として **45 字** と記載 — Apple が docs を更新せずに cap を引き上げたケース。どちらの limit でも safe に収めるなら 45 字以内。本 runbook の現行 copy は 55 字向けに最適化 (EN yearly がちょうど 55)。Display Name は 30 字上限。soft warning なし — 上限超過は保存時に reject。 |
| 12 | Description content compliance | Apple の Description フィールドへの content 要件は「各オファリングのメリットを明確に区別する」ことのみ — feature 詳細列挙 + 価格 / 自動更新 / 無料トライアル開示の義務は **in-app sign-up screen (paywall)** にあり Description field ではない ([App Store Review Guidelines §3.1.2(c)](https://developer.apple.com/app-store/review/guidelines/) + [Apple Subscriptions page](https://developer.apple.com/app-store/subscriptions/) 参照)。「all Pro features」「全機能を解放」+ savings claim スタイルは compliant。比較 savings claim (「Save 40%+」「40% お得」) は Subscriptions ページ「Billing amount」節で明示的に許可 (purchase flow 内で total billing amount より subordinate に表示する条件)。 |

## Phase 39 全体パイプライン内の位置

この runbook は RevenueCat プロダクト ↔ パッケージ紐付けステップの前に完了する 2 つのストア side のうちの 1 つ。順序:

1. **App Store Connect セットアップ** (この runbook) → ステータス「Ready to Submit」
2. **Play Console セットアップ** ([iap-setup-play-console.md](iap-setup-play-console.md)) → 両 base plan Active
3. **RevenueCat プロダクト登録** (次セッション) — Claude が RevenueCat MCP を使って:
   - iOS プロダクト `io.github.b150005.skeinly.pro.monthly` + `.yearly` をインポート
   - Android プロダクト `skeinly_pro:pro-monthly` + `skeinly_pro:pro-yearly` をインポート
   - 4 つ全てを既存の `$rc_monthly` / `$rc_annual` パッケージに紐付け
   - Skeinly Pro エンタイトルメント `entlaaca26b181` が 4 つ全てをカバーしていることを確認
4. **End-to-end smoke test** — sandbox テスター signed in 状態の TestFlight ビルドでペイウォール開いて購入、エンタイトルメント付与と `revenuecat-webhook` 経由の `subscriptions` 行書き込みを確認。
