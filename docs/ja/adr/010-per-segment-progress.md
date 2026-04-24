# ADR-010: セグメント単位進捗データモデル

> Source: [English version](../../en/adr/010-per-segment-progress.md)

## ステータス

Proposed

## コンテキスト

ADR-007 §決定 2 で行カウンターは「チャート内の進捗ポインター」へと
再定義された。Phase 29–32 で構造化チャート（データモデル、ビューア、
パラメトリック入力・技法/読み方メタデータ付きの Editor MVP）が
揃った。現行の行カウンター（`Project.currentRow` +
`ProgressEntity` の履歴）はチャートを認識しない: どのレイヤーの
どのステッチを編んでいるかを知らずに整数を進めるだけである。

Phase 34 ではこのギャップを埋める。ユーザーが構造化チャートを開い
たとき、個別のステッチ（セグメント）を `todo` / `wip` / `done` の
いずれかに印付けでき、その状態がデバイス間で可視化されるように
する必要がある。これが Phase 31/32 のレンダリング作業を正当化
するユーザー価値のループである — これが無ければ構造化チャートは
静的な文書に過ぎず、進捗は従来どおり行カウンターでしか測れない。

本 ADR が解決する設計上の問いは **セグメント進捗はどこに格納
されるべきか** である。

次の 5 つの力が働く:

1. **カーディナリティ.** `Pattern` は 0..1 の `StructuredChart` を
   持つ。`Project` は `Pattern` を参照する。複数ユーザーがそれぞれ
   同じ（公開）`Pattern` → 同じ `StructuredChart` に対して自分の
   `Project` を持ちうる。進捗はユーザー × プロジェクト単位で独立
   していなければならない。
2. **contentHash の不変性（ADR-008 §7）.** `StructuredChart.contentHash`
   は Phase 37 の diff / コラボのために描画内容の同一性を保護する。
   進捗の更新でこれを無効化してはならない。
3. **RLS の形状.** `StructuredChart` は公開されうる（pattern.visibility
   = public）。一方、そのチャート上の進捗は各ユーザーに対し非公開
   でなければならない。同一行に 2 つの可視性は共存できない。
4. **Realtime の粒度.** 編み物の操作は基本的にステッチ 1 目ずつ。
   セル単位 Realtime イベントは UX に合う。チャート全体を投げる形
   はノイズになり Phase 37 の多書き手シナリオとも相性が悪い。
5. **AI インポート時のチャートサイズ（ADR-008 §コンテキスト）.**
   数百行 × 数十目/行 → 1 チャートに数千セグメントが起こりうる。
   ストレージは「チャートサイズに比例」ではなく「進捗量に比例」で
   スケールしなければならない。

## 決定

### 1. `project_segments` テーブル新設（粒度: プロジェクト単位、チャート単位ではない）

セグメント進捗は新テーブルに行として保存し、`chart_id` ではなく
`project_id` で scope する。進捗のプロジェクト単位カーディナリティ
に一致し、チャート公開と直交させる。

ローカル（SQLDelight）:

```sql
CREATE TABLE ProjectSegmentEntity (
    id TEXT NOT NULL PRIMARY KEY,
    project_id TEXT NOT NULL,
    layer_id TEXT NOT NULL,
    cell_x INTEGER NOT NULL,
    cell_y INTEGER NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('wip', 'done')),
    updated_at TEXT NOT NULL,
    owner_id TEXT NOT NULL DEFAULT 'local-user',
    FOREIGN KEY (project_id) REFERENCES ProjectEntity(id) ON DELETE CASCADE,
    UNIQUE(project_id, layer_id, cell_x, cell_y)
);
```

リモート（Supabase `migrations/013_project_segments.sql`）:

```sql
CREATE TABLE public.project_segments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES public.projects(id) ON DELETE CASCADE,
    layer_id TEXT NOT NULL,
    cell_x INT NOT NULL,
    cell_y INT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('wip', 'done')),
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(project_id, layer_id, cell_x, cell_y)
);
```

RLS: オーナーのみ CRUD 可能。公開読み取りポリシーは無い — 進捗は
常に private である。親 `Pattern` が public でも各ユーザーの
進捗行は `owner_id` で隔離される。

Realtime パブリケーションに追加し、2 台目端末がセグメント単位の
更新を受信できるようにする。

### 2. 状態列挙: `todo` は行の不在で表現する

```kotlin
@Serializable
enum class SegmentState {
    @SerialName("wip") WIP,
    @SerialName("done") DONE,
}
```

行はユーザーが操作したセグメントに対してのみ存在する。行が無い
セグメントはデフォルトで `todo`。これでテーブルサイズは **
チャートサイズではなく進捗量に比例する** — 力 5 に対応。

帰結: 「todo に戻す」は `state = 'todo'` への更新ではなく行削除で
実装される。同期セマンティクスが単純になり、通信ペイロードが
小さくなり、ファントム行ライフサイクルが発生しない。

### 3. セグメント同定: 合成 id + タプル UNIQUE

主キーは合成文字列 `id`。`UNIQUE(project_id, layer_id, cell_x,
cell_y)` 制約で 1 プロジェクト 1 セグメントあたり最大 1 行を保証。

合成 id が必要な理由: `PendingSync.entity_id` は TEXT 単一列で、
既存 `SyncExecutor` は単一 id でパラメータ化されているため。決定
論的 id の形式は `"seg:<projectId>:<layerId>:<x>:<y>"`:

- 決定論的 id なら同セグメントへのダブルタップの冪等性が既存の
  `PendingSync` `(entity_type, entity_id)` マージにそのまま畳み
  込める（「これは新規行か？」を DB ラウンドトリップで判定する
  必要なし）。
- ローカル／リモートで同一 id なので `SyncExecutor` の upsert-or-
  insert 分岐が単純化する。

### 4. 座標規約は `ChartCell` と一致

`cell_x` / `cell_y` は `ChartCell.x` / `y` と同じ規約を用いる —
y-up、最下段が y=0（ADR-008 §5 と `docs/en/chart-coordinates.md`）。

`CoordinateSystem.POLAR_ROUND` では `ChartCell.x/y` は `(ring,
position)` 等を符号化する。`ProjectSegmentEntity.cell_x/y` もその
値をそのまま持つ。Polar セグメント UX は Phase 35（Editor 上級）で
扱う。Phase 34 は rect-grid を対象。

### 5. 同期プロトコル: 既存エンティティと同経路

- `PendingSync.EntityType` に `PROJECT_SEGMENT` を追加。
- セグメント状態トグル → `local.upsert(segment)` → `syncManager.syncOrEnqueue(PROJECT_SEGMENT, id, UPSERT, json)`。
- todo に戻す → `local.deleteById(segment.id)` → `syncManager.syncOrEnqueue(PROJECT_SEGMENT, id, DELETE, "")`。
- `PendingSync` 既存マージで同一セグメントの連打はネットワーク前に畳まれる。
- `RemoteSyncOperations` に `ProjectSegment` 分岐を既存の upsert /
  delete スイッチへ追加するだけ。新規基盤は不要。

オフラインファースト: `Progress`, `Pattern` 等と同一。

### 6. Realtime サブスクリプション範囲

- `StructuredChart` がリンクされた `Project` を開いたときに購読。
- フィルタ: `owner_id = auth.uid()`（RLS 整合）。
- クライアント側でチャートドキュメントレベルのフィルタは不要 —
  RLS によりサーバー側で scope 済み。
- 既存 `RealtimeChannelProvider` を利用。既存 3 チャネル（projects /
  progress / patterns）と並べて 1 チャネル追加。

### 7. `contentHash` は不変

ADR-008 §7 と Phase 32.1 追補のとおり、`contentHash` は描画同一性
のみを保護する。進捗は別テーブルに住み、チャートドキュメントに触れ
ないため不変は自明に維持される。

### 8. ドメイン層の形状

```kotlin
@Serializable
data class ProjectSegment(
    val id: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("layer_id") val layerId: String,
    @SerialName("cell_x") val cellX: Int,
    @SerialName("cell_y") val cellY: Int,
    val state: SegmentState,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("updated_at") val updatedAt: Instant,
)

interface ProjectSegmentRepository {
    fun observeByProjectId(projectId: String): Flow<List<ProjectSegment>>
    suspend fun upsert(segment: ProjectSegment): Result<Unit>
    suspend fun resetSegment(projectId: String, layerId: String, cellX: Int, cellY: Int): Result<Unit>
    suspend fun resetProject(projectId: String): Result<Unit>
}
```

UseCase（Phase 34 最小セット）:

1. `ObserveProjectSegments(projectId)` — ビューアオーバーレイ用 Flow
2. `ToggleSegmentState(projectId, layerId, x, y)` — タップで循環 todo → wip → done → todo
3. `MarkSegmentDone(projectId, layerId, x, y)` — 長押し／スイープ用の明示的 done
4. `ResetProjectProgress(projectId)` — プロジェクト全セグメントを初期化

既存行カウンター UI が使う「現在作業中位置」ポインターは
ViewModel 層で派生する（最初の `wip`、無ければ `StructuredChart.readingConvention`
の読み順で最初の `todo`）。ストレージには保存しない。ポインターと
状態テーブルの整合バグを構造的に排除するため。

### 9. 既存行カウンターとの共存

`Project.currentRow` + `ProgressEntity` 履歴は **残す**。直交する:

- `currentRow` / 履歴: 行レベルの物語（「12 段目からケーブル交差
  を撮影」）。チャートが無くても有用。
- `ProjectSegment`: `StructuredChart` にリンクされたプロジェクト
  向けのステッチレベル状態。

チャートが未リンクのプロジェクトは今日と完全に同じ振る舞い。

### 10. Phase 34 スコープ境界

Phase 34 が提供するもの:

- ドメインモデル + シリアライゼーション
- SQLDelight マイグレーション + `PendingSync` 列挙拡張
- Supabase マイグレーション 013（`project_segments`、RLS、Realtime、インデックス）
- Local/Remote DataSource + Mapper + コーディネーターリポジトリ
- `SyncExecutor` の `PROJECT_SEGMENT` 対応
- 上記 4 つの UseCase + ViewModel 状態形状
- チャートビューアのセグメントオーバーレイ（Compose + SwiftUI）: タップでトグル、長押しで done
- プロジェクト詳細画面の進捗リセットアクション

Phase 34 が提供しないもの:

- Polar チャートセグメント UX（Phase 35）
- 「行まるごと done」一括操作（Phase 35）
- 進捗エクスポート／共有（Phase 36）
- 多書き手セグメント衝突解決（Phase 37+）
- 進捗トグルの undo/redo（スコープ外 — 進捗は「やったこと」であり編集履歴ではない）
- チャート作成者の「推奨編み順」オーバーレイ（Phase 35+）

## 帰結

### Positive

- チャートドキュメントは描画専用のまま、`contentHash` 不変が維持され、Phase 37 diff が綺麗に保たれる
- 進捗はユーザー × プロジェクト単位で、パターン共有時のクロスユーザーデータ漏洩リスクが無い
- 不在 = todo によりストレージは進捗量に比例し、AI インポートの巨大チャートでも初期コスト 0
- 同期経路は Phase 3b+/14/15 の既存プリミティブを再利用（PendingSync、SyncExecutor、RealtimeChannelProvider）。新規基盤不要
- 決定論的セグメント id によりダブルタップ冪等性を既存マージに畳み込める（DB ラウンドトリップ不要）

### Negative

- 新テーブル、新同期分岐、新 Realtime チャネルで Phase 29 テンプレートに準じた ~400 LOC の機械的配線
- Polar セグメント UX 先送りで Phase 34 は丸編み（amigurumi / doilies）で不完全。Phase 35 が Polar Editor の本拠地のため許容
- 決定論的 id 形式（`seg:<projectId>:<layerId>:<x>:<y>`）は構造を漏らす。ユーザー不可視 + オフライン合体を有効化するので許容。コメントで明記
- 「todo に戻す = DELETE」のため Realtime 購読側は INSERT を見ずに DELETE を見ることがありうる（ピア端末の todo→done→reset が INSERT+UPDATE+DELETE を発火）。クライアント側で `Map<SegmentKey, SegmentState>`（不在 = todo）としてストレージ層と同じ真値モデルで処理

### Neutral

- 既存 `Progress` / `ProgressEntity` 履歴セマンティクスは変更なし
- `Project.currentRow` 行カウンターポインターは変更なし
- チャートレベルの「% 完了」は `done_count / 可視レイヤー総セル数` で計算可能になるが Phase 34 では UI 未公開（後続ポリッシュ）

## 検討した代替案

| 代替案 | 利点 | 欠点 | 採用しなかった理由 |
|---|---|---|---|
| `ChartCell` にセグメント状態をインライン（`StructuredChart` 拡張） | 同期経路 1 本、アトミックなビューア更新 | カーディナリティ不整合 — チャートはパターン単位、進捗はプロジェクト単位; `contentHash` 不変を破壊; RLS 破壊（公開チャート + 私有進捗が 1 行に同居不可）; AI インポート巨大化が更に悪化 | 構造不整合、Phase 37 diff を殺す |
| チャートドキュメント内に並行 `progressLayers: List<ChartLayer>` | セルと状態の分離は保てる | 上と同じカーディナリティ／RLS 問題; `contentHash` 無効化; 進捗がチャート公開サイクルに結合 | 同上 |
| `chart_id` で scope する別テーブル | 対象チャートとの関係性が明確 | ユーザー × プロジェクトのカーディナリティを取り損ねる; Phase 36 fork で進捗まで fork される（誤り） | Project がユーザー進捗の正しいスコープ |
| 複合主キー `(project_id, layer_id, cell_x, cell_y)`（合成 id なし） | 正規化、冗長 id 無し | `PendingSync.entity_id` は単一 TEXT 列; 複合キーはスキーマ変更か encode/decode 層の追加を要求し、どのみち決定論的 id と同じアイデア | 決定論的合成 id は同じ情報で既存同期に互換 |
| `todo` を明示する 3 値列挙 | ストレージが対称 | ストレージが進捗ではなくチャートサイズにスケール; AI インポートで 1 プロジェクトあたり数千の todo 行が初日から発生 | 不在 = todo がストレージ効率の形 |
| イベントログ方式（追加専用の状態遷移） | Phase 37 準備が無料で付いてくる | 「セグメント X の現状態は？」がアグリゲートに; Phase 34 が必要としない複雑さを前倒し; 他全同期エンティティの「現状態」形状と不整合 | Phase 37 でコミット到来時に履歴を上に重ねる; Phase 34 は現状態を保存 |

## 参考

- ADR-007: 構造化チャート作成へのピボット（進捗ループをコアに据えた）
- ADR-008: 構造化チャートデータモデル（contentHash 不変、RLS 形状、座標規約）
- ADR-003: オフラインファースト同期戦略（PendingSync 合体、syncOrEnqueue）
- ADR-004: Supabase スキーマ v1（RLS ポリシーテンプレート）
- `docs/en/chart-coordinates.md`（y-up チャート座標規約）
- Phase 1.5 `ProgressRepository`（履歴セマンティクス — 置き換えず保持）
