# SOP — GDPR / CCPA データエクスポートリクエスト

> EN: [docs/en/ops/data-export-sop.md](../../en/ops/data-export-sop.md)

ユーザーのデータポータビリティ請求 (GDPR Article 20 / CCPA right to know) の対応方法。Alpha 期間はオペレーター駆動、in-app 「データをエクスポート」ボタン (A20 Option B) は Phase 40 GA 前に着手予定。

## SLA

GDPR Article 12(3): 受領から**1 ヶ月** (30 暦日) 以内に応答。複雑 / 多数のリクエストでは追加 2 ヶ月延長可能だが、30 日初回応答が目標。CCPA: 45 日。

Skeinly alpha 期の運用目標: **7 暦日以内に応答**。短い window で SOP を rehearsed に保ち、30 日境界での SLA bunching を回避する。

## 受領 → 完了 workflow

### 1. 受領

ユーザーが `skeinly.app@gmail.com` (Privacy Policy + ToS DMCA agent ブロックで指定された support address) に件名で `Data Export Request` または `データエクスポート` を含めて送信 (ユーザーが自由件名で送ってきた場合はオペレーターが分類)。

オペレーターが private notebook に記録:
- 受領 timestamp (yyyy-mm-dd hh:mm JST)
- 請求者 email (From アドレス)
- 請求者 Supabase UID (step 2 で本人確認後に派生)
- 目標応答日 (受領 + 7 日)

### 2. 本人確認

請求者に受領確認返信 **+** Skeinly アカウントに登録されているメールアドレスからの再送依頼。初回が account email から来ていれば暗黙的に成立。

確認テンプレ (JA):
> ご連絡ありがとうございます。データエクスポートのご請求を受け付けました。本人確認のため、Skeinly アカウントに登録されているメールアドレスから返信をお願いします。本人確認後、7 暦日以内にデータエクスポートをお送りします。

EN:
> Hi — we've received your data export request. To confirm your identity, please reply from the email address associated with your Skeinly account. We'll respond with your data export within 7 calendar days of identity confirmation.

別アドレスから返信があった場合はリクエストを拒否し account email からの再送を依頼 (GDPR Article 12(6) で正当化)。未認証チャネルから処理してはいけない。

### 3. ユーザーの Supabase UID を引く

Supabase Dashboard → SQL Editor:

```sql
SELECT id, email, created_at, last_sign_in_at
FROM auth.users
WHERE lower(email) = lower('<requester_email>');
```

0 行: 該当アカウント無しの旨を返信。終了。

1 行: `id` (UUID) をコピー。これが step 4 で使う `<uid>`。

複数行: 発生しないはず (`auth.users.email` は Supabase で unique-indexed)。エスカレーション。

### 4. エクスポート bundle を生成

下の SQL を Supabase Dashboard → SQL Editor で UID 埋め込みで実行。1 行 1 JSON で出力すると consume しやすい。

```sql
-- <uid> を実 UUID にインライン置換 (Dashboard SQL Editor は SELECT-only
-- session では :variable parameter binding 未対応)

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

Dashboard で「Download CSV」をクリック。`table` 列 + `data` 列 (行毎の JSON blob) の単一 `.csv` ファイルが生成される。Portable な export 形式として acceptable。JSON 出力を明示要求するユーザーには JSON で再出力。

Storage avatar object も列挙:

```sql
SELECT name, bucket_id, created_at, updated_at, last_accessed_at, metadata
FROM storage.objects
WHERE bucket_id = 'avatars'
  AND (storage.foldername(name))[1] = '<uid>';
```

各行をダッシュボード Storage → avatars browser からダウンロード。CSV と bundle 配信。

### 5. auth.users メタデータ

`auth.users` には email、created_at、サインイン timestamp、`raw_user_meta_data` JSONB (full_name 等) がある。Bundle 内で `account` 別 JSON オブジェクトとして関連 field を含める:

```sql
SELECT id, email, created_at, last_sign_in_at, raw_user_meta_data
FROM auth.users
WHERE id = '<uid>';
```

オペレーターが行 field を bundle に手動コピー。

### 6. バグレポートデータ (GitHub)

In-app 「Send Feedback」ボタンで送信されたバグレポートは `Skeinly Feedback` GitHub App が `b150005/skeinly` 上の GitHub Issue として記録。公開済、ユーザーの PostHog `distinct_id` (匿名 per-install UUID) と紐付け (Supabase UID ではない)。Bundle には (local app の PostHog state から既知なら) ユーザーの PostHog distinct_id を含め、不明なら「unknown — server-side lookup 容易でない」と注記、GitHub issue 検索でユーザー自身が思い出せる Issue を引けるよう pointer 添付。

### 7. RevenueCat / Apple / Google IAP

サブスクリプション取引データは部分的に RevenueCat、部分的に Apple/Google にある。ユーザーの RevenueCat `app_user_id` (= Supabase UID) を提示し、RevenueCat サポートに直接請求してもらう。Apple/Google IAP 履歴は Apple/Google のデータアクセスフローで請求可能、Skeinly のコントロール外。

### 8. Sentry + PostHog (オプトイン)

ユーザーが Sentry / PostHog に同意済 (Phase 39.4 W3 consent UI) なら、各々の匿名 install UUID を提示。Sentry / PostHog にはそれぞれ独自のユーザーデータアクセスフローがある、UUID 提示で self-serve 可能に。

### 9. 応答メール作成

返信内容:

- CSV (+ あればアバター画像ファイル) を添付
- 含まれるものの平易な日本語サマリー
- 含まれないもの (step 6–8) の注記
- Privacy Policy データポータビリティ section への pointer

JA テンプレ:
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

EN テンプレ:
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

### 10. クローズ記録

オペレーター notebook を更新:
- 応答送信 timestamp
- 添付数 (CSV + N アバターファイル)
- 請求者からのフォローアップがあれば

## Scope deferrals

この SOP は **alpha-scope** の A20 closure (Option A — オペレーター駆動) でした。**Option B は 2026-05-16 に着手完了**: Settings → Privacy → 「データをエクスポート」ボタンが `export-my-data` Edge Function (`verify_jwt = true`) を呼び、JWT 由来の caller id で scope した同等の server-side query (step 4 の table 列挙が query body) を実行、bundle を OS 共有シートに返す。**この SOP は引き続き fallback として残す** — in-app フローを完了できないユーザー (アプリ未利用 / アクセシビリティ要件 / アカウント削除前の請求 等) 向け。撤去しないこと。

in-app と SOP の差分 (オペレーターが把握すべき点):
- in-app エクスポートはアバターのオブジェクト **メタデータ** のみ (名前 / タイムスタンプ / サイズ) を含み、画像バイト列は含まない。バイナリが必要なユーザーは本 SOP step 4 の Storage ダウンロード、またはプロフィールから再取得。
- in-app の `notes` セクションは step 6–8 の「Skeinly 非保有」ソース (GitHub Issues / RevenueCat-Apple-Google / Sentry-PostHog) を静的に案内し、エクスポートが暗黙に不完全にならないようにしている。
- Edge Function は **GA かそれ以前にオペレーターがデプロイ** — [`supabase/functions/export-my-data/README.md`](../../../supabase/functions/export-my-data/README.md) §Deploy 参照 (`supabase functions deploy export-my-data`、新規 secret なし)。デプロイ前は本 SOP 経路のみ機能。

追跡: CLAUDE.md `### Pre-Phase-40 polish` (A20 Option B エントリ、2026-05-16 CLOSED)。

## クロスリファレンス

- [pre-alpha-checklist.md §32](../../en/ops/pre-alpha-checklist.md) — A20 closure 記録
- [Privacy Policy 「あなたの権利」](https://b150005.github.io/skeinly/ja/privacy-policy/#your-rights) — Policy の約束内容
- [ADR-005](../../en/adr/005-account-deletion.md) — 隣接権 (アカウント削除) フロー

## 更新履歴

| 日付 | 変更 | 実施者 |
|---|---|---|
| 2026-05-12 | 初版 SOP — pre-alpha audit 項目 A20 (Option A alpha-scope closure; Option B は Phase 40 GA 前) | b150005 |
| 2026-05-16 | Option B 着手完了 (in-app `export-my-data` Edge Function + Settings → Privacy → データをエクスポート、Compose + SwiftUI)。SOP は fallback として保持。 | b150005 |
