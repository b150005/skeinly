# ADR-002: Share を第一級エンティティとしたデータモデル設計

> Source: [English version](../../en/adr/002-data-model-design.md)

## ステータス

承認済み

## コンテキスト

Skeinly の差別化要因はユーザー間の共有とインタラクションである。データモデルは共有をコア概念として扱い、後付け機能にしてはならない。主な要件：

- ユーザーがパターンを作成しプロジェクトの進捗を追跡する
- ユーザーが特定のユーザーまたは公開でパターンを共有する
- ユーザーがパターンやプロジェクトにコメントできる
- アクティビティフィードが関連イベント（共有、コメント、完了）を表示する
- パターンに可視性コントロール（プライベート、共有、公開）がある

バックエンドは Supabase/PostgreSQL（ADR-001 参照）であるため、外部キーと行レベルセキュリティを備えたリレーショナルモデルを使用する。

## 決定

以下の 6 つのコアエンティティからなるデータモデルを採用する。`Share` と `Activity` は第一級エンティティであり、副次的な機能ではない。

### エンティティ定義

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│   User   │────<│ Pattern  │────<│ Project  │
└──────────┘     └──────────┘     └──────────┘
                      │                │
                      │                │
                 ┌────┴────┐     ┌────┴────┐
                 │  Share  │     │ Progress │
                 ├─────────┤     └──────────┘
                 │ Comment │
                 ├─────────┤
                 │Activity │
                 └─────────┘
```

#### User
| フィールド | 型 | 説明 |
|-----------|------|------|
| id | UUID (PK) | Supabase Auth ユーザー ID |
| display_name | TEXT | 表示名 |
| avatar_url | TEXT? | プロフィール画像 URL |
| bio | TEXT? | 短い自己紹介 |
| created_at | TIMESTAMPTZ | 登録日 |

#### Pattern
| フィールド | 型 | 説明 |
|-----------|------|------|
| id | UUID (PK) | 自動生成 |
| owner_id | UUID (FK → User) | 作成者 |
| title | TEXT | パターン名 |
| description | TEXT? | 備考、説明 |
| difficulty | TEXT? | beginner / intermediate / advanced |
| gauge | TEXT? | ゲージ情報 |
| yarn_info | TEXT? | 糸の種類と重さ |
| needle_size | TEXT? | 針のサイズ |
| chart_image_urls | TEXT[] | 編み図写真 URL（Supabase Storage） |
| visibility | TEXT | `private` / `shared` / `public` |
| created_at | TIMESTAMPTZ | 作成日 |
| updated_at | TIMESTAMPTZ | 最終更新日 |

#### Project
| フィールド | 型 | 説明 |
|-----------|------|------|
| id | UUID (PK) | 自動生成 |
| owner_id | UUID (FK → User) | 編んでいる人 |
| pattern_id | UUID (FK → Pattern) | どのパターン |
| title | TEXT | プロジェクト名 |
| status | TEXT | `not_started` / `in_progress` / `completed` |
| current_row | INT | 現在の段/周回番号 |
| total_rows | INT? | 合計段数（わかる場合） |
| started_at | TIMESTAMPTZ? | 編み始めた日時 |
| completed_at | TIMESTAMPTZ? | 完了日時 |
| created_at | TIMESTAMPTZ | レコード作成日 |

#### Progress
| フィールド | 型 | 説明 |
|-----------|------|------|
| id | UUID (PK) | 自動生成 |
| project_id | UUID (FK → Project) | どのプロジェクト |
| row_number | INT | 完了した段 |
| photo_url | TEXT? | 進捗写真 URL |
| note | TEXT? | セッションメモ |
| created_at | TIMESTAMPTZ | この段を完了した日時 |

#### Share
| フィールド | 型 | 説明 |
|-----------|------|------|
| id | UUID (PK) | 自動生成 |
| pattern_id | UUID (FK → Pattern) | 共有されたパターン |
| from_user_id | UUID (FK → User) | 共有した人 |
| to_user_id | UUID (FK → User)? | 受信者（リンク共有の場合は null） |
| permission | TEXT | `view` / `fork` |
| status | TEXT | `pending` / `accepted` / `declined` |
| share_token | TEXT? | リンクベース共有用トークン |
| shared_at | TIMESTAMPTZ | 共有日時 |

#### Comment
| フィールド | 型 | 説明 |
|-----------|------|------|
| id | UUID (PK) | 自動生成 |
| author_id | UUID (FK → User) | コメントした人 |
| target_type | TEXT | `pattern` / `project` |
| target_id | UUID | Pattern または Project の ID |
| body | TEXT | コメント本文 |
| created_at | TIMESTAMPTZ | 投稿日時 |

#### Activity
| フィールド | 型 | 説明 |
|-----------|------|------|
| id | UUID (PK) | 自動生成 |
| user_id | UUID (FK → User) | アクションを実行した人 |
| type | TEXT | `shared` / `commented` / `forked` / `completed` / `started` |
| target_type | TEXT | `pattern` / `project` |
| target_id | UUID | 対象エンティティ ID |
| metadata | JSONB? | 追加コンテキスト（例：コメントプレビュー） |
| created_at | TIMESTAMPTZ | 発生日時 |

### 行レベルセキュリティ戦略

- **Pattern**: オーナーがフルアクセス。公開パターンは全員が閲覧可能。共有パターンは共有先のみ閲覧可能。
- **Project**: オーナーがフルアクセス。リンクされたパターンが公開または共有の場合のみ他者に表示。
- **Share**: from_user と to_user の両方が閲覧可能。from_user のみ作成可能。to_user のみステータス更新可能。
- **Comment**: ターゲットを閲覧できる人は閲覧可能。認証済みユーザーが作成可能。著者のみ削除可能。
- **Activity**: ユーザー本人とフォロワー（将来機能）が閲覧可能。システムトリガーのみ挿入可能。

## 結果

### ポジティブ

- **Share が明示的**: すべての共有に出所（誰が、誰に、いつ、どの権限で）があり、権限管理と分析が可能。
- **アクティビティフィード対応**: Activity テーブルにより、後付けなしで初日からソーシャルフィードが実現。
- **フォークサポート**: Share の `fork` 権限により、ユーザーがパターンをコピーして改変可能。パターンエコシステムを構築。
- **柔軟な可視性**: 3 段階の可視性（プライベート/共有/公開）でユーザーがコンテンツを管理。
- **リンク共有**: share_token によりアプリ外（SNS、メッセージングアプリ）への共有が可能。

### ネガティブ

- **テーブル数の増加**: 6 つのコアエンティティと RLS ポリシーは、ローカルのみの SQLite スキーマより複雑。
- **Activity テーブルの肥大化**: インデックス戦略と将来的なアーカイブ/ページネーションが必要。
- **RLS の複雑さ**: 共有コンテンツの行レベルセキュリティポリシー（特に共有を通じた推移的アクセス）には慎重なテストが必要。

### ニュートラル

- shared モジュールの Kotlin ドメインモデルは、kotlinx.serialization データクラスを使用してこれらのテーブルを反映する。
- SQLDelight はローカルキャッシュに使用（ADR-003 参照）、これらのテーブルのサブセットをローカルに複製。

## 検討した代替案

| 代替案 | メリット | デメリット | 不採用の理由 |
|--------|---------|-----------|-------------|
| Pattern のフラグとして共有（Share テーブルなし） | よりシンプルなスキーマ | 権限制御なし、出所追跡なし、リンク共有なし | アプリを差別化するソーシャル機能に不十分 |
| ドキュメントベースモデル（Firestore） | 柔軟なスキーマ、簡単なネスト | 多対多関係の非正規化、フィードや共有コンテンツの複雑なクエリ | リレーショナルモデルの方が適切（ADR-001 参照） |
| 別のソーシャルマイクロサービス | 分離、独立してスケーラブル | MVP には過剰設計、デプロイの複雑さ | スケールが要求するまで不要 |
