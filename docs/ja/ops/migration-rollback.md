# Runbook — Migration Rollback

> EN: [docs/en/ops/migration-rollback.md](../../en/ops/migration-rollback.md)

破壊的な Supabase migration が prod に landed して間違いだったと判明した場合の復旧方法。**forward-only** 原則、destructive-migration マトリックスと recovery path、drill discipline をカバーする。

## メンタルモデル

Supabase migration は**forward-only**。プラットフォームが実行してくれる `down` step は無く、一般に migration を "uninstall" することはできない。復旧は常に以下のいずれか:

- **Forward-fix** — 前の変更を schema レベルで undo する新しい migration を書く。純粋な構造変更 (drop 済 column を追加し直す、drop 済 function を復元する、policy 編集を逆にする) で機能する。元 migration で失われた**データ自体は forward-fix では戻らない**。
- **PITR restore** — Supabase Point-in-Time Recovery で、bad migration 前の snapshot にデータベース全体を巻き戻す。データは戻るが間隔中の他の write は全て失われる。Supabase Pro 必須 (PITR retention default 7 日)。
- **両方** — 同時 user activity を伴う破壊的 migration では PITR restore + 訂正 migration の両方が必要

選択は migration class に依存。下のマトリックスを使う。

## Destructive migration マトリックス

| Migration class | データ喪失? | Schema 復元可能? | Recovery path |
|---|---|---|---|
| `DROP TABLE` | ✅ あり (table 内の全データ) | ❌ なし (データは消えた) | **PITR restore** がデータを戻す唯一のパス。Forward-fix で table schema は復元できるが行は復元できない。 |
| `DROP COLUMN` | ✅ あり (column のデータ) | ✅ あり (schema レベル) | データには **PITR restore** + forward-fix migration で同じ type/constraint で column を re-add。Column が本番未使用なら forward-fix のみで OK。 |
| `ALTER COLUMN ... TYPE` (lossy cast) | ✅ あり (精度 / format 喪失) | ✅ あり (schema レベル) | データには **PITR restore**。Forward-fix だけだと column type は戻るが cast 済の値は既に truncate されている。 |
| `ALTER COLUMN ... TYPE` (widening / lossless) | ❌ なし | ✅ あり | Forward-fix migration `ALTER COLUMN ... TYPE` で narrower type に戻す。全値が narrower type に収まるか先に確認。 |
| `DROP FUNCTION` | ❌ なし (data 無し) | ✅ あり | Forward-fix migration で function を定義から recreate。SECURITY DEFINER function は `pg_get_functiondef` 出力を version control 内に keep (`supabase/migrations/` history 参照)。 |
| `ALTER FUNCTION ... SET search_path = ''` | ❌ なし | ✅ あり | Forward-fix `ALTER FUNCTION ... RESET search_path` (または前の値に set)。これが必要になることは無いはず — `search_path` lock は security 向上。 |
| `REVOKE EXECUTE` | ❌ なし | ✅ あり | Forward-fix `GRANT EXECUTE ON FUNCTION ... TO <role>` で前の ACL を復元。 |
| `DROP POLICY` / `CREATE POLICY` replace | ❌ なし | ✅ あり | Forward-fix migration で `DROP POLICY IF EXISTS` + `CREATE POLICY` を前の式で。Reverse 作成を容易にするため、元 migration の comment に `pg_get_expr(polqual, polrelid)` 出力を keep する。 |
| `DELETE FROM <table> WHERE ...` (data-only migration) | ✅ あり (削除された行) | n/a | **PITR restore** が唯一のパス。`supabase/migrations/` には data-only migration を入れない方針が望ましい。Dashboard SQL editor で one-shot delete を明示承認付きで実施する。 |
| `RLS DISABLE` 後 re-`ENABLE` | 場合による — disable と re-enable の間で RLS 違反となる service-role / postgres-role write が persist された場合あり | ✅ schema レベル | RLS-bypass window で bad write が走った可能性があれば **PITR restore**。それ以外は forward-fix で re-`ENABLE` のみで OK。 |

## Pre-migration safety discipline

Column / table / function を DROP する、RLS を変更する、CHECK / FK constraint を変更する全 migration は MUST:

1. **SQL ファイル冒頭の migration comment header に `BREAKING` タグ**。`supabase/migrations/` を risk 観点で grep する reviewer / future operator が destructive migration を header grep で識別できるように。
2. **同じ commit 内に rollback plan を documented**:
   - Schema レベルで reversible なら、migration の末尾に reverse migration の literal SQL を comment block で記載。例:
     ```sql
     -- Rollback plan (forward-fix):
     -- ALTER TABLE public.foo ADD COLUMN bar TEXT NOT NULL DEFAULT '';
     -- UPDATE public.foo SET bar = ... WHERE ...;
     ```
   - 不可逆なら (DROP TABLE、lossy ALTER)、PITR target time + data-loss boundary (どの他の write が in-window か) を documented。PITR を取るか loss を accept するかは Owner 判断。
3. **Low-write window で apply** することで rollback 時の PITR blast radius を tight に。Pre-alpha は user がいないので全時間が "low-write"、post-alpha は off-peak hour を preferred。

## Pre-v1 breaking-change policy

プロジェクト memory `pre_v1_breaking_changes.md` の通り、Phase 40 GA までは destructive migration を backward-compat shim 無しで permitted (より良い v1.0 outcome を産む場合)。既存 2 例:

- Phase D terminology audit: `pull_requests` → `suggestions` ファミリーの rename (migrations 026, 027)。Backward-compat view 無し、既存 code は同じ PR 内で switched。
- Phase 39 pre-alpha hardening (this wave): migration 030 で `comments` SELECT policy の semantics を変更、share-token leak arm を削除。既存の app-side query は引き続き動作、leak arm に依存していた client は見える行が減る (share-token UX で comment 可視性を advertise していなかったので今日該当する client は無い)。

Phase 40 GA でこのポリシーは tighten される。GA 後の destructive migration は要求:

- Backward-compat を accept する理由を明文化した ADR
- Coupled client-side compatibility window (例: schema removal と migration apply の間に app 2 release)
- Double-staffed review

## Rollback drill — practice procedure

初めて rollback が必要になった瞬間は手順を考える時間ではない。Alpha launch 前に 1 度 + その後 quarterly に 1 度 drill を実施する。

1. 最近の reversible migration を選ぶ (例: `030_pre_alpha_security_hardening.sql`)
2. Supabase Dashboard → SQL Editor を開く
3. **local のみ** で reverse migration を author (prod に apply しない) — migration 030 なら:
   ```sql
   -- Reverse of 030 (drill — do not apply).
   ALTER FUNCTION public.handle_new_user()              RESET search_path;
   ALTER FUNCTION public.set_progress_owner_id()        RESET search_path;
   ALTER FUNCTION public.touch_subscriptions_updated_at() RESET search_path;
   ALTER FUNCTION public.update_updated_at()            RESET search_path;
   GRANT EXECUTE ON FUNCTION public.grant_alpha_pro(uuid) TO anon, authenticated;
   -- ... + 残り 8 個の GRANT EXECUTE 文 ...
   -- + `Anyone can read avatars` policy 復元
   -- + share-token arm 付き comments SELECT policy 復元
   ```
4. Reverse migration が syntactically valid であることを確認: SQL Editor に貼って `BEGIN; ... ROLLBACK;` で囲み、変更を commit しない
5. Reverse migration が constraint / dependency で trip したらそれが教訓 — 元 migration の "Rollback plan" comment block を訂正 reverse で update
6. Drill output は廃棄。実際の rollback が必要にならない限り reverse migration を commit しない

## PITR procedure

Forward-fix では不足で実際の PITR restore が必要な場合:

1. Target time を特定。`supabase_migrations.schema_migrations.version` (or Dashboard Migrations ページ) で migration apply timestamp を確認 — bad migration を除外するため**その 1 分前**に restore する
2. Data-loss boundary を決定。Target time から現在までの全 write が失われる。Pre-alpha は失う user データ無し、post-alpha は影響 user に loss window を communicate
3. Supabase Dashboard → Database → Backups → Point-in-Time Recovery を開く
4. Target timestamp を pick
5. "Restore" クリック — Supabase が in-place restore を開始
6. **WAIT** — restore 中プロジェクトは unavailable。小規模 DB なら分単位、大規模なら長くなる
7. Restore 後検証:
   - `supabase_migrations.schema_migrations` に bad migration が無いこと
   - 影響 schema state が pre-migration expectation と一致
   - Application traffic が正常に resume
8. 元意図が正しく実装だけ間違いだったら訂正 forward migration を author。元意図自体が間違いだったら re-apply 無しで archive

## Forward-fix と PITR の選択

| Symptom | 選択 |
|---|---|
| Schema が間違っているが間違った schema を通った production data 無し | Forward-fix |
| 間違った RLS policy が live だったが leak read が発生していないと証明可能 | Forward-fix |
| 間違った RLS policy が live で leak read 発生した可能性あり | PITR — restore + post-mortem |
| 重要な行のある table を DROP TABLE | PITR |
| 重要なデータの column を DROP COLUMN | PITR |
| Function を誤って drop / replace、data implication 無し | Forward-fix |
| `ALTER COLUMN ... TYPE` で値を truncate | PITR |
| Bad data-only migration で user 行を delete | PITR |

迷ったら**先に PITR**、その上に forward-fix を apply する。PITR は 10 分操作、log から失ったデータを reconstruct する作業は青天井。

## クロスリファレンス

- [release.md](release.md) — app-release operation (DB migration とは別)
- [secrets-rotation.md](secrets-rotation.md) — secret rotation (DB-rollback とは無関係)
- [incident-playbook.md](incident-playbook.md) — symptom-indexed failure mode
- [supabase/migrations/](../../../supabase/migrations/) — migration history そのもの
- [pre-alpha-checklist.md §27.2](../../en/ops/pre-alpha-checklist.md) — pre-alpha audit 項目 A18 closure

## 更新履歴

| 日付 | 変更 | 実施者 |
|---|---|---|
| 2026-05-12 | 初版 — pre-alpha audit 項目 A18 | b150005 |
