> このドキュメントは `docs/en/ci-cd-pipeline.md` の日本語訳です。英語版が原文（Source of Truth）です。

# CI/CD パイプライン

## CI/CD とは？

CI/CD（継続的インテグレーション / 継続的デリバリー）は、コード変更のビルド、テスト、デプロイのプロセスを自動化するソフトウェア開発手法です。リポジトリにプッシュされたすべての変更が自動パイプラインをトリガーし、本番環境に到達する前に変更を検証します。

### 継続的インテグレーション（CI）

CI は、コードがプッシュされたりプルリクエストが開かれるたびに、自動的にチェックを実行します：

- **Lint**: 静的解析でコードスタイル違反や潜在的なバグを検出
- **Test**: 自動テスト実行で正確性を検証
- **Build**: コンパイルまたはバンドルでプロジェクトが正常にビルドされることを確認

いずれかのステップが失敗すると、パイプラインが変更のマージをブロックします。

### 継続的デリバリー（CD）

CD は CI を拡張し、検証済みの変更をステージングまたは本番環境に自動的にデプロイします。デプロイ戦略はプロジェクトのインフラとリリースプロセスによって異なります。

## GitHub Actions

このテンプレートは CI/CD プラットフォームとして [GitHub Actions](https://docs.github.com/ja/actions) を使用しています。GitHub Actions ワークフローは `.github/workflows/` に YAML ファイルとして定義されます。

### 再利用可能なワークフロー

GitHub Actions は `workflow_call` トリガーを介して**再利用可能なワークフロー**をサポートしています。再利用可能なワークフローは一度定義され、他のワークフローから呼び出されることで、リポジトリ間の重複を削減します。

このテンプレートは `ci-base.yml` を再利用可能なワークフローとして提供しています。派生リポジトリは、プロジェクト固有の入力（言語、コマンド、バージョン）を指定して呼び出す独自のワークフローを作成します。

### セキュリティスキャン

2つの自動セキュリティメカニズムが含まれています：

- **CodeQL**: GitHub の静的解析エンジンで、コードのセキュリティ脆弱性をスキャンします。プッシュ、プルリクエスト、および毎週のスケジュールで実行されます。
- **Dependabot**: 既知の脆弱性について依存関係を自動的に監視し、更新のためのプルリクエストを作成します。

## アップストリームテンプレートからの同期

GitHub テンプレートリポジトリはアップストリーム接続を維持しません。プロジェクト作成後にテンプレートの更新を取り込むには：

### 初期設定（1回のみ）

```bash
git remote add template https://github.com/{owner}/ecc-base-template.git
```

### 同期

```bash
# 最新のテンプレート変更を取得
git fetch template

# 特定のファイルをチェリーピック（推奨）
git checkout template/main -- .github/workflows/ci-base.yml

# またはすべてをマージ（コンフリクト解決が必要）
git merge template/main --allow-unrelated-histories
```

## 参考リンク

このドキュメントで解説している技術の1次情報です：

- [GitHub Actions Documentation](https://docs.github.com/ja/actions) — ワークフロー、ジョブ、ステップ、ランナー、マーケットプレイス
- [Reusable Workflows](https://docs.github.com/en/actions/sharing-automations/reusing-workflows) — `workflow_call` トリガーとリポジトリ間での利用
- [GitHub CodeQL](https://docs.github.com/en/code-security/code-scanning/introduction-to-code-scanning/about-code-scanning-with-codeql) — セキュリティ脆弱性の静的解析
- [Dependabot Documentation](https://docs.github.com/ja/code-security/dependabot) — 依存関係の更新とセキュリティアラート
- [GitHub Template Repositories](https://docs.github.com/ja/repositories/creating-and-managing-repositories/creating-a-template-repository) — テンプレートリポジトリの仕組みと制限事項
