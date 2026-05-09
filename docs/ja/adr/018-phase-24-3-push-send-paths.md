# ADR-018 — Phase 24.3: APNs + FCM 送信経路の実装

> **ステータス**: Proposed (2026-05-09)
> **フェーズ**: 24.3 (Phase 24 のサブスライス、ADR-017 に続く)
> **関連**: ADR-017 (Phase 24 push notifications — 本 ADR が実装するアーキテクチャを規定)、ADR-014 (PR ワークフロー — 主たるイベント源)、ADR-013 (コラボレーション基盤)。
> **トラッキング**: ADR-017 §7 Q1 (APNs sandbox vs production 選択) に加え、Phase 24.3 着手時点で表面化した 4 件の実装レベルの設計判断を resolve する。Phase 24.1 の "log-only `notify-on-write` shell" と Phase 24.3 の最初の end-to-end push 配信との実装ギャップを閉じる。

英語版 (canonical): [../../en/adr/018-phase-24-3-push-send-paths.md](../../en/adr/018-phase-24-3-push-send-paths.md)

## 1. 背景

ADR-017 で Phase 24 のアーキテクチャは決定済:

- 二系統トランスポート (iOS は APNs direct、Android は FCM HTTP v1)
- トリガー源は Supabase Database Webhook → `notify-on-write` Edge Function
- Recipient 計算は `mapping.ts` の純関数 (Phase 24.1 で出荷、Deno test 29 で網羅)
- APNs 410 / FCM 404 で token 削除
- iOS は production-only entitlement (Phase 24.2e の `iosApp.entitlements` で `aps-environment = production`)

ADR-017 が意図的に空欄にしていた点: Edge Function が JWT を実際にどう構築するか、OAuth token をどう管理するか、recipient 毎の HTTP 呼び出しをどう sequence するか、どの error code が token 削除を warrant するか、APNs の sandbox / production endpoint を実装でどう選ぶか。アーキテクチャ上のコミットは変わらないが、本 ADR で実装契約を明示し、Phase 24.3 が明確な blueprint で出荷でき、Phase 24.4+ がこれらの判断を再議論せずに済むようにする。

本 ADR が設計する具体的成果物は、ADR-017 §3.9 step 3 で言及した `dispatchPush(userId, templateKey, params)` の実装本体 (現在は `supabase/functions/notify-on-write/index.ts` lines 106–113 の `notify_on_write_skipped_send` ログ行)。

## 2. 決定 (高レベル)

1. **APNs JWT 署名**: `djwt` JSR (`@^3`) をインポートして ES256。crypto を手書きしない。
2. **FCM SA OAuth token のキャッシュ**: 5 分の安全マージン付きでインスタンス内メモ化。LRU 不要、module-scope `let` で十分。
3. **Recipient 毎の送信ループ**: 単純な `for (const dispatch of dispatches) { for (const token of resolveTokens(...)) { send(...) } }`。バッチ API は使わない。`Promise.allSettled` で内側ループの total wall-clock を bound。
4. **Token 削除のマッピング**: APNs `410 Unregistered` / `400 BadDeviceToken` / `400 DeviceTokenNotForTopic`、FCM `404 UNREGISTERED` / `403 SENDER_ID_MISMATCH` で DELETE。それ以外は log + continue (DELETE しない)。
5. **APNs environment**: Phase 24.3 closed beta は production-only。`device_tokens.environment` カラムは追加しない。Phase 24.4+ で local-debug push iteration が必要になれば revisit。

## 3. 決定 (詳細)

### 3.1 APNs JWT 署名 — `djwt` JSR

APNs HTTP/2 push は `:authorization` header に short-lived JWT を載せ、`.p8` 秘密鍵で ES256 (P-256 ECDSA + SHA-256) 署名する。Header `{ "alg": "ES256", "kid": <APNS_KEY_ID> }`、body `{ "iss": <APNS_TEAM_ID>, "iat": <unix_seconds> }`。Apple は約 1 時間の有効期限 + 約 20 分に 1 回の発行制限を課す。

検討した 2 つの実装パス:

**(A) `crypto.subtle` で手書き**: Deno 標準の WebCrypto を使えば約 80 行で書ける。`.p8` PEM を `crypto.subtle.importKey("pkcs8", ...)` で `CryptoKey` にして、header + payload を base64url、`crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, message)` で署名、WebCrypto の IEEE-P1363 r||s 出力を JWS-compact 形式 (APNs はこの形式を verbatim 要求 — DER エンコードでは NG) に変換、ドット結合で組み立て。実環境で見られる failure mode: IEEE-P1363 → DER 変換忘れ (一部 JWT ライブラリは DER 想定)、base64url のパディング誤り、署名 byte order 誤り。いずれも APNs が `403 InvalidProviderToken` を返すだけで有用な diagnostic がない silent mis-signing。

**(B) `djwt` JSR (`jsr:@djwt/djwt@^3`)**: Deno-native のサードパーティライブラリ。audited (Supabase 自身の sample でも採用)、ES256 + RS256 + HS256 を直接サポート、IEEE-P1363 → JWS-compact 変換も正しく処理 (JSR ソース `signature.ts:42-58` で確認)。1 行 import + 1 関数呼び出し (`create(header, payload, key)`)。

**決定: (B) djwt JSR**。理由:
- Phase 24.3 のバリューは push 配信そのものであり、crypto plumbing ではない。手書き ES256 は load-bearing でない複雑性。
- 誤署名 failure mode (`403 InvalidProviderToken` で diagnostic なし) は、まさに maintainer の push system 信頼を侵食するクラスのバグ。audited library の方が failure surface が遥かに小さい。
- 同じ `djwt` を FCM SA JWT 署名にも再利用 (§3.2)。1 つの dependency 行で両 stack を cover。
- `revenuecat-webhook` (Phase 39 prep) で既に JSR から HMAC-SHA256 を import しており、2 つ目の JSR import は既存先例と consistent。

`notify-on-write/index.ts` の dependency 行:

```typescript
import { create as createJwt, getNumericDate } from "jsr:@djwt/djwt@^3";
```

既存 `jsr:@supabase/supabase-js@2` と同じ SemVer 風の pin。`^3` で patch / minor は許容、major は明示的 bump 経由 — プロジェクトのサードパーティライブラリ運用と一致。

### 3.2 FCM SA OAuth token のキャッシュ

FCM HTTP v1 は SA JWT (RS256) から発行する Bearer access token を要求する。1 リクエスト毎のフロー:

1. SA 秘密鍵で JWT 署名 (audience `https://oauth2.googleapis.com/token`、scope `https://www.googleapis.com/auth/firebase.messaging`)。
2. `https://oauth2.googleapis.com/token` に `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=<jwt>` で POST。
3. `{ "access_token": "...", "expires_in": 3599 }` を受信。
4. `https://fcm.googleapis.com/v1/projects/<project-id>/messages:send` に `Authorization: Bearer <access_token>` で POST。

Steps 1–3 は cold call 1 回あたり ~50–150ms (JWT 署名 + Google への HTTPS RT)、step 4 が実 push (~30–80ms)。同じ Edge Function instance 内の複数 invocation で access token を cache すると自然に最適化できる。

**キャッシュ戦略の選択肢**:

**(A) キャッシュなし — push 毎に fresh token を取得**: 最もシンプル。ただし push 毎に 50–150ms のレイテンシ + 不要な OAuth fetch budget 消費。Google の OAuth レート制限は分間数千 fetch / SA — closed-beta scale より遥か上だが、レイテンシ税は実在。

**(B) 安全マージン付きインスタンス内メモ化**: module-scope `let cachedFcmToken: { value: string; expiresAt: number } | null = null;`。呼び出し時、null OR `expiresAt - now() < 5 * 60 * 1000` (5 分マージン) なら refresh、そうでなければ cached を返す。Edge Function instance ライフタイムは Supabase autoscaler に依存 (典型的に idle 数分後 recycle、負荷時はもっと長い) ので cache lifetime は暗黙、手動 eviction 不要。

**(C) 外部キャッシュ (KV / Redis)**: instance 跨ぎで共有。closed-beta スケールには over-engineered、infra surface 増。却下。

**決定: (B) 5 分の安全マージン付きインスタンス内メモ化**。

実装イメージ (約 15 行):

```typescript
let cachedFcmAccessToken: { value: string; expiresAt: number } | null = null;
const FCM_TOKEN_REFRESH_MARGIN_MS = 5 * 60 * 1000;

async function getFcmAccessToken(saJson: ServiceAccount): Promise<string> {
    const now = Date.now();
    if (cachedFcmAccessToken && cachedFcmAccessToken.expiresAt - now > FCM_TOKEN_REFRESH_MARGIN_MS) {
        return cachedFcmAccessToken.value;
    }
    const fresh = await fetchFcmAccessToken(saJson);
    cachedFcmAccessToken = {
        value: fresh.accessToken,
        expiresAt: now + (fresh.expiresInSeconds * 1000),
    };
    return fresh.accessToken;
}
```

性質:
- Cold-start パスは OAuth fetch を 1 回支払う (~50–150ms)。約 55 分以内の後続 invocation はキャッシュ再利用。
- 5 分マージンで「キャッシュヒット直後に FCM POST 中で expire」境界ケースを回避。Deno fetch の FCM タイムアウトは ~10 秒、5 分は十分余裕。
- キャッシュは instance per — 異なる Edge Function instance はそれぞれ独自に保持。closed-beta scale では cold-start frequency が低い (時間に 1〜2 回程度) ので問題なし。
- Mutex 不要 — Deno V8 は instance 毎 single-threaded なので cache read/write は atomic。

5 分マージンの数値はインライン文書化、本 ADR を読まなくても後の reader が rationale を見えるように。

### 3.3 Recipient 毎の送信ループ

ADR-017 §3.9 step 3 でループ shape はスケッチ済 (`device_tokens` を recipient で SELECT、`device_tokens.locale` で body render、send)。実装の問題は、1 つの dispatch が複数 token に fan-out する場合 (例: ユーザが iPhone と Android tablet 両方持っている、または iPhone 2 台) の HTTP call sequence。

**選択肢**:

**(A) `await` 毎の sequential `for` ループ**: 最もシンプル。total wall-clock = per-send latency の総和。3 token × 80ms = ~240ms。

**(B) recipient 内で `Promise.all` 並列**: recipient 内で並列化。total wall-clock = max(per-send latency)。同じ 3 token なら ~80ms。ただし `Promise.all` は最初の rejection で短絡 — APNs 503 が 1 つあると in-flight FCM の結果も捨てる。

**(C) recipient 内で `Promise.allSettled` 並列**: 並列化 + 個別 failure に関わらず全結果を集める。個別 settled 結果を独立に token cleanup 処理。~80ms wall-clock + fault-tolerant 集約。

**(D) keep-alive APNs connection で HTTP/2 multiplexing**: APNs HTTP/2 は明示的に multiplex 対応 (legacy binary protocol の "connection-per-token" を置き換える設計目標)。Deno fetch は明示的な connection control を expose しない、内部で multiplex するかは未確認。

**決定: (C) recipient 内 `Promise.allSettled`、recipient 跨ぎ sequential**。

理由:
- Recipient 内 (典型 1〜3 token) は並列化が wall-clock + ユーザ体感 push レイテンシで free win。
- `all` でなく `allSettled` — 1 token への transient APNs 5xx で他 token (ユーザの active な他デバイスかも) の配信を blocking してはならない。
- Recipient 跨ぎの sequential は closed-beta scale では問題なし (PR コメント fan-out は最大 2 = author + target owner − comment author、PR-opened/merged/closed は 1)。recipient 跨ぎ並列化は Edge Function timeout 予算を careful に管理する必要があり、このスケールでは benefit がない。
- 将来スケール (Phase 24+ で multi-collaborator thread が来たら): 内側ループは recipient 毎ロジックを既に isolate しているので、recipient 跨ぎ並列化は `for` → `Promise.allSettled([...].map(...))` の 1 行 swap で対応可能。

実装イメージ:

```typescript
for (const dispatch of dispatches) {
    const tokens = await resolveTokens(supabase, dispatch.recipientUserId);
    if (tokens.length === 0) continue;
    const results = await Promise.allSettled(
        tokens.map((tokenRow) => sendOneNotification(saJson, apnsKey, tokenRow, dispatch))
    );
    for (let i = 0; i < results.length; i++) {
        await processResult(supabase, tokens[i], results[i]);
    }
}
```

`processResult` は結果を読んで §3.4 の token cleanup を処理。`processResult` の sequential は意図的 — DB DELETE を発行するので N-way race は望まない。

### 3.4 Error code → token cleanup マッピング

ADR-017 §3.10 で 2 つの error code (APNs 410 BadDeviceToken/Unregistered、FCM 404 UNREGISTERED) を named。Phase 24.3 実装では full mapping が必要 — 認識しない code のせいで stale token が永遠に蓄積したり、transient outage で valid token を消したりしないように。

**APNs error codes** (Apple の [Sending Notification Requests to APNs](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns) より):

| HTTP | Reason | Action | 理由 |
|---|---|---|---|
| 200 | (success) | none | 配信完了 |
| 400 | `BadDeviceToken` | **DELETE** | このチームの token として無効、絶対成功しない |
| 400 | `DeviceTokenNotForTopic` | **DELETE** | 別 bundle id 用 token、使えない |
| 400 | `BadCollapseId` 他 | log + continue | Edge Function バグ / 設定ミス、token を罰しない |
| 403 | `InvalidProviderToken` 他 | log + alert | provider auth 故障、`.p8` / JWT を直す |
| 404 | `BadPath` 他 | log + continue | Edge Function URL バグ |
| 410 | `Unregistered` | **DELETE** | デバイス側で明示的 retired (uninstall, OS reset) |
| 413 | `PayloadTooLarge` | log + continue | Edge Function payload size バグ |
| 429 | `TooManyRequests` 他 | log + continue | rate limit、次 push で recover |
| 500 | `InternalServerError` | log + continue | APNs 内部、transient |
| 503 | `ServiceUnavailable` 他 | log + continue | APNs outage、transient |

**FCM v1 error codes** ([Firebase HTTP v1 reference](https://firebase.google.com/docs/cloud-messaging/send-message) より):

| HTTP | error.status / errorCode | Action | 理由 |
|---|---|---|---|
| 200 | (success) | none | 配信完了 |
| 400 | `INVALID_ARGUMENT` | log + continue | Edge Function payload バグ |
| 401 | `UNAUTHENTICATED` | OAuth refresh + 1 回 retry | SA token expired、refresh パス |
| 403 | `SENDER_ID_MISMATCH` | **DELETE** | 別 Firebase project 用 token、recover 不可 |
| 404 | `UNREGISTERED` | **DELETE** | token retired (uninstall, app data clear, OS reset) |
| 429 | `QUOTA_EXCEEDED` | log + continue | rate limit |
| 500 | `INTERNAL` | log + continue | FCM 内部、transient |
| 503 | `UNAVAILABLE` | log + continue | FCM outage、transient |
| 504 | `DEADLINE_EXCEEDED` | log + continue | network or FCM timeout、transient |

**実装契約**:

```typescript
type SendOutcome =
    | { kind: "success" }
    | { kind: "delete_token"; reason: string }
    | { kind: "transient_error"; reason: string }
    | { kind: "config_error"; reason: string };

function classifyApnsResponse(httpStatus: number, reason: string | null): SendOutcome { ... }
function classifyFcmResponse(httpStatus: number, errorCode: string | null): SendOutcome { ... }
```

各 `classify*` は上記 table を網羅。未知 reason 文字列 (将来 Apple が新 reason code を追加した場合) は `transient_error` に fall-through — fail-safe、未知 signal で token を絶対消さない。

`delete_token` arm は `DELETE FROM device_tokens WHERE token = $1` を実行 (token はマイグレーション 025 の `UNIQUE (user_id, platform, token)` により user_id 跨ぎでも unique なので token のみで削除、行の user_id は informational)。失敗 token あたり DB RT 1 回、bounded。

`config_error` arm は `console.error` レベルでログ — Supabase ログ検索で見えるように。Phase 24.3 は Sentry を wire しない (Edge Function ログは Supabase Dashboard で検索可能、Sentry SDK 追加は別件、実 triage volume が warrant したら将来 "Edge Function observability" slice で対応)。

Token 削除も operator visibility のために `console.log` でログ:

```typescript
console.log(JSON.stringify({
    event: "device_token_deleted",
    platform: tokenRow.platform,
    reason: outcome.reason,
    user_id_prefix: tokenRow.user_id.substring(0, 8),
}));
```

user_id prefix は `revenuecat-webhook` と同形 — triage 相互参照には足りるが、ログ閲覧でユーザを enumerate するには不十分。

### 3.5 APNs サーバ URL — Phase 24.3 は production のみ

ADR-017 §7 Q1 で 2 つの APNs ホストを named:
- `https://api.sandbox.push.apple.com` (development entitlement、debug build + development provisioning profile)
- `https://api.push.apple.com` (production entitlement、TestFlight + App Store + distribution provisioning profile)

同じ `.p8` がどちらでも動く。Edge Function は token 毎に 1 つ選ぶ必要がある。

**Phase 24.3 closed-beta コンテキスト**:
- iOS クライアントは TestFlight 配布 (Distribution Provisioning Profile + Phase 24.2e の `iosApp.entitlements` に `aps-environment = production`)。
- maintainer のローカル debug build は push を受け取らない (Phase-24.3 前の受容: ADR-017 §3.2 entitlement 決定により「TestFlight は prod APNs を使う、on-device debug push test は意図的に unsupported」)。
- Closed-beta テスター (5–10 名) は全員 TestFlight or App Store 経由でインストール — `device_tokens` の全 token は APNs-production-entitled binary が登録したもの。

これらの constraint 下、3 つの選択肢:

**(A) Production-only**: Edge Function に `api.push.apple.com` をハードコード。development-entitled binary 由来 token (closed-beta scale では存在しないはずだが、defense-in-depth) は `BadDeviceToken` を受けて §3.4 で削除 — 削除は無害 (その token はそもそも push を受けられない)。

**(B) prod を試して BadDeviceToken なら sandbox にフォールバック**: client misconfiguration への defensive。debug-build token 毎に余計な round trip 1 回。stale token 1 つにつき ~80ms 浪費するが実デバイスは絶対 miss しない。

**(C) `device_tokens.environment` カラム (Phase 39 `subscriptions.environment` と同型)**: client が upsert 時に environment を declare。マイグレーション + enum + CHECK + client 側分岐が必要。最もクリーンだが surface area 最大。

**決定: (A) Phase 24.3 は production-only**。

理由:
- Closed-beta スコープ: 全テスターが TestFlight 経由。`device_tokens` に正規の sandbox-registered token は無い。
- 唯一の failure mode は malformed token (誰かが development-entitlement build を sideload して upsert path に到達)。これは §3.4 の `BadDeviceToken → DELETE` arm に落ちる、無害な no-op (dev-entitled binary は push を受けられないので)。
- (C) のマイグレーション cost は実在 (column + CHECK + client wiring + tests)、closed-beta scale ではバリュー 0。
- Phase 24.4+ で local-debug push iteration が maintainer ergonomics として必要になれば revisit (例: Skeinly contributor が TestFlight roundtrip なしで push UX を iterate したい)。その時点で (C) が自然な pivot。

ハードコード URL は APNs client の冒頭付近、module 定数として配置:

```typescript
const APNS_HOST = "https://api.push.apple.com";
// Phase 24.3 closed beta: production-only path. ADR-018 §3.5 documents
// the rationale + the Phase 24.4+ pivot to a `device_tokens.environment`
// column if local-debug push iteration becomes a need.
```

### 3.6 テスト surface

ADR-017 §6 では「happy + token-invalid + 5xx-retry-tolerated パスで Deno test ~20」と budget。Phase 24.3 で適度に拡張:

- **JWT 署名** (3 tests): APNs ES256 が parseable な header/body/signature 三組を生成、FCM SA RS256 同様、両方 `verify` で round-trip (defense-in-depth — sign-then-verify テストは visual inspection で見落とす IEEE-P1363 vs DER エラーを catch)。
- **OAuth キャッシュ** (4 tests): cold call で fetch、margin 内 warm call は cache 返却、5 分以内なら refresh、process restart で cold-cache から refresh。
- **APNs response classifier** (8 tests): success / `BadDeviceToken` / `Unregistered` / `DeviceTokenNotForTopic` / `InvalidProviderToken` / `TooManyRequests` / 未知 4xx reason / 未知 5xx の各 1 ケース。
- **FCM response classifier** (6 tests): success / `UNREGISTERED` / `SENDER_ID_MISMATCH` / `UNAUTHENTICATED` / `INTERNAL` / 未知。
- **End-to-end dispatch** (5 tests): 1 recipient 1 token 成功、1 recipient 2 token で 1 個 DELETE-warranting failure (他 token 配信成功 + DB 削除 + 200 を Supabase に返却、を verify)、2 recipient fan-out、PR コメント dispatch 実 mapping payload、`device_tokens.locale` からの locale 解決。

合計: 新規 Deno test ~26。既存 mapping test 29 + revenuecat-webhook test 39 = 68 → Phase 24.3 後 ~94 Deno test。

**Test fakes**: `globalThis.fetch` を stub する HTTP fake (Phase 24.3 が初導入)。新規 `notify-on-write/_fakes.ts` (アンダースコア prefix で「test 専用、production 非対象」を表記) が以下を export:

```typescript
export interface FetchFake {
    setApnsResponse(token: string, response: { status: number; reason?: string }): void;
    setFcmResponse(token: string, response: { status: number; errorCode?: string }): void;
    setOAuthResponse(response: { status: number; body?: unknown }): void;
    install(): void;
    restore(): void;
}
```

各テストで fake を arrange → production code を dispatch path 経由で実行 → DB 呼び出し + HTTP response shape を assert。production code 自体は変更不要、`index.ts` に test 専用分岐は入れない。

`Deno.test.beforeEach` / `afterEach` で fake を tear down、cross-test 汚染を防ぐ。

### 3.7 Sentry / observability layer は入れない

ADR-017 §3.9 step 3.e は当初「他の failure → log + Sentry breadcrumb」と記載していた。Phase 24.3 は意図的に Sentry SDK を入れずに出荷する。理由:

- Supabase Dashboard ログ検索は JSON フィールドで search 可能 (`console.log(JSON.stringify({event: ...}))` パターンはコードベース先例 — `revenuecat-webhook/index.ts` および `notify-on-write/index.ts` Phase 24.1 shell)。
- Closed-beta scale (5–10 名 × 1 日数件 PR イベント) のログ volume は trivial、`supabase functions logs notify-on-write` の手動 triage で対応可能。
- Sentry の Deno SDK 追加は JSR import + DSN secret + invocation overhead を追加するが、その observability ゲインはまだ未測定。
- 構造化 `console.log` events (`device_token_deleted`、`notify_on_write_dispatched`) は既に grep 可能、Sentry の value-add は grouping + alerting だがこのスケールでは不要。

Phase 24+ で実 triage volume / alerting needs が surface したら revisit。それまでは構造化 `console.log` が observability layer。

## 4. プライバシー + セキュリティ要約

本 ADR は ADR-017 §5 のプライバシー / セキュリティコミットを変更しない。保持されるもの:

- Edge Function は token 値をログに出さない。`user_id_prefix` (先頭 8 文字) と `platform` のみ triage 用。
- Token 削除は server-side のみ、client 側 visible な副作用は「次回 push が残り token に向かう」だけ。
- APNs `.p8` (EF-1) と Firebase SA JSON (EF-3) は Edge Function secret のみに存在。
- `device_tokens` の RLS は不変、Edge Function は service-role key 使用 (Phase 24.1 shell で既に存在)。
- 新規 client-side surface 無し。

## 5. サブスライス計画

本 ADR は Phase 24.3 のみの実装契約を scope する。Phase 24.3 は 1 commit で出荷 (Phase 24.1 / 24.2e の「全 slice 1 commit + commonTest / Deno test delta」先例に従う)。

**Phase 24.3 スコープ (1 commit)**:
- `notify-on-write/index.ts` — `notify_on_write_skipped_send` ログ行を §3.3 + §3.4 に従う実 `dispatchPush` 呼び出しに置換。
- 新規 `notify-on-write/apns.ts` — JWT 署名 (§3.1) + APNs HTTP client + response classifier (§3.4)。
- 新規 `notify-on-write/fcm.ts` — SA OAuth キャッシュ (§3.2) + FCM HTTP v1 client + response classifier (§3.4)。
- 新規 `notify-on-write/_fakes.ts` — test 専用 HTTP fakes (§3.6)。
- テストファイル追加: `apns.test.ts`、`fcm.test.ts`、`dispatch.test.ts` (新規 ~26 tests)。
- `notify-on-write/README.md` — Phase 24.1 SHELL section を Phase 24.3 end-to-end behavior に置換、両 endpoint の smoke test レシピ文書化。

Phase 24.3 でマイグレーション無し (`device_tokens.environment` カラムは 24.4+ で必要なら追加)。

commonTest delta 無し (data-layer + Edge-Function-only)。i18n キー無し (push body は Edge Function の mapping.ts に既存)。

User-side アクション (autonomous 不可):
- 24.3 deploy 後、README の curl レシピで smoke test event 送信、TestFlight デバイスに push 着地を verify。
- 故意に malformed token を 1 個生成、1 push 試行後 `device_tokens` から削除されることを確認。

## 6. Phase 24.4+ に deferred な open questions

- **Q1 (sandbox vs prod) revisit**: local-debug push iteration が maintainer ergonomics needs として浮上したら、§3.5 option (C) `device_tokens.environment` に pivot。マイグレーション NNN + CHECK + `PushTokenRegistrar.ios.kt` で `environment = isDebugBuild ? "sandbox" : "production"` 宣言。
- **Q2 (Sentry instrumentation)**: 実 triage volume が emerge したら revisit。Phase 24.3 は構造化 `console.log` のみ。
- **Q3 (per-event throttling / coalescence)**: Phase 24.4+ — テスターが active な PR thread での comment-毎 通知 spam を不満に思うか次第。ADR-017 §4 で既に deferral として named。
- **Q4 (Notification Service Extension で iOS rich content)**: Phase 24.4+ で実プロダクト需要があれば。

## 7. 検討した代替案 (横断)

§3.1–§3.5 の決定毎の代替案に加え:

**A1. APNs + FCM をラップする高レベル「send push」ライブラリ使用**: 例 node-pushnotifications、apn-http2。却下 — (a) ほとんどが Node 用で Deno 非対応、Deno 動作するものは abandoned、(b) 抽象化が token cleanup のために explicit に読みたい response classification を隠す、(c) サードパーティ push library 追加で supply chain audit scope が `djwt` 単体より倍増。

**A2. push fan-out 専用 vendor (OneSignal、Pusher) 経由で送信**: 却下。HTTP code 数百行で済む機能のために vendor dependency 追加。Vendor lock-in。スコープ外。

**A3. APNs HTTP/3 (QUIC) に migrate**: APNs HTTP/3 は beta、まだ GA でない、Deno runtime fetch でもまだサポートされていない。両方 stabilize するまで defer。

**A4. write 時に通知 body を pre-compute して `pull_requests` / `pull_request_comments` 行に store**: send 時の per-recipient locale lookup を回避。却下 — locale は recipient 毎で event 毎ではない、pre-compute は event 1 個あたり N 行 (locale 毎 1 行) 必要 or locale lookup を行内に denormalize、どちらも既存 `mapping.ts` の純粋性を犯す。

## 8. 結果

**Positive**:
- Phase 24.3 で closed-beta テスター向けの最初の end-to-end push 配信完了 — Phase 39 launch unlock のうちコラボ UX で最大級。
- Classifier ベースの error handling パターン (§3.4) は Phase 24.4+ event でも改修なしで通用。
- `djwt` JSR import で APNs + FCM 両方の署名を 1 ライブラリに集約、将来 ADR amendment で crypto 選択を再議論する必要がない。
- インスタンス内 OAuth キャッシュで warm-up 後の FCM パスレイテンシを ~50–150ms 削減、infra cost 0。

**Negative**:
- Edge Function module 2 個追加 (`apns.ts` + `fcm.ts`)、bounded、各 ~150 行。
- HTTP fake test infrastructure は Skeinly Deno test suite で新規。パターン自体は well-trodden (Node 系 HTTP test suite で同形) だが test directory に新ファイルタイプが増える。
- Production-only APNs パス (§3.5) — 「ローカル debug build から test push」ワークフローは Phase 24.4+ の revisit まで構造的に unsupported。
- `device_token_deleted` ログ行が Supabase Dashboard logs の visibility surface に蓄積。Closed-beta scale では fine、entitlement environment misconfiguration があると初回 push 試行で全 token 削除 + ログ volume spike (loud failure mode、意図的)。

**Tracking**:
- Phase 24.3 で ADR-017 §6 のサブスライス計画が 24.3 まで完了。
- ADR-017 Q1 を resolve。
- Phase 39 closed-beta テスター招待の HARD-GATE: 「Phase 24.2e ✅、Phase 24.3 ⏳」から smoke test 通過後「Phase 24.3 ✅」に進捗。

## 9. 参考

- ADR-017 (Phase 24 push notifications — 本実装が slot in するアーキテクチャ枠組み)
- [Apple — Sending notification requests to APNs](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns)
- [Apple — Establishing a token-based connection to APNs](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns)
- [Firebase — Send messages with the FCM HTTP v1 API](https://firebase.google.com/docs/cloud-messaging/send-message)
- [Firebase — HTTP v1 errors](https://firebase.google.com/docs/cloud-messaging/send-message#admin)
- [djwt JSR](https://jsr.io/@djwt/djwt)
- [Supabase — Edge Function limits](https://supabase.com/docs/guides/functions/limits)
- `supabase/functions/notify-on-write/index.ts` (Phase 24.1 shell — 本 slice が拡張する統合点)
- `supabase/functions/notify-on-write/mapping.ts` (Phase 24.1 — recipient + body 計算、24.3 で不変)
- `supabase/functions/revenuecat-webhook/index.ts` (Phase 39 prep — Bearer auth + JSON-log 先例)
