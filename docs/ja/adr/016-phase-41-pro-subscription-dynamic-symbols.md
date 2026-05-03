# ADR-016 — Phase 41: Pro サブスク + 動的シンボルパック配信

> **ステータス**: Proposed (2026-05-04)
> **フェーズ**: 41 (Phase 39 ベータクローズ後 + Phase 40 GA リリース後)
> **関連**: ADR-005 (アカウント削除)、ADR-008 (構造化チャート データモデル)、ADR-009 (パラメトリックシンボル)、ADR-013 (コラボレーションコア)、ADR-014 (PR ワークフロー)
> **追跡**: F1 in [.claude/docs/active-backlog.md](../../../.claude/docs/active-backlog.md)。Migration 017 (`subscriptions` テーブル) は Phase 39 alpha で配置済み。RevenueCat ベンダー設定は [docs/ja/vendor-setup.md](../vendor-setup.md) Phase A0d 参照。

英語版 (canonical): [../../en/adr/016-phase-41-pro-subscription-dynamic-symbols.md](../../en/adr/016-phase-41-pro-subscription-dynamic-symbols.md)

## 1. 背景

Skeinly の構造化チャートビジョンは**コンパイル時バンドル済シンボルカタログ** ([DefaultSymbolCatalog](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/catalog/DefaultSymbolCatalog.kt)) に依存しており、現在は 35 のかぎ針 + 30+ の棒針 JIS シンボルを内蔵している。プロダクトが持続可能なマネタイズへ向かう中で、2 つの非柔軟性が表面化している:

**問題 1 — シンボルカタログが Store リリース間で凍結される。** グリフのタイポ、`jis.crochet.reverse-sc` の chevron 形状の改善、または編み手コミュニティから要望されるシンボル追加は、すべて Store リリースサイクルを必要とする。CLAUDE.md の Tech Debt にすでに 4 つの保留シンボル系 (`fsc` / `fdc` / `exsc` / spike / `turning-ch-N`) がユーザー需要シグナル待ちで列挙されているが、新シンボル追加が**数時間ではなく数週間**かかる現状ではシグナル蓄積自体が困難。

**問題 2 — マネタイズ基盤がない。** Phase 37 / 38 / 35.2 (今後) は重量級のクロスプラットフォーム機能群を構成する。マネタイズ層がないと、すべての機能を無料で配布することになり、運用コスト (Supabase Edge Function, Realtime チャネル枠, ストレージ) と機能広さのバランスが取れない。Agent team の 2026-04-30 合議で**サブスクモデルで高度機能をゲート**、最初のレバレッジポイントを**シンボルカタログ**とすることに決まった (編み手のクラフトレベルとシンボルレベルが強く相関 + Drops Design 等の業界実績あり)。

**Phase 39 alpha で 2 つのベンダー柱は準備済**: Migration 017 (`subscriptions` テーブル + `verify-receipt` Edge Function service-role 書込) + RevenueCat (vendor-setup.md A0d、Public iOS/Android SDK Key 登録済)。

**F1 として未着手の部分**: `symbol_packs` + `user_symbol_pack_state` テーブル、Supabase Storage バケット、クライアント `CompositeSymbolCatalog`、`EntitlementResolver`、ペイウォール画面、パック管理画面、ギャラリー連携、テレメトリ。

## 2. 決定事項 (高レベル)

1. **シンボルパックは一級エンティティ** として Supabase Postgres (`symbol_packs`) に格納。マニフェスト + version + tier (`free`/`pro`) + 署名期限を保持。ペイロード (SVG path data) は **Supabase Storage バケット**に `<pack_id>/<version>/payload.json` として配置。**外部 CMS は採用しない** — ベンダー面を最小化、既存 RLS で entitlement スコープ可、agent team が `apply_migration` + `execute_sql` で直接コンテンツ作成可。

2. **Pack tier ゲートはランタイム** で `EntitlementResolver` 経由。ダウンロード時ではなく、`CompositeSymbolCatalog.get(id)` の毎回。サブ失効後もパック ファイルはディスクに残す (再加入時の再 DL コスト回避)。失効ユーザーが Pro シンボルを参照したチャートを開くと、レンダリング時に "?" フォールバック。**保存済チャートは無効化されない** — シンボル参照は安定 ID 契約により pack 失効を生き延びる。

3. **無料パックも同じ配信インフラ** を使う。tier='free' は entitlement gate が「常に開く」設定なだけ。これにより**無料パックのグリフ修正が Store update 不要で配布可能** — Tech Debt の "Future opportunistic crochet catalog additions" の Store リリースサイクル ボトルネックが解消。

4. **マニフェスト駆動同期モデル**。アプリ起動時 (バックグラウンド、メイン UI が interactive になった後): `GET /symbol_packs/manifest` (RLS で entitlement されたパックのみ) → ローカル `user_symbol_pack_state` キャッシュと diff → 失効/欠落パックを Storage signed URL でダウンロード。**起動を絶対にブロックしない**。失敗は静か (次回起動で再試行、Sentry 報告)。

5. **`ChartCell.symbolId` は安定契約**。Pack version bump はレンダリング (path data, parameter slot のデフォルト) を改善するが、ID は変えない。意味を破壊する変更は新 ID (`jis.knit.cable.6st.v2` 等)、旧 ID は deprecation alias として永続。

6. **ユーザー シンボル要望は GitHub Issues 経由**。`.github/ISSUE_TEMPLATE/symbol-request.yml` (knit/crochet セレクタ + JIS 参照 URL + 用途 + サンプルチャート スクショ) を新設。Agent team がトリアージし、Supabase 直接書込でパック更新、Issue クローズ。**v1 にアプリ内要望フォームは作らない** (modal infra + Edge Function リレーがコスト過大、GitHub URL prefill で十分)。

7. **Pro tier の初期スコープ**: 中級 + 上級シンボルパック (おおまかに 無料 ~50 / Pro 中級 ~30 / Pro 上級 ~20 シンボル)。将来の Pro-only 機能ゲート (Phase 35.2 polar 編集、PR 承認ゲート等) も同じ `EntitlementResolver` を使う。**初期価格**: $3.99/月 + $24.99/年 (年額 ~48% 割引)、7 日間無料トライアル — App Store Connect IAP 商品として Phase A0b-3 で登録済。

8. **オフライン耐性**: 初回起動でネットワークなし → コンパイル時バンドル `DefaultSymbolCatalog` のみ表示 (現行と同じ 35 + 30+ JIS シンボル)。Pack manifest が UI をブロックすることはない。2 回目以降は キャッシュ済 merged catalog を即座に表示し、バックグラウンドで refresh。

## 3. スキーマ

### 3.1 主要テーブル + Storage

| テーブル | 役割 |
|---|---|
| `symbol_packs` | id (pack 名), tier (free/pro), version (monotonic int), display_name, description, payload_path (Storage パス), payload_size, symbol_count, signed_until, タイムスタンプ |
| `symbol_pack_locales` | (pack_id, locale) 複合 PK; ロケール固有の display_name + description |
| `user_symbol_pack_state` | (user_id, pack_id) 複合 PK; downloaded_version, downloaded_at, last_accessed_at — クライアントローカル キャッシュ ミラー |

**Storage バケット** `symbol-packs`:
- 無料パック (`tier='free'`) は public-read。
- Pro パックは `request_pack_download` RPC が**ユーザー単位で 1 時間 TTL の signed URL** を発行 (RPC が entitlement を検証してから URL 生成)。
- レイアウト: `<pack_id>/<version>/payload.json` + `<pack_id>/<version>/preview.png` (オプション、ペイウォール サムネ用)。

**RLS**:
- `symbol_packs` + `symbol_pack_locales`: 全員 SELECT 可 (ペイウォールが Pro パック メタデータ可視性を必要とする)。
- `user_symbol_pack_state`: 自レコード SELECT/INSERT/UPDATE のみ。DELETE policy なし (auth.users CASCADE のみ)。
- すべてのテーブルの INSERT/UPDATE/DELETE on `symbol_packs` + `_locales` は service-role のみ (admin-time content authoring)。

**RPC `request_pack_download(p_pack_id)`** (SECURITY DEFINER):
- `auth.uid()` 取得 → 未認証で `RAISE`。
- `tier='pro'` パックは `subscriptions` を見て `status IN ('active', 'in_grace_period')` AND (`expires_at IS NULL OR expires_at > now()`) を確認。entitlement なしで `RAISE EXCEPTION 'Pro entitlement required for pack: %'`。
- `storage.create_signed_url(bucket_id => 'symbol-packs', object_path => v_pack.payload_path, expires_in => 3600)` で 1h signed URL 生成。
- 戻り値: `payload_url`, `payload_url_ttl`, `current_version`, `payload_size` の `TABLE`。
- 冪等性: 同一 user + 同一 pack の並列呼び出しは 2 つの有効な signed URL を返すだけ (FOR UPDATE 不要)。
- **Pack-id error-message oracle**: `RAISE EXCEPTION 'Pack not found: %', p_pack_id` は `p_pack_id` を呼び出し元に戻す → 未認証 prober が pack id 列挙可能。**容認** — `symbol_packs` SELECT policy は open-read (全 pack id はペイウォール プレビュー メタデータとして public 可読) のため、RLS 上の追加情報漏洩はない。

**RLS 書込み default-deny ノート**: `symbol_packs` + `symbol_pack_locales` には INSERT/UPDATE/DELETE policy を**意図的に作らない** — RLS enabled + permissive policy 不在 = service-role bypass のみが書込み経路。Migration SQL に明示コメント `-- DEFAULT-DENY: service-role only — DO NOT add a permissive write policy` を入れる (将来の agent team メンバーが誤って permissive policy を追加するのを防ぐ documentation hygiene)。

### 3.2 ペイロード形式

```json
{
  "pack_id": "jis.knit.intermediate",
  "version": 7,
  "schema_version": 1,
  "symbols": [
    { "id": "jis.knit.cable.6st", "category": "KNIT", "tier": "pro",
      "path_data": "M0,0 L8,0 ...", "fill": false,
      "width_units": 6, "height_units": 1,
      "parameter_slots": [],
      "ja_label": "6目交差", "en_label": "6-stitch cable" }
  ]
}
```

`schema_version` は将来の SymbolDefinition フィールド追加に対する forward-compat (古いクライアントは未知フィールドを無視)。Breaking change が必要なら新 `pack_id` を切る (シンボル ID の安定契約と同じ)。

## 4. クライアント アーキテクチャ

### 4.1 `CompositeSymbolCatalog`

実質的に「DefaultSymbolCatalog (compiled-in) + DownloadedPackStore (SQLDelight) + EntitlementResolver」を merge する SymbolCatalog 実装。

`get(id)` の優先順位:
1. ダウンロード済パックを検索 (新しい version が勝つ)。
2. ヒットしたら、その pack が `tier='pro'` で `!entitlementResolver.isPro()` → `null` 返却 (renderer は "?" フォールバック)。
3. ヒットしなければ bundled (DefaultSymbolCatalog) にフォールスルー。

**重要不変条件**:
- `bundled` はオフライン フォールバック。初回起動 + ネット無し ユーザーが今日見ているものと同一。
- Pro gate は **毎回の `get()` 呼出** で実行 — 安価 (`EntitlementResolver.isPro()` は同期キャッシュ読出)。
- パックは sub 失効でも削除しない。再加入で即座にロック解除 (再 DL なし)。

### 4.2 `EntitlementResolver`

- `SubscriptionRepository.cachedActiveSubscription()` から同期読出 (SQLDelight)。
- `status IN ('active', 'in_grace_period') && (expires_at IS NULL || expires_at > now())` を判定。
- キャッシュは Realtime チャネル `subscriptions-<userId>` で温まる (verify-receipt Edge Function が写経 → push)。
- **コールドスタート + ネット無し + キャッシュ空 = `isPro() = false`** (オフライン デフォルト deny)。代替案 (オフライン デフォルト allow) は失効ユーザーが永遠にオフラインで Pro 維持できる悪用パスを開くため不採用。

### 4.3 同期フロー

アプリ起動時、メイン UI が interactive になった後にバックグラウンド実行:

1. `SymbolPackSyncManager.sync()`
2. `GET symbol_packs` (RLS open-read)
3. ローカル キャッシュと per-pack version 比較
4. 各 stale-or-missing pack について:
   - `tier='free'` → public-read storage URL から直接 fetch
   - `tier='pro'` → `request_pack_download` RPC → signed URL fetch。`'Pro entitlement required'` を `RAISE` されたら静かに skip (sub 加入時に再開)
5. 新ペイロードを SQLDelight + ファイルシステムに永続化
6. `user_symbol_pack_state` UPSERT (downloaded_version)
7. テレメトリ用 `SymbolPackSyncResult` イベント発火

失敗モード: ネット エラー → 次回起動で再試行 (silent), 403 signed URL → 再 mint, version regression → Sentry warning + skip。

## 5. UX

### 5.1 ペイウォール トリガー
- ユーザーが SymbolGalleryScreen で Pro シンボルをタップ (プレビュー常時可視、locked シンボル タップで購入導線)
- 編集パレットの "高度シンボルを見る" CTA タップ
- Pro シンボル含むパターン/チャート共有を受信 → "?" プレースホルダから ペイウォールへリンク

### 5.2 パック管理画面 (Settings → シンボルパック)
- 全 entitled パックを表示: ダウンロード状況 + サイズ + シンボル数
- Pro パックは entitlement バッジ (active/expired/never)
- "ストレージ解放" affordance: `downloaded_version=0` 書込でローカル削除、次回同期で再 DL
- "シンボル要望" 入口 → GitHub Issues prefill URL

### 5.3 ギャラリー統合
SymbolGalleryScreen は Pro シンボル に小さなロックバッジを表示 (`!entitlementResolver.isPro()` 時のみ)。タップでペイウォール。chart-editor.md に記載の E2E load-bearing testTags は変更なし。

### 5.4 i18n キー (~25 推定)
title_pack_management / title_paywall / label_pack_size_kb・mb / label_pack_symbol_count (parametric) / label_pack_version_x (parametric) / label_pack_status_{downloaded,update_available,not_downloaded,locked} / action_{download_pack,update_pack,subscribe_monthly,_yearly,restore_purchase,request_symbol,manage_downloads,free_up_storage} / body_paywall_{pitch,legal,trial_disclosure} / label_subscription_active_until (parametric) / label_subscription_expired / body_pack_locked_inline / body_offline_pro_locked / dialog_free_up_storage_title・_body。JA は Pro 表記 + Apple JA 更新通知文言テンプレに従って意味的に divergence する箇所あり。

## 6. サブスライス計画

| Slice | 内容 | テスト delta | i18n |
|---|---|---|---|
| **41.0** | この ADR (本書、no code) | — | — |
| **41.1** | Migration 020 (`symbol_packs` + `_locales` + `user_symbol_pack_state` + RLS + indexes) + Migration 021 (`request_pack_download` RPC) + Storage バケット `symbol-packs` プロビジョニング + 既存 35+30+ JIS シンボルを `jis.knit.beginner` + `jis.crochet.beginner` (free) パックに移行 (バンドル維持で v1 は重複; オフライン フォールバックを保証) | +10 | 0 |
| **41.2** | `CompositeSymbolCatalog` + `EntitlementResolver` + `DownloadedPackStore` (SQLDelight) + `SymbolPackSyncManager` + Realtime channel `subscriptions-<userId>` + `SubscriptionRepository.cachedActiveSubscription()` + Koin DI 配線 (CompositeSymbolCatalog を本番 SymbolCatalog として注入、DefaultSymbolCatalog は ctor 引数 `bundled` に降格) | +25 | 0 |
| **41.3** | `PaywallScreen` Compose + SwiftUI + `PaywallViewModel` + `RevenueCatService` expect/actual + 自動トリガー (CompositeSymbolCatalog.get() null → ペイウォール sheet) + Settings 入口 | +15 | 一部 (~12) |
| **41.4** | SymbolGalleryScreen ロックバッジ + `PackManagementScreen` Compose + SwiftUI + `PackManagementViewModel` + GitHub Issue template `symbol-request.yml` 作成 + 残り i18n | +10 | 残り (~13) |
| **41.5** | `EntitlementResolver` の Pro tier feature gate 利用パターン ドキュメント (将来の Phase 35.2 / PR 承認ゲート etc 用、no code) | — | — |

各サブスライスは独立 ship 可能 + 巻き戻し可能。41.5 は明示的に「Pro tier をいくつかの**既存機能**に適用する」のではなく「将来の機能が `EntitlementResolver` を使う基盤を文書化する」ものであり、実コードは伴わない。各機能を Pro tier 化するかどうかは、その機能ごとのプロダクト判断 (別 PR / 別 ADR で)。

## 7. テレメトリ / 観測性

PostHog `ClickAction` (ADR-015 §6 の taxonomy 拡張): `RequestPackDownload(pack_id)` / `OpenPaywall(trigger)` / `PurchaseSubscription(product_id)` / `RestorePurchases()` / `RequestSymbol()` / `FreeUpStorage(pack_id)`。

`AnalyticsEvent.Outcome` (typed): `PackDownloaded(pack_id, version, ms)` / `PackSyncFailed(pack_id, reason)` / `PaywallConverted(product_id, trigger)` / `PaywallDismissed(trigger, reason)`。

ダッシュボード:
- Pro 転換ファネル: paywall opens → trigger 別購入数。
- パック人気度: PackDownloaded by pack_id。
- シンボル要望率: RequestSymbol/週 → agent team トリアージ ケイデンス シグナル。

Sentry が `PackSyncFailed` の reason + `RevenueCatService` 例外を追跡。

## 8. ネガティブ コンセクエンス

1. **Supabase 無料枠ストレージ プレッシャー** — 30 packs × ~50 KB = 初日 1.5 MB。バージョン bump で蓄積。1 GB 上限まで余裕大 (~50 packs / 18 ヶ月、~3 versions/pack 想定で 7.5 MB)。500 MB 超えたら旧 version を cold storage に移すか cull。
2. **クライアント側キャッシュ サイズ** — ~1.5 MB。iOS/Android sandbox quota に対して微小。Pack management UI で "ストレージ解放" を提供。
3. **編集中の同期競合** — パック sync 中にユーザーが編集を開く → パレットは bundled シンボルのみ (sync 完了で gallery 自動更新)。
4. **Pro パック フォールバック "?" グリフ** — Pro user が作ったチャートを free user が見ると Pro シンボルが "?"。**現行 v1 と比較して悪化はない** (現行も pack 外シンボル ID は単純に未表示)。新 UX は ?→ペイウォール リンクで unlock パスを提示する点で純粋に改善。
5. **オフライン デフォルト deny** — 失効ユーザーがフライト中に Pro パックがロックされる。トレードオフ既出。

   **Clock-manipulation バイパス窓** (関連): `EntitlementResolver.isPro()` は `sub.expiresAt > clock.now()` を端末ローカル時計で評価。オフライン状態で時計を巻き戻すと `isPro() = true` を期限後も維持できる。境界条件: (a) Realtime push が再接続で server 真実を復元、(b) `request_pack_download` RPC は Postgres `now()` (server 時計) で再検証 → 新規 pack DL は失敗。**容認** — バイパスが延長するのは「既ダウンロード済」Pro pack ファイルへのアクセスのみ、entitlement-gated server リソースへの新規アクセスは得られない。launch 後テレメトリで abuse シグナルが出たら緩和策: (i) クライアントに「最終 verified at」を保持し N 日 (例 14 日) を超えたら online 再検証必須化、(ii) server 発行 JWT で entitlement 状態を表現し短い TTL を持たせる。
6. **クライアント側パック署名検証なし (v1)** — Supabase Storage HTTPS + signed URL 契約に依存。Supabase compromise → 全クライアントへ悪意ある SVG 注入の理論リスクあり。緩和: SymbolDrawing.kt は `M`/`L`/`C`/`Z` トークンしか解釈しない (script 実行なし)。**Strict-token contract**: SymbolDrawing.kt は `{M, L, C, Z}` 以外のトークンを**reject** + ログ + フォールバック "?" グリフに置換する **— silent ignore してはならない**。SVG parser を将来拡張する際 (例 `A` arc support 追加) はこの不変条件を再検証する。将来 Ed25519 署名スキーム (バンドル公開鍵) で upstream-payload-trust ギャップを閉じる。
7. **GitHub Issue 要望フローはパブリック リポに紐づく** — プライバシー懸念ユーザーは要望を出しにくい。ADR-015 §4 のプライバシーポリシーで GitHub Issues がベータ期間中の連絡経路と明記されている。

## 9. スコープカット (post-v1、明示的に F1 MVP に含めない)

検討した上で意図的に除外: アプリ内シンボル要望フォーム / Pack 署名スキーム / Pack-level 割引・promo / Family Sharing / Tiered Pro plans (Pro Lite / Plus) / Per-symbol IAP / シンボル パック タギング・検索 / ペイウォール 価格 A/B / Web/desktop クライアント / Pack analytics for content authors / Sub 開始時の prefetch / Local pack expiry / Pack rollback (新 version 公開で対応)。

## 10. 未解決事項 (Phase 41.1 着手前に答えるべきもの)

1. **Storage retention**: 全 historical version 永続 vs. older-than-N prune。デフォルト全保存 (audit + cold-cache 用途) → 100 MB 超で再評価。
2. **`subscriptions` Realtime チャネル多重化**: 現状 7 チャネル (5 baseline + Phase 38 で 2 追加)。`subscriptions-<userId>` を別チャネルと collapse して free tier 接続上限を稼ぐか? `patterns-<userId>` と統合候補。41.2 実装時に判断。
3. **i18n キー数**: ~25 推定。Phase 38.3 / 38.4 の precedent (14 / 10) より多めだが、ペイウォール + パック管理併せて 30-35 に膨らむ可能性。41.4 で精緻化。
4. **トライアル期間 UX 開示**: 7 日間無料トライアルは IAP product 設定済。App Store JA review はトライアル明示開示を求めることが多い → submission 直前に Apple JA App Review との copy 確認必要。`body_paywall_trial_disclosure` キー追加かもしれない。
5. **返金ハンドリング**: Apple/Google 経由の返金 → RevenueCat webhook → verify-receipt Edge Function で `status='refunded'` 書込 → Realtime push → クライアント `EntitlementResolver.isPro() = false` → CompositeSymbolCatalog が即座に Pro パックロック → 既存チャートの Pro シンボル参照は "?" フォールバック。Apple ガイドライン上 entitlement 取り消しは許可されている (App Review が refund-on-revoke を受け入れる)。

6. **`request_pack_download` RPC の rate-limit**: RPC は per-caller rate cap を持たない。subscriber が呼び出しをスクリプト化すれば数千の有効 signed URL (各 1 時間 TTL) を生成可能。Supabase Storage CDN cache が実際のバイト コストを吸収するが、Postgres RPC compute は無制限。Phase 41 GA 前に (a) Supabase Edge Function を RPC の前段に配置し per-user req/min 制限、または (b) Postgres advisory-lock + sliding-window check を RPC 内に追加。ADR ブロッカーではなく、41.1 実装 slice で対応。(2026-05-04 security review 指摘事項)
