# ADR-008: Research verification layer — adversarial review with primary-source-only Critic

## Status

Accepted — 2026-05-08

## Context

The template's `docs-researcher` agent (ADR-006 §Triage protocol, plus
the Anthropic-docs verification chain in ADR-007) already executes
freshness-safe queries and verifies claims against documentation.
However, the freshness protocol guards *query rot* — it does not guard
the *correctness* of the retrieved answer. Three failure modes have
appeared in practice:

1. **Confirmation echo.** A single research pass commits to the first
   plausible answer. If the answer is subtly wrong (version mismatch,
   conditional API, deprecated method), the implementer downstream
   inherits the mistake and the cost surfaces only at build/test time.
2. **Secondary-source drift.** Blog posts, Q&A sites, and AI-generated
   summaries lag behind primary docs by months. A research pass that
   leans on these sources can return an answer that is internally
   coherent but contradicts the current official documentation.
3. **Hallucinated APIs.** When a library version is recent or
   infrequently used, the model invents argument names, default values,
   or method signatures that compile in prose but not in code.

A naive "review the research" loop has its own failure mode:
**resonance**. If a Reviewer (Critic) consults the same secondary
sources the original researcher used, both share the same blind spot
and the loop converges on a wrong answer with high confidence.

The agent-team council (architect, docs-researcher, code-reviewer,
orchestrator) deliberated four mechanism strengths and converged on a
hybrid: **two-stage independent re-research with primary-source-only
citation**, plus **bounded GAN-style iteration** with explicit
escalation when consensus does not hold.

## Decision

Introduce a Skill at `.claude/skills/research-verification/` plus a
new `research-critic` agent. External research that informs a
decision (architecture, library selection, API usage, version
constraints) runs through this layer before the result is consumed by
downstream agents.

### Principles

1. **Generator and Critic are separate agents.** `docs-researcher`
   stays as the Generator (initial research, freshness-safe queries,
   `ours-vs-upstream` triage). `research-critic` is a new agent whose
   sole responsibility is adversarial review. Separation defeats
   confirmation echo more reliably than self-critique inside a single
   agent.
2. **Critic citations must be primary sources.** The Critic must cite
   at least one source the Generator did not use. That source **must**
   be a primary source (official documentation, vendor GitHub README,
   CHANGELOG, type definitions, RFC/W3C spec, official API reference,
   or MDN for Web standards). Secondary sources (blogs, Q&A sites, AI
   summaries, tutorials, cached snippets) are explicitly disallowed as
   the Critic's independent citation. Rationale: secondary sources
   lag primary sources and the Critic's whole purpose is to catch the
   lag. Permitting them defeats the mechanism.
3. **Bounded iteration.** Maximum 2 GAN rounds (Generator → Critic →
   Generator → Critic). After round 2, if findings remain, escalate
   to the orchestrator with both positions stated; the orchestrator
   either asks the user or marks the claim `UNVERIFIED:` and proceeds
   with that label visible downstream.
4. **Tier-declared scope.** The Generator declares a Tier on every
   external-research output. T1 (breaking changes, auth, security):
   full two-stage + GAN. T2 (API arguments, return types, common
   library behaviour): two-stage only, single Critic round. T3
   (style, usage examples, generally-known patterns): Generator
   self-check against primary source, no Critic. The Tier is a
   declaration, not a permission — the orchestrator can override it
   upward but never downward.
5. **Resonance prevention by construction.** The Critic receives the
   Generator's tool log and citation list as input and is instructed
   to use a different tool family (e.g., if Generator used Context7,
   Critic uses direct URL fetch on the vendor docs site or `gh`
   against the vendor's repo). Same source family + same query is
   forbidden.
6. **Skill is the protocol; agents are the actors.** The Skill at
   `.claude/skills/research-verification/SKILL.md` defines the
   protocol, checklist, and output format. The agents (`docs-researcher`,
   `research-critic`, `orchestrator`) reference the Skill at their
   trigger points. CLAUDE.md gets one paragraph in Workflow §3 plus
   one row in the Agent Team table — no protocol detail in CLAUDE.md
   itself, consistent with ADR-007.
7. **Opt-out via config.** `.claude/research-verification.yml` controls
   `enabled` (default `true`), `max_iterations` (default `2`), and
   `default_tier` (default `T2`). Adopters who want lighter-weight
   workflows can set `enabled: false` and the entire layer becomes
   inert; agents continue working without research-verification, with
   no error.
8. **English-only Skill,** consistent with ADR-007 and the existing
   `.claude/meta/references/*.md` pattern.

### Protocol order

The research-verification layer composes with the existing protocols:

1. **Freshness-safe query construction** (`docs-researcher` existing
   protocol).
2. **Triage** (ADR-006 ours-vs-upstream 3-step protocol) — runs only
   for defect-investigation requests, not for design or library-
   selection requests.
3. **Tier declaration** by the Generator on the research output.
4. **Critic review** (this layer) for T1 and T2 outputs.
5. **Orchestrator verdict** — accept, request changes, or escalate.

Steps 1–3 happen inside `docs-researcher`. Step 4 dispatches to
`research-critic`. Step 5 returns to the orchestrator. Steps 1 and 2
are not modified by this ADR.

### Tier-confirmation guardrail

Generators declare Tier with no second pair of eyes. Silent
under-classification on the highest-risk path (T1 declared as T2)
halves the verification cost where it is most needed. The orchestrator
applies a small allowlist of risk keywords — `auth`, `authn`, `authz`,
`crypto`, `breaking change`, `migration`, `CVE`, `security`,
`permission`, `token` — and confirms the declared Tier when a research
topic contains any of them. If in doubt, escalate to T1. This is a
judgement-based guardrail, parallel to the primary-source allowlist —
not a heuristic auto-classifier.

### Tier definitions

| Tier | Scope | Mechanism |
|---|---|---|
| T1 | Breaking changes, authentication/authorization, security-sensitive APIs, cryptographic primitives | Two-stage (independent re-research + primary-source check) + GAN up to 2 rounds |
| T2 | Public API arguments, return types, default values, common library behaviour, version-specific feature availability | Two-stage; Critic runs once, no iteration unless CRITICAL/HIGH severity |
| T3 | Idiomatic style, common usage examples, widely-known patterns | Generator self-check against one primary source; no Critic |

### Severity classification (Critic findings)

Adapted from `code-reviewer`'s severity model:

| Level | Definition | Action |
|---|---|---|
| CRITICAL | Source does not exist (404, fabricated URL), claim is hallucinated | Reject, full re-research |
| HIGH | Version mismatch, unstated breaking change, primary-source contradiction | Re-research with version-pinned primary source |
| MEDIUM | Only secondary sources cited by Generator; primary-source confirmation missing | Generator must add primary-source citation |
| LOW | Date missing on citation, ambiguous wording | Annotate; does not block |

Round terminates when remaining findings are LOW only, or `max_iterations`
is reached.

### Critic's primary-source allowlist

The Critic's independent citation must come from one of:

- The vendor's official documentation site (e.g., `nextjs.org/docs`,
  `pub.dev`, `flutter.dev/docs`, `pkg.go.dev`)
- The vendor's official GitHub repository: README, CHANGELOG, source
  code, type definitions, official examples
- The vendor's official issue tracker (for known-bug confirmation)
- RFC, W3C, ECMA, or equivalent standards bodies
- MDN Web Docs (for Web platform APIs only)
- Language-runtime official references (e.g., `docs.python.org`,
  `pkg.go.dev/std`, `doc.rust-lang.org/std`)

Explicitly **not** acceptable as the Critic's independent source:
Stack Overflow, Qiita, Zenn, dev.to, Medium, personal blogs, AI
summary sites, cached snippets, screenshots, tutorial repositories
that are not the vendor's own.

### Provided artifacts

| Path | Role |
|---|---|
| `.claude/skills/research-verification/SKILL.md` | Entry point, protocol, Pre/Post checklists, navigation |
| `.claude/skills/research-verification/checklist.md` | Critic checklist (10 items) and primary-source allowlist |
| `.claude/skills/research-verification/failure-modes.md` | Five typical research-error patterns with mitigations |
| `.claude/agents/research-critic.md` | New Critic agent definition |
| `.claude/templates/research-review-template.md` | Output artifact format (Generator claims + Critic findings + verdict) |
| `.claude/research-verification.yml.example` | Opt-out config template (`enabled`, `max_iterations`, `default_tier`) |

### Modified artifacts

| Path | Change |
|---|---|
| `.claude/CLAUDE.md` | One paragraph in Workflow §3, one row in Agent Team table |
| `.claude/agents/docs-researcher.md` | Output format aligned with Critic input contract; Tier declaration required |
| `.claude/agents/orchestrator.md` | Skill trigger conditions and escalation flow |

### Out of scope (deliberately)

- A separate Critic for code review (that is `code-reviewer`'s
  domain; reusing the agent across both contexts dilutes both).
- Automatic Tier inference. The Generator declares Tier; the
  orchestrator can escalate. Heuristic auto-classification was
  rejected as adding a second source of error.
- A linter that enforces primary-source-only citations. The check is
  judgement-based (is `nextjs.org/learn/...` a primary or secondary
  source?). Human review remains in the loop.
- Caching of verified research across sessions. Cross-session memory
  introduces drift risk that cancels the freshness protocol's
  guarantees.

## Consequences

### Positive

- Confirmation echo and secondary-source drift are both addressed by
  the same mechanism (primary-source-only Critic citation).
- Hallucinated APIs surface within the research step rather than at
  build/test time, where the cost of correction is much higher.
- Tier declaration keeps the cost proportional to the risk: T3 work
  pays a single primary-source check; only T1 work pays the full
  two-stage + GAN cost.
- The mechanism is opt-out via one config line; adopters who do not
  want it pay nothing.

### Negative

- T1 research costs roughly 2-3× a single research pass. For projects
  doing heavy library evaluation this is a real budget hit. Mitigation:
  Tier declaration ensures T1 is invoked only where the cost is
  justified.
- The primary-source-only constraint can fail when a vendor has
  genuinely poor documentation. In that case the Critic must escalate
  to the orchestrator rather than relax to secondary sources, which
  surfaces a real research blocker rather than papering over it.
- Resonance is prevented by construction but not eliminated. If
  Generator and Critic both rely on the same primary source and that
  source is itself wrong, the loop will agree. This is unavoidable
  without a third independent oracle.

### Neutral

- The Skill is English-only. Critic findings emitted to the user are
  in the project's language; only the Skill protocol is English.
- The `research-verification.yml` config file ships as
  `.example` — adopters opt in to creation, similar to
  `.github/workaround-tracker.yml`.

## Alternatives considered

| Alternative | Pros | Cons | Why not chosen |
|---|---|---|---|
| Self-critique inside `docs-researcher` (no new agent) | One fewer agent, simpler topology | Confirmation echo not addressed; agent's prompt is already long | Resonance is the dominant failure mode and self-critique cannot break it |
| Permit any source for Critic's independent citation (incl. blogs) | Wider source pool, easier to satisfy | Defeats the entire mechanism — secondary sources lag primary, which is exactly what the Critic must catch | Direct user feedback: "二次情報は最新の情報でない可能性が非常に高い" |
| Unbounded iteration until Critic returns zero findings | Highest theoretical accuracy | Cost runaway; pathological cases never terminate; humans cannot intervene cleanly | Bounded iteration with explicit escalation matches how the agent team already operates (ADR-006) |
| Single Tier (apply T1 to all research) | Simplest scope | 3× cost on every research pass, including trivial style lookups | Tier declaration matches risk to mechanism; user explicitly accepted higher cost where justified |
| `code-reviewer` doubles as Critic | Reuses an existing agent | Code review and research review are different objects (diff vs. claim+citation) and the prompts diverge | Separation matches the agent-boundary principle elsewhere in the team |
| All inline in `docs-researcher` (no Skill) | Fewest files | Hard-codes a protocol that should evolve as we learn what fails in practice | Skill+agent split (per ADR-007 pattern) keeps the protocol updatable without rewriting the agent |

## References

- ADR-006 — upstream-workaround-tracking; the triage protocol it
  defines is upstream of this layer (Generator's input).
- ADR-007 — claude-md-authoring Skill; this ADR follows the same
  Skill+agent partition and the same CLAUDE.md-minimization rule.
- Council deliberation 2026-05-08 — architect, docs-researcher,
  code-reviewer, orchestrator reviewed the proposal in parallel and
  converged on the hybrid (two-stage + GAN) plus the primary-source
  constraint. The user explicitly tightened the Critic's independent
  source rule to primary-only after the council converged.
- `.claude/skills/research-verification/SKILL.md` — protocol and
  Pre/Post checklist.
- `.claude/skills/research-verification/checklist.md` — Critic
  checklist and primary-source allowlist.
- `.claude/skills/research-verification/failure-modes.md` — typical
  research-error patterns informing the Critic checklist.

## Amendment — 2026-05-08 (per ADR-010)

ADR-010 generalised the research-verification layer into a unified
`verification-layer` Skill with three domains. The research domain
in this ADR is preserved verbatim in semantics; only the file
locations moved. **Path translation table for any reader looking up
a path mentioned above:**

| Path in ADR-008 body | Current location |
|---|---|
| `.claude/skills/research-verification/SKILL.md` | `.claude/skills/verification-layer/research/protocol.md` (research-domain protocol) and `.claude/skills/verification-layer/SKILL.md` (shared invariants) |
| `.claude/skills/research-verification/checklist.md` | `.claude/skills/verification-layer/research/checklist.md` |
| `.claude/skills/research-verification/failure-modes.md` | `.claude/skills/verification-layer/research/failure-modes.md` |
| `.claude/research-verification.yml.example` | `.claude/verification.yml.example` (with `research:` section) |
| `.claude/research-verification.yml` | `.claude/verification.yml` |
| `.claude/templates/research-review-template.md` | `.claude/templates/verification-review-template.md` (now per-domain sectioned) |

ADR-010 — see
`.claude/meta/adr/010-verification-layer-generalization.md` — is the
authoritative reference for the cross-domain abstraction. ADR-008's
body remains unchanged because the research-domain decision and its
rationale are still the canonical record for that domain.

## Amendment — 2026-05-09 (per ADR-013)

ADR-013 introduced **Tier 1.5 — issuing-regulator official
interpretive guidance** as a verification-layer-wide extension to the
primary-source-only citation rule established by this ADR. The
Tier 1.5 allowlist (EDPB Guidelines, PPC ガイドライン/Q&A/通達, CPPA
Regulations, Apple Privacy Manifest spec, Google Play SDK Index) is
admissible across the research domain only when the question under
review intersects a delegated-regulator domain, and only paired with
a Tier 1 citation on the same item.

The research-domain protocol files have been updated to reference the
Tier 1.5 allowlist:

- `.claude/skills/verification-layer/research/checklist.md` — adds a
  `## Tier 1.5` section after the primary-source allowlist.
- `.claude/skills/verification-layer/SKILL.md` — shared invariant 3
  now references ADR-013 as the Tier 1.5 single source of truth.

ADR-008's original Decision text is unchanged. Tier 1.5 admits a
narrow, closed extension to the Critic's primary-source allowlist for
delegated-regulator topics; it does not relax the rule for general
framework / library / language research, which continues under
Tier 1 only.

See ADR-013 for the closed allowlist, pairing rule, authority floor,
stale-guidance handling, and re-evaluation triggers.
