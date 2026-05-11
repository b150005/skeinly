# Runbook — シンボルパックコンテンツ公開

> 英語原典: [docs/en/ops/content-publishing.md](../../en/ops/content-publishing.md)
>
> **目的**: 新規シンボルパックの公開、既存パックのバージョンアップ、ロールバックのステップバイステップ手順。1 タスクで自己完結。
>
> **対象**: 本番 Supabase プロジェクトに対して作業する運用者 (典型的にはオーナー本人)。
>
> **本 runbook の範囲外**: シンボル個別の作画規約 (グリフデザイン、JIS 参照基準、parameter slot 意味論) は [ADR-009](../../en/adr/009-parametric-symbols.md) と `symbol-review/` シリーズを参照。RevenueCat Pro エンタイトルメント設定は sandbox tester フローを扱う [docs/ja/ops/beta-testing.md](beta-testing.md) を参照。

## メンタルモデル

シンボルパックは **2 個の write がペア** で公開:

1. **Storage**: `payload.json` (シンボル本体) を private な Supabase Storage bucket の `symbol-packs/<pack_id>/<version>/payload.json` にアップロード
2. **Postgres**: `public.symbol_packs` テーブルに 1 行 INSERT (or UPDATE)、上記パスを指し、`version` / `tier` / `payload_size` / `symbol_count` を宣言

オプション:
- `public.symbol_pack_locales` に ja-JP (or 将来のロケール) 用 display name + description を INSERT
- `preview.png` (オプションのサムネイル) を payload と同じパス階層 `<pack_id>/<version>/preview.png` にアップロード

クライアントは次の sync サイクル (コールドスタート + 購入後 + フォアグラウンドフック) で新パックを取り込む。**アプリ更新不要**。

現在のアーキテクチャは [docs/ja/spec/symbol-pack-delivery.md](../spec/symbol-pack-delivery.md) (現状では未翻訳、英語版を参照: [docs/en/spec/symbol-pack-delivery.md](../../en/spec/symbol-pack-delivery.md)) を参照。

---

## タスク: 新規 Free 層パックを公開

### 前提条件

- パックのシンボル定義が `shared/src/commonMain/.../domain/symbol/catalog/` のバンドル済コンパイル時 catalog に追加済み
  - これが `payload.json` 生成のソース・オブ・トゥルース。バンドル外のオーサリングは v1 範囲外 — 現在 ship 済の全パックはバンドル `DefaultSymbolCatalog` の partition
- 安定した unique `pack_id` を採用。命名規約: `<system>.<craft>.<tier_or_qualifier>` (例: `jis.knit.intermediate`、`cyc.crochet.colorwork`)。一度決めた id はパックの life span で immutable — `schema_version` を bump する破壊的変更は新しい id を強制 (ADR-016 / ADR-009 §9)

### 手順

1. **payload + seed SQL ブロックをローカル生成**
    ```bash
    ./gradlew :shared:generateSymbolPackPayloads
    ```
    タスクは常時走る `SymbolPackPayloadGeneratorTest` を `skeinly.payloads.outputDir` system property 付きで実行。出力先は `shared/build/generated/symbol-pack-payloads/`。Gradle コンソールに manifest summary + seed SQL ブロック (`INSERT INTO public.symbol_packs ...`) が標準出力される。

    > 新パックの partition がジェネレータに未定義なら、まずジェネレータを拡張する必要がある。partition ロジックは `SymbolPackPayloadGeneratorTest.kt` にあり、`pack_id` をキーに `List<SymbolDefinition>` を bundled catalog から選択する partition を追加する。新 partition を宣言してジェネレータを再実行するまでテストは fail する。

2. **生成ファイルをローカルで確認**
    ```bash
    ls -lh shared/build/generated/symbol-pack-payloads/
    jq '.pack_id, .version, .schema_version, (.symbols | length)' \
      shared/build/generated/symbol-pack-payloads/<pack_id>__<version>__payload.json
    ```
    妥当性チェック:
    - `pack_id` が選択した id と一致
    - `version` は新規パックなら 1
    - `schema_version` は `SymbolPackPayload.CURRENT_SCHEMA_VERSION` (今日時点で 1) と一致
    - `symbols.length` が想定する pack inventory と一致

3. **`payload.json` を Storage bucket にアップロード**

    **オプション A — Supabase Dashboard** (1 回限りのアップロードに楽):
    - Storage → `symbol-packs` バケット
    - フォルダパス `<pack_id>/<version>/` を作成 (Dashboard 上でパス入力中にフォルダ作成プロンプトが出る)
    - そのフォルダに `payload.json` をアップロード

    **オプション B — Supabase CLI** (スクリプト化向き):
    ```bash
    supabase storage cp \
      shared/build/generated/symbol-pack-payloads/<pack_id>__<version>__payload.json \
      ss:///symbol-packs/<pack_id>/<version>/payload.json
    ```

    確認:
    ```bash
    supabase storage ls ss:///symbol-packs/<pack_id>/<version>/
    ```

    期待: 手順 2 で得たバイトサイズの `payload.json` が 1 行表示される。

4. **seed メタデータを Postgres に適用**

    `:shared:generateSymbolPackPayloads` が標準出力した `INSERT INTO public.symbol_packs ...` ブロックをコピー。

    Supabase MCP ツール経由で適用 (推奨 — 追跡 migration として残る):
    ```
    mcp__supabase__apply_migration を以下で実行:
      name: phase_X_X_seed_<pack_id_safe>
      query: <出力された INSERT ブロック>
    ```

    または CLI:
    ```bash
    supabase migration new phase_X_X_seed_<pack_id_safe>
    # INSERT ブロックを supabase/migrations/ 配下の新規ファイルにペースト
    supabase db push
    ```

5. **ja-JP locale 行を追加** (JP ユーザーに ship するパックでは必須):
    ```sql
    INSERT INTO public.symbol_pack_locales (pack_id, locale, display_name, description) VALUES
      ('<pack_id>', 'ja', '<日本語表示名>', '<日本語の説明>');
    ```

    手順 4 と同じ方法で適用。en-US は親行の `display_name` / `description` にフォールバックするので en 行は別途不要。

6. **エンドツーエンドで動作確認**
    ```sql
    SELECT
      id, tier, version, display_name, payload_size, symbol_count,
      (SELECT display_name FROM public.symbol_pack_locales WHERE pack_id = sp.id AND locale = 'ja') AS ja_name
    FROM public.symbol_packs sp
    WHERE id = '<pack_id>';
    ```
    `mcp__supabase__execute_sql` または `supabase db query` で適用。期待: 行データと populate された `ja_name` を持つ 1 行。

    続いて Edge Function を sign-in 済ユーザー視点で smoke test:
    ```bash
    USER_JWT="<実ユーザーセッションから取得>"
    PROJECT_REF="<Supabase project ref>"
    curl -s -X POST \
      "https://${PROJECT_REF}.supabase.co/functions/v1/request-pack-download" \
      -H "Authorization: Bearer ${USER_JWT}" \
      -H "Content-Type: application/json" \
      -d "{\"pack_id\":\"<pack_id>\"}"
    ```
    期待: HTTP 200 + `{payload_url, payload_url_ttl, current_version, payload_size}`。`curl -s ${payload_url}` で実際にアップした JSON 本体が返る。

7. **実機クライアントで確認**
    - TestFlight or Play Internal ビルドでアプリをコールド起動
    - sync manager ログ (`SymbolPackSyncManager.sync()` outcome) で `Downloaded(packId=<pack_id>, version=1)` を確認
    - 編図エディタを開く — 新パックのシンボルが `category` でフィルタされたパレットに出る

### 時間目安

手順 1–6: レイアウトに慣れていれば ~10 分。手順 7 (実機確認) はテスター稼働次第。クローズドベータサイクルの後に回しても OK。

---

## タスク: Pro 層パックを公開

Free 層フローと同じ。差分:

- **手順 4 INSERT**: `tier` カラムは `'free'` でなく `'pro'`
- **Storage パス** は同じ `<pack_id>/<version>/payload.json` レイアウト。バケットは両層 private、Edge Function だけが認可された読者でエンタイトルメントゲート後に signed URL を発行
- **手順 6 確認はアクティブな `subscriptions` 行を持つ sign-in ユーザーで** (status `active` または `in_grace_period`)。非 Pro ユーザーの curl は `HTTP 403 {"error":"pro_entitlement_required","pack_id":"<id>"}` を返す — 意図された挙動
- **`payload.json` 内の per-symbol `tier` フィールド**: 今日は Pro パックの全シンボルが `"tier": "PRO"`。「Free パックに有料シンボルが混在」配置は wire format レベルで forward-compat (ADR-016 通り) だが現行運用では未使用

### Pro パック公開前チェックリスト

- [ ] RevenueCat 上で iOS / Android SKU を含む product 設定済
- [ ] `subscriptions` テーブルに最低 1 名のテスターのアクティブ行 (`grant_alpha_pro(uid)` で smoke 用、または完全 E2E は [beta-testing.md](beta-testing.md) の sandbox tester フロー)
- [ ] Pro パックメタデータコピーが `monetization-strategist` agent + `knitter` agent でペイウォール適合性レビュー済

---

## タスク: 既存パックをパッチ (バージョン bump)

グリフ修正、コピー修正、追加シンボル拡張をアプリ更新なしで既存ユーザーに届ける場合に使用。

### 手順

1. **bump 後のバージョンを決定**。バージョンは pack_id ごとに monotonic な整数。1 ずつインクリメント。バージョン再利用は禁止 — 全クライアントが `version` を数値比較し、regression は最高 cached バージョンをサイレントに保持する (`SymbolPackSyncManager.VersionRegression` outcome 参照)

2. **新バージョンで payload を再生成**
    同じ `:shared:generateSymbolPackPayloads` タスク。ジェネレータの partition 宣言がバージョンを制御 — 走らせる前にそこを bump

3. **新 payload をアップロード**
    ```bash
    supabase storage cp \
      shared/build/generated/symbol-pack-payloads/<pack_id>__<new_version>__payload.json \
      ss:///symbol-packs/<pack_id>/<new_version>/payload.json
    ```
    旧パス (`<pack_id>/<old_version>/payload.json`) は Storage に残す — 無害 (現行カーディナリティではコスト圧なし)、bump 直前に古い URL を sign してしまったユーザーを保護

4. **catalog 行を UPDATE**
    ```sql
    UPDATE public.symbol_packs
       SET version       = <new_version>,
           payload_path  = '<pack_id>/<new_version>/payload.json',
           payload_size  = <新ファイルのバイト数>,
           symbol_count  = <新ファイルの symbol 数>,
           updated_at    = now()  -- 冗長; BEFORE UPDATE トリガーがいずれにせよ set
     WHERE id = '<pack_id>';
    ```

5. **(任意) ja-JP locale 行を更新** ローカライズされた表示名 or 説明が変わった場合:
    ```sql
    UPDATE public.symbol_pack_locales
       SET display_name = '<新 ja display name>',
           description  = '<新 ja description>'
     WHERE pack_id = '<pack_id>' AND locale = 'ja';
    ```

6. **確認**
    新規パック手順 6 と同じ。

### クライアント側の挙動

次の sync で各クライアントが `pack.version > cachedVersion` を観測して再ダウンロード。ローカル SQLDelight cache に `(pack_id, new_version)` が格納、旧バージョンは peer 行として残り naturally orphan 化 (`getLatestPayload(packId)` クエリが最高バージョンを返す)。将来の Phase 41.4 「ストレージを空ける」アフォーダンスがこの orphan を掃除する。

---

## タスク: 公開済パックをロールバック

**非推奨操作** — 修正版に forward bump する方が構造的に安全。ロールバックは `symbol_packs.version` を野外でアクティブなものより小さい値にセットすることになり、クライアントは `VersionRegression` 扱いで適用を拒否 (より高い cached バージョンを保持)。

新規公開したパックにセキュリティ関連の欠陥 (例: editor をクラッシュさせる parameter-slot payload) があり、クライアントの一貫性より即時取り消しが優先されるケース限定。

### 手順

1. **修正版に forward bump**。これが安全パス。上の patch フローを欠陥を除いたより高いバージョン番号で再実行

2. **(最終手段) 該当行を削除**
    ```sql
    DELETE FROM public.symbol_packs WHERE id = '<pack_id>';
    ```
    効果: 次の sync で全クライアントの manifest から消える。`LocalSymbolPackDataSource.replaceManifest` がローカル catalog 行を drop するが **payload テーブルには触らない** — 既にダウンロード済ユーザーは本体をローカルに保持 ("サーバー archive はユーザーが authoring 済のセルに対応するシンボルをサイレント削除すべきでない" 設計判断)。手動でストレージを空ける (Phase 41.4) まで キャッシュされたシンボルはレンダリングし続ける

3. **デザインが大きく変わって再キーが妥当なら新 id で再公開**。`payload.json` 内部はシンボル id を参照しているので、bundled compile-time fallback にそれらの id が残っていれば既存 authoring 済チャートは描画し続ける

---

## タスク: 既存パックに新ロケールを追加

現在は en フォールバック (親行) と並んで `ja` 翻訳を ship。3 番目のロケール (例: `zh-CN`, `ko-KR`) を追加するには:

```sql
INSERT INTO public.symbol_pack_locales (pack_id, locale, display_name, description) VALUES
  ('<pack_id>', '<bcp47>', '<localized name>', '<localized description>');
```

`locale` CHECK 制約は `^[a-z]{2}(-[A-Z]{2})?$` — `en`, `ja`, `zh`, `zh-CN`, `ko-KR` すべて valid。アプリ側も新ロケール用に UI 文字列がローカライズ済である必要がある (別作業 — `docs/en/i18n-convention.md` 参照)。パックメタデータは多数ある i18n surface の 1 つ。

---

## タスク: オプションのプレビューサムネイルをアップロード

paywall プレビュー UX (Phase 41.4) 用に `preview.png` を `payload.json` と並べる:

```
symbol-packs/<pack_id>/<version>/payload.json
symbol-packs/<pack_id>/<version>/preview.png   ← オプション
```

バケットの `allowed_mime_types` に `image/png` 含む。追加の Postgres 行は不要 — クライアントがサムネイル URL を慣習で構築。

```bash
supabase storage cp ./preview.png ss:///symbol-packs/<pack_id>/<version>/preview.png
```

---

## トラブルシューティング

### `request-pack-download` が smoke test で GitHub Issues の HTTP 422 を返した

Edge Function の smoke test を間違えている — その error は `submit-bug-report` のもの。正しい smoke test は上の手順 6。

### `request-pack-download` が HTTP 404 `pack_not_found` を返す

seed migration が実際に適用されたか確認: `SELECT * FROM public.symbol_packs WHERE id = '<pack_id>';`。よくある原因: migration をローカル作成したが push してない (`supabase db push`)、migration ファイル名で pack_id を typo してる。

### クライアントが free パックに対して `PackSyncOutcome.SkippedProEntitlement` をログ

catalog 行の `tier` カラムが `'pro'` になっている (seed INSERT を再確認 — tier 値の typo が最頻原因)、または manifest がクライアント側で再 fetch されていない (コールド起動で強制リロード)。

### クライアントが `PackSyncOutcome.VersionRegression` をログ

サーバー側 `symbol_packs.version` がクライアントにローカル cached されたものより厳密に小さい。バージョンをロールバックした (上記「ロールバック」タスクで非推奨な理由参照) か、手動 UPDATE で誤ってバージョンを下げた。いずれかのクライアントが見たことのあるバージョンより大きい値に forward bump。

### Storage upload は成功するが Edge Function が 500 `internal_error` を返す

function ログを引く:
```
mcp__supabase__get_logs を service: edge-function で実行
```
`storage sign failed` か `symbol_packs lookup failed` を探す。最頻原因: catalog 行の `payload_path` が実際の Storage パスと一致しない (大文字小文字、末尾 slash、バージョン segment 欠落)、またはバケットポリシーが何かのはずみで変わった。

### Sync がサイレント — `SymbolPackSyncManager` から何もログが出ない

テスト対象 build の post-Phase-41.3 トリガーパスがちゃんと配線されているか確認:
- フォアグラウンド復帰フック (`onResume` → `manager.sync()`)
- 購入後 RevenueCat callback (Phase 41.3)
- アプリ起動ウォームアップ

コンストラクタの warm-up `CompositeSymbolCatalog.refresh()` だけが発火して sync manager が走らないなら、トリガー配線が regression している — `di/` モジュール内の `applicationScope` 設定を確認。

---

## リファレンスデータ — 本番カタログ (2026-05-12 時点)

```sql
SELECT id, tier, version, payload_path, symbol_count, payload_size FROM public.symbol_packs;
```

| id | tier | version | payload_path | symbols | bytes |
|---|---|---|---|---|---|
| `jis.knit.beginner` | free | 1 | `jis.knit.beginner/1/payload.json` | 35 | 13,558 |
| `jis.crochet.beginner` | free | 1 | `jis.crochet.beginner/1/payload.json` | 35 | 20,492 |

Pro パックはまだ公開されていない。

## 関連 runbook

- [docs/ja/ops/incident-playbook.md](incident-playbook.md) — シンボルパック DL 含むよくある障害モード (英語版: [docs/en/ops/incident-playbook.md](../../en/ops/incident-playbook.md))
- [docs/ja/ops/beta-testing.md](beta-testing.md) — Pro パックを E2E で確認するために必要な sandbox tester 設定
- [docs/ja/ops/secrets-rotation.md](secrets-rotation.md) — Edge Function service-role custody ローテーション (まれだが本 surface に影響)
