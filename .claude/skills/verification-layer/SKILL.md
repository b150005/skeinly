---
name: verification-layer
description: >
  Adversarial review protocol for external research that informs
  decisions. Pairs a Generator with a Critic that uses a different tool
  family and may cite only primary sources. Bounded GAN-style iteration,
  per-domain opt-in via .claude/verification.yml, and a shared severity
  vocabulary. Reference this Skill when an external-research artifact
  will be consumed by a downstream agent for a decision (architecture,
  library selection, API usage, version constraints, runner labels,
  SDK identifiers).

  Skill contents (Progressive Disclosure):
    SKILL.md                 — overview, shared invariants, navigator
    research/protocol.md     — research-domain protocol (Tier table, GAN)
    research/checklist.md    — Critic checklist (10 items) + allowlist
    research/failure-modes.md — five typical research-error patterns

  This Skill is research-domain only by design — Skeinly opted out of
  the implementation and design domains documented in the upstream
  ecc-base-template since the existing code-reviewer / architect agents
  already cover those review surfaces.
disable-model-invocation: true
arguments: []
---

# Verification Layer (Research Domain)

## Purpose

Stop wrong conclusions from reaching downstream agents. The research
domain catches the failure mode where a single WebFetch / docs lookup
returns hallucinated identifiers (runner labels, SDK versions, API
paths, function names) that are then reflected into code or config
without independent confirmation.

| Domain | Generator | Critic | What it catches |
|---|---|---|---|
| `research` | `docs-researcher` | `research-critic` | Confirmation echo, secondary-source drift, hallucinated APIs |

The 2026-05-09 incident where `macos-26-arm64` was committed based on a
single WebFetch (the canonical label is `macos-26`; cost ~2h CI queue
time and a forced fix-forward commit) is the canonical anti-example
this domain exists to prevent.

## Shared invariants

These are non-negotiable. A workflow that drops one of them is no
longer a verification-layer workflow.

1. **Generator-vs-Critic separation.** The agent that produces the
   artifact does not also adjudicate it.

2. **Different tool family.** The Critic uses a tool family disjoint
   from the Generator's wherever feasible. The enumerated families
   are below.

3. **Primary-source-only citation, with a Tier 1.5 allowance for
   issuing-regulator official interpretive guidance.** The Critic may
   cite only Tier 1 primary sources for any external claim. Tier 1.5
   guidance from the closed allowlist defined in [ADR-013] is
   admissible only when the topic under review intersects a
   delegated-regulator domain, and only paired with a Tier 1 citation
   on the same item. Secondary sources (blogs, Q&A sites, AI
   summaries, translations of primary sources, regulator informal
   output) are disqualifying. The Tier 1 allowlist lives in
   [research/checklist.md](./research/checklist.md); the Tier 1.5
   allowlist lives in [ADR-013].

   [ADR-013]: ../../meta/adr/013-invariant-2-source-tier-model.md

4. **Severity vocabulary.** All findings use the four-level severity
   table:

   | Severity | When to use |
   |---|---|
   | CRITICAL | Source 404 or fabricated; identifier does not exist on the resolved page |
   | HIGH | Version mismatch; cited identifier is right family but wrong specific value |
   | MEDIUM | Generator cited only secondary sources; Critic produced agreement on different inputs |
   | LOW | Citation date missing; minor style; non-blocking observation |

5. **Bounded iteration.** The Generator/Critic round trip is capped
   (`max_iterations`, default 2). After the cap, escalate to the
   orchestrator with the structured report described in
   [research/protocol.md §Escalation contract](./research/protocol.md).

6. **Opt-in.** The research domain is active when `.claude/verification.yml`
   is present and `research.enabled` is true. Absent file = inert.

## Tool families (resonance prevention)

The "different tool family" rule above requires an enumerated list. Two
calls are considered to have used the *same* family if they fall in the
same row.

| Family | Examples |
|---|---|
| Curated MCP docs lookup | Context7 MCP, plugin context7 variants |
| Direct vendor docs | WebFetch / direct URL fetch on vendor docs site |
| GitHub access | `gh` CLI, GitHub MCP, raw `github.com/<vendor>/<repo>` URLs at a tag |
| Web search | Generic web search (last resort; not acceptable as Critic's *only* family) |
| Vendor-specific MCP | Firebase MCP, dart MCP, Supabase MCP — for that vendor only |
| Local execution | Run the project's own test suite, profiler, or static analyser |

For Skeinly specifically, the `actions/runner-images` GitHub repository
README at a specific tag is the canonical tier-1 source for GitHub-hosted
runner labels — pinning a label without consulting it is the well-known
hallucination surface this domain exists to prevent.

## Configuration

`.claude/verification.yml` controls activation:

| Knob | File absent | File present, key absent | File present, key explicit |
|---|---|---|---|
| `research.enabled` | inert | `true` | as written |
| `citation_discipline.enabled` | **on** | `true` | as written |

Citation discipline is the exception that runs even without a config
file — its runtime cost is essentially zero per CI run and its failure
mode (silent secondary-source accumulation in load-bearing documents)
is severe.

See [`.claude/verification.yml.example`](../../verification.yml.example)
for the full template with annotated knobs.

## When to invoke

Per the research domain's protocol:

> If the artifact you are about to ship will inform a decision a
> downstream agent or human reviewer takes — invoke. If the artifact
> is a casual lookup or an internal scratch step — do not.

Casual conversational use, style lookups (T3 in the research domain),
and re-reading the project's own commit history are out of scope.

See [research/protocol.md](./research/protocol.md) for the full Tier
table and the trigger / non-trigger conditions.

## Cross-document citation discipline

ADR / spec / README files in this repo carry the same primary-source-only
citation rule. The `research-critic` agent and any future CI script
share the allowlist in
[research/checklist.md §Allowlist](./research/checklist.md) as the
single source of truth.

## Domain navigator

| Step | File |
|---|---|
| Start | [research/protocol.md](./research/protocol.md) |
| Critic checklist | [research/checklist.md](./research/checklist.md) |
| Failure patterns | [research/failure-modes.md](./research/failure-modes.md) |
| Generator agent | [`.claude/agents/docs-researcher.md`](../../agents/docs-researcher.md) |
| Critic agent | [`.claude/agents/research-critic.md`](../../agents/research-critic.md) |
| Output template | [`.claude/templates/verification-review-template.md`](../../templates/verification-review-template.md) |

## See also

- [`.claude/meta/adr/008-research-verification-layer.md`](../../meta/adr/008-research-verification-layer.md)
  — original research-domain rationale
- [`.claude/meta/adr/013-invariant-2-source-tier-model.md`](../../meta/adr/013-invariant-2-source-tier-model.md)
  — source tier model and primary-source allowlist
- CLAUDE.md `## Output Quality Standard` — the project-side rule that
  references this Skill ("Never trust a single WebFetch summary on
  identifier-precision claims")
