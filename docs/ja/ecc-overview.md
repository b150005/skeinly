> このドキュメントは `docs/en/ecc-overview.md` の日本語訳です。英語版が原文（Source of Truth）です。

# Everything Claude Code (ECC) 概要

## ECC とは？

Everything Claude Code（ECC）は、エージェント、スキル、コマンド、フック、ルールからなるエコシステムで、Claude Code のソフトウェア開発能力を拡張します。汎用 AI アシスタントを、構造化されたワークフローを持つ専門的な開発環境に変換します。

## アーキテクチャ

ECC の設定は `.claude/` ディレクトリ（プロジェクトレベル）と `~/.claude/`（グローバル）に配置されます。主要なコンポーネントは以下の通りです：

### エージェント

エージェントは、それぞれ特定の役割に特化した AI ペルソナです。`.claude/agents/` または `~/.claude/agents/` に `.md` ファイルとして定義されます。

| エージェント | 役割 |
|-------------|------|
| **planner** | コーディング開始前に実装計画を作成します |
| **tdd-guide** | テスト駆動開発（テストを先に書く）を強制します |
| **code-reviewer** | コードの品質、セキュリティ、保守性をレビューします |
| **security-reviewer** | 脆弱性をスキャンします（OWASP Top 10、シークレット、インジェクション） |
| **architect** | システムアーキテクチャを設計し、技術的な決定を行います |
| **build-error-resolver** | ビルド失敗を診断し修正します |

エージェントは、タスクが専門分野に一致する場合に Claude Code によって自動的に呼び出されるか、Agent ツールを介して手動で呼び出されます。

### スキル

スキルは、特定の実装タスクに対する詳細なリファレンスドキュメントです。`.claude/skills/` または `~/.claude/skills/` に配置され、`/skill-name` 構文で呼び出されます。

例：
- `/tdd` — テスト駆動開発ワークフローを強制
- `/code-review` — 包括的なコードレビューを実行
- `/plan` — 実装計画を作成
- `/verify` — 検証チェックを実行

### ルール

ルールはコーディング標準と規約を定義します。階層構造を使用します：

```
.claude/rules/
├── common/           # 言語非依存の原則
│   ├── coding-style.md
│   ├── testing.md
│   ├── security.md
│   └── ...
└── typescript/       # 言語固有のオーバーライド
    ├── coding-style.md
    ├── testing.md
    └── ...
```

言語固有のルールは共通ルールを拡張し、オーバーライドできます。例えば、Go のポインタレシーバは一般的な不変性の推奨をオーバーライドします。

### フック

フックは Claude Code のイベントによってトリガーされる自動アクションです：

| フックタイプ | 実行タイミング | 例 |
|-------------|-------------|------|
| **PreToolUse** | ツール実行前 | パラメータの検証 |
| **PostToolUse** | ツール実行後 | 編集後のコード自動フォーマット |
| **Stop** | セッション終了時 | 最終検証の実行 |

フックは `.claude/settings.json` の `hooks` キーで設定されます。

### コマンド

コマンドは `/command-name` で呼び出される再利用可能なプロンプトです。`.claude/commands/` に `.md` ファイルとして配置されます。スキル（リファレンス素材）とは異なり、コマンドはアクション指向の指示です。

## 全体の仕組み

```
ユーザーリクエスト
    │
    ├─→ CLAUDE.md（プロジェクトコンテキスト）
    ├─→ ルール（コーディング標準）
    │
    ├─→ Planner エージェント（計画作成）
    ├─→ TDD Guide エージェント（テストを先に書く）
    ├─→ Code Reviewer エージェント（変更をレビュー）
    │
    ├─→ フック（自動フォーマット、自動 lint）
    │
    └─→ CI/CD（GitHub Actions がすべてを検証）
```

## ECC を始める

1. **Claude Code のインストール**: [公式インストールガイド](https://docs.anthropic.com/en/docs/claude-code/overview)に従ってください
2. **ECC のインストール**: ECC リポジトリをクローンしてインストーラーを実行するか、エージェント/スキル/ルールを手動で `~/.claude/` にコピーします
3. **プロジェクトごとの設定**: プロジェクト固有のコンテキストで `.claude/CLAUDE.md` を作成します
4. **エージェントの使用**: Claude Code はタスクに基づいて自動的に専門エージェントに委任します

## 参考リンク

このドキュメントで解説している技術の1次情報です：

- [Claude Code Overview](https://docs.anthropic.com/en/docs/claude-code/overview) — Claude Code 公式ドキュメント
- [Claude Code Configuration](https://docs.anthropic.com/en/docs/claude-code/settings) — 設定、CLAUDE.md、プロジェクト構成
- [Claude Code Hooks](https://docs.anthropic.com/en/docs/claude-code/hooks) — PreToolUse、PostToolUse、Stop フック
- [Claude Code Agent Tool](https://docs.anthropic.com/en/docs/claude-code/sub-agents) — エージェント（サブエージェント）の仕組み
