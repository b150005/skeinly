# CI/CD Pipeline

## What is CI/CD?

CI/CD (Continuous Integration / Continuous Delivery) is a software development practice that automates the process of building, testing, and deploying code changes. Every change pushed to the repository triggers an automated pipeline that validates the change before it reaches production.

### Continuous Integration (CI)

CI automatically runs checks every time code is pushed or a pull request is opened:

- **Lint**: Static analysis to catch code style violations and potential bugs
- **Test**: Automated test execution to verify correctness
- **Build**: Compilation or bundling to confirm the project builds successfully

If any step fails, the pipeline blocks the change from being merged.

### Continuous Delivery (CD)

CD extends CI by automatically deploying validated changes to staging or production environments. The deployment strategy depends on the project's infrastructure and release process.

## GitHub Actions

This template uses [GitHub Actions](https://docs.github.com/en/actions) as the CI/CD platform. GitHub Actions workflows are defined as YAML files in `.github/workflows/`.

### Reusable Workflows

GitHub Actions supports **reusable workflows** via the `workflow_call` trigger. A reusable workflow is defined once and called by other workflows, reducing duplication across repositories.

This template provides `ci-base.yml` as a reusable workflow. Derived repositories create their own workflow that calls it with project-specific inputs (language, commands, versions).

### Security Scanning

Two automated security mechanisms are included:

- **CodeQL**: GitHub's static analysis engine that scans code for security vulnerabilities. It runs on push, pull request, and on a weekly schedule.
- **Dependabot**: Automatically monitors dependencies for known vulnerabilities and creates pull requests to update them.

## Syncing from Upstream Template

GitHub Template Repositories do not maintain an upstream connection. To pull in template updates after creating your project:

### Initial Setup (once)

```bash
git remote add template https://github.com/{owner}/ecc-base-template.git
```

### Sync Updates

```bash
# Fetch latest template changes
git fetch template

# Cherry-pick specific files (recommended)
git checkout template/main -- .github/workflows/ci-base.yml

# Or merge everything (requires conflict resolution)
git merge template/main --allow-unrelated-histories
```

## References

Primary sources for the technologies described in this document:

- [GitHub Actions Documentation](https://docs.github.com/en/actions) — Workflows, jobs, steps, runners, and marketplace
- [Reusable Workflows](https://docs.github.com/en/actions/sharing-automations/reusing-workflows) — `workflow_call` trigger and cross-repo usage
- [GitHub CodeQL](https://docs.github.com/en/code-security/code-scanning/introduction-to-code-scanning/about-code-scanning-with-codeql) — Static analysis for security vulnerabilities
- [Dependabot Documentation](https://docs.github.com/en/code-security/dependabot) — Dependency updates and security alerts
- [GitHub Template Repositories](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-template-repository) — How template repos work and their limitations
