# Phase 39 Closed Beta — Sandbox Tester 設定手順

> English source: [docs/en/ops/beta-testing.md](../../en/ops/beta-testing.md)

## ゴール

closed beta テスター（[Phase 39 rubric](../../en/phase/phase-39-beta-rubric.md) 通り 5–10 名、ラウンドチャート作者 ≥1 + ja-JP テスター ≥1 含む）に **Pro 購入フローを実購入なしでフル体験**してもらう:
- Apple StoreKit / Google Play Billing ダイアログを実際に表示
- 実 receipt 検証
- 実 RevenueCat イベント
- 実 `subscriptions` テーブル書き込み

これは下記のテスター登録経路で実現:
- **Apple Sandbox tester**（App Store Connect）
- **Google Play License tester**（Play Console）

両方とも sandbox receipt を発行し、同じ RevenueCat project + 同じ `revenuecat-webhook` Edge Function を経由する。Sandbox vs Production の区別は `event.environment = "SANDBOX"` でログ上のみ識別される。

## なぜ `grant_alpha_pro` ではなくこちらか

migration 017 には `grant_alpha_pro(uid)` RPC があり、purchase フローを完全にバイパスして直接 `subscriptions` 行を sentinel `platform = 'alpha-grant'` で挿入できる。**機能はする**が、以下を test できない:
- Apple / Google paywall UX（購入ダイアログの UI バグを catch できない）
- RevenueCat receipt 検証
- Webhook 配信経路
- 更新 / 解約 / 返金サイクル

Sandbox 経由なら **全部** をテストできる。さらに sandbox の **加速時間**（月額: 1ヶ月 → 5 分、年額: 1年 → 1 時間）により、テスターが 1 セッション内で更新イベントを観測できる。

`grant_alpha_pro` は sandbox 設定が困難なテスター（企業管理デバイス等）や quick smoke test のためのフォールバックとして残す。両者は共存可能 — 同じ `user_id` に `platform = 'alpha-grant'` 行と `platform = 'ios' | 'android'` 行が両方あっても `is_pro(uid)` は両方で true になる。

## 前提条件（一度限りのセットアップ完了確認）

- [ ] Migration 023 (`upsert_subscription_from_webhook` RPC) が prod に適用済み。`mcp__supabase__list_migrations` で `phase_39_revenuecat_webhook_helper` が並んでいるか確認。
- [ ] `revenuecat-webhook` Edge Function がデプロイ済み（`supabase functions deploy revenuecat-webhook`）。
- [ ] `REVENUECAT_WEBHOOK_SECRET` が Supabase Edge Function secret に登録済み（[release-secrets.md EF-4](../release-secrets.md#ef-4-revenuecat_webhook_secret)）。
- [ ] RevenueCat Dashboard → Webhooks → Add Webhook 設定済み（同 EF-4 セクション参照）。「Send test event」が緑チェック。
- [ ] RevenueCat Dashboard → Project Settings → Apps → Skeinly iOS + Android 両方に Public SDK Key（`appl_...` / `goog_...`）が設定済み（[vendor-setup.md A0d-2 / A0d-3](../vendor-setup.md)）。
- [ ] iOS app が `REVENUECAT_API_KEY_IOS` GitHub secret 配線でビルド済み（closed beta 用 TestFlight ビルド）。
- [ ] Android app が `REVENUECAT_API_KEY_ANDROID` GitHub secret 配線でビルド済み（`gradle-play-publisher` 経由 Internal Testing track アップロード）。
- [ ] `RevenueCatAuthBridge` が production code に入っている（commit `e1088d1` 以降）。Skeinly auth 後に `Purchases.logIn(userId)` が発火することを保証。

## テスターごとのセットアップ（テスター人数分繰り返す）

各テスターから以下を確認:
- メールアドレス（sandbox 購入時に App Store / Google Play サインインに使うもの — Skeinly ログインのメールと別でも OK、プラットフォーム側の sandbox アカウント）
- iOS / Android / 両方どれか

### iOS — Apple Sandbox tester 登録

1. [App Store Connect](https://appstoreconnect.apple.com) → **ユーザーとアクセス** → **Sandbox** タブ → **テスター** → **+** で新規追加。
2. 入力:
   - **氏名**: 任意（テスターにのみ表示される）
   - **メール**: **既存の Apple ID と紐付いていない** 新しいアドレス。推奨: `tester-name+skeinly-sandbox@gmail.com`（Gmail の `+` トリック）または使い捨てメール。テスターが普段使っている Apple ID を実購入用に保ちたい場合は、別途 sandbox 専用の Apple ID が必要 — App Store Connect の sandbox tester メールは **世界中のあらゆる Apple ID と被ってはならない**。
   - **パスワード**: テスターが選ぶ（テスト中、iOS の設定でこの認証情報でサインイン）
   - **生年月日**: 18 歳以上の任意の日
   - **App Store の地域**: テスターが使うストアフロントに合わせる（ja-JP テスターは「日本」 — 価格表示が円になる）
3. 保存。Apple がアカウント確認メールを送るので、テスターにリンクをクリックしてもらう。
4. **テスターのデバイスセットアップ**（iPhone / iPad で一度きり）:
   - 設定 → App Store → Sandbox アカウント → 手順 2 の sandbox 認証情報でサインイン
   - **通常の Apple ID からはサインアウトしない** — sandbox アカウントは IAP テスト専用の別スロット
5. **購入フローテスト**（テスターが Skeinly TestFlight ビルドをインストール + 自分の Skeinly アカウントでサインイン後）:
   - Skeinly でペイウォールを開く
   - 「月額プラン」タップ
   - Apple StoreKit ダイアログに `[Sandbox]` ウォーターマークが価格欄に表示される
   - 「購読」 → sandbox Apple ID パスワード確認
   - **料金発生せず**。Pro が解放
6. **更新観測**（月額の場合 ~5 分待つ）:
   - ~5 分後、RevenueCat が `RENEWAL` webhook を発火
   - Supabase Dashboard → Database → `subscriptions` テーブルでテスターの `user_id` 行の `last_verified_at` が更新、`expires_at` が前進していることを確認

### Android — Google Play License tester 登録

1. [Play Console](https://play.google.com/console) → **設定** → **ライセンス テスト** → **ライセンス テスター** → テスターの Google アカウントメールを追加
2. **アカウントの設定** → **ライセンスの応答** を `RESPOND_NORMALLY` に設定（receipt 検証が RevenueCat 経由で正常に流れる。`LICENSED` / `NOT_LICENSED` は IAP 以外のライセンステスト用なのでここでは使わない）
3. 保存
4. **テスターのデバイスセットアップ**:
   - テスターは Android デバイスで **手順 1 で追加した同じ Google アカウント** にサインイン
   - そのアカウントは Skeinly の Internal Testing track にも追加されている必要あり（Play Console → テスト → 内部テスト → テスター → メールリスト）。そうでなければテストビルドをインストールできない
5. **購入フローテスト**:
   - テスターは Skeinly Internal Testing ビルドをインストール（手順 4 の後にメールで届く Play Store 内部テストリンク経由）
   - Skeinly に自分のアカウントでサインイン
   - ペイウォール → 「月額プラン」タップ
   - Google Play Billing ダイアログに `(test)` 注釈 + 黄色バナー「これはテスト購入です。料金は発生しません」
   - 「購読」タップ
   - **料金発生せず**。Pro が解放
6. **更新観測**（~5 分）: iOS と同じ — `subscriptions.expires_at` が RevenueCat `RENEWAL` イベントで前進

## ベータ期間中の smoke check

prod Supabase project に対して定期的に（または「Pro が解放されない」報告が来たら）:

### 特定テスターの webhook イベントが届いているか確認

```sql
-- <tester_user_id> を実際の auth.users.id UUID に置き換え
SELECT
  id,
  user_id,
  platform,
  product_id,
  status,
  environment,    -- ベータテスター行は 'sandbox'、実ユーザーは 'production'
  expires_at,
  last_verified_at,
  is_in_trial,
  auto_renew_status
FROM public.subscriptions
WHERE user_id = '<tester_user_id>'
ORDER BY last_verified_at DESC;
```

### 環境別の分析クエリ（production-only メトリクス）

`environment` カラム (migration 024) により sandbox dev-noise を除外した分析が可能:

```sql
-- 本番アクティブ Pro 購読総数（sandbox テスター除外）
SELECT COUNT(*) FROM public.subscriptions
WHERE environment = 'production'
  AND status IN ('active', 'in_grace_period')
  AND (expires_at IS NULL OR expires_at > NOW());

-- closed beta 中の sandbox テスター活動
SELECT user_id, platform, product_id, status, last_verified_at
FROM public.subscriptions
WHERE environment = 'sandbox'
ORDER BY last_verified_at DESC
LIMIT 50;
```

`idx_subscriptions_active_production` (migration 024) により GA 規模でも production-active 集計クエリが高速。

行が返らない → webhook が発火していない、または function に届いていない、または event の `app_user_id` が使えなかった。Edge Function ログ確認。

### 特定テスターのイベント Edge Function ログ

```bash
supabase functions logs revenuecat-webhook --follow
```

ログ出力でテスターの user UUID を grep — upsert 成功は `app_user_id` + `event_type` + `status` をログ出力。失敗は `rpc_error`。

mcp 経由でも:
```text
mcp__supabase__get_logs service=edge-function
```

### Apple sandbox + Play license-test の永続性確認

iOS: テスターの設定 → App Store → Sandbox アカウントが手順 2 の sandbox tester メールで表示されているか（長期未使用で silent サインアウトすることがある）。

Android: Play Console → 設定 → ライセンステストで該当メールが残っているか確認（稀だが意図せず削除される場合あり）。

## よくある問題

### 「Sandbox 購入したのに Pro 解放されない」

頻度順:

1. **テスターが購入時とは別の Skeinly アカウントでサインイン中**。`Purchases.logIn(userId)` が purchase ↔ Skeinly account を紐付けるので、テスターが user-A でサインイン → 購入 → ログアウト → user-B でサインインすると、Pro は user-A だけに付与される。確認: テスターが一貫して同じ Skeinly アカウントでサインインしているか。
2. **`RevenueCatAuthBridge` が起動前**。bridge は Application init + Koin DI 完了後に発火するので、ユーザーが deep link でペイウォールに直接到達した場合 `app_user_id` が anonymous の可能性。Edge Function ログで `anonymous_or_invalid_app_user_id_ignored` が出ていればこのケース。修正: テスターがアプリを強制終了 → 再起動 → サインイン → 1 秒待つ → ペイウォール。
3. **Webhook が発火していない**。RevenueCat dashboard → Webhooks → 該当 webhook クリック → **Logs** タブで配信試行を確認。原因候補: Webhook URL が古い（project-ref 違い）、Authorization header 不一致、RevenueCat 側のリトライ枯渇（sandbox では稀）。
4. **RPC 失敗**。Edge Function ログに `upsert RPC failed` + エラーメッセージ。最有力は CHECK 制約違反（status enum mis-mapped）。バグ報告 + ログを添付。

### 「Sandbox では成功、Phase 40 GA 後に実購入で失敗」

Sandbox / Production 違いは `event.environment` のみ。beta では動いて GA で動かない場合の疑い:
- Production-only の RevenueCat 設定（別 project? Production 専用 API key? — Skeinly は単一 project なのでこれは起こらない想定）
- App Store Connect / Play Console product ID の sandbox / production 不整合。RevenueCat が dashboard で "Unconfigured product" として surface する

### 「App Store Connect Sandbox tester に追加したのに Apple がサインインさせない」

Apple sandbox tester アカウントは作成時 **18 歳以上** が必須。修正: tester レコード編集 → 生年月日 → 2007 年以前に。Apple の地域によっては請求先住所等の追加フィールドも要求される — placeholder 値で OK、sandbox は検証しない。

## 終了基準

beta クローズの判定は [phase-39-beta-rubric.md](../../en/phase/phase-39-beta-rubric.md):
- ≥5 テスターが Phase 32+34+38 happy path 完走
- CRITICAL ゼロ
- HIGH ≤3
- ラウンドチャート作者 ≥1 名から Phase 35.2 優先度シグナル

Sandbox-driven Pro 購入フローは exit criteria に**明示的には**含まれないが、実テスターが体験することで以下のバグが surface する:
- ペイウォール UX（タップターゲット、アクセシビリティ、コピー）
- RevenueCat SDK エラーハンドリング（購入中ネットワーク途絶）
- サブスク状態 UI（成功 toast、期限切れの gating）

購入フローのバグはベータ中、同じ beta-bug template で severity タグ付きで報告。Phase 39.5 でバグレポート送信 UX が ship 済みなのでフローはアンブロック。

## Phase 40 GA 切替（ベータ終了後）

Phase 39 closed → Phase 40 GA launch 時:

- App Store Connect: tester アカウントを Sandbox testers に残してよい（cleanup 不要、production ユーザーは自分の実 Apple ID を使うので衝突しない）
- Play Console: License testers リストも残してよい（同様）
- RevenueCat dashboard: Webhook URL + secret は同じまま。再設定不要
- `subscriptions` テーブルには本番 `event.environment = "PRODUCTION"` イベントが流入し始める（継続中の internal testing からの sandbox イベントと併存）。両方とも同じテーブルに書き込まれるが、`environment` カラム (migration 024、Phase 39 prep でシップ済み) で各行が sandbox / production にマークされているので、分析クエリは `WHERE environment = 'production'` で sandbox dev-noise を除外可能。`idx_subscriptions_active_production` パーシャルインデックスにより GA 規模でもこの絞り込みが高速。
