# Runbook — リリース

> 英語原典: [docs/en/ops/release.md](../../en/ops/release.md)
>
> **目的**: Skeinly のリリースをタグして TestFlight + Play Internal Testing に出す。
>
> **対象**: リリースを起動する運用者。
>
> **頻度**: リリース毎 (ベータ期は概ね 1–4 週間に 1 回、GA 後は希薄)。

## メンタルモデル

リリースは **タグ駆動**。`main` 上で `v*` にマッチするタグを push すると `.github/workflows/release.yml` が起動、3 ジョブを並列実行して両ストアにアップロードする。ローカル検証チェーン (`make ci-local`) は CI と同じものを走らせる — ローカル green と CI green の間に隙間はない。

ベータ期 (Phase 40 GA 前) のバージョンは semver `0.X.Y`。major-zero は load-bearing — `BuildFlags.isBeta` の codegen が `major == 0` で分岐し、iOS の `CFBundleShortVersionString` は数字とドットのみ受け付けるためハイフン suffix prerelease タグは App Store Connect が拒否する。

ソース・オブ・トゥルース:
- [version.properties](../../../version.properties) — `VERSION_NAME` (semver 文字列) + `VERSION_CODE` (Play 用 monotonic int)
- [iosApp/project.yml](../../../iosApp/project.yml) — `MARKETING_VERSION` は `VERSION_NAME` のミラー。`CURRENT_PROJECT_VERSION` は CI が `github.run_number` で上書き
- Gradle invariant `verifyIosBetaFlag` が `(major == 0) <=> (IS_BETA == "YES")` を assert するので片側だけの bump は landing しない

## 事前検証: ローカルで通す

新リリースの初タグ前に手元で signing chain を回す:

```bash
make release-ipa-local   # fastlane で署名済 IPA、アップロードなし
make release-aab-local   # Gradle で署名済 AAB、アップロードなし
```

両方ともローカルキーチェーン / `keystore.jks` の本番グレード signing マテリアルでエンドツーエンド走る。ここでの失敗は CI red 後の診断より遥かに安い。

直前のリリースからコード変更がある場合:

```bash
make ci-local
```

これが **canonical な pre-push エントリポイント**。30–45 分。booted な Android emulator + iOS Simulator が必要。CI 全部を再現する。

## Step 1: バージョン bump

[version.properties](../../../version.properties) を編集:

```properties
VERSION_NAME=0.1.1
VERSION_CODE=2
```

ルール:
- `VERSION_NAME` は semver。ベータ期は `0.X.Y`、Phase 40 GA で `1.0.0`
- `VERSION_CODE` は直前の Play Console アップロード成功値より **必ず大** (Play は重複を拒否、再利用は復旧不能)
- iOS build number は Release workflow の `github.run_number` から取るので手動編集不要

[iosApp/project.yml](../../../iosApp/project.yml) を編集:

```yaml
settings:
  base:
    MARKETING_VERSION: 0.1.1   # version.properties VERSION_NAME と一致必須
    IS_BETA: "YES"             # major == 0 中は YES、v1.0.0 で NO
```

Xcode プロジェクトを再生成:

```bash
make ios-regenerate   # または: cd iosApp && xcodegen
```

コミット:

```bash
git add version.properties iosApp/project.yml
git commit -m "chore(release): bump to v0.1.1"
git push origin main
```

このコミットの CI が green になるまで待ってからタグ。

## Step 2: 検証 + タグ

```bash
make release-tag-validate          # 事前: branch=main, clean tree, タグ未存在
CONFIRM=yes make release-tag-publish   # v$VERSION_NAME を HEAD にタグして push
```

`CONFIRM=yes` env var は accidental タグ push 防御 (Tab 補完事故で本番アップロードが走らない)。`release-tag-publish` ターゲットはタグ名を `version.properties` の `VERSION_NAME` から導出するので、リリース毎の編集は同ファイルの bump のみ。

raw git で済ませる場合:

```bash
git tag -a v0.1.1 -m "Release v0.1.1"
git push origin v0.1.1
```

どちらのパスでも `.github/workflows/release.yml` を起動する。

## Step 3: CI run を監視

```bash
gh run list --workflow=release.yml -L 1
gh run watch <run-id>
```

3 ジョブ並列:

| Job | 役割 | 所要 |
|---|---|---|
| `build-android` | `bundleRelease` → `publishBundle` が Play Internal Testing に `releaseStatus = DRAFT` でアップ。APK は workflow artifact | ~8–12 min |
| `build-ios` | fastlane `beta` lane: ephemeral keychain → secret から cert + profile 取り込み → `build_app` → `upload_to_testflight skip_waiting_for_build_processing: true` | ~12–20 min |
| `create-release` | 両バイナリ添付の draft GitHub Release | 2 ジョブ完了後 ~1 min |

`release-tag-publish` Makefile ターゲットは secret 欠落時に gracefully degrade — 各プラットフォームのアップロード step は必要な secret 存在チェックで gate されており、`::warning::` を出して fail せず artifact のみ build を produce する。

ジョブ fail 時は [incident-playbook.md → "Release CI failure"](incident-playbook.md#symptom-release-ci-failure) を参照。

## Step 4: アップロード後の手動操作

### Play Console (Android)

1. Play Console → Skeinly → Internal Testing トラックを開く
2. トップにある新 Draft release を選択
3. リリースノートを記入 (JP ストアリスティング向けに en + ja 必須)
4. "Save → Review release → Start rollout to Internal testing" をクリック

Draft 状態は **load-bearing**: rollout をクリックするまでテスターには見えない。CI からテスターへの regression を silent に出荷しないための意図的な選択。詳細は [ADR-006](../../en/adr/006-ci-signing-strategy.md)。

### App Store Connect (iOS)

1. Apple processing 待ち。通常 5–30 分、たまに長い
2. **新バージョンの最初のビルド** では ASC が export compliance disclosure を求めてくる。Skeinly は標準 OS 提供の HTTPS-only 暗号化のみ使用で exemption 適合 — "uses non-exempt encryption" には "No" で回答
3. Processing 完了後、Internal Testing グループに build を追加

External (パブリックリンク) TestFlight は Apple レビューが必要 (新バージョン最初の build で ~24h、同 train の subsequent builds はより速い)。計画的に。

## Step 5: 実機で smoke test

各プラットフォームで build がテスターデバイスに到達後:

- コールド起動 → onboarding (初インストール時) → ホーム
- 編図エディタを開く → シンボルパレットが populate されることを確認 (シンボルパック sync path の動作確認)
- push 通知配線済 build で 1 つコラボイベントを起動 (Suggestion を開くか コメント投稿) — 通知が宛先デバイスに到達することを確認
- Settings → フィードバック送信 (ベータジェスチャ: Android 3 本指長押し、iOS シェイク) からバグ報告 — Issue が `b150005/skeinly` に到達することを確認

いずれかの smoke test が fail したらリリースは **未完了**:
- パッチ (`v0.1.2`) で forward fix
- (最終手段) テストトラックから build を pull (Play Console: "Halt rollout"; ASC: テスター向けに build を expire)。その後 forward fix

## Step 6: リリース後の片付け

- [CLAUDE.md](../../../.claude/CLAUDE.md) の `### Completed` セクションに新リリースエントリを追記 (コミット + リリース後)
- フェーズクローズリリースなら archive policy に従って `### Completed` から `docs/en/phase/completed-archive.md` にフェーズエントリを昇格
- このリリースで `0.X.Y` → `1.0.0` (Phase 40 GA) bump なら `iosApp/project.yml` `IS_BETA: "YES"` → `"NO"` も flip (`verifyIosBetaFlag` タスクがこの coupling を強制 — 片側だけのコミットは CI fail)

## トラブルシューティング

[incident-playbook.md](incident-playbook.md) で症状別の障害モードを参照。

よくある罠:

- **`version.properties` を bump したが `iosApp/project.yml` 再生成漏れ**: `verifyIosBetaFlag` が CI で fail。`make ios-regenerate` してから re-commit
- **Play Console が「Version code already used」で reject**: `VERSION_CODE` を bump して同じ `VERSION_NAME` + 高い code で re-tag。使用済 code は復旧不能
- **fastlane が「No provisioning profile found」で fail**: secret がローテーションされて再登録漏れ。profile + cert チェーンは [secrets-rotation.md](secrets-rotation.md)
- **`upload_to_testflight` 成功するが build が ASC に出ない**: メタデータ不整合で Apple がサイレントに drop することがある。workflow log の `xcrun altool --validate-app` 出力をチェック — validation エラーが mismatch を明かす (通常は vendor-setup 変更後の bundle id か entitlement 不一致)

## 参照

- [.github/workflows/release.yml](../../../.github/workflows/release.yml) — workflow 本体
- [docs/en/release-secrets.md](../../en/release-secrets.md) — workflow が読む全 secret
- [docs/en/vendor-setup.md](../../en/vendor-setup.md) — Apple Developer + ASC + Universal Links 一発設定
- [docs/ja/ops/repo-policy.md](repo-policy.md) — `main` を保護するブランチ保護
- [ADR-006](../../en/adr/006-ci-signing-strategy.md) — code signing 戦略
