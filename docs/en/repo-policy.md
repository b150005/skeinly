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

## Security posture (self-hosted CI runner)

CI for this repository runs on a self-hosted GitHub Actions runner installed at `/Users/b150005/Development/Tools/actions-runner-knitnote/` on the maintainer's macOS host. The runner is registered as `b150005mac-host` with labels `[self-hosted, macos, apple-silicon, host]`. This section documents the threat model, applied mitigations, and deferred items.

### Threat model

The repository is **public**. The self-hosted runner executes as user `b150005` (admin-group member) on the maintainer's primary development Mac, with access to:

- The maintainer's home directory, including SSH keys, gh CLI tokens, browser data, and the macOS login keychain
- The maintainer's other Development repositories
- The local network and unrestricted internet egress

The most dangerous attack vectors against this configuration are:

1. **Arbitrary code execution via fork PR** — by default GitHub allows fork PRs to trigger `pull_request` workflow events, executing PR-branch code on the runner.
2. **Third-party action supply chain compromise** — actions referenced by mutable tag (`@v2`, `@v6`) can be re-pointed to malicious commits if the upstream maintainer account is compromised.
3. **Cross-run workspace poisoning** — the runner is non-ephemeral; an attacker who achieves one code-execution event can plant payloads in `_work/` or `~/.gradle/caches/` that execute in subsequent runs.
4. **Unscoped GITHUB_TOKEN** — workflows without an explicit `permissions:` block inherit the repository default; if that default is later widened, all workflows silently gain write capabilities.

### Applied mitigations

| Threat | Mitigation | Where |
|---|---|---|
| Fork PR arbitrary code execution | Repository Settings → Actions → "Fork pull request workflows from outside collaborators" set to **"Require approval for all outside collaborators"**. The maintainer must explicitly approve the first workflow run from any contributor before it dispatches to the runner. | GitHub UI |
| Branch direct push by non-Owner | `update` rule + Repository Admin-only bypass (this `main-strict` ruleset). | Ruleset id 15581036 |
| Third-party action supply chain | All non-GitHub-owned actions pinned to commit SHA: `reactivecircus/android-emulator-runner@e89f39f`, `gradle/actions/setup-gradle@50e97c2`, `softprops/action-gh-release@b430933`. Dependabot tracks updates. | `.github/workflows/*.yml` |
| GITHUB_TOKEN scope creep | Top-level `permissions: contents: read` in every workflow that can be triggered by external events (`ci.yml`, `e2e.yml`, `security.yml`). | Workflow YAML |
| OIDC token in host process space | `pages.yml` (which holds `id-token: write` for GitHub Pages OIDC federation) runs on `ubuntu-latest` rather than the self-hosted runner. | `.github/workflows/pages.yml` |
| Shell injection via step output interpolation | `${{ steps.sim.outputs.dest }}` (and similar step-output references in shell `run:` blocks) assigned to `env:` block first, then referenced as `$XCODE_DEST` in shell. Pattern applied in `ci.yml` and `security.yml`. | Workflow YAML |
| Secrets in committed source | Repository Settings → Secret scanning + Push protection both **ENABLED**. | GitHub UI |
| Action version skew | All `actions/checkout` references unified at `@v6`. | Workflow YAML |

### Deferred items (post-launch)

These have been audited and accepted as residual risk for the closed-beta window. They will be revisited if the runner is ever observed to be compromised, or as part of a future hardening sprint.

| ID | Risk | Why deferred |
|---|---|---|
| H1 | Persistent workspace allows cross-run payload persistence | Closing it requires runner isolation (Lume VM revival or equivalent) — significant work; mitigated for now by fork-PR-approval gating who can run jobs |
| H2 | Runner runs as admin-group user with full Keychain access | Same root cause as H1 — requires runner isolation |
| M2 | `~/.gradle/caches/` shared between CI and local development; poisoned cache could affect local builds | Splitting `GRADLE_USER_HOME` to a CI-dedicated path is straightforward but adds first-run cache miss; mitigated for now by SHA-pinning third-party actions |
| L2 | No network egress restriction on the runner process | Requires third-party macOS firewall (Little Snitch / LuLu); deferred until first observed need |

### User responsibilities (one-time setup)

- **SSH keys without passphrase** (`~/.ssh/id_ed25519`, `~/.ssh/b150005-GitHub`): silent exfiltration risk if runner is ever compromised. Set passphrases via `ssh-keygen -p -f <key>` when convenient.
- **Dependabot security updates**: enable at Repository Settings → Code security & analysis. Pairs naturally with the SHA-pinned actions to surface upstream vulnerabilities promptly.
- **Apple ID app-specific passwords used during VM provisioning**: revoke at `account.apple.com → Sign-In and Security → App-Specific Passwords` if no longer needed (the host runner does not require them).

### Known runner-specific behaviors

- **Android E2E install conflict**: the host's Android AVD persists across CI runs and across local development sessions. A prior dev install of the app signed with the local debug keystore conflicts with the CI-built APK signed with the default Android debug keystore (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Workaround: `adb uninstall io.github.b150005.knitnote.android || true` runs before `adb install -r` in `e2e.yml`.
- **iOS Simulator destination**: the `Resolve iOS Simulator destination` step parses `xcrun simctl list devices available -j` and picks the first available iPhone simulator. This selection depends on the host's installed simulator runtimes; if the host's Xcode is updated and simulator runtimes change, the destination resolution may pick a different device.
- **Single runner sequential execution**: with one registered runner, all workflows triggered by a single push queue sequentially. Total CI cycle time is ~80 minutes (vs ~25 minutes when previously parallel on GitHub-hosted runners). Multi-runner expansion is a documented option if cycle time becomes painful.

## Update history

| Date | Change | By |
|---|---|---|
| 2026-04-27 | Initial `main-strict` ruleset created (id `15581036`) | b150005 |
| 2026-04-27 | Self-hosted runner activated; security audit + hardening landed (Fork PR approval, third-party SHA pin, ci.yml permissions block, pages.yml reverted to ubuntu-latest, env-var step output pattern). See commits `1a119e1` + `8d6c6ae`. | b150005 |
