# SOP — GDPR / CCPA Data Export Request

> JA: [docs/ja/ops/data-export-sop.md](../../ja/ops/data-export-sop.md)

How to fulfill a user's data-portability request (GDPR Article 20 / CCPA right to know). Operator-driven for alpha; the in-app "Export My Data" button (A20 Option B) is scheduled pre-Phase-40 GA.

## SLA

GDPR Article 12(3): respond within **one month** (30 calendar days) of receipt. Extendable by two additional months for complex / numerous requests, but a 30-day initial response is the target. CCPA: 45 days.

Skeinly's alpha-scope operational target: **respond within 7 calendar days**. The smaller window keeps the SOP rehearsed and avoids the SLA bunching that develops at the 30-day boundary.

## Receipt → fulfillment workflow

### 1. Receipt

The user emails `skeinly.app@gmail.com` (the designated support address documented in the Privacy Policy + ToS DMCA agent block) with the subject line containing the phrase `Data Export Request` or `データエクスポート` (or the user uses a free-form subject that the operator classifies as one).

Operator logs the request in a private notebook entry with:
- Receipt timestamp (yyyy-mm-dd hh:mm in JST)
- Requester email (the From address)
- Requester Supabase UID (derived from the email after step 2 confirms identity)
- Target response date (receipt + 7 days)

### 2. Identity verification

Reply to the requester confirming receipt **and** asking them to verify identity by responding from the email address associated with their Skeinly account. If the original message already came from the account email, this step is implicit.

Verification language (EN):
> Hi — we've received your data export request. To confirm your identity, please reply from the email address associated with your Skeinly account. We'll respond with your data export within 7 calendar days of identity confirmation.

JA:
> ご連絡ありがとうございます。データエクスポートのご請求を受け付けました。本人確認のため、Skeinly アカウントに登録されているメールアドレスから返信をお願いします。本人確認後、7 暦日以内にデータエクスポートをお送りします。

If the user replies from a different address, refuse the request and ask them to use the account email — do NOT process the request from an unverified channel (GDPR Article 12(6) authorizes this).

### 3. Look up the user's Supabase UID

In Supabase Dashboard → SQL Editor:

```sql
SELECT id, email, created_at, last_sign_in_at
FROM auth.users
WHERE lower(email) = lower('<requester_email>');
```

If zero rows: respond to the requester that no account exists with that email. Done.

If one row: copy the `id` (UUID). This is the `<uid>` used in step 4.

If multiple rows: should never happen (`auth.users.email` is unique-indexed in Supabase). Escalate.

### 4. Generate the export bundle

Run the export SQL below in Supabase Dashboard → SQL Editor, parameterized with the user's UID. Use a SQL block that emits one JSON object per row so the output is easy to consume.

```sql
-- Replace <uid> with the actual UUID inline (Dashboard SQL Editor
-- doesn't support :variable parameter binding for SELECT-only sessions).

WITH user_uid AS (SELECT '<uid>'::uuid AS uid)

SELECT 'profile' AS table, row_to_json(t) AS data
FROM public.profiles t, user_uid u
WHERE t.id = u.uid

UNION ALL SELECT 'pattern', row_to_json(t)
FROM public.patterns t, user_uid u
WHERE t.owner_id = u.uid

UNION ALL SELECT 'project', row_to_json(t)
FROM public.projects t, user_uid u
WHERE t.owner_id = u.uid

UNION ALL SELECT 'progress', row_to_json(t)
FROM public.progress t, user_uid u
WHERE t.owner_id = u.uid

UNION ALL SELECT 'project_segment', row_to_json(t)
FROM public.project_segments t
JOIN public.projects pr ON pr.id = t.project_id
JOIN user_uid u ON pr.owner_id = u.uid

UNION ALL SELECT 'chart_document', row_to_json(t)
FROM public.chart_documents t
JOIN public.patterns p ON p.id = t.pattern_id
JOIN user_uid u ON p.owner_id = u.uid

UNION ALL SELECT 'chart_version', row_to_json(t)
FROM public.chart_versions t
JOIN public.patterns p ON p.id = t.pattern_id
JOIN user_uid u ON p.owner_id = u.uid

UNION ALL SELECT 'chart_variation', row_to_json(t)
FROM public.chart_variations t
JOIN public.patterns p ON p.id = t.pattern_id
JOIN user_uid u ON p.owner_id = u.uid

UNION ALL SELECT 'share', row_to_json(t)
FROM public.shares t, user_uid u
WHERE t.from_user_id = u.uid OR t.to_user_id = u.uid

UNION ALL SELECT 'comment', row_to_json(t)
FROM public.comments t, user_uid u
WHERE t.author_id = u.uid

UNION ALL SELECT 'suggestion', row_to_json(t)
FROM public.suggestions t, user_uid u
WHERE t.author_id = u.uid

UNION ALL SELECT 'suggestion_comment', row_to_json(t)
FROM public.suggestion_comments t, user_uid u
WHERE t.author_id = u.uid

UNION ALL SELECT 'activity', row_to_json(t)
FROM public.activities t, user_uid u
WHERE t.actor_id = u.uid

UNION ALL SELECT 'device_token', row_to_json(t)
FROM public.device_tokens t, user_uid u
WHERE t.user_id = u.uid

UNION ALL SELECT 'subscription', row_to_json(t)
FROM public.subscriptions t, user_uid u
WHERE t.user_id = u.uid

UNION ALL SELECT 'user_symbol_pack_state', row_to_json(t)
FROM public.user_symbol_pack_state t, user_uid u
WHERE t.user_id = u.uid

UNION ALL SELECT 'feedback', row_to_json(t)
FROM public.feedback t, user_uid u
WHERE t.user_id = u.uid;
```

Click "Download CSV" in the Dashboard. This produces a single `.csv` file with one column `table` + one column `data` (the JSON blob per row). Acceptable as a portable export format. For users who request JSON specifically, rerun and select JSON output instead.

Also enumerate the user's Storage avatar object(s):

```sql
SELECT name, bucket_id, created_at, updated_at, last_accessed_at, metadata
FROM storage.objects
WHERE bucket_id = 'avatars'
  AND (storage.foldername(name))[1] = '<uid>';
```

For each row, download the file via the Dashboard → Storage → avatars browser. Bundle the avatar file(s) with the CSV.

### 5. Note on auth.users metadata

`auth.users` has email, created_at, sign-in timestamps, and the `raw_user_meta_data` JSONB (full_name etc.). Include the relevant fields in the export as a separate `account` JSON object:

```sql
SELECT id, email, created_at, last_sign_in_at, raw_user_meta_data
FROM auth.users
WHERE id = '<uid>';
```

Operator copies the row's fields into the bundle manually.

### 6. Note on bug-report data (GitHub)

Bug reports submitted via the in-app "Send Feedback" button are filed as GitHub Issues on `b150005/skeinly` by the `Skeinly Feedback` GitHub App. They are visible publicly and tied to the user's PostHog `distinct_id` (anonymous per-install UUID), NOT the user's Supabase UID. The export bundle should include the user's PostHog distinct_id (from the local app's PostHog state if known; if unknown, document "unknown — no easy server-side lookup") and a note pointing the user to GitHub issue search for any Issues they recall filing.

### 7. Note on RevenueCat / Apple / Google IAP

Subscription transactions live partially in RevenueCat + partially in Apple/Google. Document the user's RevenueCat `app_user_id` (= Supabase UID) so they can request their RevenueCat-side records directly from RevenueCat support if they want them. Apple/Google IAP history is requestable from Apple/Google directly per their respective data-access flows; outside Skeinly's control.

### 8. Note on Sentry + PostHog (opt-in)

If the user has consented to Sentry / PostHog (Phase 39.4 W3 consent UI), document their anonymous install UUID for each. Sentry / PostHog have their own per-user data access flows on request; we provide the UUID so they can self-serve.

### 9. Compose the response email

Reply to the user with:

- The CSV (and avatar files if any) as attachments
- Plain-English summary listing what's included
- A note on what's NOT in the bundle (the items listed in steps 6–8 above)
- A pointer to the Privacy Policy data-portability section

Template (EN):
> Hi — here's your Skeinly data export per your request received <date>.
>
> Attached:
>   - `skeinly-export-<short-uid>.csv` — your account, profile, patterns, projects, progress, segments, charts, variations, shares, comments, suggestions, activities, device tokens, subscriptions, symbol-pack state, feedback rows.
>   - Any avatar image files you uploaded.
>
> Not included (not under Skeinly's direct control — request directly from the source if you need them):
>   - Apple App Store / Google Play IAP transaction history (request via Apple or Google data-access tools)
>   - RevenueCat subscription analytics records (your RevenueCat app_user_id is `<uid>`; contact RevenueCat support)
>   - GitHub bug-report Issues — visible publicly at https://github.com/b150005/skeinly/issues (filter by the title or content you remember)
>   - Sentry crash data (if you opted in) — anonymous install UUID `<sentry_uuid_if_known>`, contact Sentry support
>   - PostHog analytics events (if you opted in) — anonymous distinct_id `<posthog_id_if_known>`, contact PostHog support
>
> Reference: https://b150005.github.io/skeinly/privacy-policy/ §"Your Rights"
>
> If you spot anything missing or unexpected, reply and we'll follow up.

JA template:
> Skeinly のデータエクスポートをお送りします (<date> ご請求分)。
>
> 添付:
>   - `skeinly-export-<short-uid>.csv` — アカウント、プロフィール、パターン、プロジェクト、進捗、セグメント、編み図、バリエーション、共有、コメント、提案、アクティビティ、デバイストークン、サブスクリプション、シンボルパック状態、フィードバックの全行
>   - アップロード済みアバター画像 (あれば)
>
> 含まれないもの (Skeinly が直接保有していないため、各サービスにご請求ください):
>   - Apple App Store / Google Play の IAP 取引履歴 — Apple / Google のデータアクセスツールから
>   - RevenueCat のサブスクリプション分析データ — RevenueCat app_user_id は `<uid>`、RevenueCat サポートまで
>   - GitHub のバグレポート Issue — https://github.com/b150005/skeinly/issues で公開済 (タイトル / 内容で検索)
>   - Sentry のクラッシュデータ (オプトインの場合) — 匿名 install UUID は `<sentry_uuid_if_known>`、Sentry サポートまで
>   - PostHog の分析イベント (オプトインの場合) — 匿名 distinct_id は `<posthog_id_if_known>`、PostHog サポートまで
>
> 参考: https://b150005.github.io/skeinly/ja/privacy-policy/ §「あなたの権利」
>
> ご不明点があればこのメールに返信してください。

### 10. Log the closure

Update the operator's notebook entry with:
- Response sent timestamp
- Attachment count (CSV + N avatar files)
- Any follow-up the requester sent

## Scope deferrals

This SOP is the **alpha-scope** A20 closure. Pre-Phase-40 GA, A20 will be upgraded to **Option B** — an in-app "Export My Data" button in Settings that calls a new Edge Function `export-my-data` (with `verify_jwt = true`) running the equivalent server-side queries scoped to the caller's UID and returning a downloadable JSON bundle. The Edge Function will reuse the table enumeration in step 4 above as its query body. The operational SOP will remain as a fallback for users who cannot complete the in-app flow.

The Option B upgrade is tracked in `pre-alpha-checklist.md` under "Pre-Phase-40 polish" + CLAUDE.md `### Planned — pre-Phase-40 polish`.

## Cross-reference

- [pre-alpha-checklist.md §32](pre-alpha-checklist.md) — A20 closure record
- [Privacy Policy "Your Rights"](https://b150005.github.io/skeinly/privacy-policy/#your-rights) — what the policy promises
- [ADR-005](../adr/005-account-deletion.md) — adjacent right (account deletion) flow

## Update history

| Date | Change | By |
|---|---|---|
| 2026-05-12 | Initial SOP — pre-alpha audit item A20 (Option A alpha-scope closure; Option B scheduled pre-Phase-40) | b150005 |
