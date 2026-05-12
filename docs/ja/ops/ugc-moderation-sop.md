# UGC モデレーション — オペレータ SOP

> 英語版が source of truth: [docs/en/ops/ugc-moderation-sop.md](../../en/ops/ugc-moderation-sop.md)。最新の手順は英語版を参照。

`submit-ugc-report` Edge Function (ADR-021 §D5) 経由で受領した UGC レポートのトリアージ手順。pre-alpha checklist の **A1** (UGC モデレーション) と **A5** (Block User 機構の基盤) を closure する。

## スコープ

この SOP は pre-alpha でリリースされる **foundation slice** を扱う:

- データスパイン: `public.ugc_reports` + `public.user_blocks` (migration 031)
- RLS レベルの block フィルタ (migration 032)
- `submit-ugc-report` Edge Function → label `ugc-report` で [b150005/skeinly](https://github.com/b150005/skeinly) に GitHub Issue ミラー
- Supabase Dashboard SQL Editor 経由のオペレータ介入トリアージ

アプリ内 Report ボタン + Block User UI + Blocked Users 一覧は pre-Phase-40 GA で出荷 (ADR-021 §D4)。それまで、alpha テスターは `skeinly.app@gmail.com` に objectionable content を報告し、オペレータが代理で本 SOP を実行する。

## SLA

レポート受領から解決判断まで **24 時間**。Apple Guideline 1.2 が "objectionable content removal" にこのフロアを設定しており、カテゴリに関わらず全レポートで採用する。

## レポート着信時の手順

トリガー: [b150005/skeinly に `ugc-report` ラベル付きの新 Issue](https://github.com/b150005/skeinly/issues?q=is%3Aopen+label%3Augc-report) が現れる。

Issue 本文は `submit-ugc-report/mapping.ts` がレンダリングする以下のシェイプ:

```text
## UGC report

**Report ID**: `<UUID>`
**Reporter**: `<UUID>`
**Target**: `<target_type>` / `<target_id>`
**Reason category**: `<category>`

### Reason

<最初の 500 文字; 超過時は注釈付き truncate>
```

### ステップ 1 — 受領記録

Issue 着信から 1 時間以内 (深夜着信なら朝一):

1. GitHub Issue に `triaging` ラベルを追加。
2. DB 行 state を triage 進行中に更新:
   ```sql
   UPDATE public.ugc_reports
   SET state = 'triaging',
       operator_notes = 'Triage 開始 <YYYY-MM-DD HH:MM> JST.'
   WHERE id = '<report_id>';
   ```

### ステップ 2 — 対象行の特定

Issue 本文を読み、埋め込みの Dashboard SQL を実行:

```sql
SELECT * FROM public.<target_type>s WHERE id = '<target_id>';
```

複数形テーブル名のマッピング:

| target_type | テーブル |
|---|---|
| `pattern` | `public.patterns` |
| `comment` | `public.comments` |
| `suggestion` | `public.suggestions` |
| `suggestion_comment` | `public.suggestion_comments` |

行が既に削除されている場合は「Target row no longer exists at triage time」と記録し dismissed で close。詳細は「エッジケース」セクション参照。

### ステップ 3 — フル理由テキストの取得

Issue 本文は最初の 500 文字のみ表示。フル理由は:

```sql
SELECT reason FROM public.ugc_reports WHERE id = '<report_id>';
```

### ステップ 4 — 判断

カテゴリと内容に応じて 3 つの解決から選択:

#### resolved_remove — コンテンツがポリシー違反

Apple Guideline 1.2 に従い該当行を非公開化または削除。

- **Pattern**: `visibility = 'private'` (DELETE しない — 異議申し立て時の証拠保全):
  ```sql
  UPDATE public.patterns SET visibility = 'private' WHERE id = '<target_id>';
  ```
- **Comment / suggestion / suggestion_comment**: DELETE (visibility カラムなし):
  ```sql
  DELETE FROM public.comments WHERE id = '<target_id>';
  -- または suggestions / suggestion_comments
  ```

レポートを close:

```sql
UPDATE public.ugc_reports
SET state = 'resolved_remove',
    operator_notes = '<YYYY-MM-DD> に非公開化/削除. 理由: <1行サマリー>.',
    resolved_at = now()
WHERE id = '<report_id>';
```

#### resolved_keep — false-positive / コンテンツ正当

レポーターの誤認、またはコンテンツが実際にはポリシー違反でない場合。

```sql
UPDATE public.ugc_reports
SET state = 'resolved_keep',
    operator_notes = '<YYYY-MM-DD> レビュー. ポリシー違反でない理由: <1行>.',
    resolved_at = now()
WHERE id = '<report_id>';
```

#### dismissed — 虚偽レポート (報復目的)

レポーターが対象ユーザーへの嫌がらせのため虚偽レポートを提出。レポーター UUID を記録 — 30 日以内に 3 件以上の `dismissed` がある UUID は問題ある報告者の可能性大。

```sql
UPDATE public.ugc_reports
SET state = 'dismissed',
    operator_notes = '<YYYY-MM-DD> dismissed — 虚偽レポートの兆候. 報告者の履歴を別途監査.',
    resolved_at = now()
WHERE id = '<report_id>';

-- 報告者の履歴監査:
SELECT id, state, reason_category, target_type, created_at
FROM public.ugc_reports
WHERE reporter_id = '<reporter_uuid>'
ORDER BY created_at DESC;
```

過去 30 日で `dismissed` が 3 件以上ある場合は escalation: アカウント suspension の検討、または最低限 manual な rate limit (= leadership-level decision)。

### ステップ 5 — GitHub Issue を close

resolution への参照を含むコメントを追加:

```text
Closed: <state> — see ugc_reports.id <report_id>.
<operator_notes の 1 行サマリー>
```

その後 Issue を close + `triaging` ラベル除去。

### ステップ 6 — レポーターへの通知 (任意)

`resolved_remove` の場合のみ、対応完了通知を courtesy としてメールしてもよい (pre-alpha では契約上の義務でない)。レポーターメール取得:

```sql
SELECT u.email
FROM auth.users u
WHERE u.id = '<reporter_uuid>';
```

`resolved_keep` / `dismissed` の場合は通知しない — 拒否理由の説明は議論を招きやすく alpha スケールでは生産的でない。

## Block User — オペレータ介入

テスターが `skeinly.app@gmail.com` に `<blocked-display-name>` のブロック依頼を送る場合。

### blocked ユーザーの特定

```sql
-- blocker (依頼テスター) を UUID に解決:
SELECT id FROM auth.users WHERE email = '<blocker-email>';
-- blocked (対象ユーザー) を display_name から:
SELECT id FROM public.profiles WHERE display_name = '<blocked-display-name>';
-- display_name が曖昧なら blocker から見える最近のインタラクションで絞り込み:
SELECT DISTINCT c.author_id, p.display_name
FROM public.comments c
JOIN public.patterns pat ON pat.id = c.target_id AND c.target_type = 'pattern'
JOIN public.profiles p ON p.id = c.author_id
WHERE pat.owner_id = '<blocker-uuid>';
```

### block を挿入

```sql
INSERT INTO public.user_blocks (blocker_id, blocked_id)
VALUES ('<blocker-uuid>', '<blocked-uuid>')
ON CONFLICT DO NOTHING;
```

Migration 031 が `blocker_id <> blocked_id` を CHECK、`auth.users` の FK CASCADE を保証 (ADR-005 アカウント削除パスでクリーン)。Migration 032 の RLS NOT-EXISTS arm が即時発動 — blocked ユーザーのコンテンツが blocker の patterns / comments / suggestions / suggestion_comments クエリから消える。

### テスターに確認メール

> 「ブロック適用: `<blocked-display-name>` は Discovery および Suggestion スレッドに今後表示されません。後で解除する場合は skeinly.app@gmail.com まで。GA で Settings → Privacy にブロック一覧が実装されます。」

## 監視クエリ

オペレータ daily / weekly ダッシュボード:

```sql
-- 未対応レポート (SLA 違反に近い順):
SELECT id, target_type, reason_category, created_at,
       extract(epoch FROM (now() - created_at))/3600 AS hours_open
FROM public.ugc_reports
WHERE state IN ('open', 'triaging')
ORDER BY created_at ASC;

-- GitHub Issue ミラー失敗 (Edge Function best-effort fallback per ADR-021 §D3):
SELECT id, target_type, reason_category, created_at
FROM public.ugc_reports
WHERE github_issue_url IS NULL AND state = 'open'
ORDER BY created_at ASC;

-- ブロック被件数 (大量集中は brigading 攻撃の兆候):
SELECT blocked_id, count(*) AS times_blocked
FROM public.user_blocks
GROUP BY blocked_id
HAVING count(*) >= 3
ORDER BY times_blocked DESC;
```

## エッジケース

### 対象行が既に存在しない

レポーターが triage 前に削除された行に対してレポートを提出。Edge Function は提出時点で target_id の存在検証を行わない (意図的 — `mapping.ts` `validateInput` の rationale 参照)。「Target row no longer exists at triage time」operator_notes で `dismissed`。

### オペレータ自身が所有する行へのレポート

オペレータがテスターも兼ねている場合に発生しうる。利益相反のため recuse: operator_notes に conflict of interest を記載して `dismissed`。将来 GA で 2 名以上のモデレータプールを導入すれば recusal なしで処理可能。

### 同一コンテンツへの複数レポート

`idx_ugc_reports_target` インデックスが以下のクエリをサポート:

```sql
SELECT * FROM public.ugc_reports
WHERE target_type = '<t>' AND target_id = '<id>'
ORDER BY created_at ASC;
```

最初のレポートを内容で解決。後続レポートは:
- 最初が `resolved_keep` なら `resolved_keep` で close (operator_notes に prior report_id 参照)
- 最初が `resolved_remove` なら既に削除済み (operator_notes に同じく参照)

再調査不要。

### Block/Unblock の繰り返し悪用

各 unblock は `public.user_blocks` の DELETE。RLS フィルタは行の不在を即座に検出して可視性を回復する — "soft block" の中間状態はない。テスターが cyclic block/unblock 悪用を報告した場合は元レポートの operator_notes に記録。GA UI で unblock 発効までのクールダウン導入を検討する。

## コンプライアンス姿勢 (Apple Guideline 1.2 / Google Play UGC policy)

| 要件 | ステータス | 備考 |
|---|---|---|
| フィルタ / モデレーション機構 | ✅ (本 SOP + RLS フィルタ) | pre-alpha はオペレータ介入. 自動キーワードフィルタは GA 後にボリュームが正当化すれば再検討 (ADR-021 §A3) |
| レポート機構 | ✅ (foundation) | Edge Function + GitHub Issue トリアージ. アプリ内 Report ボタンは GA |
| Block User | ✅ (foundation) | データモデル + RLS フィルタ live. オペレータがテスター依頼で INSERT. アプリ内 UI は GA |
| 公開連絡先 | ✅ | `skeinly.app@gmail.com` が Privacy / ToS / Help / Settings / web account-deletion / app footer に掲載済 |

## 参考

- [ADR-021 — Pre-Alpha UGC Moderation](../adr/021-pre-alpha-ugc-moderation.md) (英語; JA mirror なし)
- [Migration 031 — `ugc_reports` + `user_blocks`](../../../supabase/migrations/031_ugc_moderation.sql)
- [Migration 032 — RLS block filter](../../../supabase/migrations/032_ugc_block_filter.sql)
- [Edge Function `submit-ugc-report`](../../../supabase/functions/submit-ugc-report/README.md)
- [Apple App Store Review Guideline 1.2 — User Generated Content](https://developer.apple.com/app-store/review/guidelines/#user-generated-content)
- [Google Play UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937)

## Revision history

| 日付 | 変更 | Author |
|---|---|---|
| 2026-05-12 | 初版 — foundation slice (pre-alpha A1+A5 closure) | b150005 |
