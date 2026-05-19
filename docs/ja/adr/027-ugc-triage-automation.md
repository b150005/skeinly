# ADR-027: UGC モデレーション・トリアージ自動化 (GitHub Issue webhook)

> 英語原文: [docs/en/adr/027-ugc-triage-automation.md](../../en/adr/027-ugc-triage-automation.md)
>
> 状態: Proposed (2026-05-19)。Operator 採択待ち。実装は別 worker batch (Z2 placeholder) で段階分け; Z1 (doc-only) で本 ADR と ADR-021 §D5 cross-link + JA mirror を配備。

## 概要

ADR-021 §D5「Operator triage workflow (foundation, pre-alpha)」は現状、UGC 報告トリアージを **Supabase と GitHub の手動 dual-sync** として規定している:

1. UGC 報告 Issue が `b150005/skeinly` に `ugc-report` ラベル付きで着地 (ADR-021 §D3 の Edge Function `submit-ugc-report` 経由)。
2. 運用者が Issue を開き、Supabase Dashboard SQL Editor で `target_id` を実コンテンツに解決。
3. Apple 24 時間 SLA 内に 3 択 (`resolved_remove` / `resolved_keep` / `dismissed`) で判断。
4. 運用者が手動で `UPDATE public.ugc_reports SET state = '<resolution>', operator_notes = '…', resolved_at = now() WHERE id = '<report_id>';` を Dashboard SQL Editor で実行。
5. 運用者が手動で GitHub Issue を `ugc_reports.id` 参照付き 1 行 summary で close。

詳細 runbook は `docs/en/ops/ugc-moderation-sop.md` (Step 1–5 + optional Step 6 reporter 通知)。pre-alpha 低 volume では正しい設計だが、dual-sync は volume と独立に解消可能な structural risk を 3 点抱える:

- **人為ミスモード**: SQL UPDATE 忘れ / Issue Close 忘れ / state 値の typo (`'resolved-remove'` vs `'resolved_remove'`) / 同時 2 件トリアージ時の operator_notes 取り違え。各失敗が SLA 計測を静かに壊し、運用上 audit でのみ surface する。
- **SLA の構造的保証なし**: Apple 24 時間 SLA は `resolved_at` を起点に計測するが、これは運用者の `SET resolved_at = now()` に依存する手動値。UPDATE が遅延 / 抜けると、SLA monitoring query (`docs/en/ops/ugc-moderation-sop.md` `## Monitoring queries`) が古い `resolved_at IS NULL` を読み続け、GitHub 側で実際は解決済の報告が 24 時間 open として残る乖離が生じる。
- **Volume 独立の自動化価値**: ADR-021 §D5 末尾「~10 reports/week 超えたら revisit」は *モデレータープール scale-out* (ダッシュボード構築 / 契約モデレーター onboarding) の閾値判断であり、本 ADR が対象とする「個別トリアージの構造的人為ミス削減 + audit trail 自動化」とは **直交**する。週 1 件規模でも自動化のリターンがあるため、§D5 の volume 閾値とは独立に採用する。

ADR-027 は ADR-021 §D5 を **1 方向の自動化** で evolve する: GitHub Issue close (運用者の Issue 上の*自然な*終端アクション) を webhook trigger として、Supabase Edge Function が対応する `ugc_reports.state` UPDATE を実行。手動 SQL UPDATE path は webhook 不通時の fallback として残す。

新規 ADR として立てる理由 (§D5 in-place 書き換えではなく): pre-alpha 低 volume の「manual で OK」判断は §D5 出荷後の運用知見から出た evolve であり、§D5 元設計の欠陥ではない。§D5 を保全して cross-link することで両方の判断 (§D5 = 当時の妥当性 / ADR-027 = 現在の改善) が audit 可能。ADR-021 Revision history は cross-link 追加を記録、本 ADR が新設計を carry。

## 主要な判断

### D1 — GitHub Webhook 設定 (リポジトリレベル)

Webhook subscription は **リポジトリレベル** で `https://github.com/b150005/skeinly/settings/hooks` に登録。Operator が 2026-05-19 に確定 (代替の App-level scope は §A1 で却下)。

| 設定項目 | 値 |
|---|---|
| Payload URL | `<SUPABASE_FUNCTIONS_URL>/github-webhook` |
| Content type | `application/json` |
| Secret | `GITHUB_WEBHOOK_SECRET` (新規; 32+ byte hex; HMAC SHA-256 の source) |
| SSL verification | Enabled (default) |
| Events | `Let me select individual events.` → `Issues` のみ |
| Active | ✅ |

リポジトリレベル scope のメリット:

- 既存「Skeinly Feedback」GitHub App (ADR-020 §D6 step 4) の `Webhook section → uncheck Active` は変更不要。App は *outbound* (Supabase → GitHub) 用 (`submit-bug-report` + `submit-ugc-report`)、本 webhook は *inbound* (GitHub → Supabase) のトリアージ自動化用。2 つの concern が disjoint な secret + config surface で運用される。
- ADR-020 §Q2 で確定した `b150005/skeinly` single-repo concentration の boundary を維持。
- Secret rotation を App PEM と独立で実施可能。ローテーション手順: 新規 hex 生成 → GitHub webhook ページ登録 → Supabase Edge Function secrets 登録 → `github-webhook` 再デプロイ → GitHub 側の旧値削除。

`Issues` event scope は Issue ライフサイクル全アクション (`opened` / `edited` / `closed` / `reopened` / `labeled` / `assigned` / 等) に対し payload を配信する。Edge Function が `action === 'closed'` のみフィルタする。GitHub の event payload 形状は action 間で共通なため、subscription を分割するより function 側フィルタの方が運用 surface が小さい。

### D2 — Edge Function `github-webhook` (Deno)

`supabase/config.toml` に以下で登録:

```toml
[functions.github-webhook]
verify_jwt = false
```

`verify_jwt = false` は ADR-020 §Q4 の precedent と同根: 呼び出し元 (GitHub Webhook Delivery) は Supabase Auth context を持たないため、Supabase レイヤーの JWT check は performative になるか正当配信を拒否するかのいずれか。実認証は **HMAC SHA-256 署名** を `X-Hub-Signature-256` ヘッダで検証 (function 内で `GITHUB_WEBHOOK_SECRET` 利用)。`notify-on-write` / `revenuecat-webhook` と同形の「third-party-credential-bearing Edge Function: verify_jwt = false + 関数内で別途認証」パターン。

#### コード構造

```
supabase/functions/github-webhook/
├── index.ts        — Deno.serve handler: HMAC 検証 → event filter → payload validate → state map → DB UPDATE
├── hmac.ts         — X-Hub-Signature-256 検証 (timing-safe compare; raw body HMAC-SHA-256)
├── mapping.ts      — Issue label → ugc_reports.state closed-enum マッピング (D3 参照)
├── _fakes.ts       — Deno テスト向け globalThis.fetch + Supabase service-role client モック
├── index.test.ts   — handler スイート (HMAC pass/fail / event filter / payload 形状 / idempotency)
├── deno.json       — runtime + test 設定
└── README.md       — deploy + smoke-test + secret rotation 手順
```

`submit-bug-report/` + `submit-ugc-report/` と揃えた配置で Edge Function fleet を統一。

#### リクエストフロー

```
┌────────────┐  POST /functions/v1/github-webhook
│ GitHub     │  Content-Type: application/json
│ Webhook    │  X-Hub-Signature-256: sha256=<HMAC hex>
│ Delivery   │  X-GitHub-Event: issues
└─────┬──────┘  X-GitHub-Delivery: <UUID>
      │         { action, issue, repository, sender, … }
      ▼
┌─────────────────────────────────────────────────────┐
│ Edge Function github-webhook (verify_jwt = false)   │
│                                                     │
│  1. raw body を Uint8Array で読む (署名は raw bytes  │
│     対象。JSON 経由の再シリアライズでは NG)。       │
│  2. HMAC-SHA-256(GITHUB_WEBHOOK_SECRET, body) と     │
│     X-Hub-Signature-256 tail を constant-time       │
│     compare。不一致 → 401 で drop。                  │
│  3. JSON.parse(body)。action !== 'closed' →         │
│     200 {ok: true, code: 'IGNORED_EVENT'} で終了。  │
│  4. Payload validation (下表)。失敗 → 200 {ok:      │
│     false, code: '<reason>'} で終了 (GitHub は 2xx  │
│     で再配信しない)。                                │
│  5. Decision label → ugc_reports.state 値マップ。   │
│  6. UPDATE public.ugc_reports SET state = $1,        │
│     resolved_at = $2, operator_notes = $3 WHERE     │
│     id = $4 AND resolved_at IS NULL                 │
│     (service-role client; idempotent + no-clobber)。│
│  7. 200 {ok: true, code: 'UPDATED', ugc_report_id,  │
│     new_state} を返却。                             │
└─────────────────────────────────────────────────────┘
```

function は GitHub に常に HTTP 200 を返す。アプリケーションレベル失敗は body 内 `ok: false, code: '<enum>'` でエンコード。非 200 は Supabase プラットフォーム障害用 (GitHub の webhook-redelivery 機構が役立つケース) に予約。トレードオフ: `INVALID_HMAC` 等で GitHub 側再試行しないため、デバッグは function log + GitHub webhook delivery 履歴ページに依存。validation 失敗のほぼ全ては設定ミス / 改ざんペイロードを示すため、これは正しいデフォルト。

#### Payload validation (HMAC 検証通過後)

| チェック | 失敗 code | 結果 |
|---|---|---|
| `action === 'closed'` | `IGNORED_EVENT` | 200、DB 書き込みなし |
| `issue.labels` に `ugc-report` (`submit-ugc-report` が設定する parent label) を含む | `NOT_UGC_REPORT` | 200、DB 書き込みなし |
| `issue.body` が `/Report ID:\s*`([0-9a-f-]{36})`/i` にマッチ (UUID 抽出。SOP の body template から確定) | `MISSING_REPORT_ID` | 200、DB 書き込みなし + log |
| `issue.labels` に 3 decision label (`state-resolved-remove` / `state-resolved-keep` / `state-dismissed`) のちょうど 1 つを含む | `MISSING_DECISION_LABEL` (0 個) / `AMBIGUOUS_DECISION_LABEL` (≥2 個) | 200、DB 書き込みなし + log |
| 抽出 UUID = `ugc_reports.id` で行が存在 | `REPORT_NOT_FOUND` | 200、DB 書き込みなし + log |

`MISSING_REPORT_ID` と `REPORT_NOT_FOUND` の場合は手動 fallback 維持: 運用者は Dashboard SQL Editor で手動 UPDATE 可能。Log があるので失敗診断は function-logs grep で済む。

#### Idempotency

UPDATE 文に `AND resolved_at IS NULL` 条件を付与。運用者が ADR-021 §D5 step 4 通り手動で `resolved_at` を埋めた後に webhook が動いた場合、UPDATE は no-op。逆方向 (webhook 先 / 手動後) も `resolved_at` が既に NULL でないため no-op。"last-write wins" race は存在せず、先勝ち (auto / manual のいずれか) が canonical。

#### Operator_notes 自動生成

deterministic string を書く:

```
Auto-resolved on <issue.closed_at ISO 8601> by github-webhook (delivery <X-GitHub-Delivery>). Label: <state-resolved-…>. GitHub Issue: #<issue.number>.
```

これで audit trail を operator の手書き notes 再入力に依存せず保持。Z2 の SOP 更新で、運用者は *追加* context (例: recusal の理由) のみを必要に応じて upsert する形に簡素化。

### D3 — 判断マッピング (label ベース)

運用者は Issue を close する前に 3 種類の新規 GitHub ラベルのうち **ちょうど 1 つ** を付与:

| GitHub ラベル | `ugc_reports.state` 値 | SOP Step 4 の結果対応 |
|---|---|---|
| `state-resolved-remove` | `'resolved_remove'` | コンテンツ除去 (パターン → `visibility = 'private'` / コメント・suggestion → DELETE) |
| `state-resolved-keep` | `'resolved_keep'` | 報告 false-positive、コンテンツ維持 |
| `state-dismissed` | `'dismissed'` | 報告自体 abusive / false; reporter パターン確認に続く |

`state-` prefix は既存 `triaging` ラベル (ADR-021 §D5 step 1) + 既存 `ugc-report` parent ラベルとの混同回避。代替検討: `triage-*` は `triaging` と名前衝突; `resolution-*` は冗長。`state-` は短く、GitHub Labels picker でアルファベット順に固まり、オートコンプリート上も視認性が高い。

運用者ワークフロー:

1. Issue が `ugc-report` ラベル付きで着地 → 運用者が開く + SQL で target inspect。
2. `triaging` ラベル付与 (既存仕様; 本 ADR では自動化対象外)。
3. 判断 (`resolved_remove` / `resolved_keep` / `dismissed`)。
4. 対応する `state-…` ラベルを 1 つ付与 (新規ステップ)。
5. *コンテンツアクション* を手動で実施: SQL で `patterns.visibility = 'private'` SET / コメント DELETE。(本ステップは手動維持。§スコープ外参照。)
6. Issue close。
7. Webhook 起動 → Edge Function が `ugc_reports.state` + `resolved_at` + auto-`operator_notes` を UPDATE。

Step 1, 2, 3, 5 は ADR-021 §D5 から変更なし。Step 4 が新規、Step 6 は変更なし、Step 7 が新規自動化。Step 4 漏れ (`state-…` ラベル無し) や誤 (`state-resolved-keep` + `state-dismissed` 同時 2 付け) は webhook が `MISSING_DECISION_LABEL` / `AMBIGUOUS_DECISION_LABEL` を log + UPDATE せず。運用者は Issue を re-open + ラベル修正 + 再 close で再トリガー、または手動 SQL fallback。

Comment-keyword 代替 (close コメントに `resolved_remove` 等のキーワード) は §A2 で却下。

### D4 — SLA 計測 (既存 `resolved_at` 再利用)

Edge Function は webhook payload 内 `issue.closed_at` (ISO 8601 文字列 → TIMESTAMPTZ にパース) を migration 031 既存の `ugc_reports.resolved_at` カラムに書き込む。**新カラム不要、新マイグレーション不要**。

2026-05-19 に operator が agent-team deliberation (architect / security-reviewer / code-reviewer / implementer / product-manager の voices) を経て、代替の新カラム `state_resolved_at` に対しこれを確定。詳細根拠は `Implementation notes for Z2` に。

`docs/en/ops/ugc-moderation-sop.md` `## Monitoring queries` の既存クエリは既に `resolved_at` を参照しており、自動書き込みでそのまま動く。Z2 で sop.md に SLA-compliance クエリを追加可能:

```sql
-- SLA compliance window: resolution within 24 hours
SELECT id, state, created_at, resolved_at,
       EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600 AS hours_to_resolve
FROM public.ugc_reports
WHERE resolved_at IS NOT NULL
ORDER BY created_at DESC;
```

UPDATE 文の `AND resolved_at IS NULL` ガードで webhook が手動入力を上書きしない設計。webhook 起動より先に運用者が手動で `resolved_at` を埋めた場合 (webhook 遅延 / 先取りの稀ケース)、手動値が canonical。

### D5 — 後方互換 fallback

ADR-021 §D5 全体が fallback path として end-to-end で動き続ける。webhook は best-effort な自動化レイヤー。手動による全代替が可能な失敗モード:

1. **HMAC 検証失敗** — secret rotation 中 / 改ざん POST。Edge Function は 401 を返す (唯一の非 200 ケース; 攻撃シグナル応答)。UPDATE なし。
2. **Edge Function 利用不可** — Supabase deploy 中 / regional outage / ネットワーク分断。GitHub の webhook 再配信は 8 回約 14 時間 (GitHub Docs 参照) で打ち切られる; その後は運用者が手動で dual-sync を完遂。
3. **Decision label 不在 / 曖昧** — 運用者の付与忘れ / 二重付与。function は log + 200 IGNORED 系を返す; 運用者は label 修正 + 再 close か手動 SQL。
4. **報告行がトリアージ前に削除** (稀; ADR-021 §D5 + sop.md `## Edge cases` の "target row no longer exists" は target 側であり report 側ではない。Report 行削除は運用者操作要なので通常フローでは発生しない) — function は 200 `REPORT_NOT_FOUND`; 運用者が手動対応。

すべての fallback ケースで SOP の Step 1–5 は無修正で動く。Z2 の sop.md 更新で各 step に「webhook 動作時 = auto」「webhook 不通時 = manual fallback path」のペア注記を追加し、運用者がどちらのモードか即判断可能にする。

### D6 — Runbook

Z2 (実装スライス) の出荷物:

- `supabase/functions/github-webhook/` (D2 構造の新規 Edge Function)
- `supabase/config.toml` の `[functions.github-webhook]` + `verify_jwt = false` 追加
- `docs/en/ops/ugc-moderation-sop.md` + JA mirror — Step 1–5 各 step に auto vs manual fallback path のペア注記 + 24 時間 SLA 文言は変更なし + SLA-compliance monitoring クエリ追加
- `docs/en/ops/release-secrets.md` + JA mirror — `GITHUB_WEBHOOK_SECRET` の新 slot エントリ (既存 EF-6+ と同形式)
- `docs/en/adr/021-pre-alpha-ugc-moderation.md` §D5 — Z1 で短い evolution cross-link を入れる; Z2 で sop.md 更新後の文言確定に合わせて必要なら拡張
- Migration: **不要** (D4 通り、既存 `resolved_at` を再利用)

運用者対応 (Z2 user-attended):

1. `b150005/skeinly` Settings → Webhooks → Add webhook で D1 表通り設定 (URL, content-type, secret, events = Issues, Active)。
2. Secret は `openssl rand -hex 32` で 32 byte hex 生成し、GitHub webhook ページ + Supabase Edge Function secrets (`GITHUB_WEBHOOK_SECRET`) の両方に投入。リポジトリには絶対に commit しない。
3. `b150005/skeinly` Issues → Labels → New label で 3 ラベル作成: `state-resolved-remove`, `state-resolved-keep`, `state-dismissed`。`ugc-report` parent と `triaging` は既存。
4. Edge Function deploy: `supabase functions deploy github-webhook` (Skeinly 側 autonomous)。
5. Smoke test (manual): 捨て試し用 UGC report Issue を作成 (またはサンドボックス状態の既存 report を利用)、`state-…` ラベル付与 + close → Dashboard SQL Editor で `ugc_reports.state` が更新されたことを確認。GitHub の webhook delivery 履歴ページで request/response trail も確認。

Step 1, 2, 3 は user-attended (GitHub UI + secret 取り扱い)、Step 4 は autonomous、Step 5 は実 report または sandbox 必要。

## 代替案検討

### A1 — Skeinly Feedback App レベル webhook (vs リポジトリレベル)

却下 (operator 2026-05-19 確定)。既存「Skeinly Feedback」GitHub App の Webhook section (現状 ADR-020 §D6 通り `Active = uncheck`) を有効化し、App secret を webhook secret に兼用する案。Pros: 単一 secret surface; install scope が App と一致。

却下理由:

- ADR-020 で App には明示的に webhook 不要を確定済み。今 active 化すると App の outbound concern (`submit-bug-report` / `submit-ugc-report` の Issue 作成) と inbound triage concern (UGC moderation closing) を App レベルで coupling させる。片方の rotation がもう片方を壊す。
- Bug-report Issue (ADR-020) も UGC-report Issue (ADR-021) も close するが、本自動化対象は UGC-report close のみ。App-level webhook では両方 deliver され、function 側で厳密 filter + false-positive 率上昇。
- 運用面: App 設定ページと repo Settings → Webhooks ページは異なる runbook surface。リポジトリレベルは UGC triage を repo-level concern として repo-level config trail に揃える。

リポジトリレベル (D1) を採用。

### A2 — Comment-keyword 解析 (vs label ベース)

却下。代替案: 運用者が close コメントに `resolved_remove` (3 キーワードのいずれか) を入力し、function がコメント最終投稿を regex 解析。

Pros: ラベル付与 step 不要; 運用者は notes を一度書くだけ。

Cons:

- Typo / 大文字小文字揺れ: `resolved_remove` vs `Resolved Remove` vs `resolved-remove`。厳格 parse は 1 通り以外を reject; 緩い parse は typo を取り逃がす。
- マルチライン + 自由文の close comment は抽出 brittle。運用者は keyword を文章に埋め込むケースが多く (例: "Resolving as resolved_keep because…")、naive regex は誤拾い、保守的 parser は取り逃がす混在挙動。
- GitHub Labels UI affordance なし。Labels は Issue ヘッダーに色付き strip 表示されるが、コメント keyword は thread 内に埋もれ、Issues 一覧スキャン時に視認困難。
- Audit trail が close event + comment に分散。Labels は Issue identity の一部 (`labels` リスト)、解決記録が 1 箇所に集約。

Label ベース (D3) が parser stability + UI affordance + audit trail compactness で優位。

### A3 — GitHub Actions `workflow_dispatch` + service-role 直書き (vs Edge Function)

却下。代替案: `b150005/skeinly` 上のワークフローを `issues.closed` でトリガーし、Supabase service-role key を GitHub repository secret として渡して直接 Supabase に書き込む。

Pros: Edge Function 不要; runner spin-up 平均 < 10 s; GitHub 側で end-to-end 可視。

Cons:

- Supabase service-role key が GitHub repository secret になる。leak 時の blast radius が「Supabase function 内部のみ」から「repo secret を読めるあらゆる workflow」に拡大 — 特に `pull_request_target` 系の `permissions:` 不備で悪意ある PR が secret を exfiltrate するリスク。Edge Function なら service-role key は Supabase 内部に留まる。
- Function vs workflow の選択は ADR-020 §Q4 で同根拠 (secret surface 最小化 + cold-start + Supabase 行書き込みとの局所性) で Edge Function を採用済み。同 pattern 再利用でアーキテクチャを統一。
- Workflow cold start は queue + runner provision + checkout で実測 10–30 s。Supabase Edge Function cold start は typical < 1 s。週 < 10 報告では latency は二次的だが、Edge Function 側が速い。

Edge Function (D2) を採用。ADR-020 §Q4 precedent をそのまま適用。

### A4 — 自動化スキップ、ADR-021 §D5 手動 + 運用者 monitoring queries 追加

却下。代替案: webhook 自動化を作らず、`docs/en/ops/ugc-moderation-sop.md` `## Monitoring queries` に「`created_at` から 12 時間以上経過しても `resolved_at IS NULL` の報告を flag (SLA 50% で早期警報)」の日次 SQL を追加する。Volume が低いので人為ミスは absorb 可能。

Pros: 実装コストゼロ — 運用者の時間追加のみ。

却下理由:

- 人為ミスモードは本 ADR が targeting する構造的 risk そのもの。失敗を *防止* (自動化) する代わりに失敗を *捕捉* (monitoring) する形では、SLA 計測が誰かが query を手動実行するまで unaudited。
- Phase 40 GA 開始の open-distribution で volume 増加; 自動化価値は volume-independent (§概要参照)。
- Apple/Google review はモデレーション timeliness の *構造的保証* (audit trail 自動生成) を *手順的保証* (運用者が定期 query 実行) より評価しやすい。App Store Connect review notes の説明も構造的 framing の方が明確。

A4 = "何もしない" 極。却下。(Skeinly project policy: コストは先送り理由にならない; 本 ADR 採用はスコープ判断であってコスト駆動ではない。)

### A5 — 双方向 sync (Supabase 行 UPDATE → GitHub Issue 自動 close)

ADR-027 scope では却下 (deferred to future ADR)。対称方向 — 運用者が Dashboard SQL Editor で `UPDATE ugc_reports SET state = …` を直接実行すると system が GitHub Issue を自動 close — を実装すれば運用者の手動 close (D3 ワークフロー Step 6) が不要に。

Pros: SQL Editor を主 surface とする運用者なら GitHub UI に context-switch せずに済む。

却下理由:

- Supabase Database Webhook → Edge Function → GitHub API (Issue close) の第二 function が必要。secret rotation の cadence が二系統、失敗モードドキュメントが二系統。
- ADR-027 の close-then-update モデルを update-then-close に逆転。idempotency を慎重に設計すれば両モデル共存可能 (`AND resolved_at IS NULL` ガードで webhook 先勝ち、対称 guard で GitHub close 側でも既 close 検出) だが、設計 surface が 2x。
- 現状ワークフローは SOP Step 1 (acknowledge 用 `triaging` 付与) + Step 5 (close) で既に GitHub UI を触れる。Step 5 close は高 friction surface ではないため、close 自動化単独の value は close → SQL 方向より低い。

SQL-first ワークフローが運用者の明示的 preference になった時点で future ADR で再検討。§スコープ外に列挙。

## コンプライアンス対応

### Apple App Store Review Guideline 1.2 — UGC Safety

ADR-021 のコンプライアンス対応表は要件レベル (Filter / Report / Block / Published contact 全て ✅) で変更なし。ADR-027 はモデレーション timeliness の *構造的保証* を強化:

- トリアージ判断 → SLA 計測が SoT (`ugc_reports.state` + `resolved_at`) で end-to-end 自動化。
- GitHub Issue thread は引き続き immutable audit trail; GitHub の webhook delivery 履歴ページが二次 audit trail (request/response per delivery, GitHub の webhook retention policy 通り現状 30 日保持)。
- 手動 fallback path は維持; ADR-021 §D5 step 3 の SLA コミットメントは変更なし。

App Store Connect Review Notes 更新 (Z2 で文言確定):

> Skeinly's UGC triage now writes `ugc_reports.state` automatically when the operator closes the corresponding GitHub Issue with a labeled decision. The Supabase Edge Function performs HMAC-verified UPDATE on `resolved_at` and the canonical state column. The manual SQL UPDATE path remains as fallback if the webhook is unavailable. The 24-hour SLA commitment is unchanged.

### Google Play UGC ポリシー

マッピング同一; Google Play は Apple のサブセット。同じ構造的保証で同時に満たす。

## スコープ外

ADR-027 含めず、future ADR or 後続 worker batch (Z2 以降) で再検討する範囲:

- **Reporter への解決通知**: `resolved_remove` 時に reporter に email する courtesy は SOP Step 6 で optional に既に regulate。in-app / push の programmatic 通知は別 ADR (通知 surface 設計 + i18n + opt-out — UGC triage automation scope 外)。
- **マルチリポジトリ webhook subscription**: ADR-020 §Q2 の `b150005/skeinly` single-repo concentration は固定。fork / mirror リポジトリは明示的に範囲外。
- **コンテンツ自動除去**: `state = 'resolved_remove'` 遷移時に運用者が手動実施する `UPDATE patterns SET visibility = 'private' WHERE id = '<target_id>'` (またはコメントの DELETE 等価) を webhook chain で自動化することは safety 観点で却下: コンテンツ除去は不可逆 (DELETE) または影響大 (visibility flip) なため operator-in-the-loop boundary を維持。Phase 40 GA 後の高 volume 時に再検討余地。
- **双方向 sync (Supabase → GitHub auto-close)**: §A5 参照。SQL-first ワークフローが運用者 preference になった時点で future ADR。
- **Ambiguous decision-label の自動コメント**: D3 は現状 0 / ≥2 ラベルで log + no-op。Issue にコメント自動投稿で運用者に修正依頼するのは scope creep; ADR-027 範囲外 (Z2 が頻度高い失敗モードと判断したら追加可能)。
- **モデレータープール recusal 対応**: ADR-021 §Out-of-scope で既出。マルチモデレーター + recusal は変更なし。
- **Trust-and-safety ML 分類器**: ADR-021 §A3 / §Out-of-scope と同様; 変更なし。

## 実装段階

| Slice | スコープ | Wave |
|---|---|---|
| 027.0 — ADR draft + ADR-021 §D5 cross-link | 本 ADR + JA mirror + ADR-021 EN+JA §D5 cross-link + Revision history 追記 + Z1.md タスクファイル | Z1 (本 worker) |
| 027.1 — Edge Function + config | `supabase/functions/github-webhook/{index,hmac,mapping,_fakes}.ts` + テスト + README + `supabase/config.toml` 登録 | Z2 (別 worker batch) |
| 027.2 — sop.md 更新 | `docs/en/ops/ugc-moderation-sop.md` + JA mirror に Step 1–5 の auto/manual fallback 注記 + SLA-compliance クエリ追加 + Z1 cross-link 文言調整 | Z2 |
| 027.3 — release-secrets.md | `GITHUB_WEBHOOK_SECRET` 新 slot エントリ (EN + JA, 既存 EF-6+ 形式) | Z2 |
| 027.4 — Operator 設定 | GitHub Repo Settings → Webhooks 登録 + 3 新規 Labels + Supabase Edge Function secret 登録 + EF deploy + smoke test | Z2 (user-attended) |

### Implementation notes for Z2

Z1 が ADR 起草中に surface した、Z2 実装着手時に必要な事実:

- **マイグレーション不要**: migration 031 (ADR-021 §D1) で配備された既存 `ugc_reports.resolved_at TIMESTAMPTZ NULL` カラムが SLA 計測 timestamp 役。Edge Function は `issue.closed_at` を `AND resolved_at IS NULL` idempotency ガード下でこのカラムに書き込む。下流設計上必要にならない限り Z2 で migration 追加すべきでない。
- **マイグレーション番号 (必要になった場合)**: Z1 起草時点で migration 037 (`037_phase_25_1_wipe_friend_graph.sql`) が最新。Z2 でスキーマ追加が必要になった場合 (例: `ugc_reports` から `issues_closed_log` テーブルへの FK) は `ls supabase/migrations/` で最新確認後、次連番を claim。
- **`resolved_at` vs `state_resolved_at` 選択**: 2026-05-19 に operator が agent-team deliberation (architect / security-reviewer / code-reviewer / implementer / product-manager) を経て、新 `state_resolved_at` カラム導入 vs 既存 `resolved_at` 再利用で後者を確定。根拠: スキーマ重複なし / migration risk なし / monitoring queries 既存のまま / `WHERE resolved_at IS NULL` で idempotency 明確 / 解決時刻 single source of truth。Z1 を spawn した worker prompt は仮想 `state_resolved_at` を参照していたが、これは obsolete; Z2 は本記録に従う。
- **Label 名は固定**: `state-resolved-remove`, `state-resolved-keep`, `state-dismissed`。§D6 user-attended Step 3 で EF deploy 前に repo Labels に作成。リネームは labels + `mapping.ts` + sop.md の 3 箇所で coordinated change が必要。
- **`triaging` ラベルは自動化対象外**: 既存 SOP Step 1 (「運用者が handling 開始時に `triaging` 付与」) は手動維持。webhook は close 時のみ起動; opening + acknowledge は引き続き運用者操作。
- **HMAC 実装詳細**: GitHub は HMAC-SHA-256 を *raw request body bytes* に対し計算 (JSON 経由再シリアライズではない)。Deno handler は signature verify 前に body を Uint8Array で読む必要がある。Deno 標準ライブラリの `crypto.subtle.importKey` + `crypto.subtle.verify` with `{name: 'HMAC', hash: 'SHA-256'}` がサポート; 実装時 [GitHub Docs — Validating webhook deliveries](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries) を参照。
- **Issue body template の安定性**: payload validation の UUID 抽出は `supabase/functions/submit-ugc-report/mapping.ts` が emit する `Report ID: \`<UUID>\`` 行に依存。Z2 (または future change) がこの template を変更すると `github-webhook` regex も lockstep で更新必要。両所に 1 行コメント明記推奨。

## クロスリファレンス

- [ADR-021](021-pre-alpha-ugc-moderation.md) — base ADR; §D5 を本 ADR が evolve
- [ADR-020](020-phase-39-w5-bug-report-proxy.md) — Edge Function `verify_jwt = false` precedent (Q4) + GitHub App + secret custody pattern (D6)
- [supabase/functions/submit-ugc-report/](../../../supabase/functions/submit-ugc-report/) — Issue body template (`mapping.ts`) が webhook regex anchor
- [supabase/functions/submit-bug-report/](../../../supabase/functions/submit-bug-report/) — `github-webhook` の Edge Function コード形状 precedent
- [docs/en/ops/ugc-moderation-sop.md](../../en/ops/ugc-moderation-sop.md) — Z2 で auto/manual fallback 注記更新
- [docs/en/ops/release-secrets.md](../../en/ops/release-secrets.md) — Z2 で `GITHUB_WEBHOOK_SECRET` slot 追加
- [Apple App Store Review Guideline 1.2 — User Generated Content](https://developer.apple.com/app-store/review/guidelines/#user-generated-content)
- [Google Play UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937)
- [GitHub Docs — Repository webhooks](https://docs.github.com/en/webhooks)
- [GitHub Docs — Validating webhook deliveries](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries)
- [GitHub Docs — Webhook events: Issues](https://docs.github.com/en/webhooks/webhook-events-and-payloads#issues)

## 改訂履歴

| 日付 | 変更 | 著者 |
|---|---|---|
| 2026-05-19 | 初版提案 (Z1 worker、Y1–Y4 batch consolidation 後)。実装は Z2 で段階分け。Webhook scope (repo-level) と SLA カラム (既存 `resolved_at` 再利用) は agent-team deliberation 経由で operator 確定。 | b150005 |
