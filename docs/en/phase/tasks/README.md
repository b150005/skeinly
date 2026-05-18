# Per-task ownership-progress files

One file per parallel task, `<task-id>.md`, copied from
[`_TEMPLATE.md`](_TEMPLATE.md) at the start of a worker session.

**Invariant**: each file is created, owned, and written by **exactly one
worktree**, and **never read or written by another**. This is the
structural reason parallel worktrees do not merge-conflict on progress
state — the old conflict driver was CLAUDE.md's shared mutable sections;
those are now relocated to orchestrator-exclusive
[`roadmap.md`](../roadmap.md) / [`tech-debt.md`](../tech-debt.md) /
[`completed-archive.md`](../completed-archive.md), and live worker
progress is per-file here.

Lifecycle: `PLANNED → IN_PROGRESS → BLOCKED → READY_FOR_CONSOLIDATION →
CLOSED`. The worker advances to at most `READY_FOR_CONSOLIDATION`. The
orchestrator validates, folds `Result summary` into the archive, and
`git rm`s the per-task file in the consolidation commit. These files are
ephemeral; the archive is permanent.

Full protocol: [`.claude/CLAUDE.md`](../../../../.claude/CLAUDE.md)
`## Parallel-Worktree Workflow Protocol`.

`_TEMPLATE.md` and this README are the only permanent files in this dir;
everything else is a live or just-closed task.
