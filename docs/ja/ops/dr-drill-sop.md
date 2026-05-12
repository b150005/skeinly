# Runbook — Disaster Recovery (DR) Drill

> EN: [docs/en/ops/dr-drill-sop.md](../../en/ops/dr-drill-sop.md)

Prod Supabase プロジェクト上で migration 起因でないデータ喪失 incident (誤った destructive SQL、Dashboard 乗っ取り、faulty admin action) が発生した場合の復旧方法。[migration-rollback.md](migration-rollback.md) (migration 起因の rollback) とは別。

## RTO / RPO 目標

| 指標 | 目標 | 根拠 |
|---|---|---|
| **RTO** (Recovery Time Objective) | ≤ 1 時間 | オペレーターが incident に気づく → restore 開始 → 1 時間以内に service 復旧。Supabase PITR restore 時間 (Skeinly DB サイズで通常 5–15 分) + オペレーター対応 + 検証オーバーヘッド 30–45 分の制約下。 |
| **RPO** (Recovery Point Objective) | ≤ 5 分 | Supabase PITR の粒度によりデータ喪失は 5 分以内に bounded。Incident 直前 5 分以内の write は喪失リスクあり。 |

クローズドアルファ (≤10 テスター) 向け目標。Post-GA で scale が必要になれば締める。

## 前提条件

1. Supabase プロジェクトが **Pro tier 以上** (Free tier は daily snapshot のみ — PITR なし)。Dashboard → Project Settings → General で確認。2026-05-12 時点これは **ユーザー側 action V17** — アルファローンチ前に確認。
2. PITR retention window: 7 日 (Pro) / 14 日 (Team) / 28 日 (Enterprise)。Alpha は Pro = 7 日 window。Incident 発見が 7 日以上経過後だと PITR 復旧不可。
3. オペレーターが Supabase プロジェクト Owner role 保有 (Dashboard アクセス + SQL Editor 権限)。

## DR scenario — drill カタログ

Pre-alpha DR drill で walk through する scenario。各々は他プロジェクトで実際に起きた failure mode を反映。一度 drill しておけば、本番発生時に muscle memory が機能する。

### Scenario 1: WHERE なしの誤 `DELETE`

オペレーターが SQL Editor で `DELETE FROM patterns WHERE owner_id = 'abc'` を実行したつもりだったが、WHERE 句が不完全 (前半だけ選択していて、実際には `DELETE FROM patterns` が実行された)。

**症状**: `patterns` の全行が消失。アプリの Discovery feed が空。カスケード: 子テーブル `chart_documents` / `chart_versions` 等も消失。

**復旧**: Bad query 1 分前への PITR restore。Restore 後 SELECT で `patterns` が populate されていることを検証。

### Scenario 2: Live table への `DROP TABLE`

オペレーターが staging copy のつもりが prod table を drop。

**症状**: アプリ側 query が `relation does not exist` エラー。Sentry / app log に 500 番台が氾濫。

**復旧**: PITR restore。

### Scenario 3: 不具合 migration

Prod に commit + apply された migration が意図せざる destructive 文を含んでいた (例: rename のつもりが `DROP COLUMN`)。

**症状**: アプリ側 query で drop された column が select 時にエラー。既存行で当該 column のデータ喪失。

**復旧**: PITR restore。Migration 固有の復旧 logic は [migration-rollback.md](migration-rollback.md) 参照。

### Scenario 4: アカウント乗っ取り — Dashboard アクセス侵害

攻撃者が Owner の Supabase Dashboard 認証情報を入手し、テーブルへ `DROP TABLE`、`TRUNCATE`、`DELETE` 実行。

**症状**: Scenario 1–3 と同じ + `audit logs` (Supabase Dashboard → Project Settings → Audit Logs) に怪しい活動。

**復旧**:
1. Restore **前に** Supabase Owner 認証情報を rotate (パスワード + 漏洩した PAT トークン) — 攻撃者アクセスが残ったまま restore すると同じ被害が再発する。
2. Dashboard Audit Log で攻撃者活動の全範囲を確認 (他のテーブル touch、RLS policy 編集、secret reset)。
3. 最も早い攻撃者活動の 1 分前へ PITR restore。
4. Supabase に保管されていて漏洩した可能性のある全 secret を force rotation (Edge Function secrets、RevenueCat / GitHub App / FCM / APNs trio、`secrets-rotation.md` 参照)。
5. `docs/en/ops/incident-playbook.md` に post-incident report を起票。

### Scenario 5: RLS policy バグで大量上書き発生

新規 RLS policy の OR 句に bug があり、authenticated ユーザー A が誤って user B の行を UPDATE できる状態に。大量上書きが発覚。

**症状**: ユーザーが「自分のプロジェクトに他人のコンテンツが見える」と報告、または SQL Editor からのデータ整合性チェックで行 version drift 発覚。

**復旧**:
1. 先に診断 (絶対に panic-restore しない — 壊れた policy を残したまま restore すると被害が再発する)。SQL Editor:
   ```sql
   -- Policy 確認:
   SELECT polname, pg_get_expr(polqual, polrelid) FROM pg_policy
   WHERE polrelid = 'public.<table>'::regclass;
   ```
2. Policy を DROP / 修正 (forward-fix migration)。
3. Policy 適用 1 分前 (または最初の大量上書きイベント 1 分前) へ PITR restore。
4. Restore 後に訂正済 policy を適用。

## Drill 手順 (quarterly、初回は pre-alpha)

1. **スケジュール**: low-traffic 時間帯を選ぶ (pre-alpha は何時でも可、post-alpha は off-peak)。
2. **通知**: オペレーター private notebook に 1 行記録: 「DR drill 2026-MM-DD HH:MM — scenarios N, N, N 実施」。
3. **セットアップ**: Supabase に throwaway な side-project を作成 (drill 用途なら free tier で OK) または staging プロジェクトがあればそれを使う。**Prod に対して destructive SQL を含む drill は絶対に実施しない。**
4. **各 scenario walk through**:
   1. 上記の scenario 手順を読む
   2. Disaster を simulate (例: staging プロジェクト上で `DELETE FROM patterns`)
   3. Simulated disaster の timestamp 記録
   4. Dashboard → Database → Backups → Point-in-Time Recovery を開く
   5. Simulated disaster 1 分前の timestamp を pick
   6. Restore クリック
   7. Restore 完了まで wait、完了時刻を記録
   8. Staging プロジェクトのデータが pre-disaster 状態と一致することを SELECT で検証
   9. RTO 記録 (simulated disaster → restore 完了 + 検証完了までの時間)
5. **集計 RTO**: 5 scenario の平均。1 時間超ならば runbook を refine (より明確な手順、pre-staged credential 等) して再 drill。
6. **結果 documented**: 下の drill log table に行追加。

## Drill log

| 日付 | オペレーター | 実施 scenario | 平均 RTO | メモ / 改善 |
|---|---|---|---|---|
| _未実施_ | — | — | — | 初回 drill を pre-alpha launch 前に予定 (目標: 2026-05-XX)。 |

## PITR 手順 (本番 incident 中)

実 incident 発生時 (drill ではない):

1. **冷静に、まず止血**: incident 進行中なら (攻撃者が session 内、bad SQL が cron で走っている等) restore 試行前に source を terminate する
   - Dashboard 乗っ取り: 認証情報 rotate + Supabase → Project Settings → Security から active session を revoke
   - 暴走 SQL: Dashboard SQL Editor で `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE state = 'active' AND pid <> pg_backend_pid();` で connection を kill
   - Migration roll-out 途中の失敗: [migration-rollback.md](migration-rollback.md) 参照

2. **Target restore timestamp の特定**: log (Sentry、PostHog、Supabase audit log、application telemetry) から incident 開始時刻を trace。Target = (incident 開始) − 1 分を safety margin として選択。

3. **Downtime 通知**: Skeinly には in-app status page なし (アルファ規模)。クローズドテスター用 Slack / Discord channel があればそこか、email でアルファテスターに通知。

4. **Restore 実行**:
   - Supabase Dashboard → Database → Backups → Point-in-Time Recovery → timestamp 選択
   - Restore クリック。Restore window 中はプロジェクトが **read-only その後 unavailable**
   - Restore 時間は alpha 時の Skeinly DB サイズで通常 5–15 分

5. **Restore 後検証**:
   - 影響テーブルの SELECT count が pre-incident expectation と一致
   - TestFlight build で代表的なフロー (サインイン → Discovery 読み込み → パターン open) をテスト
   - Restore で migration が "un-applied" になっていないことを確認: `SELECT version FROM supabase_migrations.schema_migrations ORDER BY version DESC LIMIT 5;` が pre-incident 状態と一致

6. **Forward-fix 適用** (必要なら): Restore で incident 後に必要な訂正 migration が戻されていれば再適用。Incident 起因の faulty migration が戻されていれば再適用しない。

7. **Post-incident report**: `docs/en/ops/incident-playbook.md` にエントリ追加:
   - Incident 日時
   - 症状 + 検知経緯
   - 根本原因
   - 復旧アクション (restore target time、restore duration、RTO、RPO)
   - 教訓 + runbook 改善

## Daily-snapshot fallback (Free tier のみ)

V17 が未完で incident 発生時に Free tier のままだった場合:

1. Supabase Dashboard → Database → Backups → "Restore from daily backup"
2. 最新 daily snapshot のみが target、データ喪失は 24 時間に bounded
3. RPO = 24 時間 (worst case)、Pro PITR 5 分目標より大幅に悪い

**結論**: V17 (Pro tier アップグレード) は実質 alpha 前必須。$25/月 Pro tier コストは Free tier の回復不能データ喪失リスクと比較すれば trivial。

## このランブックがカバーしないもの

- **Migration 起因の rollback** — [migration-rollback.md](migration-rollback.md) 参照
- **アプリ側 incident** (crash storm、ANR spike、push failure cascade) — [incident-playbook.md](incident-playbook.md) 参照
- **Secret 漏洩 rotation 手順** — [secrets-rotation.md](secrets-rotation.md) 参照
- **Storage のみのデータ喪失** (Supabase Storage ファイル削除、DB は intact)。Storage は PITR 対象外、失った Storage object は復旧不能。緩和: アプリはユーザー所有フォルダにアバター / 編み図画像をアップロード、喪失はユーザー単位、グローバルではない。

## クロスリファレンス

- [migration-rollback.md](migration-rollback.md) — migration rollback (別の incident class)
- [secrets-rotation.md](secrets-rotation.md) — secret rotation 手順
- [incident-playbook.md](incident-playbook.md) — symptom-indexed failure mode (ほぼ app-side)
- [pre-alpha-checklist.md §26](../../en/ops/pre-alpha-checklist.md) — A17 closure 記録
- [Supabase PITR ドキュメント](https://supabase.com/docs/guides/platform/backups#point-in-time-recovery)

## 更新履歴

| 日付 | 変更 | 実施者 |
|---|---|---|
| 2026-05-12 | 初版 DR drill runbook — pre-alpha audit 項目 A17 | b150005 |
