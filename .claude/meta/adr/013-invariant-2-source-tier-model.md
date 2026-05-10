# ADR-013: Invariant 2 Source Tier Model — Regulator Guidance in Compliance Citations

## Status

Accepted — 2026-05-09

User selected **Option A — Tier 1.5 allow-list extension** with
**verification-layer-wide scope** on 2026-05-09 after ADR-013 was
shipped Proposed with two coherent decisions. The architecture-critic
counter-proposal (Option D — statute-only tightening) is preserved
verbatim under `## Counter-proposal` per ADR-010's design-domain
protocol, with re-evaluation triggers stated.

## Context

ADR-011 introduced the `compliance-checklist` Skill with six
invariants. **Invariant 2 — primary-source-only citation** — names
statute repositories (e-Gov 法令検索, EUR-Lex, California Legislative
Information) and platform policy pages as the allowed citation sources,
and explicitly disqualifies "blog summaries, Q&A sites, AI summaries,
news articles, law-firm explainers." The lineage is ADR-008 (research
verification layer) and ADR-010 (verification layer generalization),
both of which establish primary-source-only citation as the
verification-layer's load-bearing invariant.

A third category is unaccounted for: **regulator-issued official
interpretive guidance**. Concrete examples already cited in the Skill
body:

- **EDPB Guidelines** under GDPR Art. 70 (e.g., Guidelines 03/2022 on
  deceptive design patterns, cited in
  `.claude/skills/compliance-checklist/jurisdictions/EU.md` lines 73–76;
  EDPB Guidelines on DPIA cited at lines 52–54).
- **個人情報保護委員会 (PPC)** Q&A, ガイドライン, and 通達 (cited at
  `.claude/skills/compliance-checklist/jurisdictions/JP.md` lines 81–84
  for the cross-border-transfer adequate-protection list).
- **Apple Privacy Manifest specification** under Required Reasons API
  (cited at `.claude/skills/compliance-checklist/jurisdictions/platform.md`
  lines 38–40).
- **Google Play SDK Index** obligations under the User Data policy
  (referenced at `platform.md` line 69).
- (Anticipated) **California Privacy Protection Agency (CPPA)
  Regulations** under CCPA §1798.185 once US-CA citations expand.

These documents are **neither** legislative text **nor** secondary
commentary. They are issued by named regulators acting under
delegation authority granted by the parent statute (GDPR Art. 70(1)(e)
for EDPB; 個人情報保護法 §132 for PPC; CCPA §1798.185 for CPPA;
Apple's first-party platform authority for the Privacy Manifest spec).
Strict reading of Invariant 2 forces their removal — which materially
degrades the Skill, because:

- DPIA-necessity judgment under GDPR Art. 35 turns on the nine-criteria
  test that lives in EDPB-endorsed WP248 and EDPB Guidelines, not in
  Art. 35 statute text alone.
- Cookie-consent dark-pattern criteria under ePrivacy Art. 5(3) live
  in EDPB Guidelines 03/2022; the directive itself defines neither
  "deceptive" nor "free choice" operationally.
- PPC enforcement posture for cross-border transfers (§28) and breach
  notification (§26) lives in PPC ガイドライン and 通達; statute
  text gives the obligation but not the operational threshold.

The 2026-05-09 internal debate (architect / architecture-critic /
security-reviewer) deferred this question to the half-yearly cadence
(target 2026-11-09). The user has now requested resolution in this
session, ahead of cadence. The half-yearly re-verification of all
`jurisdictions/*.md` citations remains scheduled for 2026-11-09 ± weeks
and is independent of this ADR.

### Forces in tension

- **Bright-line verifiability** (the verification-layer's foundational
  property) wants a closed allowlist that a CI script can enforce.
  Adding a new tier moves the boundary; the boundary must still be
  closed.
- **Operational usefulness** of the compliance-checklist Skill depends
  on being able to point human reviewers at the documents that
  actually answer their question. Statute text alone does not.
- **Scope coupling** to ADR-008 / ADR-010. Whatever is decided here
  may either propagate to the whole verification-layer (research,
  implementation, design domains) or stay scoped to compliance-checklist
  alone — the choice is itself a decision.

## Decision

Extend Invariant 2 across the verification-layer (and therefore the
compliance-checklist Skill that inherits it) to admit a **Tier 1.5 —
issuing-regulator official interpretive guidance**, with five binding
sub-rules. ADR-008 and ADR-010 receive a coordinated amendment that
records the propagation; the per-domain protocols are not otherwise
rewritten.

### Option A — Tier 1.5 allow-list extension

1. **Closed allowlist, fixed at the ADR layer.** Tier 1.5 admits
   citations only from these named regulators:
   - **EDPB** Guidelines, Recommendations, and Opinions adopted under
     GDPR Art. 70(1)(e).
   - **個人情報保護委員会 (PPC)** ガイドライン, Q&A, and 通達 issued
     under 個人情報保護法 §147–§149 delegation authority.
   - **California Privacy Protection Agency (CPPA)** Regulations
     adopted under CCPA §1798.185.
   - **Apple** Privacy Manifest specification and Required Reasons
     API documentation under Apple's first-party platform authority.
   - **Google** Play User Data policy and SDK Index documentation
     under Google's first-party platform authority.

   Adding a sixth regulator requires a new ADR. Skill maintainers
   cannot extend the list via Skill body edits.

2. **Pairing rule.** A Tier 1.5 citation must always appear alongside
   a Tier 1 (statute or first-party platform spec) citation on the
   same checklist item. Tier 1.5 alone is invalid output and the
   Skill must reject it.

3. **Authority floor.** Tier 1.5 admits only formal instruments
   issued by the named regulator under their enabling statute's
   delegation authority. Excluded as Tier 1.5: regulator blog posts,
   press releases, regulator-staff individual op-eds, regulator
   social-media posts, FAQ landing pages, conference slides. Those
   sit at Tier 3 (not citable).

4. **Stale-guidance handling.** Each Tier 1.5 citation is verified
   in-force (not "superseded by" / "withdrawn") at re-verification
   time. Withdrawn guidance auto-demotes to Tier 3 and is removed
   from `jurisdictions/*.md`. The half-yearly cadence (180 days for
   JP/EU/US-CA, 90 days for platform) covers this check.

5. **Scope: verification-layer-wide.** Tier 1.5 is admitted across
   the verification-layer's three domains (`research`,
   `implementation`, `design`) and the `compliance-checklist` Skill
   that inherits the citation discipline. The same five sub-rules
   apply uniformly:
   - `docs-researcher` and `research-critic` may cite Tier 1.5 when
     the question under review concerns a regulated domain whose
     statute *delegates* interpretation to a named regulator on the
     allowlist (e.g., a question about GDPR DPIA mechanics may cite
     EDPB Guidelines alongside GDPR Art. 35; a question about React
     `useEffect` may not — there is no enabling regulator).
   - `architecture-critic` (design domain) and
     `adversarial-implementer` (implementation domain) inherit the
     same rule: Tier 1.5 is citable when the design or
     implementation under review intersects a delegated-regulator
     domain.
   - The pairing rule (sub-rule 2) and authority floor (sub-rule 3)
     apply unchanged across domains.
   - Topics outside delegated-regulator domains continue to require
     Tier 1 only — the verification-layer's bright line is preserved
     for the 95%+ of research that has nothing to do with legal
     compliance.

   ADR-008 and ADR-010 each receive an amendment section recording
   this propagation, parallel to ADR-008's existing 2026-05-08
   amendment for ADR-010. The per-domain protocols
   (`research/protocol.md`, `research/checklist.md`,
   `implementation/protocol.md`, `design/protocol.md`) are updated to
   reference the Tier 1.5 allowlist; no domain protocol is otherwise
   rewritten. The closed allowlist itself lives in this ADR (sub-rule
   1) and is referenced from `verification-layer/SKILL.md` as the
   single source of truth.

### Why this Decision was offered with two coherent paths

ADR-008 and ADR-010 each landed a single Decision because their
debate was about *mechanism* (how Generator/Critic split, how iteration
bounds work). The Invariant 2 question is about a *boundary* — what
counts as a primary source — and the boundary itself is values-bound:
Option A privileges operational usefulness; Option D privileges
verifiability. Both are internally coherent. The closest precedent is
ADR-012, which preserved the rejected alternative as `## Counter-proposal`
under ADR-010's design-domain protocol. ADR-013 followed that pattern.
The user selected Option A on 2026-05-09 with verification-layer-wide
scope; Option D is preserved verbatim below as `## Counter-proposal`
with re-evaluation triggers.

### Migration follows in a separate commit

Per ADR-011's what/why-vs-how separation, the migration work is filed
as a separate commit:

- Rewrite `compliance-checklist/SKILL.md` Invariant 2 with the
  three-tier structure (Tier 1 / Tier 1.5 / disqualifying).
- Annotate every regulator-guidance citation in
  `jurisdictions/EU.md` / `JP.md` / `platform.md` with a `[Tier 1.5]`
  marker, paired with the Tier 1 statute or platform-spec citation.
- Update `verification-layer/SKILL.md` shared invariants section
  (invariant 3 — primary-source-only citation) to point at this
  ADR's allowlist and cross-reference `research/checklist.md`'s
  primary-source allowlist as the Tier 1 reference.
- Update `verification-layer/research/checklist.md` to add a
  Tier 1.5 section that references back to this ADR.

## Counter-proposal

> Adversarial review by `architecture-critic`, 2026-05-09. Round 1/1.
> Preserved verbatim per ADR-010 design-domain protocol.

### Selected alternative

Option D — remove regulator guidance from the citation tier system
entirely; demote to a non-citation `## See also` reading list.

### Counter-decision

Invariant 2's primary-source allowlist remains restricted to enacted
statutes and first-party platform specs. Regulator guidance (EDPB
Guidelines, PPC Q&A, Apple review notes) MAY appear in a `## See also`
section as orientation reading, but MUST NOT be cited as evidence
supporting a checklist item.

### Counter-consequences

#### Positive

- Bright-line verifiability: every cited source is independently
  authoritative under its enabling statute (GDPR Art. 288 TFEU;
  個人情報保護法 §132 designating PPC).
- No tier-drift: collapses the 1.0 / 1.5 boundary debate by removing
  the boundary.
- Reviewer load drops: no per-citation Tier adjudication.

#### Negative

- Compliance items needing *interpretive* support (e.g., GDPR
  Recital 26 anonymization standard) lose direct citation backing and
  must rest on statute text alone.

#### Neutral

- `## See also` becomes a permanent jurisdiction-file section;
  technical-writer owns its hygiene.

### Independent citations

- https://eur-lex.europa.eu/eli/reg/2016/679/oj — GDPR Art. 70
  (EDPB's role is *advisory*, not law-making) — retrieved 2026-05-09.
- https://elaws.e-gov.go.jp/document?lawid=415AC0000000057 —
  個人情報保護法 §132 (PPC's enabling statute scope) — retrieved
  2026-05-09.

### Re-evaluation trigger

Switch from D to A when **a checklist item fails review twice within
one cadence cycle solely because regulator guidance was unavailable
as a citation** — recorded in the verification-review log. Until that
signal appears, D holds.

### Recommendation

Adopt D; revisit only on the trigger above or at the 2026-11-09
cadence.

## Consequences

### Positive

- The compliance-checklist Skill keeps pointing reviewers at the
  documents that actually answer their operational questions (DPIA
  nine-criteria, cookie-consent dark-patterns, PPC cross-border list).
- Invariant 2 stops being silently violated by the Skill body — the
  prior state, where regulator guidance was cited "in supplementary
  positions" with no rule covering them, ends.
- The five binding sub-rules (closed allowlist, pairing rule,
  authority floor, stale handling, verification-layer-wide scope) keep
  the boundary closed even though it has moved. CI-enforceable.
- Verification-layer-wide propagation prevents the asymmetry the
  architect flagged during steering: research / implementation /
  design Critics no longer reject EDPB Guidelines as a citation when
  the topic is a delegated-regulator domain. Cross-domain operators
  see one rule, not two.

### Negative

- Invariant 2 is no longer a one-line rule. Tier 1.5 admits a small
  number of named regulators, each of which can issue, supersede, or
  withdraw documents. Maintenance is real and recurring; mitigated by
  pinning each citation to a retrieval date and re-verifying at
  cadence.
- ADR-008 and ADR-010 are amended (not rewritten) — the per-domain
  Critic checklists need to internalize the Tier 1.5 sub-rules.
  Surface area grows in three protocol files (`research/checklist.md`,
  `implementation/checklist.md`, `design/checklist.md`).
- The pairing rule shifts cognitive load from "is this a primary
  source" (binary) to "is the topic in a delegated-regulator domain"
  (judgement). Generator and Critic must agree on the latter or the
  Critic will reject Tier 1.5 citations as out of scope for the topic.

### Risks

- A regulator issues a controversial Guideline that a court later
  invalidates. Citation discipline says "cite what the regulator
  issued" but the legal value is now contested. Mitigation: the
  Disclaimer block in compliance-checklist already says output is
  not legal advice and that qualified counsel must review; this risk
  is bounded by the same layer that bounds every other risk in the
  Skill. For research / implementation / design domains the same
  Critic finding mechanism handles "Tier 1.5 cited but contested" via
  the standard severity table.
- Counter-proposal's failure mode: readers may route around the
  pairing rule by treating Tier 1.5 as primary in practice. The
  re-evaluation trigger named in `## Counter-proposal` (two failures
  per cadence cycle attributable to Tier 1.5 misuse) is the formal
  mechanism for catching this and considering the bright-line option.

### Neutral

- ADR-011's `## Known ambiguity` section is updated to
  `Resolved by ADR-013`.
- CHANGELOG `[Unreleased]` records the ADR and the migration commit
  separately.
- The half-yearly cadence pass at 2026-11-09 ± weeks is unaffected.
  Citations are re-verified against primary sources regardless.
- ADR-008 and ADR-010 receive amendment sections recording the
  Tier 1.5 propagation, parallel to ADR-008's existing 2026-05-08
  amendment for ADR-010. The original Decision text in those ADRs
  is not rewritten; the amendment notes the cross-domain change.

## Alternatives considered

| Alternative | Pros | Cons | Why not chosen (or "co-equal — user selects") |
|---|---|---|---|
| **A: Tier 1.5 allow-list extension (verification-layer-wide)** | Preserves Skill's operational value; closed allowlist remains CI-enforceable; pairing rule prevents Tier 1.5-only citations; uniform rule across verification-layer domains | Maintenance surface grows; pairing rule shifts some load to topic-scope judgement | **Selected by user 2026-05-09 with verification-layer-wide scope.** This is the accepted decision |
| **A-narrow: Tier 1.5 scoped to compliance-checklist only** | Smallest surface area change; ADR-008/010 untouched | Asymmetry: verification-layer Critics still reject EDPB Guidelines while compliance-checklist accepts them; cross-domain operators see two rules | Considered during selection; user explicitly opted for verification-layer-wide scope to avoid the asymmetry |
| **D: Statute-only tightening with `## See also` demotion** | Bright-line verifiability; verification-layer-wide consistency at the bright-line; no new tier | Skill loses direct citation backing for operational thresholds; readers route around indirection | Preserved verbatim under `## Counter-proposal` per ADR-010 design-domain protocol; re-evaluation trigger stated there |
| **B: Per-document tier label** (every cited regulator document gets its own tier marker chosen by the Skill at output time) | Maximum granularity | Defeats the purpose of an invariant — the rule becomes "case-by-case judgment" which is exactly what Invariant 2 was meant to remove | The verification-layer's value is *closure*, not granularity |
| **C: Status quo — leave the ambiguity in place** | Zero work | The ambiguity itself is a defect; ADR-011 already records it as such | The user has explicitly asked us to resolve, not defer further |
| **E: Split ADR-013 into two parallel ADRs (one per option), accept the one that holds up under follow-up review** | Maximum reversibility | Doubles the ADR surface for one decision; the design-domain protocol already supports preserving the rejected option in one ADR (ADR-012 precedent) | One ADR with two options + counter-proposal is the established pattern; splitting adds files without adding clarity |

## References

- [`.claude/meta/adr/008-research-verification-layer.md`](./008-research-verification-layer.md)
  — establishes primary-source-only citation as the verification-layer's
  load-bearing invariant. Not modified by this ADR.
- [`.claude/meta/adr/010-verification-layer-generalization.md`](./010-verification-layer-generalization.md)
  — defines the design-domain Critic protocol that produces ADR-013's
  `## Counter-proposal` section. Not modified by this ADR.
- [`.claude/meta/adr/011-compliance-checklist-skill.md`](./011-compliance-checklist-skill.md)
  — introduces Invariant 2 in the compliance-checklist Skill and
  records the `## Known ambiguity` this ADR resolves.
- [`.claude/meta/adr/012-code-reviewer-dispatcher.md`](./012-code-reviewer-dispatcher.md)
  — closest precedent for the "Accepted with rejected alternative
  preserved verbatim under `## Counter-proposal`" pattern.
- [`.claude/skills/compliance-checklist/SKILL.md`](../../skills/compliance-checklist/SKILL.md)
  — current Invariant 2 text (lines 100–106).
- [`.claude/skills/compliance-checklist/jurisdictions/EU.md`](../../skills/compliance-checklist/jurisdictions/EU.md)
  — current EDPB Guidelines citations at lines 52–54 and 73–76.
- [`.claude/skills/compliance-checklist/jurisdictions/JP.md`](../../skills/compliance-checklist/jurisdictions/JP.md)
  — current PPC reference at lines 81–84.
- [`.claude/skills/compliance-checklist/jurisdictions/platform.md`](../../skills/compliance-checklist/jurisdictions/platform.md)
  — current Apple Privacy Manifest reference at lines 38–40 and
  Google Play SDK Index reference at line 69.
- [`.claude/skills/verification-layer/research/checklist.md`](../../skills/verification-layer/research/checklist.md)
  — primary-source allowlist; Tier 1 reference for both options.
- EUR-Lex (https://eur-lex.europa.eu/) — primary source for GDPR
  consolidated text; Art. 70 defines EDPB's advisory role.
- e-Gov 法令検索 (https://elaws.e-gov.go.jp/) — primary source for
  個人情報保護法; §132–§149 define PPC's enabling authority.
- California Legislative Information
  (https://leginfo.legislature.ca.gov/) — primary source for CCPA;
  §1798.185 delegates rulemaking authority to CPPA.
