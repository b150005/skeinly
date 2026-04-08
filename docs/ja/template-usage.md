> このドキュメントは `docs/en/template-usage.md` の日本語訳です。英語版が原文（Source of Truth）です。

# テンプレート利用ガイド

## GitHub テンプレートリポジトリとは？

GitHub テンプレートリポジトリを使用すると、テンプレートと同じディレクトリ構造、ファイル、設定を持つ新しいリポジトリを作成できます。フォークとは異なり、新しいリポジトリはクリーンなコミット履歴を持ち、アップストリーム接続はありません。

## 新しいプロジェクトの作成

### ステップ 1: テンプレートを使用

1. GitHub のテンプレートリポジトリにアクセスします
2. 緑色の **「Use this template」** ボタンをクリックします
3. **「Create a new repository」** を選択します
4. オーナー、リポジトリ名、公開設定を選びます
5. **「Create repository」** をクリックします

### ステップ 2: クローンとカスタマイズ

```bash
git clone https://github.com/{owner}/{repo-name}.git
cd {repo-name}
```

## カスタマイズチェックリスト

### 必須

- [ ] **`.claude/CLAUDE.md`** — 「About This Project」セクションをプロジェクトのコンテキストに置き換えます
- [ ] **`.gitignore`** — [github/gitignore](https://github.com/github/gitignore) から言語固有のパターンを追加します
- [ ] **`README.md`** — プロジェクトの説明に置き換えます
- [ ] **`LICENSE`** — 著作権者名を更新します（またはライセンスを変更します）
- [ ] **`.env.example`** — プロジェクトの環境変数を追加します

### 推奨

- [ ] **`.devcontainer/devcontainer.json`** — フレームワーク用に設定します
- [ ] **`.github/CODEOWNERS`** — GitHub ユーザー名またはチームを設定します
- [ ] **`.github/dependabot.yml`** — 言語のパッケージエコシステムを追加します
- [ ] **`.github/workflows/security.yml`** — CodeQL の言語マトリクスを更新します

### 任意

- [ ] **CI ワークフローの作成** — `ci-base.yml` を呼び出す `.github/workflows/ci.yml` を追加します
- [ ] **ECC ルールの追加** — 言語固有のルールを `.claude/rules/` にコピーします
- [ ] **ADR の追加** — `docs/en/adr/` と `docs/ja/adr/` にアーキテクチャ決定を記録します

## テンプレート更新の取り込み

GitHub テンプレートリポジトリはアップストリーム接続を維持しません。後で更新を同期するには：

```bash
# テンプレートをリモートとして追加（1回のみ）
git remote add template https://github.com/{owner}/ecc-base-template.git

# 取得して特定のファイルをチェリーピック
git fetch template
git checkout template/main -- .github/workflows/ci-base.yml
```

## 参考リンク

このドキュメントで解説している概念の1次情報です：

- [Creating a Repository from a Template](https://docs.github.com/ja/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template) — 「Use this template」の仕組み
- [github/gitignore](https://github.com/github/gitignore) — 言語別の公式 .gitignore テンプレート
- [Conventional Commits](https://www.conventionalcommits.org/) — コミットメッセージフォーマット（feat, fix 等）
- [Keep a Changelog](https://keepachangelog.com/) — CHANGELOG フォーマット標準
