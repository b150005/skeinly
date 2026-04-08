# ADR-001: バックエンドプラットフォームとして Supabase を採用

> Source: [English version](../../en/adr/001-backend-platform.md)

## ステータス

承認済み

## コンテキスト

Knit Note のコアバリューは、編み物のパターンや進捗に関するユーザー間の共有とインタラクションである。以下の機能を提供するバックエンドプラットフォームが必要：

- **認証**: ユーザー登録/ログイン（Google、Apple、メール）
- **データベース**: リアルタイムサブスクリプション付きのリレーショナルデータ（ユーザー、パターン、プロジェクト、共有、コメント、アクティビティフィード）
- **ファイルストレージ**: 編み図画像と進捗写真
- **リアルタイム**: パターンが共有・コメントされた際の即時更新
- **KMP 互換性**: shared モジュールでの Kotlin Multiplatform ファーストクラスサポート

データモデルは本質的にリレーショナルである。ユーザーがパターンを他のユーザーと共有し、プロジェクトにコメントし、アクティビティフィードをフォローする。これらは SQL JOIN と行レベルセキュリティの恩恵を受ける多対多の関係である。

## 決定

**Supabase** を `supabase-kt` ライブラリ（v3.5.0+）経由でバックエンドプラットフォームとして使用する。

### ライブラリ座標

```kotlin
// BOM
implementation(platform("io.github.jan-tennert.supabase:bom:3.5.0"))

// モジュール
implementation("io.github.jan-tennert.supabase:postgrest-kt")   // データベース
implementation("io.github.jan-tennert.supabase:auth-kt")         // 認証
implementation("io.github.jan-tennert.supabase:storage-kt")      // ファイルストレージ
implementation("io.github.jan-tennert.supabase:realtime-kt")     // リアルタイムサブスクリプション
implementation("io.github.jan-tennert.supabase:functions-kt")    // Edge Functions
implementation("io.github.jan-tennert.supabase:compose-auth")    // Compose 認証統合
```

### 要件

- Ktor 3.x（プロジェクト依存関係に含まれている）
- kotlinx.serialization（プロジェクト依存関係に含まれている）

## 結果

### ポジティブ

- **優れた KMP カバレッジ**: supabase-kt v3.5.0 は PostgREST、Auth、Storage、Realtime、Functions の完全な API カバレッジを持つ。Firebase の GitLive SDK には大きなギャップがある（Firestore 60%、Storage 40%、Messaging 10%）。
- **リレーショナルデータモデル**: PostgreSQL は Share/Activity/Comment エンティティの多対多関係に自然にフィットする。Firestore のドキュメントモデルでは非正規化と複雑なサブコレクションクエリが必要になる。
- **行レベルセキュリティ (RLS)**: Supabase の RLS ポリシーにより、データベースレベルでアクセスルールを適用する（例：「ユーザーは自分と共有されたパターンのみ閲覧可能」）。クライアント側の Firestore セキュリティルールより攻撃面を削減。
- **Compose 統合**: compose-auth と compose-auth-ui が Android 向けの事前構築済み認証 UI コンポーネントを提供。
- **活発な開発**: supabase-kt は非常に活発にメンテナンスされている（v3.5.0 は 2026 年 4 月リリース）、BOM によるバージョン管理付き。
- **SQL マイグレーション**: スキーマ変更は標準的な SQL マイグレーションで管理、バージョン管理されレビュー可能。
- **セルフホスティングオプション**: 将来必要に応じて Supabase をセルフホスト可能。

### ネガティブ

- **プッシュ通知なし**: Supabase にはプッシュ通知サービスが含まれていない。別途ソリューションが必要（OneSignal、Firebase Cloud Messaging 単体、または Supabase Edge Functions + APNs/FCM 直接利用）。
- **分析/クラッシュレポートなし**: アプリ分析とクラッシュレポートには別途ソリューションが必要。
- **コミュニティ SDK**: supabase-kt は Supabase が直接メンテナンスするものではないコミュニティライブラリ（ただし公式 Supabase ドキュメントで紹介されている）。
- **PostgreSQL 接続制限**: 特にリアルタイムサブスクリプションで多数の同時ユーザーがいる場合、接続プーリングの監視が必要。

### ニュートラル

- プッシュ通知と分析は後から独立して追加可能（例：FCM/Analytics のみ Firebase を使用、またはプラットフォームネイティブソリューション）。
- Supabase の無料枠は MVP 開発と初期ユーザーベースに十分。

## 検討した代替案

| 代替案 | メリット | デメリット | 不採用の理由 |
|--------|---------|-----------|-------------|
| Firebase (GitLive SDK v2.4.0) | Firestore リスナーによるリアルタイム、FCM によるプッシュ通知、Analytics/Crashlytics 内蔵 | KMP API カバレッジのギャップ（Firestore 60%、Storage 40%、Messaging 10%）、ドキュメントモデルはリレーショナルデータに非正規化が必要、中程度のメンテナンスのコミュニティ SDK | コア機能に対する KMP カバレッジ不足、ドキュメントモデルはリレーショナルなソーシャルデータに不向き |
| カスタムバックエンド (Ktor サーバー) | 完全な制御、テーラーメイド API | 大きな開発オーバーヘッド、インフラ管理、MVP の遅延 | この段階では不要。Supabase が必要な機能をすべて提供 |
| Appwrite | オープンソース BaaS、セルフホスト可能 | 確立された KMP SDK なし、小さなエコシステム | KMP サポートの欠如がブロッカー |
