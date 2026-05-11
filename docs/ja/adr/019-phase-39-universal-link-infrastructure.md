# ADR-019 — Phase 39 (W3): Universal Link / Android App Link インフラ

> **ステータス**: Accepted (2026-05-11)
> **フェーズ**: 39 (W3 wave — アルファ起動前提)
> **関連**: ADR-017 §3.8 (push deep-link routing — ホスト相対パス形式、本 ADR で決めた URL family の兄弟)

英語版 (canonical): [../../en/adr/019-phase-39-universal-link-infrastructure.md](../../en/adr/019-phase-39-universal-link-infrastructure.md)

## 1. 背景

Phase 39 (W3) で、ユーザーが SMS・iMessage・メール・Slack 等で Skeinly のコンテンツを共有した際にリンクから直接アプリを開けるようにする外部 Universal Link (iOS) / Android App Link 対応を導入。

W3 以前は `skeinly://share/<token>` カスタムスキームで実装していたが、(a) チャットアプリでリッチプレビュー unfurl されない、(b) 受信者が Skeinly 未インストール時に Web fallback がない、(c) ドメイン所有検証がないので任意のアプリが `skeinly://` を claim できてしまう、という欠点があった。

W3 deep-link 監査 (2026-05-10、agent-team 協議 — product-manager / architect / ui-ux-designer / knitter / security-reviewer の合意) で URL family を `https://b150005.github.io/skeinly/<resource>/<id>` 形式とし、3 つのリソース種別 (`patterns/shared/<token>` 共有トークン、`patterns/<patternId>` 公開パターン詳細 — アルファ範囲では予約のみ、`pull-requests/<prId>` PR 詳細) を初期スコープと決定。

W3 commit (5847508) 後に残った疑問: **AASA (Apple App Site Association) と Android assetlinks.json をどこに hosting するか**。Apple と Google はこのファイルを apex domain の **ルート** からのみ取得する仕様:

- iOS: `https://<domain>/.well-known/apple-app-site-association`
- Android: `https://<domain>/.well-known/assetlinks.json`

サブパス (`b150005.github.io/skeinly/.well-known/...`) は仕様上無視される。Skeinly の Project Pages サイト (`b150005.github.io/skeinly/` 配下) ではこのファイルを host できない — W3 初回実装はこの構造的問題で pages CD が 404 検出し失敗した (正しい挙動)。

選択肢は 2 つ:

| | Option A: カスタムドメイン (例: `skeinly.app`) | Option B: User Pages repo `b150005/b150005.github.io` |
|---|---|---|
| ドメインブランディング | `skeinly.app/patterns/shared/<token>` (短く、ブランド) | `b150005.github.io/skeinly/patterns/shared/<token>` (長い、個人 username 入り) |
| コスト | 年 ~$15 | 無料 |
| セットアップ | DNS 設定 + GitHub Pages custom domain 設定 + Skeinly 側ホスト変更 | User Pages repo 作成 (1-click) + 2 ファイル push |
| 将来移行 | 不要 | GA 時にカスタムドメイン化 — エンタイトルメント + Manifest + parser のホスト値変更のみの小コミット |

## 2. 決定

### 2.1 ホスティング: アルファでは User Pages repo (Option B)、GA で Option A を再検討

**根拠:**

- **アルファ起動速度**: Option B は repo 作成 + 1 push、Option A はドメイン購入 + DNS propagation + Pages 再設定 + Skeinly 側ホスト変更が必要。アルファ起動をドメイン決定で gating すべきでない。
- **構造的等価**: 両者とも Universal Link / App Link 検証は機能する。Apple AASA と Google assetlinks.json は apex-domain root にあれば、どちらのドメインでも振る舞い同じ。
- **安価な移行パス**: GA で Option A を採用する場合、変更点は限定的 — (a) `iosApp/iosApp.entitlements` の `applinks:` 値、(b) `androidApp/src/main/AndroidManifest.xml` intent-filter `host=`、(c) Kotlin `parseExternalRoute` / Swift mirror の期待 hostname、(d) 共有 URL 生成、(e) AASA + assetlinks ファイルを新ドメイン配下に移動 (または重複保持しても無害)。データ移行なし、約 1 日の集中作業 + DNS propagation 待ち時間。
- **運用フットプリント**: 無料 + GitHub-native = 第三者レジストラ不要、DNS 管理面なし、recurring billing なし。≤10 名のアルファでは合理的。

### 2.2 User Pages repo のファイル配置

```
b150005/b150005.github.io
├── .nojekyll
└── .well-known/
    ├── apple-app-site-association
    └── assetlinks.json
```

**`.nojekyll` が必要な理由**: GitHub Pages は既定で Jekyll preprocessing を実行し、`.well-known/` のような dot 始まりのディレクトリを除外する。`.nojekyll` 空ファイルを repo root に置くと Jekyll を完全 bypass してファイルツリーをそのまま配信。これがないと AASA endpoint は静かに 404 を返す。

**コンテンツ:**

- `apple-app-site-association` (拡張子なし、Apple 慣例): `appIDs: ["L9ZR4679P5.io.github.b150005.skeinly"]` と 3 つのリソースパターンの `components`。Mime type は `application/octet-stream` (GitHub Pages の既定) — [Apple ドキュメント](https://developer.apple.com/documentation/xcode/supporting-associated-domains)で明示的に受理 ("the content type doesn't have to be `application/json`")。
- `assetlinks.json` (拡張子必須): `io.github.b150005.skeinly` に対する `delegate_permission/common.handle_all_urls`、リリース keystore の SHA256 指紋付き (`18:B3:0D:4F:...:8C`、alias `knit-note`)。Mime type `application/json` は Google が必須要求しており、GitHub Pages が自動で `.json` ファイルに付与。

### 2.3 Skeinly URL family (W3 監査結果から不変)

| リソース | URL パターン | Typed route |
|---|---|---|
| 共有トークン | `https://b150005.github.io/skeinly/patterns/shared/<token>` | `SharedContent(token, shareId=null)` (Compose) / `.sharedContent(token:shareId:)` (SwiftUI) |
| 公開パターン | `https://b150005.github.io/skeinly/patterns/<patternId>` | 予約 — アルファ範囲では placeholder (§4 参照) |
| Pull request | `https://b150005.github.io/skeinly/pull-requests/<prId>` | `SuggestionDetail(prId)` (Compose) / `.pullRequestDetail(prId:)` (SwiftUI) |

共有トークンは UUID v4 形式を必須 (Kotlin + Swift mirror の `parseExternalRoute` で検証)。クエリ文字列と URL フラグメントは route matching 前に除去される。

レガシー `skeinly://` カスタムスキームは **完全削除** (Tech Debt fallback なし) — pre-v1 breaking-changes-accepted ポリシー適用、アルファ未起動なのでクライアント影響なし。

### 2.4 リリース時検証ゲート

`.github/workflows/release.yml` の `validate-tag` job で Android / iOS Release build 前に検証:

- AASA + assetlinks 両 endpoint の HTTP 200
- AASA に期待 `appID` 文字列が含まれること
- assetlinks に期待 `package_name` + SHA256 prefix が含まれること

失敗時はダウンストリームの Build job (~25 分) を skipping。"User Pages repo が偶発的に cleanup された" 系の障害を最早期に検出。

## 3. トレードオフとリスク

### 3.1 ドメインブランディング (Option B 妥協点)

`b150005.github.io/skeinly/...` URL に個人 username が露出する。≤10 名のアルファでは許容範囲 (テスター全員がメンテナーを認知済)。Beta / GA で公開ユーザー向けにはプロフェッショナリズム重視で Option A 推奨。移行コストは限定的 (§2.1 参照)。

### 3.2 User Pages repo lifecycle への結合

Skeinly app の deep-link 動作が別 repo の deploy 健全性に依存。`b150005/b150005.github.io` での誤設定 (ファイル誤削除、ブランチリセット、Pages source-branch 誤設定) は、新規 iOS インストール時の Universal Link 検証を静かに壊す — 既存インストールはクラッシュしない (iOS は AASA 結果を、Android は assetlinks 検証を install 時に cache する)。新規 install のみ link が browser に fall back する failure mode を経験。

リリース時検証ゲート (§2.4) で tag push 時に fail-fast。Post-alpha で daily monitor (curl + alert) を追加してリリース間のギャップを閉じる。

### 3.3 Apple AASA ファイルサイズ制限 (128 KB)

現在のファイルは ~422 byte で、制限の 3 桁下。`components` 配列が数百のパスパターンになると制限に近づく — アルファ範囲の 3 パターンでは無関係。記載は将来的「deep link URL を大量追加」変更時の sanity-check 用。

### 3.4 Android Digital Asset Links キャッシュ TTL

Android は assetlinks.json 検証を app-install 時にキャッシュ。インストール後のファイル変更 (例: SHA256 更新) は既存デバイスでの検証再取得に再インストールが必要。iOS AASA キャッシュも同様にアグレッシブ (典型 1 時間〜1 日、正確な TTL は非公開)。

Skeinly では assetlinks.json の SHA256 は Play Console 署名 keystore (`docs/en/release-secrets.md` §8 参照、実質不変) の変更時のみ更新。Apple TEAM_ID も同等に安定。通常運用ではキャッシュ TTL は問題にならない。

## 4. アルファでのスコープ削減

W3 監査で識別した追加の公開 URL 表面 (Discovery 検索結果、Profile、Variation list、Suggestion list、Chart 詳細、Project 詳細) はすべて post-alpha 後送り。アルファ rubric では share + PR 詳細のみが cross-app surface 必須。

`patterns/<patternId>` (共有トークンなし公開パターン詳細) は AASA `components` に登録されているが、**Typed Compose Navigation destination はまだ無い**。Post-alpha で 1 画面追加 (`PatternDetail` Composable + Swift mirror + AppRouter case) で対応。それまでは web fallback (Project Pages 上で現在は 404) に degrade。

Web fallback HTML (`docs/public/skeinly/patterns/shared/index.html` 等) も post-alpha 送り。User Pages repo は bare AASA + assetlinks のみ host し UX は持たない。

## 5. 運用 runbook

### 5.1 AASA / assetlinks 更新手順

```bash
git clone https://github.com/b150005/b150005.github.io
cd b150005.github.io
# .well-known/apple-app-site-association または .well-known/assetlinks.json を編集
git add . && git commit -m "..." && git push
# Pages が自動 deploy、約 30 秒後に検証:
curl -fsSL https://b150005.github.io/.well-known/apple-app-site-association
curl -fsSL https://b150005.github.io/.well-known/assetlinks.json
```

### 5.2 新規リソースパス追加手順

1. §2.3 規約に従い URL 形状を決定 (resource path + 識別子セグメント)
2. 必要なら `shared/.../NavGraph.kt` で Typed Compose Navigation route を追加
3. `parseExternalRoute` (commonMain) + Swift mirror `parseExternalRoute(url:)` (AppRouter.swift) を新パスに拡張
4. User Pages repo の `.well-known/apple-app-site-association` に `components` エントリ追加
5. 両 repo (Skeinly + User Pages) に push、リリースタグ bump

### 5.3 カスタムドメイン移行 (post-alpha Option A path)

GA で branded ドメインに upgrade する際の順序:

1. ドメイン登録 (Cloudflare Registrar / Namecheap 等)
2. DNS: GitHub Pages の A レコード (185.199.108.153 等) に向ける。`www.` サブドメインや CNAME-flattening を使う apex は CNAME → `b150005.github.io`
3. `b150005/b150005.github.io` repo の Pages 設定で custom domain を指定 (repo の `CNAME` ファイルが自動生成される)
4. AASA + assetlinks は User Pages repo に置いたまま (新ドメインで配信される)
5. Skeinly repo を更新: entitlements + Manifest + parser + 共有 URL 生成
6. `release.yml` の `AASA_URL` / `ASSETLINKS_URL` 定数を更新
7. リリースタグを bump、CI で end-to-end 検証

集中 1 日 + DNS propagation 待ち (通常 1 時間以内) で完了。

## 6. Post-alpha の open question

- **Daily AASA / assetlinks monitor**: GitHub Actions の cron job で両 endpoint を curl、200 でなければ Slack / メール通知。Beta 時点で推奨。
- **Web fallback ランディングページ**: Skeinly 未インストールユーザーが `b150005.github.io/skeinly/patterns/shared/<token>` を tap した際に App Store / Play Store 誘導するシンプル HTML。Post-alpha polish、アルファテスターは事前インストール前提。
- **Universal Link analytics**: PostHog で `link_opened_via_universal_link` を計測。アルファスコープ外。
