# Repository Policy

> 翻訳: [docs/ja/repo-policy.md](../ja/repo-policy.md)

This document describes the branch protection rules and contribution policy for the `knit-note` repository.

## Quick reference

| Topic | Policy |
|---|---|
| Default branch | `main` |
| Direct push to `main` | **Admin only** (Owner = `b150005`) — bypassed via Repository Role |
| Force push, branch deletion | **Forbidden** for everyone (no bypass) |
| Merge commits | **Forbidden** — squash or rebase only (linear history enforced) |
| PR approval requirement | At least 1 approval; stale approvals dismissed on push; last-push approval required; all review threads must be resolved |
| Required CI status checks | `Shared Module (Lint + Test + Coverage + Build)`, `Android (Build + Test)`, `iOS (Build + Test)`, `Android E2E (Maestro)`, `iOS E2E (Maestro)`, `CodeQL Analysis (swift, macos-latest, manual)` |
| Bypass actor | Repository Admin role (Owner only on a personal account) |

The active ruleset is `main-strict` (id `15581036`). Configuration: <https://github.com/b150005/knit-note/rules/15581036>.

## Why these rules exist

The repository is **public** and the project ships to end-users via TestFlight and Google Play. The closed beta starts in Phase 39 with external testers. The rules are designed for these threat models:

1. **Accidental destructive action by Owner** — force-push, branch deletion, history rewriting are all blocked by structural rules with no bypass.
2. **External fork PRs from contributors** — contributors can open PRs from forked repositories but only the Owner can merge them. Status checks must pass before merge.
3. **Future Write-role collaborator scenarios** — even if a future contributor is granted Write role, they cannot bypass merge gates or push directly. Only Admin role can bypass.
4. **Compromised Owner account** — an attacker with the Owner's credentials could still bypass everything (Admin), but the audit trail of any bypass is preserved in GitHub's activity log.

## Rule breakdown

### `update` rule
Blocks all updates to the `main` branch ref. This includes:
- Direct `git push origin main` from CLI
- Merging a PR via the GitHub UI (technically also a push)
- Any GitHub API call that writes to the `main` ref

**Bypass actor**: Repository Admin role (Owner). Only the Owner can perform these operations; everyone else (Write role, fork contributors) is blocked.

### `non_fast_forward` rule
Forbids force-push (`git push --force`). No bypass — even the Owner cannot force-push to `main`. This protects against accidental history rewriting.

### `deletion` rule
Forbids deletion of the `main` branch. No bypass.

### `required_linear_history` rule
Forbids merge commits. PRs must be merged via squash or rebase, producing a linear history. Combined with `allowed_merge_methods: ["squash", "rebase"]` on the `pull_request` rule.

### `pull_request` rule
| Parameter | Value | Reason |
|---|---|---|
| `required_approving_review_count` | `1` | At least one approval required. Currently solo, so the Owner must self-approve via bypass on their own PRs, but this enforces the gate if a Write-role collaborator is added later. |
| `dismiss_stale_reviews_on_push` | `true` | Pushing new commits to a PR branch invalidates prior approvals. |
| `require_last_push_approval` | `true` | An approval issued before the most recent commit does not count — fresh approval required after any new push. |
| `required_review_thread_resolution` | `true` | All conversation threads on the PR must be marked resolved before merge. |
| `require_code_owner_review` | `false` | No `CODEOWNERS` file currently in the repository. |
| `allowed_merge_methods` | `["squash", "rebase"]` | Merge commits forbidden (matches `required_linear_history`). |

### `required_status_checks` rule
The following CI jobs must complete with status `success` before a PR can be merged:

| Status check | Workflow file | Job key |
|---|---|---|
| Shared Module (Lint + Test + Coverage + Build) | `.github/workflows/ci.yml` | `shared-checks` |
| Android (Build + Test) | `.github/workflows/ci.yml` | `android` |
| iOS (Build + Test) | `.github/workflows/ci.yml` | `ios` |
| Android E2E (Maestro) | `.github/workflows/e2e.yml` | `android-e2e` |
| iOS E2E (Maestro) | `.github/workflows/e2e.yml` | `ios-e2e` |
| CodeQL Analysis (swift, macos-latest, manual) | `.github/workflows/security.yml` | `codeql` (swift matrix entry) |

`strict_required_status_checks_policy` is `false` — the PR branch does not need to be up-to-date with `main` for the checks to be considered fresh. This avoids rebase-loop friction on long-running PRs.

**Note on `CodeQL Analysis (java-kotlin, ubuntu-latest, manual)`** — this check is intentionally *not* required because of an upstream extraction issue with Kotlin builds (see `CLAUDE.md` → "CI Known Limitations"). The job is configured with `continue-on-error: true` in the workflow so it does not block the workflow run, and is excluded from required checks until the underlying issue is resolved.

## Bypass mechanics

The `main-strict` ruleset has exactly one bypass actor:

```json
{
  "actor_type": "RepositoryRole",
  "actor_id": 5,
  "bypass_mode": "always"
}
```

- `actor_id: 5` corresponds to the **Repository Admin role**.
- `bypass_mode: "always"` means the actor can bypass any rule in this ruleset at any time.
- On a personal-account repository, **only the Owner holds the Admin role by default**. Granting Admin to another user requires an explicit collaborator invitation by the Owner.
- Therefore, on the current repository configuration, only `b150005` (the Owner) can bypass.

**To preserve the "Admin = Owner only" invariant**, do not grant Admin role to any future collaborator. Use Write or lower for contributors.

## Workflow examples

### As the Owner: direct push for trivial changes

```bash
git add .
git commit -m "docs: fix typo"
git push origin main   # Bypassed: allowed via Admin
```

This bypasses all rules. Use sparingly — for trivial changes (typos, doc fixes, etc.) where PR review would be ceremony.

### As the Owner: PR-based workflow for substantial changes

```bash
git checkout -b feat/new-feature
# ... make changes ...
git push origin feat/new-feature
gh pr create --base main --head feat/new-feature
# Wait for CI to go green
gh pr review --approve  # Self-approve via bypass
gh pr merge --squash    # or --rebase
```

This goes through the full PR workflow — useful for changes that benefit from review surface area (large refactors, security-sensitive code, etc.).

### As an external contributor (fork PR)

1. Fork `b150005/knit-note` to your own account.
2. Push your changes to a branch on your fork.
3. Open a PR against `b150005/knit-note`'s `main` branch.
4. Wait for CI to pass.
5. The Owner reviews, approves, and merges.

External contributors cannot push to the `main` branch or merge any PR — only the Owner can.

### Emergency direct push as the Owner

```bash
git push origin main   # Always works for Admin role
```

The `update` rule applies to everyone, but the Admin role bypasses it. This means the Owner can always push directly when needed (e.g., reverting a broken commit, applying a critical security fix).

## How to inspect or modify the ruleset

```bash
# View the active ruleset
gh api repos/b150005/knit-note/rulesets/15581036 | jq

# List all rulesets on the repository
gh api repos/b150005/knit-note/rulesets | jq '.[] | {id, name, enforcement}'

# Disable temporarily (e.g., for a major migration)
gh api -X PUT repos/b150005/knit-note/rulesets/15581036 -f enforcement=disabled

# Re-enable
gh api -X PUT repos/b150005/knit-note/rulesets/15581036 -f enforcement=active

# Delete entirely
gh api -X DELETE repos/b150005/knit-note/rulesets/15581036
```

## Update history

| Date | Change | By |
|---|---|---|
| 2026-04-27 | Initial `main-strict` ruleset created (id `15581036`) | b150005 |
