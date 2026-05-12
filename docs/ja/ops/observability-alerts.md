# Runbook — オブザーバビリティアラート

> EN: [docs/en/ops/observability-alerts.md](../../en/ops/observability-alerts.md)

Sentry + Google Play Console のアラートを設定し、本番障害発生時に数日でなく数分でオペレーター inbox に届くようにする。Pre-alpha audit 項目 **A28** (Sentry crash-free SLO) と **A29** (Sentry ANR + Play Vitals アラート) をカバー。

## クローズドアルファでも設定する理由

5–10 テスターのクローズドアルファは 1 日のセッション数が少ないため、1 件のクラッシュで crash-free rate が 10–20% 変動する。アラートなしでは「3 日後にテスターから WhatsApp で報告」になる代わりに、設定済なら「3 分後にメール inbox に届く」。Day 1 から本番品質のオブザーバビリティで運用する。

## インベントリ — 設定すべきアラート

| アラート | ソース | 重要度 | 通知先 |
|---|---|---|---|
| Crash-free sessions (iOS) | Sentry | Page | オペレーター email |
| Crash-free sessions (Android) | Sentry | Page | オペレーター email |
| Crash-free users (iOS + Android) | Sentry | Warn | オペレーター email |
| ANR rate (Android) | Sentry | Page | オペレーター email |
| ANR rate (Android) | Play Console Vitals | Page | オペレーター email |
| Crash rate (Android) | Play Console Vitals | Warn | オペレーター email |
| 遅い cold start (Android) | Play Console Vitals | Info | オペレーター email |

合計 7 アラート。全て `skeinly.app@gmail.com` 宛。Slack / PagerDuty 移行は post-alpha (on-call rotation 立ち上げ時)。

## SLO 目標

| 指標 | アルファ閾値 | GA 閾値 (Phase 40+) |
|---|---|---|
| Crash-free sessions | ≥ 99.0% (rolling 24h) | ≥ 99.5% |
| Crash-free users | ≥ 95.0% (rolling 24h) | ≥ 99.0% |
| ANR rate (Android, daily users) | ≤ 0.47% | ≤ 0.20% |
| Crash rate (Android, daily users) | ≤ 1.09% | ≤ 0.50% |
| Cold start, slow startup rate | ≤ 30% (P50 ≤ 5 秒) | ≤ 15% (P50 ≤ 3 秒) |

アルファ閾値は意図的に緩い: 5–10 テスターでは 1 テスターが 1 件クラッシュしても 1 日 99% を超えるセッション数が出ない。アルファ期の "page" 重要度 = 「2 時間以内に確認すべき」、文字通りのページ呼び出しではない。

Play Vitals ANR / Crash 閾値根拠:
- **ANR ≤ 0.47%** は Google Play の [bad-behavior threshold](https://support.google.com/googleplay/android-developer/answer/9844486) — 超えるとストア掲載降格対象に
- **Crash ≤ 1.09%** は同じく store-listing demotion threshold
- アルファ期で超えた場合は調査対象 (リリースブロッカーではない)

Sentry 閾値根拠:
- **Crash-free sessions ≥ 99%** はコンシューマーアプリ業界標準。アルファテスター人数では概ね aspirational; アラートは主に「deploy 後の全セッションクラッシュ」回帰を捕捉
- **Crash-free users ≥ 95%** は sessions 99% より緩いのは、1 ユーザーが同じクラッシュを繰り返してもユーザー数は変わらず sessions のみ落とすケースを別軸でも捕捉する dual-metric 設計

## Sentry — アラート設定手順

**各** Sentry プロジェクト (`skeinly-android` と `skeinly-ios`) に対して実施。DE region URL は `https://de.sentry.io`。

### 1. Crash-free sessions アラート (プロジェクト毎)

1. Sentry → project `skeinly-android` → Alerts → Create Alert を開く
2. **Metric Alert** を選択 → 指標 = "crash-free session rate"
3. 条件:
   - Aggregate: `percentage()`
   - Filters: 空 (全環境、全リリース)
   - Window: 1 時間 rolling
   - Threshold: crash-free session rate が **99.0%** を ≥ 10 分間下回ったら発火
4. Actions: トリガー時と解決時に `skeinly.app@gmail.com` メール送信
5. Owner: unassigned (single-operator setup)
6. Name: `crash-free-sessions-android-alpha`
7. Save。`skeinly-ios` も同様 (name `crash-free-sessions-ios-alpha`)

### 2. Crash-free users アラート (プロジェクト毎)

同じフロー、ただし:
- 指標 = "crash-free user rate"
- Threshold: 95.0% (alpha) / 99.0% (GA)
- Name: `crash-free-users-android-alpha` と `-ios-alpha`
- アラート metadata の severity: `warn`

### 3. ANR アラート (Android のみ)

Sentry の ANR issue は `issue.category:performance` + `event.type:application_not_responding` の Issue として扱われる。Metric Alert でなく **Issue Alert** で設定。

1. Sentry → project `skeinly-android` → Alerts → Create Alert を開く
2. **Issue Alert** を選択
3. 条件:
   - When: "A new issue is created"
   - Filter: `event.type:application_not_responding`
   - Filter: `environment:production`
4. Actions: `skeinly.app@gmail.com` メール送信
5. Frequency: 同一 ANR グループにつき 1 時間に 1 回まで (固まったスレッドが繰り返し発火する notification storm を回避)
6. Name: `anr-fired-android-alpha`
7. Save

### 4. 命名 + 状態

5 件の Sentry アラート設定後、Sentry Dashboard → Issues → "Create test issue" で sanity check。Crash-free アラートはテスト 1 件では発火しない (rate は実セッションデータから算出) が、Issue Alert (#3) はテスト ANR issue を作れば発火する。メールがオペレーター inbox に届くか確認。

## Google Play Console — Vitals アラート設定

初回の internal-track アップロード成功後に 1 度実施。

1. Play Console → `skeinly` app → 品質 (Quality) → Android vitals → 概要 (Overview) を開く
2. "Vitals alerts" (または現行 Play Console UI の同等項目) クリック
3. 以下のアラートを設定 (Play Console の UI ラベルはリリース毎に変動。基底指標は下記):
   - **User-perceived ANR rate above bad behavior threshold (0.47%)** → email
   - **User-perceived crash rate above bad behavior threshold (1.09%)** → email
   - **Slow cold start rate above 30%** → email (info 重要度)
4. メール送信先: `skeinly.app@gmail.com`
5. Save

Play Vitals は日次集計。閾値超過時 24 時間 window に 1 回発火。

## 検証 — アルファローンチ準備チェック

アルファテスター招待前に確認:

- [ ] 5 つの Sentry アラート設定済 (2× crash-free sessions + 2× crash-free users + 1× ANR) — 命名規則 `*-{platform}-alpha`
- [ ] 3 つの Play Console Vitals アラート設定済 (ANR + crash + cold start)
- [ ] 各 Sentry アラート + 各 Play Vitals アラートのテストメールが `skeinly.app@gmail.com` に届く (silent config を信用しない)
- [ ] オペレーターのメールフィルタが Sentry / Play 送信者 (`alerts@sentry.io`, `noreply@google.com`) を自動アーカイブしない
- [ ] `secrets-rotation.md` retention 注記: アラート設定は本ドキュメントで管理、アラート自体に secret rotation 不要 (DSN / API アクセスは不変)

## アラート発火時の初動チェック

1. メール本文を読む — プロジェクト、issue title、影響リリース、Sentry Issue (または Play Vitals page) への deep link 含む
2. リンク先 Issue を開く。最上位の stack frame + 影響ユーザー数を読む
3. **一過性** (次の 30 分自動チェックで解決) だった場合は `incident-playbook.md` に記録して終了
4. **1 時間以上継続** する場合:
   - 重要度分類: blocker (全セッションクラッシュ) vs. one-edge-case (単一ユーザー、狭い stack)
   - 直近 commit を確認: `git log --since="48 hours ago"`。アラート発火直前の commit が容疑者
   - `incident-playbook.md` エントリを起票 + recovery フロー開始 (rollback、forward-fix、または accept-and-mitigate)
5. 24 時間以内に post-incident review を書く + アラートチューニング教訓 (閾値は妥当だったか? 発火タイミングは適切だったか?)

## アラートチューニングサイクル

アルファ期間はプロダクト検証期間であると同時にアラートチューニング期間。以下のチューニングサイクルを予期:

- **Week 1**: アルファローンチ + アラートノイズ。閾値較正; 1 日 5 回以上 benign で発火するなら閾値が間違っている
- **Week 2-3**: real signal vs. noise。observed false-positive vs. true-positive ratio で閾値を上下調整
- **Pre-GA**: §SLO テーブルの GA 目標まで締める。"page" 重要度が文字通りに: GA on-call rotation は real page を ≤ 15 分で応答必須

## クロスリファレンス

- [pre-alpha-checklist.md §46-47](../../en/ops/pre-alpha-checklist.md) — A28 + A29 closure 記録
- [incident-playbook.md](incident-playbook.md) — symptom-indexed failure mode (アラート対応のログ先)
- [Sentry Alert Rules ドキュメント](https://docs.sentry.io/product/alerts/create-alerts/)
- [Play Vitals ドキュメント](https://support.google.com/googleplay/android-developer/answer/9844486)

## 更新履歴

| 日付 | 変更 | 実施者 |
|---|---|---|
| 2026-05-12 | 初版 — pre-alpha audit 項目 A28 + A29 | b150005 |
