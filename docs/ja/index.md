# ドキュメント

> 英語原典: [docs/en/index.md](../en/index.md)
>
> ソース・オブ・トゥルースは英語版 (`docs/en/`)。Claude Code はコンテキスト節約のため英語のみ読み込みます。人間貢献者はどちらでも参照可。

## まずここから

- **プロジェクト参加 / 長期離脱後の復帰** → [architecture.md](architecture.md) — システムレベル現状
- **機能拡張** → [spec/](spec/) — 機能レベル現状
- **運用タスク** (コンテンツ公開・リリース・障害対応) → [ops/README.md](ops/README.md)
- **設計判断の追跡** → [adr/](../en/adr/) (英語のみのものも多い)

## ドキュメントの 4 lane

| Lane | 目的 | 場所 |
|---|---|---|
| **WHAT IS** | システム/機能の現状 | [architecture.md](architecture.md) (システム) + [spec/](spec/) (機能ごと) |
| **WHY** | 設計判断の理由 + 検討した代替案 | [adr/](../en/adr/) |
| **WHAT TO DO** | 運用手順 | [ops/](ops/) |
| **HISTORICAL** | どう辿り着いたか | [phase/](../en/phase/) + ADR revision history |

実装が ADR から drift したら spec / `architecture.md` を更新する。ADR は *decision* が変わった時だけ更新。

## 一般リファレンス

| ドキュメント | 説明 |
|---|---|
| [release-secrets.md](release-secrets.md) | GitHub Secrets + Edge Function secret のレジストリ。初回登録手順 |
| [vendor-setup.md](vendor-setup.md) | Apple Developer / App Store Connect / Universal Links 一発設定 |
| [i18n-convention.md](i18n-convention.md) | 5 個の i18n ソースをまたいだキー命名規則 |
| [tdd-workflow.md](tdd-workflow.md) | テスト駆動開発の方法論 |
| [chart-coordinates.md](chart-coordinates.md) | 編図座標系リファレンス |
| [privacy-policy.md](privacy-policy.md) | プライバシーポリシー原本 |
| [symbol-review/](symbol-review/) | フェーズ別シンボルデザインレビュー記録 |

## テンプレート / ECC リファレンス

| ドキュメント | 説明 |
|---|---|
| [ecc-overview.md](ecc-overview.md) | Everything Claude Code とは何か |
| [ci-cd-pipeline.md](ci-cd-pipeline.md) | GitHub Actions ワークフローと自動化 |
| [devcontainer.md](devcontainer.md) | 開発コンテナのセットアップ |
| [github-features.md](github-features.md) | CODEOWNERS, Dependabot, テンプレート, ブランチ保護 |
| [template-usage.md](template-usage.md) | このテンプレートからプロジェクトを作る方法 |
