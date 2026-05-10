# ADR-017 — Phase 24: プッシュ通知

> **ステータス**: Proposed (2026-05-09)
> **フェーズ**: 24 (Phase 40 GA より前 / Phase 39 closed-beta テスター招待の HARD-GATE)
> **関連**: ADR-013 (コラボレーション基盤、Phase 24 が surface する activity event の発生源)、ADR-014 (PR ワークフロー、Phase 24 MVP の主要イベントソース)、ADR-015 (Phase 39 ベータバグレポート、Phase 24 が再利用する consent + opt-in パターンを確立)

英語版 (canonical): [../../en/adr/017-phase-24-push-notifications.md](../../en/adr/017-phase-24-push-notifications.md)

## 1. 背景

Phase 38 で完全な PR ワークフロー (open / list / detail / comment / close / merge / conflict resolution) が出荷された。Phase 36 + 37 で fork + コラボレーション履歴が出荷された。Phase 39 closed beta は、これらコラボレーション surface が maintainer 以外の実テスターによって exercise される最初の機会となる。

**現状コラボループはプロセス間で sail**: ユーザが他人の pattern を fork した PR を開いても、upstream owner は (a) Skeinly app を開く、(b) ProjectList → overflow → "Pull requests" → Incoming に navigate、(c) unread badge を確認 (Phase 38.2 で明示的に保留) しない限り知る術がない。closed beta 5–10 名の異なるタイムゾーンで、PR への中央値 response time が 24 時間を超える可能性が高く、起票テスターが議論コンテキストを失い disengagement する。

**2026-05-09 user 方針シフト**: 以前は Phase 24 が CLAUDE.md "Post-v1.0" に Phase 21 (macOS target) と並んで登録されていた。User が Phase 24 を Phase 39 closed-beta テスター招待**前**に出荷するよう明示的に reprioritize。理由 (user 方針): closed beta はコラボ機能 end-to-end usability の実証の場であり、push なしでは「usable」が「maintainer + 1 名のテスターが out-of-band channel で手動連携可能」に retreat してしまい、機能 surface の意味のある test にならない。

**ベンダー柱は準備済**: APNs `.p8` (vendor-setup A0a-2 で生成、`APPLE_APNS_KEY_P8` + `APPLE_APNS_KEY_ID` として Supabase Edge Function secret に登録済) + Firebase Service Account JSON (`FIREBASE_SERVICE_ACCOUNT_JSON` 登録済) + iOS Bundle ID Push Notifications capability 有効化済 + Android Firebase project FCM SDK SHA-1 登録済。

**F2 として未着手**:
- Edge Function `notify-on-write` 本体 (release-secrets.md に複数箇所で言及されているが未実装)
- `device_tokens` テーブル + RLS
- クライアント device token 登録 (KMP `expect/actual` で iOS APNs registration + Android FCM token retrieval)
- 通知 permission UX
- Deep link routing (push tap → 該当画面)
- iOS notification handler (`UNUserNotificationCenterDelegate`) + Android `FirebaseMessagingService`
- Trigger source (Postgres trigger + pg_net? Database Webhook? Edge Function chain?)
- Event scope (どのコラボイベントが OS-level interruption に値するか)
- Localization model (server pre-render? client render?)
- Privacy policy 更新

## 2. 決定事項 (高レベル)

1. **2-stack push transport**: iOS = APNs 直接 (`.p8` + JWT signing in Edge Function); Android = FCM HTTP v1 (Firebase Admin SDK SA → OAuth 2.0 access token → FCM v1 endpoint)。Firebase iOS Push SDK は採用しない (Apple がネイティブで提供している機能のために Firebase iOS dependency を増やすコストを払わない)。
2. **Trigger source = Supabase Database Webhooks** (Postgres triggers + `pg_net` ではない)。Supabase 管理機能で auto-signed、`pg_net` extension dependency 不要、Dashboard UI で wiring 確認可能。
3. **Event source = `notify-on-write` Edge Function 一本** が 3 テーブル (`pull_requests` INSERT/UPDATE、`pull_request_comments` INSERT) からの Database Webhook を受信。各 row を notification template + recipient set + dispatch にマッピング。
4. **Phase 24 MVP イベント (3 種)**: (a) PR opened on owned pattern (target owner が push 受信)、(b) PR comment added (PR participant set MINUS comment author が受信)、(c) PR merged/closed by other party (相手側が受信)。これらが OS-level interruption に値する明確なコラボ moment。`activities` テーブルの他の event 種 (chart edits, project updates, fork events 等) は push noise で engagement が下がるため意図的に除外。
5. **`device_tokens` テーブル**: `(user_id, platform, token)` UNIQUE 複合キー、own-row SELECT/INSERT/UPDATE/DELETE RLS。Service-role bypass は `notify-on-write` のみ。Token rotation はクライアント upsert、invalid token cleanup は APNs/FCM error code 経路で Edge Function が DELETE。
6. **Permission UX = deferred prompt パターン**: app launch / onboarding 時には permission を求めない。最初の PR 関連 action 時に「コラボレーターからの応答時に通知します」と説明する pre-permission dialog を出し、ユーザが「Enable」を tap した後に OS prompt 発火。iOS HIG + Android Material Design pre-prompt 定石。
7. **Localization = server-side、device_tokens.locale ベース**: BCP-47 tag (`en-US` / `ja-JP`) を device_tokens 列に保存、Edge Function が読み込んで in-function string table から EN/JA を選択。~6 unique notification body strings × 2 locales = 12 entries hard-coded。push notification は app killed 状態でも OS notification center に着地するためクライアント rendering hop 不可。
8. **Deep link routing**: notification `data` payload に `route` フィールド (例: `pull-request/<prId>`)。iOS `UNUserNotificationCenterDelegate` + Android `FirebaseMessagingService` がパースして既存 NavGraph / `AppRouter` 経由で該当画面に navigate。
9. **Phase 24 は in-app notification feed を追加しない**: 既存 `activities` テーブル + Phase 36.5 ActivityFeedScreen が in-app surface を担う。push は cross-medium amplifier であり duplicate UI ではない。

## 3. 主な実装上の判断

### 3.1 iOS APNs 直接 vs FCM 経由

Firebase iOS SDK は iOS push を FCM 経由に proxy 可能 (Edge Function 側のコードパスが unify される)。考慮 + 却下。

**APNs 直接の理由**:
- Apple stack は iOS ネイティブ — iOS app 側に追加 SDK dependency 不要 (Phase 24.2 では `UserNotifications.framework` + 既存 AppDelegate `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)` callback のみ)。
- APNs JWT signing は Edge Function (Deno) で小さなコードパス: `djwt` (JSR registry) で HMAC-ES256。
- Latency が低い (Edge Function → APNs の 1 hop、FCM 経由は 2 hop)。
- 認証情報ローテーション 1 回少ない (FCM 経由は依然 APNs `.p8` を Firebase Console にも登録要)。

**Trade-off 認識**: Edge Function に 2 経路 (APNs + FCM) になる。ただし両者は `notify-on-write` envelope (recipient lookup, locale resolution, body templating) を共有し HTTP 呼出だけが diverge。各 ~50 行で bounded、許容。

### 3.2 Android FCM HTTP v1 (legacy server key API ではない)

FCM legacy server key API (`https://fcm.googleapis.com/fcm/send` + `Authorization: key=<SERVER_KEY>`) は 2024 年 deprecation、Google が 2026 年初頭 sunset 予告 (Phase 40 timeline post)。HTTP v1 が forward-compatible な唯一の選択。

実装: Edge Function が `FIREBASE_SERVICE_ACCOUNT_JSON` (EF-3 既登録) を読み込み、SA private key で短命 JWT (audience = `https://oauth2.googleapis.com/token`、scope = `https://www.googleapis.com/auth/firebase.messaging`) を mint、OAuth 2.0 access token に交換、FCM v1 endpoint に POST。同じ `djwt` library が APNs + FCM SA JWT 両方を扱う。

### 3.3 Trigger source: Supabase Database Webhooks

3 候補を比較:
- **(A) Postgres triggers + pg_net**: extension surface 増 + trigger 管理が migration file に分散 + Dashboard UI から wiring 不可視。
- **(B) Database Webhooks (Supabase 管理機能)**: Supabase Dashboard で UI 設定 (Method / URL / Timeout / HTTP Headers / HTTP Parameters)、pg_net 不要、Dashboard で wiring 確認可能。
- **(C) Realtime subscription**: Edge Function は request/response モデルなので不適。除外。

**決定: (B) Database Webhooks**。理由:
- Infrastructure surface が小さい (pg_net extension 管理不要)。
- Supabase Dashboard UI で wiring 検証可能 — maintainer が SQL 実行せずに「webhook が実際に配線されているか」確認可。
- 認証は Dashboard の HTTP Headers セクションで `Authorization: Bearer <secret>` を設定、Edge Function 側で定数時間文字列比較 (`revenuecat-webhook` の Bearer パターンと同形)。
- Supabase docs 推奨パスに従う (将来 maintainer の path of least surprise)。

> **2026-05-09 amendment (1st)**: ADR 当初は (B) の説明として「auto-signed (HMAC-SHA256)」と記載していた。これは誤り — [Supabase 公式 Database Webhooks doc](https://supabase.com/docs/guides/database/webhooks) と Dashboard UI を確認した結果、Database Webhook は payload を自動署名しない (Dashboard の設定項目は Method / URL or Edge Function / Timeout / HTTP Headers / HTTP Parameters のみ、signing-secret 欄も signature ヘッダもない)。認証は上記の通り custom HTTP header 経由 (Bearer) に変更。決定 (A/B/C のうち B) は変更なし、auth 詳細のみ修正。詳細は §3.9 参照。

> **2026-05-09 amendment (2nd)**: 3 つの webhook を Dashboard で設定する際は **`Supabase Edge Functions`** type を選択 (NOT `HTTP Request`)。両 type とも裏側は同じ `supabase_functions.http_request()` trigger 関数 (prod 上で `pg_get_functiondef` で確認済) にマップされ、user supplied HTTP Headers を `net.http_post` にそのまま転送するだけで auth 自動注入ロジックは持たない。Edge Functions type のメリットは UX + 保守性: (a) 関数選択が dropdown — URL タイポリスク 0、関数 rename にも自動追随、(b) Dashboard 詳細画面で「→ Edge Function: notify-on-write」と表示され URL 文字列より可読性が高い。Edge Functions type を選ぶと Dashboard が `Authorization` ヘッダを `Bearer <project anon key>` で auto-populate するが、**maintainer はこれを `Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET>` に上書きする必要がある** — anon key は public (Skeinly mobile app に埋め込み済み) のため放置すると app の利用者なら誰でも Edge Function URL に payload を spoof 投稿でき、フェイク push 通知 (Phase 24.3+ で APNs/FCM credentials が active 化以降) を任意ユーザに送れる。Envoy gateway の `/functions/v1/` route は API-key validation を bypass する ([self-hosted Envoy gateway docs](https://supabase.com/docs/guides/self-hosting/self-hosted-functions)) ため gateway 段の auth にも頼れない。

**Trade-off 認識**:
- Database Webhook 設定は `supabase/migrations/` に versioned されない。Mitigation: 新規 `supabase/webhooks.md` に 3 webhook 設定 + Dashboard navigation 手順を記載、Phase 24.x 各 slice で webhook 追加/削除時に refresh。
- Bearer secret 値は各 webhook の config row 内に literal として保存される (Dashboard が secret-store 参照構文を露出しない)。Mitigation: Dashboard ACL がプロジェクトメンバーに scoped、ローテーション時は 3 webhook の header 行を更新 (+ `supabase secrets set`)。Stripe-style HMAC-of-body は TLS downgrade シナリオに対する防御を提供するが (Supabase HTTPS 強制下で発生確率は極小)、v1 トレードオフとして許容。

### 3.4 イベントスコープ (3 MVP)

| Event | Trigger | 受信者 | Body template |
|---|---|---|---|
| PR opened | `pull_requests` INSERT | `target_pattern_id` の owner | "{actor}さんが{pattern}にプルリクエストを開きました" |
| PR comment | `pull_request_comments` INSERT | PR participant set MINUS comment author | "{actor}さんが{pr_title}にコメントしました" |
| PR merged/closed | `pull_requests` UPDATE WHERE old.status='open' AND new.status IN ('merged','closed') | actor の相手側 (= author if actor==target_owner、else target_owner if actor==author) | "{actor}さんがあなたの{pattern}へのプルリクエストを{merged|closed}しました" |

**MVP 除外 (Phase 24+ で実 signal が surface したら追加)**: `activities` テーブル (chart edits, project updates, fork events) — push noise 過多で engagement 低下。`chart_revisions` INSERT — 同。`subscriptions` テーブル — ユーザは自身の購入/解約を通常知っている。`share` テーブル (Phase 4b) — low-priority workflow。

### 3.5 `device_tokens` テーブル

```sql
CREATE TABLE public.device_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    platform        TEXT NOT NULL CHECK (platform IN ('ios', 'android')),
    token           TEXT NOT NULL,
    locale          TEXT NOT NULL DEFAULT 'en-US' CHECK (locale IN ('en-US', 'ja-JP')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, platform, token)
);
-- + own-row SELECT/INSERT/UPDATE/DELETE RLS, indices
```

- `locale` CHECK closed enum (EN + JA)。3rd locale 拡張時は migration + Edge Function string table 両方の更新要。
- ON DELETE CASCADE on user_id: ADR-005 `delete_own_account` RPC 経由のアカウント削除で device_tokens cascade。
- Token rotation: クライアントが ON CONFLICT DO UPDATE 形式 upsert。古い token row は accumulate するが invalid 化、APNs/FCM error code 経路で Edge Function が DELETE (§3.10)。

### 3.6 Permission UX (deferred-prompt)

App launch / onboarding 時に permission 要求しない。最初の PR 関連 action 時 (PR list 表示時 with PRs / PR detail 表示時 / コメント投稿時) に in-app pre-permission explainer dialog を出す:

- Title: 「最新の動向を逃さない」
- Body: 「コラボレーターがプルリクエストを開いた、コメントした、変更をマージした時に通知します」
- Primary CTA: 「有効にする」 → OS permission prompt 発火
- Secondary CTA: 「あとで」 → in-app dismiss を記録、OS prompt は発火させない (将来 Settings から有効化する OS path を残す)

**Settings entry**: 新規 "通知" row を Settings の Beta section (Phase 39.4 で diagnostic data toggle が追加されたセクション) に追加。OS permission state を読んで「有効」「無効 (設定で変更)」表示、tap で OS settings deep-link (`UIApplication.openSettingsURLString` / Android `Settings.ACTION_APP_NOTIFICATION_SETTINGS`)。

`NotificationPermissionPrompter` service (KMP shared) が「trigger X で既に聞いたか」状態を `Preferences` で永続化。

### 3.7 Localization

2 候補:
- **(A) Client-renders**: server が `{ type, params }` push、クライアントが locale 解決 + render。app killed 時は OS Notification Service Extension (iOS) / FirebaseMessagingService (Android) がレンダー必要。
- **(B) Server-renders**: server が `device_tokens.locale` 読み in-Edge-Function table から EN/JA 選択 + parameter format + pre-rendered body 送信。

**決定: (B)**。理由:
- App killed 時でも OS notification center に着地。Notification Service Extension は別 target + bundle config で複雑度増、Android FirebaseMessagingService はプロセス生存依存 (常に成立しない)。
- Locale set が小さい (EN + JA) + unique notification template count 小 (~6 strings) のため、12 entries の Edge Function ハードコードは bounded + grep-friendly。
- Locale フィールドが将来 timezone-aware send time / tester segmentation 等の personalization 拡張点となる (client-side 経路では難しい)。

**Trade-off 認識**: notification body strings が 2 場所 (in-app は composeResources/strings.xml + iOS xcstrings、push は notify-on-write/index.ts) に分かれる。EN/JA 自動同期検証なし。Mitigation: 純粋な Deno test `notify-on-write/strings.test.ts` で `Object.keys(EN) == Object.keys(JA)` を assert。

**初期 6 templates**: `pr_opened` / `pr_commented` / `pr_merged_to_author` / `pr_closed_to_author` / `pr_closed_to_owner` / `actor_someone` (display_name null 時の fallback)。

Locale fallback: 不明 locale → `en-US`。template key 不明 → log warning + `pr_opened` placeholder fallback (silent drop よりは可視化)。

### 3.8 Deep link routing

各 push に `data` payload + visible `notification` body を同梱。`data.route` フィールドにルート文字列 (例: `pull-request/<pr_uuid>`)。

- **iOS**: `UNUserNotificationCenterDelegate.userNotificationCenter(_:didReceive:withCompletionHandler:)` で `userInfo["data"]` パース、`Notification.Name("openPushRoute")` ポスト。`AppRootView` SwiftUI body が `.onReceive(...)` で subscribe、`path.append(Route.pullRequestDetail(prId:))` 実行 (Phase 39.5 shake-to-bug-report と同パターン)。
- **Android**: `FirebaseMessagingService.onMessageReceived(RemoteMessage)` で `remoteMessage.data["route"]` パース、`PendingIntent` carrying intent extra で `NotificationCompat.Builder` 構築、tap 時に MainActivity が `onNewIntent` で extra 読み + `navController.navigate(...)` 実行。

**Cold-start 経路**: app killed 時、両プラットフォームとも launch options / intent extras 経由で payload 着地。`LaunchedEffect` + NavGraph mount 後の one-shot delay で `path.append`。

### 3.9 Edge Function `notify-on-write` シェイプ

Database Webhook payload `{ type, table, record, old_record? }` を受信、`Authorization: Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET の値>` ヘッダを定数時間文字列比較で verify、table+type で branch、recipient set 計算、各 recipient × device_token 行に対して APNs (ios) / FCM (android) dispatch。410/404 (BadDeviceToken / UNREGISTERED) で device_tokens DELETE、その他失敗は log + Sentry breadcrumb。Webhook 自体には 200 を unconditional 返却 (push 配信失敗は webhook 失敗ではない)。

> **2026-05-09 amendment**: ADR 当初は HMAC-SHA256 of body + `x-supabase-webhook-signature` ヘッダ検証として設計していた。[Supabase 公式 Database Webhooks doc](https://supabase.com/docs/guides/database/webhooks) と Dashboard UI を確認した結果、**Supabase Database Webhook は payload を自動署名しない** ことが判明 (Dashboard が露出する設定項目は Method / URL or Edge Function / Timeout / HTTP Headers / HTTP Parameters のみで、signing-secret 欄も signature ヘッダも存在しない)。認証は Dashboard の HTTP Headers セクションで `Authorization: Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET>` をペアごとに設定する形に変更、`revenuecat-webhook` の Bearer パターンと同形に揃えた。secret 名 `SKEINLY_DATABASE_WEBHOOK_SECRET` は変更なし。Edge Function 側は HMAC 計算なしで定数時間文字列比較のみ。`SKEINLY_` プレフィックスは load-bearing — Supabase が `SUPABASE_*` をプラットフォーム予約しているため ([Edge Function limits doc](https://supabase.com/docs/guides/functions/limits#secrets))。

> **Webhook type 選択**: Dashboard では 2 type 提供 — `HTTP Request` (URL 手動) と `Supabase Edge Functions` (function dropdown)。`Supabase Edge Functions` を選ぶ (URL タイポリスク 0、関数 rename 耐性、Dashboard 上の可読性向上)。両 type とも裏側は同じ `supabase_functions.http_request()` trigger にマップされ HTTP Headers をそのまま転送するため、Edge Function 側コードは無関係に同形。Edge Functions type は `Authorization` を anon key で auto-populate するが、これを `Bearer <SKEINLY_DATABASE_WEBHOOK_SECRET>` に **上書きが必要** — anon key は public (mobile app に埋込み済) のため放置すると app 利用者が Edge Function URL に直接 payload を spoof 投稿してフェイク push を任意ユーザに送れる。Envoy gateway の `/functions/v1/` route は API-key validation を bypass するため gateway 段の auth にも頼れない ([self-hosted Envoy gateway docs](https://supabase.com/docs/guides/self-hosting/self-hosted-functions))。

Rate-limit: APNs ~9000 req/sec/team、FCM v1 ~600 req/min/project — Phase 39 closed-beta scale (5–10 testers × 数 PR event/day) に対し過剰、v1 では rate-limiter 不要。

### 3.10 送信失敗時の token cleanup

- **APNs**: HTTP 410 + `reason: "BadDeviceToken"` または `"Unregistered"` → Edge Function が `DELETE FROM device_tokens WHERE token = $1` 実行。
- **FCM v1**: HTTP 404 + `error.status: "NOT_FOUND"` + `details[].errorCode: "UNREGISTERED"` → 同。

その他 transient error (5xx, timeout, throttling) → log + 次回 webhook fire で retry 機会あり (recipient 同じなら)。明示的 retry queue なし。

### 3.11 Privacy + consent

push notification は opt-in surface — ユーザが OS permission 明示 grant しない限り送信不可。

**Privacy policy update (Phase 24.6)**: `docs/public/privacy-policy/index.html` (EN) + JA mirror に新「Push Notifications」subsection。開示内容: device token (Apple/Google が発行する匿名識別子、人物に直接マッピング不可)、locale (BCP-47 tag、localized notification body rendering 用)、notification preferences (Phase 24+ で granular toggles が landed したら)。Notification body 内容に他ユーザの display name (例: "Alice opened a pull request") が含まれる — これは受信者の lock screen に出る他 Skeinly ユーザに「ついての」データ。Revocation: OS-level (Settings → Notifications → Skeinly → off)。アカウント削除 (ADR-005) で device_tokens cascade-delete via FK。

**Consent モデル**: OS-level permission grant が consent そのもの。in-app analytics-style opt-in toggle 別途無し (OS permission が meaningful consent moment 既に cover)。pre-permission explainer (§3.6) で informed-consent context 提供。

## 4. サブスライス計画

| Slice | 内容 | code 規模 |
|---|---|---|
| 24.0 | このADR + CLAUDE.md promotion | doc only |
| 24.1 | データスパイン: `device_tokens` 移行 + `notify-on-write` shell + Database Webhook config doc + EF-6 secret 登録 doc | 1 migration + Edge Function shell + ~15 Deno tests + 0 commonTest + 0 i18n |
| 24.2 | クライアント device token registration + permission UX (KMP `expect/actual` `PushTokenRegistrar` + `NotificationPermissionPrompter` + pre-permission Composable + Settings 行 + OS settings deep-link) | ~25 commonTest + ~12 i18n keys + iOS/Android native code |
| 24.3 | PR comment 通知 end-to-end (Edge Function 実 APNs + FCM 呼出) | ~20 Deno tests + 0 commonTest + 0 i18n |
| 24.4 | PR-opened + merged + closed イベント | ~10 Deno tests |
| 24.5 | Deep link routing (iOS/Android native + cold-start path) | iOS/Android native code |
| 24.6 | Privacy policy update + closed-beta validation | doc + manual test pass |

各サブスライス independently shippable。24.4 を slip しても 24.3 のコメント push は機能、24.3 を slip しても Phase 24 scaffold は完成 (alpha-tester-only として shippable)、24.5 を slip しても push は出るが tap 反応なし (degraded but not regressive)。

**Phase 39 closed-beta テスター招待 HARD-GATE は 24.6 完了時に satisfy**。

## 5. Open questions

- **Q1 APNs sandbox vs production**: 同じ `.p8` でも別 endpoint (`https://api.sandbox.push.apple.com` vs `https://api.push.apple.com`)。Phase 24.3 実装時に解決 — `device_tokens.environment` 列で client が宣言する Phase 39 `subscriptions.environment` 先例に lean。
- **Q2 Android 12 以下の permission**: API < 33 はインストール時許可 (runtime permission 不要)。Phase 24.2 解決: pre-permission explainer は出すが OS prompt 分岐は skip (API がない)。
- **Q3 thread-identifier grouping**: APNs `thread-identifier` / FCM `tag` で OS-side coalescence 可能。Phase 24+ で defer (closed-beta scale で over-engineering)。
- **Q4 EN/JA 以外の system locale**: `fr-FR` 等のユーザは `en-US` を受信。Phase 40+ で locale が増えれば string table が線形増加。Phase 24 で action 不要。
- **Q5 Edge Function cold start latency**: ~100–500ms。コラボ push は delay 許容 >> 秒、Mitigation 不要。

## 6. 検討して却下した代替案

- **A1 closed beta で push 出荷しない**: 2026-05-09 user 方針 shift で却下、closed beta はコラボループの validation moment。
- **A2 FCM 経由 iOS push**: §3.1 で却下、Apple ネイティブ機能のために Firebase iOS SDK dep を増やすコスト過剰。
- **A3 Postgres triggers + pg_net**: §3.3 で却下、extension surface 増 + Dashboard 不可視。
- **A4 in-app feed のみ (push なし)**: ActivityFeedScreen は既存。「コラボレーターが応答した時に時差越えて interrupt」use case に対応せず。
- **A5 Email digest**: scope 外、real signal surface まで defer。
- **A6 Topic-based subscriptions**: scale 時 (>1000 user) に有用、closed beta では over-engineering。
- **A7 Web Push**: Skeinly はモバイル専用、scope 外。

## 7. Consequences

**Positive**: closed beta テスターがリアルタイムコラボ awareness を獲得し PR turnaround time 大幅短縮。既存 vendor secret (EF-1/2/3) が本来用途で activate。Edge Function pattern 4 つ目に拡張 (`revenuecat-webhook`、`request-pack-download`、`notify-on-write`、削除済みの `verify-receipt`)。Database Webhooks pattern が introduce — 将来「table change → server-side action」flow で再利用可能。Privacy policy 拡張は 1 回のコスト、後続 push-using feature が disclosure 継承。

**Negative**: 2-stack push transport (APNs + FCM) で 2 codepath 維持。Notification body strings が 2 場所 (in-app vs push) に分かれ、parity 検証は Deno test 任せ (§3.7)。pre-permission dialog が natural flow と競合する modal を追加、prompt timing PRD-style A/B test は post-MVP。Database Webhook config が migration に versioned されない、`supabase/webhooks.md` 維持に依存。新規 Edge Function secret (`SKEINLY_DATABASE_WEBHOOK_SECRET`) 1 個。新規 Postgres table (`device_tokens`) 1 個 + 4 RLS policy + 1 index。iOS Notification Service Extension 出荷せず (Phase 24+ で rich content の real demand が surface したら)。Token cleanup on uninstall は reactive (次回 push 試行失敗 → DELETE)、proactive ではない — 数週〜数ヶ月で stale row 自然発生、bounded。

## 8. References

EN canonical の §10 References を参照。

---

## Amendment — 2026-05-10 (用語監査、決定後)

本 ADR 本文で `pull_requests` / `pull_request_comments` テーブル名 +
status enum 値 `'merged'` を参照している箇所は、設計時点での名称。
2026-05-10 の用語監査 (`audits/terminology-audit-2026-05-10.md`) +
Migration 027 適用後、以下に置換済:
- テーブル名: `suggestions` / `suggestion_comments`
- status enum 値: `'applied'` (旧 `'merged'`)
- Edge Function `notify-on-write` は新テーブル名で dispatch
- Database Webhook の source-table 設定も新テーブル名 (Postgres OID
  経由で自動追従)
- `webhooks.md` も新名に更新済

push 通知テンプレート (EN/JA) も ADR-014 amendment + 監査 doc 決定に
従い書き換え (「プルリクエストを開きました」→「提案を送りました」、
「マージしました」→「反映しました」 等)。

Edge Function 内部の TypeScript 型名 (`PullRequestRow` など) は内部
アーティファクトとして旧表記を保持。
