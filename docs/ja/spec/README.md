# Feature Spec

> 英語原典: [docs/en/spec/README.md](../../en/spec/README.md)

機能ごとの「現状」doc。各 spec は 1 つの機能が **今日** どう動いているかを記述 — ファイルパス、スキーマ、public contract、現状カーディナリティ。設計判断の *理由* は各 spec 末尾の ADR を辿る。

## インデックス

| Spec | 範囲 | 関連 ADR |
|---|---|---|
| [chart-editor.md](../../../.claude/docs/spec/chart-editor.md) | 編図オーサリング surface (パレット + キャンバス + 履歴 + 保存フロー) | ADR-007, 008, 009, 011, 013 |
| [suggestion-flow.md](../../../.claude/docs/spec/suggestion-flow.md) | Suggestion (PR) open / list / detail / コメント / close / apply / conflict resolution | ADR-012, 013, 014 |
| [collaboration-history.md](../../../.claude/docs/spec/collaboration-history.md) | `chart_versions` 追記専用 spine、バージョン履歴 / variants / 比較 / restore | ADR-007, 013 |
| [symbol-pack-delivery.md](symbol-pack-delivery.md) | 動的シンボルパック配信 — catalog manifest, payload storage, entitlement gate, sync manager, render-time lookup | ADR-016 |

> 上位 3 spec は歴史的経緯から `.claude/docs/spec/` にある (agent context として authored)。新規 spec は `docs/en/spec/` (+ JA mirror) に置く。両者を概念的に同一ディレクトリとして扱う。

## spec が正しいツールである時

「**この機能は今 `main` でどう見えるか**」が問いのとき — ファイルパス、データ spine、public contract、grep で再発見しないと貢献者が見落とす gotcha。

以下では spec を使わない:
- 設計判断の理由 → ADR
- 機能運用の手順 → [ops/](../ops/)
- per-symbol 作画規約や他の deep reference → 専用 reference doc (例: `symbol-review/`)

## spec hygiene

- spec は実装が doc から drift した時に更新する **唯一の non-test artifact**。drift と同じコミットで spec も update。ADR は *decision* が変わった時だけ
- spec は skim-friendly。ファイルマップやスキーマは表、データフローは図、prose は構造が不足な箇所のみ
- spec はベンダー / フレームワーク doc にリンクし、再記述しない。再記述はベンダー doc 進化に伴う tech debt
- 1 spec ~500 行以下が健全。それ以上は split を検討 (既存の chart-editor / suggestion-flow / collaboration-history 分割を参照)

## 横断参照

- システムレベル現状: [docs/ja/architecture.md](../architecture.md)
- 運用 runbook: [docs/ja/ops/README.md](../ops/README.md)
- 設計判断: [docs/en/adr/](../../en/adr/)
