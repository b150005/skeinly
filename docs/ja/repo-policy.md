# リポジトリ運用ポリシー

> 英語原典: [docs/en/repo-policy.md](../en/repo-policy.md)

このドキュメントは `knit-note` リポジトリのブランチ保護ルールと貢献ポリシーを記述します。

## クイックリファレンス

| 項目 | ポリシー |
|---|---|
| デフォルトブランチ | `main` |
| `main` への直接 push | **Admin のみ** (Owner = `b150005`) — Repository Role 経由で bypass |
| Force push、ブランチ削除 | **全員禁止** (bypass無し) |
| Merge コミット | **禁止** — squash または rebase のみ (linear history 強制) |
| PR 承認要件 | 最低1件の承認、push 時に古い承認は破棄、最終 push に対する承認必須、全レビュースレッド解決必須 |
| 必須 CI status check | `Shared Module (Lint + Test + Coverage + Build)`, `Android (Build + Test)`, `iOS (Build + Test)`, `Android E2E (Maestro)`, `iOS E2E (Maestro)`, `CodeQL Analysis (swift, macos-latest, manual)` |
| Bypass actor | Repository Admin role (Personal account では Owner のみ) |

有効な ruleset 名は `main-strict` (id `15581036`)。設定: <https://github.com/b150005/knit-note/rules/15581036>

## ルール設定の理由

このリポジトリは **public** で、TestFlight および Google Play でエンドユーザに配信されます。Phase 39 から外部テスターを含むclosed beta が開始されます。以下の脅威モデルを想定しています:

1. **Owner による誤操作の防止** — force-push、ブランチ削除、履歴改竄は構造的に block (bypass無し)
2. **Fork からの外部 PR** — contributor は fork から PR を出せるが、merge は Owner のみ可能。merge前に status check 必須
3. **将来の Write role collaborator 追加シナリオ** — 将来 Write role を付与しても merge gate / 直 push を bypass できない。Admin role のみ bypass 可
4. **Owner アカウント乗っ取り** — Admin 権限なら全 bypass 可能だが、bypass の監査ログが GitHub の activity log に保存される

## 各ルールの説明

### `update` ルール

`main` ブランチ ref への全更新を block:
- CLI からの `git push origin main`
- GitHub UI 経由の PR merge (技術的にも push の一種)
- `main` ref への書き込みを伴う GitHub API 呼び出し全般

**Bypass actor**: Repository Admin role (Owner)。Owner のみがこれらの操作を実行可能、それ以外 (Write role、fork contributor) は block されます。

### `non_fast_forward` ルール

Force push (`git push --force`) を禁止。bypass 無し — Owner であっても `main` への force-push 不可。これは履歴の意図せぬ改竄からの保護です。

### `deletion` ルール

`main` ブランチの削除を禁止。bypass 無し。

### `required_linear_history` ルール

Merge コミット禁止。PR は squash または rebase でのみ merge 可能で、linear な履歴が維持されます。`pull_request` ルールの `allowed_merge_methods: ["squash", "rebase"]` と整合。

### `pull_request` ルール

| パラメータ | 値 | 理由 |
|---|---|---|
| `required_approving_review_count` | `1` | 承認最低1件。現状ソロのため Owner が自分の PR を bypass 経由で自己承認するが、将来 Write role collaborator 追加時にゲート機能。 |
| `dismiss_stale_reviews_on_push` | `true` | PR ブランチへの新コミットで以前の承認を無効化。 |
| `require_last_push_approval` | `true` | 最新コミット以前の承認は無効。新 push 後に再承認必須。 |
| `required_review_thread_resolution` | `true` | PR 上の全会話スレッドが resolved 状態でないと merge 不可。 |
| `require_code_owner_review` | `false` | リポジトリに `CODEOWNERS` ファイルなし。 |
| `allowed_merge_methods` | `["squash", "rebase"]` | Merge コミット禁止 (`required_linear_history` に対応)。 |

### `required_status_checks` ルール

以下の CI ジョブが status `success` で完了しないと PR を merge できません:

| Status check | Workflow file | Job key |
|---|---|---|
| Shared Module (Lint + Test + Coverage + Build) | `.github/workflows/ci.yml` | `shared-checks` |
| Android (Build + Test) | `.github/workflows/ci.yml` | `android` |
| iOS (Build + Test) | `.github/workflows/ci.yml` | `ios` |
| Android E2E (Maestro) | `.github/workflows/e2e.yml` | `android-e2e` |
| iOS E2E (Maestro) | `.github/workflows/e2e.yml` | `ios-e2e` |
| CodeQL Analysis (swift, macos-latest, manual) | `.github/workflows/security.yml` | `codeql` (swift matrix entry) |

`strict_required_status_checks_policy` は `false` — PR ブランチが `main` の最新まで up-to-date でなくても check は valid 扱い。長期 PR の rebase loop 摩擦を回避するため。

**`CodeQL Analysis (java-kotlin, ubuntu-latest, manual)` について** — このチェックは Kotlin ビルドの上流抽出問題のため意図的に必須から除外しています (`CLAUDE.md` の "CI Known Limitations" 参照)。Workflow 側で `continue-on-error: true` 設定により workflow 全体を block しないようになっており、原因解決まで required check から除外を継続します。

## Bypass の仕組み

`main-strict` ruleset は bypass actor を1つだけ持ちます:

```json
{
  "actor_type": "RepositoryRole",
  "actor_id": 5,
  "bypass_mode": "always"
}
```

- `actor_id: 5` は **Repository Admin role** に対応
- `bypass_mode: "always"` は actor がいつでも全ルールを bypass 可能
- Personal account のリポジトリでは、**デフォルトで Owner のみが Admin role を持つ**。他のユーザに Admin を付与するには Owner による明示的な collaborator 招待が必要
- したがって現状のリポジトリ設定では、bypass 可能なのは `b150005` (Owner) のみ

**「Admin = Owner のみ」の不変条件を維持するため**、将来の collaborator に Admin role を付与しないでください。Contributor には Write 以下を付与します。

## ワークフロー例

### Owner として: 軽微な変更を直 push

```bash
git add .
git commit -m "docs: fix typo"
git push origin main   # bypass: Admin のため許可
```

全ルールを bypass します。typo 修正等で PR レビューが過剰となる軽微な変更に限り使用。

### Owner として: 大きな変更は PR 経由

```bash
git checkout -b feat/new-feature
# ... 変更を加える ...
git push origin feat/new-feature
gh pr create --base main --head feat/new-feature
# CI が green になるまで待つ
gh pr review --approve  # bypass で自己承認
gh pr merge --squash    # または --rebase
```

レビュー対象が広い変更 (大規模 refactor、security 関連等) は PR ワークフローで進めます。

### 外部 contributor として (fork PR)

1. `b150005/knit-note` を自分のアカウントに fork
2. 自分の fork のブランチに変更を push
3. `b150005/knit-note` の `main` ブランチに PR を出す
4. CI が pass するのを待つ
5. Owner がレビュー、承認、merge

外部 contributor は `main` ブランチへの push も、PR の merge も不可 — Owner のみが merge できます。

### 緊急直 push (Owner のみ)

```bash
git push origin main   # Admin role なら常に通る
```

`update` ルールは全員に適用されますが、Admin role が bypass します。Owner はいつでも直 push 可能 (壊れた commit の revert、緊急の security fix 等)。

## Ruleset の確認・変更方法

```bash
# 有効な ruleset の表示
gh api repos/b150005/knit-note/rulesets/15581036 | jq

# リポジトリの全 ruleset 一覧
gh api repos/b150005/knit-note/rulesets | jq '.[] | {id, name, enforcement}'

# 一時的に無効化 (例: 大規模 migration 時)
gh api -X PUT repos/b150005/knit-note/rulesets/15581036 -f enforcement=disabled

# 再有効化
gh api -X PUT repos/b150005/knit-note/rulesets/15581036 -f enforcement=active

# 完全削除
gh api -X DELETE repos/b150005/knit-note/rulesets/15581036
```

## セキュリティ姿勢 (self-hosted CI runner)

このリポジトリの CI は、メンテナの macOS ホスト上 (`/Users/b150005/Development/Tools/actions-runner-knitnote/`) に install された self-hosted GitHub Actions runner で実行されます。Runner 名: `b150005mac-host`、labels: `[self-hosted, macos, apple-silicon, host]`。本セクションは脅威モデル、適用済み緩和策、deferred 項目を記述します。

### 脅威モデル

リポジトリは **public**。Self-hosted runner は user `b150005` (admin group) として動作し、以下にアクセス可能:

- メンテナの home directory (SSH 鍵、gh CLI tokens、ブラウザデータ、macOS Login Keychain)
- メンテナの他の Development リポジトリ
- ローカルネットワーク + 制限なしの internet egress

最も危険な攻撃ベクトル:

1. **Fork PR からの任意コード実行** — GitHub のデフォルトでは fork PR が `pull_request` workflow event を trigger 可能、PR ブランチのコードが runner で実行される
2. **Third-party action supply chain の侵害** — mutable tag (`@v2`, `@v6`) で参照される action は、上流メンテナアカウント乗っ取り時に悪意 commit に re-point 可能
3. **Cross-run workspace 汚染** — runner は non-ephemeral、1 度コード実行に成功した攻撃者が `_work/` または `~/.gradle/caches/` に payload を植え、後続 run で実行され得る
4. **GITHUB_TOKEN scope unbounded** — `permissions:` block を持たない workflow はリポジトリデフォルトを継承、デフォルトが拡大されると全 workflow が黙って write 権限を獲得

### 適用済み緩和策

| 脅威 | 緩和策 | 場所 |
|---|---|---|
| Fork PR 任意コード実行 | Repository Settings → Actions → "Fork pull request workflows from outside collaborators" を **"Require approval for all outside collaborators"** に設定。任意 contributor からの初回 workflow run はメンテナの明示承認が必要 | GitHub UI |
| Owner 以外による branch 直 push | `update` rule + Repository Admin-only bypass (この `main-strict` ruleset) | Ruleset id 15581036 |
| Third-party action supply chain | GitHub 公式以外の全 action を commit SHA pin: `reactivecircus/android-emulator-runner@e89f39f`, `gradle/actions/setup-gradle@50e97c2`, `softprops/action-gh-release@b430933`。Dependabot が更新追跡 | `.github/workflows/*.yml` |
| GITHUB_TOKEN scope creep | 外部 trigger 可能な全 workflow (`ci.yml`, `e2e.yml`, `security.yml`) に top-level `permissions: contents: read` を明示 | Workflow YAML |
| OIDC token を host process 空間に | `pages.yml` (GitHub Pages OIDC federation 用 `id-token: write` を保持) を self-hosted ではなく `ubuntu-latest` で実行 | `.github/workflows/pages.yml` |
| Step output interpolation 経由の shell injection | `${{ steps.sim.outputs.dest }}` 等の step output 参照を、shell `run:` 内の `${{ }}` 直挿入ではなく `env:` block 経由 (`$XCODE_DEST`) で参照 | Workflow YAML |
| Commit 済 source 内の secret | Repository Settings → Secret scanning + Push protection 両方 **ENABLED** | GitHub UI |
| Action version 不揃い | `actions/checkout` の参照を `@v6` で統一 | Workflow YAML |

### Deferred 項目 (post-launch)

監査の結果、closed beta 期間中の残余リスクとして受容。Runner 侵害観測時または将来の hardening sprint で再検討。

| ID | リスク | Deferred 理由 |
|---|---|---|
| H1 | Persistent workspace に payload 残存 | Runner isolation (Lume VM 復活等) 必要 — 大工数。fork PR 承認 gate で実行可能ユーザを限定して当面緩和 |
| H2 | Runner が admin group user として動作、Keychain 全アクセス | H1 と同根 — runner isolation 必要 |
| M2 | `~/.gradle/caches/` を CI と local dev で共有、汚染 cache が local build にも波及 | `GRADLE_USER_HOME` 分離は容易だが初回 cache miss コスト追加。SHA-pin で当面緩和 |
| L2 | Runner プロセスの network egress 無制限 | 3rd-party macOS firewall (Little Snitch / LuLu) 必要、初回観測ニーズまで deferred |

### ユーザ作業 (一度きりの設定)

- **Passphrase なしの SSH 鍵** (`~/.ssh/id_ed25519`, `~/.ssh/b150005-GitHub`): runner 侵害時に silent 漏洩リスク。`ssh-keygen -p -f <key>` で都合の良い時に passphrase 設定推奨
- **Dependabot security updates**: Repository Settings → Code security & analysis で有効化推奨。SHA-pin した actions と相性最良
- **VM provisioning 時に作成した Apple ID app-specific password**: もう不要なら `account.apple.com → Sign-In and Security → App-Specific Passwords` で revoke (host runner では不要)

### Runner 特有の既知挙動

- **Android E2E install conflict**: host の Android AVD は CI run 間 + local 開発セッション間で persist。Local 開発時 install したアプリ (local debug keystore で署名) と CI build APK (default Android debug keystore で署名) で署名不一致 → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。回避: `e2e.yml` の `adb install -r` 前に `adb uninstall io.github.b150005.knitnote.android || true` 実行
- **iOS Simulator destination**: `Resolve iOS Simulator destination` step は `xcrun simctl list devices available -j` を parse して最初の利用可能 iPhone simulator を選択。Host の install 済 simulator runtime に依存、Xcode 更新で runtime 変わると選択 device 変動可能性
- **Single runner sequential 実行**: 1 個の runner 登録のため、1 push で trigger された全 workflow は順次 queue。CI 1 cycle ~80 分 (前 parallel GitHub-hosted の ~25 分から増加)。Multi-runner 拡張は cycle time が痛くなれば検討

## 更新履歴

| 日付 | 変更 | 実施者 |
|---|---|---|
| 2026-04-27 | 初回 `main-strict` ruleset 作成 (id `15581036`) | b150005 |
| 2026-04-27 | Self-hosted runner 稼働開始、security audit + hardening 完了 (Fork PR approval、third-party SHA pin、ci.yml permissions block、pages.yml の ubuntu-latest 復帰、env-var step output pattern)。Commits `1a119e1` + `8d6c6ae` 参照 | b150005 |
