# ADR-016 — Phase 41: Pro サブスク + 動的シンボルパック配信

> **ステータス**: Proposed (2026-05-04)
> **フェーズ**: 41 (Phase 39 ベータクローズ後 + Phase 40 GA リリース後)
> **関連**: ADR-005 (アカウント削除)、ADR-008 (構造化チャート データモデル)、ADR-009 (パラメトリックシンボル)、ADR-013 (コラボレーションコア)、ADR-014 (PR ワークフロー)
> **追跡**: F1 = Phase 41 (Pro サブスクリプション + 動的シンボル)。Phase 41.1 データスパイン (migrations 020 / 021 / 022 + Edge Function `request-pack-download`) は出荷済み — [.claude/CLAUDE.md](../../../.claude/CLAUDE.md) `### Completed` 参照。Phase 41.2 (composite catalog + entitlement resolver + downloaded pack store + Realtime) が §6 サブスライス計画に従い次。Migration 017 (`subscriptions` テーブル) は prod 適用済み。RevenueCat ベンダー設定は [docs/ja/vendor-setup.md](../vendor-setup.md) Phase A0d 参照。

> **2026-05-09 修正 — `verify-receipt` 削除**: 本 ADR 内の `verify-receipt` Edge Function 言及はすべて歴史的な記述。Agent team 協議の結果 (RevenueCat が Apple/Google レシート検証を server-side で完結している以上補完的役割が確立できない、クライアント呼び出し site が無い、defense-in-depth の追加価値が Apple `.p8` + Google SA キー custody surface 拡大コストを上回らない、post-beta で実 fail mode 観察してから判断する方が妥当)、2026-05-09 に削除しました。`subscriptions` テーブルの実 writer は `revenuecat-webhook` (Phase 39 prep, 2026-05-08; migration 023 の `upsert_subscription_from_webhook` SECURITY DEFINER RPC を `last_verified_at` ordering guard 付きで呼び出し)。アーキテクチャ契約 (service-role 書込, single writer, refund/cancellation を webhook 経由) は不変、関数 identity だけが変更。Apple App Store Server Notifications V2 + Google Play Real-Time Developer Notifications は Skeinly 自前 Edge Function ではなく RevenueCat に着地し、RevenueCat から `revenuecat-webhook` に fan-in する。CANCELED / REFUNDED 契約は `supabase/functions/revenuecat-webhook/mapping.ts` で保存。post-beta で独立 server-side validator (RevenueCat 障害時 fallback、audit trail 等) の実需要が surface した場合は別 ADR で「Phase H'」として起票する。

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
- **free / pro 両方とも private**。bucket-level public-read なし。
- 全 payload 読出は (free / pro 問わず) `request-pack-download` Edge Function (§3.3) 経由 — 関数内で entitlement 検証してから per-call で短 TTL signed URL を mint。free pack は entitlement check skip (auth 済みなら rate cap 範囲内で常に成功)、pro pack は `subscriptions` の有効行確認を追加。
- なぜ free 経路も Edge Function 経由にするか: (a) client のダウンロード経路が 1 本に統一され free→pro 昇格時の挙動差異がなくなる、(b) §7 のテレメトリが pack download 全件で構造化ログ取得可能になる、(c) §10 Q5 (refund revocation) と Q6 (rate limit) が単一 control plane に乗る、(d) public-read GET vs signed-URL POST の二経路化を回避できる(失敗ハンドリング divergence の元を断つ)。
- **Signed URL TTL: 5 分**。短 TTL は意図的設計 — client は受領後即 download + 永続化。返金後ユーザーは *次の* リクエストで 403 になる(Realtime push 経由で `subscriptions` 即無効化)、既発行 URL は最大 5 分の TTL までしか有効でない。version bump or TTL 経過時は同 Edge Function に再呼出。
- レイアウト: `<pack_id>/<version>/payload.json` + `<pack_id>/<version>/preview.png` (オプション、ペイウォール サムネ用)。

**RLS**:
- `symbol_packs` + `symbol_pack_locales`: 全員 SELECT 可 (ペイウォールが Pro パック メタデータ可視性を必要とする)。
- `user_symbol_pack_state`: 自レコード SELECT/INSERT/UPDATE のみ。DELETE policy なし (auth.users CASCADE のみ)。
- すべてのテーブルの INSERT/UPDATE/DELETE on `symbol_packs` + `_locales` は service-role のみ (admin-time content authoring)。

**Edge Function `request-pack-download`** (Deno runtime — 当初提案の Postgres SECURITY DEFINER RPC からの pivot、2026-05-06 41.1.1a で記録):
- 当初の RPC 案は実装不可: 現行 Supabase Postgres は `storage.create_signed_url(...)` ヘルパーを露出していない。代替案 (`pg_net` + Storage REST callback、`pgjwt` + vault に保管した署名鍵で手動 JWT 発行) は extension 依存と署名鍵 custody の security surface を追加し、entitlement gate には過剰。`verify-receipt` (Phase H) precedent と Supabase 推奨パターンに合わせ Edge Function に pivot。
- リクエスト: `POST /functions/v1/request-pack-download` + `Authorization: Bearer <user_jwt>` + body `{ "pack_id": "..." }`。
- レスポンス: 200 で `{ payload_url, payload_url_ttl, current_version, payload_size }` を返す。401 unauthenticated / 404 pack_not_found / 403 pro_entitlement_required / 429 rate_limited を区別して返す。
- 内部フロー: (1) `verify_jwt: true` で deploy 時に Supabase が JWT 強制、(2) per-user sliding-window rate limiter (10 calls / 60s per user_id、in-memory `Map<user_id, timestamps[]>`、§10 Q6 解決)、(3) `symbol_packs` 行 lookup → 404、(4) `tier='pro'` なら `subscriptions` の `status IN ('active','in_grace_period') AND (expires_at IS NULL OR expires_at > now())` 有効行確認 → 403、(5) Storage REST `POST /storage/v1/object/sign/symbol-packs/<payload_path>?expiresIn=300` を service-role key で叩いて 5 分 signed URL mint、(6) 構造化ログ `{event:"pack_download_signed", user_id, pack_id, version, tier, ts}` 出力 (`mcp__supabase__get_logs service=edge-function` で取得可)、(7) envelope 返却。
- 並行性: stateless。同一 user × 同一 pack の並列呼出は単に有効な signed URL を 2 本発行するだけ。rate limiter のみが共有状態 (per-instance、cold-start で reset、v1 容認)。
- **Pack-id error-message oracle**: 404 は `pack_id` を caller に echo (403 entitlement-failure と区別可)。**容認** — `symbol_packs` SELECT policy は open-read のため、RLS 上の追加情報漏洩はない。
- **Service-role key custody**: `supabase secrets set SUPABASE_SERVICE_ROLE_KEY=...` で Edge Function env に格納。client binary には絶対埋めない、ログ出力もしない。`Deno.env.get(...)` で request 時に読出。
- **Refund-revocation セマンティクス**: `subscriptions.status='refunded'` 書込が `verify-receipt` 経由で landed すると、当該 user の *次の* `request-pack-download` 呼出が 403 を返す(local `EntitlementResolver` キャッシュが Realtime push を消化する前であっても server-side で revoke される)。5-min signed-URL TTL が「返金直前に発行された URL」の残存アクセスを ≤5 分に bounded する。当初 RPC 設計の 1h TTL より細粒度 — §10 Q5 解決。

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
4. 各 stale-or-missing pack について `request-pack-download` Edge Function 呼出:
   - 200 success → 返却された 5-min signed URL から payload fetch
   - 403 pro_entitlement_required → 静かに skip (sub 加入時に Realtime push で entitlement 復活)
   - 429 rate_limited → exponential backoff、次回起動で再試行
   - 401 unauthenticated → 次セッションへ deferral (auth refresh 経路)
5. 新ペイロードを SQLDelight + ファイルシステムに永続化
6. `user_symbol_pack_state` UPSERT (downloaded_version)
7. テレメトリ用 `SymbolPackSyncResult` イベント発火

失敗モード: ネット エラー → 次回起動で再試行 (silent)、signed URL TTL 切れ midway → Edge Function 再呼出で fresh URL、version regression → Sentry warning + skip。free / pro 同経路で「sub 失効時の short-circuit が sync manager 構造に見えない」点が §3.1 の refund revocation 対称性 + §10 Q6 rate-limit 対称性の必要条件。

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
| **41.1** | 6 サブスライスに operational split: **41.1.0** (doc — §10 Q1+Q2 解決、2026-05-06 shipped) → **41.1.1a** (doc — §3 Edge Function pivot、§10 Q5+Q6 解決、2026-05-06 shipped) → **41.1.1b** (Migration 020 — テーブル + RLS + indexes、**RPC migration なし**、Edge Function は 41.1.5 で deploy) → **41.1.2** (Storage バケット `symbol-packs` 両 tier private、prod 作成は user-side gate) → **41.1.3** (Domain `SymbolPack` + `SymbolPackPayload` + `SymbolPackMapper`) → **41.1.4** (Gradle task `generateSymbolPackPayloads` + seed INSERT、prod upload は user-side gate) → **41.1.5** (Edge Function `request-pack-download` deploy)。バンドル `DefaultSymbolCatalog` 維持で v1 は重複 (オフライン フォールバック保証)。 | +15 (元 +10 から bump、Edge Function pure-helper coverage 分) | 0 |
| **41.2** | `CompositeSymbolCatalog` + `EntitlementResolver` + `DownloadedPackStore` (SQLDelight) + `SymbolPackSyncManager` + Realtime channel `subscriptions-<userId>` + `SubscriptionRepository.cachedActiveSubscription()` + Koin DI 配線 (CompositeSymbolCatalog を本番 SymbolCatalog として注入、DefaultSymbolCatalog は ctor 引数 `bundled` に降格) | +25 | 0 |
| **41.3** | `PaywallScreen` Compose + SwiftUI + `PaywallViewModel` + `RevenueCatService` expect/actual + 自動トリガー (CompositeSymbolCatalog.get() null → ペイウォール sheet) + Settings 入口 | +15 | 一部 (~12) |
| **41.4** | SymbolGalleryScreen ロックバッジ + `PackManagementScreen` Compose + SwiftUI + `PackManagementViewModel` + GitHub Issue template `symbol-request.yml` 作成 + 残り i18n | +10 | 残り (~13) |
| **41.5** | `EntitlementResolver` の Pro tier feature gate 利用パターン ドキュメント (将来の Phase 35.2 / PR 承認ゲート etc 用、no code) | — | — |

各サブスライスは独立 ship 可能 + 巻き戻し可能。41.5 は明示的に「Pro tier をいくつかの**既存機能**に適用する」のではなく「将来の機能が `EntitlementResolver` を使う基盤を文書化する」ものであり、実コードは伴わない。各機能を Pro tier 化するかどうかは、その機能ごとのプロダクト判断 (別 PR / 別 ADR で)。

### 6.1 §41.5 — `EntitlementResolver` 利用パターン (gate site vs call site)

**ルール**: Pro entitlement に基づいて挙動を変える機能は、`EntitlementResolver` を **gate site** (Free vs Pro の判定が実際に発生する domain layer の表面 — catalog / use case / repository / coordinator) に注入する。**call site** (UI 層 — Composable / SwiftUI view / ViewModel) は `EntitlementResolver` を注入してはならず、`isPro()` を直接呼んではならない。call site は gate site が公開する Pro-policy-agnostic な interface (nullable 戻り値 / sealed `Available | Locked` / `_paywallRequests: Channel<Unit>` 等) のみを消費する。

**理由**: (1) feature ごとに `isPro()` 呼出が 1 箇所に集約される (ポリシー変更が 1 ファイルで完結)、(2) UI 層が Pro-policy-agnostic になる (UI tests / screenshot tests / Maestro flows がポリシー変更で壊れない)、(3) Fake で gate-site interface を差し替えれば `EntitlementResolver` / `SubscriptionRepository` / `AuthRepository` の plumbing 抜きで ViewModel + UI tests が書ける。

**Pro-affording UX 例外 (forward-looking)**: 「Pro 状態をユーザーに反映する」のが主目的の call site (Settings の "Pro 加入日" 表示行 / 将来の "サブスクリプション管理" 画面 / "あなたは既に Pro です" の paywall 分岐等) は `isPro()` を直接読んでも §41.5.1 違反にならない。判別: 「機能を表示/非表示」「アクションを enable/disable」「ロックバッジ vs 完全シンボル描画」は **gating** で gate-site 経由。「ユーザーに現状を伝える」「購入後 routing を決める」は **affording** で UI 直読 OK。**Phase 41.5 時点で本例外に該当する consumer は存在しない** — 将来の affording surface のための forward-looking carve-out。なお `PackManagementViewModel` の直接注入 (下表) は本例外に該当しない (`PackStatus.Locked` 導出は per-row download action を gating しているため、affording ではなく gating)。

**現状 consumer (Phase 41.5 時点)**:

| Consumer | Layer | `EntitlementResolver` 利用 | パターン適合 |
|---|---|---|---|
| `CompositeSymbolCatalog` | Domain (gate site) | resolver 注入。`get()` / `listByCategory()` / `all()` で `isPro()` を呼び Pro symbol gating (未 entitle 時は null / Pro entry 除外)。`listLockedPro()` は `isPro()` で「Pro user → 空リスト」分岐 — UI に「どの Pro symbol をロックバッジ表示するか」を伝える **observability surface** であり、access を制限する gating 呼出ではない (返したシンボルは UI が表示するために surface している) | ✅ 適合 gate site |
| `DefaultSymbolPackCatalog` | Domain (gate site) | resolver 注入。`listInventory()` で `isPro()` を per-call 1 回スナップショットし、未 entitle 時の Pro pack に `PackStatus.Locked` を導出。`CompositeSymbolCatalog` の sibling — pack はメタデータレベルの関心、symbol は描画 hot path で別 cadence。§41.5.6 cleanup で `PackManagementViewModel` deviation を解消する際に追加 | ✅ 適合 gate site |
| `ChartEditorViewModel` | UI (call site, gate-delegated) | `symbolCatalog.listLockedPro(category)` 経由で palette badge 描画。`EntitlementResolver` 注入なし、`isPro()` 直接呼出なし | ✅ 適合 call site (gate を `CompositeSymbolCatalog` に委譲) |
| `PackManagementViewModel` | UI (call site, gate-delegated) | `SymbolPackCatalog.listInventory()` を消費し、解決済みの `PackRow` リストを state に転送。`EntitlementResolver` 注入なし、`isPro()` 直接呼出なし。(§41.5.6 cleanup 前: resolver を直接注入し `PackStatus.Locked` を inline で導出 — §41.5.3 が当初記録した deviation。§41.5.6 で解消) | ✅ 適合 call site (gate を `DefaultSymbolPackCatalog` に委譲) |
| `PaywallViewModel` | UI (現状 consumer ではない) | `EntitlementResolver` 注入なし、`isPro()` 直接呼出なし。購入後 routing は `RestoreResult.Success.proActive` + `PurchaseResult.Success` を `RevenueCatService` から読む。KDoc 内で `EntitlementResolver` に言及するのみ | n/a (consumer 関係なし) |

§41.5.6 cleanup により、コードベースの **適合 gate site は 2 つ** (`CompositeSymbolCatalog`, `DefaultSymbolPackCatalog`)、**適合 call site は 2 つ** (`ChartEditorViewModel`, `PackManagementViewModel`)、**未解決の §41.5.1 deviation はゼロ**。当初の deviation 記録は §41.5.5 で「間違った layer に Pro logic を入れた既存コードを retire する worked example」として保存。

**将来の consumer (例)**: Phase 35.2 polar editing → `PolarEditorAvailability` が `EntitlementResolver` 注入、tri-state `Free | Pro | LockedPro` を expose、editor toolbar は tri-state を消費。post-v1 PR 承認ゲート → `PullRequestApprovalPolicy` が gating 対象、`PullRequestDetailViewModel` は `isPro()` 呼出なし。

**新 Pro feature ランディング時のチェックリスト** (詳細は EN §41.5.4): (1) gate site を特定 (ViewModel / Composable に Pro logic が入りそうなら domain layer に押し出す)、(2) gate site のみに `EntitlementResolver` 注入、(3) Pro-policy-agnostic interface を expose (`Boolean isPro` を caller に漏らさない)、(4) gate-site test で 4 分岐カバー (Pro / 非 Pro / 未認証 / feature-specific edge)、(5) call-site reaction を Fake gate で test、(6) paywall trigger は 41.3b `_paywallRequests: Channel<Unit>(BUFFERED)` precedent に従う、(7) 新 gate site を ADR-016 §41.5.3 amendment か feature 自身の ADR に記録。

**§41.5 のスコープ外**: 既存機能を Pro 化するかの product 判断 (別 ADR / PRD)、`EntitlementResolver` の API 変更 (将来 Pro Lite / Pro Plus 等を導入する場合は別 amendment)、「間違った layer に Pro logic を入れた既存コード」の migration discipline (Phase 41.5 ドキュメント時点で該当 site が 1 つ存在 — `PackManagementViewModel` が `EntitlementResolver` 直接注入で `PackStatus.Locked` を inline 導出 — §41.5.6 cleanup で sibling `SymbolPackCatalog` interface を導入して解消。§41.5.6 が worked example。将来 code-review で追加違反が surface したら同じ shape で inline 対処、汎用 migration plan は不要)。

#### §41.5.6 Update: `PackManagementViewModel` deviation 解消

Phase 41.5 ドキュメント時点で唯一既知だった §41.5.1 deviation (`PackManagementViewModel` が `EntitlementResolver` を直接注入し `readSnapshot()` で `PackStatus.Locked` を inline 導出) を、§41.5 ドキュメント直後の follow-up slice で retire。§41.5.3 reference table は cleanup 後の状態を反映済。

**変更の shape**: `domain/symbol/` に sibling interface `SymbolPackCatalog` を新設 (`suspend fun listInventory(): PackInventory`)。`PackRow` + `PackStatus` を `ui.packmanagement` から `domain.symbol` に移動 (Kotlin/Native ObjC bridge は package 非依存で simple class name を export するため iOS Swift 側の参照は無変更)。`DefaultSymbolPackCatalog` 実装が `LocalSymbolPackDataSource` + `EntitlementResolver` を注入し、status fold (Locked / NotDownloaded / UpdateAvailable / Downloaded) + 順序 (FREE → PRO 各昇順) + total bytes 計算をすべて gate 側で完結。`isPro()` を per-call 1 回スナップショットして 1 回の `listInventory()` 呼出内では coherent な gate view を返す。`PackManagementViewModel` は `SymbolPackCatalog` のみを注入する Pro-policy-agnostic な consumer になり、`load` / `refresh` は `catalog.listInventory()` を呼んで state に転送するだけのシン wrapper に。dead state だった `isProEntitled` フィールドも同 cleanup で削除。

**sibling interface にした理由**: `SymbolCatalog` は palette + cell draw の render hot path consumer で `get(id)` がマイクロ秒スケールのシンクロナス lookup。inventory は per-screen-load の suspending な metadata mirror 読込で cadence が違う。1 つの bloated `SymbolCatalog` に統合すると render hot path が pack metadata を触る誘惑が生まれる。

**テスト再編**: gate-fold tests は ViewModel から `DefaultSymbolPackCatalogTest` (12 ケース、real SQLDelight in-memory driver + `EntitlementResolver` test fakes) に移動。`PackManagementViewModelTest` は state machine の関心 (load + refresh + error + in-flight guard + clear-error) に narrow し、`FakeSymbolPackCatalog` で canned `PackInventory` を返す shape に。Pro-gate logic は所有層でテストされる。

**前方互換**: Phase 41.6+ で ADR-016 §5.2 「Free up storage」 affordance / per-pack download dispatch が landing する際は `SymbolPackCatalog` を拡張 (または §41.5.3 forward-looking entry の `PackDownloadCoordinator` に昇格)。いずれの場合も gate 決定は domain layer に残り、ViewModel は user intent forward + 解決済 state 描画のみ。§41.5.4 checklist は変更なし。

**完了**: 本 update により未解決の §41.5.1 deviation はゼロ。今後 Pro-gating コードが call site に landing したら rule 違反 (sanctioned exception ではない) — reviewer は §41.5.1 + §41.5.6 worked example を pointer に PR を reject。

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

   **Clock-manipulation バイパス窓** (関連): `EntitlementResolver.isPro()` は `sub.expiresAt > clock.now()` を端末ローカル時計で評価。オフライン状態で時計を巻き戻すと `isPro() = true` を期限後も維持できる。境界条件: (a) Realtime push が再接続で server 真実を復元、(b) `request-pack-download` Edge Function は server 側 `now()` で subscriptions を再検証 → 新規 pack DL は失敗。**容認** — バイパスが延長するのは「既ダウンロード済」Pro pack ファイルへのアクセスのみ、entitlement-gated server リソースへの新規アクセスは得られない。launch 後テレメトリで abuse シグナルが出たら緩和策: (i) クライアントに「最終 verified at」を保持し N 日 (例 14 日) を超えたら online 再検証必須化、(ii) server 発行 JWT で entitlement 状態を表現し短い TTL を持たせる。
6. **クライアント側パック署名検証なし (v1)** — Supabase Storage HTTPS + signed URL 契約に依存。Supabase compromise → 全クライアントへ悪意ある SVG 注入の理論リスクあり。緩和: SymbolDrawing.kt は `M`/`L`/`C`/`Z` トークンしか解釈しない (script 実行なし)。**Strict-token contract**: SymbolDrawing.kt は `{M, L, C, Z}` 以外のトークンを**reject** + ログ + フォールバック "?" グリフに置換する **— silent ignore してはならない**。SVG parser を将来拡張する際 (例 `A` arc support 追加) はこの不変条件を再検証する。将来 Ed25519 署名スキーム (バンドル公開鍵) で upstream-payload-trust ギャップを閉じる。
7. **GitHub Issue 要望フローはパブリック リポに紐づく** — プライバシー懸念ユーザーは要望を出しにくい。ADR-015 §4 のプライバシーポリシーで GitHub Issues がベータ期間中の連絡経路と明記されている。

## 9. スコープカット (post-v1、明示的に F1 MVP に含めない)

検討した上で意図的に除外: アプリ内シンボル要望フォーム / Pack 署名スキーム / Pack-level 割引・promo / Family Sharing / Tiered Pro plans (Pro Lite / Plus) / Per-symbol IAP / シンボル パック タギング・検索 / ペイウォール 価格 A/B / Web/desktop クライアント / Pack analytics for content authors / Sub 開始時の prefetch / Local pack expiry / Pack rollback (新 version 公開で対応)。

## 10. 未解決事項 (Phase 41.1 着手前に答えるべきもの)

1. **Storage retention** — **2026-05-06 (41.1.0) 解決**: 全 historical version 無期限保存。`symbol_packs.payload_size` 列 (bytes) で `SELECT SUM(payload_size)` により bucket 総量を安価に監視可能。v1 想定カーディナリティ (≤10 pack × ≤10 version × ≤1 MB ≈ 100 MB) では storage cost 無視可能。**再評価トリガー**: `SUM(payload_size) > 100 MB` を初めて越えた時点で、prune ポリシーを ADR amendment として cut する (最新版 + 直近 N 件 supersede を保持、古いものは cold storage 退避が candidate)。それまで client / server どちらにも prune ロジックは入らない。
2. **`subscriptions` Realtime チャネル多重化** — **41.2 へ defer (スコープ確定)**: 現状 7 チャネル (5 baseline + Phase 38 で 2 追加)。`subscriptions-<userId>` を独立 8 番目とするか `patterns-<userId>` と統合するかは、41.2 wiring 時点の channel 数 vs. tier 上限を見たエンジニアリング判断。41.1 データスパイン側はどちらでも schema / RPC 変更不要 (中立)。41.2 実装者が決定し ADR amendment か commit message に記録する。
3. **i18n キー数**: ~25 推定。Phase 38.3 / 38.4 の precedent (14 / 10) より多めだが、ペイウォール + パック管理併せて 30-35 に膨らむ可能性。41.4 で精緻化。
4. **トライアル期間 UX 開示**: 7 日間無料トライアルは IAP product 設定済。App Store JA review はトライアル明示開示を求めることが多い → submission 直前に Apple JA App Review との copy 確認必要。`body_paywall_trial_disclosure` キー追加かもしれない。
5. **返金ハンドリング** — **2026-05-06 (41.1.1a) Path A pivot で解決**: Apple/Google 経由の返金 → RevenueCat webhook → verify-receipt Edge Function で `status='refunded'` 書込 → Realtime push → クライアント `EntitlementResolver.isPro() = false` → `CompositeSymbolCatalog` が即座に Pro パックロック → 既存チャートの Pro シンボル参照は "?" フォールバック。**サーバ側 revocation** は `request-pack-download` (§3.3) が呼出毎に `subscriptions` を再確認することで強制 — 5-min signed-URL TTL が「返金直前に発行された URL」の残存アクセスを ≤5 分に bounded する。Apple App Review は entitlement 取り消し許可 (well-trodden pattern)。

6. **rate-limit** — **2026-05-06 (41.1.1a) Path A pivot で解決**: 当初の `request_pack_download` Postgres RPC 案は per-caller rate cap を持たなかった。`request-pack-download` Edge Function (§3.3) への architecture pivot で rate limiter を関数本体に直接組込 — sliding window 10 calls / 60s per `user_id`、`Map<user_id, timestamps[]>` を request 時に評価、超過時 429。in-memory limiter は cold-start で reset (v1 alpha + closed beta スケールで容認、subscriber 数が増えたら Upstash Redis or Postgres `edge_function_rate_limit` テーブルに移行)。(原始 concern 報告: 2026-05-04 security review)
