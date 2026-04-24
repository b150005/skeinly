# ADR-011: Phase 35 高度チャートエディタ + 極座標 UX

> Source: [English version](../../en/adr/011-phase-35-advanced-editor.md)

## ステータス

Proposed

## コンテキスト

Phase 32 でチャートエディタ MVP を出荷した — タップ配置オーサリング、
undo/redo、パラメトリック入力、技法/読み方メタデータ。Phase 34
（ADR-010）で矩形グリッドチャート向けのセグメント単位進捗
（todo/wip/done）を追加した。

2 つの大きな UX ギャップが残っており、Phase 36 以降の
ディスカバリ／コラボ作業をブロックしている:

1. **極座標チャートが閲覧のみで編集不可、かつ進捗も無い。**
   データモデル (`ChartExtents.Polar(rings, stitchesPerRing)`) は
   Phase 29 から存在するが、エディタは `RECT_GRID` を強制し、
   Phase 34 のオーバーレイは
   `message_segment_progress_polar_deferred` という「Phase 35
   で対応予定」通知を出すだけである。これはあみぐるみ、ドイリー、
   そしてあらゆるかぎ針ラウンドチャートを進捗ループから除外する —
   対象ユーザーのかなりの割合を占める。

2. **エディタに対称化・一括操作が無い。**
   4 方向対称のレースパネルは現状 4 倍のタップ配置を要する。28 目
   ケーブルの 12 段目を編み終えたユーザーは、段全体を done にする
   ためにステッチごとに長押しまたはダブルタップする必要がある。
   どちらも日常的なワークフローで、欠落は「紙切れ傷」であって
   エッジケースではない。

3 つの補助項目（レイヤー操作、スナップグリッド、グリッドサイズ
ピッカー）は CLAUDE.md の Phase 35 ロードマップ行で触れられている。
本 ADR で MVP 採用 vs. 延期を決着する。

引き継がれる制約:

- **ADR-008 §7 / Phase 32.1:** `contentHash` は描画の同一性のみを
  対象とする。対称化操作は `layers` を書き換え → `contentHash` は
  再計算される（期待動作）。進捗はプロジェクトスコープ（ADR-010）
  であり、ハッシュには含まれない。
- **ADR-010 §4:** `ProjectSegmentEntity.cell_x / cell_y` は
  `ChartCell.x / y` と同じ値を**再解釈せずに**保持する。本 ADR では
  極座標における列の解釈を明示する — 列のセマンティクスは
  変更せず、読み手が適用する規約を定義する。
- **ADR-009:** パラメトリック記号は対称化セマンティクスの対象外 —
  その技法的意味はパラメータに縛られ、幾何に縛られない。
- **Phase 32 MVP 不変条件:** `EditHistory` は描画のみ（ADR-008 §7 +
  Phase 32 完了ノート）; メタデータ変更は undo スロットを消費しない。
  極座標編集はこれを維持する — チャート作成時の極座標↔矩形切替は
  メタデータ操作（履歴に入らない）だが、極座標チャート内のセル配置
  は描画操作で履歴に入る。

## エージェントチーム審議

4 つの論点は相互作用するためチーム会議は ADR 全体で 1 回とした
（極座標が対称化操作の一般化の仕方を決め、一括 row-done は対称化
操作の配信経路に乗り、グリッドサイズピッカーは極座標エクステント
オーサリングと連動する）。

### 出席者

- **architect:** 極座標ジオメトリは `cellScreenRect` +
  `GridHitTest` と並列の純粋ヘルパー (`PolarCellLayout`) に置くべき
  であり、レンダラ内に置くべきではない。これにより 2 つのレンダラ
  (Compose + `ScopedViewModel` 経由の SwiftUI ブリッジ) が三角関数を
  再導出せずに済み、テストを pure-Kotlin に保てる。対称化操作は
  `List<ChartLayer>` に作用する `SymmetryOp` ユースケースに置くべき
  で、ビューアとエディタは draft 状態を変異させずに対称化プレビュー
  を取得できるべきである。一括 row-done は MVP で新しいリポジトリ
  bulk API を導入すべきではない — per-segment ループを再利用する。
  bulk API は Phase 37 のマルチライタマージで初めて本質的に必要に
  なり、今設計を固めると誤った形になる。

- **product-manager:** 極座標が鍵。これが無いと本プロダクトの
  「編み手」は「平編みのみの編み手」を意味し、JP 市場の約半分
  （あみぐるみは JP で巨大、ドイリー + トップダウンヨークセーター
  が続く）を切り捨てることになる。対称化操作はレースチャート
  オーサリングにおいてテーブルステークス — 延期するとディスカバリで
  おもちゃに見える。一括 row-done は既存ユーザーの QOL 改善。
  レイヤー操作 + グリッドリサイズは当然あるべき機能 — ユーザーは
  無いと離脱する。スナップグリッドは非ゴール: チャート自体がグリッド
  であり、スナップする対象が無い。

- **knitter:** 極座標の角度原点は JP ラウンドチャート規約に合わせ
  **12 時方向・時計回り**でなければならない（JIS L 0201 §5 図は
  この読み方、日本のあみぐるみ本は例外なくこの読み方）。
  3 時方向・反時計回りは米国かぎ針教科書の規約で、これは違和感を
  生む。水平対称下で非対称記号は**必ず** mirror-variant 参照を経由
  すべき — 左傾ケーブルを JIS カタログに存在しないミラーグリフに
  無言で幾何反転すると、一見妥当に見えて編めないチャートが生成
  される。拒否するかプロンプトするかで、推測はしない。回転対称に
  ついて: 表目と裏目の入れ替えは回転ではない — バー方向は技法的に
  意味がある。ニットのバーを 90° 回転しても裏目の凹凸にはならず、
  これらを回転ペアとして扱うとチャートを静かに破損させる。
  ミラーペアは軸ごと; 軸を混同しない。

- **implementer:** `SymbolDefinition` にオプショナルの
  `mirrorHorizontal: String?` フィールドが必要（ミラー変種 id を
  指す）。ケーブルや方向性のある減目（k2tog ↔ ssk など）に対して
  設定し、対称なグリフ（ニットバー、パール点、ヤーンオーバー）は
  null のままにする。対称化操作ユースケースは UI がプロンプト
  できるように、ミラー不可能セルを列挙する型付き結果を返すべき。
  極座標ヒットテストは中心穴（内側）タップを考慮する必要がある:
  `radius < innerRadius` → null。平面の「グリッド外 = null」規約と
  揃える。iOS の SwiftUI ジェスチャチェーンは Phase 34 で
  `.onTapGesture` と `.onLongPressGesture` の二重発火バグがあった —
  極座標オーバーレイでも再現する可能性が高いので、Phase 34 の
  `longPressActive` 抑制フラグパターンを再利用する。

- **ui-ux-designer:** 一括「段を done に」の可視性はツールバーの
  ボタンではない方が良い — ツールバー領域は狭く、既存の
  サイクルタップ／長押しの可視性が操作モデルを示唆している。
  段番号ラベル（段番号が描画される時に見える; ADR-008 §5 の段番号
  規約）の長押しに置く。極座標の等価物はリング番号ラベル（リングの
  「12 時」径に沿って描画）の長押し。対称化操作は単一のツールバー
  オーバーフローメニュー（`Mirror horizontal`、`Mirror vertical`、
  `Rotate 180°`）の背後に置く — いずれも新しい `EditHistory`
  エントリを書く一発操作で、undo が自然に 1 ステップ戻る。
  グリッドサイズピッカーは、オーバーフローメニューからの
  `Resize chart` ダイアログで出荷する; 新しいエクステント外のセルは
  トリム（グリッド外保持はしない）され、カウントは確認前にダイアログで
  表示する。

### チームが決着させた決定点

1. **極座標の角度原点** → 12 時、時計回り（knitter、強い主張）。
2. **表目↔裏目の回転対称扱い** → 対称化操作ではない（knitter、強い主張
   — 技法的に意味があり幾何ではない）。
3. **ミラー変種参照** → 新規 `SymbolDefinition.mirrorHorizontal:
   String?`; ミラー不可能セルは無言スキップではなく UI プロンプトに
   上げる（knitter + implementer 合意）。
4. **一括 row-done のリポジトリ形状** → MVP は per-segment upsert
   ループ; 新 bulk API 無し（architect、強い主張; product-manager 譲歩）。
5. **スナップグリッド** → 非ゴール（product-manager; architect 同意）。
6. **レイヤー操作 + グリッドリサイズ** → MVP（product-manager;
   architect はスコープ注釈付きで同意、下記を参照）。
7. **一括 row-done の可視性** → 段／リング番号ラベルの長押し
   (ui-ux-designer; implementer は iOS ジェスチャ競合再利用に言及)。

## 決定

### 1. 極座標の規約と変換

`ChartExtents.Polar(rings, stitchesPerRing: List<Int>)` は
セルを `(stitch_index, ring_index)` として解釈する:

- `ChartCell.x = stitch_index` — リング内の角度位置、
  `0 ≤ x < stitchesPerRing[y]`。
- `ChartCell.y = ring_index` — 半径位置、`0 ≤ y < rings`、最内側の
  リングが `y = 0`。Y は外側に増える、ラウンドの「進捗は外に伸びる」
  読み方に整合。

これは **規約であってデータ変更ではない。** 列の型と `ProjectSegment`
ストレージは ADR-010 §4 から変更なし。ドキュメントレベルの
`extents.stitchesPerRing[y]` がリング `y` のステッチ数の正式値;
`ChartCell.width` は極座標では無視される（極座標のセルは常に 1×1 —
ウェッジをまたぐ配置は Phase 35.x フォローアップ、下記 §9 を参照）。

新規純粋ヘルパー `domain/chart/PolarCellLayout.kt`:

```kotlin
object PolarCellLayout {
    data class Layout(
        val cx: Double,
        val cy: Double,
        val innerRadius: Double,
        val ringThickness: Double,
    )

    /** セル (x = stitch, y = ring) のスクリーン空間ウェッジ境界。 */
    data class Wedge(
        val innerRadius: Double,
        val outerRadius: Double,
        val startAngleRad: Double,  // 12 時 = -π/2; 時計回り正
        val sweepAngleRad: Double,
    )

    fun wedgeFor(
        stitch: Int,
        ring: Int,
        extents: ChartExtents.Polar,
        layout: Layout,
    ): Wedge

    /** 記号グリフ配置用のセルウェッジの直交中心。 */
    fun cellCenter(
        stitch: Int,
        ring: Int,
        extents: ChartExtents.Polar,
        layout: Layout,
    ): Pair<Double, Double>

    /** セルの「ローカル上」が半径方向外側を向くような回転（ラジアン）。 */
    fun cellRadialUpRotation(
        stitch: Int,
        ring: Int,
        extents: ChartExtents.Polar,
    ): Double
}
```

ヒットテスト逆変換は `GridHitTest` の新メソッド（新オブジェクトには
しない — 矩形・極座標共通で screen→cell 呼び出しサイトを揃えるため）:

```kotlin
fun hitTestPolar(
    screenX: Double,
    screenY: Double,
    extents: ChartExtents.Polar,
    layout: PolarCellLayout.Layout,
): Cell?
```

アルゴリズム:

1. `dx = screenX - cx`、`dy = screenY - cy`。
2. `radius = √(dx² + dy²)`。
3. `radius < innerRadius` または
   `radius ≥ innerRadius + rings * ringThickness` → null（中心穴タップ
   または最外リング外側）。
4. `ring = floor((radius - innerRadius) / ringThickness)`。
5. `theta = atan2(dy, dx) + π/2`、`[0, 2π)` に正規化（12 時 = 0、
   時計回り正にシフト — 編み手の規約に整合）。
6. `stitch = floor(theta / (2π / stitchesPerRing[ring]))`。
7. `Cell(x = stitch, y = ring)` を返す。

極座標セル内の記号レンダリングは `cellCenter` を中心とする**内接
正方形**を用い、`cellRadialUpRotation + cell.rotation` で回転する。
これによりグリフは歪まず（JIS ラウンドチャート規約に整合）、内側
リングのグリフが外側リングより小さく描画される代償を伴う。
内接正方形の一辺 = `min(ringThickness, 2 * r * sin(π /
stitchesPerRing[ring]))`。グリフのアンチエイリアスは、サイズ差が
目立ちすぎる場合 Phase 35.x のポリッシュ課題とする。

### 2. セグメント単位進捗オーバーレイの極座標拡張

Phase 34 のオーバーレイロジックは既に `(layerId, cellX, cellY)` で
`ProjectSegment` 行をイテレートしている。極座標対応はペイント
パスとヒットテストの差し替え:

- `ChartViewerScreen` が `extents is ChartExtents.Polar` を検出し、
  平面矩形オーバーレイの代わりに `drawPolarSegmentOverlay`
  （`DONE` で塗りつぶしウェッジ、`WIP` でストロークウェッジ）を
  呼び出す。
- タップは `hitTest` ではなく `hitTestPolar` を経由する; それ以降
  (`ToggleSegmentState`、`MarkSegmentDone`) は不変。
- Phase 34 の `message_segment_progress_polar_deferred` 通知を削除。

既存 Phase 34 の iOS ジェスチャ競合抑制（`longPressActive` フラグ、
0.3 秒ウィンドウ）はそのまま再利用。

### 3. 対称化 / ミラーコピーのエディタ操作

新規 `SymmetryOp` ユースケース（リポジトリ操作ではない — 対称化は
データプリミティブではなくエディタ変換）:

```kotlin
sealed interface SymmetryAxis {
    data object Horizontal : SymmetryAxis      // 垂直軸まわりの反転
    data object Vertical : SymmetryAxis        // 水平軸まわりの反転
    data object Rotate180 : SymmetryAxis       // H + V
    data class PolarAngular(val aboutStitch: Int) : SymmetryAxis  // 光線まわりのミラー
    // Rotate90 / Rotate270 は意図的に不在 — §7, ADR-009 §0 の非対称グリフ
    // 回転; 一般回転は技法的に無意味。
}

class SymmetrizeLayersUseCase(
    private val symbolCatalog: SymbolCatalog,
) {
    data class Result(
        val mirrored: List<ChartLayer>,
        val unmirrorableCells: List<UnmirrorableCell>,  // ミラーペアを持たない記号のセル
    )
    data class UnmirrorableCell(val layerId: String, val x: Int, val y: Int, val symbolId: String)

    suspend operator fun invoke(
        layers: List<ChartLayer>,
        extents: ChartExtents,
        axis: SymmetryAxis,
    ): Result
}
```

挙動:

- **矩形水平**（垂直軸まわり反転）: `(x, y) → (maxX - (x - minX), y)`;
  非対称記号は `SymbolDefinition.mirrorHorizontal` を参照; null →
  `unmirrorableCells` に追加し、`mirrored` に元のまま保持。
- **矩形垂直**（水平軸まわり反転）: `(x, y) → (x, maxY - (y - minY))`;
  `rotation` を符号反転（`(360 - rotation) mod 360`）。
- **矩形 Rotate180**: H + V の合成; `rotation = (rotation + 180) mod 360`。
- **極座標角度反転**（ステッチ番号 `aboutStitch` を通る光線まわりの
  ミラー）: `(x, y) → ((2·aboutStitch - x) mod stitchesPerRing[y], y)`;
  `rotation` を符号反転。
- 元のレイヤーは**保持**される — ユースケースは新しい `layers`
  リストを返す。エディタはミラー版のセルを新しい*ターゲット*レイヤー
  （デフォルト）に追加するか、ソースレイヤーにマージする（ユーザーが
  opt-in した場合）。これにより参照用として元の半分をロックした
  レイヤーに残せる。

UI フロー:

1. ユーザーがツールバーオーバーフローをタップ → `Mirror horizontal`
   （または vertical / rotate180 / angular）。
2. angular の場合: ユーザーがキャンバス上で「ミラー光線」をタップ
   （ハイライトされた径を可視化）; `hitTestPolar` 経由でステッチ番号を
   解決。
3. `SymmetrizeLayersUseCase` が実行される; `unmirrorableCells` が
   非空なら `(layerId, x, y, symbolId, symbolLabel)` を並べたダイアログ
   を出し、3 択を提示: **Skip unmirrorable**（現状挙動 — 元を保持）、
   **Cancel**、（将来）**Edit mirror pair**（ミラーペアオーサリング
   UI へディープリンク — Phase 35.x）。
4. 確認後、draft レイヤーを置換し、`EditHistory` エントリを記録
   （標準の `HistoryEntry.LayersSnapshot` — 対称化操作は 1 スナップ
   ショットで、undo が 1 ステップ戻る）。

新規 `SymbolDefinition` フィールド:

```kotlin
data class SymbolDefinition(
    // ... 既存フィールド ...
    val mirrorHorizontal: String? = null,
)
```

Phase 35 で投入すべきミラーペアデータ:

- `jis.knit.cable.2-2-right` ↔ `jis.knit.cable.2-2-left`（全ケーブル
  ファミリー）。
- `jis.knit.k2tog` ↔ `jis.knit.ssk`。
- `jis.knit.k3tog` ↔ `jis.knit.sssk`。
- かぎ針クラスタの傾き — JIS L 0201 §6 に従って knitter が列挙。

対称なグリフ（ニットバー、パール点、ヤーンオーバー、ほとんどの
かぎ針基本目）は `mirrorHorizontal = null` のまま; ユースケースは
null を「記号は自己対称」と解釈し、`unmirrorableCells` に**追加
しない**。明示的な `selfSymmetric: Boolean = false` フラグも検討
したが却下（null チェックと情報が同じで重複）。

垂直ミラーはミラーペア参照を使わない — JIS には垂直ペアが無い
（「上下逆ニット」は存在しない）。垂直ミラーは `rotation` を
符号反転し、全セルに無条件で適用する。

### 4. 一括「段を done に」

UX 可視性: **段番号ラベルの長押し**（矩形）または
**リング番号ラベルの長押し**（極座標）。両レンダラとも
ラベル領域はチャート脇に描画される（ADR-008 §5 の規約）;
Phase 35 でその周囲にヒットターゲットを追加。

実装経路（エージェントチーム審議 §1 の決定）:

```kotlin
class MarkRowSegmentsDoneUseCase(
    private val repository: ProjectSegmentRepository,
    private val getStructuredChart: GetStructuredChartByPatternIdUseCase,
    // ... 加えて既存の dispatcher と ownerId 依存 ...
) {
    suspend operator fun invoke(
        projectId: String,
        row: Int,   // チャート y 座標（矩形）またはリング番号（極座標）
    ) {
        val chart = getStructuredChart(...)
        val visibleCellsInRow = chart.layers
            .filter { it.visible }
            .flatMap { layer -> layer.cells.filter { it.y == row }.map { cell -> layer.id to cell } }
        // per-segment upsert ループ — 新 bulk API は無し。ADR-011 §4 のトレードオフ参照。
        visibleCellsInRow.forEach { (layerId, cell) ->
            repository.upsert(
                ProjectSegment(
                    id = ProjectSegment.buildId(projectId, layerId, cell.x, cell.y),
                    projectId = projectId,
                    layerId = layerId,
                    cellX = cell.x,
                    cellY = cell.y,
                    state = SegmentState.DONE,
                    // ...
                )
            )
        }
    }
}
```

**トレードオフ: per-segment ループ vs. 新 bulk API。**

| 軸 | ループ（採用） | bulk API |
|---|---|---|
| LOC | UseCase 内 ~30 | Repo/DataSource/Remote/Supabase 横断 ~150 |
| 同期の形 | N 個の `PendingSync` 行、`(entity_type, entity_id)` で coalesce | 1 行が N 要素ペイロードを保持、新しい coalesce ルールが必要 |
| オフライン整合性 | 自明 — 各セグメントは独立 | リプレイ時にトランザクショナル適用が必要、または partial-apply セマンティクス |
| ピアの Realtime バースト | N 個の個別 INSERT/UPDATE イベント | 1 個の「行更新」イベント、クライアント側デコード新規 |
| Phase 37 diff セマンティクス | 各セグメントが独立して diff 可能 | bulk ペイロードはデコードしない限り不透明な blob |
| 失敗リカバリ | 既存 `SyncExecutor` によるセグメント単位リトライ | partial ペイロード向けの新リトライ形状 |

bulk API が正解なのは**コラボレーションバースト**パターンが効く
場合 — 例えば Phase 37 の「チームメンバー X が 42 段目を done に
した」を 1 イベントとして diff 可能な 1 単位で扱うケース。Phase 35
ではループが同期ワイヤ量以外の全軸で優っており、典型的な段
（10–30 ステッチ）での同期量のコストはローカル DB でサブミリ秒、
Realtime 上では不可視である。極座標リングは 200 ステッチを超える
ことがある（ドイリーの外側ラウンド）が、そこでも 200 個の
PendingSync 行はローカルで <100ms で適用でき、ユーザーが再実行
すればネットワーク前に coalesce される。

`upsertBatch` bulk API は diff 形状が問題を強制する **Phase 37 に
延期**。ここに文書化しておくことで後日スクラッチから再発見されない。

### 5. レイヤー操作

MVP スコープ:

- レイヤー追加（空）。
- レイヤー削除（セルがあれば確認ダイアログ）。
- レイヤーリネーム。
- レイヤーの並び替え（レイヤー一覧パネルのドラッグハンドル）。
- 可視性トグル（`ChartLayer.visible` — データモデル済み; 現状ビューアは
  尊重する）。
- ロックトグル（`ChartLayer.locked` — データモデル済み; エディタは
  ロックレイヤーへの配置を拒否し、パレットで該当レイヤー行を
  グレーアウト）。

すべてのレイヤー操作は `EditHistory` エントリである（描画同一性の
変更 — `contentHash` はレイヤー順とセル内容に依存）。

レイヤー一覧パネル UI: 右側ドロワー、両プラットフォームで
スワイプ in。各行に: ドラッグハンドル、可視性トグル、ロックトグル、
名前（タップでインラインリネーム）、オーバーフローメニュー（削除）。

### 6. グリッドサイズピッカー（「Resize chart」）

入口: エディタオーバーフローメニュー → `Resize chart`。

ダイアログ内容:

- **矩形:** Width + Height 数値入力（最小 1、最大 256 各）。
- **極座標:** Rings 数値入力; リングごとのステッチ数は「一律
  （全リングに N を適用）」または「リングごとリスト」（高度、
  Phase 35.x — MVP は一律のみ）。

トリム挙動:

- `x > newMaxX` または `y > newMaxY`（矩形）のセルは削除。
- 極座標では `y >= newRings` または
  `x >= newStitchesPerRing[y]` のセルは削除。
- ダイアログは確認前に「N セルが削除されます」のカウントを表示。
- リサイズは 1 個の `EditHistory` エントリ（トリムを含む）。

エクステントは縮小**または**拡大する。拡大は自由（トリム無し）。
縮小で trim>0 なら明示確認が必要。

### 7. Phase 35 MVP に明確に入れないもの

- **スナップグリッドトグル。** チャートが**既にグリッド**。グリッド外に
  スナップする対象が無い。
- **任意回転（90°/270°/自由角度）。** 大半の記号グリフは技法的に
  意味のある回転を持たない — 90° 回転されたニットバーは有効な
  JIS 記号ではなく、チャートビューアは意味ありげに見えて意味の
  無いものをレンダリングしてしまう。回転モチーフが必要な
  パターン作者は回転対応パーツから合成できる。Phase 36 以降、
  ディスカバリが回転済みインポート需要（例: 横置きタペストリー
  チャート）を表面化した時点で再訪する。
- **リングごと可変ステッチ数の編集。** データモデルは対応済み
  (`stitchesPerRing: List<Int>`) だが、編集には専用 UI（リングごと
  エディタ）が要る。MVP は一律リング; 可変リングを含むインポート
  チャートは正しくレンダリングされる。
- **極座標ウェッジをまたぐセル。** `ChartCell.width` は極座標 MVP で
  無視される。2 角度位置をまたぐステッチ（例: 前ラウンドの 2
  ステッチを消費する減目）は、その意味を記号 id に託した単一セル
  減目記号として 1 ステッチ位置に配置する（JIS 規約に整合）。
- **bulk upsert リポジトリ API。** §4 のトレードオフ参照。
- **90°/270° の回転対称操作。** §7 の第 1 項目参照。
- **ミラーペアエディタ**（カスタム記号向けに新しいミラーペアを
  オーサリング）。カタログはキュレート済み; カスタム記号オーサリング
  は Phase 36 以降。

## 影響

### ポジティブ

- 極座標ユーザーはオーサリングと進捗追跡の両方でブロック解消。
  ビューアの「Phase 35」通知が消える。
- レースオーサラーは 4 方向対称パネルで ~4 倍のタップ作業を削減。
- 行単位の進捗マーキングにより、ステッチごとにタップしたくない
  ユーザーにもステッチ単位のリズムに合う進捗ループになる。
- レイヤー操作 + グリッドリサイズは、ディスカバリが露呈するはずの
  「エディタがおもちゃ」ギャップを埋める。
- 記号カタログレイヤーでのミラーペア参照により、将来の記号追加
  （Phase 36 のカスタム記号を含む）はコードではなくデータを通して
  ミラーセマンティクスを継承する。

### ネガティブ

- `PolarCellLayout` は 2 番目の座標変換コードパスを追加する。
  セルに触れるヒットテストまたはレンダラアサーションは、矩形と極座標
  の両方を単体テストで覆う必要がある。ジオメトリで ~30 件の新規
  commonTest が見込まれる。
- `SymmetrizeLayersUseCase` は、エディタ UI が処理しなければならない
  新種のエラー表面（ミラー不可能セル）を導入する。テストマトリクスが
  増える。
- エージェントチームは bulk upsert API を明示的に却下した; Phase 37
  のコラボは diff セマンティクスが要求した時点でその設計を再開する
  必要がある。後日 Phase 37 の担当者が「この道は検討済みで延期した」
  と分かるようここに文書化しておく。
- 記号カタログのミラーペアオーサリングは手作業（knitter +
  implementer のペア作業）。MVP 時点で 20–40 ペアの投入を見込む;
  カタログ拡張に伴い増加。
- 極座標の内接正方形グリフサイジングは、内側リングが外側リングより
  小さくレンダリングされる。MVP では許容（商用チャートがそうして
  いる）; Phase 35.x のポリッシュ課題で最小サイズフロア + グリフ
  スレッディングにより補正可能。

### ニュートラル

- `ChartExtents.Polar` データ型は不変。
- `ProjectSegment` スキーマは ADR-010 から不変。
- `contentHash` 計算は不変 — 対称化操作は新しい `layers` リストを
  生成し、ハッシュは自然に再計算される。
- `EditHistory` セマンティクスは不変 — 対称化とリサイズはそれぞれ
  1 スナップショット; レイヤー操作は操作ごと 1 スナップショット;
  undo/redo の挙動は Phase 32 の期待と一致。

## 検討した代替案

| 代替案 | 長所 | 短所 | 不採用理由 |
|---|---|---|---|
| 極座標の角度原点: 3 時、反時計回り | 米国の数学規約に整合 | JIS ラウンドチャート規約と不一致 — すべての日本あみぐるみ本は 12 時時計回り | knitter が拒否; JP 市場が主対象 |
| 極座標グリフレンダリング: パスをウェッジに歪める | セル領域を最大活用 | 内側リングでグリフが判読不能; 商用チャートはどこもこうしない | 内接正方形が JIS 規約に整合し綺麗にレンダリング |
| 対称化操作: ソースレイヤーを in-place 変異 | UX が単純 | 元の半分を参照として残せない; 即時コミット強制 | 新リスト返却で任意性を保ち、`EditHistory.LayersSnapshot` を自明に再利用 |
| 対称化操作: 非対称記号を無言で幾何反転 | ミラー不可能用 UI 面不要 | 一見妥当に見えて実は読めないチャートを生成 — ユーザーはプロジェクト中盤まで気付かない | knitter が拒否; スキップオプション付き明示プロンプトが技法的に正しい |
| ミラー変種: `selfSymmetric: Boolean` フラグ代替で `mirrorHorizontal: String?` = null を使用しない | 意図の明示 | null チェックと情報が重複; 全グリフが 2 フィールドを持つことに | 却下; 単一 nullable フィールドで等価 |
| 一括 row-done: 新規 `upsertBatch(segments)` リポジトリ API | バッチあたり PendingSync 1 行; Realtime 1 イベント | Supabase 経路で bulk upsert が必要; Phase 37 diff に対して不透明; partial-apply 失敗セマンティクス | Phase 37 で diff セマンティクスが問題を強制するまで延期; ループはローカル ≤1ms |
| 一括 row-done の可視性: ツールバーボタン | 発見しやすい | ツールバーが狭い; 既存の長押しサイクルタップ可視性が「長押し = 拡張操作」を既に示唆 | 段ラベル長押しは既存ジェスチャ語彙の拡張 |
| スナップグリッドトグル | 他エディタで馴染み | チャートがグリッド; スナップする非グリッド状態が無い | 非ゴール |
| グリッドサイズピッカー: 軸ごとに別ダイアログ | 単画面が単純 | ユーザーは 1 操作で両軸をリサイズすることがほとんど | 1 ダイアログに両入力が mental model に整合 |
| 90°/270° の回転対称 | ベクタツール並みのエディタ機能 | 大半の記号（ニットバー、ケーブル傾斜）で技法的に無意味; 無効な JIS チャートを生成 | 却下; 技法整合 > エディタパリティ |
| MVP でリングごと可変ステッチ数 | データモデル完全 | 存在しない専用リングごとエディタ UI が必要 | MVP は一律リング; 可変リングのインポートチャートは正しくレンダリングされる; 可変リングのオーサリングは Phase 35.x |

## 参考文献

- ADR-007: チャートオーサリング移行（Phase 35 を advanced editor の場と位置付け）
- ADR-008: 構造化チャートデータモデル（`ChartExtents.Polar`、`contentHash` 不変、座標規約）
- ADR-009: パラメトリック記号（パラメトリック記号のミラーセマンティクスはここでは対象外）
- ADR-010: セグメント単位進捗（Phase 34 の極座標延期を本 §2 で解決）
- `docs/en/chart-coordinates.md`（本 ADR で極座標に拡張される y-up 矩形規約）
- Phase 32 完了ノート（エディタ MVP 不変条件、`EditHistory` スコープ）
- Phase 34 完了ノート（セグメントオーバーレイ、iOS ジェスチャ競合パターン、極座標延期通知）
- JIS L 0201 Table 2 + §5–§6（ラウンドチャートの角度規約、かぎ針記号ファミリー）

## Phase 35.2f 補遺 — レイヤー操作 MVP 実装判断

§5 はスコープ（追加 / 削除 / 名称変更 / 並び替え / 可視性 / ロック
+ `EditHistory` エントリ + 右側ドロワーパネル）を確定済み。Phase 35.2f
はそのスコープを実装する。Agent チーム協議で確定した 5 つの実装判断を
新 ADR ではなく本節に記録する（§5 の決定に対する実装詳細の解決であり、
新たなアーキテクチャ導入ではないため）。

1. **ロックは配置・対称操作の書き込み先・セグメント進捗タップを抑止する。**
   ロック済みレイヤーは読み取り専用として、(a) エディタ側の `PlaceCell`、
   (b) 対象レイヤーがロック済みの場合の `ApplyRotationalSymmetry` /
   `ApplyReflection`、(c) ビューア側の `ToggleSegmentState` /
   `MarkSegmentDone` を抑止する — ビューアでロック済みレイヤー配下のセル
   をタップしても無視される（オーバーレイは既存進捗を読めるよう描画し続
   ける）。ロックは `Undo` / `Redo` を抑止しない — 履歴が真実であり、各
   スナップショットはロック状態自体を持つため、ロック切替を跨いだ undo
   は以前のロック値を復元する。ロックは可視性トグル・名称変更・削除を
   抑止しない — これらはロック済みレイヤーのメタデータ管理を意図的に
   許可するレイヤーリスト操作である。

2. **明示的な `selectedLayerId` が Phase 32 の `layers[0]` ハードコードを置き換える。**
   `ChartEditorState.selectedLayerId: String?` を配置先の単一の真実の源と
   する。初期状態はデフォルト `"L1"` レイヤーを指す（観測可能な挙動は
   変更なし）。ロードは `chart.layers.firstOrNull()?.id` に再代入する。
   `AddLayer` は新レイヤーを自動選択する。`RemoveLayer` は隣接レイヤー
   を再選択する（直前のレイヤー、なければ次のレイヤー、リストが空になる
   なら null）。`selectedLayerId` が null であることは有効なドラフト状態
   であり、`PlaceCell` はその場合レイヤーを自動作成せず no-op とする。
   自動作成は初期状態で 1 回のみ発生する。`SelectLayer` は
   `pendingParameterInput` がセットされている間はブロックされる —
   ダイアログを開いたレイヤー以外に parametric コミットが着地するのを
   防ぐため。

3. **並び替えジェスチャ: 行全体ではなく専用ハンドル上の長押し + ドラッグ。**
   行全体の長押しはタップ選択と競合する。並び替えハンドルは Compose
   では Material 3 標準の `DragHandle` アイコン、SwiftUI ではドロワー
   展開時に `.editMode` を切り替える `List.onMove`。並び替えは 1 件
   の `EditHistory` エントリを書き込む（移動前のレイヤーリスト全体の
   スナップショット）。

4. **レイヤーリストパネル: 右側セミモーダルドロワー、ツールバーアイコンで起動。**
   両プラットフォーム: オーバーフローメニューのレイヤーアイコンをタップ →
   ドロワーが右からスライドイン。Compose: `ModalNavigationDrawer` に
   `drawerState` + 右端アンカー、`gesturesEnabled=false`（右からのスワイプ
   は Android predictive-back と競合するため）。SwiftUI:
   `.sheet(isPresented:)` + `.presentationDetents([.medium, .large])` —
   iOS ユーザーの期待に一致するネイティブイディオム。行の内容: ドラッグ
   ハンドル・可視アイコン・ロック錠・名前（`OutlinedTextField` /
   `TextField` を差し替えたインラインリネーム）・オーバーフローメニュー
   （削除）。削除確認ダイアログはレイヤーにセルが存在する場合のみ表示
   （空レイヤーはサイレント削除）。

5. **スキーマバンプなし。** `ChartLayer.visible` と `ChartLayer.locked`
   はすでにスキーマ v2 のデータクラスにデフォルト値付きで存在する — Phase 32
   は dormant のまま出荷した。Phase 35.2f はそれらをエディタ UI に露出し、
   配置 + ビューア側タップ制御を通して結線する。ADR-008 のスキーマ v3
   バンプは不要。`contentHash` は `layers` のシリアライゼーションに依存
   するため、レイヤー操作の編集は自然にハッシュを再計算する — §5 の
   「drawing identity changes」の文言と一致する。

### Phase 35.2f 明示的スコープ外

- **対称操作のコードパス上でのロック尊重** — `!locked` フィルタヘルパーは
  35.2f に着地する（配置側が消費）。Phase 35.2g の対称操作のレイヤー
  ターゲット指定は、対称操作がレイヤー選択可能なターゲットを持つように
  なった時点で同じヘルパーを消費する（Phase 32.2b は選択に関わらず
  ターゲットを `layers[0]` にハードコードしている。Phase 35.2g は
  対称操作ターゲットを `selectedLayerId` に統一する）。
- **カスタムレイヤー色 / ティント** — Phase 36+ の discovery-polish 作業。
- **レイヤーマージ** — コラボレーション時代。Phase 37+。
- **レイヤー別不透明度スライダ** — Phase 36+ polish。MVP では可視性は
  boolean。
