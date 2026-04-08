> このドキュメントは `docs/en/github-features.md` の日本語訳です。英語版が原文（Source of Truth）です。

# GitHub の機能

このドキュメントでは、テンプレートで設定されている GitHub 固有の機能を解説します。

## CODEOWNERS

**ファイル**: `.github/CODEOWNERS`

CODEOWNERS は、プルリクエストが特定のファイルを変更した際に、自動的にレビュアーとしてリクエストされるユーザーを定義します。GitHub がこのファイルを読み取り、指定されたユーザーやチームをレビュアーとして追加します。

### 仕組み

各行がファイルパターンと1人以上のオーナーを対応付けます：

```
# デフォルトで全ファイルのオーナー
* @username

# プラットフォームチームが CI/CD 設定を担当
.github/ @platform-team

# セキュリティチームが認証関連の変更をレビュー
src/auth/ @security-team
```

PR がパターンに一致するファイルを変更すると、対応するオーナーが自動的にレビュアーとして追加されます。**ブランチ保護ルール**で CODEOWNERS の承認が必要な場合、オーナーが承認するまで PR をマージできません。

### パターン構文

| パターン | マッチ対象 |
|---------|-----------|
| `*` | すべてのファイル |
| `*.js` | すべての JavaScript ファイル |
| `/docs/` | リポジトリルートの `docs/` ディレクトリ |
| `docs/` | 任意の深さの `docs/` ディレクトリ |
| `src/auth/**` | `src/auth/` 配下のすべて（再帰的） |

### 主な動作

- 後のルールが前のルールを上書きします（最後にマッチしたものが優先）
- オーナーには GitHub ユーザー名（`@user`）またはチーム（`@org/team`）を指定できます
- オーナーなしのパターン（`@` なし）は、そのファイルのオーナーシップを無効にします
- ファイルは `.github/`、リポジトリルート、または `docs/` ディレクトリに配置する必要があります

## Dependabot

**ファイル**: `.github/dependabot.yml`

Dependabot はプロジェクトの依存関係を自動的に監視し、既知のセキュリティ脆弱性や古いバージョンを検出します。更新のためのプルリクエストを自動作成します。

### 仕組み

1. Dependabot が `dependabot.yml` を読み取り、どのパッケージエコシステムを監視するか把握します
2. 設定されたスケジュールで更新を確認します
3. 更新がある場合、各依存関係ごとに個別の PR を作成します
4. 通常のコード変更と同様に PR をレビューしてマージします

### パッケージエコシステム

各エコシステムは言語の依存関係管理ツールに対応しています：

| エコシステム | マニフェストファイル |
|------------|-------------------|
| `github-actions` | `.github/workflows/*.yml` |
| `npm` | `package.json` |
| `pip` | `requirements.txt`, `pyproject.toml` |
| `gomod` | `go.mod` |
| `cargo` | `Cargo.toml` |
| `pub` | `pubspec.yaml` |
| `maven` | `pom.xml` |
| `gradle` | `build.gradle`, `build.gradle.kts` |
| `composer` | `composer.json` |
| `bundler` | `Gemfile` |
| `nuget` | `*.csproj`, `packages.config` |
| `swift` | `Package.swift` |

### 設定オプション

```yaml
version: 2
updates:
  - package-ecosystem: "npm"
    directory: "/"            # マニフェストファイルの場所
    schedule:
      interval: "weekly"      # daily, weekly, または monthly
    open-pull-requests-limit: 10  # 同時に開ける PR の最大数
    reviewers:
      - "username"            # レビュアーの自動割り当て
    labels:
      - "dependencies"        # PR に付けるラベル
    ignore:
      - dependency-name: "lodash"  # 特定パッケージをスキップ
```

### セキュリティアップデート vs バージョンアップデート

- **セキュリティアップデート**: GitHub Advisory Database によってトリガーされます。スケジュールに関係なく常に作成されます。
- **バージョンアップデート**: 新しいバージョンの定期チェックです。`dependabot.yml` で設定します。

## プルリクエストテンプレート

**ファイル**: `.github/PULL_REQUEST_TEMPLATE.md`

PR テンプレートは、新しいプルリクエストが作成されたときに説明フィールドを自動で埋めます。これにより、すべての PR がレビュアーに必要な情報を含む一貫した構造になります。

### 仕組み

コントリビューターが新しい PR を開くと、GitHub が自動的にテンプレートの内容で説明を入力します。コントリビューターはその後、各セクションを記入します。

### このテンプレートの構成

このリポジトリのテンプレートには以下が含まれます：

- **概要（Summary）**: PR が何をするか
- **変更内容（Changes）**: 変更の箇条書き
- **影響範囲（Impact）**: どのコンポーネントやシステムに影響するか
- **テスト計画（Test Plan）**: 確認ステップのチェックリスト
- **チェックリスト**: コード品質ゲート（規約、シークレット、テスト、ドキュメント）

### 複数テンプレート

大規模プロジェクトでは複数のテンプレートを持つことができます：

```
.github/
├── PULL_REQUEST_TEMPLATE.md          # デフォルトテンプレート
└── PULL_REQUEST_TEMPLATE/
    ├── feature.md                    # 機能 PR 用
    ├── bugfix.md                     # バグ修正 PR 用
    └── release.md                    # リリース PR 用
```

コントリビューターは URL クエリパラメータでテンプレートを選択します：`?template=feature.md`

## Issue テンプレート

**ディレクトリ**: `.github/ISSUE_TEMPLATE/`

Issue テンプレートは、Issue を作成するための構造化されたフォームを提供します。報告者がアクションに必要なすべての情報を含めることを保証します。

### YAML フォーム vs Markdown テンプレート

GitHub は2つのフォーマットをサポートしています：

| フォーマット | 拡張子 | 特徴 |
|------------|--------|------|
| **YAML フォーム**（`.yml`） | 構造化フィールド、ドロップダウン、チェックボックス、必須バリデーション | このテンプレートで使用 |
| **Markdown**（`.md`） | 推奨セクション付きの自由形式テキスト | よりシンプルだが構造化が弱い |

YAML フォームは構造を強制でき、フィールドを必須にできるため推奨されます。

### フォームフィールドの種類

```yaml
body:
  - type: input        # 1行テキスト
  - type: textarea     # 複数行テキスト
  - type: dropdown     # 選択肢から選択
  - type: checkboxes   # 複数選択
  - type: markdown     # 静的な説明テキスト（フィールドではない）
```

### このテンプレートの Issue フォーム

**バグ報告**（`bug_report.yml`）：
- 説明、再現手順、期待される/実際の動作、環境、スクリーンショット

**機能リクエスト**（`feature_request.yml`）：
- 課題、提案する解決策、検討した代替案、追加情報

### テンプレートの追加

`.github/ISSUE_TEMPLATE/` に新しい `.yml` ファイルを作成します。`config.yml` を追加して Issue 作成ページをカスタマイズできます：

```yaml
# .github/ISSUE_TEMPLATE/config.yml
blank_issues_enabled: false    # 空の Issue を無効化
contact_links:
  - name: Discussions
    url: https://github.com/{owner}/{repo}/discussions
    about: Issue の代わりに Discussions で質問してください
```

## GitHub Actions

**ディレクトリ**: `.github/workflows/`

GitHub Actions は、リポジトリのイベント（push、pull request、スケジュールなど）に応じて自動ワークフローを実行する CI/CD プラットフォームです。

### 主要コンセプト

**ワークフロー（Workflow）**: `.github/workflows/` の YAML ファイルで、自動化プロセスを定義します。イベントによってトリガーされます。

**ジョブ（Job）**: 同じランナー（仮想マシン）で実行されるステップのセットです。ワークフロー内のジョブはデフォルトで並列実行されます。

**ステップ（Step）**: ジョブ内の単一タスクです。シェルコマンド（`run:`）または事前構築されたアクション（`uses:`）を実行できます。

**アクション（Action）**: 再利用可能なコードの単位です。GitHub Marketplace で公開されるか、ローカルに定義されます。`owner/repo@version` として参照されます。

**ランナー（Runner）**: ジョブを実行する仮想マシンです。GitHub はホストランナー（Ubuntu、Windows、macOS）を提供しますが、セルフホストも可能です。

### 再利用可能なワークフロー

再利用可能なワークフローは `workflow_call` トリガーを使用します。他のワークフローが `uses:` で呼び出します：

```yaml
# 呼び出し側ワークフロー
jobs:
  ci:
    uses: owner/repo/.github/workflows/reusable.yml@main
    with:
      input-name: "value"
```

このテンプレートは `ci-base.yml` を再利用可能なワークフローとして提供しています。派生リポジトリは独自のワークフローを作成して呼び出します。

### シークレット

機密値（API キー、トークン）はリポジトリの Settings > Secrets に保存します。ワークフロー内では `${{ secrets.SECRET_NAME }}` でアクセスします。シークレットはログでマスクされ、ワークフローの出力には公開されません。

## ブランチ保護ルール

リポジトリ内のファイルではありませんが、Settings > Branches で設定する重要な GitHub 機能です。

### `main` ブランチの推奨ルール

| ルール | 目的 |
|-------|------|
| **プルリクエストレビューを必須にする** | マージ前に最低1件の承認が必要 |
| **ステータスチェックの合格を必須にする** | マージ前に CI が合格する必要がある |
| **CODEOWNERS レビューを必須にする** | オーナーが自分のファイルへの変更を承認する必要がある |
| **リニア履歴を必須にする** | リベースまたはスカッシュマージを強制 |
| **バイパスを許可しない** | 管理者もルールに従う必要がある |

これらは GitHub の UI で設定され、ファイルには含まれません。ブランチ保護の設定は ADR に記録してください。

## 参考リンク

このドキュメントで解説しているすべての機能の1次情報です：

- [About CODEOWNERS](https://docs.github.com/ja/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners) — 構文、配置、動作
- [Dependabot Configuration](https://docs.github.com/ja/code-security/dependabot/dependabot-version-updates/configuration-options-for-the-dependabot.yml-file) — `dependabot.yml` の全オプション
- [About Dependabot Security Updates](https://docs.github.com/ja/code-security/dependabot/dependabot-security-updates/about-dependabot-security-updates) — セキュリティ vs バージョンアップデート
- [Creating a PR Template](https://docs.github.com/ja/communities/using-templates-to-encourage-useful-contributions/creating-a-pull-request-template-for-your-repository) — PR テンプレートの配置と使用方法
- [Configuring Issue Templates](https://docs.github.com/ja/communities/using-templates-to-encourage-useful-contributions/configuring-issue-templates-for-your-repository) — YAML フォームと Markdown テンプレート
- [Issue Form Syntax](https://docs.github.com/ja/communities/using-templates-to-encourage-useful-contributions/syntax-for-issue-forms) — すべてのフィールドタイプとバリデーション
- [GitHub Actions Documentation](https://docs.github.com/ja/actions) — ワークフロー、イベント、ジョブ、ランナー
- [Reusable Workflows](https://docs.github.com/en/actions/sharing-automations/reusing-workflows) — `workflow_call` トリガー
- [Encrypted Secrets](https://docs.github.com/ja/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions) — Actions でのシークレット管理
- [Branch Protection Rules](https://docs.github.com/ja/repositories/configuring-branches-and-merges-in-your-repository/managing-a-branch-protection-rule) — すべての保護オプション
