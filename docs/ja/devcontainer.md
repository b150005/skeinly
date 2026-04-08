> このドキュメントは `docs/en/devcontainer.md` の日本語訳です。英語版が原文（Source of Truth）です。

# Dev Containers

## Dev Containers とは？

Dev Containers（開発コンテナ）は、`devcontainer.json` 設定ファイルで定義された Docker ベースの開発環境です。チームの全開発者が同じツール、ランタイム、設定を使用することを保証し、「自分の環境では動く」問題を排除します。

## 仕組み

1. `devcontainer.json` ファイルが環境（ベースイメージ、ツール、拡張機能）を定義します
2. IDE（VS Code、JetBrains、GitHub Codespaces）がこのファイルを読み取ります
3. 指定された設定で Docker コンテナが作成されます
4. プロジェクトのコードがコンテナにマウントされます
5. すべてのツールがプリインストールされた状態でコンテナ内で開発します

## 主要コンセプト

### ベースイメージ

コンテナの出発点です。Microsoft は主要な言語用の公式 Dev Container イメージ（`mcr.microsoft.com/devcontainers/...`）を提供していますが、任意の Docker イメージを使用することもできます。

### Features

Dev Container Features は、Dockerfile を書かずにコンテナにツールを追加するモジュラーパッケージです。`devcontainer.json` の `features` セクションで指定します。

利用可能な features を参照: https://containers.dev/features

### ライフサイクルコマンド

コンテナのライフサイクルの異なる段階で実行されるコマンドです：

- `postCreateCommand`: コンテナ作成後に1回実行（例: 依存関係のインストール）
- `postStartCommand`: コンテナ起動時に毎回実行
- `postAttachCommand`: クライアントがコンテナに接続するたびに実行

### ポートフォワーディング

Dev Containers は、コンテナからホストマシンにポートを転送できます。`forwardPorts` でポートを宣言すると、コンテナ内で実行されているサービスにブラウザや他のツールからアクセスできます。

### IDE 拡張機能

`customizations` セクションを通じて、拡張機能をコンテナ内にプリインストールできます。これにより、チーム全体が同じエディタツールを使用することが保証されます。

## IDE サポート

| IDE | サポート |
|-----|---------|
| VS Code | Dev Containers 拡張機能によるネイティブサポート |
| GitHub Codespaces | ネイティブ（クラウドホスト型 Dev Containers） |
| JetBrains IDE | Gateway / リモート開発経由 |
| Cursor | ネイティブ（VS Code 拡張機能エコシステムを共有） |

## このテンプレートのアプローチ

このテンプレートの `.devcontainer/devcontainer.json` は**コメント付きテンプレート**です。ベーステンプレートはフレームワーク非依存であるため、ベースイメージや features を指定していません。派生リポジトリは、自分のスタックに合ったセクションのコメントを解除してカスタマイズします。

## ヒント

- **イメージを小さく保つ**: features ですべてインストールするのではなく、言語固有のイメージを使用してください
- **依存関係をキャッシュ**: `postCreateCommand` で依存関係をインストールし、コンテナに永続化します
- **ポートフォワーディング**: アプリが実行するサービス（Web サーバー、データベースなど）用に `forwardPorts` を宣言します
- **環境変数**: 開発専用の変数には `remoteEnv` を使用してください。シークレットには使用しないでください

## 参考リンク

このドキュメントで解説している技術の1次情報です：

- [Dev Containers Specification](https://containers.dev/) — 公式仕様、JSON リファレンス、feature レジストリ
- [devcontainer.json Reference](https://containers.dev/implementors/json_reference/) — すべての設定プロパティ
- [Available Features](https://containers.dev/features) — インストール可能な features の一覧と検索
- [VS Code Dev Containers](https://code.visualstudio.com/docs/devcontainers/containers) — VS Code との統合ガイド
- [GitHub Codespaces](https://docs.github.com/ja/codespaces) — クラウドホスト型 Dev Containers
- [Microsoft Dev Container Images](https://mcr.microsoft.com/en-us/catalog?search=devcontainers) — 公式ベースイメージカタログ
