# Knit Note

A Kotlin Multiplatform knitting app for managing patterns, tracking progress, and sharing with others.

[English](#english) | [日本語](#japanese)

---

<a id="english"></a>

## English

### Features

- **Pattern Management** — Store, organize, and browse knitting charts and patterns
- **Progress Tracking** — Track row-by-row or section-based progress on your knitting projects
- **Sharing** — Share patterns and progress snapshots with other knitters

### Platforms

| Platform | UI Framework | Status |
|----------|-------------|--------|
| Android | Jetpack Compose + Material 3 | Planned |
| iOS | SwiftUI | Planned |
| macOS | SwiftUI | Stretch goal |

### Architecture

- **Kotlin Multiplatform (KMP)** shared module for business logic, domain models, and data access
- **Platform-native UI**: Jetpack Compose (Android) and SwiftUI (iOS/macOS)
- **Local-first** data storage with SQLDelight, with cloud sync planned
- **Clean Architecture**: UI -> ViewModel -> UseCase -> Repository -> DataSource

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Shared Logic | Kotlin Multiplatform |
| Android UI | Jetpack Compose, Material 3 |
| iOS/macOS UI | SwiftUI |
| Local DB | SQLDelight |
| Networking | Ktor Client |
| Serialization | kotlinx.serialization |
| Async | Kotlin Coroutines + Flow / Swift async-await |
| DI | Koin Multiplatform |

### Development

This project uses an [ECC agent team](https://github.com/b150005/ecc-base-template) with 14 specialized agents for planning, design, implementation, testing, and release.

#### Prerequisites

- JDK 17+
- Android Studio or IntelliJ IDEA
- Xcode 16+ (for iOS/macOS)
- Kotlin 2.1+

#### Build

```bash
# Shared module tests
./gradlew :shared:allTests

# Android
./gradlew :androidApp:assembleDebug

# iOS (via Xcode)
open iosApp/iosApp.xcodeproj
```

### Documentation

| Document | Description |
|----------|-------------|
| [ECC Overview](docs/en/ecc-overview.md) | What is ECC and how it works |
| [TDD Workflow](docs/en/tdd-workflow.md) | Test-driven development with ECC agents |
| [CI/CD Pipeline](docs/en/ci-cd-pipeline.md) | GitHub Actions workflows |
| [Template Usage](docs/en/template-usage.md) | How this template was customized |

### License

[MIT](LICENSE)

---

<a id="japanese"></a>

## 日本語

### 機能

- **編み図管理** — 編み図やパターンの保存、整理、閲覧
- **進捗記録** — 段ごと・セクションごとの進捗トラッキング
- **共有** — 編み図や進捗のスナップショットを他の編み物愛好者と共有

### 対応プラットフォーム

| プラットフォーム | UI フレームワーク | 状態 |
|----------------|-----------------|------|
| Android | Jetpack Compose + Material 3 | 予定 |
| iOS | SwiftUI | 予定 |
| macOS | SwiftUI | 将来目標 |

### アーキテクチャ

- **Kotlin Multiplatform (KMP)** 共有モジュールでビジネスロジック、ドメインモデル、データアクセスを共有
- **プラットフォームネイティブ UI**: Jetpack Compose (Android)、SwiftUI (iOS/macOS)
- **ローカルファースト** のデータ保存 (SQLDelight) + クラウド同期予定
- **クリーンアーキテクチャ**: UI -> ViewModel -> UseCase -> Repository -> DataSource

### 開発

このプロジェクトは [ECC エージェントチーム](https://github.com/b150005/ecc-base-template)（14体の専門エージェント）を使用しています。

#### 前提条件

- JDK 17+
- Android Studio または IntelliJ IDEA
- Xcode 16+（iOS/macOS 向け）
- Kotlin 2.1+

#### ビルド

```bash
# 共有モジュールテスト
./gradlew :shared:allTests

# Android
./gradlew :androidApp:assembleDebug

# iOS（Xcode 経由）
open iosApp/iosApp.xcodeproj
```

### ドキュメント

| ドキュメント | 説明 |
|-------------|------|
| [ECC 概要](docs/ja/ecc-overview.md) | ECC とは何か、どのように機能するか |
| [TDD ワークフロー](docs/ja/tdd-workflow.md) | ECC エージェントによるテスト駆動開発 |
| [CI/CD パイプライン](docs/ja/ci-cd-pipeline.md) | GitHub Actions ワークフローの解説 |
| [テンプレート利用ガイド](docs/ja/template-usage.md) | テンプレートのカスタマイズ方法 |

### ライセンス

[MIT](LICENSE)
