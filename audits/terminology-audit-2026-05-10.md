# Terminology audit — Skeinly v0.1.0 (closed-beta) — 2026-05-10

## Purpose

Layperson-knitter comprehensibility audit of every user-facing term in
the Skeinly app, EN + JA. Run before tag-pushing v0.1.0 to TestFlight +
Play Internal so closed-beta testers see knitter-friendly language, not
Git/GitHub jargon transplanted from the dev team's mental model.

Scope: i18n strings, code identifiers (KMP shared + Android + iOS
SwiftUI), Supabase tables/columns, Maestro flow assertions, ADR titles,
spec titles, CLAUDE.md domain-model section.

## Methodology

1. **Inventory extraction** — direct read of all 5 i18n sources +
   targeted grep for code identifiers + ADR title scan + Supabase
   migration scan. (Alternative: Explore subagent — failed with
   "Prompt is too long" on the first attempt; switched to direct
   extraction.)
2. **Verification-layer (research domain) audit** per
   `.claude/skills/verification-layer/SKILL.md`:
   - Generator: `docs-researcher` agent dispatched 2026-05-10 with
     T2 tier — "industry knitting term survey across primary sources +
     competitor app UI strings".
   - Critic: re-verify with a different tool family (direct WebFetch
     on competitor app store screenshots / GitHub-MCP for any
     open-source knitting app UI strings) once Generator returns.
   - Citation discipline: primary sources only (Craft Yarn Council,
     日本毛糸手芸協会, established publishers, screenshot-verified
     competitor app strings).
3. **Agent-team deliberation** per term (knitter / ui-ux-designer /
   technical-writer / product-manager voices, 2–3 sentences each, per
   CLAUDE.md `## Development Workflow §10`). Concludes with explicit
   `**Decision:**` line.
4. **Implementation impact estimate** per term — i18n key list, code
   identifier list, Supabase rename, Maestro assertion list, ADR/spec
   ref count.

## Suspect terms (user-flagged on session entry)

User direct flag: 構造化チャート / Structured Chart, fork / branch /
merge / pull request / revision / discovery / gauge.

Cross-cutting additions surfaced during inventory: commit, diff,
conflict, "take theirs" / "keep mine".

Hidden git jargon found post-first-pass: **"current tip"** (JA leaves
"tip" untranslated as English mid-sentence — a lock-in bug),
**"upstream patterns" / "upstream chart"** (used in 2 PR-flow keys).

---

## Generator findings (docs-researcher, 2026-05-10, T2)

The verification-layer Generator (docs-researcher agent, T2 tier)
surveyed primary sources: Craft Yarn Council, Nihon Vogue-sha
(tezukuritown.com), Ravelry, Stitch Fiddle, knitCompanion,
EnvisioKnit, Patternum, AMMIES, amu app, StitchBook, Vogue Knitting,
Interweave. Tools used: WebSearch × 18, WebFetch × 16. The Critic
(research-critic agent) is dispatched in parallel using a different
tool family (GitHub access + direct WebFetch on cited URLs).

### Confirmed decisions (HIGH-confidence primary-source-backed)

| Term | EN decision | JA decision | Primary source |
|---|---|---|---|
| Structured chart | **`Chart`** (drop qualifier) | **「編み図」** | CYC "stitch charts in knit patterns are being used"; Nihon Vogue-sha 「編み目記号」「編み図」; Ravelry filter "chart" vs "photo or video tutorial"; Stitch Fiddle "Create new chart"; amu app 「編み図」「編み図づくり」 |
| Fork | **`Save a copy`** (CTA) / **`Save to my library`** (verbose) | **「コピーを保存」** / **「マイライブラリに追加」** | Ravelry "add to Ravelry library"; amu app 「複製」 (with subtypes 「通常複製」「回転複製」); knitCompanion "Copy"; no app uses "fork" |
| Branch | **`Variation`** (UI) / **`Variant`** (model) | **「アレンジ」** | Patternum "version" (size variants); Generator notes 「アレンジ」 "may resonate more strongly with craft-culture knitters" |
| Merge | **`Apply changes`** | **「変更を反映」** / **「変更を取り込む」** | No knitting app surveyed uses "merge" — concept is novel; clarity over alignment |
| Pull request | **`Suggested change`** (noun) / **`Suggest a change`** (verb) | **「変更の提案」** | Concept is novel in knitting apps (Skeinly originates it); CTA uses descriptive vocabulary |
| Revision / Commit | **`Version`** (snapshot) / **`History`** (the screen) | **「バージョン」** / **「履歴」** | amu app 「操作履歴」; Patternum "versions"; EnvisioKnit "revision" used in copy but no UI label |
| Discovery | **`Browse Patterns`** | **「パターンを探す」** (already shipped!) | Ravelry "Browse Categories" / "patterns"; Nihon Vogue-sha 「パターン（編み図）」; matches Ravelry's dominant nav idiom |
| Gauge | **`Gauge`** (no-op) | **「ゲージ」** (no-op) | CYC "**gauge**" universal; Nihon Vogue-sha 「ゲージとは編み目の大きさのことです」; loanword is the JP industry standard |

### Branch / Revision naming collision — resolved

The Generator's first instinct was to map both Branch + Revision onto
"Version" / 「バージョン」, but they're semantically distinct:
- **Revision** = a saved snapshot at a point in time (Google Docs
  version-history entry)
- **Branch** = a named pointer to a particular revision; users can
  have multiple branches each tracking a parallel design

Resolution that avoids the collision:

| Concept | EN | JA | Rationale |
|---|---|---|---|
| Saved snapshot | `Version` | 「バージョン」 | Universal consumer-software idiom (Google Docs, Figma, Apple Pages) |
| Named parallel design | `Variation` | 「アレンジ」 | Knitting-industry "variation" (Vogue Knitting, Interweave editorial usage) + 「アレンジ」 well-established in JP knitting culture |

Code identifier: `ChartRevision` → `ChartVersion`,
`ChartBranch` → `ChartVariation` (avoids Kotlin keyword collision
with `kotlin.Variant` typealiases). DB tables:
`chart_revisions` → `chart_versions`,
`chart_branches` → `chart_variations`.

### Critic verification status — ✅ COMPLETE 2026-05-10

Critic (research-critic) tool family used: direct WebFetch on
Generator's cited URLs + `gh search code` for Ravelry corroboration
(disjoint from Generator's WebSearch-first family). Round 1/2.

**Verdict: REQUEST CHANGES — Generator reliability MEDIUM (6/9 PASS).**

| # | Claim | Result | Severity | Implication for rename decision |
|---|---|---|---|---|
| 1 | Ravelry "Pattern Instructions" filter labels (chart/written/...) | FAIL-MISMATCH (secondary-source-only) | HIGH | Citation drops; **decision UNCHANGED** because the chart-vs-photo distinction is independently confirmed by CYC + amu app + Stitch Fiddle |
| 2 | blog.ravelry.com dropdown labels ("save in favorites" etc.) | PASS | — | — |
| 3 | CYC "Stitch charts in knit patterns are being used more and more" | PASS | — | Strongest evidence for §1 Structured Chart → Chart |
| 4 | CYC "Knit Gauge Range" yarn weight table | PASS | — | Confirms §11 Gauge no-op |
| 5 | Nihon Vogue-sha 「編み目記号」「編み図」 | PASS-DRIFT (only 編み目記号 on cited URL) | MEDIUM | 「編み図」 confirmed via amu app primary instead; **decision UNCHANGED** |
| 6 | Nihon Vogue-sha 「ゲージとは編み目の大きさのことです」 | PASS | — | Confirms §11 Gauge JA no-op |
| 7 | amu app 「編み図」「複製」「通常複製」「回転複製」 etc. | PASS | — | Strong primary support for §1 + §2 (Fork → 「複製」/「コピー」) |
| 8 | Patternum "version" for size variants | FAIL-MISMATCH (only "PDF version" on page) | HIGH | Citation drops; **decision UNCHANGED** because Revision → Version is independently grounded in Google Docs / Apple Pages / Figma universal idiom + amu「操作履歴」 |
| 9 | EnvisioKnit features page exists | PASS | — | — |

### Critic-driven citation corrections (folded into Phase D)

- §1 Structured Chart → Chart: drop monarch-knitting blog citation;
  retain CYC + amu + Stitch Fiddle as primary evidence.
- §1 Structured Chart → 編み図: replace ckihonkb5/ URL citation
  with amu app primary (App Store JP description carries 「編み図」
  「編み図づくり」「編み図記号」 verbatim).
- §6 Revision → Version: drop Patternum citation; retain Google
  Docs / Apple Pages / Figma industry idiom argument + amu
  「操作履歴」 (history concept).

### Critic-recommended Round 2 — scoped + executed 2026-05-10

User directive: "Phase Dの前にRound 2を回すかどうかはAgent Team内で
協議してください". Agent-team deliberation chose a **scoped Round 2**
covering only the Variation / アレンジ pair (the LOW-MEDIUM
confidence item from Round 1). The 3 dropped citations from Round 1
were not re-litigated — they are recorded above as "citation drop /
decision unchanged".

**Round 2 result (Generator, HIGH-confidence primary sources):**

EN side — Brooklyn Tweed Shapeshift Cardigan: "You may choose to
knit from six **variations**". Norah Gaughan Maremma: "this
**variation**, distinguishing it from the base chunky version".
Stephen West Boneyard Shawl: "**striped variation** with elongated
wingspan". Tuttle Japanese Knitting Stitch Bible: "designs and
**variations** result in intricate patterns". Kotomi Hayashi 55
Fantastic Japanese Knitting Stitches: "some are **variations** on
a theme". HIGH confidence — multiple T1 publishers using the term
at chart/pattern level for parallel design forks.

JA side — 日本ヴォーグ社 (tezukuritown.com): 「チュニックプルオーバーを
ベストに**アレンジ**」 / 「スリーブレスプルオーバーをカーディガンに
**アレンジ**」 / 「基本的な模様をおさえた上で、自由に**アレンジ**して
楽しむことができます」. HIGH confidence — Japan's most authoritative
knitting publisher uses 「アレンジ」 verbatim for "take a base
pattern and produce a modified design version of it".

Bilingual codification: not found in a single bilingual source
(MEDIUM on this specific axis), but no semantic conflict detected
in either language's corpus.

**Semantic asymmetry note**: 「アレンジ」 carries more creative-
authorship weight than EN "variation" (which is more neutral).
For Skeinly's use case — a named branch of a chart representing
the knitter's own design fork — this asymmetry is a FEATURE: the
JA UI reads as "your creative rework", which positions
collaboration more actively, while the EN "Variation" reads
neutrally and is recognized.

**Round 2 decision: KEEP `Variation` (EN) / 「アレンジ」 (JA) as
the rename pair. Confidence HIGH per-language; pair plausibility
backed by independent T1 evidence in both markets.**

### Round 1 dropped-citation audit trail (unchanged)

The Round 1 Critic flagged 3 dropped citations:
1. Ravelry "Pattern Instructions" filter labels — secondary-source
   only (monarch-knitting blog). DROPPED. Decision unchanged
   because chart-vs-photo distinction is independently confirmed by
   CYC + amu app + Stitch Fiddle.
2. Nihon Vogue-sha 「編み図」 not on cited URL ckihonkb5/ — only
   「編み目記号」 there. DROPPED. 「編み図」 confirmed via amu app
   App Store JP description (verbatim 「編み図」「編み図づくり」「編み図記号」).
3. Patternum "version" — page only uses "PDF version". DROPPED.
   §6 Revision → Version is independently grounded in Google Docs
   / Apple Pages / Figma idiom + amu「操作履歴」 + Patternum's
   absence does not invalidate.

The audit trail is captured here so a future ADR amendment that
cites this audit doc does not propagate the dropped secondary
citations.

---

---

## 1. "Structured Chart" / 「構造化チャート」

### Current state

| Surface | Locations |
|---|---|
| i18n keys (EN+JA) | `state_no_structured_chart`, `action_view_structured_chart`, `action_edit_structured_chart`, `action_create_structured_chart` (4 keys) |
| Code identifiers | `domain/model/StructuredChart.kt`, `data/repository/StructuredChartRepositoryImpl.kt`, `data/local/LocalStructuredChartDataSource.kt`, `data/remote/RemoteStructuredChartDataSource.kt`, `data/mapper/StructuredChartMapper.kt`, references throughout `ui/chart/`, `ui/projectdetail/`, `ui/discovery/`, `ui/pullrequest/` (~30 files) |
| Supabase | Table `chart_documents` (Migration 012). Column references throughout `chart_revisions` (Migration 015) and `pull_requests` (Migration 016) |
| ADR / spec | ADR-007 title ("Pivot from Row Counter to **Structured Chart** Authoring"), ADR-008 title ("**Structured Chart** Data Model"), ADR-012 subtitle ("Structured Chart Extension"), Spec `chart-editor.md` H1 ("Spec — **Structured Chart** Editor") |
| CLAUDE.md | `## Domain Model (Core Concepts)` — does not currently use the term; references "Pattern" only. ADR pointer references. |

### App's intent

The app distinguishes:
- **Structured chart** = symbol-grid chart authored cell-by-cell with
  the editor (Phase 32+). The data spine: `chart_documents` table +
  `chart_revisions` history + `chart_branches` variants.
- **Chart image** = photo / scan of a printed chart attached to a
  project as reference (`label_chart_images_section`). Pre-Phase-32
  workflow.

The "structured" qualifier exists to disambiguate the two surfaces.

### Agent-team deliberation

**knitter** (domain advisor): 「構造化チャート」って何が "構造化" なのか
は editorial 側の話で、編み物者には伝わらない。普通「編み図」一語で済む。
編み図画像との区別は app 内の dichotomy なら名前で吸収できる: 「編み図」
vs「編み図写真」/「参考画像」.

**ui-ux-designer**: "Structured" qualifier is dev-internal
vocabulary — the dichotomy with "Chart Image" already disambiguates
naturally without it. Title screen `title_chart_viewer = "Chart"`
already drops the qualifier; we're inconsistent against ourselves on
the action labels. Rename to plain "Chart" in user-facing copy +
keep "Chart Image" as the photo-attachment side.

**technical-writer**: i18n key rename: `*_structured_chart` →
`*_chart` (collision check: `state_empty_chart` exists but is
distinct semantics — "the structured chart you opened is empty"
vs "no structured chart created yet"; we'll keep both with new
labels). Code rename: `StructuredChart` → `Chart` (no `Chart` class
exists today; `chart_documents` table aligns naturally). Supabase:
`chart_documents` is fine — the `_documents` suffix already
disambiguates without needing "structured".

**product-manager**: Brand positioning — knitter-first language.
The "structured" qualifier is dev mental-model leakage. Removing it
makes the editor / viewer / Discovery copy read naturally to a
beta tester opening the app for the first time.

**Decision (pending docs-researcher confirmation)**:
- EN: `Structured Chart` → `Chart` (drop qualifier)
- JA: 「構造化チャート」 → 「編み図」 (use established knitting term)
- Code identifier: `StructuredChart` → `Chart`
- Supabase: keep `chart_documents` (no rename — disambiguation
  already encoded in suffix)
- ADR titles: rewrite ADR-007 / ADR-008 / ADR-012 / Spec
  `chart-editor.md` to drop "structured"

### Implementation impact

| Surface | Count |
|---|---|
| i18n keys to rename + retranslate | 4 |
| Kotlin files referencing `StructuredChart` | ~30 |
| Supabase migrations | 0 (rename of class but not table) |
| ADRs to retitle | 3 (007 / 008 / 012) |
| Specs to retitle | 1 (`chart-editor.md`) |
| Maestro flows | 0 (no flow asserts on the qualifier text) |

---

## 2. "Fork" / 「フォーク」

### Current state

| Surface | Locations |
|---|---|
| i18n keys | `action_fork_pattern`, `action_fork`, `state_forking_pattern`, `label_permission_fork`, `label_forked_from`, `state_forked_from_deleted`, `label_activity_forked_by`, `action_fork_to_projects`, `message_forked_successfully`, `message_forked_chart_failed` (10 keys) |
| Code identifiers | `domain/usecase/ForkSharedPatternUseCase.kt`, `domain/usecase/ForkPublicPatternUseCase.kt`, `Pattern.parentPatternId`, references in DiscoveryViewModel / NavGraph / ProjectDetail |
| Supabase | Column `patterns.parent_pattern_id` (no `fork` in column names) |
| ADR / spec | ADR-012 title ("Phase 36 Chart Discovery + **Fork**"), inline references throughout |

### App's intent

Click on a public pattern in Discovery → it's copied into your
library with attribution to the original author. This preserves
the "lineage" so the contributor can later open a pull request
(`Phase 38`) back to the original.

### Agent-team deliberation

**knitter**: 「フォーク」 sounds like cutlery to a knitter. Industry
mental model: 「コピーする」「マイパターンに保存」「アレンジする」.
Ravelry's `Save to Library` is the established crochet/knit-app
convention.

**ui-ux-designer**: The CTA's actual operation is "make a copy of this
pattern + remember the source so I can later contribute back". A
knitter-facing label needs to convey the copy operation; the
attribution is metadata shown in pattern detail. "Save to my
patterns" matches Ravelry; "Make a copy" is universal. The
GitHub-flavored "Fork" makes the experience read like a developer
tool.

**technical-writer**: Wide rename: 10 i18n keys + 2 use-cases + every
ViewModel reference. New verb: `save` is overloaded with
`action_save` (the generic save action), so use-case rename should
be `SavePatternToLibraryUseCase` or `CopyPatternToLibraryUseCase`
to avoid identifier collision. JA i18n — 「コピー」 is recognized but
generic; 「マイパターンに追加」 is more action-oriented + matches
Ravelry JA conventions.

**product-manager**: This is the highest-leverage rename in the
audit — "Fork" appears in Discovery (where every new beta tester
lands) and in the Activity Feed. Mis-translation here puts a
GitHub-shaped speedbump in front of the most onboarding-critical
flow. The post-PR-back-to-original semantics of "fork" don't need
to surface in the user-facing CTA — the Pull Request feature
introduces them when they matter.

**Decision (pending docs-researcher confirmation)**:
- EN: `Fork` → `Save to my patterns` (CTA + activity feed) /
  `Save` (where the verb stands alone in space-constrained UI)
- JA: 「フォーク」 → 「マイパターンに追加」 (CTA + activity feed) /
  「追加」 (terse) — final wording pending Ravelry JA + StitchBook
  JA primary-source confirmation
- Code identifiers: `ForkPublicPatternUseCase` →
  `SavePublicPatternToLibraryUseCase`,
  `ForkSharedPatternUseCase` → `SaveSharedPatternToLibraryUseCase`,
  `Pattern.parentPatternId` → keep (column name internal; rename
  noise outweighs win)
- Supabase: no rename (no `fork` in column names; `parent_pattern_id`
  is fine)
- ADR-012 title: "Phase 36 Pattern Discovery + Save-to-Library" (or
  similar — pending)

### Implementation impact

| Surface | Count |
|---|---|
| i18n keys to rename + retranslate | 10 |
| Kotlin files referencing `Fork*` | ~15 |
| Supabase migrations | 0 |
| ADR / spec | ADR-012 title + inline refs (~30 occurrences) |
| Maestro flows | needs grep — Maestro asserts on `text: "Fork"` likely exist |

---

## 3. "Branch" / 「ブランチ」

### Current state

| Surface | Locations |
|---|---|
| i18n keys | `title_branch_picker`, `action_create_branch`, `dialog_create_branch_title`, `label_branch_name`, `label_current_branch`, `state_no_branches`, `action_switch`, `message_switched_to_branch` (8 keys) |
| Code identifiers | `domain/model/ChartBranch.kt`, `data/repository/ChartBranchRepositoryImpl.kt`, `ui/chart/ChartBranchPickerViewModel.kt`, NavGraph references |
| Supabase | Table `chart_branches` (Migration 015) |
| ADR / spec | ADR-013 title ("Phase 37 Collaboration Core (Commit History, **Branch**, Diff View)"), Spec `collaboration-history.md` H1 |

### App's intent

Phase 37.4 — git-style named pointer to a chart revision. Lets you
try variant designs (e.g. "lace yoke variant", "longer sleeves
variant") without losing the original chart.

### Agent-team deliberation

**knitter**: 「ブランチ」 is git-only vocabulary. Industry equivalent
in JP knitting culture: 「アレンジ」 — well-established term meaning
"a customized take on a base pattern". EN: "variation" is the
standard editorial term in published knitting books.

**ui-ux-designer**: The branch picker UX (modal sheet listing
variants with "current" indicator + new-branch CTA) maps cleanly
to "Variations" without UX surgery. Naming pivot doesn't change
behavior.

**technical-writer**: Rename `ChartBranch` → `ChartVariation`
(or `ChartVariant`). Supabase: `chart_branches` →
`chart_variations` (or `chart_variants`). User-side breaking-change
policy permits this pre-v1. `ChartVariation` slightly conflicts
with knitting "stitch variation" lexicon; `ChartVariant` is more
neutral. Recommend `ChartVariant` + `chart_variants`.

**product-manager**: Beta testers will encounter the branch picker
when they save a second version of a chart. "Variations" /
「アレンジ」 lands on a familiar mental model immediately;
"Branches" / 「ブランチ」 forces explanation.

**Decision (pending docs-researcher confirmation)**:
- EN: `Branch` → `Variant` (model + UI) / `Variations` (UI plural,
  picker title)
- JA: 「ブランチ」 → 「アレンジ」 (UI) / 「アレンジ」 in code DB doesn't
  matter — code stays English: `ChartVariant` / `chart_variants`
- Supabase: rename `chart_branches` → `chart_variants` (Migration
  026) + retitle column references throughout downstream tables

### Implementation impact

| Surface | Count |
|---|---|
| i18n keys to rename + retranslate | 8 |
| Kotlin files referencing `ChartBranch` / `Branch` | ~15 |
| Supabase migrations | new migration 026: `ALTER TABLE chart_branches RENAME TO chart_variants` + column rename in dependent tables |
| ADR titles | ADR-013 retitle |
| Spec titles | `collaboration-history.md` retitle |
| Maestro flows | possible asserts on "Branch" / 「ブランチ」 |

---

## 4. "Merge" / 「マージ」 + "Conflict" / 「コンフリクト」

### Current state

| Surface | Locations |
|---|---|
| i18n keys (merge) | `action_merge_pr`, `dialog_merge_pr_title`, `dialog_merge_pr_body`, `message_pr_merged_successfully`, `action_apply_and_merge`, `label_pr_status_merged` (6 keys) |
| i18n keys (conflict) | `title_resolve_conflicts`, `label_conflict_summary`, `label_conflict_layer`, `label_conflict_cell`, `state_all_conflicts_resolved` (5 keys) + `action_take_theirs`, `action_keep_mine`, `action_skip_conflict` (3 buttons) |
| Code identifiers | `domain/usecase/MergePullRequestUseCase.kt`, `ui/pullrequest/ConflictResolutionViewModel.kt` + supporting types |
| Supabase | Column `pull_requests.status` (enum: 'open' / 'merged' / 'closed') |
| ADR / spec | ADR-014 title ("Phase 38 Pull Request Workflow (Comment, Approve, **Merge**, **Conflict** Resolution)") |

### App's intent

Phase 38.4 — a pattern owner accepts a pull request, applying the
contributor's chart changes. If the pattern owner has edited the
same cells since the fork, conflict resolution lets them choose
contributor's value vs their own value vs ancestor (skip).

### Agent-team deliberation

**knitter**: 「マージ」「コンフリクト」 SaaS dev jargon. JP equivalents:
「適用」「反映」/ 「衝突」「不一致」. EN: "Apply changes" / "Combine"
/ "Disagreement" / "Overlap".

**ui-ux-designer**: The merge button conveys "approve this
contributor's proposal + apply it". "Apply changes" is the
universal verb-noun pair. For conflicts, "Disagreement" is too
human-relations-flavored; "Overlap" is more neutral but loses the
contention semantics. Settle on "Conflict" → "Issue" or just
embed in copy: "Some cells need your decision".

**technical-writer**: Code rename: `MergePullRequestUseCase` →
`ApplySuggestionUseCase` (cascades from §5 PR rename). Status enum
`'merged'` → `'applied'` in Supabase. JA:「適用済み」for status,
「変更を適用」for action verb. Conflict resolution UI: keep
「コンフリクト」 too jarring; rename to 「重複」 or 「不一致」.
The JA conflict-resolution buttons 「相手側を採用」「自分側を維持」
are already knitter-friendly — no rename needed. EN
"Take theirs" / "Keep mine" → "Use contributor's" / "Use mine"
or simpler "Use suggested" / "Use original".

**product-manager**: Conflict resolution is a sophisticated power-user
flow that ≥80% of beta testers won't encounter (no concurrent
edits on the same cell). The merge button is the high-traffic
surface; nail it. Conflicts surface only when the path runs.

**Decision (pending docs-researcher confirmation)**:
- EN: `Merge` (verb + noun) → `Apply` / `Apply changes` /
  `Apply suggestion`. Status: `merged` → `applied`. CTA: `Merge`
  button → `Apply changes`.
- JA: 「マージ」 → 「適用」/「変更を適用」. Status: 「マージ済み」 →
  「適用済み」.
- EN: `Conflict` → `Conflict` retained inline copy with
  knitter-friendly framing ("Some cells changed in both places —
  pick which version to keep") + button rename `Take theirs` →
  `Use contributor's`, `Keep mine` → `Use mine`.
- JA: 「コンフリクト」 → 「重複」 or 「不一致」 (final pending docs-
  researcher). 「相手側を採用」「自分側を維持」 retained (already
  knitter-friendly).
- Supabase: alter the `status` CHECK constraint to swap `merged`
  for `applied` value; data migration converts existing rows.

### Implementation impact

| Surface | Count |
|---|---|
| i18n keys to rename + retranslate | 11 |
| Kotlin files referencing `Merge*` | ~10 |
| Supabase migrations | new migration 027: alter `pull_requests.status` enum |
| ADR | ADR-014 title + inline refs |
| Maestro flows | possible asserts on "Merge" / 「マージ」 |

---

## 5. "Pull request" / 「プルリクエスト」 (PR)

### Current state

| Surface | Locations |
|---|---|
| i18n keys | `title_pull_requests`, `action_pull_requests`, `state_no_pull_requests`, `state_no_pull_requests_body`, `label_pr_status_open`, `label_pr_status_merged`, `label_pr_status_closed`, `label_pr_authored_by`, `title_pull_request_detail`, `label_pr_description`, `label_pr_diff_preview`, `label_pr_comments`, `hint_add_comment_to_pr`, `action_post_comment`, `action_merge_pr`, `action_close_pr`, `dialog_merge_pr_title`, `dialog_merge_pr_body`, `dialog_close_pr_title`, `dialog_close_pr_body`, `state_pr_not_found`, `message_pr_closed_successfully`, `action_open_pull_request`, `dialog_open_pull_request_title`, `label_pr_title`, `hint_pr_title`, `hint_pr_description_optional`, `action_open_pr`, `message_pr_opened_successfully`, `message_pr_merged_successfully`, `label_filter_incoming`, `label_filter_outgoing` (~32 keys — Phase 38 wave) |
| Code identifiers | `domain/model/PullRequest.kt`, `data/repository/PullRequestRepositoryImpl.kt`, `domain/usecase/MergePullRequestUseCase.kt`, `domain/usecase/GetPullRequestUseCase.kt`, `ui/pullrequest/PullRequestListViewModel.kt`, `ui/pullrequest/PullRequestDetailViewModel.kt`, `ui/pullrequest/ConflictResolutionViewModel.kt`, `ui/pullrequest/PullRequestListScreen` (Compose), iOS `PullRequestListScreen.swift`, `PullRequestDetailScreen.swift` (~40+ files) |
| Supabase | Table `pull_requests` (Migration 016), `pull_request_comments` (Migration 016) |
| ADR / spec | ADR-014 title ("Phase 38 **Pull Request** Workflow"), Spec `pull-request-flow.md` H1 |

### App's intent

Phase 38 — a contributor with a forked pattern proposes their chart
changes back to the original pattern owner. The owner reviews, can
comment, then merges or closes.

### Agent-team deliberation

**knitter**: 「プルリクエスト」 100% GitHub jargon. No layperson
knitter on Earth recognizes this as a knitting-app affordance.
JP/EN both fail equally hard.

**ui-ux-designer**: The mental model is "I want to suggest a change
to the original pattern". CTA: "Suggest a change" / 「変更を提案」.
Noun: "Suggestion" / 「提案」. Filter chips
`Incoming / Outgoing` → `Received / Sent` /
「受け取った提案 / 送った提案」.

**technical-writer**: ~32 i18n keys + ~40 code files + 2 Supabase
tables. Largest rename surface in the audit. Code: `PullRequest`
→ `Suggestion`. Tables: `pull_requests` → `suggestions`,
`pull_request_comments` → `suggestion_comments`. Status enum
`'open'` is fine as-is. ADR-014 retitle to "Phase 38 Suggestion
Workflow". All `_pr_*` keys → `_suggestion_*`. CommonTest names +
test fakes also rename.

**product-manager**: Single highest-impact rename across the audit.
Phase 38 is a flagship beta-feature; it MUST land on knitter-
friendly language or the feature reads as enterprise dev tooling.
The community / collaboration positioning depends on this term
being inviting rather than technical.

**Decision (pending docs-researcher confirmation)**:
- EN: `Pull request` → `Suggestion` (noun) / `Suggest a change`
  (CTA verb)
- JA: 「プルリクエスト」 → 「提案」 (noun) /
  「変更を提案」 (CTA verb)
- Code: `PullRequest` → `Suggestion` (model + repo + UC + VM)
- Supabase: rename `pull_requests` → `suggestions` (Migration
  028), `pull_request_comments` → `suggestion_comments`
- ADR-014 retitle, Spec `pull-request-flow.md` retitle and rename
  to `suggestion-flow.md`
- Filters: `Incoming / Outgoing` → `Received / Sent` /
  「受け取った / 送った」

### Implementation impact

| Surface | Count |
|---|---|
| i18n keys to rename + retranslate | 32 |
| Kotlin files referencing `PullRequest` | ~40+ |
| Swift files | ~5 |
| Supabase migrations | new migration 028: `RENAME TABLE` × 2 + `RENAME COLUMN` references |
| ADR | ADR-014 title + body + JA mirror |
| Spec | rename file + retitle |
| Maestro flows | very likely many asserts |

---

## 6. "Revision" / 「リビジョン」 + "Commit" / 「コミット」

### Current state

| Surface | Locations |
|---|---|
| i18n keys | `state_no_chart_history_body` (mentions "revisions"), `label_initial_commit`, `action_restore_revision`, `dialog_restore_revision_title`, `dialog_restore_revision_body`, `state_no_changes` ("between these revisions") (6 keys with the terms inline) |
| Code identifiers | `domain/model/ChartRevision.kt`, `data/repository/ChartRevisionRepositoryImpl.kt`, `data/local/LocalChartRevisionDataSource.kt`, `data/remote/RemoteChartRevisionDataSource.kt`, `data/mapper/ChartRevisionMapper.kt`, `domain/repository/ChartRevisionRepository.kt`, references in ChartHistory / Diff / Branch ViewModels (~20+ files) |
| Supabase | Table `chart_revisions` (Migration 015) |
| ADR / spec | ADR-013 title ("Phase 37 Collaboration Core (**Commit History**, ...)") |

### App's intent

Phase 37.2 — every chart save appends a revision (a versioned
snapshot) to history. Users can view + restore any past revision.

### Agent-team deliberation

**knitter**: 「リビジョン」「コミット」 SE-only vocabulary. Industry
mental model: 「バージョン」「保存履歴」.

**ui-ux-designer**: "Version" is the universal user-facing noun
for "saved snapshot at a point in time" — Google Docs, Figma,
Apple Pages all use it. The verb stays "save". The chart
history screen becomes "Version history" / 「バージョン履歴」.

**technical-writer**: Rename `ChartRevision` → `ChartVersion`,
`chart_revisions` → `chart_versions`. Collision check:
`label_pack_version_x = "v%1$d"` (pack version, separate noun) —
no source-code collision because pack version is just a format
string. Migration 029 renames table; downstream `pull_requests`
column refs update too.

**product-manager**: "Version" is what every consumer software
trains users to expect. Ship this rename; it's the cleanest
match to user mental model.

**Decision (pending docs-researcher confirmation)**:
- EN: `Revision` → `Version`. `Initial commit` → `Initial version`.
  `Restore as new commit` → `Save as new version`.
- JA: 「リビジョン」 → 「バージョン」. 「最初のコミット」 →
  「最初のバージョン」. 「新しいコミットとして復元」 →
  「新しいバージョンとして保存」.
- Code: `ChartRevision` → `ChartVersion` everywhere
- Supabase: `chart_revisions` → `chart_versions` (Migration 029)
- ADR-013 retitle: drop "Commit History" → "Version History"

### Implementation impact

| Surface | Count |
|---|---|
| i18n keys to rename + retranslate | 6 |
| Kotlin files referencing `ChartRevision` / `Revision` | ~20+ |
| Supabase migrations | new migration 029: rename `chart_revisions` table |
| ADR | ADR-013 title + body + JA mirror |
| Maestro flows | asserts on "Initial commit" / 「最初のコミット」 likely |

---

## 7. "Diff" / 「差分」 (Phase 37.3 cross-cutting)

### Current state

| Surface | Locations |
|---|---|
| i18n keys | `title_chart_diff = "Chart Diff"` / 「チャート差分」, `label_diff_added`, `label_diff_modified`, `label_diff_removed`, `label_pr_diff_preview = "Proposed changes"` / 「提案された変更」 (5 keys) |
| Code identifiers | `domain/model/ChartDiff.kt`, `ui/chart/ChartDiffScreen.kt`, `ui/chart/ChartDiffViewModel.kt` |
| Supabase | none (computed) |
| ADR / spec | ADR-013 title ("...**Diff View**"), inline refs |

### Agent-team deliberation

**knitter**: 「差分」 is OK in JA — slightly technical but used in
non-software contexts (= "difference"). EN "Diff" is dev jargon.

**ui-ux-designer**: "Compare versions" / 「バージョンの比較」 is the
consumer software idiom (Google Docs, Microsoft Word "Compare").

**technical-writer**: Code: `ChartDiff` → `ChartComparison`
(no Supabase impact). i18n: `title_chart_diff = "Chart Diff"` →
"Chart comparison" / 「チャートの比較」.

**product-manager**: Lower priority than the PR/Branch renames —
"diff" surfaces only when a user opens version comparison, which
is a power-user flow.

**Decision**:
- EN: `Diff` → `Comparison` / `Compare` (verb)
- JA: 「差分」 → 「比較」 (clearer match to mental model)
- Code: `ChartDiff` → `ChartComparison`
- Supabase: none

### Implementation impact: 5 i18n keys + ~5 code files.

---

## 8. "Discovery" / 「Discovery」「ディスカバリー」(actually 「パターンを探す」)

### Current state

| Surface | Locations |
|---|---|
| i18n keys EN | `title_discover_patterns = "Discover Patterns"`, `action_discover_patterns = "Discover Patterns"` |
| i18n keys JA | 「パターンを探す」 (already knitter-friendly!) |
| Code identifiers | `ui/discovery/DiscoveryViewModel.kt`, `ui/discovery/DiscoveryScreen.kt`, NavGraph route name |
| Supabase | none |
| ADR | ADR-012 title ("Phase 36 Chart **Discovery** + Fork") |

### Agent-team deliberation

**knitter**: JA 「パターンを探す」 already perfect. EN "Discover" is
slightly aspirational; "Browse" is more task-oriented and matches
Ravelry idiom.

**ui-ux-designer**: "Discover" works as engagement-positive marketing
language. "Browse" is task-positive. Either is acceptable for a
v0.1.0 audience; "Discover" has slight brand-positive lift.
Defer to docs-researcher data on competitor norms.

**technical-writer**: JA already aligned. EN copy is the only edit;
code identifier `DiscoveryViewModel` etc. is internal — keep.

**product-manager**: Low-stakes decision. JA is already shipped at
the right level; EN "Discover" reads fine even to non-knitters.

**Decision (pending docs-researcher confirmation)**:
- EN: keep `Discover Patterns` (defer to docs-researcher; possible
  pivot to `Browse Patterns` if Ravelry / StitchBook precedent
  supports it)
- JA: keep 「パターンを探す」 (no change)
- Code: keep `Discovery*` (internal naming; no consumer reach)
- ADR-012: keep title (or rename if Fork/Save rename cascades)

### Implementation impact

| Surface | Count |
|---|---|
| i18n keys to potentially rename | 2 (EN only) |
| Kotlin files | 0 (internal naming retained) |
| Supabase | 0 |
| ADR | only if Fork rename cascades |

---

## 9. "tip" / 「tip」 (hidden git jargon — found post-first-pass)

### Current state

| Surface | Locations |
|---|---|
| i18n keys | `dialog_restore_revision_body` body — EN "on top of the **current tip**", JA 「現在の**tip**の上に...」 (literally untranslated English word in JA copy!) |
| Code refs | KDoc comment ref in strings.xml line 587 ("the chart's current tip") |
| ADR / spec | likely in ADR-013 body |

### Severity: HIGH for JA

JA copy leaves "tip" as a foreign English word in the middle of
Japanese sentence. A Japanese knitter reading "現在のtipの上に" gets
zero meaning from "tip" — they see an unknown 3-letter English
fragment.

### Decision

- EN: `current tip` → `current version` (cascades from §6
  Revision→Version rename — "current version" makes natural sense
  alongside "Save as new version")
- JA: 「現在のtip」 → 「現在のバージョン」

### Implementation impact: 1 i18n key body retranslate (cascades with §6).

---

## 10. "Upstream" / 「上流」 (Git jargon found post-first-pass)

### Current state

| Surface | Locations |
|---|---|
| i18n keys | `state_no_pull_requests_body` ("Suggestions from forks and to **upstream** patterns will appear here"), `dialog_close_pr_body` ("the changes will not land on the **upstream** chart") |
| JA equivalents | 「上流パターン」「上流のチャート」 |
| Code refs | KDoc throughout PR detail viewmodel + strings.xml comments |

### Severity: HIGH

"Upstream" = git/GitHub idiom for "the original repo this was forked
from". Knitters don't know this. JA「上流」 is the literal kanji
translation but reads as a river-flow analogy that's confusing.

### Agent-team deliberation

**knitter**: 「上流」「downstream」 = SE のリポジトリ言語. 「元のパターン」
「元のチャート」が natural.

**ui-ux-designer**: "Upstream" used here means "the original".
"Original pattern" / "Original chart" / 「元のパターン」/「元のチャート」
read naturally to a non-coder.

**Decision**:
- EN: `upstream patterns` / `upstream chart` →
  `original patterns` / `original chart`
- JA: 「上流パターン」「上流のチャート」 →
  「元のパターン」「元のチャート」

### Implementation impact: 2 i18n key bodies retranslate.

---

## 11. "Gauge" / 「ゲージ」 (sanity)

### Current state

i18n keys: `label_gauge`, `label_gauge_value`, `hint_gauge_example`.

### Sanity check

- EN "gauge" — universally accepted knitting industry term per Craft
  Yarn Council standards. (T1 — Craft Yarn Council "Standards &
  Guidelines for Knitting and Crochet" defines gauge as the primary
  measurement unit.)
- JA 「ゲージ」 — established loanword, used universally in modern
  Japanese knitting industry references. Native alternatives 「目数/
  段数」 are too granular (those are stitch + row counts respectively;
  「ゲージ」 unifies both as the measurement concept).

**Decision: No-op.** Both EN + JA are correct industry terms. This
sanity check is clean.

---

## Cross-cutting decisions

### Status enum value renames (Supabase impact)

If decisions §4 (Merge → Apply) + §5 (Pull request → Suggestion)
land:
- `pull_requests.status` enum: `'open' / 'merged' / 'closed'` →
  `'open' / 'applied' / 'closed'` (Migration 027)
- Then table itself renames in Migration 028: `pull_requests` →
  `suggestions`, `pull_request_comments` → `suggestion_comments`

If decisions §3 (Branch → Variant) + §6 (Revision → Version) land:
- Migration 026: `chart_branches` → `chart_variants`
- Migration 029: `chart_revisions` → `chart_versions`

Recommend: bundle into a single migration 026 covering all 4
table-rename + status-enum-rename ops. Pre-v1 breaking-change
policy permits beta-tester data reset.

### Maestro flow audit — ✅ CHECKED 2026-05-10

`grep -rnE "text:.*(Fork|Pull request|Branch|Merge|Initial commit|...)" e2e/flows/`
returned **zero hits** for both EN and JA suspect terms. The PR + Branch
+ Revision feature surfaces are excluded from the run-on-CI flows
(tagged `requires-supabase` per `e2e/run-{android,ios}.sh`'s
`--exclude-tags requires-supabase`), so no Maestro YAML asserts
on the suspect strings need updating.

**Maestro impact: 0 files.**

### ADR + spec retitle list

| Doc | Current title | Proposed title |
|---|---|---|
| ADR-007 | Pivot from Row Counter to Structured Chart Authoring | Pivot from Row Counter to Chart Authoring |
| ADR-008 | Structured Chart Data Model | Chart Data Model |
| ADR-012 | Phase 36 Chart Discovery + Fork (Structured Chart Extension) | Phase 36 Pattern Discovery + Save-to-Library |
| ADR-013 | Phase 37 Collaboration Core (Commit History, Branch, Diff View) | Phase 37 Collaboration Core (Version History, Variants, Comparison View) |
| ADR-014 | Phase 38 Pull Request Workflow (Comment, Approve, Merge, Conflict Resolution) | Phase 38 Suggestion Workflow (Comment, Approve, Apply, Conflict Resolution) |
| Spec `chart-editor.md` | Spec — Structured Chart Editor | Spec — Chart Editor |
| Spec `collaboration-history.md` | Spec — Collaboration / History / Branch / Diff | Spec — Collaboration / Version History / Variants / Comparison |
| Spec `pull-request-flow.md` | Spec — Pull Request Flow | Spec — Suggestion Flow |

### CLAUDE.md updates

- `## Domain Model (Core Concepts)` — currently mentions Pattern /
  Project / Progress / Share. Add note about Chart / Suggestion /
  Variant / Version vocabulary post-rename.
- Phase entries (38.x, 37.x, 36.x) reference the old names — update
  inline.

---

## Aggregate impact

| Layer | Approx file count | Approx line touches |
|---|---|---|
| i18n (5 sources × 2 langs) | 5 | ~80 keys × 2 = ~160 strings |
| KMP shared Kotlin | ~80 files | ~400 line touches (renames) |
| iOS SwiftUI | ~10 files | ~60 line touches |
| Android XML | 2 files (notification channel) | ~5 line touches |
| Supabase migrations | 1 new file (026) | ~80 lines SQL |
| ADRs (en + ja) | 5 ADRs × 2 langs = 10 files | ~40 line touches per file |
| Specs (en) | 3 files | ~10 line touches per file |
| Maestro flows | TBD | TBD |
| CLAUDE.md | 1 file | ~30 line touches |
| README + docs/public | TBD | TBD |

**Estimated total**: ~110 files, ~800 line touches, 1 net-new
Supabase migration, 1 spec rename.

---

## Open questions awaiting docs-researcher Generator return

1. Ravelry's actual term for "fork" / pattern-copy operation —
   is it "Save to library" or something else?
2. StitchBook JA UI string for the public-pattern browse screen
   header — does it use 「探す」 or 「Discovery」?
3. Knitbase / Knit Companion / Stitch Fiddle terminology survey
   for "version" / "branch" / "snapshot".
4. Whether 「アレンジ」 (current JP knitting culture term for
   pattern-variant) is captured in any 編み物 industry glossary
   or only in vernacular use.
5. Standard EN+JA term for "comparing two versions of a chart" in
   knitting industry references — is "compare" / 「比較」 the
   established phrasing?

## Open questions awaiting agent-team / user

1. Should `Pattern.parentPatternId` rename to `Pattern.copiedFromPatternId`
   for clarity? Cascades into Supabase column rename.
2. Final EN word for "Conflict": keep `Conflict` (knitter-readable
   in context) or pivot to `Issue` / `Overlap`?
3. Migration 026 grouping: bundle all table renames into one, or
   one migration per concern?

---

## Methodology notes

- The Explore subagent failed with "Prompt is too long" on first
  attempt — direct extraction was used instead. Inventory is direct-
  read from the EN+JA strings.xml (full files), iOS xcstrings
  (sample read), Android strings.xml (full files), grep across
  shared/.../{domain,ui,data}/ for code identifiers, glob across
  docs/en/adr/ + .claude/docs/spec/ for ADR + spec titles, grep
  across supabase/migrations/ for table names.
- The verification-layer Generator (docs-researcher) is dispatched
  with T2 tier on the question of "industry knitting term survey
  across primary sources + competitor app UI strings". When it
  returns, this document's `**Decision (pending docs-researcher
  confirmation)**` lines will be marked as either Confirmed or
  Adjusted with the primary-source citations.
- Phase D implementation gate: NO renames land in code until the
  user signs off on the final audit table.
