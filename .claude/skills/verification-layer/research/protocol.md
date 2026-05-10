# Research domain — protocol

> Loaded on demand from the verification-layer SKILL.md.
> Shared invariants (Generator/Critic, primary-source-only, severity,
> tool families) live in `../SKILL.md`. This file states the
> research-domain specifics: Tier table, GAN protocol, Pre/Post
> checklists, escalation contract, and output format.

This domain corresponds to ADR-008.

## When to invoke

- Generator (`docs-researcher`) is about to return findings that an
  architect, implementer, security-reviewer, or any other agent will
  rely on for a decision (technology choice, API usage, version pin,
  breaking-change assessment).
- Reviewing an ADR draft whose rationale cites external sources.
- The orchestrator is routing a research output to multiple downstream
  agents and wants the result verified once.

Do **not** invoke for:

- Style or convention lookups where the answer does not change a
  design decision (T3 below — Generator self-check is enough).
- Re-reading the project's own code or commit history (not external
  research).
- Casual conversational lookups during pair-programming.

## Configuration

`.claude/verification.yml` → `research:` section:

```yaml
research:
  enabled: true               # default-on when the file is present
  max_iterations: 2
  default_tier: T2
  external_facts_only: true
```

If the whole file is absent, the research domain is **inert** (the
Skill exits silently — matching ADR-008's original behaviour).

## Tier table

The Generator declares a Tier on every external-research output. The
orchestrator can escalate upward (T3 → T2, T2 → T1) but never downward.

| Tier | Scope | Mechanism |
|---|---|---|
| T1 | Breaking changes, auth, security-sensitive APIs, crypto primitives | Two-stage (independent re-research + primary-source check) + GAN up to 2 rounds |
| T2 | Public API arguments, return types, defaults, common library behaviour, version-specific feature availability | Two-stage; Critic runs once, no iteration unless CRITICAL/HIGH |
| T3 | Idiomatic style, common usage examples, widely-known patterns | Generator self-check against one primary source; no Critic |

If unsure between Tiers, choose the higher one.

## Protocol (T1 / T2)

```
[1] Generator (docs-researcher)
    - Freshness-safe query (existing protocol; see docs-researcher.md)
    - Produce draft using verification-review-template.md
    - Declare Tier
    - Record tool log + citation list

[2] Two-stage review (parallel)
    [2a] Critic (research-critic)
         - Receive Generator's tool log + citations
         - Use a DIFFERENT tool family (see SKILL.md §Tool families)
         - Cite at least one primary source the Generator did not use
         - Apply checklist.md (10 items)
         - Output findings with severity (CRITICAL / HIGH / MEDIUM / LOW)
    [2b] Generator self-check (docs-researcher)
         - Re-run with version-pinned primary source
         - Confirm or revise each claim against that source

[3] Verdict
    - LOW only or no findings: PASS
    - MEDIUM/HIGH/CRITICAL present: REQUEST CHANGES → [4]

[4] GAN iteration (T1 only, T2 only if CRITICAL/HIGH remain)
    - Generator addresses findings, produces v2
    - Critic re-reviews
    - Up to max_iterations (default 2). After that, escalate.
    - For T2: if remaining findings are MEDIUM/LOW only, terminate
      (do not iterate). Iteration is reserved for CRITICAL/HIGH.

[5] Escalation (if iterations exhausted with findings remaining)
    - Orchestrator presents both positions to the user, OR
    - Mark the claim UNVERIFIED: and pass it to downstream agents
      with that label preserved.
```

T3 collapses to step [1] plus a single primary-source check inside
the Generator. Critic is not invoked.

## Pre-research checklist (Generator)

Before opening Context7 / WebFetch / `gh search`:

- [ ] **Tier declared.** T1 / T2 / T3, decided by the impact of the
  answer on downstream work.
- [ ] **Question restated in primary-source language.** If the question
  uses framework jargon, translate it to the terms the official docs
  use (e.g. "Server Actions" not "server-side functions").
- [ ] **Version pinned.** State the version the answer must apply to.
  If unknown, the answer is at best provisional — surface this.
- [ ] **Primary source identified.** Where will the authoritative
  answer come from? If you cannot name a primary source for this
  question, the question is ill-posed; reframe it.

## Post-research review checklist (Critic)

See [checklist.md](./checklist.md) for the full 10-item version. The
core five:

- [ ] **Primary source cited?** At least one citation must be from
  the primary-source allowlist.
- [ ] **Version-pinned URL?** Tagged docs URL, commit SHA, or release
  page — not a moving "latest" pointer.
- [ ] **Generator's tool log inspected?** The Critic must use a
  different tool family than the Generator did.
- [ ] **Conditional claims flagged?** "Works only when X" claims need
  the X stated explicitly.
- [ ] **Retrieval date present?** Every citation carries a
  `(retrieved: YYYY-MM-DD)` annotation.

## Output format

Generator and Critic both write into
[`.claude/templates/verification-review-template.md`](../../../templates/verification-review-template.md).
Minimum required fields are reproduced in the template; the research
domain uses the `Tier:` field and the `Findings table` columns
documented there.

## Escalation contract

When `max_iterations` is reached and findings remain, the orchestrator
receives a structured report:

```markdown
## Escalation: verification-layer / research

- Topic: <what was being researched>
- Final Tier: T1 | T2 | T3
- Iterations consumed: 2/2
- Generator final position: <one sentence>
- Critic final position: <one sentence>
- Disagreement: <where they differ, with both citations>
- Recommended action:
  - [ ] Ask user for tie-break
  - [ ] Mark claim UNVERIFIED: and proceed
  - [ ] Block downstream until resolved
```

The orchestrator picks one of the three actions and records it in
the session output.
