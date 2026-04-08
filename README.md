# ECC Base Template

A framework-agnostic base repository template powered by [Everything Claude Code (ECC)](https://github.com/anthropics/claude-code). Use this template to start new projects with an agent team, TDD workflow, CI/CD, and standardized project structure already in place.

[English](#english) | [日本語](#japanese)

---

<a id="english"></a>

## English

### What's Included

- **Agent Team** — 14 specialized agents covering the full product lifecycle: planning, design, implementation, testing, and release
- **Claude Code Configuration** — `.claude/CLAUDE.md` with development workflow, testing standards, and code quality rules
- **CI/CD Pipelines** — Reusable GitHub Actions workflows for lint, test, build, and security scanning
- **DevContainer Template** — Commented template ready for customization per framework
- **Bilingual Documentation** — Technology guides in English (`docs/en/`) and Japanese (`docs/ja/`)
- **Community Health Files** — Issue templates, PR template, CODEOWNERS, Dependabot

### Agent Team

| Agent | Phase | Role |
|-------|-------|------|
| **orchestrator** | All | Coordinates the team. Analyzes issues, plans work, delegates to specialists |
| **product-manager** | Planning | PRD, user stories, acceptance criteria, backlog prioritization |
| **market-analyst** | Planning | Market research, competitor analysis, user segment identification |
| **monetization-strategist** | Planning | Business model design, pricing strategy, revenue analysis |
| **ui-ux-designer** | Design | UI/UX design, usability review, accessibility compliance |
| **architect** | Design | System architecture, technology decisions, ADR creation |
| **implementer** | Build | Code implementation following architecture specs and TDD |
| **code-reviewer** | Quality | Code quality, maintainability, and standards review |
| **test-runner** | Quality | Test execution, coverage reporting, TDD support |
| **linter** | Quality | Static analysis and code style enforcement |
| **security-reviewer** | Quality | Vulnerability detection, secret scanning, OWASP Top 10 |
| **performance-engineer** | Quality | Profiling, bottleneck identification, optimization |
| **devops-engineer** | Release | CI/CD, deployment strategy, release management |
| **technical-writer** | Release | Documentation, changelog, bilingual docs maintenance |

All agents are ecosystem-agnostic. They detect the project's language and framework at runtime by reading `.claude/CLAUDE.md` and project manifest files.

### Quick Start

1. Click **"Use this template"** on GitHub
2. Choose your repository name and visibility
3. Clone your new repository
4. Follow the [Template Usage Guide](docs/en/template-usage.md) to customize

### Documentation

| Document | Description |
|----------|-------------|
| [ECC Overview](docs/en/ecc-overview.md) | What is ECC and how it works |
| [TDD Workflow](docs/en/tdd-workflow.md) | Test-driven development with ECC agents |
| [CI/CD Pipeline](docs/en/ci-cd-pipeline.md) | GitHub Actions workflows explained |
| [DevContainer](docs/en/devcontainer.md) | Development container setup guide |
| [GitHub Features](docs/en/github-features.md) | CODEOWNERS, Dependabot, templates, Actions |
| [Template Usage](docs/en/template-usage.md) | How to use and customize this template |

### License

[MIT](LICENSE)

---

<a id="japanese"></a>

## 日本語

### 含まれるもの

- **エージェントチーム** — 14体の専門エージェントがプロダクトの全ライフサイクル（企画・設計・実装・テスト・リリース）をカバー
- **Claude Code 設定** — `.claude/CLAUDE.md` に開発ワークフロー、テスト基準、コード品質ルールを定義
- **CI/CD パイプライン** — lint、test、build、セキュリティスキャンの再利用可能な GitHub Actions ワークフロー
- **DevContainer テンプレート** — フレームワークに応じてカスタマイズ可能なコメント付きテンプレート
- **バイリンガルドキュメント** — 英語 (`docs/en/`) と日本語 (`docs/ja/`) の技術ガイド
- **コミュニティヘルスファイル** — Issue テンプレート、PR テンプレート、CODEOWNERS、Dependabot

### エージェントチーム

| エージェント | フェーズ | 役割 |
|-------------|---------|------|
| **orchestrator** | 全体 | チームを統括。Issue を分析し、作業を計画し、各専門エージェントに委任 |
| **product-manager** | 企画 | PRD、ユーザーストーリー、受け入れ基準、バックログ優先順位付け |
| **market-analyst** | 企画 | 市場調査、競合分析、ユーザーセグメントの特定 |
| **monetization-strategist** | 企画 | ビジネスモデル設計、価格戦略、収益分析 |
| **ui-ux-designer** | 設計 | UI/UX 設計、ユーザビリティレビュー、アクセシビリティ準拠 |
| **architect** | 設計 | システムアーキテクチャ、技術選定、ADR 作成 |
| **implementer** | 実装 | アーキテクチャ仕様と TDD に基づくコード実装 |
| **code-reviewer** | 品質 | コード品質、保守性、規約準拠のレビュー |
| **test-runner** | 品質 | テスト実行、カバレッジ報告、TDD サポート |
| **linter** | 品質 | 静的解析とコードスタイルの強制 |
| **security-reviewer** | 品質 | 脆弱性検出、シークレットスキャン、OWASP Top 10 |
| **performance-engineer** | 品質 | プロファイリング、ボトルネック特定、最適化 |
| **devops-engineer** | リリース | CI/CD、デプロイ戦略、リリース管理 |
| **technical-writer** | リリース | ドキュメント、CHANGELOG、バイリンガルドキュメント管理 |

すべてのエージェントはエコシステム非依存です。`.claude/CLAUDE.md` とプロジェクトのマニフェストファイルを読み取り、実行時に言語やフレームワークを検出します。

### クイックスタート

1. GitHub で **「Use this template」** をクリック
2. リポジトリ名と公開設定を選択
3. 新しいリポジトリをクローン
4. [テンプレート利用ガイド](docs/ja/template-usage.md) に従ってカスタマイズ

### ドキュメント

| ドキュメント | 説明 |
|-------------|------|
| [ECC 概要](docs/ja/ecc-overview.md) | ECC とは何か、どのように機能するか |
| [TDD ワークフロー](docs/ja/tdd-workflow.md) | ECC エージェントによるテスト駆動開発 |
| [CI/CD パイプライン](docs/ja/ci-cd-pipeline.md) | GitHub Actions ワークフローの解説 |
| [DevContainer](docs/ja/devcontainer.md) | 開発コンテナのセットアップガイド |
| [GitHub の機能](docs/ja/github-features.md) | CODEOWNERS、Dependabot、テンプレート、Actions |
| [テンプレート利用ガイド](docs/ja/template-usage.md) | テンプレートの使い方とカスタマイズ方法 |

### ライセンス

[MIT](LICENSE)
