# Template Usage Guide

## What is a GitHub Template Repository?

A GitHub Template Repository allows you to create new repositories with the same directory structure, files, and configuration as the template. Unlike forking, the new repository has a clean commit history and no upstream connection.

## Creating a New Project

### Step 1: Use This Template

1. Go to the template repository on GitHub
2. Click the green **"Use this template"** button
3. Select **"Create a new repository"**
4. Choose the owner, repository name, and visibility
5. Click **"Create repository"**

### Step 2: Clone and Customize

```bash
git clone https://github.com/{owner}/{repo-name}.git
cd {repo-name}
```

## Customization Checklist

### Required

- [ ] **`.claude/CLAUDE.md`** — Replace the "About This Project" section with your project context
- [ ] **`.gitignore`** — Add language-specific patterns from [github/gitignore](https://github.com/github/gitignore)
- [ ] **`README.md`** — Replace with your project's description
- [ ] **`LICENSE`** — Update the copyright holder (or change the license)
- [ ] **`.env.example`** — Add your project's environment variables

### Recommended

- [ ] **`.devcontainer/devcontainer.json`** — Configure for your framework
- [ ] **`.github/CODEOWNERS`** — Set your GitHub username or team
- [ ] **`.github/dependabot.yml`** — Add your language's package ecosystem
- [ ] **`.github/workflows/security.yml`** — Update the CodeQL language matrix

### Optional

- [ ] **Create CI workflow** — Add `.github/workflows/ci.yml` that calls `ci-base.yml`
- [ ] **Add ECC rules** — Copy language-specific rules to `.claude/rules/`
- [ ] **Add ADRs** — Document architectural decisions in `docs/en/adr/` and `docs/ja/adr/`

## Keeping Up with Template Updates

GitHub Template Repositories do not maintain an upstream connection. To sync updates later:

```bash
# Add the template as a remote (once)
git remote add template https://github.com/{owner}/ecc-base-template.git

# Fetch and cherry-pick specific files
git fetch template
git checkout template/main -- .github/workflows/ci-base.yml
```

## References

Primary sources for the concepts described in this document:

- [Creating a Repository from a Template](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template) — How "Use this template" works
- [github/gitignore](https://github.com/github/gitignore) — Official .gitignore templates per language
- [Conventional Commits](https://www.conventionalcommits.org/) — Commit message format (feat, fix, etc.)
- [Keep a Changelog](https://keepachangelog.com/) — Changelog format standard
