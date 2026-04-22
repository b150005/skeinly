# i18n 運用規約 (Phase 33)

> 英語原典: [docs/en/i18n-convention.md](../en/i18n-convention.md)

最終更新: 2026-04-19.

## 目的

1. Android / iOS の user-visible 文字列は必ず localization resource
   経由で解決する（hardcoded literal は禁止）。
2. 両プラットフォームの **鍵セットは一致**。CI check でズレたら build
   失敗。
3. 日英の両方を launch 時点で first-class に扱う。追加 locale は value
   の翻訳のみで足り、鍵は変わらない。

## 正本

| Locale | Android native (`R.string.*`) | 共有 Compose (`Res.string.*`) | iOS SwiftUI |
|---|---|---|---|
| 英語 | `androidApp/src/main/res/values/strings.xml` | `shared/src/commonMain/composeResources/values/strings.xml` | `iosApp/iosApp/Resources/Localizable.xcstrings` (`"en"`) |
| 日本語 | `androidApp/src/main/res/values-ja/strings.xml` | `shared/src/commonMain/composeResources/values-ja/strings.xml` | `iosApp/iosApp/Resources/Localizable.xcstrings` (`"ja"`) |

計 5 ファイル。consumer の種類ごとに解決経路を分けている:

- **`androidApp/.../strings.xml`** — `R.string.*` を直接参照する
  Android-native コード (Activity タイトル / manifest / network-security
  descriptor / `androidApp/` 配下のみで定義される画面) 用。
- **`shared/.../composeResources/values/`** — 共有 Compose
  Multiplatform 画面が `import io.github.b150005.knitnote.generated.resources.Res`
  + `stringResource(Res.string.<key>)` で参照。shared commonMain は
  Android の `R` を参照できないため、Compose resources plugin 経由で
  解決する。
- **`iosApp/.../Localizable.xcstrings`** — `iosApp/` の SwiftUI 画面
  用。iOS は Phase 6 以降フル native SwiftUI で、共有 Compose 画面を
  render しないため独立管理。`project.yml` で
  `CFBundleDevelopmentRegion: en` と `CFBundleLocalizations: [en, ja]`
  を宣言。

i18n verifier (`scripts/verify-i18n-keys.sh`) が **5 ファイルすべて同一
鍵セット** であることを強制する。追加時は 5 ファイルすべてに mirror、
削除時も同様。CI で drift を block。

## 鍵命名

- `snake_case`。
- 画面名ではなく **役割** で prefix:
  - `action_*` — ボタン / メニュー / a11y ラベル等の動作名
    (`action_save`, `action_undo`)
  - `state_*` — load / empty / idle 状態ラベル (`state_loading`)
  - `error_*` — error メッセージ (`error_load`, `error_save`)
  - `dialog_*_title` / `dialog_*_body` — title と body 文字列がペアの
    確認ダイアログ用。本文がランタイムのエラー文字列で固定 body 鍵を
    持たない単一ボタンのエラーアラートは、存在しない `dialog_error_body`
    の暗黙的なペアを示唆しないよう `dialog_error_title` ではなく
    `title_error` を使う。
  - `title_*` — action を兼ねない screen title
  - `body_*` — `title_*` とペアで表示される本文段落（dialog / empty
    state 以外の context 用。onboarding のページ本文など）。
    primary が `state_*` / `dialog_*` でない場合にこの prefix を使う。
  - `label_*` — non-action ラベル（フォームのフィールドラベル、
    ステータスチップ、ドロップダウンの選択肢名など）
  - `hint_*` — 入力欄の placeholder / hint（`hint_search_projects`）
- 同じ単語が別画面で異なる意味になる場合のみ画面 prefix を許容（稀）。
  原則は再利用可能な role-prefixed 鍵。

## 参照方法

**共有 Compose Multiplatform (`commonMain`)** — `shared/src/commonMain/.../ui/`
配下の画面はこの経路を最優先:

```kotlin
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_save
import org.jetbrains.compose.resources.stringResource

Text(stringResource(Res.string.action_save))
```

`Res` は `compose.components.resources` plugin が生成する
(設定は `shared/build.gradle.kts` の `compose.resources { }` block)。
実体は `shared/src/commonMain/composeResources/values{,-ja}/strings.xml`
を読む。

**Android native (`androidApp/`)** — shared module の `Res` を参照でき
ない Android platform コードではこちら:

```kotlin
import androidx.compose.ui.res.stringResource
import io.github.b150005.knitnote.android.R

Text(stringResource(R.string.action_save))
```

**iOS (SwiftUI)**:

```swift
Text("action_save")   // Text(_:tableName:) が自動 localize
Button("action_save") { ... }
```

`Text` ではなく `String` が必要な場合は `String(localized:)`:

```swift
let label = String(localized: "action_save")
```

SwiftUI は literal が key と一致すれば自動的に `Localizable.xcstrings`
から解決する。`NSLocalizedString` でラップしないこと。

## 鍵の追加手順

1. **5 ファイルすべて** に entry を追加:
   - `androidApp/src/main/res/values/strings.xml`
   - `androidApp/src/main/res/values-ja/strings.xml`
   - `shared/src/commonMain/composeResources/values/strings.xml`
   - `shared/src/commonMain/composeResources/values-ja/strings.xml`
   - `iosApp/iosApp/Resources/Localizable.xcstrings`
2. ローカルで確認:
   ```
   ./scripts/verify-i18n-keys.sh
   ```
3. CI で鍵 sync を強制 — platform のどれかに無ければ build fail。

## 鍵の削除

同一 commit で 5 ファイルすべてから削除する。部分削除は verifier が
検出する。

## Phase 33 の非目的

- 既存画面の migration。既存 hardcoded 文字列は、専用の i18n sweep
  phase まで hardcoded のまま。新規 UI (Phase 32 editor 以降) は最初
  から localized。
- Symbol Catalog の bilingual label。`SymbolCatalog` は現状すべての
  glyph に JA + EN の両ラベルを持つ (Phase 30.1 gallery 設計)。後続
  phase で `Locale.current` による locale-appropriate label 選択に
  移行予定。当面は両方を表示。
- 複数形対応。最初の複数形鍵が出現した時点で `@plural` /
  `stringsdict` を追加する。

## 関連

- Script: [`scripts/verify-i18n-keys.sh`](../../scripts/verify-i18n-keys.sh)
- CI step: `.github/workflows/ci.yml` → `Verify i18n key sync`
- ADR-008 (symbol catalog) は、symbol label の locale 切替についてこの
  規約を参照する。
