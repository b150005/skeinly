# ADR-007: 行カウンタから構造化編み図オーサリングへの方針転換

> Source: [English version](../../en/adr/007-pivot-to-chart-authoring.md)

## ステータス

Accepted

## コンテキスト

Phase 1〜27b で行カウンタアプリとして機能完成。Supabase 同期、共有、写真ベースの編み図画像、進捗写真、公開パターンの discovery、ストア提出準備までが揃い、Phase 27c で v1.0 リリースを控えていた。

リリース直前で 2 つの事象が方針再評価を強制した:

1. **実機動作確認で iOS の重大バグが発覚** (commit `f65e308` で修正): Koin の `factory` スコープが SwiftUI View の `init` 毎に新しい ViewModel を生成する一方、`@StateObject` オブザーバは最初のインスタンスの state flow に固定される。Onboarding 画面の Next/Skip ボタンタップが孤立した ViewModel にイベントを送り、UI が凍結した。既存 E2E テストは launch argument で Onboarding をバイパスしていたため、Next/Skip のタップ経路は一度も検証されていなかった (品質ゲートの穴)。

2. **行カウンタという枠組みが製品ビジョンと合致しない**。本来のコアバリューは**編み図の作成と管理**であり、以下を含む:
   - 記号単位でのプログラム的編み図作成 (写真アップロードではなく)
   - レイヤー構造 + ポイント単位の進捗管理
   - Git 風コラボレーション (commit, branch, pull request)
   - 初心者向け記号辞典
   - 日英ローカライズ
   
   現状のデータモデルは `Pattern` エンティティに任意の画像 URL を添えるだけであり、上記機能は一切表現できない。ステッチ・レイヤー・編集履歴を表現する構造化スキーマが必要。

行カウンタモデルのまま v1.0 をリリースすると、拡張不能なデータ契約を公開インストールに固定することになる。将来スキーマ書き換えのたびに全ユーザを複数回マイグレートする負債が積み上がり、公開名 (Play Console パッケージ、App Store Connect bundle ID) も製品アイデンティティ確定前に固定される。

## 決定

1. **v1.0 公開リリースを無期延期する**。Phase 27c (ストア提出) をアクティブロードマップから削除。
2. **構造化編み図オーサリングへの製品リフレーミング**。行カウンタは編み図**内部**の進捗ポインタという副次機能に降格。
3. **新しいフェーズ構成 Phase 28〜40 を追加**: bundle ID 変更、構造化 chart データモデル、記号ライブラリ、chart viewer と editor、ポイント単位進捗、discovery と fork、Git 風コラボレーション (commit, branch, pull request)、クローズドベータ、最終的に公開 v1.0 リリース。
4. **chart 描画はネイティブ Canvas 二重実装**: Android は Compose Canvas、iOS は SwiftUI Canvas。共通 SVG ジェネレータや WebView ホストは採用しない。
5. **プレリリース期間中はローカル DB を使い捨て扱い**。プレリリースフェーズ間の破壊的マイグレーションを許容。Phase 39 のクローズドベータまで本番ユーザはゼロなので後方互換性は不要。

## 結果

### ポジティブ

- App Store/Play Console の公開名と bundle ID が固定される前に製品アイデンティティ (オーサリング、コラボレーション、オープンソース編み図) を確定できる。
- 本番ユーザがいない状態で最大のスキーマ・レンダリング書き換えを行えるため、マイグレーション負債が発生しない。
- 構造化 chart データモデルは Git 風コラボレーションのビジョン (ADR-TBD) を**二度目の書き換えなし**で実現可能にする。
- ネイティブ描画により、アプリで最も視覚的に複雑な画面でプラットフォームごとの最高のパフォーマンスが得られる。

### ネガティブ

- 公開リリースまで 4〜6 ヶ月 (パートタイム) の暦上遅延。
- Phase 27 の準備成果 (署名 CI、プライバシポリシ、スクリーンショットスキャフォールド、feature graphic) が一時的に塩漬け。破棄はしないが、方針転換期間中はユーザ価値を生まない。
- プラットフォーム別 Canvas 実装は共通レンダラと比較して chart viewer と editor の UI 工数が倍になる。

### ニュートラル

- 既存の行カウンタ、プロジェクト CRUD、進捗ノート、写真ベース編み図画像、共有、Supabase 同期はコードベースに残す。削除ではなく構造化 chart モデルを中心に再フレーミングする。
- Bundle ID 変更 (`com.skeinly.*` → `io.github.b150005.skeinly`) は chart 関連のリネーム工数と切り離すために Phase 28 に独立。

## 検討した代替案

| 代替案 | Pros | Cons | 不採用理由 |
|--------|------|------|-----------|
| 行カウンタモデルのまま v1.0 をリリースし、編み図オーサリングは v2.0 で追加 | 早期に公開可能、ユーザとフィードバックが早く得られる | 製品アイデンティティ確定前に公開名と bundle ID が固定される; 実ユーザデータに対する破壊的スキーママイグレーションが発生; v2.0 が別アプリに感じられる | Pre-PMF プロジェクトでは早期ユーザ価値よりマイグレーション負債と identity lock-in のリスクが上回る |
| 共通 SVG レンダラ (Compose Multiplatform Canvas) | chart 描画を一本化できる | CMP のグラフィックレイヤは成熟途上 (CMP 1.10.x); SwiftUI ユーザにとって非ネイティブな違和感; 将来の記号インタラクション (tap-to-edit) がネイティブでない canvas では難しい | chart はアプリで最も目立つ表面であり、実装経済性よりネイティブ忠実度を優先 |
| WebView + HTML Canvas (ammies 風) | コード再利用が最大化; 既存 web 実装を移植できる | モバイルでは非ネイティブなスクロール/ズーム、メモリフットプリント高、将来のオフラインファースト要件と相性悪 | ADR-001 で合意した "ネイティブ KMP" 戦略を骨抜きにする |
| v1.0 から Git 風コラボレーションを外し、オーサリングのみで早期リリース | スコープ縮小で公開が早まる | Git モデルこそが Ammies + KnitCompanion に対する差別化の肝であり、これなしの公開は弱い製品ストーリー | コラボレーションはフェーズ分割可 (Phase 37〜38) だが v1.0 スコープからは外さない |

---

## Amendment — 2026-05-10 (用語監査、v0.1.0 直前)

`audits/terminology-audit-2026-05-10.md` の決定に基づく用語ピボット。
詳細は EN 版 ADR の Amendment ブロックを参照。

**主要なリネーム** (本セッション 2026-05-10 適用):

| 旧 | 新 |
|---|---|
| Structured Chart / 構造化チャート | Chart / 編み図 |
| Fork / フォーク | Save a copy / コピーを保存 |
| Branch / ブランチ | Variation / アレンジ |
| Revision・Commit / リビジョン・コミット | Version / バージョン |
| Pull request / プルリクエスト | Suggestion / 提案 |
| Merge / マージ | Apply changes / 変更を反映 |
| Diff / 差分 | Comparison / 比較 |
| Discovery (EN) | Browse Patterns (EN); JA は既に「パターンを探す」で OK |

**Supabase migrations 026 + 027** で `chart_revisions` → `chart_versions`、
`chart_branches` → `chart_variations`、`pull_requests` → `suggestions`、
`pull_request_comments` → `suggestion_comments`、status enum value
`'merged'` → `'applied'`、`merge_pull_request` RPC → `apply_suggestion` を
適用済 (prod 反映済 2026-05-10)。

**検証ベース**: docs-researcher (T2) Round 1 + scoped Round 2 が
Craft Yarn Council, 日本ヴォーグ社 (tezukuritown.com), Brooklyn
Tweed, Stephen West, amu app 等の primary source で各リネームを
裏付けた。research-critic agent が Round 1 を独立 tool family
(WebFetch + GitHub) で再検証し 6/9 PASS を確認。

事前 v1 破壊的変更ポリシー (CLAUDE.md `### Planned — Phase 39`
HARD-GATE) により内部表名・status enum 値の変更を許容。
