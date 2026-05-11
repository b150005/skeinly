# 運用 Runbook

> Skeinly を運用するためのステップバイステップ手順。各ファイルは **1 タスクで自己完結** しているので、実行中にドキュメント間をジャンプしなくて済む。
>
> 英語原典: [docs/en/ops/README.md](../../en/ops/README.md)
>
> 「システムが今どうなっているか」は [architecture.md](../architecture.md) + [spec/](../../en/spec/) を参照。「なぜそう設計したか」は [adr/](../../en/adr/)。

## インデックス

### コンテンツ運用

| Runbook | 用途 |
|---|---|
| [content-publishing.md](content-publishing.md) | 新規シンボルパックの公開、既存パックのパッチ、ロールバック。Free + Pro 両層 |

### リリース運用

| Runbook | 用途 |
|---|---|
| [release.md](release.md) | タグ駆動リリース手順 — バージョン管理、検証、タグ push 時の挙動、アップロード後の手動操作 |
| [beta-testing.md](beta-testing.md) | クローズドベータテスター招待 (TestFlight + Play Internal + RevenueCat sandbox 設定) |

### 障害対応

| Runbook | 用途 |
|---|---|
| [incident-playbook.md](incident-playbook.md) | 症状別の障害モード一覧 + 一次トリアージ手順。シンボルパック DL / push 通知 / バグ報告送信 / Auth / RevenueCat |

### Secret / 認証情報の運用

| Runbook | 用途 |
|---|---|
| [../../en/release-secrets.md](../../en/release-secrets.md) | GitHub Secrets 21 個 + Edge Function secret 7 個のレジストリ。初回セットアップ手順 |
| [secrets-rotation.md](secrets-rotation.md) | secret ごとのローテーション手順。年次定期 or 漏洩疑い時 |

### インフラ

| Runbook | 用途 |
|---|---|
| [webhooks.md](webhooks.md) | Supabase Database Webhook 設定 (`notify-on-write` を駆動する 3 webhook) |
| [repo-policy.md](repo-policy.md) | ブランチ保護ルール、bypass メカニクス、ステータスチェックゲート |

## このディレクトリの規約

- **1 タスクで自己完結** — 1 runbook が 1 タスクをエンドツーエンドで完結させる。
- **コマンドは常に最新** — 機能コードが変わって runbook のコマンドが壊れる場合、同じコミットで runbook も直す。
- **ベンダー公式 doc は link で済ませる** — 手順の権威ソースが外部 doc (Supabase / Apple Developer / Google Play / GitHub Apps API) の場合、再記述せずリンクのみ。再記述するとベンダー doc 変更時の tech debt になる。
- **JA mirror ポリシー** — ops/ runbook は全て `docs/en/ops/` の英語版を持ち、JA はその翻訳。英語版が source of truth。

## 新しい runbook をいつ追加するか

以下のときに追加:
- 既存 runbook でカバーされていない繰り返しの運用タスク (例: GitHub App private key の年次更新) が発生した
- インシデント対応が定型のトリアージパスに収斂した
- ベンダー surface 変更で多段階手順が必要になった

以下では追加しない:
- 1 回きりのセットアップタスク → [vendor-setup.md](../../en/vendor-setup.md) に追記
- コードレベルの規約 → `spec/` または `~/.claude/rules/` に追記
