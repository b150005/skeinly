# Spec — シンボルパック配信

> 英語原典: [docs/en/spec/symbol-pack-delivery.md](../../en/spec/symbol-pack-delivery.md)
>
> **目的**: 編み物シンボルコンテンツがアプリにどう配信されているかの現状ビュー。「今どうなっているか」を記述、「なぜ」は [ADR-016](../../en/adr/016-phase-41-pro-subscription-dynamic-symbols.md)。
>
> **対象**: シンボルカタログ拡張、新規 Pro パック追加、DL 失敗デバッグ、新エンタイトルメントソース配線をする agent or 貢献者。
>
> **範囲外**: パック内シンボルの作画規約 ([ADR-009](../../en/adr/009-parametric-symbols.md) parametric symbols + `symbol-review/` シリーズ)、サブスク課金 (RevenueCat / `subscriptions` テーブル — ADR-016 §3.3)、編図エディタのパレット UX。

## 一文メンタルモデル

シンボルパックは **versioned なシンボル定義のバンドル** (パックあたり ~30–50 シンボル) を private な Supabase Storage に 1 ファイル `payload.json` として保存する。アプリの Postgres ミラーテーブルは **どのパックが存在するかの目次** であり、シンボル本体は持たない。Pro 層パックは追加でエンタイトルメントチェックを通る。

## 現状

### 3 層データレイアウト

```
┌─ Postgres (Supabase) ────────────────────────────────────┐
│  symbol_packs                                            │  メタデータカタログ (manifest)
│   ↳ id / tier / version / display_name / description     │  RLS: open SELECT
│   ↳ payload_path / payload_size / symbol_count           │  Write: service-role のみ
│  symbol_pack_locales (pack_id × locale)                  │
│   ↳ locale 別の display_name / description                │
│  user_symbol_pack_state (user × pack)                    │  RLS: own-row のみ
│   ↳ downloaded_version / last_accessed_at                │  ユーザーがディスクに持つ
│                                                          │  ものをサーバー側ミラー
└──────────────────────────────────────────────────────────┘
                  │ Postgres はメタデータのみ。
                  │ シンボル本体は Storage に。
                  ▼
┌─ Storage bucket `symbol-packs` (private, 1MB/file cap) ──┐
│  jis.knit.beginner/1/payload.json    ← 35 シンボル ~13 KB │
│  jis.crochet.beginner/1/payload.json ← 35 シンボル ~20 KB │
│  <future-pack>/<version>/payload.json                    │
│  <future-pack>/<version>/preview.png  (オプション)        │
└──────────────────────────────────────────────────────────┘
                  │ クライアント直接読み込み path なし。
                  │ Storage REST `/object/sign/…` が
                  │ per-call signed URL を発行 (5 分 TTL)。
                  ▼
┌─ Edge Function `request-pack-download` ──────────────────┐
│  • user JWT を検証 (verify_jwt: true)                    │
│  • per-user sliding rate-limit (10 req / 60s)            │
│  • pack 行 lookup、tier='pro' なら subscription gate     │
│  • Storage REST 経由で 5 分 signed URL 発行              │
│  • {payload_url, ttl, version, size} 返却                │
└──────────────────────────────────────────────────────────┘
                  │
                  ▼
┌─ KMP クライアント (SQLDelight) ──────────────────────────┐
│  SymbolPackEntity            ← カタログミラー             │
│  DownloadedPackPayloadEntity ← payload.json 本体キャッシュ│
└──────────────────────────────────────────────────────────┘
```

### ファイルマップ

#### Postgres (Supabase)

| Artifact | 役割 |
|---|---|
| Migration [020_symbol_packs.sql](../../../supabase/migrations/020_symbol_packs.sql) | `symbol_packs` + `symbol_pack_locales` + `user_symbol_pack_state` を RLS 付きで作成 (カタログ open-read、ユーザー状態 own-row) |
| Migration [021_symbol_packs_bucket.sql](../../../supabase/migrations/021_symbol_packs_bucket.sql) | private Storage bucket を `file_size_limit = 1 MiB` + `allowed_mime_types = [application/json, image/png]` で provisioning |
| Migration [022_seed_symbol_pack_metadata.sql](../../../supabase/migrations/022_seed_symbol_pack_metadata.sql) | 2 個の free 層パック (`jis.knit.beginner` + `jis.crochet.beginner`) + ja-locale 行を seed |

#### Edge Function

| Artifact | 役割 |
|---|---|
| [supabase/functions/request-pack-download/index.ts](../../../supabase/functions/request-pack-download/index.ts) | handler: JWT verify → rate-limit → pack lookup → entitlement check → signed URL 発行 |
| [supabase/functions/request-pack-download/rate-limit.ts](../../../supabase/functions/request-pack-download/rate-limit.ts) | in-memory sliding window (10 req / 60s per user)。cold start で reset、クローズドベータスケールで許容 |

#### クライアント (KMP shared)

| Artifact | 役割 |
|---|---|
| [shared/.../sqldelight/.../SymbolPack.sq](../../../shared/src/commonMain/sqldelight/io/github/b150005/skeinly/db/SymbolPack.sq) | `symbol_packs` のローカルミラー |
| [shared/.../sqldelight/.../DownloadedPackPayload.sq](../../../shared/src/commonMain/sqldelight/io/github/b150005/skeinly/db/DownloadedPackPayload.sq) | デコード済 `payload.json` 本体のローカルキャッシュ、(pack_id, version) をキー |
| [shared/.../data/local/LocalSymbolPackDataSource.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/local/LocalSymbolPackDataSource.kt) | 両テーブルを 1 リポジトリ surface に。トランザクション付き manifest replace + payload upsert |
| [shared/.../data/remote/RemoteSymbolPackDataSource.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/remote/RemoteSymbolPackDataSource.kt) | `fetchManifest()` (supabase-kt postgrest)、`requestDownload(packId)` (supabase-kt functions plugin → signed URL fetch → payload decode) |
| [shared/.../data/sync/SymbolPackSyncManager.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/sync/SymbolPackSyncManager.kt) | 1 sync サイクルを統括: manifest fetch → ローカル diff → stale-or-missing パック DL。mutex で直列化 |
| [shared/.../domain/symbol/CompositeSymbolCatalog.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/CompositeSymbolCatalog.kt) | render 時 `SymbolCatalog` — DL 済パックを bundle compile-time catalog 上にオーバーレイ。Pro エントリは [EntitlementResolver](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/EntitlementResolver.kt) が gate |
| [shared/.../domain/model/SymbolPackPayload.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/model/SymbolPackPayload.kt) | `payload.json` の wire format。`schema_version` forward-compat 契約 |
| [shared/.../ui/packmanagement/](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/packmanagement/) | パック管理画面 (paywall preview, DL 済パック, 「ストレージを空ける」) |
| [shared/.../tools/SymbolPackPayloadGenerator.kt](../../../shared/src/androidHostTest/kotlin/io/github/b150005/skeinly/tools/SymbolPackPayloadGeneratorTest.kt) | build-time ジェネレータ: bundle compile-time catalog から `payload.json` を生成。常時 invariant として動き、`skeinly.payloads.outputDir` system property セット時のみファイルを emit (`generateSymbolPackPayloads` Gradle タスク参照) |

### データフロー — コールド起動 (auth 済ユーザー)

1. アプリ起動で `CompositeSymbolCatalog` を配線。コンストラクタが `applicationScope` 上で fire-and-forget `refresh()` を 1 回スケジュール
2. `SymbolPackSyncManager.sync()` が発火 (呼び出し側 — 典型的にはフォアグラウンドフック + 購入後 RevenueCat callback in Phase 41.3)
3. `RemoteSymbolPackDataSource.fetchManifest()` → SELECT `symbol_packs` → `List<SymbolPack>` 返却 (メタデータのみ)
4. `LocalSymbolPackDataSource.replaceManifest(packs)` が `SymbolPackEntity` に各パック upsert + サーバー archive された id を削除。payload キャッシュは cascade しない — サーバー archive されたパックも本体はローカル保持
5. 各パックについて `pack.version` vs ローカルキャッシュ payload version を比較:
   - **一致** → `AlreadyUpToDate` (DL なし)
   - **キャッシュなし or cache.version < pack.version** → `request-pack-download` Edge Function 呼び出し
   - **cache.version > pack.version** → `VersionRegression` outcome (警告 + 高い方のキャッシュ保持)
6. 200 で Edge Function が `{payload_url, payload_url_ttl, current_version, payload_size}` 返却。クライアントは別途 inject された Ktor `HttpClient` で signed URL を GET (supabase-kt 内部 client は絶対 URL で再利用不可)
7. payload JSON を `SymbolPackPayload` にデコード。envelope の `current_version` と `payload.version` が **必ず一致**。不一致は `Parse` 失敗 (古い payload を指す stale signed URL に対する defense-in-depth)
8. `LocalSymbolPackDataSource.upsertPayload(packId, version, jsonString)` で本体を `DownloadedPackPayloadEntity` に書く
9. `CompositeSymbolCatalog.refresh()` が in-memory スナップショットを再構築 (render hot path 用の同期 `get(id)`)

### データフロー — render 時のシンボル lookup

```
ChartEditor.cell.draw(symbol_id)
  ↓
CompositeSymbolCatalog.get(symbol_id)
  ├─ DL 済スナップショットに symbol_id ある?
  │   ├─ yes → entry.tier をチェック
  │   │       ├─ FREE → entry.definition 返却
  │   │       └─ PRO  → entitlementResolver.isPro()?
  │   │                  ├─ true  → entry.definition 返却
  │   │                  └─ false → null 返却 (セルは "?" でレンダー)
  │   └─ no → bundle compile-time catalog にフォールスルー
  └─ bundled.get(symbol_id)
```

`EntitlementResolver.isPro()` は同期 — `SubscriptionRepository.cachedActiveSubscription(userId)` (単一 PK-indexed SQLDelight 行) を読んで `expires_at` を `Clock.System.now()` と比較。render path にコルーチン境界なし。

### Pro 層多層防御

| 層 | 仕組み | 失敗モード |
|---|---|---|
| **サーバー側 DL gate** | `request-pack-download` Edge Function が `subscriptions WHERE status IN ('active','in_grace_period') AND (expires_at IS NULL OR expires_at > now())` をチェック。なければ 403 `pro_entitlement_required` | 失効ユーザーは新規 Pro パックを DL できない |
| **signed URL TTL** | 5 分。失効後は Storage が 403 を返す | URL 漏洩や revocation 後の in-flight fetch を時間で bound |
| **クライアント側 render gate** | `EntitlementResolver.isPro()` が `CompositeSymbolCatalog.get(id)` 毎回。Pro エントリは非サブスクで null | 失効ユーザーは次の render で Pro パック行がパレットから消える |
| **`subscriptions` ソース・オブ・トゥルース** | `revenuecat-webhook` Edge Function が status 遷移 (EXPIRATION / CANCELLATION / REFUND) を `upsert_subscription_from_webhook` RPC + `last_verified_at` 順序ガードで書く | webhook retry が古い状態で新しい状態を上書きできない |
| **オフライン default-deny** | cached subscription 行なし OR `expires_at <= now()` で `isPro()` が false | 初回起動でネット未接続のユーザーは初回 refresh まで Pro パック lock |

**自覚的限界 (ADR-016 §8 #5)**: オフラインでデバイス時計を巻き戻すユーザーは true expiry 後も `isPro()` true を維持。緩和: `SubscriptionRepository.refresh()` は reconnect 時にサーバー `now()` で再評価し、`request-pack-download` も全 DL でサーバー側再評価する — bypass 窓は「既にディスクにあるパック」へのアクセスを延長するだけで、新規エンタイトルメント gated DL は不可能。

### Edge Function レスポンス shape

**成功 (HTTP 200)**:
```json
{
  "payload_url":     "https://<project>.supabase.co/storage/v1/object/sign/symbol-packs/<path>?token=...",
  "payload_url_ttl": "2026-05-12T08:00:00Z",
  "current_version": 2,
  "payload_size":    13558
}
```

**失敗**:

| HTTP | `error` | 発生条件 | クライアント mapping |
|---|---|---|---|
| 400 | `invalid_json` | Body パース失敗 | `SymbolPackDownloadResult.Failure.Unknown` |
| 400 | `missing_pack_id` | `pack_id` 空 | `Failure.Unknown` |
| 401 | `unauthorized` | Bearer JWT 欠落 / 無効 | `Failure.Unauthenticated` |
| 403 | `pro_entitlement_required` | Pro パック + active subscription なし | `Failure.ProEntitlementRequired` → sync の `SkippedProEntitlement` |
| 404 | `pack_not_found` | `symbol_packs` 行マッチなし | `Failure.PackNotFound` |
| 429 | `rate_limited` | 10 req / 60s 予算超過、body に `retry_after_seconds` | `Failure.RateLimited` |
| 500 | `edge_function_misconfigured` | `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` runtime 欠落 | `Failure.Unknown` |
| 500 | `internal_error` | Storage sign 失敗 / pack lookup クエリエラー | `Failure.Unknown` |

### `payload.json` の wire format

トップレベル shape ([SymbolPackPayload.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/model/SymbolPackPayload.kt) より):

```json
{
  "pack_id": "jis.knit.beginner",
  "version": 1,
  "schema_version": 1,
  "symbols": [
    {
      "id": "jis.knit.k",
      "category": "KNIT",
      "tier": "FREE",
      "path_data": "M 0 0 L 1 1 ...",
      "fill": false,
      "width_units": 1,
      "height_units": 1,
      "parameter_slots": [...],
      "ja_label": "表目",
      "en_label": "Knit",
      "ja_description": "...",
      "en_description": "...",
      "aliases": [],
      "jis_reference": "JIS L 0201-1995 §5.1",
      "cyc_name": null
    }
  ]
}
```

**`schema_version` の forward-compat 契約**:
- `symbols[]` エントリへの **additive な変更** (新規オプショナルフィールド) では `schema_version` を bump しない。旧クライアントは `ignoreUnknownKeys = true` でデコード
- **破壊的変更** (フィールド削除、意味変更、トップレベル形状変更) は `schema_version` を bump。bump 時はパックを新 `pack_id` に split (ADR-009 §9 symbol-id 安定性契約) — 旧クライアントが旧 id で旧 payload を引き続き見つけられる
- per-symbol `tier` フィールドが 1 パック内の free + pro 混在を許可。v1 は親パック tier に全部揃えて ship

### バンドルフォールバック

Phase 41 前のコンパイル時 `DefaultSymbolCatalog` はアプリバイナリに残る。初回起動でネット未接続のユーザーは今日と同じ 70 個の JIS シンボルを見る。DL 済パックは初回 sync 成功後にカタログのソース・オブ・トゥルースになる。これは ADR-016 §4.1 が言うオフラインセーフティネット。バンドルカタログと seed migration 済 free パックは今日同じグリフ定義を ship しているので wire format レベルではゼロコスト。

DL 済パックが同 id のバンドルシンボルを上書きするとき、DL 済エントリが勝つ (newer-version-wins、`CompositeSymbolCatalog` の lookup 順を KDoc 参照)。

### バージョニング + キャッシュ無効化

- `symbol_packs.version` はパック id ごと monotonic。bump で全クライアントが次 sync で再 DL する
- ローカルキャッシュキーは `(pack_id, version)` — SQLDelight クエリ `getLatestPayload(packId)` が最高バージョンを返す。古いバージョンはユーザーが明示的にストレージ解放するまでディスクに残る (Phase 41.4 パック管理画面で予定)
- Edge Function は `<pack_id>/<version>/payload.json` に対して URL 署名。パッチ時は `<pack_id>/<new_version>/payload.json` をアップロード後、`UPDATE symbol_packs SET version = new_version, payload_path = '<pack_id>/<new_version>/payload.json', payload_size = ...`。旧 payload ファイルは Storage に残して OK (v1 カーディナリティではコスト圧なし)。将来のクリーンアップタスクで purge

### 本番現状

2026-05-12 時点で prod `symbol_packs` テーブルは 2 行:

| id | tier | version | payload_path | symbol_count | payload_size |
|---|---|---|---|---|---|
| `jis.knit.beginner` | free | 1 | `jis.knit.beginner/1/payload.json` | 35 | 13,558 |
| `jis.crochet.beginner` | free | 1 | `jis.crochet.beginner/1/payload.json` | 35 | 20,492 |

Pro パックはまだない。エンドツーエンド DL 経路は検証済 (sync manager が毎回コールド起動で Storage からローカルキャッシュを populate) だが、**Pro 層パックのオーサリング + seed は未着手**。Phase 39 closed beta polish 一覧で順序付け (ADR-016 §5 の予定 Pro パックラインアップ参照)。

## この surface の運用方法

**パック公開・パッチ** → [docs/ja/ops/content-publishing.md](../ops/content-publishing.md)。

**DL 失敗診断** → [docs/ja/ops/incident-playbook.md](../ops/incident-playbook.md#症状-シンボルパックダウンロードが失敗)。

**Edge Function service-role custody ローテーション** → 別途アクションなし。function は Supabase platform-injected `SUPABASE_SERVICE_ROLE_KEY` を読む。

## 重要参照

- [ADR-016](../../en/adr/016-phase-41-pro-subscription-dynamic-symbols.md) — 完全設計、Edge Function path の 4 way 判断、paywall UX、テレメトリ
- [ADR-009](../../en/adr/009-parametric-symbols.md) — parametric symbols + symbol-id 安定性契約
- [supabase/functions/request-pack-download/](../../../supabase/functions/request-pack-download/) — Edge Function コード + README

## 追跡 tech debt

- 最初の Pro パックのオーサリング + seed が未完了。Phase 40 GA 前に `knitter` agent + `monetization-strategist` agent で候補ラインアップを inventory
- Phase 41.4 パック管理画面 + 「ストレージを空ける」アフォーダンス未実装 (`user_symbol_pack_state` テーブルはこのために予約)
- Edge Function インスタンスのコールド起動で rate-limit map がリセット。ADR-016 §10 Q6 で persistent storage (Upstash Redis or `edge_function_rate_limit` テーブル) を post-beta アップグレードパスとして named
