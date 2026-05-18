<!--
Per-task ownership-progress file. Copy this to `<task-id>.md` (e.g.
`R1b.md`, `R4.md`) at the START of a worker session. Rules:

- Created + owned + written by EXACTLY ONE worktree. NEVER read or
  written by another worktree → zero merge conflict by construction.
- This is the worker's ONLY mutable progress surface. A worker NEVER
  edits `.claude/CLAUDE.md`, `roadmap.md`, `tech-debt.md`, or
  `completed-archive.md` (orchestrator-exclusive).
- `Declared write-set` is the disjointness contract. The orchestrator
  proves pairwise-disjointness across a batch from these declarations
  BEFORE dispatch, and verifies `git diff --name-only` stayed inside
  the declared set AFTER the run. Writing outside the declared set is
  a protocol violation the orchestrator must catch pre-consolidation.
- `Status` is a closed enum: PLANNED → IN_PROGRESS → BLOCKED →
  READY_FOR_CONSOLIDATION → CLOSED. The worker advances it to at most
  READY_FOR_CONSOLIDATION; only the orchestrator sets CLOSED (Stop-Loss
  gate: orchestrator validates before close).
- On CLOSE the orchestrator folds `Result summary` into
  `completed-archive.md` (or `tech-debt.md`), then `git rm`s this file
  in the same consolidation commit. The per-task file is ephemeral;
  the archive is permanent.
- New i18n keys: do NOT edit the 3 shared i18n files. Ship a sibling
  `<task-id>.i18n.tsv` (cols: key⇥en⇥ja⇥xcstrings_value). The
  orchestrator splices + runs `verifyI18nKeys` at consolidation.
- EN-only (matches the `docs/en/phase/**` ledger convention).
-->

# <task-id> — <one-line title>

## Status

`PLANNED`  <!-- PLANNED | IN_PROGRESS | BLOCKED | READY_FOR_CONSOLIDATION | CLOSED -->

<!-- If BLOCKED, add:
Blocked-reason: <rebase-conflict | red-tests | scope-question | upstream | other>
Blocked-detail: <exact conflicting paths / failing target / question>
-->

## Scope

<!-- 2–5 sentences. What this task delivers and its explicit boundary.
Name what is deliberately OUT of scope so the write-set stays honest. -->

## Declared write-set

<!-- The disjointness contract. List every path/glob this task may
write. Be exhaustive and conservative — under-declaring causes a
false-disjoint batch. Reference the CLAUDE.md hot-file inventory: if
any hot file appears here AND in a sibling task's set in the same
batch, the orchestrator must serialize (or, for the 3 i18n files only,
apply the i18n-fragment rule). -->

- `shared/src/commonMain/kotlin/.../<...>`
- `shared/src/commonTest/kotlin/.../<...>`
- `iosApp/iosApp/.../<...>`
- `docs/en/spec/<feature>.md`  <!-- only if strictly in this task's feature scope -->
- `docs/en/phase/tasks/<task-id>.md`  <!-- this file, always -->
- `docs/en/phase/tasks/<task-id>.i18n.tsv`  <!-- only if adding i18n keys -->

## ADR / Spec refs

<!-- ADRs this task implements/extends + the spec it must keep current
in the SAME commit (Workflow step 7). If editing an ADR revision-history
block, that ADR file is a hot file — confirm no sibling task touches the
same ADR this batch. -->

## Test delta

<!-- Planned: +N commonTest / +M XCUITest / +K Deno, locking which
regression surface. Final: actual counts + the running total. -->

## Result summary

<!-- Filled when Status → READY_FOR_CONSOLIDATION. Mirror the Completed-
bullet anatomy the orchestrator will fold into completed-archive.md:
what shipped, key design decisions, scope cuts (what/why/when-revisit),
test delta, review findings landed (CRITICAL→LOW), verification run
(gradle invariant block / ios-build / e2e as applicable), pushed SHA. -->

## Follow-ups

<!-- New Tech Debt / roadmap items this task surfaced. The orchestrator
moves these into tech-debt.md / roadmap.md at consolidation — the worker
does NOT edit those files. One line each. -->

## Task Result (orchestrator-consumed handoff block)

<!-- Emit this verbatim as the LAST thing the worker outputs. The
orchestrator parses it mechanically. Keep the field names exact. -->

```
TASK RESULT
TASK-ID: <task-id>
STATUS: <READY_FOR_CONSOLIDATION | BLOCKED>
FILES CHANGED: <git diff --name-only, newline-listed>
WRITE-SET RESPECTED: <YES | NO — list any out-of-declared-set paths>
TEST RESULTS: <pass/fail summary + running total>
I18N FRAGMENT: <none | path to <task-id>.i18n.tsv, N keys>
PUSHED: <SHA on origin/main | NOT PUSHED — reason>
REVIEW: <all CRITICAL→LOW landed | outstanding: ...>
RECOMMENDATION: <SHIP | NEEDS WORK | BLOCKED>
NOTES: <one-liner for the orchestrator; e.g. rebase-conflict paths>
```
