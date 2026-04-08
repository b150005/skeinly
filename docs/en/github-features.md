# GitHub Features

This document explains the GitHub-specific features configured in this template.

## CODEOWNERS

**File**: `.github/CODEOWNERS`

CODEOWNERS defines who is automatically requested as a reviewer when a pull request modifies certain files. GitHub reads this file and adds the specified users or teams as reviewers.

### How It Works

Each line maps a file pattern to one or more owners:

```
# Everyone owns everything by default
* @username

# The platform team owns CI/CD configuration
.github/ @platform-team

# The security team must review auth changes
src/auth/ @security-team
```

When a PR changes files matching a pattern, the corresponding owners are automatically added as reviewers. If **branch protection rules** require CODEOWNERS approval, the PR cannot be merged until an owner approves.

### Pattern Syntax

| Pattern | Matches |
|---------|---------|
| `*` | All files |
| `*.js` | All JavaScript files |
| `/docs/` | The `docs/` directory at the repo root |
| `docs/` | Any `docs/` directory (any depth) |
| `src/auth/**` | Everything under `src/auth/` recursively |

### Key Behaviors

- Later rules override earlier ones (last match wins)
- Owners can be GitHub usernames (`@user`) or teams (`@org/team`)
- An empty owner (just a pattern with no `@`) disables ownership for those files
- The file must be in `.github/`, the repo root, or the `docs/` directory

## Dependabot

**File**: `.github/dependabot.yml`

Dependabot automatically monitors your project's dependencies for known security vulnerabilities and outdated versions. It creates pull requests to update them.

### How It Works

1. Dependabot reads `dependabot.yml` to know which package ecosystems to monitor
2. On the configured schedule, it checks for updates
3. If updates are available, it creates individual PRs for each dependency
4. You review and merge the PRs like any other code change

### Package Ecosystems

Each ecosystem corresponds to a language's dependency management tool:

| Ecosystem | Manifest File |
|-----------|--------------|
| `github-actions` | `.github/workflows/*.yml` |
| `npm` | `package.json` |
| `pip` | `requirements.txt`, `pyproject.toml` |
| `gomod` | `go.mod` |
| `cargo` | `Cargo.toml` |
| `pub` | `pubspec.yaml` |
| `maven` | `pom.xml` |
| `gradle` | `build.gradle`, `build.gradle.kts` |
| `composer` | `composer.json` |
| `bundler` | `Gemfile` |
| `nuget` | `*.csproj`, `packages.config` |
| `swift` | `Package.swift` |

### Configuration Options

```yaml
version: 2
updates:
  - package-ecosystem: "npm"
    directory: "/"            # Where the manifest file is
    schedule:
      interval: "weekly"      # daily, weekly, or monthly
    open-pull-requests-limit: 10  # Max concurrent PRs
    reviewers:
      - "username"            # Auto-assign reviewers
    labels:
      - "dependencies"        # Labels for the PRs
    ignore:
      - dependency-name: "lodash"  # Skip specific packages
```

### Security Updates vs Version Updates

- **Security updates**: Triggered by GitHub Advisory Database. Always created regardless of schedule.
- **Version updates**: Scheduled checks for newer versions. Configured by `dependabot.yml`.

## Pull Request Templates

**File**: `.github/PULL_REQUEST_TEMPLATE.md`

A PR template pre-fills the description field when a new pull request is created. This ensures every PR has a consistent structure with the information reviewers need.

### How It Works

When a contributor opens a new PR, GitHub automatically populates the description with the template content. The contributor then fills in the sections.

### This Template's Structure

The template in this repository includes:

- **Summary**: What the PR does
- **Changes**: Bullet list of modifications
- **Impact**: Which components or systems are affected
- **Test Plan**: Checklist of verification steps
- **Checklist**: Code quality gates (conventions, secrets, tests, docs)

### Multiple Templates

For larger projects, you can have multiple templates:

```
.github/
├── PULL_REQUEST_TEMPLATE.md          # Default template
└── PULL_REQUEST_TEMPLATE/
    ├── feature.md                    # Feature PRs
    ├── bugfix.md                     # Bug fix PRs
    └── release.md                    # Release PRs
```

Contributors select a template via URL query parameter: `?template=feature.md`

## Issue Templates

**Directory**: `.github/ISSUE_TEMPLATE/`

Issue templates provide structured forms for creating issues. They ensure reporters include all the information needed to act on the issue.

### YAML Forms vs Markdown Templates

GitHub supports two formats:

| Format | Extension | Features |
|--------|-----------|----------|
| **YAML forms** (`.yml`) | Structured fields, dropdowns, checkboxes, required validation | Used in this template |
| **Markdown** (`.md`) | Free-form text with suggested sections | Simpler but less structured |

YAML forms are recommended because they enforce structure and can mark fields as required.

### Form Field Types

```yaml
body:
  - type: input        # Single-line text
  - type: textarea     # Multi-line text
  - type: dropdown     # Select from options
  - type: checkboxes   # Multiple choice
  - type: markdown     # Static instructional text (not a field)
```

### This Template's Issue Forms

**Bug Report** (`bug_report.yml`):
- Description, steps to reproduce, expected/actual behavior, environment, screenshots

**Feature Request** (`feature_request.yml`):
- Problem statement, proposed solution, alternatives considered, additional context

### Adding More Templates

Create a new `.yml` file in `.github/ISSUE_TEMPLATE/`. Add a `config.yml` to customize the issue creation page:

```yaml
# .github/ISSUE_TEMPLATE/config.yml
blank_issues_enabled: false    # Disable blank issues
contact_links:
  - name: Discussions
    url: https://github.com/{owner}/{repo}/discussions
    about: Ask questions in Discussions instead of opening issues
```

## GitHub Actions

**Directory**: `.github/workflows/`

GitHub Actions is a CI/CD platform that runs automated workflows in response to repository events (push, pull request, schedule, etc.).

### Key Concepts

**Workflow**: A YAML file in `.github/workflows/` that defines an automated process. Triggered by events.

**Job**: A set of steps that run on the same runner (virtual machine). Jobs within a workflow run in parallel by default.

**Step**: A single task within a job. Can run a shell command (`run:`) or use a pre-built action (`uses:`).

**Action**: A reusable unit of code. Published on GitHub Marketplace or defined locally. Referenced as `owner/repo@version`.

**Runner**: The virtual machine that executes jobs. GitHub provides hosted runners (Ubuntu, Windows, macOS) or you can host your own.

### Reusable Workflows

A reusable workflow uses the `workflow_call` trigger. Other workflows call it with `uses:`:

```yaml
# Caller workflow
jobs:
  ci:
    uses: owner/repo/.github/workflows/reusable.yml@main
    with:
      input-name: "value"
```

This template provides `ci-base.yml` as a reusable workflow. Derived repos create their own workflow that calls it.

### Secrets

Sensitive values (API keys, tokens) are stored in repository Settings > Secrets. Access them in workflows via `${{ secrets.SECRET_NAME }}`. Secrets are masked in logs and never exposed in workflow outputs.

## Branch Protection Rules

Not a file in the repository, but a critical GitHub feature to configure via Settings > Branches.

### Recommended Rules for `main`

| Rule | Purpose |
|------|---------|
| **Require pull request reviews** | At least 1 approval before merge |
| **Require status checks to pass** | CI must pass before merge |
| **Require CODEOWNERS review** | Owners must approve changes to their files |
| **Require linear history** | Enforces rebase or squash merging |
| **Do not allow bypassing** | Even admins must follow the rules |

These are configured in the GitHub UI, not in files. Document your branch protection setup in an ADR.

## References

Primary sources for every feature described in this document:

- [About CODEOWNERS](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners) — Syntax, placement, and behavior
- [Dependabot Configuration](https://docs.github.com/en/code-security/dependabot/dependabot-version-updates/configuration-options-for-the-dependabot.yml-file) — All `dependabot.yml` options
- [About Dependabot Security Updates](https://docs.github.com/en/code-security/dependabot/dependabot-security-updates/about-dependabot-security-updates) — Security vs version updates
- [Creating a PR Template](https://docs.github.com/en/communities/using-templates-to-encourage-useful-contributions/creating-a-pull-request-template-for-your-repository) — PR template placement and usage
- [Configuring Issue Templates](https://docs.github.com/en/communities/using-templates-to-encourage-useful-contributions/configuring-issue-templates-for-your-repository) — YAML forms and markdown templates
- [Issue Form Syntax](https://docs.github.com/en/communities/using-templates-to-encourage-useful-contributions/syntax-for-issue-forms) — All field types and validation
- [GitHub Actions Documentation](https://docs.github.com/en/actions) — Workflows, events, jobs, and runners
- [Reusable Workflows](https://docs.github.com/en/actions/sharing-automations/reusing-workflows) — `workflow_call` trigger
- [Encrypted Secrets](https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions) — Managing secrets in Actions
- [Branch Protection Rules](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-a-branch-protection-rule) — All protection options
