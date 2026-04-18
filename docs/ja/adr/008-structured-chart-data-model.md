# ADR-008: 構造化編み図データモデル

> Source: [English version](../../en/adr/008-structured-chart-data-model.md)

## ステータス

Accepted

## コンテキスト

ADR-007 で Knit Note は行カウンタから構造化編み図オーサリングへ方針転換した。
Phase 29 はそのデータモデルの初版 — 写真ではなく構造化ドキュメントとして編み図を表現する層 — を投入する必要がある。以降の Phase 30 (シンボルライブラリ)、Phase 31 (ビューア)、Phase 32 (エディタ MVP)、Phase 37+ (Git 風コラボレーション) は全てこの上に構築する。

モデルに要求される事項:

1. 平編みの矩形グリッドと、輪編み (polar) の両方をサポート
2. 複数のシンボル規格を同一ドキュメント内で扱える (JIS L 0201 は日本で支配的、Craft Yarn Council は米国で事実上標準、ISO 規格なし、ユーザ定義シンボルも必要)
3. 既存の `Pattern.chartImageUrls` (写真編み図) と共存。将来 AI による写真→構造化変換を想定しており、インポート結果は巨大 (数百行 × 数十目/行) となる可能性あり
4. Phase 37 のコラボレーション (コミット履歴、ブランチ、diff) で行き詰まらない
5. プロダクション移行不要 — ADR-007 §Decision 5 の「プレリリース throwaway 窓」中

## 決定事項

### 1. ストレージ: JSON ドキュメント方式 (正規化しない)

編み図 1 枚 = 1 ドキュメント:

- Local: `StructuredChartEntity` の 1 行、`document TEXT` 列にシリアライズ済 JSON
- Remote: `chart_documents` の 1 行、Postgres `jsonb` 列

却下案:

- **正規化 3 テーブル (chart / layer / cell)**: Supabase Realtime のセル単位 UPDATE は編み図全体のコンテキストを伝えられない。エディタのメンタルモデルも「編み図全体を保存」なので、書き込み粒度を細かくしても得がない。mapper / sync 表面積が 3 倍になる割にリターンがない
- **SQLite FTS / jsonb GIN**: Phase 29〜32 でチャート内部を検索する UseCase は存在しない。Phase 36 Discovery でタグ/シンボル検索が要るようになった時点で `document` 列に GIN を張れば良く、スキーマ変更は不要

### 2. 全体 upsert (セル単位の diff は取らない)

1 回の編集で新しい完全ドキュメントを生成:

- `local.upsert(chart)` — 1 行 update
- `syncManager.syncOrEnqueue(STRUCTURED_CHART, id, UPDATE, json)` — 1 payload

理由:

- 既存の `PendingSync` coalescing が `(entityType, entityId)` で連続編集を統合してくれる。UX 応答性は問題なし
- Realtime `postgres_changes` は新しい行全体を emit する。ビューアはインメモリの編み図を atomic に置換すれば良い
- 既存の `PatternRepositoryImpl` / `ProjectRepositoryImpl` と同じ流れ。新しい sync プリミティブを追加する必要なし

### 3. スキーマバージョニング + 前方互換エスケープ

ドキュメントは `schema_version: Int` (Phase 29 で 1 に固定) を持ち、`chart_documents` 行は別途 `storage_variant TEXT` 列を持つ (Phase 29 は `'inline'` のみ)。破壊的 migration 無しで以下に対応可能:

- ドキュメント内レイアウトの変更 → `schema_version` bump + mapper の `when` 分岐
- AI インポートで 1 行に入らない巨大編み図が出現 → `storage_variant = 'chunked'` で別テーブル `chart_chunks` に分割、`chart_documents.document` は manifest となる。**Phase 29 では実装しない** — 列だけ確保しておくことで将来の分岐点を保持

### 4. ドメインモデル形状

```kotlin
@Serializable
data class StructuredChart(
    val id: String,
    @SerialName("pattern_id") val patternId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("coordinate_system") val coordinateSystem: CoordinateSystem,
    val extents: ChartExtents,
    val layers: List<ChartLayer>,
    @SerialName("revision_id") val revisionId: String,
    @SerialName("parent_revision_id") val parentRevisionId: String?,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
)

@Serializable enum class CoordinateSystem {
    @SerialName("rect_grid") RECT_GRID,
    @SerialName("polar_round") POLAR_ROUND,
}

@Serializable
sealed interface ChartExtents {
    @Serializable @SerialName("rect")
    data class Rect(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) : ChartExtents
    @Serializable @SerialName("polar")
    data class Polar(val rings: Int, val stitchesPerRing: List<Int>) : ChartExtents
}

@Serializable
data class ChartLayer(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val cells: List<ChartCell>,
)

@Serializable
data class ChartCell(
    @SerialName("symbol_id") val symbolId: String,
    val x: Int,
    val y: Int,
    val width: Int = 1,
    val height: Int = 1,
    val rotation: Int = 0,
    @SerialName("color_id") val colorId: String? = null,
)
```

### 5. 座標系の約束

`RECT_GRID` では `y` は上方向が正。編み図は慣習的に下から上に読むため、`y = 0` のセルが 1 段目 (最下段)。この約束は `docs/ja/chart-coordinates.md` (Phase 29) に記載。Phase 31 の SwiftUI / Compose Canvas レンダラがビューポート層で `scaleY(-1)` 変換を掛ける。

### 6. シンボル ID 形式 (カタログ中身は Phase 30)

シンボル ID は名前空間つきドット区切り小文字文字列、正規表現 `^[a-z]+(\.[a-z0-9_]+)+$` に一致:

- `jis.*` — JIS L 0201 編目記号 (棒針 + かぎ針 + アフガン)
- `std.*` — 国際標準 (Craft Yarn Council)
- `user.{uuid}.*` — ユーザ定義カスタムシンボル
- `ext.*` — 将来の拡張用予約

`enum class` ではなく文字列にした理由:

- enum は新シンボル追加毎に再コンパイルが必要。Phase 35 のユーザ定義シンボルは別フィールドが必要になる
- JSON 境界の安全性: 未知の ID はデシリアライズ失敗ではなくプレースホルダ描画に落とせる

Phase 29 は **フォーマットのみ** を確定する。実際の `SymbolCatalog` (ID → SVG path + バイリンガルラベル + JIS↔CYC マッピング) は Phase 30 の仕事。

### 7. Revision メタデータを Phase 29 で入れる (Phase 37 待ちにしない)

`revisionId`, `parentRevisionId`, `contentHash` を Phase 29 時点で入れる:

- Phase 37 で追加すると Phase 39 クローズドベータ後のユーザデータに backfill が必要となり、ADR-007 §5 に反する
- `contentHash` は Phase 29 時点でもべき等 sync retry と Realtime 重複排除に使える
- Phase 37 では別テーブル `chart_revisions` で DAG を持つが、`chart_documents` は常に tip を保持する。破壊的 migration は発生しない

### 8. Pattern との関係

`StructuredChart.patternId` は non-null。すべての構造化編み図はパターンに属する。パターン対構造化編み図は 1:0..1 (`chart_documents` の `UNIQUE(pattern_id)` で強制)。

`Pattern.chartImageUrls` (写真) は残す。1 つのパターンが写真 + 構造化編み図を両方持つ状態は valid。Phase 31 ビューアの優先表示ルールは Phase 31 の判断事項。

### 9. RLS + Realtime

`chart_documents` の RLS は `patterns` と同じモデル:

- オーナは自分の編み図を CRUD 可能
- 親 `patterns.visibility = 'public'` のときは誰でも読み取り可能

`supabase_realtime` publication に追加してビューアがピア編集を受信できるようにする (実用は Phase 37 以降のマルチライター、Phase 37 前は同一ユーザの別端末同期用途)。

### 10. Phase 29 スコープ境界

Phase 29 で実装:

- ドメインモデル + シリアライゼーション + golden test
- SQLDelight migration + `PendingSync` enum 拡張
- Supabase migration 012 (`chart_documents`, RLS, Realtime, インデックス)
- Local/Remote DataSource + mapper
- `StructuredChartRepository` (coordinator pattern, `PatternRepositoryImpl` を踏襲)
- `SyncExecutor` に `STRUCTURED_CHART` 対応を追加
- UseCase 5 個: Get / Observe / Create / Update / Delete
- Koin 配線
- `ProjectDetail` に "編み図あり" インジケータ (Android + iOS) — Phase 31 の入口として

Phase 29 で実装**しない**:

- Canvas レンダリング (Phase 31)
- エディタ UI / パレット / tap-to-place (Phase 32)
- `SymbolCatalog` の中身 (Phase 30)
- セル単位の進捗オーバーレイ (Phase 34)
- Revision DAG テーブル (Phase 37)
- タグ / シンボルレベル検索インデックス (Phase 36)

## 結果

### ポジティブ

- 1 テーブル / 1 payload / 1 sync 経路 — 他のドメインエンティティと全く同じメンタルモデル
- `schema_version` + `storage_variant` の 2 軸で破壊的 migration なしに将来拡張可能
- Revision メタデータが最初から入っているので Phase 39 フリーズ後の backfill を避けられる
- AI インポート由来の巨大編み図 (数百行) も Postgres `jsonb` の実用行サイズまでは同一テーブルに入る。溢れたら `storage_variant = 'chunked'` に切り替える前提で設計済み

### ネガティブ

- 1 セル編集でも行全体を書き直す。想定サイズ (≤2000 セル / 〜150KB) では許容範囲。実測で問題が出たら再評価
- `ChartExtents` sealed を永続的に 2 パターン保守することになる。polar は v1 必須なので rect 単一化は不可
- シンボル ID が不透明文字列のためコンパイル時 exhaustiveness が失われる。Phase 30 の `SymbolCatalog` がランタイムレジストリ + lint で補完する想定

### ニュートラル

- 既存 `Pattern.chartImageUrls` (写真) はそのまま。写真 + 構造化編み図の併用状態は valid。Phase 31 のビューア優先ルールはその時決める

## 検討した代替案

| 代替案 | 却下理由 |
|---|---|
| 正規化 3 テーブル (chart / layer / cell) | Realtime がセル UPDATE 時に編み図コンテキストを運べない。エディタも全体保存モデル |
| Postgres で行 = セル + 行単位 Realtime | 1 編み図で数千行が発生し、クエリ / sync 性能が壊滅 |
| 編み図全体を共有 SVG で持つ | シンボル単位のセマンティクスが失われ、Phase 34 セル単位進捗 / Phase 37 diff が不可能 |
| `patterns.chart_data jsonb` に同居 | 単一責任違反。パターンは無関係メタデータも持つ。public fork の RLS セマンティクスもズレる |
| `SymbolId` を enum で始めて Phase 35 で文字列化 | プレリリース→クローズドベータ間で JSON 境界の再シリアライズが発生、ADR-007 §5 が警告する migration コスト |

## Phase 30 追補 — シンボルカタログとパラメトリックセル

Phase 30 完了時に、ADR 本文で forward-looking に記述していた設計決定を
実装確定版として追記する。

### シンボル ID の命名規則

- 複数単語セグメントは **kebab-case** (ハイフン)。例:
  `jis.knit.k2tog-r`、`jis.knit.cable-2x2-l`
- JIS 標準番号は ID に **埋め込まない**。`SymbolDefinition.jisReference`
  に保持することで、JIS 改訂で番号がシフトしても ID は安定
- `SYMBOL_ID_REGEX` を `^[a-z]+(\.[a-z0-9_]+)+$` から
  `^[a-z]+(\.[a-z0-9][a-z0-9_-]*)+$` に拡張してハイフンを許容。Phase 29
  のバリデータテストで reject されていた全ケースは引き続き reject

### パラメトリック記号

編出し増目・伏せ目 n 目・引き返し編み段ラベルなど、数値パラメータを
記号内に描画するケースに対応。

- `SymbolDefinition.parameterSlots: List<ParameterSlot>` — slot key、
  unit-square 内のアンカー座標、バイリンガル label、optional default
- `ChartCell.symbolParameters: Map<String, String>` (JSON key は
  `symbol_parameters`) — セルごとの値。default は空 Map。既存セルは
  そのままデコードできる

schema_version は **1 のまま据え置き**。新フィールドは追加 + default 空で
後方互換。`chart_documents.document` jsonb の round-trip はマイグレーション
も `schema_version` bump も不要。将来パラメータスロットの **意味論** を
変える (型システム、バリデーション等) 場合に `2` へ上げる。

### SVG path parser サブセット

commonMain に同梱する parser は
`M m L l H h V v C c S s Q q T t Z z` をサポート。楕円弧 (`A a`) は
意図的に除外 — JIS L 0201 記号で必要なものはなく、円はベジェ 4 本で
近似 (ベクタエディタの export もそうなっている)。未対応文字は文字を
名指しして throw。オペランドのない命令は silent drop ではなく throw
(truncated export で stroke が失われる事故を防ぐ)。

### シンボル内部の単位正方形座標

シンボル path は `viewBox 0 0 1 1`、**y-down** (SVG 準拠) で描画。
Renderer が draw 時にセル矩形へ affine transform する。これは
`docs/ja/chart-coordinates.md` の y-up 編み図座標とは独立 —
編み図レイアウトは y-up (編み手視点)、シンボル内部は y-down
(SVG 互換)。境界は renderer の transform が吸収する。

### プラットフォームレンダリング方針

`PathCommand` は commonMain の sealed interface IR。Android は Compose
`Path.moveTo / lineTo / cubicTo / quadTo / close` で描画、iOS は SwiftUI
`Path` API で同等実装。parser は純ロジックなので `expect/actual` 不使用。
`expect/actual` は Phase 31 で Canvas 描画エントリポイントにだけ使う予定。

## Phase 30.5 追補 — シンボル出典ポリシー

現実の編み手は JIS L 0201 を「唯一の規範」として扱っていない、という
ユーザフィードバックを受けた追補。JIS はあくまで複数ある参照コーパスの
一つであり、本プロダクトの正典ではない。

### プロダクトが認識する出典

「同梱する」→「ユーザが持ち込む」順:

1. **JIS L 0201:1995** — デフォルトカタログの基礎リファレンス。
   `jis.*` ID で同梱。発行時の JIS グリフに忠実に描画し、各出版社の
   独自バリエーションには合わせない
2. **日本の主要出版社** — 日本ヴォーグ社・文化出版局は JIS と異なる
   de-facto 変種 (線の太さ、交差の向き、補助マークの有無) を持つ。
   これらは `jis.*` では同梱 **しない**。同じ編目について別グリフを
   同梱する場合は ID を `std.<house>.<stitch>` とする
   (例: `std.vogue.k2tog-r`)。Phase 30 デフォルトカタログには 1 件も
   含まれない
3. **Craft Yarn Council (CYC)** — `std.cyc.*` を予約。Phase 30 では
   同梱しない。追加は Phase 30.x タスク
4. **ユーザ定義記号** — `user.<uuid>.*` 配下。オーサリング UI は後続
   Phase で着手。ID 名前空間と `CompositeSymbolCatalog` の拡張点は
   現時点で設計しておく
5. **合成・派生記号** — 基本ストローク (縦線=表、横線=裏、斜線=減目方向、
   丸=かけ目など) の組み合わせで作る記号。Phase 30 カタログはこれらを
   網羅しようとしない。**カタログ網羅性は非目標**。長いテールは
   ユーザが合成 / インポートする前提

### このポリシーの帰結

- **`jis.*` ID は出版社バリエーションの有無に依らず安定**。JIS を
  引用する日本語パターンがそのまま動き続ける。出版社バリエーションは
  別 ID で同梱し、原典とのラウンドトリップ忠実性を保つ
- **デフォルトカタログは網羅的ではないし、そうと主張もしない**。
  Phase 30 棒針セットは JIS 収録の編目のみ。かぎ針 / アフガン /
  機械編みも同様のスコープ。どの規格にも載らない記号は同梱対象外
- **出典間の衝突はユーザに委ねる**。`knitter` エージェントが出典の
  不一致を提示し、`product-manager` がカタログ枠に値するか判断し、
  いずれでも決着しない場合はユーザにエスカレート
- **カタログ拡張は非破壊**。`SymbolCatalog` は interface。将来
  `CompositeSymbolCatalog(default, user, imported)` で複数カタログを
  fan-in できる。`DefaultSymbolCatalog` と `jis.*` ID には手を入れない
- **Phase 30 の視覚検証は Phase 31 まで据え置く**。Viewer がないまま
  記号セットを増やすと、設計バグが全カテゴリに複製される。順序は
  Viewer → Phase 30 JIS セットの knitter レビュー → 次に拡張する
  カテゴリ (かぎ針 / アフガン / 機械編み) の選定

### バイリンガルラベルの取り扱い

すべてのカタログエントリに JA / EN ラベルを持つ。EN 名が出典で揺れる
場合 (対称減目の "k2tog" / "SSK" / "K2tog tbl" など) は CYC 推奨表記を
ラベル、コミュニティ変種を `aliases` に保持。JA ラベルは JIS 正式名
を既定とし、現行日本語パターンでヴォーグ社 / 文化出版局表記が
明確に優勢なケースに限って上書きする。

## Addendum — Phase 30.1 レビュー結果 (2026-04-18)

本 ADR が予約した Knitter 主導の視覚レビューは Phase 30.1 で実施した。
全所見は [`docs/ja/symbol-review/phase-30.1.md`](../symbol-review/phase-30.1.md)。
ここで記録する決定事項:

- **次 catalog category = `jis.crochet.*`（Phase 30.2）**。4 要因
  （商用頻度 / JIS + CYC ギャップ / ユーザセグメント解放 / 実装コスト）
  採点で crochet 18、afghan 8、machine 11。JIS L 0201 Table 2 が
  既にかぎ針記号を規定しており、JP 商用パターン volume は一度に解放
  できる最大のオーディエンス。
- **Geometry 修正 = Phase 30.1-fix（geometry-only PR）**。35 glyph 中
  ~16 に craft-correctness 懸念あり。影響の大きい 4 点:
  (a) 裏目バー幅が広すぎ（0.1→0.9 ではなく中央短線 ~0.3→0.7）、
  (b) 交差記号で over / under 描き分け不足（両対角線 unbroken のため
  右上 / 左上が視覚的に同一）、
  (c) SSK / k2tog / p2tog / k3tog 方向性 glyph が対称逆 V（JIS の
  「縦棒 + 斜線 1 本」ではない）、
  (d) `jis.knit.kfb` の JA ラベル `ねじり増し目` が実際には
  twisted-M1 を指しており kfb と不一致。
  これらはレビュードキュメント §5 へのユーザ回答後に focused な
  `KnitSymbols.kt` PR として出す。
- **`DefaultSymbolCatalog` は意図的に非網羅**。Phase 30 カタログは
  first pass。出版社固有の variants（Vogue JP / 文化 / CYC 限定 glyph）
  は `std.<house>.*` / `std.cyc.*` / `user.*` 名前空間ポリシーに従って
  JIS コアの上にオーバーレイとして入る。`jis.knit.*` の既存エントリは
  編集しない。

本 addendum は本 ADR 本文のデータモデル / 記号 ID 方式を変更しない。

## Addendum — Phase 30.1-fix: `std.cyc.kfb` と geometry 修正 (2026-04-18)

Phase 30.1 レビューで予告した geometry-only 追随が Phase 30.1-fix に
入った。名前空間ポリシーの実運用を示す 2 点を記録しておく:

- **初の `std.cyc.*` エントリが着地**。`CycSymbols.kt` が
  `KnitSymbols.kt` と並んで配置され、`DefaultSymbolCatalog` では
  `KnitSymbols.all + CycSymbols.all` の結合で供給する。現時点の唯一の
  エントリは `std.cyc.kfb`。JIS L 0201-1995 は `kfb` 記号を標準化して
  おらず、従来の `jis.knit.kfb` エントリは出典を誤って申告していた
  ため、正しい名前空間に移した。以降も CYC 限定・出版社固有の glyph は
  `jis.*` を編集せずここへ追加する（Phase 30.5 シンボル出典ポリシー
  準拠）。
- **`SymbolDefinition` に検索用 alias**。`SymbolDefinition` に
  `aliases: List<String>` を追加した。Phase 30.1-fix で投入した値は
  1 件だけで、`jis.knit.twist-r` に `ねじり増し目` を alias として
  登録した。これにより Phase 30.2 以降の dictionary 検索 UI で JA の
  慣用語「ねじり増し目」から正しい JIS glyph（右ねじり目 = twisted-M1）
  を引けるようになる。カタログ ID 方式と主ラベルの扱いは変わらない。

Geometry 修正（裏目バーを短く・セル下部に；交差記号の下線を
セル中央で break；減目 glyph を `縦棒 + 射線` に描き直し、交差点下の
縦棒 tail を残す）は Phase 30.1 レビュードキュメント §5 への回答に
従って `KnitSymbols.kt` の path data 編集に留めており、データモデル
には影響しない。

## Addendum — Phase 30.2: `jis.crochet.*` カタログ first pass (2026-04-18)

Phase 30.1 の next-category 推奨に沿って、Phase 30.2 でかぎ針シンボル
カタログを追加した。

- **新名前空間が稼働**。`CrochetSymbols.kt` が `KnitSymbols.kt` /
  `CycSymbols.kt` と並び、`jis.crochet.*` 名前空間で登録。
  `DefaultSymbolCatalog` は
  `KnitSymbols.all + CycSymbols.all + CrochetSymbols.all` を結合する。
- **first pass 25 glyph**。JIS L 0201 Table 2 の中核と、CYC / JP 出版社の
  高頻度 composite glyph を対象: ch / sl-st / sc / hdc / dc / tr / dtr /
  qtr、sc2tog / sc3tog / dc2tog / dc3tog、dc-cluster-3 / dc-cluster-5
  (widthUnits=2)、inc-sc / inc-dc、magic-ring、fpdc / bpdc、sc-fl / sc-bl、
  popcorn、picot-3、turning-ch、ch-space。
- **データモデルとレンダラは据え置き**。既存の `SymbolDefinition` スキーマ、
  unit-square 座標規約、`SymbolCategory.CROCHET` enum をそのまま使う。
  カテゴリ順の不変条件（UI フィルタは `SymbolCategory.entries` を回す）は
  新たに catalog テストで担保した。
- **Knitter advisory は Phase 30.2-fix へ**。Knitter レビュー
  （`docs/ja/symbol-review/phase-30.2.md`）で major 2 件 + minor 13 件が
  提起された。major 2 件 — `sl-st` が塗りでなく線で描かれている
  （レンダラ側; `SymbolDefinition.fill: Boolean` 追加を推奨）、
  `dc2tog/dc3tog` のトップバーが 24dp でクラスタの楕円と判別困難 —
  と、ユーザ判断が必要な open question 10 件は Phase 30.2-fix で処理する。
  Phase 30.3 の種として reverse-sc / puff / hdc-cluster が推奨されている。

本 addendum は記号 ID 正規表現、座標規約、カタログインタフェースを
変更しない。

## Addendum — Phase 30.2-fix: Knitter advisory 対応 (2026-04-18)

Phase 30.2-fix は Phase 30.2 knitter advisory が flag した major 2 件と
load-bearing な minor を解決する。各 open question に対する team consensus
は `docs/ja/symbol-review/phase-30.2.md` §5 に `**Team:**` マーカーで記録
されており、ここでは ADR-008 への形状的影響を要約する。

- **`SymbolDefinition.fill: Boolean = false` field 追加。** Phase 29 以降
  初の非ラベル field 追加。両 renderer（Compose 側
  `SymbolDrawing.drawSymbolPath`、SwiftUI 側 `StructuredChartViewerScreen`
  と `SymbolGalleryScreen` の `drawSymbolPath` / `drawSymbol`）で honour
  される。default は既存の全 glyph を stroke 維持し、Phase 30.2-fix では
  `jis.crochet.sl-st` のみを `fill = true` に切替える。意図的に additive
  な変更で、既存のシリアライズ済みシンボルメタデータ（カタログは in-process
  なのでまだ存在しない）に対して forward-compatible。
- **`jis.*` namespace = JIS authority** が解決済み question で再確認された。
  `jis.*` namespace 内で JIS と CYC が geometry で disagree した場合、team
  は JIS 形を採る。具体的には `jis.crochet.sc` を CYC `+` から JIS `×` に、
  `sc2tog` / `sc3tog` の crossbar を mid-cell（`y≈0.55`）から apex 寄り
  （`y≈0.30`）へ、`popcorn` を CYC radial-ray circle から JIS pentagon に
  切替えた。これは Phase 30.5 addendum で既に文書化されたポリシーおよび
  ADR-008 §6 の symbol-id 命名規則と整合する。
- **`dc2tog` / `dc3tog` の top-bar を広げる** ことで open horizontal line
  が外側 stroke の端点を visibly 越えて延びるようになり、24dp 時に dominant
  だった cluster vs decrease の disambiguation bug を完全解消。
- **`fpdc` / `bpdc` を quadratic-curve C-wrap で再描画**（旧 right-angle
  L-shape の置換）。方向は CYC（front=右、back=左）を維持。JIS は方向に
  ついて silent。
- **Q8 `turning-ch` parameterisation は Phase 30.3 へ deferral。** per-stitch
  variants（`turning-ch-2 … turning-ch-6`）は既存の `dc-cluster-3 / -5`
  per-count 規約と一致し、renderer 変更不要なので、`count`-slot
  parameterisation ではなく Phase 30.3 catalog 追加と一緒に出荷する。
  今日のモデル変更は不要。
- **Q9 `inc-sc` / `inc-dc` の V-spread を維持。** JIS は composite glyph
  について silent。V-spread は increase として明白に読め、最も一般的な
  Western chart rendering と一致する。
- **Phase 30.3 scope 確定。** reverse-sc / puff stitch / hdc-cluster が
  top-3 追加。明示的に優先順位を上げない限り Phase 32 editor MVP の後ろに
  queue する。

本 addendum は記号 ID 正規表現、座標規約、JSON スキーマを変更しない。
新規 `fill` field は catalog 内部のものであり、chart ドキュメントには
シリアライズされない（cell は symbol を `id` で参照し、definition では
ない）。

## 参考

- ADR-001: Supabase をバックエンドに採用
- ADR-003: オフラインファースト同期戦略
- ADR-004: Supabase スキーマ v1
- ADR-007: 編み図オーサリングへの方針転換
- `docs/ja/chart-coordinates.md` (Phase 29)
- `docs/ja/symbol-review/phase-30.1.md` (Phase 30.1)
- `docs/ja/symbol-review/phase-30.2.md` (Phase 30.2)
- JIS L 0201:1995 編目記号 (参照コーパス、規範ではない)
- Craft Yarn Council chart symbol reference (`std.cyc.*` に予約)
- 日本ヴォーグ社 / 文化出版局の慣習 (`std.<house>.*` に予約)
