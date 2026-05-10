# Verification Review: <topic>

> Output artifact for the verification-layer protocol
> (`.claude/skills/verification-layer/SKILL.md`). The Generator writes
> the top sections. The Critic appends to "Critic Findings" and
> "Verdict". Use the per-domain section that matches the artifact
> being verified — research, implementation, or design.

## Header (always)

- Domain: research | implementation | design
- Generator agent: <docs-researcher | implementer | architect>
- Critic agent: <research-critic | adversarial-implementer | architecture-critic>
- Tools used (Generator): <list>
- Tool family used (Critic): <different from Generator>
- Retrieved / Performed on: YYYY-MM-DD
- Topic: <one line — what is being verified>

---

## Research domain (use only when Domain: research)

### Generator

- Tier: T1 | T2 | T3
- Question: <what was asked, in primary-source language>
- Version pin: <e.g., "Next.js 14.2.x", or "unpinned — answer is provisional">

### Claims

| # | Claim | Source URL | Retrieved | Severity (Critic) |
|---|-------|-----------|-----------|-------------------|
| 1 | <what the Generator concluded> | <URL with version tag> | YYYY-MM-DD | <filled by Critic> |
| 2 | ... | ... | ... | ... |

### Generator self-check

For T3, this is the only verification step. For T1/T2, this runs in
parallel with the Critic.

- [ ] Each claim cites at least one primary source.
- [ ] Each citation URL is version-tagged or commit-pinned.
- [ ] Each citation has a retrieval date.
- [ ] No conditional claims left implicit.

---

## Implementation domain (use only when Domain: implementation)

### Header constraints

> User-library constraint: <none | "User pinned <library X>; alternatives 3-4 disabled">
> Level used: 1 | 2 | 3 | 4-blocked

### Spec / acceptance criteria summary

<one short paragraph reproducing the criteria the Critic implemented
against — not the Generator's diff>

### Behavioural delta table

| # | Test name | Generator output | Critic output | Severity |
|---|-----------|------------------|---------------|----------|
| 1 | <test id> | <result / error> | <result / error> | <CRITICAL/HIGH/MEDIUM/LOW> |
| 2 | ... | ... | ... | ... |

> If implementations agree on every observable, replace this table
> with the line: **"Agrees on all observable behaviour across N
> tests."** Do NOT leave the table empty without a statement.

### Performance delta (only when a user-visible threshold is crossed)

| Metric | Generator | Critic | Threshold | Crossed? |
|---|---|---|---|---|
| <e.g., p50 latency> | ... | ... | ... | yes/no |

### Verification-blocked note (only when Level: 4-blocked)

- Reason: alternative library/runtime requires environment changes.
- Candidate alternative: <name>
- Why it would be a useful comparison: <one sentence>
- Required to enable: <list — CLI, Docker, SDK, license, etc.>
- Fallback used: level <1|2|3 — what was actually compared, if anything>

---

## Design domain (use only when Domain: design)

### ADR under review

- ADR file: <path/to/NNN-title.md>
- Status: Proposed
- Architect: <agent name = architect>

### Selected rejected alternative

<name of the alternative — preferably the one most quickly dismissed
in the original ADR's Alternatives considered table>

### Counter-proposal location

The full counter-proposal is appended to the ADR draft itself under
a `## Counter-proposal` section while Status remains Proposed. This
artifact records seriousness-bar findings only — not the counter
content.

### Seriousness-bar findings (if any)

| # | Failure shape | Severity | Detail |
|---|---|---|---|
| 1 | <e.g., "fewer than 2 Positive bullets"> | MEDIUM | <which sub-section> |
| 2 | ... | ... | ... |

If the counter-proposal passes the seriousness bar with no findings,
write **"Seriousness bar PASS — counter-proposal ready for the
architect to respond to."**

---

## Critic Findings (all domains)

> Severity vocabulary applies across all three domains:
> CRITICAL / HIGH / MEDIUM / LOW. See `verification-layer/SKILL.md`
> §"Shared invariants" for the full table.

- **[<domain> / Claim #N | Test #N | Failure-shape #N]**: <SEVERITY> —
  <one-line reason>
  - Primary source (Critic): <URL with version tag, from
    primary-source allowlist>
  - Tool family used: <different from Generator>
  - Retrieved: YYYY-MM-DD
  - Detail: <what the primary source says vs. the claim, OR what
    the behavioural delta is, OR which seriousness-bar item failed>

## Verdict

- Round: <N>/<max_iterations>
- [ ] PASS (no findings, or LOW only)
- [ ] REQUEST CHANGES (MEDIUM/HIGH/CRITICAL findings; back to
      Generator for revision)
- [ ] ESCALATE (round limit reached, findings remain; orchestrator
      decides)

## Escalation block (only when ESCALATE)

- Topic: <one line>
- Domain: research | implementation | design
- Iterations consumed: <N>/<max_iterations>
- Generator final position: <one sentence>
- Critic final position: <one sentence>
- Disagreement: <where they differ, with both citations or
  behavioural traces>
- Recommended action:
  - [ ] Ask user for tie-break
  - [ ] Mark claim UNVERIFIED: / accept the verification gap and
        proceed
  - [ ] Block downstream until resolved

## Audit trail

- Generator log: <tool log for research; commit SHA for
  implementation; ADR diff for design>
- Critic primary-source allowlist consulted: yes
- Same-tool-family check: <Generator tools/approach> vs
  <Critic tools/approach> → distinct
