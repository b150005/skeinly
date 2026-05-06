# Upstream Issue Triage Process

When the project hits an unexpected build, test, or runtime failure that
isn't reproduced by isolated changes in your scope, the failure is most
likely caused by an upstream library / SDK / tool. The discipline below
makes sure such failures are **researched against upstream trackers**
before being classified as a "CI Known Limitation" or quietly worked
around. It also ensures workarounds are tracked and removed when upstream
ships a fix.

This is a process spec — not a skill in the global `~/.claude/skills/`
sense. CLAUDE.md "Development Workflow" Step 0 references this file as
mandatory pre-classification reading.

## Why this exists

Without this discipline, "CI Known Limitations" accumulates entries that:

- Don't link to anything verifiable, so a future reader cannot tell
  whether the workaround is still needed.
- Hide a real upstream bug that someone else may have already fixed.
- Cause the project to maintain workarounds long past the upstream fix
  date, growing technical debt.

The 2026-05-06 Maestro `127.0.0.1:7001` triage was the prompting
incident: four upstream issues (mobile-dev-inc/Maestro #3218 / #3248 /
#3254 / #3137) directly described the symptom. Without searching upstream
first, we would have misdiagnosed it as a Skeinly-side packaging or
locale problem.

## When to apply

Apply this process when:

- A build / test / E2E flow / runtime path fails unexpectedly AND
- The failure is **not** reproduced by your isolated change scope (i.e.
  reverting your edits doesn't fix it; or a fresh checkout of `main`
  also fails) AND
- The failure mentions a specific library / SDK / tool symbol, port,
  bundle, or error class

Concretely: if the error mentions Maestro, Gradle, Kotlin, Xcode,
Supabase, RevenueCat, AGP, KMP plugins, ktlint, kover, xcodegen,
SwiftPM, etc. — search those projects' issue trackers FIRST. Only after
the search comes up empty should you classify the issue as Skeinly-side
or as a "CI Known Limitation" without an upstream reference.

## The three-stage process

### 1. DETECT — confirm the failure is upstream-shaped

Before searching, narrow down the suspect. Ask:

- Does the same input fail on a clean checkout? (rules out your edits)
- Does the same input fail in CI? (rules out host-only state — see the
  Maestro 2026-05-06 case where CI was green and host was red)
- What library / tool's symbol is in the stack trace, port, or error
  message? (this is the search target)
- What versions are involved? (OS, language, primary library, anything
  in the build chain) — these are search filters and reproduction
  signal you'll need

Document this triage triplet in your investigation log:
`{ symptom, scope, suspect-upstream }`.

### 2. RECORD — search the upstream tracker

Use `gh` for repos hosted on GitHub. The most useful entry points:

```bash
# Keyword search across open + closed
gh search issues --repo <owner>/<repo> "<keyword>" --json number,title,state,createdAt,updatedAt,url --limit 15

# Open-only (live problems)
gh search issues --repo <owner>/<repo> "<keyword>" --state open --limit 15

# Read full issue + comments
gh issue view <number> --repo <owner>/<repo> --json title,body,state,closedAt,stateReason,comments
```

Search keywords to try, in order of effectiveness:

1. The exact error string (e.g. `"Failed to connect to /127.0.0.1:7001"`).
2. The version + environment combo (e.g. `"Xcode 26.4"`).
3. The symbol or component name (e.g. `"MaestroDriverLib"`).
4. The general failure mode (e.g. `"iOS 26"`).

Read the **most recent open** issue first, then any **recently closed**
ones — closed-with-workaround issues often contain the same root cause
documented by the maintainer.

For each candidate match, record:

- Issue URL
- State (OPEN / CLOSED / merged-and-released-in-vX.Y)
- Date of last activity
- Whether your symptom matches (full / partial)
- Workaround if any, with caveat ("works in Maestro 2.0.10 only" etc.)

### 3. TRACK — record the entry + workaround discipline

Add a CLAUDE.md "CI Known Limitations" entry using this format:

```markdown
- **<short symptom name>**: <one-line description of what breaks>.
  - **Upstream**: [<repo>#<issue>](https://github.com/<owner>/<repo>/issues/<num>) (<state>, last-checked YYYY-MM-DD); related: #<n>, #<n>
  - **Affected scope**: <what fails — e.g. "host iOS E2E on Xcode 26.4 + Maestro 2.5.x">
  - **Workaround**: <description, or "none — failing this on host is accepted while CI gates the same work">
  - **Expected fix**: <upstream milestone / version / "no eta">
  - **Re-check by**: YYYY-MM-DD (default: monthly; sooner if upstream signals an imminent release)
```

The `Re-check by` date is load-bearing: it forces a deliberate touch
of the entry. When that date arrives, run the upstream search again,
update `last-checked`, and:

- If the upstream issue closed with a fix: ship a follow-up commit
  that **removes the workaround** AND the entry. Do not leave dead
  workarounds in the repo.
- If still open: bump `last-checked` and `Re-check by`.
- If status unclear: read recent comments + linked PRs.

Workarounds are explicitly temporary. They earn their place in the
codebase by being tied to a tracked upstream issue — when the issue
resolves, the workaround leaves. This contract is the entire point of
this process.

## Failure modes (anti-patterns to avoid)

- **"Quick local fix without searching"** — patching around the symptom
  without checking if upstream already has a tracking issue. Likely
  duplicates work, may diverge from upstream's intended fix.
- **"Tech Debt entry with no upstream link"** — only acceptable if no
  upstream tracker exists (e.g. proprietary SaaS without public issue
  log). In that case, document the absence explicitly: `**Upstream**:
  no public tracker; reported via vendor support ticket #...`.
- **"Workaround that outlives its issue"** — caught by the `Re-check by`
  rule. Anyone reviewing entries past their re-check date should
  re-verify upstream status.
- **"Multiple symptoms collapsed into one entry"** — if two symptoms
  have different upstream issues, they get separate entries. Don't
  bundle.

## When NOT to apply

- Failures clearly caused by your own edits (revert + retest first).
- Failures reproduced in your latest commit but not on `main` — that's
  a regression you authored, not an upstream problem.
- Failures that are clearly project-specific configuration (e.g. wrong
  env var, missing local file) — fix the configuration.

## Tooling roadmap

For now (2026-05-06): manual `gh search` + manual entry maintenance.
The number of CI Known Limitations entries with upstream links is small
enough that automation is yagni. Re-evaluate when the count exceeds
~10 entries — at that point a `scripts/check-upstream-issues.sh` script
parsing entry IDs and shelling out to `gh issue view` for status flips
becomes worth the engineering effort.

## See also

- CLAUDE.md "Development Workflow" Step 0 (entry point)
- CLAUDE.md "CI Known Limitations" (the catalog)
- CLAUDE.md "Tech Debt Backlog" (for non-CI-bounded items)
